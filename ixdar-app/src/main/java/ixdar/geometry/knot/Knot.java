
package ixdar.geometry.knot;

import java.util.ArrayList;
import java.util.HashMap;

import ixdar.common.exceptions.MultipleCyclesFoundException;
import ixdar.common.exceptions.SegmentBalanceException;
import ixdar.common.utils.Compat;
import ixdar.geometry.cuts.CutMatch;
import ixdar.geometry.cuts.DisjointUnionSets;
import ixdar.geometry.point.Point2D;
import ixdar.geometry.point.PointND;
import ixdar.geometry.shell.Shell;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.sdf.SDFCircle;
import ixdar.graphics.render.text.HyperString;
import ixdar.gui.ui.actions.Action;
import ixdar.gui.ui.tools.Tool;
import ixdar.platform.Toggle;
import ixdar.scenes.main.MainScene;

public class Knot extends SDFCircle {
    public int minMatches;
    public int maxMatches;
    public int matchCount;
    public ArrayList<Knot> externalKnots;
    public ArrayList<Knot> knotPoints;
    public ArrayList<Knot> knotPointsFlattened;
    public ArrayList<Segment> sortedSegments;
    public HashMap<Long, Segment> segmentLookup;
    public int id;

    Knot topGroupKnot;
    Shell shell;
    public ArrayList<Segment> manifoldSegments;
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
    public static DisjointUnionSets unionSet = new DisjointUnionSets();

    public void setMatch(Knot matchPoint, Segment s) {
        matchList.add(matchPoint);
        if (s1 == null) {
            m1 = matchPoint;
            s1 = s;
        } else {
            m2 = matchPoint;
            s2 = s;
        }
        matchCount++;
    }

    private void removeMatch(Knot other) {
        if (other.equals(m1)) {
            m1 = null;
            s1 = null;
        } else if (other.equals(m2)) {
            m2 = null;
            s2 = null;
        }
        matchCount--;
        if (matchCount < 0) {
            matchCount = 0;
        }
    }

    public CutMatch getDeltaDistTo(Knot o) {
        boolean isSingle = this.isSingleton();
        boolean oSingle = o.isSingleton();
        CutMatch cm = null;
        if (isSingle && oSingle) {
            cm = new CutMatch("Points", shell, new SegmentBalanceException());
            Segment s = o.getClosestSegment(this, null);
            cm.matchSegments.add(s);
            cm.matchSegments.add(s);
        } else if (isSingle || oSingle) {
            Knot p = isSingle ? this : o;
            Knot k = isSingle ? o : this;
            cm = new CutMatch("PointToKnot", shell, new SegmentBalanceException());
            double minDelta = Double.MAX_VALUE;
            Segment cutSegment = null;
            for (Segment manifoldSegment : k.manifoldSegments) {
                double delta = manifoldSegment.first.getSegment(p).distance
                        + manifoldSegment.last.getSegment(p).distance - manifoldSegment.distance;
                if (delta < minDelta) {
                    minDelta = delta;
                    cutSegment = manifoldSegment;
                }
            }
            cm.matchSegments.add(cutSegment.first.getSegment(p));
            cm.matchSegments.add(cutSegment.last.getSegment(p));
            cm.cutSegments.add(cutSegment);
        } else {
            Knot k1 = o;
            Knot k2 = this;
            cm = new CutMatch("KnotToKnotPipe", shell, new SegmentBalanceException());
            double minDelta = Double.MAX_VALUE;
            Segment cutSegment1 = null;
            Segment cutSegment2 = null;
            Segment matchSegment1 = null;
            Segment matchSegment2 = null;
            for (Segment manifoldSegment1 : k1.manifoldSegments) {
                for (Segment manifoldSegment2 : k2.manifoldSegments) {
                    Segment pipe1 = manifoldSegment1.first.getSegment(manifoldSegment2.first);
                    Segment pipe2 = manifoldSegment1.last.getSegment(manifoldSegment2.last);
                    double delta = pipe1.distance + pipe2.distance - manifoldSegment1.distance
                            - manifoldSegment2.distance;
                    if (delta < minDelta) {
                        minDelta = delta;
                        matchSegment1 = pipe1;
                        matchSegment2 = pipe2;
                        cutSegment1 = manifoldSegment1;
                        cutSegment2 = manifoldSegment2;
                    }
                    pipe1 = manifoldSegment1.last.getSegment(manifoldSegment2.first);
                    pipe2 = manifoldSegment1.first.getSegment(manifoldSegment2.last);
                    delta = pipe1.distance + pipe2.distance - manifoldSegment1.distance - manifoldSegment2.distance;
                    if (delta < minDelta) {
                        minDelta = delta;
                        matchSegment1 = pipe1;
                        matchSegment2 = pipe2;
                        cutSegment1 = manifoldSegment1;
                        cutSegment2 = manifoldSegment2;
                    }
                }
            }
            if (matchSegment1 == null || matchSegment2 == null || cutSegment1 == null || cutSegment2 == null) {
                return null;
            }
            cm.matchSegments.add(matchSegment1);
            cm.matchSegments.add(matchSegment2);
            cm.cutSegments.add(cutSegment1);
            cm.cutSegments.add(cutSegment2);
        }
        cm.updateDelta();
        return cm;
    }

    public Knot(PointND pnd, Shell shell) {
        this.p = pnd;
        simpleConstructor(shell, p.getID());
        shell.knotEngine.unvisited.add(this);
        knotPointsFlattened.add(this);
        knotPoints.add(this);
        minMatches = 2;
        maxMatches = 2;
        matchCount = 0;
        unionSet.addSet(this);
    }

    public Knot(CutMatch smallestMove, Knot k1, Knot k2) throws MultipleCyclesFoundException {
        simpleConstructor(k1.shell, k1.shell.pointMap.keySet().size());
        minMatches = 2;
        maxMatches = 2;
        knotPoints.add(k1);
        knotPoints.add(k2);
        unionSet.addSet(this);
        unionSet.union(this, k1);
        unionSet.union(this, k2);
        for (Segment cut : smallestMove.cutSegments) {
            cut.first.removeMatch(cut.last);
            cut.last.removeMatch(cut.first);
        }

        for (Segment match : smallestMove.matchSegments) {
            match.first.setMatch(match.last, match);
            match.last.setMatch(match.first, match);
            sortedSegments.add(match);
        }
        sortedSegments.sort(null);

        Knot vp = k1.knotPointsFlattened.get(0);
        fixKnotPointsFlattened(vp);
        createManifold();
    }

    private void fixKnotPointsFlattened(Knot vp) throws MultipleCyclesFoundException {
        int expectedFlattenedKnotPoints = 0;
        for (Knot k : knotPoints) {
            expectedFlattenedKnotPoints += k.knotPointsFlattened.size();
        }
        Knot addPoint = vp;
        Knot prevPoint = addPoint.m1;
        for (int j = 0; j < expectedFlattenedKnotPoints; j++) {
            if (addPoint == null) {
                throw new MultipleCyclesFoundException(new SegmentBalanceException());
            }
            knotPointsFlattened.add(addPoint);
            if (prevPoint.equals(addPoint.m2)) {
                prevPoint = addPoint;
                addPoint = addPoint.m1;
            } else {
                prevPoint = addPoint;
                addPoint = addPoint.m2;
            }
        }
    }

    private void createManifold() throws MultipleCyclesFoundException {
        Knot addPoint = knotPointsFlattened.get(0);
        Knot prevPoint = addPoint.m1;
        for (int j = 0; j < knotPointsFlattened.size(); j++) {
            if (addPoint == null) {
                throw new MultipleCyclesFoundException(new SegmentBalanceException());
            }
            if (prevPoint.equals(addPoint.m2)) {
                prevPoint = addPoint;
                manifoldSegments.add(addPoint.s1);
                addPoint = addPoint.m1;
            } else {
                prevPoint = addPoint;
                manifoldSegments.add(addPoint.s2);
                addPoint = addPoint.m2;
            }
        }
    }

    public void growByPoint(CutMatch smallestMove, Knot p) throws MultipleCyclesFoundException {
        knotPoints.add(p);
        unionSet.union(this, p);
        for (Segment cut : smallestMove.cutSegments) {
            cut.first.removeMatch(cut.last);
            cut.last.removeMatch(cut.first);
            sortedSegments.remove(cut);
            manifoldSegments.remove(cut);
        }
        for (Segment match : smallestMove.matchSegments) {
            match.first.setMatch(match.last, match);
            match.last.setMatch(match.first, match);
            sortedSegments.add(match);
            manifoldSegments.add(match);
        }
        maxMatches++;
        sortedSegments.sort(null);
        knotPointsFlattened = new ArrayList<>();
        fixKnotPointsFlattened(p);

    }

    public void simpleConstructor(Shell shell, int id) {

        this.shell = shell;
        this.id = id;
        shell.pointMap.put(id, this);

        knotPoints = new ArrayList<>();
        sortedSegments = new ArrayList<>();
        knotPointsFlattened = new ArrayList<>();
        segmentLookup = new HashMap<>();
        manifoldSegments = new ArrayList<>();
        matchList = new ArrayList<>();
    }

    public Segment getPointer(int idx) {
        int count = idx;
        ArrayList<Segment> seenGroups = new ArrayList<Segment>();
        ArrayList<Knot> seenPoints = new ArrayList<Knot>();
        for (int i = 0; i < sortedSegments.size(); i++) {
            Segment s = sortedSegments.get(i);
            Knot knotPoint = s.getKnotPoint(knotPointsFlattened);
            Knot basePoint = s.getOther(knotPoint);
            Knot vp = basePoint;
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
        str = Compat.stripTrailing(str);
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
        str = Compat.stripTrailing(str);
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
        str = Compat.stripTrailing(str);
        str += "]";
        return str;
    }

    public HyperString toHyperString() {
        HyperString h = new HyperString();
        Tool tool = MainScene.tool;
        Color c = MainScene.stickyColor;
        if (tool.canUseToggle(Toggle.DrawKnotGradient)) {
            c = MainScene.getKnotGradientColorFlatten((Knot) this);
        } else if (tool.canUseToggle(Toggle.DrawMetroDiagram)) {
            c = MainScene.getMetroColorFlatten((Knot) this);
        }
        Action clickAction = () -> {
            MainScene.setDrawLevelToKnot(this);
            MainScene.camera.zoomToKnot(this);
        };
        Knot hoverKnot = MainScene.getKnotFlatten(this);
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
