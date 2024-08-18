package shell.shell;

import java.util.Comparator;

public class ShellComparator implements Comparator<ShellPair> {
    // Overriding compare()method of Comparator
    // for descending order of cgpa
    @Override
    public int compare(ShellPair o1, ShellPair o2) {
        return Integer.compare(o1.priority, o2.priority);
    }
}