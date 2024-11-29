package shell.file;

import java.util.ArrayList;

import shell.PointND;

public abstract class PointCollection {
    public abstract String usage();

    public abstract String desc();

    public abstract int argLength();

    public abstract ArrayList<PointND> realizePoints();
}
