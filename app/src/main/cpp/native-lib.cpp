#include <jni.h>
#include <android/log.h>
#include <sys/stat.h>

#include <mutex>
#include <string>

#include "llama.h"

namespace {
constexpr char kLogTag[] = "LLM-PLAYER";

std::mutex g_model_mutex;
llama_model * g_model = nullptr;

void unload_model_locked() {
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_MainActivity_stringFromJNI(
    JNIEnv* env,
    jobject /* this */) {
    std::string hello = "Hello from native C++";
    return env->NewStringUTF(hello.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_MainActivity_nativeLoadModel(
    JNIEnv* env,
    jobject /* this */,
    jstring model_path) {
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

    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "Loading GGUF model: %s (%lld bytes)",
        path,
        static_cast<long long>(file_stat.st_size));

    unload_model_locked();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;

    g_model = llama_model_load_from_file(path, model_params);

    env->ReleaseStringUTFChars(model_path, path);

    if (g_model == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Failed to load GGUF model");
        return env->NewStringUTF("ERROR: llama_model_load_from_file failed");
    }

    __android_log_print(ANDROID_LOG_INFO, kLogTag, "GGUF model loaded successfully");
    return env->NewStringUTF("SUCCESS: GGUF model loaded");
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_MainActivity_nativeUnloadModel(
    JNIEnv* /* env */,
    jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    unload_model_locked();
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "GGUF model unloaded");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_MainActivity_nativeIsModelLoaded(
    JNIEnv* /* env */,
    jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_model_mutex);
    return g_model != nullptr ? JNI_TRUE : JNI_FALSE;
}
