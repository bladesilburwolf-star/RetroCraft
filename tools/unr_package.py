#!/usr/bin/env python3
"""Unreal Engine 1 package reader for Unreal Gold .unr maps (package version ~61–69).

Reads name / import / export tables correctly, then deserializes Actor property
bags looking for Location (Vector). Drops garbage (binary ids, NaN, absurd coords).

  python tools/unr_package.py Maps/Unreal.unr --json out/ue1_maps/Unreal.json
"""
from __future__ import annotations

import argparse
import json
import math
import os
import struct
from dataclasses import dataclass, field
from typing import BinaryIO, Dict, List, Optional, Tuple

TAG = 0x9E2A83C1

# Property type codes (UE1)
PROP_BYTE = 1
PROP_INT = 2
PROP_BOOL = 3
PROP_FLOAT = 4
PROP_OBJECT = 5
PROP_NAME = 6
PROP_STRING = 7
PROP_CLASS = 8
PROP_ARRAY = 9
PROP_STRUCT = 10
PROP_VECTOR = 11  # sometimes
PROP_ROTATOR = 12
PROP_STR = 13
PROP_MAP = 14
PROP_FIXED_ARRAY = 15


def read_index(f: BinaryIO) -> int:
    b0 = f.read(1)[0]
    neg = bool(b0 & 0x80)
    if (b0 & 0x40) == 0:
        val = b0 & 0x3F
    else:
        b1 = f.read(1)[0]
        if (b1 & 0x80) == 0:
            val = (b0 & 0x3F) | ((b1 & 0x7F) << 6)
        else:
            b2 = f.read(1)[0]
            if (b2 & 0x80) == 0:
                val = (b0 & 0x3F) | ((b1 & 0x7F) << 6) | ((b2 & 0x7F) << 13)
            else:
                b3 = f.read(1)[0]
                if (b3 & 0x80) == 0:
                    val = (b0 & 0x3F) | ((b1 & 0x7F) << 6) | ((b2 & 0x7F) << 13) | ((b3 & 0x7F) << 20)
                else:
                    b4 = f.read(1)[0]
                    val = (b0 & 0x3F) | ((b1 & 0x7F) << 6) | ((b2 & 0x7F) << 13) | ((b3 & 0x7F) << 20) | ((b4 & 0x7F) << 27)
    return -val if neg else val


def read_index_buf(data: bytes, pos: int) -> Tuple[int, int]:
    class Cursor:
        def __init__(self, d, p):
            self.d, self.p = d, p
        def read(self, n=1):
            b = self.d[self.p:self.p + n]
            if len(b) < n:
                raise EOFError
            self.p += n
            return b
    c = Cursor(data, pos)
    v = read_index(c)  # type: ignore
    return v, c.p


def is_printable_name(s: str) -> bool:
    if not s or len(s) > 64:
        return False
    if not all(32 <= ord(c) < 127 for c in s):
        return False
    # must look like an identifier-ish token
    ok = sum(c.isalnum() or c in '._' for c in s)
    return ok >= max(1, len(s) // 2)


@dataclass
class NameEntry:
    name: str
    flags: int


@dataclass
class ImportEntry:
    class_pkg: int
    class_name: int
    package: int
    object_name: int


@dataclass
class ExportEntry:
    class_ref: int
    super_ref: int
    package: int
    object_name: int
    object_flags: int
    serial_size: int
    serial_offset: int


@dataclass
class UnrPackage:
    path: str
    version: int
    licensee: int
    names: List[NameEntry] = field(default_factory=list)
    imports: List[ImportEntry] = field(default_factory=list)
    exports: List[ExportEntry] = field(default_factory=list)
    data: bytes = b''
    name_to_index: Dict[str, int] = field(default_factory=dict)

    def name_str(self, idx: int) -> str:
        if 0 <= idx < len(self.names):
            return self.names[idx].name
        return ''

    def obj_ref_name(self, ref: int) -> str:
        if ref == 0:
            return 'None'
        if ref > 0:
            i = ref - 1
            if 0 <= i < len(self.exports):
                return self.name_str(self.exports[i].object_name)
            return ''
        i = -ref - 1
        if 0 <= i < len(self.imports):
            return self.name_str(self.imports[i].object_name)
        return ''

    def class_name_of_export(self, exp: ExportEntry) -> str:
        return self.obj_ref_name(exp.class_ref)


def load_package(path: str) -> UnrPackage:
    with open(path, 'rb') as f:
        data = f.read()
    if len(data) < 64:
        raise ValueError('file too small')
    tag = struct.unpack_from('<I', data, 0)[0]
    if tag != TAG:
        raise ValueError(f'not an Unreal package (tag={tag:#x})')
    version = struct.unpack_from('<H', data, 4)[0]
    licensee = struct.unpack_from('<H', data, 6)[0]
    # UE1 header (v61 Unreal Gold):
    # flags u32 @8, NameCount @12, NameOffset @16, ExportCount @20, ExportOffset @24,
    # ImportCount @28, ImportOffset @32, HeritageCount @36, HeritageOffset @40
    flags, name_count, name_offset, export_count, export_offset, import_count, import_offset = struct.unpack_from(
        '<IIIIIII', data, 8
    )
    if name_count > 200_000 or export_count > 200_000 or import_count > 200_000:
        raise ValueError(f'insane table sizes name={name_count} exp={export_count} imp={import_count}')
    if name_offset >= len(data) or export_offset >= len(data) or import_offset >= len(data):
        raise ValueError('table offset past EOF')

    pkg = UnrPackage(path=path, version=version, licensee=licensee, data=data)


    # --- Name table ---
    # Format: BYTE length (includes trailing NUL) + chars + DWORD flags  (UT/Unreal common)
    # Fallback: NUL-terminated string + DWORD flags
    # If header offset is wrong, scan for length-prefixed "None"

    def read_names_length_prefixed(start, count):
        pos = start
        names = []
        for i in range(count):
            if pos >= len(data):
                raise ValueError(f'name table truncated at {i}/{count}')
            length = data[pos]
            pos += 1
            if length < 1 or length > 128 or pos + length + 4 > len(data):
                raise ValueError(f'bad name length {length} at name #{i} offset {pos - 1}')
            raw = data[pos:pos + length]
            pos += length
            name = raw.split(b'\0')[0].decode('latin1', 'replace')
            flags_n = struct.unpack_from('<I', data, pos)[0]
            pos += 4
            names.append(NameEntry(name, flags_n))
        return names

    def read_names_nul_terminated(start, count):
        pos = start
        names = []
        for i in range(count):
            if pos >= len(data):
                raise ValueError(f'name table truncated at {i}/{count}')
            endp = data.find(b'\0', pos)
            if endp < 0 or endp - pos > 128:
                raise ValueError(f'bad nul-terminated name at #{i} offset {pos}')
            name = data[pos:endp].decode('latin1', 'replace')
            pos = endp + 1
            if pos + 4 > len(data):
                raise ValueError(f'name flags past EOF at #{i}')
            flags_n = struct.unpack_from('<I', data, pos)[0]
            pos += 4
            names.append(NameEntry(name, flags_n))
        return names

    def names_look_sane(names):
        if not names:
            return False
        good = sum(1 for n in names[:25] if is_printable_name(n.name))
        first = names[0].name if names else ''
        # Real packages almost always start with None
        return good >= 5 or first == 'None'

    errors = []
    names = None
    for label, reader, off in (
        ('length@header', read_names_length_prefixed, name_offset),
        ('nul@header', read_names_nul_terminated, name_offset),
    ):
        try:
            cand = reader(off, name_count)
            if names_look_sane(cand):
                names = cand
                break
            errors.append(f'{label}: parsed but not sane (first={cand[0].name!r})')
        except ValueError as e:
            errors.append(f'{label}: {e}')

    if names is None:
        # Scan for length-prefixed "None" (05 4E 6F 6E 65 00)
        found = None
        needle = b'\x05None\x00'
        idx = data.find(needle, 0, min(len(data), 0x2000))
        if idx >= 0:
            found = idx
        if found is None:
            # NUL form
            idx = data.find(b'None\x00', 0x20, min(len(data), 0x2000))
            if idx >= 0:
                found = idx
        if found is not None:
            for label, reader in (('length@scan', read_names_length_prefixed), ('nul@scan', read_names_nul_terminated)):
                try:
                    cand = reader(found, name_count)
                    if names_look_sane(cand):
                        names = cand
                        break
                    errors.append(f'{label}@{found}: not sane')
                except ValueError as e:
                    errors.append(f'{label}@{found}: {e}')

    if names is None:
        raise ValueError(
            f'name table failed (name_offset={name_offset}, name_count={name_count}, version={version}): '
            + ' | '.join(errors)
        )

    for i, n in enumerate(names):
        pkg.names.append(n)
        if n.name and n.name not in pkg.name_to_index:
            pkg.name_to_index[n.name] = i

    # --- Import / export tables ---
    def parse_exports(count, offset):
        f = open(path, 'rb')
        f.seek(offset)
        out = []
        for _ in range(count):
            class_ref = read_index(f)
            super_ref = read_index(f)
            package = struct.unpack('<i', f.read(4))[0]
            oname = read_index(f)
            oflags = struct.unpack('<I', f.read(4))[0]
            ssize = read_index(f)
            soff = read_index(f) if ssize != 0 else 0
            out.append(ExportEntry(class_ref, super_ref, package, oname, oflags, ssize, soff))
        f.close()
        return out

    def parse_imports(count, offset):
        f = open(path, 'rb')
        f.seek(offset)
        out = []
        for _ in range(count):
            cpkg = read_index(f)
            cname = read_index(f)
            package = struct.unpack('<i', f.read(4))[0]
            oname = read_index(f)
            out.append(ImportEntry(cpkg, cname, package, oname))
        f.close()
        return out

    pkg.imports = parse_imports(import_count, import_offset)
    pkg.exports = parse_exports(export_count, export_offset)
    return pkg


def _skip_property_value(data: bytes, pos: int, info: int, pkg: UnrPackage) -> int:
    """Advance past one property value; returns new pos. Best-effort for UE1."""
    typ = info & 0x0F
    size_code = (info >> 4) & 0x07
    # size hint when not array
    size_map = {0: 1, 1: 2, 2: 4, 3: 12, 4: 16, 5: 1, 6: 2, 7: 4}
    try:
        if typ == PROP_BOOL:
            return pos  # bool is in info bit 7 sometimes; no extra for older
        if typ in (PROP_BYTE,):
            return pos + 1
        if typ in (PROP_INT, PROP_FLOAT, PROP_OBJECT, PROP_NAME, PROP_CLASS):
            if typ == PROP_NAME:
                _, pos = read_index_buf(data, pos)
                return pos
            if typ == PROP_OBJECT or typ == PROP_CLASS:
                _, pos = read_index_buf(data, pos)
                return pos
            return pos + 4
        if typ == PROP_STRUCT:
            # struct name INDEX then body — size often in size_code
            _, pos = read_index_buf(data, pos)
            # Vector/Rotator are 12 bytes
            if size_code == 3:
                return pos + 12
            if size_code == 4:
                return pos + 16
            # unknown struct: try size from size_code if 5–7 means dynamic
            if size_code == 5:
                return pos + 1
            if size_code == 6:
                return pos + 2
            if size_code == 7:
                sz = struct.unpack_from('<I', data, pos)[0]
                return pos + 4 + sz
            return pos + size_map.get(size_code, 0)
        if typ in (PROP_STRING, PROP_STR):
            # length-prefixed or index
            if pos < len(data):
                ln = data[pos]
                return pos + 1 + ln
            return pos
        if typ == PROP_ARRAY or typ == PROP_FIXED_ARRAY:
            # hard — stop this object
            raise StopIteration
        # fallback by size code
        if size_code == 7:
            sz = struct.unpack_from('<I', data, pos)[0]
            return pos + 4 + max(0, min(sz, 1_000_000))
        return pos + size_map.get(size_code, 0)
    except (struct.error, EOFError, IndexError):
        raise StopIteration


def parse_actor_properties(pkg: UnrPackage, blob: bytes) -> dict:
    """Parse UE1 tagged property list until 'None' name. Returns dict of known fields."""
    pos = 0
    out = {}
    none_idx = pkg.name_to_index.get('None')
    loc_idx = pkg.name_to_index.get('Location')
    rot_idx = pkg.name_to_index.get('Rotation')
    # limit iterations
    for _ in range(500):
        if pos >= len(blob) - 1:
            break
        try:
            name_i, pos2 = read_index_buf(blob, pos)
        except (EOFError, IndexError):
            break
        pos = pos2
        if name_i == none_idx or pkg.name_str(name_i) == 'None':
            break
        if name_i < 0 or name_i >= len(pkg.names):
            break
        pname = pkg.name_str(name_i)
        if pos >= len(blob):
            break
        info = blob[pos]
        pos += 1
        typ = info & 0x0F
        # Location: Struct Vector = 3 floats, or direct
        if name_i == loc_idx or pname == 'Location':
            try:
                if typ == PROP_STRUCT:
                    # struct name
                    _, pos = read_index_buf(blob, pos)
                    x, y, z = struct.unpack_from('<fff', blob, pos)
                    pos += 12
                    if all(math.isfinite(v) for v in (x, y, z)):
                        out['Location'] = (x, y, z)
                    continue
                if typ == PROP_FLOAT:
                    # shouldn't be single float
                    pass
                # some versions pack vector without extra struct name after size
                if pos + 12 <= len(blob):
                    x, y, z = struct.unpack_from('<fff', blob, pos)
                    if all(math.isfinite(v) and abs(v) < 1e7 for v in (x, y, z)):
                        out['Location'] = (x, y, z)
                        pos += 12
                        continue
            except (struct.error, EOFError, StopIteration):
                break
        if name_i == rot_idx or pname == 'Rotation':
            try:
                if typ == PROP_STRUCT:
                    _, pos = read_index_buf(blob, pos)
                if pos + 12 <= len(blob):
                    pitch, yaw, roll = struct.unpack_from('<iii', blob, pos)
                    pos += 12
                    out['Rotation'] = (pitch, yaw, roll)
                    continue
            except (struct.error, EOFError):
                break
        # skip other properties
        try:
            pos = _skip_property_value(blob, pos, info, pkg)
        except StopIteration:
            break
    return out


def valid_location(loc: Tuple[float, float, float]) -> bool:
    x, y, z = loc
    if not all(math.isfinite(v) for v in (x, y, z)):
        return False
    if abs(x) > 500_000 or abs(y) > 500_000 or abs(z) > 500_000:
        return False
    # pure zero is allowed for origin actors
    return True


# Classes that are typically placed in a map with a Location
ACTOR_CLASSES = {
    'Actor', 'Brush', 'Mover', 'PlayerStart', 'Pawn', 'Scout', 'Light', 'Trigger',
    'Inventory', 'Weapon', 'Ammo', 'Pickup', 'Decoration', 'Interpolator',
    'PathNode', 'InventorySpot', 'WarpZoneInfo', 'LevelInfo', 'ZoneInfo',
    'SkyZoneInfo', 'Teleporter', 'LiftCenter', 'LiftExit', 'TriggerLight',
    'ExplosionChain', 'BlockAll', 'BlockMonsters', 'Keypoint', 'LocationID',
    'NaliPlayer', 'UnrealPlayer', 'Female', 'Male', 'Cow', 'Nali', 'Skaarj',
    'Krall', 'Tentacle', 'Slith', 'Devilfish', 'Bird', 'Bird1', 'Brute',
    'Gasbag', 'Manta', 'Mercenary', 'Pupae', 'Queen', 'Titan', 'Warlord',
    'Spinner', 'Fly', 'Bloblet', 'Predator', 'ScriptedPawn',
}


def list_map_summary(pkg: UnrPackage) -> dict:
    classes: Dict[str, int] = {}
    placed = []
    level_objects = []
    bad_names = 0

    for exp in pkg.exports:
        cname = pkg.class_name_of_export(exp)
        oname = pkg.name_str(exp.object_name)
        if not is_printable_name(cname) and cname != 'None':
            bad_names += 1
            continue
        if cname:
            classes[cname] = classes.get(cname, 0) + 1
        if cname in ('Level', 'LevelInfo', 'Model', 'Polys', 'BspNodes', 'BspSurfs'):
            level_objects.append({
                'name': oname, 'class': cname,
                'size': exp.serial_size, 'offset': exp.serial_offset,
            })
        if exp.serial_size <= 0 or exp.serial_size > 100_000:
            continue
        if not is_printable_name(oname):
            continue
        # Prefer known actor classes; also accept *Start, *Light, etc.
        interesting = (
            cname in ACTOR_CLASSES
            or cname.endswith('Light')
            or cname.endswith('Start')
            or cname.endswith('Trigger')
            or cname.endswith('Mover')
            or cname.endswith('Pawn')
            or cname.endswith('Weapon')
            or cname.endswith('Ammo')
            or cname.endswith('Pickup')
            or cname.endswith('Decoration')
            or cname.endswith('Node')
            or cname.endswith('Info')
            or cname == 'Brush'
        )
        if not interesting:
            continue
        blob = pkg.data[exp.serial_offset:exp.serial_offset + exp.serial_size]
        props = parse_actor_properties(pkg, blob)
        loc = props.get('Location')
        if loc is None or not valid_location(loc):
            continue
        placed.append({
            'name': oname,
            'class': cname,
            'location': list(loc),
            'rotation': list(props['Rotation']) if 'Rotation' in props else None,
        })

    return {
        'file': os.path.basename(pkg.path),
        'path': pkg.path,
        'version': pkg.version,
        'names': len(pkg.names),
        'imports': len(pkg.imports),
        'exports': len(pkg.exports),
        'bad_name_skips': bad_names,
        'class_counts': dict(sorted(classes.items(), key=lambda kv: -kv[1])[:50]),
        'level_objects': level_objects,
        'placed': placed,
        'placed_count': len(placed),
        'sample_names': [n.name for n in pkg.names[:30] if is_printable_name(n.name)],
    }


def export_actors_json(pkg: UnrPackage, out_path: str, units_per_block: float = 50.0) -> dict:
    summary = list_map_summary(pkg)
    objects = []
    for a in summary['placed']:
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
        'name': os.path.splitext(os.path.basename(pkg.path))[0],
        'source': 'unreal_gold_unr',
        'units_per_block': units_per_block,
        'package_version': pkg.version,
        'cells': [],
        'objects': objects,
        'models': [],
        'note': 'Actor Location properties only — BSP geometry not decoded yet',
        'stats': {
            'exports': summary['exports'],
            'placed': summary['placed_count'],
            'top_classes': summary['class_counts'],
        },
    }
    os.makedirs(os.path.dirname(out_path) or '.', exist_ok=True)
    with open(out_path, 'w', encoding='utf-8') as f:
        json.dump(doc, f, indent=2)
    return doc


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('unr', help='path to .unr')
    ap.add_argument('--json', help='write cleaned actor JSON')
    ap.add_argument('--units-per-block', type=float, default=50.0)
    ap.add_argument('--dump-names', action='store_true')
    args = ap.parse_args()
    pkg = load_package(args.unr)
    summary = list_map_summary(pkg)
    print(f"{summary['file']}: v{summary['version']}  names={summary['names']}  "
          f"imports={summary['imports']}  exports={summary['exports']}  "
          f"placed={summary['placed_count']}  bad_name_skips={summary['bad_name_skips']}")
    print('Sample names:', ', '.join(summary['sample_names'][:20]))
    print('Top classes:', ', '.join(f'{k}:{v}' for k, v in list(summary['class_counts'].items())[:15]))
    for a in summary['placed'][:20]:
        print(f"  {a['class']:22} {a['name']:28} {a['location']}")
    if args.dump_names:
        for i, n in enumerate(pkg.names[:100]):
            print(f'  [{i}] {n.name!r}')
    if args.json:
        doc = export_actors_json(pkg, args.json, args.units_per_block)
        print(f"Wrote {len(doc['objects'])} clean actors -> {args.json}")


if __name__ == '__main__':
    main()
