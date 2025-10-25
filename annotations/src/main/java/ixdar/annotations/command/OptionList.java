package ixdar.annotations.command;

import java.util.ArrayList;

public class OptionList extends ArrayList<String> {

    public OptionList(String... string) {
        for (String s : string) {
            this.add(s);
        }
    }

    @Override
    public boolean contains(Object o) {
        if (!(o instanceof String)) {
            return false;
        }
        String str = (String) o;
        return super.contains(str.toLowerCase());

    }

}
