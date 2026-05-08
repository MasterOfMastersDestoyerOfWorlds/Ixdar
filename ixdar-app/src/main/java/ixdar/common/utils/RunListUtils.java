package ixdar.common.utils;

import java.util.ArrayList;

import ixdar.geometry.knot.Knot;

/**
 * Helpers for working with "run lists" - ordered {@link ArrayList}s of
 * {@link Knot}s along a knot run. Supports ID membership tests and slicing the
 * run between two indices.
 */
public final class RunListUtils {

    /**
     * Test whether every ID in {@code integers} is present (by knot {@code id})
     * in {@code runList}.
     *
     * @param runList list of knots to scan
     * @param integers IDs that must all appear
     * @return {@code true} if all IDs are present
     */
    public static boolean containsIDs(ArrayList<Knot> runList, ArrayList<Integer> integers) {
        int numIds = integers.size();
        for (Knot vp : runList) {
            if (integers.contains(vp.id)) {
                numIds--;
            }
        }
        return numIds <= 0;
    }

    /**
     * Test whether {@code runList} contains a knot with the given ID.
     *
     * @param runList list of knots to scan
     * @param i ID to search for
     * @return {@code true} if found
     */
    public static boolean containsID(ArrayList<Knot> runList, int i) {
        for (Knot vp : runList) {
            if (i == vp.id) {
                return true;
            }
        }
        return false;
    }

    /**
     * Inclusive slice of {@code runList} between the two given indices (in any
     * order).
     *
     * @param runList source list
     * @param idxBp1 first index (inclusive)
     * @param idxBp2 second index (inclusive)
     * @return new list with elements from {@code min(idx1, idx2)} to {@code max(idx1, idx2)}
     */
    public static ArrayList<Knot> subList(ArrayList<Knot> runList, int idxBp1, int idxBp2) {
        ArrayList<Knot> result = new ArrayList<>();
        int start = Math.min(idxBp1, idxBp2);
        int end = Math.max(idxBp1, idxBp2);
        for (int i = start; i <= end; i++) {
            result.add(runList.get(i));
        }
        return result;
    }

    /**
     * Complement of {@link #subList}: returns the elements outside the inclusive
     * range {@code [min(idx1,idx2), max(idx1,idx2)]}.
     *
     * @param runList source list
     * @param idxBp1 first range index
     * @param idxBp2 second range index
     * @return new list with the elements before and after the excluded range
     */
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
