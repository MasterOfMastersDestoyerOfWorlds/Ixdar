package ixdar.common.utils;

import java.util.ArrayList;

public class StringBuff {
    ArrayList<SearchString> strings = new ArrayList<>();
    int currentDepth = 0;

    /**
     * TODO: document {@code flush}.
     */
    public void flush() {
        strings = new ArrayList<>();
    }

    /**
     * TODO: document {@code add}.
     *
     * @param s TODO: describe
     */
    public void add(Object s) {
        if (s != null) {
            strings.add(new SearchString(s.toString(), currentDepth));
        } else {
            strings.add(new SearchString("null", currentDepth));
        }
    }

    /**
     * TODO: document {@code add}.
     *
     * @param condition TODO: describe
     * @param s TODO: describe
     */
    public void add(boolean condition, Object s) {
        if (condition) {
            this.add(s);
        }
    }

    /**
     * TODO: document {@code printAll}.
     */
    public void printAll() {
        for (SearchString s : strings) {
            System.out.println(s);
        }
    }

    /**
     * TODO: document {@code printLayer}.
     *
     * @param depth TODO: describe
     */
    public void printLayer(int depth) {
        for (SearchString s : strings) {
            if (s.depth == depth) {
                System.out.println(s);
            }
        }
    }

    /**
     * TODO: document {@code sizeLayer}.
     *
     * @param depth TODO: describe
     * @return TODO: describe
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

    class SearchString {

        String str;
        int depth;

        /**
         * TODO: document {@code SearchString}.
         *
         * @param string TODO: describe
         * @param depth TODO: describe
         */
        public SearchString(String string, int depth) {
            str = string;
            this.depth = depth;
        }

        /**
         * TODO: document {@code toString}.
         *
         * @return TODO: describe
         */
        @Override
        public String toString() {
            return str;
        }

    }
}