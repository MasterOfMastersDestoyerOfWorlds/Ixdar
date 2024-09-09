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

public class EditManifoldTool extends Tool {

    public Manifold manifold;
    VirtualPoint lastPoint;

    public EditManifoldTool() {
        disallowedToggles = new ToggleType[] { ToggleType.DrawCutMatch, ToggleType.CanSwitchLayer };
    }

    @Override
    public void reset() {
        hover = null;
        hoverCP = null;
        hoverKP = null;
        manifold = null;
        ArrayList<Manifold> manifolds = Main.manifolds;
        manifold = manifolds.get(Main.manifoldIdx).copy();
        Main.metroDrawLayer = Main.shell.cutEngine.totalLayers;
        lastPoint = Main.shell.pointMap.get(manifold.cp1);
    }

    @Override
    public void draw(Graphics2D g2, Camera camera, int minLineThickness) {
        if (hover != null && !lastPoint.equals(hoverKP)) {
            long matchId = Segment.idTransform(lastPoint.id, hoverKP.id);
            Segment matchSeg = lastPoint.segmentLookup.get(matchId);
            long cutId = Segment.idTransform(hoverKP.id, hoverCP.id);
            Segment cutSeg = hoverKP.segmentLookup.get(cutId);
            Drawing.drawSingleCutMatch(Main.main, g2, manifold.cutMatchList, matchSeg, cutSeg,
                    manifold.manifoldKnot, Drawing.MIN_THICKNESS * 2, Main.retTup.ps, camera);
        }
        Drawing.drawCutMatch(Main.main, g2, manifold.cutMatchList, manifold.manifoldCutSegment1,
                manifold.manifoldCutSegment2, manifold.manifoldExSegment1, manifold.manifoldExSegment2,
                manifold.manifoldKnot, Drawing.MIN_THICKNESS * 2, Main.retTup.ps, camera);
    }

    @Override
    public void click(Segment s, VirtualPoint kp, VirtualPoint cp) {

    }
    
    @Override
    public String displayName() {
        return "Edit Cut Match";
    }
}
