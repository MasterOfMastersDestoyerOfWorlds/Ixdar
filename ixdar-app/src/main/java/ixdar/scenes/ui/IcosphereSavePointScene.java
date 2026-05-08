package ixdar.scenes.ui;

import java.util.ArrayList;
import java.util.Random;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.standalone.Icosphere;
import ixdar.graphics.render.Clock;
import ixdar.graphics.render.model.IcosphereRuntime;
import ixdar.scenes.Scene;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.platform.Platforms;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.MouseTrap;
import ixdar.platform.input.SceneInputFrameUpdater;

@SceneAnnotation(id = "icosphere-save-point-canvas")
public class IcosphereSavePointScene extends Scene {
    public static final float NUM_1 = 1f;
    public static final float NUM_0_01 = 0.01f;
    public static final float NUM_200 = 200f;
    public static final float NUM_0 = 0f;
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_0_18 = 0.18f;
    public static final float NUM_0_0001 = 0.0001f;
    public static final float NUM_0_1 = 0.1f;
    public static final float NUM_0_06 = 0.06f;
    public static final float NUM_0_8 = 0.8f;
    public static final float NUM_0_4 = 0.4f;

    private static final float ICO_RADIUS = 2.5f;
    private static final float EXPAND_DISTANCE = 1.5f;
    private static final float ROTATION_SPEED = 6.0f;
    private static final float MOVE_PAUSE_SECONDS = 0.35f;
    private static final float ROTATION_STEP = (float) ((Math.PI * 2.0) / 5.0);

    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f clipTransform = new Matrix4f();
    private final Vector4f centerClip = new Vector4f();
    private final Random random = new Random(123L);
    private final Vector3f activeAxis = new Vector3f();
    private final ArrayList<Integer> activeGroup = new ArrayList<>();

    private Icosphere geometry;
    private IcosphereRuntime runtime;
    private boolean isHovered;
    private float currentExpansion;
    private float shuffleTimer;
    private boolean isRotating;
    private float currentRotation;
    private float targetRotation;
    private boolean saveTriggered;

    @Override
    public void initGL() {
        super.initGL();
        initCameraControls();
        try {
            geometry = new Icosphere(ICO_RADIUS);
            runtime = new IcosphereRuntime(geometry);
            runtime.frameCamera(camera);
            runtime.resetToIdeal();
        } catch (Exception ex) {
            Platforms.get().log("[IcosphereSavePointScene] Failed to init runtime: " + ex.getMessage());
        }
    }

    @Override
    public void drawScene() {
        updateCameraControls();
        if (runtime == null || geometry == null) {
            return;
        }
        camera.resetView();
        updateHoverState();
        updateAnimation();
        runtime.render(camera, currentExpansion);
    }

    private void initCameraControls() {
        MenuBox.menuVisible = false;
        keys = new KeyGuy(camera, this);
        mouse = new IcosphereMouseTrap(camera);
        bindAutomationIfAvailable(Platforms.get(), keys, mouse);
    }

    private void updateCameraControls() {
        SceneInputFrameUpdater.update(keys, mouse);
        camera.updateViewFirstPerson();
    }

    @Override
    public void activate(boolean state) {
        super.activate(state);
        if (!state) {
            disposeRuntime();
        }
    }

    @Override
    public void shutdown() {
        disposeRuntime();
        super.shutdown();
    }

    private void updateHoverState() {
        IcosphereMouseTrap trap = (IcosphereMouseTrap) mouse;
        if (!trap.hasMousePosition()) {
            isHovered = false;
            return;
        }

        int width = Platforms.get().getWindowWidth();
        int height = Platforms.get().getWindowHeight();
        float aspect = width <= 0 || height <= 0 ? NUM_1 : ((float) width / (float) height);
        projection.identity().perspective((float) Math.toRadians((float) camera.fov), aspect, NUM_0_01, NUM_200);
        clipTransform.set(projection).mul(camera.view);
        centerClip.set(NUM_0, NUM_0, NUM_0, NUM_1);
        clipTransform.transform(centerClip);
        if (centerClip.w == NUM_0) {
            isHovered = false;
            return;
        }

        float ndcX = centerClip.x / centerClip.w;
        float ndcY = centerClip.y / centerClip.w;
        float centerX = (ndcX * NUM_0_5 + NUM_0_5) * width;
        float centerY = (NUM_1 - (ndcY * NUM_0_5 + NUM_0_5)) * height;
        float hoverRadius = Math.min(width, height) * NUM_0_18;
        float dx = trap.lastWindowX - centerX;
        float dy = trap.lastWindowY - centerY;
        isHovered = (dx * dx + dy * dy) <= (hoverRadius * hoverRadius);
    }

    private void updateAnimation() {
        float delta = Math.max(NUM_0_0001, (float) Clock.deltaTime());
        if (!isHovered) {
            isRotating = false;
            shuffleTimer = NUM_0;
            currentExpansion = lerp(currentExpansion, NUM_0, NUM_0_1);
            runtime.applyExpansion(currentExpansion, EXPAND_DISTANCE);
            return;
        }

        currentExpansion = lerp(currentExpansion, NUM_1, NUM_0_06);
        if (currentExpansion > NUM_0_8) {
            if (!isRotating) {
                shuffleTimer += delta;
                if (shuffleTimer >= MOVE_PAUSE_SECONDS) {
                    triggerMove();
                    shuffleTimer = NUM_0;
                }
            } else {
                float step = ROTATION_SPEED * delta;
                if (currentRotation + step >= targetRotation) {
                    float remain = targetRotation - currentRotation;
                    runtime.applyRotation(activeGroup, activeAxis, remain);
                    runtime.snapToIdeal(geometry.idealStates());
                    isRotating = false;
                } else {
                    currentRotation += step;
                    runtime.applyRotation(activeGroup, activeAxis, step);
                }
            }
        }
        runtime.applyExpansion(currentExpansion, EXPAND_DISTANCE);
    }

    private void triggerMove() {
        boolean cap = random.nextFloat() > NUM_0_4;
        activeAxis.set(geometry.randomAxis(random));
        activeGroup.clear();
        activeGroup.addAll(geometry.selectBand(activeAxis, cap));
        if (activeGroup.isEmpty()) {
            activeGroup.addAll(geometry.selectBand(activeAxis, true));
        }
        targetRotation = ROTATION_STEP;
        currentRotation = NUM_0;
        isRotating = true;
    }

    private void onPrimaryClick() {
        if (isHovered && !saveTriggered) {
            saveTriggered = true;
            Platforms.get().log("[IcosphereSavePointScene] Save point activated.");
        }
    }

    private void disposeRuntime() {
        if (runtime != null) {
            runtime.dispose();
            runtime = null;
        }
    }

    private float lerp(float from, float to, float alpha) {
        return from + (to - from) * alpha;
    }

    private class IcosphereMouseTrap extends MouseTrap {
        private float lastWindowX = Float.NaN;
        private float lastWindowY = Float.NaN;

        IcosphereMouseTrap(ixdar.graphics.cameras.Camera3D cam) {
            super(null, cam, IcosphereSavePointScene.this);
        }

        boolean hasMousePosition() {
            return !Float.isNaN(lastWindowX) && !Float.isNaN(lastWindowY);
        }

        @Override
        public void mousePos(float x, float y) {
            normalizedPosX = camera.getNormalizePosX(x);
            normalizedPosY = camera.getNormalizePosY(y);
            lastX = (int) x;
            lastY = (int) y;
            lastWindowX = x;
            lastWindowY = y;
        }

        @Override
        public void mouseClicked(float xPos, float yPos, int button) {
            super.mouseClicked(xPos, yPos, button);
            if (button == 0) {
                onPrimaryClick();
            }
        }
    }
}
