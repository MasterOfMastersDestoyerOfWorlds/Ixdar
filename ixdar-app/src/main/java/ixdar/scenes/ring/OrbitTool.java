package ixdar.scenes.ring;

import java.util.List;

import ixdar.platform.Platforms;
import ixdar.scenes.model.ControlHint;

/**
 * The look-only tool of the editing scene: the mouse orbits, pans and zooms the camera and
 * nothing else, so the rings, their anchors and the region selection cannot change while it is
 * active.
 */
public final class OrbitTool implements EditTool {

    public static final String TOOL_NAME = "orbit";

    /** Scene whose mouse the tool leaves to the camera. */
    public final RingScene scene;

    /**
     * Binds the tool to its scene.
     *
     * @param scene the editing scene whose camera the mouse drives
     */
    public OrbitTool(RingScene scene) {
        this.scene = scene;
    }

    @Override
    public String toolName() {
        return TOOL_NAME;
    }

    /** Leave every click and drag to the camera. */
    @Override
    public void activate() {
        if (scene.orbitMouse != null) {
            scene.orbitMouse.toolClick = null;
            scene.orbitMouse.toolGrab = null;
            scene.orbitMouse.toolRelease = null;
        }
        Platforms.get().log(RingScene.LOG_PREFIX
                + "orbit: drag orbits, Shift+drag pans, scroll zooms; nothing is edited");
    }

    /** Nothing to hand back: the tool never took the mouse. */
    @Override
    public void deactivate() {
    }

    /** Nothing runs per frame; the camera moves through the orbit mouse directly. */
    @Override
    public void perFrame() {
    }

    @Override
    public void addControls(List<ControlHint> controls) {
        controls.add(new ControlHint("shift+drag", "pan the camera"));
    }
}
