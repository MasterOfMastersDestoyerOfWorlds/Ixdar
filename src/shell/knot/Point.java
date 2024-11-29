package shell.knot;

import java.util.ArrayList;
import java.util.HashMap;

import shell.objects.PointND;
import shell.render.text.HyperString;
import shell.shell.Shell;

public class Point extends VirtualPoint {
    public PointND p;

    public Point(PointND p, Shell shell) {
        this.shell = shell;
        this.p = p;
        this.id = p.getID();
        shell.unvisited.add(this);
        isKnot = false;
        isRun = false;
        group = this;
        topGroup = this;
        topGroupVirtualPoint = this;
        this.externalVirtualPoints = new ArrayList<>();
        externalVirtualPoints.add(this);
        knotPointsFlattened = new ArrayList<VirtualPoint>();
        knotPointsFlattened.add(this);
        sortedSegments = new ArrayList<Segment>();
        segmentLookup = new HashMap<>();
    }

    @Override
    public Point getNearestBasePoint(VirtualPoint vp) {
        assert (basePoint1 != null);
        return basePoint1;
    }

    @Override
    public int getHeight() {
        return 0;
    }

    @Override
    public String toString() {
        return "" + this.p.getID();
    }

    @Override
    public String fullString() {
        return "" + this.p.getID()
                + " match1: " + (match1 == null ? " none " : "" + match1)
                + " match1endpoint: " + (match1endpoint == null ? " none " : "" + match1endpoint.id)
                + " basepoint1: " + (basePoint1 == null ? " none " : "" + basePoint1.id)
                + " match2: " + (match2 == null ? " none " : "" + match2)
                + " match2endpoint: " + (match2endpoint == null ? " none " : "" + match2endpoint.id)
                + " basepoint2: " + (basePoint2 == null ? " none " : "" + basePoint2.id);
    }

    @Override
    public boolean contains(VirtualPoint vp) {
        if (this.equals(vp)) {
            return true;
        }
        return false;
    }

    @Override
    public HyperString toHyperString() {
        HyperString h = new HyperString();
        h.addWord(this.toString());
        return h;
    }
}