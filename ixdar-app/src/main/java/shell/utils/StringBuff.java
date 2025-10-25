package shell.utils;

import java.util.ArrayList;

public class StringBuff {
    ArrayList<SearchString> strings = new ArrayList<>();
    int currentDepth = 0;

    class SearchString {

        String str;
        int depth;

        public SearchString(String string, int depth) {
            str = string;
            this.depth = depth;
        }

        @Override
        public String toString() {
            return str;
        }

    }

    public void flush() {
        strings = new ArrayList<>();
    }

    public void add(Object s) {
        if (s != null) {
            strings.add(new SearchString(s.toString(), currentDepth));
        } else {
            strings.add(new SearchString("null", currentDepth));
        }
    }

    public void add(boolean condition, Object s) {
        if (condition) {
            this.add(s);
        }
    }

    public void printAll() {
        for (SearchString s : strings) {
            System.out.println(s);
        }
    }

    public void printLayer(int depth) {
        for (SearchString s : strings) {
            if (s.depth == depth) {
                System.out.println(s);
            }
        }
    }

    public int sizeLayer(int depth) {
        int count = 0;
        for (SearchString s : strings) {
            if (s.depth == depth) {
                count++;
            }
        }
        return count;
    }
}