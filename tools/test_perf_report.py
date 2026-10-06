import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stdout

import perf_report


def line(event, **fields):
    return json.dumps({"event": event, **fields})


class PerfReportTest(unittest.TestCase):
    def write_log(self, lines):
        handle = tempfile.NamedTemporaryFile("w", suffix=".jsonl", delete=False)
        handle.write("\n".join(lines) + "\n")
        handle.close()
        self.addCleanup(os.unlink, handle.name)
        return handle.name

    def sample_log(self, scale=1):
        lines = []
        for i in range(10):
            lines.append(line("preview_decode_end", success=True, bucket="PREVIEW", priority="VISIBLE", duration_ns=(i + 1) * 1_000_000 * scale))
            lines.append(line("photo_visible_result", success=True, duration_ns=(i + 1) * 2_000_000 * scale))
        lines += [line("preview_cache_hit")] * 10
        lines += [line("preview_decode_path", path="embedded_jpeg")] * 7
        lines += [line("preview_decode_path", path="half_raw_fallback")] * 3
        lines.append(line("system_sample", pss_kb=2048, native_heap=1048576, dropped_logs=0))
        lines.append("{torn last line")
        return lines

    def test_percentiles_and_ratios(self):
        summary = perf_report.summarise(perf_report.load(self.write_log(self.sample_log())))
        decode = summary["decode_by_bucket"]["PREVIEW/VISIBLE"]
        self.assertEqual(decode["n"], 10)
        self.assertAlmostEqual(decode["p50"], 5.0)
        self.assertAlmostEqual(decode["max"], 10.0)
        self.assertAlmostEqual(summary["cache_hit_ratio"], 0.5)
        self.assertEqual(summary["paths"], {"embedded_jpeg": 7, "half_raw_fallback": 3})

    def test_torn_lines_are_ignored(self):
        events = perf_report.load(self.write_log(['{"event":"a"}', "{broken", ""]))
        self.assertEqual(len(events), 1)

    def test_comparison_shows_the_change(self):
        before = self.write_log(self.sample_log(scale=2))
        after = self.write_log(self.sample_log(scale=1))
        out = io.StringIO()
        with redirect_stdout(out):
            perf_report.main([before, after])
        self.assertIn("(-50%)", out.getvalue())

    def test_instrumented_benchmark_results_are_compared(self):
        def bench(p50):
            return line("bench_result", bench="swipe_next_with_prefetch", samples=20, p50_ms=p50, p95_ms=p50 * 2, max_ms=p50 * 3, mean_ms=p50)

        before = self.write_log([bench(40.0)])
        after = self.write_log([bench(4.0)])
        out = io.StringIO()
        with redirect_stdout(out):
            perf_report.main([before, after])
        self.assertIn("bench swipe_next_with_prefetch", out.getvalue())
        self.assertIn("(-90%)", out.getvalue())

    def test_empty_log_does_not_crash(self):
        out = io.StringIO()
        with redirect_stdout(out):
            perf_report.main([self.write_log([])])
        self.assertIn("OmaiPerf report", out.getvalue())


if __name__ == "__main__":
    unittest.main()
