# Contributing to Local LLM

Thanks for your interest. This is a personal fork of [LM Playground](https://github.com/andriydruk/LMPlayground)
that is developed in the open, and bug reports, ideas and pull requests are welcome.

By taking part you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Before you start

- **Bug or idea?** Open an [issue](https://github.com/Leo00703/Local-LLM/issues/new/choose) first. A short
  discussion saves you writing code that does not fit.
- **Security problem?** Do not open an issue, see [SECURITY.md](SECURITY.md).
- **Problem in the original Google Play app?** Report it to [upstream](https://github.com/andriydruk/LMPlayground/issues).
- Branch from `main`, which is always the current state of the app.

## Building

You need Android Studio (or JDK 21 + the Android SDK), NDK `27.2.12479018` and CMake `3.31.5`. Clone with
submodules, because llama.cpp is pinned as one:

```bash
git clone --recurse-submodules https://github.com/Leo00703/Local-LLM.git
./gradlew assembleDebug -Pabis=arm64-v8a
```

If you have no local toolchain, push your branch to your fork: the **Build Debug APK** workflow compiles it and
uploads the APK as an artifact (about 15 minutes). That workflow is the compiler check for every change.

## Pull requests

1. Keep a pull request to one change. Unrelated reformatting makes review harder.
2. Say how you verified it: the CI run, the device, the models you tried.
3. For a user-visible change, bump `version.properties` and prepend an entry to
   [`Changelog.kt`](app/src/main/java/com/druk/lmplayground/settings/Changelog.kt) (newest first; the types are
   `NEW`, `IMPROVED`, `FIX`, `DESIGN`).
4. Put new user-facing text in `app/src/main/res/values/strings.xml`. English is the source; other locales fall
   back to it.
5. Commit titles follow `v{major}.{minor}.{patch}: short description`, where the version is the one in
   `version.properties`.

## Where things live

[`AGENTS.md`](AGENTS.md) has the architecture map, the CI details, and a list of traps that already cost a
build (for example `org.json` returning the string `"null"`, and the two call sites of the remote-server UI).
Read it before touching the conversation, remote or native code.

The llama.cpp changes live as patch files in `app/src/main/cpp/patches/llama.cpp/`, never as edits inside the
submodule, because the recursive checkout would reset them.

## Translations

The app is translated into about 28 languages, all partial. Improvements to a locale's `strings.xml` are
welcome as a pull request; keep placeholders such as `%1$s` untouched.
