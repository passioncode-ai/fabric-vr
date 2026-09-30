# `:feature-assistant`

The chat that has read your notes. Its endpoint must be https (`DEC-0005`).

## Owns

- **`SseParser`** — OpenRouter sends `: OPENROUTER PROCESSING` keep-alive comments; a parser that
  feeds those to a JSON reader fails on the first slow answer, so comments are classified rather
  than parsed. `data: [DONE]` terminates; the final content-free chunk carries `usage`.
- **`OpenRouterClient`** — `POST /chat/completions` with `Authorization`, `HTTP-Referer` and
  `X-Title`. Errors are typed: 401 rejected key, 402 credits, 429 rate limit, anything else keeps
  its status. No key means no request is made at all.
- **`NotesContextBuilder`** — matching notes first, then recent ones, until a character budget is
  spent; an empty base says so instead of inventing context. The chat shows which titles were used.
- **`Assistant`** — question in, tokens out, with the notes already attached.

## Refuses

To store anything, to decide the model (that is a setting), or to send a note anywhere except as
context for the question the person just asked.

## Checks

`SseParserTest` (5), `OpenRouterClientTest` (6: token order with keep-alives, no key makes no
request, 401 with its message, credits that are final and are not retried, a rate limit that
survives its one retry, the headers and model on the wire), `StreamFailureTest` (4: an error frame
inside the stream, a stream that ends without a finish reason, a rate limit retried once honouring
`Retry-After`), `StreamBackpressureTest` (1), `StopWordsTest` (3),
`NotesContextBuilderTest` (5: relevance order, budget, empty base, transcript reaches the context,
a truncated note rather than a dropped one) and `AssistantRedirectPolicyTest` (2 — `B-197`, against
the real OkHttp redirect machinery with every host resolved to loopback: a `307` off the private
network never receiving the prompt, and one that stays inside it still followed) —
**26 JVM tests**.

**`B-197`: this module was the one `NetworkPolicy` caller with no hop check.** `DEC-0077` put a
network interceptor on all three clients in `:feature-stt`, so a redirect could no longer move a
recording or a transcription key to a public host in the clear. `OpenRouterClient` built its own
`OkHttpClient` here and followed redirects unchecked — and what travels on this one is the
**context**: `NotesContextBuilder` packs up to 12 KB of the person's own notes into every request,
under an `Authorization` header carrying their OpenRouter key. `DEC-0020` unlinked this module from
`:app`, so nothing installable reached it, which is why it was a board row and not a blocker; it is
also exactly why it had to be closed now rather than later, because the day the module is linked
again is the day nobody remembers the row.

The client is now built by `FabricHttp`, which moved from `:feature-stt` to `:core-common` for this
reason (`DEC-0079`: the factory belongs beside the rule it enforces, not beside its first caller) — an `internal` factory in another module was not something this one could call. It is also
a `companion object val` rather than a default argument, so it is one client for the process
instead of one OkHttp dispatcher, thread pool and connection pool per construction (`I-28`), and so
the wiring can be held and asserted at all. A refused hop now reaches the person as
`AppError.InsecureUrl` rather than as a generic network failure: `InsecureHopException` is an
`IOException` by design, which is what would otherwise have let `onFailure` swallow it as *the
network failed*.

## Status — cut from v1

`DEC-0020` closes the assistant route for v1: the chat screen, its view model, the entry point and
the OpenRouter key and model settings are deleted, and **`:app` no longer depends on this module**,
so none of it reaches the APK and nothing can invoke it. **The module itself stays**, and its 26
tests keep running — deleting it would delete real coverage of code this project intends to bring
back. Re-entry is one dependency line in `app/build.gradle.kts` plus the work the spec describes. Every precondition it needed was missing at once — retrieval broken for
the language the notes are written in, up to 12 000 characters of raw dictations posted to a third
party with no disclosure, and a conversation that did not survive leaving the screen. The code stays
in git history; `docs/evidence/plans/2026-09-20-v2/T-033.md` names what bringing it back would take.
