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
     * Empty shell with an empty point map; populated later via {@link #initShell(DistanceMatrix)}
     * or by adding points directly.
     */
    public Shell() {
        pointMap = new HashMap<>();
    }

    /**
     * Initializes a new shell with no parent or child; a blank slate.
     *
     * @param points points to seed the shell with (in order)
     */

    public Shell(PointND... points) {
        for (int i = 0; i < points.length; i++) {
            this.add(points[i]);
        }
    }


    /**
     * Build a shell containing every point in the supplied set, preserving the iteration order.
     *
     * @param points point set to copy into the shell
     */
    public Shell(PointSet points) {
        for (int i = 0; i < points.size(); i++) {
            this.add(points.get(i));
        }
    }

    /**
     * Wrap each point of {@code distanceMatrix} in a {@link Knot}, building each knot's
     * sorted segment list and lookup keyed by every other point in the matrix.
     * Does not populate the shell-level segment fields.
     *
     * @param distanceMatrix supplies the point set and pairwise distances
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
     * Stub TSP entry point: initializes points and asks the {@link KnotEngine} to build knots.
     * Currently returns an empty shell and intentionally divides by zero when more than one
     * knot remains, signalling that the recursion limit was reached.
     *
     * @param A unused candidate shell (parameter retained for API compatibility)
     * @param distanceMatrix pairwise distances driving knot creation
     * @return placeholder result shell
     * @throws SegmentBalanceException propagated from {@link KnotEngine#createKnots}
     * @throws BalancerException propagated from {@link KnotEngine#createKnots}
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
     * Build the per-knot segment lists and the shell-level {@code sortedSegments} /
     * {@code segmentLookup} containing each unordered pair once. Sorts both segment lists.
     *
     * @param distanceMatrix supplies the points and pairwise distances
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
     * Initialize the shell from {@code distanceMatrix} and run the knot engine to a fixed
     * recursion depth.
     *
     * @param A unused candidate shell (parameter retained for API compatibility)
     * @param distanceMatrix pairwise distances driving knot creation
     * @param layers maximum recursion depth passed through to the {@link KnotEngine}
     * @return knots produced by the engine
     * @throws MultipleCyclesFoundException propagated from the knot engine
     */
    public ArrayList<Knot> slowSolve(Shell A, DistanceMatrix distanceMatrix, int layers)
            throws MultipleCyclesFoundException {
        initShell(distanceMatrix);
        ArrayList<Knot> knots = knotEngine.createKnots(layers, this.sortedSegments);
        return knots;
    }

    /**
     * Record the first (smallest) knot id observed for each point. Lazily allocates the lookup
     * table on first call; never overwrites an existing assignment.
     *
     * @param knotNew newly formed knot whose flattened points should be claimed if unclaimed
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
     * Record the smallest knot containing each unordered pair of points. Lazily allocates the
     * symmetric lookup table on first call; never overwrites an existing pair.
     *
     * @param knotNew newly formed knot whose pairs of flattened points should be tagged if untagged
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
     * Solve a Hamiltonian path between fixed endpoints by reducing to a TSP cycle: appends a
     * dummy node connected only to {@code first} and {@code last}, runs {@link #tspSolve}, then
     * rotates the result so it begins at {@code first}.
     *
     * @param first required start point of the path
     * @param last required end point of the path
     * @param A intermediate points to include between the endpoints
     * @param d distance matrix that already knows the relevant pairwise distances
     * @return shell representing the open path from {@code first} to {@code last}
     * @throws SegmentBalanceException propagated from {@link #tspSolve}
     * @throws BalancerException propagated from {@link #tspSolve}
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
     * Length of the open path through the shell, i.e. {@link #getLength()} without the
     * closing edge from the last point back to the first.
     *
     * @return summed pairwise distances along the open path
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
     * @param p point in the shell whose neighbour edges are summed
     * @param d distance source for the lookups
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
     * @param p point whose immediate neighbours are inspected
     * @param d distance source for the lookup
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
     * Build a new shell by walking {@code A} and substituting each point with the one in
     * {@code ps} that has the same id.
     *
     * @param A template shell whose order and ids are copied
     * @param ps source of the replacement {@link PointND} instances, indexed by id
     * @return shell with the same order as {@code A} but pointing at {@code ps}'s objects
     */
    public static Shell replaceByID(Shell A, PointSet ps) {
        Shell result = new Shell();
        for (PointND p : A) {
            result.add(ps.getByID(p.getID()));
        }
        return result;
    }

    /**
     * Find the position in this shell of the point with the given id.
     *
     * @param idTarget id to locate
     * @return zero-based index into the shell
     * @throws IdDoesNotExistException if no point in the shell has that id
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
     * Remove and return the point with the given id.
     *
     * @param idTarget id of the point to remove
     * @return the removed point
     * @throws IdDoesNotExistException if no point in the shell has that id
     */
    public PointND removeByID(int idTarget) throws IdDoesNotExistException {
        int idx = getIndexByID(idTarget);
        return this.remove(idx);
    }

    /**
     * Drop any point not in {@code ps} (treated as the dummy boundary) and rotate the remaining
     * points so the segment that the dummy bridged is split, with the {@code after} group
     * placed before the {@code before} group.
     *
     * @param ps the set of real points to retain
     * @return the rotated shell containing exactly {@code size() - 1} points
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
     * Rotate the shell in place so that the edge between {@code p1} and {@code p2} (in either
     * direction) is the wrap-around edge of the underlying linked list.
     *
     * @param p1 one endpoint of the edge to put at the seam
     * @param p2 the other endpoint of that edge
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
     * Build a new shell with this shell's points in reversed order.
     *
     * @return reversed shell (does not mutate this one)
     */
    public Shell reverse() {
        Shell result = new Shell();
        for (PointND p : this) {
            result.addFirst(p);
        }
        return result;
    }

    /**
     * Comma-separated rendering of the shell. Points with a real id print as their id, dummy
     * points (id {@code -1}) fall back to their full {@link PointND#toString()}.
     *
     * @return debug-friendly bracketed listing
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
     * Render two shells side by side using {@code A}'s indices as the canonical ordering. Used
     * for debugging which permutation of the same point set a second shell represents.
     *
     * @param A reference shell whose positions define the index labels
     * @param B comparison shell, expected to contain the same points in some order
     * @return two-line bracketed listing
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
     * Append a point to the end of the shell (delegates to {@link LinkedList#add(Object)}).
     *
     * @param e point to append
     * @return always {@code true}
     */
    @Override
    public boolean add(PointND e) {
        super.add(e);
        return true;

    }

    /**
     * Append all points of {@code c} to the end of the shell, preserving iteration order.
     *
     * @param c points to append
     * @return always {@code true}
     */
    @Override
    public boolean addAll(Collection<? extends PointND> c) {
        super.addAll(c);
        return true;
    }

    /**
     * Prepend all points of {@code c} to the front of the shell, preserving their order.
     *
     * @param c points to prepend
     * @return always {@code true}
     */
    public boolean addAllFirst(Collection<? extends PointND> c) {
        Object[] points = c.toArray();
        for (int i = points.length - 1; i >= 0; i--) {
            this.addFirst((PointND) points[i]);
        }
        return true;
    }

    /**
     * Insert {@code insert} immediately after the existing point {@code contained}.
     *
     * @param contained anchor point that must already be in the shell
     * @param insert point to insert directly after the anchor
     */
    public void addAfter(PointND contained, PointND insert) {
        super.add(this.indexOf(contained) + 1, insert);
    }

    /**
     * Insert {@code insert} immediately after {@code contained} (which must be one of the
     * shell's endpoints) and rotate so {@code insert} becomes the new tail or head, keeping the
     * insertion on the outer edge of the cycle.
     *
     * @param contained current first or last point of the shell
     * @param insert point to splice in next to that endpoint
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
     * Splice {@code other} onto this shell at the endpoint {@code contained}, joined via the
     * endpoint of {@code other} that equals {@code connector}. Reverses {@code other} when its
     * orientation does not align with the join.
     *
     * @param contained the endpoint of this shell to attach to (must be first or last)
     * @param connector the endpoint of {@code other} that should meet {@code contained}
     * @param other the shell to merge in
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
     * Return whichever endpoint of the shell is not {@code endpoint}.
     *
     * @param endpoint the first or last point of the shell
     * @return the opposite endpoint
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
     * Test whether {@code p} is the first or last point of the shell.
     *
     * @param p point to check
     * @return {@code true} if {@code p} sits at either end
     */
    public boolean isEndpoint(PointND p) {
        return p.equals(this.getLast()) || p.equals(this.getFirst());
    }

    /**
     * Linear search for a point with the given id.
     *
     * @param id id to search for
     * @return {@code true} if any point in the shell has that id
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
     * Test whether the shell contains both endpoints of {@code r}. Does not check the
     * interior ids.
     *
     * @param r range whose start and end ids must both be present
     * @return {@code true} when both endpoint ids appear in the shell
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
     * Wrap-around accessor for the point one position after index {@code i}.
     *
     * @param i base index
     * @return point at {@code (i + 1) mod size()}
     */
    public PointND getNext(int i) {
        if (i + 1 >= this.size()) {
            return this.get(0);
        }
        return this.get(i + 1);
    }

    /**
     * Wrap-around accessor for the point one position before index {@code i}.
     *
     * @param i base index
     * @return point at {@code (i - 1 + size()) mod size()}
     */
    public PointND getPrev(int i) {
        if (i - 1 < 0) {
            return this.get(this.size() - 1);
        }
        return this.get(i - 1);
    }

    /**
     * Remove the points in {@code idTarget} from the shell and reinsert them immediately after
     * {@code idDest}. Reverses the moved block when {@code idTarget} is reversed.
     *
     * @param idTarget inclusive id range describing the block to relocate
     * @param idDest id of the point the moved block lands directly after
     * @throws IdDoesNotExistException if either {@code idTarget}'s endpoints or {@code idDest}
     *         is not in the shell
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
     * Remove the points in {@code idTarget} from the shell and reinsert them immediately
     * before {@code idDest}.
     *
     * @param idTarget inclusive id range describing the block to relocate
     * @param idDest id of the point the moved block lands directly before
     * @throws IdDoesNotExistException if either {@code idTarget}'s endpoints or {@code idDest}
     *         is not in the shell
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
     * Remove the points in {@code idTarget} and reinsert them between two adjacent points
     * {@code idDest1} and {@code idDest2}. The two destination points must be neighbours in
     * the shell (or the wrap-around pair).
     *
     * @param idTarget inclusive id range to relocate
     * @param idDest1 first of the two neighbouring destination ids
     * @param idDest2 second of the two neighbouring destination ids
     * @throws IdDoesNotExistException if any of the referenced ids is missing from the shell
     * @throws IdsNotConcurrentException if {@code idDest1} and {@code idDest2} are not neighbours
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
     * Linear search by id (alias of {@link #containsID(int)}).
     *
     * @param id id to look for
     * @return {@code true} when a point with that id is present
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
     * Append every point of {@code orgShell} whose id lies in {@code r} to this shell, in
     * the order they appear in {@code orgShell}.
     *
     * @param r id range used to filter points
     * @param orgShell source shell to scan
     */
    public void addAllInRange(Range r, Shell orgShell) {
        for (PointND p : orgShell) {
            if (r.hasPoint(p)) {
                this.add(p);
            }
        }
    }

    /**
     * Collect all points whose id lies inside {@code r}, in shell order, without modifying
     * this shell.
     *
     * @param r id range used to filter points
     * @return new list of matching points
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
     * Remove all points whose id lies inside {@code r} and return them in the order they
     * appeared in this shell.
     *
     * @param r id range describing which points to extract
     * @return new list containing the removed points
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
     * Look for a 2-opt-style improvement: a point that, if removed and reinserted on a
     * different non-adjacent edge, would shorten the cycle by more than {@code 1e-7}.
     *
     * @return {@code (curr, (currD, nextD))} describing a beneficial move (relocate
     *         {@code curr} between {@code currD} and {@code nextD}), or {@code null} when no
     *         such move exists
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
     * Lazily build (and cache) one debug-tooltip {@link HyperString} per point in the shell.
     * Each tooltip shows the point's id, the colour {@code c}, and {@link PointND#toString()}
     * as the body text.
     *
     * @param c tooltip accent colour
     * @return cached list of hyperstrings, one per point in the shell
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
