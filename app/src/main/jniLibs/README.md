# jniLibs — vendored native libraries

## `arm64-v8a/libLiteRtTopKOpenClSampler.so`

The GPU TopK sampler for the LiteRT-LM runtime. It is **required for GPU
sampling**, which in turn is required for Multi-Token Prediction (MTP) to be a
decode *speedup* instead of a regression: without it, the runtime falls back to
CPU sampling and every MTP step ships K+1 logit vectors GPU->CPU, making MTP
~2.5x slower than plain decode on this device.

The Maven AAR `com.google.ai.edge.litertlm:litertlm-android` does **not** bundle
this sampler (it ships only `liblitertlm_jni.so`). The runtime looks for it by
name (`libLiteRtTopKOpenClSampler.so`) and loads it from the app's native
library directory, so we vendor it here. It lives in the LiteRT-LM repo under
`prebuilt/android_arm64/`.

**It must be refreshed together with the AAR version.** The sampler talks to
the runtime through a C API whose shape changes between releases (0.17 added
`CanHandleInput`, `HandlesInput` and `SetInferenceFuncAndInputTensors`), and a
stale one is not rejected loudly: the runtime just falls back to CPU sampling.
`LiteRtSamplerLibraryTest` fails when the version recorded below no longer
matches the one in `app/build.gradle.kts`.

### Provenance
- Source: `google-ai-edge/LiteRT-LM`, tag **v0.17.1** (matches our AAR version),
  Git LFS object `prebuilt/android_arm64/libLiteRtTopKOpenClSampler.so`.
- sha256: `4993295bbf6ae0bb0f8fd1c5621595dd0e00876231aa348b11dc624146000a0a`,
  11,794,256 bytes, committed **unmodified**.
- License: Apache-2.0.

### What changed from 0.13.1 (why there is no local patch any more)
Up to 0.16 the runtime was split into `liblitertlm_jni.so` and a separate
`libLiteRt.so`, and the 1.2 MB sampler was a thin plugin with ~166 undefined
`LiteRt*` symbols that it expected to find in the global linker group. In an
Android app (System.loadLibrary, RTLD_LOCAL) it never did, so the sampler failed
to load with `cannot locate symbol "LiteRtCreateEnvironment"` (LiteRT-LM issue
#2211). We worked around it by adding `libLiteRt.so` to its `DT_NEEDED` with LIEF.

Since 0.17.0 the runtime is one self-contained `liblitertlm_jni.so`
(`libLiteRt.so` and the GL accelerator are folded into it), and the sampler is
self-contained too: 0 undefined `LiteRt*` symbols, `DT_NEEDED` limited to system
libraries (`libandroid`, `liblog`, `libm`, `libdl`, `libGLESv3`, `libEGL`, `libc`).
Patching it with `libLiteRt.so` would now make it fail to load, since that library
no longer exists. All `PT_LOAD` segments are 16 KB aligned.
