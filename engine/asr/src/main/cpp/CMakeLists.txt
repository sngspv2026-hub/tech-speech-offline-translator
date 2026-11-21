cmake_minimum_required(VERSION 3.22.1)
project("offline_asr_jni" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Optimize for ARMv8.2-A NEON + DotProd on mid-range Cortex-A78 / Cortex-A715 (Redmi Note Pro 5G)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    add_compile_options(-O3 -ffast-math -march=armv8.2-a+dotprod+fp16)
else()
    add_compile_options(-O3 -ffast-math)
endif()

add_library(
    offline_asr_jni
    SHARED
    native_asr_bridge.cpp
)

find_library(log-lib log)

target_link_libraries(
    offline_asr_jni
    ${log-lib}
)
