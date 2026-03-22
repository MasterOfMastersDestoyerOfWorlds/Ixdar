package ixdar.geometry.point;

import java.util.ArrayList;

import ixdar.annotations.command.TerminalOption;
import ixdar.common.exceptions.TerminalParseException;
import ixdar.platform.file.FileStringable;

public interface PointCollection extends FileStringable, TerminalOption {

    public abstract ArrayList<PointND> realizePoints();

    @Override
    public default int minArgLength() {
        return 0;
    }

    public abstract PointCollection parseCollection(String[] args, int i) throws TerminalParseException;

}
