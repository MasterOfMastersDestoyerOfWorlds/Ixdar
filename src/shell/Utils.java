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
                float z = 1 / 0;
    
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
            VirtualPoint k1 = knot.knotPoints.get(idx);
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
                float z = 1 / 0;
    
            }
        }
    }

    public static Pair<VirtualPoint, VirtualPoint> marchLookup(Knot knot, VirtualPoint other, VirtualPoint kp1, Segment cutSegment2) {
        int idx = knot.knotPoints.indexOf(kp1);
        int idx2 = knot.knotPoints.indexOf(other);

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
    
}
