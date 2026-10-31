# Security policy

## Supported versions

Only the latest release (and the latest CI build of `main`) receives fixes. Older builds are not patched.

## Reporting a vulnerability

Please **do not open a public issue** for a security problem.

Email **leogalli00703@gmail.com** with:

- what you found and which version of the app it affects,
- steps to reproduce it, or a proof of concept,
- the impact as you see it.

You will get an answer within a few days. Once a fix is released, you are welcome to be credited in the release notes.

## What is in scope

- The app code in this repository (Kotlin and the C++ glue in `app/src/main/cpp`)
- The built-in tools (web search, page fetch, JavaScript sandbox) and how they handle untrusted content
- Handling of remote-server API keys and downloaded model files

Vulnerabilities in [llama.cpp](https://github.com/ggml-org/llama.cpp) itself, or in a model, should be reported to those projects.

## Notes for sideloaded builds

The CI debug and sideload builds are signed with the committed `debug.keystore` (password `android`). That key is public by design: it does not prove who built an APK. Install builds only from this repository's Releases or from Actions runs you triggered yourself.
