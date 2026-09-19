package ixdar.geometry.mesh.nodes.selection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

import ixdar.geometry.mesh.data.RingCandidates;
import ixdar.geometry.mesh.data.paths.SurfaceWaypoints;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.parsing.python.PythonParser;

/**
 * Appends a ring to a working DSL graph as one statement carrying its surface points, so reloading
 * the file re-evaluates the same ring. Every minted id is {@code ring_NN}, one past the highest
 * number the file already carries, which only keeps ids unique; the overlay numbers by position.
 */
public final class RingDslWriter {

    /** Prefix of every id and label the writer mints, completed by a two-digit ring number. */
    public static final String STATEMENT_ID_PREFIX = RingCandidates.MARK_LABEL_PREFIX;

    /** Port a chained statement reads from when its upstream node has no geometry output. */
    public static final String DEFAULT_UPSTREAM_PORT = "geometry";

    /** Line break every written statement is separated by, so one ring is always one string. */
    public static final String LINE_BREAK = "\n";

    /** Opening of a statement's points argument, where a rewrite splices the new points in. */
    public static final String POINTS_ARGUMENT = "points=\"";

    /** Refusal when the graph has nothing for a ring statement to chain onto. */
    public static final String NO_UPSTREAM =
            "the working graph has no statement for the ring to read its geometry from";

    /** Longest run of digits a ring number is read from, short of overflowing an int. */
    private static final int RING_NUMBER_DIGIT_CEILING = 9;

    private RingDslWriter() {
    }

    /**
     * The DSL source with one more ring statement, chained onto the graph's last statement.
     *
     * @param dslSource     working graph the ring is added to
     * @param packedXyz     ring waypoints as packed xyz
     * @param waypointCount waypoints to write from the front of the array
     * @param tighten       whether the written statement tightens the ring with FlipOut
     * @param pin           whether the written statement holds the ring on its waypoints
     * @throws IllegalArgumentException when the source holds no statement to chain onto, or when
     *                                  the id the ring would take is already bound
     * @return the source with the statement and a trailing newline appended
     */
    public static String append(String dslSource, float[] packedXyz, int waypointCount,
            boolean tighten, boolean pin) {
        List<PythonParser.ParsedNode> statements = upstreamStatements(dslSource);
        PythonParser.ParsedNode upstream = statements.get(statements.size() - 1);
        String id = mintRingId(statements, dslSource, List.of());
        return appended(dslSource, statement(id,
                upstream.id + "." + geometryPort(upstream.type), packedXyz, waypointCount, tighten,
                pin));
    }

    /**
     * The DSL source with one more spline ring appended, chained onto the graph's last statement.
     *
     * @param dslSource   working graph the ring is added to
     * @param packedXyz   spline anchors as packed xyz
     * @param anchorCount anchors to write from the front of the array
     * @throws IllegalArgumentException when the source holds no statement to chain onto, or when
     *                                  the id the ring would take is already bound
     * @return the source with the statement and a trailing newline appended
     */
    public static String appendSpline(String dslSource, float[] packedXyz, int anchorCount) {
        return appendSpline(dslSource, packedXyz, anchorCount, List.of());
    }

    /**
     * The DSL source with one more spline ring appended, its id also clear of the ring labels a
     * running graph produced without naming them in the text, such as {@code ring_candidates}
     * output.
     *
     * @param dslSource   working graph the ring is added to
     * @param packedXyz   spline anchors as packed xyz
     * @param anchorCount anchors to write from the front of the array
     * @param liveLabels  ring labels the running graph holds that the text may not name
     * @throws IllegalArgumentException when the source holds no statement to chain onto, or when
     *                                  the id the ring would take is already bound
     * @return the source with the statement and a trailing newline appended
     */
    public static String appendSpline(String dslSource, float[] packedXyz, int anchorCount,
            Collection<String> liveLabels) {
        List<PythonParser.ParsedNode> statements = upstreamStatements(dslSource);
        PythonParser.ParsedNode upstream = statements.get(statements.size() - 1);
        String id = mintRingId(statements, dslSource, liveLabels);
        return appended(dslSource, splineStatement(id,
                upstream.id + "." + geometryPort(upstream.type), packedXyz, anchorCount));
    }

    /**
     * The statement id the next ring appended to a graph will take.
     *
     * @param dslSource working graph the ring would be added to
     * @throws IllegalArgumentException when the graph binds an id twice, or already binds the id
     * @return the id, {@code ring_} followed by a two-digit number
     */
    public static String nextRingId(String dslSource) {
        return nextRingId(dslSource, List.of());
    }

    /**
     * The statement id the next ring appended to a graph will take, clear of the ring labels a
     * running graph holds without naming them in the text.
     *
     * @param dslSource  working graph the ring would be added to
     * @param liveLabels ring labels the running graph holds that the text may not name
     * @throws IllegalArgumentException when the graph binds an id twice, or already binds the id
     * @return the id, {@code ring_} followed by a two-digit number
     */
    public static String nextRingId(String dslSource, Collection<String> liveLabels) {
        return mintRingId(NodeGraphRuntime.fromSource(dslSource).statements, dslSource, liveLabels);
    }

    /**
     * The statement text {@link #append} or {@link #appendSpline} added, for echoing what a save
     * wrote without assuming the appended source is longer than the source it came from.
     *
     * @param dslSource source the statement was appended to
     * @param updated   what the writer returned for that source
     * @return the appended statement, trimmed of its surrounding whitespace
     */
    public static String appendedStatement(String dslSource, String updated) {
        return updated.substring(dslSource.stripTrailing().length()).trim();
    }

    /**
     * The DSL source with one ring statement's points replaced, for an edit that moves a ring the
     * file already holds. Only the {@code points=} argument changes: the id, the label, every
     * other argument and any trailing comment survive.
     *
     * @param dslSource   working graph holding the statement
     * @param id          statement id to rewrite
     * @param packedXyz   the ring's new anchors as packed xyz
     * @param anchorCount anchors to write from the front of the array
     * @throws IllegalArgumentException when no line, or more than one line, binds that id, or when
     *                                  the line carries no points argument
     * @return the source with that one argument rewritten
     */
    public static String replaceSpline(String dslSource, String id, float[] packedXyz,
            int anchorCount) {
        String[] lines = dslSource.split(LINE_BREAK, -1);
        int target = -1;
        for (int line = 0; line < lines.length; line++) {
            if (!binds(lines[line], id)) {
                continue;
            }
            if (target >= 0) {
                throw new IllegalArgumentException("the working graph binds " + id
                        + " on more than one line, so the ring has no one statement to rewrite");
            }
            target = line;
        }
        if (target < 0) {
            throw new IllegalArgumentException("the working graph holds no statement "
                    + id + " to rewrite");
        }
        int opening = lines[target].indexOf(POINTS_ARGUMENT);
        int start = opening < 0 ? -1 : opening + POINTS_ARGUMENT.length();
        int closing = start < 0 ? -1 : lines[target].indexOf('"', start);
        if (closing < 0) {
            throw new IllegalArgumentException("statement " + id
                    + " carries no points argument for the moved ring to be written into");
        }
        lines[target] = lines[target].substring(0, start)
                + SurfaceWaypoints.format(packedXyz, anchorCount)
                + lines[target].substring(closing);
        return String.join(LINE_BREAK, lines);
    }

    /**
     * Replace a file's contents in one step, so a failure mid-write leaves the previous graph on
     * disk instead of a truncated one.
     *
     * @param path   file to replace
     * @param source full text the file should hold
     * @throws IOException when the neighbouring temporary file cannot be written or moved
     */
    public static void writeAtomically(Path path, String source) throws IOException {
        Path directory = path.toAbsolutePath().getParent();
        Path temporary = Files.createTempFile(directory, path.getFileName().toString(), ".dsltmp");
        try {
            Files.write(temporary, source.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * One spline ring statement, written with fixed-precision anchors so a ring is always one
     * string and its reload re-traces the same curve.
     *
     * @param id            statement id to bind the ring to
     * @param geometryInput reference the ring reads its geometry from, as {@code node.port}
     * @param packedXyz     spline anchors as packed xyz
     * @param anchorCount   anchors to write from the front of the array
     * @return the statement text, without a trailing newline
     */
    public static String splineStatement(String id, String geometryInput, float[] packedXyz,
            int anchorCount) {
        return id + " = spline_ring(geometry=" + geometryInput
                + ", " + POINTS_ARGUMENT + SurfaceWaypoints.format(packedXyz, anchorCount)
                + "\", label=\"" + id + "\")";
    }

    /**
     * One ring statement, written with fixed-precision waypoints so a ring is always one string.
     *
     * @param id            statement id to bind the ring to
     * @param geometryInput reference the ring reads its geometry from, as {@code node.port}
     * @param packedXyz     ring waypoints as packed xyz
     * @param waypointCount waypoints to write from the front of the array
     * @param tighten       whether the written statement tightens the ring with FlipOut
     * @param pin           whether the written statement holds the ring on its waypoints
     * @return the statement text, without a trailing newline
     */
    public static String statement(String id, String geometryInput, float[] packedXyz,
            int waypointCount, boolean tighten, boolean pin) {
        return id + " = loop_through_points(geometry=" + geometryInput
                + ", " + POINTS_ARGUMENT + SurfaceWaypoints.format(packedXyz, waypointCount)
                + "\", tighten=" + tighten
                + ", pin=" + pin
                + ", label=\"" + id + "\")";
    }

    /**
     * The output port a downstream node should read a node type's surface from.
     *
     * @param nodeType DSL id of the upstream node
     * @return its first geometry-bundle output, or {@code geometry} when the type is unknown
     */
    public static String geometryPort(String nodeType) {
        Supplier<? extends MeshNode> supplier = NodeGraphRuntime.supplierFor(nodeType);
        if (supplier == null) {
            return DEFAULT_UPSTREAM_PORT;
        }
        for (OutputPort output : supplier.get().outputs()) {
            if (output.type == PortType.GEOMETRY_BUNDLE) {
                return output.name;
            }
        }
        return DEFAULT_UPSTREAM_PORT;
    }

    /**
     * The graph's statements, refusing a source with nothing for a ring to chain onto.
     *
     * @param dslSource working graph to parse
     * @throws IllegalArgumentException when the source holds no statement
     * @return the parsed statements, never empty
     */
    private static List<PythonParser.ParsedNode> upstreamStatements(String dslSource) {
        List<PythonParser.ParsedNode> statements = NodeGraphRuntime.fromSource(dslSource).statements;
        if (statements.isEmpty()) {
            throw new IllegalArgumentException(NO_UPSTREAM);
        }
        return statements;
    }

    /**
     * The id the next ring takes: one past the highest ring number anywhere in the file, refused
     * outright when the graph already binds it or binds any id twice.
     *
     * @param statements the graph's parsed statements
     * @param dslSource  the graph's text, scanned for ids and labels the parse does not expose
     * @param liveLabels ring labels the running graph holds that the text may not name
     * @throws IllegalArgumentException when an id repeats, or the minted id is already bound
     * @return the minted {@code ring_NN} id
     */
    private static String mintRingId(List<PythonParser.ParsedNode> statements, String dslSource,
            Collection<String> liveLabels) {
        Set<String> bound = new HashSet<>();
        for (PythonParser.ParsedNode statement : statements) {
            if (!bound.add(statement.id)) {
                throw new IllegalArgumentException("the working graph binds " + statement.id
                        + " twice; the graph keeps only the last of them, so nothing was written");
            }
        }
        int highest = Math.max(highestRingNumber(dslSource),
                highestRingNumber(String.join(LINE_BREAK, liveLabels)));
        String id = String.format(Locale.ROOT, "%s%02d", STATEMENT_ID_PREFIX, highest + 1);
        if (bound.contains(id)) {
            throw new IllegalArgumentException("the working graph already binds " + id
                    + ", the id the next ring would take, so nothing was written");
        }
        return id;
    }

    /**
     * The highest ring number the text carries, over {@code ring_NN} and legacy {@code ringN} ids,
     * labels and comments alike, or {@code -1} when it carries none. {@code rings} and
     * {@code ring_candidates} are not ring numbers and do not count.
     *
     * @param dslSource text to scan
     * @return the highest number found, or {@code -1}
     */
    private static int highestRingNumber(String dslSource) {
        int highest = -1;
        String bare = STATEMENT_ID_PREFIX.substring(0, STATEMENT_ID_PREFIX.length() - 1);
        for (int at = dslSource.indexOf(bare); at >= 0; at = dslSource.indexOf(bare, at + 1)) {
            if (at > 0 && isIdentifierPart(dslSource.charAt(at - 1))) {
                continue;
            }
            int digit = at + bare.length();
            if (digit < dslSource.length() && dslSource.charAt(digit) == '_') {
                digit++;
            }
            int end = digit;
            while (end < dslSource.length() && isDigit(dslSource.charAt(end))) {
                end++;
            }
            if (end == digit || end - digit > RING_NUMBER_DIGIT_CEILING) {
                continue;
            }
            if (end < dslSource.length() && isIdentifierPart(dslSource.charAt(end))) {
                continue;
            }
            highest = Math.max(highest, Integer.parseInt(dslSource.substring(digit, end)));
        }
        return highest;
    }

    /**
     * Whether one line is the assignment binding a statement id.
     *
     * @param line line to test
     * @param id   statement id the line would bind
     * @return true when the line opens with {@code id =}
     */
    private static boolean binds(String line, String id) {
        if (!line.startsWith(id)) {
            return false;
        }
        int at = id.length();
        while (at < line.length() && line.charAt(at) == ' ') {
            at++;
        }
        return at < line.length() && line.charAt(at) == '=';
    }

    /**
     * The source with one statement on its own line at the end, whatever trailing blank lines the
     * source had.
     *
     * @param dslSource source to append to
     * @param body      statement text, without a newline
     * @return the appended source, ending in a newline
     */
    private static String appended(String dslSource, String body) {
        return dslSource.stripTrailing() + LINE_BREAK + body + LINE_BREAK;
    }

    private static boolean isIdentifierPart(char character) {
        return Character.isLetterOrDigit(character) || character == '_';
    }

    private static boolean isDigit(char character) {
        return character >= '0' && character <= '9';
    }
}
