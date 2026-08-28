#include <jni.h>
#include <android/log.h>
#include <sys/stat.h>

#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

namespace {
constexpr char kLogTag[] = "LLM-PLAYER";

std::mutex g_model_mutex;
llama_model * g_model = nullptr;
llama_context * g_context = nullptr;

void unload_model_locked() {
    if (g_context != nullptr) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

bool tokenize_prompt(const std::string & prompt, std::vector<llama_token> & tokens) {
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    if (vocab == nullptr) {
        return false;
    }

    const int32_t count = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    if (count <= 0) {
        return false;
    }

    tokens.resize(static_cast<size_t>(count));
    const int32_t result = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(), count, true, true);
    return result == count;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_MainActivity_stringFromJNI(JNIEnv* env, jobject /* this */) {
    return env->NewStringUTF("Hello from native C++");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_MainActivity_nativeLoadModel(JNIEnv* env, jobject /* this */, jstring model_path) {
    if (model_path == nullptr) {
        return env->NewStringUTF("ERROR: model path is null");
    }

    const char * path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) {
        return env->NewStringUTF("ERROR: failed to read model path");
    }

    std::lock_guard<std::mutex> lock(g_model_mutex);
    struct stat file_stat {};
    if (stat(path, &file_stat) != 0 || !S_ISREG(file_stat.st_mode)) {
        env->ReleaseStringUTFChars(model_path, path);
        return env->NewStringUTF("ERROR: model file does not exist or is not a regular file");
    }

    unload_model_locked();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
    g_model = llama_model_load_from_file(path, model_params);

    if (g_model == nullptr) {
        env->ReleaseStringUTFChars(model_path, path);
        return env->NewStringUTF("ERROR: llama_model_load_from_file failed");
    }

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = 512;
    context_params.n_batch = 512;
    context_params.n_threads = 4;
    context_params.n_threads_batch = 4;
    g_context = llama_init_from_model(g_model, context_params);

    env->ReleaseStringUTFChars(model_path, path);

    if (g_context == nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
        return env->NewStringUTF("ERROR: model loaded, but llama_init_from_model failed");
    }

    return env->NewStringUTF("SUCCESS: GGUF model + context loaded");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_MainActivity_nativeRunTestInference(JNIEnv* env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_model_mutex);

    if (g_model == nullptr || g_context == nullptr) {
        return env->NewStringUTF("ERROR: model/context is not loaded");
    }

    const std::string prompt = "こんにちは。短く自己紹介してください。";
    std::vector<llama_token> tokens;
    if (!tokenize_prompt(prompt, tokens)) {
        return env->NewStringUTF("ERROR: llama_tokenize failed");
    }
    if (tokens.empty() || tokens.size() > 512) {
        return env->NewStringUTF("ERROR: invalid token count");
    }

    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    const int32_t decode_result = llama_decode(g_context, batch);
    if (decode_result != 0) {
        return env->NewStringUTF("ERROR: llama_decode failed");
    }

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    const float * logits = llama_get_logits(g_context);
    if (vocab == nullptr || logits == nullptr) {
        return env->NewStringUTF("ERROR: logits are unavailable");
    }

    const int32_t vocab_size = llama_vocab_n_tokens(vocab);
    if (vocab_size <= 0) {
        return env->NewStringUTF("ERROR: invalid vocabulary size");
    }

    int32_t best_token = 0;
    float best_logit = logits[0];
    for (int32_t i = 1; i < vocab_size; ++i) {
        if (logits[i] > best_logit) {
            best_logit = logits[i];
            best_token = i;
        }
    }

    char token_text[256] = {};
    const int32_t token_length = llama_token_to_piece(vocab, best_token, token_text, static_cast<int32_t>(sizeof(token_text)), 0, true);
    if (token_length < 0) {
        return env->NewStringUTF("ERROR: llama_token_to_piece failed");
    }

    const std::string result = "SUCCESS: inference step completed\nTOKEN COUNT: " + std::to_string(tokens.size()) +
        "\nBEST TOKEN ID: " + std::to_string(best_token) +
        "\nBEST TOKEN PIECE: " + std::string(token_text, static_cast<size_t>(token_length));
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "First inference step completed");
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_MainActivity_nativeUnloadModel(JNIEnv* /* env */, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    unload_model_locked();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_MainActivity_nativeIsModelLoaded(JNIEnv* /* env */, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    return (g_model != nullptr && g_context != nullptr) ? JNI_TRUE : JNI_FALSE;
}
