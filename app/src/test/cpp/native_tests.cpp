// Host-side tests for the pure C++ pieces of the native layer (no Android, no LibRaw).
//   g++ -std=c++17 -O2 -I app/src/main/cpp app/src/test/cpp/native_tests.cpp -o native_tests && ./native_tests
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <unistd.h>
#include <vector>

#include "JpegProbe.h"
#include "RawFileView.h"
#include "RgbPack.h"

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

static void testSampleFactorMatchesKotlinPreviewSizing() {
    CHECK(sampleFactor(2817, 1876, 2048) == 2);  // half-size 21 MP RAW -> full-screen preview
    CHECK(sampleFactor(2817, 1876, 512) == 8);   // the same RAW as a gallery thumbnail
    CHECK(sampleFactor(2048, 1536, 2048) == 1);  // already fits
    CHECK(sampleFactor(1876, 2817, 2048) == 2);  // portrait uses the long edge too
    CHECK(sampleFactor(4000, 3000, 0) == 1);     // 0 = full development, never scaled
    CHECK(sampleFactor(0, 0, 2048) == 1);
}

static void testPackKeepsChannelOrderForAndroidArgb() {
    const uint8_t rgb[] = {10, 20, 30, 40, 50, 60};
    uint32_t out[2] = {};
    packRgbToArgb(rgb, 2, 1, 1, reinterpret_cast<uint8_t *>(out), sizeof(out));
    CHECK(out[0] == 0xFF1E140Au);  // A=FF, B=30, G=20, R=10 in a little-endian word
    CHECK(out[1] == 0xFF3C3228u);
}

static void testPackAveragesBlocksAndDropsPartialEdges() {
    // 5x3 RGB, factor 2 -> 2x1 output; the fifth column and the third row do not fill a block.
    std::vector<uint8_t> rgb(5 * 3 * 3, 0);
    auto px = [&](int x, int y) { return &rgb[(static_cast<size_t>(y) * 5 + x) * 3]; };
    const uint8_t block[4] = {0, 100, 200, 255};
    for (int i = 0; i < 4; i++) {
        uint8_t *p = px(i % 2, i / 2);
        p[0] = block[i];
        p[1] = 40;
        p[2] = 255;
    }
    for (int y = 0; y < 2; y++)
        for (int x = 2; x < 4; x++) px(x, y)[0] = 8;
    px(4, 0)[0] = 255;  // dropped edge column
    px(0, 2)[0] = 255;  // dropped edge row
    uint32_t out[3] = {0, 0, 0xDEADBEEFu};
    packRgbToArgb(rgb.data(), 5, 3, 2, reinterpret_cast<uint8_t *>(out), 2 * sizeof(uint32_t));
    CHECK((out[0] & 0xFF) == 139);           // (0 + 100 + 200 + 255) / 4, rounded
    CHECK(((out[0] >> 8) & 0xFF) == 40);
    CHECK(((out[0] >> 16) & 0xFF) == 255);
    CHECK((out[0] >> 24) == 0xFF);
    CHECK((out[1] & 0xFF) == 8);
    CHECK(out[2] == 0xDEADBEEFu);            // never writes past the output row
}

static void testPackHonoursTheBitmapStride() {
    const uint8_t rgb[] = {1, 2, 3, 4, 5, 6};  // 1x2
    uint32_t out[4] = {0, 0xAAAAAAAAu, 0, 0xAAAAAAAAu};
    packRgbToArgb(rgb, 1, 2, 1, reinterpret_cast<uint8_t *>(out), 2 * sizeof(uint32_t));
    CHECK(out[0] == 0xFF030201u);
    CHECK(out[1] == 0xAAAAAAAAu);  // row padding untouched
    CHECK(out[2] == 0xFF060504u);
}

int main() {
    testSampleFactorMatchesKotlinPreviewSizing();
    testPackKeepsChannelOrderForAndroidArgb();
    testPackAveragesBlocksAndDropsPartialEdges();
    testPackHonoursTheBitmapStride();
    testProbeReadsRealDimensions();
    testProbeSkipsNestedExifThumbnail();
    testProbeAcceptsProgressiveSof2();
    testProbeRejectsGarbageAndTruncation();
    testProbeNeverReadsPastTheBuffer();
    testRegularFilesAreMappedNotCopied();
    testPipesFallBackToBoundedMemory();
    testInvalidAndEmptyFdsAreRejected();
    if (gFailures == 0) std::puts("native tests: all passed");
    return gFailures == 0 ? 0 : 1;
}
