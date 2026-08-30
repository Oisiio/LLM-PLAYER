# SPECIFICATION — Phase 4-G-1: JNI Streaming Generation

## 1. Overview
Phase 4-G-1 introduces token-by-token streaming generation from the native C++ inference engine (`llama.cpp`) to the Android Kotlin runtime via JNI.
It enables real-time token reception as each token is generated, while maintaining 100% backward compatibility with existing synchronous batch generation APIs and all sampling parameters (Temperature, Top-K, Top-P, Min-P, Typical-P, Repetition Penalty).

## 2. TokenCallback Interface Specification (Kotlin)

```kotlin
fun interface TokenCallback {
    /**
     * Called synchronously from native code for each generated token piece.
     *
     * @param tokenPiece The string decoded from the generated token.
     * @return true to continue generation, false to abort generation (USER_CANCEL).
     */
    fun onToken(tokenPiece: String): Boolean
}
```

* **Interface Type**: Functional interface (`fun interface`) to allow SAM conversion with Kotlin lambdas.
* **Return Value**:
  * `true`: Continue generating next tokens.
  * `false`: Abort generation immediately. The native inference loop breaks with `stop_reason = "USER_CANCEL"`.

## 3. JNI Streaming API Specification

### 3.1 JNI Function Signature (C++)
```cpp
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_MainActivity_nativeGenerateStreaming(
    JNIEnv* env,
    jobject thiz,
    jstring prompt,
    jfloat temperature,
    jint top_k,
    jfloat top_p,
    jfloat min_p,
    jobject callback
);
```

### 3.2 Kotlin External Declaration
```kotlin
external fun nativeGenerateStreaming(
    prompt: String,
    temperature: Float,
    topK: Int,
    topP: Float,
    minP: Float,
    callback: TokenCallback?
): String
```

### 3.3 Arguments
1. `prompt`: Input prompt string (supports inline sampling tags like `[min_p=0.05]` and `[typical_p=0.95]`).
2. `temperature`: Sampling temperature.
3. `topK`: Top-K token cutoff.
4. `topP`: Top-P (nucleus) sampling threshold.
5. `minP`: Min-P sampling threshold.
6. `callback`: `TokenCallback` instance or `null`. If `null`, behaves like batch generation.

### 3.4 Return Value
Returns a `jstring` containing the complete generated text, followed by metadata and benchmark metrics (same format as batch generation):
* `GENERATED TOKEN COUNT: ...`
* `STOP REASON: ...` (e.g. `USER_CANCEL`, `EOG_TOKEN`, `MAX_TOKENS`, `<END>`)
* `TYPICAL-P: ...`
* `MIN-P: ...`
* `PROMPT TIME: ...`
* `TTFT: ...`
* `GENERATION TIME: ...`
* `TOTAL TIME: ...`
* `SPEED: ...`

## 4. Native → Kotlin Callback Mechanism & Thread Model

### 4.1 Invocation Flow
1. Inference is invoked from Kotlin coroutine on a background thread (e.g. `Dispatchers.Default` or `Dispatchers.IO`).
2. Inside `generate_sampling_locked(...)`, after each token is sampled and decoded into a string piece (`llama_token_to_piece`), if a callback is provided:
   * Look up `jclass` and `jmethodID` for `onToken(Ljava/lang/String;)Z`.
   * Convert `std::string` piece to `jstring` via `env->NewStringUTF(piece.c_str())`.
   * Call `env->CallBooleanMethod(callback, method_id, piece_jstr)`.
   * Check for exceptions (`env->ExceptionCheck()`). If an exception occurred, clear it (`env->ExceptionClear()`) and abort generation safely.
   * Check return boolean: if `false`, set `stop_reason = "USER_CANCEL"` and break from generation loop.
3. **Local Reference Management**:
   * Every call creates local references (`piece_jstr`, `callback_class`).
   * Explicitly call `env->DeleteLocalRef` inside the generation loop to prevent JNI local reference table overflow (Android limits local references to 512 by default).

### 4.2 Thread Model
* The callback `onToken` is executed synchronously on the calling thread (native caller thread).
* Android UI updates from the callback should be dispatched to `Dispatchers.Main` / main thread when updating Compose state.

## 5. Cancellation & Abort Specification
* When `callback.onToken(piece)` returns `false`:
  * Native loop terminates immediately without evaluating further tokens.
  * Stop reason is explicitly recorded as `USER_CANCEL`.
  * KV cache and context remain valid and consistent.
  * Inference lock `g_model_mutex` is unlocked cleanly via `std::lock_guard`.
  * Full generated text up to the cancellation point is returned alongside metrics.

## 6. Backward Compatibility & Sampling Integrity
* All existing batch generation APIs remain intact:
  * `nativeRunTestInference()`
  * `nativeEchoPrompt(prompt)`
  * `nativeGenerateWithTemperature(...)`
  * `nativeGenerateWithTemperatureAndTopK(...)`
  * `nativeGenerateWithTemperatureTopKTopP(...)`
  * `nativeGenerateWithTemperatureTopKTopPMinP(...)`
* Core generation logic `generate_sampling_locked` takes an optional `std::function<bool(const std::string&)>` hook. Batch APIs pass `nullptr` or empty lambda, resulting in zero overhead.
* `third_party/llama.cpp` is never modified.
* All sampling parameters (Temperature, Top-K, Typical-P, Top-P, Min-P, Repetition Penalty) apply identically in both streaming and batch modes.
