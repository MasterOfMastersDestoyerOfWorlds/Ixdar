package shell.ui.tools;

import org.apache.commons.math3.util.Pair;

import shell.Toggle;
import shell.cameras.Camera2D;
import shell.cuts.Manifold;
import shell.knot.Segment;
import shell.knot.Knot;
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
    public Knot firstSelectedKP;
    public Knot firstSelectedCP;

    public FindManifoldTool() {
        disallowedToggles = new Toggle[] { Toggle.DrawCutMatch, Toggle.CanSwitchLayer };
    }

    @Override
    public void reset() {
        super.reset();
        state = States.FindStart;
        firstSelectedSegment = null;
        firstSelectedKP = null;
        firstSelectedCP = null;
        Main.knotDrawLayer = Main.shell.cutEngine.totalLayers;
        Main.updateKnotsDisplayed();
        Main.terminal.instruct("Select the starting cut");
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
    public void click(Segment s, Knot kp, Knot cp) {
        confirm();
    }

    @Override
    public void confirm() {

        if (displaySegment != null) {
            if (state == FindManifoldTool.States.FindStart) {
                firstSelectedSegment = displaySegment;
                firstSelectedKP = displayKP;
                firstSelectedCP = displayCP;
                state = FindManifoldTool.States.FirstSelected;
                Main.terminal.instruct("Select the ending cut");
                clearHover();
            } else if (state == FindManifoldTool.States.FirstSelected) {
                if (!displaySegment.equals(firstSelectedSegment)) {
                    Pair<Manifold, Integer> p = Manifold.findManifold(firstSelectedCP, firstSelectedKP, displayCP,
                            displayKP, Main.manifolds);
                    Manifold m = p.getFirst();
                    if (m == null) {
                        Main.terminal.error("No Manifold Found");
                    } else {
                        Main.manifoldIdx = p.getSecond();
                        Main.terminal.history.addLine(m.toFileString(), Color.GREEN);
                        Main.terminal.history.addLine("Delta: " + m.cutMatchList.delta, Color.BLUE_WHITE);
                    }
                    Main.tool.freeTool();
                }
            }
        }
    }

    @Override
    public HyperString buildInfoText() {
        HyperString h = new HyperString();
        return h;
    }

    @Override
    public String displayName() {
        return "Find Manifold";
    }

    @Override
    public String fullName() {
        return "findmanifold";
    }

    @Override
    public String shortName() {
        return "fm";
    }

    @Override
    public String desc() {
        return "A tool to find a specific cut pair on the manifold and prep it for other manifold tools";
    }
}
