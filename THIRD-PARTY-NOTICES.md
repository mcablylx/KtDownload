# Third-Party Notices

This file lists third-party components used by **KtDownload** (package name `KtDownload`),
their version, license, and where to obtain the
corresponding source — as required by the licenses of those components.

The components below are integrated in three different ways, with three different
license consequences:

| Component | Integration | Effect on KtDownload's own license |
| --- | --- | --- |
| FFmpeg | external process (not linked) | none (mere aggregation) |
| VLC / libvlc | dynamically linked via JNA | LGPL-2.1 §6 obligations (replaceable binary) |
| JCEF / Chromium | dynamically loaded native binaries, in-app browser | Apache-2.0 / BSD-style notices (see below) |
| **YunX-Desktop resolver** | **source compiled into the app** | **AGPL-3.0 covers the whole program — see below** |

> ✅ Resolved in the GitHub-bound `KtDownload` export: the root `LICENSE` is now
> **AGPL-3.0**, covering the whole combined work including the YunX-derived resolver.
> The complete Corresponding Source is published in this repository, satisfying AGPL-3.0 §13.
> Upstream attribution is preserved in `third-party/yunx-desktop/SOURCE.txt` and in the app's
> "Open Source Licenses & Attribution" dialog.

---

## FFmpeg

| Field | Value |
| --- | --- |
| Component | FFmpeg |
| Version | `n8.1.3-24-g92611900bb-20261024` (based on FFmpeg 8.1 "Hoare") |
| License | GNU Lesser General Public License v3.0 or later (LGPL-3.0-or-later) |
| Purpose | HLS (m3u8) remux — converting downloaded MPEG-TS segments to MP4 |
| Integration | External process via `ProcessBuilder` — **not linked** |
| Source | https://ffmpeg.org/releases/ffmpeg-8.1.3.tar.xz |
| Build provider | BtbN/FFmpeg-Builds (autobuild) — **Windows only**; macOS is built from source, see below |
| Build tag | `autobuild-2026-10-24-13-02` |
| Build asset | `ffmpeg-n8.1.3-24-g92611900bb-win64-lgpl-8.1.zip` |
| Fetch script | [`scripts/fetch-ffmpeg.ps1`](scripts/fetch-ffmpeg.ps1) (pinned + SHA-256 verified) |
| Build configuration | See [`third-party/ffmpeg/SOURCE.txt`](third-party/ffmpeg/SOURCE.txt) |
| SHA-256 of `ffmpeg.exe` | `5a11d3d34496b6d4b3cedf0230739ddc3d2710c0ac58637ae134df801238d503` |

### Why this is not a derivative work

KtDownload spawns the bundled `ffmpeg` / `ffmpeg.exe` as a separate process (subprocess invocation),
which the FSF defines as "mere aggregation". No FFmpeg library (`libavcodec`,
`libavformat`, etc.) is linked into KtDownload via JNI, JNA, or any other
mechanism. KtDownload therefore retains its own license and is **not** subject
to LGPL copyleft.

### LGPL compliance checklist

- [x] LGPL 3.0 full text shipped alongside the binary — see
      [`resources/common/ffmpeg/LICENSE.txt`](resources/common/ffmpeg/LICENSE.txt)
      (the upstream 2.1 text is also shipped as `LICENSE.LGPL-2.1.txt`)
- [x] Corresponding source available — URLs in [`SOURCE.txt`](third-party/ffmpeg/SOURCE.txt)
- [x] Users can replace the binary — `ffmpeg.exe` is a standalone file under
      `<install-dir>\resources\ffmpeg\`, not integrity-checked or encrypted
- [x] Application discloses use of FFmpeg — this file + the in-app
      "Settings → Open Source Licenses & Attribution" dialog (see the section below)
- [x] Binary verified as LGPL build — `configuration:` contains `--enable-version3`
      and neither `--enable-gpl` nor `--enable-nonfree`

### Used FFmpeg capabilities

Only **remux** (container conversion, `-c copy`) is used:

- `-c copy` — copy audio/video streams without re-encoding
- `-bsf:a aac_adtstoasc` — strip ADTS headers from AAC when moving TS → MP4
- `-movflags +faststart` — relocate `moov` box to the front for fast preview

No GPL-only encoders (`libx264`, `libx265`, `libxvid`) are used, which is why an
LGPL build is sufficient.

### Replacing FFmpeg

Users may replace the bundled `ffmpeg.exe` with any **LGPL** build of FFmpeg.
Verify the replacement is LGPL before redistributing:

```bash
ffmpeg -version
# The "configuration:" line must contain --enable-version3 and must NOT contain
# --enable-gpl or --enable-nonfree.
```

### macOS build

The macOS app bundles a minimal FFmpeg built from the official release tarball:
BtbN publishes no macOS assets, and the prebuilt macOS LGPL binaries we checked
omit the `concat` demuxer that `FfmpegMuxer` needs for TS playlists.

| Field | Value |
| --- | --- |
| Version | 8.1.3 |
| License | LGPL-3.0-or-later (`--enable-version3`, no `--enable-gpl` / `--enable-nonfree`) |
| Source | https://ffmpeg.org/releases/ffmpeg-8.1.3.tar.xz |
| SHA-256 of source tarball | `7138d28c96d9d3e3af4ee3d8cad72741f8ffb40da90c1112235dea3ecd3178a3` |
| Build script | [`scripts/fetch-ffmpeg-macos.sh`](scripts/fetch-ffmpeg-macos.sh) |
| Build configuration | `--disable-everything` + `file`/`pipe` protocols, `mpegts`/`mov`/`concat` demuxers, `mp4` muxer, `h264`/`hevc`/`aac` parsers, `aac` decoder (ADTS probe), `aac_adtstoasc` bsf |
| Output | `resources/macos/ffmpeg/ffmpeg` (+x) and `SOURCE.txt` |

The binary is built natively on the machine that packages the app (x86_64 on Intel,
arm64 on Apple Silicon) and is ad-hoc signed together with the app bundle.
Building, packaging, signing and on-device verification require macOS; the Intel
x86_64 chain (including a GUI M3U8 → MP4 download) was verified on 2026-10-25 in the
original development repository.

---

## VLC / libvlc

| Field | Value |
| --- | --- |
| Component | VLC media player — `libvlc` / `libvlccore` + plugin modules |
| Version | `3.0.21` (VLC 3.0.x series) |
| License | GNU Lesser General Public License v2.1 or later (LGPL-2.1-or-later) |
| Purpose | In-app playback of `m3u8` (HLS) and `mp4` |
| Integration | **Dynamically linked** through JNA (`vlcj` 4.11.0) — see below |
| Source | https://get.videolan.org/vlc/3.0.21/ |
| Prepared by | Gradle plugin `ir.mahozad.vlc-setup` 0.1.0 (`./gradlew :composeApp:vlcSetup`) |
| Location in package | `<app>/resources/` — `libvlc.dll` + `libvlccore.dll` + `plugins/` (Windows); `libvlc.dylib` + `plugins/` (macOS) |
| License files | `<app>/resources/vlc/COPYING.LIB.txt` (LGPL-2.1) + `COPYING.txt` (GPL-2.0, upstream) + `SOURCE.txt` — tracked in [`resources/common/vlc/`](resources/common/vlc/) |
| Modifications | **None.** The binaries are shipped unmodified; only unused plugin modules are deleted (see below) |

### This one *is* linked — what that means

Unlike FFmpeg (a separate process), libvlc is loaded **into the KtDownload process** via JNA
and used through its C API. LGPL-2.1 §6 therefore applies, and KtDownload meets it by:

- shipping the LGPL-2.1 full text with the package — `<app>/resources/vlc/COPYING.LIB.txt`,
  merged from [`resources/common/vlc/`](resources/common/vlc/) at packaging time,
- **dynamic linking only** — `libvlc` stays a separate shared library in `app/resources/`,
  so a user can replace it with their own build of the same version,
- shipping **unmodified** binaries and stating that no changes were made,
- not hiding the interface: the version, the source location, and the build script are
  all listed in this table.

The Java-side binding (`vlcj`) is GPL-3.0 with a linking exception that explicitly permits
use in non-GPL applications; see <https://github.com/caprica/vlcj>.

### Deleted plugin modules (size only)

`libvlc` finds its modules at runtime, so a module that is absent simply means that
capability is unavailable. `pruneBundledVlc` removes the ones a player-only embed never
touches (≈14 MB): `plugins/gui` (Qt / skins2 interfaces), `plugins/visualization`,
`plugins/stream_out`, `plugins/access_output`, `plugins/services_discovery`,
plus the browser plugins `axvlc.dll` / `npvlc.dll`. Codec, demux, access and video-output
modules are **never** pruned — removing those breaks playback (HLS needs
`plugins/demux/libadaptive_plugin.dll` and `plugins/access/libhttp_plugin.dll`).

### Replacing libvlc

The bundled copy is located at runtime by `VlcLocator` in this order:

1. `-Dktdownload.vlc=<dir>` (system property),
2. `compose.application.resources.dir` (the packaged `app/resources`),
3. `resources/<platform>/` in a source checkout (development),
4. a system-wide VLC installation.

Dropping a self-built `libvlc` into the directory named by (1) is enough to run against
a different build without touching the packaged files.

---

## JCEF / Chromium (in-app netdisk web login)

The cloud-drive login screen embeds a Chromium browser **inside** the application
(no external browser is launched). This follows the same approach as
YunX-Desktop: `SwingPanel` hosts a `CefBrowser` from JCEF, and the wrapper
[jcefmaven](https://github.com/jcefmaven/jcefmaven) loads the matching native
bundle from the `jcef-natives-<platform>` jar shipped on the classpath
(`windows-amd64`, `macosx-amd64`, `macosx-arm64`, `linux-amd64` — the build
picks one for the host platform/arch). No Chromium bundle is downloaded at
runtime: a missing kernel is installed only on an explicit user action.

| Field | Value |
| --- | --- |
| Components | jcefmaven `132.3.1`, jcef-api / JCEF build `jcef-1770317+cef-132.3.1+g144febe`, Chromium `132.0.6834.83` |
| Licenses | jcefmaven: **Apache-2.0**; JCEF / Chromium: **BSD-style** licenses (see the `LICENSE` / `NOTICE` files in the upstream distributions) |
| Supporting library | Apache Commons Compress `1.27.1` (Apache-2.0), Gson `2.11.0` (Apache-2.0, transitive) |
| Purpose | In-app web login for netdisk accounts; login state is read from the browser's Cookie store |
| Integration | Normal jar + native binaries loaded at runtime; **not** linked into KtDownload's own source |
| Sources | https://github.com/jcefmaven/jcefmaven · https://bitbucket.org/chromiumembedded/cef · https://chromium.googlesource.com/chromium/src |
| Declared in | `gradle/libs.versions.toml` (`jcefmaven`, `jcefNatives`, `commonsCompress`) |
| Notes | The natives jar is 118–138 MB depending on platform (~125 MB on macOS Intel) and is included in the distribution: first use unpacks it to `~/.mclydownload/jcef/bundle` (≈7 s measured on macOS 12 / Intel). `java.prefs`, `jdk.unsupported` and `jdk.unsupported.desktop` were added to the jlink module list for this integration. |

> The JCEF login code itself (`ui/components/CloudDriveLoginScreen.kt`,
> `engine/clouddrive/browser/*`, `engine/clouddrive/CloudDriveLoginConfig.kt`) is
> **adapted from YunX-Desktop** and is therefore covered by the AGPL-3.0 section
> below, even though JCEF's own licenses are permissive.

---

## YunX-Desktop cloud-drive resolver (AGPL-3.0)

| Field | Value |
| --- | --- |
| Component | YunX-Desktop — share-link parser + netdisk Web API resolvers |
| Version | `main` @ `00a030f7` (tag **v1.3.4**, 2026-11-13); corrected 2026-11-14 — the ported file headers previously said "release v1.3.3 era" |
| License | **GNU Affero General Public License v3.0 (AGPL-3.0)** — strong network copyleft |
| Purpose | "云盘" tool: parse a share link → browse the file tree → obtain direct download URLs |
| Integration | **Source-level**: 56 upstream `.kt` files (13,670 lines — the original 47 / 12,479, plus `StarSeaApi.kt` + `StarSeaConstants.kt` in batch 1 and 7 `GitHub*.kt` / 1,037 lines in batch 2, both on 2026-11-14) compiled into KtDownload, relocated into `top.mcly99.dl.data.*`, plus 4 compatibility shims. Most files are package-rename-only, but **20 contain downstream changes** — the Baidu no-save enhancement, Xunlei guest captcha, the StarSea `Log`→`AppLogger` shim, temp-dir renames, the contract flag, the GitHub User-Agent, the adapted GitHub mirror/token stores, and the 2026-11-15 P1 `HttpClients.kt` proxy/HTTP/2 rewiring plus the P2 `GitHubMirror.kt` auto/fallback change are all recorded in `SOURCE.txt` (§Downstream changes, N-01…N-15). **P3 (2026-11-15) added no upstream file and changed no AGPL-3.0 file**: the multi-account pool and C1 rotation are KtDownload-original work recorded as N-12. No server-side proxy. |
| Source | <https://github.com/tidain/YunX-Desktop> — snapshot archive `YunX-Desktop-main-1.3.4.zip`, SHA-256 `44c4678614042df59ca0337f344fe90ba5336aca70787f7ccf10dd22b974eb7d` (previous v1.3.3 snapshot: `878bbc83d0ac6017b921cc9c0dafa865c031682eba2256b34d455e83e65bcbd9`) |
| Full license text | [`third-party/yunx-desktop/LICENSE.txt`](third-party/yunx-desktop/LICENSE.txt), also shipped in-app as [`resources/common/yunx-desktop/LICENSE.txt`](resources/common/yunx-desktop/LICENSE.txt) |
| File list & modifications | [`third-party/yunx-desktop/SOURCE.txt`](third-party/yunx-desktop/SOURCE.txt) |
| Build dependencies pulled in | OkHttp 4.12.0 (Apache-2.0), org.json 20240303 (JSON License), okio (Apache-2.0, transitive) |

### What was ported

`data/network/` (link recognition, OkHttp client, 10 platform API clients + crypto/constants,
the StarSea Chinese-command client added in batch 1, and the 7 `GitHub*.kt` files
added in batch 2: REST client, models, link parser, response/commit-date caches,
mirror speed probe and encrypted token store),
`data/network/model/` (share/file/download-link models), `data/repository/` (one resolver
per platform, unified `ShareResolveRepository` contract). Not ported: the upstream Compose UI,
SQLite account store, download engine, player, browser extension, and the 1,329-line
`GitHubMarkdownImageTransformer.kt` (GFM README image rendering — no downstream
Markdown renderer exists). The GitHub navigation was **reimplemented**, not copied:
KtDownload's own `engine/clouddrive/GitHubBrowser.kt` projects repo trees, Releases
and account repo lists onto the existing `ShareFile` list. The JCEF login flow is
**adapted, not copied verbatim** (no BrowserCookieImporter, no upstream SQLite
account DB, no separate login window); credentials are encrypted at rest in a
local properties file. As of 2026-11-15 (P3) KtDownload has its **own**
AES-256-GCM multi-account pool (`engine/clouddrive/CloudAccountPool.kt`), so one
platform can hold several accounts with a primary flag, remarks and per-file C1
rotation — still without introducing `sqlite-jdbc` or copying the upstream
`*_account` tables. See `SOURCE.txt` N-12.

### Chinese share commands (third-party services)

Batch 1 (2026-11-14) implements D-19 / D-20: text that the local `ShareLinkParser`
does not recognise is first offered to Xunlei's `api-shoulei-ssl.xunlei.com` jump
endpoint, then to the StarSea resolver at `https://starsea-api.qwq.pics/api/resolve`
(ported `StarSeaApi.kt` / `StarSeaConstants.kt`, AGPL-3.0). **The original command
text is sent to those third-party services.** The app logs only its length and the
failure reason, never the text itself; KtDownload further requires a CJK character
before trying StarSea, so plain English text and any text containing a URL are never
sent out. The StarSea API key is hard-coded upstream and therefore public; the
service is rate-limited (HTTP 429) and its key or endpoint may be rotated or
discontinued at any time. Using a Chinese share command means accepting this
third-party transmission; ordinary share links never touch these services.

### GitHub browsing (third-party API and optional mirror proxies)

Batch 2 (2026-11-14, D-18) adds GitHub repository / Releases / direct-link
browsing. Resolving a GitHub URL sends the repository owner/name, release tags and
file paths to `api.github.com`; downloading uses `raw.githubusercontent.com`,
`codeload.github.com` or the Release asset URL. An optional Personal Access Token
(configured on the GitHub card, AES-256-GCM encrypted at rest) is sent as
`Authorization: Bearer` **only to `api.github.com`** to raise the anonymous rate
limit from 60/hour to 5,000/hour. It is never logged, never shown in full in the
UI, and never sent to any netdisk platform. GitHub is anonymous by default.

GitHub downloads use **"fastest automatically" by default** (since P2,
2026-11-15). At startup KtDownload sends a 256 KB Range probe to each of the
five `gh-proxy.org` presets (6-hour TTL, 10 minutes after a failed round) and
uses the fastest mirror that actually passed. **A mirror prefix is only added
after a successful probe**: probing not started / in progress / all five failed
means the download goes to GitHub directly. The cloud-drive page shows the
currently active mirror (or "direct") at all times. Choosing **"Direct"**
persists the choice, disables probing entirely and always connects straight to
GitHub; choosing one of the five presets fixes that mirror explicitly.
Only public download URLs (`raw.githubusercontent.com` / `codeload` / release
assets) can carry a mirror prefix — at that point the full GitHub URL is
disclosed to that third-party proxy. `api.github.com` metadata requests are
never routed through a third party. See `SOURCE.txt` (N-07, N-11).

### Local link history and bookmarks (no upstream code)

Batch 0 (2026-11-14, D-29) keeps the share links you resolved successfully and any
links you bookmark in `~/.mclydownload/cloud-links.json` (POSIX `0600`, directory
`0700`, atomic replace). Share text frequently contains passcodes, so **this data
never leaves the machine** — it is not uploaded, synced, logged or sent to any
third-party service. Chinese share commands are stored as the *resolved real URL*,
so reopening an entry does not contact the command services described above.
Clearing all history and bookmarks deletes the file. No upstream bookmark,
history or video-preview source was ported for this feature; see `SOURCE.txt`
(N-08) for the deliberately excluded upstream files. Batch 0 added **no new
AGPL-3.0 files** — the ported count stays at 56.

### C2 / P4 (2026-11-15): capability work, not new upstream source

C2 (single-file multi-source chunk download) and P4 (one self-authored "My Drive"
page for 10 cloud drives, with folder create / rename / move / delete / share /
download / save-share) **added no new AGPL-3.0 file and modified no ported file**.
The count stays at 56 files / 13,670 lines. The new adapters only *call* the
already-ported `data/network/*Api.kt` classes; `CloudDriveService.repository()`
was widened from `private` to `internal`, and that file is KtDownload-original
glue rather than upstream source. Both batches are recorded as N-13 and N-14 in
`SOURCE.txt`. The P4 page deliberately omits upload and recycle-bin actions:
the ported APIs have no reliable implementation and the upstream UI advertises
a "restore from recycle bin" action it cannot actually perform. GitHub is
excluded from "My Drive" because it is a code host, not a personal drive.

### v1.3.4 release-notes audit (2026-11-15): N-15, still no new upstream source

`git ls-remote` + `git fetch` confirm GitHub `origin/main` is unchanged at
`00a030f778a65840e9be65baecb15497a94b04ec` (zero new commits; latest release is
still v1.3.4). The batch therefore audits the v1.3.4 release notes for fixes
that also apply downstream:

- Non-ASCII / control-character HTTP header values are now percent-encoded
  before any request is built (self-authored `HttpHeaderValues.kt`); structured
  JSON credentials are explicitly spared.
- A `<name>.chunks.plan` signature prevents resuming fixed-size chunks after
  the thread count or remote length changed, which would otherwise merge
  silently misaligned bytes.
- "Shut down when downloads finish" is implemented as an in-app 60-second
  cancellable countdown with a one-shot OS shutdown, default OFF.
- `session_start` / `session_end` markers, read-only `hs_err_pid*.log`
  harvesting, and no native error dialog during shutdown were added.

**No AGPL-3.0 file was added or modified, and the root `LICENSE` was untouched at that time**;
the ported count stays at 56 files / 13,670 lines. `CloudDriveService.kt` is
KtDownload-original glue, not upstream source. Full per-item analysis and the
the provenance entry is N-15 in `SOURCE.txt`. The per-item analysis remains in the
original development repository.

### P2 capability batch (2026-11-15): N-16, still no new upstream source

The remaining P2 items were closed with self-authored code only:

- Passphrase-encrypted account backup / restore (PBKDF2-SHA256 → AES-256-GCM,
  AAD-bound header, merge-on-import that never overwrites).
- One-click diagnostics bundle plus copy-only crash-log archiving; credentials
  are never packaged and the home directory is redacted.
- Share-expiry tiers are now driven by a per-platform capability field instead
  of a hardcoded two-item list, including 115's own 101..105 scale.
- Two real defects were fixed on the way: the account store was instantiated
  twice (full-snapshot writes made the two instances overwrite each other and
  lose accounts), and "My Drive" never followed the 139/123 list cursor, so
  everything past page 1 was invisible.
- Native Windows toast progress notifications are deliberately **not** shipped;
  they need WinRT / JNA and could not be verified on the build machine.

**No AGPL-3.0 file was added or modified, and the root `LICENSE` was untouched at that time**;
the ported count stays at 56 files / 13,670 lines. The provenance entry is N-16
in `SOURCE.txt`.

### Dangerous-path guard (2026-11-15): N-17, reviewed upstream's F:-drive incident

Upstream v1.3.3 could wipe an entire drive: the storage directory was
configurable, switching it migrated data and **cleaned up the old location at
startup**, and nothing refused a drive root, an ancestor of the data directory,
or a target containing user files. v1.3.4 added exactly those refusals.

This project has none of the three preconditions — no configurable storage or
cache directory, no directory migration, no startup cleanup of an old data dir —
so the incident cannot happen here. That immunity comes from *missing features*,
not from a guarded delete path, so a new `utils/SafeDelete.kt` now owns every
recursive delete and refuses drive roots, the home directory, ancestors of the
app data directory, and targets above 50,000 entries. **Still no AGPL-3.0 file
touched**; the provenance entry is N-17 in `SOURCE.txt`.

### What this means for redistribution

Unlike FFmpeg (a subprocess) and libvlc (a replaceable shared library), this code is
**compiled into the same program**. AGPL-3.0 §5/§13 therefore applies to the combined
work. Each batch that ports more upstream source widens this obligation: batch 1
(2026-11-14) added 2 AGPL-3.0 files / 154 upstream lines for Chinese share commands,
and batch 2 (2026-11-14) added 7 more / 1,037 upstream lines for the GitHub
subsystem — 9 new AGPL-3.0 files in total across both batches:

- [x] AGPL-3.0 full text shipped with the source tree and with the packaged app
- [x] Every ported file carries an upstream/AGPL provenance header
- [x] Upstream source location, exact snapshot hash and the list of modifications are recorded
- [x] **Resolved:** the whole application is relicensed under AGPL-3.0 (root `LICENSE`) and
      its complete Corresponding Source is published in this repository; no process
      isolation is needed.
- [x] Upstream attribution in the app's "Open Source Licenses" screen (Settings) —
      implemented 2026-11-14, see the section below

### In-app attribution (Settings → Open Source Licenses)

A repository-root notice is invisible to end users, so AGPL-3.0 §5(a)/§13 attribution is
also rendered **inside the application**. `Settings → Open Source Licenses & Attribution`
opens `ui/components/OpenSourceLicensesDialog.kt`, backed by the bilingual data table in
`theme/LicenseStrings.kt` (KtDownload-original code, no upstream source):

- all four third-party components (FFmpeg LGPL-3.0-or-later, VLC/libvlc LGPL-2.1,
  JCEF/Chromium Apache-2.0 / BSD-style, **YunX-Desktop AGPL-3.0**) with license,
  integration mode, upstream URL and the path to the bundled full license text;
- the AGPL entry names the upstream project, snapshot `v1.3.4` @ `00a030f7`, the 56
  ported files / 13,670 lines, the modification log (`SOURCE.txt`) and `LICENSE.txt`;
- a prominent notice before the list states that the whole application is AGPL-3.0,
  that the complete Corresponding Source is available in this repository, and that
  upstream YunX-Desktop attribution is preserved;
- "Open license folder" opens the parent of the bundled `ffmpeg` directory, which also
  holds `vlc/` and `yunx-desktop/` license texts (both source-tree and packaged layouts).

`jvmTest/theme/LicenseStringsTest.kt` locks the required attribution markers, the
bilingual component parity and the existence of the three bundled license texts.
**This closes the attribution TODO; the relicense decision was completed in the
GitHub-bound export by changing the root `LICENSE` to AGPL-3.0.**

### Upstream disclaimer, carried forward

Downstream users of this branch should be told the same things the upstream project
tells its users. Full upstream text: <https://github.com/tidain/YunX-Desktop>.
A Chinese summary is kept in the maintainer's local review notes, which live outside
version control and are **not** distributed with this repository.

1. **Non-commercial, study/research only.** No official support or warranty. The project is
   free and open source; **any paid copy, resale or "licensed" build is a scam** — ask for a
   refund and report it.
2. **No content is hosted or distributed.** The app only resolves share links supplied by the
   user. Copyright stays with the rights holder; delete downloaded content within 24 hours.
3. **No affiliation.** KtDownload / YunX are not connected to Quark, UC, Xunlei, Baidu, 139,
   123, 115, GuangYa or Lanzou. Resolvers are based on reverse-engineered public interfaces,
   may stop working whenever a platform changes, and third-party clients can trigger account
   risk control or bans (**especially Baidu**). Consequences are the user's own.
4. **Compliance.** Users must follow local law and each platform's terms; no infringing or
   prohibited content. Liability rests with the user.
5. **No warranty.** Provided "as is", without express or implied warranties; authors and
   contributors are not liable for data loss, account loss or device problems.
6. **Acceptance.** Installing or using the software means these terms have been read and
   accepted; otherwise stop using it and delete it.

---

## Update policy

When upgrading FFmpeg:

1. **Windows** — download a pinned autobuild from
   <https://github.com/BtbN/FFmpeg-Builds/releases>.
2. Record the new asset name, build tag, and `ffmpeg.exe` SHA-256 in
   [`third-party/ffmpeg/SOURCE.txt`](third-party/ffmpeg/SOURCE.txt).
3. Verify the `configuration:` line contains `--enable-version3` and has no
   `--enable-gpl` / `--enable-nonfree`.
4. Update the tag / asset / SHA-256 constants in
   [`scripts/fetch-ffmpeg.ps1`](scripts/fetch-ffmpeg.ps1) and run it to refresh
   `resources/windows/ffmpeg/ffmpeg.exe`.
5. **macOS** — bump `FFMPEG_VERSION` + `EXPECTED_SHA256` in
   [`scripts/fetch-ffmpeg-macos.sh`](scripts/fetch-ffmpeg-macos.sh) and rebuild on a Mac.
6. Update the version + SHA-256 in this file.
7. Bump `appVersion` and add a `CHANGELOG.md` entry.

When upgrading VLC / libvlc:

1. Change `vlcBundleVersion` in [`composeApp/build.gradle.kts`](composeApp/build.gradle.kts)
   (and `vlcVersion` in the `vlcSetup {}` block, which reads from it).
2. Re-run `./gradlew :composeApp:vlcSetup`; it refreshes `resources/<platform>/`.
   On networks that cannot reach `get.videolan.org` / GitHub, pre-seed
   `~/.gradle/vlcSetup/vlc-<version>.zip` and `upx-<version>.zip` — the download tasks
   are skipped when those files exist.
3. Re-verify HLS playback (the `KTDL_VLC_SMOKE=1` smoke test covers mp4 + HLS + referer).
4. Update the version + archive SHA-256 in
   [`resources/common/vlc/SOURCE.txt`](resources/common/vlc/SOURCE.txt), the version row in
   this file, and the size figures in `README.md`. Refresh `COPYING.txt` from the new
   archive if the upstream license text changed.
