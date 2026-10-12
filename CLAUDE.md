See [AGENTS.md](AGENTS.md) for the working instructions of this repository (build/verify loop,
commit conventions, architecture map, known traps). Everything there applies to Claude Code too.

## graphify (optional code map)

`graphify-out/` (git-ignored, rebuilt locally in ~20 s) holds a knowledge graph of the app's own
code: Kotlin, the C++ glue in `app/src/main/cpp/*.cpp`, tests. It is a map for orientation, not a
replacement for reading the source.

- Build or refresh it with `graphify update .` (AST only, no LLM, no network). Install with
  `uv tool install graphifyy` (the PyPI name has a double y).
- Good for "what connects A to B" and "what is in this subsystem": `graphify query "<question>"`,
  `graphify path "<A>" "<B>"`, `graphify explain "<symbol>"`, or read `graphify-out/GRAPH_REPORT.md`
  for the god nodes and communities.
- Not a substitute for grep when you need an exact constant, string resource or line: the graph
  models classes, functions and imports, not every value. `ConversationViewModel.kt` is a single
  ~4000-line class, so a query lands on the file rather than on the part you want.
- `.graphifyignore` keeps the pinned llama.cpp submodule and build output out of the graph.
- The skill lives in `.claude/skills/graphify/` (`/graphify`). No hooks are registered: the stock
  ones force a graph query before every grep, which is wrong for this repo.
