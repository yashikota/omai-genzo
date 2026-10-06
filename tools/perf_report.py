#!/usr/bin/env python3
"""Summarise OmaiPerf JSONL logs (and PreviewDecodeBenchmark results) as Markdown.

  tools/perf_report.py omai-perf.jsonl                  # one run
  tools/perf_report.py before.jsonl after.jsonl         # side by side, with deltas
  tools/perf_report.py a.jsonl b.jsonl --warn-regression 30

Latencies are in milliseconds. Only events the app already writes are used.
"""
import argparse
import json
import sys
from collections import Counter, defaultdict


def load(path):
    events = []
    with open(path, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            line = line.strip()
            if not line.startswith("{"):
                continue
            try:
                events.append(json.loads(line))
            except json.JSONDecodeError:
                continue  # a torn last line after a crash is expected
    return events


def percentile(sorted_values, p):
    if not sorted_values:
        return None
    return sorted_values[int((len(sorted_values) - 1) * p)]


def stats(values):
    ordered = sorted(values)
    if not ordered:
        return None
    return {
        "n": len(ordered),
        "p50": percentile(ordered, 0.50),
        "p95": percentile(ordered, 0.95),
        "max": ordered[-1],
        "mean": sum(ordered) / len(ordered),
    }


def ms(event, key="duration_ns"):
    return event[key] / 1e6


def summarise(events):
    by_name = defaultdict(list)
    for event in events:
        by_name[event.get("event")].append(event)

    decodes = [e for e in by_name["preview_decode_end"] if e.get("success")]
    hits = len(by_name["preview_cache_hit"])
    misses = len(by_name["preview_decode_end"])

    summary = {
        "decode_by_bucket": {},
        "visible": stats([ms(e) for e in by_name["photo_visible_result"] if e.get("success")]),
        "cache_hit_ratio": hits / (hits + misses) if hits + misses else None,
        "paths": dict(Counter(e.get("path", "?") for e in by_name["preview_decode_path"])),
        "joins": len(by_name["single_flight_join"]),
        "failed_decodes": sum(1 for e in by_name["preview_decode_end"] if not e.get("success")),
        "thermal_max": max((e.get("status", 0) for e in by_name["thermal"]), default=0),
        "dropped_logs": max((e.get("dropped_logs", 0) for e in by_name["system_sample"]), default=0),
        "bench": {e["bench"]: e for e in by_name["bench_result"] + by_name[None] if "bench" in e},
    }
    grouped = defaultdict(list)
    for event in decodes:
        grouped[(event.get("bucket", "?"), event.get("priority", "?"))].append(ms(event))
    for key, values in grouped.items():
        summary["decode_by_bucket"]["/".join(key)] = stats(values)
    samples = by_name["system_sample"]
    if samples:
        summary["pss_mb_max"] = max(e.get("pss_kb", 0) for e in samples) / 1024
        summary["native_heap_mb_max"] = max(e.get("native_heap", 0) for e in samples) / 1048576
    return summary


def fmt(value, digits=1):
    return "-" if value is None else f"{value:.{digits}f}"


def delta(before, after):
    if before in (None, 0) or after is None:
        return ""
    change = (after - before) / before * 100
    return f" ({change:+.0f}%)"


def stat_rows(title, left, right):
    rows = []
    for column in ("p50", "p95", "max", "mean"):
        a = left[column] if left else None
        b = right[column] if right else None
        cell = fmt(a) if right is None else f"{fmt(a)} -> {fmt(b)}{delta(a, b)}"
        rows.append(cell)
    n = (left or {}).get("n", 0) if right is None else f"{(left or {}).get('n', 0)} -> {(right or {}).get('n', 0)}"
    return f"| {title} | {n} | " + " | ".join(rows) + " |"


def render(summaries, names):
    out = []
    left = summaries[0]
    right = summaries[1] if len(summaries) > 1 else None
    out.append(f"# OmaiPerf report: {' vs '.join(names)}\n")
    out.append("| metric (ms) | n | p50 | p95 | max | mean |")
    out.append("|---|---|---|---|---|---|")
    out.append(stat_rows("photo visible (request -> bitmap)", left["visible"], right["visible"] if right else None))
    keys = sorted(set(left["decode_by_bucket"]) | (set(right["decode_by_bucket"]) if right else set()))
    for key in keys:
        out.append(stat_rows(f"decode {key}", left["decode_by_bucket"].get(key), right["decode_by_bucket"].get(key) if right else None))
    for bench in sorted(set(left["bench"]) | (set(right["bench"]) if right else set())):
        a = left["bench"].get(bench)
        b = right["bench"].get(bench) if right else None
        conv = lambda e: None if e is None else {"n": e["samples"], "p50": e["p50_ms"], "p95": e["p95_ms"], "max": e["max_ms"], "mean": e["mean_ms"]}
        out.append(stat_rows(f"bench {bench}", conv(a), conv(b) if right else None))

    out.append("\n| counter | " + " | ".join(names) + " |")
    out.append("|---|" + "---|" * len(names))
    rows = [
        ("cache hit ratio", "cache_hit_ratio", 3),
        ("single-flight joins", "joins", 0),
        ("failed decodes", "failed_decodes", 0),
        ("thermal status (max)", "thermal_max", 0),
        ("dropped log lines", "dropped_logs", 0),
        ("PSS max (MB)", "pss_mb_max", 0),
        ("native heap max (MB)", "native_heap_mb_max", 0),
    ]
    for label, key, digits in rows:
        out.append(f"| {label} | " + " | ".join(fmt(s.get(key), digits) for s in summaries) + " |")
    out.append("\n**decode paths**")
    for name, summary in zip(names, summaries):
        out.append(f"- {name}: " + (", ".join(f"{k} x{v}" for k, v in sorted(summary["paths"].items())) or "none"))
    return "\n".join(out)


def regressions(before, after, threshold_percent):
    found = []
    for name, new in sorted(after["bench"].items()):
        old = before["bench"].get(name)
        if not old or not old["p50_ms"]:
            continue
        change = (new["p50_ms"] - old["p50_ms"]) / old["p50_ms"] * 100
        if change > threshold_percent:
            found.append((name, old["p50_ms"], new["p50_ms"], change))
    return found


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("logs", nargs="+", help="one log, or two to compare (before after)")
    parser.add_argument(
        "--warn-regression",
        type=float,
        metavar="PERCENT",
        help="print a GitHub ::warning to stderr for each benchmark whose p50 grew by more than PERCENT (never fails)",
    )
    args = parser.parse_args(argv)
    if len(args.logs) > 2:
        parser.error("pass one log, or two to compare")
    summaries = [summarise(load(p)) for p in args.logs]
    print(render(summaries, args.logs))
    if args.warn_regression is not None and len(summaries) == 2:
        for name, old, new, change in regressions(summaries[0], summaries[1], args.warn_regression):
            print(f"::warning title=Benchmark regression::{name} p50 {old:.1f}ms -> {new:.1f}ms ({change:+.0f}%)", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
