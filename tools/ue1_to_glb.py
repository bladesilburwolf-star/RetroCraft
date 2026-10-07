#!/usr/bin/env python3
"""Batch-convert UModel (or manual) mesh exports to GLB for Unrealcraft.

Supports:
  - Already .glb/.gltf files (copied/normalized)
  - .obj (+ optional .mtl / textures) via Blender if --blender is set

Usage:
  python tools/ue1_to_glb.py --in out/ue1_raw --out assets/.../models/glb
  python tools/ue1_to_glb.py --in out/ue1_raw --out out/glb --blender "C:/Program Files/Blender Foundation/Blender 4.2/blender.exe"
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import tempfile


BLENDER_OBJ_SCRIPT = r'''
import bpy, sys, os
argv = sys.argv[sys.argv.index("--") + 1:]
src, dst = argv[0], argv[1]
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.wm.obj_import(filepath=src)
# optional: join meshes
for obj in list(bpy.data.objects):
    if obj.type != 'MESH':
        bpy.data.objects.remove(obj, do_unlink=True)
bpy.ops.object.select_all(action='SELECT')
os.makedirs(os.path.dirname(dst) or ".", exist_ok=True)
bpy.ops.export_scene.gltf(filepath=dst, export_format='GLB', export_image_format='AUTO')
print("OK", dst)
'''


def convert_obj(blender: str, obj_path: str, glb_path: str) -> int:
    with tempfile.NamedTemporaryFile('w', suffix='.py', delete=False) as f:
        f.write(BLENDER_OBJ_SCRIPT)
        script = f.name
    try:
        cmd = [blender, '--background', '--python', script, '--', obj_path, glb_path]
        print(' '.join(cmd))
        return subprocess.call(cmd)
    finally:
        try:
            os.unlink(script)
        except OSError:
            pass


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--in', dest='inp', required=True, help='Folder of UModel exports')
    ap.add_argument('--out', required=True, help='Output folder for .glb')
    ap.add_argument('--blender', default='', help='Blender path (needed for .obj)')
    args = ap.parse_args()
    inp, out = args.inp, args.out
    os.makedirs(out, exist_ok=True)
    n_copy = n_obj = n_fail = 0
    for root, _, files in os.walk(inp):
        for fn in files:
            path = os.path.join(root, fn)
            rel = os.path.relpath(path, inp)
            stem, ext = os.path.splitext(fn)
            el = ext.lower()
            if el in ('.glb', '.gltf'):
                dest = os.path.join(out, stem + '.glb') if el == '.glb' else os.path.join(out, stem + '.glb')
                if el == '.glb':
                    os.makedirs(os.path.dirname(dest) or out, exist_ok=True)
                    # flatten name: replace path seps
                    flat = rel.replace('\\', '/').replace('/', '_')
                    if flat.lower().endswith('.glb'):
                        flat = flat[:-4]
                    dest = os.path.join(out, flat + '.glb')
                    shutil.copy2(path, dest)
                    print('copy', dest)
                    n_copy += 1
                else:
                    print('skip gltf (export glb from Blender):', rel)
                continue
            if el == '.obj':
                if not args.blender:
                    print('need --blender for', rel)
                    n_fail += 1
                    continue
                flat = rel.replace('\\', '/').replace('/', '_')
                if flat.lower().endswith('.obj'):
                    flat = flat[:-4]
                dest = os.path.join(out, flat + '.glb')
                code = convert_obj(args.blender, path, dest)
                if code == 0:
                    n_obj += 1
                else:
                    n_fail += 1
    print(f'done: {n_copy} glb copied, {n_obj} obj converted, {n_fail} failed')
    return 0 if n_fail == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
