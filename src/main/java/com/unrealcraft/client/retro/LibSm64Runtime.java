package com.unrealcraft.client.retro;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.unrealcraft.Unrealcraft;
import com.unrealcraft.quake3.Quake3Bsp;
import com.unrealcraft.sm64.Sm64PropCollision;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.client.util.math.MatrixStack;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWGamepadState;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Thin JNA bridge to the same libsm64 ABI used by the Melty 1.21.4 demo. */
final class LibSm64Runtime {
    private static final int MAX_TRIANGLES = 1024;
    private static final Identifier ATLAS = Identifier.of("unrealcraft", "sm64_atlas");
    private static final Identifier WHITE = Identifier.of("unrealcraft", "sm64_white");
    private static boolean libraryLoaded;
    private static boolean initialized;
    private static Memory rom, texture, inputs, state, geo, positions, normals, colors, uvs;
    private static int marioId = -1, triangleCount, action;
    private static final float[] marioPos = new float[3];
    private static volatile boolean audioActive;
    private static boolean audioThreadStarted;
    private static Memory audioBuffer;
    private static boolean loggedGeometry;
    private static short playingMusic;
    private static final ConcurrentLinkedQueue<Runnable> AUDIO_COMMANDS = new ConcurrentLinkedQueue<>();
    private static final GLFWGamepadState GAMEPAD = GLFWGamepadState.create();
    private static int gamepadId = -1;
    private static float padX, padY, padCameraX, padCameraY;
    private static boolean padA, padB, padZ;

    private LibSm64Runtime() {}

    private static void bind(Path dll) {
        if (libraryLoaded) return;
        Native.register(LibSm64Runtime.class, NativeLibrary.getInstance(dll.toAbsolutePath().toString()));
        libraryLoaded = true;
    }

    private static native void sm64_global_init(Pointer rom, Pointer textureOut);
    private static native void sm64_audio_init(Pointer rom);
    private static native int sm64_audio_tick(int samplesQueued, int samplesToProduce, Pointer audioOut);
    private static native void sm64_set_sound_volume(float volume);
    private static native void sm64_set_music_volume(float volume);
    private static native void sm64_play_music(byte player, short sequence, short fadeFrames);
    private static native void sm64_stop_background_music(short sequence);
    private static native int sm64_mario_create(float x, float y, float z);
    private static native void sm64_mario_tick(int id, Pointer inputs, Pointer state, Pointer geo);
    private static native void sm64_mario_delete(int id);
    private static native void sm64_static_surfaces_load(Pointer surfaces, int count);

    private static boolean initialize(MinecraftClient mc) {
        if (initialized) return true;
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("mario64");
        Path dll = dir.resolve("sm64.dll"), romPath = dir.resolve("baserom.us.z64");
        if (!Files.isRegularFile(dll) || !Files.isRegularFile(romPath)) {
            if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal("Add sm64.dll and your legally obtained baserom.us.z64 to " + dir), false);
            return false;
        }
        try {
            bind(dll);
            byte[] bytes = Files.readAllBytes(romPath);
            rom = new Memory(bytes.length); rom.write(0, bytes, 0, bytes.length);
            texture = new Memory(704L * 64 * 4);
            inputs = new Memory(20); state = new Memory(60); geo = new Memory(40);
            positions = new Memory(MAX_TRIANGLES * 9L * 4); normals = new Memory(MAX_TRIANGLES * 9L * 4);
            colors = new Memory(MAX_TRIANGLES * 9L * 4); uvs = new Memory(MAX_TRIANGLES * 6L * 4);
            geo.setPointer(0, positions); geo.setPointer(8, normals); geo.setPointer(16, colors); geo.setPointer(24, uvs);
            sm64_global_init(rom, texture);
            AUDIO_COMMANDS.add(() -> {
                sm64_audio_init(rom);
                sm64_set_sound_volume(1.0f);
                sm64_set_music_volume(1.0f);
            });
            uploadAtlas(mc, texture.getByteArray(0, 704 * 64 * 4));
            startAudioThread();
            initialized = true;
            Unrealcraft.LOGGER.info("libsm64 initialized from {}", romPath);
            return true;
        } catch (Throwable e) {
            Unrealcraft.LOGGER.error("Could not initialize libsm64", e);
            if (mc.player != null) mc.player.sendMessage(net.minecraft.text.Text.literal("libsm64 failed to initialize: " + e.getMessage()), false);
            return false;
        }
    }

    private static synchronized void startAudioThread() {
        if (audioThreadStarted) return;
        audioThreadStarted = true;
        audioBuffer = new Memory(8704);
        Thread audio = new Thread(() -> {
            AudioFormat format = new AudioFormat(32000f, 16, 2, true, false);
            try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
                line.open(format, 64000);
                line.start();
                byte[] pcm = new byte[8704];
                long nextTick = System.nanoTime();
                while (!Thread.currentThread().isInterrupted()) {
                    Runnable command = AUDIO_COMMANDS.poll();
                    if (command != null) {
                        command.run();
                        continue;
                    }
                    if (!audioActive) {
                        line.flush();
                        nextTick = System.nanoTime();
                        Thread.sleep(40);
                        continue;
                    }
                    int queuedFrames = (line.getBufferSize() - line.available()) / 4;
                    int frames = sm64_audio_tick(queuedFrames, 1100, audioBuffer);
                    if (queuedFrames < 6000 && frames > 0) {
                        // libsm64 returns sample units whose PCM block is 2 channels × 4 bytes per unit.
                        int bytes = Math.min(pcm.length, frames * 2 * 4);
                        audioBuffer.read(0, pcm, 0, bytes);
                        line.write(pcm, 0, bytes);
                    }
                    nextTick += 33_000_000L;
                    long waitNanos = nextTick - System.nanoTime();
                    if (waitNanos > 0) Thread.sleep(waitNanos / 1_000_000L, (int)(waitNanos % 1_000_000L));
                    else nextTick = System.nanoTime();
                }
            } catch (Throwable e) {
                Unrealcraft.LOGGER.warn("libsm64 audio output stopped: {}", e.toString());
            }
        }, "unrealcraft-sm64-audio");
        audio.setDaemon(true);
        audio.start();
    }

    private static void uploadAtlas(MinecraftClient mc, byte[] rgba) {
        NativeImage image = new NativeImage(704, 64, true);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 704; x++) {
            int i = (y * 704 + x) * 4;
            image.setColor(x, y, (rgba[i + 3] & 255) << 24 | (rgba[i] & 255) << 16 | (rgba[i + 1] & 255) << 8 | rgba[i + 2] & 255);
        }
        mc.getTextureManager().registerTexture(ATLAS, new NativeImageBackedTexture(image));
        NativeImage white = new NativeImage(16, 16, true);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) white.setColor(x, y, 0xFFFFFFFF);
        mc.getTextureManager().registerTexture(WHITE, new NativeImageBackedTexture(white));
    }

    static boolean start(MinecraftClient mc, RetroMapClient.Active level) {
        if (!initialize(mc)) return false;
        try {
            var pl = level.mesh.placement;
            List<Quake3Bsp.Tri> tris = Sm64PropCollision.merge(level.sm64.tris(), level.props);
            ByteBuffer surfaces = ByteBuffer.allocateDirect(Math.max(1, tris.size()) * 44).order(ByteOrder.LITTLE_ENDIAN);
            for (var tri : tris) {
                String type = tri.texture();
                short surface = (short)(type.contains("SLIPPERY") ? (type.contains("NOT_SLIPPERY") ? 21 : 20) : type.contains("BURNING") ? 1 : 0);
                float[] p = tri.xyz(), n = tri.nrm();
                int[] v = new int[9];
                for (int k = 0; k < 3; k++) {
                    v[k * 3] = Math.round((p[k * 3] - pl.refX()) * 100f / pl.scale());
                    v[k * 3 + 1] = Math.round((p[k * 3 + 1] - pl.refY()) * 100f / pl.scale());
                    v[k * 3 + 2] = Math.round((p[k * 3 + 2] - pl.refZ()) * 100f / pl.scale());
                }
                long ax = v[3] - v[0], ay = v[4] - v[1], az = v[5] - v[2];
                long bx = v[6] - v[3], by = v[7] - v[4], bz = v[8] - v[5];
                double dot = (ay * bz - az * by) * n[0] + (az * bx - ax * bz) * n[1] + (ax * by - ay * bx) * n[2];
                if (dot < 0) for (int k = 0; k < 3; k++) { int t = v[3 + k]; v[3 + k] = v[6 + k]; v[6 + k] = t; }
                surfaces.putShort(surface).putShort((short)0).putShort((short)1).putShort((short)0);
                for (int c : v) surfaces.putInt(c);
            }
            surfaces.flip();
            sm64_static_surfaces_load(Native.getDirectBufferPointer(surfaces), tris.size());
            Vec3d p = mc.player.getPos();
            float x = (float)((p.x - level.x) * 100), y = (float)((p.y - level.y) * 100), z = (float)((p.z - level.z) * 100);
            marioId = sm64_mario_create(x, y, z);
            if (marioId < 0) return false;
            marioPos[0] = x; marioPos[1] = y; marioPos[2] = z;
            triangleCount = 0;
            playingMusic = (short)musicFor(level.sm64.level());
            short track = playingMusic;
            AUDIO_COMMANDS.add(() -> sm64_play_music((byte)0, (short)(0x400 | track), (short)0));
            audioActive = true;
            return true;
        } catch (Throwable e) {
            Unrealcraft.LOGGER.error("Could not start native Mario", e);
            return false;
        }
    }

    static void stop() {
        if (audioActive && initialized) {
            short track = playingMusic;
            AUDIO_COMMANDS.add(() -> sm64_stop_background_music(track));
        }
        audioActive = false;
        if (marioId >= 0 && initialized) sm64_mario_delete(marioId);
        marioId = -1; triangleCount = 0;
    }

    static void tick(MinecraftClient mc, RetroMapClient.Active level, int frames) {
        if (marioId < 0 || mc.player == null) return;
        pollGamepad();
        var o = mc.options;
        float fwd = (o.forwardKey.isPressed() ? 1 : 0) - (o.backKey.isPressed() ? 1 : 0);
        float strafe = (o.rightKey.isPressed() ? 1 : 0) - (o.leftKey.isPressed() ? 1 : 0);
        if (padX != 0 || padY != 0) { strafe = padX; fwd = -padY; }
        float len = (float)Math.sqrt(fwd * fwd + strafe * strafe);
        if (len > 1) { fwd /= len; strafe /= len; }
        if (padCameraX != 0 || padCameraY != 0) {
            mc.player.setYaw(mc.player.getYaw() + padCameraX * 4.0f);
            mc.player.setPitch(Math.max(-90, Math.min(90, mc.player.getPitch() + padCameraY * 3.0f)));
        }
        float yaw = (float)Math.toRadians(mc.player.getYaw());
        inputs.setFloat(0, - (float)Math.sin(yaw)); inputs.setFloat(4, (float)Math.cos(yaw));
        inputs.setFloat(8, strafe); inputs.setFloat(12, -fwd);
        inputs.setByte(16, (byte)(o.jumpKey.isPressed() || padA ? 1 : 0));
        inputs.setByte(17, (byte)(o.sprintKey.isPressed() || padB ? 1 : 0));
        inputs.setByte(18, (byte)(o.sneakKey.isPressed() || padZ ? 1 : 0));
        for (int i = 0; i < frames; i++) {
            sm64_mario_tick(marioId, inputs, state, geo);
            state.read(0, marioPos, 0, 3);
            action = state.getInt(36);
            triangleCount = Math.min(MAX_TRIANGLES, geo.getShort(32) & 0xffff);
            positions.read(0, POS_CACHE, 0, triangleCount * 9);
            normals.read(0, NORMAL_CACHE, 0, triangleCount * 9);
            colors.read(0, COLOR_CACHE, 0, triangleCount * 9);
            uvs.read(0, UV_CACHE, 0, triangleCount * 6);
            if (!loggedGeometry && triangleCount > 0) {
                loggedGeometry = true;
                Unrealcraft.LOGGER.info("libsm64 generated Mario mesh: {} triangles, first vertex=({}, {}, {})", triangleCount, POS_CACHE[0], POS_CACHE[1], POS_CACHE[2]);
            }
        }
        mc.player.setVelocity(Vec3d.ZERO);
        mc.player.setPosition(level.x + marioPos[0] / 100.0, level.y + marioPos[1] / 100.0, level.z + marioPos[2] / 100.0);
        mc.player.fallDistance = 0;
        mc.player.setInvisible(true);
    }

    private static void pollGamepad() {
        padX = padY = padCameraX = padCameraY = 0;
        padA = padB = padZ = false;
        if (gamepadId < 0 || !GLFW.glfwJoystickIsGamepad(gamepadId)) {
            gamepadId = -1;
            for (int id = GLFW.GLFW_JOYSTICK_1; id <= GLFW.GLFW_JOYSTICK_LAST; id++) {
                if (GLFW.glfwJoystickPresent(id) && GLFW.glfwJoystickIsGamepad(id)) { gamepadId = id; break; }
            }
        }
        if (gamepadId < 0 || !GLFW.glfwGetGamepadState(gamepadId, GAMEPAD)) return;
        float[] left = radial(GAMEPAD.axes(0), GAMEPAD.axes(1), 0.18f);
        float[] right = radial(GAMEPAD.axes(2), GAMEPAD.axes(3), 0.18f);
        padX = left[0]; padY = left[1]; padCameraX = right[0]; padCameraY = right[1];
        padA = button(GLFW.GLFW_GAMEPAD_BUTTON_A);
        padB = button(GLFW.GLFW_GAMEPAD_BUTTON_B) || button(GLFW.GLFW_GAMEPAD_BUTTON_X);
        padZ = GAMEPAD.axes(4) > 0.3f || GAMEPAD.axes(5) > 0.3f || button(GLFW.GLFW_GAMEPAD_BUTTON_LEFT_BUMPER);
    }

    private static boolean button(int button) { return GAMEPAD.buttons(button) == GLFW.GLFW_PRESS; }

    private static float[] radial(float x, float y, float deadzone) {
        float magnitude = (float)Math.sqrt(x * x + y * y);
        if (magnitude <= deadzone) return new float[]{0, 0};
        float scaled = Math.min(1, (magnitude - deadzone) / (1 - deadzone));
        return new float[]{x / magnitude * scaled, y / magnitude * scaled};
    }

    private static final float[] POS_CACHE = new float[MAX_TRIANGLES * 9], NORMAL_CACHE = new float[MAX_TRIANGLES * 9], COLOR_CACHE = new float[MAX_TRIANGLES * 9], UV_CACHE = new float[MAX_TRIANGLES * 6];
    private static int musicFor(String level) {
        return switch (level.toLowerCase(java.util.Locale.ROOT)) {
            case "jrb", "ddd" -> 5;
            case "ccm", "sl" -> 8;
            case "bbh" -> 10;
            case "hmc" -> 12;
            case "lll", "ssl" -> 6;
            case "wdw" -> 3;
            case "ttm", "rr" -> 9;
            case "castle_grounds", "castle_inside" -> 4;
            default -> 3;
        };
    }
    static String actionName() { return Integer.toHexString(action); }
    static int health() { return initialized ? state.getShort(32) : 0x880; }

    static void render(MatrixStack ms, VertexConsumerProvider vcp, Vec3d cam, Vec3d playerPos) {
        if (triangleCount == 0) return;
        RetroMapClient.Active level = RetroMapClient.sm64();
        if (level == null) return;
        // libsm64 geometry is already transformed into its local world coordinates, including Mario's
        // facing. Translate by the map anchor, not the player position, or Mario's position is applied twice.
        ms.push(); ms.translate(level.x - cam.x, level.y - cam.y, level.z - cam.z); ms.scale(.01f, .01f, .01f);
        var entry = ms.peek();
        // Use a dedicated texture/layer: the vanilla white-concrete layer may already have been flushed
        // earlier in AFTER_ENTITIES, leaving its Immediate provider buffer closed for this callback.
        VertexConsumer colorPass = vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(WHITE));
        for (int t = 0; t < triangleCount; t++) for (int k = 0; k < 4; k++) {
            int v = t * 3 + Math.min(k, 2), i = v * 3, u = v * 2;
            float x = POS_CACHE[i], y = POS_CACHE[i + 1], z = POS_CACHE[i + 2];
            float nx = NORMAL_CACHE[i], ny = NORMAL_CACHE[i + 1], nz = NORMAL_CACHE[i + 2];
            colorPass.vertex(entry, x, y, z).color(POSITIVE(COLOR_CACHE[i]), POSITIVE(COLOR_CACHE[i + 1]), POSITIVE(COLOR_CACHE[i + 2]), 255).texture(.5f,.5f).overlay(0).light(0xF000F0).normal(entry,nx,ny,nz);
        }
        // Immediate providers flush the previous RenderLayer when getBuffer switches layers, so finish
        // the color pass before obtaining the atlas consumer.
        VertexConsumer texturePass = vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(ATLAS));
        for (int t = 0; t < triangleCount; t++) for (int k = 0; k < 4; k++) {
            int v = t * 3 + Math.min(k, 2), i = v * 3, u = v * 2;
            if (UV_CACHE[u] == 1f && UV_CACHE[u + 1] == 1f) continue;
            float x = POS_CACHE[i], y = POS_CACHE[i + 1], z = POS_CACHE[i + 2];
            float nx = NORMAL_CACHE[i], ny = NORMAL_CACHE[i + 1], nz = NORMAL_CACHE[i + 2];
            texturePass.vertex(entry, x + nx * .0035f, y + ny * .0035f, z + nz * .0035f).color(255,255,255,255).texture(UV_CACHE[u],UV_CACHE[u + 1]).overlay(0).light(0xF000F0).normal(entry,nx,ny,nz);
        }
        ms.pop();
    }
    private static int POSITIVE(float c) { return Math.max(0, Math.min(255, Math.round(c * 255))); }
}
