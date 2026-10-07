package com.unrealcraft.map;

import java.nio.file.Path;

/** One map file discovered under mapsDir (or built-in asset). */
public record MapInfo(
        String id,
        String displayName,
        /** Primary file: .glb / .t3d / .unr / .bsp / .pk3 / mesh json */
        Path path,
        /** Optional companion mesh/actor JSON */
        Path jsonPath,
        String format,
        boolean fromAssets
) {
    public boolean isGlb() { return "glb".equals(format); }
    public boolean isMeshJson() { return "mesh_json".equals(format); }
    public boolean isT3d() { return "t3d".equals(format); }
    public boolean isUnr() { return "unr".equals(format); }
    public boolean isBsp() { return "bsp".equals(format); }
    public boolean isPk3() { return "pk3".equals(format); }

    /** Back-compat for older MapLoader field names. */
    public Path glbPath() {
        return isGlb() ? path : null;
    }
}
