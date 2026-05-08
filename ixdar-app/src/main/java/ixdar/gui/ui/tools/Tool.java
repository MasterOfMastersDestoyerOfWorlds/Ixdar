package ixdar.gui.ui.tools;

import java.util.ArrayList;

import org.apache.commons.math3.util.Pair;

import ixdar.geometry.knot.Knot;
import ixdar.geometry.knot.Segment;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.Clock;
import ixdar.graphics.render.text.HyperString;
import ixdar.platform.Platforms;
import ixdar.platform.Toggle;
import ixdar.scenes.main.MainScene;

public abstract class Tool {

    public Segment displaySegment;
    public Knot displayKP;
    public Knot displayCP;

    public Segment selectedSegment;
    public Knot selectedKP;
    public Knot selectedCP;
    public float ScreenOffsetY;
    public float ScreenOffsetX;

    Toggle[] disallowedToggles = new Toggle[] {};

    private HyperString toolInfoHyperString;

    /**
     * Render this tool's overlay (highlights, previews, gizmos) on top of the
     * scene. Subclasses must override; the base implementation is a guard.
     *
     * @param camera the scene camera used to project knot points to the screen
     * @param lineThickness base line thickness in pixels before camera scaling
     * @throws UnsupportedOperationException if a subclass forgets to override
     */
    public void draw(Camera2D camera, float lineThickness) {
        throw new UnsupportedOperationException("Unimplemented method 'draw'");
    }

    /**
     * Cycle the displayed knot/cut-point pair clockwise around the current
     * knot, advancing the segment under inspection.
     */
    public void cycleLeft() {
        ArrayList<Knot> knotsDisplayed = MainScene.knotsDisplayed;
        if (displaySegment == null) {
            displaySegment = MainScene.manifoldKnot.manifoldSegments.get(0);
            displayKP = displaySegment.first;
            displayCP = displaySegment.last;
        } else {
            for (Knot k : knotsDisplayed) {
                if (k.contains(displayKP)) {
                    Knot clockWise = k.getNextClockWise(displayKP);
                    if (clockWise.equals(displayCP)) {
                        clockWise = displayKP;
                        displayKP = displayCP;
                        displayCP = clockWise;
                        displaySegment = displayKP.getSegment(displayCP);
                    } else {
                        displayCP = clockWise;
                        displaySegment = displayKP.getSegment(displayCP);
                    }
                    break;
                }
            }
        }
        hoverChanged();
    }

    /**
     * Cycle the displayed knot/cut-point pair counter-clockwise around the
     * current knot, advancing the segment under inspection in the reverse
     * direction of {@link #cycleLeft()}.
     */
    public void cycleRight() {
        if (displaySegment == null) {
            displaySegment = MainScene.manifoldKnot.manifoldSegments.get(0);
            displayKP = displaySegment.first;
            displayCP = displaySegment.last;
        } else {
            ArrayList<Knot> knotsDisplayed = MainScene.knotsDisplayed;
            for (Knot k : knotsDisplayed) {
                if (k.contains(displayKP)) {
                    Knot clockWise = k.getNextCounterClockWise(displayKP);
                    if (clockWise.equals(displayCP)) {
                        clockWise = displayKP;
                        displayKP = displayCP;
                        displayCP = clockWise;
                        displaySegment = displayKP.getSegment(displayCP);
                    } else {
                        displayCP = clockWise;
                        displaySegment = displayKP.getSegment(displayCP);
                    }
                    break;
                }
            }
        }
        hoverChanged();
    }

    /**
     * Subclass hook returning the knot the tool considers "selected" for
     * downstream consumers. The base implementation returns {@code null}.
     *
     * @return the selected knot, or null if none
     */
    public Knot selectedKnot() {
        return null;
    }

    /**
     * Apply the tool's pending action (e.g. on Enter). Subclasses must
     * override; the base implementation is a guard.
     *
     * @throws UnsupportedOperationException if a subclass forgets to override
     */
    public void confirm() {
        throw new UnsupportedOperationException("Unimplemented method 'confirm'");
    }

    /**
     * Default click handler: latch the hovered segment / knot-point /
     * cut-point as both the selection and the display target.
     *
     * @param s  segment hit by the click, or null
     * @param kp the segment endpoint nearer the click (knot point), or null
     * @param cp the other endpoint of the segment (cut point), or null
     */
    public void click(Segment s, Knot kp, Knot cp) {
        selectedSegment = s;
        selectedKP = kp;
        selectedCP = cp;
        displaySegment = s;
        displayKP = kp;
        displayCP = cp;
    }

    /**
     * Clear all selection and display state and wipe any active terminal
     * instruction. Called on tool (de)activation.
     */
    public void reset() {
        selectedSegment = null;
        selectedKP = null;
        selectedCP = null;
        displaySegment = null;
        displayKP = null;
        displayCP = null;
        MainScene.terminal.clearInstruct();
    }

    /**
     * Switch the active scene tool back to the free/default tool and reset it.
     */
    public void freeTool() {
        MainScene.tool = MainScene.freeTool;
        MainScene.freeTool.reset();
    }

    /**
     * Drop the current hover by reverting display state to whatever is
     * currently selected.
     */
    public void clearHover() {
        displaySegment = selectedSegment;
        displayKP = selectedKP;
        displayCP = selectedCP;
    }

    /**
     * Set the hovered segment / knot-point / cut-point. Fires
     * {@link #hoverChanged()} when either endpoint actually differs from the
     * previous frame.
     *
     * @param s  the hovered segment, or null
     * @param kp the segment endpoint nearer the cursor, or null
     * @param cp the other endpoint of the segment, or null
     */
    public void setHover(Segment s, Knot kp, Knot cp) {
        boolean changed = (kp != null && !kp.equals(displayKP)) || (cp != null && !cp.equals(displayCP));
        displaySegment = s;
        displayKP = kp;
        displayCP = cp;
        if (changed) {
            hoverChanged();
        }
    }

    /**
     * Hook fired when the hovered point changes. Subclasses override to
     * recompute hover-dependent state (e.g. info text). No-op by default.
     */
    public void hoverChanged() {
    }

    /**
     * Test whether a toggle is permitted while this tool is active. Returns
     * false for any toggle in {@link #disallowedToggles}; otherwise returns
     * the toggle's current value.
     *
     * @param toggle the toggle to test
     * @return true if the toggle may apply, false if blocked or off
     */
    public boolean canUseToggle(Toggle toggle) {
        for (int i = 0; i < disallowedToggles.length; i++) {
            if (disallowedToggles[i].equals(toggle)) {
                return false;
            }
        }
        return toggle.value;
    }

    /**
     * Hit-test the displayed knots against the cursor position and update the
     * active tool's hover state to the closest segment (and the endpoint
     * nearer the cursor). Clears the hover when the cursor leaves the window.
     *
     * @param normalizedPosX cursor x in window pixels (pre screen-offset)
     * @param normalizedPosY cursor y in window pixels (pre screen-offset)
     */
    public void calculateHover(float normalizedPosX, float normalizedPosY) {

        Tool tool = MainScene.tool;
        float x = normalizedPosX - ScreenOffsetX;
        float y = normalizedPosY - ScreenOffsetY;
        if (x <= Platforms.get().getWindowWidth() && x >= 0
                && y <= Platforms.get().getWindowHeight() && y >= 0) {
            ArrayList<Knot> knotsDisplayed = MainScene.knotsDisplayed;
            Camera2D camera = MainScene.camera;
            if (knotsDisplayed != null) {
                camera.calculateCameraTransform(MainScene.retTup.ps);
                x = camera.screenTransformX(x);
                y = camera.screenTransformY(y);
                double minDist = Double.MAX_VALUE;
                Segment hoverSegment = null;
                for (Knot k : knotsDisplayed) {
                    for (Segment s : k.manifoldSegments) {
                        double result = s.boundContains(x, y);
                        if (result > 0) {
                            if (result < minDist) {
                                minDist = result;
                                hoverSegment = s;
                            }
                        }
                    }
                }
                if (hoverSegment != null) {
                    Knot closestPoint = hoverSegment.closestPoint(x, y);
                    if (closestPoint.equals(hoverSegment.first)) {
                        tool.setHover(hoverSegment, hoverSegment.first, hoverSegment.last);
                    } else {
                        tool.setHover(hoverSegment, hoverSegment.last, hoverSegment.first);
                    }
                } else {
                    tool.clearHover();
                }
            }
        } else {
            tool.clearHover();
        }
    }

    /**
     * Hit-test the displayed knots against a click position and forward the
     * resulting segment / knot-point / cut-point triple to the active tool's
     * {@link #click(Segment, Knot, Knot)}.
     *
     * @param normalizedPosX click x in window pixels (pre screen-offset)
     * @param normalizedPosY click y in window pixels (pre screen-offset)
     */
    public void calculateClick(float normalizedPosX, float normalizedPosY) {

        Tool tool = MainScene.tool;
        float x = normalizedPosX - ScreenOffsetX;
        float y = normalizedPosY - ScreenOffsetY;
        ArrayList<Knot> knotsDisplayed = MainScene.knotsDisplayed;
        Camera2D camera = MainScene.camera;
        if (knotsDisplayed != null) {
            camera.calculateCameraTransform(MainScene.retTup.ps);
            x = camera.screenTransformX(x);
            y = camera.screenTransformY(y);
            double minDist = Double.MAX_VALUE;
            Segment hoverSegment = null;
            for (Knot k : knotsDisplayed) {
                for (Segment s : k.manifoldSegments) {
                    double result = s.boundContains(x, y);
                    if (result > 0) {
                        if (result < minDist) {
                            minDist = result;
                            hoverSegment = s;
                        }
                    }
                }
            }
            Knot kp = null, cp = null;
            if (hoverSegment != null) {
                Knot closestPoint = hoverSegment.closestPoint(x, y);
                if (closestPoint.equals(hoverSegment.first)) {
                    kp = hoverSegment.first;
                    cp = hoverSegment.last;
                } else {
                    kp = hoverSegment.last;
                    cp = hoverSegment.first;
                }
            }
            tool.click(hoverSegment, kp, cp);
        }

    }

    /**
     * Build, for each manifold segment of {@code k}, the pair of ordered
     * segment ids (first&rarr;last and last&rarr;first). Used by drawing code
     * that needs to look up per-direction colors.
     *
     * @param k knot whose manifold segments to enumerate
     * @return one pair per manifold segment, in segment order
     */
    public static ArrayList<Pair<Long, Long>> lookupSegmentPairs(Knot k) {

        ArrayList<Pair<Long, Long>> idTransform = new ArrayList<>();
        for (int i = 0; i < k.manifoldSegments.size(); i++) {
            Segment s = k.manifoldSegments.get(i);
            long matchId = Segment.idTransformOrdered(s.first.id, s.last.id);
            long matchId2 = Segment.idTransformOrdered(s.last.id, s.first.id);
            idTransform.add(new Pair<Long, Long>(matchId, matchId2));
        }
        return idTransform;

    }

    /**
     * Categorize this tool. The default {@link Type#None} is overridden by
     * the free/default tool to return {@link Type#Free}.
     *
     * @return this tool's {@link Type}
     */
    public Type toolType() {
        return Type.None;
    }


    /**
     * Build the tool-specific info text (status, hover details, hints) shown
     * in the side panel. Subclasses must implement.
     *
     * @return a freshly constructed {@link HyperString} for display
     */
    public abstract HyperString buildInfoText();

    /**
     * Cached accessor for {@link #buildInfoText()}; the underlying text is
     * constructed lazily on first call.
     *
     * @return the cached info hyperstring
     */
    public HyperString info() {
        if(toolInfoHyperString == null){
            toolInfoHyperString = buildInfoText();
        }
        return toolInfoHyperString;
    }

    /**
     * Build the always-visible header info (FPS plus current tool display
     * name) shown above the tool-specific info panel.
     *
     * @return a freshly constructed {@link HyperString}
     */
    public HyperString toolGeneralInfo() {
        HyperString h = new HyperString();
        h.addWord("FPS:" + Clock.fps());
        h.newLine();
        h.addWord("Tool: " + this.displayName());
        h.wrap();
        return h;
    }

    /**
     * Back-navigation hook: if already on the free tool, deactivate the main
     * scene; otherwise revert to the free tool.
     */
    public void back() {
        if (MainScene.tool.toolType() == Tool.Type.Free) {
            MainScene.activate(false);
        }
        MainScene.tool.freeTool();
    }

    /**
     * Cache the active camera's screen offsets so subsequent hover/click
     * coordinate math can subtract them.
     *
     * @param camera the camera whose offsets to mirror onto this tool
     */
    public void setScreenOffset(Camera2D camera) {
        ScreenOffsetX = camera.ScreenOffsetX;
        ScreenOffsetY = camera.ScreenOffsetY;
    }

    /**
     * Step the displayed knot layer one level up (toward {@code totalLayers}),
     * clamped to the valid range and respecting the
     * {@link Toggle#CanSwitchLayer} / {@link Toggle#CanSwitchTopLayer} gates.
     */
    public void increaseViewLayer() {
        if (canUseToggle(Toggle.CanSwitchLayer)) {

            if (!canUseToggle(Toggle.CanSwitchTopLayer) && MainScene.knotDrawLayer == MainScene.totalLayers - 1) {
                return;
            }
            MainScene.knotDrawLayer++;
            if (MainScene.knotDrawLayer > MainScene.totalLayers) {
                MainScene.knotDrawLayer = MainScene.totalLayers;
            }
            if (MainScene.knotDrawLayer < 1) {
                MainScene.knotDrawLayer = 1;
            }
            MainScene.updateKnotsDisplayed();
        }
    }

    /**
     * Step the displayed knot layer one level down (toward layer 1), clamped
     * to the valid range and respecting {@link Toggle#CanSwitchLayer}. A
     * sentinel layer of -1 jumps straight to {@code totalLayers}.
     */
    public void decreaseViewLayer() {
        if (canUseToggle(Toggle.CanSwitchLayer)) {

            if (MainScene.knotDrawLayer == -1) {
                MainScene.knotDrawLayer = MainScene.totalLayers;
            } else {
                MainScene.knotDrawLayer--;
                if (MainScene.knotDrawLayer < 1) {
                    MainScene.knotDrawLayer = 1;
                }
            }
            MainScene.updateKnotsDisplayed();
        }
    }

    /**
     * Convenience alias for {@link #decreaseViewLayer()}.
     */
    public void cycleToolLayerPrev() {
        decreaseViewLayer();
    }

    /**
     * Convenience alias for {@link #increaseViewLayer()}.
     */
    public void cycleToolLayerNext() {
        increaseViewLayer();
    }

    /**
     * Human-readable name of the tool, e.g. shown in the side panel header.
     *
     * @return the display name (typically Title Case with spaces)
     */
    public abstract String displayName();

    /**
     * Long token for this tool, used by terminal commands like
     * {@code tool <fullName>} to switch tools.
     *
     * @return the lowercase, no-spaces full name
     */
    public abstract String fullName();

    /**
     * Short token for this tool, used as a terse alias by terminal commands.
     *
     * @return the lowercase short alias
     */
    public abstract String shortName();

    /**
     * Long-form description of what this tool does, shown in tool listings.
     *
     * @return a one-sentence description
     */
    public abstract String desc();

    public enum Type {
        Free, None
    };;;;

}
