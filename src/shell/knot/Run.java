package shell.knot;

import java.util.ArrayList;
import java.util.HashMap;

import shell.render.text.HyperString;
import shell.shell.Shell;
import shell.utils.RunListUtils;

public class Run extends VirtualPoint {
    public int size;
    public ArrayList<VirtualPoint> knotPoints; // [ vp1, vp2, ... vpm];
    public HashMap<Integer, VirtualPoint> pointToInternalKnot;
    public VirtualPoint endpoint1;
    public VirtualPoint endpoint2;
    // [[s1, ..., sn-1], [s1, ..., sn-1], ... m]; sorted and remove
    // vp1, vp2, ... vpm

    public Run(ArrayList<VirtualPoint> knotPoints, Shell shell) {
        this.shell = shell;
        // TODO: need to flatten all runs in the constructor
        sortedSegments = new ArrayList<>();

        ArrayList<VirtualPoint> flattenRunPoints = RunListUtils.flattenRunPoints(knotPoints, false);
        RunListUtils.fixRunList(flattenRunPoints, flattenRunPoints.size() - 1);
        if (flattenRunPoints.size() != knotPoints.size()) {
        }
        this.knotPoints = flattenRunPoints;
        this.endpoint1 = this.knotPoints.get(0);
        this.endpoint2 = this.knotPoints.get(this.knotPoints.size() - 1);
        isKnot = false;
        isRun = true;
        this.topGroup = this;
        size = this.knotPoints.size();
        this.externalVirtualPoints = new ArrayList<>();
        externalVirtualPoints.add(endpoint1);
        externalVirtualPoints.add(endpoint2);
        knotPointsFlattened = new ArrayList<VirtualPoint>();
        pointToInternalKnot = new HashMap<>();

        for (VirtualPoint vp : this.knotPoints) {
            if (vp.isKnot) {
                Knot knot = ((Knot) vp);
                for (VirtualPoint p : knot.knotPointsFlattened) {
                    knotPointsFlattened.add(p);
                    pointToInternalKnot.put(p.id, knot);
                }
            } else if (vp.isRun) {
                Run knot = ((Run) vp);
                for (VirtualPoint p : knot.knotPointsFlattened) {
                    knotPointsFlattened.add(p);
                    pointToInternalKnot.put(p.id, knot);
                }
            } else {
                pointToInternalKnot.put(vp.id, vp);
                knotPointsFlattened.add(vp);
            }
        }

        // store the segment lists of each point contained in the knot, recursive
        ArrayList<VirtualPoint> endpoints = new ArrayList<>();
        endpoints.add(endpoint2);
        endpoints.add(endpoint1);
        for (VirtualPoint vp : endpoints) {
            if (vp.isKnot) {
                ArrayList<Segment> vpExternal = vp.sortedSegments;
                for (Segment s : vpExternal) {
                    if (!(knotPointsFlattened.contains(s.first) && knotPointsFlattened.contains(s.last))) {
                        sortedSegments.add(s);
                    }
                }
            } else if (vp.isRun) {
                ArrayList<Segment> vpExternal = vp.sortedSegments;
                for (Segment s : vpExternal) {
                    if (!(knotPointsFlattened.contains(s.first) && knotPointsFlattened.contains(s.last))) {
                        sortedSegments.add(s);
                    }
                }
            } else {
                ArrayList<Segment> vpExternal = vp.sortedSegments;
                for (Segment s : vpExternal) {
                    if (!(knotPointsFlattened.contains(s.first) && knotPointsFlattened.contains(s.last))) {
                        sortedSegments.add(s);
                    }
                }
            }
            for (VirtualPoint flat : vp.knotPointsFlattened) {
                flat.topGroupVirtualPoint = vp;
            }
        }
        for (VirtualPoint vp : this.knotPoints) {
            vp.group = this;
            vp.topGroup = this;
        }
        for (VirtualPoint p : knotPointsFlattened) {
            p.topGroup = this;
        }
        sortedSegments.sort(null);
        shell.buff.add(sortedSegments);
        this.id = shell.pointMap.keySet().size();
        shell.pointMap.put(id, this);
        shell.unvisited.add(this);
    }

    @Override
    public Point getNearestBasePoint(VirtualPoint vp) {
        for (int i = 0; i < sortedSegments.size(); i++) {
            Segment s = sortedSegments.get(i);
            if (vp.isKnot) {
                Knot knot = (Knot) vp;
                VirtualPoint p = s.getKnotPoint(knot.knotPointsFlattened);
                if (p != null) {
                    return (Point) s.getOther(p);
                }
            } else {
                if (s.contains(vp)) {
                    return (Point) s.getOther(vp);
                }
            }
        }
        assert (false);
        return null;
    }

    @Override
    public String toString() {
        String str = "Run[";
        for (VirtualPoint vp : knotPoints) {
            str += vp + " ";
        }
        str.stripTrailing();
        str += "]";
        return str;
    }

    @Override
    public String fullString() {
        return "" + this
                + " match1: " + (match1 == null ? " none " : "" + match1)
                + " match1endpoint: " + (match1endpoint == null ? " none " : "" + match1endpoint.id)
                + " basepoint1: " + (basePoint1 == null ? " none " : "" + basePoint1.id)
                + " match2: " + (match2 == null ? " none " : "" + match2)
                + " match2endpoint: " + (match2endpoint == null ? " none " : "" + match2endpoint.id)
                + " basepoint2: " + (basePoint2 == null ? " none " : "" + basePoint2.id)
                + " endPoint1: " + (endpoint1 == null ? " none " : "" + endpoint1.id)
                + " endPoint2: " + (endpoint2 == null ? " none " : "" + endpoint2.id);
    }

    @Override
    public HyperString toHyperString() {
        HyperString h = new HyperString();
        h.addWord(this.toString());
        return h;
    }

    @Override
    public boolean contains(VirtualPoint vp) {
        if (this.equals(vp)) {
            return true;
        }
        if (knotPointsFlattened.contains(vp)) {
            return true;
        }
        return false;
    }
}