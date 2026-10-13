# Unrealcraft MODLOG

## 2026-10-06
- Scaffold Fabric 1.21.1 mod `unrealcraft`
- GLB prop entity + `/ue spawn` from Minewind-style loader
- Toolkit: `tools/ue1_to_glb.py`, `tools/ue1_pipeline.md` (UModel → GLB)
\n## Unified importer patch — 2026-10-07\n- Separated world-map GLB transforms from item/prop transforms.\n- Removed automatic 45-degree item diagonal and longest-axis normalization from world GLBs.\n- Added map-unit scaling, X/Z centering, sea-level grounding, and surface collision voxelization.\n- Added direct T3D `_mesh.json` map loading.\n- Merged GoldSrc/HL1 BSP v30 + WAD3 import into Unrealcraft.\n- Added Quake 3 BSP v46 and PK3-container loading.\n

## Q3 visibility fix - 2026-10-07
- Q3 maps are drawn client-side (WorldRenderEvents.AFTER_ENTITIES) from a `retro_map` payload instead of a tracked entity. Old entity path was culled whenever the map's Y section was outside render distance (map at mapSeaY, player elsewhere) and untracked past server view distance.
- Q3 placement: X/Z origin = player block, lowest drawable point = mapSeaY, player moved onto the spawn floor (info_player_start / first deathmatch spawn).
- Sky, nodraw, hint, skip and common/clip surfaces are dropped from both render and collision.
- Collision now voxelizes exactly the rendered triangles with the same placement; barriers sit below the visible floor.
- New config: `retroRenderRadius` (blocks, default 192).


## Q3 mesh shredded fix - 2026-10-07
- RetroMapClient emitted 3 vertices per triangle into a QUADS render layer, which regrouped vertices across triangles. Each triangle is now written as a degenerate quad (a, b, c, c).

## SM64 decomp level experiment - 2026-10-07
- New: `/sm64 dir <path-to-your-n64decomp-sm64-clone>`, `/sm64 load <level> [area]`, `/sm64 unload`.
- Level collision is parsed straight from `levels/<level>/areas/<n>/collision.inc.c`; Mario's start from `levels/<level>/script.c`.
- Reuses the Q3 client render path and voxel collision. Surfaces are tinted by SURFACE_* type (slippery = blue, very slippery = cyan, not slippery = tan, burning = orange, hangable = purple...).
- Config: `sm64Dir`, `sm64UnitsPerBlock` (default 100). No ROM needed; textures/audio would need one.

## SM64 levels in the map list - 2026-10-07
- Every level/area with collision data in `sm64Dir` is listed as `sm64_<level>_<area>` (K menu, `/ue map list`, `/ue map load sm64_bob_1`).
- `run/config/unrealcraft.json` now ships with `sm64Dir` set to C:/Users/jwgreer/sm64.

## SM64 Mario physics + Mario mode - 2026-10-08
- New `sm64/Sm64Collision` (port of surface_load.c / surface_collision.c: floors, ceilings, walls, 16x16 spatial grid) and `sm64/Sm64Mario` (port of mario_step.c + parts of mario.c, mario_actions_moving.c, mario_actions_airborne.c).
- Ported: walking/running, braking, jump / double jump / triple jump chaining, freefall, landing, wall kick, air control, bonk reflection, slope accel/decel by surface class. Approximated: steep-floor sliding. Not yet: ledge grab, dive/punch, ground pound, long jump, crouch, water, objects.
- `M` key (or `/sm64 mario`) toggles Mario mode: WASD = stick, Space = A, Shift = Z, mouse = camera. Barrier blocks are removed while it is on and restored when it is turned off.
- Placeholder boxy Mario (client/retro/MarioModel). Real model from actors/mario comes next.
- Headless test on BOB-omb Battlefield: run speed 32, standing jump apex 242 units (= decomp launch speed 42 minus 4/frame), JUMP -> DOUBLE_JUMP -> TRIPLE_JUMP chain, 3000-frame wall stress without falling out of the level.

## SM64 textured levels - 2026-10-08
- New `sm64/F3d` (reads and executes the decomp's F3D display lists: vertices, triangles, nested lists, lights, geometry mode, texture images), `sm64/Sm64Visual` (level area geo layout -> display lists -> textured triangles), `sm64/Sm64Png` (channel-exact reader for the PNGs n64graphics writes).
- Client: `Sm64VisualMesh` + `Sm64Textures` draw the real level model with your extracted ROM textures. Lighting from the decomp's Lights1 is baked into vertex colours. Decal layers are offset along their normal to avoid z-fighting.
- Falls back to the tinted collision shape when a level's visual model is incomplete (visual footprint < 90% of collision: ttm, rr, wf, totwc, lll, bits/bitdw/bitfs: their terrain lives in sub-layouts not read yet) or when `sm64Textures` is false.
- Missing PNGs (ROM assets not extracted) render white with vertex colours. Check latest.log for "SM64 texture missing".
- Tested against all 30 levels with collision data: 0 unresolved symbols on 29 of them; BOB-omb Battlefield visual bounds equal collision bounds exactly.

## Merge of the Grok pass (HUD, props, extra movement) - 2026-10-08
- Added `Sm64Hud` (lives/stars/coins/power meter + current action; the counters are placeholders), `Sm64Objects` + `Sm64PropRenderer` (trees, coins, stars, signs read from collision.inc.c / macro.inc.c / script.c and drawn as placeholder boxes), long jump, backflip, dive, ground pound, crouch, B = Ctrl/Sprint.
- Fixed while merging: `Sm64Visual.textureSizes` had been stubbed to an empty map (every texture would have been scaled as 32x32); dive now adds 15 speed capped at 48, long jump uses the decomp's gravity of 2 per frame, and ground pound hovers (20-2t per frame for 10 frames) before falling; object loader only reads the objects of the loaded AREA from script.c, strips comments, and no longer matches OBJECT inside SPECIAL_OBJECT/MACRO_OBJECT; prop yaw is degrees, not s16.
- Fixed: the dark ovals on the grass were alpha-blended decals (ia16 textures) drawn opaque. LAYER_TRANSPARENT* geometry is now drawn alpha-blended.
- Fixed: rooms in bbh / hmc / castle_inside each list their neighbours' display lists, so the same display list was drawn up to 5 times. Display lists are now de-duplicated (about 4x fewer triangles, no z-fighting).
- Not merged: `sm64-mario-mod` (a separate standalone Fabric mod with the same M key and Mario mode). Do not install its jar next to this one.

## Overnight pass merged (spawn eggs, enemies, more props, coin pickup) - 2026-10-09
- New: `entity/Sm64MobEntity` (+ `Sm64MobRenderer`), `item/Sm64SpawnEggItem` (18 eggs in the creative tab "Unrealcraft / SM64", plus door/sign/coin/star stand-ins), `Sm64Collectibles` (coin/star pickup in Mario mode, client side, bumps the HUD), `Sm64Enemies` + `Sm64EnemyRenderer` (level enemy spawn markers), prop shapes for cannon/door/sign/flag/chest/box/platform/mine/butterfly/red+blue coins.
- Fixed while merging:
  - `ModItems` used `Item.Settings.registryKey()` (Minecraft 1.21.2+; this project is 1.21.1): removed.
  - The eggs had no item models (they would show as the missing-texture cube): added `models/item/spawn_*.json`.
  - `lang/en_us.json` merged instead of replaced.
  - The overnight `Sm64Objects` came from before the loader fixes (comment stripping, per-area filter, OBJECT boundary): kept my loader, replaced only the classifier.
  - Classifier rewritten token based (so "amp" no longer matches "ramp") and checked against all 456 distinct model/preset names in the decomp. Level-geometry pieces (`special_level_geo_*`, `MODEL_LEVEL_GEOMETRY_*`) are skipped instead of drawn as boxes. `MODEL_BOBOMB_BUDDY` is a friendly NPC, not an enemy. `MODEL_KOOPA_WITH_SHELL` is the Koopa. Only the real Bowser boss counts as `bowser` (18 decorative `*_BOWSER_*` pieces no longer do).
  - `king_bobomb` could never match (`bobomb` was tested first).
  - script.c objects live in `script_func_local_N` functions that an AREA calls; the area filter now follows those calls (Whomp's Fortress: 0 -> 2 Thwomps, 2 Whomps, 3 Piranha Plants).
  - Mob hitbox per kind was computed but never applied; mobs are persistent so they do not despawn.

## F3D pass merged + crash fix (BOB / WF) - 2026-10-09
- Cause of the BOB / WF crash: `Sm64PropRenderer.draw` fetched one VertexConsumer, then the F3D tree code requested a different render layer. Minecraft's immediate renderer ends the previous custom-texture buffer when another layer is requested, so every box / sign / cannon drawn after the first tree wrote into a closed buffer. BOB (17 trees among 8 boxes, 6 cannons, 13 signs) and WF hit it immediately. Fixed: a fresh buffer is fetched per prop and never kept across another getBuffer().
- The F3D tree drew raw N64 units (hundreds) as blocks and divided UVs by 32 twice, which produced giant squashed trees. Now 0.01 block per unit, UVs normalised by the texture size (`Sm64Visual.textureSize`), one buffer per texture (the bubbly tree is two quads with two textures), alpha-cutout, billboarded to face the camera.
- Levels whose terrain is only partly read (visual coverage < 90%: rr, bitdw/bitfs/bits, totwc, ttm, wf, lll): the textured part is kept and the collision shape is drawn tinted 4 units behind every surface so the rest of the level is visible instead of an invisible floor. Their "use any non-empty visual" gate is kept, but with that backdrop.
- Their `Sm64Visual` (area geo + GEO_BRANCH only, no object geos, 150k triangle cap) produced identical output to the previous version in tests on all 30 levels, no OOM at 512 MB.
