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
        # GPU backend: OpenCL, the Qualcomm-authored, Adreno-optimized mobile GPU
        # path (arm64 only). The Adreno kernels + kernel-embedding are ON by
        # default in ggml; find_package(OpenCL) resolves against the headers +
        # libOpenCL.so the "Set up OpenCL toolchain" CI step stages into the NDK
        # sysroot. Opt-in at runtime behind the "GPU acceleration" setting.
        set(GGML_OPENCL ON)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
        # No OpenCL driver on the x86_64 emulator — CPU only.
        set(GGML_OPENCL OFF)
    endif()
endif()

# Include llama.cpp subdirectory
add_subdirectory(llama.cpp)

# --- mtmd (multimodal / vision) -----------------------------------------
# Builds libmtmd.so (the image/CLIP encoder + tokenizer). It is NOT built by
# default because llama.cpp only descends into tools/ when LLAMA_BUILD_TOOLS is
# ON, which we intentionally leave off (it would build every CLI/server too).
# Pulling in just tools/mtmd here is the narrow path.
#
# tools/mtmd/CMakeLists.txt sets the target VERSION to ${LLAMA_INSTALL_VERSION},
# which is only defined inside the llama.cpp subdirectory scope; from this
# parent scope it would be empty, and an empty VERSION property is a hard CMake
# error. Define a fallback before pulling mtmd in. mtmd links only ggml + llama
# + pthread (all already linked), so no new external dependency is introduced.
set(LLAMA_INSTALL_VERSION 0.0.0)
add_subdirectory(llama.cpp/tools/mtmd)

add_library(${CMAKE_PROJECT_NAME} SHARED
        native-lib.cpp
        LlamaModel.cpp
        LlamaGenerationSession.cpp)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        llama.cpp/tools/mtmd)   # mtmd.h / mtmd-helper.h

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama-common
        llama
        mtmd
        android
        log)

# --- litert_preload -----------------------------------------------------
# Tiny standalone JNI shim (no llama.cpp dependency) that dlopens a library
# with RTLD_GLOBAL. Used to promote the LiteRT-LM AAR's libLiteRt.so into the
# linker's global group so the hand-bundled libLiteRtTopKOpenClSampler.so can
# resolve its LiteRt* symbols and GPU sampling activates during MTP. Loaded in
# the app's main process by LiteRtEngine (separate from the :llama process's
# ${CMAKE_PROJECT_NAME} lib). See litert_preload.cpp for the full rationale.
add_library(litert_preload SHARED litert_preload.cpp)
target_link_libraries(litert_preload log)
