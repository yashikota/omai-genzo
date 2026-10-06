#include <jni.h>
#include <string>
#include <vector>
#include <algorithm>
#include <android/log.h>
#include <android/bitmap.h>
#include <android/native_window_jni.h>
#include "libraw/libraw.h"
#include "FastGpuEngine.h"
#include "JpegProbe.h"
#include "RawFileView.h"

#define LOG_TAG "NativeLibRaw"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using omaigenzo::RawFileView;

namespace {

// Resolved once in JNI_OnLoad: FindClass/GetStaticMethodID on every decode is pure overhead.
struct BitmapJni {
    jclass bitmapClass = nullptr;
    jmethodID createBitmap = nullptr;
    jobject argb8888 = nullptr;
};
BitmapJni gBitmap;

// Keeps the mapped RAW (and, for makers without a usable thumbnail list, a private copy of the
// JPEG) alive while Kotlin decodes straight out of it. Released with closeView().
struct PreviewHandle {
    RawFileView view;
    std::vector<uint8_t> ownedJpeg;
};

jobject createArgbBitmap(JNIEnv *env, int width, int height) {
    if (!gBitmap.bitmapClass) return nullptr;
    return env->CallStaticObjectMethod(gBitmap.bitmapClass, gBitmap.createBitmap, width, height, gBitmap.argb8888);
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;

    jclass bitmapLocal = env->FindClass("android/graphics/Bitmap");
    jclass configLocal = env->FindClass("android/graphics/Bitmap$Config");
    if (!bitmapLocal || !configLocal) return JNI_ERR;
    jfieldID argbField = env->GetStaticFieldID(configLocal, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject argbLocal = env->GetStaticObjectField(configLocal, argbField);

    gBitmap.bitmapClass = static_cast<jclass>(env->NewGlobalRef(bitmapLocal));
    gBitmap.createBitmap = env->GetStaticMethodID(
            bitmapLocal, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    gBitmap.argb8888 = env->NewGlobalRef(argbLocal);
    return JNI_VERSION_1_6;
}

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

// Maps the RAW behind [fd] and lists every JPEG embedded in it, without copying or decoding.
// Result layout: [handle, rawFlip, count, (address, length, width, height) * count].
// The addresses point into the mapping, so the caller must closeView(handle) when it is done.
JNIEXPORT jlongArray JNICALL
Java_com_yashikota_omaigenzo_LibRawBridge_openEmbeddedPreviews(JNIEnv *env, jobject thiz, jint fd) {
    auto *handle = new PreviewHandle();
    if (!handle->view.open(fd, RawFileView::Access::Random)) {
        delete handle;
        return nullptr;
    }

    struct Found {
        const uint8_t *address;
        size_t length;
        int width, height;
    };
    std::vector<Found> found;
    int rawFlip = 0;

    {
        LibRaw raw;
        if (raw.open_buffer(handle->view.data(), handle->view.size()) != LIBRAW_SUCCESS) {
            delete handle;
            return nullptr;
        }
        rawFlip = raw.imgdata.sizes.flip;

        const size_t fileSize = handle->view.size();
        const int count = std::min(raw.imgdata.thumbs_list.thumbcount, static_cast<int>(LIBRAW_THUMBNAIL_MAXCOUNT));
        for (int i = 0; i < count; i++) {
            const libraw_thumbnail_item_t &item = raw.imgdata.thumbs_list.thumblist[i];
            if (item.tformat != LIBRAW_INTERNAL_THUMBNAIL_JPEG || item.tlength < 64 || item.toffset < 0) continue;
            const auto offset = static_cast<size_t>(item.toffset);
            if (offset >= fileSize || item.tlength > fileSize - offset) continue;
            const uint8_t *address = handle->view.data() + offset;
            int w = 0, h = 0;
            if (!omaigenzo::probeJpegSize(address, item.tlength, &w, &h)) continue;
            found.push_back({address, item.tlength, w, h});
        }

        if (found.empty() && raw.unpack_thumb() == LIBRAW_SUCCESS &&
            raw.imgdata.thumbnail.tformat == LIBRAW_THUMBNAIL_JPEG && raw.imgdata.thumbnail.thumb &&
            raw.imgdata.thumbnail.tlength >= 64) {
            // Rare: the maker keeps no usable offset table. One bounded copy of that JPEG only.
            const auto *src = reinterpret_cast<const uint8_t *>(raw.imgdata.thumbnail.thumb);
            handle->ownedJpeg.assign(src, src + raw.imgdata.thumbnail.tlength);
            int w = 0, h = 0;
            if (omaigenzo::probeJpegSize(handle->ownedJpeg.data(), handle->ownedJpeg.size(), &w, &h)) {
                found.push_back({handle->ownedJpeg.data(), handle->ownedJpeg.size(), w, h});
            }
        }
        raw.recycle();
    }

    if (found.empty()) {
        delete handle;
        return nullptr;
    }

    const jsize total = static_cast<jsize>(3 + found.size() * 4);
    jlongArray out = env->NewLongArray(total);
    if (!out) {
        delete handle;
        return nullptr;
    }
    std::vector<jlong> values(static_cast<size_t>(total));
    values[0] = reinterpret_cast<jlong>(handle);
    values[1] = rawFlip;
    values[2] = static_cast<jlong>(found.size());
    for (size_t i = 0; i < found.size(); i++) {
        values[3 + i * 4 + 0] = reinterpret_cast<jlong>(found[i].address);
        values[3 + i * 4 + 1] = static_cast<jlong>(found[i].length);
        values[3 + i * 4 + 2] = found[i].width;
        values[3 + i * 4 + 3] = found[i].height;
    }
    env->SetLongArrayRegion(out, 0, total, values.data());
    return out;
}

// Exposes mapped memory to ImageDecoder as a direct ByteBuffer: no copy of the JPEG at all.
JNIEXPORT jobject JNICALL
Java_com_yashikota_omaigenzo_LibRawBridge_wrapDirect(JNIEnv *env, jobject thiz, jlong address, jlong length) {
    if (address == 0 || length <= 0) return nullptr;
    return env->NewDirectByteBuffer(reinterpret_cast<void *>(address), length);
}

JNIEXPORT void JNICALL
Java_com_yashikota_omaigenzo_LibRawBridge_closeView(JNIEnv *env, jobject thiz, jlong handle) {
    delete reinterpret_cast<PreviewHandle *>(handle);
}

// Last resort: reduced (half_size) or full RAW development straight from the descriptor.
JNIEXPORT jobject JNICALL
Java_com_yashikota_omaigenzo_LibRawBridge_decodeRawFromFd(JNIEnv *env, jobject thiz, jint fd, jboolean half_size) {
    RawFileView view;
    if (!view.open(fd, RawFileView::Access::Sequential)) return nullptr;

    LibRaw raw;
    if (raw.open_buffer(view.data(), view.size()) != LIBRAW_SUCCESS) {
        LOGE("decodeRawFromFd: open_buffer failed");
        return nullptr;
    }
    if (raw.unpack() != LIBRAW_SUCCESS) {
        LOGE("decodeRawFromFd: unpack failed");
        return nullptr;
    }

    raw.imgdata.params.half_size = half_size ? 1 : 0;
    raw.imgdata.params.output_bps = 8;
    raw.imgdata.params.use_camera_wb = 1;
    // Orientation is left to the display layer.
    raw.imgdata.params.user_flip = 0;

    if (raw.dcraw_process() != LIBRAW_SUCCESS) {
        LOGE("decodeRawFromFd: dcraw_process failed");
        return nullptr;
    }

    int errorCode = 0;
    libraw_processed_image_t *img = raw.dcraw_make_mem_image(&errorCode);
    if (!img) return nullptr;
    if (img->type != LIBRAW_IMAGE_BITMAP || img->colors != 3 || img->bits != 8) {
        LibRaw::dcraw_clear_mem(img);
        return nullptr;
    }

    const int width = img->width;
    const int height = img->height;
    jobject bitmap = createArgbBitmap(env, width, height);
    if (env->ExceptionCheck()) {  // OutOfMemoryError: report "no bitmap" instead of crashing the caller
        env->ExceptionClear();
        LibRaw::dcraw_clear_mem(img);
        return nullptr;
    }
    AndroidBitmapInfo info{};
    void *pixels = nullptr;
    if (!bitmap || AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LibRaw::dcraw_clear_mem(img);
        return nullptr;
    }

    for (int y = 0; y < height; y++) {
        const uint8_t *src = img->data + static_cast<size_t>(y) * width * 3;
        auto *dst = reinterpret_cast<uint32_t *>(static_cast<uint8_t *>(pixels) + static_cast<size_t>(y) * info.stride);
        for (int x = 0; x < width; x++) {
            // ARGB_8888 is ABGR in memory on little-endian ARM/x86.
            dst[x] = 0xFF000000u | (static_cast<uint32_t>(src[x * 3 + 2]) << 16) |
                     (static_cast<uint32_t>(src[x * 3 + 1]) << 8) | src[x * 3 + 0];
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    LibRaw::dcraw_clear_mem(img);
    LOGI("Developed RAW from fd: %dx%d (half=%d)", width, height, half_size ? 1 : 0);
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

