package com.example.onedgeagent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

class AgentViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = AgentEngine(application)
    
    val engineState: StateFlow<EngineState> = AgentEngine.engineState

    fun loadModel() {
        engine.loadModel()
    }

    override fun onCleared() {
        super.onCleared()
        engine.stop()
    }
}

