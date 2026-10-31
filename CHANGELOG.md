# Changelog

Generated from the in-app changelog (`Changelog.kt`) by `.github/scripts/changelog.py`; do not edit by hand.

## 1.9.139 · Faster Qwen in long chats

- ⚡ Qwen 3 and Qwen 3.5 now answer faster in long conversations: the app keeps what it has already read instead of processing the whole chat again on every message.

## 1.9.138 · Stability fixes

- 🐛 Fixed a crash that could happen when you switched model or unloaded it while a message was still being prepared, especially with images on a slow phone.
- 🐛 The keyboard now closes when you open the model picker, so it no longer hides half of the list.
- 🐛 A new chat now always shows up in the chat list instead of appearing just above the visible part.

## 1.9.137 · LFM3

- ✨ LFM3 1.7B from Liquid AI is in the model list: a 1.1 GB reasoning model with tool calling, a thinking switch and a very long context.
- ⚡ The AI engine is updated to the newest version, which also brings support for other new model families.

## 1.9.136 · Spark-X2.6

- ✨ Spark-X2.6 4B and 1.7B from XHToken are in the model list: the newer versions of Spark-X2.5, reasoning models that can use tools and have a thinking switch. They run on the current AI engine, no update needed.

## 1.9.135 · MTP for Gemma 4

- ✨ Speculative Decoding (MTP) now also works for Gemma 4 on the on-device engine (E2B, E4B and 12B). Turning it on downloads a small helper file the first time, about 93 to 242 MB. It is off by default and experimental: it only applies to replies without tools or images, and it may not be faster on every phone.

## 1.9.134 · MTP for Qwen 3.5

- ✨ Speculative Decoding (MTP) is now in the chat settings for Qwen 3.5 models that include an MTP head. It is off by default and experimental: it only applies to replies without tools or images, and it may not be faster on every phone.
- ⚡ Speculative decoding now also works with the normal creativity setting and with thinking on, not only at temperature 0.

## 1.9.133 · Newer AI engine

- ⚡ The on-device AI engine (llama.cpp) is updated to a newer version, adding support for more recent models.

## 1.9.132 · Cleaner server model names

- ⚡ Server model names are tidier: sizes and tags such as 24B, A2B, UD and MTP are upper-cased, brands like LFM, GPT, Gemma, Qwen and Spark are capitalized, and names written with spaces are handled too, for example "LFM 2.5 24B A2B UD (Q4_K_XL)".

## 1.9.131 · Longer tool chains

- ⚡ A model can now make up to 30 tool calls in one reply (on-device models were stopped after 5), so longer research with search and page reads is no longer cut short.

## 1.9.130 · Measure smoothness, faster first start

- ✨ Settings > Advanced has a "UI smoothness test": it streams a long formatted reply through the chat at the pace of a fast model and reports frame times, so two builds or settings can be compared by number.
- ⚡ The lighter "sideload" build now ships a startup profile for the app's own code, so it starts and first scrolls with less stutter after being installed.

## 1.9.129 · Quicker model list, steadier downloads and history

- ⚡ The model list, the model picker and the storage screen read your model folder in one query instead of one per file, so they open faster.
- ✨ Settings > Advanced has a new "Download models on Wi-Fi only" switch, so big downloads never use mobile data.
- 🐛 Downloads stop retrying a link that answers with an error such as 404, stop when the storage is full instead of retrying forever, and a download that ends short is resumed instead of being kept as a corrupt model.
- ⚡ A reopened chat keeps its tool steps and the earlier versions of regenerated answers.
- 🐛 Gemma 4 (LiteRT) models now remember the conversation after a regenerate, an edit, reopening a chat, a settings change, or switching a tool on or off.
- 🐛 A chat that cannot be read (for example because of a very large attached file) now shows a message instead of crashing the app, and the HTML source preview of an attachment is limited to 200,000 characters so it cannot cause that.
- ⚡ The PDF preview opens the document once and keeps the last few pages, so scrolling a long PDF is faster.
- 🐛 Messages sent within the same millisecond keep their order.

## 1.9.128 · Safer tools, tidier network

- 🐛 A tool you have switched off can no longer be run by the model, for example after it reads a page that tries to tell it to.
- ⚡ Fetching a web page refuses more private network ranges (local IPv6, VPN overlays such as Tailscale), works for addresses like httpbin.org, and reports an error page instead of presenting it as the page.
- 🐛 Web search now says when it is being rate-limited instead of reporting "no results", and tool errors with line breaks reach the model intact.
- ⚡ Saved memory notes are marked as data, never instructions, when they are added to a chat.
- ⚡ Your server API keys and settings are no longer included in Android cloud backups or device transfers.
- 🐛 After a failed request to a server, retrying no longer breaks servers that need user and assistant turns to alternate.
- ⚡ Server checks share one network connection pool, the haptic ticks do less work per token, and the networking library is updated.

## 1.9.127 · Smoother streaming

- ⚡ Replies stream more smoothly: the screen is updated at most about 30 times a second instead of once per token, and a reply is parsed once per update instead of repeatedly.
- ⚡ The collapsed reasoning preview only formats the few lines it shows, so long reasoning no longer slows the stream down.
- ⚡ The chat screen redraws far less while a reply is arriving, and the animated dots on the thinking card no longer redraw the whole card every frame.
- 🐛 Pasting a very large text into the message box can no longer crash the app when it goes to the background; only the first 20,000 characters of an unsent draft are kept.

## 1.9.126 · Faster prompt processing

- ⚡ Reading long prompts (documents, web pages, tool results) now uses all of your phone's fast CPU cores instead of four, so the first word of the answer arrives sooner.
- ⚡ The engine keeps its worker threads alive between tokens instead of creating them again for every token, which trims overhead, mostly on fast small models.

## 1.9.125 · Steadier chats and engine

- 🐛 Deleting the chat you have open now also clears it from the model's memory, so the next new chat no longer starts with the deleted conversation still in mind.
- 🐛 A chat can no longer get stuck on "generating" with an empty bubble after the engine failed to rebuild its session, and a context size that does not fit in memory now keeps the previous one and tells you.
- 🐛 Tool calls on hybrid models (like Qwen 3.5) no longer show the call to the model twice.
- 🐛 A model that cannot start a chat for lack of memory now says so instead of crashing the engine.
- 🐛 Leaving a model loaded for many hours no longer risks the app being stopped by Android 15's time limit for background work.
- ⚡ The suggested context window is now computed from each model's real attention size and starts at up to 16k tokens instead of 32k, which uses much less memory and loads faster. You can still raise it.
- ⚡ The app no longer waits for the engine to warm up at start-up, and recovery after an engine crash mid-reply is immediate.

## 1.9.124 · Finding out why the engine stops

- 🐛 When the engine stops while a model is loading, the message now says how it ended (a crash or the system freeing memory) and how far it got, instead of showing nothing.

## 1.9.123 · Why a model failed to load

- ⚡ When a model cannot be loaded, the message now also shows what the engine reported (for example an unsupported operation or not enough memory), even if the engine had to stop itself, instead of only saying that the file may be corrupt.

## 1.9.122 · Newer Gemma 4 engine

- ⚡ The engine behind Gemma 4 E2B and E4B (Google LiteRT-LM) is updated to its latest release, 0.17.1. It uses less memory for long contexts and fixes a bug with whole-number values in tool calls. The GPU sampler it relies on is updated with it, so the faster speculative decoding keeps working. It takes about 10 MB more space on the phone.

## 1.9.121 · Newest AI engine

- ⚡ The on-device AI engine (llama.cpp) is updated to its newest release, 193 builds newer than before. It brings fixes for GPU acceleration on Adreno phones, template and tool-calling fixes, and support for newer models. Nothing changes in how you use it.

## 1.9.120 · Spark-X2.5 1.7B

- ✨ Spark-X2.5 1.7B from XHToken is in the model list: the smaller sibling of the 4B, a 1.1 GB reasoning model that can use tools and has a thinking switch.

## 1.9.119 · Long research no longer stops

- 🐛 A model on a remote server no longer stops in the middle of a long research. Replies ended after 5 rounds of tools with nothing written; server models now get 25 rounds, and when that budget runs out the model writes its answer from what it found instead of leaving an empty reply.
- ⚡ When a server reply ends early, the chat now says why under the reply: the server hit its context or output limit, the connection closed before the end, or the server sent nothing.
- ⚡ An on-device model that reaches its limit of 5 tool rounds now tells you, instead of just stopping.

## 1.9.118 · Several remote servers

- ✨ You can now save several remote servers (LM Studio, Ollama, llama.cpp), each with its own name, address and optional API key. A new Save button stores the one you are editing; tap a saved server to change it, or use the bin to remove it. The server you had before is kept.
- ⚡ The model picker checks your saved servers when it opens and lists only the ones that are online, each with its own models. The server you used last starts open.
- ⚡ A server you type in by hand now gets its logo too, and a missing http:// or a trailing /v1 in the address is fixed for you.

## 1.9.117 · Server model names and tools

- ⚡ Models from a llama.cpp or Ollama server now show clean names in the model list, like "Qwen 3 4B (Q4_K_M)", instead of file names with ".gguf" and quantization pieces. Two quantizations of one model stay apart because the quantization is kept in the name.
- ⚡ A model on a remote server now starts with all its tools on, and the choice you make for each model is remembered. The Location tool only starts on if its permission is already granted.
- ✨ An Enable all switch at the top of the tools list, in the chat and in Settings → Tools, turns every tool on or off at once.
- 🐛 The tool switches now show their real state right after loading a server model or a Gemma 4 model; before, they showed everything off until the first message.

## 1.9.116 · Qwen 3.5 on the GPU

- ⚡ Qwen 3.5 (0.8B, 2B and 4B) now comes in a higher-quality format, Q4_K_M, that the GPU can run directly; the old Q3_K_M format had no GPU support and fell back to the processor. The new files are about 12-17% bigger (579 MB, 1.40 GB and 3.01 GB). Download the new one from the model list; once it is on your phone, the old file is deleted automatically to give the space back, and the old one keeps working until then.

## 1.9.115 · LFM2.5-VL vision models

- ✨ Three small vision models from Liquid AI: LFM2.5-VL 450M (the smallest, 229 MB), 1.6B and 3B (which also reads text in photos, in 16 languages). They are fast even on a phone without a strong GPU, and all three can use tools. Their image add-on (from 102 MB to 583 MB) is offered the first time you attach a picture.

## 1.9.114 · Spark-X2.5 and SmolLM3 tools

- ✨ Spark-X2.5 4B from XHToken is in the model list: a reasoning model that can use tools and has a thinking switch that works both ways. It needs the newer AI engine from the last update.
- 🐛 SmolLM3 3B can now use tools. Before, the tool calls it wrote appeared in the chat as raw text instead of running, because its built-in chat template never described them. The app now loads it with a corrected template.

## 1.9.113 · Pictures without copying files

- ✨ Qwen 3.5, Gemma 3, Gemma 4 and Ministral can now look at pictures without you finding and copying a file by hand. Tap the attach button and, if the model's image add-on isn't on your phone yet, the app asks whether to download it (from about 200 MB to 850 MB depending on the model). It downloads in the background and tells you when it's ready.
- ⚡ Deleting a model now also removes the image add-on that was downloaded for it, unless another model you kept still uses the same one (both Gemma 4 E2B versions share it).

## 1.9.112 · Tools now work with photos

- 🐛 Tools now work together with a photo. When tools were on and you attached a picture, the model was told about the tools but its tool calls were not recognised, so a request to use one could show up as raw text instead of running. A photo question can now use the calculator, web search and the other tools like any other message.

## 1.9.111 · Chat switching and image detail fixes

- 🐛 Opening a saved chat while a reply is still being written now stops that reply first. Before, the old reply could keep streaming into the chat you had just opened and be saved there.
- 🐛 The Image detail slider no longer offers its lowest position (64), which is below the minimum Gemma 4's image add-on can work with and could make it fail to load. The lowest setting is now 96, and a lower value saved earlier is raised to it automatically.

## 1.9.110 · Updated AI engine

- ⚡ Updated the on-device AI engine (llama.cpp) from a build of June 2026 to one of late September 2026, about 1,700 upstream changes. It brings newer model support, many speed and memory improvements, and fixes.
- 🐛 Some Gemma 4 model files downloaded in mid-July (Google briefly published them with duplicated entries in the vocabulary) refused to load and crashed the app. They now load.

## 1.9.109 · New models, honest thinking switch

- ✨ Six new models are ready to download: LFM2.5 2.6B and LFM2.5 1.2B Instruct from Liquid AI, Granite 4.2 3B from IBM, MiniCPM5 1B and 2B from OpenBMB, and SmolLM3 3B from Hugging Face. SmolLM3 chats and thinks, but tool use is not turned on for it yet.
- ⚡ The thinking switch now follows how each model really behaves instead of what its chat template claims. Models that never think no longer show a switch that does nothing, and models that always reason (like DeepSeek R1 Distill 1.5B and LFM2.5 2.6B) no longer offer an "off" they would ignore. Their thinking limit in the parameters card still works.
- 🐛 Llama 3.2 1B, Phi-4 mini and Mistral 7B no longer show a tools badge in the model list. Their chat templates cannot call tools, so the badge promised something the app could never do.

## 1.9.108 · Emoji and vision stability

- 🐛 Fixed a crash that could close the app while a reply was streaming if an emoji or other multi-byte character was split across two chunks of text. The half-written character is now held back until the rest arrives.
- 🐛 Attaching a photo again with the same image-detail setting no longer reloads the vision add-on, which avoids a rare crash while a chat is using it. Changing the image-detail setting still reloads it as before.

## 1.9.107 · Crash and blank-reply fixes

- 🐛 Fixed a crash that could close the app when a message was rejected before the model started replying: the engine was asked to run an empty request. The turn now ends cleanly instead.
- 🐛 Fixed blank or repeated replies in long chats. After a chat-template hiccup the app re-sent the whole conversation on top of the model's old memory, which duplicated the history and could leave no room to answer. The old memory is now cleared first.
- 🐛 Turning on tools with a model whose chat template produces an unusable tool-call rule (some custom models) no longer crashes the app. It now answers normally without that rule.
- 🐛 Loading, unloading or switching a model at the same moment a chat is being started could crash the app. Starting a chat and freeing the model now wait for each other.
- 🐛 A damaged saved prompt cache is now discarded cleanly instead of crashing the next reply.
- 🐛 Attaching a photo at the same moment a message is sent can no longer scramble the image data.

## 1.9.106 · llama.cpp server fixes

- 🐛 Chatting with a llama.cpp server no longer prints the word "null" at the start of the model's replies and after tool calls: the server's empty placeholder chunks were being read as text and are now ignored.
- 🎨 The llama.cpp icon in the model picker now shows the full logo in its native square shape instead of being cropped to a circle.

## 1.9.105 · llama.cpp remote server

- ✨ Chat with llama.cpp (llama-server) models running on your PC or another device: the network scan now finds llama.cpp servers the same way it finds LM Studio and Ollama, and you can chat with them like any other remote model (streaming replies, tools, thinking, and image input for vision models).
- ✨ A llama.cpp server's model list now shows each model's real details: quantization, context size, and parameter count (for example 7.6B), read from the server itself.
- ✨ New optional API key field in the remote server settings, only needed if you started the server with an API key (--api-key).
- ⚡ For remote models, the Tools tab now appears only when the server reports that the model can use tools, so a model without tool support no longer fails mid-conversation.

## 1.9.104 · Better Gemma 4 downloads, storage fix

- ⚡ The built-in Gemma 4 models (E2B, E4B, 12B) now download Google's official QAT builds, which keep near-full quality at the same size. Versions you already downloaded keep working; the QAT builds are offered for new downloads.
- 🐛 Fixed a case where moving your models to a new folder could make one vanish from the list: if the system storage appended a file extension during the copy, the model is now renamed back so it stays visible.

## 1.9.103 · More stability and security hardening

- 🐛 Gemma 4 (LiteRT) chats no longer lose their context after the first reply. Auto-naming a new chat was quietly rebuilding the model's single shared conversation, which erased the running history and could crash the app if you sent the next message at the same moment. LiteRT chats now keep their full context (they keep the placeholder title instead of an auto-generated one).
- 🐛 The calculator tool can no longer crash the app on a pathological expression (thousands of nested parentheses); it returns a clean error instead.
- 🐛 Switching or reloading a remote or Gemma 4 model is now serialized, so changing two settings in quick succession during a load can no longer leave two models in memory at once and trigger a low-memory kill.
- ⚡ Web page fetching now refuses to reach the phone itself or the local network, so a tool-driven model, or an instruction hidden inside a fetched page, cannot be steered at your router or private services.
- ⚡ Lighter response rendering: the text-cleanup patterns are now built once instead of on every streamed token.

## 1.9.102 · Stability, security and cleanup

- 🐛 Hardened file and web-page reading: a malformed or hostile document (a zip-bomb Office file, an oversized spreadsheet, or a huge page a tool fetches) can no longer run the app out of memory.
- 🐛 Tool results are now always valid JSON, so a calculator error or a search query containing quotes no longer confuses the model.
- 🐛 Remote server: listing models and warm-up now time out instead of freezing the loading screen if a server accepts the connection but never replies, and the server's own error message is shown instead of a bare HTTP code.
- ⚡ Internal cleanup: removed leftover template code and unused resources to slim the app, with no change to how it works.

## 1.9.101 · Gemma 4: image input

- ✨ Gemma 4 (LiteRT) E2B and E4B can now see images. Attach a photo or take one and ask about it, the same as the other vision models. Vision turns on automatically when there is enough free memory for the image encoder; on a tight-memory device the model still loads for text and the attach button stays hidden.

## 1.9.100 · Fix Gemma 4 E4B out-of-memory crash

- 🐛 Loading a large model that does not fit in the phone's free memory (like Gemma 4 E4B with speculative decoding on the GPU) no longer crashes the app. It now checks free memory first and, when there is not enough, shows a clear message instead of being killed: close other apps, use the smaller E2B model, or turn off Speculative Decoding. A lighter setup (E4B on CPU, or on GPU with speculative decoding off) is allowed when it fits.

## 1.9.99 · Open folders, model name in settings

- ⚡ Tap a folder to open it and see its chats. A new chat you start while inside a folder is saved into that folder, and its empty background shows the folder name so you know where it will land. Use the back arrow to leave the folder.
- ⚡ The parameters card now shows the full name of the loaded model at the top, for all models, so a long name that gets cut off in the title bar is always readable.

## 1.9.98 · Fix Gemma 4 crash during tool use

- 🐛 Fixed a crash that could occur when Gemma 4 (LiteRT) used a tool, especially with speculative decoding on the GPU. The engine's stop signal was being sent after a reply had already finished, which could race with the tool follow-up turn and crash the app. It now fires only when you actually stop generation.

## 1.9.97 · Gemma 4: tool calling

- ✨ Gemma 4 (LiteRT) models can now call tools. Turn on tools in Settings and Gemma 4 uses them (calculator, date and time, web search, and so on) with the same permission prompts and reasoning card as the other models. Changing the enabled tools partway through a chat restarts that conversation.

## 1.9.96 · Gemma 4: thinking and system prompt

- ✨ Gemma 4 (LiteRT) models now support thinking. Turn on the Thinking toggle and the model streams its reasoning live in the thinking card before the answer, the same as the other models.
- ⚡ The system prompt now reaches Gemma 4 (LiteRT) through the model's own chat template, instead of being pasted onto your first message. Set a persona or instructions and Gemma 4 follows them across the whole chat.

## 1.9.95 · Fix Gemma 4 streaming

- 🐛 Gemma 4 (LiteRT) replies now stream token by token as they are generated, instead of appearing all at once at the end. The real cause: a token-count read that ran during decoding was blocking on the engine, holding every token back until generation finished. That read now happens only before and after decoding, so tokens flow through live.

## 1.9.94 · Live streaming for Gemma 4

- ⚡ Gemma 4 (LiteRT) replies now stream token by token as they are produced, instead of appearing all at once when generation finishes. The per-token callback runs on a dedicated looper thread so each token is delivered live.

## 1.9.93 · Restore Gemma 4 generation

- 🐛 Restored Gemma 4 (LiteRT) generation. A streaming change in the previous build stopped it from producing any output. The decode now runs on a background thread with an unlimited buffer, so the reply drains progressively rather than blocking the display.

## 1.9.92 · Real token-by-token streaming for Gemma 4

- 🐛 Gemma 4 (LiteRT) replies now truly stream token by token as they are generated. The earlier fix stopped the reply from being cut off, but the text still appeared all at once because the streaming API delivered it in one burst at the end. This switches to LiteRT's per-token callback, run on a dedicated thread, so the answer types out live.

## 1.9.91 · Fix Gemma 4 streaming and truncation

- 🐛 Gemma 4 (LiteRT) replies now stream token by token again, and no longer get cut off partway through. The engine's output was being buffered and dropped when the on-screen display could not keep up; it now drains each token as it is produced.
- ⚡ The KV cache precision selector is hidden for Gemma 4 (LiteRT) models, since the LiteRT engine keeps its KV cache at a fixed precision and does not support quantizing it.

## 1.9.90 · Adjustable Gemma 4 context (up to 32K)

- ⚡ The context window for Gemma 4 (LiteRT) models is now adjustable up to 32K tokens with the context slider in the generation settings, and your context, temperature, and sampling choices are remembered per model. A larger context uses more memory (LiteRT keeps its KV cache at full precision), so the slider lets you pick the balance. Changing the context reloads the model.

## 1.9.89 · Gemma 4 chat: context, speed readout, MTP toggle

- ⚡ Gemma 4 (LiteRT) chat context raised from 4096 to 8192 tokens, so conversations can run longer before older messages drop off.
- 🐛 The tokens-per-second shown under a Gemma 4 reply is now correct. It was undercounting when multi-token prediction was on, because it counted streamed chunks instead of real tokens.
- ✨ Added a Speculative Decoding (MTP) toggle in the generation settings for Gemma 4 models. You can turn multi-token prediction on or off on both CPU and GPU. Changing it reloads the model.

## 1.9.88 · Chat with Gemma 4 (LiteRT)

- ✨ Gemma 4 models (E2B and E4B) now show up in the model picker and run in the real chat on the new LiteRT engine, with multi-token prediction on the GPU for much faster replies. Selecting a Gemma 4 model automatically unloads the llama.cpp model. Tools, thinking, and image input on LiteRT are coming next.

## 1.9.87 · LiteRT dev test: E4B model + decode chart

- ⚡ The developer LiteRT test now benchmarks every Gemma 4 model found on the device (E2B and E4B) across CPU and GPU with speculative decoding off and on, and shows the results as a bar chart of decode tokens per second, in the same style as the normal benchmarks. Each bar also checks that the output matches its base config.

## 1.9.86 · LiteRT: real tokens/sec in the dev test

- ⚡ The developer LiteRT test now also reports real decode tokens per second, measured with LiteRT's built-in benchmark (the same one Google's Edge Gallery uses), next to characters per second. Makes the numbers directly recognizable and comparable to Edge Gallery.

## 1.9.85 · LiteRT: full-run timing to compare with Edge Gallery (dev)

- ⚡ The developer LiteRT test now times the full response the same way Google's Edge Gallery app does (total seconds for the identical output), so we can compare our speed to Google's directly, not just the multi-token-prediction ratio. It also reports time to first token and caps generation at 4096 tokens to match.

## 1.9.84 · LiteRT MTP: correct decode measurement (dev)

- 🐛 The developer LiteRT test was counting streamed messages instead of tokens, which badly undercounted multi-token prediction (it emits several tokens per message, so it looked slower than it really is). It now measures by characters of the identical output, so the MTP versus normal comparison is finally apples to apples, and it also checks that MTP produces the exact same text as normal decoding.

## 1.9.83 · LiteRT MTP: GPU sampler link fix (dev)

- 🐛 Second attempt at the GPU sampler fix. The previous build loaded the runtime into the shared linker scope, but Android's library isolation still kept the GPU sampler from finding the runtime's functions, so it stayed on slow CPU sampling. This build links the sampler directly against the runtime library instead, which should finally let multi-token prediction run its sampling on the GPU and speed up decoding.

## 1.9.82 · LiteRT MTP: GPU sampler symbol fix (dev)

- 🐛 Follow-up to the last build: bundling the GPU sampler was not enough on its own, because the library could not find the runtime symbols it needs, so the app quietly fell back to slow CPU sampling. This build loads the LiteRT runtime into the shared linker scope first, so the GPU sampler can resolve its symbols and stay on the GPU during multi-token prediction. If it works, MTP decode should finally beat normal decoding on this device.

## 1.9.81 · LiteRT GPU sampler for MTP (dev)

- 🐛 The LiteRT test showed multi-token prediction running slower than normal decoding, because a GPU sampling library was missing from the runtime, so every step fell back to slow CPU sampling and copied data back and forth. This build bundles that library so sampling stays on the GPU, which should let the MTP speedup finally show up on this device.

## 1.9.80 · LiteRT speedup measurement (dev)

- ⚡ The developer LiteRT test now measures decode speed on both CPU and GPU, with multi-token prediction off and on, so we can see the real MTP speedup on this device (the whole reason for the new engine).

## 1.9.79 · LiteRT proof-of-life (dev)

- ✨ Added a temporary developer test button in the benchmark screen that loads a Gemma 4 model through the new LiteRT engine and streams a few tokens, to confirm the second engine actually runs on this device before wiring it into the app properly.

## 1.9.78 · LiteRT engine groundwork

- ✨ First step toward a second on-device engine: added the LiteRT-LM runtime (Google AI Edge) to the app. It will run Gemma 4 models with hardware-accelerated multi-token prediction, the real decode speedup our llama.cpp experiment topped out at break-even on. No user-facing change yet; this build just integrates the runtime.

## 1.9.77 · MTP larger draft test

- ⚡ Experimental MTP now drafts 3 tokens per step instead of 2, to check whether a larger batch pays off (especially on GPU, where speculative decoding can amortize weight loading). A diagnostic step toward finding whether MTP can ever beat normal decoding on this hardware.

## 1.9.76 · MTP on GPU (experimental)

- ✨ Experimental MTP speculative decoding now also runs on the GPU, not just CPU. On CPU it turned out to slow decoding down slightly (the verify overhead outweighs the batching), but the GPU is where speculative decoding can actually pay off, so it is now worth measuring there. It stays a safe no-op if the GPU backend cannot run the verify.

## 1.9.75 · Accurate MTP decode measurement

- 🐛 The benchmark now measures decode speed from the engine's real streamed-token count and timing instead of counting UI stream updates. With MTP on, accepted tokens arrive in bursts that the UI stream merges, which made the decode number read as ~0 even though generation completed. Applies to normal decoding too.

## 1.9.74 · MTP draft size tuning

- 🐛 Another step on experimental MTP: reduced the speculative draft size so the verify step fits within what Qwen 3.5's recurrent layers support (larger drafts were failing the decode). This build helps pin down the working ceiling.

## 1.9.73 · MTP verify step fix

- 🐛 Experimental MTP speculative decoding now gets through its verify step on Qwen 3.5 (the hybrid model's recurrent state needed more rollback room for the multi-token verify pass, which was failing the decode). Speculation runs on CPU for now; the GPU path keeps normal decoding.

## 1.9.72 · MTP crash fix (now runs end to end)

- 🐛 Fixed a crash that stopped experimental MTP speculative decoding from actually running in the benchmark (the MTP head was handed a batch without token positions). It now runs end to end on Qwen 3.5 models with an MTP head, and the result badge shows the draft acceptance percentage.

## 1.9.71 · MTP now engages on Qwen 3.5

- 🐛 MTP speculative decoding was silently skipped on Qwen 3.5 (its hybrid layers need per-token rollback snapshots to trim rejected drafts, which the target context was not set up for). It now engages, and the benchmark result shows the draft acceptance percentage next to the hardware (for example "CPU . MTP 75%").

## 1.9.70 · MTP speculative decoding (experimental)

- ✨ Experimental self-MTP speculative decoding now actually runs in the benchmark on Qwen 3.5 models that have an MTP head: the model drafts several tokens ahead and verifies them in a single pass, which can speed up decoding when the drafts are accepted. Greedy only for now (benchmark), and the output stays identical to normal decoding. The result shows the draft acceptance rate so you can see whether it is helping.

## 1.9.69 · MTP status in the benchmark

- ⚡ The benchmark now shows the experimental MTP outcome right on the result: "CPU · MTP" when the model's MTP head was built, or "no MTP" when the model does not support it. No more digging through logs to confirm a Qwen 3.5 model is MTP-capable, before the real speedup arrives in a later update.

## 1.9.68 · Experimental MTP groundwork

- ✨ First step toward MTP speculative decoding on Qwen 3.5 models: an experimental toggle in the benchmark. This step only detects whether a model has an MTP head (check the logs); the actual decode speedup lands in a later update, and it does nothing on other models.

## 1.9.67 · Benchmark comparison

- ✨ New Compare toggle on the benchmark screen: see every model side by side, one bar per model and accelerator for decode speed, prefill speed and time-to-first-token, sorted best first and colored, so you can tell at a glance which model is fastest on this device. Uses each model's latest result per CPU/GPU; tap a bar for the full detail.

## 1.9.66 · Benchmark loading indicator

- 🐛 Starting a benchmark now immediately shows a loading indicator while the model loads into memory, instead of leaving the Run button up (which looked like nothing had happened, so it got tapped again).

## 1.9.65 · Benchmark detail cards

- ⚡ Tap a saved benchmark to open a detail card with colored score bars for prefill, decode and time-to-first-token, so you can see at a glance whether a model ran well. Each result also records how long it took.
- ⚡ The benchmark history shows a short CPU / GPU badge (the full accelerator name is in the detail card).
- 🐛 The model settings tabs spread evenly across the full width again.

## 1.9.64 · Dedicated benchmark screen

- ✨ Benchmark moved to its own screen in Settings: pick any downloaded model, pick the accelerator (CPU, GPU, or both in sequence), and run a benchmark that blocks until it finishes and keeps a per-model history. The model you had loaded in chat is restored afterward.

## 1.9.63 · Benchmark fixes

- 🐛 The benchmark now reports real prefill and decode speeds. The on-device engine doesn't expose those timings, so they are computed from wall-clock timing plus the token counts (before, both showed 0).
- 🐛 The model settings tabs no longer wrap onto two lines with the Benchmark tab present.

## 1.9.62 · Tidier model picker + names

- ⚡ Downloaded models in the picker are now grouped by provider and sorted alphabetically within each group, the same way remote server models already were.
- ⚡ For a model loaded from a remote server, its full name now shows at the top of the parameters sheet, so you can read it even when the top bar truncates a long name.

## 1.9.61 · Benchmark your models

- ✨ New Benchmark tab in a model's settings sheet: measure the loaded model's prefill and decode speed (tokens per second) plus time-to-first-token over a few runs, and keep a per-model history. Set the prefill tokens, decode tokens and number of runs. On-device models only; comparison across models and charts are coming next.

## 1.9.60 · Models list refreshes on its own

- 🐛 Models copied into the models folder by hand now show up without re-picking the folder: the Models screen rescans every time you open it, there is a Refresh button in its top bar, and re-selecting the same folder rescans instead of doing nothing.

## 1.9.59 · Memory you can manage

- ✨ New Memory screen in Settings: view, add, edit and delete the notes the model remembers across chats, all in one place. The model can still save and update them on its own with the memory tool.
- ✨ Saved memories can now be added to every chat automatically, so the model recalls them without being asked. This is opt-in: turn on Use memory in the Memory screen (off by default).
- 🐛 In a multi-step turn, the last reasoning step now appears in the process card as soon as the answer starts, instead of staying hidden until the whole response finished generating.

## 1.9.58 · Context ring counts active tools

- 🐛 The context-usage ring now includes the tokens taken by the enabled tools' definitions, which the model always receives at the start of the prompt. With several tools on this can be a lot, so the ring now shows the real remaining space from the start instead of ignoring the tool overhead until the first reply.

## 1.9.57 · Fix crash on startup

- 🐛 The previous update could crash the app on startup, because the new Memory feature created its database table with a slightly wrong schema. Fixed so the app opens normally again; your chats and settings are untouched.

## 1.9.56 · Memory tool, clearer tool steps

- ✨ New Memory tool (turn it on in Settings, then Tools): the model can save short notes and recall them in later chats, for example "remember that…". Notes are stored only on your device.
- ⚡ In the reasoning card, each tool step now shows the tool's real name and its own icon (calculator, location pin, and so on) instead of a generic wrench and the internal name.

## 1.9.55 · Location tool, tidier attach menu

- ✨ New Location tool (turn it on in Settings, then Tools): the model can get your device's approximate location (city level) when it asks, for example "what city am I in?". It asks for the location permission when you enable it, only shares an approximate position, and stays off by default.
- 🎨 The attach menu is now a compact box on the left, sitting just above the input bar, instead of stretching the full screen width.

## 1.9.54 · Attach menu hugs the bar, smarter web tools

- 🎨 The attach menu (Document, Image, Take photo) now opens right above the input bar instead of floating up into the chat.
- ⚡ Web search can now be scoped by region and recency (day, week, month or year). And Fetch web page now reads linked PDF and Office documents (PDF, DOCX, XLSX, PPTX, ODT, RTF, EPUB) as text, not just web pages.

## 1.9.53 · Tidier tools list, plus a Wikipedia tool

- ✨ New Wikipedia tool (turn it on in Settings, then Tools): the model can look up a topic and get a concise summary with the article title and link, more reliable than a general web search for well-known facts.
- ⚡ In the model's Tools tab, every tool now shows a proper name, description and icon; the tools added in the last update previously showed only their raw internal name there. Both tool screens now share the same presentation.

## 1.9.52 · New tools: calculator, unit converter, date & time, device info

- ✨ Four new tools the model can use (turn them on in Settings, then Tools): a Calculator for exact math so there are no more arithmetic slips, a Unit converter (length, mass, temperature and more), Current date & time (live, so time-sensitive answers are correct instead of using a frozen date), and Device info (your phone's model, RAM, storage and battery, so the model can reason about what runs on your device). Each is off by default; Device info only reads device specs, no location or personal data.
- ⚡ The attach menu (Document, Image, Take photo) now opens closer to the input bar.

## 1.9.51 · Bigger context by default, provider logos on your models

- ⚡ On-device models now open with a much larger context window by default, sized to safely fit your phone's memory (up to the model's trained limit). Long documents you attach are far less likely to be cut off. You can still change the context size in the model settings, and if something is still too big to fit, the app now tells you it will be trimmed.
- ⚡ Your sideloaded (custom) models now show the right provider logo when the name is recognizable (for example a Gemma model shows the Google logo) instead of the generic placeholder.

## 1.9.50 · Take a photo to attach

- ✨ The attach menu on a vision model now has a "Take photo" option that opens the camera, so you can snap a picture and send it straight to the model without saving it to the gallery first.

## 1.9.49 · Regenerate keeps the old answers

- ✨ Regenerating a reply no longer throws the old one away. Each answer is kept as a variant, and back and forward arrows next to the Regenerate button (with a "2/2" counter) let you page between them. The answer you leave showing is the one the chat continues from. Variants are kept for the current session; they are not saved across an app restart yet.

## 1.9.48 · Send images to remote servers

- ✨ You can now attach an image to a chat with a remote server model, just like with on-device models. The image is sent to the server together with your message. The attach-image button appears when the server reports a vision-capable model (and always for LM Studio, which doesn't advertise it, so you can try). If the model can't see images, the server returns an error.

## 1.9.47 · GPU works with any KV setting

- 🐛 With GPU acceleration on, creating a chat failed ("Failed to create session") whenever the KV cache was set to Q8_0 or Q4_0, which is the default. The GPU's OpenCL backend can't run a quantized KV cache, so the app now keeps the KV cache at full precision (F16) on the GPU automatically. KV quantization still works on the CPU.

## 1.9.46 · Reach the phone's OpenCL driver

- 🐛 With GPU acceleration on, the Compute backend showed "no OpenCL device" because the app couldn't reach the phone's own GPU driver. The app now declares access to the device OpenCL library and uses it directly, so an Adreno GPU can be detected. Still opt-in; if your device does not expose OpenCL, it falls back to the CPU as before.

## 1.9.45 · Cleaner wording

- ⚡ Polished the wording across settings, menus and these release notes for a cleaner, more natural style.

## 1.9.44 · Corrected the GPU setting label

- 🐛 The GPU acceleration setting still said "Vulkan". It now correctly says "OpenCL", the GPU backend the app actually switched to.

## 1.9.43 · See which backend a model runs on

- ✨ The model settings sheet (tune icon → Parameters) now shows a "Compute backend" line for local models: "GPU (OpenCL): <your GPU> (N/N layers)" when the GPU is actually in use, or "CPU" otherwise. This lets you verify the experimental GPU toggle really took effect. If it says CPU while the toggle is on, the GPU couldn't be used and it safely ran on the CPU.

## 1.9.42 · GPU backend switched to OpenCL

- ⚡ The experimental GPU path now uses OpenCL (Qualcomm's Adreno-native GPU API) instead of Vulkan. Vulkan crashed on Adreno GPUs; OpenCL is the path Google's own on-device AI and llama.cpp both use on Qualcomm chips, so it has a real chance of running the model on the GPU reliably. Still opt-in (Settings → Advanced → GPU acceleration); if it doesn't help or misbehaves on your device, leave it off. Everything runs on the CPU by default.

## 1.9.41 · GPU acceleration is now an opt-in toggle

- 🐛 Vision on the GPU crashed the inference engine on some devices (e.g. Adreno). GPU use is now OFF by default, so vision and text both run reliably on the CPU again, with no more crashes out of the box.
- ✨ New "GPU acceleration (experimental)" switch in Settings → Advanced. Turn it on to offload the whole model (LLM and image encoding) to the GPU (Vulkan). Faster prompt processing and image encoding when your GPU supports it; if you get wrong output or a crash, turn it back off. Applies on the next model load.

## 1.9.40 · GPU-accelerated image vision

- ✨ Image processing for vision models now runs on your phone's GPU (Vulkan) instead of the CPU, noticeably faster image encoding, especially for larger/higher-detail images. Text generation still runs on the CPU (that's by design, the GPU isn't faster there on mobile).
- ⚡ Safety net: if a device's GPU driver can't handle the vision encoder, the app automatically falls back to CPU vision: a known-bad GPU is skipped up front, and a GPU that crashes once is disabled for good afterwards, so vision keeps working.

## 1.9.39 · GPU groundwork (Vulkan)

- ⚡ Under the hood: the Vulkan GPU backend is now compiled into the app, groundwork for upcoming GPU-accelerated image processing. No behaviour change yet: text generation and image input still run on the CPU exactly as before; this build just verifies the GPU support builds and installs cleanly on your device.

## 1.9.38 · KV-cache quantization

- ✨ New "KV cache" picker in a local model's settings (Parameters tab): FP16, Q8_0 (standard), or Q4_0. Q8_0 is now the default: it roughly halves the memory the conversation cache uses, at near-zero quality loss, and can speed up longer chats.
- ⚡ Quantized cache automatically enables Flash Attention, and safely falls back to full-precision FP16 if your device can't run that combination, so a model always loads.

## 1.9.37 · True model-view + wider vision detection

- ✨ A sent image now displays at the exact resolution the model actually received it, so you see the real image the model "saw". Move the Image detail slider and watch it change.
- ⚡ Any downloaded model paired with a matching projector (mmproj) file in your models folder is now recognized as a vision model (not just sideloaded ones), so its image badge shows in the picker.

## 1.9.36 · Image token count + detail slider

- ✨ Sent images now show how many tokens they used (🖼 N), so you can see how much of the context a photo consumes.
- ✨ New "Image detail" slider in a vision model's settings: trade image resolution/detail against tokens and speed. Higher = sharper, lower = faster & lighter on context.

## 1.9.35 · Vision projector loads (audio encoder skipped)

- 🐛 Fixed the crash when loading a vision model whose projector also bundles an audio encoder (e.g. Gemma 3n / Gemma 4 E2B): the unused audio part is now skipped, so image input can finally load.

## 1.9.34 · Vision load diagnostics

- ⚡ If a vision model's image projector fails to load, the app now shows a copyable diagnostic (memory + the native crash report) so projector-compatibility issues on sideloaded models can be pinned down precisely.

## 1.9.32 · Image preview + clearer vision errors

- ✨ Tap an attached or sent image to view it full-screen: pinch to zoom, tap to close, just like the document and PDF previews.
- ⚡ When a vision model can't load its image projector, the message now says exactly why (e.g. an incompatible mmproj) instead of a generic error.

## 1.9.31 · Send images to vision models

- ✨ Attach a photo from your gallery and ask about it. The attach button now offers Document / Image for vision models (those with an mmproj projector in your models folder).
- ✨ Sent images show as a thumbnail in the chat and are kept with the conversation.
- ⚡ The vision projector loads only at your first image, so text chats stay as fast as ever; the model line shows "Loading vision…" while it loads.
- ⚡ Photos are downscaled on-device before reaching the model. HEIC from the camera works, and portrait shots stay upright.

## 1.9.30 · Vision projector no longer auto-loads

- 🐛 Paired vision models no longer load their image projector at startup. This was causing some sideloaded models to hang when generating. Image support will load the projector only when an image is attached.

## 1.9.29 · Vision loading hardened

- 🐛 An incompatible or oversized vision projector can no longer crash the app or block loading other models. A failed projector now just means the model loads as text.
- 🐛 Tightened model↔projector pairing so an unrelated model is never tagged as vision by mistake.

## 1.9.28 · Vision models recognised

- ✨ Drop a vision model together with its mmproj projector in your models folder and it's now detected automatically, shown with an image badge in the model list.
- ✨ Loading such a model attaches its projector on-device. Attaching and sending images arrives in the next update.

## 1.9.27 · Vision groundwork II

- ✨ Wired up on-device image tokenization and generation (vision). The feature keeps coming together; UI arrives soon.

## 1.9.26 · Vision groundwork

- ✨ Laid the on-device foundation for image input (vision). Image understanding arrives over the next few updates.

## 1.9.25 · Unified, tabbed model settings

- ✨ Manage your saved system prompts straight from the model settings: pick, edit, delete or create one, without leaving the chat.
- ⚡ Remote server models now share the same tabbed settings (Prompt · Tools · Parameters) as local models, including system prompts.
- 🎨 The settings tabs now blend into the sheet instead of sitting on a dark bar.
- 🎨 Redesigned this changelog, grouped by change type.

## 1.9.24 · Preview & settings polish

- 🎨 Removed the fiddly text-preview zoom (PDF and HTML zoom are unchanged).
- 🐛 Restored a missing divider in Settings › Tools.

## 1.9.23 · Tabbed model settings

- ✨ The model settings sheet is now split into Prompt · Tools · Parameters tabs.

## 1.9.22 · Smarter chats & more file types

- ✨ Auto-name new chats from the first reply. Optional, in Settings › Tools.
- ✨ Preview spreadsheets (Excel/CSV) as a table, with a Table ⇄ Raw toggle.
- ✨ Attach RTF, OpenDocument (ODT/ODS/ODP) and EPUB files.
- ⚡ More accurate token estimates for non-Latin (CJK) text.
- ⚡ Drag a zoomed PDF with a single finger.

## 1.9.21 · PDF zoom reaches every edge

- 🐛 Two-finger pan on a zoomed PDF now reaches every edge, including single-page documents.

## 1.9.20 · Office documents

- ✨ Attach Word (.docx), Excel (.xlsx) and PowerPoint (.pptx) files. Their text is extracted and sent to the model.

## 1.9.19 · Pinch to zoom PDFs

- ✨ Pinch to zoom PDF pages in the preview.

## 1.9.18 · Real PDF pages

- ✨ Preview PDFs as real pages, not just text, with a Pages ⇄ Text toggle.
- ⚡ Scanned or image-only PDFs now preview too. Pages show even without extractable text.

## 1.9.17 · Attach PDFs

- ✨ Attach PDF files. Their text is extracted and sent to the model.

## 1.9.16 · Complete HTML previews

- 🐛 HTML preview now shows the whole page, including sections that reveal on scroll.

## 1.9.15 · Roomier, sturdier previews

- 🎨 The file-preview card is much larger for easier reading.
- 🐛 HTML files preview correctly, and the Raw view no longer freezes the app.

## 1.9.14 · Per-chat context & file previews

- ⚡ The context-usage ring now reflects each chat as you switch between them.
- ✨ Tap an attached file to preview it, with a Raw ⇄ Formatted toggle (a rendered page for HTML).

## 1.9.13 · Multi-file attachments

- ✨ Attach several files at once; long-press a file chip to see its full name and token cost.
- 🎨 A roomier message box.

## 1.9.12 · Attach text & HTML

- ✨ Attach text and HTML files. Their contents are extracted and sent along with your message.

## 1.9.11 · Clearer settings & reasoning

- 🎨 Settings reorganised into clearer sections.
- ⚡ The live reasoning tail stays aligned and can be expanded while the model writes.

## 1.9.10 · Advanced settings

- ✨ New Advanced settings screen.
- 🐛 Stability and model-picker fixes.

## 1.9.9 · In-app changelog & date

- ✨ In-app changelog: tap the version number a few times to open this list.
- ✨ Optional current date in the system prompt (toggle in Settings).

## 1.9.8 · Themed logo & reasoning peek

- 🎨 The welcome-screen logo now follows your chosen accent colour.
- ⚡ A shimmer plus a live peek of the model's reasoning while it works.
- 🐛 Custom GGUF models with junk metadata now show a clean name from the filename.
- 🎨 Rose and red theme colours are now clearly distinct.

## 1.9.7 · Colour-picker polish

- 🎨 Aligned swatch grid, a gradient System swatch, and added Red and Grey.

## 1.9.6 · Accent colours

- ✨ Choose the app's accent colour: System (Material You) plus several presets.

## 1.9.5 · Richer model info

- ✨ More provider logos and richer Ollama model details (capability badges).
- 🎨 A favicon cluster and a live reasoning label in the process card.

## 1.9.4 · Live process card

- ⚡ A live process card during generation, with tappable web sources.
- ✨ Added Ernie (Baidu) and Ornith provider logos.

## 1.9.3 · Tidy agent turns

- 🎨 Multi-step agent turns collapse into one tidy, inspectable card.

## 1.9.2 · Reasoning display fix

- 🐛 Reasoning text no longer shows dark code boxes.

## 1.9.1 · Remote tools

- ✨ Web search, web fetch and JavaScript tools now work over remote servers.

## 1.9.0 · Rich Markdown & a fresh UI

- ✨ Rich Markdown answers: code cards, tables and math.
- 🎨 Provider logos and model details in the top bar; a frosted UI throughout.

