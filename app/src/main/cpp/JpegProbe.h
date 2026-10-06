#ifndef OMAI_JPEG_PROBE_H
#define OMAI_JPEG_PROBE_H

#include <cstddef>
#include <cstdint>

namespace omaigenzo {

// Validates the SOI marker and reads the real pixel size from the first SOF segment without
// decoding anything. LibRaw's thumbnail width/height are often 0 or stale, and a bogus offset
// inside a RAW must never reach the image decoder.
// APPn segments (EXIF, which can carry its own nested thumbnail) are skipped by length.
inline bool probeJpegSize(const uint8_t *p, size_t len, int *width, int *height) {
    if (len < 4 || p[0] != 0xFF || p[1] != 0xD8) return false;
    size_t i = 2;
    while (i + 4 <= len) {
        if (p[i] != 0xFF) {
            i++;
            continue;
        }
        const uint8_t marker = p[i + 1];
        if (marker == 0xFF) {  // fill byte
            i++;
            continue;
        }
        if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) {  // standalone markers
            i += 2;
            continue;
        }
        if (marker == 0xD9 || marker == 0xDA) return false;  // EOI / SOS before any SOF

        const size_t segmentLength = (static_cast<size_t>(p[i + 2]) << 8) | p[i + 3];
        if (segmentLength < 2) return false;

        const bool isSof = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
        if (isSof) {
            if (i + 9 > len) return false;
            *height = (p[i + 5] << 8) | p[i + 6];
            *width = (p[i + 7] << 8) | p[i + 8];
            return *width > 0 && *height > 0;
        }
        i += 2 + segmentLength;
    }
    return false;
}

} // namespace omaigenzo

#endif // OMAI_JPEG_PROBE_H
