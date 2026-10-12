package com.unrealcraft.client;

import java.util.List;
import java.util.Map;

/**
 * Optional per-model settings, read from assets/minewind/models/glb/NAME.json:
 * { "rotate": [x,y,z] degrees, "scale": 1.0, "auto_orient": true, "flip": false,
 *   "diagonal": true, "offset": [x,y,z] in blocks, "size": blocks (entities only) }
 */
public final class GlbOptions {
    public float rx, ry, rz;
    public float ox, oy, oz;
    public float scale = 1f;
    /** Rotate the longest axis of the mesh to point up (+Y). */
    public boolean autoOrient = true;
    /** Turn the model upside down after auto-orienting (use if the tip points at the hilt end). */
    public boolean flip = false;
    /** Tilt 45 degrees like vanilla sword icons so the handheld/gui transforms look right. */
    public boolean diagonal = true;
    /** Entity mode: feet on y=0, centred on x/z, scaled so the longest side is {@link #size} blocks. */
    public boolean entity = false;
    /** Entity mode only: target length in blocks of the model's longest side. */
    public float size = 1f;

    /** Raw mode (world props): no centring or reorientation, positions are just multiplied by {@link #scale}. */
    public boolean raw = false;
    /** World-map mode: preserve geometry size, centre X/Z, and place the lowest point at Y=0. */
    public boolean map = false;

    public static GlbOptions defaults() { return new GlbOptions(); }

    public static GlbOptions rawDefaults(float scale) {
        GlbOptions o = new GlbOptions();
        o.raw = true;
        o.scale = scale;
        return o;
    }

    public static GlbOptions mapDefaults(float unitsPerBlock, float rx, float ry, float rz) {
        GlbOptions o = new GlbOptions();
        o.map = true;
        o.raw = false;
        o.autoOrient = false;
        o.diagonal = false;
        o.scale = 1.0f / Math.max(unitsPerBlock, 0.001f);
        o.rx = rx; o.ry = ry; o.rz = rz;
        return o;
    }

    public static GlbOptions entityDefaults() {
        GlbOptions o = new GlbOptions();
        o.entity = true;
        o.autoOrient = false;
        o.diagonal = false;
        return o;
    }

    public static GlbOptions fromJson(String json) { return fromJson(json, false); }

    @SuppressWarnings("unchecked")
    public static GlbOptions fromJson(String json, boolean entity) {
        GlbOptions o = entity ? entityDefaults() : defaults();
        Map<String, Object> m = (Map<String, Object>) MiniJson.parse(json);
        if (m.get("rotate") instanceof List<?> r && r.size() >= 3) {
            o.rx = ((Number) r.get(0)).floatValue();
            o.ry = ((Number) r.get(1)).floatValue();
            o.rz = ((Number) r.get(2)).floatValue();
        }
        if (m.get("offset") instanceof List<?> r && r.size() >= 3) {
            o.ox = ((Number) r.get(0)).floatValue();
            o.oy = ((Number) r.get(1)).floatValue();
            o.oz = ((Number) r.get(2)).floatValue();
        }
        if (m.get("scale") instanceof Number n) o.scale = n.floatValue();
        if (m.get("size") instanceof Number n) o.size = n.floatValue();
        if (m.get("auto_orient") instanceof Boolean b) o.autoOrient = b;
        if (m.get("flip") instanceof Boolean b) o.flip = b;
        if (m.get("diagonal") instanceof Boolean b) o.diagonal = b;
        if (m.get("map") instanceof Boolean b) o.map = b;
        return o;
    }
}
