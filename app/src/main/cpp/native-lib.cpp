#include <jni.h>
#include <string>
#include <android/log.h>
#include <android/bitmap.h>
#include "libraw/libraw.h"

#define LOG_TAG "NativeLibRaw"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

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

    if (raw.unpack_thumb() != LIBRAW_SUCCESS) {
        raw.recycle();
        env->ReleaseStringUTFChars(file_path, path);
        return nullptr;
    }

    libraw_processed_image_t *img = raw.dcraw_make_mem_thumb();
    if (!img) {
        raw.recycle();
        env->ReleaseStringUTFChars(file_path, path);
        return nullptr;
    }

    jbyteArray byteArray = nullptr;
    if (img->type == LIBRAW_IMAGE_JPEG) {
        byteArray = env->NewByteArray(img->data_size);
        env->SetByteArrayRegion(byteArray, 0, img->data_size, reinterpret_cast<jbyte*>(img->data));
    }

    LibRaw::dcraw_clear_mem(img);
    raw.recycle();
    env->ReleaseStringUTFChars(file_path, path);

    return byteArray;
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

}
