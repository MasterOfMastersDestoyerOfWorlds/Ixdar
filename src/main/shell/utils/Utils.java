package shell.utils;

import java.util.ArrayList;
import java.util.Set;

import org.apache.commons.math3.util.Pair;

import shell.knot.Knot;
import shell.knot.Segment;

public final class Utils {

    public static <K, V> String pairsToString(ArrayList<Pair<K, V>> pairs) {
        String str = "[";
        for (Pair<K, V> p : pairs) {
            str += Utils.pairToString(p) + ",";
        }
        str += "]";
        return str;

    }

    public static <K, V> String pairToString(Pair<K, V> pair) {
        return "Pair[" + pair.getFirst() + " : " + pair.getSecond() + "]";

    }

    public static <K> String printArray(K[] array) {
        if (array == null) {
            return "null";
        }
        String str = "[";
        for (K entry : array) {
            str += entry + ", ";
        }
        str += "]";
        return str;
    }

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

    public static Segment[] toSegmentArray(ArrayList<Segment> first) {
        Segment[] array = new Segment[first.size()];
        for (int i = 0; i < first.size(); i++) {
            array[i] = first.get(i);
        }
        return array;
    }

    public static Segment[] toSegmentArray(Set<Segment> first) {
        Segment[] array = new Segment[first.size()];
        int i = 0;
        for (Segment s : first) {
            array[i] = s;
            i++;
        }
        return array;
    }

    public static boolean setContains(Set<Segment> matches, Segment matchSegmentAcrossFinal) {
        for (Segment segment : matches) {
            if (segment.equals(matchSegmentAcrossFinal)) {
                return true;
            }
        }
        return false;
    }

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

    public static boolean hasKnot(ArrayList<Knot> runList, int i) {
        for (Knot vp : runList) {
            if (vp.id == i) {
                return true;
            }
        }
        return false;
    }
}
