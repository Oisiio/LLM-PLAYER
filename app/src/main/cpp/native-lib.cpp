#include <jni.h>
#include <android/log.h>
#include <sys/stat.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <mutex>
#include <random>
#include <string>
#include <unordered_set>
#include <vector>

#include "llama.h"

namespace {
constexpr char kLogTag[] = "LLM-PLAYER";
constexpr float kTemperature = 0.7f;
constexpr int32_t kTopK = 40;
constexpr float kTopP = 0.9f;
constexpr float kMinP = 0.0f;
constexpr float kTypicalP = 1.0f;
constexpr float kRepetitionPenalty = 1.1f;
constexpr int32_t kPenaltyLastN = 64;
constexpr int64_t kSeed = 12345;

float g_default_min_p = kMinP;
float g_default_typical_p = kTypicalP;

std::mutex g_model_mutex;
llama_model * g_model = nullptr;
llama_context * g_context = nullptr;

void unload_model_locked() {
    if (g_context != nullptr) { llama_free(g_context); g_context = nullptr; }
    if (g_model != nullptr) { llama_model_free(g_model); g_model = nullptr; }
}

bool tokenize_prompt(const std::string & prompt, std::vector<llama_token> & tokens) {
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    if (vocab == nullptr) return false;
    const int32_t required_signed = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, false);
    if (required_signed >= 0) return false;
    const int32_t required = -required_signed;
    if (required <= 0) return false;
    tokens.resize(static_cast<size_t>(required));
    const int32_t actual = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(), required, true, false);
    if (actual < 0) { tokens.clear(); return false; }
    if (actual != required) tokens.resize(static_cast<size_t>(actual));
    return !tokens.empty();
}

void parse_prompt_sampling_tags(std::string & prompt, float & min_p, float & typical_p) {
    if (prompt.size() < 8 || prompt.front() != '[') return;
    const size_t close_pos = prompt.find(']');
    if (close_pos == std::string::npos || close_pos >= 40) return;
    const std::string tag = prompt.substr(1, close_pos - 1);
    const size_t sep_pos = tag.find_first_of("=:");
    if (sep_pos == std::string::npos) return;
    std::string key = tag.substr(0, sep_pos);
    const std::string val = tag.substr(sep_pos + 1);
    while (!key.empty() && (key.front() == ' ' || key.front() == '\t')) key.erase(0, 1);
    while (!key.empty() && (key.back() == ' ' || key.back() == '\t')) key.pop_back();
    try {
        const float parsed = std::stof(val);
        if (key == "min_p" || key == "min-p" || key == "Min-P" || key == "minP") min_p = std::max(0.0f, std::min(1.0f, parsed));
        else if (key == "typical_p" || key == "typical-p" || key == "Typical-P" || key == "typicalP") typical_p = std::max(0.0f, std::min(1.0f, parsed));
        else return;
        prompt = prompt.substr(close_pos + 1);
        while (!prompt.empty() && (prompt.front() == ' ' || prompt.front() == '\t')) prompt.erase(0, 1);
    } catch (...) {}
}

llama_token sample_with_sampling_filters(const float * logits, int32_t vocab_size, float temperature, int32_t top_k, float top_p, float min_p, float typical_p, std::mt19937 & rng) {
    if (vocab_size <= 0) return 0;
    top_k = std::max<int32_t>(1, std::min<int32_t>(top_k, vocab_size));
    top_p = std::max(0.0f, std::min(1.0f, top_p));
    min_p = std::max(0.0f, std::min(1.0f, min_p));
    typical_p = std::max(0.0f, std::min(1.0f, typical_p));

    std::vector<int32_t> indices(static_cast<size_t>(vocab_size));
    for (int32_t i = 0; i < vocab_size; ++i) indices[static_cast<size_t>(i)] = i;
    std::partial_sort(indices.begin(), indices.begin() + top_k, indices.end(), [logits](int32_t a, int32_t b) { return logits[a] > logits[b]; });
    if (temperature <= 0.0f) return static_cast<llama_token>(indices[0]);

    const float max_logit = logits[indices[0]];
    std::vector<double> probabilities(static_cast<size_t>(top_k));
    double probability_sum = 0.0;
    for (int32_t i = 0; i < top_k; ++i) {
        const double scaled = (static_cast<double>(logits[indices[static_cast<size_t>(i)]]) - max_logit) / temperature;
        const double probability = std::exp(scaled);
        probabilities[static_cast<size_t>(i)] = probability;
        probability_sum += probability;
    }
    if (!(probability_sum > 0.0) || !std::isfinite(probability_sum)) return static_cast<llama_token>(indices[0]);
    for (double & p : probabilities) p /= probability_sum;

    // Typical-P: compute entropy and retain tokens whose surprisal is closest
    // to the expected information content. Non-typical candidates are assigned
    // zero probability while preserving the original probability order.
    if (typical_p < 1.0f) {
        double entropy = 0.0;
        for (double p : probabilities) if (p > 0.0) entropy -= p * std::log(p);
        std::vector<int32_t> order(static_cast<size_t>(top_k));
        for (int32_t i = 0; i < top_k; ++i) order[static_cast<size_t>(i)] = i;
        std::stable_sort(order.begin(), order.end(), [&probabilities, entropy](int32_t a, int32_t b) {
            const double da = std::fabs(-std::log(probabilities[static_cast<size_t>(a)]) - entropy);
            const double db = std::fabs(-std::log(probabilities[static_cast<size_t>(b)]) - entropy);
            if (da != db) return da < db;
            return a < b;
        });
        std::vector<bool> keep(static_cast<size_t>(top_k), false);
        double cumulative = 0.0;
        for (int32_t pos : order) {
            const double p = probabilities[static_cast<size_t>(pos)];
            keep[static_cast<size_t>(pos)] = true;
            cumulative += p;
            if (cumulative >= static_cast<double>(typical_p)) break;
        }
        for (int32_t i = 0; i < top_k; ++i) if (!keep[static_cast<size_t>(i)]) probabilities[static_cast<size_t>(i)] = 0.0;
    }

    // Top-P and Min-P operate on the surviving candidates. The original
    // ordering remains descending by probability, so no additional sort is needed.
    if (top_p < 1.0f) {
        double cumulative = 0.0;
        bool reached = false;
        for (int32_t i = 0; i < top_k; ++i) {
            const double p = probabilities[static_cast<size_t>(i)];
            if (p <= 0.0) continue;
            if (!reached) {
                cumulative += p;
                if (cumulative >= static_cast<double>(top_p)) reached = true;
            } else probabilities[static_cast<size_t>(i)] = 0.0;
        }
    }

    if (min_p > 0.0f) {
        double max_prob = 0.0;
        for (double p : probabilities) max_prob = std::max(max_prob, p);
        const double threshold = max_prob * static_cast<double>(min_p);
        for (double & p : probabilities) if (p > 0.0 && p < threshold) p = 0.0;
    }

    double filtered_probability_sum = 0.0;
    for (double p : probabilities) filtered_probability_sum += p;
    if (!(filtered_probability_sum > 0.0) || !std::isfinite(filtered_probability_sum)) return static_cast<llama_token>(indices[0]);
    std::uniform_real_distribution<double> distribution(0.0, filtered_probability_sum);
    const double target = distribution(rng);
    double cumulative = 0.0;
    for (int32_t i = 0; i < top_k; ++i) {
        cumulative += probabilities[static_cast<size_t>(i)];
        if (target <= cumulative) return static_cast<llama_token>(indices[static_cast<size_t>(i)]);
    }
    for (int32_t i = top_k - 1; i >= 0; --i) if (probabilities[static_cast<size_t>(i)] > 0.0) return static_cast<llama_token>(indices[static_cast<size_t>(i)]);
    return static_cast<llama_token>(indices[0]);
}

void apply_repetition_penalty(std::vector<float> & logits, const llama_vocab * vocab, const std::vector<llama_token> & past_tokens, float penalty, int32_t penalty_last_n) {
    if (penalty <= 1.0f || !std::isfinite(penalty) || past_tokens.empty() || penalty_last_n <= 0) return;
    const int32_t vocab_size = static_cast<int32_t>(logits.size());
    const size_t total_tokens = past_tokens.size();
    const size_t start_idx = (total_tokens > static_cast<size_t>(penalty_last_n)) ? (total_tokens - static_cast<size_t>(penalty_last_n)) : 0;
    std::unordered_set<llama_token> penalized;
    for (size_t i = start_idx; i < total_tokens; ++i) {
        const llama_token token = past_tokens[i];
        if (token < 0 || token >= vocab_size) continue;
        if (vocab != nullptr && llama_vocab_is_eog(vocab, token)) continue;
        if (penalized.insert(token).second) {
            float & logit = logits[static_cast<size_t>(token)];
            if (logit <= 0.0f) logit *= penalty; else logit /= penalty;
        }
    }
}

std::string format_metric(double val, int precision) { char buf[64]; std::snprintf(buf, sizeof(buf), "%.*f", precision, val); return std::string(buf); }

std::string piece_for_token(const llama_vocab * vocab, llama_token token) {
    char buf[256] = {};
    const int32_t len = llama_token_to_piece(vocab, token, buf, static_cast<int32_t>(sizeof(buf)), 0, true);
    if (len < 0) return "<ERROR>";
    return std::string(buf, static_cast<size_t>(len));
}

std::string stop_tokenization_report(const llama_vocab * vocab, const std::string & stop_sequence) {
    std::vector<llama_token> stop_tokens;
    const int32_t required_signed = llama_tokenize(vocab, stop_sequence.c_str(), static_cast<int32_t>(stop_sequence.size()), nullptr, 0, false, false);
    if (required_signed >= 0) return "STOP SEQUENCE TOKENIZATION: ERROR\n";
    const int32_t required = -required_signed;
    if (required <= 0) return "STOP SEQUENCE TOKENIZATION: 0 token(s)\n";
    stop_tokens.resize(static_cast<size_t>(required));
    const int32_t actual = llama_tokenize(vocab, stop_sequence.c_str(), static_cast<int32_t>(stop_sequence.size()), stop_tokens.data(), required, false, false);
    if (actual < 0) return "STOP SEQUENCE TOKENIZATION: ERROR\n";
    stop_tokens.resize(static_cast<size_t>(actual));
    std::string report = "STOP SEQUENCE TOKENIZATION: " + std::to_string(stop_tokens.size()) + " token(s)\n";
    for (size_t i = 0; i < stop_tokens.size(); ++i) report += "STOP TOKEN " + std::to_string(i) + ": ID=" + std::to_string(stop_tokens[i]) + " PIECE=[" + piece_for_token(vocab, stop_tokens[i]) + "]\n";
    return report;
}

std::string generate_sampling_locked(const std::string & prompt_input, float temperature = kTemperature, int32_t top_k = kTopK, float top_p = kTopP, float min_p = kMinP, float repetition_penalty = kRepetitionPenalty, int64_t seed = kSeed) {
    std::string prompt_text = prompt_input;
    if (min_p <= 0.0f && g_default_min_p > 0.0f) min_p = g_default_min_p;
    float typical_p = g_default_typical_p;
    parse_prompt_sampling_tags(prompt_text, min_p, typical_p);
    if (!std::isfinite(min_p)) min_p = 0.0f;
    min_p = std::max(0.0f, std::min(1.0f, min_p));
    if (!std::isfinite(typical_p)) typical_p = 1.0f;
    typical_p = std::max(0.0f, std::min(1.0f, typical_p));
    if (!std::isfinite(repetition_penalty) || repetition_penalty < 0.0f) repetition_penalty = 1.0f;
    std::vector<llama_token> tokens;
    if (!tokenize_prompt(prompt_text, tokens)) return "ERROR: llama_tokenize failed";
    if (tokens.empty() || tokens.size() > 512) return "ERROR: invalid token count";
    llama_memory_clear(llama_get_memory(g_context), true);
    const auto t_prompt_start = std::chrono::steady_clock::now();
    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    if (llama_decode(g_context, batch) != 0) return "ERROR: llama_decode failed";
    const auto t_prompt_end = std::chrono::steady_clock::now();
    const double prompt_processing_time_ms = std::chrono::duration<double, std::milli>(t_prompt_end - t_prompt_start).count();
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    if (vocab == nullptr) return "ERROR: vocab is unavailable";
    const int32_t vocab_size = llama_vocab_n_tokens(vocab);
    if (vocab_size <= 0) return "ERROR: invalid vocabulary size";
    constexpr int32_t kMaxGenTokens = 128;
    constexpr int32_t kMaxContextTokens = 512;
    constexpr const char * kStopSequence = "<END>";
    const std::string stop_report = stop_tokenization_report(vocab, kStopSequence);
    std::mt19937 rng;
    std::string seed_str;
    if (seed >= 0) { rng.seed(static_cast<uint32_t>(seed)); seed_str = std::to_string(seed); } else { std::random_device rd; rng.seed(rd()); seed_str = "RANDOM"; }
    std::string generated_text;
    int32_t generated_count = 0;
    std::vector<llama_token> generated_tokens;
    generated_tokens.reserve(kMaxGenTokens);
    bool first_token_determined = false;
    std::chrono::steady_clock::time_point t_first_token;
    std::string stop_reason = "MAX_TOKENS";
    for (int32_t i = 0; i < kMaxGenTokens; ++i) {
        if (static_cast<int32_t>(tokens.size()) + generated_count >= kMaxContextTokens) { stop_reason = "MAX_CONTEXT"; break; }
        const float * logits = llama_get_logits(g_context);
        if (logits == nullptr) return "ERROR: logits are unavailable";
        std::vector<float> penalized_logits;
        const float * effective_logits = logits;
        if (repetition_penalty > 1.0f && std::isfinite(repetition_penalty) && !generated_tokens.empty()) {
            penalized_logits.assign(logits, logits + vocab_size);
            apply_repetition_penalty(penalized_logits, vocab, generated_tokens, repetition_penalty, kPenaltyLastN);
            effective_logits = penalized_logits.data();
        }
        const llama_token current_token = sample_with_sampling_filters(effective_logits, vocab_size, temperature, top_k, top_p, min_p, typical_p, rng);
        if (!first_token_determined) { t_first_token = std::chrono::steady_clock::now(); first_token_determined = true; }
        if (llama_vocab_is_eog(vocab, current_token)) { stop_reason = "EOG"; break; }
        char token_text[256] = {};
        const int32_t token_length = llama_token_to_piece(vocab, current_token, token_text, static_cast<int32_t>(sizeof(token_text)), 0, true);
        if (token_length < 0) return "ERROR: llama_token_to_piece failed";
        if (token_length > 0) generated_text.append(token_text, static_cast<size_t>(token_length));
        generated_count++;
        generated_tokens.push_back(current_token);
        const size_t stop_pos = generated_text.find(kStopSequence);
        if (stop_pos != std::string::npos) { generated_text.erase(stop_pos); stop_reason = "STOP_SEQUENCE"; break; }
        if (generated_count >= kMaxGenTokens) { stop_reason = "MAX_TOKENS"; break; }
        llama_token next_token = current_token;
        llama_batch token_batch = llama_batch_get_one(&next_token, 1);
        if (llama_decode(g_context, token_batch) != 0) return "ERROR: llama_decode failed";
    }
    const auto t_generation_end = std::chrono::steady_clock::now();
    double ttft_ms = 0.0, generation_time_ms = 0.0, total_time_ms = 0.0, generation_speed = 0.0;
    if (first_token_determined) {
        ttft_ms = std::chrono::duration<double, std::milli>(t_first_token - t_prompt_start).count();
        generation_time_ms = std::chrono::duration<double, std::milli>(t_generation_end - t_first_token).count();
        total_time_ms = std::chrono::duration<double, std::milli>(t_generation_end - t_prompt_start).count();
        if (generation_time_ms > 0.0 && generated_count > 0) generation_speed = static_cast<double>(generated_count) / (generation_time_ms / 1000.0);
    } else total_time_ms = prompt_processing_time_ms;
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "Inference metrics: prompt_tokens=%zu, gen_tokens=%d, typical_p=%.2f, min_p=%.2f, seed=%s, stop_reason=%s, prompt_time=%.2f ms, ttft=%.2f ms, gen_time=%.2f ms, total=%.2f ms, speed=%.2f tokens/sec", tokens.size(), generated_count, typical_p, min_p, seed_str.c_str(), stop_reason.c_str(), prompt_processing_time_ms, ttft_ms, generation_time_ms, total_time_ms, generation_speed);
    return "SUCCESS: temperature + top-k + typical-p + top-p + min-p + repetition-penalty sampling completed\n"
        "TEMPERATURE: " + std::to_string(temperature) + "\n"
        "TOP-K: " + std::to_string(top_k) + "\n"
        "TYPICAL-P: " + std::to_string(typical_p) + "\n"
        "TOP-P: " + std::to_string(top_p) + "\n"
        "MIN-P: " + std::to_string(min_p) + "\n"
        "REPETITION PENALTY: " + std::to_string(repetition_penalty) + "\n"
        "PROMPT: " + prompt_text + "\n"
        "PROMPT TOKEN COUNT: " + std::to_string(tokens.size()) + "\n"
        "GENERATED TOKEN COUNT: " + std::to_string(generated_count) + "\n"
        "SEED: " + seed_str + "\n"
        "STOP REASON: " + stop_reason + "\n"
        + stop_report
        + "PROMPT PROCESSING TIME: " + format_metric(prompt_processing_time_ms, 2) + " ms\n"
        + "TTFT: " + format_metric(ttft_ms, 2) + " ms\n"
        + "GENERATION TIME: " + format_metric(generation_time_ms, 2) + " ms\n"
        + "TOTAL TIME: " + format_metric(total_time_ms, 2) + " ms\n"
        + "GENERATION SPEED: " + format_metric(generation_speed, 2) + " tokens/sec\n"
        + "GENERATED TEXT: " + generated_text;
}
}

extern "C" JNIEXPORT jstring JNICALL Java_com_example_MainActivity_stringFromJNI(JNIEnv* env, jobject /* this */) { return env->NewStringUTF("Hello from native C++"); }

extern "C" JNIEXPORT jstring JNICALL Java_com_example_MainActivity_nativeLoadModel(JNIEnv* env, jobject /* this */, jstring model_path) {
    if (model_path == nullptr) return env->NewStringUTF("ERROR: model path is null");
    const char * path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) return env->NewStringUTF("ERROR: failed to read model path");
    std::lock_guard<std::mutex> lock(g_model_mutex);
    struct stat file_stat {};
    if (stat(path, &file_stat) != 0 || !S_ISREG(file_stat.st_mode)) { env->ReleaseStringUTFChars(model_path, path); return env->NewStringUTF("ERROR: model file does not exist or is not a regular file"); }
    unload_model_locked();
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
    g_model = llama_model_load_from_file(path, model_params);
    if (g_model == nullptr) { env->ReleaseStringUTFChars(model_path, path); return env->NewStringUTF("ERROR: llama_model_load_from_file failed"); }
    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = 512;
    context_params.n_batch = 512;
    context_params.n_threads = 4;
    context_params.n_threads_batch = 4;
    g_context = llama_init_from_model(g_model, context_params);
    env->ReleaseStringUTFChars(model_path, path);
    if (g_context == nullptr) { llama_model_free(g_model); g_model = nullptr; return env->NewStringUTF("ERROR: model loaded, but llama_init_from_model failed"); }
    return env->NewStringUTF("SUCCESS: GGUF model + context loaded");
}

extern "C" JNIEXPORT jstring JNICALL Java_com_example_MainActivity_nativeRunTestInference(JNIEnv* env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    if (g_model == nullptr || g_context == nullptr) return env->NewStringUTF("ERROR: model/context is not loaded");
    const std::string result = generate_sampling_locked("こんにちは。短く自己紹介してください。");
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL Java_com_example_MainActivity_nativeEchoPrompt(JNIEnv* env, jobject /* this */, jstring prompt) {
    if (prompt == nullptr) return env->NewStringUTF("ERROR: prompt is null");
    const char * prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    if (prompt_chars == nullptr) return env->NewStringUTF("ERROR: failed to read prompt");
    const std::string prompt_text(prompt_chars);
    env->ReleaseStringUTFChars(prompt, prompt_chars);
    std::lock_guard<std::mutex> lock(g_model_mutex);
    if (g_model == nullptr || g_context == nullptr) return env->NewStringUTF("ERROR: model/context is not loaded");
    const std::string result = generate_sampling_locked(prompt_text);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL Java_com_example_MainActivity_nativeGenerateWithTemperature(JNIEnv* env, jobject /* this */, jstring prompt, jfloat temperature) {
    if (prompt == nullptr) return env->NewStringUTF("ERROR: prompt is null");
    const char * prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    if (prompt_chars == nullptr) return env->NewStringUTF("ERROR: failed to read prompt");
    const std::string prompt_text(prompt_chars);
    env->ReleaseStringUTFChars(prompt, prompt_chars);
    std::lock_guard<std::mutex> lock(g_model_mutex);
    if (g_model == nullptr || g_context == nullptr) return env->NewStringUTF("ERROR: model/context is not loaded");
    const std::string result = generate_sampling_locked(prompt_text, static_cast<float>(temperature));
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL Java_com_example_MainActivity_nativeGenerateWithTemperatureAndTopK(JNIEnv* env, jobject /* this */, jstring prompt, jfloat temperature, jint top_k) {
    if (prompt == nullptr) return env->NewStringUTF("ERROR: prompt is null");
    const char * prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    if (prompt_chars == nullptr) return env->NewStringUTF("ERROR: failed to read prompt");
    const std::string prompt_text(prompt_chars);
    env->ReleaseStringUTFChars(prompt, prompt_chars);
    std::lock_guard<std::mutex> lock(g_model_mutex);
    if (g_model == nullptr || g_context == nullptr) return env->NewStringUTF("ERROR: model/context is not loaded");
    const std::string result = generate_sampling_locked(prompt_text, static_cast<float>(temperature), static_cast<int32_t>(top_k));
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL Java_com_example_MainActivity_nativeGenerateWithTemperatureTopKTopP(JNIEnv* env, jobject /* this */, jstring prompt, jfloat temperature, jint top_k, jfloat top_p) {
    if (prompt == nullptr) return env->NewStringUTF("ERROR: prompt is null");
    const char * prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    if (prompt_chars == nullptr) return env->NewStringUTF("ERROR: failed to read prompt");
    const std::string prompt_text(prompt_chars);
    env->ReleaseStringUTFChars(prompt, prompt_chars);
    std::lock_guard<std::mutex> lock(g_model_mutex);
    if (g_model == nullptr || g_context == nullptr) return env->NewStringUTF("ERROR: model/context is not loaded");
    const std::string result = generate_sampling_locked(prompt_text, static_cast<float>(temperature), static_cast<int32_t>(top_k), static_cast<float>(top_p), g_default_min_p);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL Java_com_example_MainActivity_nativeGenerateWithTemperatureTopKTopPMinP(JNIEnv* env, jobject /* this */, jstring prompt, jfloat temperature, jint top_k, jfloat top_p, jfloat min_p) {
    if (prompt == nullptr) return env->NewStringUTF("ERROR: prompt is null");
    const char * prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    if (prompt_chars == nullptr) return env->NewStringUTF("ERROR: failed to read prompt");
    const std::string prompt_text(prompt_chars);
    env->ReleaseStringUTFChars(prompt, prompt_chars);
    std::lock_guard<std::mutex> lock(g_model_mutex);
    if (g_model == nullptr || g_context == nullptr) return env->NewStringUTF("ERROR: model/context is not loaded");
    const std::string result = generate_sampling_locked(prompt_text, static_cast<float>(temperature), static_cast<int32_t>(top_k), static_cast<float>(top_p), static_cast<float>(min_p));
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL Java_com_example_MainActivity_nativeSetMinP(JNIEnv* /* env */, jobject /* this */, jfloat min_p) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    g_default_min_p = std::max(0.0f, std::min(1.0f, static_cast<float>(min_p)));
}

extern "C" JNIEXPORT jfloat JNICALL Java_com_example_MainActivity_nativeGetMinP(JNIEnv* /* env */, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    return static_cast<jfloat>(g_default_min_p);
}

extern "C" JNIEXPORT void JNICALL Java_com_example_MainActivity_nativeSetTypicalP(JNIEnv* /* env */, jobject /* this */, jfloat typical_p) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    g_default_typical_p = std::max(0.0f, std::min(1.0f, static_cast<float>(typical_p)));
}

extern "C" JNIEXPORT jfloat JNICALL Java_com_example_MainActivity_nativeGetTypicalP(JNIEnv* /* env */, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    return static_cast<jfloat>(g_default_typical_p);
}

extern "C" JNIEXPORT void JNICALL Java_com_example_MainActivity_nativeUnloadModel(JNIEnv* /* env */, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    unload_model_locked();
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_example_MainActivity_nativeIsModelLoaded(JNIEnv* /* env */, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    return (g_model != nullptr && g_context != nullptr) ? JNI_TRUE : JNI_FALSE;
}
