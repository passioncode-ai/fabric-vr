# `:core-common`

Design tokens, the theme, the error taxonomy, secure settings and the one network **policy** both
HTTP clients obey. The key's storage is `DEC-0006`; the cleartext rule is `DEC-0005`. Nothing here
knows what a note is, how speech is recognised, or how to reach a server — `NetworkPolicy` decides
whether a URL may be reached, and never reaches it. Since `T-010` it **parses** the host with
`java.net.URI` rather than carving it out of the string: the old surgery read a URL's *userinfo*
as its host, so `http://192.168.0.5:8080@evil.invalid/` passed the private-address test while
the HTTP client resolved the public host after the `@` — the cloud key and a recording of the
person's voice in the clear to whatever the attacker named (`C-01`). It also refused
`http://[::1]:8080/`, an address the person genuinely has, and accepted `example.com.local`,
which is not an mDNS name: RFC 6762 restricts those to a single label (`C-23`).

**The policy now has two doors, and `B-187` is why.** `requireReachable` judges a *string* — the
address in Settings, asked once. `permits(scheme, host)` judges a *hop*, and is asked again for
every redirect a server names, from an OkHttp interceptor that already holds a parsed `HttpUrl`
(re-serialising that to a string so this could parse it back is exactly the round trip `T-010`
proved unsafe). It takes both arguments nullable so that "the client could not tell me" lands on
refusal, like an unparseable URL. The second door **delegates to the first's predicate** rather
than restating it, because a divergence between them would be a cleartext hole with a green test
over it; `NetworkPolicyTest` asserts the agreement as a table across both.

**Since `B-197` the enforcement lives here too, and that is a deliberate change of shape.** The
OkHttp adapter (`DEC-0079`) — `NetworkPolicyInterceptor` and the `FabricHttp` factory that puts it on every
client — was `internal` to `:feature-stt`, which is exactly how the rule came to cover two of three
callers and then three of four: `OpenRouterClient` in `:feature-assistant` cannot import another
module's `internal` symbol, so it built its own client and followed redirects unchecked. The
alternative was to duplicate four lines in the assistant, and it was refused for the reason the two
doors above are refused: two copies of a cleartext rule is how one of them quietly stops being the
rule. It costs this module an `api` dependency on OkHttp — visible to every module, used by two —
and buys one definition of *where this app will send bytes in the clear*.

**And since `B-216` there is a THIRD door, which answers a different question entirely.**
`permits` asks *may these bytes travel in the clear*, and it answers **yes to every `https` host**
— correctly, because TLS is TLS wherever it points. So the whole cleartext apparatus above had
nothing at all to say about a `307` that re-POSTed the recording to `https://attacker`: encrypted,
and to somebody the person never named. `permitsRedirect(originHost, targetHost)` is the question
nobody had asked — *may a redirect change host at all* — and the rule is that a request **carrying
something** may not. Same host, any path or port: followed, because a server moving its own route
is ordinary, and whisper.cpp's own server issues no redirect at all. Another host: refused.

Three things make that rule shippable rather than merely strict, and each is a test:

- **The payload test is `NetworkPolicyInterceptor`'s, not this file's.** Whether a request carries
  a body or an `Authorization` header is an HTTP question; `permitsRedirect` answers only *may
  these two hosts differ*. A credential-free `GET` is exempt, and the model download is why — a
  vendor's `resolve/main` answers with a cross-host CDN redirect **by design**, and there is
  nothing of the person's on that request to misdirect. Its guards are the pinned SHA-256 and the
  store's own allow-list, which is where `ModelDownloader` has had this shape since it was written.
- **Two private hosts are exempt, and that is `DEC-0005`'s own trust boundary rather than a
  softening.** A hop from `192.168.1.50` to `whisper.local` moves the recording somewhere that
  decision already permits it to go in the clear, so following it exposes nothing new — which is
  what keeps `DEC-0077`'s accepted case (a local gateway in front of a whisper-server) true.
- **A separate `AppError`.** `AppError.RedirectRefused(host)` and not `InsecureUrl`, because
  `error_insecure_url` says *"Only https, or http to a device on your own network"* and printing
  that for an `https` → `https` hop would be a lie about why nothing was sent. The host is named,
  and only the host: a refused `Location` can carry a query string and a query string is where a
  token ends up. `InsecureHopException.error` widened from `AppError.InsecureUrl` to `AppError`
  so the new refusal reaches the person down the path the old one already had — four `when`s in
  three modules, one of them `:feature-assistant`, which is the module that proved a second
  exception class would have been forgotten (`B-197`).

`NetworkPolicy` still decides whether a URL may be reached and never reaches one; `FabricHttp`
builds a client and never makes a call with it. The module's rule is unchanged: it holds the
policy, not the traffic.

## Owns

- **`Tokens`** (`core-common/src/main/kotlin/ai/passioncode/fabricvr/common/theme/Tokens.kt`) — the sheleg-design *workbench* palette, spacing, radii, type
  scale and motion durations. Dark-first, because the panel floats over passthrough. Every screen
  reads values from here; a colour typed into a screen is a defect.
- **`FabricTheme`** — the Material 3 scheme built from those tokens.
- **`AppError`** — the one failure taxonomy. Every public suspend function in the app returns
  `Result<T>` whose failure carries an `AppError`; the UI never catches a raw exception.
  **The network branch of `toAppError` has to be complete, because everything below it is
  storage** (`M12`). It named three shapes, and `ConnectException`, `SSLException`,
  `NoRouteToHostException` and `SocketException` are all `IOException`s — so a whisper-server that
  refused the connection, or one whose certificate did not verify, became `Storage` and reached
  the person as *"Couldn't save. Your text is still here."*: a sentence about their note, for a
  failure their note was never in. All four are named in the branch now, two of them redundantly
  (they extend `SocketException`), because the audit row names them and a reader looking for one
  of those words should find it there rather than have to know the hierarchy.
  **`Network` carries the host** the caller could not reach, when the caller knows one. The person
  typed that address into Settings and it is the only part of the sentence they can check.
- **`UiStateMapper`** — the only place an error becomes words, and the reason no screen invents its
  own wording. Every branch returns a distinct, non-empty message; `Unknown` still renders.
  **`Storage.op` reaches the words** (`M12`). The taxonomy has carried the operation from the
  beginning and this mapper ignored it, so a failed export and a keystore that would not open both
  read as the sentence about the person's text. Four operations — save, export, delete recording,
  keystore — choose a **sentence**; every other op falls back to a general one, because
  `vault.purge` is an internal key and no raw key may reach a person (`REQ-061`). The mapping is
  by full op name and not by prefix: `vault.audio` is adopting a recording into a note's folder
  and `vault.audio.remove` is deleting one, and a prefix rule would file the second under the
  first.
  **And `Storage.op` was only the first of four identifiers doing this** (`REQ-061`, audit
  `M27`). The provider key reached the person as *"No key saved for cloud."*, a throwable's
  class as *"Something failed: NullPointerException."*, and a model key as *"small, 190 MB"*.
  The rule that fixed `op` is now the rule for all of them, in a form a test can decide: **no
  argument of a `UiMessage` is a raw `String`** (`DEC-0071`) — a number is a number, a size has been
  formatted, and anything that is a name is a `UiMessage.Res`, a resource id the rendering
  context resolves. That is what lets `:app` supply an enum's name for an enum `:core-common`
  cannot see, and it is what `NoRawIdentifiersTest` asserts, so the next identifier added to
  `AppError` fails a test rather than appearing in a banner. The class of an `Unknown` failure
  now goes to `Log2` instead, where `DEC-0023`'s crash report carries it off the headset.
  **`OpenRouter` maps to one sentence, not four.** `DEC-0020` cut the assistant and `:app` does
  not link `:feature-assistant`, so no installable build can construct that shape; the four
  sentences it fanned out into all named a service the product has not got, and they are
  deleted. The branch stays because the shape does — `:feature-assistant` is still in the tree.
  **`NoApiKey` is the cloud transcription key**, not the assistant's: `CloudTranscriptionClient`
  raises it, and its sentence used to send the person looking for an OpenRouter field no screen
  has.
- **`SecureSettings`** — AES/GCM through an `AndroidKeyStore` key (`fabricvr_settings_v1`).
  `androidx.security:security-crypto` is deprecated, so this is the platform path.
  `InMemorySecureSettings` exists for tests.
  **A failed decrypt is classified, not assumed fatal** (`DEC-0037`). It deleted the ciphertext on
  *any* throwable, so a transient `KeyStoreException` — a busy keystore, a device still finishing
  boot — destroyed the value permanently, and this store holds every non-secret setting too, so a
  run of them reset the app's configuration one key per read (`C-04`). Permanent means
  `AEADBadTagException`, `BadPaddingException`, `KeyPermanentlyInvalidatedException` or a
  malformed stored string, matched on **type** through the cause chain; everything else keeps its
  bytes and joins `unreadableKeys`, which empties on the next successful read. The default is
  transient because the two mistakes do not cost the same: one wasted read against a lost value.
  **A third class, *unusable*, is the alias rather than one value** (`B-188`, `DEC-0075`). An entry that
  exists and cannot be opened — `UnrecoverableKeyException` out of `getEntry`, the vendor's
  *"invalid key blob"* inside it, a plain `InvalidKeyException` — fell into the transient default,
  so nothing ever called `deleteEntry`: every read re-failed and, because this store holds every
  non-secret setting, every `put` returned `Storage("keystore")` and the Settings screen stopped
  saving until the person cleared app data. It is now checked **after** permanent, so `DEC-0037`
  keeps every shape it claimed, and it repairs instead of reporting: the alias is deleted, a key
  generated, the store cleared in one act because nothing in it can be read again, and the `put`
  that discovered it retried once. `java.security.KeyStoreException` is deliberately **not** in
  this class — a busy keystore throws it, and `C-04` is the record of what treating that as loss
  costs. The two values a person cannot re-derive, `openrouter_api_key` and `cloud_stt_key`, are
  published through `corruptedKeys` — and only when they were actually stored, because announcing
  the loss of a key nobody set is its own wrong sentence. An alias that cannot be *replaced* is
  not a repaired one: nothing is cleared, nothing is announced, and the `put` fails honestly.
  The key lives behind `SecretKeyProvider` (`AndroidKeystoreKeyProvider` in production) because
  deleting and regenerating an alias happens nowhere near a decrypt, so `decryptFailure` can
  neither provoke nor observe it — and Robolectric has no `AndroidKeyStore`, so a test using the
  real one could watch the repair be attempted and never watch it succeed.
**The palette has a floor, and it is the display's** (`DEC-0085`). Meta's *Color* page: LCD
panels cannot meaningfully differentiate brightness below **13 of 255**, so a token darker than
that describes a distinction the hardware throws away. `ink` was `#0B0E12` — red 11 — and
`accentInk` red 5; both were raised, and `PaletteFloorTest` holds the whole palette to the floor
rather than the two that happened to be caught. It also asserts `ink` and `surface` stay five
levels apart, because a floor buys nothing if the two tones the dark theme rests on still land on
one. `inkSpace` is the same tone at alpha 0.92: the immersive host paints it so the room shows
through a panel that had been configured transparent and then covered.

**A control's boundary is visible, and its floor is a scan rather than a comment** (`DEC-0086`).
`Theme.kt` maps Material's `outline` to `Palette.line`, the only edge on every `FabricOutlinedButton`
and all six `OutlinedTextField`s; it was `#2A3340` at **1.38:1** against `surface` where Meta asks
for 3:1, and is `#5C6A7E` at 3.15:1 now. `FabricButton` joins the wrappers so a primary action gets
the 72 dp floor without being told, because `ControlFloorTest` had excluded `Button` on a fact that
stopped being true five call sites later.

**The panel's minimum height is derived from these tokens, never typed** (`DEC-0046`):
`PanelSizeTest` sums them, after a spec stated a budget that omitted the 128 dp control it was
about and was therefore unreachable by 58 dp.

- **`UiMessage`** — a string id plus the values that fill it, and `UiMessage.Res` for a value
  that is itself a sentence. See the identifier rule under `UiStateMapper`.
- **`FabricHttp` / `NetworkPolicyInterceptor` / `InsecureHopException`** (`DEC-0077`, `B-197`) —
  `DEC-0005` applied to **every hop of a call**, as the one factory every `OkHttpClient` in this
  repository is built by. A **network** interceptor, not an application one: an application
  interceptor runs once per *call*, above `RetryAndFollowUpInterceptor`, so it never sees the
  redirect it would have to refuse. `InsecureHopException` is an `IOException` and that is not
  cosmetic — OkHttp's `AsyncCall` passes an `IOException` to `onFailure` unchanged and wraps
  anything else, so a refusal thrown as a plain `Exception` would reach the client with its
  `AppError` already lost and the person would be told their dictation was cancelled.
  `FabricHttpSourceTest` scans every module's `src/main` and fails on an `OkHttpClient`
  constructed anywhere but here — the tree-wide question a per-module wiring test cannot ask.
  Since `B-216` the interceptor carries a second rule as well: a hop that would move a request
  **carrying a body or an `Authorization` header** to a different host is refused. It is the one
  rule here that is not a property of a single hop — *somewhere else* has no meaning without the
  place the call started — so it reads `chain.call().request()`, OkHttp's own record of what
  `newCall` was handed, rather than remembering anything itself.
- **`Log2`** — one tag (`FabricVR`) and a `redact()` that reports a value's shape, never its
  content. Note bodies, transcripts and keys never reach the log. Since `REQ-061` it also
  carries the diagnosis an error message no longer shows.
- **`core-common/src/main/kotlin/ai/passioncode/fabricvr/common/ui/Controls.kt`** — `FabricTextButton`, `FabricOutlinedButton`, `FabricChip` and
  `FabricIconButton`: thin wrappers that apply `Tokens.Space.controlHeight` (72 dp) once, because
  Material's own defaults are sized for a fingertip on a phone and a controller ray pivots at the
  wrist (`B-18`). **The rule has enforcement now, and it needed it.** This file has said "the
  screens call these" since it was written, and when step 8's verification counted, only
  `ErrorBanner` and `TodayScreen` did (`B-154`): nineteen raw controls across Settings, Search,
  Licences and the editor, several of them respelling `heightIn` at the call site — which is the
  drift the wrapper exists to prevent, because the twentieth call site is the one that forgets.
  All nineteen use the wrappers as of this change, and `:app`'s `ControlFloorTest` scans
  `app/src/main/kotlin/**/ui/*.kt` and fails the build with a file:line list when a bare
  `TextButton`, `OutlinedButton`, `FilterChip` or `IconButton` reappears. It has a planted-case
  test beside it, for `MainThreadPolicyTest`'s reason: a scan that matches nothing prints the
  same silence as a clean tree. `Button` is deliberately outside the rule — every one in this
  tree already carries an explicit height, and forcing 72 dp on the 128 dp record control would
  be a wrapper the next screen works around.

  **Two more wrappers since `B-154`, and both close a gap the size rule could not see.**

  - `FabricWrapRow` — a wrapping row with `Tokens.Space.s` in **both** axes. Every wrapping row in
    the product was written as `FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s))`
    and nothing else, and a `FlowRow`'s cross-axis default is `Arrangement.Top`, which is **zero**.
    Eleven of them, measured 2026-09-22 — so ten language chips wrapping inside a panel put two
    72 dp targets edge to edge, and the miss is silent because both are legal taps. All eleven
    call the wrapper now, and `:app`'s `DestructiveAffordanceTest` scans for a bare `FlowRow` the
    way `ControlFloorTest` scans for a bare `TextButton`, with the same planted-case pair beside it.
  - `FabricDangerButton` — an outlined control in `Tokens.Palette.danger`, for an action that
    cannot be taken back. **That token was defined when the palette was written and rendered by
    nothing**: it reached Material as the `error` role in `Theme.kt` and no control in the product
    ever wore it, so *Delete* looked exactly like *Play*. Outlined rather than filled on purpose —
    a filled red control would out-shout *Record*, which is the one thing the product exists to
    press. It keeps the same 72 dp floor: a destructive control is the last one that should be
    hard to aim at deliberately.

## Refuses

To know what a note is, to **make** a network call, or to decide navigation. It holds the cleartext
policy and the interceptor that applies it (`B-197`); nothing here opens a socket, and no class
here knows what a transcription or a chat completion is.

- **`CrashLog`** — one record per crash into a bounded file, trimmed from the front so a crash
  loop keeps the newest rather than the first. Never throws: it runs on a thread that is already
  dying, and a handler that throws replaces a diagnosable crash with an undiagnosable one.
  `DEC-0027`.
- **`Redaction`** — removes credential-*shaped* substrings from free text about to be written down.
  Distinct from `Log2.redact`, which replaces a whole value because the caller knows it is a
  secret; here the caller does not, and replacing all of an exception message would leave a report
  that diagnoses nothing. Its shape list mirrors `scripts/check-secrets.sh` and
  `RedactionParityTest` fails when the two drift.

## Checks

`UiStateMapperTest` (15: every branch maps to a real id, no two branches produce the same message,
the actions right, the missing model names its size and offers writing instead, **a storage
failure naming its operation and an unreachable host naming itself** (`M12`), and — `T-032` —
**every `AppError` shape has a case, by construction**: `sealedSubclasses - covered` must be empty,
and it reported `[InsecureUrl, SttNotConfigured]` when it was written), `AppErrorTest` (3: the
classification **as a table**, because `M12` was a missing row and a missing row is what a list of
hand-written assertions cannot show you — four network shapes plus a disk failure as the control,
the host carried, and a caller with no host still getting the right shape), `NetworkPolicyTest` (17: https anywhere, cleartext only to RFC1918, loopback and
a **single-label** `.local`, userinfo that cannot pose as the host, private IPv6 reachable and
public IPv6 not, a host the parser cannot identify refused rather than assumed, and — `B-187` —
**the hop rule and the url rule agreeing across fifteen addresses**, a hop this app cannot
identify refused, and the scheme matched without regard to case; plus `B-216`'s four — a redirect
off the configured host refused in both directions, one that stays on it followed, two hosts on
the person's own network exempt with `example.com.local` still refused among them, and an
unidentifiable redirect refused rather than assumed), `InMemorySecureSettingsTest` (3), `KeystoreSecureSettingsTest` (12 — `C-04`'s classifier and
what each kind of failure does to the stored bytes, plus `B-188`'s third class: an unusable alias
deleted and regenerated so the save goes through, the two secrets it took named while a secret
nobody stored is not, the same repair from a read, the class decided by type through a wrapper
with `DEC-0037`'s permanent shapes and `C-04`'s transient default both proved not to reach the
alias, and an alias that cannot be replaced failing without clearing the store. Robolectric has
no `AndroidKeyStore`, so a decrypt failure is injected before the cipher is touched; everything
downstream of a repair runs against a real AES key from a fake `SecretKeyProvider`, which is why
the round-trip after the repair is a measurement rather than a claim) and
`LoggingTest` (1: redaction never returns the value) and `NoRawIdentifiersTest` (6 — `REQ-061`:
**no argument of a `UiMessage` is a raw `String`**, which is the decidable form of "no internal
identifier reaches a person", plus the rendered sentences for the four shapes that carry one),
`CrashLogTest` (8), `LoggingOffDeviceTest` (1), `RedactionParityTest` (1) and
`FabricHttpSourceTest` (3 — `B-197`: **no module anywhere in the tree constructs an `OkHttpClient`
outside `FabricHttp`**, plus the canary that proves the scan can fail and does not red on a correct
line, plus the factory being where the exemption says it is) —
**76 JVM tests**, counted from the tree on 2026-09-24. That figure is `@Test` annotations
under `src/test`, which is what `check-docs.sh` §17 counts (`DEC-0053`, the section that exists because this
file carried two wrong counts at once); a **run** reports one more, because
`LoggingTest.kt` declares two classes over one annotation. Both are right about different
things, and the one stated here is the one a gate can check. On the device, `SecureSettingsTest` (4: a Keystore round-trip, a value
that cannot be decrypted, the alias under concurrent use) and `UiStringsTest` (5: every message
resolves to a non-blank sentence with no placeholder left unfilled, every `AppError` shape has a
case, a missing speech provider is **named** in the sentence rather than referred to, both the
status and the class name reach the text, and **the unreachable host reaches the sentence while
no storage op key does**) — **9 instrumented tests**. Seven of them ran on a
Quest 3 on 2026-09-19 with 0 failures; the eighth, `everyAppErrorShapeIsCovered`, was added by
`T-032` afterwards and **has never run on a device** — it is the structural half of `REQ-023`,
and its JVM twin in `UiStateMapperTest` is what executes on every build. The split is deliberate: the JVM has no `AndroidKeyStore` and
no resource table, so both would pass for the wrong reason there.
