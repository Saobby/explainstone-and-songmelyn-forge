# Explainstone and Songmelyn (Forge 1.20.1)

Port of the Fabric 1.21.1 edition of **Explainstone / Songmelyn / Broadcast Transmitter**.

* Stone speakers that play bundled or imported `.ogg` music.
* Redstone triggering with rising/falling edge detection.
* Playlist modes (sequence / repeat one / shuffle), channel broadcasting and receive mode.
* Imported audio is uploaded in chunks and stored inside the world save
  (`<world>/explainstone/audio/<sha1>.ogg`), then streamed to nearby clients on demand.
  Only the current track and the next track in the playlist are fetched: entering a
  speaker's range no longer downloads the whole playlist up front.
* **Importing is metadata-first**: the client first sends the track metadata
  (`label`, `sha1`, `duration hint`) and the server adds the entries to the playlist
  *immediately*. The reply lists which SHA-1s still need bytes, and only those are
  uploaded. The playlist therefore appears complete at once and never grows
  track-by-track, and per-file progress can no longer reset playback.
* **Playback never waits for bytes.** Audio availability is derived from the content
  store (`<world>/explainstone/audio/`), not stored in the playlist, and is synced as a
  bitmap with the playlist (plus small `AudioAvailability` pushes when a file lands or is
  reclaimed). Tracks whose bytes are still missing render greyed out with *(pending upload)*;
  everyone who does not have the file stays silent for that span while the shared timeline
  keeps running, so uploaders hear their own music immediately and the other players join in
  as soon as the upload finishes — even for playlists that already referenced the same SHA-1.
* **Playlist changes are deltas, not rebuilds.** Appending tracks, renaming them, or
  replacing a duration hint updates the running session in place (the "current track +
  offset" is preserved); only deleting the currently playing track, changing the selection
  or the play mode restarts the timeline.
* Uploads are purely background work: the server re-checks SHA-1, Ogg framing and duration,
  then writes the file and flips that SHA-1 to available. Validation failures drop the
  offending entry (and put the uploader on a short cooldown) instead of resetting everyone's
  playback.
* The server keeps a `sha1 -> speakers/transmitters` usage map
  (`<world>/explainstone/audio-usage.nbt`). Every playlist change (import, remove,
  delete-all, block broken, chunk load) updates it; once the last reference is gone the
  corresponding `.ogg` is deleted from the world save.
* Client-side imports run asynchronously: reading, SHA-1 hashing, duration probing and the
  local cache write happen off the render thread, and chunks are sent over multiple client
  ticks. The block UI shows a local-only "Processing %" / "Checking…" / "Uploading %" status.
* Import limits are configurable: 10 MB / 10 minutes / 500 tracks per playlist by default.
  The server also re-checks the SHA-1, enforces interaction distance, assembles chunks by
  index, expires abandoned partial uploads and limits how many "metadata only" entries one
  player may keep pending.
* The import picker uses the desktop's native dialog when one is available — `zenity` /
  `qarma` / `kdialog` on Linux (KDE sessions prefer `kdialog`, others prefer `zenity`),
  AWT `FileDialog` elsewhere — and always forces the dialog on top of the fullscreen game
  window. If no system dialog can be created at all (headless JRE without `libawt_xawt.so`,
  Flatpak sandbox, `-Djava.awt.headless=true`, …), the mod switches to an in-game file
  browser instead, so importing never depends on the desktop at all. Cancelling the dialog
  simply does nothing; there is no hidden fallback directory. Failures are logged under
  `explainstone-picker`.
* The in-game file browser starts in the last folder you used: click folders to navigate,
  click `.ogg` files to toggle them, use *Select all* / *Clear all* / *Invert* for bulk
  selection, and press *Import*. The selection survives folder changes, so files can be
  gathered from several folders in one go.
* `filePicker = "system"` (default) picks the system dialog with the in-game browser as a
  fallback, `filePicker = "in-game"` always opens the in-game browser — handy for trying it
  out on Windows/macOS where the native dialog normally works.
* The screen includes delete-all, current-track display and a separate progress row so the
  time/progress text no longer overlaps the playlist.
* Client-side OpenAL playback engine, independent of resource packs.
* Directional playback: decoded audio is mono-downmixed and positioned in the world,
  so the stereo image follows where the speaker actually is (turn around and left/right swap).
* Server-authoritative synchronized playback: the server keeps a fixed playlist order and
  start tick for every speaker / channel broadcast. Clients compute the current track and
  offset from that timeline, so all players are in sync and leaving/re-entering range no
  longer restarts the song.
* Playlists larger than one network frame are split on track boundaries into parts of at
  most 64k characters (each part carries its index, total part count and the session
  revision). Clients reassemble the parts before applying the timeline; the periodic
  session resend sends the full part set again, so late joiners and lost packets self-heal.
  This removes the old single-frame size limit on large playlists.
* Playback sessions are persisted in `<world>/explainstone/playback.nbt`: the shuffled
  order and the elapsed position are saved every few seconds and on world save, then
  restored when the world is loaded again. Shuffle mode resumes the same random order
  instead of restarting from the selected track every time. A paused session keeps its
  frozen position and order in the same file, so it can be resumed after a reload.
* The play button doubles as a pause button: while something is playing it reads
  "Pause"; clicking it freezes the current position (clients go silent immediately) while
  the progress row stays visible with "Paused: <track>" and the title switches to
  "· paused". Pressing play resumes from exactly that position with the same shuffled
  order. The separate stop button discards the pause point, so the next play starts from
  the selected track again.
* Audio decoding runs on a background thread. The first track of a session is decoded
  asynchronously (the voice starts a moment later instead of freezing the game), and the
  next track is pre-decoded while the current one plays, so track changes do not hitch.
* One volume slider controls both loudness and audible range:
  `effective range = audibleRangeAtFullVolume × volume / 100`. Setting it to 0 mutes the
  speaker, so there is no separate sound on/off toggle. Local playback vs. receive mode is
  chosen with the tabs at the top of the screen.
* The broadcast transmitter has an extra global volume slider next to its signal-range
  slider. Every receiving speaker plays at `speaker volume × transmitter volume / 100`
  (and its audible range scales the same way), so one transmitter can turn the whole
  channel down at once. Live changes are pushed to clients as a tiny dedicated packet,
  so dragging the slider never re-sends the playlist.
* Directional attenuation: volume follows the 3D angle between the speaker-to-listener
  vector and the grille direction. Directly in front is loudest; behind is quieter
  (configurable via `rearVolumeFactor`); standing above the speaker is quieter than
  standing directly in front at the same level.
* Direct-sound occlusion: walls and other blocks between the speaker and the listener
  attenuate the sound. The low-frequency attenuation is always applied; with OpenAL EFX
  available a low-pass filter additionally muffles the highs, otherwise only the
  broadband attenuation is used. Closed doors and trapdoors block their cell but remain
  incomplete blocks (square-root transmission); open ones are treated as air.
* Room reverb: each speaker samples its surrounding room with an icosphere ray grid,
  estimates volume/surface area/absorption and RT60, and drives an OpenAL EFX reverb
  auxiliary send. Room parameters belong to the speaker and are rebuilt periodically.
  Fully open/outdoor areas do not get reverb.
* Acoustic material coverage: all 103 vanilla `SoundType`s are mapped; tags and
  block-name rules refine special cases (wool, concrete, ice, sculk, plants, etc.).
  Unknown or modded custom sound types fall back to `STONE`. Other mods can call
  `SoundMaterials.register(Block, SoundMaterial)` or
  `SoundMaterials.register(SoundType, SoundMaterial)` to override it.

## Build

```bash
./gradlew build
```

The mod jar is written to `build/libs/explainstone-<version>.jar`.

### CI

`.github/workflows/build.yml` builds the mod on GitHub Actions:

* pushes to `master`, pull requests against `master`, and manual runs
  (`Run workflow` on the Actions tab) produce a downloadable artifact
  `explainstone-<mod_version>` containing the mod jar;
* pushing a tag such as `v1.0.7-beta` additionally creates a GitHub Release with
  that jar attached (an existing release for the tag is updated in place).

```bash
git tag v1.0.7-beta
git push origin v1.0.7-beta
```

## Run

```bash
./gradlew runClient   # client
./gradlew runServer   # dedicated server
```

## Config

Forge config file: `config/explainstone-common.toml`

```toml
broadcastRequiresOp = false
# When true, only operators (permission level 2) can start a broadcast. Redstone
# power cannot start one in this mode either (it can still stop one).
# Audible range in blocks at 100% volume. The effective range scales linearly
# with volume; keep this value identical on client and server.
audibleRangeAtFullVolume = 40
# Volume multiplier directly behind a speaker (0.0 = silent, 1.0 = disabled).
rearVolumeFactor = 0.25
# Attenuate direct sound through blocks between the speaker and the listener.
soundOcclusion = true
# Room reverb for enclosed spaces.
reverbEnabled = true
# Maximum room sampling distance in blocks.
reverbMaxRoomDistance = 48.0
# EFX reverb effect gain / high-frequency gain, and wet return level.
reverbGain = 0.3
reverbGainHF = 0.8
reverbWet = 0.8
# Which file picker the "Import ogg…" button opens (client-side):
# "system"  = native dialog first, in-game browser as fallback;
# "in-game" = always use the in-game file browser.
filePicker = "system"
# Imported ogg size limit in MiB (1-100).
maxAudioFileMb = 10
# Single-track duration limit in seconds (1-7200).
maxAudioDurationSeconds = 600
# Maximum number of tracks in one playlist (1-10000).
maxPlaylistTracks = 500
# --- Optimistic import limits (server-side) ---
# How many "metadata added, bytes not uploaded yet" tracks one player may hold at once.
pendingImportsPerPlayer = 500
# Seconds a pending entry survives without its bytes; then it is removed from the playlist.
pendingImportTimeoutSeconds = 120
# Cooldown in seconds after a failed upload validation (0 disables it).
importFailureCooldownSeconds = 60
# --- Server-side anti-abuse limits (defaults are generous for normal play) ---
# Simultaneous chunked imports per player / on the whole server, and the total
# memory budget for incomplete uploads in MiB.
maxUploadsPerPlayer = 4
maxUploadsTotal = 128
maxUploadBufferMb = 256
# Interface action token bucket: burst capacity and refill per second.
actionBurst = 30
actionRefillPerSecond = 15
# Audio download request bucket: burst capacity and refill per second.
audioRequestBurst = 24
audioRequestRefillPerSecond = 6
# Audio download traffic bucket per player: burst in MiB and refill in MiB/s.
audioDownloadBurstMb = 128
audioDownloadMbPerSecond = 8
```

Keep the three import-limit values identical on the client and the server: the client
checks file size while preparing an upload, hashes the files, probes the duration and submits
the metadata; the server enforces pending-entry quotas, and then enforces size, SHA-1,
duration and playlist length for the bytes that do need uploading. Incomplete multi-chunk
uploads are dropped after two minutes without a new chunk, when the player logs out, or when the
server stops. Pending entries whose bytes never arrive are removed again after
`pendingImportTimeoutSeconds`, and entries that still reference missing files are pruned from
the playlist when the world loads.

> **Protocol note:** this import pipeline changed the network protocol
> (`PROTOCOL_VERSION = 9`): metadata-first playlists, availability bitmap and the
> `AudioAvailability` push replaced the old pre-upload SHA-1 query. Clients and servers must
> both run this version (or newer); Forge refuses to connect mixed versions.

## Differences from the Fabric edition
* Forge `DeferredRegister` / `SimpleChannel` replace the Fabric API registration and
  payload APIs.
* The JSON config was replaced by a Forge common config (`ForgeConfigSpec`).
* All assets and data pack files were converted to the 1.20.1 layout
  (`recipes/`, `loot_tables/`, `advancements/`, item field instead of id).
* Source sets were merged into `src/main/java`; client-only classes are still kept in
  `com.ginka.explainstone.client` and are never loaded on a dedicated server.

Author: GINKA_3L96WW1.  
Original Repo: https://github.com/3L96WW1/explainstone-and-songmelyn/  
Licensed under MPL-2.0.
