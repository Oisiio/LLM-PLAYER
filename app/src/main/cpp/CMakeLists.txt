cmake_minimum_required(VERSION 3.22.1)

project("localllm_native")

# llama.cpp Phase 3-A: CPU-only, arm64-v8a
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build shared libraries" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "ggml: use OpenMP" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "ggml: use LLAMAFILE" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "ggml: optimize the build for the current system" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "llama: build common utils library" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "llama: build tests" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "llama: build tools" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "llama: build examples" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "llama: build server example" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# llama.cpp is normally fetched by GitHub Actions at a fixed commit.
# Google AI Studio may only have the repository checkout, where the
# third_party/llama.cpp directory is intentionally incomplete. In that case,
# fetch the exact same pinned llama.cpp revision used by CI.
set(LLAMA_CPP_COMMIT "0f3a71be15af836d277c9f918adfafb45732677e")
set(LLAMA_CPP_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp")

if(NOT EXISTS "${LLAMA_CPP_SOURCE_DIR}/ggml/CMakeLists.txt" OR
   NOT EXISTS "${LLAMA_CPP_SOURCE_DIR}/src/CMakeLists.txt")
    set(LLAMA_CPP_FETCH_DIR "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp-source")

    if(NOT EXISTS "${LLAMA_CPP_FETCH_DIR}/ggml/CMakeLists.txt" OR
       NOT EXISTS "${LLAMA_CPP_FETCH_DIR}/src/CMakeLists.txt")
        message(STATUS "llama.cpp source is incomplete; fetching pinned commit ${LLAMA_CPP_COMMIT}")

        file(REMOVE_RECURSE "${LLAMA_CPP_FETCH_DIR}")
        file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}")

        execute_process(
            COMMAND git clone --depth 1 https://github.com/ggml-org/llama.cpp.git "${LLAMA_CPP_FETCH_DIR}"
            RESULT_VARIABLE LLAMA_CLONE_RESULT
            OUTPUT_VARIABLE LLAMA_CLONE_OUTPUT
            ERROR_VARIABLE LLAMA_CLONE_ERROR
        )
        if(NOT LLAMA_CLONE_RESULT EQUAL 0)
            message(FATAL_ERROR
                "Failed to clone llama.cpp.\n"
                "stdout: ${LLAMA_CLONE_OUTPUT}\n"
                "stderr: ${LLAMA_CLONE_ERROR}")
        endif()

        execute_process(
            COMMAND git fetch --depth 1 origin ${LLAMA_CPP_COMMIT}
            WORKING_DIRECTORY "${LLAMA_CPP_FETCH_DIR}"
            RESULT_VARIABLE LLAMA_FETCH_RESULT
            OUTPUT_VARIABLE LLAMA_FETCH_OUTPUT
            ERROR_VARIABLE LLAMA_FETCH_ERROR
        )
        if(NOT LLAMA_FETCH_RESULT EQUAL 0)
            message(FATAL_ERROR
                "Failed to fetch pinned llama.cpp commit ${LLAMA_CPP_COMMIT}.\n"
                "stdout: ${LLAMA_FETCH_OUTPUT}\n"
                "stderr: ${LLAMA_FETCH_ERROR}")
        endif()

        execute_process(
            COMMAND git checkout --detach ${LLAMA_CPP_COMMIT}
            WORKING_DIRECTORY "${LLAMA_CPP_FETCH_DIR}"
            RESULT_VARIABLE LLAMA_CHECKOUT_RESULT
            OUTPUT_VARIABLE LLAMA_CHECKOUT_OUTPUT
            ERROR_VARIABLE LLAMA_CHECKOUT_ERROR
        )
        if(NOT LLAMA_CHECKOUT_RESULT EQUAL 0)
            message(FATAL_ERROR
                "Failed to checkout pinned llama.cpp commit ${LLAMA_CPP_COMMIT}.\n"
                "stdout: ${LLAMA_CHECKOUT_OUTPUT}\n"
                "stderr: ${LLAMA_CHECKOUT_ERROR}")
        endif()
    endif()

    set(LLAMA_CPP_SOURCE_DIR "${LLAMA_CPP_FETCH_DIR}")
else()
    message(STATUS "Using existing llama.cpp source: ${LLAMA_CPP_SOURCE_DIR}")
endif()

add_subdirectory(
    "${LLAMA_CPP_SOURCE_DIR}"
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

target_include_directories(
    localllm_native
    PRIVATE
    "${LLAMA_CPP_SOURCE_DIR}/common"
)

target_link_libraries(
    localllm_native
    llama
    llama-common
    ${log-lib}
)
