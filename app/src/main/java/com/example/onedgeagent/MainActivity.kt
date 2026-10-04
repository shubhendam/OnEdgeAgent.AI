package com.example.onedgeagent

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val viewModel: AgentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            val engineState by viewModel.engineState.collectAsState()
            
            var hasStorage by remember { mutableStateOf(checkStoragePermission()) }
            var hasOverlay by remember { mutableStateOf(checkOverlayPermission()) }

            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "OnEdgeAgent Status: $engineState")
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    if (!hasStorage) {
                        Button(onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                intent.data = Uri.parse("package:$packageName")
                                startActivity(intent)
                            }
                        }) {
                            Text("Grant Manage Files Permission")
                        }
                    } else if (!hasOverlay) {
                        Button(onClick = {
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                            startActivity(intent)
                        }) {
                            Text("Grant Overlay Permission")
                        }
                    } else {
                        if (engineState == EngineState.READY) {
                            LaunchedEffect(Unit) {
                                val serviceIntent = Intent(this@MainActivity, FloatingChatService::class.java)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    startForegroundService(serviceIntent)
                                } else {
                                    startService(serviceIntent)
                                }
                            }
                        }

                        if (engineState == EngineState.IDLE || engineState == EngineState.ERROR) {
                            Button(onClick = {
                                viewModel.loadModel()
                            }) {
                                Text("Initialize OnEdgeAgent")
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        hasStorage = checkStoragePermission()
                        hasOverlay = checkOverlayPermission()
                    }) {
                        Text("Refresh Permissions")
                    }
                }
            }
        }
    }
    
    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true // Assume true for simplicity on < API 30
        }
    }
    
    private fun checkOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }
}

