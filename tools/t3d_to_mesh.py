#!/usr/bin/env python3
"""Convert Unreal Editor .t3d map export to OBJ + mesh JSON (Minecraft axes).

Handles Brush actors with PolyList / Polygon / Vertex lines, applying
Location, PrePivot, and PostScale/MainScale when present.

  python tools/t3d_to_mesh.py Unreal.t3d --obj out/ue1_maps/Unreal.obj --json out/ue1_maps/Unreal_mesh.json
"""
from __future__ import annotations

import argparse
import json
import math
import os
import re
from typing import Dict, List, Optional, Tuple

Vec3 = Tuple[float, float, float]

RE_LOCATION = re.compile(
    r'Location=\(X=([-\d.]+),Y=([-\d.]+),Z=([-\d.]+)\)'
)
RE_PREPIVOT = re.compile(
    r'PrePivot=\(X=([-\d.]+),Y=([-\d.]+),Z=([-\d.]+)\)'
)
RE_SCALE = re.compile(
    r'(?:PostScale|MainScale|TempScale)=\(Scale=\(X=([-\d.]+),Y=([-\d.]+),Z=([-\d.]+)\)'
)
RE_VERTEX = re.compile(
    r'Vertex\s+([+\-\d.]+),([+\-\d.]+),([+\-\d.]+)'
)
RE_ACTOR = re.compile(r'^Begin Actor Class=(\S+) Name=(\S+)')
RE_CSG = re.compile(r'CsgOper=(\w+)')


def parse_vec(m: re.Match) -> Vec3:
    return (float(m.group(1)), float(m.group(2)), float(m.group(3)))


def transform_vert(v: Vec3, location: Vec3, prepivot: Vec3, scale: Vec3) -> Vec3:
    """Brush local vertex → world (Unreal: X,Y horizontal, Z up)."""
    x = (v[0] - prepivot[0]) * scale[0] + location[0]
    y = (v[1] - prepivot[1]) * scale[1] + location[1]
    z = (v[2] - prepivot[2]) * scale[2] + location[2]
    return (x, y, z)


def ue_to_mc(v: Vec3, upb: float) -> Vec3:
    x, y, z = v
    return (x / upb, z / upb, -y / upb)


def triangulate(verts: List[Vec3]) -> List[Tuple[Vec3, Vec3, Vec3]]:
    if len(verts) < 3:
        return []
    tris = []
    o = verts[0]
    for i in range(1, len(verts) - 1):
        tris.append((o, verts[i], verts[i + 1]))
    return tris


def parse_t3d(path: str) -> dict:
    with open(path, 'r', encoding='latin1', errors='replace') as f:
        lines = f.readlines()

    brushes = []
    actors_other = []
    i = 0
    n = len(lines)

    while i < n:
        line = lines[i].strip()
        m = RE_ACTOR.match(line)
        if not m:
            i += 1
            continue
        cls, name = m.group(1), m.group(2)
        block = [line]
        i += 1
        depth = 1
        while i < n and depth > 0:
            l = lines[i]
            block.append(l.rstrip('\n'))
            s = l.strip()
            if s.startswith('Begin '):
                depth += 1
            elif s.startswith('End '):
                depth -= 1
            i += 1
        text = '\n'.join(block)

        location = (0.0, 0.0, 0.0)
        prepivot = (0.0, 0.0, 0.0)
        scale = (1.0, 1.0, 1.0)
        lm = RE_LOCATION.search(text)
        if lm:
            location = parse_vec(lm)
        pm = RE_PREPIVOT.search(text)
        if pm:
            prepivot = parse_vec(pm)
        # Prefer PostScale, else MainScale, else TempScale (last wins in finditer order)
        for sm in RE_SCALE.finditer(text):
            scale = parse_vec(sm)

        if cls != 'Brush':
            actors_other.append({
                'class': cls,
                'name': name,
                'location': list(location),
            })
            continue

        csg = 'CSG_Add'
        cm = RE_CSG.search(text)
        if cm:
            csg = cm.group(1)

        # Extract polygons
        polys = []
        in_poly = False
        verts: List[Vec3] = []
        texture = ''
        for bl in block:
            s = bl.strip()
            if s.startswith('Begin Polygon'):
                in_poly = True
                verts = []
                texture = ''
                tm = re.search(r'Texture=([^\s]+)', s)
                if tm:
                    texture = tm.group(1)
                continue
            if s.startswith('End Polygon'):
                if in_poly and len(verts) >= 3:
                    world = [transform_vert(v, location, prepivot, scale) for v in verts]
                    polys.append({'texture': texture, 'verts': world})
                in_poly = False
                continue
            if in_poly:
                vm = RE_VERTEX.search(s)
                if vm:
                    verts.append((float(vm.group(1)), float(vm.group(2)), float(vm.group(3))))

        if polys:
            brushes.append({
                'name': name,
                'csg': csg,
                'location': list(location),
                'prepivot': list(prepivot),
                'scale': list(scale),
                'polys': polys,
            })

    return {'brushes': brushes, 'actors': actors_other}


def export_mesh(t3d_path: str, out_obj: str, out_json: str, units_per_block: float = 50.0) -> dict:
    data = parse_t3d(t3d_path)
    tris_ue: List[Tuple[Vec3, Vec3, Vec3]] = []
    poly_count = 0
    for b in data['brushes']:
        # Skip pure subtract-only if we only want additive shell? Include all for now.
        for poly in b['polys']:
            poly_count += 1
            tris_ue.extend(triangulate(poly['verts']))

    os.makedirs(os.path.dirname(out_obj) or '.', exist_ok=True)
    with open(out_obj, 'w', encoding='utf-8') as f:
        f.write(f'# T3D from {os.path.basename(t3d_path)}\n')
        f.write(f'# brushes={len(data["brushes"])} polys={poly_count} tris={len(tris_ue)}\n')
        vi = 1
        for t in tris_ue:
            for p in t:
                f.write(f'v {p[0]:.6f} {p[1]:.6f} {p[2]:.6f}\n')
            f.write(f'f {vi} {vi+1} {vi+2}\n')
            vi += 3

    mc_tris = []
    for t in tris_ue:
        mc_tris.append([list(ue_to_mc(p, units_per_block)) for p in t])

    # Actor locations for lights etc.
    objects = []
    for a in data['actors']:
        x, y, z = a['location']
        objects.append({
            'id': a['name'],
            'type': a['class'],
            'model': '',
            'pos': [
                round(x / units_per_block, 3),
                round(z / units_per_block, 3),
                round(-y / units_per_block, 3),
            ],
            'quat': [0, 0, 0, 1],
            'scale': 1.0,
        })

    doc = {
        'format': 1,
        'name': os.path.splitext(os.path.basename(t3d_path))[0],
        'source': 'unreal_t3d',
        'units_per_block': units_per_block,
        'stats': {
            'brushes': len(data['brushes']),
            'polys': poly_count,
            'tris': len(tris_ue),
            'actors': len(data['actors']),
        },
        'triangles': mc_tris[:100000],
        'objects': objects[:2000],
        'note': 'T3D brush polys (CSG not boolean-resolved; additive+subtract faces both exported)',
    }
    os.makedirs(os.path.dirname(out_json) or '.', exist_ok=True)
    with open(out_json, 'w', encoding='utf-8') as f:
        json.dump(doc, f, separators=(',', ':'))
    return doc


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('t3d')
    ap.add_argument('--obj', required=True)
    ap.add_argument('--json', required=True)
    ap.add_argument('--units-per-block', type=float, default=50.0)
    args = ap.parse_args()
    doc = export_mesh(args.t3d, args.obj, args.json, args.units_per_block)
    s = doc['stats']
    print(f"brushes={s['brushes']} polys={s['polys']} tris={s['tris']} actors={s['actors']}")
    print(f"OBJ → {args.obj}")
    print(f"JSON → {args.json}")


if __name__ == '__main__':
    main()
