package shell.point;

import java.util.ArrayList;

import shell.exceptions.TerminalParseException;
import shell.file.FileStringable;
import shell.terminal.TerminalOption;

public interface PointCollection extends FileStringable, TerminalOption {

    public abstract ArrayList<PointND> realizePoints();

    @Override
    public default int minArgLength() {
        return 0;
    }

    public abstract PointCollection parseCollection(String[] args, int i) throws TerminalParseException;

}
