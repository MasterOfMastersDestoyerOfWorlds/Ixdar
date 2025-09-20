package shell.shell;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;

import org.apache.commons.math3.util.Pair;

import shell.DistanceMatrix;
import shell.PointSet;
import shell.cuts.engines.KnotEngine;
import shell.exceptions.BalancerException;
import shell.exceptions.IdDoesNotExistException;
import shell.exceptions.IdsNotConcurrentException;
import shell.exceptions.MultipleCyclesFoundException;
import shell.exceptions.SegmentBalanceException;
import shell.knot.Knot;
import shell.knot.Segment;
import shell.point.PointND;
import shell.render.text.HyperString;
import shell.utils.StringBuff;
import shell.render.color.Color;

/**
 * This class represents a list of some points in the point set. Initially each
 * shell is a convex hull, but they are eventually combined together to form the
 * optimal tsp path and they lose their convex property
 */

public class Shell extends LinkedList<PointND> {
    public static int failed = 0;
    private Shell child;
    public HashMap<Integer, Knot> pointMap = new HashMap<Integer, Knot>();
    public DistanceMatrix distanceMatrix;
    public String knotName;

    public KnotEngine knotEngine = new KnotEngine(this);

    public StringBuff buff = new StringBuff();

    int breakCount = 0;
    int runCount = 0;

    boolean skipHalfKnotFlag = true;
    public ArrayList<Segment> sortedSegments;
    public HashMap<Long, Segment> segmentLookup;
    public ArrayList<HyperString> hyperStrings = new ArrayList<>();

    public Shell() {
        pointMap = new HashMap<>();
    }

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

    @SuppressWarnings("unused")
    public Shell tspSolve(Shell A, DistanceMatrix distanceMatrix) throws SegmentBalanceException, BalancerException {

        Shell result = new Shell();
        pointMap = new HashMap<>();
        initPoints(distanceMatrix);
        int idx = 0;
        ArrayList<Knot> knots = knotEngine.createKnots(30, this.sortedSegments);
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

    public ArrayList<Knot> slowSolve(Shell A, DistanceMatrix distanceMatrix, int layers)
            throws MultipleCyclesFoundException {
        initShell(distanceMatrix);
        ArrayList<Knot> knots = knotEngine.createKnots(layers, this.sortedSegments);
        return knots;
    }

    public Integer[][] smallestCommonKnotLookup;
    public Integer[][] largestUncommonKnotLookup;
    public Integer[] smallestKnotLookup;

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
     * Initializes a new shell with no parent or child; a blank slate
     * 
     * @param points
     */

    public Shell(PointND... points) {
        for (int i = 0; i < points.length; i++) {
            this.add(points[i]);
        }
    }


    public Shell(PointSet points) {
        for (int i = 0; i < points.size(); i++) {
            this.add(points.get(i));
        }
    }

    /**
     * Get the length of the shell
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
     * Gets the distance from a point to its neighboring points in the shell
     * 
     * @param p
     * @param maxDist
     * @return the sum of the distance from p to the prev point in the shell and the
     *         distance from p to the next point in the shell
     */
    public double distanceToNeighbors(PointND p, DistanceMatrix d) {
        PointND prevP = prevPoint(p), nextP = nextPoint(p);

        return d.getDistance(p, prevP) + d.getDistance(p, nextP);

    }

    /**
     * Gets the distance from the point previous to p and the point after p in the
     * shell
     * 
     * @param p
     * @param maxDist
     * @return the sum of the distance from the prev point in the shell to the next
     *         point in the shell
     */
    public double distanceBetweenNeighbors(PointND p, DistanceMatrix d) {
        PointND prevP = prevPoint(p), nextP = nextPoint(p);

        return d.getDistance(nextP, prevP);

    }

    /**
     * Finds the previous point in the shell
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
     * Finds the next point in the shell
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

    public static Shell replaceByID(Shell A, PointSet ps) {
        Shell result = new Shell();
        for (PointND p : A) {
            result.add(ps.getByID(p.getID()));
        }
        return result;
    }

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

    public PointND removeByID(int idTarget) throws IdDoesNotExistException {
        int idx = getIndexByID(idTarget);
        return this.remove(idx);
    }

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
     * Shallow copies a shell so that it does not point to any childern
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
     * Turns a shell into a PointSet object
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
     * Determines equality of shells based on if they represent the same tsp path
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

    public Shell reverse() {
        Shell result = new Shell();
        for (PointND p : this) {
            result.addFirst(p);
        }
        return result;
    }

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
                str += ", ";
            }
        }

        return str + "]";
    }

    public static String compareTo(Shell A, Shell B) {
        String str = "Shell A[";
        for (int i = 0; i < A.size() - 1; i++) {
            str += (i) + ", ";
        }
        str += A.size() - 1 + "]";

        str += "\nShell B[";
        for (int i = 0; i < B.size() - 1; i++) {
            str += (A.indexOf(B.get(i))) + ", ";
        }
        str += (A.indexOf(B.get(B.size() - 1))) + "]";

        return str;

    }

    @Override
    public boolean add(PointND e) {
        super.add(e);
        return true;

    }

    @Override
    public boolean addAll(Collection<? extends PointND> c) {
        super.addAll(c);
        return true;
    }

    public boolean addAllFirst(Collection<? extends PointND> c) {
        Object[] points = c.toArray();
        for (int i = points.length - 1; i >= 0; i--) {
            this.addFirst((PointND) points[i]);
        }
        return true;
    }

    public void addAfter(PointND contained, PointND insert) {
        super.add(this.indexOf(contained) + 1, insert);
    }

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

    public PointND getOppositeOutside(PointND endpoint) {
        assert (this.getLast().equals(endpoint) || this.getFirst().equals(endpoint)) : endpoint.getID();
        if (this.getLast().equals(endpoint)) {
            return this.getFirst();
        } else {
            return this.getLast();
        }
    }

    public boolean isEndpoint(PointND p) {
        return p.equals(this.getLast()) || p.equals(this.getFirst());
    }

    public boolean containsID(int id) {
        for (PointND pointND : this) {
            if (pointND.getID() == id) {
                return true;
            }
        }
        return false;
    }

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

    public PointND getNext(int i) {
        if (i + 1 >= this.size()) {
            return this.get(0);
        }
        return this.get(i + 1);
    }

    public PointND getPrev(int i) {
        if (i - 1 < 0) {
            return this.get(this.size() - 1);
        }
        return this.get(i - 1);
    }

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

    public boolean hasPoint(int id) {
        for (PointND p : this) {
            if (p.getID() == id) {
                return true;
            }
        }
        return false;
    }

    public void addAllInRange(Range r, Shell orgShell) {
        for (PointND p : orgShell) {
            if (r.hasPoint(p)) {
                this.add(p);
            }
        }
    }

    public ArrayList<PointND> getAllInRange(Range r) {
        ArrayList<PointND> points = new ArrayList<>();
        for (PointND p : this) {
            if (r.hasPoint(p)) {
                points.add(p);
            }
        }
        return points;
    }

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
                    if (delta2 < 0 && delta2 < -0.0000001) {
                        return new Pair<PointND, Pair<PointND, PointND>>(curr,
                                new Pair<PointND, PointND>(currD, nextD));
                    }
                }
            }
        }
        return null;
    }

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
