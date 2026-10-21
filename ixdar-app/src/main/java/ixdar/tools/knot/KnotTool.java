package ixdar.tools.knot;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.math3.util.Pair;

import ixdar.geometry.tsp.knot.Knot;
import ixdar.geometry.tsp.knot.Segment;
import ixdar.graphics.cameras.Camera2D;
import ixdar.gui.ui.text.HyperString;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.scenes.main.MainScene;
import ixdar.scenes.main.Toggle;
import ixdar.tools.ControlHint;
import ixdar.tools.Tool;

/**
 * Base of the knot editor's four knot tools: the segment and endpoints a tool shows and has
 * selected. It sits here, not on {@link MainScene}, because each tool resets it on activation and
 * moves it its own way.
 */
public abstract class KnotTool implements Tool {

    /** The editor whose knots, camera and terminal the tool works on. */
    public final MainScene editor;

    public Segment displaySegment;
    public Knot displayKP;
    public Knot displayCP;

    public Segment selectedSegment;
    public Knot selectedKP;
    public Knot selectedCP;

    /** Toggles the editor ignores while this tool is active. */
    public Toggle[] disallowedToggles = new Toggle[] {};

    /** What the left and right keys step through, for their control rows. */
    public String cycleKeysStep = "the shown segment around its knot";

    /** What the layer keys step through, for their control rows. */
    public String layerKeysStep = "knot layer";

    /** The info pane text, built on demand and dropped whenever the hover or selection changes. */
    public HyperString infoText;

    /** Point-space x of the last {@link #segmentAt} query. */
    public float pointX;

    /** Point-space y of the last {@link #segmentAt} query. */
    public float pointY;

    /**
     * Build a tool over {@code editor}'s knots.
     *
     * @param editor the editor the tool works on
     */
    public KnotTool(MainScene editor) {
        this.editor = editor;
    }

    /**
     * Start afresh: clear the selection and what is shown, and wipe any terminal instruction.
     */
    @Override
    public void activate() {
        selectedSegment = null;
        selectedKP = null;
        selectedCP = null;
        displaySegment = null;
        displayKP = null;
        displayCP = null;
        infoText = null;
        editor.terminal.clearInstruct();
    }

    /** Hand over to another tool; the next {@link #activate} starts afresh anyway. */
    @Override
    public void deactivate() {
    }

    /**
     * The knot keys: left and right cycle what is shown, held to repeat; the bracket and arrow
     * keys step the layer; Enter confirms. All but the cycle keys act only with the knot view
     * focused.
     *
     * @param controls the scene's control list
     */
    @Override
    public void addControls(List<ControlHint> controls) {
        controls.add(ControlHint.repeating(Keys.LEFT, "left", "cycle " + cycleKeysStep + " clockwise",
                this::cycleLeft));
        controls.add(ControlHint.repeating(Keys.RIGHT, "right", "cycle " + cycleKeysStep + " anticlockwise",
                this::cycleRight));
        controls.add(new ControlHint(Keys.RIGHT_BRACKET, "]", "next " + layerKeysStep,
                Toggle.IsMainFocused.gate(this::increaseViewLayer)));
        controls.add(new ControlHint(Keys.UP, "up", "next " + layerKeysStep,
                Toggle.IsMainFocused.gate(this::increaseViewLayer)));
        controls.add(new ControlHint(Keys.LEFT_BRACKET, "[", "previous " + layerKeysStep,
                Toggle.IsMainFocused.gate(this::decreaseViewLayer)));
        controls.add(new ControlHint(Keys.DOWN, "down", "previous " + layerKeysStep,
                Toggle.IsMainFocused.gate(this::decreaseViewLayer)));
        controls.add(new ControlHint(Keys.ENTER, "enter", "confirm", Toggle.IsMainFocused.gate(this::confirm)));
    }

    /**
     * Apply the tool's pending action, on Enter.
     */
    public abstract void confirm();

    /**
     * Cycle the displayed knot/cut-point pair clockwise around the current
     * knot, advancing the segment under inspection.
     */
    public void cycleLeft() {
        if (displaySegment == null) {
            displaySegment = editor.manifoldKnot.manifoldSegments.get(0);
            displayKP = displaySegment.first;
            displayCP = displaySegment.last;
        } else {
            for (Knot k : editor.knotsDisplayed) {
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
        infoText = null;
    }

    /**
     * Cycle the displayed knot/cut-point pair counter-clockwise around the
     * current knot, advancing the segment under inspection in the reverse
     * direction of {@link #cycleLeft()}.
     */
    public void cycleRight() {
        if (displaySegment == null) {
            displaySegment = editor.manifoldKnot.manifoldSegments.get(0);
            displayKP = displaySegment.first;
            displayCP = displaySegment.last;
        } else {
            for (Knot k : editor.knotsDisplayed) {
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
        infoText = null;
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
        infoText = null;
    }

    /**
     * Drop the current hover by reverting display state to whatever is
     * currently selected.
     */
    @Override
    public void clearHover() {
        if (displaySegment != selectedSegment || displayKP != selectedKP || displayCP != selectedCP) {
            infoText = null;
        }
        displaySegment = selectedSegment;
        displayKP = selectedKP;
        displayCP = selectedCP;
    }

    /**
     * Set the hovered segment / knot-point / cut-point, dropping the info text when either
     * endpoint differs from what was shown.
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
            infoText = null;
        }
    }

    /**
     * Whether the editor applies {@code toggle} while this tool is active.
     *
     * @param toggle the toggle to test
     * @return false for any toggle in {@link #disallowedToggles}, else the toggle's value
     */
    @Override
    public boolean canUseToggle(Toggle toggle) {
        for (int i = 0; i < disallowedToggles.length; i++) {
            if (disallowedToggles[i].equals(toggle)) {
                return false;
            }
        }
        return toggle.value;
    }

    /**
     * Hover the displayed segment nearest the cursor, with the endpoint nearer the cursor as the
     * knot point; nothing hit, or a cursor outside the window, drops the hover.
     *
     * @param x framebuffer x of the cursor
     * @param y framebuffer y of the cursor, y up
     */
    @Override
    public void onHover(float x, float y) {
        float viewX = x - editor.mainViewOffsetX;
        float viewY = y - editor.mainViewOffsetY;
        if (viewX > Platforms.get().getFrameBufferWidth() || viewX < 0
                || viewY > Platforms.get().getFrameBufferHeight() || viewY < 0) {
            clearHover();
            return;
        }
        Segment hoverSegment = segmentAt(viewX, viewY);
        if (hoverSegment == null) {
            clearHover();
        } else if (hoverSegment.closestPoint(pointX, pointY).equals(hoverSegment.first)) {
            setHover(hoverSegment, hoverSegment.first, hoverSegment.last);
        } else {
            setHover(hoverSegment, hoverSegment.last, hoverSegment.first);
        }
    }

    /**
     * Select the displayed segment nearest the click, or clear the selection when none is hit.
     *
     * @param x framebuffer x of the click
     * @param y framebuffer y of the click, y up
     * @return true
     */
    @Override
    public boolean onClick(float x, float y) {
        Segment hitSegment = segmentAt(x - editor.mainViewOffsetX, y - editor.mainViewOffsetY);
        Knot kp = null;
        Knot cp = null;
        if (hitSegment != null) {
            if (hitSegment.closestPoint(pointX, pointY).equals(hitSegment.first)) {
                kp = hitSegment.first;
                cp = hitSegment.last;
            } else {
                kp = hitSegment.last;
                cp = hitSegment.first;
            }
        }
        click(hitSegment, kp, cp);
        return true;
    }

    /**
     * The displayed segment whose hit bound holds a main-view point most tightly, leaving the
     * point in point space in {@link #pointX}, {@link #pointY}.
     *
     * @param viewX x within the editor's main view
     * @param viewY y within the editor's main view, y up
     * @return the segment, or null when no displayed segment's bound holds the point
     */
    public Segment segmentAt(float viewX, float viewY) {
        Camera2D camera = editor.editorCamera;
        camera.calculateCameraTransform(editor.retTup.ps);
        pointX = camera.screenTransformX(viewX);
        pointY = camera.screenTransformY(viewY);
        double minDist = Double.MAX_VALUE;
        Segment hitSegment = null;
        for (Knot k : editor.knotsDisplayed) {
            for (Segment s : k.manifoldSegments) {
                double result = s.boundContains(pointX, pointY);
                if (result > 0 && result < minDist) {
                    minDist = result;
                    hitSegment = s;
                }
            }
        }
        return hitSegment;
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
     * Build the tool-specific info text (status, hover details, hints) shown
     * in the side panel.
     *
     * @return a freshly constructed {@link HyperString} for display
     */
    public abstract HyperString buildInfoText();

    /**
     * The info pane text, rebuilt by {@link #buildInfoText} after the hover or selection changed.
     *
     * @return the current info text
     */
    @Override
    public HyperString info() {
        if (infoText == null) {
            infoText = buildInfoText();
        }
        return infoText;
    }

    /**
     * Step the displayed knot layer one level up (toward {@code totalLayers}),
     * clamped to the valid range and respecting the
     * {@link Toggle#CanSwitchLayer} / {@link Toggle#CanSwitchTopLayer} gates.
     */
    public void increaseViewLayer() {
        if (canUseToggle(Toggle.CanSwitchLayer)) {
            if (!canUseToggle(Toggle.CanSwitchTopLayer) && editor.knotDrawLayer == editor.totalLayers - 1) {
                return;
            }
            editor.knotDrawLayer++;
            if (editor.knotDrawLayer > editor.totalLayers) {
                editor.knotDrawLayer = editor.totalLayers;
            }
            if (editor.knotDrawLayer < 1) {
                editor.knotDrawLayer = 1;
            }
            editor.updateKnotsDisplayed();
            infoText = null;
        }
    }

    /**
     * Step the displayed knot layer one level down (toward layer 1), clamped
     * to the valid range and respecting {@link Toggle#CanSwitchLayer}. A
     * sentinel layer of -1 jumps straight to {@code totalLayers}.
     */
    public void decreaseViewLayer() {
        if (canUseToggle(Toggle.CanSwitchLayer)) {
            if (editor.knotDrawLayer == -1) {
                editor.knotDrawLayer = editor.totalLayers;
            } else {
                editor.knotDrawLayer--;
                if (editor.knotDrawLayer < 1) {
                    editor.knotDrawLayer = 1;
                }
            }
            editor.updateKnotsDisplayed();
            infoText = null;
        }
    }
}
