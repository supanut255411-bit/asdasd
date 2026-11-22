# StoneRestore 1.1

Client-side Fabric mod for **Minecraft Java 1.21.11** (Java 21) that restores vanilla visuals while an
"every block is stone" resource pack (Unstable SMP style) **stays enabled**. Nothing on disk is modified.

## What changed in 1.1

* **Restoration now covers models, blockstates and particle definitions**, not only PNGs (see below).
* **Short grass / tall grass** hidden by the pack (transparent textures, or blockstates/models that point at an
  empty model) are given back.
* **Fire, soul fire, campfires, candles, candle cakes, flames, smoke, torches, lanterns are never touched.**
* Test packs: new `StoneRestore-TestPack-UnstableVisual.zip` (approximation of the visual pack, see "Test plan").
* **`/stonerestorer chunk ...`** commands (local render distance), and `/stonerestorer` works as a full alias of
  `/stonerestore`, tab completion included.
* Existing commands, config file, config screen, reload handling and cache are unchanged.

## How restoration works

The pack is never disabled or edited. On every resource reload `ReloadableResourceManagerImpl.reload` (one mixin)
wraps each enabled pack in `RestoringResourcePack`. The wrapper hides selected files *inside* the pack, so pack
order cannot defeat it: the lookup falls through to the next pack down, ending at the vanilla assets Minecraft
always has loaded (Preserve Other Packs = OFF: the stone pack serves the vanilla file itself instead).

What gets hidden, decided by `StoneScanner` per pack (lazily, on loader threads, cached by file name+size+mtime):

| Asset | Hidden when |
|---|---|
| texture PNG | bytes (or decoded pixels) equal vanilla `stone.png` but vanilla's texture at that path is not stone |
| short/tall grass PNG | pack's file differs from vanilla (typically fully transparent) |
| blockstate / model / particle json | content (not formatting) differs from vanilla **and** one of: it is a short/tall grass blockstate or model; it points at `minecraft:block/stone`; it belongs to a block whose texture was stone-ified (forced 0 degree rotation, changed UVs, moved/floating elements, changed parent) |

Always left alone: stone at stone's own path, assets vanilla doesn't have, custom models unrelated to stone, fire/flame
assets, and every pack with fewer than `minReplacements` (default 8) stone copies. Because whole vanilla
blockstates/models come back, random orientation variants, UVs, particle references and geometry return together.

After the reload each restored identifier is looked up in the real resource manager; anything that still resolves to the
pack's replacement is logged with its exact identifier and is **not** counted. The chat report uses those real numbers.

## Build

Needs JDK 21.

1. Confirm the five values marked `CONFIRM` in `gradle.properties` at https://fabricmc.net/develop/ (1.21.11).
2. Create the Gradle wrapper (`gradle wrapper --gradle-version <version Fabric's template uses>`), or copy it from a
   generated Fabric template.
3. `./gradlew build` -> `build/libs/stonerestore-1.1.0.jar`; put it in `mods/` with Fabric API.

## Commands (`/stonerestore` and `/stonerestorer` are the same tree)

| Command | Effect |
|---|---|
| `on` / `off`, `scan`, `reload`, `status`, `force [off]`, `reset`, `config`, `exclude <pack id>`, `maketestpack` | unchanged from 1.0; `scan`/`status`/the chat report now include models/blockstates |
| `chunk` / `chunk help` | usage + examples |
| `chunk set <3-32>` | sets Minecraft's real render-distance option, writes options.txt, reads the value back, saves it in `config/stonerestore.json` (re-applied once at startup). Rejects <3, >32, negatives, decimals and text with a message; never clamps |
| `chunk max` | same as `set 32` |
| `chunk status` | current setting + saved selection |
| `chunk refresh` | `WorldRenderer.reload()` (same as F3+A), 3 s cooldown, needs a world |
| `chunk diagnose` | compares your setting with the chunks actually loaded around you |

**Render distance vs server:** the command only changes the client option. The chunks you receive are capped by the
server's own `view-distance`; `diagnose` measures the loaded chunks instead of assuming. The main server still needs
**`view-distance=10`** (was 2) in its own `server.properties`, `simulation-distance` unchanged. That is a server
file, not part of this mod, and **has not been applied** - the server files were not available here.

## Test plan

Run `/stonerestore maketestpack`, then enable packs from the resource pack screen.

* **A. Textures/models:** enable `...UnstableVisual.zip`: dirt/gravel/sand/etc. look vanilla and rotate randomly again, the
  red-sand floating slab is gone, chat reports textures *and* models restored.
* **B. Grass:** in the same pack, short and tall grass (both halves) are visible again; world blocks are never touched.
* **C. Particles:** break dirt/sand/gravel/logs/ores/deepslate: material particles (they follow the restored textures).
* **D. Fire:** campfire, soul campfire, candles, fire: untouched by the mod (verify with the pack on and off; `debug` mode
  logs every fire asset skipped).
* **E. Geometry:** only the pack's own stone-related geometry is replaced; your terrain/builds are never altered.
* **F. Reload / reconnect:** F3+T, reorder packs, reconnect: restoration reapplies, second scan comes from the cache.
* **G. Regression:** all 1.0 commands/config work. **H. Chunk commands:** values 3, 5, 10, 16, 32 work; 2, 33, abc, 2.5, -4 are
  rejected with usage; tab completion lists only 3-32.
* `StoneRestore-TestPack-AllStone.zip` and `...-Custom.zip` behave as in 1.0.

## Verified vs not verified

Verified in the build sandbox (JDK 21, no network): `tools/CoreSelfTest.java` runs **87 checks, 0 failed** on the
Minecraft-free core (PNG decoding vs ImageIO, stone detection incl. re-encoded copies, threshold, categories, force
mode, JSON comparison ignoring formatting, fire protection incl. look-alikes such as `fire_coral_block`, `smoker_front`,
`sea_lantern`, short/tall grass, stone-pointing and rotation-reset blockstates, floating-geometry model).
`javac -d out src/main/java/dev/stonerestore/core/*.java tools/CoreSelfTest.java && java -cp out CoreSelfTest`

The Minecraft-facing code compiled only against **hand-written API stubs** - that checks my own syntax and types, not the
real 1.21.11 signatures. **No JAR was built and nothing was run in-game.**

Most likely to need a tweak on the first real build (read real signatures with `./gradlew genSources`):
`ResourcePack` abstract methods; `ReloadableResourceManagerImpl#reload` + its `type` field (mixin); `ResourceReload#whenComplete`;
`ResourcePackProfile/Manager` methods; `Resource#getInputStream`; `GameOptions#getViewDistance/write`; `SimpleOption#setValue`;
`WorldRenderer#reload`; `ChunkManager#getWorldChunk`; `ClientPlayerEntity#getChunkPos`; `MinecraftClient#isIntegratedServerRunning/send/reloadResources`;
`DrawContext#drawCenteredTextWithShadow`; Fabric class names (`ClientCommandRegistrationCallback`, `ClientLifecycleEvents`, `ClientPlayConnectionEvents`).
Also unverified: that `GameOptions.write()` informs the server of the new distance (vanilla's own options packet), runtime mixin behaviour,
the generated test packs' `pack_format`, Sodium compatibility.

## Not covered / limitations

* **16x16 ghost grids are not handled.** They were out of scope for this round, and I have no source for whatever
  generates them. If the real pack builds them from model geometry, the model rules above may remove some of it - that is
  untested. If they come from another client-side renderer, they need that renderer's code.
* The real Unstable pack was not available: the model/blockstate rules follow the described behaviour, and the
  `UnstableVisual` test pack is my approximation (its "full solid block" selection is name based).
* A pack that only changes block **items** or `items/*.json` definitions is not examined.
