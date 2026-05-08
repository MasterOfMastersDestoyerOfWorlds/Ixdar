package ixdar.common.utils;

import java.util.ArrayList;
import java.util.Set;

import org.apache.commons.math3.util.Pair;

import ixdar.geometry.knot.Knot;
import ixdar.geometry.knot.Segment;

/**
 * Grab-bag of small static helpers used across the geometry/balancer code:
 * pretty-printing, knot-point marching around a knot's circular index, and
 * miscellaneous list/set conversions.
 */
public final class Utils {
    public static final String LBRACE = "[";
    public static final String RBRACE = "]";

    /**
     * Render a list of {@link Pair}s as {@code [Pair[a : b],...]}.
     *
     * @param <K> first-element type
     * @param <V> second-element type
     * @param pairs pairs to format
     * @return bracketed comma-separated string
     */
    public static <K, V> String pairsToString(ArrayList<Pair<K, V>> pairs) {
        String str = LBRACE;
        for (Pair<K, V> p : pairs) {
            str += Utils.pairToString(p) + ",";
        }
        str += RBRACE;
        return str;

    }

    /**
     * Render a single {@link Pair} as {@code Pair[first : second]}.
     *
     * @param <K> first-element type
     * @param <V> second-element type
     * @param pair pair to format
     * @return formatted string
     */
    public static <K, V> String pairToString(Pair<K, V> pair) {
        return "Pair[" + pair.getFirst() + " : " + pair.getSecond() + RBRACE;

    }

    /**
     * Render an array as {@code [a, b, ...]} (or {@code "null"} when null).
     *
     * @param <K> element type
     * @param array array to format
     * @return bracketed comma-separated string
     */
    public static <K> String printArray(K[] array) {
        if (array == null) {
            return "null";
        }
        String str = LBRACE;
        for (K entry : array) {
            str += entry + ", ";
        }
        str += RBRACE;
        return str;
    }

    /**
     * March around {@code knot.knotPoints} starting at {@code vp2} toward
     * {@code kp2} and return the first adjacent pair whose far end lies in
     * {@code potentialNeighbors}, or {@code null} if no such pair exists in one
     * full revolution.
     *
     * @param knot knot whose circular point list is walked
     * @param kp2 reference knot point used to pick the march direction
     * @param vp2 starting knot point
     * @param potentialNeighbors candidate "next" knot points to stop at
     * @return adjacent (current, next) pair or {@code null}
     */
    public static Pair<Knot, Knot> marchLookup(Knot knot, Knot kp2, Knot vp2,
            ArrayList<Knot> potentialNeighbors) {
        int idx = knot.knotPoints.indexOf(vp2);
        int idx2 = knot.knotPoints.indexOf(kp2);
        int marchDirection = idx2 - idx < 0 ? -1 : 1;
        if (idx == 0 && idx2 == knot.knotPoints.size() - 1) {
            marchDirection = -1;
        }
        if (idx2 == 0 && idx == knot.knotPoints.size() - 1) {
            marchDirection = 1;
        }
        int totalIter = 0;
        while (true) {
            Knot k1 = knot.knotPoints.get(idx);
            int next = idx + marchDirection;
            if (marchDirection < 0 && next < 0) {
                next = knot.knotPoints.size() - 1;
            } else if (marchDirection > 0 && next >= knot.knotPoints.size()) {
                next = 0;
            }
            Knot k2 = knot.knotPoints.get(next);
            if (potentialNeighbors.contains(k2)) {
                return new Pair<>(k1, k2);
            }
            idx = next;
            totalIter++;
            if (totalIter > knot.knotPoints.size()) {
                return null;
            }
        }
    }

    /**
     * March around {@code knot} starting from {@code startPoint} away from
     * {@code awaySegment} and check whether {@code target} is reached before
     * crossing into {@code subKnot}.
     *
     * @param startPoint starting knot point
     * @param awaySegment segment whose direction we move away from
     * @param target knot point we are looking for
     * @param knot knot whose circular point list is walked
     * @param subKnot knot whose membership terminates the march early
     * @return {@code true} if {@code target} was hit before entering {@code subKnot}
     */
    public static boolean marchContains(Knot startPoint, Segment awaySegment, Knot target, Knot knot,
            Knot subKnot) {
        int idx = knot.knotPoints.indexOf(startPoint);
        int idx2 = knot.knotPoints.indexOf(awaySegment.getOther(startPoint));
        int marchDirection = idx2 - idx < 0 ? -1 : 1;
        if (idx == 0 && idx2 == knot.knotPoints.size() - 1) {
            marchDirection = -1;
        }
        if (idx2 == 0 && idx == knot.knotPoints.size() - 1) {
            marchDirection = 1;
        }
        marchDirection = -marchDirection;
        int totalIter = 0;
        while (true) {
            int next = idx + marchDirection;
            if (marchDirection < 0 && next < 0) {
                next = knot.knotPoints.size() - 1;
            } else if (marchDirection > 0 && next >= knot.knotPoints.size()) {
                next = 0;
            }
            Knot k2 = knot.knotPoints.get(next);
            if (subKnot.contains(k2)) {
                return false;
            }
            if (k2.equals(target)) {
                return true;
            }
            idx = next;
            totalIter++;
            if (totalIter > knot.knotPoints.size()) {
                return false;

            }
        }
    }

    /**
     * March around {@code knot} starting at {@code start} away from {@code away}
     * and return the first adjacent pair lying on {@code cutSegment2}.
     *
     * @param knot knot whose circular point list is walked
     * @param start starting knot point
     * @param away knot point used to pick the (then reversed) march direction
     * @param cutSegment2 segment whose endpoints define the stop condition
     * @return adjacent (current, next) pair on {@code cutSegment2}, or {@code null}
     *         if {@code knot} does not contain that segment
     */
    public static Pair<Knot, Knot> marchLookup(Knot knot, Knot start, Knot away,
            Segment cutSegment2) {
        if (!knot.hasSegment(cutSegment2)) {
            return null;
        }
        int idx = knot.knotPoints.indexOf(start);
        int idx2 = knot.knotPoints.indexOf(away);

        int marchDirection = idx2 - idx < 0 ? -1 : 1;
        if (idx == 0 && idx2 == knot.knotPoints.size() - 1) {
            marchDirection = -1;
        }
        if (idx2 == 0 && idx == knot.knotPoints.size() - 1) {
            marchDirection = 1;
        }
        int next = idx + marchDirection;
        if (marchDirection < 0 && next < 0) {
            next = knot.knotPoints.size() - 1;
        } else if (marchDirection > 0 && next >= knot.knotPoints.size()) {
            next = 0;
        }
        marchDirection = -marchDirection;
        Knot curr = knot.knotPoints.get(idx);
        while (true) {
            curr = knot.knotPoints.get(idx);
            next = idx + marchDirection;
            if (marchDirection < 0 && next < 0) {
                next = knot.knotPoints.size() - 1;
            } else if (marchDirection > 0 && next >= knot.knotPoints.size()) {
                next = 0;
            }
            Knot nextp = knot.knotPoints.get(next);

            if (cutSegment2.contains(nextp) && cutSegment2.contains(curr)) {
                return new Pair<>(curr, nextp);
            }
            idx = next;
        }
    }

    /**
     * Test whether placing two cuts (cutp1-knotp1 and cutp2-knotp2) along
     * {@code knotList} would leave a segment of the run isolated/orphaned by
     * comparing the relative ordering of the four indices.
     *
     * @param cutp1 first cut endpoint
     * @param knotp1 first cut's knot-side endpoint
     * @param cutp2 second cut endpoint
     * @param knotp2 second cut's knot-side endpoint
     * @param knotList ordered run of knot points to check against
     * @return {@code true} if the proposed cuts would orphan part of the run
     */
    public static boolean wouldOrphan(Knot cutp1, Knot knotp1, Knot cutp2, Knot knotp2,
            ArrayList<Knot> knotList) {
        int cp1 = knotList.indexOf(cutp1);
        int kp1 = knotList.indexOf(knotp1);

        int cp2 = knotList.indexOf(cutp2);
        int kp2 = knotList.indexOf(knotp2);

        if ((cp1 > kp1 && cp1 < kp2 && cp2 > kp1 && cp2 < kp2)
                ||
                (cp1 > kp2 && cp1 < kp1 && cp2 > kp2 && cp2 < kp1)
                ||
                (kp1 > cp2 && kp1 < cp1 && kp2 > cp2 && kp2 < cp1)
                ||
                (kp1 > cp1 && kp1 < cp2 && kp2 > cp1 && kp2 < cp2)
                ||
                (kp1 > cp1 && kp1 > cp2 && kp2 > cp1 && kp2 > cp2)
                ||
                (kp1 < cp1 && kp1 < cp2 && kp2 < cp1 && kp2 < cp2)) {
            return true;
        }

        return false;
    }

    /**
     * March around {@code knot} away from {@code awaySegment} and return
     * {@code true} if {@code untilSegment} is reached before encountering both
     * {@code kp1} and {@code kp2} (which would indicate two knot-points have
     * been seen in the marched arc).
     *
     * @param startPoint starting knot point
     * @param awaySegment segment whose direction we move away from
     * @param untilSegment segment whose appearance terminates the march with success
     * @param kp1 first knot-point sentinel
     * @param kp2 second knot-point sentinel
     * @param knot knot whose circular point list is walked
     * @return {@code true} if {@code untilSegment} reached before both sentinels
     */
    public static boolean marchUntilHasOneKnotPoint(Knot startPoint, Segment awaySegment,
            Segment untilSegment, Knot kp1, Knot kp2, Knot knot) {
        int idx = knot.knotPoints.indexOf(startPoint);
        int idx2 = knot.knotPoints.indexOf(awaySegment.getOther(startPoint));
        int marchDirection = idx2 - idx < 0 ? -1 : 1;
        if (idx == 0 && idx2 == knot.knotPoints.size() - 1) {
            marchDirection = -1;
        }
        if (idx2 == 0 && idx == knot.knotPoints.size() - 1) {
            marchDirection = 1;
        }
        marchDirection = -marchDirection;
        int totalIter = 0;
        int numKnotPoints = 0;
        Knot first = knot.knotPoints.get(idx);
        if (first.equals(kp1) || first.equals(kp2)) {
            numKnotPoints++;
        }
        while (true) {
            Knot k1 = knot.knotPoints.get(idx);
            int next = idx + marchDirection;
            if (marchDirection < 0 && next < 0) {
                next = knot.knotPoints.size() - 1;
            } else if (marchDirection > 0 && next >= knot.knotPoints.size()) {
                next = 0;
            }
            Knot k2 = knot.knotPoints.get(next);
            if (knot.getSegment(k1, k2).equals(untilSegment)) {
                return true;
            }
            if (k2.equals(kp1)) {
                numKnotPoints++;
            }
            if (k2.equals(kp2)) {
                numKnotPoints++;
            }
            if (numKnotPoints >= 2) {
                return false;
            }
            idx = next;
            totalIter++;
            if (totalIter > knot.knotPoints.size()) {
                return false;
            }
        }
    }

    /**
     * Copy an {@link ArrayList} of segments into a new array.
     *
     * @param first source list
     * @return new array of the same length and contents
     */
    public static Segment[] toSegmentArray(ArrayList<Segment> first) {
        Segment[] array = new Segment[first.size()];
        for (int i = 0; i < first.size(); i++) {
            array[i] = first.get(i);
        }
        return array;
    }

    /**
     * Copy a {@link Set} of segments into a new array (iteration order preserved).
     *
     * @param first source set
     * @return new array of the same length and contents
     */
    public static Segment[] toSegmentArray(Set<Segment> first) {
        Segment[] array = new Segment[first.size()];
        int i = 0;
        for (Segment s : first) {
            array[i] = s;
            i++;
        }
        return array;
    }

    /**
     * {@link Set#contains}-style probe that uses {@link Segment#equals} on every
     * element (useful when set hashing cannot be relied on).
     *
     * @param matches set of segments to scan
     * @param matchSegmentAcrossFinal segment to test for membership
     * @return {@code true} if an equal segment is present
     */
    public static boolean setContains(Set<Segment> matches, Segment matchSegmentAcrossFinal) {
        for (Segment segment : matches) {
            if (segment.equals(matchSegmentAcrossFinal)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Convert a closed ring of segments to its ordered list of shared knot
     * endpoints (each entry is the overlap between consecutive segments).
     *
     * @param segments ordered ring of segments
     * @return ordered list of shared knot points
     */
    public static ArrayList<Knot> segmentListToPath(ArrayList<Segment> segments) {
        ArrayList<Knot> result = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            int prev = i - 1 < 0 ? segments.size() - 1 : i - 1;
            Segment s1 = segments.get(prev);
            Segment s2 = segments.get(i);
            Knot p = s1.getOverlap(s2);
            result.add(p);
        }
        return result;
    }

    /**
     * Find the segment that connects {@code otherNeighborPoint} to its neighbor
     * in {@code superKnot} that lies inside the sub-knot {@code knot}.
     *
     * @param otherNeighborPoint pivot knot point
     * @param knot sub-knot containing one of the neighbors
     * @param superKnot enclosing knot whose flattened point list defines neighbors
     * @return closest segment toward the matching neighbor, or {@code null} if neither neighbor lies in {@code knot}
     */
    public static Segment getSegmentInSubKnot(Knot otherNeighborPoint, Knot knot, Knot superKnot) {
        int idx = superKnot.knotPointsFlattened.indexOf(otherNeighborPoint);
        Knot prev = superKnot.getPrev(idx);
        Knot next = superKnot.getNext(idx);
        if (knot.contains(prev)) {
            return otherNeighborPoint.getClosestSegment(prev, null);
        } else if (knot.contains(next)) {
            return otherNeighborPoint.getClosestSegment(next, null);
        }
        return null;

    }

    /**
     * Test whether any knot in {@code runList} has the given ID.
     *
     * @param runList list of knots to scan
     * @param i ID to search for
     * @return {@code true} if a knot with {@code id == i} exists in the list
     */
    public static boolean hasKnot(ArrayList<Knot> runList, int i) {
        for (Knot vp : runList) {
            if (vp.id == i) {
                return true;
            }
        }
        return false;
    }
}
