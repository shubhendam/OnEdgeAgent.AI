# OnEdgeAgent (OEA): A Novel Framework for Hosting Autonomous Edge AI Agents on Android

**License:** Apache 2.0  
**Target API:** 30+ (Android 11+)  
**Architecture:** `arm64-v8a`

---

## Abstract

OnEdgeAgent (OEA) is a zero-dependency, 100% on-device multimodal agent architecture for Android. By eliminating external Python servers and cloud APIs, OEA establishes a fully local, self-contained vision-to-action control loop. It relies on a statically compiled C++ `llama-server` runtime combined with a Kotlin `AccessibilityService` via dual-loop IPC to autonomously read the screen, plan actions, and execute physical UI taps natively on the device.

---

## System Architecture Diagram

```mermaid
sequenceDiagram
    participant User
    participant Compose UI (OEA App)
    participant C++ Llama Engine (8080)
    participant Kotlin IPC Server (8081)
    participant Accessibility Service (Android OS)

    User->>Compose UI: Issue Command (e.g. "Tap Search")
    Compose UI->>C++ Llama Engine (8080): HTTP POST /v1/chat/completions
    C++ Llama Engine (8080)-->>Compose UI: Tool Call: get_screen_context
    Compose UI->>Kotlin IPC Server (8081): HTTP GET /api/vision/screenshot
    Kotlin IPC Server (8081)->>Accessibility Service (Android OS): Request Display Buffer
    Accessibility Service (Android OS)-->>Kotlin IPC Server (8081): Return compressed JPEG
    Kotlin IPC Server (8081)-->>Compose UI: JSON Array (Image + Metadata)
    Compose UI->>C++ Llama Engine (8080): Return Vision Payload (User Role)
    C++ Llama Engine (8080)-->>Compose UI: Tool Call: click_screen_element(x, y)
    Compose UI->>Kotlin IPC Server (8081): HTTP POST /api/action/tap
    Kotlin IPC Server (8081)->>Accessibility Service (Android OS): dispatchGesture(x, y)
    Accessibility Service (Android OS)-->>User: Physical Screen Tap Executed
```

---

## Key Technical Innovations

*   **Scoped Storage File Descriptor Forwarding:** Bypasses complex Android native file I/O limits by utilizing `/proc/self/fd/` forwarding, allowing the raw C++ `llama-server` binary to seamlessly read model files (`.gguf`) located in `getExternalFilesDir()`.
*   **Small Model Action Steering:** Fine-tuned tool calling utilizing `<|think|>` channel suppression for `gemma-4-E2B-it`. We enforce deterministic JSON structural generation despite extremely constrained token generation limits on Edge devices.
*   **Dynamic WindowManager Handling:** Seamless focus-stealing transitions utilizing `FLAG_NOT_FOCUSABLE` toggles on a persistent `WindowManager` overlay. This allows the agent chat ball to seamlessly summon the soft keyboard (IME) when tapped, and instantly yield touch events to the background OS when collapsed.
*   **DisplayMetrics Coordinate Scaling:** Implements absolute fractional scaling, transforming the model's normalized `(0.0 - 1.0)` coordinate predictions into physical pixel matrices via `Resources.getSystem().displayMetrics`, bypassing localized Window inset padding for perfect physical precision.

---

## Hardware Requirements & Setup

### Minimum Specifications
*   **RAM:** 8 GB
*   **Processor:** ARM64-v8a (Snapdragon 8 Gen 1+ recommended for acceptable generation speeds)
*   **OS:** Android 11+ (API 30+)

### Tested Models
*   **Main LLM:** `gemma-4-E2B-it-Q4_K_M.gguf`
*   **Multimodal Projector:** `mmproj-F16.gguf`

### Setup Instructions
1.  **Clone the Repository:**
    ```bash
    git clone https://github.com/your-org/OnEdgeAgent.git
    cd OnEdgeAgent
    ```
2.  **Load the Models:**
    Download the required `.gguf` weights and place them inside the Android application's external files directory:
    `/storage/emulated/0/Android/data/com.example.onedgeagent/files/`
3.  **Build the APK:**
    Open the project in Android Studio or build via Gradle wrapper:
    ```bash
    ./gradlew assembleRelease
    ```
4.  **Install & Enable Accessibility:**
    Install the APK, then navigate to **Settings > Accessibility > Downloaded apps > OnEdgeAgent** and toggle the service to **ON**. (Note: If installed via ADB/sideload, you must first allow restricted settings under the app's info page).

---

## Empirical Benchmarks

*   **Initial Image Tensor Evaluation:** ~106s on modern mobile CPUs for a 720p heavily compressed JPEG. (Dependent on hardware and thread allocations).
*   **Action Execution Latency:** Sub-second response time (~50-100ms) from Tool Call parse to `dispatchGesture` physical tap on the display panel.
*   **Memory Footprint:** ~2.1 GB overhead for a Q4_K_M quantized Gemma model holding context.

---

## Citation

If you use OnEdgeAgent in your academic research, please cite our project:

```bibtex
@misc{onedgeagent2026,
  author = {Your Name / Research Group},
  title = {OnEdgeAgent (OEA): A Novel Framework for Hosting Autonomous Edge AI Agents on Android},
  year = {2026},
  publisher = {GitHub},
  journal = {GitHub repository},
  howpublished = {\url{https://github.com/your-org/OnEdgeAgent}}
}
```
