#ifndef OMAI_FAST_GPU_ENGINE_H
#define OMAI_FAST_GPU_ENGINE_H

#include <android/native_window.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <string>
#include <mutex>
#include "ColorScience.h"
#include "libraw/libraw.h"

namespace omaigenzo {

struct TextureSlot {
    GLuint textureId = 0;
    int rawWidth = 0;
    int rawHeight = 0;
    RawMetadata metadata;
    std::string filePath;
    bool isLoaded = false;
};

class FastGpuEngine {
public:
    FastGpuEngine();
    ~FastGpuEngine();

    bool init(ANativeWindow* window);
    void resize(int width, int height);
    bool loadPhotoFromFd(int fd, int slotIndex);
    bool loadPhotoFromPath(const char* path, int slotIndex);
    void setActiveSlot(int slotIndex);
    void setTransform(float scale, float panX, float panY);
    void setExposure(float exposureEV);
    void render();
    void destroy();

private:
    bool initEGL();
    bool initShaders();
    bool setupQuad();
    bool compileShader(GLenum type, const char* source, GLuint& shader);
    void updateUniforms();

    ANativeWindow* mWindow = nullptr;
    EGLDisplay mDisplay = EGL_NO_DISPLAY;
    EGLSurface mSurface = EGL_NO_SURFACE;
    EGLContext mContext = EGL_NO_CONTEXT;
    EGLConfig mConfig = nullptr;

    int mViewportWidth = 0;
    int mViewportHeight = 0;

    GLuint mProgram = 0;
    GLuint mVAO = 0;
    GLuint mVBO = 0;

    // Uniform locations
    GLint u_bayerTextureLoc = -1;
    GLint u_rawSizeLoc = -1;
    GLint u_blackLevelLoc = -1;
    GLint u_whiteLevelLoc = -1;
    GLint u_camWbLoc = -1;
    GLint u_colorMatrixLoc = -1;
    GLint u_exposureLoc = -1;
    GLint u_panOffsetLoc = -1;
    GLint u_zoomScaleLoc = -1;
    GLint u_cfaPatternLoc = -1;
    GLint u_flipLoc = -1;

    // 3-slot texture ring pool
    static constexpr int RING_SLOTS = 3;
    TextureSlot mSlots[RING_SLOTS];
    int mActiveSlot = 0;

    // Render parameters
    float mZoomScale = 1.0f;
    float mPanX = 0.0f;
    float mPanY = 0.0f;
    float mExposureEV = 0.0f;

    std::mutex mEngineMutex;
    bool mIsInitialized = false;
};

} // namespace omaigenzo

#endif // OMAI_FAST_GPU_ENGINE_H
