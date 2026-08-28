cmake_minimum_required(VERSION 3.22.1)

project("localllm_native")

# llama.cpp Phase 3-A: CPU-only, arm64-v8a
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build shared libraries" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "ggml: use OpenMP" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "ggml: use LLAMAFILE" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "ggml: optimize the build for the current system" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "llama: build common utils library" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "llama: build tests" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "llama: build tools" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "llama: build examples" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "llama: build server example" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# llama.cpp is fetched by GitHub Actions at a fixed commit.
add_subdirectory(
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp"
    "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp"
)

add_library(
    localllm_native
    SHARED
    native-lib.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    localllm_native
    llama
    ${log-lib}
)
