#!/usr/bin/env python3
"""Fail a release candidate when Macrobenchmark results exceed AF's budgets."""

from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path
import sys


def long_path(path: Path) -> Path:
    resolved = path.resolve()
    if os.name != "nt":
        return resolved
    raw = str(resolved)
    if raw.startswith("\\\\?\\"):
        return resolved
    if raw.startswith("\\\\"):
        return Path("\\\\?\\UNC\\" + raw[2:])
    return Path("\\\\?\\" + raw)


def newest_report(root: Path) -> Path:
    searchable_root = long_path(root)
    candidates = [path for path in searchable_root.rglob("*-benchmarkData.json") if path.is_file()]
    if not candidates:
        raise ValueError(f"No Macrobenchmark JSON report found under {root}")
    return max(candidates, key=lambda path: path.stat().st_mtime_ns)


def percentile(values: list[float], fraction: float) -> float:
    if not values:
        raise ValueError("Metric has no measured runs")
    ordered = sorted(values)
    return ordered[max(0, math.ceil(fraction * len(ordered)) - 1)]


def benchmark_by_name(report: dict, requested: str) -> dict:
    matches = [
        item for item in report.get("benchmarks", [])
        if item.get("name") == requested or str(item.get("name", "")).endswith(requested)
    ]
    if len(matches) != 1:
        found = ", ".join(str(item.get("name")) for item in report.get("benchmarks", []))
        raise ValueError(f"Expected one benchmark ending in {requested!r}; found: {found}")
    return matches[0]


def observed(metric: dict, statistic: str) -> float:
    sampled_key = statistic.upper()
    if sampled_key in {"P50", "P90", "P95", "P99"} and sampled_key in metric:
        return float(metric[sampled_key])
    if statistic == "median":
        return float(metric["median"])
    if statistic == "maximum":
        return float(metric["maximum"])
    runs = [float(value) for value in metric.get("runs", [])]
    if statistic == "p95":
        return percentile(runs, 0.95)
    raise ValueError(f"Unsupported statistic: {statistic}")


def find_metric(benchmark: dict, metric_name: str) -> dict | None:
    regular = benchmark.get("metrics", {}).get(metric_name)
    sampled = benchmark.get("sampledMetrics", {}).get(metric_name)
    if regular is not None and sampled is not None:
        raise ValueError(f"Metric {metric_name!r} is present in both metric collections")
    metric = regular if regular is not None else sampled
    return metric if isinstance(metric, dict) else None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("report_root", type=Path)
    parser.add_argument("--budgets", type=Path, default=Path("performance/budgets.json"))
    args = parser.parse_args()

    report_path = newest_report(args.report_root)
    report = json.loads(report_path.read_text(encoding="utf-8"))
    budgets = json.loads(args.budgets.read_text(encoding="utf-8"))
    if budgets.get("schemaVersion") != 1:
        raise ValueError("Unsupported performance budget schema")

    failures: list[str] = []
    observations: list[str] = []
    minimum_iterations = int(budgets.get("minimumIterations", 5))
    for benchmark_name, metric_budgets in budgets.get("benchmarks", {}).items():
        benchmark = benchmark_by_name(report, benchmark_name)
        repeats = int(benchmark.get("repeatIterations", 0))
        if repeats < minimum_iterations:
            failures.append(f"{benchmark_name}: only {repeats} iterations; need {minimum_iterations}")
        for metric_name, limits in metric_budgets.items():
            metric = find_metric(benchmark, metric_name)
            if metric is None:
                failures.append(f"{benchmark_name}: missing metric {metric_name}")
                continue
            for key, limit in limits.items():
                if not key.endswith("Max"):
                    raise ValueError(f"Unsupported budget key: {key}")
                statistic = key[:-3]
                value = observed(metric, statistic)
                maximum = float(limit)
                observations.append(f"{benchmark_name}.{metric_name}.{statistic}={value:.2f} (limit {maximum:.2f})")
                if value > maximum:
                    failures.append(
                        f"{benchmark_name}.{metric_name}.{statistic}: {value:.2f} exceeds {maximum:.2f}"
                    )

    print(f"Performance report: {report_path}")
    for line in observations:
        print(line)
    if failures:
        print("Performance gate failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1
    print("Performance gate passed")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(f"Performance gate could not run: {error}", file=sys.stderr)
        raise SystemExit(2)
