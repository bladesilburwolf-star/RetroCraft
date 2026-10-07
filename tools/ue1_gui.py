#!/usr/bin/env python3
"""Unreal Engine 1 → GLB toolkit GUI for Unrealcraft.

  python tools/ue1_gui.py

Owns-your-game only. Uses UModel (external) + optional Blender for OBJ→GLB,
then you load meshes in-game with /ue spawn <name>.
"""
from __future__ import annotations

import json
import os
import queue
import shutil
import subprocess
import sys
import tempfile
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
from tkinter.scrolledtext import ScrolledText

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
try:
    import unr_package as unr
except ImportError:
    unr = None
try:
    import unr_geometry as unr_geo
except ImportError:
    unr_geo = None
try:
    import t3d_to_mesh as t3dmesh
except ImportError:
    t3dmesh = None

ROOT = os.path.abspath(os.path.join(HERE, '..'))
SETTINGS = os.path.join(HERE, 'ue1_gui_settings.json')

BLENDER_OBJ_SCRIPT = r'''
import bpy, sys, os
argv = sys.argv[sys.argv.index("--") + 1:]
src, dst = argv[0], argv[1]
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.wm.obj_import(filepath=src)
for obj in list(bpy.data.objects):
    if obj.type != 'MESH':
        bpy.data.objects.remove(obj, do_unlink=True)
bpy.ops.object.select_all(action='SELECT')
os.makedirs(os.path.dirname(dst) or ".", exist_ok=True)
bpy.ops.export_scene.gltf(filepath=dst, export_format='GLB', export_image_format='AUTO')
print("OK", dst)
'''


def load_settings():
    if os.path.isfile(SETTINGS):
        try:
            with open(SETTINGS, encoding='utf-8') as f:
                return json.load(f)
        except Exception:
            pass
    return {}


def save_settings(data):
    with open(SETTINGS, 'w', encoding='utf-8') as f:
        json.dump(data, f, indent=2)


class Ue1App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title('Unrealcraft — UE1 → GLB Tools')
        self.geometry('880x700')
        self.minsize(720, 560)
        s = load_settings()
        self.q = queue.Queue()
        self.busy = False

        self.vars = {
            'umodel': tk.StringVar(value=s.get('umodel', '')),
            'game_dir': tk.StringVar(value=s.get('game_dir', '')),
            'raw_dir': tk.StringVar(value=s.get('raw_dir', os.path.join(ROOT, 'out', 'ue1_raw'))),
            'glb_out': tk.StringVar(value=s.get('glb_out', os.path.join(
                ROOT, 'src', 'main', 'resources', 'assets', 'unrealcraft', 'models', 'glb'))),
            'blender': tk.StringVar(value=s.get('blender', '')),
            'filter': tk.StringVar(value=s.get('filter', '')),
            'maps_dir': tk.StringVar(value=s.get('maps_dir', '')),
            'unr_file': tk.StringVar(value=s.get('unr_file', '')),
            't3d_file': tk.StringVar(value=s.get('t3d_file', '')),
        }
        self.status = tk.StringVar(value='Set UModel + game folder, export meshes, convert to GLB.')
        self._build()
        self.after(100, self._poll)
        self._refresh_list()

    def _build(self):
        pad = dict(padx=8, pady=4)

        paths = ttk.LabelFrame(self, text='Paths')
        paths.pack(fill='x', **pad)
        for key, label, filemode in (
            ('umodel', 'UModel (ueviewer) executable', 'file'),
            ('game_dir', 'UE1 game / System folder', 'dir'),
            ('raw_dir', 'UModel export folder (raw)', 'dir'),
            ('glb_out', 'GLB output (mod assets)', 'dir'),
            ('blender', 'Blender (for .obj → .glb)', 'file'),
        ):
            fr = ttk.Frame(paths)
            fr.pack(fill='x', padx=6, pady=2)
            ttk.Label(fr, text=label, width=28).pack(side='left')
            ttk.Entry(fr, textvariable=self.vars[key]).pack(side='left', fill='x', expand=True, padx=4)
            ttk.Button(fr, text='…', width=3,
                       command=lambda k=key, m=filemode: self._browse(k, m)).pack(side='left')

        steps = ttk.LabelFrame(self, text='Pipeline')
        steps.pack(fill='x', **pad)
        b = ttk.Frame(steps)
        b.pack(fill='x', padx=6, pady=6)
        ttk.Button(b, text='1) Open UModel', command=self.do_open_umodel).pack(side='left', padx=4)
        ttk.Button(b, text='2) Open raw folder', command=self.do_open_raw).pack(side='left', padx=4)
        ttk.Button(b, text='3) Scan raw exports', command=self.do_scan).pack(side='left', padx=4)
        ttk.Button(b, text='4) Convert selected → GLB', command=self.do_convert).pack(side='left', padx=4)
        ttk.Button(b, text='Convert all → GLB', command=self.do_convert_all).pack(side='left', padx=4)

        filt = ttk.Frame(steps)
        filt.pack(fill='x', padx=6, pady=(0, 6))
        ttk.Label(filt, text='Filter').pack(side='left')
        ttk.Entry(filt, textvariable=self.vars['filter'], width=30).pack(side='left', padx=4)
        ttk.Button(filt, text='Apply', command=self._refresh_list).pack(side='left', padx=4)
        ttk.Button(filt, text='Select all', command=self._select_all).pack(side='left', padx=4)
        ttk.Button(filt, text='Select none', command=self._select_none).pack(side='left', padx=4)

        list_fr = ttk.LabelFrame(self, text='Exported meshes in raw folder')
        list_fr.pack(fill='both', expand=True, **pad)
        cols = ('kind', 'rel')
        self.tree = ttk.Treeview(list_fr, columns=cols, show='headings', selectmode='extended')
        self.tree.heading('kind', text='Type')
        self.tree.heading('rel', text='File')
        self.tree.column('kind', width=70, stretch=False)
        self.tree.column('rel', width=600, stretch=True)
        sy = ttk.Scrollbar(list_fr, orient='vertical', command=self.tree.yview)
        self.tree.configure(yscrollcommand=sy.set)
        self.tree.pack(side='left', fill='both', expand=True)
        sy.pack(side='right', fill='y')
        self._files = []  # list of (kind, abspath, rel)


        maps = ttk.LabelFrame(self, text='Unreal Gold maps (.unr)')
        maps.pack(fill='x', **pad)
        mf = ttk.Frame(maps)
        mf.pack(fill='x', padx=6, pady=4)
        ttk.Label(mf, text='Maps folder', width=14).pack(side='left')
        ttk.Entry(mf, textvariable=self.vars['maps_dir']).pack(side='left', fill='x', expand=True, padx=4)
        ttk.Button(mf, text='…', width=3, command=lambda: self._browse('maps_dir', 'dir')).pack(side='left')
        mf2 = ttk.Frame(maps)
        mf2.pack(fill='x', padx=6, pady=2)
        ttk.Label(mf2, text='.unr file', width=14).pack(side='left')
        ttk.Entry(mf2, textvariable=self.vars['unr_file']).pack(side='left', fill='x', expand=True, padx=4)
        ttk.Button(mf2, text='…', width=3, command=self._browse_unr).pack(side='left')
        mb = ttk.Frame(maps)
        mb.pack(fill='x', padx=6, pady=6)
        ttk.Button(mb, text='List .unr in folder', command=self.do_list_unr).pack(side='left', padx=4)
        ttk.Button(mb, text='Scan selected .unr', command=self.do_scan_unr).pack(side='left', padx=4)
        ttk.Button(mb, text='Export actor JSON', command=self.do_export_unr_json).pack(side='left', padx=4)
        ttk.Button(mb, text='Export geometry (OBJ+JSON)', command=self.do_export_geometry).pack(side='left', padx=4)
        mf3 = ttk.Frame(maps)
        mf3.pack(fill='x', padx=6, pady=2)
        ttk.Label(mf3, text='.t3d file', width=14).pack(side='left')
        ttk.Entry(mf3, textvariable=self.vars['t3d_file']).pack(side='left', fill='x', expand=True, padx=4)
        ttk.Button(mf3, text='…', width=3, command=self._browse_t3d).pack(side='left')
        ttk.Button(mf3, text='Convert T3D → OBJ', command=self.do_t3d_convert).pack(side='left', padx=4)

        help_fr = ttk.LabelFrame(self, text='In-game')
        help_fr.pack(fill='x', **pad)
        ttk.Label(
            help_fr,
            text='After GLBs are in the mod assets folder:  /ue spawn <NameWithoutGlb>  [scale]',
            justify='left',
        ).pack(anchor='w', padx=8, pady=6)

        ttk.Label(self, textvariable=self.status).pack(anchor='w', padx=10)
        self.log = ScrolledText(self, height=10, state='disabled', font=('Consolas', 9))
        self.log.pack(fill='x', padx=8, pady=4)

        ttk.Button(self, text='Save settings', command=self._save).pack(anchor='e', padx=10, pady=4)

    def _browse(self, key, mode):
        if mode == 'file':
            p = filedialog.askopenfilename(title=key)
        else:
            p = filedialog.askdirectory(title=key)
        if p:
            self.vars[key].set(p)

    def _save(self):
        save_settings({k: v.get().strip() for k, v in self.vars.items()})
        self._log('Settings saved.')

    def _log(self, msg):
        self.q.put(('log', msg))

    def _set_status(self, msg):
        self.q.put(('status', msg))

    def _poll(self):
        try:
            while True:
                kind, payload = self.q.get_nowait()
                if kind == 'log':
                    self.log.configure(state='normal')
                    self.log.insert('end', payload + '\n')
                    self.log.see('end')
                    self.log.configure(state='disabled')
                elif kind == 'status':
                    self.status.set(payload)
                elif kind == 'list':
                    self._files = payload
                    self.tree.delete(*self.tree.get_children())
                    for i, (k, _abs, rel) in enumerate(payload):
                        self.tree.insert('', 'end', iid=str(i), values=(k, rel))
                elif kind == 'done':
                    self.busy = False
        except queue.Empty:
            pass
        self.after(100, self._poll)

    def _run(self, fn):
        if self.busy:
            messagebox.showinfo('Busy', 'Wait for the current job.')
            return
        self.busy = True
        threading.Thread(target=self._wrap, args=(fn,), daemon=True).start()

    def _wrap(self, fn):
        try:
            fn()
        except Exception as e:
            self._log(f'ERROR: {e}')
            self._set_status(str(e))
        finally:
            self.q.put(('done', None))

    def do_open_umodel(self):
        um = self.vars['umodel'].get().strip()
        game = self.vars['game_dir'].get().strip()
        raw = self.vars['raw_dir'].get().strip()
        if not um or not os.path.isfile(um):
            messagebox.showerror('UModel', 'Set a valid UModel executable path.')
            return
        os.makedirs(raw, exist_ok=True)
        # Launch GUI; user exports from UModel. Pass game path when possible.
        cmd = [um]
        if game:
            cmd += ['-path=' + game]
        try:
            subprocess.Popen(cmd, cwd=os.path.dirname(um) or None)
            self._log('Launched UModel. Export Static Meshes to:\n  ' + raw)
            self._set_status('Export meshes into the raw folder, then Scan.')
        except Exception as e:
            messagebox.showerror('UModel', str(e))

    def do_open_raw(self):
        raw = self.vars['raw_dir'].get().strip()
        os.makedirs(raw, exist_ok=True)
        if sys.platform.startswith('win'):
            os.startfile(raw)  # type: ignore
        elif sys.platform == 'darwin':
            subprocess.Popen(['open', raw])
        else:
            subprocess.Popen(['xdg-open', raw])

    def do_scan(self):
        self._run(self._scan)

    def _scan(self):
        raw = self.vars['raw_dir'].get().strip()
        if not os.path.isdir(raw):
            raise RuntimeError('Raw folder does not exist')
        filt = self.vars['filter'].get().strip().lower()
        found = []
        for root, _, files in os.walk(raw):
            for fn in files:
                el = os.path.splitext(fn)[1].lower()
                if el not in ('.obj', '.glb', '.gltf', '.psk'):
                    continue
                abspath = os.path.join(root, fn)
                rel = os.path.relpath(abspath, raw).replace('\\', '/')
                if filt and filt not in rel.lower():
                    continue
                kind = el[1:].upper()
                found.append((kind, abspath, rel))
        found.sort(key=lambda x: x[2].lower())
        self._log(f'Scan: {len(found)} mesh files in {raw}')
        self.q.put(('list', found))
        self._set_status(f'{len(found)} files — select and convert')

    def _refresh_list(self):
        if self.vars['raw_dir'].get().strip() and os.path.isdir(self.vars['raw_dir'].get().strip()):
            self.do_scan()

    def _select_all(self):
        self.tree.selection_set(self.tree.get_children())

    def _select_none(self):
        self.tree.selection_remove(self.tree.get_children())

    def _selected_paths(self):
        out = []
        for iid in self.tree.selection():
            try:
                out.append(self._files[int(iid)])
            except (IndexError, ValueError):
                pass
        return out

    def do_convert(self):
        sel = self._selected_paths()
        if not sel:
            messagebox.showinfo('Convert', 'Select one or more files (or use Convert all).')
            return
        self._run(lambda: self._convert(sel))

    def do_convert_all(self):
        if not self._files:
            self._scan()
        self._run(lambda: self._convert(list(self._files)))

    def _convert(self, items):
        self._save()
        out = self.vars['glb_out'].get().strip()
        blender = self.vars['blender'].get().strip()
        os.makedirs(out, exist_ok=True)
        n_ok = n_fail = 0
        for kind, abspath, rel in items:
            flat = rel.replace('/', '_').replace('\\', '_')
            stem = os.path.splitext(flat)[0]
            dest = os.path.join(out, stem + '.glb')
            try:
                if kind == 'GLB':
                    shutil.copy2(abspath, dest)
                    self._log(f'copy {stem}.glb')
                    n_ok += 1
                elif kind == 'GLTF':
                    self._log(f'skip GLTF (open in Blender, export GLB): {rel}')
                    n_fail += 1
                elif kind == 'OBJ':
                    if not blender or not os.path.isfile(blender):
                        self._log(f'need Blender path for OBJ: {rel}')
                        n_fail += 1
                        continue
                    code = self._obj_to_glb(blender, abspath, dest)
                    if code == 0:
                        self._log(f'obj→glb {stem}.glb')
                        n_ok += 1
                    else:
                        self._log(f'Blender failed ({code}): {rel}')
                        n_fail += 1
                elif kind == 'PSK':
                    self._log(f'PSK needs Blender PSK addon or UModel OBJ export: {rel}')
                    n_fail += 1
                else:
                    n_fail += 1
            except Exception as e:
                self._log(f'fail {rel}: {e}')
                n_fail += 1
        self._set_status(f'Convert done: {n_ok} ok, {n_fail} failed → {out}')
        self._log(f'In game: /ue spawn <name>   (name = filename without .glb)')

    def _obj_to_glb(self, blender, obj_path, glb_path):
        with tempfile.NamedTemporaryFile('w', suffix='.py', delete=False, encoding='utf-8') as f:
            f.write(BLENDER_OBJ_SCRIPT)
            script = f.name
        try:
            cmd = [blender, '--background', '--python', script, '--', obj_path, glb_path]
            self._log(' '.join(cmd))
            return subprocess.call(cmd)
        finally:
            try:
                os.unlink(script)
            except OSError:
                pass


    def _browse_unr(self):
        p = filedialog.askopenfilename(
            title='Unreal Gold map',
            filetypes=[('Unreal map', '*.unr'), ('All', '*.*')],
        )
        if p:
            self.vars['unr_file'].set(p)

    def do_list_unr(self):
        d = self.vars['maps_dir'].get().strip()
        if not d or not os.path.isdir(d):
            messagebox.showerror('Maps', 'Set a Maps folder (Unreal Gold Maps/*.unr)')
            return
        files = sorted(f for f in os.listdir(d) if f.lower().endswith('.unr'))
        self._log(f'{len(files)} .unr in {d}')
        for f in files[:80]:
            self._log('  ' + f)
        if len(files) > 80:
            self._log(f'  … +{len(files) - 80} more')
        self._set_status(f'{len(files)} maps listed')

    def do_scan_unr(self):
        self._run(self._scan_unr)

    def _scan_unr(self):
        if unr is None:
            raise RuntimeError('unr_package.py missing')
        path = self.vars['unr_file'].get().strip()
        if not path or not os.path.isfile(path):
            raise RuntimeError('Pick a .unr file')
        self._log(f'Loading package {path} …')
        pkg = unr.load_package(path)
        summary = unr.list_map_summary(pkg)
        self._log(
            f"v{summary['version']} names={summary['names']} imports={summary['imports']} "
            f"exports={summary['exports']} placed~{summary['placed_count']}"
        )
        self._log('Top classes: ' + ', '.join(f'{k}:{v}' for k, v in list(summary['class_counts'].items())[:15]))
        for a in summary['placed'][:25]:
            self._log(f"  {a['class']:18} {a['name']:28} {a['location']}")
        if summary['placed_count'] > 25:
            self._log(f"  … +{summary['placed_count'] - 25} more")
        self._set_status(f"Scanned {summary['file']}: ~{summary['placed_count']} placed objects")

    def do_export_unr_json(self):
        self._run(self._export_unr_json)

    def _export_unr_json(self):
        if unr is None:
            raise RuntimeError('unr_package.py missing')
        path = self.vars['unr_file'].get().strip()
        if not path or not os.path.isfile(path):
            raise RuntimeError('Pick a .unr file')
        pkg = unr.load_package(path)
        out_dir = os.path.join(ROOT, 'out', 'ue1_maps')
        os.makedirs(out_dir, exist_ok=True)
        stem = os.path.splitext(os.path.basename(path))[0]
        out = os.path.join(out_dir, stem + '.json')
        doc = unr.export_actors_json(pkg, out, units_per_block=50.0)
        self._log(f"Wrote {len(doc['objects'])} actors → {out}")
        self._log('Note: BSP geometry not included yet — locations only')
        self._set_status(f'Exported {stem}.json ({len(doc["objects"])} objects)')


    def do_export_geometry(self):
        self._run(self._export_geometry)

    def _export_geometry(self):
        if unr is None or unr_geo is None:
            raise RuntimeError('unr_package / unr_geometry missing')
        path = self.vars['unr_file'].get().strip()
        if not path or not os.path.isfile(path):
            raise RuntimeError('Pick a .unr file')
        pkg = unr.load_package(path)
        out_dir = os.path.join(ROOT, 'out', 'ue1_maps')
        os.makedirs(out_dir, exist_ok=True)
        stem = os.path.splitext(os.path.basename(path))[0]
        obj_path = os.path.join(out_dir, stem + '.obj')
        json_path = os.path.join(out_dir, stem + '_mesh.json')
        self._log(f'Extracting polys from {stem}…')
        doc = unr_geo.export_geometry(pkg, obj_path, json_path, units_per_block=50.0)
        s = doc['stats']
        self._log(f"Polys exports={s['polys_exports']} polys={s['polys']} tris={s['tris']}")
        self._log(f'OBJ → {obj_path}')
        self._log(f'Mesh JSON → {json_path}')
        if s['tris'] == 0:
            self._log('WARN: 0 triangles — FPoly layout may need another pass for this map')
        self._set_status(f"Geometry: {s['tris']} tris")


    def _browse_t3d(self):
        p = filedialog.askopenfilename(
            title='Unreal T3D export',
            filetypes=[('Unreal T3D', '*.t3d'), ('All', '*.*')],
        )
        if p:
            self.vars['t3d_file'].set(p)

    def do_t3d_convert(self):
        self._run(self._t3d_convert)

    def _t3d_convert(self):
        if t3dmesh is None:
            raise RuntimeError('t3d_to_mesh.py missing')
        path = self.vars['t3d_file'].get().strip()
        if not path or not os.path.isfile(path):
            raise RuntimeError('Pick a .t3d file from UnrealEd')
        out_dir = os.path.join(ROOT, 'out', 'ue1_maps')
        os.makedirs(out_dir, exist_ok=True)
        stem = os.path.splitext(os.path.basename(path))[0]
        obj_path = os.path.join(out_dir, stem + '_t3d.obj')
        json_path = os.path.join(out_dir, stem + '_t3d_mesh.json')
        self._log(f'Converting T3D {stem}…')
        doc = t3dmesh.export_mesh(path, obj_path, json_path, units_per_block=50.0)
        s = doc['stats']
        self._log(f"brushes={s['brushes']} polys={s['polys']} tris={s['tris']} actors={s['actors']}")
        self._log(f'OBJ → {obj_path}')
        self._log(f'JSON → {json_path}')
        self._set_status(f"T3D: {s['tris']} tris from {s['brushes']} brushes")



if __name__ == '__main__':
    Ue1App().mainloop()
