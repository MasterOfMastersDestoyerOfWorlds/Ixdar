package shell.ui.tools;

import java.awt.Graphics2D;
import java.util.ArrayList;

import shell.Main;
import shell.ToggleType;
import shell.file.Manifold;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.ui.Camera;
import shell.ui.Drawing;

public class FindManifoldTool extends Tool {
    public enum States {
        FindStart,
        FirstSelected
    }

    public States state = States.FindStart;

    public Segment firstSelectedSegment;
    public VirtualPoint firstSelectedKP;
    public VirtualPoint firstSelectedCP;

    public FindManifoldTool() {
        disallowedToggles = new ToggleType[] { ToggleType.DrawCutMatch, ToggleType.CanSwitchLayer };
    }

    @Override
    public void reset() {
        state = States.FindStart;
        hover = null;
        hoverCP = null;
        hoverKP = null;
        firstSelectedSegment = null;
        firstSelectedKP = null;
        firstSelectedCP = null;
    }

    @Override
    public void draw(Graphics2D g2, Camera camera, int minLineThickness) {
        if (hover != null
                && !hover.equals(firstSelectedSegment)) {
            Drawing.drawManifoldCut(g2, hoverKP, hoverCP, camera,
                    minLineThickness * 2);
        }
        if (firstSelectedSegment != null) {
            Drawing.drawManifoldCut(g2, firstSelectedKP, firstSelectedCP,
                    camera,
                    minLineThickness * 2);
        }
    }

    @Override
    public void click(Segment s, VirtualPoint kp, VirtualPoint cp) {
        confirm();
    }

    @Override
    public void confirm() {
        ArrayList<Manifold> manifolds = Main.manifolds;

        if (hover != null) {
            if (state == FindManifoldTool.States.FindStart) {
                firstSelectedSegment = hover;
                firstSelectedKP = hoverKP;
                firstSelectedCP = hoverCP;
                state = FindManifoldTool.States.FirstSelected;
                clearHover();
            }
            else if (state == FindManifoldTool.States.FirstSelected) {
                if (!hover.equals(firstSelectedSegment)) {
                    for (int i = 0; i < manifolds.size(); i++) {
                        Manifold m = manifolds.get(i);
                        if (m.manifoldCutSegment1.equals(firstSelectedSegment)
                                && m.manifoldCutSegment2.equals(hover)) {
                            Main.manifoldIdx = i;
                            break;
                        }
                        if (m.manifoldCutSegment2.equals(firstSelectedSegment)
                                && m.manifoldCutSegment1.equals(hover)) {
                            Main.manifoldIdx = i;
                            break;
                        }
                    }
                    Main.tool = Main.freeTool;
                }
            }
        }
    }

    @Override
    public void leftArrow() {
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

    @Override
    public void rightArrow() {
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

    @Override
    public String displayName() {
        return "Find Manifold";
    }
}
