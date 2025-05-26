
package shell.knot;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.HashMap;

import shell.Toggle;
import shell.exceptions.SegmentBalanceException;
import shell.point.PointND;
import shell.render.color.Color;
import shell.render.text.HyperString;
import shell.shell.Shell;
import shell.ui.actions.Action;
import shell.ui.main.Main;
import shell.ui.tools.Tool;

public class Knot {
    public int minMatches;
    public int maxMatches;
    public ArrayList<Knot> externalKnots;
    public ArrayList<Knot> knotPoints;
    public ArrayList<Knot> knotPointsFlattened;
    public ArrayList<Segment> sortedSegments;
    public HashMap<Long, Segment> segmentLookup;
    public Segment[] pointSegmentLookup;
    public int id;
    public Knot group;
    public Knot topGroup;
    public Knot topKnot;
    Knot topGroupKnot;
    Shell shell;
    public ArrayList<Segment> manifoldSegments;
    public ArrayList<Long> manifoldSegmentIds;
    int height = -1;
    public int numKnots;
    public HashMap<Integer, Knot> pointToInternalKnot;
    public PointND p;
    public ArrayList<Knot> matchList;
    public WindingOrder order = WindingOrder.None;
    public Segment s1;
    public Segment s2;
    public Knot m1;
    public Knot m2;

    public Knot(ArrayList<Knot> knotPointsToAdd, Shell shell) {
        constructor(knotPointsToAdd, shell, true);
    }

    public Knot(ArrayList<Knot> knotPointsToAdd, Shell shell, boolean setMatches) {
        constructor(knotPointsToAdd, shell, setMatches);
    }

    public Knot(PointND pnd, Shell shell) {
        this.p = pnd;
        this.shell = shell;
        sortedSegments = new ArrayList<>();
        this.shell = shell;
        this.id = p.getID();
        shell.knotEngine.unvisited.add(this);
        group = this;
        topGroup = this;
        topKnot = this;
        knotPointsFlattened = new ArrayList<Knot>();
        knotPointsFlattened.add(this);
        knotPoints = new ArrayList<>();
        knotPoints.add(this);
        sortedSegments = new ArrayList<Segment>();
        segmentLookup = new HashMap<>();
        pointSegmentLookup = new Segment[shell.size()];
        manifoldSegments = new ArrayList<>();
        minMatches = 2;
        maxMatches = 2;
        matchList = new ArrayList<>();
    }

    public void constructor(ArrayList<Knot> knotPointsToAdd, Shell shell, boolean setMatches) {
        knotPointsToAdd = new ArrayList<>(knotPointsToAdd);
        minMatches = 2;
        maxMatches = 2;
        matchList = new ArrayList<>();
        ArrayList<Knot> addList = new ArrayList<>();
        int size = knotPointsToAdd.size();
        for (int i = 0; i < knotPointsToAdd.size(); i++) {
            Knot vp = knotPointsToAdd.get(i);
            if (!vp.isSingleton() && ((Knot) vp).knotPoints.size() == 2) {
                Knot last = knotPointsToAdd.get(Math.floorMod(i - 1, size));
                Knot next = knotPointsToAdd.get(Math.floorMod(i + 1, size));
                Knot vp1 = ((Knot) vp).knotPoints.get(0);
                Knot vp2 = ((Knot) vp).knotPoints.get(1);
                if (!vp1.isSingleton() && !vp2.isSingleton()) {
                    Segment lastSeg = last.getClosestSegment(vp, null);
                    Knot lastKnotPoint = lastSeg.getOtherKnot(last);
                    Segment nextSeg = next.getClosestSegment(vp, lastSeg);
                    Knot nextKnotPoint = nextSeg.getOtherKnot(next);
                    addList.add(vp);
                } else {
                    addList.add(vp);
                }
            } else {
                addList.add(vp);
            }
        }
        this.shell = shell;
        sortedSegments = new ArrayList<>();
        this.knotPoints = addList;
        this.topGroup = this;
        this.topKnot = this;
        this.group = this;
        size = knotPoints.size();
        knotPointsFlattened = new ArrayList<Knot>();
        pointToInternalKnot = new HashMap<>();

        for (Knot vp : knotPoints) {
            if (!vp.isSingleton()) {
                Knot knot = ((Knot) vp);
                for (Knot p : knot.knotPointsFlattened) {
                    knotPointsFlattened.add(p);
                    pointToInternalKnot.put(p.id, knot);
                }
            } else {
                pointToInternalKnot.put(vp.id, vp);
                knotPointsFlattened.add(vp);
            }
        }

        this.externalKnots = new ArrayList<>();
        externalKnots.addAll(knotPointsFlattened);
        // store the segment lists of each point contained in the knot, recursive
        sortedSegments = new ArrayList<Segment>();
        for (Knot vp : knotPoints) {
            if (!vp.isSingleton()) {
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
                vp.topKnot = this;
                for (Knot flat : vp.knotPointsFlattened) {
                    flat.topGroupKnot = vp;
                }
                vp.topGroupKnot = vp;
            }
        }
        if (setMatches) {
            for (Knot p : knotPointsFlattened) {
                p.topGroup = this;
                p.topKnot = this;
            }
        }
        sortedSegments.sort(null);
        this.id = shell.pointMap.keySet().size();
        shell.pointMap.put(id, this);
        manifoldSegments = new ArrayList<>();
        manifoldSegmentIds = new ArrayList<>();
        if (knotPointsFlattened.size() == knotPoints.size()) {
            for (int a = 0; a < knotPoints.size(); a++) {
                Knot knotPoint1 = knotPoints.get(a);
                Knot knotPoint2 = knotPoints.get(a + 1 >= knotPoints.size() ? 0 : a + 1);
                Segment s = knotPoint1.getClosestSegment(knotPoint2, null);
                manifoldSegments.add(s);
                manifoldSegmentIds.add(s.id);
            }
        }
        height = 0;
        for (Knot vp : knotPoints) {
            int pHeight = vp.getHeight();
            if (pHeight > height) {
                height = pHeight;
            }
        }
        height++;
        numKnots = 0;
        for (Knot vp : knotPoints) {
            if (!vp.isSingleton()) {
                Knot k = (Knot) vp;
                numKnots += k.numKnots;
            }
        }
        assert (this.size() > 0);
        numKnots++;
    }

    public Segment getPointer(int idx) {
        int count = idx;
        ArrayList<Segment> seenGroups = new ArrayList<Segment>();
        ArrayList<Knot> seenPoints = new ArrayList<Knot>();
        for (int i = 0; i < sortedSegments.size(); i++) {
            Segment s = sortedSegments.get(i);
            Knot knotPoint = s.getKnotPoint(knotPointsFlattened);
            Knot basePoint = s.getOther(knotPoint);
            Knot vp = basePoint.group;
            if (vp.group != null) {
                vp = vp.group;
            }
            Segment potentialSegment = new Segment(basePoint, knotPoint, 0);
            if ((!seenGroups.contains(potentialSegment)) && (!seenPoints.contains(knotPoint))
                    && (!seenPoints.contains(basePoint))
                    || vp.matchList.contains(knotPoint)) {
                count--;
                if (count == 0) {
                    return s;
                }
                seenGroups.add(potentialSegment);
                if (!this.isSingleton()) {
                    seenPoints.add(knotPoint);
                }
                if (!vp.isSingleton()) {
                    seenPoints.add(basePoint);
                }
            }
        }
        return null;
    }

    public Knot getNearestBasePoint(Knot vp) {
        for (int i = 0; i < sortedSegments.size(); i++) {
            Segment s = sortedSegments.get(i);
            if (!vp.isSingleton()) {
                Knot knot = (Knot) vp;
                Knot p = s.getKnotPoint(knot.knotPointsFlattened);
                if (p != null) {
                    return s.getOther(p);
                }
            } else {
                if (s.contains(vp)) {
                    return s.getOther(vp);
                }
            }
        }
        assert (false);
        return null;
    }

    public Knot getPrev(int idx) {
        return knotPoints.get(idx - 1 < 0 ? knotPoints.size() - 1 : idx - 1);
    }

    public Knot getPrev(Knot prev) {
        int idx = knotPointsFlattened.indexOf(prev);
        return knotPoints.get(idx - 1 < 0 ? knotPoints.size() - 1 : idx - 1);
    }

    public Knot getNext(int idx) {
        return knotPoints.get(idx + 1 >= knotPoints.size() ? 0 : idx + 1);
    }

    public Knot getNext(Knot next) {
        int idx = knotPointsFlattened.indexOf(next);
        return knotPoints.get(idx + 1 >= knotPoints.size() ? 0 : idx + 1);
    }

    public Knot getOtherNeighbor(Knot vp, Knot neighbor) {
        int idx = knotPointsFlattened.indexOf(vp);
        Knot neighborNext = knotPoints.get(idx + 1 >= knotPoints.size() ? 0 : idx + 1);
        if (neighborNext.id == neighbor.id) {
            return knotPoints.get(idx - 1 < 0 ? knotPoints.size() - 1 : idx - 1);
        }
        return neighborNext;
    }

    public boolean isSingleton() {
        return this.size() == 1;
    }

    public int size() {
        return knotPointsFlattened.size();
    }

    public Segment getSegment(Knot a, Knot b) {

        if (a.matchList.contains(b)) {
            return a.getClosestSegment(b, null);
        }
        if (a.isSingleton() && b.isSingleton()) {
            Knot ap = a;
            Knot bp = b;
            return new Segment(bp, ap, shell.distanceMatrix.getDistance(ap.p, bp.p));
        }
        return null;
    }

    public Segment getClosestSegment(Knot vp, Segment excludeSegment) {
        Knot excludethis = excludeSegment == null ? null : excludeSegment.getKnotPoint(knotPointsFlattened);
        Knot excludeother = excludeSegment == null ? null : excludeSegment.getKnotPoint(vp.knotPointsFlattened);

        for (int i = 0; i < sortedSegments.size(); i++) {
            Segment s = sortedSegments.get(i);
            Knot knot = (Knot) vp;
            if (s.getKnotPoint(knot.knotPointsFlattened) != null
                    && (excludeSegment == null || ((vp.isSingleton() || !s.contains(excludeother))
                            && (this.isSingleton() || !s.contains(excludethis))))) {
                return s;
            }
        }

        @SuppressWarnings("unused")
        float zero = 1 / 0;
        return null;
    }

    public Segment getSegment(Knot vp) {
        long a = this.id;
        long b = vp.id;
        long id = a >= b ? a * a + a + b : b + a + b * b;
        Segment look = this.segmentLookup.get(id);
        return look;
    }

    public boolean contains(Knot vp) {
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

                Knot knotPoint1 = knotPoints.get(a);
                Knot knotPoint2 = knotPoints.get(a + 1 >= knotPoints.size() ? 0 : a + 1);
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
        for (Knot vp : minKnot.knotPoints) {
            if (this.contains(vp)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasPoint(int i) {
        for (Knot vp : knotPointsFlattened) {
            if (vp.id == i) {
                return true;
            }
        }
        return false;
    }

    public Segment getOtherSegment(Segment implicitCut, Knot vp) {
        for (int a = 0; a < knotPoints.size(); a++) {

            Knot knotPoint1 = knotPoints.get(a);
            Knot knotPoint2 = knotPoints.get(a + 1 >= knotPoints.size() ? 0 : a + 1);
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

    public int getHeight() {
        if (height == -1) {
            if (!this.isSingleton()) {
                Knot k = (Knot) this;
                int max = 1;
                for (Knot vp : k.knotPoints) {
                    if (!vp.isSingleton()) {
                        int h = vp.getHeight() + 1;
                        if (h > max) {
                            max = h;
                        }
                    }
                }
                height = max;
                return max;
            } else {
                height = 1;
                return 1;
            }
        }
        return height;
    }

    public void setMatch(Knot matchPoint, Segment s) {
        matchList.add(matchPoint);
        if (s1 == null) {
            m1 = matchPoint;
            s1 = s;
        } else {
            m2 = matchPoint;
            s2 = s;
        }
        if (m1 != null && m2 != null) {
            assert (m1.id != m2.id);
        }
    }

    public Knot getNextClockWise(Knot displayPoint) {
        if (order.equals(WindingOrder.None)) {
            order = DetermineWindingOrder();
        }
        if (order.equals(WindingOrder.Clockwise)) {
            return this.getPrev(displayPoint);
        } else {
            return this.getNext(displayPoint);
        }
    }

    public Knot getNextCounterClockWise(Knot displayPoint) {
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
        Point2D lastV = (knotPointsFlattened.get(nVerts - 1)).p.toPoint2D();
        if (lastV.equals((knotPointsFlattened.get(0)).p.toPoint2D()))
            nVerts -= 1;
        int iMinVertex = FindCornerVertex();
        // Orientation matrix:
        // [ 1 xa ya ]
        // O = | 1 xb yb |
        // [ 1 xc yc ]
        Point2D a = (knotPointsFlattened.get(WrapAt(iMinVertex - 1, nVerts))).p.toPoint2D();
        Point2D b = (knotPointsFlattened.get(iMinVertex)).p.toPoint2D();
        Point2D c = (knotPointsFlattened.get(WrapAt(iMinVertex + 1, nVerts))).p.toPoint2D();
        // determinant(O) = (xb*yc + xa*yb + ya*xc) - (ya*xb + yb*xc + xa*yc)
        double detOrient = (b.getX() * c.getY() + a.getX() * b.getY() + a.getY() * c.getX())
                - (a.getY() * b.getX() + b.getY() * c.getX() + a.getX() * c.getY());

        // TBD: check for "==0", in which case is not defined?
        // Can that happen? Do we need to check other vertices / eliminate duplicate
        // vertices?
        WindingOrder result = detOrient > 0 ? WindingOrder.Clockwise : WindingOrder.CounterClockwise;
        return result;
    }

    public enum WindingOrder {
        None, Clockwise, CounterClockwise
    }

    // Find vertex along one edge of bounding box.
    // In this case, we find smallest y; in case of tie also smallest x.
    private int FindCornerVertex() {
        int iMinVertex = -1;
        double minY = Float.MAX_VALUE;
        double minXAtMinY = Float.MAX_VALUE;
        for (int i = 0; i < knotPointsFlattened.size(); i++) {

            Point2D vert = (knotPointsFlattened.get(i)).p.toPoint2D();
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

    @Override
    public String toString() {
        if (this.isSingleton()) {
            return Integer.toString(id);
        }
        String str = "Knot[ ";
        for (Knot vp : knotPoints) {
            str += vp + " ";
        }
        str.stripTrailing();
        str += "]";
        return str;
    }

    public String beforeString(int id) {
        String str = "Knot[ ";
        for (Knot vp : knotPoints) {
            if (vp.id == id) {
                return str;
            }
            str += vp + " ";
        }
        str.stripTrailing();
        str += "]";
        return str;
    }

    public String afterString(int id) {
        String str = "Knot[";
        for (Knot vp : knotPoints) {
            str += vp + " ";
            if (vp.id == id) {
                str = "";
            }
        }
        str.stripTrailing();
        str += "]";
        return str;
    }

    public HyperString toHyperString() {
        HyperString h = new HyperString();
        Tool tool = Main.tool;
        Color c = Main.stickyColor;
        if (tool.canUseToggle(Toggle.DrawKnotGradient)) {
            c = Main.getKnotGradientColorFlatten((Knot) this);
        } else if (tool.canUseToggle(Toggle.DrawMetroDiagram)) {
            c = Main.getMetroColorFlatten((Knot) this);
        }
        Action clickAction = () -> {
            Main.setDrawLevelToKnot(this);
            Main.camera.zoomToKnot(this);
        };
        Knot hoverKnot = Main.getKnotFlatten(this);
        h.addHoverKnot("Knot[ ", c, hoverKnot, clickAction);
        for (Knot vp : knotPoints) {
            if (!vp.isSingleton()) {
                h.addHyperString(((Knot) vp).toHyperString());
            } else {
                h.addHoverKnot(vp + " ", c, hoverKnot, clickAction);
            }
        }

        h.addHoverKnot("]", c, hoverKnot, clickAction);
        return h;
    }

    public boolean isFull() {
        return matchList.size() == maxMatches;
    }

    public ArrayList<Knot> getRunList(Knot k2) {
        Knot next = this.m1;
        Knot curr = this;
        ArrayList<Knot> runList = new ArrayList<>();
        if (next == null) {
            float z = 0;
        }
        while (curr.id != k2.id) {
            runList.add(curr);
            Knot nextTemp = null;
            if (next.m1.id == curr.id) {
                nextTemp = next.m2;
            } else {
                nextTemp = next.m1;
            }
            curr = next;
            next = nextTemp;
        }
        runList.add(curr);
        return runList;
    }

    public ArrayList<Knot> getRunList() {
        Knot next = this.m2;
        Knot curr = this;
        ArrayList<Knot> runList = new ArrayList<>();
        if (next == null) {
            float z = 0;
        }
        while (next.id != this.id) {
            runList.add(curr);
            Knot nextTemp = null;
            if (next.m1.id == curr.id) {
                nextTemp = next.m2;
            } else {
                nextTemp = next.m1;
            }
            curr = next;
            next = nextTemp;
        }
        runList.add(curr);
        return runList;
    }
}
