package com.yashikota.omaigenzo

import android.util.Log
import android.view.Surface

class FastGpuBridge {

    companion object {
        private const val TAG = "FastGpuBridge"

        init {
            try {
                System.loadLibrary("native-lib")
                Log.i(TAG, "Native library loaded successfully.")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library: ${e.message}")
            }
        }
    }

    external fun createEngine(surface: Surface): Long
    external fun destroyEngine(engineHandle: Long)
    external fun resize(engineHandle: Long, width: Int, height: Int)
    external fun loadPhotoFromPath(engineHandle: Long, filePath: String, slotIndex: Int): Boolean
    external fun loadPhotoFromFd(engineHandle: Long, fd: Int, slotIndex: Int): Boolean
    external fun setActiveSlot(engineHandle: Long, slotIndex: Int)
    external fun updateTransform(engineHandle: Long, scale: Float, panX: Float, panY: Float)
    external fun updateExposure(engineHandle: Long, exposureEV: Float)
    external fun renderFrame(engineHandle: Long)
}
