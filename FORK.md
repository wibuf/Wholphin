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
  test build: point Scuffed at it to try it on the Shield. Only the `gooseflix` release is
  published to friends' TVs on its own (Scuffed `collector/wholphin_feed.py`, since 2026-11-01),
  so a test build stays on the Shield; point Scuffed back at `gooseflix` once it ships.
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
- The image loader sends the Jellyfin token only to the Jellyfin server. Upstream's sent it with every image request, so TMDB images (pill art, Discover) received it too.
- Google Play build: `bot.grit.gooseflix`, game cores bundled, minSdk 24, signing key exported to Play App Signing (`.github/workflows/play-signing-key.yml`, one-off).

### Home page
- Quick pills: a row of shortcut buttons at the top (Guide, favourite channels) per user, set in Scuffed's admin page (`services/QuickPillsService.kt`, `ui/main/QuickPills.kt`).
  - The row draws at once from the last config kept on the device, then catches up with one request for the config and one for what's on the pill channels. Backdrops start as stock art bundled in the app (guide, news, usa, hallmark, sports, tv).
  - What's on a channel pill gets real art and details in the background: the show's backdrop, logo and exact episode (title, description, "S10 E4") when it's in the library, otherwise a TMDB backdrop and logo from Scuffed's `/api/gooseflix/art` (#1421).
  - The Guide pill takes focus when the app opens, rather than the nav drawer.
- Live game pills for the Patriots, Celtics, Bruins and Red Sox, first in the pill row while a game or its pregame is on. They come from Scuffed's `/api/gooseflix/live` (#1427, #1422).
- Rows for libraries added after the user saved their home settings, placed in library order (`services/NewHomeRows.kt`).
- Recently Added shows a single new episode, or a new season, as its show (card, header and click), with a "New episode · S1 E2 · Name" or "New episodes · Season 2" line in the header. Setting: Interface → "Show the series for new episodes", on by default (#1501; `services/NewEpisodeSeries.kt`).
- First load shows the page as soon as the top row is ready; the rows below show as loading and fill in as each finishes (upstream waits for every row) (`ui/main/HomeFirstLoad.kt`).
- Defaults: Continue Watching and Next Up as separate rows, no Watch Live row, 50 items per row.
- Holding up or down on the home page moves one row every 75 ms, the fastest the Shield held without focus getting stuck; every repeat (no limit) gets stuck (`ui/main/HoldThrottle.kt`; upstream damontecres/Wholphin#2034). With verbose logging on, the `GooseFlixNav` logcat tag logs each key, row/card focus change and the list's scroll position (`ui/main/HomeNavLog.kt`).
- The previous user's rows are cleared when switching user.

### Navigation drawer
- Order: Live TV, Favorites, Discover, then the other libraries, then Video Games last (upstream puts Favorites, Discover and Games before every library). The libraries' order is each user's Jellyfin library order, which Scuffed sets to the default once per user (Live TV, Movies, TV, the seasonal library, 4K, Cams, the streaming libraries A–Z, Anime, Kids, Reality, Sports, Youtube, Recordings, Playlists, Review) and then leaves alone. The list is `LIBRARY_ORDER` in Scuffed's `collector/library_order.py`. A user's own reordering, in the app or in Jellyfin, wins (`services/NavDrawerService.kt`).
- Who sees which library (Anime (Streaming) only for sti, Review only for the Play review account) is Jellyfin library access, not the app.
- Settings → Navigation drawer has **Reset to default order**: it forgets the positions saved on the device and keeps what's pinned or under More. Closing that screen without a change no longer saves anything; upstream saved every position whenever it closed, so merely opening it froze the drawer's order (`ui/preferences/NavDrawerPreference.kt`).

### Video games
- Native retro game playback from the Moonbase Jellyfin plugin: libretro host in `app/src/main/cpp`, games UI in `games/`.
- Controllers mapped per port; artwork fetched through a retrying disk cache; a home row and header for each games library.
- Game libraries only show for users who can see that library in Jellyfin. The plugin itself doesn't check permissions.

### Ratings and details
- IMDb, MyAnimeList (anime), Rotten Tomatoes (critics and Popcornmeter) and TMDB ratings from the MDBList Ratings plugin, with provider logos (#1862; `services/MdbListRatingsService.kt`, `ui/components/MdbListRatingsRow.kt`).
- The server's own critic and community scores are hidden by default, but shown whenever MDBList has nothing for an item (not fetched yet, or an empty result). The home page header looks MDBList up for the focused item, like the details page. The app's MDBList lookups expire (6 h when found, 10 min when not), so an item looked up before the plugin's daily refresh doesn't stay blank until the app restarts.
- Details pages show every genre on one line (upstream stops at four and adds "+N"), and drop "Ends at" from the details line so the ratings fit; the player still shows it (#1451).

### Requests (Seerr)
- Every signed-in user is connected to Seerr automatically through Scuffed's proxy, with their Jellyfin token in place of an API key; accounts the proxy blocks (eg `playreview`) get no Seerr, so no Discover (#1516; `services/GooseFlixSeerr.kt`, Scuffed `collector/gooseflix_seerr.py`). `SEERR_PROXY_URL` is `https://add.beefmc.com/api/gooseflix/seerr`; `-PforkSeerrProxyUrl=` (empty) turns it off. The proxy confirms Seerr acts as each user before forwarding anything for them, and refuses if not.

### Search
- Titles that exist in more than one library show the library name in place of the year (`ui/search/SearchLibraries.kt`).

### Playback and live TV
- Live TV recovery: a stream that errors or hangs mid-play reconnects on its own (up to three times). A channel that fails before it starts playing goes straight to the server transcoding it, at the live edge, without the reconnect waits, and the app transcodes that channel straight away until it's closed (`LiveTvRetryPolicy.kt`, `LiveTvTranscodeMemory.kt`).
- Passout protection for live TV: "Still watching?" after a set time with no button pressed, then stop. Steps are 30 minutes, and it can be set for live TV, shows/movies or both (`LiveTvPassout.kt`).
- Guide: any programme's channel can be watched, whatever time the programme is.
- Guide moves like a cable box: up/down go to what's on at the time you're looking at and only scroll vertically; left/right set that time and only scroll sideways (`ui/detail/livetv/GuideFocus.kt`). A move only finishes once the target programme really has focus, so the highlight and the header at the top never disagree.
- Debug overlay: stall counter, buffer ahead, distance behind live, reconnects (`StallTracker.kt`).
- Playback timeline log (logcat tag `GooseFlixPlay`) for diagnosing start-up problems (`PlaybackTimeline.kt`).
- Subtitle keyword priorities (Settings → Advanced → Playback): preferred keywords (GooseFlix default "full, dialogue, eng") rank a track first and count as the default flag, whatever the audio language (a 2026-10-03 exception that skipped them over same-language audio, eg English audio, was removed on 2026-11-11 at the owner's request); avoided ones (default "signs, songs, forced, foreign, dub, commentary, ext") only rank it lower, never exclude it. Defaults tuned on the anime library. Keywords also count when a series has a remembered track choice (upstream's track memory), which upstream scores without them: a choice that only remembers a language (every one converted from the old per-series setting) would otherwise pick the default-flagged track, often signs-only on anime. A track the user picked by hand that itself goes against the keywords (a signs or commentary track) still wins. The signs check ignores [bracketed] group tags too (`services/StreamChoiceService.kt`; tests in `TestStreamChoiceServiceKeywords`).
- MPV: dialogue boost and loud-scene compression options.
- MPV leaves 10-bit H.264 (Hi10P) for the server to transcode on devices without a hardware decoder for it, as ExoPlayer already did, instead of direct-playing it in software and stuttering (#1512; `util/mpv/MpvDeviceProfile.kt`).
- During an intro, recap, outro or commercial, the player menu opens with focus on its Skip button (after the floating "Skip intro" button has hidden), so skipping is one press to open and one to skip; outside a segment it opens on play/pause as before. If Skip has focus when the segment ends, focus moves to play/pause (#1548; `ui/playback/overlay/PlaybackControls.kt`).
- Defaults: OLED black theme, pause with one press of the centre button.

## Upstream

- Synced with upstream **v1.1.1** on 2026-11-09 (translations and three fixes on top of the last sync; upstream's #2163 live stream id fix was already in the fork), with upstream **main at `38bd690c`** (31 commits after v1.0.9) on 2026-11-01, and with **v1.0.9** on 2026-10-12, each a merge.
- Upstream's server plugin (damontecres/Wholphin #1384, #2134) isn't installed on jelly.grit.bot, so its home settings and Seerr auto-configuration stay idle. If it ever is installed, the Scuffed Seerr proxy still wins: `UserSwitchListener` connects through it before the plugin's Seerr step.
- Upstream's session-restore change (#1905) moved the remembered server/user to a new store, with a migration gated on the new version being 1.0.8 or older. A fork build is 1.0.9-N, so the fork also runs that migration whenever the new store is empty (`AppUpgradeHandler`); without it every TV would be signed out by the update. The fork first grew from `45c1ceca`, just after v1.0.7. To sync again: fetch upstream's tags, merge the release tag into a `claude/...` branch, resolve conflicts (keep fork behaviour, take upstream's structure), run the tests, and install that branch's build on a Shield before merging it into the working branch.
- Version codes count fork commits since `v1.0.7` (`FORK_BASE_TAG` in `app/build.gradle.kts`), not since the latest tag. Merging a new upstream tag resets the latter, and Android refuses to install a lower code.
- Issues we've filed upstream: damontecres/Wholphin#2034 (home page focus stuck), fixed here.
