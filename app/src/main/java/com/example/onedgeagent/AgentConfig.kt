package com.example.onedgeagent

object AgentConfig {
    const val SYSTEM_PROMPT = """
You are OnEdgeAgent, an autonomous Android assistant.
Your goal is to fulfill user requests by calling the appropriate tools.

AVAILABLE TOOLS:
- append_memory_file(content: string): Saves facts to memory.
- read_memory_file(): Reads facts from memory.
- get_screen_context(): Takes a screenshot.
- click_screen_element(x: float, y: float): Taps the screen.
- press_home(): Presses home.
- press_back(): Presses back.
- launch_app(package: string): Launches an app.

If you need to call a tool, you MUST output ONLY valid JSON in this exact format:
{
  "tool": "tool_name",
  "arguments": {
    "arg1": "value1"
  }
}

If you do NOT need to call a tool, just respond normally to the user in plain text. Do not output JSON if you are just talking to the user.
"""""""

    val TOOLS_JSON = """
    [
        {
            "type": "function",
            "function": {
                "name": "get_screen_context",
                "description": "Takes a screenshot of the current Android screen and returns it for analysis.",
                "parameters": { "type": "object", "properties": {} }
            }
        },
        {
            "type": "function",
            "function": {
                "name": "click_screen_element",
                "description": "Taps on the screen at the given x and y coordinates.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "x": { "type": "number", "description": "The x coordinate on the screen" },
                        "y": { "type": "number", "description": "The y coordinate on the screen" }
                    },
                    "required": ["x", "y"]
                }
            }
        },
        {
            "type": "function",
            "function": {
                "name": "press_home",
                "description": "Simulates pressing the global Home button on the Android device.",
                "parameters": { "type": "object", "properties": {} }
            }
        },
        {
            "type": "function",
            "function": {
                "name": "press_back",
                "description": "Simulates pressing the global Back button on the Android device.",
                "parameters": { "type": "object", "properties": {} }
            }
        },
        {
            "type": "function",
            "function": {
                "name": "launch_app",
                "description": "Launches an Android application using its package name.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "package": { "type": "string", "description": "The package name (e.g., com.whatsapp)" }
                    },
                    "required": ["package"]
                }
            }
        },
        {
            "type": "function",
            "function": {
                "name": "read_memory_file",
                "description": "Reads the local MEMORY.md file to remember facts about the user.",
                "parameters": { "type": "object", "properties": {} }
            }
        },
        {
            "type": "function",
            "function": {
                "name": "append_memory_file",
                "description": "Appends a new fact to the local MEMORY.md file.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "content": { "type": "string", "description": "The fact to remember" }
                    },
                    "required": ["content"]
                }
            }
        }
    ]
    """.trimIndent()
}







