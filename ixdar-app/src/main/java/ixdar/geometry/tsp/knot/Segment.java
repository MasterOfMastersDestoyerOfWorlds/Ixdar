package ixdar.geometry.tsp.knot;

import java.util.ArrayList;

import org.apache.commons.math3.geometry.euclidean.twod.Vector2D;
import org.joml.Vector2d;
import org.joml.Vector2f;

import ixdar.geometry.tsp.PointSetPath;
import ixdar.geometry.tsp.points.PointND;
import ixdar.geometry.tsp.shell.DistanceMatrix;
import ixdar.graphics.render.color.Color;
import ixdar.gui.ui.actions.Action;
import ixdar.gui.ui.text.HyperString;

public class Segment implements Comparable<Segment> {
    public static final double HIT_TEST_WIDTH_RATIO = 0.2;
    public Knot first;
    public Knot last;
    public double distance;
    public long id;

    /**
     * Build a segment between two knots with an explicit distance. The
     * Cantor-pair-style {@link #idTransform(long, long)} of the endpoints is
     * stored as {@link #id}.
     *
     * @param first one endpoint knot
     * @param last the other endpoint knot
     * @param distance edge length to associate with this segment
     */
    public Segment(Knot first, Knot last, double distance) {
        this.first = first;
        this.last = last;
        this.distance = distance;
        long a = first.id;
        long b = last.id;
        id = idTransform(a, b);
    }

    /**
     * Build a segment between two knots, looking up the distance from a
     * {@link DistanceMatrix} so callers can reuse cached point-to-point costs.
     *
     * @param knot1 one endpoint knot
     * @param knot2 the other endpoint knot
     * @param distanceMatrix metric source consulted for the segment length
     */
    public Segment(Knot knot1, Knot knot2, DistanceMatrix distanceMatrix) {
        this.first = knot1;
        this.last = knot2;
        this.distance = distanceMatrix.getDistance(knot1.p, knot2.p);
        long a = knot1.id;
        long b = knot2.id;
        id = idTransform(a, b);
    }

    /**
     * Return the endpoint of this segment that is not {@code vp}, or
     * {@code null} when {@code vp} is neither endpoint.
     *
     * @param vp one endpoint of this segment
     * @return the opposite endpoint, or {@code null} for non-incident inputs
     */
    public Knot getOther(Knot vp) {
        if (vp.equals(first)) {
            return last;
        }
        if (vp.equals(last)) {
            return first;
        }
        return null;
    }

    /**
     * Like {@link #getOther(Knot)} but accepts a non-singleton {@code vp}: the
     * matching endpoint is found via {@code vp.knotPointsFlattened} before its
     * opposite is returned.
     *
     * @param vp endpoint, possibly a parent knot
     * @return the opposite endpoint
     */
    public Knot getOtherKnot(Knot vp) {
        if (!vp.isSingleton()) {
            Knot knot = (Knot) vp;
            Knot p = this.getKnotPoint(knot.knotPointsFlattened);
            return this.getOther(p);
        } else {
            return this.getOther(vp);
        }
    }

    /**
     * Whether {@code vp} is one of this segment's two endpoints.
     *
     * @param vp candidate knot
     * @return {@code true} when {@code vp} equals {@link #first} or {@link #last}
     */
    public boolean contains(Knot vp) {
        return first.equals(vp) || last.equals(vp);
    }

    /**
     * Whether any element of {@code vp} is an endpoint of this segment.
     *
     * @param vp candidate knot array
     * @return {@code true} if at least one entry in {@code vp} is an endpoint
     */
    public boolean contains(Knot[] vp) {
        boolean contains = false;
        for (int i = 0; i < vp.length; i++) {
            if (first.equals(vp[i]) || last.equals(vp[i])) {
                contains = true;
            }
        }
        return contains;
    }

    /**
     * Return whichever of {@link #first}/{@link #last} appears in
     * {@code knotPointsFlattened}, prioritizing {@code first}.
     *
     * @param knotPointsFlattened the membership list to query
     * @return the matching endpoint, or {@code null} if neither belongs to the list
     */
    public Knot getKnotPoint(ArrayList<Knot> knotPointsFlattened) {
        if (knotPointsFlattened.contains(first)) {
            return first;
        }
        if (knotPointsFlattened.contains(last)) {
            return last;
        }
        return null;
    }

    /**
     * Order segments primarily by ascending {@link #distance}, breaking ties
     * by {@link #id}. Non-{@code Segment} inputs sort first ({@code -1}).
     *
     * @param o segment to compare against
     * @return -1, 0 or 1 per {@link Comparable#compareTo}
     */
    @Override
    public int compareTo(Segment o) {
        if (o.getClass() == Segment.class) {
            Segment s = (Segment) o;
            if (s.distance < this.distance) {
                return 1;
            } else if (s.distance > this.distance) {
                return -1;
            } else {
                if (s.id < this.id) {
                    return 1;
                } else if (s.id > this.id) {
                    return -1;
                }
                return 0;
            }
        }
        return -1;
    }

    /**
     * Two segments are equal when they share the same {@link #id}, i.e. the
     * same unordered pair of endpoint ids.
     *
     * @param obj candidate object
     * @return {@code true} if {@code obj} is a {@code Segment} with matching id
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (obj.getClass() != Segment.class) {
            return false;
        } else {
            Segment s2 = (Segment) obj;
            return this.id == s2.id;
            // return (this.first.id == s2.first.id && this.last.id == s2.last.id)
            // || (this.first.id == s2.last.id && this.last.id == s2.first.id);
        }
    }

    /**
     * Whether this segment shares exactly one endpoint with {@code cutSegment2}.
     *
     * @param cutSegment2 the other segment
     * @return {@code true} for "T"-style overlaps where exactly one endpoint coincides
     */
    public boolean partialOverlaps(Segment cutSegment2) {
        if ((cutSegment2.contains(first) && !cutSegment2.contains(last))
                || (cutSegment2.contains(last) && !cutSegment2.contains(first))) {
            return true;
        }
        return false;
    }

    /**
     * Whether this segment shares at least one endpoint with {@code cutSegment2}.
     *
     * @param cutSegment2 the other segment
     * @return {@code true} when the two segments are incident at an endpoint
     */
    public boolean intersects(Segment cutSegment2) {
        if (cutSegment2.contains(first) || cutSegment2.contains(last)) {
            return true;
        }
        return false;
    }

    /**
     * Return the endpoint that is shared with {@code other}, prioritizing
     * {@link #first}.
     *
     * @param other another segment
     * @return the shared endpoint, or {@code null} if none is shared
     */
    public Knot getOverlap(Segment other) {
        if (other.contains(first)) {
            return first;
        } else if (other.contains(last)) {
            return last;
        }
        return null;
    }

    /**
     * Return the first knot in {@code neighbors} that is also an endpoint of
     * this segment.
     *
     * @param neighbors candidate knots to scan
     * @return the matching endpoint, or {@code null} if none of {@code neighbors} is incident
     */
    public Knot containsAny(ArrayList<Knot> neighbors) {
        for (Knot vp : neighbors) {
            if (this.contains(vp)) {
                return vp;
            }
        }
        return null;
    }

    /**
     * Whether this segment is degenerate, i.e. starts and ends at the same knot.
     *
     * @return {@code true} when {@link #first} equals {@link #last}
     */
    public boolean isDegenerate() {
        return first.equals(last);
    }

    /**
     * Whether this segment connects exactly the endpoints with ids {@code i}
     * and {@code j}, in either order.
     *
     * @param i first endpoint id
     * @param j second endpoint id
     * @return {@code true} when {@code {first.id, last.id} == {i, j}}
     */
    public boolean hasPoints(int i, int j) {
        if (first.id == i || first.id == j) {
            if (last.id == i || last.id == j) {
                return true;
            }
        }
        return false;
    }

    /**
     * Smaller of two endpoint ids, used as the canonical "first" id for an
     * undirected pair.
     *
     * @param firstInnerNeighbor one endpoint
     * @param k2 other endpoint
     * @return {@code min(firstInnerNeighbor.id, k2.id)}
     */
    public static int getFirstOrderId(Knot firstInnerNeighbor, Knot k2) {
        int first = firstInnerNeighbor.id < k2.id ? firstInnerNeighbor.id : k2.id;
        return first;
    }

    /**
     * Larger of two endpoint ids, the companion to
     * {@link #getFirstOrderId(Knot, Knot)}.
     *
     * @param firstInnerNeighbor one endpoint
     * @param k2 other endpoint
     * @return {@code max(firstInnerNeighbor.id, k2.id)}
     */
    public static int getLastOrderId(Knot firstInnerNeighbor, Knot k2) {
        return firstInnerNeighbor.id < k2.id ? k2.id : firstInnerNeighbor.id;
    }

    /**
     * Smaller of {@code s}'s endpoint ids.
     *
     * @param s segment to inspect
     * @return {@code min(s.first.id, s.last.id)}
     */
    public static int getFirstOrderId(Segment s) {
        return getFirstOrderId(s.first, s.last);
    }

    /**
     * Larger of {@code s}'s endpoint ids.
     *
     * @param s segment to inspect
     * @return {@code max(s.first.id, s.last.id)}
     */
    public static int getLastOrderId(Segment s) {
        return getLastOrderId(s.first, s.last);
    }

    /**
     * Hash this segment by the product of its endpoint ids; matched to
     * {@link #equals(Object)}'s id-based comparison.
     *
     * @return {@code first.id * last.id}
     */
    @Override
    public int hashCode() {
        return first.id * last.id; // or any other constant
    }

    /**
     * Whether either endpoint has the supplied id.
     *
     * @param i candidate endpoint id
     * @return {@code true} when {@code first.id == i} or {@code last.id == i}
     */
    public boolean hasPoint(Integer i) {
        if (first.id == i || last.id == i) {
            return true;
        }
        return false;
    }

    /**
     * Return whichever endpoint has the supplied id, or {@code null} when
     * neither matches.
     *
     * @param i candidate endpoint id
     * @return matching endpoint knot, or {@code null}
     */
    public Knot getPoint(Integer i) {
        if (first.id == i) {
            return first;
        }
        if (last.id == i) {
            return last;
        }
        return null;
    }

    /**
     * Compute an undirected ("Szudzik-pair") id key from a segment's
     * endpoints. Order-independent; matches {@link #idTransform(long, long)}.
     *
     * @param s segment whose endpoints supply {@code (a, b)}
     * @return packed long key
     */
    public static long idTransform(Segment s) {
        long a = s.first.id;
        long b = s.last.id;
        return a >= b ? a * a + a + b : b + a + b * b;
    }

    /**
     * Order-independent pairing function over two long ids; the same value is
     * produced regardless of argument order.
     *
     * @param a first id
     * @param b second id
     * @return packed long key
     */
    public static long idTransform(long a, long b) {
        return a >= b ? a * a + a + b : b + a + b * b;
    }

    /**
     * Cantor-pair id key over a segment's endpoints, sensitive to the
     * {@code first}/{@code last} order via the trailing {@code +b} term.
     *
     * @param s segment whose endpoints supply {@code (a, b)}
     * @return packed long key
     */
    public static long idTransformOrdered(Segment s) {
        long a = s.first.id;
        long b = s.last.id;
        return (a + b) * (a + b + 1) / 2 + b;
    }

    /**
     * Cantor-pair id key for the directed pair (other endpoint of
     * {@code cutSegment} at the {@code knotPoint} side).
     *
     * @param cutSegment segment whose other endpoint supplies {@code a}
     * @param knotPoint endpoint that supplies {@code b}
     * @return packed long key
     */
    public static long idTransformOrdered(Segment cutSegment, Knot knotPoint) {
        Knot cutPoint = cutSegment.getOther(knotPoint);
        long a = cutPoint.id;
        long b = knotPoint.id;
        return (a + b) * (a + b + 1) / 2 + b;
    }

    /**
     * Cantor-pair id key over a directed pair of long ids.
     *
     * @param a first id
     * @param b second id
     * @return packed long key
     */
    public static long idTransformOrdered(long a, long b) {
        return (a + b) * (a + b + 1) / 2 + b;
    }

    /**
     * Test whether the screen-space point {@code (x, y)} lies inside the
     * thickened bounding rectangle of this segment (width = 0.2 of length on
     * each side); when inside, return the perpendicular distance to the
     * segment's line.
     *
     * @param x screen-space X
     * @param y screen-space Y
     * @return perpendicular distance when the point is inside the band, or {@code -1.0} otherwise
     */
    public double boundContains(double x, double y) {
        PointND p1 = (first).p;
        PointND p2 = (last).p;
        double x1 = p1.getScreenX();
        double y1 = p1.getScreenY();
        double x2 = p2.getScreenX();
        double y2 = p2.getScreenY();
        double dx = x2 - x1;
        double dy = y2 - y1;
        double normalX = -dy;
        double normalY = dx;
        Vector2D firstVec = new Vector2D(x1, y1);
        Vector2D lastVec = new Vector2D(x2, y2);
        Vector2D normalUnitVector = new Vector2D(normalX, normalY);
        normalUnitVector = normalUnitVector.normalize().scalarMultiply(distance).scalarMultiply(HIT_TEST_WIDTH_RATIO);
        Vector2D tL = normalUnitVector.add(firstVec);
        Vector2D bL = firstVec.subtract(normalUnitVector);
        Vector2D tR = normalUnitVector.add(lastVec);
        Vector2D bR = lastVec.subtract(normalUnitVector);
        Vector2D pointVector = new Vector2D(x, y);

        if ((x - tL.getX()) * (tL.getY() - bL.getY()) + (y - tL.getY()) * (bL.getX() - tL.getX()) > 0
                && (x - bL.getX()) * (bL.getY() - bR.getY()) + (y - bL.getY()) * (bR.getX() - bL.getX()) > 0
                && (x - bR.getX()) * (bR.getY() - tR.getY()) + (y - bR.getY()) * (tR.getX() - bR.getX()) > 0
                && (x - tR.getX()) * (tR.getY() - tL.getY()) + (y - tR.getY()) * (tL.getX() - tR.getX()) > 0) {
            double result = Math.abs(
                    (y2 - y1) * pointVector.getX() - ((x2 - x1) * pointVector.getY()) + x2 * y1 - y2 * x1) / distance;
            return result;
        }
        return -1;

    }

    /**
     * Return the endpoint of this segment closer (in screen space) to
     * {@code (x, y)}.
     *
     * @param x screen-space X
     * @param y screen-space Y
     * @return {@link #first} or {@link #last}, whichever is nearer
     */
    public Knot closestPoint(double x, double y) {
        PointND p1 = (first).p;
        PointND p2 = (last).p;
        double x1 = p1.getScreenX();
        double y1 = p1.getScreenY();
        double x2 = p2.getScreenX();
        double y2 = p2.getScreenY();
        double distFirst = Math.sqrt((x1 - x) * (x1 - x) + (y1 - y) * (y1 - y));
        double distLast = Math.sqrt((x2 - x) * (x2 - x) + (y2 - y) * (y2 - y));
        if (distFirst < distLast) {
            return first;
        } else {
            return last;
        }

    }

    /**
     * Compact debug representation: {@code "Segment[<firstId>:<lastId>]"}.
     *
     * @return debug string
     */
    @Override
    public String toString() {
        return "Segment[" + first.id + ":" + last.id + "]";
    }

    /**
     * Like {@link #toString()} but without the leading {@code "Segment"} label,
     * yielding {@code "[<firstId>:<lastId>]"}.
     *
     * @return shorter debug string
     */
    public String toStringNoLabel() {
        return "[" + first.id + ":" + last.id + "]";
    }

    /**
     * Render this segment as an interactive {@link HyperString} with the
     * supplied color and a click handler that zooms to it. Distance label is
     * suppressed.
     *
     * @param pointSetPath the point set the segment joins, carrying the view's hover and focus callbacks
     * @param color label color
     * @param labelAsSegment whether to prefix the label with {@code "Segment"}
     * @return clickable hyperstring representation
     */
    public HyperString toHyperString(PointSetPath pointSetPath, Color color, boolean labelAsSegment) {
        return toHyperString(pointSetPath, color, labelAsSegment, false);
    }

    /**
     * Render this segment as an interactive {@link HyperString} with the
     * supplied color and a click handler that zooms to it.
     *
     * @param pointSetPath the point set the segment joins, carrying the view's hover and focus callbacks
     * @param color label color
     * @param labelAsSegment whether to prefix the label with {@code "Segment"}
     * @param labelDistance whether to append the segment distance to the label
     * @return clickable hyperstring representation
     */
    public HyperString toHyperString(PointSetPath pointSetPath, Color color, boolean labelAsSegment,
            boolean labelDistance) {
        HyperString h = new HyperString();
        Action clickAction = () -> pointSetPath.focusSegment.accept(this);
        String str = "";

        if (labelAsSegment) {
            str += "Segment";
        }
        str += "[" + first.id + ":" + last.id + "]";
        if (labelDistance) {
            str += ", " + String.format("%.2f", this.distance);
        }
        h.addHoverSegment(str, color, this, () -> pointSetPath.hoverSegment.accept(this, color),
                pointSetPath.clearHoverSegment,
                clickAction);
        return h;
    }

    /**
     * Read the point-space {@code (x, y)} of an endpoint, reaching into the
     * first flattened sub-knot when {@code k1} is a parent knot.
     *
     * @param k1 endpoint knot
     * @return point-space coordinates of {@code k1}
     */
    public Vector2f getPointSpaceVector(Knot k1) {
        Vector2d p1;
        if (!k1.isSingleton()) {
            p1 = (((Knot) k1).knotPoints.get(0)).p.toVector2d();
        } else {
            p1 = (k1).p.toVector2d();
        }
        return new Vector2f(p1);
    }
}