# Architecture Audit — Hermie Assistant

*Auto-generated: 2026-05-29. Source: `app/src/main/java/com/hermie/assistant/` Kotlin files.*

---

## 1. LLM Engines

### 1.1 `LlmEngine` (Interface)
- **Path**: `app/src/main/java/com/hermie/assistant/llm/LlmEngine.kt`
- **Purpose**: Core inference interface with `generate()`, `loadModel()`, `unloadModel()`, `stopGeneration()`
- **Key type**: `LlmEngine.Message` data class with optional `imageRgb` for vision

### 1.2 `LlamaNativeEngine`
- **Path**: `app/src/main/java/com/hermie/assistant/llm/LlamaNativeEngine.kt`
- **Role**: Production brain on **slot 0**
- **Model**: Configurable — currently Qwen 3.5 (0.8B–8B Q4_K_M), determined by user settings via `HermieSettings.getActiveModelId("brain")`
- **Instance**: Created once in `HermieViewModel`, referenced as both `llamaEngine` (for slot-specific ops) and `engine: LlmEngine` (for generic calls)
- **Singleton/shared state**: Uses the process-wide `LlamaCpp.getInferenceEngine(context)` singleton underneath; `slotMutex` (companion object) is shared with `MindLlmEngine`
- **Key features**: Context shifting, turbo cache support, context pressure detection (`approximateContextUsedPct`), `THINK_PREFIX` token handling
- **Callers**: `HermieViewModel` (chat, tasks), `TaskManager` (task execution), `MemoryModule` (consolidation), `StudyModule` (fact extraction), `WardrobeModule` (categorization)
- **State**: Active/used

### 1.3 `MindLlmEngine`
- **Path**: `app/src/main/java/com/hermie/assistant/llm/MindLlmEngine.kt`
- **Role**: SLM classifier on **slot 1**
- **Model**: SmolLM2-360M-Instruct-Mem-Cat (fine-tuned for memory classification)
- **Instance**: Created once in `HermieViewModel`, referenced as `mindEngine`
- **Slot sharing**: Uses `LlamaNativeEngine.slotMutex` for slot operations; has its own `generationMutex` for generation serialization
- **Output format**: JSON `{"fact":str|null,"retrieve":bool,"tool":bool,"emotion":str}`
- **512-token context**, fully reset per call (no history)
- **System prompt**: Set per-call via `generate(messages, systemPrompt=...)` — supports two modes:
  - `DRIP_ATOMIZER`: Memory classification prompt
  - `NOTIFICATION_FILTER`: DnD notification filtering prompt
- **Callers**: `MemoryModule` (drip atomization, memory classification), `SmartDndModule` (notification filtering), `ScreenTimeModule` (screen time interventions)
- **State**: Active/used

### 1.4 `MockLlmEngine`
- **Path**: `app/src/main/java/com/hermie/assistant/llm/MockLlmEngine.kt`
- **Role**: Dev stub, echoes user input
- **State**: Dead code — not instantiated anywhere; present only for testing/development

### 1.5 `EmbeddingEngine`
- **Path**: `app/src/main/java/com/hermie/assistant/llm/EmbeddingEngine.kt`
- **Role**: TFLite MiniLM-L6-v2 for 384-dim sentence embeddings
- **Uses**: `MemoryModule` for semantic retrieval / edge weight computation
- **Model**: MiniLM-L6-v2 (TFLite format, loaded from `models/mind/`)
- **State**: Active

### 1.6 `InferenceEngine` (underlying JNI wrapper)
- **Path**: `lib/src/main/java/com/hermie/llamacpp/InferenceEngine.kt` + `internal/InferenceEngineImpl.kt`
- **Entry point**: `LlamaCpp.getInferenceEngine(context)` — process-wide singleton
- **State**: Active (all llama.cpp inference goes through this)

---

## 2. Module Registry & Tool Modules

### 2.1 `ModuleRegistry`
- **Path**: `app/src/main/java/com/hermie/assistant/modules/ModuleRegistry.kt`
- **Holds**: `StateFlow<Map<String, HermieModule>>`
- **Filters**: `getToolDefinitionsForMode(mode)` — `BrainMode.CHAT` gives curated subset, `BrainMode.TASKS` gives full inventory
- **Tool execution**: `executeTool(name, params)` iterates `toolModules` and dispatches

### 2.2 All Registered Modules

| Module | Path | Interfaces | Tools Exposed | Chat Mode |
|---|---|---|---|---|
| `AlarmModule` | `modules/tools/AlarmModule.kt` | ToolModule | `alarm.set` | ✅ full |
| `ReminderModule` | `modules/tools/` | ToolModule | (Reminder tools) | ✅ full |
| `CalendarModule` | `modules/tools/CalendarModule.kt` | ToolModule | `calendar.check`, `calendar.add` | ✅ `calendar.check` only |
| `ContactsModule` | `modules/tools/` | ToolModule | Contacts lookup | ❌ |
| `IntentModule` | `modules/tools/` | ToolModule | Android intents | ❌ |
| `WebSearchModule` | `modules/tools/WebSearchModule.kt` | ToolModule | `web.search` | ❌ |
| `ClipboardModule` | `modules/tools/ClipboardModule.kt` | ToolModule | `clipboard.read`, `clipboard.write` | ✅ `clipboard.read` only |
| `WeatherModule` | `modules/tools/WeatherModule.kt` | ToolModule | `weather.now`, `weather.forecast` | ✅ full |
| `OverpassModule` | `modules/tools/` | ToolModule | OpenStreetMap queries | ❌ |
| `DuckDuckGoModule` | `modules/tools/` | ToolModule | Web results | ❌ |
| `WebFetchModule` | `modules/tools/` | ToolModule | URL content fetch | ❌ |
| `ArxivModule` | `modules/tools/ArxivModule.kt` | ToolModule | `arxiv.search` | ❌ |
| `ScreenTimeModule` | `modules/screentime/ScreenTimeModule.kt` | ToolModule, BackgroundModule | `screentime.today`, `screentime.app`, `screentime.limit` | ❌ |
| `SmartDndModule` | `modules/dnd/SmartDndModule.kt` | ToolModule, BackgroundModule | `dnd.toggle`, `dnd.status`, `dnd.add_rule`, `dnd.remove_rule`, `dnd.list_rules`, `dnd.missed`, `dnd.allow_contact`, `dnd.allow_app`, `notification.recent`, `notification.from`, `notification.summary` | ❌ |
| `MemoryModule` | `modules/memory/MemoryModule.kt` | ToolModule, BackgroundModule | `memory.recall`, `memory.store`, `memory.count` | ❌ |
| `NotificationModule` | `modules/notifications/NotificationModule.kt` | ToolModule | `notification.recent`, `notification.from`, `notification.summary` | ❌ |
| `WardrobeModule` | `modules/wardrobe/WardrobeModule.kt` | ScreenModule | (none — UI only) | N/A |
| `StudyModule` | `modules/study/StudyModule.kt` | ScreenModule | (none — UI only) | N/A |

### 2.3 Registration Flow
All modules are registered in `HermieViewModel.initializeModules()`:
1. `ScreenTimeModule` (first — wiring to mind engine + background service)
2. 6 chat-safe tool modules: Alarm, Reminder, Calendar, Contacts, Intent, WebSearch, Clipboard
3. 5 external-data tool modules: Weather, Overpass, DuckDuckGo, WebFetch, Arxiv
4. `SmartDndModule`, `MemoryModule`, `WardrobeModule`, `StudyModule`
5. Background service wires: `HermieBackgroundService.moduleRegistry`, `.taskManager`, `.mindEngine`, `.canAcquireBrain`

### 2.4 No Dead Modules
All registered modules are actively used. `NotificationModule` and `SmartDndModule` both export the same 3 notification tools (`notification.recent`, `.from`, `.summary`) — there is duplication at the tool name level, but `SmartDndModule` additionally provides DnD-specific tools.

---

## 3. Tool Execution Pipeline

### 3.1 User Message Flow

```
User input (text/voice)
    → HermieViewModel.sendMessage()
    → Adds user message to UI
    → If CHAT mode:
        → engine.generate(messages, systemPrompt=chat_prompt)
        → Streaming tokens → UI
        → No tool parsing in chat mode (LLM responds directly via <tool> XML tags, if at all)
    → If TASKS mode:
        → TaskManager planCurrentTask() / executeAllSubtasks()
```

### 3.2 Task Execution (Tasks Mode)
- **`TaskManager`** (`modules/tasks/TaskManager.kt`) is the sole task executor
- **Think/Commit split** in `executeSubtaskIteratively()`:
  - **Pass 1 (Think, temp 0.3)**: Model reasons, ends with `ACTION:`, `DONE:`, or `GIVE_UP:`
  - **Pass 2 (Commit, temp 0.1)**: Model emits exactly one `<tool>`, `<done>`, or `<subtask>` tag
- **Tool tag format**: `<tool>module.func(param="value")</tool>`
- **Parsing**: `fun executeSubtaskIteratively()` uses these regexes:
  - `toolPattern`: `<tool>(.*?)</tool>`
  - `funcPattern`: `(\w+\.\w+)\((.*)\)`
  - `paramPattern`: `(\w+)="([^"]*?)"`
- **Tool dispatch**: `moduleRegistry.executeTool(toolName, params)` — iterates all `toolModules` checking for matching tool name
- **No `parseAndExecuteTools()` function exists** — the name `parseAndExecuteTools` does not appear anywhere in the codebase. Tool parsing is inline in `executeSubtaskIteratively()`.

### 3.3 SLM/Mind Classifier
- **Current role**: Memory classification (drip atomizer) + DnD notification filtering
- **No longer involved in chat routing**: Per `MemoryModule` v4 architecture, "SLM removed from realtime chat path (no more per-message classification)"
- **Drip atomizer flow**: `MemoryModule.onBackgroundTick()` → batches raw messages → `MindLlmEngine.generate()` with memory classification prompt → JSON output → facts buffered for consolidation
- **Notification filter flow**: `SmartDndModule` → `MindLlmEngine.generate()` with DnD filter prompt → `{"action":"ALERT"|"SILENCE","reason":str}`

### 3.4 Background Tick Loop
`HermieBackgroundService.startBackgroundTick()`:
- Runs every `TICK_INTERVAL_MS` (60s)
- Iterates `moduleRegistry.backgroundModules` and calls `onBackgroundTick()` on each active module
- Background modules: `MemoryModule`, `SmartDndModule`, `ScreenTimeModule`

---

## 4. Broken / TODO / FIXME

### 4.1 TODO markers
- **`MainActivity.kt:410`**: `onEditItem = { /* TODO: edit dialog */ }` — task artifact edit dialog not implemented

### 4.2 FIXME/HACK/BROKEN/XXX
- **None found** in any `.kt` file across the codebase

### 4.3 Potential Issues Observed

1. **Notification tool duplication**: Both `NotificationModule` and `SmartDndModule` expose tools named `notification.recent`, `notification.from`, and `notification.summary`. When `executeTool()` is called, the first matching module in iteration order wins (`SmartDndModule` is registered after `NotificationModule` is... actually `NotificationModule` is NOT directly registered — it appears registered only indirectly via `SmartDndModule`). However, `NotificationModule` is listed in `ModuleRegistry` but is not present in the ViewModel's `initializeModules()` call list — **`NotificationModule` may be dead code**.

2. **SLM mode switching without system prompt swap**: `switchMindMode()` in `HermieViewModel` only logs which mode it's switching to — it does NOT actually call `engine.setSystemPrompt()` or `engine.loadModel()` for the SLM. The system prompt swapping is implicit (whoever calls `generate()` passes the prompt). This means if multiple callers race, the system prompt could be wrong for the next generate call.

3. **Slot mutex edge case**: `MindLlmEngine.generate()` holds `slotMutex` for the entire operation, but `LlamaNativeEngine.generate()` does NOT hold it (it runs entirely on slot 0). This is documented as intentional — brain generate never switches slots. However, `TaskManager` calls `engine.generate()` directly (which is `LlamaNativeEngine`), meaning tool execution in Tasks mode never acquires the slot mutex either.

---

## 5. Permissions

From `app/src/main/AndroidManifest.xml`:

| Permission | Purpose |
|---|---|
| `INTERNET` | Model downloads, web search |
| `ACCESS_NETWORK_STATE` | Connectivity checks |
| `ACCESS_COARSE_LOCATION` | Weather (Open-Meteo by lat/lon) |
| `FOREGROUND_SERVICE` | Background tick loop |
| `FOREGROUND_SERVICE_DATA_SYNC` | Background service type |
| `POST_NOTIFICATIONS` | Reminders, DnD alerts |
| `SYSTEM_ALERT_WINDOW` | Overlay bubble (mascot) |
| `PACKAGE_USAGE_STATS` | Screen time tracking |
| `READ_CALENDAR` / `WRITE_CALENDAR` | Calendar tool module |
| `READ_CONTACTS` | DnD contact allowlist |
| `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` | Reminders, task alarms |
| `RECORD_AUDIO` | Voice input (Whisper STT) |
| `VIBRATE` | Notification vibrations |
| `WAKE_LOCK` | Keep device awake during processing |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Background service resilience |
| `BIND_ACCESSIBILITY_SERVICE` | App monitoring |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Notification interception |
| `MANAGE_EXTERNAL_STORAGE` (maxSdk=29) | File export |

---

## 6. Dependencies

From `app/build.gradle.kts` (non-standard Android/Compose deps):

| Dependency | Purpose |
|---|---|
| `:lib` (project) | llama.cpp JNI wrapper — all LLM inference |
| `org.tensorflow:tensorflow-lite:2.16.1` | MiniLM-L6-v2 embeddings for memory |
| `org.apache.commons:commons-compress:1.27.1` | espeak-ng-data tar.bz2 for Piper TTS |
| `sherpa-onnx.aar` (local file) | Whisper STT + Piper TTS (offline voice) |
| `com.tom-roush:pdfbox-android:2.0.27.0` | PDF text extraction (Study module) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0` | Async operations throughout |

No OkHttp, no Room, no CameraX. Network calls use raw `HttpURLConnection` (no HTTP client library).

---

## 7. Key Architectural Notes

### 7.1 Singleton Chain
```
LlamaCpp.getInferenceEngine(context) — process singleton
  → LlamaNativeEngine (wraps it for slot 0)
  → MindLlmEngine (wraps it for slot 1)
```
Both share the same native `InferenceEngine` instance. Slot switching is done via `engine.setActiveSlot(n)` protected by `LlamaNativeEngine.slotMutex`.

### 7.2 System Prompt Contract
TaskManager does NOT swap LLM system prompts. `HermieViewModel` must:
1. Set `tasks_planner.txt` before planning
2. Set `tasks_system.txt` before execution
3. Restore chat prompt in `finally` block

### 7.3 Prompt Files (23 .txt files in `assets/prompts/`)
Task-related: `tasks_planner.txt`, `tasks_system.txt`, `task_executor.txt`, `task_planner.txt`
Chat: `system_prompt_base.txt`, `system_prompt_finetuned.txt`
DnD: `dnd_system.txt`, `dnd_evaluate.txt`, `dnd_summarize.txt`
Screen time: `screentime_system.txt`, `screentime_trigger.txt`, `screentime_close.txt`, `screentime_giveup.txt`, `screentime_redismiss.txt`, `screentime_reopen.txt`, `screentime_reply.txt`
Memory: `mind_system.txt`, `drip_system.txt`, `consolidation_personal.txt`, `consolidation_study.txt`, `exploratory_link.txt`
Wardrobe: `wardrobe_categorize.txt`, `wardrobe_outfit.txt`

### 7.4 Voice Pipeline
- **STT**: `SherpaOnnxSttEngine` (Whisper tiny.en via sherpa-onnx) — loaded on demand
- **TTS**: `PiperTtsEngine` (en_US-lessac-medium via sherpa-onnx) — loaded on demand
- **Coordinator**: `SpeechManager` handles listening states, wake-word, TTS queue

### 7.5 UI Architecture
- `MainActivity` → `HermieNavigation` (sealed class routes: home, chat, tasks, settings, onboarding, module/{id})
- Single shared `HermieViewModel` across all Composables
- `MascotState` / `MascotMood` state machine drives idle/thinking/speaking/error animations
- Bubble overlay via `BubbleActivity` (dismissible, icon cached with schema versioning)
