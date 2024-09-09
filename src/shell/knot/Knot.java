package shell.knot;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.HashMap;

import org.apache.commons.math3.geometry.euclidean.twod.Vector2D;

import shell.shell.Shell;
import shell.utils.RunListUtils;

public class Knot extends VirtualPoint {

    static int knotmergecount = 0;
    static int knotflattencount = 0;
    static int runlistmergecount = 0;
    static int runmergecount = 0;
    public int size;
    public ArrayList<VirtualPoint> knotPoints; // [ vp1, vp2, ... vpm];
    public HashMap<Integer, VirtualPoint> pointToInternalKnot;
    public ArrayList<Segment> manifoldSegments;
    public ArrayList<Long> manifoldSegmentIds;
    int height;
    public int numKnots;

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
        manifoldSegments = new ArrayList<>();
        manifoldSegmentIds = new ArrayList<>();
        if (knotPointsFlattened.size() == knotPoints.size()) {
            for (int a = 0; a < knotPoints.size(); a++) {
                VirtualPoint knotPoint1 = knotPoints.get(a);
                VirtualPoint knotPoint2 = knotPoints.get(a + 1 >= knotPoints.size() ? 0 : a + 1);
                Segment s = knotPoint1.getClosestSegment(knotPoint2, null);
                manifoldSegments.add(s);
                manifoldSegmentIds.add(s.id);
            }
        }
        height = 0;
        for (VirtualPoint vp : knotPoints) {
            int pHeight = vp.getHeight();
            if (pHeight > height) {
                height = pHeight;
            }
        }
        height++;
        numKnots = 0;
        for (VirtualPoint vp : knotPoints) {
            if (vp.isKnot) {
                Knot k = (Knot) vp;
                numKnots += k.numKnots;
            }
        }
        numKnots++;
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

    public VirtualPoint getPrev(int idx) {
        return knotPoints.get(idx - 1 < 0 ? knotPoints.size() - 1 : idx - 1);
    }

    public VirtualPoint getPrev(VirtualPoint prev) {
        int idx = knotPointsFlattened.indexOf(prev);
        return knotPoints.get(idx - 1 < 0 ? knotPoints.size() - 1 : idx - 1);
    }

    public VirtualPoint getNext(int idx) {
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
        if (manifoldSegments.size() == 0) {
            for (int a = 0; a < knotPoints.size(); a++) {

                VirtualPoint knotPoint1 = knotPoints.get(a);
                VirtualPoint knotPoint2 = knotPoints.get(a + 1 >= knotPoints.size() ? 0 : a + 1);
                if (cut.contains(knotPoint1) && cut.contains(knotPoint2)) {
                    return true;
                }

            }
        } else {
            return manifoldSegments.contains(cut);
        }
        return false;
    }

    public boolean hasSegmentManifold(Segment cut) {
        return manifoldSegmentIds.contains(cut.id);
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

    public double getLength() {
        double d = 0.0;
        for (Segment s : manifoldSegments) {
            d += s.distance;
        }
        return d;
    }

    public WindingOrder order = WindingOrder.None;

    public VirtualPoint getNextClockWise(VirtualPoint displayPoint) {
        if (order.equals(WindingOrder.None)) {
            order = DetermineWindingOrder();
        }
        if (order.equals(WindingOrder.Clockwise)) {
            return this.getPrev(displayPoint);
        } else {
            return this.getNext(displayPoint);
        }
    }

    public VirtualPoint getNextCounterClockWise(VirtualPoint displayPoint) {
        if (order.equals(WindingOrder.None)) {
            order = DetermineWindingOrder();
        }
        if (order.equals(WindingOrder.Clockwise)) {
            return this.getNext(displayPoint);
        } else {
            return this.getPrev(displayPoint);
        }
    }

    // https://en.wikipedia.org/wiki/Curve_orientation#Orientation_of_a_simple_polygon
    public WindingOrder DetermineWindingOrder() {
        int nVerts = knotPointsFlattened.size();
        // If vertices duplicates first as last to represent closed polygon,
        // skip last.
        Point2D lastV = ((Point) knotPointsFlattened.get(nVerts - 1)).p.toPoint2D();
        if (lastV.equals(((Point) knotPointsFlattened.get(0)).p.toPoint2D()))
            nVerts -= 1;
        int iMinVertex = FindCornerVertex();
        // Orientation matrix:
        // [ 1 xa ya ]
        // O = | 1 xb yb |
        // [ 1 xc yc ]
        Point2D a = ((Point) knotPointsFlattened.get(WrapAt(iMinVertex - 1, nVerts))).p.toPoint2D();
        Point2D b = ((Point) knotPointsFlattened.get(iMinVertex)).p.toPoint2D();
        Point2D c = ((Point) knotPointsFlattened.get(WrapAt(iMinVertex + 1, nVerts))).p.toPoint2D();
        // determinant(O) = (xb*yc + xa*yb + ya*xc) - (ya*xb + yb*xc + xa*yc)
        double detOrient = (b.getX() * c.getY() + a.getX() * b.getY() + a.getY() * c.getX())
                - (a.getY() * b.getX() + b.getY() * c.getX() + a.getX() * c.getY());

        // TBD: check for "==0", in which case is not defined?
        // Can that happen? Do we need to check other vertices / eliminate duplicate
        // vertices?
        WindingOrder result = detOrient > 0
                ? WindingOrder.Clockwise
                : WindingOrder.CounterClockwise;
        return result;
    }

    public enum WindingOrder {
        None,
        Clockwise,
        CounterClockwise
    }

    // Find vertex along one edge of bounding box.
    // In this case, we find smallest y; in case of tie also smallest x.
    private int FindCornerVertex() {
        int iMinVertex = -1;
        double minY = Float.MAX_VALUE;
        double minXAtMinY = Float.MAX_VALUE;
        for (int i = 0; i < knotPointsFlattened.size(); i++) {

            Point2D vert = ((Point) knotPointsFlattened.get(i)).p.toPoint2D();
            double y = (double) vert.getY();
            if (y > minY)
                continue;
            if (y == minY)
                if (vert.getX() >= minXAtMinY)
                    continue;

            // Minimum so far.
            iMinVertex = i;
            minY = y;
            minXAtMinY = vert.getX();
        }

        return iMinVertex;
    }

    // Return value in (0..n-1).
    // Works for i in (-n..+infinity).
    // If need to allow more negative values, need more complex formula.
    private static int WrapAt(int i, int n) {
        // "+n": Moves (-n..) up to (0..).
        return (i + n) % n;
    }

}
