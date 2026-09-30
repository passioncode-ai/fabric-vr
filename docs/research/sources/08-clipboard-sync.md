# Source brief 08 — Cross-device clipboard sync (web research, 2026-09-19)

Produced by a research sub-agent (Sonnet); WebSearch budget was exhausted immediately, so this
brief runs on SearchAPI Google-light + WebFetch, ~21 tool calls. Facts carry [Source, date];
uncorroborated items are in §5.

## 1. Prior art and how it works

**Apple Universal Clipboard** (Continuity) needs the same Apple ID plus Bluetooth+Wi-Fi+Handoff on
both devices; discovery is BLE, the payload moves peer-to-peer over Wi-Fi, not through iCloud
[support.apple.com/102430, 2026-02-18]. Content expires from the shared pasteboard in roughly
1–2 minutes if unpasted [applevis.com, 2016; onetapapp.co, 2026-07-20]. No published size limit or
encryption spec. Reliability is a chronic, still-open complaint: "just... permanently unreliable,"
~50% success [Reddit r/apple, 2026-08-10].

**Microsoft Cloud Clipboard** (Win+V) holds 25 items, text/HTML/images, **4 MB/item**, synced via
Microsoft account [windowslatest, 2025-09-02; windowsforum, 2026-07-28]. **SwiftKey**'s separate
cloud clipboard caps at **30 clips × 1,000 chars, text-only** [support.microsoft.com]. Breaks
repeatedly across updates: "still breaking in 2026" [Reddit r/Swiftkey, 2026-02-19]; broken on
Windows 11 25H2 [MS Tech Community, 2026-02-18]. **Phone Link** has its own "Cross-device copy and
paste" toggle (Android + iPhone) — much improved by 2026 per one hands-on [MakeUseOf, 2026-08-16],
yet outages continue [r/PhoneLink, ongoing].

**KDE Connect / GSConnect / Valent**: LAN-only, no cloud relay — mDNS/UDP discovery, TLS 1.2 for
all paired traffic [community.kde.org, 2025-04-30]. Free, **GPLv2/v3**. Cross-network use needs an
external VPN/Tailscale overlay [uniclipboard.app, 2026-05-09]. iOS client exists but is immature.
KDE's own community flagged the risk: clipboard sharing "broadcasts potentially very sensitive
information to every paired device" — debated as opt-in vs. default [discuss.kde.org, 2024-11-02].

**Google**: ChromeOS↔Android clipboard sync has shipped via Phone Hub since ~2021 [r/chromeos
recap, 2023-04-05]. **No first-party Android↔Windows clipboard feature from Google was found for
2025–2026** — only an unofficial third-party Chrome extension.

**Samsung** syncs clipboard across Galaxy devices via "Continue on other devices" (Samsung account)
[samsung.com, 2026-06-29]. OnePlus/Oppo has an equivalent, "Multi-Screen Connect → Sync clipboards"
[androidpolice.com, 2025-12-04]. No Xiaomi/Huawei-specific source found.

**Intel Unison** — discontinued: service ended 2025-06-30, fully retired 2026-01-01 [intel.com;
multiple press, 2025-07] — a caution that companion-app clipboard sync churns often. **Pushbullet**
(2014, still running) never reached Mac/Linux/iPhone [syncraapp.com]. **Join** (joaoapps) is active,
4.3★/4,995 ratings, syncs clipboard among its features. **Maccy** is free/open-source, macOS-only,
**local-only**, and drops items a password manager removed [maccy.app]. **Raycast**'s Clipboard
History auto-ignores flagged-sensitive items, also single-Mac. **Paste** syncs Mac/iPhone/iPad.
Named "Clipt"/"Pasta" products were not independently located this pass.

## 2. Platform constraints

**iOS**: general pasteboard is readable only while foregrounded; iOS 14+ banners an unrequested
read, iOS 16+ requires an explicit **"Allow Paste"** grant unless the read follows the user's own
paste gesture. Available channels for a sync app: a Share Sheet extension (user-invoked, full
access), a Shortcuts automation (reads at trigger time), a custom keyboard with Full Access. Apple
grants ambient Handoff-style sync to no third party.

**Android**: since Android 10, clipboard reads succeed only for the foreground app or current IME.
Accessibility services bypass this via system-level UI events, but Android 16 adds
`AccessibilityDataSensitive` so apps can mark views accessibility services can't read
[medium.com, 2025-12-17] — narrowing that path. Android 13 added a copy-preview toast and an
inactivity auto-clear timer; Google's guidance is to flag sensitive `ClipData` with
`EXTRA_IS_SENSITIVE` to suppress both [developer.android.com, 2024-09-24].

**Host APIs**: `NSPasteboard` has no change-notification API — every manager, Maccy included,
polls `changeCount` [SO consensus]. Windows' `AddClipboardFormatListener` is event-driven
(`WM_CLIPBOARDUPDATE`) — cheaper to integrate.

**Horizon OS / Quest**: Meta's own Virtual Display help page, fetched directly, covers pairing and
up-to-3 displays and **mentions no clipboard, copy/paste, or file transfer at all**
[meta.com/help/quest/1370025034331518, fetched 2026-09-19]. A Meta dev-forum question — "does
Oculus have a clipboard... can you access the android clipboard?" — has no confirmed answer
found. The base feature request, "copy and paste basic functions required for wireless VR," dates
to 2020 and is still being worked around via keyboard remaps as of [Facebook Quest 2 Community,
2026-02-24]. **Meta Virtual Display does not sync clipboard today — this is a confirmed gap, not
an existing Meta feature Fabric VR would duplicate.**

## 3. Architecture patterns

Two designs recur: **proximity P2P** (Apple: BLE discovery + direct Wi-Fi, account only gates
authorization) vs. **account-based cloud sync** (Microsoft, SwiftKey, Samsung — server round-trip,
no proximity needed). KDE Connect/Valent is a third: **LAN-only, no relay by design**; commercial
tools (UniClipboard) and small OSS (`kai3316/clipsync`, TLS 1.3+AES-256-GCM, zero-config) exist
specifically to add relay/off-LAN reach.

Size benchmarks from incumbents: Windows history caps 4 MB/item; SwiftKey's path is text-only,
30×1,000 chars. This supports a **two-tier transport** — text/links pushed eagerly on the
low-latency path; images/files sent as a reference (hash+size+source) with lazy pull on a separate
channel, so a large blob never blocks interactive paste.

**Privacy conventions to enforce at the relay, not just trust the source app**: macOS
`org.nspasteboard.ConcealedType`/`TransientType` (documented at nspasteboard.org, followed by
1Password, respected by Maccy); Android `EXTRA_IS_SENSITIVE`. Neither is OS-enforced — a naive
daemon that polls and relays everything will leak passwords cross-device, exactly the risk KDE
Connect's own community raised [discuss.kde.org, 2024-11-02].

**Reusable OSS**: KDE Connect protocol (**GPLv2/v3** — copyleft if code is reused) for pairing/plugin
design; **LocalSend** (**Apache-2.0**) — mDNS + HTTPS with per-session cert pinning, directly
reusable for image/file lazy-fetch; **Syncthing** (**MPL-2.0**) — device-identity/trust model,
likely overkill for clipboard; **magic-wormhole** (**MIT**) — PAKE one-time-code relay, a good
pairing pattern when phone and host aren't on the same LAN.

## 4. What users want

The pattern repeats across every incumbent: works, then silently regresses across OS updates.
Apple: "permanently unreliable," ~50% success [r/apple, 2026-08-10]; a years-old recurring "Ya
know what sucks? The Universal Clipboard" thread. Microsoft/SwiftKey/Phone Link: "still breaking
in 2026" [r/Swiftkey, 2026-02-19]; broken on 25H2 specifically [MS Tech Community, 2026-02-18].
For VR, dated 2025–2026 complaint threads are thin — the load-bearing feature request is from
2020 — but adjacent evidence is real: Meta was still iterating basic Quest 3 copy/paste/keyboard UX
in a Horizon OS v85 public test as of February 2026, and users report manual workarounds like
pasting text to themselves via WhatsApp to get it into the Quest browser [r/OculusQuest pattern,
cited through 2023, still current]. **Read as thin-but-consistent unmet demand, not a large dated
corpus** — flagged in §5.

## Recommended clipboard-sync design for Fabric VR

- Two transport tiers: eager push for text/links; reference + lazy pull for images/files, so a
  large payload never blocks interactive paste.
- LAN-first (mDNS + TLS, KDE-Connect-style) since headset and host already share Wi-Fi for Virtual
  Display; add an E2E relay (magic-wormhole-style PAKE) only as an off-LAN fallback for phones on
  cellular.
- Route through the host as hub — it already runs the Fabric agent — rather than three separate
  pairwise links (headset↔phone, headset↔host, phone↔host).
- Enforce `org.nspasteboard.ConcealedType`/`TransientType` (macOS) and `EXTRA_IS_SENSITIVE`
  (Android) at capture, before anything is relayed — never trust the source app alone.
- On Quest: Meta ships no clipboard sync (confirmed gap) and Horizon OS is AOSP-based, so plan for
  Android's foreground/IME-only read restriction — capture from Fabric VR's own panel or IME, not a
  background service.
- On iPhone: no background read is possible; ship a Share Sheet extension plus a Shortcuts
  automation as the two entry points — do not promise ambient Handoff-style sync.
- On Android phone: a companion IME or accessibility-service bridge is the realistic path, with
  Android 16's `AccessibilityDataSensitive` narrowing already visible on the roadmap.
- Give the host AI agent clipboard access through the same privacy-filtering layer, not a raw
  pasteboard read, so agent-initiated copies are auditable and filtered identically to human sync.
- Auto-expire relayed items (~1–2 min, matching the Apple precedent users already expect); make
  history opt-in per device, echoing KDE Connect's own default-on debate.
- Build the LAN pairing layer on Apache-2.0/MIT prior art (LocalSend, magic-wormhole) rather than
  GPL (KDE Connect) if Fabric VR ships closed-source.

## Unverified / conflicting

- Universal Clipboard's exact expiry window ("1 or 2 minutes" / "about two minutes") has no
  Apple-published number.
- Universal Clipboard's precise encryption model (device-to-device vs. iCloud relay, E2E or not) —
  no primary Apple source found.
- Whether Horizon OS enforces Android's foreground/IME-only clipboard restriction — no Meta
  developer doc confirms either way; treated as a working assumption.
- Rumor of iOS-style "Allow Paste" prompts coming to macOS 16 — sourced only to a Facebook post
  [2025-05-15], uncorroborated.
- "Clipt"/"Pasta" as distinct named products — not located; possible confusion with "Paste" or
  Windows' "Ditto."
- Any first-party Google Android↔Windows clipboard feature — none found (only ChromeOS↔Android
  Phone Hub is confirmed).
- Xiaomi/Huawei clipboard-ecosystem specifics — no source found; the one adjacent hit (Multi-Screen
  Connect) is OnePlus/Oppo, not Xiaomi/Huawei.
- Dated, VR-specific clipboard complaint threads for 2025–2026 are sparse — evidence here leans on
  a 2020 feature request plus 2026 adjacent workaround discussion, not a large complaint corpus.
