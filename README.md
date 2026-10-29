# Shield

An offline adult-content blocker for Android, built for one person's phone and
built around a single rule: **turning protection on is instant, turning it off is
slow and needs someone else.**

No accounts, no subscriptions, no premium tier, no server. Every byte of your
usage data stays on the phone.

Built and tested against a **vivo Y400 5G (Snapdragon 4 Gen 2, 8 GB / 256 GB)
running OriginOS 6 / Android 16**, which is why there is specific handling for
OriginOS's background-app killing and for Android's restrictions on sideloaded
apps.

- **[SETUP.md](SETUP.md)** — install it and get every permission right
- **[app/release/](app/release/)** — the ready-to-install APK
- Read the *Honest limitations* section below before you rely on this

---

## What it actually does

### 1. Blocks adult content everywhere, not per-browser

A local `VpnService` answers every DNS lookup the phone makes. Because it is the
system resolver, it covers every app at once: Chrome, the vivo browser, Firefox,
private and incognito tabs, in-app browsers inside Instagram or Telegram,
WebViews, widgets, everything. There is no per-browser setting that can be
forgotten.

It is a **DNS-only tunnel**. Only DNS is routed into the VPN; all other traffic
goes out over the normal network untouched. That means no throughput cost, no
battery drain from proxying video, and no app breaking because of a userspace TCP
stack — but no app can resolve a blocked name.

Three independent layers decide what to refuse:

| Layer | What it catches |
|---|---|
| Downloaded blocklists | ~400,000 known adult domains, refreshed daily |
| Built-in patterns | Newly registered domains no list knows yet |
| Family-filtering upstream resolvers | Everything else, filtered server-side by CleanBrowsing / Cloudflare for Families / AdGuard Family / OpenDNS FamilyShield |

### 2. Forced SafeSearch, which is what covers images

A blocklist cannot block Google Images without blocking Google. So Shield answers
lookups for the search engines with the address of their **permanently filtered
endpoint** instead:

| You look up | You get the address of |
|---|---|
| `google.com`, `images.google.com`, any `google.*` country domain | `forcesafesearch.google.com` |
| `youtube.com`, `m.youtube.com`, `youtubei.googleapis.com` | `restrict.youtube.com` |
| `bing.com` | `strict.bing.com` |
| `duckduckgo.com` | `safe.duckduckgo.com` |
| `yandex.com` | `familysearch.yandex.ru` |

SafeSearch is then enforced at the network level, so it cannot be switched off in
the app, in the site's settings, or by signing out. Gmail, Docs, Drive and the
Play Store are explicitly *not* rewritten.

### 3. Closes the DNS escape routes

Filtering at the DNS layer invites three bypasses, and each is handled:

- **An app using its own encrypted resolver.** Lookups for the known DoH/DoT
  provider hostnames are refused.
- **An app with a hardcoded resolver IP.** The well-known public resolver
  addresses (Google, Cloudflare, Quad9, AdGuard, NextDNS, ControlD and ~40 more)
  are routed into the tunnel and answered by Shield. Their DoH/DoT ports get no
  answer at all, so a browser's "Secure DNS" fails closed and falls back to the
  system resolver, which is Shield.
- **Encrypted Client Hello hiding the hostname.** DNS type 65 (HTTPS/SVCB)
  records are refused for blocked and SafeSearch-pinned hosts, so ECH keys cannot
  be discovered.

If an upstream resolver cannot be reached, Shield **fails closed** — it refuses
the lookup rather than letting the client fall back to its own resolver.

### 4. Hard to turn off

An accessibility service watches the foreground screen. When it sees the
uninstall dialog, this app's info page, VPN settings, accessibility settings,
device-admin settings, Private DNS or developer options, it navigates away,
records a tamper attempt, and texts your partner.

Anything that *weakens* protection goes through one gate (`LockdownManager`):

```
request  ──▶  24-hour cooling-off  ──▶  partner's 6-digit code  ──▶  10-minute window
   │                   │
   └── cancel ─────────┘   (cancelling is always one tap, instantly)
```

The 24 hours survives reboots, force-stops, clearing recents and changing the
system clock backwards. The cooldown can be raised but never lowered. Tightening
protection — reporting a site, blocking an app — bypasses all of this and applies
immediately.

With **device owner** set (see [SETUP.md](SETUP.md)), the system itself enforces
the rest: uninstall blocked, VPN changes forbidden, always-on VPN with lockdown,
safe mode disabled, factory reset disabled, USB debugging disabled. This is the
difference between "inconvenient to bypass" and "actually cannot".

### 5. Accountability partner, with no server involved

Approval uses a standard **TOTP** seed. At setup, your partner stores it in any
authenticator app (Google Authenticator, Aegis, FreeOTP). To unlock, they read
you the current six digits.

This is deliberate. It needs no account, no internet on either phone, and the
seed only ever exists on your partner's device plus inside this phone's hardware
keystore. It is shown **once**, on a screen that blocks screenshots, and the
partner must read back a working code to prove they stored it before setup is
accepted. You cannot retrieve it afterwards and generate your own codes. Each
code works once — a code read out "just to check" cannot be reused.

Alerts are sent as ordinary SMS, directly from the phone's radio to your
partner's number, so nothing passes through a server.

### 6. Stays running

OriginOS is aggressive about killing background apps, so four independent
mechanisms overlap:

- the VPN runs as a foreground service, the hardest thing for Android to kill;
- a `WorkManager` job re-checks every 15 minutes;
- an `AlarmManager` alarm covers the window right after a kill;
- the accessibility service — which Android restarts on its own — re-checks on
  every screen change.

Plus `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, the vendor
quick-boot broadcasts, and `START_STICKY`. Any gap is written to the local log and
shown on the dashboard rather than passing silently.

### 7. Urge support

When something is blocked, a full-screen calming screen appears with a 60-second
countdown that cannot be dismissed, a 4-4-6 breathing prompt, your own written
reason for starting, a short list of physical alternatives, and a one-tap call to
your partner.

### 8. Private progress tracking

Clean-day streak, blocked attempts, tamper attempts, and a 14-day chart, in a
local SQLite database. There is no network code anywhere in its path and no
identifier in the schema. Cloud backup and device transfer are both disabled, so
it cannot leak that way either.

### 9. Self-maintaining blocklist

A daily job downloads from [StevenBlack](https://github.com/StevenBlack/hosts),
[The Blocklist Project](https://github.com/blocklistproject/Lists) and
[OISD NSFW](https://oisd.nl). It is a plain GET of a public text file — no
identifiers, no query string, nothing uploaded. A truncated or failed download is
never allowed to replace a working list.

"Report a site that got through" adds a domain instantly, with no waiting period.

---

## Honest limitations

These are also shown inside the app, on purpose. A blocker that oversells itself
is worse than no blocker, because you stop planning around the holes you still
have.

| Gap | Can it be closed? |
|---|---|
| **Safe mode** disables accessibility services and third-party apps | Only by device owner mode, which disables safe boot |
| **Factory reset / flashing** removes everything | Device owner blocks reset from Settings, but not hardware recovery |
| **A second VPN app** takes the single VPN slot | Only by device owner's always-on + lockdown |
| **An app with its own brand-new encrypted resolver** | Known providers are blocked; an unknown one works until added |
| **Adult content inside apps that serve everything from one domain** (Reddit, X, Telegram) | Not separable by domain — block the whole app instead |
| **Images and video are not inspected** | No on-device image recognition. Covered indirectly by forced SafeSearch |
| **Second user / guest session** | Device owner blocks adding users |
| **Root or an unlocked bootloader** | Nothing can survive this. True of paid blockers too |
| **Another device** — laptop, work phone | Outside this app. Filter at your router as well |
| **Partner alerts need signal and SMS permission** | Without them, alerts are logged locally but delayed |

Two further honest notes:

- **Keyword matching on screen text is conservative.** It needs two or more
  explicit phrases before acting, so a single word in a news article or a chat
  message does not trip it. That means it also misses subtle content. The domain
  layer is the reliable one.
- **Without a partner configured, you can unlock yourself** after the waiting
  period by typing a confirmation sentence. That is much easier to do in a weak
  moment. Set up a partner.

---

## Architecture

```
app/src/main/java/app/guardian/shield/
├── ShieldApp.kt                     Application entry; loads state off the main thread
├── core/
│   ├── KeystoreCrypto.kt            AES-GCM via hardware keystore (partner seed)
│   ├── Prefs.kt                     All settings; cooldown can rise, never fall
│   └── Notifications.kt             Foreground-service notice and alerts
├── data/
│   └── EventStore.kt                Local SQLite log, streak and daily counts
├── dns/
│   ├── DnsMessage.kt                DNS wire format: parse question, build answers
│   ├── DomainMatcher.kt             Pure matching rules (unit tested)
│   ├── Blocklist.kt                 Loading, persistence, user edits
│   ├── SafeSearch.kt                Search-engine pinning
│   └── DnsFilter.kt                 Allow / Block / Rewrite decision
├── vpn/
│   ├── ShieldVpnService.kt          The DNS-only tunnel
│   ├── IpPacket.kt                  IPv4/IPv6 + UDP read and write, checksums
│   ├── UpstreamResolver.kt          Protected sockets to family resolvers
│   ├── Upstreams.kt                 Upstream and captured resolver addresses
│   └── VpnController.kt             Start/stop, gated by the lockdown
├── guard/
│   ├── GuardAccessibilityService.kt On-screen guard and persistence anchor
│   ├── SettingsGuard.kt             Recognises screens that could weaken protection
│   ├── TextScanner.kt               Bounded URL and risk extraction
│   ├── GuardBypass.kt               Narrow grace period for setup screens
│   └── BlockSignal.kt               Decides when to show the urge screen
├── lock/
│   ├── LockdownManager.kt           The gate every weakening action passes
│   └── Totp.kt                      RFC 6238 codes and RFC 4648 Base32
├── admin/
│   ├── ShieldDeviceAdminReceiver.kt Admin registration
│   └── DeviceOwnerManager.kt        Device-owner policies
├── partner/
│   └── PartnerNotifier.kt           Throttled SMS alerts
├── service/
│   ├── Watchdog.kt                  Four overlapping persistence mechanisms
│   ├── WatchdogWorker.kt            Periodic health check and repair
│   ├── BlocklistUpdateWorker.kt     Daily list refresh (download only)
│   └── Receivers.kt                 Boot, alarm and restart receivers
├── ui/                              Dashboard, setup, urge, unlock, stats, limits
└── util/
    └── OemIntents.kt                vivo / OriginOS deep links with fallbacks
```

### Why a DNS-only tunnel

A full tunnel would need a userspace TCP/IP stack, which costs battery, adds
latency to everything, and breaks apps in ways that are hard to debug — and a
blocker that makes the phone annoying gets uninstalled. Routing only DNS plus the
public resolver addresses gives the same coverage for name-based blocking at
almost no cost. The trade-off is that a connection to a raw IP address with no DNS
lookup is not seen; in practice that does not happen for browsing.

---

## Building

Requires JDK 17+ and the Android SDK (API 36, build-tools 36.0.0).

```bash
# Debug build
./gradlew assembleDebug

# Run the test suite (55 tests, no device needed)
./gradlew testDebugUnitTest

# Signed release build
./tools/make-keystore.sh
./gradlew assembleRelease -PKS_PASS=<password> -PKS_KEY_PASS=<password> -PKS_ALIAS=blocker
```

The signing key is intentionally **not** in this repository. If you hold it you
can build a neutered Shield and install it over the real one as an update, which
is the cleanest bypass of everything here. `tools/make-keystore.sh` explains what
to do with it instead.

### Tests

The parts where a bug is silent or dangerous are covered directly:

| Area | Why it is tested |
|---|---|
| `DnsMessage` | A misread question lets the wrong site through; a malformed answer kills all name resolution |
| `IpPacket` | A wrong checksum means the kernel drops every reply and DNS appears dead |
| `DomainMatcher` | Over-matching breaks innocent domains — `sussex.ac.uk`, `analytics.google.com`, `cumbria.gov.uk` are explicit test cases |
| `SafeSearch` | Over-matching would break Gmail and Google Docs |
| `Totp` | Checked against the RFC 6238 reference vectors, plus replay rejection |
| `Base32` | Checked against the RFC 4648 vectors, so authenticator apps accept the seed |

---

## Privacy

- No analytics, no crash reporting, no telemetry, no ads, no accounts.
- The only outbound request is the blocklist download: a public text file, fetched
  with no identifiers and no query string.
- Browsing history is never stored. The event log records a blocked hostname so
  you can see what was stopped, nothing more, and it is pruned after 400 days.
- Screen contents read by the accessibility service are examined in memory and
  discarded. They are never written to disk or transmitted.
- Cloud backup and device-to-device transfer are disabled for this app.
- Partner alerts are SMS sent directly from the phone. They contain one sentence
  about what happened, and only if you enable them.

## Licence

MIT. See [LICENSE](LICENSE).
