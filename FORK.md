# GooseFlix: what this fork changes

GooseFlix is a fork of [Wholphin](https://github.com/damontecres/Wholphin), the Android TV
client for Jellyfin, built for one Jellyfin server (jelly.grit.bot). This file is the record of
what the fork adds or changes and why. Keep it current: add a line whenever a fork feature lands.

Tracking for everything else lives in the **GooseFlix** repo's issues
([wibuf/GooseFlix](https://github.com/wibuf/GooseFlix/issues)), filed through GitPilot.
App issues there are titled "App: ...".

## Where the work lives

- **Branch `gooseflix` is the fork's main branch.** The fork's `main` is still upstream's code
  (where the fork branched, `45c1ceca`). Sessions work on their own `claude/...` branch, started from
  `gooseflix`, and ship by merging into it. (Until 2026-10-14 the work lived on a session branch,
  `claude/tender-johnson-wqvwci`, which `gooseflix` continues.)
- **Every push to `gooseflix` or a `claude/**` branch** is built by `.github/workflows/fork-build.yml`
  into a signed release and published as a rolling prerelease named after the branch. The
  `gooseflix` release title carries the version, eg `v1.0.9-63-g…`.
- **Sideloaded TVs** update from Scuffed: Scuffed reads the **`gooseflix`** release (its `fork_tag`
  setting), installs it on the Shield over ADB, and serves `/update` and `/get` for the app's
  self-updater and the Downloader install at add.beefmc.com/tv. A session branch's release is a
  test build: point Scuffed at it only to try something on the Shield, and back afterwards, since
  the same setting feeds every TV.
- **Google Play** (`bot.grit.gooseflix`): the same release carries `app-appstore-release.aab`.
  Upload it by hand in Play Console (Closed testing → Alpha → Create new release).

## Build

```
./gradlew -PforkBuild :app:assembleDefaultRelease        # sideload build
./gradlew -PforkBuild :app:bundleAppstoreRelease         # Play bundle (run scripts/fetch-libretro-cores.sh first)
./gradlew -PforkBuild :app:testDefaultDebugUnitTest --tests '*SomeTest'
```

`-PforkBuild` turns on `BuildConfig.GOOSEFLIX`. Without it the code builds as plain Wholphin.

Flavours:
- `default`: sideload, app id `...wholphin.fork`, self-updating from Scuffed, game cores downloaded on demand.
- `appstore`: Play, app id `bot.grit.gooseflix`, leanback, minSdk 24 (Play's automatic protection), no self-update, game cores bundled from `app/src/appstore/libretro-cores.txt`.
- `firetv`: as upstream.

The version code is tags × 10000 + commits since the tag, so every push is a higher version.

## Conventions

- **Fork code goes in its own files** where it can, marked `Fork-only` in a comment. Changes
  inside upstream files are small and gated on `BuildConfig.GOOSEFLIX`. That keeps merges from
  upstream manageable.
- **Proto fields the fork adds are numbered 500+** (`WholphinDataStore.proto`), and enum entries
  are added at the end. A number upstream later claims would silently turn one setting into
  another.
- **Defaults for a fresh install** are in `preferences/GooseFlixDefaults.kt`. They don't change
  what an existing install already has.

## What the fork adds

Newest first within each area. The numbers are GooseFlix issues unless they say otherwise.

### Identity and setup
- Rebranded as GooseFlix: name, launcher icons, TV banner, sign-in logo. Art is in `app/src/fork/art`.
- Built-in server: no server address to type. The sign-in screen has a username/password form and a QR code for signing in from a phone (`ui/setup/home/`).
- Self-updater from Scuffed's feed: silent install on Android 12+, one press below that (`services/update/`).
- Google Play build: `bot.grit.gooseflix`, game cores bundled, minSdk 24, signing key exported to Play App Signing (`.github/workflows/play-signing-key.yml`, one-off).

### Home page
- Quick pills: a row of shortcut buttons at the top (Guide, favourite channels) per user, set in Scuffed's admin page (`services/QuickPillsService.kt`, `ui/main/QuickPills.kt`).
- Live game pills for the Patriots, Celtics, Bruins and Red Sox, first in the pill row while a game or its pregame is on. They come from Scuffed's `/api/gooseflix/live` (#1427, #1422).
- Rows for libraries added after the user saved their home settings, placed in library order (`services/NewHomeRows.kt`).
- Recently Added shows a single new episode as its show (card, header and click), with a "New episode · S1 E2 · Name" line in the header. Setting: Interface → "Show the series for new episodes", on by default (#1501; `services/NewEpisodeSeries.kt`).
- Defaults: Continue Watching and Next Up as separate rows, no Watch Live row, 50 items per row.
- Holding up or down on the home page moves one row every 75 ms, the fastest the Shield held without focus getting stuck; every repeat (no limit) gets stuck (`ui/main/HoldThrottle.kt`; upstream damontecres/Wholphin#2034). With verbose logging on, the `GooseFlixNav` logcat tag logs each key, row/card focus change and the list's scroll position (`ui/main/HomeNavLog.kt`).
- The previous user's rows are cleared when switching user.

### Video games
- Native retro game playback from the Moonbase Jellyfin plugin: libretro host in `app/src/main/cpp`, games UI in `games/`.
- Controllers mapped per port; artwork fetched through a retrying disk cache; a home row and header for each games library.
- Game libraries only show for users who can see that library in Jellyfin. The plugin itself doesn't check permissions.

### Ratings and details
- IMDb, MyAnimeList (anime), Rotten Tomatoes (critics and Popcornmeter) and TMDB ratings from the MDBList Ratings plugin, with provider logos (#1862; `services/MdbListRatingsService.kt`, `ui/components/MdbListRatingsRow.kt`).
- The server's own critic and community scores are hidden by default.
- Details pages show every genre on one line (upstream stops at four and adds "+N"), and drop "Ends at" from the details line so the ratings fit; the player still shows it (#1451).

### Requests (Seerr)
- Every signed-in user is connected to Seerr automatically through Scuffed's proxy, with their Jellyfin token in place of an API key; accounts the proxy blocks (eg `playreview`) get no Seerr, so no Discover (#1516; `services/GooseFlixSeerr.kt`, Scuffed `collector/gooseflix_seerr.py`). `SEERR_PROXY_URL` is `https://add.beefmc.com/api/gooseflix/seerr`; `-PforkSeerrProxyUrl=` (empty) turns it off. The proxy confirms Seerr acts as each user before forwarding anything for them, and refuses if not.

### Search
- Titles that exist in more than one library show the library name in place of the year (`ui/search/SearchLibraries.kt`).

### Playback and live TV
- Live TV recovery: a stream that errors or hangs reconnects on its own, and a channel that won't start falls back to the server transcoding it (`LiveTvRetryPolicy.kt`, `LiveTvTranscodeMemory.kt`).
- Passout protection for live TV: "Still watching?" after a set time with no button pressed, then stop. Steps are 30 minutes, and it can be set for live TV, shows/movies or both (`LiveTvPassout.kt`).
- Guide: any programme's channel can be watched, whatever time the programme is.
- Guide moves like a cable box: up/down go to what's on at the time you're looking at and only scroll vertically; left/right set that time and only scroll sideways (`ui/detail/livetv/GuideFocus.kt`). A move only finishes once the target programme really has focus, so the highlight and the header at the top never disagree.
- Debug overlay: stall counter, buffer ahead, distance behind live, reconnects (`StallTracker.kt`).
- Playback timeline log (logcat tag `GooseFlixPlay`) for diagnosing start-up problems (`PlaybackTimeline.kt`).
- Subtitle keyword priorities: prefer "full"/"dialogue" tracks and avoid signs/songs/forced ones, tuned on the anime library.
- MPV: dialogue boost and loud-scene compression options.
- MPV leaves 10-bit H.264 (Hi10P) for the server to transcode on devices without a hardware decoder for it, as ExoPlayer already did, instead of direct-playing it in software and stuttering (#1512; `util/mpv/MpvDeviceProfile.kt`).
- Defaults: OLED black theme, pause with one press of the centre button.

## Upstream

- Synced with upstream **v1.0.9** on 2026-10-12, a merge on the sync branch. The fork first grew from `45c1ceca`, just after v1.0.7. To sync again: fetch upstream's tags, merge the release tag into a `claude/...` branch, resolve conflicts (keep fork behaviour, take upstream's structure), run the tests, and install that branch's build on a Shield before merging it into the working branch.
- Version codes count fork commits since `v1.0.7` (`FORK_BASE_TAG` in `app/build.gradle.kts`), not since the latest tag. Merging a new upstream tag resets the latter, and Android refuses to install a lower code.
- Issues we've filed upstream: damontecres/Wholphin#2034 (home page focus stuck), fixed here.
