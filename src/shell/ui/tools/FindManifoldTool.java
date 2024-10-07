package shell.ui.tools;

import java.util.ArrayList;

import shell.Main;
import shell.ToggleType;
import shell.cameras.Camera2D;
import shell.file.Manifold;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.render.text.HyperString;
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
    public void draw(Camera2D camera, int minLineThickness) {
        if (hover != null
                && !hover.equals(firstSelectedSegment)) {
            Drawing.drawManifoldCut(hoverKP, hoverCP, camera,
                    minLineThickness * 2);
        }
        if (firstSelectedSegment != null) {
            Drawing.drawManifoldCut(firstSelectedKP, firstSelectedCP,
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
            } else if (state == FindManifoldTool.States.FirstSelected) {
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
    public String displayName() {
        return "Find Manifold";
    }

    @Override
    public HyperString info() {
        HyperString h = new HyperString();
        return h;
    }
}
