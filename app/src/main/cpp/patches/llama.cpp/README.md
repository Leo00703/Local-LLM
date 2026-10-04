# llama.cpp Android patches

`app/src/main/cpp/llama.cpp` is a git submodule pinned to a plain **ggml-org release tag**. The two
changes the app needs on top of it live here as patch files, and CI applies them right after the
checkout (the `Apply Android patches to llama.cpp` step in `build-debug-apk.yml`,
`pull-request-check.yml` and `deploy-internal.yml`). A committed edit inside the submodule would be
reset by `submodules: recursive`, which is why they are not made in place.

| Patch | What it does | Why the app needs it |
|---|---|---|
| `0001-android-accept-fd-N-paths-...` | `ggml_fopen` and `llama-mmap` accept `fd:N` | Models live in a Storage Access Framework folder, so they are opened from a file descriptor rather than a path |
| `0002-vocab-warn-instead-of-abort-...` | A duplicated token in the vocab logs a warning instead of aborting | Some community GGUFs repeat a token and would not load at all |

Both come from [andriydruk/llama.cpp-android](https://github.com/andriydruk/llama.cpp-android)
(commits `187409ea8` and `2a50e266a`), which carries them on top of ggml-org. That fork's third
commit, a vendored `tools/parakeet`, is not carried: the app does not build it.

## Bumping llama.cpp

1. In the submodule: `git fetch ggml-org tag bNNNN && git checkout bNNNN`.
2. `git apply --check` each patch there. If one no longer applies, rebase it and regenerate it with
   `git format-patch`.
3. Check the `skip_audio` line the workflows patch with `sed` (see AGENTS.md) still exists in
   `tools/mtmd/clip.cpp`.
4. Diff the C++ API the native code uses between the old and the new tag (headers only) before
   touching the code.
