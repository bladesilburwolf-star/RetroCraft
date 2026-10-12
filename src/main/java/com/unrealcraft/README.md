# Safe object F3D + solid collision (Fabric 1.21.1)

Does **not** merge level sub-geos (avoids BOB/WF crash). Only places individual actors at script positions.

## Copy

```
sm64/Sm64ActorModels.java      → replace
sm64/Sm64PropCollision.java    → new
client/retro/Sm64PropRenderer.java → replace
client/retro/MarioClient.java  → replace (or patch collision line only)
```

## What you get

| Prop | Visual | Collision (Mario mode) |
|------|--------|------------------------|
| tree | F3D (already working) | no |
| coin / red / blue | F3D if `actors/coin` exists | no (pickup) |
| star | F3D if `actors/star` | no |
| sign | F3D if `actors/wooden_signpost` | yes (thin post) |
| cannon | F3D if found | yes |
| door | F3D if found | yes |
| box / chest / mine | F3D if found | yes |
| platform | box fallback (level platforms are local geos) | yes (plank AABB) |

Collision is AABB boxes in SM64 units, merged into `Sm64Collision` when Mario mode starts.

## Note

Platforms that are **level object geos** (RR swinging platforms, etc.) still need a later pass with per-object transform — not included here for safety.
