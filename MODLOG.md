# Unrealcraft MODLOG

## 2026-10-06
- Scaffold Fabric 1.21.1 mod `unrealcraft`
- GLB prop entity + `/ue spawn` from Minewind-style loader
- Toolkit: `tools/ue1_to_glb.py`, `tools/ue1_pipeline.md` (UModel → GLB)
\n## Unified importer patch — 2026-10-07\n- Separated world-map GLB transforms from item/prop transforms.\n- Removed automatic 45-degree item diagonal and longest-axis normalization from world GLBs.\n- Added map-unit scaling, X/Z centering, sea-level grounding, and surface collision voxelization.\n- Added direct T3D `_mesh.json` map loading.\n- Merged GoldSrc/HL1 BSP v30 + WAD3 import into Unrealcraft.\n- Added Quake 3 BSP v46 and PK3-container loading.\n