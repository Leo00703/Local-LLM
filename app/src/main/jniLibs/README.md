# jniLibs — vendored native libraries

## `arm64-v8a/libLiteRtTopKOpenClSampler.so`

The GPU TopK sampler for the LiteRT-LM runtime. It is **required for GPU
sampling**, which in turn is required for Multi-Token Prediction (MTP) to be a
decode *speedup* instead of a regression: without it, the runtime falls back to
CPU sampling and every MTP step ships K+1 logit vectors GPU->CPU, making MTP
~2.5x slower than plain decode on this device.

The Maven AAR `com.google.ai.edge.litertlm:litertlm-android:0.13.1` does **not**
bundle this sampler (it ships only `libLiteRt.so`, `liblitertlm_jni.so`,
`libLiteRtClGlAccelerator.so`). It lives in the LiteRT-LM repo under
`prebuilt/android_arm64/`, so we vendor it here.

### Provenance
- Source: `google-ai-edge/LiteRT-LM`, tag **v0.13.1** (matches our AAR version),
  Git LFS object `prebuilt/android_arm64/libLiteRtTopKOpenClSampler.so`.
- Upstream oid sha256: `5ca7f34117d8299f88a52f2e6ba4c50219a62bfb870ba08c9f38c46c7122f984`
  (the pristine 1250680-byte file, before the patch below).
- License: Apache-2.0.

### Local patch (one added DT_NEEDED)
The stock sampler has ~166 undefined `LiteRt*` symbols (led by
`LiteRtCreateEnvironment`) but does **not** list `libLiteRt.so` in its
`DT_NEEDED`. It relies on those symbols being in the linker namespace's global
group. In an Android app that loads the runtime via the AAR (System.loadLibrary,
RTLD_LOCAL), `libLiteRt.so` never reaches the global group, so the sampler fails
to load with `cannot locate symbol "LiteRtCreateEnvironment"` and the runtime
silently falls back to CPU sampling (LiteRT-LM issue #2211).

Fix: add `libLiteRt.so` to the sampler's `DT_NEEDED` so the linker resolves the
symbols directly against it, independent of global-group / namespace rules. Done
with LIEF (patchelf `--add-needed` corrupts the GNU hash table and is rejected
by the Android linker, per #2211):

```python
import lief
b = lief.parse("libLiteRtTopKOpenClSampler.so")   # pristine v0.13.1
b.add_library("libLiteRt.so")                       # prepend DT_NEEDED
b.write("libLiteRtTopKOpenClSampler.so")            # patched, committed here
```

Verified after patching: all 298 dynamic symbols preserved, all 166 undefined
`LiteRt*` still `SHN_UNDEF`, the 4 exported `LiteRtTopKOpenClSampler_*` C-API
entry points intact, `DT_GNU_HASH` intact. `libLiteRt.so`'s SONAME is exactly
`libLiteRt.so` (matches the added `DT_NEEDED`) and it defines
`LiteRtCreateEnvironment`.
