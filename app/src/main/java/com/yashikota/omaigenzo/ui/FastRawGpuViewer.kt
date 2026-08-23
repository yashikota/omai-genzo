package com.yashikota.omaigenzo.ui

import android.content.Context
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.yashikota.omaigenzo.FastGpuBridge
import com.yashikota.omaigenzo.PhotoItem
import com.yashikota.omaigenzo.ui.theme.BorderColor
import com.yashikota.omaigenzo.ui.theme.DarkSurfaceVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "FastRawGpuViewer"

@Composable
fun FastRawGpuViewer(
    photo: PhotoItem,
    modifier: Modifier = Modifier,
    exposureEV: Float = 0.0f,
    zoomScale: Float = 1.0f,
    panX: Float = 0.0f,
    panY: Float = 0.0f,
) {
    val appContext = androidx.compose.ui.platform.LocalContext.current
    val gpuBridge = remember { FastGpuBridge() }
    val scope = rememberCoroutineScope()
    val engineMutex = remember { Mutex() }
    var engineHandle by remember { mutableLongStateOf(0L) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(DarkSurfaceVariant)
            .border(1.dp, BorderColor, RoundedCornerShape(20.dp)),
    ) {
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            val handle = gpuBridge.createEngine(holder.surface)
                            engineHandle = handle
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                            if (engineHandle != 0L) {
                                val handle = engineHandle
                                scope.launch(Dispatchers.IO) {
                                    engineMutex.withLock {
                                        gpuBridge.resize(handle, w, h)
                                        gpuBridge.renderFrame(handle)
                                    }
                                }
                            }
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            if (engineHandle != 0L) {
                                val handle = engineHandle
                                engineHandle = 0L
                                scope.launch(Dispatchers.IO) {
                                    engineMutex.withLock { gpuBridge.destroyEngine(handle) }
                                }
                            }
                        }
                    })
                }
            },
            update = {
                // Native decoding is driven by LaunchedEffect off the UI thread.
            },
            modifier = Modifier.fillMaxSize(),
        )
    }

    LaunchedEffect(photo.id, engineHandle) {
        val handle = engineHandle
        if (handle != 0L) {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                engineMutex.withLock {
                    loadPhotoIntoEngine(appContext, gpuBridge, handle, photo, 0)
                    gpuBridge.setActiveSlot(handle, 0)
                    gpuBridge.renderFrame(handle)
                }
            }
        }
    }

    LaunchedEffect(exposureEV, zoomScale, panX, panY, engineHandle) {
        if (engineHandle != 0L) {
            val handle = engineHandle
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                engineMutex.withLock {
                    gpuBridge.updateExposure(handle, exposureEV)
                    gpuBridge.updateTransform(handle, zoomScale, panX, panY)
                    gpuBridge.renderFrame(handle)
                }
            }
        }
    }
}

private fun loadPhotoIntoEngine(
    context: Context,
    bridge: FastGpuBridge,
    handle: Long,
    photo: PhotoItem,
    slotIndex: Int,
) {
    try {
        if (!photo.rawPath.isNullOrEmpty()) {
            bridge.loadPhotoFromPath(handle, photo.rawPath, slotIndex)
        } else if (!photo.rawUriString.isNullOrEmpty()) {
            val uri = photo.rawUriString.toUri()
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                bridge.loadPhotoFromFd(handle, pfd.fd, slotIndex)
            }
        } else if (!photo.primaryPath.isEmpty()) {
            bridge.loadPhotoFromPath(handle, photo.primaryPath, slotIndex)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error loading photo into GPU engine: ${e.message}")
    }
}
