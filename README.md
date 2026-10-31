# Explainstone and Songmelyn (Forge 1.20.1)

Port of the Fabric 1.21.1 edition of **Explainstone / Songmelyn / Broadcast Transmitter**.

* Stone speakers that play bundled or imported `.ogg` music.
* Redstone triggering with rising/falling edge detection.
* Playlist modes (sequence / repeat one / shuffle), channel broadcasting and receive mode.
* Imported audio is uploaded in chunks and stored inside the world save
  (`<world>/explainstone/audio/<sha1>.ogg`), then streamed to nearby clients on demand.
  Only the current track and the next track in the playlist are fetched: entering a
  speaker's range no longer downloads the whole playlist up front.
* Before any bytes are sent, the client asks the server with the SHA-1 list of the files
  it is about to import. The server reports which ones are already stored, adds those
  tracks to the speaker/transmitter playlist itself, and the client only uploads the
  missing files — shared audio is transferred once per world.
* The server keeps a `sha1 -> speakers/transmitters` usage map
  (`<world>/explainstone/audio-usage.nbt`). Every playlist change (import, remove,
  delete-all, block broken, chunk load) updates it; once the last reference is gone the
  corresponding `.ogg` is deleted from the world save.
* Client-side imports run asynchronously: reading, SHA-1 hashing and the local cache write
  happen off the render thread, and chunks are sent over multiple client ticks. The block
  UI shows a local-only "Processing %" / "Checking…" / "Uploading %" status.
* Import limits are configurable: 10 MB / 10 minutes / 500 tracks per playlist by default.
  The server also re-checks the SHA-1, enforces interaction distance, assembles chunks by
  index and expires abandoned partial uploads.
* The screen includes delete-all, current-track display and a separate progress row so the
  time/progress text no longer overlaps the playlist.
* Client-side OpenAL playback engine, independent of resource packs.
* Directional playback: decoded audio is mono-downmixed and positioned in the world,
  so the stereo image follows where the speaker actually is (turn around and left/right swap).
* Server-authoritative synchronized playback: the server keeps a fixed playlist order and
  start tick for every speaker / channel broadcast. Clients compute the current track and
  offset from that timeline, so all players are in sync and leaving/re-entering range no
  longer restarts the song.
* One volume slider controls both loudness and audible range:
  `effective range = audibleRangeAtFullVolume × volume / 100`.
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

## Run

```bash
./gradlew runClient   # client
./gradlew runServer   # dedicated server
```

## Config

Forge config file: `config/explainstone-common.toml`

```toml
broadcastRequiresOp = false
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
# Imported ogg size limit in MiB (1-100).
maxAudioFileMb = 10
# Single-track duration limit in seconds (1-7200).
maxAudioDurationSeconds = 600
# Maximum number of tracks in one playlist (1-10000).
maxPlaylistTracks = 500
```

Keep the three import-limit values identical on the client and the server: the client
checks file size while preparing an upload, hashes the files and asks the server which
SHA-1s already exist before sending anything. The server still enforces size, SHA-1,
duration and playlist length for the files that do need uploading. Incomplete multi-chunk
uploads are dropped after two minutes without a new chunk, when the player logs out, or when the
server stops.

## Differences from the Fabric edition

* Forge `DeferredRegister` / `SimpleChannel` replace the Fabric API registration and
  payload APIs.
* The JSON config was replaced by a Forge common config (`ForgeConfigSpec`).
* All assets and data pack files were converted to the 1.20.1 layout
  (`recipes/`, `loot_tables/`, `advancements/`, item field instead of id).
* Source sets were merged into `src/main/java`; client-only classes are still kept in
  `com.ginka.explainstone.client` and are never loaded on a dedicated server.

Author: GINKA_3L96WW1. Licensed under MPL-2.0.
