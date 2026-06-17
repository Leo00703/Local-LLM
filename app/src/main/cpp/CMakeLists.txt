# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
# 3.31.5 is the newest CMake the Android SDK ships (no 3.31.6 package exists),
# so the minimum is capped there; the patch difference is behaviourally inert.
cmake_minimum_required(VERSION 3.31.5)

# Declares the project name first
project("llamacpp")

# Per-ABI performance flags (must be set before add_subdirectory).
# OpenMP is OFF on both ABIs: it's the upstream-recommended Android path
# (see llama.cpp docs/android.md) and ggml's own pthread threadpool replaces
# it with comparable performance. Disabling OpenMP also produces cleaner
# native crash stacks (no libomp.so frames) and shrinks the APK by ~500 KB
# (no libomp.so packaged).
if(DEFINED ANDROID_ABI)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP OFF)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    endif()
endif()

# Include llama.cpp subdirectory
add_subdirectory(llama.cpp)

add_library(${CMAKE_PROJECT_NAME} SHARED
        native-lib.cpp
        LlamaModel.cpp
        LlamaGenerationSession.cpp)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama-common
        llama
        android
        log)
