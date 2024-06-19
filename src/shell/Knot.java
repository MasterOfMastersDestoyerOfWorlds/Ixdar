package shell;

import java.util.ArrayList;
import java.util.HashMap;

public class Knot extends VirtualPoint {

    static int knotmergecount = 0;
    static int knotflattencount = 0;
    static int runlistmergecount = 0;
    static int runmergecount = 0;
    public int size;
    public ArrayList<VirtualPoint> knotPoints; // [ vp1, vp2, ... vpm];
    public HashMap<Integer, VirtualPoint> pointToInternalKnot;

    // [[s1, ..., sn-1], [s1, ..., sn-1], ... m]; sorted and remove
    // vp1, vp2, ... vpm

    public Knot(ArrayList<VirtualPoint> knotPointsToAdd, Shell shell) {
        constructor(knotPointsToAdd, shell, true);
    }

    public Knot(ArrayList<VirtualPoint> knotPointsToAdd, Shell shell, boolean setMatches) {
        constructor(knotPointsToAdd, shell, setMatches);
    }

    public void constructor(ArrayList<VirtualPoint> knotPointsToAdd, Shell shell, boolean setMatches) {
        this.shell = shell;
        if (setMatches) {
            if (knotPointsToAdd.get(0).match2 == null
                    || knotPointsToAdd.get(knotPointsToAdd.size() - 1).match2 == null) {
                VirtualPoint vp1 = knotPointsToAdd.get(0);
                VirtualPoint vp2 = knotPointsToAdd.get(knotPointsToAdd.size() - 1);
                Segment s = vp1.getClosestSegment(vp2, vp1.s1);
                Point bp2 = (Point) s.getOtherKnot(vp1);
                Point bp1 = (Point) s.getOther(bp2);
                if (vp2.basePoint1 != null && vp2.isKnot && vp2.basePoint1.equals(bp2)) {
                    s = vp1.getClosestSegment(vp2, vp2.s1);
                    bp2 = (Point) s.getOtherKnot(vp1);
                    bp1 = (Point) s.getOther(bp2);
                }
                vp1.setMatch2(vp2, bp2, bp1, s);
                vp2.setMatch2(vp1, bp1, bp2, s);
            }
        }
        sortedSegments = new ArrayList<>();
        ArrayList<VirtualPoint> flattenRunPoints = RunListUtils.flattenRunPoints(knotPointsToAdd, true);
        if (setMatches) {
            RunListUtils.fixRunList(flattenRunPoints, flattenRunPoints.size());
        }
        this.knotPoints = flattenRunPoints;
        isKnot = true;
        isRun = false;
        this.topGroup = this;
        this.group = this;
        size = knotPoints.size();
        knotPointsFlattened = new ArrayList<VirtualPoint>();
        pointToInternalKnot = new HashMap<>();

        for (VirtualPoint vp : knotPoints) {
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

        this.externalVirtualPoints = new ArrayList<>();
        externalVirtualPoints.addAll(knotPointsFlattened);
        // store the segment lists of each point contained in the knot, recursive
        sortedSegments = new ArrayList<Segment>();
        for (VirtualPoint vp : knotPoints) {
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
            if (setMatches) {
                vp.group = this;
                vp.topGroup = this;
                for (VirtualPoint flat : vp.knotPointsFlattened) {
                    flat.topGroupVirtualPoint = vp;
                }
                vp.topGroupVirtualPoint = vp;
            }
        }
        if (setMatches) {
            for (VirtualPoint p : knotPointsFlattened) {
                p.topGroup = this;
            }
        }
        sortedSegments.sort(null);
        this.id = shell.pointMap.keySet().size();
        shell.pointMap.put(id, this);
        if (setMatches) {
            shell.unvisited.add(this);
        }
    }

    public Segment getSegment(VirtualPoint a, VirtualPoint b) {

        if (a.match1.equals(b)) {
            return a.s1;
        }
        if (a.match2.equals(b)) {
            return a.s2;
        }
        if (!a.isKnot && !b.isKnot) {
            Point ap = (Point) a;
            Point bp = (Point) b;
            return new Segment(bp, ap, shell.distanceMatrix.getDistance(ap.p, bp.p));
        }
        return null;
    }

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

    VirtualPoint getPrev(int idx) {
        return knotPoints.get(idx - 1 < 0 ? knotPoints.size() - 1 : idx - 1);
    }

    public VirtualPoint getPrev(VirtualPoint prev) {
        int idx = knotPointsFlattened.indexOf(prev);
        return knotPoints.get(idx - 1 < 0 ? knotPoints.size() - 1 : idx - 1);
    }

    VirtualPoint getNext(int idx) {
        return knotPoints.get(idx + 1 >= knotPoints.size() ? 0 : idx + 1);
    }
    public VirtualPoint getNext(VirtualPoint next) {
        int idx = knotPointsFlattened.indexOf(next);
        return knotPoints.get(idx + 1 >= knotPoints.size() ? 0 : idx + 1);
    }

    @Override
    public String toString() {
        String str = "Knot[";
        for (VirtualPoint vp : knotPoints) {
            str += vp + " ";
        }
        str.stripTrailing();
        str += "]";
        return str;
    }

    public String fullString() {
        return "" + this
                + " match1: " + (match1 == null ? " none " : "" + match1)
                + " match1endpoint: " + (match1endpoint == null ? " none " : "" + match1endpoint.id)
                + " basepoint1: " + (basePoint1 == null ? " none " : "" + basePoint1.id)
                + " match2: " + (match2 == null ? " none " : "" + match2)
                + " match2endpoint: " + (match2endpoint == null ? " none " : "" + match2endpoint.id)
                + " basepoint2: " + (basePoint2 == null ? " none " : "" + basePoint2.id);
    }

    public boolean contains(VirtualPoint vp) {
        if (this.equals(vp)) {
            return true;
        }
        if (knotPointsFlattened.contains(vp)) {
            return true;
        }
        return false;
    }

    public boolean hasSegment(Segment cut) {

        for (int a = 0; a < knotPoints.size(); a++) {

            VirtualPoint knotPoint1 = knotPoints.get(a);
            VirtualPoint knotPoint2 = knotPoints.get(a + 1 >= knotPoints.size() ? 0 : a + 1);
            if (cut.contains(knotPoint1) && cut.contains(knotPoint2)) {
                return true;
            }

        }
        return false;
    }

    public boolean overlaps(Knot minKnot) {
        for (VirtualPoint vp : minKnot.knotPoints) {
            if (this.contains(vp)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasPoint(int i) {
        for (VirtualPoint vp : knotPointsFlattened) {
            if (vp.id == i) {
                return true;
            }
        }
        return false;
    }

    public int size() {
        return knotPointsFlattened.size();
    }

    public Segment getOtherSegment(Segment implicitCut, VirtualPoint vp) {
        for (int a = 0; a < knotPoints.size(); a++) {

            VirtualPoint knotPoint1 = knotPoints.get(a);
            VirtualPoint knotPoint2 = knotPoints.get(a + 1 >= knotPoints.size() ? 0 : a + 1);
            boolean right = implicitCut.contains(knotPoint1);
            boolean left = implicitCut.contains(knotPoint2);
            boolean hasPoint = knotPoint1.equals(vp) || knotPoint2.equals(vp);
            if (right && !left && hasPoint) {
                return knotPoint1.getClosestSegment(knotPoint2, null);
            } else if (left && !right && hasPoint) {
                return knotPoint2.getClosestSegment(knotPoint1, null);
            }

        }
        return null;
    }

}
