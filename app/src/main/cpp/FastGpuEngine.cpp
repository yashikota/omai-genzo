#include "FastGpuEngine.h"
#include <android/log.h>
#include <cmath>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#define LOG_TAG "FastGpuEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace omaigenzo {

static const char* VERTEX_SHADER_SOURCE = R"glsl(#version 300 es
layout(location = 0) in vec2 a_position;
layout(location = 1) in vec2 a_texCoord;

out vec2 v_texCoord;

void main() {
    gl_Position = vec4(a_position, 0.0, 1.0);
    v_texCoord = a_texCoord;
}
)glsl";

static const char* FRAGMENT_SHADER_SOURCE = R"glsl(#version 300 es
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
uniform int u_cfaPattern;

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
            rgb.r = c0;
            rgb.g = cN * 0.25 + cE * 0.25;
            rgb.b = cD * 0.25;
        } else {
            rgb.r = cE * 0.5;
            rgb.g = c0;
            rgb.b = cN * 0.5;
        }
    } else {
        if (isEvenX == 0) {
            rgb.r = cN * 0.5;
            rgb.g = c0;
            rgb.b = cE * 0.5;
        } else {
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
    return pow(mapped, vec3(1.0 / 2.2));
}

void main() {
    vec2 centeredUv = v_texCoord - vec2(0.5);
    vec2 transformedUv = (centeredUv / max(u_zoomScale, 0.01)) - u_panOffset + vec2(0.5);

    if (transformedUv.x < 0.0 || transformedUv.x > 1.0 || transformedUv.y < 0.0 || transformedUv.y > 1.0) {
        fragColor = vec4(0.04, 0.04, 0.06, 1.0);
        return;
    }

    ivec2 rawPos = ivec2(transformedUv * vec2(u_rawSize));

    vec3 linearRgb = demosaicMHC(rawPos);
    linearRgb *= u_camWb;
    linearRgb *= u_exposure;

    vec3 srgbLinear = clamp(u_colorMatrix * linearRgb, 0.0, 1.0);
    vec3 finalRgb = toneMapACES(srgbLinear);

    fragColor = vec4(finalRgb, 1.0);
}
)glsl";

FastGpuEngine::FastGpuEngine() = default;

FastGpuEngine::~FastGpuEngine() {
    destroy();
}

bool FastGpuEngine::init(ANativeWindow* window) {
    std::lock_guard<std::mutex> lock(mEngineMutex);
    mWindow = window;

    if (!initEGL()) {
        LOGE("Failed to init EGL");
        return false;
    }

    if (!initShaders()) {
        LOGE("Failed to init Shaders");
        return false;
    }

    if (!setupQuad()) {
        LOGE("Failed to setup Quad");
        return false;
    }

    // Allocate persistent texture ring pool
    for (int i = 0; i < RING_SLOTS; i++) {
        glGenTextures(1, &mSlots[i].textureId);
        glBindTexture(GL_TEXTURE_2D, mSlots[i].textureId);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }
    glBindTexture(GL_TEXTURE_2D, 0);

    mIsInitialized = true;
    LOGI("FastGpuEngine successfully initialized with 3-slot texture pool.");
    return true;
}

bool FastGpuEngine::initEGL() {
    mDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (mDisplay == EGL_NO_DISPLAY) return false;

    EGLint major, minor;
    if (!eglInitialize(mDisplay, &major, &minor)) return false;

    const EGLint attribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_BLUE_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_RED_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 0,
        EGL_NONE
    };

    EGLint numConfigs;
    if (!eglChooseConfig(mDisplay, attribs, &mConfig, 1, &numConfigs) || numConfigs <= 0) {
        return false;
    }

    const EGLint contextAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };

    mContext = eglCreateContext(mDisplay, mConfig, EGL_NO_CONTEXT, contextAttribs);
    if (mContext == EGL_NO_CONTEXT) return false;

    mSurface = eglCreateWindowSurface(mDisplay, mConfig, mWindow, nullptr);
    if (mSurface == EGL_NO_SURFACE) return false;

    if (!eglMakeCurrent(mDisplay, mSurface, mSurface, mContext)) return false;

    return true;
}

bool FastGpuEngine::compileShader(GLenum type, const char* source, GLuint& shader) {
    shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);

    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint infoLen = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 0) {
            char* buf = new char[infoLen];
            glGetShaderInfoLog(shader, infoLen, nullptr, buf);
            LOGE("Could not compile shader %d: %s", type, buf);
            delete[] buf;
        }
        glDeleteShader(shader);
        shader = 0;
        return false;
    }
    return true;
}

bool FastGpuEngine::initShaders() {
    GLuint vertShader = 0;
    GLuint fragShader = 0;

    if (!compileShader(GL_VERTEX_SHADER, VERTEX_SHADER_SOURCE, vertShader)) return false;
    if (!compileShader(GL_FRAGMENT_SHADER, FRAGMENT_SHADER_SOURCE, fragShader)) {
        glDeleteShader(vertShader);
        return false;
    }

    mProgram = glCreateProgram();
    glAttachShader(mProgram, vertShader);
    glAttachShader(mProgram, fragShader);
    glLinkProgram(mProgram);

    GLint linkStatus = 0;
    glGetProgramiv(mProgram, GL_LINK_STATUS, &linkStatus);
    if (!linkStatus) {
        LOGE("Could not link program");
        glDeleteProgram(mProgram);
        mProgram = 0;
        return false;
    }

    glDeleteShader(vertShader);
    glDeleteShader(fragShader);

    // Cache Uniform Locations
    u_bayerTextureLoc = glGetUniformLocation(mProgram, "u_bayerTexture");
    u_rawSizeLoc = glGetUniformLocation(mProgram, "u_rawSize");
    u_blackLevelLoc = glGetUniformLocation(mProgram, "u_blackLevel");
    u_whiteLevelLoc = glGetUniformLocation(mProgram, "u_whiteLevel");
    u_camWbLoc = glGetUniformLocation(mProgram, "u_camWb");
    u_colorMatrixLoc = glGetUniformLocation(mProgram, "u_colorMatrix");
    u_exposureLoc = glGetUniformLocation(mProgram, "u_exposure");
    u_panOffsetLoc = glGetUniformLocation(mProgram, "u_panOffset");
    u_zoomScaleLoc = glGetUniformLocation(mProgram, "u_zoomScale");
    u_cfaPatternLoc = glGetUniformLocation(mProgram, "u_cfaPattern");

    return true;
}

bool FastGpuEngine::setupQuad() {
    const float quadVertices[] = {
        // Position (x, y),  TexCoord (u, v)
        -1.0f,  1.0f,   0.0f, 0.0f,
        -1.0f, -1.0f,   0.0f, 1.0f,
         1.0f,  1.0f,   1.0f, 0.0f,
         1.0f, -1.0f,   1.0f, 1.0f,
    };

    glGenVertexArrays(1, &mVAO);
    glBindVertexArray(mVAO);

    glGenBuffers(1, &mVBO);
    glBindBuffer(GL_ARRAY_BUFFER, mVBO);
    glBufferData(GL_ARRAY_BUFFER, sizeof(quadVertices), quadVertices, GL_STATIC_DRAW);

    // Position attribute
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)0);

    // TexCoord attribute
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)(2 * sizeof(float)));

    glBindVertexArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    return true;
}

void FastGpuEngine::resize(int width, int height) {
    std::lock_guard<std::mutex> lock(mEngineMutex);
    mViewportWidth = width;
    mViewportHeight = height;
    if (mIsInitialized) {
        glViewport(0, 0, width, height);
    }
}

bool FastGpuEngine::loadPhotoFromPath(const char* path, int slotIndex) {
    if (slotIndex < 0 || slotIndex >= RING_SLOTS) return false;

    std::lock_guard<std::mutex> lock(mEngineMutex);
    LibRaw raw;
    if (raw.open_file(path) != LIBRAW_SUCCESS) {
        LOGE("Failed to open RAW file: %s", path);
        return false;
    }

    if (raw.unpack() != LIBRAW_SUCCESS) {
        LOGE("Failed to unpack RAW file: %s", path);
        raw.recycle();
        return false;
    }

    int rawWidth = raw.imgdata.sizes.raw_width;
    int rawHeight = raw.imgdata.sizes.raw_height;
    if (rawWidth <= 0 || rawHeight <= 0) {
        rawWidth = raw.imgdata.sizes.width;
        rawHeight = raw.imgdata.sizes.height;
    }

    uint16_t* bayerData = raw.imgdata.rawdata.raw_image;
    if (!bayerData && raw.imgdata.image) {
        bayerData = (uint16_t*)raw.imgdata.image;
    }

    if (!bayerData) {
        LOGE("No raw bayer data available in unpacked RAW");
        raw.recycle();
        return false;
    }

    TextureSlot& slot = mSlots[slotIndex];
    slot.rawWidth = rawWidth;
    slot.rawHeight = rawHeight;
    slot.filePath = path;

    // Extract metadata
    slot.metadata.blackLevel = (float)raw.imgdata.color.cblack[0];
    if (slot.metadata.blackLevel <= 0.0f) slot.metadata.blackLevel = (float)raw.imgdata.color.black;
    slot.metadata.whiteLevel = (float)raw.imgdata.color.maximum;
    if (slot.metadata.whiteLevel <= slot.metadata.blackLevel) slot.metadata.whiteLevel = 16383.0f;

    // Camera WB
    slot.metadata.camWb[0] = raw.imgdata.color.cam_mul[0];
    slot.metadata.camWb[1] = raw.imgdata.color.cam_mul[1];
    slot.metadata.camWb[2] = raw.imgdata.color.cam_mul[2];
    slot.metadata.camWb[3] = raw.imgdata.color.cam_mul[3];

    // Normalize green multiplier
    if (slot.metadata.camWb[1] > 0.0f) {
        slot.metadata.camWb[0] /= slot.metadata.camWb[1];
        slot.metadata.camWb[2] /= slot.metadata.camWb[1];
        slot.metadata.camWb[1] = 1.0f;
    } else {
        slot.metadata.camWb[0] = 1.8f;
        slot.metadata.camWb[1] = 1.0f;
        slot.metadata.camWb[2] = 1.5f;
    }

    // Camera to sRGB Matrix (rgb_cam)
    for (int r = 0; r < 3; r++) {
        for (int c = 0; c < 3; c++) {
            slot.metadata.colorMatrix[r * 3 + c] = raw.imgdata.color.rgb_cam[r][c];
        }
    }

    // Upload to GPU Texture
    if (mIsInitialized && eglMakeCurrent(mDisplay, mSurface, mSurface, mContext)) {
        glBindTexture(GL_TEXTURE_2D, slot.textureId);
        glTexImage2D(
            GL_TEXTURE_2D, 0, GL_R16UI,
            rawWidth, rawHeight, 0,
            GL_RED_INTEGER, GL_UNSIGNED_SHORT,
            bayerData
        );
        glBindTexture(GL_TEXTURE_2D, 0);
        slot.isLoaded = true;
    }

    raw.recycle();
    LOGI("Uploaded RAW %s to Texture Slot %d (%dx%d)", path, slotIndex, rawWidth, rawHeight);
    return true;
}

bool FastGpuEngine::loadPhotoFromFd(int fd, int slotIndex) {
    if (slotIndex < 0 || slotIndex >= RING_SLOTS || fd < 0) return false;

    std::lock_guard<std::mutex> lock(mEngineMutex);
    struct stat sb;
    if (fstat(fd, &sb) != 0 || sb.st_size <= 0) {
        LOGE("Failed to stat fd: %d", fd);
        return false;
    }

    void* mapped = mmap(nullptr, sb.st_size, PROT_READ, MAP_SHARED, fd, 0);
    if (mapped == MAP_FAILED) {
        LOGE("Failed to mmap fd: %d", fd);
        return false;
    }

    LibRaw raw;
    int ret = raw.open_buffer(mapped, sb.st_size);
    if (ret != LIBRAW_SUCCESS) {
        LOGE("Failed to open RAW buffer from mmap fd %d (code: %d)", fd, ret);
        munmap(mapped, sb.st_size);
        return false;
    }

    if (raw.unpack() != LIBRAW_SUCCESS) {
        LOGE("Failed to unpack RAW from mmap fd: %d", fd);
        raw.recycle();
        munmap(mapped, sb.st_size);
        return false;
    }

    int rawWidth = raw.imgdata.sizes.raw_width > 0 ? raw.imgdata.sizes.raw_width : raw.imgdata.sizes.width;
    int rawHeight = raw.imgdata.sizes.raw_height > 0 ? raw.imgdata.sizes.raw_height : raw.imgdata.sizes.height;

    uint16_t* bayerData = raw.imgdata.rawdata.raw_image;
    if (!bayerData && raw.imgdata.image) {
        bayerData = (uint16_t*)raw.imgdata.image;
    }

    if (!bayerData) {
        raw.recycle();
        munmap(mapped, sb.st_size);
        return false;
    }

    TextureSlot& slot = mSlots[slotIndex];
    slot.rawWidth = rawWidth;
    slot.rawHeight = rawHeight;

    slot.metadata.blackLevel = (float)raw.imgdata.color.cblack[0];
    if (slot.metadata.blackLevel <= 0.0f) slot.metadata.blackLevel = (float)raw.imgdata.color.black;
    slot.metadata.whiteLevel = (float)raw.imgdata.color.maximum;
    if (slot.metadata.whiteLevel <= slot.metadata.blackLevel) slot.metadata.whiteLevel = 16383.0f;

    slot.metadata.camWb[0] = raw.imgdata.color.cam_mul[0] / (raw.imgdata.color.cam_mul[1] > 0 ? raw.imgdata.color.cam_mul[1] : 1.0f);
    slot.metadata.camWb[1] = 1.0f;
    slot.metadata.camWb[2] = raw.imgdata.color.cam_mul[2] / (raw.imgdata.color.cam_mul[1] > 0 ? raw.imgdata.color.cam_mul[1] : 1.0f);

    for (int r = 0; r < 3; r++) {
        for (int c = 0; c < 3; c++) {
            slot.metadata.colorMatrix[r * 3 + c] = raw.imgdata.color.rgb_cam[r][c];
        }
    }

    if (mIsInitialized && eglMakeCurrent(mDisplay, mSurface, mSurface, mContext)) {
        glBindTexture(GL_TEXTURE_2D, slot.textureId);
        glTexImage2D(
            GL_TEXTURE_2D, 0, GL_R16UI,
            rawWidth, rawHeight, 0,
            GL_RED_INTEGER, GL_UNSIGNED_SHORT,
            bayerData
        );
        glBindTexture(GL_TEXTURE_2D, 0);
        slot.isLoaded = true;
    }

    raw.recycle();
    munmap(mapped, sb.st_size);
    LOGI("Successfully loaded RAW from fd %d to slot %d via zero-copy mmap (%dx%d)", fd, slotIndex, rawWidth, rawHeight);
    return true;
}

void FastGpuEngine::setActiveSlot(int slotIndex) {
    std::lock_guard<std::mutex> lock(mEngineMutex);
    if (slotIndex >= 0 && slotIndex < RING_SLOTS) {
        mActiveSlot = slotIndex;
    }
}

void FastGpuEngine::setTransform(float scale, float panX, float panY) {
    std::lock_guard<std::mutex> lock(mEngineMutex);
    mZoomScale = scale;
    mPanX = panX;
    mPanY = panY;
}

void FastGpuEngine::setExposure(float exposureEV) {
    std::lock_guard<std::mutex> lock(mEngineMutex);
    mExposureEV = exposureEV;
}

void FastGpuEngine::render() {
    std::lock_guard<std::mutex> lock(mEngineMutex);
    if (!mIsInitialized || mDisplay == EGL_NO_DISPLAY || mSurface == EGL_NO_SURFACE) return;

    eglMakeCurrent(mDisplay, mSurface, mSurface, mContext);

    glClearColor(0.04f, 0.04f, 0.06f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    const TextureSlot& slot = mSlots[mActiveSlot];
    if (!slot.isLoaded) {
        eglSwapBuffers(mDisplay, mSurface);
        return;
    }

    glUseProgram(mProgram);

    // Bind Bayer Texture
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, slot.textureId);
    glUniform1i(u_bayerTextureLoc, 0);

    // Set Uniforms
    glUniform2i(u_rawSizeLoc, slot.rawWidth, slot.rawHeight);
    glUniform1f(u_blackLevelLoc, slot.metadata.blackLevel);
    glUniform1f(u_whiteLevelLoc, slot.metadata.whiteLevel);
    glUniform3f(u_camWbLoc, slot.metadata.camWb[0], slot.metadata.camWb[1], slot.metadata.camWb[2]);
    glUniformMatrix3fv(u_colorMatrixLoc, 1, GL_FALSE, slot.metadata.colorMatrix);

    float linearExposure = ColorScience::evToLinearMultiplier(mExposureEV);
    glUniform1f(u_exposureLoc, linearExposure);

    glUniform1f(u_zoomScaleLoc, mZoomScale);
    glUniform2f(u_panOffsetLoc, mPanX, mPanY);
    glUniform1i(u_cfaPatternLoc, slot.metadata.cfaPattern);

    // Draw Quad
    glBindVertexArray(mVAO);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);

    eglSwapBuffers(mDisplay, mSurface);
}

void FastGpuEngine::destroy() {
    std::lock_guard<std::mutex> lock(mEngineMutex);
    if (!mIsInitialized) return;

    eglMakeCurrent(mDisplay, mSurface, mSurface, mContext);

    for (int i = 0; i < RING_SLOTS; i++) {
        if (mSlots[i].textureId != 0) {
            glDeleteTextures(1, &mSlots[i].textureId);
            mSlots[i].textureId = 0;
            mSlots[i].isLoaded = false;
        }
    }

    if (mVAO) { glDeleteVertexArrays(1, &mVAO); mVAO = 0; }
    if (mVBO) { glDeleteBuffers(1, &mVBO); mVBO = 0; }
    if (mProgram) { glDeleteProgram(mProgram); mProgram = 0; }

    if (mDisplay != EGL_NO_DISPLAY) {
        eglMakeCurrent(mDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (mSurface != EGL_NO_SURFACE) { eglDestroySurface(mDisplay, mSurface); mSurface = EGL_NO_SURFACE; }
        if (mContext != EGL_NO_CONTEXT) { eglDestroyContext(mDisplay, mContext); mContext = EGL_NO_CONTEXT; }
        eglTerminate(mDisplay);
        mDisplay = EGL_NO_DISPLAY;
    }

    if (mWindow) {
        ANativeWindow_release(mWindow);
        mWindow = nullptr;
    }

    mIsInitialized = false;
    LOGI("FastGpuEngine destroyed.");
}

} // namespace omaigenzo
