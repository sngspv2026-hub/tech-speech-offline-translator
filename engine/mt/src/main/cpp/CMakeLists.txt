cmake_minimum_required(VERSION 3.22.1)
project("offline_mt_jni" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    add_compile_options(-O3 -ffast-math -march=armv8.2-a+dotprod+fp16)
else()
    add_compile_options(-O3 -ffast-math)
endif()

add_library(
    offline_mt_jni
    SHARED
    native_mt_bridge.cpp
)

find_library(log-lib log)

target_link_libraries(
    offline_mt_jni
    ${log-lib}
)
