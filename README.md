# Metrodesk

Desktop YouTube Music client for **Linux and Windows**, based on [Metrolist](https://github.com/MetrolistGroup/Metrolist).
Built with Kotlin, Compose Multiplatform (Material 3) and libVLC.

Metrodesk reuses Metrolist's `innertube` parsing module and InnerTubeX extraction. It is a separate desktop port, not a complete Android feature-for-feature replacement. Linux and Windows share one JVM UI, with portable parsing/validation in Kotlin Multiplatform `shared/commonMain`.

## Features

- Home feed, Explore (new releases, moods & genres, charts), search with suggestions and filters
- Albums, artists, playlists, podcasts, "Listen again"
- Player with queue, shuffle, repeat, radio (endless autoplay), sleep timer, volume normalization
- Synced lyrics (LrcLib, YouTube Music fallback)
- Library: liked songs, history, local playlists, saved albums/artists/playlists
- Optional YouTube account login (cookie) to see your own library and recommendations
- Downloads for offline playback
- **Listen Together**, compatible with Metrolist Android rooms (same metroserver protocol)
- Dynamic theme from album art, light/dark mode
- System tray, media keys, keyboard shortcuts, MPRIS on Linux (desktop media controls)

## Install

### Requirements

Playback uses **VLC**. Install it first:

| OS | How |
|----|-----|
| Windows | Install [VLC 64-bit](https://www.videolan.org/vlc/download-windows.html) (default location is detected automatically) |
| Debian/Ubuntu | `sudo apt install vlc` |
| Fedora | `sudo dnf install vlc` |
| Arch | `sudo pacman -S vlc` |

If VLC is installed somewhere unusual, set `VLC_PATH` to the folder that contains `libvlc`.

### Download

Grab the latest build from [Releases](../../releases) or the [Actions](../../actions) artifacts:

- **Windows**: `metrodesk-x.y.z.msi`
- **Debian/Ubuntu**: `metrodesk_x.y.z_amd64.deb` (`sudo apt install ./metrodesk_*.deb`)
- **Other Linux**: the portable app folder, run `bin/metrodesk`

Packages bundle their own Java runtime.

## Keyboard shortcuts

| Keys | Action |
|------|--------|
| Ctrl + Space | Play / pause |
| Ctrl + → / ← | Next / previous |
| Ctrl + ↑ / ↓ | Volume up / down |
| Ctrl + L | Like current song |
| Alt + ← | Back |
| Media keys | Play/pause, next, previous |

## Listen Together

Open **Together**, enter a username, then **Create room** and share the code, or **Join room** with a friend's code.
Desktop and Android Metrolist users can be in the same room as long as everyone uses the same server.

The default server is `wss://metrolist.caliph.dev/ws`. You can change it in **Settings**.
Hosting your own: see [metroserver](https://github.com/MetrolistGroup/metroserver).

## Signing in

Metrodesk has no embedded browser, so sign-in uses your YouTube Music cookie:

1. Open https://music.youtube.com in your browser and sign in.
2. Open DevTools (F12) → Network, reload, click any request to `music.youtube.com`.
3. Copy the full `Cookie` request header.
4. Paste it in **Settings → Account**.

The cookie is stored only on your computer and is **not encrypted**. On Linux the settings file is owner-only. On Windows it inherits your user-profile folder permissions. Treat it like a password and never share `settings.json`.

## Data location

| OS | Folder |
|----|--------|
| Windows | `%APPDATA%\metrodesk` |
| Linux | `~/.local/share/metrodesk` |

## Build from source

Requires JDK 21 and VLC 64-bit. Run the Windows installer tasks on Windows, with WiX Toolset 3 installed.

```bash
./gradlew :app:run                 # run
./gradlew :shared:jvmTest :app:test # parser, validation and codec tests
./gradlew :app:playerSmoke         # real native VLC paused-load/play/seek/pause check
./gradlew :app:ltProbe             # live host/guest check against configured test server
./gradlew :app:packageDeb          # Linux .deb
./gradlew :app:packageMsi          # Windows .msi (run on Windows)
./gradlew :app:createDistributable # portable app folder
```

On a drive without executable permissions, use `bash ./gradlew ...`. On Windows use `gradlew.bat ...` in PowerShell or Command Prompt.
For remote/headless Linux testing, run with `JAVA_TOOL_OPTIONS=-Dskiko.renderApi=SOFTWARE` under Xvfb.

Project layout:

- `shared/` Kotlin Multiplatform logic (lyrics parsing, input validation)
- `innertube/` YouTube Music parsing/API module copied from Metrolist, with a JVM build and desktop logging shim
- `app/` desktop app: UI, player, Listen Together, downloads

## Differences from Metrolist Android

Not available on desktop yet: Google sign-in through a web page, account sync of likes/playlists back to YouTube, equalizer, crossfade, song recognition, widgets, Discord rich presence, Chromecast.

## License

GPL-3.0, same as Metrolist. Not affiliated with Google or YouTube.
