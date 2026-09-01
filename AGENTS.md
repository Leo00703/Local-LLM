# AGENTS.md

Working instructions for AI agents (and humans) contributing to this repository.
Distilled from the README, the recent commit history, and the CI build behavior.

## What this is

**Local LLM** — an Android app (Kotlin, Jetpack Compose) for running LLMs on-device
with bundled llama.cpp, plus chatting with remote OpenAI-compatible servers
(LM Studio, Ollama, llama.cpp's llama-server). Personal fork of
[andriydruk/LMPlayground](https://github.com/andriydruk/LMPlayground) (MIT),
green rebrand, name "Local LLM".

- `origin` = `Leo00703/Local-LLM` (SSH), `upstream` = `andriydruk/LMPlayground`.
- **Not published to the Play Store.** Distribution = GitHub Actions debug
  APKs, sideloaded onto the phone (installs alongside the Play version via
  the `.debug` applicationIdSuffix).
- All current development happens on `feature/remote-tools` (the branch is far
  ahead of `main`; do not assume `main` is current).
- Git identity: `Leonardo Galli <leogalli00703@gmail.com>`.

## The build/verify loop (read this first)

**You cannot build locally on the dev machine**: only JRE 8 + no Android SDK
are installed; the project needs JDK 21 + Android SDK + NDK + CMake. The
GitHub Actions build is the real compiler check. The loop that works:

1. Make the change. Verify what you can without Gradle:
   - XML well-formedness (e.g. Python `xml.etree.ElementTree.parse`).
   - Comment/string-aware bracket balance on changed Kotlin files.
   - `git diff` review.
   - (Optional) standalone `kotlinc` parse check — expect unresolved-reference
     errors without a classpath; only syntax errors matter.
2. Commit (see conventions below), push to `feature/remote-tools`.
3. The `Build Debug APK` workflow runs automatically (~13–17 min). Watch it:
   `gh run list -R Leo00703/Local-LLM --branch feature/remote-tools` and
   `gh run watch -R Leo00703/Local-LLM <run-id> --exit-status`.
4. On failure, read the annotations (no log download needed): the workflow
   surfaces Gradle's "What went wrong" and CMake errors as `::error`
   annotations, queryable via the API.
5. On success, download the `lmplayground-debug-apk` artifact, install
   `app-arm64-v8a-debug.apk` on the phone, and test the behavior live.
6. Fix issues in follow-up commits (hotfix pattern below).

Staged features are marked in commit titles, e.g. `(parity build 1/2)`,
`(Step 2 code)`, `(dev)` — multiple CI builds per feature is normal.

## Commit conventions

- **Title**: `v{major}.{minor}.{patch}: <short description>`, where the
  version is the value in `version.properties` — **bump it in the same commit
  for any user-visible change** (patch increment is the norm for features and
  fixes).
- **Hotfix pattern** (a push broke the build, or a small follow-up to the same
  user-visible change): keep the same version, no bump. Titles look like
  `v1.9.92 fix: sendMessage callback overload takes Contents, not String`, or
  are version-less (`Fix compile error: ...`), or `diag: ...` for temporary
  diagnostic commits.
- **Body**: plain text, sectioned by area (e.g. `Scanner (LocalServerScanner):`),
  `-` bullets, dense technical detail: *cause*, *what changed*, *why*,
  *how verified*. Prefer verifying behavior against upstream/external sources
  (e.g. the llama.cpp server source) before writing code, and say so.
- **Trailer** (every commit, no exceptions):
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
- **In-app changelog** (easy to forget — it's part of the commit):
  prepend a `ChangelogEntry` to `CHANGELOG` in
  `app/src/main/java/com/druk/lmplayground/settings/Changelog.kt` for every
  user-visible version. Newest first. Each change is a
  `Change(ChangeType, "user-facing sentence")` with
  `NEW(✨) / IMPROVED(⚡) / FIX(🐛) / DESIGN(🎨)` — note it is
  `IMPROVED`, not `IMPROVEMENT` (a typo caused a failed CI build). Keep text
  short, plain English, no internal jargon. The file's own KDoc restates these
  rules.
- **fastlane changelog files** (`fastlane/metadata/android/*/changelogs/`,
  named `{major*10000+minor*100+patch}.txt`, e.g. 1.5.0 → `10500.txt`): none
  have been added since 1.7.1 — recent releases rely on `default.txt` fallback
  in `deploy-internal.yml`. Do not add one unless told to.

## CI workflows (`.github/workflows/`)

| Workflow | Trigger | What it does |
|---|---|---|
| `build-debug-apk.yml` | push to non-main (md/docs paths ignored) + manual | Builds debug APKs. The daily verification path. |
| `pull-request-check.yml` | PR | Same native setup + KVM, runs `./gradlew app:mvdApi35Check` (Managed Virtual Device, Pixel API 35). |
| `deploy-internal.yml` | **manual only** — the fork has no Play secrets (auto-runs would burn a build then fail) | Release AAB + fastlane internal upload. `versionCode = BASE*1000 + GITHUB_RUN_NUMBER`. |
| `update-listing.yml` | push to main touching `fastlane/metadata/**` (changelogs excluded) | Play listing sync; also secret-less. |

`build-debug-apk.yml` does, in order: checkout with recursive submodules →
**mtmd fork patch** (below) → Java 21 (Zulu) + Android SDK → install pinned
NDK 27.2.12479018 + CMake 3.31.5 → **OpenCL toolchain** (below) →
`./gradlew assembleDebug` → upload `lmplayground-debug-apk`
(arm64-v8a / x86_64 / universal — install the arm64-v8a one) and
`native-debug-symbols-arm64` (for `ndk-stack` symbolication; `if-no-files-found: warn`).

### The two non-obvious build steps

1. **mtmd fork patch (applied at build time, in BOTH `build-debug-apk.yml` and
   `pull-request-check.yml`)**. llama.cpp is a **pinned submodule**
   (`andriydruk/llama.cpp-android`, branch `android-b9496`, pinned commit
   `42b4d85`), so any committed working-tree edit inside it would be reset by
   `submodules: recursive`. Instead CI does a `sed` replacement in
   `app/src/main/cpp/llama.cpp/tools/mtmd/clip.cpp`:
   `skip_audio = ctx_vision->model.proj_type == PROJECTOR_TYPE_GEMMA3NV;` →
   `skip_audio = true;` (vision-only app; the pinned mtmd aborts on combined
   vision+audio projectors). The step greps before and after and **fails the
   build if the patch doesn't take**. Consequence: if you change the pinned
   llama.cpp commit, you must update the patch step in both workflows to match
   the new source (or drop the step if the pinned version fixed the bug).
   (Commit `bcf5dd8` exists purely to replicate this patch onto the main-branch
   workflows.)
2. **OpenCL toolchain**. GPU backend = OpenCL, arm64 only (replaces Vulkan,
   which crashed on Adreno 830). CI installs the Khronos OpenCL headers into
   the NDK sysroot and builds the Khronos ICD loader (`libOpenCL.so`) for
   arm64 — **link-only**: the app loads the device's own vendor OpenCL driver
   at runtime (`uses-native-library` in the manifest; a bundled `libOpenCL.so`
   would shadow it and find no GPU). A build without this step fails at
   `find_package(OpenCL)` — the two steps must land together.

## Critical build-system facts

- **Pins**: NDK `27.2.12479018`, CMake `3.31.5`, Java 21, AGP 8.13.2,
  Kotlin 2.3.0 / KSP 2.3.0, compose compiler 2.3.0, compileSdk 35, minSdk 30,
  Gradle 8.13. `haze` is pinned at 1.6.10 specifically to stay on compileSdk 35
  (see the comment in `gradle/libs.versions.toml` — bump both together for
  1.7.x).
- **ABI split**: `arm64-v8a` (phones), `x86_64` (emulators), plus universal.
- **versionCode** = `(major*10000 + minor*100 + patch) * 1000 + GITHUB_RUN_NUMBER`
  (local builds use 0) — ~1000 CI builds fit per patch version, which is why
  hotfixes can share a patch number.
- **Native libs**: `BUILD_SHARED_LIBS` + `GGML_BACKEND_DL` +
  `GGML_CPU_ALL_VARIANTS` build one `libggml-cpu-<variant>.so` per ARM feature
  level (NEON, dotprod, i8mm, SVE, …) picked at runtime via dlopen;
  `useLegacyPackaging = true` (extractNativeLibs) is required for the
  `opendir`-based variant lookup. `debugSymbolLevel = SYMBOL_TABLE` so native
  tombstones symbolicate with ndk-stack (CI uploads the symbols).
- **Signing**: prefers `~/.android/keystore.jks` + `STORE_PASSWORD` env vars,
  falls back to the committed `debug.keystore` (password `android`).

## Architecture map (where things live)

```
app/src/main/java/com/druk/
├── llamacpp/        # Local engine glue: GenerationModel.kt, GenerationBackend.kt
│                    # (AIDL to the separate :llama process running bundled llama.cpp)
└── lmplayground/
    ├── conversation/  # ConversationViewModel.kt = all model wiring + capability
    │                  # gating (LiteRT / local llama / remote); MessageFormatter
    │                  # parses <think>…</think>; WhatsNew.kt is an old empty-chat
    │                  # watermark, NOT the release notes
    ├── remote/        # LocalServerScanner (LAN scan + server-type detection),
    │                  # RemoteOpenAiClient (list/details/offload, per-server branches),
    │                  # RemoteOpenAiBackend (SSE stream parser — shared by ALL remote
    │                  # server types), RemoteOpenAiModel
    ├── litert/        # In-process LiteRT-LM engine (Gemma 4)
    ├── tools/         # Built-in tool set: ToolRegistry, WebSearch/Fetch, JavaScript,
    │                  # Calculator, DateTime, HardwareInfo, Location, Memory, …
    ├── models/        # SelectModelDialog.kt (model picker; server header logos),
    │                  # ModelInfoProvider.kt (the built-in model catalog)
    ├── settings/      # Changelog.kt (in-app release notes — the live mechanism),
    │                  # RemoteServerScreen/Fragment (phone) AND the tablet pane in
    │                  # SettingsFragment (two call sites!)
    ├── download/      # OkHttp + WorkManager download engine
    ├── storage/       # SAF-based model storage, migrations,
    │                  # StoragePreferences.kt (the app's SharedPreferences wrapper)
    └── inference/     # LlamaService.kt = the :llama process service (separate
                       # process so a SIGSEGV in llama.cpp can't kill the UI),
                       # GenerationWorker, ProcessUtils, notifications/sound
```

- **Remote servers all flow through the OpenAI-compatible API**
  (`/v1/chat/completions`, `/v1/models`) via `RemoteOpenAiClient` +
  `RemoteOpenAiBackend`; vendor-native endpoints are used only for metadata
  (LM Studio `/api/v0/models`, Ollama `/api/show`, llama.cpp `/v1/models` meta
  + `/props`) and offload. When adding a server type, verify its streaming
  shape against its source before touching the shared SSE parser.
- **Capability gating** (`ConversationViewModel`): `details.capabilities`
  empty = "unknown" = allow (keeps LM Studio / custom servers behaving);
  servers that report capabilities (Ollama, llama.cpp) gate on them.
- **Strings**: `app/src/main/res/values/strings.xml` (English is source of
  truth); ~28 locale dirs are partial and fall back to English.
- `docs/` is the GitHub Pages project website — changes there skip the APK
  build (`paths-ignore`).

## Known traps (learned the hard way)

- **`org.json` `optString()` returns the literal string `"null"`** for explicit
  JSON `null` values (only ABSENT keys read as `""`). llama-server streams
  explicit nulls in role-only and tool-call chunks; the app printed "null"
  before every phrase. Use a `when (opt(key)) { is String -> … }`-style helper
  for streamed delta text (see `RemoteOpenAiBackend.optText`).
- **`ChangeType.IMPROVED`**, not `IMPROVEMENT` (enum typo broke a CI build).
- **Remote-server UI has two call sites** — phone `RemoteServerScreen`
  (`RemoteServerFragment`) and the tablet pane inside `SettingsFragment`.
  New parameters/fields must be threaded through both.
- **Logo clipping in the model-picker server header**
  (`SelectModelDialog.RemoteServerHeader`): square marks (LM Studio,
  llama.cpp) get `RoundedCornerShape(7.dp)`; round marks (Ollama) get
  `CircleShape`. The llama.cpp logo is additionally tinted with
  `onSurface` at runtime (it's a monochrome vector).
- **Server-type detection order in `LocalServerScanner`**: check llama.cpp's
  `owned_by == "llamacpp"` FIRST from the `/v1/models` body — its data-array
  shape otherwise matches LM Studio's body-shape probe. Port fallback is last.
- **VectorDrawables**: Android's PathParser does not handle scientific
  notation in path data (`3.25756e-05` → `0.0000325756`).
- **Version bump + `Changelog.kt` entry + `Co-Authored-By` trailer** are the
  three things easy to forget in the same commit (1.9.105 forgot the
  changelog; it was patched in 1.9.106).

## gh CLI + environment quirks

- `gh`'s default repo resolution can point at **upstream**
  (`andriydruk/LMPlayground`). Always pass
  `-R Leo00703/Local-LLM` for run/artifact queries on this fork.
- The installed `gh` version lacks `run watch --log-failed` and
  `run view --json artifacts`. Use:
  `gh api repos/Leo00703/Local-LLM/actions/runs/<id>/artifacts`.
- Dev machine is Windows + Git Bash: MSYS paths (`/c/tmp/...` = `C:/tmp/...`);
  bash heredocs mangle backslashes — write scripts to a file with the editor
  instead of `python - <<EOF`.
- Only JRE 8 is installed; never attempt `./gradlew` locally — push and let
  CI build.
