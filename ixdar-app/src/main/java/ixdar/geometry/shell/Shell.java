package ixdar.geometry.shell;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;

import org.apache.commons.math3.util.Pair;

import ixdar.common.exceptions.BalancerException;
import ixdar.common.exceptions.IdDoesNotExistException;
import ixdar.common.exceptions.IdsNotConcurrentException;
import ixdar.common.exceptions.MultipleCyclesFoundException;
import ixdar.common.exceptions.SegmentBalanceException;
import ixdar.common.utils.StringBuff;
import ixdar.geometry.cuts.engines.KnotEngine;
import ixdar.geometry.knot.Knot;
import ixdar.geometry.knot.Segment;
import ixdar.geometry.point.PointND;
import ixdar.geometry.point.PointSet;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.text.HyperString;

/**
 * This class represents a list of some points in the point set. Initially each
 * shell is a convex hull, but they are eventually combined together to form the
 * optimal tsp path and they lose their convex property
 */

public class Shell extends LinkedList<PointND> {
    public static final String STR = ", ";
    public static final String STR_2 = "]";
    public static final int NUM_30 = 30;
    public static final double NUM_0_0000001 = 0.0000001;
    public static int failed = 0;
    public HashMap<Integer, Knot> pointMap = new HashMap<Integer, Knot>();
    public DistanceMatrix distanceMatrix;
    public String knotName;

    public KnotEngine knotEngine = new KnotEngine(this);

    public StringBuff buff = new StringBuff();
    public ArrayList<Segment> sortedSegments;
    public HashMap<Long, Segment> segmentLookup;
    public ArrayList<HyperString> hyperStrings = new ArrayList<>();

    public Integer[][] smallestCommonKnotLookup;
    public Integer[][] largestUncommonKnotLookup;
    public Integer[] smallestKnotLookup;

    int breakCount = 0;
    int runCount = 0;

    boolean skipHalfKnotFlag = true;
    private Shell child;

    /**
     * TODO: document {@code Shell}.
     */
    public Shell() {
        pointMap = new HashMap<>();
    }

    /**
     * Initializes a new shell with no parent or child; a blank slate.
     *
     * @param points TODO: describe
     */

    public Shell(PointND... points) {
        for (int i = 0; i < points.length; i++) {
            this.add(points[i]);
        }
    }


    /**
     * TODO: document {@code Shell}.
     *
     * @param points TODO: describe
     */
    public Shell(PointSet points) {
        for (int i = 0; i < points.size(); i++) {
            this.add(points.get(i));
        }
    }

    /**
     * TODO: document {@code initPoints}.
     *
     * @param distanceMatrix TODO: describe
     */
    public void initPoints(DistanceMatrix distanceMatrix) {
        this.distanceMatrix = distanceMatrix;
        int numPoints = distanceMatrix.size();
        for (int i = 0; i < numPoints; i++) {
            PointND pnd = distanceMatrix.getPoints().get(i);
            Knot p = new Knot(pnd, this);
            pointMap.put(pnd.getID(), p);
        }
        for (int i = 0; i < numPoints; i++) {
            Knot p1 = pointMap.get(i);
            for (int j = 0; j < numPoints; j++) {
                if (i != j) {
                    Knot p2 = pointMap.get(j);
                    Segment s = new Segment(p1, p2, distanceMatrix.getDistance(p1.p, p2.p));
                    p1.sortedSegments.add(s);
                    p1.segmentLookup.put(s.id, s);
                }
            }
            p1.sortedSegments.sort(null);
        }
    }

    /**
     * TODO: document {@code tspSolve}.
     *
     * @param A TODO: describe
     * @param distanceMatrix TODO: describe
     * @throws SegmentBalanceException TODO: describe
     * @throws BalancerException TODO: describe
     * @return TODO: describe
     */
    @SuppressWarnings("unused")
    public Shell tspSolve(Shell A, DistanceMatrix distanceMatrix) throws SegmentBalanceException, BalancerException {

        Shell result = new Shell();
        pointMap = new HashMap<>();
        initPoints(distanceMatrix);
        int idx = 0;
        ArrayList<Knot> knots = knotEngine.createKnots(NUM_30, this.sortedSegments);
        if (knots.size() > 1) {
            System.out.println("Recursion Limit REACHED");
            float zero = 1 / 0;
        }
        buff.add("\n================= - WARNING - =================");
        buff.add("警告:ゴーディアスノットを切断します");
        buff.add("システムロックが解除されました");
        buff.add("ナイフが噛み合った");
        buff.add("カット開始");
        buff.add("================= - WARNING - =================\n");
        int knotsCleared = 0;
        return result;
    }

    /**
     * TODO: document {@code initShell}.
     *
     * @param distanceMatrix TODO: describe
     */
    public void initShell(DistanceMatrix distanceMatrix) {
        this.distanceMatrix = distanceMatrix;
        pointMap = new HashMap<>();
        this.sortedSegments = new ArrayList<Segment>();
        this.segmentLookup = new HashMap<>();
        int numPoints = distanceMatrix.size();
        for (int i = 0; i < numPoints; i++) {
            Knot p = new Knot(distanceMatrix.getPoints().get(i), this);
            pointMap.put(i, p);
        }
        for (int i = 0; i < numPoints; i++) {
            Knot p1 = pointMap.get(i);
            for (int j = 0; j < numPoints; j++) {
                Knot p2 = pointMap.get(j);
                Segment s = new Segment(p1, p2, distanceMatrix.getDistance(p1.p, p2.p));
                if (i != j) {
                    p1.sortedSegments.add(s);
                    p1.segmentLookup.put(s.id, s);
                }
                if (i < j) {
                    this.sortedSegments.add(s);
                    this.segmentLookup.put(s.id, s);
                }
            }
            p1.sortedSegments.sort(null);
        }
        sortedSegments.sort(null);
    }

    /**
     * TODO: document {@code slowSolve}.
     *
     * @param A TODO: describe
     * @param distanceMatrix TODO: describe
     * @param layers TODO: describe
     * @throws MultipleCyclesFoundException TODO: describe
     * @return TODO: describe
     */
    public ArrayList<Knot> slowSolve(Shell A, DistanceMatrix distanceMatrix, int layers)
            throws MultipleCyclesFoundException {
        initShell(distanceMatrix);
        ArrayList<Knot> knots = knotEngine.createKnots(layers, this.sortedSegments);
        return knots;
    }

    /**
     * TODO: document {@code updateSmallestKnot}.
     *
     * @param knotNew TODO: describe
     */
    public void updateSmallestKnot(Knot knotNew) {

        if (smallestKnotLookup == null) {

            smallestKnotLookup = new Integer[distanceMatrix.size()];
            Arrays.fill(smallestKnotLookup, -1);
        }

        for (Knot vp : knotNew.knotPointsFlattened) {
            int low = vp.id;
            if (smallestKnotLookup[low] == -1) {
                smallestKnotLookup[low] = knotNew.id;
            }
        }
    }

    /**
     * TODO: document {@code updateSmallestCommonKnot}.
     *
     * @param knotNew TODO: describe
     */
    public void updateSmallestCommonKnot(Knot knotNew) {

        if (smallestCommonKnotLookup == null) {

            smallestCommonKnotLookup = new Integer[distanceMatrix.size()][distanceMatrix.size()];
            for (int i = 0; i < smallestCommonKnotLookup.length; i++) {
                Arrays.fill(smallestCommonKnotLookup[i], -1);
            }
        }

        for (Knot vp : knotNew.knotPointsFlattened) {
            int low = vp.id;
            for (Knot vp2 : knotNew.knotPointsFlattened) {
                int high = vp2.id;
                if (smallestCommonKnotLookup[high][low] != -1) {
                    continue;
                }
                smallestCommonKnotLookup[high][low] = knotNew.id;
                smallestCommonKnotLookup[low][high] = knotNew.id;
            }
        }
    }

    /**
     * TODO: document {@code solveBetweenEndpoints}.
     *
     * @param first TODO: describe
     * @param last TODO: describe
     * @param A TODO: describe
     * @param d TODO: describe
     * @throws SegmentBalanceException TODO: describe
     * @throws BalancerException TODO: describe
     * @return TODO: describe
     */
    public Shell solveBetweenEndpoints(PointND first, PointND last, Shell A, DistanceMatrix d)
            throws SegmentBalanceException, BalancerException {
        PointSet ps = new PointSet();

        assert (!first.equals(last));

        ps.add(first);
        if (!first.equals(last)) {
            ps.add(last);
        }
        ps.addAll(A);
        DistanceMatrix d1 = new DistanceMatrix(ps, d);
        PointND dummy = d1.addDummyNode(-1, first, last);
        ps.add(dummy);
        Shell answer = new Shell();
        answer.add(first);
        answer.addAll(A.copyShallow());
        answer.add(last);
        answer.add(dummy);
        Shell result = tspSolve(answer, d1);

        assert (d1.getZero() != 0);
        assert (d1.getMaxDist() / 2 <= d1.getZero()) : "Zero: " + d1.getZero() + " MaxDist: " + d1.getMaxDist();

        ps.remove(dummy);
        result = result.removeRotate(ps);
        if (!result.get(0).equals(first)) {
            result = result.reverse();
        }

        return result;

    }

    /**
     * Get the length of the shell.
     *
     * @return the length of the path between all points in the shell
     */
    public double getLength() {
        if (this.size() == 0) {
            return 0;
        }
        PointND first = null, last = null;
        double length = 0.0;
        for (PointND p : this) {
            if (first == null) {
                last = p;
                first = p;
            } else {
                length += last.distance(p);
                last = p;
            }
        }
        length += last.distance(first);
        return length;

    }

    /**
     * TODO: document {@code getLengthEndpoints}.
     *
     * @return TODO: describe
     */
    public double getLengthEndpoints() {
        PointND first = null, last = null;
        double length = 0.0;
        for (PointND p : this) {
            if (first == null) {
                last = p;
                first = p;
            } else {
                length += last.distance(p);
                last = p;
            }
        }
        return length;

    }

    /**
     * Gets the distance from a point to its neighboring points in the shell.
     *
     * @param p TODO: describe
     * @param d TODO: describe
     * @return the sum of the distance from p to the prev point in the shell and the
     *         distance from p to the next point in the shell
     */
    public double distanceToNeighbors(PointND p, DistanceMatrix d) {
        PointND prevP = prevPoint(p), nextP = nextPoint(p);

        return d.getDistance(p, prevP) + d.getDistance(p, nextP);

    }

    /**
     * Gets the distance from the point previous to p and the point after p in the
     * shell.
     *
     * @param p TODO: describe
     * @param d TODO: describe
     * @return the sum of the distance from the prev point in the shell to the next
     *         point in the shell
     */
    public double distanceBetweenNeighbors(PointND p, DistanceMatrix d) {
        PointND prevP = prevPoint(p), nextP = nextPoint(p);

        return d.getDistance(nextP, prevP);

    }

    /**
     * Finds the previous point in the shell.
     *
     * @param p reference point
     * @return the point that comes before p in the shell
     */
    public PointND prevPoint(PointND p) {
        int i = this.indexOf(p), before = 0;
        if (i == 0) {
            before = this.size() - 1;
        } else {
            before = i - 1;
        }
        return this.get(before);
    }

    /**
     * Finds the next point in the shell.
     *
     * @param p reference point
     * @return the point that comes after p in the shell
     */
    public PointND nextPoint(PointND p) {
        int i = this.indexOf(p), after = 0;
        if (i == this.size() - 1) {
            after = 0;
        } else {
            after = i + 1;
        }
        return this.get(after);
    }

    /**
     * TODO: document {@code replaceByID}.
     *
     * @param A TODO: describe
     * @param ps TODO: describe
     * @return TODO: describe
     */
    public static Shell replaceByID(Shell A, PointSet ps) {
        Shell result = new Shell();
        for (PointND p : A) {
            result.add(ps.getByID(p.getID()));
        }
        return result;
    }

    /**
     * TODO: document {@code getIndexByID}.
     *
     * @param idTarget TODO: describe
     * @throws IdDoesNotExistException TODO: describe
     * @return TODO: describe
     */
    public int getIndexByID(int idTarget) throws IdDoesNotExistException {
        int idx = 0;
        for (PointND p : this) {
            if (p.getID() == idTarget) {
                return idx;
            }
            idx++;
        }
        throw new IdDoesNotExistException(idTarget);
    }

    /**
     * TODO: document {@code removeByID}.
     *
     * @param idTarget TODO: describe
     * @throws IdDoesNotExistException TODO: describe
     * @return TODO: describe
     */
    public PointND removeByID(int idTarget) throws IdDoesNotExistException {
        int idx = getIndexByID(idTarget);
        return this.remove(idx);
    }

    /**
     * TODO: document {@code removeRotate}.
     *
     * @param ps TODO: describe
     * @return TODO: describe
     */
    public Shell removeRotate(PointSet ps) {

        Shell before = new Shell(), after = new Shell();

        boolean isBeforePoint = true;
        for (PointND p : this) {
            if (!ps.contains(p)) {
                isBeforePoint = false;
            } else {
                if (isBeforePoint) {
                    before.add(p);
                } else {
                    after.add(p);
                }
            }
        }
        after.addAll(before);

        assert (after.size() == this.size() - 1);

        return after;
    }

    /**
     * TODO: document {@code rotateTo}.
     *
     * @param p1 TODO: describe
     * @param p2 TODO: describe
     */
    public void rotateTo(PointND p1, PointND p2) {
        Shell before = new Shell(), after = new Shell();

        boolean isBeforePoint = true;
        for (PointND p : this) {
            if ((p.equals(p1) && this.nextPoint(p).equals(p2)) || (p.equals(p2) && this.nextPoint(p).equals(p1))) {
                isBeforePoint = false;
                before.add(p);
            } else {
                if (isBeforePoint) {
                    before.add(p);
                } else {
                    after.add(p);
                }
            }
        }
        this.removeAll(before);
        this.addAll(before);
    }

    /**
     * Shallow copies a shell so that it does not point to any childern.
     *
     * @return a copy of the current shell with no references to its children
     */
    public Shell copyShallow() {
        Shell copy = new Shell();

        for (PointND q : this) {
            copy.add(q);
        }
        return copy;
    }

    /**
     * Turns a shell into a PointSet object.
     *
     * @return all of the points in the Shell and its children
     */
    public PointSet toPointSet() {
        PointSet ps = new PointSet();
        Shell currShell = this;
        while (currShell != null) {
            for (PointND p : currShell) {
                ps.add(p);

            }
            currShell = currShell.child;
        }
        return ps;

    }

    /**
     * Determines equality of shells based on if they represent the same tsp path.
     *
     * @param o shell to compare to
     * @return true if the shells are equal and false if they are not
     */
    @Override
    public boolean equals(Object o) {
        if (o instanceof Shell) {
            Shell other = (Shell) o;
            if (other.size() != this.size()) {
                return false;
            }
            PointND otherFirst = other.getFirst();
            int startIndex = -1;
            for (PointND p : this) {
                if (p.equals(otherFirst)) {
                    startIndex = this.indexOf(p);
                    break;
                }
            }
            if (startIndex == -1) {
                return false;
            }
            for (int i = 0; i < other.size(); i++) {
                if (!other.get(i).equals(this.get(startIndex))) {
                    return false;
                }
                startIndex = (startIndex + 1) % other.size();
            }
            return true;
        }
        return false;

    }

    /**
     * TODO: document {@code reverse}.
     *
     * @return TODO: describe
     */
    public Shell reverse() {
        Shell result = new Shell();
        for (PointND p : this) {
            result.addFirst(p);
        }
        return result;
    }

    /**
     * TODO: document {@code toString}.
     *
     * @return TODO: describe
     */
    @Override
    public String toString() {
        String str = "Shell[";
        for (int i = 0; i < this.size(); i++) {
            PointND p = this.get(i);
            if (p.getID() != -1) {
                str += p.getID();
            } else {
                str += p.toString();
            }
            if (i < this.size() - 1) {
                str += STR;
            }
        }

        return str + STR_2;
    }

    /**
     * TODO: document {@code compareTo}.
     *
     * @param A TODO: describe
     * @param B TODO: describe
     * @return TODO: describe
     */
    public static String compareTo(Shell A, Shell B) {
        String str = "Shell A[";
        for (int i = 0; i < A.size() - 1; i++) {
            str += (i) + STR;
        }
        str += A.size() - 1 + STR_2;

        str += "\nShell B[";
        for (int i = 0; i < B.size() - 1; i++) {
            str += (A.indexOf(B.get(i))) + STR;
        }
        str += (A.indexOf(B.get(B.size() - 1))) + STR_2;

        return str;

    }

    /**
     * TODO: document {@code add}.
     *
     * @param e TODO: describe
     * @return TODO: describe
     */
    @Override
    public boolean add(PointND e) {
        super.add(e);
        return true;

    }

    /**
     * TODO: document {@code addAll}.
     *
     * @param c TODO: describe
     * @return TODO: describe
     */
    @Override
    public boolean addAll(Collection<? extends PointND> c) {
        super.addAll(c);
        return true;
    }

    /**
     * TODO: document {@code addAllFirst}.
     *
     * @param c TODO: describe
     * @return TODO: describe
     */
    public boolean addAllFirst(Collection<? extends PointND> c) {
        Object[] points = c.toArray();
        for (int i = points.length - 1; i >= 0; i--) {
            this.addFirst((PointND) points[i]);
        }
        return true;
    }

    /**
     * TODO: document {@code addAfter}.
     *
     * @param contained TODO: describe
     * @param insert TODO: describe
     */
    public void addAfter(PointND contained, PointND insert) {
        super.add(this.indexOf(contained) + 1, insert);
    }

    /**
     * TODO: document {@code addOutside}.
     *
     * @param contained TODO: describe
     * @param insert TODO: describe
     */
    public void addOutside(PointND contained, PointND insert) {
        assert (this.getLast().equals(contained) || this.getFirst().equals(contained))
                : insert.getID() + " " + contained.getID() + " " + this.toString();
        super.add(this.indexOf(contained) + 1, insert);
        if (this.getLast().equals(contained)) {
            this.rotateTo(this.getFirst(), insert);
        } else {
            this.rotateTo(this.getLast(), insert);
        }
    }

    /**
     * TODO: document {@code addAllAtSegment}.
     *
     * @param contained TODO: describe
     * @param connector TODO: describe
     * @param other TODO: describe
     */
    public void addAllAtSegment(PointND contained, PointND connector, Shell other) {
        if (this.getLast().equals(contained)) {
            if (other.getLast().equals(connector)) {
                Shell reverse = other.reverse();
                this.addAll(reverse);
            } else {
                this.addAll(other);
            }
        } else {
            if (other.getLast().equals(connector)) {
                this.addAllFirst(other);
            } else {
                Shell reverse = other.reverse();
                this.addAllFirst(reverse);
            }
        }
    }

    /**
     * TODO: document {@code getOppositeOutside}.
     *
     * @param endpoint TODO: describe
     * @return TODO: describe
     */
    public PointND getOppositeOutside(PointND endpoint) {
        assert (this.getLast().equals(endpoint) || this.getFirst().equals(endpoint)) : endpoint.getID();
        if (this.getLast().equals(endpoint)) {
            return this.getFirst();
        } else {
            return this.getLast();
        }
    }

    /**
     * TODO: document {@code isEndpoint}.
     *
     * @param p TODO: describe
     * @return TODO: describe
     */
    public boolean isEndpoint(PointND p) {
        return p.equals(this.getLast()) || p.equals(this.getFirst());
    }

    /**
     * TODO: document {@code containsID}.
     *
     * @param id TODO: describe
     * @return TODO: describe
     */
    public boolean containsID(int id) {
        for (PointND pointND : this) {
            if (pointND.getID() == id) {
                return true;
            }
        }
        return false;
    }

    /**
     * TODO: document {@code containsRange}.
     *
     * @param r TODO: describe
     * @return TODO: describe
     */
    public boolean containsRange(Range r) {
        boolean hasStart = false;
        boolean hasEnd = false;
        for (PointND pointND : this) {
            if (pointND.getID() == r.endIdx) {
                hasEnd = true;
            }

            if (pointND.getID() == r.startIdx) {
                hasStart = true;
            }
        }
        return hasStart && hasEnd;
    }

    /**
     * TODO: document {@code getNext}.
     *
     * @param i TODO: describe
     * @return TODO: describe
     */
    public PointND getNext(int i) {
        if (i + 1 >= this.size()) {
            return this.get(0);
        }
        return this.get(i + 1);
    }

    /**
     * TODO: document {@code getPrev}.
     *
     * @param i TODO: describe
     * @return TODO: describe
     */
    public PointND getPrev(int i) {
        if (i - 1 < 0) {
            return this.get(this.size() - 1);
        }
        return this.get(i - 1);
    }

    /**
     * TODO: document {@code moveAfter}.
     *
     * @param idTarget TODO: describe
     * @param idDest TODO: describe
     * @throws IdDoesNotExistException TODO: describe
     */
    public void moveAfter(Range idTarget, int idDest) throws IdDoesNotExistException {
        if (!containsRange(idTarget)) {
            throw new IdDoesNotExistException(idTarget);
        }
        if (!containsID(idDest)) {
            throw new IdDoesNotExistException(idDest);
        }
        ArrayList<PointND> p = this.removeAllInRange(idTarget);
        int idxDest = this.getIndexByID(idDest);
        if (idTarget.reversed) {
            Collections.reverse(p);
            this.addAll(idxDest + 1, p);
        } else {
            this.addAll(idxDest + 1, p);
        }
    }

    /**
     * TODO: document {@code moveBefore}.
     *
     * @param idTarget TODO: describe
     * @param idDest TODO: describe
     * @throws IdDoesNotExistException TODO: describe
     */
    public void moveBefore(Range idTarget, int idDest) throws IdDoesNotExistException {
        if (!containsRange(idTarget)) {
            throw new IdDoesNotExistException(idTarget);
        }
        if (!containsID(idDest)) {
            throw new IdDoesNotExistException(idDest);
        }
        ArrayList<PointND> p = this.removeAllInRange(idTarget);
        int idxDest = this.getIndexByID(idDest);
        this.addAll(idxDest, p);
    }

    /**
     * TODO: document {@code moveBetween}.
     *
     * @param idTarget TODO: describe
     * @param idDest1 TODO: describe
     * @param idDest2 TODO: describe
     * @throws IdDoesNotExistException TODO: describe
     * @throws IdsNotConcurrentException TODO: describe
     */
    public void moveBetween(Range idTarget, int idDest1, int idDest2)
            throws IdDoesNotExistException, IdsNotConcurrentException {
        if (!containsRange(idTarget)) {
            throw new IdDoesNotExistException(idTarget);
        }
        if (!containsID(idDest1)) {
            throw new IdDoesNotExistException(idDest1);
        }
        if (!containsID(idDest2)) {
            throw new IdDoesNotExistException(idDest2);
        }
        int idxDest1 = this.getIndexByID(idDest1);
        int idxDest2 = this.getIndexByID(idDest2);
        if (idxDest1 + 1 != idxDest2 && idxDest2 + 1 != idxDest1
                && !((idxDest1 == 0 && idxDest2 == this.size()) || (idxDest2 == 0 && idxDest1 == this.size()))) {
            throw new IdsNotConcurrentException(idxDest1, idxDest2);
        }
        ArrayList<PointND> p = this.removeAllInRange(idTarget);
        idxDest1 = this.getIndexByID(idDest1);
        idxDest2 = this.getIndexByID(idDest2);
        if (idxDest1 == 0 && idxDest2 == this.size()) {
            this.addAll(idxDest1, p);
        } else if ((idxDest2 == 0 && idxDest1 == this.size())) {
            this.addAll(idxDest2, p);
        } else if (idxDest1 > idxDest2) {
            this.addAll(idxDest2, p);
        } else {
            this.addAll(idxDest1, p);
        }
    }

    /**
     * TODO: document {@code hasPoint}.
     *
     * @param id TODO: describe
     * @return TODO: describe
     */
    public boolean hasPoint(int id) {
        for (PointND p : this) {
            if (p.getID() == id) {
                return true;
            }
        }
        return false;
    }

    /**
     * TODO: document {@code addAllInRange}.
     *
     * @param r TODO: describe
     * @param orgShell TODO: describe
     */
    public void addAllInRange(Range r, Shell orgShell) {
        for (PointND p : orgShell) {
            if (r.hasPoint(p)) {
                this.add(p);
            }
        }
    }

    /**
     * TODO: document {@code getAllInRange}.
     *
     * @param r TODO: describe
     * @return TODO: describe
     */
    public ArrayList<PointND> getAllInRange(Range r) {
        ArrayList<PointND> points = new ArrayList<>();
        for (PointND p : this) {
            if (r.hasPoint(p)) {
                points.add(p);
            }
        }
        return points;
    }

    /**
     * TODO: document {@code removeAllInRange}.
     *
     * @param r TODO: describe
     * @return TODO: describe
     */
    public ArrayList<PointND> removeAllInRange(Range r) {
        ArrayList<PointND> points = new ArrayList<>();
        for (PointND p : this) {
            if (r.hasPoint(p)) {
                points.add(p);
            }
        }
        this.removeAll(points);
        return points;
    }

    /**
     * TODO: document {@code isLocalMinima}.
     *
     * @return TODO: describe
     */
    public Pair<PointND, Pair<PointND, PointND>> isLocalMinima() {
        for (int i = 0; i < this.size(); i++) {
            PointND curr = this.get(i);
            PointND next = this.getNext(i);
            PointND prev = this.getPrev(i);
            double delta = next.distance(prev) - next.distance(curr) - prev.distance(curr);
            for (int j = 0; j < this.size(); j++) {
                int nextJ = j + 1 >= this.size() ? 0 : j + 1;
                if (i != j && i != nextJ) {
                    PointND currD = this.get(j);
                    PointND nextD = this.get(nextJ);
                    double delta2 = delta - currD.distance(nextD) + currD.distance(curr) + nextD.distance(curr);
                    if (delta2 < 0 && delta2 < -NUM_0_0000001) {
                        return new Pair<PointND, Pair<PointND, PointND>>(curr,
                                new Pair<PointND, PointND>(currD, nextD));
                    }
                }
            }
        }
        return null;
    }

    /**
     * TODO: document {@code getHyperStrings}.
     *
     * @param c TODO: describe
     * @return TODO: describe
     */
    public ArrayList<HyperString> getHyperStrings(Color c) {
        if (hyperStrings.size() == this.size()) {
            return hyperStrings;
        }
        hyperStrings = new ArrayList<>();
        for (PointND p : this) {
            HyperString number = new HyperString();
            HyperString pointInfo = new HyperString();
            pointInfo.addWord(p.toString());
            number.addTooltip(p.getID() + "", c, pointInfo, () -> {
            });
            number.debug = true;
            number.setData(p);
            hyperStrings.add(number);


        }
        return hyperStrings;
    }

}
