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
     * TODO: document {@code draw}.
     *
     * @param camera TODO: describe
     * @param lineThickness TODO: describe
     * @throws UnsupportedOperationException TODO: describe
     */
    public void draw(Camera2D camera, float lineThickness) {
        throw new UnsupportedOperationException("Unimplemented method 'draw'");
    }

    /**
     * TODO: document {@code cycleLeft}.
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
     * TODO: document {@code cycleRight}.
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
     * TODO: document {@code selectedKnot}.
     *
     * @return TODO: describe
     */
    public Knot selectedKnot() {
        return null;
    }

    /**
     * TODO: document {@code confirm}.
     *
     * @throws UnsupportedOperationException TODO: describe
     */
    public void confirm() {
        throw new UnsupportedOperationException("Unimplemented method 'confirm'");
    }

    /**
     * TODO: document {@code click}.
     *
     * @param s TODO: describe
     * @param kp TODO: describe
     * @param cp TODO: describe
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
     * TODO: document {@code reset}.
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
     * TODO: document {@code freeTool}.
     */
    public void freeTool() {
        MainScene.tool = MainScene.freeTool;
        MainScene.freeTool.reset();
    }

    /**
     * TODO: document {@code clearHover}.
     */
    public void clearHover() {
        displaySegment = selectedSegment;
        displayKP = selectedKP;
        displayCP = selectedCP;
    }

    /**
     * TODO: document {@code setHover}.
     *
     * @param s TODO: describe
     * @param kp TODO: describe
     * @param cp TODO: describe
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
     * TODO: document {@code hoverChanged}.
     */
    public void hoverChanged() {
    }

    /**
     * TODO: document {@code canUseToggle}.
     *
     * @param toggle TODO: describe
     * @return TODO: describe
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
     * TODO: document {@code calculateHover}.
     *
     * @param normalizedPosX TODO: describe
     * @param normalizedPosY TODO: describe
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
     * TODO: document {@code calculateClick}.
     *
     * @param normalizedPosX TODO: describe
     * @param normalizedPosY TODO: describe
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
     * TODO: document {@code lookupSegmentPairs}.
     *
     * @param k TODO: describe
     * @return TODO: describe
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
     * TODO: document {@code toolType}.
     *
     * @return TODO: describe
     */
    public Type toolType() {
        return Type.None;
    }


    /**
     * TODO: document {@code buildInfoText}.
     *
     * @return TODO: describe
     */
    public abstract HyperString buildInfoText();

    /**
     * TODO: document {@code info}.
     *
     * @return TODO: describe
     */
    public HyperString info() {
        if(toolInfoHyperString == null){
            toolInfoHyperString = buildInfoText();
        }
        return toolInfoHyperString;
    }

    /**
     * TODO: document {@code toolGeneralInfo}.
     *
     * @return TODO: describe
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
     * TODO: document {@code back}.
     */
    public void back() {
        if (MainScene.tool.toolType() == Tool.Type.Free) {
            MainScene.activate(false);
        }
        MainScene.tool.freeTool();
    }

    /**
     * TODO: document {@code setScreenOffset}.
     *
     * @param camera TODO: describe
     */
    public void setScreenOffset(Camera2D camera) {
        ScreenOffsetX = camera.ScreenOffsetX;
        ScreenOffsetY = camera.ScreenOffsetY;
    }

    /**
     * TODO: document {@code increaseViewLayer}.
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
     * TODO: document {@code decreaseViewLayer}.
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
     * TODO: document {@code cycleToolLayerPrev}.
     */
    public void cycleToolLayerPrev() {
        decreaseViewLayer();
    }

    /**
     * TODO: document {@code cycleToolLayerNext}.
     */
    public void cycleToolLayerNext() {
        increaseViewLayer();
    }

    /**
     * TODO: document {@code displayName}.
     *
     * @return TODO: describe
     */
    public abstract String displayName();

    /**
     * TODO: document {@code fullName}.
     *
     * @return TODO: describe
     */
    public abstract String fullName();

    /**
     * TODO: document {@code shortName}.
     *
     * @return TODO: describe
     */
    public abstract String shortName();

    /**
     * TODO: document {@code desc}.
     *
     * @return TODO: describe
     */
    public abstract String desc();

    public enum Type {
        Free, None
    };;;;

}
