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
- **Logs and crash reports reach the server:** "Send app logs", "Send media info" and crash reports are cut to fit Jellyfin's client log upload limit (1,000,000 bytes), keeping the start and the most recent lines, and a crash's stack trace has runs of the same line counted instead of listed (a stack overflow repeats one frame thousands of times). A failed send says so on screen instead of failing silently (`util/LogUpload.kt`, `ServerReportService`, `CrashReportSenderFactory`).

### Home page
- Quick pills: a row of shortcut buttons at the top (Guide, favourite channels) per user, set in Scuffed's admin page (`services/QuickPillsService.kt`, `ui/main/QuickPills.kt`).
  - The row draws at once from the last config kept on the device, then catches up with one request for the config and one for what's on the pill channels. Backdrops start as stock art bundled in the app (guide, news, usa, hallmark, sports, tv).
  - What's on a channel pill gets real art and details in the background: the show's backdrop, logo and exact episode (title, description, "S10 E4") when it's in the library, otherwise a TMDB backdrop and logo from Scuffed's `/api/gooseflix/art` (#1421).
  - The Guide pill takes focus when the app opens, rather than the nav drawer.
- Live game pills for the Patriots, Celtics, Bruins and Red Sox, first in the pill row while a game or its pregame is on. They come from Scuffed's `/api/gooseflix/live` (#1427, #1422).
- Rows for libraries added after the user saved their home settings, placed in library order (`services/NewHomeRows.kt`). Any "Recently added in…" row whose library the user can't currently see (a seasonal library out of season) is hidden but kept, and comes back with the library; shown, it would read just "Recently added" and list the latest from every library.
- Recently Added shows a single new episode, or a new season, as its show (card, header and click), with a "New episode · S1 E2 · Name" or "New episodes · Season 2" line in the header. Setting: Interface → "Show the series for new episodes", on by default (#1501; `services/NewEpisodeSeries.kt`).
- First load shows the page as soon as the top row is ready; the rows below show as loading and fill in as each finishes (upstream waits for every row) (`ui/main/HomeFirstLoad.kt`). The loading spinner in the corner (first load and refresh) goes once the top three rows have finished, not the last row far below the screen (`HomeFirstLoad.spinnerDone`). Rows load six at a time (upstream four).
- A home load fetches the user's library list once, not three times (about a second each on this server, all before the first row could start): it's reused for a minute (`NavDrawerService.userViews`). The `GooseFlixHome` logcat tag times each home load: when each row finished, when the page showed, when all rows were done (`adb logcat -s GooseFlixHome`; release builds log it too, with readable states: `row 4 ok 50`, `requests 3`, `error <exception>`).
- Defaults: Continue Watching and Next Up as separate rows, no Watch Live row, 50 items per row.
- Personal rows at the bottom of the home page, served by the GooseFlix server plugin (wibuf/jellyfin-plugin-wholphin, GooseFlix #1635): Picked for you; Because you watched *title* three times, from a movie, from a show and from anything; and up to six daily mood rows (titled with just the mood, eg "Heists & cons", #1659; a slot the plugin has no mood for today is empty and hidden). Homes that got the first set (one Because you watched, three moods) get the movie and show rows and moods 4–6 once, next to their own (`personal:v2b`), unless they'd removed that kind of row. The movie and show rows only show with plugin 0.1.1.501 or later, which answers with `X-GooseFlix-Row-Kind`; an older plugin would send the "anything" row three times. **Don't recommend** in a personal row's long-press menu marks the title disliked in Jellyfin (its own per-user like/dislike, so every device), takes it out of the personal rows on the page at once, and keeps it out (the plugin leaves disliked titles out; the app also drops any it's sent). **One card per title:** a movie in two libraries (Movies and 4K) comes back once per copy, so the app keeps the first and drops the rest (same type and TMDB id, else IMDb id, else name and year), along with copies of the title a Because you watched row is built from (`PersonalRows.distinctTitles`). They're ordinary custom endpoint rows on the plugin's `/wholphin/rows/...` endpoints, added to every user's home once (new and existing users; removed ones stay removed), with the title the plugin sends in `X-GooseFlix-Row-Title`. **The app works the same without the plugin:** a personal row that can't be fetched (not installed, upstream's plugin, an error) comes back empty and doesn't show, and after a 404 the app stops asking for 30 minutes (GooseFlix #1636; `services/PersonalRows.kt`). Rows the app adds to a home (new libraries, personal rows, these one-time moves) are saved where that home's layout was loaded from: on the device, or the user's layout on the server ("save to server" in home settings). Builds up to v1.1.1-96 saved only on the device, so a server-saved layout lost them on the next load; `personal:v2b` and `bottom:v2` replace the keys those builds recorded. A layout from the server plugin or the web client isn't rewritten: the rows are added on each load instead. They go above the Recordings and Video Games rows (and Moonbase games rows), which always stay last, in that order (`PersonalRows.BOTTOM_LIBRARIES`, by library name); homes that got the personal rows below them in the first builds have the two moved back to the bottom once (`bottom:v2`). The plugin percent-encodes its headers, which the app decodes (with `URLDecoder.decode(String, "UTF-8")`: the `Charset` overload is Android 13+ and crashed the home load on the Shield, Android 11). A personal row or My requests that fails in any way, errors included, comes back empty rather than stopping the rest of the home load.
- **My requests** row, placed above the seasonal library (right after the TV library's row; without one, after Continue Watching / Next Up): the user's own requests, newest 30, as Discover cards with their own home header (title, "Movie · Downloading 42%", overview). Needs no plugin; empty, or for an account without requests (eg `playreview`), it doesn't show (`services/MyRequests.kt`, `ui/main/RequestsHomeRow.kt`, test `MyRequestsRowTest`).
  - **Source:** one call to Scuffed's `GET /api/gooseflix/my-requests` (signed in with the user's Jellyfin token in `X-Api-Key`), which returns ready cards with Media Adder's status label and download progress. Cards show the progress as a bar along the bottom (`DiscoverItemCard`'s `progressPercent`). If Scuffed doesn't have that endpoint (404) or fails, it falls back to the Seerr API through the Scuffed proxy (`requestedBy` the user's Seerr id, then a details call per title).
  - **Header and backdrop:** Scuffed's cards carry no backdrop or overview, so the first time a card stays focused (300 ms) the app fetches its Discover details through the Scuffed Seerr proxy, once per title, for the backdrop, overview and year (`MyRequests.details`).
  - **Cache:** 2 minutes. Making, cancelling or removing a request anywhere in the app clears it, and the next fetch asks Scuffed with `fresh=1`.
  - **Long-press** a card for Open / Remove from My requests, which takes it off the user's list (Media Adder keeps the title).
- Holding up or down on the home page moves one row every 75 ms, the fastest the Shield held without focus getting stuck; every repeat (no limit) gets stuck (`ui/main/HoldThrottle.kt`; upstream damontecres/Wholphin#2034). With verbose logging on, the `GooseFlixNav` logcat tag logs each key, row/card focus change and the list's scroll position (`ui/main/HomeNavLog.kt`).
- The previous user's rows are cleared when switching user.

### Navigation drawer
- Order: Live TV, Favorites, Discover, then the other libraries, then Video Games last (upstream puts Favorites, Discover and Games before every library). The libraries' order is each user's Jellyfin library order, which Scuffed sets to the default once per user (Live TV, Movies, TV, the seasonal library, 4K, Cams, the streaming libraries A–Z, Anime, Kids, Reality, Sports, Youtube, Recordings, Playlists, Review) and then leaves alone. The list is `LIBRARY_ORDER` in Scuffed's `collector/library_order.py`. A user's own reordering, in the app or in Jellyfin, wins (`services/NavDrawerService.kt`).
- Who sees which library (Anime (Streaming) only for sti, Review only for the Play review account) is Jellyfin library access, not the app.
- Settings → Navigation drawer has **Reset to default order**: it forgets the positions saved on the device and keeps what's pinned or under More. Closing that screen without a change no longer saves anything; upstream saved every position whenever it closed, so merely opening it froze the drawer's order (`ui/preferences/NavDrawerPreference.kt`).

### Video games
- Native retro game playback from the Moonbase Jellyfin plugin: libretro host in `app/src/main/cpp`, games UI in `games/`.
- Controllers mapped per port (a device counts as external through `InputDevice.isExternal` on Android 10+, and as "not virtual" before that, where the call doesn't exist); artwork fetched through a retrying disk cache; a home row and header for each games library.
- Game libraries only show for users who can see that library in Jellyfin. The plugin itself doesn't check permissions.

### Ratings and details
- IMDb, MyAnimeList (anime), Rotten Tomatoes (critics and Popcornmeter) and TMDB ratings from the MDBList Ratings plugin, with provider logos (#1862; `services/MdbListRatingsService.kt`, `ui/components/MdbListRatingsRow.kt`).
- The server's own critic and community scores are hidden by default, but shown whenever MDBList has nothing for an item (not fetched yet, or an empty result). The home page header looks MDBList up for the focused item, like the details page. The app's MDBList lookups expire (6 h when found, 10 min when not), so an item looked up before the plugin's daily refresh doesn't stay blank until the app restarts.
- Details pages show every genre on one line (upstream stops at four and adds "+N"), and drop "Ends at" from the details line so the ratings fit; the player still shows it (#1451).

- **Ratings from the GooseFlix plugin** (GooseFlix #1671): the details ratings row asks the GooseFlix server plugin first (`/wholphin/ratings/item`, the same JSON as the MDBList Ratings plugin), then the MDBList Ratings plugin. The provider logos still come from the MDBList Ratings plugin's assets. Settings → Ratings display also offers **Metacritic** (critics, /100), **Metacritic users** (/10), **Letterboxd** (/5) and **Trakt** (%), off until turned on (`DisplayToggle` 505–508, appended at the end because the settings list pairs entries with labels by order); Metacritic's user score borrows the Metacritic icon. The details line wraps onto a second line (each rating moving down whole) instead of running off the edge when several sources are on (`QuickDetails`, a `FlowRow` in GooseFlix builds). In GooseFlix builds each rating's note reads "From MDBList, through the GooseFlix server plugin" (`src/fork/res`).
- **Sort a library by any rating** (GooseFlix #1671, "Option B"): when the plugin lists the `ratings` feature (`/wholphin/rows/capabilities`, checked every ten minutes), the library sort menu shows one **Rating** entry in place of Community and Critic rating. Next to it, a second menu picks the source: IMDb, RT critics, RT audience, MyAnimeList, Metacritic, Letterboxd, TMDB, Trakt or the MDBList score.
  - **Defaults:** MyAnimeList for a library whose name contains "anime", IMDb otherwise. The choice is remembered per user and library, in the app's own preferences (`gooseflix_rating_sort`); Jellyfin's saved sort, a database table, stays Community rating.
  - **Direction:** switching to Rating starts with the best rated.
  - **How it sorts:** Jellyfin can only sort by its own two rating fields, so the first page:
    1. asks Jellyfin for every matching title's id (the library's filters and the user's access, by name);
    2. asks the plugin for the source's score of every title in the library (`/wholphin/ratings/values`);
    3. orders them by score, with unrated titles last by name.

    Pages are then slices of that order, fetched by id (`util/RatingSortRequestHandler.kt`).
  - **Cards:** each card shows its score at the bottom left of the poster while sorted by rating ("★ 9.2", "94%" for Rotten Tomatoes).
  - **Without the plugin feature,** the library keeps Jellyfin's Community and Critic rating sorts.
  - Code: `services/PluginRatings.kt`, `ui/components/RatingSort.kt`, `CollectionFolderView.kt`, `SortByButton.kt`, `GridCard.kt`; test `PluginRatingsTest`.

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
- **Skipping per show** (player Settings → **Skipping**): lists the segment types the episode has (Intro, Recap, Credits, Preview, Ads), each with its current choice and whether it's the show's own or your default. Picking one offers **Auto skip**, **Ask (Skip button)**, **Don't skip** or **Use my default**. The choice is for that show (an episode uses its series; a movie its own), applies at once (also to the progress bar's skip-to-the-end), and is kept in the user's Jellyfin display preferences (`gooseflix_skip_<id>` in the "default" record, eg `INTRO=AUTO_SKIP,OUTRO=IGNORE`), so it follows the user to every TV. The global Skip settings stay the defaults. Not offered for live TV (`services/SkipOverridesService.kt`, `ui/playback/SkipSettings.kt`, test `SkipOverridesServiceTest`).
- **Skip segments on the progress bar** (setting, on by default; Skip settings): the intro, recap, outro, preview and commercials show as coloured stretches on the progress bar (`ui/playback/NextEpisodePreload.kt`, `overlay/SeekBar.kt`).
- **Skips that reach the end go to the end:** skipping a segment (automatic, the pop-up or the Skip in the controls) also skips the segments right after it that the user auto-skips (eg a preview after the credits), and if less than 10 s would be left it goes to the end of the episode, so the next one starts at once. A post-credits scene still plays (`NextEpisodePreload.skipTargetMs`).
- **Preload next episode** (experimental, off by default): about a minute before the outro starts (or the end, without one; never in the first half), the player asks Jellyfin how it would play the next episode (no stream opened) and, for a file that plays as it is, reads its first 4 MB, so the server's probing and a sleeping disk or `.strm` source are done before it's needed. How the episode starts is unchanged. Only when the next item would play (auto-play or Next Up), never for live TV. The playback timeline logs `next episode warmed in N ms` (`PlaybackViewModel.watchForPreload`).
- Defaults: OLED black theme, pause with one press of the centre button.

## Upstream

- Synced with upstream **v1.1.1** on 2026-11-09 (translations and three fixes on top of the last sync; upstream's #2163 live stream id fix was already in the fork), with upstream **main at `38bd690c`** (31 commits after v1.0.9) on 2026-11-01, and with **v1.0.9** on 2026-10-12, each a merge.
- Upstream's server plugin (damontecres/Wholphin #1384, #2134) isn't installed on jelly.grit.bot, so its home settings and Seerr auto-configuration stay idle. If it ever is installed, the Scuffed Seerr proxy still wins: `UserSwitchListener` connects through it before the plugin's Seerr step.
- Upstream's session-restore change (#1905) moved the remembered server/user to a new store, with a migration gated on the new version being 1.0.8 or older. A fork build is 1.0.9-N, so the fork also runs that migration whenever the new store is empty (`AppUpgradeHandler`); without it every TV would be signed out by the update. The fork first grew from `45c1ceca`, just after v1.0.7. To sync again: fetch upstream's tags, merge the release tag into a `claude/...` branch, resolve conflicts (keep fork behaviour, take upstream's structure), run the tests, and install that branch's build on a Shield before merging it into the working branch.
- Version codes count fork commits since `v1.0.7` (`FORK_BASE_TAG` in `app/build.gradle.kts`), not since the latest tag. Merging a new upstream tag resets the latter, and Android refuses to install a lower code.
- Issues we've filed upstream: damontecres/Wholphin#2034 (home page focus stuck), fixed here.
