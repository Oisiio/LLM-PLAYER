#include <jni.h>
#include <android/log.h>
#include <sys/stat.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <sched.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <functional>
#include <mutex>
#include <random>
#include <set>
#include <string>
#include <unordered_set>
#include <vector>

#include "llama.h"
#include "chat.h"
#include "reasoning-budget.h"
#include "unicode.h"

namespace {
static int get_current_cpu_core() {
#if defined(__NR_getcpu)
    unsigned int cpu = 0;
    if (syscall(__NR_getcpu, &cpu, nullptr, nullptr) == 0) {
        return static_cast<int>(cpu);
    }
#endif
#if defined(__ANDROID_API__) && __ANDROID_API__ >= 28
    return sched_getcpu();
#else
    return -1;
#endif
}

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
int32_t g_n_threads = 4;
int32_t g_n_threads_batch = 4;
int32_t g_n_ctx = 512;
constexpr int32_t kBatchSize = 512;
int32_t g_max_gen_tokens = 128;
std::atomic<bool> g_cancel_generation{false};
std::atomic<bool> g_cancel_talk_generation{false};
std::atomic<bool> g_cancel_ai_generation{false};

std::mutex g_model_mutex;
llama_model * g_model = nullptr;
llama_context * g_context = nullptr;
common_chat_templates_ptr g_chat_templates;
std::string g_kv_cache_type = "Auto";

struct AgentPrefixCache {
    std::string session_id;
    std::vector<llama_token> tokens;
    bool is_valid = false;

    void clear() {
        session_id.clear();
        tokens.clear();
        is_valid = false;
    }
};
AgentPrefixCache g_agent_prefix_cache;

struct AgentSessionState {
    std::string session_id;
    int32_t n_past = 0;
    std::vector<llama_token> tokens;
    bool is_active = false;

    void clear(llama_context * ctx = nullptr) {
        session_id.clear();
        n_past = 0;
        tokens.clear();
        is_active = false;
        if (ctx != nullptr) {
            llama_memory_clear(llama_get_memory(ctx), true);
        }
    }
};
static AgentSessionState g_agent_session;

llama_context_params create_context_params_locked(int32_t n_ctx, const std::string & kv_type) {
    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = n_ctx;
    context_params.n_batch = kBatchSize;
    context_params.n_threads = g_n_threads;
    context_params.n_threads_batch = g_n_threads_batch;

    if (kv_type == "Q8_0") {
        context_params.type_k = GGML_TYPE_Q8_0;
        context_params.type_v = GGML_TYPE_Q8_0;
    } else if (kv_type == "Q4_0") {
        context_params.type_k = GGML_TYPE_Q4_0;
        context_params.type_v = GGML_TYPE_Q4_0;
    }
    // "Auto": uses llama_context_default_params() default (F16)
    return context_params;
}

void unload_model_locked() {
    g_agent_prefix_cache.clear();
    g_agent_session.clear();
    g_chat_templates.reset();
    if (g_context != nullptr) { llama_free(g_context); g_context = nullptr; }
    if (g_model != nullptr) { llama_model_free(g_model); g_model = nullptr; }
}

bool format_chat_prompt(const std::string & user_prompt,
                        std::string & formatted_prompt,
                        std::string & template_name,
                        std::vector<std::string> & additional_stops,
                        bool enable_thinking = false,
                        std::string * out_thinking_start_tag = nullptr,
                        std::vector<std::string> * out_thinking_end_tags = nullptr) {
    if (g_model == nullptr) return false;

    if (!g_chat_templates) {
        g_chat_templates = common_chat_templates_init(g_model, "");
    }
    if (!g_chat_templates) return false;

    const char * raw_tmpl = llama_model_chat_template(g_model, nullptr);
    if ((raw_tmpl == nullptr || raw_tmpl[0] == '\0') && !common_chat_templates_was_explicit(g_chat_templates.get())) {
        formatted_prompt = user_prompt;
        template_name = "NONE";
        additional_stops.clear();
        return true;
    }

    try {
        common_chat_templates_inputs inputs;
        common_chat_msg msg;
        msg.role = "user";
        msg.content = user_prompt;
        inputs.messages.push_back(msg);
        inputs.add_generation_prompt = true;
        inputs.use_jinja = true;
        inputs.enable_thinking = enable_thinking;

        const auto chat_params = common_chat_templates_apply(g_chat_templates.get(), inputs);
        formatted_prompt = chat_params.prompt;
        additional_stops = chat_params.additional_stops;
        if (out_thinking_start_tag != nullptr) {
            *out_thinking_start_tag = chat_params.thinking_start_tag;
        }
        if (out_thinking_end_tags != nullptr) {
            *out_thinking_end_tags = chat_params.thinking_end_tags;
        }
        template_name = (raw_tmpl != nullptr && raw_tmpl[0] != '\0') ? raw_tmpl : "MODEL_CHAT_TEMPLATE";
        return !formatted_prompt.empty();
    } catch (const std::exception & e) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "format_chat_prompt exception: %s", e.what());
        return false;
    }
}

bool tokenize_prompt(const std::string & prompt, std::vector<llama_token> & tokens) {
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    if (vocab == nullptr) return false;
    const int32_t required_signed = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), nullptr, 0, false, true);
    if (required_signed >= 0) return false;
    const int32_t required = -required_signed;
    if (required <= 0) return false;
    tokens.resize(static_cast<size_t>(required));
    const int32_t actual = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(), required, false, true);
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

void append_valid_utf8_to_utf16(
    const std::string & input,
    size_t & consumed,
    std::vector<jchar> & output,
    bool is_final_flush = false
) {
    consumed = 0;
    const size_t len = input.size();
    while (consumed < len) {
        const uint8_t b0 = static_cast<uint8_t>(input[consumed]);
        uint32_t cp = 0;
        size_t needed = 0;

        if (b0 <= 0x7F) {
            cp = b0;
            needed = 1;
        } else if (b0 >= 0xC2 && b0 <= 0xDF) {
            needed = 2;
            if (len - consumed < needed) {
                if (!is_final_flush) break;
                cp = 0xFFFD;
                needed = 1;
            } else {
                const uint8_t b1 = static_cast<uint8_t>(input[consumed + 1]);
                if ((b1 & 0xC0) != 0x80) {
                    cp = 0xFFFD;
                    needed = 1;
                } else {
                    cp = ((b0 & 0x1F) << 6) | (b1 & 0x3F);
                }
            }
        } else if (b0 >= 0xE0 && b0 <= 0xEF) {
            needed = 3;
            if (len - consumed < needed) {
                if (!is_final_flush) break;
                cp = 0xFFFD;
                needed = 1;
            } else {
                const uint8_t b1 = static_cast<uint8_t>(input[consumed + 1]);
                const uint8_t b2 = static_cast<uint8_t>(input[consumed + 2]);
                const bool cont_ok = ((b1 & 0xC0) == 0x80) && ((b2 & 0xC0) == 0x80);
                const bool overlong = (b0 == 0xE0 && b1 < 0xA0);
                const bool surrogate = (b0 == 0xED && b1 >= 0xA0);
                if (!cont_ok || overlong || surrogate) {
                    cp = 0xFFFD;
                    needed = 1;
                } else {
                    cp = ((b0 & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (b2 & 0x3F);
                }
            }
        } else if (b0 >= 0xF0 && b0 <= 0xF4) {
            needed = 4;
            if (len - consumed < needed) {
                if (!is_final_flush) break;
                cp = 0xFFFD;
                needed = 1;
            } else {
                const uint8_t b1 = static_cast<uint8_t>(input[consumed + 1]);
                const uint8_t b2 = static_cast<uint8_t>(input[consumed + 2]);
                const uint8_t b3 = static_cast<uint8_t>(input[consumed + 3]);
                const bool cont_ok = ((b1 & 0xC0) == 0x80) && ((b2 & 0xC0) == 0x80) && ((b3 & 0xC0) == 0x80);
                const bool overlong = (b0 == 0xF0 && b1 < 0x90);
                const bool out_of_range = (b0 == 0xF4 && b1 >= 0x90);
                if (!cont_ok || overlong || out_of_range) {
                    cp = 0xFFFD;
                    needed = 1;
                } else {
                    cp = ((b0 & 0x07) << 18) | ((b1 & 0x3F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F);
                }
            }
        } else {
            cp = 0xFFFD;
            needed = 1;
        }

        if (cp <= 0xFFFF) {
            output.push_back(static_cast<jchar>(cp));
        } else if (cp <= 0x10FFFF) {
            cp -= 0x10000;
            output.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            output.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        } else {
            output.push_back(static_cast<jchar>(0xFFFD));
        }

        consumed += needed;
    }
}

jstring new_jstring_from_utf8(JNIEnv * env, const std::string & input) {
    if (input.empty()) {
        return env->NewString(nullptr, 0);
    }
    size_t consumed = 0;
    std::vector<jchar> utf16;
    utf16.reserve(input.size());
    append_valid_utf8_to_utf16(input, consumed, utf16, true);
    return env->NewString(utf16.empty() ? nullptr : utf16.data(), static_cast<jsize>(utf16.size()));
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

std::string generate_sampling_locked(
    const std::string & prompt_input,
    float temperature = kTemperature,
    int32_t top_k = kTopK,
    float top_p = kTopP,
    float min_p = kMinP,
    float typical_p_override = -1.0f,
    float repetition_penalty = kRepetitionPenalty,
    int32_t penalty_last_n = kPenaltyLastN,
    int64_t seed = kSeed,
    bool enable_thinking = false,
    int32_t thinking_budget = 0,
    const std::function<void(const char*, int32_t)> & on_token = nullptr,
    std::string * out_raw_text = nullptr,
    const std::function<void(double)> & on_ttft = nullptr,
    const std::function<void(int32_t, int32_t, double, double, double, double, double, int32_t)> & on_metrics = nullptr,
    const std::atomic<bool> * cancel_flag = nullptr
) {
    std::string prompt_text = prompt_input;
    if (min_p <= 0.0f && g_default_min_p > 0.0f) min_p = g_default_min_p;
    float typical_p = typical_p_override < 0.0f ? g_default_typical_p : typical_p_override;
    parse_prompt_sampling_tags(prompt_text, min_p, typical_p);
    std::string formatted_prompt;
    std::string chat_template;
    std::vector<std::string> additional_stops;
    std::string thinking_start_tag;
    std::vector<std::string> thinking_end_tags;
    if (!format_chat_prompt(prompt_text, formatted_prompt, chat_template, additional_stops, enable_thinking, &thinking_start_tag, &thinking_end_tags)) return "ERROR: chat_template_apply failed";
    if (!std::isfinite(min_p)) min_p = 0.0f;
    min_p = std::max(0.0f, std::min(1.0f, min_p));
    if (!std::isfinite(typical_p)) typical_p = 1.0f;
    typical_p = std::max(0.0f, std::min(1.0f, typical_p));
    if (!std::isfinite(repetition_penalty) || repetition_penalty < 0.0f) repetition_penalty = 1.0f;

    std::vector<llama_token> tokens;
    if (!tokenize_prompt(formatted_prompt, tokens)) return "ERROR: llama_tokenize failed";
    if (tokens.empty() || static_cast<int32_t>(tokens.size()) > g_n_ctx) return "ERROR: invalid token count";

    g_agent_prefix_cache.clear();
    llama_memory_clear(llama_get_memory(g_context), true);
    const auto t_prompt_start = std::chrono::steady_clock::now();
    for (size_t offset = 0; offset < tokens.size(); offset += static_cast<size_t>(kBatchSize)) {
        if (cancel_flag != nullptr && cancel_flag->load()) {
            if (out_raw_text != nullptr) *out_raw_text = "";
            return "USER_CANCEL";
        }
        const size_t chunk_size = std::min(tokens.size() - offset, static_cast<size_t>(kBatchSize));
        llama_batch chunk_batch = llama_batch_get_one(tokens.data() + offset, static_cast<int32_t>(chunk_size));
        if (llama_decode(g_context, chunk_batch) != 0) return "ERROR: llama_decode failed";
    }
    const auto t_prompt_end = std::chrono::steady_clock::now();
    const double prompt_processing_time_ms = std::chrono::duration<double, std::milli>(t_prompt_end - t_prompt_start).count();

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    if (vocab == nullptr) return "ERROR: vocab is unavailable";
    const int32_t vocab_size = llama_vocab_n_tokens(vocab);
    if (vocab_size <= 0) return "ERROR: invalid vocabulary size";

    struct SamplerDeleter {
        void operator()(struct llama_sampler * s) const {
            if (s != nullptr) llama_sampler_free(s);
        }
    };
    std::unique_ptr<struct llama_sampler, SamplerDeleter> rbudget_guard;

    if (enable_thinking && thinking_budget > 0) {
        std::string start_str = !thinking_start_tag.empty() ? thinking_start_tag : "<think>";
        std::vector<std::string> end_strs = !thinking_end_tags.empty() ? thinking_end_tags : std::vector<std::string>{"</think>"};

        std::vector<llama_token> start_tokens;
        tokenize_prompt(start_str, start_tokens);

        std::vector<std::vector<llama_token>> end_seqs;
        for (const auto & et : end_strs) {
            std::vector<llama_token> et_tokens;
            if (tokenize_prompt(et, et_tokens) && !et_tokens.empty()) {
                end_seqs.push_back(et_tokens);
            }
        }
        std::vector<llama_token> forced_tokens;
        if (!end_seqs.empty()) {
            forced_tokens = end_seqs.front();
        } else {
            tokenize_prompt("</think>", forced_tokens);
            if (!forced_tokens.empty()) {
                end_seqs.push_back(forced_tokens);
            }
        }

        if (!start_tokens.empty() && !end_seqs.empty()) {
            struct llama_sampler * rbudget = common_reasoning_budget_init(
                vocab,
                {start_tokens},
                end_seqs,
                forced_tokens,
                thinking_budget,
                REASONING_BUDGET_IDLE
            );
            if (rbudget != nullptr) {
                for (const auto & pt : tokens) {
                    llama_sampler_accept(rbudget, pt);
                }
                rbudget_guard.reset(rbudget);
            }
        }
    }

    const int32_t max_gen_tokens = g_max_gen_tokens > 0 ? g_max_gen_tokens : 128;
    const int32_t max_context_tokens = g_n_ctx;
    constexpr const char * kStopSequence = "<END>";
    const std::string stop_report = stop_tokenization_report(vocab, kStopSequence);

    std::mt19937 rng;
    std::string seed_str;
    if (seed >= 0) { rng.seed(static_cast<uint32_t>(seed)); seed_str = std::to_string(seed); } else { std::random_device rd; rng.seed(rd()); seed_str = "RANDOM"; }

    std::string generated_text;
    int32_t generated_count = 0;
    std::vector<llama_token> generated_tokens;
    generated_tokens.reserve(static_cast<size_t>(max_gen_tokens));
    bool first_token_determined = false;
    std::chrono::steady_clock::time_point t_first_token;
    std::string stop_reason = "MAX_TOKENS";

    for (int32_t i = 0; i < max_gen_tokens; ++i) {
        if (cancel_flag != nullptr && cancel_flag->load()) { stop_reason = "USER_CANCEL"; break; }
        if (static_cast<int32_t>(tokens.size()) + generated_count >= max_context_tokens) { stop_reason = "MAX_CONTEXT"; break; }

        const float * logits = llama_get_logits(g_context);
        if (logits == nullptr) return "ERROR: logits are unavailable";

        std::vector<float> penalized_logits;
        const float * effective_logits = logits;
        if (repetition_penalty > 1.0f && std::isfinite(repetition_penalty) && !generated_tokens.empty()) {
            penalized_logits.assign(logits, logits + vocab_size);
            apply_repetition_penalty(penalized_logits, vocab, generated_tokens, repetition_penalty, penalty_last_n);
            effective_logits = penalized_logits.data();
        }

        if (rbudget_guard && common_reasoning_budget_get_state(rbudget_guard.get()) == REASONING_BUDGET_FORCING) {
            std::vector<llama_token_data> cur(static_cast<size_t>(vocab_size));
            for (int32_t token_id = 0; token_id < vocab_size; ++token_id) {
                cur[static_cast<size_t>(token_id)] = { static_cast<llama_token>(token_id), effective_logits[token_id], 0.0f };
            }
            llama_token_data_array cur_p = { cur.data(), cur.size(), -1, false };
            llama_sampler_apply(rbudget_guard.get(), &cur_p);
            penalized_logits.resize(static_cast<size_t>(vocab_size));
            for (size_t k = 0; k < cur.size(); ++k) {
                penalized_logits[static_cast<size_t>(cur[k].id)] = cur[k].logit;
            }
            effective_logits = penalized_logits.data();
        }

        const llama_token current_token = sample_with_sampling_filters(effective_logits, vocab_size, temperature, top_k, top_p, min_p, typical_p, rng);
        if (!first_token_determined) {
            t_first_token = std::chrono::steady_clock::now();
            first_token_determined = true;
            if (on_ttft != nullptr) {
                const double current_ttft_ms = std::chrono::duration<double, std::milli>(t_first_token - t_prompt_start).count();
                on_ttft(current_ttft_ms);
            }
        }

        if (llama_vocab_is_eog(vocab, current_token)) { stop_reason = "EOG"; break; }

        char token_text[256] = {};
        const int32_t token_length = llama_token_to_piece(vocab, current_token, token_text, static_cast<int32_t>(sizeof(token_text)), 0, true);
        if (token_length < 0) return "ERROR: llama_token_to_piece failed";
        if (token_length > 0) {
            generated_text.append(token_text, static_cast<size_t>(token_length));
            if (on_token != nullptr) {
                on_token(token_text, token_length);
            }
        }
        generated_count++;
        generated_tokens.push_back(current_token);
        if (rbudget_guard) {
            llama_sampler_accept(rbudget_guard.get(), current_token);
        }

        const size_t stop_pos = generated_text.find(kStopSequence);
        if (stop_pos != std::string::npos) { generated_text.erase(stop_pos); stop_reason = "STOP_SEQUENCE"; break; }

        bool stopped_by_additional = false;
        for (const auto & add_stop : additional_stops) {
            if (add_stop.empty()) continue;
            const size_t pos = generated_text.find(add_stop);
            if (pos != std::string::npos) {
                generated_text.erase(pos);
                stop_reason = "STOP_SEQUENCE";
                stopped_by_additional = true;
                break;
            }
        }
        if (stopped_by_additional) break;

        if (generated_count >= max_gen_tokens) { stop_reason = "MAX_TOKENS"; break; }
        if (cancel_flag != nullptr && cancel_flag->load()) { stop_reason = "USER_CANCEL"; break; }

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
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "Inference metrics: threads=%d/%d, template=%s, prompt_tokens=%zu, gen_tokens=%d, typical_p=%.2f, min_p=%.2f, seed=%s, stop_reason=%s, prompt_time=%.2f ms, ttft=%.2f ms, gen_time=%.2f ms, total=%.2f ms, speed=%.2f tokens/sec", g_n_threads, g_n_threads_batch, chat_template.c_str(), tokens.size(), generated_count, typical_p, min_p, seed_str.c_str(), stop_reason.c_str(), prompt_processing_time_ms, ttft_ms, generation_time_ms, total_time_ms, generation_speed);
    if (out_raw_text != nullptr) {
        *out_raw_text = generated_text;
    }
    if (on_metrics != nullptr) {
        on_metrics(static_cast<int32_t>(tokens.size()), generated_count, prompt_processing_time_ms, ttft_ms, generation_time_ms, total_time_ms, generation_speed, g_n_threads);
    }
    return "SUCCESS: temperature + top-k + typical-p + top-p + min-p + repetition-penalty sampling completed\n"
        "TEMPERATURE: " + std::to_string(temperature) + "\n"
        "TOP-K: " + std::to_string(top_k) + "\n"
        "TYPICAL-P: " + std::to_string(typical_p) + "\n"
        "TOP-P: " + std::to_string(top_p) + "\n"
        "MIN-P: " + std::to_string(min_p) + "\n"
        "REPETITION PENALTY: " + std::to_string(repetition_penalty) + "\n"
        "CHAT TEMPLATE: " + chat_template + "\n"
        "RAW PROMPT: " + prompt_text + "\n"
        "FORMATTED PROMPT: " + formatted_prompt + "\n"
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

std::string generate_sampling_agent_locked(
    const std::string & prompt_input,
    float temperature = kTemperature,
    int32_t top_k = kTopK,
    float top_p = kTopP,
    float min_p = kMinP,
    float typical_p_override = -1.0f,
    float repetition_penalty = kRepetitionPenalty,
    int32_t penalty_last_n = kPenaltyLastN,
    int64_t seed = kSeed,
    bool enable_thinking = false,
    int32_t thinking_budget = 0,
    const std::string & session_id = "",
    bool enable_prefix_cache = true,
    const std::function<void(const char*, int32_t)> & on_token = nullptr,
    std::string * out_raw_text = nullptr,
    const std::function<void(double)> & on_ttft = nullptr,
    const std::function<void(int32_t, int32_t, double, double, double, double, double, int32_t)> & on_metrics = nullptr,
    const std::function<void(int32_t, int32_t)> & on_prefix_metrics = nullptr,
    const std::atomic<bool> * cancel_flag = nullptr
) {
    std::string prompt_text = prompt_input;
    if (min_p <= 0.0f && g_default_min_p > 0.0f) min_p = g_default_min_p;
    float typical_p = typical_p_override < 0.0f ? g_default_typical_p : typical_p_override;
    parse_prompt_sampling_tags(prompt_text, min_p, typical_p);
    std::string formatted_prompt;
    std::string chat_template;
    std::vector<std::string> additional_stops;
    std::string thinking_start_tag;
    std::vector<std::string> thinking_end_tags;
    if (!format_chat_prompt(prompt_text, formatted_prompt, chat_template, additional_stops, enable_thinking, &thinking_start_tag, &thinking_end_tags)) {
        g_agent_prefix_cache.clear();
        return "ERROR: chat_template_apply failed";
    }
    if (!std::isfinite(min_p)) min_p = 0.0f;
    min_p = std::max(0.0f, std::min(1.0f, min_p));
    if (!std::isfinite(typical_p)) typical_p = 1.0f;
    typical_p = std::max(0.0f, std::min(1.0f, typical_p));
    if (!std::isfinite(repetition_penalty) || repetition_penalty < 0.0f) repetition_penalty = 1.0f;

    std::vector<llama_token> tokens;
    if (!tokenize_prompt(formatted_prompt, tokens)) {
        g_agent_prefix_cache.clear();
        return "ERROR: llama_tokenize failed";
    }
    if (tokens.empty() || static_cast<int32_t>(tokens.size()) > g_n_ctx) {
        g_agent_prefix_cache.clear();
        return "ERROR: invalid token count";
    }

    int32_t cached_tokens = 0;

    if (enable_prefix_cache &&
        g_agent_prefix_cache.is_valid &&
        !session_id.empty() &&
        g_agent_prefix_cache.session_id == session_id &&
        !g_agent_prefix_cache.tokens.empty()) {

        size_t lcp = 0;
        const size_t max_cmp = std::min(g_agent_prefix_cache.tokens.size(), tokens.size());
        while (lcp < max_cmp && g_agent_prefix_cache.tokens[lcp] == tokens[lcp]) {
            lcp++;
        }

        // If exact match (Case A), re-decode at least 1 token to guarantee valid logits
        if (lcp >= tokens.size() && tokens.size() > 0) {
            lcp = tokens.size() - 1;
        }

        if (lcp > 0) {
            // Case B & C: Evict KV Cache positions >= lcp
            llama_memory_t mem = llama_get_memory(g_context);
            llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(lcp), -1);
            cached_tokens = static_cast<int32_t>(lcp);
            __android_log_print(ANDROID_LOG_INFO, kLogTag, "Agent Prefix Cache HIT: lcp=%zu / %zu tokens (cached=%d, new=%zu)",
                                lcp, tokens.size(), cached_tokens, tokens.size() - lcp);
        } else {
            // Case D: Zero common tokens
            llama_memory_clear(llama_get_memory(g_context), true);
            cached_tokens = 0;
            __android_log_print(ANDROID_LOG_INFO, kLogTag, "Agent Prefix Cache MISS: LCP=0, cleared full memory");
        }
    } else {
        // Cold start or disabled
        llama_memory_clear(llama_get_memory(g_context), true);
        cached_tokens = 0;
        __android_log_print(ANDROID_LOG_INFO, kLogTag, "Agent Prefix Cache cold start: cleared full memory");
    }

    const int32_t new_tokens_count = static_cast<int32_t>(tokens.size()) - cached_tokens;

    // Prefill only non-cached tokens
    const auto t_prompt_start = std::chrono::steady_clock::now();
    for (size_t offset = static_cast<size_t>(cached_tokens); offset < tokens.size(); offset += static_cast<size_t>(kBatchSize)) {
        if (cancel_flag != nullptr && cancel_flag->load()) {
            g_agent_prefix_cache.clear();
            if (out_raw_text != nullptr) *out_raw_text = "";
            return "USER_CANCEL";
        }
        const size_t chunk_size = std::min(tokens.size() - offset, static_cast<size_t>(kBatchSize));
        std::vector<llama_pos> chunk_pos(chunk_size);
        for (size_t i = 0; i < chunk_size; ++i) {
            chunk_pos[i] = static_cast<llama_pos>(offset + i);
        }
        llama_batch chunk_batch = llama_batch_get_one(tokens.data() + offset, static_cast<int32_t>(chunk_size));
        chunk_batch.pos = chunk_pos.data();
        if (llama_decode(g_context, chunk_batch) != 0) {
            g_agent_prefix_cache.clear();
            return "ERROR: llama_decode failed";
        }
    }
    const auto t_prompt_end = std::chrono::steady_clock::now();
    const double prompt_processing_time_ms = std::chrono::duration<double, std::milli>(t_prompt_end - t_prompt_start).count();

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    if (vocab == nullptr) {
        g_agent_prefix_cache.clear();
        return "ERROR: vocab is unavailable";
    }
    const int32_t vocab_size = llama_vocab_n_tokens(vocab);
    if (vocab_size <= 0) {
        g_agent_prefix_cache.clear();
        return "ERROR: invalid vocabulary size";
    }

    struct SamplerDeleter {
        void operator()(struct llama_sampler * s) const {
            if (s != nullptr) llama_sampler_free(s);
        }
    };
    std::unique_ptr<struct llama_sampler, SamplerDeleter> rbudget_guard;

    if (enable_thinking && thinking_budget > 0) {
        std::string start_str = !thinking_start_tag.empty() ? thinking_start_tag : "<think>";
        std::vector<std::string> end_strs = !thinking_end_tags.empty() ? thinking_end_tags : std::vector<std::string>{"</think>"};

        std::vector<llama_token> start_tokens;
        tokenize_prompt(start_str, start_tokens);

        std::vector<std::vector<llama_token>> end_seqs;
        for (const auto & et : end_strs) {
            std::vector<llama_token> et_tokens;
            if (tokenize_prompt(et, et_tokens) && !et_tokens.empty()) {
                end_seqs.push_back(et_tokens);
            }
        }
        std::vector<llama_token> forced_tokens;
        if (!end_seqs.empty()) {
            forced_tokens = end_seqs.front();
        } else {
            tokenize_prompt("</think>", forced_tokens);
            if (!forced_tokens.empty()) {
                end_seqs.push_back(forced_tokens);
            }
        }

        if (!start_tokens.empty() && !end_seqs.empty()) {
            struct llama_sampler * rbudget = common_reasoning_budget_init(
                vocab,
                {start_tokens},
                end_seqs,
                forced_tokens,
                thinking_budget,
                REASONING_BUDGET_IDLE
            );
            if (rbudget != nullptr) {
                for (const auto & pt : tokens) {
                    llama_sampler_accept(rbudget, pt);
                }
                rbudget_guard.reset(rbudget);
            }
        }
    }

    const int32_t max_gen_tokens = g_max_gen_tokens > 0 ? g_max_gen_tokens : 128;
    const int32_t max_context_tokens = g_n_ctx;
    constexpr const char * kStopSequence = "<END>";
    const std::string stop_report = stop_tokenization_report(vocab, kStopSequence);

    std::mt19937 rng;
    std::string seed_str;
    if (seed >= 0) { rng.seed(static_cast<uint32_t>(seed)); seed_str = std::to_string(seed); } else { std::random_device rd; rng.seed(rd()); seed_str = "RANDOM"; }

    std::string generated_text;
    int32_t generated_count = 0;
    std::vector<llama_token> generated_tokens;
    generated_tokens.reserve(static_cast<size_t>(max_gen_tokens));
    bool first_token_determined = false;
    std::chrono::steady_clock::time_point t_first_token;
    std::string stop_reason = "MAX_TOKENS";

    for (int32_t i = 0; i < max_gen_tokens; ++i) {
        if (cancel_flag != nullptr && cancel_flag->load()) { stop_reason = "USER_CANCEL"; break; }
        if (static_cast<int32_t>(tokens.size()) + generated_count >= max_context_tokens) { stop_reason = "MAX_CONTEXT"; break; }

        const float * logits = llama_get_logits(g_context);
        if (logits == nullptr) {
            g_agent_prefix_cache.clear();
            return "ERROR: logits are unavailable";
        }

        std::vector<float> penalized_logits;
        const float * effective_logits = logits;
        if (repetition_penalty > 1.0f && std::isfinite(repetition_penalty) && !generated_tokens.empty()) {
            penalized_logits.assign(logits, logits + vocab_size);
            apply_repetition_penalty(penalized_logits, vocab, generated_tokens, repetition_penalty, penalty_last_n);
            effective_logits = penalized_logits.data();
        }

        if (rbudget_guard && common_reasoning_budget_get_state(rbudget_guard.get()) == REASONING_BUDGET_FORCING) {
            std::vector<llama_token_data> cur(static_cast<size_t>(vocab_size));
            for (int32_t token_id = 0; token_id < vocab_size; ++token_id) {
                cur[static_cast<size_t>(token_id)] = { static_cast<llama_token>(token_id), effective_logits[token_id], 0.0f };
            }
            llama_token_data_array cur_p = { cur.data(), cur.size(), -1, false };
            llama_sampler_apply(rbudget_guard.get(), &cur_p);
            penalized_logits.resize(static_cast<size_t>(vocab_size));
            for (size_t k = 0; k < cur.size(); ++k) {
                penalized_logits[static_cast<size_t>(cur[k].id)] = cur[k].logit;
            }
            effective_logits = penalized_logits.data();
        }

        const llama_token current_token = sample_with_sampling_filters(effective_logits, vocab_size, temperature, top_k, top_p, min_p, typical_p, rng);
        if (!first_token_determined) {
            t_first_token = std::chrono::steady_clock::now();
            first_token_determined = true;
            if (on_ttft != nullptr) {
                const double current_ttft_ms = std::chrono::duration<double, std::milli>(t_first_token - t_prompt_start).count();
                on_ttft(current_ttft_ms);
            }
        }

        if (llama_vocab_is_eog(vocab, current_token)) { stop_reason = "EOG"; break; }

        char token_text[256] = {};
        const int32_t token_length = llama_token_to_piece(vocab, current_token, token_text, static_cast<int32_t>(sizeof(token_text)), 0, true);
        if (token_length < 0) {
            g_agent_prefix_cache.clear();
            return "ERROR: llama_token_to_piece failed";
        }
        if (token_length > 0) {
            generated_text.append(token_text, static_cast<size_t>(token_length));
            if (on_token != nullptr) {
                on_token(token_text, token_length);
            }
        }
        generated_count++;
        generated_tokens.push_back(current_token);
        if (rbudget_guard) {
            llama_sampler_accept(rbudget_guard.get(), current_token);
        }

        const size_t stop_pos = generated_text.find(kStopSequence);
        if (stop_pos != std::string::npos) { generated_text.erase(stop_pos); stop_reason = "STOP_SEQUENCE"; break; }

        bool stopped_by_additional = false;
        for (const auto & add_stop : additional_stops) {
            if (add_stop.empty()) continue;
            const size_t pos = generated_text.find(add_stop);
            if (pos != std::string::npos) {
                generated_text.erase(pos);
                stop_reason = "STOP_SEQUENCE";
                stopped_by_additional = true;
                break;
            }
        }
        if (stopped_by_additional) break;

        if (generated_count >= max_gen_tokens) { stop_reason = "MAX_TOKENS"; break; }
        if (cancel_flag != nullptr && cancel_flag->load()) { stop_reason = "USER_CANCEL"; break; }

        llama_token next_token = current_token;
        std::vector<llama_pos> next_pos = { static_cast<llama_pos>(tokens.size() + generated_count - 1) };
        llama_batch token_batch = llama_batch_get_one(&next_token, 1);
        token_batch.pos = next_pos.data();
        if (llama_decode(g_context, token_batch) != 0) {
            g_agent_prefix_cache.clear();
            return "ERROR: llama_decode failed";
        }
    }
    const auto t_generation_end = std::chrono::steady_clock::now();
    double ttft_ms = 0.0, generation_time_ms = 0.0, total_time_ms = 0.0, generation_speed = 0.0;
    if (first_token_determined) {
        ttft_ms = std::chrono::duration<double, std::milli>(t_first_token - t_prompt_start).count();
        generation_time_ms = std::chrono::duration<double, std::milli>(t_generation_end - t_first_token).count();
        total_time_ms = std::chrono::duration<double, std::milli>(t_generation_end - t_prompt_start).count();
        if (generation_time_ms > 0.0 && generated_count > 0) generation_speed = static_cast<double>(generated_count) / (generation_time_ms / 1000.0);
    } else total_time_ms = prompt_processing_time_ms;

    // Update Agent Prefix Cache for next step
    if (stop_reason != "USER_CANCEL" && enable_prefix_cache && !session_id.empty()) {
        g_agent_prefix_cache.session_id = session_id;
        g_agent_prefix_cache.tokens = tokens;
        g_agent_prefix_cache.is_valid = true;
    } else {
        g_agent_prefix_cache.clear();
    }

    __android_log_print(ANDROID_LOG_INFO, kLogTag, "Agent inference metrics: prompt_tokens=%zu (cached=%d, new=%d), gen_tokens=%d, prompt_time=%.2f ms, ttft=%.2f ms, gen_time=%.2f ms, total=%.2f ms, speed=%.2f tokens/sec",
                        tokens.size(), cached_tokens, new_tokens_count, generated_count, prompt_processing_time_ms, ttft_ms, generation_time_ms, total_time_ms, generation_speed);

    if (out_raw_text != nullptr) {
        *out_raw_text = generated_text;
    }
    if (on_prefix_metrics != nullptr) {
        on_prefix_metrics(cached_tokens, new_tokens_count);
    }
    if (on_metrics != nullptr) {
        on_metrics(static_cast<int32_t>(tokens.size()), generated_count, prompt_processing_time_ms, ttft_ms, generation_time_ms, total_time_ms, generation_speed, g_n_threads);
    }

    return "SUCCESS";
}

std::string generate_sampling_agent_session_locked(
    bool is_init,
    const std::string & prompt_input,
    float temperature = kTemperature,
    int32_t top_k = kTopK,
    float top_p = kTopP,
    float min_p = kMinP,
    float typical_p_override = -1.0f,
    float repetition_penalty = kRepetitionPenalty,
    int32_t penalty_last_n = kPenaltyLastN,
    int64_t seed = kSeed,
    bool enable_thinking = false,
    int32_t thinking_budget = 0,
    const std::string & session_id = "",
    const std::function<void(const char*, int32_t)> & on_token = nullptr,
    std::string * out_raw_text = nullptr,
    const std::function<void(double)> & on_ttft = nullptr,
    const std::function<void(int32_t, int32_t, double, double, double, double, double, int32_t)> & on_metrics = nullptr,
    const std::function<void(int32_t, int32_t)> & on_prefix_metrics = nullptr,
    const std::atomic<bool> * cancel_flag = nullptr,
    bool enable_diagnostics = false,
    const std::function<void(const std::string &)> & on_diagnostics = nullptr
) {
    if (g_model == nullptr || g_context == nullptr) {
        return "ERROR: Model or Context not loaded";
    }

    struct rusage rusage_start {};
    struct rusage rusage_end {};
    bool rusage_ok = false;
    if (enable_diagnostics) {
        rusage_ok = (getrusage(RUSAGE_SELF, &rusage_start) == 0);
    }

    std::vector<double> token_latencies;
    std::vector<double> decode_times;
    std::vector<double> sample_times;
    std::vector<double> callback_times;
    std::set<int> observed_cpus;

    std::string prompt_text = prompt_input;
    if (min_p <= 0.0f && g_default_min_p > 0.0f) min_p = g_default_min_p;
    float typical_p = typical_p_override < 0.0f ? g_default_typical_p : typical_p_override;
    parse_prompt_sampling_tags(prompt_text, min_p, typical_p);

    if (!std::isfinite(min_p)) min_p = 0.0f;
    min_p = std::max(0.0f, std::min(1.0f, min_p));
    if (!std::isfinite(typical_p)) typical_p = 1.0f;
    typical_p = std::max(0.0f, std::min(1.0f, typical_p));
    if (!std::isfinite(repetition_penalty) || repetition_penalty < 0.0f) repetition_penalty = 1.0f;

    std::vector<std::string> additional_stops;
    std::string thinking_start_tag;
    std::vector<std::string> thinking_end_tags;

    std::vector<llama_token> prefill_tokens;
    int32_t cached_tokens = 0;
    int32_t new_tokens_count = 0;

    const auto t_prompt_start = std::chrono::steady_clock::now();

    if (is_init) {
        // Step 1: Initial Prompt
        g_agent_session.clear(g_context);

        std::string formatted_prompt;
        std::string chat_template;
        if (!format_chat_prompt(prompt_text, formatted_prompt, chat_template, additional_stops, enable_thinking, &thinking_start_tag, &thinking_end_tags)) {
            g_agent_session.clear(g_context);
            return "ERROR: chat_template_apply failed";
        }

        if (!tokenize_prompt(formatted_prompt, prefill_tokens)) {
            g_agent_session.clear(g_context);
            return "ERROR: llama_tokenize failed";
        }

        if (prefill_tokens.empty() || static_cast<int32_t>(prefill_tokens.size()) > g_n_ctx) {
            g_agent_session.clear(g_context);
            return "ERROR: invalid token count";
        }

        cached_tokens = 0;
        new_tokens_count = static_cast<int32_t>(prefill_tokens.size());

        // Decode initial prompt tokens from position 0
        for (size_t offset = 0; offset < prefill_tokens.size(); offset += static_cast<size_t>(kBatchSize)) {
            if (cancel_flag != nullptr && cancel_flag->load()) {
                g_agent_session.clear(g_context);
                return "ERROR: Cancelled before prompt processing";
            }
            const size_t chunk_size = std::min(prefill_tokens.size() - offset, static_cast<size_t>(kBatchSize));
            std::vector<llama_pos> chunk_pos(chunk_size);
            for (size_t i = 0; i < chunk_size; ++i) {
                chunk_pos[i] = static_cast<llama_pos>(offset + i);
            }
            llama_batch chunk_batch = llama_batch_get_one(prefill_tokens.data() + offset, static_cast<int32_t>(chunk_size));
            chunk_batch.pos = chunk_pos.data();
            if (llama_decode(g_context, chunk_batch) != 0) {
                g_agent_session.clear(g_context);
                return "ERROR: llama_decode failed during init prefill";
            }
        }

        g_agent_session.n_past = static_cast<int32_t>(prefill_tokens.size());
        g_agent_session.tokens = prefill_tokens;
        g_agent_session.session_id = session_id;
        g_agent_session.is_active = true;

    } else {
        // Step 2+: Delta Prompt (Tool Result)
        if (!g_agent_session.is_active || g_agent_session.session_id != session_id || g_agent_session.n_past <= 0) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag, "Agent Session mismatch or inactive: expected=%s, current=%s, is_active=%d",
                                session_id.c_str(), g_agent_session.session_id.c_str(), g_agent_session.is_active);
            g_agent_session.clear(g_context);
            return "ERROR: Agent session not found or inactive";
        }

        // Tokenize delta prompt directly
        if (!tokenize_prompt(prompt_text, prefill_tokens)) {
            g_agent_session.clear(g_context);
            return "ERROR: llama_tokenize failed for delta prompt";
        }

        if (prefill_tokens.empty()) {
            g_agent_session.clear(g_context);
            return "ERROR: delta tokens empty";
        }

        if (g_agent_session.n_past + static_cast<int32_t>(prefill_tokens.size()) >= g_n_ctx) {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Agent Context Overflow: n_past=%d + delta=%zu >= n_ctx=%d",
                                g_agent_session.n_past, prefill_tokens.size(), g_n_ctx);
            g_agent_session.clear(g_context);
            return "ERROR: context overflow";
        }

        cached_tokens = g_agent_session.n_past;
        new_tokens_count = static_cast<int32_t>(prefill_tokens.size());

        // Decode delta tokens from current n_past
        for (size_t offset = 0; offset < prefill_tokens.size(); offset += static_cast<size_t>(kBatchSize)) {
            if (cancel_flag != nullptr && cancel_flag->load()) {
                g_agent_session.clear(g_context);
                return "ERROR: Cancelled before delta processing";
            }
            const size_t chunk_size = std::min(prefill_tokens.size() - offset, static_cast<size_t>(kBatchSize));
            std::vector<llama_pos> chunk_pos(chunk_size);
            for (size_t i = 0; i < chunk_size; ++i) {
                chunk_pos[i] = static_cast<llama_pos>(g_agent_session.n_past + offset + i);
            }
            llama_batch chunk_batch = llama_batch_get_one(prefill_tokens.data() + offset, static_cast<int32_t>(chunk_size));
            chunk_batch.pos = chunk_pos.data();
            if (llama_decode(g_context, chunk_batch) != 0) {
                g_agent_session.clear(g_context);
                return "ERROR: llama_decode failed during delta prefill";
            }
        }

        g_agent_session.n_past += static_cast<int32_t>(prefill_tokens.size());
        g_agent_session.tokens.insert(g_agent_session.tokens.end(), prefill_tokens.begin(), prefill_tokens.end());
    }

    const auto t_prompt_end = std::chrono::steady_clock::now();
    const double prompt_processing_time_ms = std::chrono::duration<double, std::milli>(t_prompt_end - t_prompt_start).count();

    if (on_prefix_metrics != nullptr) {
        on_prefix_metrics(cached_tokens, new_tokens_count);
    }

    // Prepare Generation
    std::string generated_text;
    int32_t generated_count = 0;
    std::string stop_reason = "NONE";
    std::vector<llama_token> generated_tokens;
    std::chrono::steady_clock::time_point t_first_token;
    bool first_token_determined = false;

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    const int32_t vocab_size = llama_vocab_n_tokens(vocab);
    const int32_t max_gen_tokens = g_max_gen_tokens > 0 ? g_max_gen_tokens : 128;
    const int32_t max_context_tokens = g_n_ctx > 0 ? g_n_ctx : 2048;
    constexpr const char * kStopSequence = "<END>";

    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> rbudget_guard(nullptr, &llama_sampler_free);
    if (enable_thinking && thinking_budget > 0) {
        std::string start_str = !thinking_start_tag.empty() ? thinking_start_tag : "<think>";
        std::vector<std::string> end_strs = !thinking_end_tags.empty() ? thinking_end_tags : std::vector<std::string>{"</think>"};

        std::vector<llama_token> start_tokens;
        tokenize_prompt(start_str, start_tokens);
        std::vector<std::vector<llama_token>> end_seqs;
        for (const auto & et : end_strs) {
            std::vector<llama_token> et_tokens;
            if (tokenize_prompt(et, et_tokens) && !et_tokens.empty()) {
                end_seqs.push_back(et_tokens);
            }
        }
        std::vector<llama_token> forced_tokens;
        if (!end_seqs.empty()) {
            forced_tokens = end_seqs.front();
        } else {
            tokenize_prompt("</think>", forced_tokens);
            if (!forced_tokens.empty()) {
                end_seqs.push_back(forced_tokens);
            }
        }

        if (!start_tokens.empty() && !end_seqs.empty()) {
            struct llama_sampler * rbudget = common_reasoning_budget_init(
                vocab,
                {start_tokens},
                end_seqs,
                forced_tokens,
                thinking_budget,
                REASONING_BUDGET_IDLE
            );
            if (rbudget != nullptr) {
                for (const auto & pt : g_agent_session.tokens) {
                    llama_sampler_accept(rbudget, pt);
                }
                rbudget_guard.reset(rbudget);
            }
        }
    }

    std::mt19937 rng;
    std::string seed_str;
    if (seed >= 0) { rng.seed(static_cast<uint32_t>(seed)); seed_str = std::to_string(seed); } else { std::random_device rd; rng.seed(rd()); seed_str = "RANDOM"; }

    // Generation Loop
    while (true) {
        if (cancel_flag != nullptr && cancel_flag->load()) {
            stop_reason = "USER_CANCEL";
            break;
        }

        if (g_agent_session.n_past >= max_context_tokens) {
            stop_reason = "MAX_CONTEXT";
            break;
        }

        const float * logits = llama_get_logits(g_context);
        if (logits == nullptr) {
            g_agent_session.clear(g_context);
            return "ERROR: logits are unavailable";
        }

        std::vector<float> penalized_logits;
        const float * effective_logits = logits;
        if (repetition_penalty > 1.0f && std::isfinite(repetition_penalty) && !generated_tokens.empty()) {
            penalized_logits.assign(logits, logits + vocab_size);
            apply_repetition_penalty(penalized_logits, vocab, generated_tokens, repetition_penalty, penalty_last_n);
            effective_logits = penalized_logits.data();
        }

        if (rbudget_guard && common_reasoning_budget_get_state(rbudget_guard.get()) == REASONING_BUDGET_FORCING) {
            std::vector<llama_token_data> cur(static_cast<size_t>(vocab_size));
            for (int32_t token_id = 0; token_id < vocab_size; ++token_id) {
                cur[static_cast<size_t>(token_id)] = { static_cast<llama_token>(token_id), effective_logits[token_id], 0.0f };
            }
            llama_token_data_array cur_p = { cur.data(), cur.size(), -1, false };
            llama_sampler_apply(rbudget_guard.get(), &cur_p);
            penalized_logits.resize(static_cast<size_t>(vocab_size));
            for (size_t k = 0; k < cur.size(); ++k) {
                penalized_logits[static_cast<size_t>(cur[k].id)] = cur[k].logit;
            }
            effective_logits = penalized_logits.data();
        }

        std::chrono::steady_clock::time_point t_tok_iter_start;
        if (enable_diagnostics) {
            t_tok_iter_start = std::chrono::steady_clock::now();
            int cpu = get_current_cpu_core();
            if (cpu >= 0) observed_cpus.insert(cpu);
        }

        std::chrono::steady_clock::time_point t_sample_start;
        if (enable_diagnostics) {
            t_sample_start = std::chrono::steady_clock::now();
        }

        const llama_token current_token = sample_with_sampling_filters(effective_logits, vocab_size, temperature, top_k, top_p, min_p, typical_p, rng);

        if (enable_diagnostics) {
            auto t_sample_end = std::chrono::steady_clock::now();
            double s_ms = std::chrono::duration<double, std::milli>(t_sample_end - t_sample_start).count();
            sample_times.push_back(s_ms);
        }

        if (!first_token_determined) {
            t_first_token = std::chrono::steady_clock::now();
            first_token_determined = true;
            if (on_ttft != nullptr) {
                const double current_ttft_ms = std::chrono::duration<double, std::milli>(t_first_token - t_prompt_start).count();
                on_ttft(current_ttft_ms);
            }
        }

        if (llama_vocab_is_eog(vocab, current_token)) {
            stop_reason = "EOG";
            break;
        }

        char token_text[256] = {};
        const int32_t token_length = llama_token_to_piece(vocab, current_token, token_text, static_cast<int32_t>(sizeof(token_text)), 0, true);
        if (token_length < 0) {
            g_agent_session.clear(g_context);
            return "ERROR: llama_token_to_piece failed";
        }
        if (token_length > 0) {
            generated_text.append(token_text, static_cast<size_t>(token_length));
            if (on_token != nullptr) {
                std::chrono::steady_clock::time_point t_cb_start;
                if (enable_diagnostics) {
                    t_cb_start = std::chrono::steady_clock::now();
                }

                on_token(token_text, token_length);

                if (enable_diagnostics) {
                    auto t_cb_end = std::chrono::steady_clock::now();
                    double cb_ms = std::chrono::duration<double, std::milli>(t_cb_end - t_cb_start).count();
                    callback_times.push_back(cb_ms);
                }
            }
        }
        generated_count++;
        generated_tokens.push_back(current_token);

        if (rbudget_guard) {
            llama_sampler_accept(rbudget_guard.get(), current_token);
        }

        // Stop sequence checks
        const size_t stop_pos = generated_text.find(kStopSequence);
        if (stop_pos != std::string::npos) { generated_text.erase(stop_pos); stop_reason = "STOP_SEQUENCE"; }

        bool stopped_by_additional = false;
        for (const auto & add_stop : additional_stops) {
            if (add_stop.empty()) continue;
            const size_t pos = generated_text.find(add_stop);
            if (pos != std::string::npos) {
                generated_text.erase(pos);
                stop_reason = "STOP_SEQUENCE";
                stopped_by_additional = true;
                break;
            }
        }
        if (stopped_by_additional) break;
        if (stop_pos != std::string::npos) break;

        if (generated_count >= max_gen_tokens) { stop_reason = "MAX_TOKENS"; break; }
        if (cancel_flag != nullptr && cancel_flag->load()) { stop_reason = "USER_CANCEL"; break; }

        // Decode this token into KV Cache at position g_agent_session.n_past
        llama_token next_token = current_token;
        llama_pos next_pos = static_cast<llama_pos>(g_agent_session.n_past);
        llama_batch token_batch = llama_batch_get_one(&next_token, 1);
        token_batch.pos = &next_pos;

        std::chrono::steady_clock::time_point t_decode_start;
        if (enable_diagnostics) {
            t_decode_start = std::chrono::steady_clock::now();
        }

        int decode_res = llama_decode(g_context, token_batch);

        if (enable_diagnostics) {
            auto t_decode_end = std::chrono::steady_clock::now();
            double d_ms = std::chrono::duration<double, std::milli>(t_decode_end - t_decode_start).count();
            decode_times.push_back(d_ms);
            double tok_ms = std::chrono::duration<double, std::milli>(t_decode_end - t_tok_iter_start).count();
            token_latencies.push_back(tok_ms);
        }

        if (decode_res != 0) {
            g_agent_session.clear(g_context);
            return "ERROR: llama_decode failed during session generation";
        }
        g_agent_session.tokens.push_back(current_token);
        g_agent_session.n_past++;
    }

    const auto t_generation_end = std::chrono::steady_clock::now();
    double ttft_ms = 0.0, generation_time_ms = 0.0, total_time_ms = 0.0, generation_speed = 0.0;
    if (first_token_determined) {
        ttft_ms = std::chrono::duration<double, std::milli>(t_first_token - t_prompt_start).count();
        generation_time_ms = std::chrono::duration<double, std::milli>(t_generation_end - t_first_token).count();
        total_time_ms = std::chrono::duration<double, std::milli>(t_generation_end - t_prompt_start).count();
        if (generation_time_ms > 0.0 && generated_count > 0) generation_speed = static_cast<double>(generated_count) / (generation_time_ms / 1000.0);
    } else total_time_ms = prompt_processing_time_ms;

    if (enable_diagnostics && on_diagnostics != nullptr) {
        long minor_faults = 0;
        long major_faults = 0;
        bool faults_avail = false;
        if (rusage_ok && getrusage(RUSAGE_SELF, &rusage_end) == 0) {
            minor_faults = rusage_end.ru_minflt - rusage_start.ru_minflt;
            major_faults = rusage_end.ru_majflt - rusage_start.ru_majflt;
            faults_avail = true;
        }

        std::string cpu_str;
        for (int c : observed_cpus) {
            if (!cpu_str.empty()) cpu_str += ", ";
            cpu_str += std::to_string(c);
        }

        size_t n_tokens = token_latencies.size();
        double first10_avg_ms = 0.0, first10_tok_s = 0.0;
        bool has_first10 = false;
        if (n_tokens >= 1) {
            has_first10 = true;
            size_t k = std::min<size_t>(10, n_tokens);
            double sum = 0.0;
            for (size_t i = 0; i < k; ++i) sum += token_latencies[i];
            first10_avg_ms = sum / static_cast<double>(k);
            if (first10_avg_ms > 0.0) first10_tok_s = 1000.0 / first10_avg_ms;
        }

        double first20_avg_ms = 0.0, first20_tok_s = 0.0;
        bool has_first20 = false;
        if (n_tokens >= 20) {
            has_first20 = true;
            double sum = 0.0;
            for (size_t i = 0; i < 20; ++i) sum += token_latencies[i];
            first20_avg_ms = sum / 20.0;
            if (first20_avg_ms > 0.0) first20_tok_s = 1000.0 / first20_avg_ms;
        }

        double middle_avg_ms = 0.0;
        bool has_middle = false;
        if (n_tokens > 20) {
            has_middle = true;
            size_t start_idx = 10;
            size_t end_idx = n_tokens - 10;
            double sum = 0.0;
            for (size_t i = start_idx; i < end_idx; ++i) sum += token_latencies[i];
            middle_avg_ms = sum / static_cast<double>(end_idx - start_idx);
        }

        double last10_avg_ms = 0.0, last10_tok_s = 0.0;
        bool has_last10 = false;
        if (n_tokens >= 1) {
            has_last10 = true;
            size_t k = std::min<size_t>(10, n_tokens);
            double sum = 0.0;
            for (size_t i = n_tokens - k; i < n_tokens; ++i) sum += token_latencies[i];
            last10_avg_ms = sum / static_cast<double>(k);
            if (last10_avg_ms > 0.0) last10_tok_s = 1000.0 / last10_avg_ms;
        }

        double decode_total_ms = 0.0, decode_avg_ms = 0.0, decode_min_ms = 0.0, decode_max_ms = 0.0;
        if (!decode_times.empty()) {
            decode_min_ms = decode_times.front();
            decode_max_ms = decode_times.front();
            for (double d : decode_times) {
                decode_total_ms += d;
                if (d < decode_min_ms) decode_min_ms = d;
                if (d > decode_max_ms) decode_max_ms = d;
            }
            decode_avg_ms = decode_total_ms / static_cast<double>(decode_times.size());
        }

        double sampling_total_ms = 0.0, sampling_avg_ms = 0.0, sampling_max_ms = 0.0;
        if (!sample_times.empty()) {
            sampling_max_ms = sample_times.front();
            for (double s : sample_times) {
                sampling_total_ms += s;
                if (s > sampling_max_ms) sampling_max_ms = s;
            }
            sampling_avg_ms = sampling_total_ms / static_cast<double>(sample_times.size());
        }

        double callback_total_ms = 0.0, callback_avg_ms = 0.0, callback_max_ms = 0.0;
        if (!callback_times.empty()) {
            callback_max_ms = callback_times.front();
            for (double c : callback_times) {
                callback_total_ms += c;
                if (c > callback_max_ms) callback_max_ms = c;
            }
            callback_avg_ms = callback_total_ms / static_cast<double>(callback_times.size());
        }

        char buf[2048];
        snprintf(buf, sizeof(buf),
            "{"
            "\"generated_tokens\":%d,"
            "\"generation_time_ms\":%.2f,"
            "\"speed_tok_per_sec\":%.2f,"
            "\"first10_avg_ms\":%.2f,\"first10_tok_s\":%.2f,\"has_first10\":%s,"
            "\"first20_avg_ms\":%.2f,\"first20_tok_s\":%.2f,\"has_first20\":%s,"
            "\"middle_avg_ms\":%.2f,\"has_middle\":%s,"
            "\"last10_avg_ms\":%.2f,\"last10_tok_s\":%.2f,\"has_last10\":%s,"
            "\"decode_total_ms\":%.2f,\"decode_avg_ms\":%.2f,\"decode_min_ms\":%.2f,\"decode_max_ms\":%.2f,"
            "\"sampling_total_ms\":%.2f,\"sampling_avg_ms\":%.2f,\"sampling_max_ms\":%.2f,"
            "\"callback_total_ms\":%.2f,\"callback_avg_ms\":%.2f,\"callback_max_ms\":%.2f,"
            "\"minor_page_faults\":%ld,\"major_page_faults\":%ld,\"page_faults_available\":%s,"
            "\"cpu_cores\":\"%s\""
            "}",
            generated_count, generation_time_ms, generation_speed,
            first10_avg_ms, first10_tok_s, has_first10 ? "true" : "false",
            first20_avg_ms, first20_tok_s, has_first20 ? "true" : "false",
            middle_avg_ms, has_middle ? "true" : "false",
            last10_avg_ms, last10_tok_s, has_last10 ? "true" : "false",
            decode_total_ms, decode_avg_ms, decode_min_ms, decode_max_ms,
            sampling_total_ms, sampling_avg_ms, sampling_max_ms,
            callback_total_ms, callback_avg_ms, callback_max_ms,
            minor_faults, major_faults, faults_avail ? "true" : "false",
            cpu_str.c_str()
        );
        on_diagnostics(std::string(buf));
    }

    // Maintain session state if not cancelled
    if (stop_reason != "USER_CANCEL" && !session_id.empty()) {
        g_agent_session.session_id = session_id;
        g_agent_session.is_active = true;
    } else {
        g_agent_session.clear(g_context);
    }

    __android_log_print(ANDROID_LOG_INFO, kLogTag, "Agent Session inference metrics: cached=%d, new=%d, gen=%d, prompt_time=%.2f ms, ttft=%.2f ms, gen_time=%.2f ms, speed=%.2f tok/s",
                        cached_tokens, new_tokens_count, generated_count, prompt_processing_time_ms, ttft_ms, generation_time_ms, generation_speed);

    if (on_metrics != nullptr) {
        const int32_t total_prompt = cached_tokens + new_tokens_count;
        on_metrics(total_prompt, generated_count, prompt_processing_time_ms, ttft_ms, generation_time_ms, total_time_ms, generation_speed, g_n_threads);
    }

    if (out_raw_text != nullptr) {
        *out_raw_text = generated_text;
    }

    return "SUCCESS";
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
    llama_context_params context_params = create_context_params_locked(g_n_ctx, g_kv_cache_type);
    g_context = llama_init_from_model(g_model, context_params);
    env->ReleaseStringUTFChars(model_path, path);
    if (g_context == nullptr) { llama_model_free(g_model); g_model = nullptr; return env->NewStringUTF("ERROR: model loaded, but llama_init_from_model failed"); }
    g_chat_templates = common_chat_templates_init(g_model, "");
    return env->NewStringUTF("SUCCESS: GGUF model + context loaded");
}

extern "C" JNIEXPORT jstring JNICALL Java_com_example_MainActivity_nativeRunTestInference(JNIEnv* env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    g_cancel_generation.store(false);
    if (g_model == nullptr || g_context == nullptr) return env->NewStringUTF("ERROR: model/context is not loaded");
    const std::string result = generate_sampling_locked("こんにちは。短く自己紹介してください。");
    return new_jstring_from_utf8(env, result);
}

extern "C" JNIEXPORT jstring JNICALL Java_com_example_MainActivity_nativeEchoPrompt(JNIEnv* env, jobject /* this */, jstring prompt) {
    if (prompt == nullptr) return env->NewStringUTF("ERROR: prompt is null");
    const char * prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    if (prompt_chars == nullptr) return env->NewStringUTF("ERROR: failed to read prompt");
    const std::string prompt_text(prompt_chars);
    env->ReleaseStringUTFChars(prompt, prompt_chars);
    std::lock_guard<std::mutex> lock(g_model_mutex);
    g_cancel_generation.store(false);
    if (g_model == nullptr || g_context == nullptr) return env->NewStringUTF("ERROR: model/context is not loaded");
    const std::string result = generate_sampling_locked(prompt_text);
    return new_jstring_from_utf8(env, result);
}

extern "C" JNIEXPORT jstring JNICALL Java_com_example_MainActivity_nativeGenerateWithTemperature(JNIEnv* env, jobject /* this */, jstring prompt, jfloat temperature) {
    if (prompt == nullptr) return env->NewStringUTF("ERROR: prompt is null");
    const char * prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    if (prompt_chars == nullptr) return env->NewStringUTF("ERROR: failed to read prompt");
    const std::string prompt_text(prompt_chars);
    env->ReleaseStringUTFChars(prompt, prompt_chars);
    std::lock_guard<std::mutex> lock(g_model_mutex);
    g_cancel_generation.store(false);
    if (g_model == nullptr || g_context == nullptr) return env->NewStringUTF("ERROR: model/context is not loaded");
    const std::string result = generate_sampling_locked(prompt_text, static_cast<float>(temperature));
    return new_jstring_from_utf8(env, result);
}

extern "C" JNIEXPORT jstring JNICALL Java_com_example_MainActivity_nativeGenerateWithTemperatureAndTopK(JNIEnv* env, jobject /* this */, jstring prompt, jfloat temperature, jint top_k) {
    if (prompt == nullptr) return env->NewStringUTF("ERROR: prompt is null");
    const char * prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    if (prompt_chars == nullptr) return env->NewStringUTF("ERROR: failed to read prompt");
    const std::string prompt_text(prompt_chars);
    env->ReleaseStringUTFChars(prompt, prompt_chars);
    std::lock_guard<std::mutex> lock(g_model_mutex);
    g_cancel_generation.store(false);
    if (g_model == nullptr || g_context == nullptr) return env->NewStringUTF("ERROR: model/context is not loaded");
    const std::string result = generate_sampling_locked(prompt_text, static_cast<float>(temperature), static_cast<int32_t>(top_k));
    return new_jstring_from_utf8(env, result);
}

extern "C" JNIEXPORT jstring JNICALL Java_com_example_MainActivity_nativeGenerateWithTemperatureTopKTopP(JNIEnv* env, jobject /* this */, jstring prompt, jfloat temperature, jint top_k, jfloat top_p) {
    if (prompt == nullptr) return env->NewStringUTF("ERROR: prompt is null");
    const char * prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    if (prompt_chars == nullptr) return env->NewStringUTF("ERROR: failed to read prompt");
    const std::string prompt_text(prompt_chars);
    env->ReleaseStringUTFChars(prompt, prompt_chars);
    std::lock_guard<std::mutex> lock(g_model_mutex);
    g_cancel_generation.store(false);
    if (g_model == nullptr || g_context == nullptr) return env->NewStringUTF("ERROR: model/context is not loaded");
    const std::string result = generate_sampling_locked(prompt_text, static_cast<float>(temperature), static_cast<int32_t>(top_k), static_cast<float>(top_p), g_default_min_p);
    return new_jstring_from_utf8(env, result);
}

extern "C" JNIEXPORT jstring JNICALL Java_com_example_MainActivity_nativeGenerateWithTemperatureTopKTopPMinP(JNIEnv* env, jobject /* this */, jstring prompt, jfloat temperature, jint top_k, jfloat top_p, jfloat min_p) {
    if (prompt == nullptr) return env->NewStringUTF("ERROR: prompt is null");
    const char * prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    if (prompt_chars == nullptr) return env->NewStringUTF("ERROR: failed to read prompt");
    const std::string prompt_text(prompt_chars);
    env->ReleaseStringUTFChars(prompt, prompt_chars);
    std::lock_guard<std::mutex> lock(g_model_mutex);
    g_cancel_generation.store(false);
    if (g_model == nullptr || g_context == nullptr) return env->NewStringUTF("ERROR: model/context is not loaded");
    const std::string result = generate_sampling_locked(prompt_text, static_cast<float>(temperature), static_cast<int32_t>(top_k), static_cast<float>(top_p), static_cast<float>(min_p));
    return new_jstring_from_utf8(env, result);
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

extern "C" JNIEXPORT void JNICALL
Java_com_example_MainActivity_nativeSetThreads(
        JNIEnv* /* env */, jobject /* this */, jint n_threads, jint n_threads_batch) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    g_n_threads = (n_threads > 0) ? static_cast<int32_t>(n_threads) : 4;
    g_n_threads_batch = (n_threads_batch > 0) ? static_cast<int32_t>(n_threads_batch) : 4;
    if (g_context != nullptr) {
        llama_set_n_threads(g_context, g_n_threads, g_n_threads_batch);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_MainActivity_nativeSetContextSize(
        JNIEnv* /* env */, jobject /* this */, jint n_ctx) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    if (n_ctx != 512 && n_ctx != 1024 && n_ctx != 2048 && n_ctx != 4096 &&
        n_ctx != 8192 && n_ctx != 16384 && n_ctx != 24576 && n_ctx != 32768) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "nativeSetContextSize: invalid n_ctx=%d", n_ctx);
        return JNI_FALSE;
    }
    const int32_t target_ctx = static_cast<int32_t>(n_ctx);
    if (target_ctx == g_n_ctx && g_context != nullptr) return JNI_TRUE;

    g_agent_prefix_cache.clear();

    // If a model is currently loaded, re-initialize g_context safely
    if (g_model != nullptr) {
        llama_context_params context_params = create_context_params_locked(target_ctx, g_kv_cache_type);

        llama_context * new_context = llama_init_from_model(g_model, context_params);
        if (new_context == nullptr) {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag, "nativeSetContextSize: llama_init_from_model failed for n_ctx=%d", target_ctx);
            return JNI_FALSE;
        }

        if (g_context != nullptr) {
            llama_free(g_context);
        }
        g_context = new_context;
        g_n_ctx = target_ctx;
    } else {
        g_n_ctx = target_ctx;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_MainActivity_nativeGetContextSize(
        JNIEnv* /* env */, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    return static_cast<jint>(g_n_ctx);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_MainActivity_nativeSetKvCacheType(
        JNIEnv* env, jobject /* this */, jstring kv_type) {
    if (kv_type == nullptr) {
        return JNI_FALSE;
    }
    const char * kv_type_chars = env->GetStringUTFChars(kv_type, nullptr);
    if (kv_type_chars == nullptr) {
        return JNI_FALSE;
    }
    const std::string target_type(kv_type_chars);
    env->ReleaseStringUTFChars(kv_type, kv_type_chars);

    // Validation: Only accept "Auto", "Q8_0", "Q4_0"
    if (target_type != "Auto" && target_type != "Q8_0" && target_type != "Q4_0") {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "nativeSetKvCacheType: invalid kv_type=%s", target_type.c_str());
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(g_model_mutex);
    if (target_type == g_kv_cache_type && g_context != nullptr) {
        return JNI_TRUE;
    }

    g_agent_prefix_cache.clear();

    // If a model is currently loaded, re-initialize g_context safely
    if (g_model != nullptr) {
        llama_context_params context_params = create_context_params_locked(g_n_ctx, target_type);

        llama_context * new_context = llama_init_from_model(g_model, context_params);
        if (new_context == nullptr) {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag, "nativeSetKvCacheType: llama_init_from_model failed for kv_type=%s", target_type.c_str());
            return JNI_FALSE;
        }

        if (g_context != nullptr) {
            llama_free(g_context);
        }
        g_context = new_context;
        g_kv_cache_type = target_type;
    } else {
        g_kv_cache_type = target_type;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_MainActivity_nativeGetKvCacheType(
        JNIEnv* env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    return env->NewStringUTF(g_kv_cache_type.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_MainActivity_nativeSetMaxOutputTokens(
        JNIEnv* /* env */, jobject /* this */, jint max_output_tokens) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    if (max_output_tokens <= 0) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "nativeSetMaxOutputTokens: invalid max_output_tokens=%d", max_output_tokens);
        return JNI_FALSE;
    }
    g_max_gen_tokens = static_cast<int32_t>(max_output_tokens);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_MainActivity_nativeGetMaxOutputTokens(
        JNIEnv* /* env */, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    return static_cast<jint>(g_max_gen_tokens);
}

extern "C" JNIEXPORT void JNICALL Java_com_example_MainActivity_nativeUnloadModel(JNIEnv* /* env */, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    unload_model_locked();
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_example_MainActivity_nativeIsModelLoaded(JNIEnv* /* env */, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    return (g_model != nullptr && g_context != nullptr) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_MainActivity_nativeGenerateWithSampling(
        JNIEnv * env, jobject /* this */, jstring prompt, jfloat temperature, jint top_k, jfloat top_p,
        jfloat min_p, jfloat typical_p, jfloat repetition_penalty, jint penalty_last_n, jlong seed,
        jboolean enable_thinking) {
    const char * chars = env->GetStringUTFChars(prompt, nullptr);
    if (chars == nullptr) return env->NewStringUTF("ERROR: prompt unavailable");
    const std::string result = [&]() {
        std::lock_guard<std::mutex> lock(g_model_mutex);
        g_cancel_ai_generation.store(false);
        if (g_model == nullptr || g_context == nullptr) return std::string("ERROR: model is not loaded");
        return generate_sampling_locked(chars, temperature, top_k, top_p, min_p, typical_p, repetition_penalty, penalty_last_n, seed, enable_thinking == JNI_TRUE, 0, nullptr, nullptr, nullptr, nullptr, &g_cancel_ai_generation);
    }();
    env->ReleaseStringUTFChars(prompt, chars);
    return new_jstring_from_utf8(env, result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_MainActivity_nativeGenerateStream(
        JNIEnv * env, jobject /* this */, jstring prompt, jfloat temperature, jint top_k, jfloat top_p,
        jfloat min_p, jfloat typical_p, jfloat repetition_penalty, jint penalty_last_n, jlong seed,
        jboolean enable_thinking, jint thinking_budget, jobject token_callback) {
    if (prompt == nullptr) return env->NewStringUTF("ERROR: prompt is null");
    const char * chars = env->GetStringUTFChars(prompt, nullptr);
    if (chars == nullptr) return env->NewStringUTF("ERROR: prompt unavailable");

    jclass cb_class = token_callback != nullptr ? env->GetObjectClass(token_callback) : nullptr;
    jmethodID on_token_mid = cb_class != nullptr ? env->GetMethodID(cb_class, "onToken", "(Ljava/lang/String;)V") : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();
    jmethodID on_ttft_mid = cb_class != nullptr ? env->GetMethodID(cb_class, "onTtft", "(D)V") : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();
    jmethodID on_metrics_mid = cb_class != nullptr ? env->GetMethodID(cb_class, "onMetrics", "(IIDDDDDI)V") : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();

    std::string raw_text;
    std::string status_or_err;
    std::string pending_utf8;

    {
        std::lock_guard<std::mutex> lock(g_model_mutex);
        g_cancel_talk_generation.store(false);
        if (g_model == nullptr || g_context == nullptr) {
            if (cb_class != nullptr) env->DeleteLocalRef(cb_class);
            env->ReleaseStringUTFChars(prompt, chars);
            return env->NewStringUTF("ERROR: model is not loaded");
        }
        status_or_err = generate_sampling_locked(
            chars, temperature, top_k, top_p, min_p, typical_p, repetition_penalty, penalty_last_n, seed,
            enable_thinking == JNI_TRUE, static_cast<int32_t>(thinking_budget),
            [&](const char * piece, int32_t len) {
                if (token_callback != nullptr && on_token_mid != nullptr && len > 0) {
                    pending_utf8.append(piece, static_cast<size_t>(len));
                    size_t consumed = 0;
                    std::vector<jchar> utf16;
                    append_valid_utf8_to_utf16(pending_utf8, consumed, utf16, false);
                    if (consumed > 0) {
                        pending_utf8.erase(0, consumed);
                        jstring jpiece = env->NewString(utf16.empty() ? nullptr : utf16.data(), static_cast<jsize>(utf16.size()));
                        if (jpiece != nullptr) {
                            env->CallVoidMethod(token_callback, on_token_mid, jpiece);
                            env->DeleteLocalRef(jpiece);
                            if (env->ExceptionCheck()) {
                                env->ExceptionClear();
                            }
                        }
                    }
                }
            },
            &raw_text,
            [&](double ttft_ms) {
                if (token_callback != nullptr && on_ttft_mid != nullptr) {
                    env->CallVoidMethod(token_callback, on_ttft_mid, static_cast<jdouble>(ttft_ms));
                    if (env->ExceptionCheck()) {
                        env->ExceptionClear();
                    }
                }
            },
            [&](int32_t prompt_tokens, int32_t gen_tokens, double prompt_time_ms, double ttft_ms, double gen_time_ms, double total_time_ms, double speed, int32_t threads) {
                if (token_callback != nullptr && on_metrics_mid != nullptr) {
                    env->CallVoidMethod(token_callback, on_metrics_mid,
                        static_cast<jint>(prompt_tokens),
                        static_cast<jint>(gen_tokens),
                        static_cast<jdouble>(prompt_time_ms),
                        static_cast<jdouble>(ttft_ms),
                        static_cast<jdouble>(gen_time_ms),
                        static_cast<jdouble>(total_time_ms),
                        static_cast<jdouble>(speed),
                        static_cast<jint>(threads));
                    if (env->ExceptionCheck()) {
                        env->ExceptionClear();
                    }
                }
            },
            &g_cancel_talk_generation
        );

        if (!g_cancel_talk_generation.load() && !pending_utf8.empty()) {
            size_t consumed = 0;
            std::vector<jchar> utf16;
            append_valid_utf8_to_utf16(pending_utf8, consumed, utf16, true);
            if (!utf16.empty() && token_callback != nullptr && on_token_mid != nullptr) {
                jstring jpiece = env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
                if (jpiece != nullptr) {
                    env->CallVoidMethod(token_callback, on_token_mid, jpiece);
                    env->DeleteLocalRef(jpiece);
                    if (env->ExceptionCheck()) {
                        env->ExceptionClear();
                    }
                }
            }
            pending_utf8.clear();
        }
    }
    if (cb_class != nullptr) {
        env->DeleteLocalRef(cb_class);
    }
    env->ReleaseStringUTFChars(prompt, chars);
    if (status_or_err.rfind("ERROR:", 0) == 0) {
        return new_jstring_from_utf8(env, status_or_err);
    }
    return new_jstring_from_utf8(env, raw_text);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_MainActivity_nativeCancelGeneration(JNIEnv * /* env */, jobject /* this */) {
    g_cancel_talk_generation.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_MainActivity_nativeCancelAiGeneration(JNIEnv * /* env */, jobject /* this */) {
    g_cancel_ai_generation.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_MainActivity_nativeClearAgentPrefixCache(JNIEnv * /* env */, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    g_agent_prefix_cache.clear();
    if (g_context != nullptr) {
        llama_memory_clear(llama_get_memory(g_context), true);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_MainActivity_nativeGenerateStreamAgent(
        JNIEnv * env, jobject /* this */, jstring prompt, jfloat temperature, jint top_k, jfloat top_p,
        jfloat min_p, jfloat typical_p, jfloat repetition_penalty, jint penalty_last_n, jlong seed,
        jboolean enable_thinking, jint thinking_budget,
        jstring session_id, jboolean enable_prefix_cache,
        jobject token_callback) {
    if (prompt == nullptr) return env->NewStringUTF("ERROR: prompt is null");
    const char * chars = env->GetStringUTFChars(prompt, nullptr);
    if (chars == nullptr) return env->NewStringUTF("ERROR: prompt unavailable");

    std::string sid_str = "";
    if (session_id != nullptr) {
        const char * sid_chars = env->GetStringUTFChars(session_id, nullptr);
        if (sid_chars != nullptr) {
            sid_str = sid_chars;
            env->ReleaseStringUTFChars(session_id, sid_chars);
        }
    }

    jclass cb_class = token_callback != nullptr ? env->GetObjectClass(token_callback) : nullptr;
    jmethodID on_token_mid = cb_class != nullptr ? env->GetMethodID(cb_class, "onToken", "(Ljava/lang/String;)V") : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();
    jmethodID on_ttft_mid = cb_class != nullptr ? env->GetMethodID(cb_class, "onTtft", "(D)V") : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();
    jmethodID on_metrics_mid = cb_class != nullptr ? env->GetMethodID(cb_class, "onMetrics", "(IIDDDDDI)V") : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();
    jmethodID on_prefix_metrics_mid = cb_class != nullptr ? env->GetMethodID(cb_class, "onPrefixCacheMetrics", "(II)V") : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();

    std::string raw_text;
    std::string status_or_err;
    std::string pending_utf8;

    {
        std::lock_guard<std::mutex> lock(g_model_mutex);
        g_cancel_talk_generation.store(false);
        if (g_model == nullptr || g_context == nullptr) {
            if (cb_class != nullptr) env->DeleteLocalRef(cb_class);
            env->ReleaseStringUTFChars(prompt, chars);
            return env->NewStringUTF("ERROR: model is not loaded");
        }
        status_or_err = generate_sampling_agent_locked(
            chars, temperature, top_k, top_p, min_p, typical_p, repetition_penalty, penalty_last_n, seed,
            enable_thinking == JNI_TRUE, static_cast<int32_t>(thinking_budget),
            sid_str, enable_prefix_cache == JNI_TRUE,
            [&](const char * piece, int32_t len) {
                if (token_callback != nullptr && on_token_mid != nullptr && len > 0) {
                    pending_utf8.append(piece, static_cast<size_t>(len));
                    size_t consumed = 0;
                    std::vector<jchar> utf16;
                    append_valid_utf8_to_utf16(pending_utf8, consumed, utf16, false);
                    if (consumed > 0) {
                        pending_utf8.erase(0, consumed);
                        jstring jpiece = env->NewString(utf16.empty() ? nullptr : utf16.data(), static_cast<jsize>(utf16.size()));
                        if (jpiece != nullptr) {
                            env->CallVoidMethod(token_callback, on_token_mid, jpiece);
                            env->DeleteLocalRef(jpiece);
                            if (env->ExceptionCheck()) {
                                env->ExceptionClear();
                            }
                        }
                    }
                }
            },
            &raw_text,
            [&](double ttft_ms) {
                if (token_callback != nullptr && on_ttft_mid != nullptr) {
                    env->CallVoidMethod(token_callback, on_ttft_mid, static_cast<jdouble>(ttft_ms));
                    if (env->ExceptionCheck()) {
                        env->ExceptionClear();
                    }
                }
            },
            [&](int32_t prompt_tokens, int32_t gen_tokens, double prompt_time_ms, double ttft_ms, double gen_time_ms, double total_time_ms, double speed, int32_t threads) {
                if (token_callback != nullptr && on_metrics_mid != nullptr) {
                    env->CallVoidMethod(token_callback, on_metrics_mid,
                        static_cast<jint>(prompt_tokens),
                        static_cast<jint>(gen_tokens),
                        static_cast<jdouble>(prompt_time_ms),
                        static_cast<jdouble>(ttft_ms),
                        static_cast<jdouble>(gen_time_ms),
                        static_cast<jdouble>(total_time_ms),
                        static_cast<jdouble>(speed),
                        static_cast<jint>(threads));
                    if (env->ExceptionCheck()) {
                        env->ExceptionClear();
                    }
                }
            },
            [&](int32_t cached_tokens, int32_t new_tokens) {
                if (token_callback != nullptr && on_prefix_metrics_mid != nullptr) {
                    env->CallVoidMethod(token_callback, on_prefix_metrics_mid, static_cast<jint>(cached_tokens), static_cast<jint>(new_tokens));
                    if (env->ExceptionCheck()) {
                        env->ExceptionClear();
                    }
                }
            },
            &g_cancel_talk_generation
        );

        if (!g_cancel_talk_generation.load() && !pending_utf8.empty()) {
            size_t consumed = 0;
            std::vector<jchar> utf16;
            append_valid_utf8_to_utf16(pending_utf8, consumed, utf16, true);
            if (!utf16.empty() && token_callback != nullptr && on_token_mid != nullptr) {
                jstring jpiece = env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
                if (jpiece != nullptr) {
                    env->CallVoidMethod(token_callback, on_token_mid, jpiece);
                    env->DeleteLocalRef(jpiece);
                    if (env->ExceptionCheck()) {
                        env->ExceptionClear();
                    }
                }
            }
            pending_utf8.clear();
        }
    }
    if (cb_class != nullptr) {
        env->DeleteLocalRef(cb_class);
    }
    env->ReleaseStringUTFChars(prompt, chars);
    if (status_or_err.rfind("ERROR:", 0) == 0) {
        return new_jstring_from_utf8(env, status_or_err);
    }
    return new_jstring_from_utf8(env, raw_text);
}

static jstring invoke_agent_session_jni(
        bool is_init,
        JNIEnv * env, jstring prompt, jfloat temperature, jint top_k, jfloat top_p,
        jfloat min_p, jfloat typical_p, jfloat repetition_penalty, jint penalty_last_n, jlong seed,
        jboolean enable_thinking, jint thinking_budget,
        jstring session_id, jboolean enable_diagnostics, jobject token_callback) {
    if (prompt == nullptr) return env->NewStringUTF("ERROR: prompt is null");
    const char * chars = env->GetStringUTFChars(prompt, nullptr);
    if (chars == nullptr) return env->NewStringUTF("ERROR: prompt unavailable");

    std::string sid_str = "";
    if (session_id != nullptr) {
        const char * sid_chars = env->GetStringUTFChars(session_id, nullptr);
        if (sid_chars != nullptr) {
            sid_str = sid_chars;
            env->ReleaseStringUTFChars(session_id, sid_chars);
        }
    }

    jclass cb_class = token_callback != nullptr ? env->GetObjectClass(token_callback) : nullptr;
    jmethodID on_token_mid = cb_class != nullptr ? env->GetMethodID(cb_class, "onToken", "(Ljava/lang/String;)V") : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();
    jmethodID on_ttft_mid = cb_class != nullptr ? env->GetMethodID(cb_class, "onTtft", "(D)V") : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();
    jmethodID on_metrics_mid = cb_class != nullptr ? env->GetMethodID(cb_class, "onMetrics", "(IIDDDDDI)V") : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();
    jmethodID on_prefix_metrics_mid = cb_class != nullptr ? env->GetMethodID(cb_class, "onPrefixCacheMetrics", "(II)V") : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();
    jmethodID on_diagnostics_mid = cb_class != nullptr ? env->GetMethodID(cb_class, "onDiagnostics", "(Ljava/lang/String;)V") : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();

    std::string status_or_err;
    std::string raw_text;
    std::string pending_utf8;

    g_cancel_talk_generation.store(false);

    {
        std::lock_guard<std::mutex> lock(g_model_mutex);
        status_or_err = generate_sampling_agent_session_locked(
            is_init,
            chars,
            temperature,
            top_k,
            top_p,
            min_p,
            typical_p,
            repetition_penalty,
            penalty_last_n,
            seed,
            enable_thinking,
            thinking_budget,
            sid_str,
            [&](const char * piece, int32_t len) {
                if (token_callback == nullptr || on_token_mid == nullptr || piece == nullptr || len <= 0) return;
                pending_utf8.append(piece, static_cast<size_t>(len));
                size_t consumed = 0;
                std::vector<jchar> utf16;
                append_valid_utf8_to_utf16(pending_utf8, consumed, utf16, false);
                if (!utf16.empty()) {
                    jstring jpiece = env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
                    if (jpiece != nullptr) {
                        env->CallVoidMethod(token_callback, on_token_mid, jpiece);
                        env->DeleteLocalRef(jpiece);
                        if (env->ExceptionCheck()) env->ExceptionClear();
                    }
                }
                if (consumed > 0) pending_utf8.erase(0, consumed);
            },
            &raw_text,
            [&](double ttft_ms) {
                if (token_callback != nullptr && on_ttft_mid != nullptr) {
                    env->CallVoidMethod(token_callback, on_ttft_mid, static_cast<jdouble>(ttft_ms));
                    if (env->ExceptionCheck()) env->ExceptionClear();
                }
            },
            [&](int32_t prompt_tokens, int32_t gen_tokens, double prompt_time_ms, double ttft_ms, double gen_time_ms, double total_time_ms, double speed, int32_t threads) {
                if (token_callback != nullptr && on_metrics_mid != nullptr) {
                    env->CallVoidMethod(token_callback, on_metrics_mid,
                        static_cast<jint>(prompt_tokens),
                        static_cast<jint>(gen_tokens),
                        static_cast<jdouble>(prompt_time_ms),
                        static_cast<jdouble>(ttft_ms),
                        static_cast<jdouble>(gen_time_ms),
                        static_cast<jdouble>(total_time_ms),
                        static_cast<jdouble>(speed),
                        static_cast<jint>(threads));
                    if (env->ExceptionCheck()) env->ExceptionClear();
                }
            },
            [&](int32_t cached_tokens, int32_t new_tokens) {
                if (token_callback != nullptr && on_prefix_metrics_mid != nullptr) {
                    env->CallVoidMethod(token_callback, on_prefix_metrics_mid, static_cast<jint>(cached_tokens), static_cast<jint>(new_tokens));
                    if (env->ExceptionCheck()) env->ExceptionClear();
                }
            },
            &g_cancel_talk_generation,
            enable_diagnostics != JNI_FALSE,
            [&](const std::string & diag_json) {
                if (token_callback != nullptr && on_diagnostics_mid != nullptr && !diag_json.empty()) {
                    jstring jdiag = new_jstring_from_utf8(env, diag_json);
                    if (jdiag != nullptr) {
                        env->CallVoidMethod(token_callback, on_diagnostics_mid, jdiag);
                        env->DeleteLocalRef(jdiag);
                        if (env->ExceptionCheck()) env->ExceptionClear();
                    }
                }
            }
        );

        if (!g_cancel_talk_generation.load() && !pending_utf8.empty()) {
            size_t consumed = 0;
            std::vector<jchar> utf16;
            append_valid_utf8_to_utf16(pending_utf8, consumed, utf16, true);
            if (!utf16.empty() && token_callback != nullptr && on_token_mid != nullptr) {
                jstring jpiece = env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
                if (jpiece != nullptr) {
                    env->CallVoidMethod(token_callback, on_token_mid, jpiece);
                    env->DeleteLocalRef(jpiece);
                    if (env->ExceptionCheck()) env->ExceptionClear();
                }
            }
            pending_utf8.clear();
        }
    }
    if (cb_class != nullptr) env->DeleteLocalRef(cb_class);
    env->ReleaseStringUTFChars(prompt, chars);
    if (status_or_err.rfind("ERROR:", 0) == 0) {
        return new_jstring_from_utf8(env, status_or_err);
    }
    return new_jstring_from_utf8(env, raw_text);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_MainActivity_nativeClearAgentSession(JNIEnv * /* env */, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    g_agent_session.clear(g_context);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_MainActivity_nativeGenerateStreamAgentSessionInit(
        JNIEnv * env, jobject /* this */, jstring prompt, jfloat temperature, jint top_k, jfloat top_p,
        jfloat min_p, jfloat typical_p, jfloat repetition_penalty, jint penalty_last_n, jlong seed,
        jboolean enable_thinking, jint thinking_budget,
        jstring session_id, jboolean enable_diagnostics, jobject token_callback) {
    return invoke_agent_session_jni(true, env, prompt, temperature, top_k, top_p, min_p, typical_p, repetition_penalty, penalty_last_n, seed, enable_thinking, thinking_budget, session_id, enable_diagnostics, token_callback);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_MainActivity_nativeGenerateStreamAgentSessionAppend(
        JNIEnv * env, jobject /* this */, jstring delta_prompt, jfloat temperature, jint top_k, jfloat top_p,
        jfloat min_p, jfloat typical_p, jfloat repetition_penalty, jint penalty_last_n, jlong seed,
        jboolean enable_thinking, jint thinking_budget,
        jstring session_id, jboolean enable_diagnostics, jobject token_callback) {
    return invoke_agent_session_jni(false, env, delta_prompt, temperature, top_k, top_p, min_p, typical_p, repetition_penalty, penalty_last_n, seed, enable_thinking, thinking_budget, session_id, enable_diagnostics, token_callback);
}

