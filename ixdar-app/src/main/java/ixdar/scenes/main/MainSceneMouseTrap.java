package ixdar.scenes.main;

import ixdar.graphics.cameras.Camera;
import ixdar.gui.ui.PaneTypes;
import ixdar.platform.input.MouseTrap;

/**
 * The tour editor's mouse handler: the editor's four panes, clicks and hover in the knot view
 * going to the active tool, clicks in the terminal going to the terminal.
 */
public class MainSceneMouseTrap extends MouseTrap {

    /** The editor whose panes, tool and terminal the mouse acts on. */
    public MainScene editor;

    /**
     * Bind the editor's mouse to its 2D camera.
     *
     * @param editor the editor the mouse acts on, also the owning canvas
     * @param camera camera to drive (pan, hover, zoom)
     */
    public MainSceneMouseTrap(MainScene editor, Camera camera) {
        super(camera, editor);
        this.editor = editor;
    }

    /**
     * The editor pane under a window point.
     *
     * @param x window-space x in pixels
     * @param y window-space y in pixels, top-down
     * @return the pane, from {@link MainScene#inView}
     */
    @Override
    public PaneTypes paneAt(float x, float y) {
        return MainScene.inView(x, y);
    }

    /**
     * Once knots are solved, a click in the knot view goes to the active tool and one in the
     * terminal to the terminal.
     *
     * @param pane the pane under the click
     */
    @Override
    public void paneClicked(PaneTypes pane) {
        if (editor.manifoldKnot != null && editor.active) {
            if (pane == PaneTypes.KnotView) {
                editor.tool.calculateClick(normalizedPosX, normalizedPosY);
            } else if (pane == PaneTypes.Terminal) {
                editor.terminal.calculateClick(normalizedPosX, normalizedPosY);
            }
        }
    }

    /**
     * The active tool hovers what is under the cursor in the knot view and drops its hover
     * anywhere else.
     *
     * @param pane the pane under the cursor
     */
    @Override
    public void paneHovered(PaneTypes pane) {
        if (editor.active) {
            if (pane == PaneTypes.KnotView) {
                editor.tool.calculateHover(normalizedPosX, normalizedPosY);
            } else {
                editor.tool.clearHover();
            }
        }
    }

    /**
     * Clear the active tool's hover so stale highlights don't linger once the cursor leaves.
     */
    @Override
    public void mouseExited() {
        editor.tool.clearHover();
    }
}
