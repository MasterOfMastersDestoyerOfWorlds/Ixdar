package shell.ui.tools;

import java.util.ArrayList;

import org.apache.commons.math3.util.Pair;

import shell.Toggle;
import shell.ToggleType;
import shell.cameras.Camera2D;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.render.Clock;
import shell.render.text.HyperString;
import shell.ui.IxdarWindow;
import shell.ui.main.Main;

public abstract class Tool {

    public Segment displaySegment;
    public VirtualPoint displayKP;
    public VirtualPoint displayCP;

    public Segment selectedSegment;
    public VirtualPoint selectedKP;
    public VirtualPoint selectedCP;

    public void draw(Camera2D camera, float lineThickness) {
        throw new UnsupportedOperationException("Unimplemented method 'draw'");
    };

    public void cycleLeft() {
        ArrayList<Knot> knotsDisplayed = Main.knotsDisplayed;
        if (displaySegment == null) {
            displaySegment = Main.manifoldKnot.manifoldSegments.get(0);
            displayKP = displaySegment.first;
            displayCP = displaySegment.last;
        } else {
            for (Knot k : knotsDisplayed) {
                if (k.contains(displayKP)) {
                    VirtualPoint clockWise = k.getNextClockWise(displayKP);
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

    public void cycleRight() {
        if (displaySegment == null) {
            displaySegment = Main.manifoldKnot.manifoldSegments.get(0);
            displayKP = displaySegment.first;
            displayCP = displaySegment.last;
        } else {
            ArrayList<Knot> knotsDisplayed = Main.knotsDisplayed;
            for (Knot k : knotsDisplayed) {
                if (k.contains(displayKP)) {
                    VirtualPoint clockWise = k.getNextCounterClockWise(displayKP);
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

    public Knot selectedKnot() {
        return null;
    }

    public void confirm() {
        throw new UnsupportedOperationException("Unimplemented method 'confirm'");
    };

    public void click(Segment s, VirtualPoint kp, VirtualPoint cp) {
        selectedSegment = s;
        selectedKP = kp;
        selectedCP = cp;
        displaySegment = s;
        displayKP = kp;
        displayCP = cp;
    }

    public void reset() {
        selectedSegment = null;
        selectedKP = null;
        selectedCP = null;
        displaySegment = null;
        displayKP = null;
        displayCP = null;
        Main.terminal.clearInstruct();
    }

    public void freeTool() {
        Main.tool = Main.freeTool;
        Main.freeTool.reset();
    }

    public void clearHover() {
        displaySegment = selectedSegment;
        displayKP = selectedKP;
        displayCP = selectedCP;
    }

    public void setHover(Segment s, VirtualPoint kp, VirtualPoint cp) {
        boolean changed = (kp != null && !kp.equals(displayKP)) || (cp != null && !cp.equals(displayCP));
        displaySegment = s;
        displayKP = kp;
        displayCP = cp;
        if (changed) {
            hoverChanged();
        }
    }

    public void hoverChanged() {
    }

    ToggleType[] disallowedToggles = new ToggleType[] {};
    public float ScreenOffsetY;
    public float ScreenOffsetX;

    public boolean canUseToggle(Toggle toggle) {
        for (int i = 0; i < disallowedToggles.length; i++) {
            if (disallowedToggles[i].equals(toggle.type)) {
                return false;
            }
        }
        return toggle.value;
    }

    public void calculateHover(float normalizedPosX, float normalizedPosY) {

        Tool tool = Main.tool;
        float x = normalizedPosX - ScreenOffsetX;
        float y = normalizedPosY - ScreenOffsetY;
        if (x <= IxdarWindow.getWidth() && x >= 0
                && y <= IxdarWindow.getHeight() && y >= 0) {
            ArrayList<Knot> knotsDisplayed = Main.knotsDisplayed;
            Camera2D camera = Main.camera;
            if (knotsDisplayed != null) {
                camera.calculateCameraTransform();
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
                    VirtualPoint closestPoint = hoverSegment.closestPoint(x, y);
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

    public void calculateClick(float normalizedPosX, float normalizedPosY) {

        Tool tool = Main.tool;
        float x = normalizedPosX - ScreenOffsetX;
        float y = normalizedPosY - ScreenOffsetY;
        ArrayList<Knot> knotsDisplayed = Main.knotsDisplayed;
        Camera2D camera = Main.camera;
        if (knotsDisplayed != null) {
            camera.calculateCameraTransform();
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
            VirtualPoint kp = null, cp = null;
            if (hoverSegment != null) {
                VirtualPoint closestPoint = hoverSegment.closestPoint(x, y);
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

    public static ArrayList<Pair<Long, Long>> lookupPairs(Knot k) {

        ArrayList<Pair<Long, Long>> idTransform = new ArrayList<>();
        for (int i = 0; i < k.manifoldSegments.size(); i++) {
            Segment s = k.manifoldSegments.get(i);
            long matchId = Segment.idTransformOrdered(s.first.id, s.last.id);
            long matchId2 = Segment.idTransformOrdered(s.last.id, s.first.id);
            idTransform.add(new Pair<Long, Long>(matchId, matchId2));
        }
        return idTransform;
    }

    public String displayName() {
        return "REEEEEEEE";
    }

    public Type toolType() {
        return Type.None;
    }

    public enum Type {
        Free, None
    }

    public abstract HyperString buildInfoText();

    public HyperString info() {
        return buildInfoText();
    };

    public HyperString toolGeneralInfo() {
        HyperString h = new HyperString();
        h.addWord("FPS:" + Clock.fps());
        h.newLine();
        h.addWord("Tool: " + this.displayName());
        h.wrap();
        return h;
    };

    public void setScreenOffset(Camera2D camera) {
        ScreenOffsetX = camera.ScreenOffsetX;
        ScreenOffsetY = camera.ScreenOffsetY;
    }

}
