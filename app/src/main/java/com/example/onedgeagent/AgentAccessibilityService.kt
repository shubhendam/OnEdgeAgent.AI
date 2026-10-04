package com.example.onedgeagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Bitmap
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.io.ByteArrayOutputStream

class AgentAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "AgentAccessibilityService"
        var instance: AgentAccessibilityService? = null
            private set
    }

    private val screenshotExecutor: Executor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        LogUtils.d("Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }

    override fun onInterrupt() { }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    fun captureScreenBytes(callback: (ByteArray?) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        val hwBuffer = screenshotResult.hardwareBuffer
                        val colorSpace = screenshotResult.colorSpace
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                val bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                                if (bitmap != null) {
                                    val scaledBitmap = scaleBitmapToWidth(bitmap, 720)
                                    val stream = ByteArrayOutputStream()
                                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
                                    callback(stream.toByteArray())
                                } else {
                                    callback(null)
                                }
                            } else {
                                callback(null)
                            }
                        } catch (e: Exception) {
                            LogUtils.e("Error capturing screen bytes", e)
                            callback(null)
                        } finally {
                            hwBuffer.close()
                        }
                    }
                    override fun onFailure(errorCode: Int) {
                        LogUtils.e("TakeScreenshotCallback failed with code: $errorCode")
                        callback(null)
                    }
                }
            )
        } else {
            callback(null)
        }
    }
    
    private fun scaleBitmapToWidth(bitmap: Bitmap, maxWidth: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxWidth) return bitmap
        
        val ratio = maxWidth.toFloat() / width
        val newHeight = (height * ratio).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, maxWidth, newHeight, true)
    }

    fun performTap(normalizedX: Float, normalizedY: Float): Boolean {
        val displayMetrics = android.content.res.Resources.getSystem().displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        
        val actualX = normalizedX * screenWidth
        val actualY = normalizedY * screenHeight
        
        LogUtils.d("Performing tap at normalized ($normalizedX, $normalizedY) -> actual ($actualX, $actualY)")
        val path = Path()
        path.moveTo(actualX, actualY)
        
        val gestureBuilder = GestureDescription.Builder()
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        gestureBuilder.addStroke(stroke)
        
        return dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {}, null)
    }
}



