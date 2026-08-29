#include <jni.h>
#include <android/log.h>
#include <sys/stat.h>

#include <algorithm>
#include <cmath>
#include <mutex>
#include <random>
#include <string>
#include <vector>

#include "llama.h"

namespace {
constexpr char kLogTag[] = "LLM-PLAYER";
constexpr float kTemperature = 0.7f;
constexpr int32_t kTopK = 40;
constexpr float kTopP = 0.9f;

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

    const int32_t required_signed = llama_tokenize(
        vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
        nullptr, 0, true, false);

    if (required_signed >= 0) {
        return false;
    }

    const int32_t required = -required_signed;
    if (required <= 0) {
        return false;
    }

    tokens.resize(static_cast<size_t>(required));
    const int32_t actual = llama_tokenize(
        vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
        tokens.data(), required, true, false);

    if (actual < 0) {
        tokens.clear();
        return false;
    }
    if (actual != required) {
        tokens.resize(static_cast<size_t>(actual));
    }
    return !tokens.empty();
}

llama_token sample_temperature_top_k_top_p(
    const float * logits,
    int32_t vocab_size,
    float temperature,
    int32_t top_k,
    float top_p,
    std::mt19937 & rng) {
    if (vocab_size <= 0) {
        return 0;
    }

    top_k = std::max<int32_t>(1, std::min<int32_t>(top_k, vocab_size));
    top_p = std::max(0.0f, std::min(1.0f, top_p));

    std::vector<int32_t> indices(static_cast<size_t>(vocab_size));
    for (int32_t i = 0; i < vocab_size; ++i) {
        indices[static_cast<size_t>(i)] = i;
    }

    std::partial_sort(
        indices.begin(), indices.begin() + top_k, indices.end(),
        [logits](int32_t a, int32_t b) { return logits[a] > logits[b]; });

    if (temperature <= 0.0f) {
        return static_cast<llama_token>(indices[0]);
    }

    float max_logit = logits[indices[0]];
    for (int32_t i = 1; i < top_k; ++i) {
        max_logit = std::max(max_logit, logits[indices[static_cast<size_t>(i)]]);
    }

    std::vector<float> probabilities(static_cast<size_t>(top_k));
    double probability_sum = 0.0;
    for (int32_t i = 0; i < top_k; ++i) {
        const int32_t token_index = indices[static_cast<size_t>(i)];
        const double scaled =
            (static_cast<double>(logits[token_index]) - max_logit) / temperature;
        const float probability = static_cast<float>(std::exp(scaled));
        probabilities[static_cast<size_t>(i)] = probability;
        probability_sum += probability;
    }

    if (!(probability_sum > 0.0) || !std::isfinite(probability_sum)) {
        return static_cast<llama_token>(indices[0]);
    }

    // Convert the Top-K candidates into normalized probabilities, then keep
    // the smallest prefix whose cumulative probability reaches Top-P.
    int32_t candidate_count = top_k;
    if (top_p < 1.0f) {
        double cumulative = 0.0;
        candidate_count = 0;
        for (int32_t i = 0; i < top_k; ++i) {
            cumulative += probabilities[static_cast<size_t>(i)] / probability_sum;
            ++candidate_count;
            if (cumulative >= static_cast<double>(top_p)) {
                break;
            }
        }
    }

    double filtered_probability_sum = 0.0;
    for (int32_t i = 0; i < candidate_count; ++i) {
        filtered_probability_sum += probabilities[static_cast<size_t>(i)];
    }

    if (!(filtered_probability_sum > 0.0) || !std::isfinite(filtered_probability_sum)) {
        return static_cast<llama_token>(indices[0]);
    }

    std::uniform_real_distribution<double> distribution(0.0, filtered_probability_sum);
    const double target = distribution(rng);
    double cumulative = 0.0;

    for (int32_t i = 0; i < candidate_count; ++i) {
        cumulative += probabilities[static_cast<size_t>(i)];
        if (target <= cumulative) {
            return static_cast<llama_token>(indices[static_cast<size_t>(i)]);
        }
    }

    return static_cast<llama_token>(indices[static_cast<size_t>(candidate_count - 1)]);
}

std::string generate_sampling_locked(
    const std::string & prompt_text,
    float temperature = kTemperature,
    int32_t top_k = kTopK) {
    std::vector<llama_token> tokens;
    if (!tokenize_prompt(prompt_text, tokens)) {
        return "ERROR: llama_tokenize failed";
    }
    if (tokens.empty() || tokens.size() > 512) {
        return "ERROR: invalid token count";
    }

    llama_memory_clear(llama_get_memory(g_context), true);

    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    if (llama_decode(g_context, batch) != 0) {
        return "ERROR: llama_decode failed";
    }

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    if (vocab == nullptr) {
        return "ERROR: vocab is unavailable";
    }

    const int32_t vocab_size = llama_vocab_n_tokens(vocab);
    if (vocab_size <= 0) {
        return "ERROR: invalid vocabulary size";
    }

    constexpr int32_t kMaxGenTokens = 128;
    constexpr int32_t kMaxContextTokens = 512;
    const llama_token eos_token = llama_vocab_eos(vocab);
    std::mt19937 rng(std::random_device{}());

    std::string generated_text;
    int32_t generated_count = 0;

    for (int32_t i = 0; i < kMaxGenTokens; ++i) {
        if (static_cast<int32_t>(tokens.size()) + generated_count >= kMaxContextTokens) {
            break;
        }

        const float * logits = llama_get_logits(g_context);
        if (logits == nullptr) {
            return "ERROR: logits are unavailable";
        }

        const llama_token current_token = sample_temperature_top_k_top_p(
            logits, vocab_size, temperature, top_k, kTopP, rng);

        if (llama_vocab_is_eog(vocab, current_token) || current_token == eos_token) {
            break;
        }

        char token_text[256] = {};
        const int32_t token_length = llama_token_to_piece(
            vocab, current_token, token_text, static_cast<int32_t>(sizeof(token_text)), 0, true);
        if (token_length < 0) {
            return "ERROR: llama_token_to_piece failed";
        }
        if (token_length > 0) {
            generated_text.append(token_text, static_cast<size_t>(token_length));
        }

        generated_count++;
        if (generated_count >= kMaxGenTokens) {
            break;
        }

        llama_token next_token = current_token;
        llama_batch token_batch = llama_batch_get_one(&next_token, 1);
        if (llama_decode(g_context, token_batch) != 0) {
            return "ERROR: llama_decode failed";
        }
    }

    return "SUCCESS: temperature + top-k + top-p sampling completed\n"
        "TEMPERATURE: " + std::to_string(temperature) + "\n"
        "TOP-K: " + std::to_string(top_k) + "\n"
        "TOP-P: " + std::to_string(kTopP) + "\n"
        "PROMPT: " + prompt_text + "\n"
        "PROMPT TOKEN COUNT: " + std::to_string(tokens.size()) + "\n"
        "GENERATED TOKEN COUNT: " + std::to_string(generated_count) + "\n"
        "GENERATED TEXT: " + generated_text;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_MainActivity_stringFromJNI(JNIEnv* env, jobject /* this */) {
    return env->NewStringUTF("Hello from native C++");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_MainActivity_nativeLoadModel(JNIEnv* env, jobject /* this */, jstring model_path) {
    if (model_path == nullptr) return env->NewStringUTF("ERROR: model path is null");
    const char * path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) return env->NewStringUTF("ERROR: failed to read model path");

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
    const std::string result = generate_sampling_locked("こんにちは。短く自己紹介してください。");
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_MainActivity_nativeEchoPrompt(JNIEnv* env, jobject /* this */, jstring prompt) {
    if (prompt == nullptr) return env->NewStringUTF("ERROR: prompt is null");
    const char * prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    if (prompt_chars == nullptr) return env->NewStringUTF("ERROR: failed to read prompt");
    const std::string prompt_text(prompt_chars);
    env->ReleaseStringUTFChars(prompt, prompt_chars);

    std::lock_guard<std::mutex> lock(g_model_mutex);
    if (g_model == nullptr || g_context == nullptr) {
        return env->NewStringUTF("ERROR: model/context is not loaded");
    }
    const std::string result = generate_sampling_locked(prompt_text);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_MainActivity_nativeGenerateWithTemperature(
    JNIEnv* env, jobject /* this */, jstring prompt, jfloat temperature) {
    if (prompt == nullptr) return env->NewStringUTF("ERROR: prompt is null");
    const char * prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    if (prompt_chars == nullptr) return env->NewStringUTF("ERROR: failed to read prompt");
    const std::string prompt_text(prompt_chars);
    env->ReleaseStringUTFChars(prompt, prompt_chars);

    std::lock_guard<std::mutex> lock(g_model_mutex);
    if (g_model == nullptr || g_context == nullptr) {
        return env->NewStringUTF("ERROR: model/context is not loaded");
    }
    const std::string result = generate_sampling_locked(prompt_text, static_cast<float>(temperature));
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_MainActivity_nativeGenerateWithTemperatureAndTopK(
    JNIEnv* env, jobject /* this */, jstring prompt, jfloat temperature, jint top_k) {
    if (prompt == nullptr) return env->NewStringUTF("ERROR: prompt is null");
    const char * prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    if (prompt_chars == nullptr) return env->NewStringUTF("ERROR: failed to read prompt");
    const std::string prompt_text(prompt_chars);
    env->ReleaseStringUTFChars(prompt, prompt_chars);

    std::lock_guard<std::mutex> lock(g_model_mutex);
    if (g_model == nullptr || g_context == nullptr) {
        return env->NewStringUTF("ERROR: model/context is not loaded");
    }
    const std::string result = generate_sampling_locked(
        prompt_text, static_cast<float>(temperature), static_cast<int32_t>(top_k));
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
