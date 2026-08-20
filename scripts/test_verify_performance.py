#!/usr/bin/env python3

import unittest
from unittest.mock import patch
from pathlib import Path

import verify_performance


class VerifyPerformanceTest(unittest.TestCase):
    def test_regular_metric_statistics(self):
        metric = {
            "minimum": 10.0,
            "maximum": 70.0,
            "median": 40.0,
            "runs": [10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0],
        }

        self.assertEqual(40.0, verify_performance.observed(metric, "median"))
        self.assertEqual(70.0, verify_performance.observed(metric, "maximum"))
        self.assertEqual(70.0, verify_performance.observed(metric, "p95"))

    def test_sampled_frame_percentiles_are_read_directly(self):
        metric = {"P50": 12.0, "P90": 24.0, "P95": 30.0, "P99": 45.0, "runs": [[1.0, 2.0]]}

        self.assertEqual(12.0, verify_performance.observed(metric, "p50"))
        self.assertEqual(30.0, verify_performance.observed(metric, "p95"))
        self.assertEqual(45.0, verify_performance.observed(metric, "p99"))

    def test_metric_can_come_from_sampled_collection(self):
        benchmark = {
            "metrics": {"timeToInitialDisplayMs": {"median": 1.0}},
            "sampledMetrics": {"frameDurationCpuMs": {"P95": 2.0}},
        }

        self.assertEqual(
            {"P95": 2.0},
            verify_performance.find_metric(benchmark, "frameDurationCpuMs"),
        )
        self.assertIsNone(verify_performance.find_metric(benchmark, "missing"))

    def test_duplicate_metric_collections_are_rejected(self):
        benchmark = {
            "metrics": {"duplicate": {"median": 1.0}},
            "sampledMetrics": {"duplicate": {"P95": 2.0}},
        }

        with self.assertRaises(ValueError):
            verify_performance.find_metric(benchmark, "duplicate")

    def test_windows_long_path_prefix_is_added(self):
        with patch.object(verify_performance.os, "name", "nt"):
            resolved = verify_performance.long_path(Path("performance"))

        self.assertTrue(str(resolved).startswith("\\\\?\\"))


if __name__ == "__main__":
    unittest.main()
