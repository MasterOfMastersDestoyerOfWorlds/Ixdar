package shell.ui.tools;

import java.util.ArrayList;

import shell.ToggleType;
import shell.cameras.Camera2D;
import shell.file.Manifold;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.render.color.Color;
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
                    boolean foundManifold = false;
                    Manifold found = null;
                    for (int i = 0; i < manifolds.size(); i++) {
                        Manifold m = manifolds.get(i);
                        if (m.cp1 == firstSelectedCP.id
                                && m.kp1 == firstSelectedKP.id
                                && m.kp2 == displayKP.id
                                && m.cp2 == displayCP.id) {
                            Main.manifoldIdx = i;
                            foundManifold = true;
                            found = m;
                            break;
                        }
                        if (m.cp2 == firstSelectedCP.id
                                && m.kp2 == firstSelectedKP.id
                                && m.kp1 == displayKP.id
                                && m.cp1 == displayCP.id) {
                            Main.manifoldIdx = i;
                            foundManifold = true;
                            found = m;
                            break;
                        }
                    }
                    if (!foundManifold) {
                        Main.terminal.error("No Manifold Found");
                    } else {
                        Main.terminal.history.addLine(found.toFileString(), Color.GREEN);
                        Main.terminal.history.addLine("Delta: " + found.cutMatchList.delta, Color.BLUE_WHITE);
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
