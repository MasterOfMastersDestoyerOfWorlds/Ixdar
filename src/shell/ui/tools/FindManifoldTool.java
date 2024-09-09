package shell.ui.tools;

import java.awt.Graphics2D;
import java.util.ArrayList;

import shell.Main;
import shell.ToggleType;
import shell.file.Manifold;
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
        ArrayList<Manifold> manifolds = Main.manifolds;

        if (s != null) {
            if (state == FindManifoldTool.States.FindStart) {
                firstSelectedSegment = s;
                firstSelectedKP = kp;
                firstSelectedCP = cp;
                state = FindManifoldTool.States.FirstSelected;
                clearHover();
            }
            if (state == FindManifoldTool.States.FirstSelected) {
                if (!s.equals(firstSelectedSegment)) {
                    for (int i = 0; i < manifolds.size(); i++) {
                        Manifold m = manifolds.get(i);
                        if (m.manifoldCutSegment1.equals(firstSelectedSegment)
                                && m.manifoldCutSegment2.equals(s)) {
                            Main.manifoldIdx = i;
                            break;
                        }
                        if (m.manifoldCutSegment2.equals(firstSelectedSegment)
                                && m.manifoldCutSegment1.equals(s)) {
                            Main.manifoldIdx = i;
                            break;
                        }
                    }
                    reset();
                }
            }
        }

    }

    @Override
    public String displayName() {
        return "Find Manifold";
    }
}
