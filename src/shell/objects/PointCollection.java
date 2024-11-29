package shell.objects;

import java.util.ArrayList;

import shell.exceptions.TerminalParseException;
import shell.file.FileStringable;
import shell.terminal.TerminalOption;

public abstract class PointCollection implements FileStringable, TerminalOption {

    public abstract ArrayList<PointND> realizePoints();

    @Override
    public int minArgLength() {
        return 0;
    }

    public abstract PointCollection parseCollection(String[] args, int i) throws TerminalParseException;

}
