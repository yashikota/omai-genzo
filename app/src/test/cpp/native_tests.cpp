// Host-side tests for the pure C++ pieces of the native layer (no Android, no LibRaw).
//   g++ -std=c++17 -O2 -I app/src/main/cpp app/src/test/cpp/native_tests.cpp -o native_tests && ./native_tests
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <unistd.h>
#include <vector>

#include "ColorScience.h"
#include "JpegProbe.h"
#include "RawFileView.h"

using namespace omaigenzo;

static int gFailures = 0;
#define CHECK(cond)                                                              \
    do {                                                                         \
        if (!(cond)) {                                                           \
            std::fprintf(stderr, "FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); \
            gFailures++;                                                         \
        }                                                                        \
    } while (0)

static void appendSegment(std::vector<uint8_t> &out, uint8_t marker, const std::vector<uint8_t> &payload) {
    out.push_back(0xFF);
    out.push_back(marker);
    const size_t len = payload.size() + 2;
    out.push_back(static_cast<uint8_t>(len >> 8));
    out.push_back(static_cast<uint8_t>(len & 0xFF));
    out.insert(out.end(), payload.begin(), payload.end());
}

static std::vector<uint8_t> sof(int w, int h) {
    return {8, static_cast<uint8_t>(h >> 8), static_cast<uint8_t>(h & 0xFF), static_cast<uint8_t>(w >> 8),
            static_cast<uint8_t>(w & 0xFF), 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1};
}

static std::vector<uint8_t> makeJpeg(int w, int h, bool withExifThumb) {
    std::vector<uint8_t> j = {0xFF, 0xD8};
    if (withExifThumb) {
        // APP1 carrying a nested JPEG with a *different* size: it must be skipped, not parsed.
        std::vector<uint8_t> nested = {'E', 'x', 'i', 'f', 0, 0, 0xFF, 0xD8};
        appendSegment(nested, 0xC0, sof(160, 120));
        appendSegment(j, 0xE1, nested);
    }
    appendSegment(j, 0xDB, std::vector<uint8_t>(64, 1));
    appendSegment(j, 0xC0, sof(w, h));
    appendSegment(j, 0xDA, {0, 0});
    return j;
}

static void testProbeReadsRealDimensions() {
    auto jpeg = makeJpeg(1616, 1080, false);
    int w = 0, h = 0;
    CHECK(probeJpegSize(jpeg.data(), jpeg.size(), &w, &h));
    CHECK(w == 1616 && h == 1080);
}

static void testProbeSkipsNestedExifThumbnail() {
    auto jpeg = makeJpeg(6000, 4000, true);
    int w = 0, h = 0;
    CHECK(probeJpegSize(jpeg.data(), jpeg.size(), &w, &h));
    CHECK(w == 6000 && h == 4000);
}

static void testProbeAcceptsProgressiveSof2() {
    std::vector<uint8_t> j = {0xFF, 0xD8};
    appendSegment(j, 0xC2, sof(4000, 3000));
    int w = 0, h = 0;
    CHECK(probeJpegSize(j.data(), j.size(), &w, &h));
    CHECK(w == 4000 && h == 3000);
}

static void testProbeRejectsGarbageAndTruncation() {
    int w = 0, h = 0;
    std::vector<uint8_t> notJpeg(256, 0x42);
    CHECK(!probeJpegSize(notJpeg.data(), notJpeg.size(), &w, &h));

    auto jpeg = makeJpeg(100, 100, false);
    CHECK(!probeJpegSize(jpeg.data(), 3, &w, &h));
    CHECK(!probeJpegSize(jpeg.data(), 20, &w, &h));  // cut before the SOF

    std::vector<uint8_t> noSof = {0xFF, 0xD8};
    appendSegment(noSof, 0xDA, {0, 0});
    CHECK(!probeJpegSize(noSof.data(), noSof.size(), &w, &h));

    std::vector<uint8_t> badLength = {0xFF, 0xD8, 0xFF, 0xE1, 0x00, 0x00, 0, 0};
    CHECK(!probeJpegSize(badLength.data(), badLength.size(), &w, &h));
}

static void testProbeNeverReadsPastTheBuffer() {
    // Every truncation of a valid JPEG must be rejected or accepted without overrunning (run under ASan).
    auto jpeg = makeJpeg(2048, 1365, true);
    for (size_t len = 0; len <= jpeg.size(); len++) {
        std::vector<uint8_t> exact(jpeg.begin(), jpeg.begin() + len);
        int w = 0, h = 0;
        (void) probeJpegSize(exact.data(), exact.size(), &w, &h);
    }
    CHECK(true);
}

static void testProbeSkipsFillBytesAndStandaloneMarkers() {
    std::vector<uint8_t> j = {0xFF, 0xD8, 0xFF, 0xFF, 0xFF, 0x01, 0xFF, 0xD0, 0xFF, 0xD7};
    appendSegment(j, 0xC0, sof(3000, 2000));
    int w = 0, h = 0;
    CHECK(probeJpegSize(j.data(), j.size(), &w, &h));
    CHECK(w == 3000 && h == 2000);
}

static void testProbeSkipsStrayBytesBetweenSegments() {
    std::vector<uint8_t> j = {0xFF, 0xD8, 0x00, 0x12, 0x34};
    appendSegment(j, 0xC0, sof(640, 480));
    int w = 0, h = 0;
    CHECK(probeJpegSize(j.data(), j.size(), &w, &h));
    CHECK(w == 640 && h == 480);
}

static void testProbeDoesNotMistakeTablesForAFrameHeader() {
    // DHT (C4), JPG (C8) and DAC (CC) sit in the SOF range but carry no dimensions.
    std::vector<uint8_t> j = {0xFF, 0xD8};
    appendSegment(j, 0xC4, sof(1, 1));
    appendSegment(j, 0xC8, sof(2, 2));
    appendSegment(j, 0xCC, sof(3, 3));
    appendSegment(j, 0xC0, sof(1616, 1080));
    int w = 0, h = 0;
    CHECK(probeJpegSize(j.data(), j.size(), &w, &h));
    CHECK(w == 1616 && h == 1080);
}

static void testProbeAcceptsEveryFrameHeaderKind() {
    const uint8_t sofs[] = {0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF};
    for (uint8_t marker : sofs) {
        std::vector<uint8_t> j = {0xFF, 0xD8};
        appendSegment(j, marker, sof(800, 600));
        int w = 0, h = 0;
        CHECK(probeJpegSize(j.data(), j.size(), &w, &h));
        CHECK(w == 800 && h == 600);
    }
}

static void testProbeRejectsZeroSizedFrames() {
    int w = -1, h = -1;
    std::vector<uint8_t> zeroWidth = {0xFF, 0xD8};
    appendSegment(zeroWidth, 0xC0, sof(0, 100));
    CHECK(!probeJpegSize(zeroWidth.data(), zeroWidth.size(), &w, &h));

    std::vector<uint8_t> zeroHeight = {0xFF, 0xD8};
    appendSegment(zeroHeight, 0xC0, sof(100, 0));
    CHECK(!probeJpegSize(zeroHeight.data(), zeroHeight.size(), &w, &h));
}

static void testProbeReadsTheLargestEncodableSize() {
    std::vector<uint8_t> j = {0xFF, 0xD8};
    appendSegment(j, 0xC0, sof(65535, 65535));
    int w = 0, h = 0;
    CHECK(probeJpegSize(j.data(), j.size(), &w, &h));
    CHECK(w == 65535 && h == 65535);
}

static void testProbeStopsAtEndOfImage() {
    std::vector<uint8_t> j = {0xFF, 0xD8, 0xFF, 0xD9};
    appendSegment(j, 0xC0, sof(100, 100));
    int w = 0, h = 0;
    CHECK(!probeJpegSize(j.data(), j.size(), &w, &h));
}

static void testProbeRejectsASegmentThatRunsOffTheEnd() {
    // APP1 claims 0x4000 bytes but the buffer ends right after it: no read past the end (ASan).
    std::vector<uint8_t> j = {0xFF, 0xD8, 0xFF, 0xE1, 0x40, 0x00, 'E', 'x', 'i', 'f'};
    int w = 0, h = 0;
    CHECK(!probeJpegSize(j.data(), j.size(), &w, &h));
}

static void testProbeRejectsATruncatedFrameHeader() {
    std::vector<uint8_t> j = {0xFF, 0xD8, 0xFF, 0xC0, 0x00, 0x11, 8, 0x04};
    int w = 0, h = 0;
    CHECK(!probeJpegSize(j.data(), j.size(), &w, &h));
}

static void testProbeFindsTheFrameAfterManySegments() {
    std::vector<uint8_t> j = {0xFF, 0xD8};
    for (int i = 0; i < 64; i++) appendSegment(j, static_cast<uint8_t>(0xE0 + (i % 16)), std::vector<uint8_t>(100 + i, 0xAB));
    appendSegment(j, 0xC0, sof(6000, 4000));
    int w = 0, h = 0;
    CHECK(probeJpegSize(j.data(), j.size(), &w, &h));
    CHECK(w == 6000 && h == 4000);
}

static void testProbeSurvivesRandomGarbage() {
    // Random bytes after a valid SOI must never crash or overrun (run under ASan/UBSan).
    uint32_t seed = 12345;
    for (int round = 0; round < 2000; round++) {
        std::vector<uint8_t> j = {0xFF, 0xD8};
        const size_t len = 2 + (round % 200);
        while (j.size() < len) {
            seed = seed * 1664525u + 1013904223u;
            j.push_back(static_cast<uint8_t>(seed >> 24));
        }
        int w = 0, h = 0;
        if (probeJpegSize(j.data(), j.size(), &w, &h)) CHECK(w > 0 && h > 0);
    }
}

static std::string writeTemp(const std::vector<uint8_t> &bytes) {
    char path[] = "/tmp/omai_native_test_XXXXXX";
    int fd = mkstemp(path);
    CHECK(fd >= 0);
    CHECK(write(fd, bytes.data(), bytes.size()) == static_cast<ssize_t>(bytes.size()));
    close(fd);
    return path;
}

static void testRegularFilesAreMappedNotCopied() {
    std::vector<uint8_t> bytes(300000);
    for (size_t i = 0; i < bytes.size(); i++) bytes[i] = static_cast<uint8_t>(i * 31);
    std::string path = writeTemp(bytes);

    int fd = open(path.c_str(), O_RDONLY);
    RawFileView view;
    CHECK(view.open(fd, RawFileView::Access::Random));
    CHECK(view.mapped());
    CHECK(view.size() == bytes.size());
    CHECK(std::memcmp(view.data(), bytes.data(), bytes.size()) == 0);
    close(fd);
    unlink(path.c_str());
}

static void testPipesFallBackToBoundedMemory() {
    int fds[2];
    CHECK(pipe(fds) == 0);
    std::vector<uint8_t> bytes(3 * (1u << 20) + 123);
    for (size_t i = 0; i < bytes.size(); i++) bytes[i] = static_cast<uint8_t>(i * 7 + 1);

    pid_t pid = fork();
    if (pid == 0) {  // writer must run concurrently: a pipe buffer is far smaller than the payload
        close(fds[0]);
        size_t off = 0;
        while (off < bytes.size()) {
            ssize_t n = write(fds[1], bytes.data() + off, bytes.size() - off);
            if (n <= 0) _exit(1);
            off += static_cast<size_t>(n);
        }
        _exit(0);
    }
    close(fds[1]);
    RawFileView view;
    CHECK(view.open(fds[0], RawFileView::Access::Sequential));
    CHECK(!view.mapped());
    CHECK(view.size() == bytes.size());
    CHECK(std::memcmp(view.data(), bytes.data(), bytes.size()) == 0);
    close(fds[0]);
}

static void testInvalidAndEmptyFdsAreRejected() {
    RawFileView a;
    CHECK(!a.open(-1, RawFileView::Access::Random));

    std::string path = writeTemp({});
    int fd = open(path.c_str(), O_RDONLY);
    RawFileView b;
    CHECK(!b.open(fd, RawFileView::Access::Random));
    close(fd);
    unlink(path.c_str());
}

static void testSequentialAccessIsMappedToo() {
    std::vector<uint8_t> bytes(4096 * 3 + 17, 0x5A);
    std::string path = writeTemp(bytes);

    int fd = open(path.c_str(), O_RDONLY);
    RawFileView view;
    CHECK(view.open(fd, RawFileView::Access::Sequential));
    CHECK(view.mapped());
    CHECK(view.size() == bytes.size());
    CHECK(view.data()[bytes.size() - 1] == 0x5A);
    close(fd);
    unlink(path.c_str());
}

static void testTheMappingOutlivesTheDescriptor() {
    // The ParcelFileDescriptor is closed once the view is open; the mapped bytes must stay readable.
    std::vector<uint8_t> bytes = {1, 2, 3, 4, 5};
    std::string path = writeTemp(bytes);

    int fd = open(path.c_str(), O_RDONLY);
    RawFileView view;
    CHECK(view.open(fd, RawFileView::Access::Random));
    close(fd);
    unlink(path.c_str());

    CHECK(view.size() == 5);
    CHECK(std::memcmp(view.data(), bytes.data(), bytes.size()) == 0);
}

static void testAOneByteFileIsMapped() {
    std::string path = writeTemp({0x42});
    int fd = open(path.c_str(), O_RDONLY);
    RawFileView view;
    CHECK(view.open(fd, RawFileView::Access::Random));
    CHECK(view.size() == 1 && view.data()[0] == 0x42);
    close(fd);
    unlink(path.c_str());
}

static void testAnEmptyPipeIsRejected() {
    int fds[2];
    CHECK(pipe(fds) == 0);
    close(fds[1]);
    RawFileView view;
    CHECK(!view.open(fds[0], RawFileView::Access::Sequential));
    CHECK(view.data() == nullptr && view.size() == 0);
    close(fds[0]);
}

static void testAClosedDescriptorIsRejected() {
    std::string path = writeTemp({1, 2, 3});
    int fd = open(path.c_str(), O_RDONLY);
    close(fd);
    RawFileView view;
    CHECK(!view.open(fd, RawFileView::Access::Random));
    unlink(path.c_str());
}

static bool near(float a, float b, float eps = 1e-4f) { return std::fabs(a - b) <= eps; }

static void testColorScienceExposure() {
    CHECK(near(ColorScience::evToLinearMultiplier(0.0f), 1.0f));
    CHECK(near(ColorScience::evToLinearMultiplier(1.0f), 2.0f));
    CHECK(near(ColorScience::evToLinearMultiplier(-2.0f), 0.25f));
}

static void testColorScienceNormalizesBayerValues() {
    CHECK(near(ColorScience::normalizeBayer(512, 512.0f, 16383.0f), 0.0f));
    CHECK(near(ColorScience::normalizeBayer(16383, 512.0f, 16383.0f), 1.0f));
    CHECK(near(ColorScience::normalizeBayer(100, 512.0f, 16383.0f), 0.0f));
    CHECK(near(ColorScience::normalizeBayer(65535, 512.0f, 16383.0f), 1.0f));
    CHECK(near(ColorScience::normalizeBayer(1024, 0.0f, 2048.0f), 0.5f));
    // A broken white level must not divide by zero or produce NaN.
    CHECK(ColorScience::normalizeBayer(1000, 512.0f, 512.0f) == 0.0f);
    CHECK(ColorScience::normalizeBayer(1000, 600.0f, 500.0f) == 0.0f);
}

static void testColorScienceGammaMatchesTheKotlinReference() {
    // Same reference values as ColorScienceEdgeCaseTest, so the GPU/native and JVM paths agree.
    CHECK(near(ColorScience::srgbGamma(0.0f), 0.0f));
    CHECK(near(ColorScience::srgbGamma(1.0f), 1.0f));
    CHECK(near(ColorScience::srgbGamma(0.002f), 0.002f * 12.92f));
    CHECK(near(ColorScience::srgbGamma(0.18f), 0.461356f));
    CHECK(near(ColorScience::srgbGamma(0.5f), 0.735357f));
    CHECK(near(ColorScience::srgbGamma(0.0031308f), ColorScience::srgbGamma(0.0031318f), 1e-4f));
    float previous = -1.0f;
    for (int i = 0; i <= 1000; i++) {
        float value = ColorScience::srgbGamma(i / 1000.0f);
        CHECK(value >= previous);
        previous = value;
    }
}

static void testColorScienceToneMapping() {
    float r, g, b;
    ColorScience::acesToneMap(0.0f, 0.18f, 1.0f, r, g, b);
    CHECK(near(r, 0.0f));
    CHECK(near(g, 0.553458f));
    CHECK(near(b, 0.908230f));

    ColorScience::acesToneMap(4.0f, 100.0f, 1000.0f, r, g, b);
    CHECK(near(r, 0.988223f));
    CHECK(near(g, 1.0f, 1e-3f) && near(b, 1.0f, 1e-3f));

    float previous = -1.0f;
    for (int i = 0; i <= 500; i++) {
        ColorScience::acesToneMap(i / 50.0f, 0.0f, 0.0f, r, g, b);
        CHECK(r >= previous && r <= 1.0f);
        CHECK(!std::isnan(r));
        previous = r;
    }
}

static void testRawMetadataDefaultsAreNeutral() {
    RawMetadata meta;
    CHECK(meta.blackLevel < meta.whiteLevel);
    for (float wb : meta.camWb) CHECK(wb == 1.0f);
    for (int i = 0; i < 9; i++) CHECK(meta.colorMatrix[i] == (i % 4 == 0 ? 1.0f : 0.0f));
    CHECK(meta.cfaPattern == 0 && meta.flip == 0);
}

int main() {
    testProbeReadsRealDimensions();
    testProbeSkipsNestedExifThumbnail();
    testProbeAcceptsProgressiveSof2();
    testProbeRejectsGarbageAndTruncation();
    testProbeNeverReadsPastTheBuffer();
    testRegularFilesAreMappedNotCopied();
    testPipesFallBackToBoundedMemory();
    testInvalidAndEmptyFdsAreRejected();
    testProbeSkipsFillBytesAndStandaloneMarkers();
    testProbeSkipsStrayBytesBetweenSegments();
    testProbeDoesNotMistakeTablesForAFrameHeader();
    testProbeAcceptsEveryFrameHeaderKind();
    testProbeRejectsZeroSizedFrames();
    testProbeReadsTheLargestEncodableSize();
    testProbeStopsAtEndOfImage();
    testProbeRejectsASegmentThatRunsOffTheEnd();
    testProbeRejectsATruncatedFrameHeader();
    testProbeFindsTheFrameAfterManySegments();
    testProbeSurvivesRandomGarbage();
    testSequentialAccessIsMappedToo();
    testTheMappingOutlivesTheDescriptor();
    testAOneByteFileIsMapped();
    testAnEmptyPipeIsRejected();
    testAClosedDescriptorIsRejected();
    testColorScienceExposure();
    testColorScienceNormalizesBayerValues();
    testColorScienceGammaMatchesTheKotlinReference();
    testColorScienceToneMapping();
    testRawMetadataDefaultsAreNeutral();
    if (gFailures == 0) std::puts("native tests: all passed");
    return gFailures == 0 ? 0 : 1;
}
