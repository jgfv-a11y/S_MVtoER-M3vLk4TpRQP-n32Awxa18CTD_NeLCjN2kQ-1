---
name: performance-engineering
description: Measurement-based performance engineering for NitroBoost: analyzes FPS, frame time, CPU, RAM and thermal from real sources, finds bottlenecks, and forbids unproven performance claims. Invoke for any performance-related change or claim at IMPLEMENT/TEST.
---

# Skill: performance-engineering

## Mission and Scope

All performance work is driven by **real measurements**, never by assumption:
FPS and frame times, CPU, RAM, thermal, network. Find actual bottlenecks, propose
optimizations only when measurements support them, and never make an unproven
claim (RULE-02).

## When to Invoke (Triggers)

- Any change or claim touching FPS, frame pacing, CPU/GPU, RAM, thermal, battery.
- TEST phase when a feature claims a performance effect.
- When a bottleneck is suspected.

## Inputs and Outputs

- Inputs: telemetry pipeline (`platform/MonitorHub`, `Monitor`, `FpsSampler` via
  `gfxinfo`, `NetSampler`, `core/telemetry/SensorFusion`,
  `core/adaptive/FrameTimeAnalysis`, `FramePacingAnalyzer`,
  `IntendedVsyncIntervalTracker`), `tools/stress_test.py` (stress/safety harness,
  NOT a gameplay A/B proof).
- Outputs: measurement report: what was measured, on what (device/ROM/API if
  known), how (source, sampling), results with uncertainty, bottleneck verdict.
  Unmeasured quantities stay `null` with a reason — never `0`.

## Execution Procedure

1. Identify the claim to verify (e.g. "X improves FPS"). Define the measurement
   protocol **before** running: same device, same game scene, same starting
   temperature/brightness, randomized and double-run order, pre-set sample
   size, metrics = frame-time p50/p95/p99, jank/hitch rate, FPS, thermal,
   battery drain.
2. Use only real sources: FPS/frametimes from `gfxinfo` frame stats; CPU/RAM/
   thermal from `MonitorHub` snapshot (check availability flags — `SensorFusion`
   marks unavailability as null, not 0); CPU frequency best-effort read-only
   from cpufreq sysfs. GPU utilization/renderer without a trustworthy source
   stays `null`.
3. Analyze with `FramePacingAnalyzer`/`FrameTimeAnalysis` (median, p95/p99,
   variance, hitch rate); compare sessions per game only
   (`SessionFpsBaselineStore` — descriptive comparison, never a controlled A/B).
4. Locate bottlenecks with `core/adaptive/BottleneckDetector.kt`; state
   confidence honestly (GPU classified by exclusion = low confidence).
5. Only propose a change if measurements show a real bottleneck; apply, then
   re-measure with the same protocol; adopt only if the gain exceeds
   measurement noise and violates no thermal/energy limit.
6. Report: numbers + environment + protocol, or explicitly "not measured".

## Acceptance Criteria

- [ ] Every performance statement cites a measurement (source, device, protocol)
  or is marked "not measured".
- [ ] null-semantics respected: unavailable ≠ 0.
- [ ] No FPS-gain claim from `tools/stress_test.py` (it is a stress/safety test)
  or from CI.
- [ ] Thermal and battery were recorded alongside any performance change.

## Testing and Verification

- Unit tests for parsers/analyzers (`FrameTimeAnalysisTest`, `FramePacingAnalyzerTest`, …).
- `python3 NitroBoost/tools/stress_test.py --selftest`-style parser tests only;
  device stress runs need ADB + a real device and are reported as such.
- CI green is necessary but proves nothing about on-device performance.

## Non-Negotiable Constraints

- RULE-02 (no unmeasured claims — absolute), RULE-03 (thermal protection wins
  over any performance goal), RULE-08 (command sent ≠ executed).

## Repository References

- `platform/FpsSampler.kt`, `platform/MonitorHub.kt`, `platform/Monitor.kt`, `platform/NetSampler.kt`
- `core/telemetry/` (`SensorFusion.kt`, `TelemetryModels.kt`, `IntendedVsyncIntervalTracker.kt`)
- `core/adaptive/FrameTimeAnalysis.kt`, `FramePacingAnalyzer.kt`, `BottleneckDetector.kt`, `SessionFpsBaselineStore.kt`
- `NitroBoost/tools/stress_test.py`, `NitroBoost/docs/COMPETITIVE_ROADMAP.md` (A/B protocol §3)
