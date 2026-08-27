cmake_minimum_required(VERSION 3.22.1)

project("localllm_native")

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
    ${log-lib}
)
