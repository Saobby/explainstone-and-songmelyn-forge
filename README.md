# Explainstone and Songmelyn (Forge 1.20.1)

Port of the Fabric 1.21.1 edition of **Explainstone / Songmelyn / Broadcast Transmitter**.

* Stone speakers that play bundled or imported `.ogg` music.
* Redstone triggering with rising/falling edge detection.
* Playlist modes (sequence / repeat one / shuffle), channel broadcasting and receive mode.
* Imported audio is uploaded in chunks and stored inside the world save
  (`<world>/explainstone/audio/<sha1>.ogg`), then streamed to nearby clients on demand.
* Client-side OpenAL playback engine, independent of resource packs.
* Directional playback: decoded audio is mono-downmixed and positioned in the world,
  so the stereo image follows where the speaker actually is (turn around and left/right swap).
* Server-authoritative synchronized playback: the server keeps a fixed playlist order and
  start tick for every speaker / channel broadcast. Clients compute the current track and
  offset from that timeline, so all players are in sync and leaving/re-entering range no
  longer restarts the song.
* One volume slider controls both loudness and audible range:
  `effective range = audibleRangeAtFullVolume × volume / 100`.
* Directional attenuation: sound is full volume in front of the grille, quieter
  behind it (configurable via `rearVolumeFactor`).
* Direct-sound occlusion: walls and other blocks between the speaker and the listener
  attenuate the sound. The low-frequency attenuation is always applied; with OpenAL EFX
  available a low-pass filter additionally muffles the highs, otherwise only the
  broadband attenuation is used. Closed doors and trapdoors block their cell but remain
  incomplete blocks (square-root transmission); open ones are treated as air.

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
```

## Differences from the Fabric edition

* Forge `DeferredRegister` / `SimpleChannel` replace the Fabric API registration and
  payload APIs.
* The JSON config was replaced by a Forge common config (`ForgeConfigSpec`).
* All assets and data pack files were converted to the 1.20.1 layout
  (`recipes/`, `loot_tables/`, `advancements/`, item field instead of id).
* Source sets were merged into `src/main/java`; client-only classes are still kept in
  `com.ginka.explainstone.client` and are never loaded on a dedicated server.

Author: GINKA_3L96WW1. Licensed under MPL-2.0.
