# MaxTV Player

Modern Android IPTV client built with Kotlin, Jetpack Compose and Media3. Designed first for
Android TV / Fire TV (D-pad navigation, focus glow, cinematic dark theme) and adaptive for
phones and tablets.

- Xtream Codes and M3U/M3U8 playlists (URL or local file), XMLTV EPG with grid guide
- Live TV, Movies, Series → Seasons → Episodes, Continue Watching, Favorites, global search
- Multiple playlists, PIN-locked categories, background auto-sync (WorkManager)
- Media3/ExoPlayer: audio tracks, embedded + external subtitles (.srt/.vtt), aspect modes, auto-reconnect
- Offline-first Room cache, Hilt DI, Coroutines/Flow, strict MVVM
- Localised: English, Spanish, French, German, Italian, Arabic, Turkish, Macedonian
- TMDB enrichment: movies/series missing a poster or synopsis are completed from TheMovieDB in the background

## Building

Requirements: JDK 17, Android SDK with platform 37 (the Gradle wrapper handles the rest).

```bash
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

## TMDB API key

Enrichment needs a (free) TMDB v3 API key from https://www.themoviedb.org/settings/api.
It is never committed; provide it in one of these ways:

1. **Build-time** – add to `local.properties` (git-ignored):
   ```properties
   tmdb.apiKey=YOUR_KEY
   ```
   or export `TMDB_API_KEY` in the environment (used by CI via the `TMDB_API_KEY` repository secret).
2. **Run-time** – in the app: *Settings → Artwork & metadata (TMDB)*. A key entered there overrides the build-time key.

## Download the APK

Every push to `main` and every pull request runs the **Android CI** workflow, which uploads
`iptv-player-debug-apk` as a build artifact (Actions tab → workflow run → Artifacts).
