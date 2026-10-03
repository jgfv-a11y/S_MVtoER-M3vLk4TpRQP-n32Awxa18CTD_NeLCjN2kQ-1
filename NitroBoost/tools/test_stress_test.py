#!/usr/bin/env python3
"""Offline parser/safety tests for stress_test.py; these do not require an ADB device."""

import argparse
import contextlib
import io
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from stress_test import (  # noqa: E402
    MemoryGrowthTracker,
    build_parser,
    normalize_temperature,
    parse_cpu_percent,
    parse_gfxinfo,
    parse_hardware_properties,
    parse_meminfo,
    parse_pss_kb,
    parse_sysfs_thermal,
    validate_args,
    validate_package,
)


class StressTestParsingTests(unittest.TestCase):
    def test_normalizes_millidegrees_and_celsius(self):
        self.assertEqual(normalize_temperature("45000"), 45.0)
        self.assertEqual(normalize_temperature("42.5"), 42.5)
        self.assertIsNone(normalize_temperature("-273150"))
        self.assertIsNone(normalize_temperature("not-a-number"))

    def test_parses_all_readable_sysfs_zones_and_uses_warmest(self):
        reading = parse_sysfs_thermal(
            "thermal_zone0|cpu-thermal|41000\n"
            "thermal_zone1|gpu-thermal|43.5\n"
            "thermal_zone2|disconnected|-273150\n"
        )
        self.assertEqual(reading.source, "sysfs")
        self.assertEqual(len(reading.sensors), 2)
        self.assertEqual(reading.max_celsius, 43.5)

    def test_hardware_properties_fallback_reads_cpu_temperatures(self):
        reading = parse_hardware_properties(
            "CPU temperatures: [41.5, 44.0]\nGPU temperatures: [43.0]\n"
        )
        self.assertEqual(reading.source, "dumpsys hardware_properties")
        self.assertEqual(reading.max_celsius, 44.0)

    def test_parses_memavailable_and_total_in_mib(self):
        available, total = parse_meminfo(
            "MemTotal:       8192000 kB\n"
            "MemFree:         1024000 kB\n"
            "MemAvailable:   2048000 kB\n"
        )
        self.assertEqual(available, 2000.0)
        self.assertEqual(total, 8000.0)

    def test_falls_back_to_memfree_if_memavailable_is_missing(self):
        available, total = parse_meminfo("MemTotal: 1024000 kB\nMemFree: 256000 kB\n")
        self.assertEqual(available, 250.0)
        self.assertEqual(total, 1000.0)

    def test_extracts_cpu_only_for_requested_package(self):
        sample = (
            "  5.4% 123/com.example.game: 5.0% user + 0.4% kernel\n"
            "  1.2% 456/com.nitroboost.app (pid 456): 1.0% user + 0.2% kernel\n"
        )
        self.assertEqual(parse_cpu_percent(sample, "com.nitroboost.app"), 1.2)
        self.assertIsNone(parse_cpu_percent(sample, "com.missing.app"))

    def test_parses_pss_and_gfx_jank_summary(self):
        self.assertAlmostEqual(parse_pss_kb("TOTAL PSS: 8192 kB\n"), 8.0)
        metrics = parse_gfxinfo(
            "Total frames rendered: 120\n"
            "Janky frames: 6 (5.00%)\n"
            "90th percentile: 16ms\n95th percentile: 20ms\n99th percentile: 28ms\n"
        )
        self.assertEqual(metrics["total_frames"], 120)
        self.assertEqual(metrics["janky_frames"], 6)
        self.assertEqual(metrics["janky_pct"], 5.0)
        self.assertEqual(metrics["p99_ms"], 28.0)

    def test_memory_growth_requires_consecutive_samples(self):
        tracker = MemoryGrowthTracker(threshold_mb=64, confirm_samples=3)
        self.assertEqual(tracker.observe(100.0), 0.0)
        self.assertIsNone(tracker.observe(170.0))
        self.assertIsNone(tracker.observe(120.0))  # resets the consecutive counter
        self.assertIsNone(tracker.observe(170.0))
        self.assertIsNone(tracker.observe(171.0))
        self.assertEqual(tracker.observe(172.0), 72.0)
        self.assertIsNone(tracker.observe(180.0))  # only report once per run

    def test_thermal_cutoff_cannot_be_raised_above_45_c(self):
        parser = build_parser()
        args = parser.parse_args(["--thermal-limit-c", "45.1"])
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                validate_args(parser, args)

    def test_package_argument_rejects_shell_metacharacters(self):
        self.assertEqual(validate_package("com.example.game"), "com.example.game")
        with self.assertRaises(argparse.ArgumentTypeError):
            validate_package("com.example;reboot")


if __name__ == "__main__":
    unittest.main()
