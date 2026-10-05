#!/usr/bin/env python3

import contextlib
import copy
import io
import json
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


class ApprovedRssBudgetTest(unittest.TestCase):
    """Check the fixed owner-approved ceilings without allocating app memory."""

    @staticmethod
    def budgets():
        path = Path(__file__).resolve().parent.parent / "performance" / "budgets.json"
        return json.loads(path.read_text(encoding="utf-8"))

    def report_at_limits(self, budgets):
        report = {"benchmarks": []}
        for name, metrics in budgets["benchmarks"].items():
            values = {}
            for metric_name, limits in metrics.items():
                metric = {}
                for key, limit in limits.items():
                    statistic = key[:-3]
                    if statistic == "p95":
                        metric["P95"] = limit
                    else:
                        metric[statistic] = limit
                values[metric_name] = metric
            report["benchmarks"].append({
                "name": name,
                "repeatIterations": budgets["minimumIterations"],
                "metrics": values,
            })
        return report

    def evaluate(self, report, budgets):
        with patch.object(verify_performance.sys, "argv", ["verify_performance.py", "synthetic-report"]), \
             patch.object(verify_performance, "newest_report", return_value=Path("synthetic-benchmarkData.json")), \
             patch.object(Path, "read_text", side_effect=[json.dumps(report), json.dumps(budgets)]), \
             contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return verify_performance.main()

    def test_ceilings_use_kib_and_are_fixed(self):
        budgets = self.budgets()
        self.assertEqual(7, budgets["minimumIterations"])
        expected = {"coldStartAndFirstPaint": 150, "largeDirectoryFirstContent": 200}
        for name, mib in expected.items():
            with self.subTest(name=name):
                self.assertEqual(mib * 1024, budgets["benchmarks"][name]["memoryRssAnonMaxKb"]["maximumMax"])

    def test_exact_limits_pass(self):
        budgets = self.budgets()
        self.assertEqual(0, self.evaluate(self.report_at_limits(budgets), budgets))

    def test_one_kib_above_either_rss_ceiling_fails(self):
        budgets = self.budgets()
        baseline = self.report_at_limits(budgets)
        for name in ("coldStartAndFirstPaint", "largeDirectoryFirstContent"):
            with self.subTest(name=name):
                report = copy.deepcopy(baseline)
                item = next(item for item in report["benchmarks"] if item["name"] == name)
                item["metrics"]["memoryRssAnonMaxKb"]["maximum"] += 1
                self.assertEqual(1, self.evaluate(report, budgets))

    def test_other_limits_are_still_enforced(self):
        budgets = self.budgets()
        baseline = self.report_at_limits(budgets)
        for benchmark in baseline["benchmarks"]:
            for metric_name, values in benchmark["metrics"].items():
                for statistic in values:
                    with self.subTest(benchmark=benchmark["name"], metric=metric_name, statistic=statistic):
                        report = copy.deepcopy(baseline)
                        item = next(item for item in report["benchmarks"] if item["name"] == benchmark["name"])
                        item["metrics"][metric_name][statistic] += 1
                        self.assertEqual(1, self.evaluate(report, budgets))

    def test_missing_metric_and_insufficient_iterations_fail(self):
        budgets = self.budgets()
        baseline = self.report_at_limits(budgets)
        missing = copy.deepcopy(baseline)
        del missing["benchmarks"][0]["metrics"]["memoryRssAnonMaxKb"]
        self.assertEqual(1, self.evaluate(missing, budgets))
        short_run = copy.deepcopy(baseline)
        short_run["benchmarks"][0]["repeatIterations"] = 6
        self.assertEqual(1, self.evaluate(short_run, budgets))


if __name__ == "__main__":
    unittest.main()
