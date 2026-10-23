#ifndef OMAI_RGB_PACK_H
#define OMAI_RGB_PACK_H

#include <cstddef>
#include <cstdint>

namespace omaigenzo {

// Same rule as PreviewSizing.sampleSize on the Kotlin side: the smallest power of two that brings
// the long edge to or below [target]. 0 (or less) means "no limit".
inline int sampleFactor(int width, int height, int target) {
    if (target <= 0 || width <= 0 || height <= 0) return 1;
    const int longEdge = width > height ? width : height;
    int factor = 1;
    while (longEdge / factor > target) factor *= 2;
    return factor;
}

inline int scaledEdge(int edge, int factor) { return edge / factor > 0 ? edge / factor : 1; }

namespace detail {

// kFactor == 0 means "use [runtimeFactor]".
template <int kFactor>
inline void packBlocks(const uint8_t *src, size_t srcStride, int outWidth, int outHeight, uint8_t *dst, size_t dstStride,
                       int runtimeFactor = kFactor) {
    const int factor = kFactor > 0 ? kFactor : runtimeFactor;
    const uint32_t count = static_cast<uint32_t>(factor) * static_cast<uint32_t>(factor);
    const uint32_t half = count / 2;
    for (int y = 0; y < outHeight; y++) {
        auto *d = reinterpret_cast<uint32_t *>(dst + static_cast<size_t>(y) * dstStride);
        const uint8_t *rowBase = src + static_cast<size_t>(y) * factor * srcStride;
        for (int x = 0; x < outWidth; x++) {
            uint32_t r = 0, g = 0, b = 0;
            const uint8_t *block = rowBase + static_cast<size_t>(x) * factor * 3;
            for (int dy = 0; dy < factor; dy++) {
                const uint8_t *s = block + static_cast<size_t>(dy) * srcStride;
                for (int dx = 0; dx < factor; dx++, s += 3) {
                    r += s[0];
                    g += s[1];
                    b += s[2];
                }
            }
            r = (r + half) / count;
            g = (g + half) / count;
            b = (b + half) / count;
            d[x] = 0xFF000000u | (b << 16) | (g << 8) | r;
        }
    }
}

}  // namespace detail

// Packs 8-bit RGB into Android ARGB_8888 (ABGR in memory on little-endian ARM/x86), averaging
// [factor]x[factor] blocks on the way. LibRaw's reduced RAW is still 2-3x the size of a full-screen
// preview, so this keeps the fallback bitmap inside the same memory bucket as an embedded JPEG
// instead of evicting several cached previews (or the whole thumbnail cache) at once.
inline void packRgbToArgb(const uint8_t *src, int width, int height, int factor, uint8_t *dst, size_t dstStride) {
    const int outWidth = scaledEdge(width, factor);
    const int outHeight = scaledEdge(height, factor);
    const size_t srcStride = static_cast<size_t>(width) * 3;
    if (factor <= 1) {
        for (int y = 0; y < outHeight; y++) {
            const uint8_t *s = src + static_cast<size_t>(y) * srcStride;
            auto *d = reinterpret_cast<uint32_t *>(dst + static_cast<size_t>(y) * dstStride);
            for (int x = 0; x < outWidth; x++, s += 3) {
                d[x] = 0xFF000000u | (static_cast<uint32_t>(s[2]) << 16) | (static_cast<uint32_t>(s[1]) << 8) | s[0];
            }
        }
        return;
    }
    // Only full blocks are averaged; edge pixels that do not fill a block are dropped, which is
    // what a sampled decoder does as well. The usual factors get a compile-time block size so the
    // inner loops unroll (a runtime factor made the 2x path twice as slow as the plain copy).
    switch (factor) {
        case 2: detail::packBlocks<2>(src, srcStride, outWidth, outHeight, dst, dstStride); break;
        case 4: detail::packBlocks<4>(src, srcStride, outWidth, outHeight, dst, dstStride); break;
        case 8: detail::packBlocks<8>(src, srcStride, outWidth, outHeight, dst, dstStride); break;
        default: detail::packBlocks<0>(src, srcStride, outWidth, outHeight, dst, dstStride, factor); break;
    }
}

} // namespace omaigenzo

#endif // OMAI_RGB_PACK_H
