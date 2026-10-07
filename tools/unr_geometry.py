#!/usr/bin/env python3
"""Extract brush/poly geometry from Unreal Gold .unr (package v61).

Strategy (v61):
  - Each Brush has a Model; Models reference separate Vectors/Points/Nodes/Surfs/Verts objects.
  - UPolys exports hold editor FPoly arrays (good source of world faces).
  - We parse Polys first → triangulate → OBJ + mesh JSON for Minecraft.

  python tools/unr_geometry.py Maps/Unreal.unr --obj out/ue1_maps/Unreal.obj --json out/ue1_maps/Unreal_mesh.json
"""
from __future__ import annotations

import argparse
import json
import math
import os
import struct
from typing import List, Optional, Tuple

import unr_package as unr

Vec3 = Tuple[float, float, float]


def _f3(data: bytes, pos: int) -> Tuple[Vec3, int]:
    x, y, z = struct.unpack_from('<fff', data, pos)
    return (x, y, z), pos + 12


def _finite(v: Vec3) -> bool:
    return all(math.isfinite(c) and abs(c) < 1e7 for c in v)


def skip_properties(pkg: unr.UnrPackage, blob: bytes, pos: int = 0) -> int:
    """Skip UE1 tagged properties until None; return position after."""
    none_idx = pkg.name_to_index.get('None')
    for _ in range(2000):
        if pos >= len(blob) - 1:
            return pos
        try:
            name_i, pos2 = unr.read_index_buf(blob, pos)
        except (EOFError, IndexError):
            return pos
        pos = pos2
        if name_i == none_idx or pkg.name_str(name_i) == 'None':
            return pos
        if name_i < 0 or name_i >= len(pkg.names):
            return pos
        if pos >= len(blob):
            return pos
        info = blob[pos]
        pos += 1
        try:
            pos = unr._skip_property_value(blob, pos, info, pkg)
        except Exception:
            return pos
    return pos


def parse_fpoly_at(data: bytes, pos: int) -> Tuple[Optional[dict], int]:
    """Try to read one FPoly starting at pos. Returns (poly|None, new_pos)."""
    if pos + 48 + 4 > len(data):
        return None, pos
    try:
        base, p = _f3(data, pos)
        normal, p = _f3(data, p)
        tu, p = _f3(data, p)
        tv, p = _f3(data, p)
        if not (_finite(base) and _finite(normal)):
            return None, pos + 1
        # Vertex count: try INT then clamp
        if p + 4 > len(data):
            return None, pos + 1
        nverts = struct.unpack_from('<i', data, p)[0]
        p += 4
        if nverts < 3 or nverts > 32:
            # alternate: some builds pack count as BYTE after vectors
            return None, pos + 1
        verts = []
        for _ in range(nverts):
            if p + 12 > len(data):
                return None, pos + 1
            v, p = _f3(data, p)
            if not _finite(v):
                return None, pos + 1
            verts.append(v)
        # PolyFlags DWORD (optional consume)
        if p + 4 <= len(data):
            flags = struct.unpack_from('<I', data, p)[0]
            p += 4
        else:
            flags = 0
        # Skip remaining FPoly fields loosely: object refs / names — stop at next plausible poly
        return {
            'base': base,
            'normal': normal,
            'verts': verts,
            'flags': flags,
        }, p
    except struct.error:
        return None, pos + 1


def parse_polys_export(pkg: unr.UnrPackage, exp: unr.ExportEntry) -> List[dict]:
    """Parse a UPolys serial blob into FPoly list."""
    blob = pkg.data[exp.serial_offset:exp.serial_offset + exp.serial_size]
    if exp.serial_size < 16:
        return []
    # Skip UObject properties
    pos = skip_properties(pkg, blob, 0)
    polys = []
    # UPolys::Serialize non-trans: DbNum, DbMax then Element[i]
    if pos + 8 > len(blob):
        return []
    db_num = struct.unpack_from('<i', blob, pos)[0]
    db_max = struct.unpack_from('<i', blob, pos + 4)[0]
    pos += 8
    if db_num < 0 or db_num > 50000:
        # try without property skip — raw at start
        pos = 0
        db_num = struct.unpack_from('<i', blob, 0)[0]
        db_max = struct.unpack_from('<i', blob, 4)[0]
        pos = 8
        if db_num < 0 or db_num > 50000:
            return []
    for i in range(db_num):
        poly, pos2 = parse_fpoly_at(blob, pos)
        if poly is None:
            # resync: scan forward for a valid poly header
            found = False
            for adv in range(pos, min(pos + 512, len(blob) - 64)):
                poly, pos2 = parse_fpoly_at(blob, adv)
                if poly is not None:
                    found = True
                    break
            if not found:
                break
        polys.append(poly)
        pos = pos2
        # After flags, FPoly has more fields (Actor, Texture, ItemName, Link…).
        # Consume a few INDEX/DWORD heuristically so next poly aligns better.
        for _ in range(6):
            if pos + 4 > len(blob):
                break
            try:
                _, pos = unr.read_index_buf(blob, pos)
            except Exception:
                pos += 4
                break
    return polys


def parse_vector_array(pkg: unr.UnrPackage, exp: unr.ExportEntry) -> List[Vec3]:
    """Parse a Vectors/Points-like object: after props, count + float triples."""
    blob = pkg.data[exp.serial_offset:exp.serial_offset + exp.serial_size]
    pos = skip_properties(pkg, blob, 0)
    out = []
    if pos + 4 > len(blob):
        return out
    # Try INDEX count then floats
    try:
        count, pos = unr.read_index_buf(blob, pos)
    except Exception:
        count = struct.unpack_from('<i', blob, pos)[0]
        pos += 4
    if count < 0 or count > 200000 or pos + count * 12 > len(blob) + 64:
        return out
    for _ in range(count):
        if pos + 12 > len(blob):
            break
        v, pos = _f3(blob, pos)
        if _finite(v):
            out.append(v)
    return out


def triangulate(poly: dict) -> List[Tuple[Vec3, Vec3, Vec3]]:
    verts = poly['verts']
    if len(verts) < 3:
        return []
    tris = []
    o = verts[0]
    for i in range(1, len(verts) - 1):
        tris.append((o, verts[i], verts[i + 1]))
    return tris


def ue_to_mc(v: Vec3, upb: float) -> Vec3:
    x, y, z = v
    return (x / upb, z / upb, -y / upb)


def export_geometry(pkg: unr.UnrPackage, out_obj: str, out_json: str, units_per_block: float = 50.0) -> dict:
    all_polys: List[dict] = []
    polys_exports = 0
    for exp in pkg.exports:
        cname = pkg.class_name_of_export(exp)
        if cname != 'Polys':
            continue
        polys_exports += 1
        got = parse_polys_export(pkg, exp)
        all_polys.extend(got)

    # Fallback: harvest points from Vectors exports for debug bounds
    vector_points = 0
    for exp in pkg.exports:
        if pkg.class_name_of_export(exp) not in ('Vectors', 'Points', 'Verts'):
            continue
        pts = parse_vector_array(pkg, exp)
        vector_points += len(pts)

    tris_ue = []
    for poly in all_polys:
        tris_ue.extend(triangulate(poly))

    # Write OBJ in Unreal space (Y up) for Blender check
    os.makedirs(os.path.dirname(out_obj) or '.', exist_ok=True)
    with open(out_obj, 'w', encoding='utf-8') as f:
        f.write(f'# Unreal Gold polys from {os.path.basename(pkg.path)}\n')
        f.write(f'# polys={len(all_polys)} tris={len(tris_ue)}\n')
        vi = 1
        for t in tris_ue:
            for p in t:
                f.write(f'v {p[0]:.4f} {p[1]:.4f} {p[2]:.4f}\n')
            f.write(f'f {vi} {vi+1} {vi+2}\n')
            vi += 3

    # Mesh JSON: triangle soup in Minecraft coords
    mc_tris = []
    for t in tris_ue:
        mc_tris.append([list(ue_to_mc(p, units_per_block)) for p in t])

    doc = {
        'format': 1,
        'name': os.path.splitext(os.path.basename(pkg.path))[0],
        'source': 'unreal_gold_polys',
        'units_per_block': units_per_block,
        'package_version': pkg.version,
        'stats': {
            'polys_exports': polys_exports,
            'polys': len(all_polys),
            'tris': len(tris_ue),
            'vector_points_seen': vector_points,
        },
        'triangles': mc_tris[:50000],  # cap for huge maps
        'note': 'FPoly triangulation from UPolys; BSP node path still optional',
    }
    os.makedirs(os.path.dirname(out_json) or '.', exist_ok=True)
    with open(out_json, 'w', encoding='utf-8') as f:
        json.dump(doc, f, separators=(',', ':'))
    return doc


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('unr')
    ap.add_argument('--obj', required=True)
    ap.add_argument('--json', required=True)
    ap.add_argument('--units-per-block', type=float, default=50.0)
    args = ap.parse_args()
    pkg = unr.load_package(args.unr)
    doc = export_geometry(pkg, args.obj, args.json, args.units_per_block)
    s = doc['stats']
    print(f"Polys exports={s['polys_exports']} polys={s['polys']} tris={s['tris']} "
          f"vector_pts={s['vector_points_seen']}")
    print(f"OBJ → {args.obj}")
    print(f"JSON → {args.json}")


if __name__ == '__main__':
    main()
