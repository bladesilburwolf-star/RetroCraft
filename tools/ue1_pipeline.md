# Unreal Engine 1 → Unrealcraft (GLB) pipeline

You must own the game (Unreal, UT99, etc.). **Do not redistribute** game packages.

## GUI (recommended)

```bat
python tools/ue1_gui.py
```
or double-click `tools/Unrealcraft_Tools.bat`

## Tools you need
1. **UModel** (UE Viewer) — https://www.gildor.org/en/projects/umodel  
2. **Blender** 3.x/4.x with glTF export  
3. This repo’s Fabric mod (`unrealcraft`)

## Steps
1. Point UModel at your UE1 game folder / `.u` packages.
2. Export **Static Meshes** (and textures) to a work folder, e.g. `out/ue1_raw/`.
   - Prefer OBJ+PNG or PSK/PSA + textures, whatever UModel offers for that package.
3. Run the helper script (or convert manually in Blender):

```bat
python tools/ue1_to_glb.py --in out/ue1_raw --out src/main/resources/assets/unrealcraft/models/glb
```

4. Build / run the mod. In game:

```
/ue spawn MyMeshName
/ue spawn MyMeshName 0.5
```

Model name = GLB filename without `.glb`.

Optional: put GLBs outside the jar and set `glbDir` in `config/unrealcraft.json`.

## Why not full maps yet
UE1 maps are brush + actors. This toolkit targets **static meshes** first (weapons, decorations, characters as props) — same strategy that worked for Minewind props, avoiding the broken BSP-style mesh path from HL1/DOOM experiments.

## Unreal Gold maps (.unr)

Maps live in `Unreal/Maps/*.unr` (same package format as `.utx` / `.u`).

### GUI
1. Set **Maps folder** / pick a **.unr file**
2. **Scan selected .unr** — package version, class counts, actor locations
3. **Export actor JSON** → `out/ue1_maps/<map>.json`

### CLI
```bat
python tools/unr_package.py "C:\Unreal\Maps\NyLeve.unr" --json out/ue1_maps/NyLeve.json
```

### Status
- Package header + name/import/export tables: **working**
- Actor **Location** scrape: **best-effort** (property system is compact INDEX-based)
- Full **BSP Model / Polys** → mesh: **not yet** (next phase after actor placement is verified)
