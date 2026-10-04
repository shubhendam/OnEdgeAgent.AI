package com.example.onedgeagent

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlin.concurrent.thread

enum class EngineState {
    IDLE, LOADING_MODEL, READY, GENERATING, ERROR, THINKING, TAPPING, UPDATING_MEMORY, MISSING_BINARY
}

class AgentEngine(private val context: Context) {
    companion object {
        const val PORT = 8080
        const val HOST = "127.0.0.1"

        private val _engineState = MutableStateFlow(EngineState.IDLE)
        val engineState: StateFlow<EngineState> = _engineState

        fun updateState(newState: EngineState) {
            _engineState.value = newState
        }
    }

    private var agentProcess: Process? = null
    private val ipcServer = AgentIpcServer(context)
    
    init {
        try {
            ipcServer.start()
            LogUtils.d("AgentIpcServer started on port 8081")
        } catch (e: Exception) {
            LogUtils.e("Failed to start AgentIpcServer", e)
        }
    }

    fun loadModel() {
        updateState(EngineState.LOADING_MODEL)
        thread {
            try {
                val agentFile = extractBinary()
                if (agentFile == null) {
                    updateState(EngineState.MISSING_BINARY)
                    return@thread
                }

                // Check external files dir first (no special permission required)
                val extDir = context.getExternalFilesDir(null)
                var modelFile = File(extDir, "gemma-4-E2B-it-Q4_K_M.gguf")
                var mmprojFile = File(extDir, "mmproj-F16.gguf")
                
                // Fallback to Download dir if they exist there
                if (!modelFile.exists()) {
                    val fallbackModel = File("/sdcard/Download/gemma-4-E2B-it-Q4_K_M.gguf")
                    if (fallbackModel.exists()) modelFile = fallbackModel
                }
                if (!mmprojFile.exists()) {
                    val fallbackMmproj = File("/sdcard/Download/mmproj-F16.gguf")
                    if (fallbackMmproj.exists()) mmprojFile = fallbackMmproj
                }

                if (!modelFile.exists() || !mmprojFile.exists()) {
                    LogUtils.e("Models not found in ${extDir?.absolutePath} or /sdcard/Download/")
                    updateState(EngineState.ERROR)
                    return@thread
                }

                LogUtils.d("Starting llama-agent with model and mmproj...")
                
                val pb = ProcessBuilder(
                    agentFile.absolutePath,
                    "-m", modelFile.absolutePath,
                    "--mmproj", mmprojFile.absolutePath,
                    "--host", HOST,
                    "--port", PORT.toString()
                )
                
                pb.directory(agentFile.parentFile)
                pb.redirectErrorStream(true)
                
                agentProcess?.destroy()
                agentProcess = pb.start()
                
                thread {
                    agentProcess?.inputStream?.bufferedReader()?.use { reader ->
                        var line: String? = reader.readLine()
                        var isReady = false
                        while (line != null) {
                            LogUtils.d(line)
                            if (!isReady && (line.contains("HTTP server listening") || line.contains("model loaded"))) {
                                isReady = true
                                updateState(EngineState.READY)
                            }
                            line = reader.readLine()
                        }
                    }
                    val exitCode = agentProcess?.waitFor()
                    LogUtils.d("llama-agent process exited with code $exitCode")
                    if (_engineState.value != EngineState.IDLE) {
                        updateState(EngineState.ERROR)
                    }
                }
            } catch (e: Exception) {
                LogUtils.e("Error loading model", e)
                updateState(EngineState.ERROR)
            }
        }
    }

    private fun extractBinary(): File? {
        val agentFile = File(context.applicationInfo.nativeLibraryDir, "libllama-agent.so")
        if (!agentFile.exists()) {
            LogUtils.e("llama-agent binary not found in nativeLibraryDir: ${agentFile.absolutePath}")
            return null
        }
        return agentFile
    }

    fun stop() {
        agentProcess?.destroy()
        agentProcess = null
        ipcServer.stop()
        updateState(EngineState.IDLE)
    }
}
