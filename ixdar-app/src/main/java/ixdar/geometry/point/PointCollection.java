package ixdar.geometry.point;

import java.util.ArrayList;

import ixdar.annotations.command.TerminalOption;
import ixdar.common.exceptions.TerminalParseException;
import ixdar.platform.file.FileStringable;

/**
 * Contract for any geometry the {@code add} terminal command can drop into a knot file: parses
 * itself from CLI arguments, expands to concrete {@link PointND} instances, and round-trips
 * through the {@code .ix} file format.
 *
 * <p>Implementations advertise their CLI shorthand via {@link #shortName()} and
 * {@link #fullName()}.
 */
public interface PointCollection extends FileStringable, TerminalOption {

    /**
     * Expand this collection into the concrete points it represents. Called
     * after construction (and after parsing) to produce the points actually
     * inserted into the active point set.
     *
     * @return freshly built list of points; primitives like {@link PointND}
     *         return a singleton list of {@code this}, while shapes such as
     *         {@link Circle} or {@link Line} return their sampled vertices
     */
    public abstract ArrayList<PointND> realizePoints();

    /**
     * Minimum number of CLI arguments the parser accepts before falling back to
     * defaults. Most collections accept either zero args (use defaults) or the
     * full {@link TerminalOption#argLength()}; default is {@code 0}.
     *
     * @return minimum acceptable trailing-argument count
     */
    @Override
    public default int minArgLength() {
        return 0;
    }

    /**
     * Parse a collection of this kind from the tail of a terminal command. The
     * implementation typically delegates to a static {@code parseXxx} helper,
     * reading {@link TerminalOption#argLength()} tokens starting at {@code i}.
     *
     * @param args the full terminal argument array
     * @param i index of the first argument that belongs to this collection
     * @throws TerminalParseException if the slice cannot be parsed (bad number
     *         format, wrong arity, missing file, etc.)
     * @return a populated collection ready for {@link #realizePoints()}
     */
    public abstract PointCollection parseCollection(String[] args, int i) throws TerminalParseException;

}
