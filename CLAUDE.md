# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Debug APK (from repo root)
./gradlew assembleDebug

# Release APK
./gradlew assembleRelease

# Install on connected device
./gradlew installDebug

# Run lint
./gradlew lint

# Build the native lib module only
./gradlew :lib:assembleDebug
```

There are no unit tests. UI testing requires a physical arm64 device (emulators lack the NDK ABI).

## Project Structure

```
app/          Android app module
lib/          llama.cpp JNI wrapper (Kotlin + C++ via CMake)
llama.cpp/    llama.cpp git submodule (built from source)
```

`app` depends on `:lib` for all native inference. The `:lib` module exposes `LlamaCpp.getInferenceEngine(context)` which returns a singleton `InferenceEngine`. All LLM calls go through this singleton.

## Architecture

### LLM Layer

`LlmEngine` interface (`llm/LlmEngine.kt`) — three implementations:
- `LlamaNativeEngine` — production brain on **slot 0**. Streaming via Kotlin Flow. Context shifting enabled.
- `MindLlmEngine` — lightweight SLM classifier on **slot 1** (SmolLM2-360M finetuned). 512-token context, fully reset per call. Used for memory classification.
- `MockLlmEngine` — dev stub that echoes input.

**Critical invariant**: `LlamaNativeEngine.slotMutex` serializes all slot-level operations across both engines. Never call `setActiveSlot`, `resetContext`, `setSystemPrompt`, or `loadModel` without holding this mutex, or system prompts land on the wrong slot.

`MindLlmEngine.generationMutex` is held for the duration of every generation. `HermieBackgroundService` acquires it (then releases) during graceful shutdown to drain in-flight SLM calls before unloading.

### Module System

`HermieModule` (`modules/HermieModule.kt`) is the base interface. Modules implement one or more of:
- `ToolModule` — exposes `toolDefinitions` the LLM can call. `availableInChatMode` + `chatModeToolNames` gate which tools appear in chat vs. Tasks mode.
- `ScreenModule` — provides a Composable `Screen()` shown on the home page.
- `BackgroundModule` — participates in the 60s background tick loop.

`ModuleRegistry` owns all modules. It filters tools per `BrainMode.CHAT` / `BrainMode.TASKS` at prompt-assembly time — all modules register regardless of mode.

### Service & Lifecycle

`HermieBackgroundService` is a foreground service started at app launch that persists when the user swipes away the task. It runs a 60s tick loop calling `onBackgroundTick()` on active `BackgroundModule`s.

Two modes driven by `ProcessLifecycleOwner` in `HermieApplication`:
- **Full** — UI active, all models loadable on demand.
- **Minimal** — app backgrounded (after 30s debounce). `onGoMinimal` callback unloads brain/vision/voice; Mind LLM stays resident for screen time and DnD.

The service communicates with the ViewModel via static lambdas (`onGoMinimal`, `onGoFull`, `canAcquireBrain`, `mindEngine`, `taskManager`, `moduleRegistry`). These are set once by `HermieViewModel`.

### ViewModel

`HermieViewModel` is the single shared ViewModel for the entire app. It owns all engine instances, the `ModuleRegistry`, `TaskManager`, `SpeechManager`, `EmbeddingEngine`, and all UI `StateFlow`s. Everything is accessible from any Composable via `viewModel<HermieViewModel>()`.

### Prompts

All LLM prompts live in `app/src/main/assets/prompts/` as `.txt` files. `PromptLoader` loads them on demand with `{placeholder}` substitution. Prompts are cached in memory after first load. To add a prompt, drop a `.txt` file in `assets/prompts/` and call `PromptLoader.loadAndFill(context, "my_prompt.txt", vars)`.

### Settings

`HermieSettings` wraps SharedPreferences (`hermie_settings`). Model paths use the convention `active_model_<typeSubDir>` (e.g. `active_model_brain`). Per-`ModelType` active model IDs are stored via `getActiveModelId(typeSubDir)` / `setActiveModelId(typeSubDir, id)`.

### Model Management

`ModelManager` defines all downloadable models grouped by `ModelType` (BRAIN, EARS, VOICE, MIND, SLM, VISION). Brain models: Qwen 3.5 (0.8B–8B Q4_K_M) as base models; Qwen 2.5 finetuned variants require a HuggingFace token stored in `HermieSettings.hfToken`. Models download to `context.filesDir/<ModelType.subDir>/`.

### UI & Navigation

Jetpack Compose + Material3. All screens are Composables. Navigation uses `Screen` sealed class routes (`home`, `chat`, `tasks`, `settings`, `onboarding`, `module/{moduleId}`). The mascot state machine lives in `ui/mascot/MascotState.kt` (`MascotMood` enum drives idle/thinking/speaking/error animations).

## Color Palette

Defined in `ui/theme/Color.kt`. Use named constants — never hardcode hex.

| Name | Hex | Role |
|---|---|---|
| `HermieForest` | `#344C3D` | Primary / text |
| `HermieTerra` | `#B57B66` | Secondary / accent |
| `HermieCream` | `#FFFEFС` | Surface |
| `HermieTan` | `#DECEBF` | Borders |
| `HermieGrey` | `#A1A79E` | Muted text |

## App Rename

Change `AppConfig.APP_NAME` in `AppConfig.kt` — propagates to UI, prompts, and notifications everywhere.

## Key Constraints

- **arm64-v8a only** — ABI filter is intentional; llama.cpp native libs target ARM NEON/i8mm.
- **minSdk 28** — foreground services and usage stats APIs require Android 9+.
- **`useLegacyPackaging = true`** in `jniLibs` — required so `ggml_backend_load_all_from_path()` can locate `.so` files on disk.
- **`libc++_shared.so` conflict** — Sherpa-ONNX and llama.cpp both ship it; `pickFirsts` resolves this.
- **Bubble icon cache** — bump `BUBBLE_ICON_SCHEMA_VERSION` in `HermieApplication` whenever `MascotBitmapRenderer` drawing logic changes to force a cache wipe on next launch.
