#!/usr/bin/env python3
"""Thermally guarded NitroBoost Android stress test (ADB + Python standard library).

Requires Python 3.10+, Android Platform Tools (`adb`), and an Android-compatible
`stress-ng` executable visible to the ADB shell. It never changes governors,
swap, or kernel tuning while testing. It snapshots CPU governors and restores
any value that changed during the run; terminating stress-ng releases its
CPU/RAM workers.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import re
import shlex
import subprocess
import sys
import threading
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Optional

THERMAL_POLL_SECONDS = 5
THERMAL_LIMIT_C = 45.0
DEFAULT_BOOSTER_PACKAGE = "com.nitroboost.app"
PACKAGE_RE = re.compile(r"^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$")
GOVERNOR_PATH_RE = re.compile(
    r"^/sys/devices/system/cpu/cpu[0-9]+/cpufreq/scaling_governor$"
)
GOVERNOR_VALUE_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")

THERMAL_SYSFS_COMMAND = r'''for z in /sys/class/thermal/thermal_zone*; do
  [ -d "$z" ] || continue
  [ -r "$z/temp" ] || continue
  v=$(cat "$z/temp" 2>/dev/null) || continue
  t=$(cat "$z/type" 2>/dev/null)
  [ -n "$t" ] || t=unknown
  printf '%s|%s|%s\n' "${z##*/}" "$t" "$v"
done'''

GOVERNOR_READ_COMMAND = r'''for f in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_governor; do
  [ -r "$f" ] || continue
  v=$(cat "$f" 2>/dev/null) || continue
  printf '%s|%s\n' "$f" "$v"
done'''

CRASH_PATTERNS = (
    ("ANR", re.compile(r"\bANR in\b|Application Not Responding", re.IGNORECASE)),
    ("CRASH", re.compile(r"FATAL EXCEPTION|Fatal signal \d+|has died", re.IGNORECASE)),
    ("NULL_POINTER", re.compile(r"NullPointerException", re.IGNORECASE)),
    ("OUT_OF_MEMORY", re.compile(r"OutOfMemoryError|lowmemorykiller", re.IGNORECASE)),
)

CSV_FIELDS = (
    "timestamp_utc",
    "elapsed_seconds",
    "max_temp_c",
    "thermal_source",
    "thermal_sensors_json",
    "available_ram_mb",
    "booster_cpu_pct",
    "booster_pss_mb",
    "gfx_total_frames",
    "gfx_fps_estimate",
    "gfx_janky_frames",
    "gfx_janky_pct",
    "gfx_p90_ms",
    "gfx_p95_ms",
    "gfx_p99_ms",
)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def normalize_temperature(raw: str | float) -> Optional[float]:
    """Normalize common Android thermal values (millidegrees or Celsius)."""
    try:
        value = float(raw)
    except (TypeError, ValueError):
        return None
    if not math.isfinite(value):
        return None
    if abs(value) >= 1000:
        value /= 1000.0
    # Ignore common disconnected-sensor sentinels, but keep valid cold readings.
    if value < -40.0 or value > 150.0:
        return None
    return round(value, 2)


@dataclass
class ThermalReading:
    source: str
    sensors: list[dict]

    @property
    def max_celsius(self) -> Optional[float]:
        values = [item["temperature_c"] for item in self.sensors]
        return max(values) if values else None


def parse_sysfs_thermal(text: str) -> ThermalReading:
    sensors: list[dict] = []
    for line in text.splitlines():
        parts = line.strip().split("|", 2)
        if len(parts) != 3:
            continue
        sensor, sensor_type, raw = parts
        temperature = normalize_temperature(raw)
        if temperature is None:
            continue
        sensors.append(
            {
                "sensor": sensor,
                "type": sensor_type,
                "raw": raw,
                "temperature_c": temperature,
            }
        )
    return ThermalReading("sysfs", sensors)


def parse_hardware_properties(text: str) -> ThermalReading:
    """Fallback for devices that hide thermal_zone files from the shell user."""
    sensors: list[dict] = []
    pattern = re.compile(
        r"\b(CPU|GPU|Battery|Skin) temperatures?\s*:\s*\[([^\]]*)\]",
        re.IGNORECASE,
    )
    for match in pattern.finditer(text):
        kind, values = match.groups()
        for index, token in enumerate(values.split(",")):
            temperature = normalize_temperature(token.strip())
            if temperature is not None:
                sensors.append(
                    {
                        "sensor": f"{kind.lower()}_{index}",
                        "type": kind.lower(),
                        "raw": token.strip(),
                        "temperature_c": temperature,
                    }
                )
    return ThermalReading("dumpsys hardware_properties", sensors)


def parse_meminfo(text: str) -> tuple[Optional[float], Optional[float]]:
    """Return (MemAvailable, MemTotal) in MiB from /proc/meminfo."""
    values: dict[str, float] = {}
    for line in text.splitlines():
        match = re.match(r"\s*(MemAvailable|MemFree|MemTotal):\s*(\d+)\s*kB", line)
        if match:
            values[match.group(1)] = int(match.group(2)) / 1024.0
    available = values.get("MemAvailable", values.get("MemFree"))
    return (
        round(available, 1) if available is not None else None,
        round(values["MemTotal"], 1) if "MemTotal" in values else None,
    )


def parse_cpu_percent(dumpsys_cpuinfo: str, package: str) -> Optional[float]:
    """Extract this package's CPU percentage from Android dumpsys cpuinfo."""
    package_re = re.compile(r"\b" + re.escape(package) + r"(?:\b|:)" )
    for line in dumpsys_cpuinfo.splitlines():
        if package_re.search(line):
            match = re.match(r"\s*(\d+(?:\.\d+)?)\s*%", line)
            if match:
                return float(match.group(1))
    return None


def parse_pss_kb(dumpsys_meminfo: str) -> Optional[float]:
    match = re.search(r"^\s*TOTAL\s+PSS:\s*([\d,]+)", dumpsys_meminfo, re.MULTILINE)
    if not match:
        return None
    return int(match.group(1).replace(",", "")) / 1024.0


def parse_gfxinfo(text: str) -> dict:
    """Read gfxinfo's cumulative frame and jank summary when available."""
    result: dict = {}
    patterns = {
        "total_frames": r"Total frames rendered:\s*(\d+)",
        "janky_frames": r"Janky frames:\s*(\d+)",
        "janky_pct": r"Janky frames:\s*\d+\s*\(([\d.]+)%\)",
        "p90_ms": r"90th percentile:\s*([\d.]+)\s*ms",
        "p95_ms": r"95th percentile:\s*([\d.]+)\s*ms",
        "p99_ms": r"99th percentile:\s*([\d.]+)\s*ms",
    }
    for key, pattern in patterns.items():
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            result[key] = float(match.group(1)) if key.endswith("_pct") or key.startswith("p") else int(match.group(1))
    return result


def validate_package(package: str) -> str:
    if not PACKAGE_RE.fullmatch(package):
        raise argparse.ArgumentTypeError(
            "package must look like com.example.app (letters, digits, underscores and dots only)"
        )
    return package


def adb_devices(adb: str) -> list[str]:
    result = subprocess.run(
        [adb, "devices", "-l"], capture_output=True, text=True, timeout=15
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "Could not run adb devices")
    devices: list[str] = []
    for line in result.stdout.splitlines()[1:]:
        fields = line.split()
        if len(fields) >= 2 and fields[1] == "device":
            devices.append(fields[0])
    return devices


class AdbDevice:
    def __init__(self, adb: str, serial: str):
        self.adb = adb
        self.serial = serial

    def shell_result(self, command: str, timeout: int = 15) -> subprocess.CompletedProcess:
        return subprocess.run(
            [self.adb, "-s", self.serial, "shell", command],
            capture_output=True,
            text=True,
            timeout=timeout,
        )

    def shell(self, command: str, timeout: int = 15, check: bool = True) -> str:
        result = self.shell_result(command, timeout=timeout)
        if check and result.returncode != 0:
            details = (result.stderr or result.stdout).strip()
            raise RuntimeError(f"adb shell command failed ({result.returncode}): {details}")
        return result.stdout.strip()

    def read_thermal(self) -> ThermalReading:
        sysfs = self.shell(THERMAL_SYSFS_COMMAND, timeout=10, check=False)
        reading = parse_sysfs_thermal(sysfs)
        if reading.sensors:
            return reading
        fallback = self.shell("dumpsys hardware_properties", timeout=10, check=False)
        return parse_hardware_properties(fallback)

    def read_governors(self) -> dict[str, str]:
        output = self.shell(GOVERNOR_READ_COMMAND, timeout=10, check=False)
        governors: dict[str, str] = {}
        for line in output.splitlines():
            path, separator, value = line.partition("|")
            value = value.strip()
            if separator and GOVERNOR_PATH_RE.fullmatch(path.strip()) and GOVERNOR_VALUE_RE.fullmatch(value):
                governors[path.strip()] = value
        return governors

    def process_alive(self, pid: int) -> bool:
        result = self.shell_result(f"kill -0 {pid} 2>/dev/null", timeout=8)
        return result.returncode == 0


class LogcatMonitor:
    """Reads live logcat without blocking the stress sampler."""

    def __init__(self, adb: str, serial: str, on_event: Callable[[str, str], None]):
        self.adb = adb
        self.serial = serial
        self.on_event = on_event
        self.process: Optional[subprocess.Popen] = None
        self.thread: Optional[threading.Thread] = None
        self.fatal_event = threading.Event()
        self._seen: set[tuple[str, str]] = set()
        self._lock = threading.Lock()

    def start(self) -> None:
        self.process = subprocess.Popen(
            [self.adb, "-s", self.serial, "logcat", "-T", "1", "-v", "time"],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
        self.thread = threading.Thread(target=self._read, name="adb-logcat", daemon=True)
        self.thread.start()
        time.sleep(0.25)
        if self.process.poll() is not None:
            raise RuntimeError("adb logcat exited before monitoring started; check platform-tools and device authorization")

    def _read(self) -> None:
        assert self.process is not None and self.process.stdout is not None
        for raw_line in self.process.stdout:
            line = raw_line.rstrip()
            for kind, pattern in CRASH_PATTERNS:
                if not pattern.search(line):
                    continue
                key = (kind, line)
                with self._lock:
                    if key in self._seen:
                        break
                    self._seen.add(key)
                self.on_event(kind, line)
                self.fatal_event.set()
                break

    def stop(self) -> None:
        if self.process is not None and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=2)
        if self.thread is not None:
            self.thread.join(timeout=2)


class MemoryGrowthTracker:
    """Flags sustained PSS growth; this is a leak heuristic, not proof of a leak."""

    def __init__(self, threshold_mb: float, confirm_samples: int):
        self.threshold_mb = threshold_mb
        self.confirm_samples = confirm_samples
        self.baseline: Optional[float] = None
        self.consecutive = 0
        self.reported = False

    def observe(self, pss_mb: Optional[float]) -> Optional[float]:
        if pss_mb is None:
            return None
        if self.baseline is None:
            self.baseline = pss_mb
            return 0.0
        growth = pss_mb - self.baseline
        if growth >= self.threshold_mb:
            self.consecutive += 1
        else:
            self.consecutive = 0
        if self.consecutive >= self.confirm_samples and not self.reported:
            self.reported = True
            return growth
        return None


class StressTest:
    def __init__(self, args: argparse.Namespace, serial: str):
        self.args = args
        self.serial = serial
        self.device = AdbDevice(args.adb, serial)
        self.run_id = uuid.uuid4().hex[:10]
        stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        self.output_dir = Path(args.output or f"nitroboost_stress_{stamp}_{self.run_id}")
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.csv_file = (self.output_dir / "samples.csv").open("w", newline="", encoding="utf-8")
        self.csv_writer = csv.DictWriter(self.csv_file, fieldnames=CSV_FIELDS)
        self.csv_writer.writeheader()
        self.events_file = (self.output_dir / "events.jsonl").open("w", encoding="utf-8")
        self.event_lock = threading.Lock()
        self.events: list[dict] = []
        self.logcat: Optional[LogcatMonitor] = None
        self.stress_pid: Optional[int] = None
        self.watchdog_pid: Optional[int] = None
        self.marker_path = f"/data/local/tmp/nitroboost-{self.run_id}.thermal"
        self.remote_log_path = f"/data/local/tmp/nitroboost-{self.run_id}.log"
        self.governors_before: dict[str, str] = {}
        self.governors_after: dict[str, str] = {}
        self.sysfs_watchdog_available = False
        self.cleanup_done = False
        self.stop_reason = "not_started"
        self.start_monotonic: Optional[float] = None
        self.memory_target_mb: Optional[int] = None
        self.cpu_workers: Optional[int] = None
        self.baseline_available_mb: Optional[float] = None
        self.memory_growth = MemoryGrowthTracker(args.leak_threshold_mb, args.leak_confirm_samples)
        self.last_gfx_total: Optional[int] = None
        self.last_gfx_elapsed: Optional[float] = None
        self.sample_count = 0

    def record_event(self, kind: str, message: str, **extra) -> None:
        event = {"timestamp_utc": utc_now(), "kind": kind, "message": message, **extra}
        with self.event_lock:
            if len(self.events) < 1000:
                self.events.append(event)
            self.events_file.write(json.dumps(event, ensure_ascii=False) + "\n")
            self.events_file.flush()
        print(f"[{kind}] {message}", flush=True)

    def _device_memory(self) -> tuple[Optional[float], Optional[float]]:
        output = self.device.shell("cat /proc/meminfo", timeout=10, check=False)
        return parse_meminfo(output)

    def _device_cpu_count(self) -> int:
        output = self.device.shell("getconf _NPROCESSORS_ONLN 2>/dev/null", timeout=8, check=False)
        match = re.search(r"\b(\d+)\b", output)
        if match:
            return max(1, int(match.group(1)))
        return 4

    def _find_stress_ng(self) -> str:
        configured = self.args.stress_ng
        if configured:
            candidate = configured.strip()
            check = self.device.shell_result(f"test -x {shlex.quote(candidate)}", timeout=8)
            if check.returncode != 0:
                raise RuntimeError(f"stress-ng is not executable on the device at: {candidate}")
            return candidate
        found = self.device.shell("command -v stress-ng 2>/dev/null", timeout=8, check=False).strip()
        if not found:
            raise RuntimeError(
                "No Android-compatible stress-ng executable is available to adb shell. "
                "Install/copy one to the device and pass --stress-ng /data/local/tmp/stress-ng."
            )
        return found.splitlines()[-1].strip()

    def prepare(self) -> tuple[ThermalReading, float]:
        state = subprocess.run(
            [self.args.adb, "-s", self.serial, "get-state"],
            capture_output=True,
            text=True,
            timeout=10,
        )
        if state.returncode != 0 or state.stdout.strip() != "device":
            raise RuntimeError("Selected ADB device is not in the 'device' state")

        self.governors_before = self.device.read_governors()
        (self.output_dir / "cpu_governors_before.json").write_text(
            json.dumps(self.governors_before, indent=2), encoding="utf-8"
        )

        thermal = self.device.read_thermal()
        if thermal.max_celsius is None:
            raise RuntimeError(
                "No readable thermal-zone or hardware_properties temperatures; refusing to start an unguarded stress test."
            )
        if thermal.max_celsius >= self.args.thermal_limit_c:
            raise RuntimeError(
                f"Device is already at {thermal.max_celsius:.1f} C; cool it below "
                f"{self.args.thermal_limit_c:.1f} C before testing."
            )

        available, total = self._device_memory()
        if available is None:
            raise RuntimeError("Could not read /proc/meminfo; refusing to estimate a RAM allocation without a safety cap.")
        self.baseline_available_mb = available
        if available < self.args.min_available_ram_mb:
            raise RuntimeError(
                f"Only {available:.0f} MiB RAM is available; configured minimum is {self.args.min_available_ram_mb} MiB."
            )

        cores = self._device_cpu_count()
        self.cpu_workers = min(self.args.cpu_workers or min(4, cores), cores)
        memory_cap = int(available * self.args.max_memory_percent / 100.0)
        self.memory_target_mb = min(self.args.memory_mb, memory_cap)
        if self.memory_target_mb < 32:
            raise RuntimeError(
                f"Safe RAM cap is only {self.memory_target_mb} MiB; at least 32 MiB is required for the VM worker."
            )

        stress_ng = self._find_stress_ng()
        version = self.device.shell(
            f"{shlex.quote(stress_ng)} --version 2>&1", timeout=8, check=False
        )
        if version:
            print(f"stress-ng: {version.splitlines()[0]}")
        self.stress_ng_path = stress_ng
        self.sysfs_watchdog_available = thermal.source == "sysfs"

        print(
            f"Device={self.serial}  CPU workers={self.cpu_workers}/{cores}  "
            f"RAM worker={self.memory_target_mb} MiB (cap {self.args.max_memory_percent}% of available)  "
            f"duration={self.args.duration_minutes} min  thermal stop >= {self.args.thermal_limit_c:.1f} C"
        )
        if not self.sysfs_watchdog_available:
            print(
                "Warning: sysfs thermal zones are hidden. Host-side ADB thermal monitoring remains active, "
                "but the on-device thermal watchdog is unavailable."
            )
        if not self.governors_before:
            print("Warning: CPU governor values are not readable; no governor changes are made by this script.")
        return thermal, available

    def _start_logcat(self) -> None:
        self.logcat = LogcatMonitor(self.args.adb, self.serial, self.record_event)
        self.logcat.start()

    def _start_stress(self) -> None:
        assert self.memory_target_mb is not None and self.cpu_workers is not None
        duration_seconds = self.args.duration_minutes * 60
        command = [
            self.stress_ng_path,
            "--cpu",
            str(self.cpu_workers),
            "--cpu-method",
            "matrixprod",
            "--vm",
            "1",
            "--vm-bytes",
            f"{self.memory_target_mb}M",
            "--vm-keep",
            "--timeout",
            f"{duration_seconds}s",
            "--metrics-brief",
        ]
        remote = (
            f"nohup {shlex.join(command)} > {shlex.quote(self.remote_log_path)} 2>&1 "
            f"< /dev/null & echo $!"
        )
        output = self.device.shell(remote, timeout=10)
        pids = re.findall(r"\b\d+\b", output)
        if not pids:
            raise RuntimeError(f"Could not obtain stress-ng PID from adb output: {output!r}")
        self.stress_pid = int(pids[-1])
        time.sleep(0.5)
        if not self.device.process_alive(self.stress_pid):
            details = self.device.shell(f"tail -30 {shlex.quote(self.remote_log_path)} 2>/dev/null", check=False)
            raise RuntimeError(
                "stress-ng exited immediately; check that the device binary matches its ABI. "
                + details[-1500:]
            )

        if self.sysfs_watchdog_available:
            self._start_remote_thermal_watchdog()

    def _start_remote_thermal_watchdog(self) -> None:
        assert self.stress_pid is not None
        limit_milli = int(round(self.args.thermal_limit_c * 1000))
        limit_celsius = int(math.floor(self.args.thermal_limit_c))
        script = f'''stress_pid={self.stress_pid}
marker={shlex.quote(self.marker_path)}
while kill -0 "$stress_pid" 2>/dev/null; do
  hot=0
  for f in /sys/class/thermal/thermal_zone*/temp; do
    [ -r "$f" ] || continue
    raw=$(cat "$f" 2>/dev/null) || continue
    case "$raw" in ''|*[!0-9]*) continue;; esac
    if [ "$raw" -ge 1000 ] 2>/dev/null; then
      [ "$raw" -ge {limit_milli} ] && hot=1
    else
      [ "$raw" -ge {limit_celsius} ] && hot=1
    fi
  done
  if [ "$hot" -eq 1 ]; then
    printf 'thermal_limit\\n' > "$marker"
    kill -TERM "$stress_pid" 2>/dev/null
    sleep 2
    kill -9 "$stress_pid" 2>/dev/null
    exit 0
  fi
  sleep {THERMAL_POLL_SECONDS}
done'''
        remote = f"nohup sh -c {shlex.quote(script)} </dev/null >/dev/null 2>&1 & echo $!"
        output = self.device.shell(remote, timeout=10, check=False)
        pids = re.findall(r"\b\d+\b", output)
        if pids:
            self.watchdog_pid = int(pids[-1])
        else:
            self.record_event("WARNING", "Could not start the on-device thermal watchdog; host-side guard remains active.")

    def _thermal_marker_present(self) -> bool:
        result = self.device.shell_result(f"test -f {shlex.quote(self.marker_path)}", timeout=8)
        return result.returncode == 0

    def _stop_remote_pid(self, pid: Optional[int], label: str) -> bool:
        if pid is None:
            return True
        script = (
            f"pid={pid}; kill -TERM $pid 2>/dev/null || exit 0; "
            "i=0; while [ $i -lt 8 ]; do "
            "kill -0 $pid 2>/dev/null || exit 0; "
            "sleep 0.25; i=$((i+1)); done; "
            "kill -9 $pid 2>/dev/null || true"
        )
        try:
            self.device.shell(script, timeout=8, check=False)
            alive = self.device.process_alive(pid)
            if alive:
                self.record_event("WARNING", f"Could not confirm {label} PID {pid} stopped.")
            return not alive
        except Exception as exc:
            self.record_event("WARNING", f"Could not stop {label} PID {pid}: {exc}")
            return False

    def _restore_governors(self) -> None:
        if not self.governors_before:
            return
        try:
            self.governors_after = self.device.read_governors()
            changed = {
                path: value
                for path, value in self.governors_before.items()
                if self.governors_after.get(path) != value
            }
            if not changed:
                print("CPU governors unchanged; no governor writes were needed.")
                return
            commands = []
            for path, value in changed.items():
                if GOVERNOR_PATH_RE.fullmatch(path) and GOVERNOR_VALUE_RE.fullmatch(value):
                    commands.append(f"printf '%s\\n' {shlex.quote(value)} > {shlex.quote(path)}")
            if not commands:
                return
            script = "; ".join(commands)
            result = self.device.shell_result(f"sh -c {shlex.quote(script)}", timeout=10)
            if result.returncode != 0:
                # Root is only attempted if restoration is needed; the test itself is unprivileged.
                result = self.device.shell_result(f"su -c {shlex.quote(script)}", timeout=10)
            self.governors_after = self.device.read_governors()
            remaining = [
                path for path, value in changed.items()
                if self.governors_after.get(path) != value
            ]
            if result.returncode != 0 or remaining:
                self.record_event(
                    "WARNING",
                    "Could not restore changed CPU governor(s); device/root policy rejected the write: "
                    + ", ".join(remaining or changed.keys()),
                )
            else:
                self.record_event("CLEANUP", "Restored CPU governor(s) to their pre-test values.")
        except Exception as exc:
            self.record_event("WARNING", f"Governor restoration check failed: {exc}")

    def _collect_sample(self, thermal: ThermalReading, elapsed: float) -> dict:
        available_mb, _ = self._device_memory()
        cpu_output = self.device.shell("dumpsys cpuinfo", timeout=12, check=False)
        mem_output = self.device.shell(
            f"dumpsys meminfo {shlex.quote(self.args.booster_package)}", timeout=15, check=False
        )
        gfx_output = self.device.shell(
            f"dumpsys gfxinfo {shlex.quote(self.args.gfx_package)}", timeout=15, check=False
        )
        cpu_pct = parse_cpu_percent(cpu_output, self.args.booster_package)
        pss_mb = parse_pss_kb(mem_output)
        gfx = parse_gfxinfo(gfx_output)
        fps: Optional[float] = None
        total_frames = gfx.get("total_frames")
        if total_frames is not None:
            if self.last_gfx_total is not None and self.last_gfx_elapsed is not None:
                frame_delta = total_frames - self.last_gfx_total
                if frame_delta < 0:  # gfxinfo counters reset or rolled over.
                    frame_delta = total_frames
                elapsed_delta = max(0.001, elapsed - self.last_gfx_elapsed)
                fps = round(frame_delta / elapsed_delta, 2)
            self.last_gfx_total = total_frames
            self.last_gfx_elapsed = elapsed

        growth = self.memory_growth.observe(pss_mb)
        if growth is not None:
            self.record_event(
                "POSSIBLE_MEMORY_GROWTH",
                f"{self.args.booster_package} PSS is up {growth:.1f} MiB from baseline for "
                f"{self.args.leak_confirm_samples} consecutive samples; this is a heuristic, not proof of a leak.",
            )

        row = {
            "timestamp_utc": utc_now(),
            "elapsed_seconds": round(elapsed, 1),
            "max_temp_c": thermal.max_celsius,
            "thermal_source": thermal.source,
            "thermal_sensors_json": json.dumps(thermal.sensors, ensure_ascii=False),
            "available_ram_mb": available_mb,
            "booster_cpu_pct": cpu_pct,
            "booster_pss_mb": round(pss_mb, 1) if pss_mb is not None else None,
            "gfx_total_frames": total_frames,
            "gfx_fps_estimate": fps,
            "gfx_janky_frames": gfx.get("janky_frames"),
            "gfx_janky_pct": gfx.get("janky_pct"),
            "gfx_p90_ms": gfx.get("p90_ms"),
            "gfx_p95_ms": gfx.get("p95_ms"),
            "gfx_p99_ms": gfx.get("p99_ms"),
        }
        self.csv_writer.writerow(row)
        self.csv_file.flush()
        self.sample_count += 1
        print(
            f"t={elapsed:6.1f}s temp={thermal.max_celsius:5.1f}C "
            f"freeRAM={available_mb if available_mb is not None else 'n/a'}MiB "
            f"boosterCPU={cpu_pct if cpu_pct is not None else 'n/a'}% "
            f"PSS={pss_mb if pss_mb is not None else 'n/a'}MiB "
            f"gfxFPS={fps if fps is not None else 'n/a'}",
            flush=True,
        )
        return row

    def run(self) -> str:
        thermal, _ = self.prepare()
        self._start_logcat()
        # Reset only gfxinfo counters; it does not modify app preferences or device tuning.
        self.device.shell(
            f"dumpsys gfxinfo {shlex.quote(self.args.gfx_package)} reset", timeout=10, check=False
        )
        self._start_stress()
        self.start_monotonic = time.monotonic()
        self.stop_reason = "running"
        print("Stress load started. Press Ctrl+C to stop safely.", flush=True)

        deadline = self.start_monotonic + self.args.duration_minutes * 60
        next_sample = self.start_monotonic + THERMAL_POLL_SECONDS
        while True:
            if self.logcat and self.logcat.fatal_event.is_set():
                self.stop_reason = "crash_or_anr_detected"
                break
            now = time.monotonic()
            if now >= deadline:
                self.stop_reason = "duration_complete"
                break
            wait_seconds = max(0.0, min(next_sample, deadline) - now)
            if self.logcat and self.logcat.fatal_event.wait(wait_seconds):
                self.stop_reason = "crash_or_anr_detected"
                break
            if self.logcat and self.logcat.fatal_event.is_set():
                self.stop_reason = "crash_or_anr_detected"
                break
            if time.monotonic() >= deadline:
                self.stop_reason = "duration_complete"
                break

            if self._thermal_marker_present():
                self.stop_reason = "thermal_limit"
                self.record_event("THERMAL_LIMIT", f"On-device watchdog stopped load at/above {self.args.thermal_limit_c:.1f} C.")
                break
            if self.stress_pid is not None and not self.device.process_alive(self.stress_pid):
                self.stop_reason = "stress_process_exited"
                break

            thermal = self.device.read_thermal()
            if thermal.max_celsius is None:
                self.stop_reason = "thermal_sensor_unavailable"
                self.record_event("SAFETY_STOP", "Thermal readings disappeared; stopping load safely.")
                break
            elapsed = time.monotonic() - self.start_monotonic
            if thermal.max_celsius >= self.args.thermal_limit_c:
                self.stop_reason = "thermal_limit"
                self.record_event(
                    "THERMAL_LIMIT",
                    f"Maximum temperature reached {thermal.max_celsius:.1f} C; stopping at the "
                    f"{self.args.thermal_limit_c:.1f} C safety limit.",
                )
                break

            row = self._collect_sample(thermal, elapsed)
            if row["available_ram_mb"] is not None and row["available_ram_mb"] < self.args.min_available_ram_mb:
                self.stop_reason = "low_available_ram"
                self.record_event(
                    "SAFETY_STOP",
                    f"Available RAM fell below {self.args.min_available_ram_mb} MiB; stopping load.",
                )
                break
            next_sample = max(next_sample + THERMAL_POLL_SECONDS, time.monotonic())

        return self.stop_reason

    def cleanup(self) -> None:
        if self.cleanup_done:
            return
        self.cleanup_done = True
        if self._thermal_marker_present_safely():
            self.stop_reason = "thermal_limit"
        stress_stopped = self._stop_remote_pid(self.stress_pid, "stress-ng")
        self._stop_remote_pid(self.watchdog_pid, "thermal watchdog")
        self._restore_governors()
        try:
            self.device.shell(
                f"rm -f {shlex.quote(self.marker_path)} {shlex.quote(self.remote_log_path)}",
                timeout=8,
                check=False,
            )
        except Exception as exc:
            self.record_event("WARNING", f"Could not remove temporary device files: {exc}")
        if self.logcat is not None:
            self.logcat.stop()
        self.csv_file.close()

        try:
            governors_after_cleanup = self.device.read_governors() if self.governors_before else {}
        except Exception as exc:
            governors_after_cleanup = {}
            self.record_event("WARNING", f"Could not read final CPU governor state: {exc}")

        summary = {
            "device_serial": self.serial,
            "booster_package": self.args.booster_package,
            "gfx_package": self.args.gfx_package,
            "requested_duration_minutes": self.args.duration_minutes,
            "stop_reason": self.stop_reason,
            "samples": self.sample_count,
            "thermal_limit_c": self.args.thermal_limit_c,
            "thermal_poll_seconds": THERMAL_POLL_SECONDS,
            "cpu_workers": self.cpu_workers,
            "stress_memory_mb": self.memory_target_mb,
            "minimum_available_ram_mb": self.args.min_available_ram_mb,
            "cpu_governors_before": self.governors_before,
            "cpu_governors_after_cleanup": governors_after_cleanup,
            "stress_process_stopped": stress_stopped,
            "memory_cleanup": "stress-ng workers terminated; their allocations are released; swap/cache settings were not changed",
            "events": self.events,
        }
        (self.output_dir / "summary.json").write_text(
            json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8"
        )
        self.events_file.close()
        print(f"Results: {self.output_dir.resolve()}")

    def _thermal_marker_present_safely(self) -> bool:
        try:
            return self._thermal_marker_present()
        except Exception:
            return False


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Run a CPU/RAM stress test on an Android device using ADB. "
            "The test stops at 45 C, on missing thermal data, low free RAM, ANR, or crash."
        ),
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--adb", default="adb", help="adb executable or path")
    parser.add_argument("--serial", help="ADB serial (required when multiple devices are connected)")
    parser.add_argument("--duration-minutes", type=int, default=30, help="maximum run time")
    parser.add_argument(
        "--cpu-workers", type=int, default=None,
        help="CPU stress workers (default: up to 4, capped to online device cores)",
    )
    parser.add_argument("--memory-mb", type=int, default=256, help="requested anonymous RAM for stress-ng")
    parser.add_argument(
        "--max-memory-percent", type=int, default=15,
        help="hard cap as a percentage of MemAvailable measured before the test (1..50)",
    )
    parser.add_argument(
        "--min-available-ram-mb", type=int, default=128,
        help="stop before/when MemAvailable falls below this guardrail",
    )
    parser.add_argument(
        "--thermal-limit-c", type=float, default=THERMAL_LIMIT_C,
        help="maximum supported thermal cutoff; values above 45 C are rejected",
    )
    parser.add_argument(
        "--stress-ng", help="path to Android-compatible stress-ng on the device (e.g. /data/local/tmp/stress-ng)",
    )
    parser.add_argument(
        "--booster-package", type=validate_package, default=DEFAULT_BOOSTER_PACKAGE,
        help="package whose CPU and PSS are sampled",
    )
    parser.add_argument(
        "--gfx-package", type=validate_package, default=DEFAULT_BOOSTER_PACKAGE,
        help="package queried by dumpsys gfxinfo (pass the game package for game frames)",
    )
    parser.add_argument(
        "--leak-threshold-mb", type=float, default=64.0,
        help="flag possible memory growth after this much sustained PSS increase",
    )
    parser.add_argument(
        "--leak-confirm-samples", type=int, default=3,
        help="consecutive samples required before recording a possible memory leak",
    )
    parser.add_argument("--output", help="result directory (default: timestamped directory in the current folder)")
    return parser


def validate_args(parser: argparse.ArgumentParser, args: argparse.Namespace) -> None:
    if args.duration_minutes < 1 or args.duration_minutes > 24 * 60:
        parser.error("--duration-minutes must be between 1 and 1440")
    if args.cpu_workers is not None and args.cpu_workers < 1:
        parser.error("--cpu-workers must be at least 1")
    if args.memory_mb < 32:
        parser.error("--memory-mb must be at least 32")
    if not 1 <= args.max_memory_percent <= 50:
        parser.error("--max-memory-percent must be between 1 and 50")
    if args.min_available_ram_mb < 32:
        parser.error("--min-available-ram-mb must be at least 32")
    if args.thermal_limit_c <= 0 or args.thermal_limit_c > THERMAL_LIMIT_C:
        parser.error("--thermal-limit-c must be > 0 and may not exceed 45 C")
    if args.leak_threshold_mb <= 0 or args.leak_confirm_samples < 2:
        parser.error("memory leak heuristic needs a positive threshold and at least 2 samples")
    if args.stress_ng and "\x00" in args.stress_ng:
        parser.error("invalid --stress-ng path")


def main(argv: Optional[list[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    validate_args(parser, args)

    try:
        devices = adb_devices(args.adb)
        if args.serial:
            if args.serial not in devices:
                raise RuntimeError(f"ADB device {args.serial!r} is not connected/authorized. Connected: {devices or 'none'}")
            serial = args.serial
        elif len(devices) == 1:
            serial = devices[0]
        elif not devices:
            raise RuntimeError("No authorized Android device found. Connect it, enable USB debugging, and accept the RSA prompt.")
        else:
            raise RuntimeError(f"Multiple devices found {devices}; pass --serial DEVICE_SERIAL")
    except (OSError, subprocess.TimeoutExpired) as exc:
        print(f"ADB setup error: {exc}", file=sys.stderr)
        return 1
    except RuntimeError as exc:
        print(f"ADB setup error: {exc}", file=sys.stderr)
        return 1

    if args.gfx_package == DEFAULT_BOOSTER_PACKAGE and args.booster_package != DEFAULT_BOOSTER_PACKAGE:
        args.gfx_package = args.booster_package

    runner: Optional[StressTest] = None
    result = 1
    try:
        runner = StressTest(args, serial)
        reason = runner.run()
        if reason == "duration_complete":
            result = 0
        elif reason == "crash_or_anr_detected":
            result = 3
        else:
            result = 2
    except KeyboardInterrupt:
        print("\nCtrl+C received; stopping load and restoring captured state...", flush=True)
        if runner is not None:
            runner.stop_reason = "interrupted"
        result = 130
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as exc:
        print(f"Stress test error: {exc}", file=sys.stderr)
        if runner is not None:
            runner.stop_reason = "error"
        result = 1
    finally:
        if runner is not None:
            runner.cleanup()
    return result


if __name__ == "__main__":
    raise SystemExit(main())
