# CHECKPOINT — Phase 4-G-1 JNI Streaming Generation
Date: 2026-08-30
Status: COMPLETE / PASS

## 1. Overview
Phase 4-G-1 implements native-to-Kotlin token-by-token streaming inference via JNI while preserving all sampling capabilities and backward compatibility.

## 2. Implemented Architecture & Specifications

### 2.1 Interface & JNI API
- **TokenCallback (Kotlin)**:
  ```kotlin
  fun interface TokenCallback {
      fun onToken(tokenPiece: String): Boolean
  }
  ```
- **JNI Streaming Function (C++)**:
  ```cpp
  extern "C" JNIEXPORT jstring JNICALL
  Java_com_example_MainActivity_nativeGenerateStreaming(
      JNIEnv* env, jobject thiz,
      jstring prompt, jfloat temperature, jint top_k, jfloat top_p, jfloat min_p, jobject callback
  );
  ```
- **External Function Declaration (Kotlin)**:
  ```kotlin
  private external fun nativeGenerateStreaming(
      prompt: String,
      temperature: Float,
      topK: Int,
      topP: Float,
      minP: Float,
      callback: TokenCallback?
  ): String
  ```

### 2.2 Callback Execution & Memory Management
- In `generate_sampling_locked(...)`, after each token is generated and decoded into `tokenPiece`, `callback.onToken(piece)` is called via JNI.
- **JNI Local Reference Management**: `env->DeleteLocalRef(piece_jstr)` is called immediately on every token generation step to prevent local reference table overflow.
- **Exception Safety**: If an exception occurs in Kotlin callback, it is caught with `env->ExceptionCheck()`, cleared via `env->ExceptionClear()`, and native inference terminates gracefully.
- **Cancellation (USER_CANCEL)**:
  - If `callback.onToken` returns `false`, native loop immediately terminates with `stop_reason = "USER_CANCEL"`.
  - The generated tokens up to cancellation are retained, model lock is cleanly released, and metrics report is returned.

### 2.3 Compatibility & Sampling Integrity
- **Batch APIs Intact**: All existing batch inference functions (`nativeGenerateWithTemperatureTopKTopP`, `nativeRunTestInference`, etc.) remain intact without modification.
- **Sampling Pipeline Unchanged**: Temperature, Top-K, Typical-P, Top-P, Min-P, and Repetition Penalty operate identically in both streaming and batch modes.
- **third_party/llama.cpp**: **Zero modifications** (untouched).

## 3. Build & Artifact Verification
- **Target Platform**: Android ARM64-v8a
- **Compilation Tool**: `compile_applet` -> PASS (Build succeeded)
- **Gradle Task**: `gradle :app:assembleDebug` -> PASS
- **Unit Tests**: `gradle :app:testDebugUnitTest` -> PASS (BUILD SUCCESSFUL)
- **Generated APK**: `app/build/outputs/apk/debug/app-debug.apk` (39MB)

## 4. Verification Checklist
- [x] Streaming API initialization and execution
- [x] Token piece callback dispatch per generated token
- [x] End-to-end generation completion with metrics
- [x] Cancellation support (`USER_CANCEL`) when callback returns `false`
- [x] Backward compatibility of batch generation APIs (`send_prompt_button`)
- [x] Preservation of all sampling parameters (Temperature, Top-K, Typical-P, Top-P, Min-P, Repetition Penalty)
- [x] Verification that `third_party/llama.cpp` was not modified

## 5. Modified Files
1. `SPEC_PHASE_4_G_1.md` (Created)
2. `CHECKPOINT_PHASE_4_G1.md` (Created)
3. `app/src/main/cpp/native-lib.cpp` (Added streaming callback hook and `nativeGenerateStreaming` JNI function)
4. `app/src/main/java/com/example/MainActivity.kt` (Added `TokenCallback`, external declaration, UI streaming/cancel test buttons)
