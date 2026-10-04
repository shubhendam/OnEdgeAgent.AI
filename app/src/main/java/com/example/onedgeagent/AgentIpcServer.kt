package com.example.onedgeagent

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayInputStream

class AgentIpcServer(private val context: Context) : NanoHTTPD(8081) {
    
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        
        val accessibilityService = AgentAccessibilityService.instance
        if (accessibilityService == null) {
            return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "Accessibility Service not running")
        }

        return try {
            when {
                method == Method.GET && uri == "/api/vision/screenshot" -> {
                    var imageBytes: ByteArray? = null
                    val lock = java.util.concurrent.CountDownLatch(1)
                    
                    // Hide chat overlay before screenshot
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        FloatingChatService.instance?.hideTemporarily()
                    }
                    Thread.sleep(150) // Give UI time to hide
                    
                    accessibilityService.captureScreenBytes { bytes ->
                        imageBytes = bytes
                        lock.countDown()
                    }
                    lock.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    
                    // Show chat overlay again
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        FloatingChatService.instance?.showAgain()
                    }
                    
                    if (imageBytes != null) {
                        newChunkedResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(imageBytes))
                    } else {
                        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Failed to capture screenshot")
                    }
                }
                method == Method.POST && uri == "/api/action/tap" -> {
                    val map = HashMap<String, String>()
                    session.parseBody(map)
                    val json = JSONObject(map["postData"] ?: "{}")
                    val x = json.optDouble("x").toFloat()
                    val y = json.optDouble("y").toFloat()
                    accessibilityService.performTap(x, y)
                    newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"ok\"}")
                }
                method == Method.POST && uri == "/api/action/home" -> {
                    accessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                    newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"ok\"}")
                }
                method == Method.POST && uri == "/api/action/back" -> {
                    accessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"ok\"}")
                }
                method == Method.POST && uri == "/api/action/launch_app" -> {
                    val map = HashMap<String, String>()
                    session.parseBody(map)
                    val json = JSONObject(map["postData"] ?: "{}")
                    val pkg = json.optString("package")
                    val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"launched\"}")
                    } else {
                        newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"App not found\"}")
                    }
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }
        } catch (e: Exception) {
            Log.e("AgentIpcServer", "Error serving request", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }
}



