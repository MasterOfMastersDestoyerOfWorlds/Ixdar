package ixdar.scenes.model;

import java.util.List;

import ixdar.platform.Platforms;
import ixdar.scenes.EditTool;
import ixdar.scenes.Scene;

/**
 * The look-only tool every model scene opens with: the mouse orbits, pans and zooms the camera
 * and nothing else, so nothing a scene's other tools edit can change while it is active.
 */
public final class OrbitTool implements EditTool {

    public static final String TOOL_NAME = "orbit";

    /** Scene whose mouse the tool leaves to the camera. */
    public final ModelScene scene;

    /**
     * Binds the tool to its scene.
     *
     * @param scene the model scene whose camera the mouse drives
     */
    public OrbitTool(ModelScene scene) {
        this.scene = scene;
    }

    @Override
    public String toolName() {
        return TOOL_NAME;
    }

    /**
     * Leave every drag to the camera; the tool edits nothing, so every click is on empty space and
     * a double-click frames the whole model.
     */
    @Override
    public void activate() {
        if (scene.orbitMouse != null) {
            scene.orbitMouse.toolClick = null;
            scene.orbitMouse.toolGrab = null;
            scene.orbitMouse.toolRelease = null;
            scene.orbitMouse.untakenClick = button -> scene.emptySpaceClicked(scene.mouse.normalizedPosX,
                    scene.mouse.normalizedPosY);
        }
        Platforms.get().log(Scene.TOOL_LOG_PREFIX
                + "orbit: drag orbits, Shift+drag pans, scroll zooms; nothing is edited");
    }

    /** Stop reading clicks, which the next tool may take. */
    @Override
    public void deactivate() {
        if (scene.orbitMouse != null) {
            scene.orbitMouse.untakenClick = null;
        }
    }

    /** Nothing runs per frame; the camera moves through the orbit mouse directly. */
    @Override
    public void perFrame() {
    }

    /**
     * Shift+drag pans and a double-click frames the whole model, as the F the scene adds does; the
     * scene lists drag and scroll.
     *
     * @param controls the scene's control list, after the tool switches
     */
    @Override
    public void addControls(List<ControlHint> controls) {
        controls.add(new ControlHint("shift+drag", "pan the camera"));
        controls.add(new ControlHint(Scene.FRAME_ALL_CLICK, "frame the whole model, as F does", scene::frameAll));
    }
}
