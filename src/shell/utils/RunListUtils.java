package shell.utils;

import java.util.ArrayList;

import shell.knot.Segment;
import shell.knot.Knot;

public final class RunListUtils {


    public static boolean containsIDs(ArrayList<Knot> runList, ArrayList<Integer> integers) {
        int numIds = integers.size();
        for (Knot vp : runList) {
            if (integers.contains(vp.id)) {
                numIds--;
            }
        }
        return numIds <= 0;
    }

    public static boolean containsID(ArrayList<Knot> runList, int i) {
        for (Knot vp : runList) {
            if (i == vp.id) {
                return true;
            }
        }
        return false;
    }

    public static ArrayList<Knot> subList(ArrayList<Knot> runList, int idxBp1, int idxBp2) {
        ArrayList<Knot> result = new ArrayList<>();
        int start = Math.min(idxBp1, idxBp2);
        int end = Math.max(idxBp1, idxBp2);
        for (int i = start; i <= end; i++) {
            result.add(runList.get(i));
        }
        return result;
    }

    public static ArrayList<Knot> excludeList(ArrayList<Knot> runList, int idxBp1, int idxBp2) {
        ArrayList<Knot> result = new ArrayList<>();
        int start = Math.min(idxBp1, idxBp2);
        int end = Math.max(idxBp1, idxBp2);
        for (int i = 0; i < start; i++) {
            result.add(runList.get(i));
        }
        for (int i = end + 1; i < runList.size(); i++) {
            result.add(runList.get(i));
        }
        return result;
    }
}
