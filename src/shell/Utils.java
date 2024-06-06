package shell;

import java.util.ArrayList;

import org.apache.commons.math3.util.Pair;

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

    public static <K> String printArray(K[] array){
        if(array == null){
            return "null";
        }
        String str = "[";
        for(K entry: array){
            str += entry + ", ";
        }
        str += "]";
        return str;
    }

    public static Pair<VirtualPoint, VirtualPoint> marchLookup(Knot knot, VirtualPoint kp2, VirtualPoint vp2,
            ArrayList<VirtualPoint> potentialNeighbors) {
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
            VirtualPoint k1 = knot.knotPoints.get(idx);
            int next = idx + marchDirection;
            if (marchDirection < 0 && next < 0) {
                next = knot.knotPoints.size() - 1;
            } else if (marchDirection > 0 && next >= knot.knotPoints.size()) {
                next = 0;
            }
            VirtualPoint k2 = knot.knotPoints.get(next);
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

    public static boolean marchContains(VirtualPoint startPoint, Segment awaySegment, VirtualPoint target, Knot knot,
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
            VirtualPoint k2 = knot.knotPoints.get(next);
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

    public static Pair<VirtualPoint, VirtualPoint> marchLookup(Knot knot, VirtualPoint start, VirtualPoint away, Segment cutSegment2) {
        if(!knot.hasSegment(cutSegment2)){
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
        VirtualPoint curr = knot.knotPoints.get(idx);
        while (true) {
            curr = knot.knotPoints.get(idx);
            next = idx + marchDirection;
            if (marchDirection < 0 && next < 0) {
                next = knot.knotPoints.size() - 1;
            } else if (marchDirection > 0 && next >= knot.knotPoints.size()) {
                next = 0;
            }
            VirtualPoint nextp = knot.knotPoints.get(next);

            if (cutSegment2.contains(nextp) && cutSegment2.contains(curr)) {
                return new Pair<>(curr, nextp);
            }
            idx = next;
        }
    }

    public static boolean wouldOrphan(VirtualPoint cutp1, VirtualPoint knotp1, VirtualPoint cutp2, VirtualPoint knotp2,
            ArrayList<VirtualPoint> knotList) {
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

    public static boolean marchUntilHasOneKnotPoint(VirtualPoint startPoint, Segment awaySegment,
            Segment untilSegment, VirtualPoint kp1, VirtualPoint kp2, Knot knot) {
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
        VirtualPoint first = knot.knotPoints.get(idx);
        if (first.equals(kp1) || first.equals(kp2)) {
            numKnotPoints++;
        }
        while (true) {
            VirtualPoint k1 = knot.knotPoints.get(idx);
            int next = idx + marchDirection;
            if (marchDirection < 0 && next < 0) {
                next = knot.knotPoints.size() - 1;
            } else if (marchDirection > 0 && next >= knot.knotPoints.size()) {
                next = 0;
            }
            VirtualPoint k2 = knot.knotPoints.get(next);
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
    
}
