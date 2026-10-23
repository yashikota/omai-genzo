import contextlib
import io
import json
import os
import tempfile
import unittest

import perf_report


def line(event, **fields):
    return json.dumps({"event": event, **fields})


def bench(name, p50, samples=10):
    return json.dumps({"bench": name, "samples": samples, "p50_ms": p50, "p95_ms": p50 * 2, "max_ms": p50 * 3, "mean_ms": p50})


class PerfReportEdgeCaseTest(unittest.TestCase):
    def write_log(self, lines):
        handle = tempfile.NamedTemporaryFile("w", suffix=".jsonl", delete=False, encoding="utf-8")
        handle.write("\n".join(lines) + "\n")
        handle.close()
        self.addCleanup(os.unlink, handle.name)
        return handle.name

    def summary(self, lines):
        return perf_report.summarise(perf_report.load(self.write_log(lines)))

    def run_cli(self, argv):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = perf_report.main(argv)
        return code, out.getvalue(), err.getvalue()

    # --- statistics ------------------------------------------------------------------------------

    def test_percentile_of_nothing_is_none(self):
        self.assertIsNone(perf_report.percentile([], 0.5))
        self.assertIsNone(perf_report.stats([]))

    def test_percentile_uses_the_lower_nearest_rank(self):
        values = [1, 2, 3, 4]
        self.assertEqual(perf_report.percentile(values, 0.0), 1)
        self.assertEqual(perf_report.percentile(values, 0.5), 2)
        self.assertEqual(perf_report.percentile(values, 0.95), 3)
        self.assertEqual(perf_report.percentile(values, 1.0), 4)

    def test_stats_sort_their_input(self):
        result = perf_report.stats([5, 1, 3])
        self.assertEqual(result, {"n": 3, "p50": 3, "p95": 3, "max": 5, "mean": 3})

    def test_a_single_sample_is_every_percentile(self):
        result = perf_report.stats([7.5])
        self.assertEqual((result["p50"], result["p95"], result["max"], result["mean"]), (7.5, 7.5, 7.5, 7.5))

    # --- summarising -----------------------------------------------------------------------------

    def test_failed_decodes_are_counted_but_kept_out_of_latency(self):
        summary = self.summary([
            line("preview_decode_end", success=True, bucket="PREVIEW", priority="VISIBLE", duration_ns=2_000_000),
            line("preview_decode_end", success=False, bucket="PREVIEW", priority="VISIBLE", duration_ns=900_000_000),
        ])
        self.assertEqual(summary["failed_decodes"], 1)
        self.assertEqual(summary["decode_by_bucket"]["PREVIEW/VISIBLE"]["max"], 2.0)
        self.assertEqual(summary["cache_hit_ratio"], 0.0, "a failed decode is still a miss")

    def test_decodes_are_grouped_by_bucket_and_priority(self):
        summary = self.summary([
            line("preview_decode_end", success=True, bucket="PREVIEW", priority="VISIBLE", duration_ns=1_000_000),
            line("preview_decode_end", success=True, bucket="PREVIEW", priority="PREFETCH", duration_ns=3_000_000),
            line("preview_decode_end", success=True, bucket="THUMBNAIL", priority="VISIBLE", duration_ns=5_000_000),
            line("preview_decode_end", success=True, duration_ns=7_000_000),
        ])
        self.assertEqual(
            sorted(summary["decode_by_bucket"]),
            ["?/?", "PREVIEW/PREFETCH", "PREVIEW/VISIBLE", "THUMBNAIL/VISIBLE"],
        )

    def test_without_any_preview_event_the_hit_ratio_is_unknown(self):
        self.assertIsNone(self.summary([line("scan_end", photos=3)])["cache_hit_ratio"])

    def test_only_successful_visible_results_count(self):
        summary = self.summary([
            line("photo_visible_result", success=True, duration_ns=4_000_000),
            line("photo_visible_result", success=False, duration_ns=1_000_000_000),
        ])
        self.assertEqual(summary["visible"]["n"], 1)
        self.assertEqual(summary["visible"]["max"], 4.0)

    def test_system_samples_are_reported_in_megabytes(self):
        summary = self.summary([
            line("system_sample", pss_kb=1024, native_heap=1048576, dropped_logs=2),
            line("system_sample", pss_kb=4096, native_heap=3 * 1048576, dropped_logs=5),
        ])
        self.assertEqual(summary["pss_mb_max"], 4.0)
        self.assertEqual(summary["native_heap_mb_max"], 3.0)
        self.assertEqual(summary["dropped_logs"], 5)

    def test_the_worst_thermal_status_is_kept(self):
        summary = self.summary([line("thermal", status=1), line("thermal", status=3), line("thermal", status=2)])
        self.assertEqual(summary["thermal_max"], 3)

    def test_single_flight_joins_are_counted(self):
        self.assertEqual(self.summary([line("single_flight_join", key="k")] * 4)["joins"], 4)

    def test_decode_paths_without_a_name_are_grouped_as_unknown(self):
        self.assertEqual(self.summary([line("preview_decode_path")])["paths"], {"?": 1})

    def test_the_latest_result_of_a_benchmark_wins(self):
        summary = self.summary([bench("swipe", 10.0), bench("swipe", 12.0)])
        self.assertEqual(summary["bench"]["swipe"]["p50_ms"], 12.0)

    # --- loading ---------------------------------------------------------------------------------

    def test_logcat_prefixes_and_blank_lines_are_skipped(self):
        events = perf_report.load(self.write_log([
            "",
            "   ",
            "10-23 12:00:00.000  1234  1234 D OmaiPerf: {\"event\":\"x\"}",
            "   {\"event\":\"indented\"}",
            "[1, 2, 3]",
        ]))
        self.assertEqual([e["event"] for e in events], ["indented"])

    def test_invalid_utf8_does_not_abort_the_report(self):
        handle = tempfile.NamedTemporaryFile("wb", suffix=".jsonl", delete=False)
        handle.write(b'{"event":"ok","name":"\xff\xfe"}\n{"event":"second"}\n')
        handle.close()
        self.addCleanup(os.unlink, handle.name)
        self.assertEqual([e["event"] for e in perf_report.load(handle.name)], ["ok", "second"])

    def test_non_ascii_paths_survive_a_round_trip(self):
        events = perf_report.load(self.write_log([line("preview_decode_path", path="写真/埋め込み")]))
        self.assertEqual(events[0]["path"], "写真/埋め込み")

    # --- rendering -------------------------------------------------------------------------------

    def test_delta_handles_missing_and_zero_baselines(self):
        self.assertEqual(perf_report.delta(None, 5), "")
        self.assertEqual(perf_report.delta(0, 5), "")
        self.assertEqual(perf_report.delta(5, None), "")
        self.assertEqual(perf_report.delta(10, 15), " (+50%)")
        self.assertEqual(perf_report.delta(10, 10), " (+0%)")

    def test_fmt_renders_missing_values_as_a_dash(self):
        self.assertEqual(perf_report.fmt(None), "-")
        self.assertEqual(perf_report.fmt(1.25, 1), "1.2")
        self.assertEqual(perf_report.fmt(3, 0), "3")

    def test_a_bucket_present_in_only_one_run_is_still_listed(self):
        before = self.write_log([line("preview_decode_end", success=True, bucket="THUMBNAIL", priority="VISIBLE", duration_ns=1_000_000)])
        after = self.write_log([line("preview_decode_end", success=True, bucket="PREVIEW", priority="VISIBLE", duration_ns=1_000_000)])
        _, out, _ = self.run_cli([before, after])
        self.assertIn("decode THUMBNAIL/VISIBLE", out)
        self.assertIn("decode PREVIEW/VISIBLE", out)

    def test_every_counter_row_has_one_column_per_log(self):
        _, out, _ = self.run_cli([self.write_log([]), self.write_log([])])
        counter_rows = [row for row in out.splitlines() if row.startswith("| cache hit ratio")]
        self.assertEqual(len(counter_rows), 1)
        self.assertEqual(counter_rows[0].count("|"), 4)

    def test_decode_paths_are_listed_for_each_log(self):
        _, out, _ = self.run_cli([self.write_log([line("preview_decode_path", path="sidecar_jpeg")]), self.write_log([])])
        self.assertIn("sidecar_jpeg x1", out)
        self.assertIn(": none", out)

    # --- regressions -----------------------------------------------------------------------------

    def test_new_and_zero_baseline_benchmarks_are_never_regressions(self):
        before = self.summary([bench("zero", 0.0)])
        after = self.summary([bench("zero", 50.0), bench("brand_new", 99.0)])
        self.assertEqual(perf_report.regressions(before, after, 10), [])

    def test_a_change_exactly_at_the_threshold_is_not_a_regression(self):
        before = self.summary([bench("a", 10.0)])
        after = self.summary([bench("a", 13.0)])
        self.assertEqual(perf_report.regressions(before, after, 30), [])
        self.assertEqual(len(perf_report.regressions(before, after, 29.9)), 1)

    def test_regressions_are_sorted_by_name(self):
        before = self.summary([bench("b", 1.0), bench("a", 1.0)])
        after = self.summary([bench("b", 2.0), bench("a", 2.0)])
        self.assertEqual([name for name, *_ in perf_report.regressions(before, after, 10)], ["a", "b"])

    def test_the_regression_flag_is_ignored_for_a_single_log(self):
        code, _, err = self.run_cli([self.write_log([bench("a", 1.0)]), "--warn-regression", "1"])
        self.assertEqual(code, 0)
        self.assertEqual(err, "")

    def test_more_than_two_logs_is_a_usage_error(self):
        logs = [self.write_log([]) for _ in range(3)]
        with self.assertRaises(SystemExit) as raised:
            self.run_cli(logs)
        self.assertEqual(raised.exception.code, 2)

    def test_a_missing_log_is_reported(self):
        with self.assertRaises(FileNotFoundError):
            self.run_cli([os.path.join(tempfile.gettempdir(), "omai-does-not-exist.jsonl")])


if __name__ == "__main__":
    unittest.main()
