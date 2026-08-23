#include <jni.h>
#include <string>
#include <android/log.h>
#include <android/bitmap.h>
#include <android/native_window_jni.h>
#include <android/imagedecoder.h>
#include <dlfcn.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include "libraw/libraw.h"
#include "FastGpuEngine.h"

#define LOG_TAG "NativeLibRaw"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static jbyteArray extractJpegThumbnail(JNIEnv *env, LibRaw &raw) {
    if (raw.unpack_thumb() != LIBRAW_SUCCESS) return nullptr;
    libraw_processed_image_t *img = raw.dcraw_make_mem_thumb();
    if (!img) return nullptr;

    jbyteArray result = nullptr;
    if (img->type == LIBRAW_IMAGE_JPEG && img->data_size > 0) {
        result = env->NewByteArray(static_cast<jsize>(img->data_size));
        if (result) {
            env->SetByteArrayRegion(
                    result,
                    0,
                    static_cast<jsize>(img->data_size),
                    reinterpret_cast<jbyte *>(img->data));
        }
    }
    LibRaw::dcraw_clear_mem(img);
    return result;
}

static jobject decodeJpegDirect(JNIEnv *env, const void *data, size_t size, int targetMax) {
    using CreateFn = int (*)(const void *, size_t, AImageDecoder **);
    using DeleteFn = void (*)(AImageDecoder *);
    using HeaderFn = const AImageDecoderHeaderInfo *(*)(const AImageDecoder *);
    using DimensionFn = int32_t (*)(const AImageDecoderHeaderInfo *);
    using SetFormatFn = int (*)(AImageDecoder *, int32_t);
    using SetSizeFn = int (*)(AImageDecoder *, int32_t, int32_t);
    using StrideFn = size_t (*)(AImageDecoder *);
    using DecodeFn = int (*)(AImageDecoder *, void *, size_t, size_t);
    void *androidLib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    if (!androidLib) return nullptr;
    auto create = reinterpret_cast<CreateFn>(dlsym(androidLib, "AImageDecoder_createFromBuffer"));
    auto destroy = reinterpret_cast<DeleteFn>(dlsym(androidLib, "AImageDecoder_delete"));
    auto header = reinterpret_cast<HeaderFn>(dlsym(androidLib, "AImageDecoder_getHeaderInfo"));
    auto getWidth = reinterpret_cast<DimensionFn>(dlsym(androidLib, "AImageDecoderHeaderInfo_getWidth"));
    auto getHeight = reinterpret_cast<DimensionFn>(dlsym(androidLib, "AImageDecoderHeaderInfo_getHeight"));
    auto setFormat = reinterpret_cast<SetFormatFn>(dlsym(androidLib, "AImageDecoder_setAndroidBitmapFormat"));
    auto setSize = reinterpret_cast<SetSizeFn>(dlsym(androidLib, "AImageDecoder_setTargetSize"));
    auto minStride = reinterpret_cast<StrideFn>(dlsym(androidLib, "AImageDecoder_getMinimumStride"));
    auto decode = reinterpret_cast<DecodeFn>(dlsym(androidLib, "AImageDecoder_decodeImage"));
    if (!create || !destroy || !header || !getWidth || !getHeight || !setFormat || !setSize || !minStride || !decode) {
        dlclose(androidLib);
        return nullptr;
    }

    AImageDecoder *decoder = nullptr;
    if (create(data, size, &decoder) != ANDROID_IMAGE_DECODER_SUCCESS || !decoder) {
        dlclose(androidLib);
        return nullptr;
    }
    const AImageDecoderHeaderInfo *info = header(decoder);
    int width = getWidth(info);
    int height = getHeight(info);
    int largest = width > height ? width : height;
    if (targetMax > 0 && largest > targetMax) {
        width = width * targetMax / largest;
        height = height * targetMax / largest;
        setSize(decoder, width, height);
    }
    setFormat(decoder, ANDROID_BITMAP_FORMAT_RGBA_8888);

    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argbField = env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject config = env->GetStaticObjectField(configClass, argbField);
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jmethodID createBitmap = env->GetStaticMethodID(
            bitmapClass, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject bitmap = env->CallStaticObjectMethod(bitmapClass, createBitmap, width, height, config);
    void *pixels = nullptr;
    AndroidBitmapInfo bitmapInfo{};
    bool ok = bitmap && AndroidBitmap_getInfo(env, bitmap, &bitmapInfo) == ANDROID_BITMAP_RESULT_SUCCESS &&
              AndroidBitmap_lockPixels(env, bitmap, &pixels) == ANDROID_BITMAP_RESULT_SUCCESS;
    if (ok) {
        size_t required = bitmapInfo.stride * static_cast<size_t>(height - 1) + minStride(decoder);
        ok = decode(decoder, pixels, bitmapInfo.stride, required) == ANDROID_IMAGE_DECODER_SUCCESS;
        AndroidBitmap_unlockPixels(env, bitmap);
    }
    destroy(decoder);
    dlclose(androidLib);
    return ok ? bitmap : nullptr;
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_yashikota_omaigenzo_LibRawBridge_getLibRawVersion(JNIEnv *env, jobject thiz) {
    return env->NewStringUTF(LibRaw::version());
}

JNIEXPORT jstring JNICALL
Java_com_yashikota_omaigenzo_LibRawBridge_getMetadata(JNIEnv *env, jobject thiz, jstring file_path) {
    const char *path = env->GetStringUTFChars(file_path, nullptr);
    LibRaw raw;

    int ret = raw.open_file(path);
    if (ret != LIBRAW_SUCCESS) {
        LOGE("Failed to open file: %s (error code: %d)", path, ret);
        env->ReleaseStringUTFChars(file_path, path);
        return env->NewStringUTF("{}");
    }

    std::string make = raw.imgdata.idata.make;
    std::string model = raw.imgdata.idata.model;
    float iso = raw.imgdata.other.iso_speed;
    float shutter = raw.imgdata.other.shutter;
    float aperture = raw.imgdata.other.aperture;
    float focal = raw.imgdata.other.focal_len;
    int width = raw.imgdata.sizes.width;
    int height = raw.imgdata.sizes.height;
    int rawWidth = raw.imgdata.sizes.raw_width;
    int rawHeight = raw.imgdata.sizes.raw_height;
    int flip = raw.imgdata.sizes.flip;

    raw.recycle();
    env->ReleaseStringUTFChars(file_path, path);

    char jsonBuf[512];
    snprintf(jsonBuf, sizeof(jsonBuf),
             "{\"make\":\"%s\",\"model\":\"%s\",\"iso\":%.1f,\"shutter\":%.5f,\"aperture\":%.1f,\"focal\":%.1f,\"width\":%d,\"height\":%d,\"rawWidth\":%d,\"rawHeight\":%d,\"flip\":%d}",
             make.c_str(), model.c_str(), iso, shutter, aperture, focal, width, height, rawWidth, rawHeight, flip);

    return env->NewStringUTF(jsonBuf);
}

JNIEXPORT jbyteArray JNICALL
Java_com_yashikota_omaigenzo_LibRawBridge_decodeThumbnail(JNIEnv *env, jobject thiz, jstring file_path) {
    const char *path = env->GetStringUTFChars(file_path, nullptr);
    LibRaw raw;

    if (raw.open_file(path) != LIBRAW_SUCCESS) {
        env->ReleaseStringUTFChars(file_path, path);
        return nullptr;
    }

    jbyteArray byteArray = extractJpegThumbnail(env, raw);
    raw.recycle();
    env->ReleaseStringUTFChars(file_path, path);

    return byteArray;
}

JNIEXPORT jbyteArray JNICALL
Java_com_yashikota_omaigenzo_LibRawBridge_decodeThumbnailFromFd(JNIEnv *env, jobject thiz, jint fd) {
    if (fd < 0) return nullptr;
    struct stat statBuf{};
    if (fstat(fd, &statBuf) != 0 || statBuf.st_size <= 0) return nullptr;

    void *mapped = mmap(nullptr, statBuf.st_size, PROT_READ, MAP_SHARED, fd, 0);
    if (mapped == MAP_FAILED) return nullptr;

    LibRaw raw;
    jbyteArray result = nullptr;
    if (raw.open_buffer(mapped, static_cast<size_t>(statBuf.st_size)) == LIBRAW_SUCCESS) {
        result = extractJpegThumbnail(env, raw);
    }
    raw.recycle();
    munmap(mapped, static_cast<size_t>(statBuf.st_size));
    return result;
}

JNIEXPORT jobject JNICALL
Java_com_yashikota_omaigenzo_LibRawBridge_decodeThumbnailBitmapFromFd(
        JNIEnv *env, jobject thiz, jint fd, jint targetMaxDimension) {
    if (fd < 0) return nullptr;
    struct stat statBuf{};
    if (fstat(fd, &statBuf) != 0 || statBuf.st_size <= 0) return nullptr;
    void *mapped = mmap(nullptr, statBuf.st_size, PROT_READ, MAP_SHARED, fd, 0);
    if (mapped == MAP_FAILED) return nullptr;

    LibRaw raw;
    jobject bitmap = nullptr;
    if (raw.open_buffer(mapped, static_cast<size_t>(statBuf.st_size)) == LIBRAW_SUCCESS &&
        raw.unpack_thumb() == LIBRAW_SUCCESS) {
        libraw_processed_image_t *img = raw.dcraw_make_mem_thumb();
        if (img) {
            if (img->type == LIBRAW_IMAGE_JPEG && img->data_size > 0) {
                bitmap = decodeJpegDirect(env, img->data, img->data_size, targetMaxDimension);
            }
            LibRaw::dcraw_clear_mem(img);
        }
    }
    raw.recycle();
    munmap(mapped, static_cast<size_t>(statBuf.st_size));
    return bitmap;
}

JNIEXPORT jobject JNICALL
Java_com_yashikota_omaigenzo_LibRawBridge_decodeFullRaw(
        JNIEnv *env,
        jobject thiz,
        jstring file_path,
        jboolean half_size
) {
    const char *path = env->GetStringUTFChars(file_path, nullptr);
    LibRaw raw;

    if (raw.open_file(path) != LIBRAW_SUCCESS) {
        LOGE("Failed to open file in decodeFullRaw");
        env->ReleaseStringUTFChars(file_path, path);
        return nullptr;
    }

    if (raw.unpack() != LIBRAW_SUCCESS) {
        LOGE("Failed to unpack RAW file");
        raw.recycle();
        env->ReleaseStringUTFChars(file_path, path);
        return nullptr;
    }

    raw.imgdata.params.half_size = half_size ? 1 : 0;
    raw.imgdata.params.output_bps = 8;
    raw.imgdata.params.use_camera_wb = 1;
    // Set 0 to get raw sensor layout and let Kotlin/Android Matrix handle exact EXIF rotation for both thumbnails and RAW
    raw.imgdata.params.user_flip = 0;

    if (raw.dcraw_process() != LIBRAW_SUCCESS) {
        LOGE("Failed to process RAW");
        raw.recycle();
        env->ReleaseStringUTFChars(file_path, path);
        return nullptr;
    }

    libraw_processed_image_t *img = raw.dcraw_make_mem_image();
    if (!img || img->type != LIBRAW_IMAGE_BITMAP) {
        LOGE("Failed to make mem image");
        if (img) LibRaw::dcraw_clear_mem(img);
        raw.recycle();
        env->ReleaseStringUTFChars(file_path, path);
        return nullptr;
    }

    int width = img->width;
    int height = img->height;
    int colors = img->colors;

    LOGI("Decoded RAW image dimensions: %dx%d, colors: %d", width, height, colors);

    jclass bitmapConfigClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb8888Field = env->GetStaticFieldID(bitmapConfigClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject argb8888Config = env->GetStaticObjectField(bitmapConfigClass, argb8888Field);

    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jmethodID createBitmapMethod = env->GetStaticMethodID(
            bitmapClass,
            "createBitmap",
            "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;"
    );

    jobject bitmap = env->CallStaticObjectMethod(bitmapClass, createBitmapMethod, width, height, argb8888Config);

    void *bitmapPixels;
    if (AndroidBitmap_lockPixels(env, bitmap, &bitmapPixels) < 0) {
        LOGE("AndroidBitmap_lockPixels failed");
        LibRaw::dcraw_clear_mem(img);
        raw.recycle();
        env->ReleaseStringUTFChars(file_path, path);
        return nullptr;
    }

    uint32_t *dst = static_cast<uint32_t *>(bitmapPixels);
    const uint8_t *src = img->data;

    for (int i = 0; i < width * height; i++) {
        uint8_t r = src[i * 3 + 0];
        uint8_t g = src[i * 3 + 1];
        uint8_t b = src[i * 3 + 2];

        // Format for Android Bitmap ARGB_8888 (ABGR in memory on little-endian ARM/x86)
        dst[i] = (0xFFu << 24) | ((uint32_t)b << 16) | ((uint32_t)g << 8) | (uint32_t)r;
    }

    AndroidBitmap_unlockPixels(env, bitmap);

    LibRaw::dcraw_clear_mem(img);
    raw.recycle();
    env->ReleaseStringUTFChars(file_path, path);

    return bitmap;
}

// -----------------------------------------------------------------------------
// FastGpuBridge JNI Bindings (GPU-First 120 FPS Rendering)
// -----------------------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_yashikota_omaigenzo_FastGpuBridge_createEngine(JNIEnv *env, jobject thiz, jobject surface) {
    if (!surface) return 0;
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (!window) return 0;

    auto *engine = new omaigenzo::FastGpuEngine();
    if (!engine->init(window)) {
        delete engine;
        return 0;
    }
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_yashikota_omaigenzo_FastGpuBridge_destroyEngine(JNIEnv *env, jobject thiz, jlong engineHandle) {
    if (engineHandle != 0) {
        auto *engine = reinterpret_cast<omaigenzo::FastGpuEngine*>(engineHandle);
        delete engine;
    }
}

JNIEXPORT void JNICALL
Java_com_yashikota_omaigenzo_FastGpuBridge_resize(JNIEnv *env, jobject thiz, jlong engineHandle, jint width, jint height) {
    if (engineHandle != 0) {
        reinterpret_cast<omaigenzo::FastGpuEngine*>(engineHandle)->resize(width, height);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_yashikota_omaigenzo_FastGpuBridge_loadPhotoFromPath(JNIEnv *env, jobject thiz, jlong engineHandle, jstring filePath, jint slotIndex) {
    if (engineHandle != 0 && filePath != nullptr) {
        const char *path = env->GetStringUTFChars(filePath, nullptr);
        bool success = reinterpret_cast<omaigenzo::FastGpuEngine*>(engineHandle)->loadPhotoFromPath(path, slotIndex);
        env->ReleaseStringUTFChars(filePath, path);
        return success ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_yashikota_omaigenzo_FastGpuBridge_loadPhotoFromFd(JNIEnv *env, jobject thiz, jlong engineHandle, jint fd, jint slotIndex) {
    if (engineHandle != 0) {
        bool success = reinterpret_cast<omaigenzo::FastGpuEngine*>(engineHandle)->loadPhotoFromFd(fd, slotIndex);
        return success ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_yashikota_omaigenzo_FastGpuBridge_setActiveSlot(JNIEnv *env, jobject thiz, jlong engineHandle, jint slotIndex) {
    if (engineHandle != 0) {
        reinterpret_cast<omaigenzo::FastGpuEngine*>(engineHandle)->setActiveSlot(slotIndex);
    }
}

JNIEXPORT void JNICALL
Java_com_yashikota_omaigenzo_FastGpuBridge_updateTransform(JNIEnv *env, jobject thiz, jlong engineHandle, jfloat scale, jfloat panX, jfloat panY) {
    if (engineHandle != 0) {
        reinterpret_cast<omaigenzo::FastGpuEngine*>(engineHandle)->setTransform(scale, panX, panY);
    }
}

JNIEXPORT void JNICALL
Java_com_yashikota_omaigenzo_FastGpuBridge_updateExposure(JNIEnv *env, jobject thiz, jlong engineHandle, jfloat exposureEV) {
    if (engineHandle != 0) {
        reinterpret_cast<omaigenzo::FastGpuEngine*>(engineHandle)->setExposure(exposureEV);
    }
}

JNIEXPORT void JNICALL
Java_com_yashikota_omaigenzo_FastGpuBridge_renderFrame(JNIEnv *env, jobject thiz, jlong engineHandle) {
    if (engineHandle != 0) {
        reinterpret_cast<omaigenzo::FastGpuEngine*>(engineHandle)->render();
    }
}

}

