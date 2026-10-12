# Unrealcraft Unified Importer

Fabric 1.21.1 importer for Unreal/Quake/Half-Life world geometry, GLB assets, and T3D-derived meshes.

## Supported import paths

- **GLB world maps** — `/ue map list` + `/ue map load <name>`
- **GLB props/items** — `/ue spawn <ModelName> [scale]`
- **Unreal T3D** — `.t3d` text map exports can be loaded directly with `/ue map load <name>`. Brush polygon geometry is parsed at runtime; no BSP intermediate or manual conversion is needed.
- **Half-Life 1 / GoldSrc BSP v30 + WAD3** — `/hl1 load <path-to-bsp>`
- **Quake 3 BSP v46** — `/q3 load <path-to-bsp>`
- **Quake 3 PK3** — `/q3 load <path-to-pk3> [map-name]`

The importer does not require Source Engine libraries.

## World-map transform rules

World imports are deliberately separate from item-model transforms.

1. No automatic longest-axis normalization.
2. No vanilla-item 45° diagonal rotation.
3. GLB geometry is scaled by `mapUnitsPerBlock`.
4. The X/Z bounds are centred around the player.
5. The lowest geometry point is placed at `mapSeaY`.
6. Collision is generated as invisible barrier surface voxels, with a configurable safety limit.

Blender-exported GLB is normally already in glTF's Y-up coordinate system, so the default GLB map rotation is zero. Change `mapRotX/Y/Z` only when the source export actually needs an additional axis conversion.

## Config

`config/unrealcraft.json`:

- `glbDir` — optional external GLB folder
- `defaultScale` — `/ue spawn` prop scale
- `mapSeaY` — default 63
- `mapUnitsPerBlock` — default 50 for UE1/Quake-style map units
- `mapRotX`, `mapRotY`, `mapRotZ` — optional GLB map rotation
- `buildCollision` — enable/disable invisible collision
- `collisionBlockLimit` — maximum generated collision blocks
- `hl1BspPath`
- `hl1WadPath` — semicolon-separated WAD3 files
- `hl1UnitsPerBlock` — default 32

## Quake 3 notes

Q3 maps are drawn client-side (not as entities) with the lowest geometry at `mapSeaY`, X/Z origin at the player, and the player moved onto the first spawn point. Sky/nodraw surfaces are skipped. Surfaces are flat-tinted per shader until texture loading lands.

PK3 files are ZIP containers. The importer extracts the selected BSP to a cache directory and imports the native BSP geometry. Polygon and mesh faces are supported; curved patch faces are intentionally left for the next geometry pass.

## T3D notes

The existing converter already transforms Unreal coordinates to Minecraft coordinates and writes scaled triangles:

```text
python tools/t3d_to_mesh.py Map.t3d --obj out/Map.obj --json out/Map_mesh.json --units-per-block 50
```

The Python converter remains available for OBJ/JSON export workflows. Direct `.t3d` imports create a companion mesh cache beside the source file when loaded.

## Collision

Collision is generated from imported map surfaces rather than the renderer's entity bounding box. This keeps the visual mesh independent of Minecraft collision geometry.

For very large maps, increase `collisionBlockLimit` carefully. A map that exceeds the limit will still render, but collision generation stops at the configured limit.

## Assets

Do not redistribute commercial Unreal, Quake, or Half-Life game data. Point the importer at game files you own.

## Super Mario 64 (decomp source) levels

Clone https://github.com/n64decomp/sm64, then in game:

```text
/sm64 dir "C:/path/to/sm64"
/sm64 load bob
/sm64 load castle_grounds 1
/sm64 unload
```

Geometry comes from the decomp's `collision.inc.c`; no ROM is needed for this. Textures are not included in the repo and are not loaded yet.

### Mario mode and Minecraft build mode

With an SM64 level loaded, open the pause menu and choose **Enter Mario Mode** to play it with Mario's ported movement physics. Choose **Enter Build Mode** in the pause menu to return to normal Minecraft movement, avatar, hotbar, inventory, and block building. `/sm64 mario` remains available as a command; there is no toggle hotkey.

| Key | Action |
|-----|--------|
| WASD | stick (relative to the camera) |
| Space | A: jump, double jump, triple jump (chain on landing), wall kick near a wall in the air |
| Shift | Z: crouch; while running + Space = long jump; crouched/still + Space = backflip; in the air = ground pound |
| Ctrl (sprint) | B: dive (in the air) |
| Mouse | camera |
| Pause menu | switch between Mario Mode and Minecraft Build Mode |

### SM64 textures

Run `python3 extract_assets.py us` once in your sm64 clone (needs `baserom.us.z64`), then load a level normally. Levels whose model is fully read are drawn with their real geometry and textures; the rest fall back to the tinted collision shape. Set `"sm64Textures": false` in `run/config/unrealcraft.json` to always use the collision shape.

### SM64 enemies, props and spawn eggs

- Open the creative inventory, tab **Unrealcraft / SM64**: spawn eggs for Goomba, Koopa, Bob-omb, Bully, Boo, Whomp, Thwomp, Piranha Plant, Chain Chomp, Fly Guy, Lakitu, Chuckya, Amp, Mr. I, King Bob-omb and Bowser. They are placeholder boxes with no AI yet and do not despawn.
- Loading an SM64 level reads placed actors from its area script, shared level scripts, collision specials, and both area and level macro lists. Trees, coins, signs, doors, cannons, boxes, platforms, and recognized enemies are drawn at their source positions; supported props and enemies use F3D meshes from the local decomp's `actors/` tree. Unsupported models fall back to simple markers. Enemies are static and do not have SM64 AI or animation yet.
- In Mario mode, walking into a coin or star marker collects it and updates the HUD (client side only for now).
