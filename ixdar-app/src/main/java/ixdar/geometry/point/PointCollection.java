package ixdar.geometry.point;

import java.util.ArrayList;

import ixdar.annotations.command.TerminalOption;
import ixdar.common.exceptions.TerminalParseException;
import ixdar.platform.file.FileStringable;

public interface PointCollection extends FileStringable, TerminalOption {

    /**
     * TODO: document {@code realizePoints}.
     *
     * @return TODO: describe
     */
    public abstract ArrayList<PointND> realizePoints();

    /**
     * TODO: document {@code minArgLength}.
     *
     * @return TODO: describe
     */
    @Override
    public default int minArgLength() {
        return 0;
    }

    /**
     * TODO: document {@code parseCollection}.
     *
     * @param args TODO: describe
     * @param i TODO: describe
     * @throws TerminalParseException TODO: describe
     * @return TODO: describe
     */
    public abstract PointCollection parseCollection(String[] args, int i) throws TerminalParseException;

}
