# Onecast — CLAUDE.md

Android podcast app styled to feel **fully native on Samsung One UI**, built on
[OneUIProject/oneui-design](https://github.com/OneUIProject/oneui-design) (the SESL forks of
AndroidX/Material). Package `be.miro.onecast`, app label "Onecast".

Source lives on GitHub at [minospace/onecast](https://github.com/minospace/onecast),
default branch `main`.

## Critical constraint: View/XML only, never Compose

The One UI look comes from Samsung's **SESL** widget forks (`io.github.oneuiproject.sesl:*`) and
`dev.oneuiproject.oneui.layout.ToolbarLayout`. These are View/XML-based — there is no Compose
equivalent that reproduces the real One UI styling. Do not introduce Jetpack Compose here.

## Build

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"   # JDK 21
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug       # build + install over adb (phone needs USB debugging on)
```

- Gradle wrapper is pinned to **8.7** (AGP 8.5.2 rejects newer Gradle; the Homebrew system
  `gradle` is 9.6 — always use `./gradlew`, never the global binary).
- Android SDK lives at `/opt/homebrew/share/android-commandlinetools` (installed via
  `brew install --cask android-commandlinetools`); `local.properties` points `sdk.dir` there and
  is gitignored — regenerate it if missing: `echo "sdk.dir=/opt/homebrew/share/android-commandlinetools" > local.properties`.
- **Bump the version only when the user asks to push** (any branch), never on an ordinary commit
  and never as an unprompted step: `versionCode` (int, `app/build.gradle.kts`) always increments by
  1. `versionName` is `MAJOR.MINOR.PATCH` — bump PATCH for a fix, MINOR for a new user-visible
  feature, MAJOR for a breaking/rearchitecture change. Also update `VERSION.txt` (gitignored local
  quick-reference, mirrors `versionCode`/`versionName`) to match — regenerate it if missing. Don't
  push yourself — the user runs the push.
- **Always log user-visible changes in `releasenotes.txt`** (project root, gitignored): whenever a
  change adds or alters something a user would notice, append one short, user-facing bullet
  describing *what changed for them* (not the implementation). Do this automatically as part of
  making the change — don't wait to be asked and don't ask whether to add it; it is not gated on a
  commit or a push. (Version bumps, by contrast, happen *only* when the user asks to push — see
  above.) Entries accumulate across commits until a release is built — `./build.sh` copies the file
  to the Desktop as `releasenotes.txt` (ready to paste into the GitHub release) and then blanks the
  working copy so the next release starts clean. Skip purely internal refactors/chores that a user
  wouldn't see.
- No emulator/Studio installed by default. A throwaway headless AVD can be created with
  `avdmanager`/`emulator` for verification — see "Testing" below for the gotchas.

## Architecture

```
app/src/main/java/be/miro/onecast/
  data/        Room entities (Podcast, Episode), DAOs, AppDatabase, PodcastRepository
  feed/        ItunesSearchClient (iTunes Search API), RssParser (XmlPullParser), FeedFetcher
  playback/    PlaybackService (Media3 MediaSessionService), PlayerConnection, MediaItems
  download/    EpisodeDownloader (queue + OkHttp), DownloadService (foreground + live
               notification), DownloadStore (files), DownloadNotifications
  widget/      OnecastWidgetProvider, WidgetState — home-screen widget
  ui/          MediaActivity (base), MainActivity (home grid), Format, SquareImageView
    search/    SearchActivity + SearchResultAdapter   — add a podcast (iTunes search + RSS URL)
    podcast/   PodcastActivity + EpisodeAdapter        — detail screen + episode list
    player/    PlayerActivity, MiniPlayerView          — now-playing UI
    downloads/ DownloadsActivity + DownloadsAdapter    — offline episodes + downloads in flight
    subscriptions/ PodcastGridAdapter
```

- **MVVM-ish, no DI framework**: `OnecastApp.repository` is the single manually-wired singleton
  (`Context.podcastRepository` extension for convenient access). No Hilt/Koin — keep it that way
  unless complexity genuinely demands it.
- **MediaActivity** is the base class for any screen that needs the player: it owns a
  lifecycle-bound `PlayerConnection` (wraps a Media3 `MediaController`) and exposes `repository`.
- **Single source of truth**: UI observes `PodcastRepository` Flows (Room) directly; no separate
  ViewModel layer was introduced — activities hold minimal state and re-render on Flow emissions
  plus `PlayerConnection.onUpdate` ticks.

## SESL / oneui-design gotchas (don't relitigate these)

- **Menus**: `ToolbarLayout` sets its internal `Toolbar` as the support action bar. Use the
  standard `onCreateOptionsMenu` / `onOptionsItemSelected` Activity callbacks — calling
  `binding.toolbarLayout.toolbar.inflateMenu(...)` directly is silently ignored (the menu never
  appears).
- **Theme**: `AppTheme` must inherit `OneUITheme` (see `values/themes.xml`).
- **Stock AndroidX must be excluded**: the `configurations.all { exclude(...) }` block in
  `app/build.gradle.kts` removes the real `androidx.appcompat`/`core`/`recyclerview`/etc. and
  `com.google.android.material` so only the SESL forks remain on the classpath. If you add a new
  dependency that pulls in stock AndroidX transitively, add a matching exclude or you'll get
  duplicate-class errors or the wrong (non-One UI) widget styling.
- **Child placement** inside `ToolbarLayout`/`DrawerLayout` uses `app:layout_location`, an enum:
  `main_content` (0), `appbar_header` (1), `footer` (2), `root` (3), `drawer_header` (4),
  `drawer_panel` (5). The mini-player lives in `footer`.
- **Navigation/back button**: `setNavigationButtonAsBack()` on detail-style screens.
- **No grey background panels** — every screen runs content edge-to-edge over *one* continuous
  surface (`oui_background_color`); the off-shade window/panel that stock One UI shows behind the
  content card was deliberately removed (see commit "Remove grey background panel on main, podcast
  and search screens" and the `sesl_round_and_bgcolor_*` / `sesl_action_bar_background_color_*`
  overrides in `values/colors.xml`). **Don't reintroduce a grey panel.** When you add a screen,
  check it doesn't show a lighter/darker band behind the content. SESL widgets that paint their own
  surface need flattening: e.g. the `PreferenceFragmentCompat` "cards" are drawn from colour
  *resources*, so in AMOLED mode (below) they're flattened with `seslSetRoundedCorner(false)` +
  `listView.seslSetFillBottomEnabled(false)` and by clearing the `"preferencecategory"`-tagged
  subheader view backgrounds (see `ui/SettingsActivity`).
- **AMOLED pure-black mode** (Settings → "Pure black dark mode"): One UI's dark surfaces are a
  near-black grey (#171717) read straight from colour *resources*, so a theme/attr override can't
  switch them at runtime. `ui/AmoledTheme` recolours surfaces in place — call
  `applyAmoledBackground(toolbar)` (MediaActivity) or `AmoledTheme.apply(...)` after
  `setContentView`; it walks the `ToolbarLayout` tree turning `app_content_background`-coloured
  views black and recolouring `RoundFrameLayout`/`RoundLinearLayout` corners. Activities call
  `recreate()` from `onResume` when the active state changed (so back-stack screens re-theme after
  the toggle). Only applies while the system is in dark mode; leave light mode alone.

## Playback gotchas

- Podcast audio URLs are almost always multi-hop tracking redirects (e.g.
  `tracking.swap.fm → byspotify → podtrac → cdn`), frequently crossing http↔https. ExoPlayer's
  `DefaultHttpDataSource` must have `setAllowCrossProtocolRedirects(true)` (wired in
  `PlaybackService.onCreate`) or playback fails with a generic "Source error". Manifest also
  needs `android:usesCleartextTraffic="true"`.
- A `BroadcastReceiver`'s `Context` (including `AppWidgetProvider.onReceive`, even after
  `goAsync()`) is **bind-restricted** — `MediaController.Builder(context, token).buildAsync()`
  throws `ReceiverCallNotAllowedException` if given the receiver's own context. Always pass
  `context.applicationContext` when connecting a `MediaController` from a receiver (see
  `OnecastWidgetProvider`).
- `PlaybackService` persists resume position periodically and on play/pause changes, and
  auto-marks an episode played on `Player.STATE_ENDED`. The home-screen widget mirrors playback
  state via `WidgetState` (a SharedPreferences snapshot) updated from the same player listener —
  keep both in sync if you touch the listener.

## Testing

There's no automated test suite (UI-driven smoke testing only, done manually via adb/uiautomator
during development). If you spin up a headless emulator to verify changes:

```bash
avdmanager create avd -n podcast_test -k "system-images;android-34;default;arm64-v8a" -d pixel_6
emulator @podcast_test -no-window -no-audio -no-snapshot -no-boot-anim \
  -gpu swiftshader_indirect -dns-server 8.8.8.8,8.8.4.4
```

- **`-dns-server` is required** — without it the emulator can't resolve podcast tracking/CDN
  hosts and playback fails with `UnknownHostException`, even though RSS/artwork fetches (simpler
  hosts) succeed.
- **Non-exported activities can't be launched via `adb shell am start`** on API 34+ — drive the UI
  with real taps: `adb shell uiautomator dump` the view tree, locate the target element's bounds,
  `adb shell input tap <x> <y>`. Retry the dump in a loop until the element appears before tapping
  — a dump taken mid-transition is a common source of flaky "tap did nothing" results, not an app
  bug.
- The home-screen widget's actual *rendering* on a launcher can't be verified on the AOSP
  test-image emulator (no widget picker) — that needs a check on a real device/launcher
  (the target is the One UI launcher on the user's Samsung phone).

## Scope

**Implemented**: add podcast (iTunes search + RSS URL), subscriptions grid, podcast detail with
swipe-refresh, streaming playback (background service, lock-screen controls, skip ±, speed),
mini-player + full player with a shared-element artwork transition, mark played (manual/bulk/auto),
resume positions, home-screen widget, light/dark following the system, episode downloads/offline
playback.

**Downloads**: strictly user-initiated — there is no auto-download and none should be added.
`EpisodeDownloader` runs one download at a time off an in-memory queue (progress is far too chatty
to persist; only the finished file's path/size/timestamp land on the `episodes` row).
`DownloadService` exists solely to hold the foreground-service notification that mirrors that queue
— it stops itself the moment nothing is queued or running. A download writes to a `.part` file that
is renamed into place only on success, so a partial file is never mistaken for a finished one; any
failure (HTTP error, truncated transfer, OkHttp read timeout, or the stall watchdog that fires when
bytes stop arriving for 60s) deletes the partial, notifies the user with the reason, and leaves a
retry row on the Downloads screen. `MainActivity` sweeps orphaned files once per process
(`PodcastRepository.pruneDownloads`), which is what catches leftovers from a process death.
