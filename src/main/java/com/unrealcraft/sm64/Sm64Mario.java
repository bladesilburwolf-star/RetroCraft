package com.unrealcraft.sm64;

/**
 * Mario's movement, ported from n64decomp/sm64:
 *   game/mario_step.c            perform_ground_step, perform_air_step (+ quarter steps), apply_gravity
 *   game/mario.c                 joystick/geometry inputs, jump chaining (set_jump_from_landing), jump launch speeds
 *   game/mario_actions_moving.c  update_walking_speed, apply_slope_accel/decel, landing actions
 *   game/mario_actions_airborne.c jump / double jump / triple jump / freefall / wall kick, air control
 *
 * Runs at SM64's native 30 frames per second. Units are SM64 units; angles are s16 (65536 = full turn).
 *
 * Also: long jump, backflip, dive, ground pound and crouch (launch speeds / gravity / hover from the decomp,
 * animation-length timings approximated).
 * NOT ported yet: sliding on steep floors (simple approximation), ledge grabs, punching, slide kick, swimming,
 * water, ice/quicksand/wind, objects. Behaviour should match the decomp for walking, running, jumping,
 * long jump, wall kicks and air control.
 */
public final class Sm64Mario {
    public enum Action { IDLE, WALKING, BRAKING, JUMP, DOUBLE_JUMP, TRIPLE_JUMP, FREEFALL, WALL_KICK_AIR, AIR_HIT_WALL,
        BONK, JUMP_LAND, FREEFALL_LAND, DOUBLE_JUMP_LAND, TRIPLE_JUMP_LAND, SLIDING,
        LONG_JUMP, GROUND_POUND, GROUND_POUND_LAND, DIVE, CROUCH_IDLE, CROUCH_MOVE }

    private static final int GROUND_STEP_NONE = 0, GROUND_STEP_LEFT_GROUND = 1, GROUND_STEP_HIT_WALL = 2,
            GROUND_STEP_HIT_WALL_STOP_QSTEPS = 2, GROUND_STEP_HIT_WALL_CONTINUE_QSTEPS = 3;
    private static final int AIR_STEP_NONE = 0, AIR_STEP_LANDED = 1, AIR_STEP_HIT_WALL = 2;

    // --- inputs, set by the caller each frame -------------------------------------------------------
    /** Analog stick, -64..64 like the N64 controller (keyboard: +-64). */
    public float stickX, stickY;
    public boolean aDown, zDown, bDown;  // B = dive (sprint key)
    /** Direction from Mario to the camera, s16 (SM64's camera->yaw). */
    public int camYaw;

    // --- state --------------------------------------------------------------------------------------
    public final float[] pos = new float[3];
    public final float[] vel = new float[3];
    public float forwardVel;
    public int faceYaw; // s16
    public Action action = Action.IDLE, prevAction = Action.IDLE;
    public Sm64Collision.Surface floor, ceil, wall;
    public float floorHeight, ceilHeight;
    public int actionTimer, doubleJumpTimer;
    public float intendedMag;
    public int intendedYaw;
    public boolean onGround = true;

    private boolean aPressed, aWasDown, zPressed, zWasDown, bPressed, bWasDown, nonzeroAnalog;
    private float slideVelX, slideVelZ;
    private final float[] lastSafe = new float[3];
    private final float[] startPos = new float[3];
    private final Sm64Collision col;

    public Sm64Mario(Sm64Collision col, float x, float y, float z, int yaw) {
        this.col = col;
        pos[0] = x; pos[1] = y; pos[2] = z;
        faceYaw = (short) yaw;
        startPos[0] = x; startPos[1] = y; startPos[2] = z;
        System.arraycopy(pos, 0, lastSafe, 0, 3);
        var f = col.findFloor(x, y, z);
        floor = f.surface; floorHeight = f.height;
    }

    // ================================================================================ math helpers
    public static float sins(int a) { return (float) Math.sin((short) a * Math.PI / 32768.0); }
    public static float coss(int a) { return (float) Math.cos((short) a * Math.PI / 32768.0); }
    /** atan2s(y, x): angle of (x,y) measured from +y toward +x (so atan2s(nz, nx) is a yaw). */
    public static int atan2s(float y, float x) { return (short) Math.round(Math.atan2(x, y) * 32768.0 / Math.PI); }
    private static float approach(float cur, float target, float inc, float dec) {
        if (cur < target) { cur += inc; if (cur > target) cur = target; }
        else { cur -= dec; if (cur < target) cur = target; }
        return cur;
    }
    private static int approachS32(int cur, int target, int inc, int dec) {
        if (cur < target) { cur += inc; if (cur > target) cur = target; }
        else { cur -= dec; if (cur < target) cur = target; }
        return cur;
    }

    // ================================================================================ per-frame entry
    /** One SM64 frame (1/30 s). */
    public void update() {
        aPressed = aDown && !aWasDown; aWasDown = aDown;
        zPressed = zDown && !zWasDown; zWasDown = zDown;
        bPressed = bDown && !bWasDown; bWasDown = bDown;
        updateJoystick();
        updateGeometry();
        if (doubleJumpTimer > 0) doubleJumpTimer--;
        switch (action) {
            case IDLE -> actIdle();
            case WALKING -> actWalking();
            case BRAKING -> actBraking();
            case JUMP -> actAirJump(Action.JUMP_LAND);
            case DOUBLE_JUMP -> actAirJump(Action.DOUBLE_JUMP_LAND);
            case TRIPLE_JUMP -> actAirJump(Action.TRIPLE_JUMP_LAND);
            case FREEFALL -> actFreefall();
            case WALL_KICK_AIR -> actAirJump(Action.JUMP_LAND);
            case AIR_HIT_WALL -> actAirHitWall();
            case BONK -> actBonk();
            case JUMP_LAND, FREEFALL_LAND, DOUBLE_JUMP_LAND, TRIPLE_JUMP_LAND -> actLanding();
            case SLIDING -> actSliding();
            case LONG_JUMP -> actAirJump(Action.JUMP_LAND);
            case GROUND_POUND -> actGroundPound();
            case GROUND_POUND_LAND -> actGroundPoundLand();
            case DIVE -> actDive();
            case CROUCH_IDLE -> actCrouchIdle();
            case CROUCH_MOVE -> actCrouchMove();
        }
        onGround = isGroundAction(action);
        if (floor != null) System.arraycopy(pos, 0, lastSafe, 0, 3);
    }

    private static boolean isGroundAction(Action a) {
        return switch (a) { case IDLE, WALKING, BRAKING, JUMP_LAND, FREEFALL_LAND, DOUBLE_JUMP_LAND, TRIPLE_JUMP_LAND, SLIDING, CROUCH_IDLE, CROUCH_MOVE, GROUND_POUND_LAND -> true; default -> false; };
    }

    // ================================================================================ inputs (mario.c)
    private void updateJoystick() {
        float stickMag = Math.min(64f, (float) Math.sqrt(stickX * stickX + stickY * stickY));
        float mag = ((stickMag / 64f) * (stickMag / 64f)) * 64f;
        intendedMag = mag / 2f;
        if (intendedMag > 0f) {
            intendedYaw = (short) (atan2s(-stickY, stickX) + camYaw);
            nonzeroAnalog = true;
        } else {
            intendedYaw = faceYaw;
            nonzeroAnalog = false;
        }
    }

    private void updateGeometry() {
        float[] p = pos;
        wallPush(p, 60f, 50f);
        wallPush(p, 30f, 24f);
        var f = col.findFloor(p[0], p[1], p[2]);
        floor = f.surface; floorHeight = f.height;
        if (floor == null) { // out of bounds: fall back to the last safe spot, else respawn
            System.arraycopy(lastSafe, 0, pos, 0, 3);
            f = col.findFloor(p[0], p[1], p[2]);
            floor = f.surface; floorHeight = f.height;
            if (floor == null) { respawn(); return; }
            vel[0] = vel[1] = vel[2] = 0; forwardVel = 0;
            if (!isGroundAction(action)) setAction(Action.FREEFALL);
        }
        var c = col.findCeil(p[0], floorHeight + 80f, p[2]);
        ceil = c.surface; ceilHeight = c.height;
        if (p[1] < -9000f) respawn();
    }

    public void respawn() {
        System.arraycopy(startPos, 0, pos, 0, 3);
        vel[0] = vel[1] = vel[2] = 0; forwardVel = 0;
        var f = col.findFloor(pos[0], pos[1], pos[2]);
        floor = f.surface; floorHeight = f.height;
        if (floor != null) pos[1] = floorHeight;
        action = Action.IDLE;
    }

    private Sm64Collision.Surface resolveWall(float[] p, float offset, float radius) {
        var d = new Sm64Collision.WallData();
        d.x = p[0]; d.y = p[1]; d.z = p[2]; d.radius = radius; d.offsetY = offset;
        Sm64Collision.Surface w = null;
        if (col.findWalls(d) > 0) w = d.walls[d.numWalls - 1];
        p[0] = d.x; p[1] = d.y; p[2] = d.z;
        return w;
    }
    private void wallPush(float[] p, float offset, float radius) { resolveWall(p, offset, radius); }

    // ================================================================================ actions
    private void setAction(Action a) {
        prevAction = action;
        action = a;
        actionTimer = 0;
        switch (a) { // set_mario_action_airborne launch speeds
            case JUMP -> { setYVelBasedOnFSpeed(42f, 0.25f); forwardVel *= 0.8f; }
            case DOUBLE_JUMP -> { setYVelBasedOnFSpeed(52f, 0.25f); forwardVel *= 0.8f; }
            case TRIPLE_JUMP -> { setYVelBasedOnFSpeed(69f, 0.0f); forwardVel *= 0.8f; }
            case WALL_KICK_AIR -> { setYVelBasedOnFSpeed(62f, 0.0f); if (forwardVel < 24f) forwardVel = 24f; }
            case LONG_JUMP -> { vel[1] = 30f; }
            case GROUND_POUND -> { vel[1] = -50f; forwardVel = 0f; }
            case DIVE -> setForwardVel(Math.min(forwardVel + 15f, 48f)); // set_mario_action_airborne ACT_DIVE
            default -> { }
        }
    }

    private void setYVelBasedOnFSpeed(float initial, float mult) { vel[1] = initial + forwardVel * mult; }

    private void setForwardVel(float v) {
        forwardVel = v;
        slideVelX = sins(faceYaw) * v; slideVelZ = coss(faceYaw) * v;
        vel[0] = slideVelX; vel[2] = slideVelZ;
    }

    // --- floor class (mario_get_floor_class) -----------------------------------------------------
    private enum FloorClass { DEFAULT, VERY_SLIPPERY, SLIPPERY, NOT_SLIPPERY }
    private FloorClass floorClass() {
        if (floor == null) return FloorClass.DEFAULT;
        return switch (floor.type) {
            case "SURFACE_NOT_SLIPPERY", "SURFACE_HARD_NOT_SLIPPERY", "SURFACE_SWITCH" -> FloorClass.NOT_SLIPPERY;
            case "SURFACE_SLIPPERY", "SURFACE_NOISE_SLIPPERY", "SURFACE_HARD_SLIPPERY", "SURFACE_NO_CAM_COL_SLIPPERY" -> FloorClass.SLIPPERY;
            case "SURFACE_VERY_SLIPPERY", "SURFACE_ICE", "SURFACE_HARD_VERY_SLIPPERY", "SURFACE_NOISE_VERY_SLIPPERY",
                 "SURFACE_NOISE_VERY_SLIPPERY_73", "SURFACE_NOISE_VERY_SLIPPERY_74", "SURFACE_NO_CAM_COL_VERY_SLIPPERY" -> FloorClass.VERY_SLIPPERY;
            default -> FloorClass.DEFAULT;
        };
    }
    private boolean floorIsSlope() {
        float n = switch (floorClass()) { case VERY_SLIPPERY -> 0.9961947f; case SLIPPERY -> 0.9848077f; case NOT_SLIPPERY -> 0.9396926f; default -> 0.9659258f; };
        return floor.ny <= n;
    }
    private boolean floorIsSlippery() {
        float n = switch (floorClass()) { case VERY_SLIPPERY -> 0.9848077f; case SLIPPERY -> 0.9396926f; case NOT_SLIPPERY -> 0.7880108f; default -> 0.8660254f; };
        return floor.ny <= n;
    }
    private int floorAngle() { return atan2s(floor.nz, floor.nx); }

    // --- ground speed (mario_actions_moving.c) ---------------------------------------------------
    private void applySlopeAccel() {
        float steepness = (float) Math.sqrt(floor.nx * floor.nx + floor.nz * floor.nz);
        int floorDYaw = (short) (floorAngle() - faceYaw);
        if (floorIsSlope()) {
            float accel = switch (floorClass()) { case VERY_SLIPPERY -> 5.3f; case SLIPPERY -> 2.7f; case NOT_SLIPPERY -> 0f; default -> 1.7f; };
            if (floorDYaw > -0x4000 && floorDYaw < 0x4000) forwardVel += accel * steepness;
            else forwardVel -= accel * steepness;
        }
        slideVelX = forwardVel * sins(faceYaw); slideVelZ = forwardVel * coss(faceYaw);
        vel[0] = slideVelX; vel[1] = 0f; vel[2] = slideVelZ;
    }

    private boolean applySlopeDecel(float coef) {
        float decel = switch (floorClass()) { case VERY_SLIPPERY -> coef * 0.2f; case SLIPPERY -> coef * 0.7f; case NOT_SLIPPERY -> coef * 3.0f; default -> coef * 2.0f; };
        forwardVel = approach(forwardVel, 0f, decel, decel);
        boolean stopped = forwardVel == 0f;
        applySlopeAccel();
        return stopped;
    }

    private void updateWalkingSpeed() {
        float maxTarget = (floor != null && floor.type.equals("SURFACE_SLOW")) ? 24f : 32f;
        float target = Math.min(intendedMag, maxTarget);
        if (forwardVel <= 0f) forwardVel += 1.1f;
        else if (forwardVel <= target) forwardVel += 1.1f - forwardVel / 43f;
        else if (floor.ny >= 0.95f) forwardVel -= 1.0f;
        if (forwardVel > 48f) forwardVel = 48f;
        faceYaw = (short) (intendedYaw - approachS32((short) (intendedYaw - faceYaw), 0, 0x800, 0x800));
        applySlopeAccel();
    }

    // --- stationary / walking / braking ----------------------------------------------------------
    private boolean steepFloor() { return floor != null && floor.ny < 0.8660254f && floorIsSlippery() && floor.ny < 0.82f; }

    private void actIdle() {
        if (steepFloor()) { setAction(Action.SLIDING); return; }
        if (aPressed) { jumpFromLanding(); return; }
        if (zDown) { setAction(Action.CROUCH_IDLE); return; }
        if (nonzeroAnalog) { faceYaw = intendedYaw; setAction(Action.WALKING); return; }
        setForwardVel(0f);
        pos[1] = floorHeight; // stationary_ground_step snaps to the floor
    }

    private void actWalking() {
        if (steepFloor()) { setAction(Action.SLIDING); return; }
        if (aPressed) { jumpFromLanding(); return; }
        if (zDown) { setAction(Action.CROUCH_MOVE); return; }
        if (!nonzeroAnalog) { beginBraking(); return; }
        updateWalkingSpeed();
        switch (performGroundStep()) {
            case GROUND_STEP_LEFT_GROUND -> setAction(Action.FREEFALL);
            default -> { }
        }
    }

    private void beginBraking() {
        if (forwardVel >= 16f && floor.ny >= 0.17364818f) setAction(Action.BRAKING);
        else setAction(Action.BRAKING); // ACT_DECELERATING behaves like braking with a gentler decel
    }

    private void actBraking() {
        if (aPressed) { jumpFromLanding(); return; }
        if (nonzeroAnalog) { faceYaw = intendedYaw; setAction(Action.WALKING); return; }
        if (applySlopeDecel(forwardVel >= 16f ? 2.0f : 1.0f)) { setAction(Action.IDLE); return; }
        if (performGroundStep() == GROUND_STEP_LEFT_GROUND) setAction(Action.FREEFALL);
    }

    /** Simple approximation of ACT_SLIDING for steep floors (the real slide actions are not ported yet). */
    private void actSliding() {
        if (floor == null || floor.ny >= 0.9f) { setAction(Action.IDLE); return; }
        if (aPressed) { jumpFromLanding(); return; }
        int down = floorAngle();
        faceYaw = down;
        forwardVel = Math.min(forwardVel + 2.0f * (float) Math.sqrt(floor.nx * floor.nx + floor.nz * floor.nz) * 4f, 40f);
        slideVelX = forwardVel * sins(down); slideVelZ = forwardVel * coss(down);
        vel[0] = slideVelX; vel[1] = 0; vel[2] = slideVelZ;
        if (performGroundStep() == GROUND_STEP_LEFT_GROUND) setAction(Action.FREEFALL);
    }

    // --- jumping --------------------------------------------------------------------------------
    /** set_jump_from_landing (mario.c). */
    private void jumpFromLanding() {
        // Long jump: Z + running + A
        if (zDown && forwardVel > 16f) {
            setAction(Action.LONG_JUMP);
            setForwardVel(Math.min(forwardVel * 1.5f, 48f));
            vel[1] = 30f;
            doubleJumpTimer = 0;
            return;
        }
        // Backflip: Z + nearly still + A
        if (zDown && Math.abs(forwardVel) < 8f) {
            setAction(Action.JUMP);
            faceYaw = (short) (faceYaw + 0x8000);
            setForwardVel(-16f);
            vel[1] = 62f;
            doubleJumpTimer = 0;
            return;
        }
        if (doubleJumpTimer == 0) setAction(Action.JUMP);
        else switch (prevAction) {
            case JUMP_LAND, FREEFALL_LAND -> setAction(Action.DOUBLE_JUMP);
            case DOUBLE_JUMP_LAND -> setAction(forwardVel > 20f ? Action.TRIPLE_JUMP : Action.JUMP);
            default -> setAction(Action.JUMP);
        }
        doubleJumpTimer = 0;
    }

    // --- landing --------------------------------------------------------------------------------
    private void actLanding() {
        int doubleTimer = (action == Action.TRIPLE_JUMP_LAND) ? 0 : 5;
        doubleJumpTimer = doubleTimer;
        if (floor.ny < 0.2923717f) { setAction(Action.FREEFALL); return; }
        if (++actionTimer >= 4) { setAction(Action.IDLE); return; }
        if (aPressed && action != Action.TRIPLE_JUMP_LAND) {
            // landing's aPressedAction: set_jumping_action(ACT_DOUBLE_JUMP) / set_triple_jump_action
            if (action == Action.DOUBLE_JUMP_LAND) setAction(forwardVel > 20f ? Action.TRIPLE_JUMP : Action.JUMP);
            else setAction(Action.DOUBLE_JUMP);
            doubleJumpTimer = 0;
            return;
        }
        if (pos[1] > floorHeight + 100f) { setAction(Action.FREEFALL); return; }
        // common_landing_action: apply_landing_accel
        forwardVel *= 0.98f;
        applySlopeAccel();
        if (performGroundStep() == GROUND_STEP_LEFT_GROUND) setAction(Action.FREEFALL);
    }

    // --- airborne -------------------------------------------------------------------------------
    private void actFreefall() {
        if (bPressed) { setAction(Action.DIVE); return; }
        if (zPressed) { setAction(Action.GROUND_POUND); return; }
        commonAirStep(Action.FREEFALL_LAND, true);
    }
    private void actAirJump(Action land) {
        if (bPressed) { setAction(Action.DIVE); return; }
        if (zPressed) { setAction(Action.GROUND_POUND); return; }
        commonAirStep(land, true);
    }

    private void commonAirStep(Action landAction, boolean control) {
        updateAirWithoutTurn();
        int result = performAirStep();
        switch (result) {
            case AIR_STEP_LANDED -> setAction(landAction);
            case AIR_STEP_HIT_WALL -> {
                if (forwardVel > 16f) {
                    bonkReflection(false);
                    faceYaw = (short) (faceYaw + 0x8000);
                    if (wall != null) setAction(Action.AIR_HIT_WALL);
                    else {
                        if (vel[1] > 0f) vel[1] = 0f;
                        if (forwardVel > 8f) setForwardVel(-8f);
                        setAction(Action.BONK);
                    }
                } else setForwardVel(0f);
            }
            default -> { }
        }
    }

    private void actAirHitWall() {
        if (++actionTimer <= 2) {
            if (aPressed) { vel[1] = 52f; faceYaw = (short) (faceYaw + 0x8000); setAction(Action.WALL_KICK_AIR); }
        } else {
            if (vel[1] > 0f) vel[1] = 0f;
            if (forwardVel >= 38f) { setAction(Action.BONK); }
            else { if (forwardVel > 8f) setForwardVel(-8f); setAction(Action.BONK); }
        }
    }

    /** Knockback-style air action: no air control, lands into idle. */
    private void actBonk() {
        int r = performAirStep();
        if (r == AIR_STEP_LANDED) { setForwardVel(0f); setAction(Action.IDLE); }
    }

    private void bonkReflection(boolean negateSpeed) {
        if (wall != null) {
            int wallAngle = atan2s(wall.nz, wall.nx);
            faceYaw = (short) (wallAngle - (short) (faceYaw - wallAngle));
        }
        if (negateSpeed) setForwardVel(-forwardVel);
    }

    private void updateAirWithoutTurn() {
        float sideways = 0f;
        float drag = 32f;
        forwardVel = approach(forwardVel, 0f, 0.35f, 0.35f);
        if (nonzeroAnalog) {
            int dYaw = (short) (intendedYaw - faceYaw);
            float mag = intendedMag / 32f;
            forwardVel += mag * coss(dYaw) * 1.5f;
            sideways = mag * sins(dYaw) * 10f;
        }
        if (forwardVel > drag) forwardVel -= 1f;
        if (forwardVel < -16f) forwardVel += 2f;
        slideVelX = forwardVel * sins(faceYaw) + sideways * sins(faceYaw + 0x4000);
        slideVelZ = forwardVel * coss(faceYaw) + sideways * coss(faceYaw + 0x4000);
        vel[0] = slideVelX; vel[2] = slideVelZ;
    }

    // ================================================================================ mario_step.c
    private int performGroundStep() {
        int result = GROUND_STEP_NONE;
        for (int i = 0; i < 4; i++) {
            float[] next = {pos[0] + floor.ny * (vel[0] / 4f), pos[1], pos[2] + floor.ny * (vel[2] / 4f)};
            result = groundQuarterStep(next);
            if (result == GROUND_STEP_LEFT_GROUND || result == GROUND_STEP_HIT_WALL_STOP_QSTEPS) break;
        }
        if (result == GROUND_STEP_HIT_WALL_CONTINUE_QSTEPS) result = GROUND_STEP_HIT_WALL;
        return result;
    }

    private int groundQuarterStep(float[] next) {
        resolveWall(next, 30f, 24f);
        Sm64Collision.Surface upper = resolveWall(next, 60f, 50f);
        var f = col.findFloor(next[0], next[1], next[2]);
        float fh = f.height;
        var c = col.findCeil(next[0], fh + 80f, next[2]);
        wall = upper;
        if (f.surface == null) return GROUND_STEP_HIT_WALL_STOP_QSTEPS;
        if (next[1] > fh + 100f) {
            if (next[1] + 160f >= c.height) return GROUND_STEP_HIT_WALL_STOP_QSTEPS;
            System.arraycopy(next, 0, pos, 0, 3);
            floor = f.surface; floorHeight = fh;
            return GROUND_STEP_LEFT_GROUND;
        }
        if (fh + 160f >= c.height) return GROUND_STEP_HIT_WALL_STOP_QSTEPS;
        pos[0] = next[0]; pos[1] = fh; pos[2] = next[2];
        floor = f.surface; floorHeight = fh;
        if (upper != null) {
            int wallDYaw = (short) (atan2s(upper.nz, upper.nx) - faceYaw);
            if (wallDYaw >= 0x2AAA && wallDYaw <= 0x5555) return GROUND_STEP_NONE;
            if (wallDYaw <= -0x2AAA && wallDYaw >= -0x5555) return GROUND_STEP_NONE;
            return GROUND_STEP_HIT_WALL_CONTINUE_QSTEPS;
        }
        return GROUND_STEP_NONE;
    }

    private int performAirStep() {
        wall = null;
        int stepResult = AIR_STEP_NONE;
        for (int i = 0; i < 4; i++) {
            float[] intended = {pos[0] + vel[0] / 4f, pos[1] + vel[1] / 4f, pos[2] + vel[2] / 4f};
            int q = airQuarterStep(intended);
            if (q != AIR_STEP_NONE) stepResult = q;
            if (q == AIR_STEP_LANDED) break;
        }
        applyGravity();
        return stepResult;
    }

    private int airQuarterStep(float[] intended) {
        float[] next = intended.clone();
        Sm64Collision.Surface upper = resolveWall(next, 150f, 50f);
        Sm64Collision.Surface lower = resolveWall(next, 30f, 50f);
        var f = col.findFloor(next[0], next[1], next[2]);
        float fh = f.height;
        var c = col.findCeil(next[0], fh + 80f, next[2]);
        float ch = c.height;
        wall = null;
        if (f.surface == null) {
            if (next[1] <= floorHeight) { pos[1] = floorHeight; return AIR_STEP_LANDED; }
            pos[1] = next[1];
            return AIR_STEP_HIT_WALL;
        }
        if (next[1] <= fh) {
            if (ch - fh > 160f) { pos[0] = next[0]; pos[2] = next[2]; floor = f.surface; floorHeight = fh; }
            pos[1] = fh;
            return AIR_STEP_LANDED;
        }
        if (next[1] + 160f > ch) {
            if (vel[1] >= 0f) { vel[1] = 0f; return AIR_STEP_NONE; }
            if (next[1] <= floorHeight) { pos[1] = floorHeight; return AIR_STEP_LANDED; }
            pos[1] = next[1];
            return AIR_STEP_HIT_WALL;
        }
        // ledge grab (AIR_STEP_CHECK_LEDGE_GRAB) is not ported: the decomp's "no grab" path
        if (upper == null && lower != null) {
            System.arraycopy(next, 0, pos, 0, 3);
            floor = f.surface; floorHeight = fh;
            return AIR_STEP_NONE;
        }
        System.arraycopy(next, 0, pos, 0, 3);
        floor = f.surface; floorHeight = fh;
        if (upper != null || lower != null) {
            wall = upper != null ? upper : lower;
            int wallDYaw = (short) (atan2s(wall.nz, wall.nx) - faceYaw);
            if (wallDYaw < -0x6000 || wallDYaw > 0x6000) return AIR_STEP_HIT_WALL;
        }
        return AIR_STEP_NONE;
    }


    // --- long jump / dive / ground pound / crouch (merged from pure-Java port) --------------------
    private void actDive() {
        updateAirWithoutTurn();
        int r = performAirStep();
        if (r == AIR_STEP_LANDED) {
            setForwardVel(forwardVel * 0.5f);
            setAction(Action.FREEFALL_LAND);
        } else if (r == AIR_STEP_HIT_WALL) {
            bonkReflection(true);
            setAction(Action.BONK);
        }
    }

    private void actGroundPound() {
        if (actionTimer < 15) { // actionState 0: the spin; rises (20 - 2t) per frame for 10 frames, no falling yet
            if (actionTimer < 10) {
                float yOff = 20f - 2f * actionTimer;
                if (pos[1] + yOff + 160f < ceilHeight) pos[1] += yOff;
            }
            vel[0] = vel[2] = 0f; vel[1] = -50f; forwardVel = 0f;
            actionTimer++;
            return;
        }
        // actionState 1: fall; gravity keeps accelerating from -50 down to the -75 cap
        vel[0] = vel[2] = 0f; forwardVel = 0f;
        int r = performAirStep();
        if (r == AIR_STEP_LANDED) {
            setAction(Action.GROUND_POUND_LAND);
        } else if (r == AIR_STEP_HIT_WALL) {
            setForwardVel(-16f);
            if (vel[1] > 0f) vel[1] = 0f;
        }
    }

    private void actGroundPoundLand() {
        setForwardVel(0f);
        pos[1] = floorHeight;
        if (++actionTimer >= 6) {
            if (aPressed) jumpFromLanding();
            else if (nonzeroAnalog) setAction(Action.WALKING);
            else setAction(Action.IDLE);
        }
    }

    private void actCrouchIdle() {
        setForwardVel(0f);
        pos[1] = floorHeight;
        if (!zDown) { setAction(Action.IDLE); return; }
        if (aPressed) { jumpFromLanding(); return; }
        if (nonzeroAnalog) setAction(Action.CROUCH_MOVE);
    }

    private void actCrouchMove() {
        if (!zDown) { setAction(Action.WALKING); return; }
        if (aPressed) { jumpFromLanding(); return; }
        if (!nonzeroAnalog) { setAction(Action.CROUCH_IDLE); return; }
        // slow crawl
        float target = Math.min(intendedMag * 0.25f, 8f);
        forwardVel = approach(forwardVel, target, 0.8f, 1.0f);
        faceYaw = (short) (intendedYaw - approachS32((short) (intendedYaw - faceYaw), 0, 0x400, 0x400));
        applySlopeAccel();
        if (performGroundStep() == GROUND_STEP_LEFT_GROUND) setAction(Action.FREEFALL);
    }

    private void applyGravity() {
        // should_strengthen_gravity_for_jump_ascent: releasing A early cuts the jump short
        if (!aDown && vel[1] > 20f && (action == Action.JUMP || action == Action.DOUBLE_JUMP || action == Action.TRIPLE_JUMP
                || action == Action.WALL_KICK_AIR)) {
            vel[1] /= 4f;
        } else if (action == Action.LONG_JUMP) {
            vel[1] -= 2f; // mario_step.c apply_gravity
            if (vel[1] < -75f) vel[1] = -75f;
        } else {
            vel[1] -= 4f;
            if (vel[1] < -75f) vel[1] = -75f;
        }
    }
}
