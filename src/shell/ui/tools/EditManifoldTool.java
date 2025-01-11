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

public class EditManifoldTool extends Tool {

    public Manifold manifold;
    VirtualPoint lastPoint;

    public EditManifoldTool() {
        disallowedToggles = new ToggleType[] { ToggleType.DrawCutMatch, ToggleType.CanSwitchLayer };
    }

    @Override
    public void reset() {
        super.reset();
        manifold = null;
        ArrayList<Manifold> manifolds = Main.manifolds;
        manifold = manifolds.get(Main.manifoldIdx).copy();
        Main.knotDrawLayer = Main.shell.cutEngine.totalLayers;
        lastPoint = Main.shell.pointMap.get(manifold.cp1);
    }

    @Override
    public void draw(Camera2D camera, float minLineThickness) {
        if (displaySegment != null && !lastPoint.equals(displayKP)) {
            long matchId = Segment.idTransform(lastPoint.id, displayKP.id);
            Segment matchSeg = lastPoint.segmentLookup.get(matchId);
            long cutId = Segment.idTransform(displayKP.id, displayCP.id);
            Segment cutSeg = displayKP.segmentLookup.get(cutId);
            Drawing.drawSingleCutMatch(Main.main, matchSeg, cutSeg, Drawing.MIN_THICKNESS, Main.retTup.ps,
                    camera);
        }
        Drawing.drawCutMatch(manifold.cutMatchList, manifold.manifoldCutSegment1,
                manifold.manifoldCutSegment2, manifold.manifoldExSegment1, manifold.manifoldExSegment2,
                manifold.manifoldKnot, Drawing.MIN_THICKNESS * 2, Main.retTup.ps, camera);
    }

    @Override
    public void click(Segment s, VirtualPoint kp, VirtualPoint cp) {

    }

    @Override
    public HyperString buildInfoText() {
        HyperString h = new HyperString();
        return h;
    }

    @Override
    public String displayName() {
        return "Edit Manifold";
    }

    @Override
    public String fullName() {
        return "editmanifold";
    }

    @Override
    public String shortName() {
        return "edm";
    }

    @Override
    public String desc() {
        return "A tool to edit the shortest hole moving path for a specified cut pair. Need to run find manifold before this tool";
    }
}
