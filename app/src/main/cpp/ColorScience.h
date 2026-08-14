#ifndef OMAI_COLOR_SCIENCE_H
#define OMAI_COLOR_SCIENCE_H

#include <cmath>
#include <algorithm>
#include <cstdint>

namespace omaigenzo {

struct RawMetadata {
    float blackLevel = 512.0f;
    float whiteLevel = 16383.0f;
    float camWb[4] = {1.0f, 1.0f, 1.0f, 1.0f}; // R, G1, B, G2
    float colorMatrix[9] = {
        1.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 1.0f
    }; // 3x3 matrix row-major
    int rawWidth = 0;
    int rawHeight = 0;
    int cfaPattern = 0; // 0: RGGB, 1: BGGR, 2: GRBG, 3: GBRG
    int flip = 0;
};

class ColorScience {
public:
    static inline float evToLinearMultiplier(float ev) {
        return std::pow(2.0f, ev);
    }

    static inline float normalizeBayer(uint16_t rawVal, float blackLevel, float whiteLevel) {
        if (whiteLevel <= blackLevel) return 0.0f;
        float val = (static_cast<float>(rawVal) - blackLevel) / (whiteLevel - blackLevel);
        return std::clamp(val, 0.0f, 1.0f);
    }

    static inline float srgbGamma(float linearVal) {
        if (linearVal <= 0.0031308f) {
            return linearVal * 12.92f;
        } else {
            return 1.055f * std::pow(linearVal, 1.0f / 2.4f) - 0.055f;
        }
    }

    static inline void acesToneMap(float r, float g, float b, float& outR, float& outG, float& outB) {
        constexpr float a = 2.51f;
        constexpr float bConst = 0.03f;
        constexpr float c = 2.43f;
        constexpr float d = 0.59f;
        constexpr float e = 0.14f;

        auto applyAces = [](float x) -> float {
            float val = std::clamp((x * (a * x + bConst)) / (x * (c * x + d) + e), 0.0f, 1.0f);
            return srgbGamma(val);
        };

        outR = applyAces(r);
        outG = applyAces(g);
        outB = applyAces(b);
    }
};

} // namespace omaigenzo

#endif // OMAI_COLOR_SCIENCE_H
