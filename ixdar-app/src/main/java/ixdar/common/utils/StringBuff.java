package ixdar.common.utils;

import java.util.ArrayList;

/**
 * Append-only debug log of stringified entries tagged with a depth/layer.
 * Used to capture nested algorithm traces and replay them per-layer for
 * diagnostics.
 */
public class StringBuff {
    ArrayList<SearchString> strings = new ArrayList<>();
    int currentDepth = 0;

    /**
     * Discard all buffered entries and reset to empty.
     */
    public void flush() {
        strings = new ArrayList<>();
    }

    /**
     * Append an entry at the current depth. {@code null} is recorded as the
     * literal string {@code "null"}.
     *
     * @param s value to stringify and record
     */
    public void add(Object s) {
        if (s != null) {
            strings.add(new SearchString(s.toString(), currentDepth));
        } else {
            strings.add(new SearchString("null", currentDepth));
        }
    }

    /**
     * Conditional variant of {@link #add(Object)}: records {@code s} only when
     * {@code condition} is true.
     *
     * @param condition guard predicate
     * @param s value to stringify and record when {@code condition} holds
     */
    public void add(boolean condition, Object s) {
        if (condition) {
            this.add(s);
        }
    }

    /**
     * Print every buffered entry to {@code System.out} in insertion order.
     */
    public void printAll() {
        for (SearchString s : strings) {
            System.out.println(s);
        }
    }

    /**
     * Print only the buffered entries at the given depth/layer.
     *
     * @param depth layer to print
     */
    public void printLayer(int depth) {
        for (SearchString s : strings) {
            if (s.depth == depth) {
                System.out.println(s);
            }
        }
    }

    /**
     * Count buffered entries at the given depth/layer.
     *
     * @param depth layer to count
     * @return number of entries recorded at that depth
     */
    public int sizeLayer(int depth) {
        int count = 0;
        for (SearchString s : strings) {
            if (s.depth == depth) {
                count++;
            }
        }
        return count;
    }

    /**
     * One depth-tagged entry in the buffer.
     */
    class SearchString {

        String str;
        int depth;

        /**
         * Construct an entry holding a string and its depth.
         *
         * @param string already-stringified value
         * @param depth layer/depth tag
         */
        public SearchString(String string, int depth) {
            str = string;
            this.depth = depth;
        }

        /**
         * Returns the stored string verbatim.
         *
         * @return the recorded string
         */
        @Override
        public String toString() {
            return str;
        }

    }
}
