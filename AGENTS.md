# Omai Genzo engineering rules

## Prime directive

Runtime speed is the product. Optimize for the shortest time from a swipe to a visible next photo. Readability, abstraction purity, architectural symmetry, and implementation elegance are secondary. Do not trade measurable latency, memory bandwidth, battery, or cache efficiency for cleaner-looking code.

## RAW selection pipeline

- Never unpack or demosaic a full RAW merely to show a selection preview.
- Preview priority is: JPEG sidecar, embedded RAW JPEG over an FD, bounded reduced preview, then RAW processing only as a last resort.
- Keep `content://` input on file descriptors. Avoid copying whole RAW files into app storage.
- Avoid Java/Kotlin byte and bitmap copies when JNI, mmap, or direct buffers can remove them.
- Decode only near the requested display size. Gallery thumbnails and full-screen previews must use separate size buckets.
- Bound caches by actual bytes, not entry count. Never allow speculative work to exceed the memory budget.
- Deduplicate concurrent work for the same photo and size bucket.
- Prefetch next, previous, and lookahead photos. Stale work must never replace the current generation.
- Never block the UI thread with provider I/O, LibRaw, bitmap decoding, or GPU uploads.

## Android storage and scanning

- Prefer a single `ContentResolver.query` over `DocumentFile` per-file calls.
- Persist SAF permissions and operate directly on source URIs.
- Treat Binder calls, allocations, stream copies, and JNI transitions as costs that require justification.

## Performance verification

- Add a regression test for cache, scheduling, sizing, or concurrency changes.
- Keep cheap counters for cache hits and decode latency so optimization decisions can use measurements.
- Verify `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and formatting before a PR.
- Benchmark on real ARM64 hardware before claiming end-to-end latency improvements. JVM micro-tests only protect hot-path algorithms.

## Performance logging

- Keep `OmaiPerf` JSONL and Logcat events intact when changing a measured hot path.
- Log full source URI, selected decode path, cache outcome, dimensions, bytes, and elapsed nanoseconds.
- Persist logs off the UI thread and keep file rotation bounded.
- The user has explicitly authorized personally identifying filenames, URIs, device information, and full exception stacks in local diagnostic logs. Do not upload them externally.
