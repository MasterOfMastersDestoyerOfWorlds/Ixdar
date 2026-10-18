package ixdar.tools.orbit;

import java.util.List;

import ixdar.annotations.tool.ToolAnnotation;
import ixdar.platform.Platforms;
import ixdar.scenes.Scene;
import ixdar.scenes.model.ModelScene;
import ixdar.tools.ControlHint;
import ixdar.tools.Tool;

/**
 * The look-only tool every model scene opens with: the mouse orbits, pans and zooms the camera
 * and nothing else, so nothing a scene's other tools edit can change while it is active.
 */
@ToolAnnotation(id = OrbitTool.TOOL_NAME, host = ModelScene.class)
public final class OrbitTool implements Tool {

    public static final String TOOL_NAME = "orbit";

    /** Scene whose camera the tool leaves the mouse to, and whose view a double-click frames. */
    public final Scene scene;

    /**
     * Binds the tool to its scene.
     *
     * @param scene the scene whose camera the mouse drives
     */
    public OrbitTool(Scene scene) {
        this.scene = scene;
    }

    @Override
    public String toolName() {
        return TOOL_NAME;
    }

    /** Leave every drag to the camera; the tool takes no press, so only its clicks are read. */
    @Override
    public void activate() {
        Platforms.get().log(Scene.TOOL_LOG_PREFIX
                + "orbit: drag orbits, Shift+drag pans, scroll zooms; nothing is edited");
    }

    /** Nothing to hand back: the tool holds no press and edits nothing. */
    @Override
    public void deactivate() {
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

    /**
     * The tool edits nothing, so every click is on empty space, and a double-click frames the
     * whole model; the overlay text's links still get the click.
     *
     * @param x     framebuffer x of the release
     * @param y     framebuffer y of the release, y up
     * @param click true when the press did not move
     * @return false, leaving the click to the overlay links as well
     */
    @Override
    public boolean onRelease(float x, float y, boolean click) {
        if (click) {
            scene.emptySpaceClicked(x, y);
        }
        return false;
    }
}
