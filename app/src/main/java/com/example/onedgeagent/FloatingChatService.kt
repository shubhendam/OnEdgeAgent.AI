package com.example.onedgeagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.savedstate.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import androidx.activity.compose.BackHandler

class OverlayRootView(context: Context, private val dispatcher: OnBackPressedDispatcher) : FrameLayout(context) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            dispatcher.onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

class FloatingChatService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner, OnBackPressedDispatcherOwner {
    
    companion object {
        var instance: FloatingChatService? = null
    }

    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private lateinit var rootView: OverlayRootView
    
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val backDispatcher = OnBackPressedDispatcher()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val onBackPressedDispatcher: OnBackPressedDispatcher get() = backDispatcher

    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private var chatHistory = JSONArray().apply {
        put(JSONObject().apply {
            put("role", "system")
            put("content", AgentConfig.SYSTEM_PROMPT)
        })
    }

    private var layoutParams: WindowManager.LayoutParams? = null

    private var floatingX = 0
    private var floatingY = 200

    override fun onCreate() {
        super.onCreate()
        instance = this
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = floatingX
            y = floatingY
        }

        rootView = OverlayRootView(this, backDispatcher).apply {
            setViewTreeLifecycleOwner(this@FloatingChatService)
            setViewTreeViewModelStoreOwner(this@FloatingChatService)
            setViewTreeSavedStateRegistryOwner(this@FloatingChatService)
            setViewTreeOnBackPressedDispatcherOwner(this@FloatingChatService)
        }

        composeView = ComposeView(this).apply {
            setContent {
                MaterialTheme {
                    ChatOverlay()
                }
            }
        }
        
        rootView.addView(composeView)
        windowManager.addView(rootView, layoutParams)
        
        val notification = createNotification()
        startForeground(1, notification)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    fun hideTemporarily() {
        rootView.visibility = android.view.View.GONE
    }
    
    fun showAgain() {
        rootView.visibility = android.view.View.VISIBLE
    }

    private fun setWindowExpanded(expanded: Boolean) {
        val p = layoutParams ?: return
        if (expanded) {
            p.width = WindowManager.LayoutParams.MATCH_PARENT
            p.height = WindowManager.LayoutParams.WRAP_CONTENT
            p.gravity = Gravity.BOTTOM
            p.x = 0
            p.y = 0
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            p.width = WindowManager.LayoutParams.WRAP_CONTENT
            p.height = WindowManager.LayoutParams.WRAP_CONTENT
            p.gravity = Gravity.TOP or Gravity.START
            p.x = floatingX
            p.y = floatingY
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        windowManager.updateViewLayout(rootView, p)
    }
    
    fun updateFloatingPosition(dx: Float, dy: Float) {
        val p = layoutParams ?: return
        floatingX += dx.toInt()
        floatingY += dy.toInt()
        p.x = floatingX
        p.y = floatingY
        windowManager.updateViewLayout(rootView, p)
    }

    @Composable
    fun ChatOverlay() {
        var expanded by remember { mutableStateOf(false) }
        var messages by remember { mutableStateOf(listOf<String>()) }
        var input by remember { mutableStateOf("") }
        var showMemoryDialog by remember { mutableStateOf(false) }
        var memoryContent by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()
        val focusRequester = remember { FocusRequester() }
        val focusManager = LocalFocusManager.current
        
        val engineState by AgentEngine.engineState.collectAsState()

        BackHandler(enabled = expanded) {
            focusManager.clearFocus()
            expanded = false
            showMemoryDialog = false
            setWindowExpanded(false)
        }

        LaunchedEffect(expanded) {
            if (expanded) {
                setWindowExpanded(true)
                delay(100)
                try {
                    focusRequester.requestFocus()
                } catch(e: Exception) {}
            } else {
                setWindowExpanded(false)
            }
        }

        if (showMemoryDialog) {
            Box(modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
                .background(Color.White)
                .padding(8.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text("Memory (MEMORY.md)", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        item { Text(memoryContent) }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Button(onClick = {
                            val file = File(this@FloatingChatService.filesDir, "MEMORY.md")
                            if (file.exists()) file.delete()
                            memoryContent = "Memory is currently empty."
                            LogUtils.d("Memory cleared by user.")
                        }) { Text("Clear") }
                        Button(onClick = { 
                            showMemoryDialog = false 
                            setWindowExpanded(true)
                            try { focusRequester.requestFocus() } catch(e: Exception) {}
                        }) { Text("Close") }
                    }
                }
            }
        } else if (!expanded) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(Color.Blue.copy(alpha = 0.8f), CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = { },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                updateFloatingPosition(dragAmount.x, dragAmount.y)
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = { expanded = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("AI", color = Color.White)
                }
            }
        } else {
            Column(modifier = Modifier
                .background(Color.White)
                .fillMaxWidth()
                .height(400.dp)
                .padding(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("OnEdgeAgent", style = MaterialTheme.typography.titleMedium)
                    Row {
                        Button(onClick = {
                            scope.launch(Dispatchers.IO) {
                                val file = File(this@FloatingChatService.filesDir, "MEMORY.md")
                                val content = if (file.exists()) file.readText() else "Memory is currently empty."
                                scope.launch(Dispatchers.Main) {
                                    memoryContent = content
                                    showMemoryDialog = true
                                }
                            }
                        }) { Text("Mem") }
                        Spacer(modifier = Modifier.width(4.dp))
                        Button(onClick = {
                            messages = emptyList()
                            chatHistory = JSONArray().apply {
                                put(JSONObject().apply {
                                    put("role", "system")
                                    put("content", AgentConfig.SYSTEM_PROMPT)
                                })
                            }
                            LogUtils.d("Context killed / New Session started.")
                        }) { Text("Reset") }
                        Spacer(modifier = Modifier.width(4.dp))
                        Button(onClick = { 
                            focusManager.clearFocus()
                            expanded = false
                            setWindowExpanded(false)
                        }) { Text("X") }
                    }
                }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(messages) { msg ->
                        Text(msg, modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f).focusRequester(focusRequester)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { 
                        val userMsg = input
                        messages = messages + "User: $userMsg"
                        input = ""
                        focusManager.clearFocus()
                        
                        LogUtils.d("User sent: $userMsg")
                        
                        chatHistory.put(JSONObject().apply {
                            put("role", "user")
                            put("content", userMsg)
                        })

                        scope.launch(Dispatchers.IO) {
                            scope.launch(Dispatchers.Main) {
                                messages = messages + "Agent: "
                            }
                            runAgentLoop { chunk ->
                                scope.launch(Dispatchers.Main) {
                                    messages = messages.toMutableList().apply {
                                        val lastIdx = size - 1
                                        this[lastIdx] = this[lastIdx] + chunk
                                    }
                                }
                            }
                        }
                    }) { Text("Send") }
                }
            }
        }
    }

    private fun runAgentLoop(onChunk: (String) -> Unit) {
        var iterations = 0
        val maxIterations = 5

        while (iterations < maxIterations) {
            iterations++
            AgentEngine.updateState(EngineState.THINKING)

            try {
                val json = JSONObject().apply {
                    put("messages", chatHistory)
                    put("stream", true)
                }
                
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("http://127.0.0.1:8080/v1/chat/completions")
                    .post(body)
                    .build()
                    
                var fullContent = ""
                val response = client.newCall(request).execute()
                
                try {
                    if (response.isSuccessful) {
                        val source = response.body?.source()
                        while (source != null && !source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (line.startsWith("data: ")) {
                                val data = line.removePrefix("data: ")
                                if (data == "[DONE]") break
                                try {
                                    val chunkObj = JSONObject(data)
                                    val delta = chunkObj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                                    val chunk = if (delta?.isNull("content") == false) delta.optString("content", "") else ""
                                    if (chunk.isNotEmpty()) {
                                        fullContent += chunk
                                        onChunk(chunk)
                                    }
                                } catch (e: Exception) {}
                            }
                        }
                    } else {
                        val errBody = response.body?.string() ?: ""
                        LogUtils.e("HTTP Error: ${response.code} $errBody")
                        onChunk("\nError: ${response.code}")
                        break
                    }
                } finally {
                    response.close()
                }
                
                LogUtils.d("Agent final streamed response: $fullContent")
                
                var toolResult: Any = ""
                var toolName = ""
                var isToolCall = false
                
                try {
                    val firstBrace = fullContent.indexOf('{')
                    val lastBrace = fullContent.lastIndexOf('}')
                    if (firstBrace != -1 && lastBrace != -1) {
                        val jsonStr = fullContent.substring(firstBrace, lastBrace + 1)
                        val toolObj = JSONObject(jsonStr)
                        if (toolObj.has("tool")) {
                            isToolCall = true
                            toolName = toolObj.optString("tool")
                            val argsObj = toolObj.optJSONObject("arguments")
                            val toolArgs = argsObj?.toString() ?: "{}"
                            
                            LogUtils.d("Manual JSON tool called: $toolName args: $toolArgs")
                            toolResult = executeTool(toolName, toolArgs)
                            LogUtils.d("Tool result: $toolResult")
                        }
                    }
                } catch(e: Exception) {
                    LogUtils.e("Failed to parse JSON tool: $e")
                }
                
                if (!isToolCall) {
                    try {
                        val lines = fullContent.split("\n").map { it.trim() }
                        val callLine = lines.find { it.contains("call:") }
                        if (callLine != null) {
                            val extractedToolName = callLine.substringAfter("call:").trim()
                            val argsMap = JSONObject()
                            for (line in lines) {
                                if (line.contains(":") && !line.contains("call:") && !line.startsWith("<")) {
                                    val key = line.substringBefore(":").trim()
                                    val value = line.substringAfter(":").trim().removeSurrounding("\"").removeSurrounding("'")
                                    argsMap.put(key, value)
                                }
                            }
                            if (extractedToolName.isNotEmpty()) {
                                isToolCall = true
                                toolName = extractedToolName
                                val toolArgs = argsMap.toString()
                                LogUtils.d("Fallback text tool called: $toolName args: $toolArgs")
                                toolResult = executeTool(toolName, toolArgs)
                                LogUtils.d("Tool result: $toolResult")
                            }
                        }
                    } catch (e: Exception) {
                        LogUtils.e("Failed to fallback parse tool: $e")
                    }
                }
                
                if (isToolCall) {
                    chatHistory.put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", fullContent)
                    })
                    
                    if (toolResult is JSONArray) {
                        chatHistory.put(JSONObject().apply {
                            put("role", "tool")
                            put("content", "{\"result\": \"Screenshot captured, user will provide the image now.\"}")
                        })
                        chatHistory.put(JSONObject().apply {
                            put("role", "user")
                            put("content", toolResult)
                        })
                    } else {
                        chatHistory.put(JSONObject().apply {
                            put("role", "user")
                            put("content", "Tool '$toolName' result: $toolResult")
                        })
                    }
                    
                    onChunk("\n\nAgent (Tool response received): ")
                    
                    if (iterations >= maxIterations) {
                        val limitMsg = "Execution stopped to prevent infinite loops."
                        onChunk("\n$limitMsg")
                        chatHistory.put(JSONObject().apply {
                            put("role", "system")
                            put("content", limitMsg)
                        })
                        break
                    }
                    continue
                } else {
                    chatHistory.put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", fullContent)
                    })
                    break
                }

            } catch (e: Exception) {
                LogUtils.e("Exception: ${e.message}", e)
                onChunk("\nException: ${e.message}")
                break
            }
        }
        
        AgentEngine.updateState(EngineState.READY)
    }
    
    private fun getErrorMessage(response: Response, defaultMessage: String): String {
        return if (response.code == 503) {
            "Error: Accessibility Service is not enabled. Ask the user to enable OnEdgeAgent Accessibility Service in Android Settings."
        } else {
            "$defaultMessage (HTTP ${response.code})"
        }
    }

    private fun executeTool(name: String, args: String): Any {
        return try {
            when (name) {
                "get_screen_context" -> {
                    val request = Request.Builder().url("http://127.0.0.1:8081/api/vision/screenshot").get().build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val bytes = response.body?.bytes()
                            if (bytes != null) {
                                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("type", "text")
                                        put("text", "Here is the screen context you requested.")
                                    })
                                    put(JSONObject().apply {
                                        put("type", "image_url")
                                        put("image_url", JSONObject().apply {
                                            put("url", "data:image/jpeg;base64,$base64")
                                        })
                                    })
                                }
                            } else "Failed to get image bytes"
                        } else getErrorMessage(response, "Failed to capture screen")
                    }
                }
                "click_screen_element" -> {
                    AgentEngine.updateState(EngineState.TAPPING)
                    val body = args.toRequestBody("application/json".toMediaType())
                    val request = Request.Builder().url("http://127.0.0.1:8081/api/action/tap").post(body).build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) "Tapped successfully" else getErrorMessage(response, "Tap failed")
                    }
                }
                "press_home" -> {
                    AgentEngine.updateState(EngineState.TAPPING)
                    val body = "{}".toRequestBody("application/json".toMediaType())
                    val request = Request.Builder().url("http://127.0.0.1:8081/api/action/home").post(body).build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) "Home button pressed" else getErrorMessage(response, "Home failed")
                    }
                }
                "press_back" -> {
                    AgentEngine.updateState(EngineState.TAPPING)
                    val body = "{}".toRequestBody("application/json".toMediaType())
                    val request = Request.Builder().url("http://127.0.0.1:8081/api/action/back").post(body).build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) "Back button pressed" else getErrorMessage(response, "Back failed")
                    }
                }
                "launch_app" -> {
                    AgentEngine.updateState(EngineState.TAPPING)
                    val body = args.toRequestBody("application/json".toMediaType())
                    val request = Request.Builder().url("http://127.0.0.1:8081/api/action/launch_app").post(body).build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) "App launched" else getErrorMessage(response, "Launch failed")
                    }
                }
                "read_memory_file" -> {
                    AgentEngine.updateState(EngineState.UPDATING_MEMORY)
                    try {
                        val memoryFile = File(this.filesDir, "MEMORY.md")
                        if (memoryFile.exists()) {
                            memoryFile.readText()
                        } else {
                            "Memory is currently empty."
                        }
                    } catch (e: Exception) {
                        LogUtils.e("File Read Error: ", e)
                        "System Error: Could not read from disk."
                    }
                }
                "append_memory_file" -> {
                    AgentEngine.updateState(EngineState.UPDATING_MEMORY)
                    try {
                        val contentObj = JSONObject(args)
                        val contentToAppend = contentObj.optString("content", "")
                        val memoryFile = File(this.filesDir, "MEMORY.md")
                        memoryFile.appendText(contentToAppend + "\n")
                        "Success: Memory updated."
                    } catch (e: Exception) {
                        LogUtils.e("File Write Error: ", e)
                        "System Error: Could not write to disk. Tell the user there is a file system error and STOP trying to save."
                    }
                }
                else -> "Unknown tool $name"
            }
        } catch (e: Exception) {
            "Tool error: ${e.message}"
        }
    }
    
    private fun createNotification(): Notification {
        val channelId = "OnEdgeAgentChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "OnEdgeAgent", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        return Notification.Builder(this, channelId)
            .setContentTitle("OnEdgeAgent")
            .setContentText("Chat overlay is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        if (::rootView.isInitialized) {
            windowManager.removeView(rootView)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}


