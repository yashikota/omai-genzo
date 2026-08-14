package com.yashikota.omaigenzo.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yashikota.omaigenzo.FastGpuBridge
import com.yashikota.omaigenzo.PhotoItem
import com.yashikota.omaigenzo.ui.theme.BorderColor
import com.yashikota.omaigenzo.ui.theme.DarkSurfaceVariant

private const val TAG = "FastRawGpuViewer"

@Composable
fun FastRawGpuViewer(
    photo: PhotoItem,
    exposureEV: Float = 0.0f,
    modifier: Modifier = Modifier,
    onZoomChanged: (Float) -> Unit = {},
) {
    val gpuBridge = remember { FastGpuBridge() }
    var engineHandle by remember { mutableLongStateOf(0L) }
    var zoomScale by remember { mutableFloatStateOf(1.0f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    // Reset zoom and pan on photo change
    LaunchedEffect(photo.id) {
        zoomScale = 1.0f
        panOffset = Offset.Zero
        onZoomChanged(1.0f)
    }

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
                            loadPhotoIntoEngine(context, gpuBridge, handle, photo, 0)
                            gpuBridge.setActiveSlot(handle, 0)
                            gpuBridge.renderFrame(handle)
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                            if (engineHandle != 0L) {
                                gpuBridge.resize(engineHandle, w, h)
                                gpuBridge.renderFrame(engineHandle)
                            }
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            if (engineHandle != 0L) {
                                gpuBridge.destroyEngine(engineHandle)
                                engineHandle = 0L
                            }
                        }
                    })
                }
            },
            update = {
                // When photo or handle changes, reload
                if (engineHandle != 0L) {
                    loadPhotoIntoEngine(it.context, gpuBridge, engineHandle, photo, 0)
                    gpuBridge.setActiveSlot(engineHandle, 0)
                    gpuBridge.updateExposure(engineHandle, exposureEV)
                    gpuBridge.updateTransform(engineHandle, zoomScale, panOffset.x, panOffset.y)
                    gpuBridge.renderFrame(engineHandle)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(photo.id) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        zoomScale = (zoomScale * zoom).coerceIn(1f, 10f)
                        onZoomChanged(zoomScale)
                        if (zoomScale > 1.05f) {
                            panOffset = Offset(
                                x = (panOffset.x + pan.x / 1000f).coerceIn(-1.5f, 1.5f),
                                y = (panOffset.y + pan.y / 1000f).coerceIn(-1.5f, 1.5f),
                            )
                        } else {
                            panOffset = Offset.Zero
                        }

                        if (engineHandle != 0L) {
                            gpuBridge.updateTransform(engineHandle, zoomScale, panOffset.x, panOffset.y)
                            gpuBridge.renderFrame(engineHandle)
                        }
                    }
                },
        )
    }

    // Dynamic Exposure compensation uniform update (<0.5ms)
    LaunchedEffect(exposureEV) {
        if (engineHandle != 0L) {
            gpuBridge.updateExposure(engineHandle, exposureEV)
            gpuBridge.renderFrame(engineHandle)
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
            val uri = Uri.parse(photo.rawUriString)
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                bridge.loadPhotoFromFd(handle, pfd.detachFd(), slotIndex)
            }
        } else if (!photo.primaryPath.isEmpty()) {
            bridge.loadPhotoFromPath(handle, photo.primaryPath, slotIndex)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error loading photo into GPU engine: ${e.message}")
    }
}
