# TuneDesk

Desktop music player inspired by ArchiveTune (Android). Kotlin + Compose Desktop.

## Features
- YouTube Music search and streaming (NewPipe Extractor)
- YouTube account sign-in with library sync: liked songs, your playlists, like/unlike from the player bar
- Local music files and folders (mp3, flac, m4a, aac, ogg, opus, wav, wma), remembered between runs
- Synced lyrics (LRCLIB), click a line to seek
- Queue, seek, volume, auto-advance

## Get TuneDesk.exe (no coding)
**Option A, GitHub builds it for you (nothing to install):**
1. Create a free GitHub repo and upload this whole folder (keep the `.github` folder).
2. Open the **Actions** tab, run **Build Windows app**, wait a few minutes.
3. Download the `TuneDesk-windows-portable` artifact, unzip, double-click **TuneDesk.exe**.
   (`TuneDesk-windows-installer` holds a setup .exe/.msi if you prefer installing.)

**Option B, build on your PC:** install JDK 17 and Gradle, then double-click `build-exe.bat`.
The exe appears in `build\compose\binaries\main\app\TuneDesk\`.

**One-time requirement:** VLC media player (64-bit) from videolan.org. TuneDesk shows a "Get VLC" button if it's missing.

## Signing in to YouTube Music
Library tab → Sign in → follow the 3 steps (copy the `cookie` request header from music.youtube.com in your browser).
The cookie is saved in `%USERPROFILE%\.tunedesk\session.json` in plain text, so treat that folder as private.
Sign out deletes it. Sessions expire eventually; just sign in again.

## Caveats
- Written without a compiler, so expect small fixes on first build.
- YouTube changes its web API often. If sync or search breaks, update the NewPipe Extractor version in
  `build.gradle.kts` and the `CLIENT_VERSION` in `Innertube.kt`.
- Library sync loads liked songs and playlists (up to ~3000 tracks each); saved albums and artists aren't synced yet.
- Not affiliated with ArchiveTune, Google or YouTube.
