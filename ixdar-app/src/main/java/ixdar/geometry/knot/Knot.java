
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

    public enum WindingOrder {
        None, Clockwise, CounterClockwise
    }

    public int minMatches;
    public int maxMatches;
    public int matchCount;
    public ArrayList<Knot> externalKnots;
    public ArrayList<Knot> knotPoints;
    public ArrayList<Knot> knotPointsFlattened;
    public ArrayList<Segment> sortedSegments;
    public HashMap<Long, Segment> segmentLookup;
    public int id;

    public Knot topGroupKnot;
    public Shell shell;
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

    public void removeMatch(Knot other) {
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

    // ==================== TRADE ROUTE OPERATIONS ====================

    /**
     * Type of edge in a knot hierarchy.
     */
    public enum EdgeType {
        /** Edge within a child knot's original structure */
        SUBKNOT_EDGE,
        /** Edge created by a previous pipe operation (connects two children) */
        PIPE_EDGE,
        /** Edge type unknown or not applicable */
        UNKNOWN
    }

    /**
     * Information about an edge in the knot hierarchy.
     */
    public static class EdgeInfo {
        public Segment edge;
        public EdgeType type;
        public Knot owningKnot;

        public EdgeInfo(Segment edge, EdgeType type, Knot owningKnot) {
            this.edge = edge;
            this.type = type;
            this.owningKnot = owningKnot;
        }
    }

    /**
     * Determine the type of an edge in this knot's hierarchy.
     * 
     * @param edge the edge to classify
     * @return EdgeInfo with type and owning knot
     */
    public EdgeInfo getEdgeInfo(Segment edge) {
        if (edge == null || !manifoldSegments.contains(edge)) {
            return new EdgeInfo(edge, EdgeType.UNKNOWN, null);
        }

        // Check if both endpoints belong to the same child knot
        for (Knot child : knotPoints) {
            if (!child.isSingleton()) {
                boolean firstInChild = child.contains(edge.first);
                boolean lastInChild = child.contains(edge.last);
                if (firstInChild && lastInChild) {
                    return new EdgeInfo(edge, EdgeType.SUBKNOT_EDGE, child);
                }
            }
        }

        // If endpoints are in different children (or singletons), it's a pipe edge
        return new EdgeInfo(edge, EdgeType.PIPE_EDGE, this);
    }

    /**
     * Create a simple 2-point loop from two singletons. Used for initial trade
     * route creation.
     * 
     * @param k1 first singleton (e.g., HQ)
     * @param k2 second singleton (destination)
     * @return PipeRecord for undo support
     */
    public static PipeRecord pipeSimple(Knot k1, Knot k2) {
        if (!k1.isSingleton() || !k2.isSingleton()) {
            throw new IllegalArgumentException("pipeSimple requires two singletons");
        }

        PipeRecord record = new PipeRecord();
        record.type = PipeRecord.PipeType.SINGLETON_TO_SINGLETON;
        record.childA = k1;
        record.childB = k2;

        // Create the new knot
        Shell shell = k1.shell;
        Knot loop = new Knot();
        loop.simpleConstructorNoRegister(shell, shell.pointMap.keySet().size());
        loop.minMatches = 2;
        loop.maxMatches = 2;

        // Add both points to the loop
        loop.knotPoints.add(k1);
        loop.knotPoints.add(k2);
        loop.knotPointsFlattened.add(k1);
        loop.knotPointsFlattened.add(k2);

        // Create segments between them (bidirectional for the loop)
        double dist = shell.distanceMatrix.getDistance(k1.p, k2.p);
        Segment s1to2 = new Segment(k1, k2, dist);
        Segment s2to1 = new Segment(k2, k1, dist);

        // Set up neighbor connections
        k1.setMatch(k2, s1to2);
        k1.setMatch(k2, s2to1);
        k2.setMatch(k1, s1to2);
        k2.setMatch(k1, s2to1);

        // Add segments to manifold
        loop.manifoldSegments.add(s1to2);
        loop.manifoldSegments.add(s2to1);
        loop.sortedSegments.add(s1to2);
        loop.sortedSegments.add(s2to1);
        loop.sortedSegments.sort(null);

        // Set parent references
        k1.topGroupKnot = loop;
        k2.topGroupKnot = loop;

        // Union sets
        unionSet.addSet(loop);
        unionSet.union(loop, k1);
        unionSet.union(loop, k2);

        // Store in record
        record.resultKnot = loop;
        record.addedSeg1 = s1to2;
        record.addedSeg2 = s2to1;

        return record;
    }

    /**
     * Pipe two knots together with user-specified edges. Handles all three cases:
     * singleton+singleton, N+singleton, N+M.
     * 
     * @param a     first knot
     * @param b     second knot
     * @param edgeA edge to cut on knot A (null for singletons)
     * @param edgeB edge to cut on knot B (null for singletons)
     * @return PipeRecord for undo support
     */
    public static PipeRecord pipe(Knot a, Knot b, Segment edgeA, Segment edgeB) {
        boolean aSingle = a.isSingleton();
        boolean bSingle = b.isSingleton();

        if (aSingle && bSingle) {
            return pipeSimple(a, b);
        }

        if (aSingle || bSingle) {
            // One singleton, one knot - this is essentially a grow operation
            Knot singleton = aSingle ? a : b;
            Knot knot = aSingle ? b : a;
            Segment cutEdge = aSingle ? edgeB : edgeA;
            return pipeToExisting(knot, singleton, cutEdge);
        }

        // Both are knots - determine hierarchy based on edge types
        EdgeInfo infoA = a.getEdgeInfo(edgeA);
        EdgeInfo infoB = b.getEdgeInfo(edgeB);

        if (infoA.type == EdgeType.SUBKNOT_EDGE && infoB.type == EdgeType.SUBKNOT_EDGE) {
            // Both edges are within sub-knots - same hierarchy level
            return pipeToSameLevel(a, b, edgeA, edgeB);
        } else {
            // At least one is a pipe edge - create new hierarchy level
            return pipeToNewLevel(a, b, edgeA, edgeB);
        }
    }

    /**
     * Add a singleton to an existing knot by cutting an edge.
     */
    private static PipeRecord pipeToExisting(Knot knot, Knot singleton, Segment cutEdge) {
        PipeRecord record = new PipeRecord();
        record.type = PipeRecord.PipeType.ADD_TO_EXISTING;
        record.resultKnot = knot;
        record.childA = knot;
        record.childB = singleton;
        record.cutEdgeA = cutEdge;

        // Remove the cut edge
        knot.manifoldSegments.remove(cutEdge);
        knot.sortedSegments.remove(cutEdge);
        cutEdge.first.removeMatch(cutEdge.last);
        cutEdge.last.removeMatch(cutEdge.first);

        // Create new segments through the singleton
        Shell shell = knot.shell;
        double dist1 = shell.distanceMatrix.getDistance(cutEdge.first.p, singleton.p);
        double dist2 = shell.distanceMatrix.getDistance(singleton.p, cutEdge.last.p);
        Segment seg1 = new Segment(cutEdge.first, singleton, dist1);
        Segment seg2 = new Segment(singleton, cutEdge.last, dist2);

        // Add new segments
        knot.manifoldSegments.add(seg1);
        knot.manifoldSegments.add(seg2);
        knot.sortedSegments.add(seg1);
        knot.sortedSegments.add(seg2);
        knot.sortedSegments.sort(null);

        // Set up match connections
        cutEdge.first.setMatch(singleton, seg1);
        singleton.setMatch(cutEdge.first, seg1);
        singleton.setMatch(cutEdge.last, seg2);
        cutEdge.last.setMatch(singleton, seg2);

        // Add singleton to knot
        knot.knotPoints.add(singleton);
        // Insert in flattened list at appropriate position
        int insertIdx = knot.knotPointsFlattened.indexOf(cutEdge.first);
        if (insertIdx >= 0) {
            knot.knotPointsFlattened.add(insertIdx + 1, singleton);
        } else {
            knot.knotPointsFlattened.add(singleton);
        }

        // Set parent reference
        singleton.topGroupKnot = knot;
        knot.maxMatches++;

        // Union sets
        unionSet.union(knot, singleton);

        // Store in record
        record.addedSeg1 = seg1;
        record.addedSeg2 = seg2;

        return record;
    }

    /**
     * Pipe two knots at the same hierarchy level.
     */
    private static PipeRecord pipeToSameLevel(Knot a, Knot b, Segment edgeA, Segment edgeB) {
        PipeRecord record = new PipeRecord();
        record.type = PipeRecord.PipeType.ADD_TO_EXISTING;
        record.childA = a;
        record.childB = b;
        record.cutEdgeA = edgeA;
        record.cutEdgeB = edgeB;

        // Find or create parent knot
        Knot parent = a.topGroupKnot;
        if (parent == null) {
            parent = a;
        }
        record.resultKnot = parent;

        // Remove cut edges
        if (edgeA != null) {
            parent.manifoldSegments.remove(edgeA);
            parent.sortedSegments.remove(edgeA);
            edgeA.first.removeMatch(edgeA.last);
            edgeA.last.removeMatch(edgeA.first);
        }
        if (edgeB != null) {
            parent.manifoldSegments.remove(edgeB);
            parent.sortedSegments.remove(edgeB);
            edgeB.first.removeMatch(edgeB.last);
            edgeB.last.removeMatch(edgeB.first);
        }

        // Create pipe segments connecting the cut edge endpoints
        Shell shell = a.shell;
        Segment pipe1 = new Segment(edgeA.first, edgeB.first,
                shell.distanceMatrix.getDistance(edgeA.first.p, edgeB.first.p));
        Segment pipe2 = new Segment(edgeA.last, edgeB.last,
                shell.distanceMatrix.getDistance(edgeA.last.p, edgeB.last.p));

        // Add pipe segments
        parent.manifoldSegments.add(pipe1);
        parent.manifoldSegments.add(pipe2);
        parent.sortedSegments.add(pipe1);
        parent.sortedSegments.add(pipe2);
        parent.sortedSegments.sort(null);

        // Set up match connections
        edgeA.first.setMatch(edgeB.first, pipe1);
        edgeB.first.setMatch(edgeA.first, pipe1);
        edgeA.last.setMatch(edgeB.last, pipe2);
        edgeB.last.setMatch(edgeA.last, pipe2);

        // Add b to parent if not already there
        if (!parent.knotPoints.contains(b)) {
            parent.knotPoints.add(b);
            for (Knot flatPoint : b.knotPointsFlattened) {
                if (!parent.knotPointsFlattened.contains(flatPoint)) {
                    parent.knotPointsFlattened.add(flatPoint);
                }
            }
            b.topGroupKnot = parent;
            parent.maxMatches++;
        }

        unionSet.union(parent, b);

        record.addedSeg1 = pipe1;
        record.addedSeg2 = pipe2;

        return record;
    }

    /**
     * Pipe two knots creating a new hierarchy level.
     */
    private static PipeRecord pipeToNewLevel(Knot a, Knot b, Segment edgeA, Segment edgeB) {
        PipeRecord record = new PipeRecord();
        record.type = PipeRecord.PipeType.NEW_HIERARCHY_LEVEL;
        record.childA = a;
        record.childB = b;
        record.cutEdgeA = edgeA;
        record.cutEdgeB = edgeB;

        // Create new parent knot
        Shell shell = a.shell;
        Knot newParent = new Knot();
        newParent.simpleConstructorNoRegister(shell, shell.pointMap.keySet().size());
        newParent.minMatches = 2;
        newParent.maxMatches = 2;

        // Add children
        newParent.knotPoints.add(a);
        newParent.knotPoints.add(b);

        // Flatten points from both children
        for (Knot flatPoint : a.knotPointsFlattened) {
            newParent.knotPointsFlattened.add(flatPoint);
        }
        for (Knot flatPoint : b.knotPointsFlattened) {
            newParent.knotPointsFlattened.add(flatPoint);
        }

        // Remove cut edges from children's manifolds
        if (edgeA != null) {
            a.manifoldSegments.remove(edgeA);
            a.sortedSegments.remove(edgeA);
            edgeA.first.removeMatch(edgeA.last);
            edgeA.last.removeMatch(edgeA.first);
        }
        if (edgeB != null) {
            b.manifoldSegments.remove(edgeB);
            b.sortedSegments.remove(edgeB);
            edgeB.first.removeMatch(edgeB.last);
            edgeB.last.removeMatch(edgeB.first);
        }

        // Create pipe segments
        Segment pipe1 = new Segment(edgeA.first, edgeB.first,
                shell.distanceMatrix.getDistance(edgeA.first.p, edgeB.first.p));
        Segment pipe2 = new Segment(edgeA.last, edgeB.last,
                shell.distanceMatrix.getDistance(edgeA.last.p, edgeB.last.p));

        // Add to new parent's manifold
        newParent.manifoldSegments.add(pipe1);
        newParent.manifoldSegments.add(pipe2);
        // Also add all segments from children
        for (Segment s : a.manifoldSegments) {
            newParent.manifoldSegments.add(s);
        }
        for (Segment s : b.manifoldSegments) {
            newParent.manifoldSegments.add(s);
        }

        newParent.sortedSegments.add(pipe1);
        newParent.sortedSegments.add(pipe2);
        newParent.sortedSegments.sort(null);

        // Set up match connections
        edgeA.first.setMatch(edgeB.first, pipe1);
        edgeB.first.setMatch(edgeA.first, pipe1);
        edgeA.last.setMatch(edgeB.last, pipe2);
        edgeB.last.setMatch(edgeA.last, pipe2);

        // Set parent references
        a.topGroupKnot = newParent;
        b.topGroupKnot = newParent;

        // Union sets
        unionSet.addSet(newParent);
        unionSet.union(newParent, a);
        unionSet.union(newParent, b);

        record.resultKnot = newParent;
        record.addedSeg1 = pipe1;
        record.addedSeg2 = pipe2;

        return record;
    }

    /**
     * Get the lowest-cost edge pair for piping two knots. Uses the existing
     * getDeltaDistTo logic.
     * 
     * @param other the other knot to pipe with
     * @return array of [edgeOnThis, edgeOnOther], or null if not possible
     */
    public Segment[] getLowestCostPipeEdges(Knot other) {
        CutMatch match = this.getDeltaDistTo(other);
        if (match == null || match.cutSegments.isEmpty()) {
            return null;
        }

        Segment[] result = new Segment[2];
        if (match.cutSegments.size() >= 1) {
            result[0] = match.cutSegments.get(0);
        }
        if (match.cutSegments.size() >= 2) {
            result[1] = match.cutSegments.get(1);
        }
        return result;
    }

    /**
     * Simple constructor that doesn't register with shell.pointMap. Used for
     * creating parent knots in trade routes.
     */
    public void simpleConstructorNoRegister(Shell shell, int id) {
        this.shell = shell;
        this.id = id;
        knotPoints = new ArrayList<>();
        sortedSegments = new ArrayList<>();
        knotPointsFlattened = new ArrayList<>();
        segmentLookup = new HashMap<>();
        manifoldSegments = new ArrayList<>();
        matchList = new ArrayList<>();
    }

    /**
     * Default constructor for creating empty knots.
     */
    public Knot() {
        knotPoints = new ArrayList<>();
        sortedSegments = new ArrayList<>();
        knotPointsFlattened = new ArrayList<>();
        segmentLookup = new HashMap<>();
        manifoldSegments = new ArrayList<>();
        matchList = new ArrayList<>();
    }

    // ==================== GROW OPERATION ====================

    /**
     * Grow this knot by inserting a singleton into a specific edge. Does not change
     * hierarchy - modifies this knot in place.
     * 
     * @param singleton the singleton point to insert
     * @param edge      the edge to split (must be in this knot's manifold)
     * @return GrowRecord for undo support
     */
    public GrowRecord grow(Knot singleton, Segment edge) {
        if (!singleton.isSingleton()) {
            throw new IllegalArgumentException("grow requires a singleton");
        }
        if (edge == null || !manifoldSegments.contains(edge)) {
            throw new IllegalArgumentException("edge must be in this knot's manifold");
        }

        GrowRecord record = new GrowRecord();
        record.modifiedKnot = this;
        record.insertedPoint = singleton;
        record.originalEdge = edge;

        // Remove the original edge
        manifoldSegments.remove(edge);
        sortedSegments.remove(edge);
        edge.first.removeMatch(edge.last);
        edge.last.removeMatch(edge.first);

        // Create two new edges through the singleton
        double dist1 = shell.distanceMatrix.getDistance(edge.first.p, singleton.p);
        double dist2 = shell.distanceMatrix.getDistance(singleton.p, edge.last.p);
        Segment newEdge1 = new Segment(edge.first, singleton, dist1);
        Segment newEdge2 = new Segment(singleton, edge.last, dist2);

        // Add new edges to manifold
        manifoldSegments.add(newEdge1);
        manifoldSegments.add(newEdge2);
        sortedSegments.add(newEdge1);
        sortedSegments.add(newEdge2);
        sortedSegments.sort(null);

        // Set up match connections
        edge.first.setMatch(singleton, newEdge1);
        singleton.setMatch(edge.first, newEdge1);
        singleton.setMatch(edge.last, newEdge2);
        edge.last.setMatch(singleton, newEdge2);

        // Add singleton to this knot
        knotPoints.add(singleton);

        // Insert in flattened list at appropriate position
        int insertIdx = knotPointsFlattened.indexOf(edge.first);
        if (insertIdx >= 0 && insertIdx < knotPointsFlattened.size() - 1) {
            knotPointsFlattened.add(insertIdx + 1, singleton);
            record.insertionIndex = insertIdx + 1;
        } else {
            knotPointsFlattened.add(singleton);
            record.insertionIndex = knotPointsFlattened.size() - 1;
        }

        // Set parent reference
        singleton.topGroupKnot = this;
        maxMatches++;

        // Union sets
        unionSet.union(this, singleton);

        // Store in record
        record.newEdge1 = newEdge1;
        record.newEdge2 = newEdge2;

        return record;
    }

    /**
     * Grow this knot by inserting a singleton at the lowest-cost edge.
     * 
     * @param singleton the singleton point to insert
     * @return GrowRecord for undo support
     */
    public GrowRecord growAtLowestCost(Knot singleton) {
        if (!singleton.isSingleton()) {
            throw new IllegalArgumentException("grow requires a singleton");
        }

        // Find the lowest-cost edge to split
        Segment bestEdge = null;
        double minDelta = Double.MAX_VALUE;

        for (Segment edge : manifoldSegments) {
            double dist1 = shell.distanceMatrix.getDistance(edge.first.p, singleton.p);
            double dist2 = shell.distanceMatrix.getDistance(singleton.p, edge.last.p);
            double delta = dist1 + dist2 - edge.distance;
            if (delta < minDelta) {
                minDelta = delta;
                bestEdge = edge;
            }
        }

        if (bestEdge == null) {
            throw new IllegalStateException("No edge found for grow operation");
        }

        return grow(singleton, bestEdge);
    }

    /**
     * Get the lowest-cost edge for inserting a singleton.
     * 
     * @param singleton the singleton to insert
     * @return the edge with lowest insertion cost
     */
    public Segment getLowestCostGrowEdge(Knot singleton) {
        if (!singleton.isSingleton()) {
            return null;
        }

        Segment bestEdge = null;
        double minDelta = Double.MAX_VALUE;

        for (Segment edge : manifoldSegments) {
            double dist1 = shell.distanceMatrix.getDistance(edge.first.p, singleton.p);
            double dist2 = shell.distanceMatrix.getDistance(singleton.p, edge.last.p);
            double delta = dist1 + dist2 - edge.distance;
            if (delta < minDelta) {
                minDelta = delta;
                bestEdge = edge;
            }
        }

        return bestEdge;
    }

    // ==================== COLLAPSE OPERATION ====================

    /**
     * Find the two pipe segments connecting two child knots. Pipes have 2 segments
     * each connecting endpoints of cut edges.
     * 
     * @param childA first child knot
     * @param childB second child knot
     * @return array of 2 segments forming the pipe, or null if not found
     */
    public Segment[] getPipeSegments(Knot childA, Knot childB) {
        ArrayList<Segment> pipeSegs = new ArrayList<>();

        for (Segment seg : manifoldSegments) {
            boolean firstInA = childA.contains(seg.first);
            boolean lastInA = childA.contains(seg.last);
            boolean firstInB = childB.contains(seg.first);
            boolean lastInB = childB.contains(seg.last);

            // A pipe segment connects a point in A to a point in B
            if ((firstInA && lastInB) || (firstInB && lastInA)) {
                pipeSegs.add(seg);
            }
        }

        if (pipeSegs.size() >= 2) {
            return new Segment[] { pipeSegs.get(0), pipeSegs.get(1) };
        } else if (pipeSegs.size() == 1) {
            return new Segment[] { pipeSegs.get(0), null };
        }
        return null;
    }

    /**
     * Find the path of child knots between two children.
     * 
     * @param startChild starting child knot
     * @param endChild   ending child knot
     * @return list of knots in the path, or null if not connected
     */
    public ArrayList<Knot> findPathBetweenChildren(Knot startChild, Knot endChild) {
        if (!knotPoints.contains(startChild) || !knotPoints.contains(endChild)) {
            return null;
        }

        // Build adjacency based on pipe connections
        HashMap<Knot, ArrayList<Knot>> adjacency = new HashMap<>();
        for (Knot child : knotPoints) {
            adjacency.put(child, new ArrayList<>());
        }

        for (int i = 0; i < knotPoints.size(); i++) {
            for (int j = i + 1; j < knotPoints.size(); j++) {
                Knot a = knotPoints.get(i);
                Knot b = knotPoints.get(j);
                Segment[] pipes = getPipeSegments(a, b);
                if (pipes != null && pipes[0] != null) {
                    adjacency.get(a).add(b);
                    adjacency.get(b).add(a);
                }
            }
        }

        // BFS to find path
        HashMap<Knot, Knot> parent = new HashMap<>();
        ArrayList<Knot> queue = new ArrayList<>();
        queue.add(startChild);
        parent.put(startChild, null);

        while (!queue.isEmpty()) {
            Knot current = queue.remove(0);
            if (current == endChild) {
                // Reconstruct path
                ArrayList<Knot> path = new ArrayList<>();
                Knot node = endChild;
                while (node != null) {
                    path.add(0, node);
                    node = parent.get(node);
                }
                return path;
            }

            for (Knot neighbor : adjacency.get(current)) {
                if (!parent.containsKey(neighbor)) {
                    parent.put(neighbor, current);
                    queue.add(neighbor);
                }
            }
        }

        return null;
    }

    /**
     * Collapse operation: Convert double-pipes to single-pipes with crossings. For
     * each pipe in the path, removes one segment and adds an internal crossing.
     * Also adds a direct connection between start and end.
     * 
     * @param startChild the starting child knot
     * @param endChild   the ending child knot
     * @return CollapseRecord for undo support
     */
    public CollapseRecord collapse(Knot startChild, Knot endChild) {
        ArrayList<Knot> path = findPathBetweenChildren(startChild, endChild);
        if (path == null || path.size() < 2) {
            throw new IllegalArgumentException("Cannot find path between children");
        }

        CollapseRecord record = new CollapseRecord();
        record.startKnot = startChild;
        record.endKnot = endChild;
        record.pathKnots = new ArrayList<>(path);

        // Track removed endpoints for creating crossings
        HashMap<Knot, ArrayList<Knot>> removedEndpoints = new HashMap<>();
        for (Knot child : path) {
            removedEndpoints.put(child, new ArrayList<>());
        }

        // For each pipe in the path, remove one segment
        for (int i = 0; i < path.size() - 1; i++) {
            Knot a = path.get(i);
            Knot b = path.get(i + 1);

            Segment[] pipeSegs = getPipeSegments(a, b);
            if (pipeSegs == null || pipeSegs[0] == null) {
                continue;
            }

            // Remove the second segment (keep the first)
            // If only one exists, we don't remove anything
            if (pipeSegs[1] != null) {
                Segment toRemove = pipeSegs[1];
                record.removedPipeSegments.add(toRemove);

                // Remove from manifold
                manifoldSegments.remove(toRemove);
                sortedSegments.remove(toRemove);

                // Remove match connections
                toRemove.first.removeMatch(toRemove.last);
                toRemove.last.removeMatch(toRemove.first);

                // Track which endpoints lost connections
                if (a.contains(toRemove.first)) {
                    removedEndpoints.get(a).add(toRemove.first);
                } else {
                    removedEndpoints.get(b).add(toRemove.first);
                }
                if (a.contains(toRemove.last)) {
                    removedEndpoints.get(a).add(toRemove.last);
                } else {
                    removedEndpoints.get(b).add(toRemove.last);
                }
            }
        }

        // For each child in the path, create an internal crossing
        // connecting the endpoints that lost their pipe connections
        for (Knot child : path) {
            ArrayList<Knot> endpoints = removedEndpoints.get(child);
            if (endpoints.size() >= 2) {
                Knot ep1 = endpoints.get(0);
                Knot ep2 = endpoints.get(1);

                double dist = shell.distanceMatrix.getDistance(ep1.p, ep2.p);
                Segment crossing = new Segment(ep1, ep2, dist);

                record.addedCrossings.add(crossing);

                // Add to manifold
                manifoldSegments.add(crossing);
                sortedSegments.add(crossing);

                // Set up match connections
                ep1.setMatch(ep2, crossing);
                ep2.setMatch(ep1, crossing);
            }
        }
        sortedSegments.sort(null);

        // Add direct connection between start and end
        // Find endpoints in startChild and endChild that can connect
        Knot startEndpoint = null;
        Knot endEndpoint = null;

        ArrayList<Knot> startEndpoints = removedEndpoints.get(startChild);
        ArrayList<Knot> endEndpoints = removedEndpoints.get(endChild);

        if (!startEndpoints.isEmpty()) {
            startEndpoint = startEndpoints.get(startEndpoints.size() - 1);
        } else {
            // Use first flattened point
            startEndpoint = startChild.knotPointsFlattened.isEmpty() ? startChild
                    : startChild.knotPointsFlattened.get(0);
        }

        if (!endEndpoints.isEmpty()) {
            endEndpoint = endEndpoints.get(0);
        } else {
            endEndpoint = endChild.knotPointsFlattened.isEmpty() ? endChild : endChild.knotPointsFlattened.get(0);
        }

        double directDist = shell.distanceMatrix.getDistance(startEndpoint.p, endEndpoint.p);
        Segment directSeg = new Segment(startEndpoint, endEndpoint, directDist);

        record.addedDirect = directSeg;

        manifoldSegments.add(directSeg);
        sortedSegments.add(directSeg);
        sortedSegments.sort(null);

        startEndpoint.setMatch(endEndpoint, directSeg);
        endEndpoint.setMatch(startEndpoint, directSeg);

        return record;
    }

    /**
     * Get the default pipe segments to remove for a collapse operation. Returns the
     * higher-cost segment from each pipe.
     * 
     * @param startChild the starting child knot
     * @param endChild   the ending child knot
     * @return list of segments that would be removed
     */
    public ArrayList<Segment> getDefaultCollapseSegments(Knot startChild, Knot endChild) {
        ArrayList<Segment> result = new ArrayList<>();
        ArrayList<Knot> path = findPathBetweenChildren(startChild, endChild);

        if (path == null || path.size() < 2) {
            return result;
        }

        for (int i = 0; i < path.size() - 1; i++) {
            Knot a = path.get(i);
            Knot b = path.get(i + 1);

            Segment[] pipeSegs = getPipeSegments(a, b);
            if (pipeSegs != null && pipeSegs[1] != null) {
                // Default: remove the longer segment
                if (pipeSegs[0].distance > pipeSegs[1].distance) {
                    result.add(pipeSegs[0]);
                } else {
                    result.add(pipeSegs[1]);
                }
            }
        }

        return result;
    }

}
