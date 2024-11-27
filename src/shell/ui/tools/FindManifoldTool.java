package shell.ui.tools;

import java.util.ArrayList;

import shell.ToggleType;
import shell.cameras.Camera2D;
import shell.file.Manifold;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.render.text.HyperString;
import shell.ui.Drawing;
import shell.ui.main.Main;

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
        displaySegment = null;
        displayCP = null;
        displayKP = null;
        firstSelectedSegment = null;
        firstSelectedKP = null;
        firstSelectedCP = null;
    }

    @Override
    public void draw(Camera2D camera, float minLineThickness) {
        if (displaySegment != null
                && !displaySegment.equals(firstSelectedSegment)) {
            Drawing.drawManifoldCut(displayKP, displayCP, camera,
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

        if (displaySegment != null) {
            if (state == FindManifoldTool.States.FindStart) {
                firstSelectedSegment = displaySegment;
                firstSelectedKP = displayKP;
                firstSelectedCP = displayCP;
                state = FindManifoldTool.States.FirstSelected;
                clearHover();
            } else if (state == FindManifoldTool.States.FirstSelected) {
                if (!displaySegment.equals(firstSelectedSegment)) {
                    for (int i = 0; i < manifolds.size(); i++) {
                        Manifold m = manifolds.get(i);
                        if (m.manifoldCutSegment1.equals(firstSelectedSegment)
                                && m.manifoldCutSegment2.equals(displaySegment)) {
                            Main.manifoldIdx = i;
                            break;
                        }
                        if (m.manifoldCutSegment2.equals(firstSelectedSegment)
                                && m.manifoldCutSegment1.equals(displaySegment)) {
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
    public HyperString buildInfoText() {
        HyperString h = new HyperString();
        return h;
    }
}
