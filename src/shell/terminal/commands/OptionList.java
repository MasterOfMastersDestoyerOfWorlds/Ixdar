package shell.terminal.commands;

import java.util.ArrayList;

public class OptionList extends ArrayList<String> {

    public OptionList(String... string) {
        for (String s : string) {
            this.add(s);
        }
    }

}
