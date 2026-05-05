package ixdar.annotations.command;

import java.util.ArrayList;

public class OptionList extends ArrayList<String> {

    /**
     * Build a list seeded with the given option tokens (typically the lowercase flag forms a
     * command accepts).
     *
     * @param string option tokens to insert in order; stored verbatim
     */
    public OptionList(String... string) {
        for (String s : string) {
            this.add(s);
        }
    }

    /**
     * Case-insensitive membership test: returns {@code true} only when {@code o} is a
     * {@link String} whose lowercase form is present in the list. Non-string arguments
     * always yield {@code false}.
     *
     * @param o candidate option, expected to be a {@link String}
     * @return {@code true} if {@code o.toString().toLowerCase()} is contained
     */
    @Override
    public boolean contains(Object o) {
        if (!(o instanceof String)) {
            return false;
        }
        String str = (String) o;
        return super.contains(str.toLowerCase());

    }

}
