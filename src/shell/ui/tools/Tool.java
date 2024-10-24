package shell.ui.tools;

import java.util.ArrayList;

import shell.Toggle;
import shell.ToggleType;
import shell.cameras.Camera2D;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.render.text.HyperString;
import shell.ui.IxdarWindow;
import shell.ui.main.Main;

public abstract class Tool {

    public Segment hover;
    public VirtualPoint hoverKP;
    public VirtualPoint hoverCP;

    public void draw(Camera2D camera, int lineThickness) {
        throw new UnsupportedOperationException("Unimplemented method 'draw'");
    };

    public void click(Segment s, VirtualPoint kp, VirtualPoint cp) {
        throw new UnsupportedOperationException("Unimplemented method 'click'");
    };

    public void cycleLeft() {
        ArrayList<Knot> knotsDisplayed = Main.knotsDisplayed;
        if (hover == null) {
            hover = Main.manifoldKnot.manifoldSegments.get(0);
            hoverKP = hover.first;
            hoverCP = hover.last;
        } else {
            for (Knot k : knotsDisplayed) {
                if (k.contains(hoverKP)) {
                    VirtualPoint clockWise = k.getNextClockWise(hoverKP);
                    if (clockWise.equals(hoverCP)) {
                        clockWise = hoverKP;
                        hoverKP = hoverCP;
                        hoverCP = clockWise;
                        hover = hoverKP.getSegment(hoverCP);
                    } else {
                        hoverCP = clockWise;
                        hover = hoverKP.getSegment(hoverCP);
                    }
                    return;
                }
            }
        }
    }

    public void cycleRight() {
        if (hover == null) {
            hover = Main.manifoldKnot.manifoldSegments.get(0);
            hoverKP = hover.first;
            hoverCP = hover.last;
        } else {
            ArrayList<Knot> knotsDisplayed = Main.knotsDisplayed;
            for (Knot k : knotsDisplayed) {
                if (k.contains(hoverKP)) {
                    VirtualPoint clockWise = k.getNextCounterClockWise(hoverKP);
                    if (clockWise.equals(hoverCP)) {
                        clockWise = hoverKP;
                        hoverKP = hoverCP;
                        hoverCP = clockWise;
                        hover = hoverKP.getSegment(hoverCP);
                    } else {
                        hoverCP = clockWise;
                        hover = hoverKP.getSegment(hoverCP);
                    }
                    return;
                }
            }
        }
    }

    public Knot selectedKnot() {
        return null;
    }

    public void confirm() {
        throw new UnsupportedOperationException("Unimplemented method 'confirm'");
    };

    public void reset() {
        hover = null;
        hoverCP = null;
        hoverKP = null;
    }

    public void clearHover() {
        hover = null;
        hoverCP = null;
        hoverKP = null;
    }

    public void setHover(Segment s, VirtualPoint kp, VirtualPoint cp) {
        hover = s;
        hoverKP = kp;
        hoverCP = cp;
    }

    ToggleType[] disallowedToggles = new ToggleType[] {};
    public float ScreenOffsetY;
    public float ScreenOffsetX;
    public float scrollOffsetY;
    public float SCROLL_SPEED = 20f;

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

    private HyperString cachedInfo;

    public HyperString info() {
        cachedInfo = buildInfoText();
        return cachedInfo;
    };

    public void setScreenOffset(Camera2D camera) {
        ScreenOffsetX = camera.ScreenOffsetX;
        ScreenOffsetY = camera.ScreenOffsetY;
    }

    public void scrollInfoPanel(boolean scrollUp) {

        float menuBottom = cachedInfo.getLastWord().yScreenOffset;

        if (scrollUp) {
            scrollOffsetY -= SCROLL_SPEED;
            if (scrollOffsetY < 0) {
                scrollOffsetY = 0;
            }
        } else if (menuBottom < Main.MAIN_VIEW_OFFSET_Y) {
            scrollOffsetY += SCROLL_SPEED;
        }
    }

}
