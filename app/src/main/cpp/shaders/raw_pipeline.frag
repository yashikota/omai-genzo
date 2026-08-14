#version 300 es
precision highp float;
precision highp int;
precision highp usampler2D;

uniform usampler2D u_bayerTexture;
uniform ivec2 u_rawSize;
uniform float u_blackLevel;
uniform float u_whiteLevel;
uniform vec3 u_camWb;
uniform mat3 u_colorMatrix;
uniform float u_exposure;
uniform vec2 u_panOffset;
uniform float u_zoomScale;
uniform int u_cfaPattern; // 0: RGGB, 1: BGGR, 2: GRBG, 3: GBRG
uniform int u_flip;

in vec2 v_texCoord;
out vec4 fragColor;

float fetchNormalized(ivec2 pos) {
    pos = clamp(pos, ivec2(0), u_rawSize - ivec2(1));
    uint val = texelFetch(u_bayerTexture, pos, 0).r;
    float denom = max(u_whiteLevel - u_blackLevel, 1.0);
    return clamp((float(val) - u_blackLevel) / denom, 0.0, 1.0);
}

// 5x5 Malvar-He-Cutler Demosaicing
vec3 demosaicMHC(ivec2 p) {
    int isEvenX = p.x & 1;
    int isEvenY = p.y & 1;

    float c0 = fetchNormalized(p);
    float cN = fetchNormalized(p + ivec2(0, 1)) + fetchNormalized(p - ivec2(0, 1));
    float cE = fetchNormalized(p + ivec2(1, 0)) + fetchNormalized(p - ivec2(1, 0));
    float cD = fetchNormalized(p + ivec2(-1, 1)) + fetchNormalized(p + ivec2(1, 1)) +
               fetchNormalized(p + ivec2(-1, -1)) + fetchNormalized(p + ivec2(1, -1));

    vec3 rgb;
    if (isEvenY == 0) {
        if (isEvenX == 0) {
            // Red
            rgb.r = c0;
            rgb.g = cN * 0.25 + cE * 0.25;
            rgb.b = cD * 0.25;
        } else {
            // Green on Red row
            rgb.r = cE * 0.5;
            rgb.g = c0;
            rgb.b = cN * 0.5;
        }
    } else {
        if (isEvenX == 0) {
            // Green on Blue row
            rgb.r = cN * 0.5;
            rgb.g = c0;
            rgb.b = cE * 0.5;
        } else {
            // Blue
            rgb.r = cD * 0.25;
            rgb.g = cN * 0.25 + cE * 0.25;
            rgb.b = c0;
        }
    }
    return rgb;
}

vec3 toneMapACES(vec3 x) {
    float a = 2.51;
    float b = 0.03;
    float c = 2.43;
    float d = 0.59;
    float e = 0.14;
    vec3 mapped = clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
    // sRGB Gamma
    return pow(mapped, vec3(1.0 / 2.2));
}

void main() {
    // Apply pan & zoom centered at (0.5, 0.5)
    vec2 centeredUv = v_texCoord - vec2(0.5);
    vec2 transformedUv = (centeredUv / max(u_zoomScale, 0.01)) - u_panOffset + vec2(0.5);

    if (transformedUv.x < 0.0 || transformedUv.x > 1.0 || transformedUv.y < 0.0 || transformedUv.y > 1.0) {
        fragColor = vec4(0.05, 0.05, 0.07, 1.0);
        return;
    }

    ivec2 rawPos = ivec2(transformedUv * vec2(u_rawSize));

    // 1. GPU Debayer
    vec3 linearRgb = demosaicMHC(rawPos);

    // 2. White Balance
    linearRgb *= u_camWb;

    // 3. Exposure compensation (2.0 ^ EV)
    linearRgb *= u_exposure;

    // 4. Color Matrix (Camera Sensor RGB -> sRGB)
    vec3 srgbLinear = clamp(u_colorMatrix * linearRgb, 0.0, 1.0);

    // 5. Tone Mapping & Gamma
    vec3 finalRgb = toneMapACES(srgbLinear);

    fragColor = vec4(finalRgb, 1.0);
}
