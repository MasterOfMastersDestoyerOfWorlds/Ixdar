package ixdar.geometry.mesh.nodes.selection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import ixdar.geometry.mesh.data.RingCandidates;
import ixdar.geometry.mesh.data.paths.RingSegmentMode;
import ixdar.geometry.mesh.data.paths.SurfaceWaypoints;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.data.SurfaceCreasesNode;
import ixdar.geometry.mesh.nodes.data.SurfaceMetricNode;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.parsing.python.PythonParser;

/**
 * Appends a ring to a working DSL graph as one statement carrying its surface points, so reloading
 * the file re-evaluates the same ring. Every minted id is {@code ring_NN}, one past the highest
 * number the file already carries, which only keeps ids unique; the overlay numbers by position.
 */
public final class RingDslWriter {

    public static final String STATEMENT_ID_PREFIX = RingCandidates.MARK_LABEL_PREFIX;

    public static final String DEFAULT_UPSTREAM_PORT = "geometry";

    public static final String LINE_BREAK = "\n";

    public static final String POINTS_ARGUMENT = "points=\"";

    public static final String NORMAL_ARGUMENT = "normal=\"";

    public static final String LABEL_ARGUMENT = "label";

    public static final String CANDIDATES_NODE = "ring_candidates";

    public static final String SPLINE_RING_NODE = "spline_ring";

    public static final String SURFACE_METRIC_NODE = "surface_metric";

    public static final String METRIC_STATEMENT_ID = "surface";

    public static final String MODE_ARGUMENT = "mode=\"";

    public static final String SURFACE_CREASES_NODE = "surface_creases";

    public static final String CREASES_STATEMENT_ID = "creases";

    public static final Set<String> MESH_PRESERVING_RING_NODES =
            Set.of(SPLINE_RING_NODE, "loop_through_points");

    public static final String NO_UPSTREAM =
            "the working graph has no statement for the ring to read its geometry from";

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
        return appendSpline(dslSource, packedXyz, anchorCount, null, liveLabels);
    }

    /**
     * The DSL source with one more spline ring appended through its authored anchors and the base
     * normal its plane leans toward, its id clear of the running graph's ring labels.
     *
     * @param dslSource   working graph the ring is added to
     * @param packedXyz   authored anchors as packed xyz
     * @param anchorCount anchors to write from the front of the array
     * @param baseNormal  the ring's base normal as packed xyz, or {@code null} to write none
     * @param liveLabels  ring labels the running graph holds that the text may not name
     * @throws IllegalArgumentException when the source holds no statement to chain onto, or when
     *                                  the id the ring would take is already bound
     * @return the source with the statement and a trailing newline appended
     */
    public static String appendSpline(String dslSource, float[] packedXyz, int anchorCount,
            float[] baseNormal, Collection<String> liveLabels) {
        List<PythonParser.ParsedNode> statements = upstreamStatements(dslSource);
        PythonParser.ParsedNode upstream = statements.get(statements.size() - 1);
        String id = mintRingId(statements, dslSource, liveLabels);
        return appended(dslSource, splineStatement(id,
                upstream.id + "." + geometryPort(upstream.type), packedXyz, anchorCount,
                baseNormal));
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
        return replaceSpline(dslSource, id, packedXyz, anchorCount, null);
    }

    /**
     * The DSL source with one spline ring's authored anchors and base normal replaced in place,
     * the {@code normal=} argument added after {@code points=} when the line has none.
     *
     * @param dslSource   working graph holding the statement
     * @param id          statement id to rewrite
     * @param packedXyz   the ring's authored anchors as packed xyz
     * @param anchorCount anchors to write from the front of the array
     * @param baseNormal  the ring's base normal as packed xyz, or {@code null} to leave it alone
     * @throws IllegalArgumentException when no line, or more than one line, binds that id, or when
     *                                  the line carries no points argument
     * @return the source with those arguments rewritten
     */
    public static String replaceSpline(String dslSource, String id, float[] packedXyz,
            int anchorCount, float[] baseNormal) {
        String[] lines = dslSource.split(LINE_BREAK, -1);
        int target = bindingLine(lines, id);
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
        if (baseNormal != null) {
            String normal = SurfaceWaypoints.format(baseNormal, 1);
            int normalOpening = lines[target].indexOf(NORMAL_ARGUMENT);
            if (normalOpening < 0) {
                int pointsEnd = lines[target].indexOf('"', start) + 1;
                lines[target] = lines[target].substring(0, pointsEnd) + ", " + NORMAL_ARGUMENT
                        + normal + "\"" + lines[target].substring(pointsEnd);
            } else {
                int normalStart = normalOpening + NORMAL_ARGUMENT.length();
                lines[target] = lines[target].substring(0, normalStart) + normal
                        + lines[target].substring(lines[target].indexOf('"', normalStart));
            }
        }
        return String.join(LINE_BREAK, lines);
    }

    /**
     * The DSL source without one statement, its readers rewired to read their geometry from the
     * statement it read from, so deleting a ring in the middle of a chain keeps the rest.
     *
     * @param dslSource working graph holding the statement
     * @param id        statement id to remove
     * @throws IllegalArgumentException when no line, or more than one line, binds that id, or when
     *                                  another statement reads an output the removal cannot rewire
     * @return the source with that line gone
     */
    public static String remove(String dslSource, String id) {
        return replace(dslSource, id, List.of(), null);
    }

    /**
     * The DSL source with one statement's line replaced by a chain of statements, the readers of
     * its geometry rewired to read the chain's output instead.
     *
     * @param dslSource       working graph holding the statement
     * @param id              statement id to replace
     * @param replacement     statement lines to put in its place, in chain order; may be empty
     * @param outputReference {@code node.port} the readers read afterwards, or {@code null} for the
     *                        replaced statement's own geometry input
     * @throws IllegalArgumentException when no line, or more than one line, binds that id, or when
     *                                  another statement reads an output that nothing produces
     *                                  afterwards
     * @return the source with that line replaced
     */
    public static String replace(String dslSource, String id, List<String> replacement,
            String outputReference) {
        String[] lines = dslSource.split(LINE_BREAK, -1);
        int target = bindingLine(lines, id);
        PythonParser.ParsedNode replaced = null;
        for (PythonParser.ParsedNode statement : NodeGraphRuntime.fromSource(dslSource).statements) {
            replaced = statement.id.equals(id) ? statement : replaced;
        }
        String rewiredTo = outputReference != null ? outputReference : geometryInput(replaced);
        String passedThrough = replaced == null ? null : id + "." + geometryPort(replaced.type);
        boolean stillBound = false;
        for (String statement : replacement) {
            stillBound |= binds(statement, id);
        }
        List<String> kept = new ArrayList<>();
        for (int line = 0; line < lines.length; line++) {
            if (line == target) {
                kept.addAll(replacement);
                continue;
            }
            String text = rewired(lines[line], passedThrough, rewiredTo);
            if (!stillBound && tokenIndex(text, id + ".", 0) >= 0) {
                throw new IllegalArgumentException("line " + (line + 1) + " reads an output of "
                        + id + " that nothing else produces, so " + id + " was not removed");
            }
            kept.add(text);
        }
        return String.join(LINE_BREAK, kept);
    }

    /**
     * The DSL source with a chain of statements inserted after the statement a reference names,
     * every later reader of that reference rewired to read the chain's output.
     *
     * @param dslSource         working graph to insert into
     * @param upstreamReference {@code node.port} the chain reads, such as {@code carrier.geometry}
     * @param statements        statement lines to insert, in chain order
     * @param outputReference   {@code node.port} the chain's last statement produces
     * @throws IllegalArgumentException when no line, or more than one line, binds the node
     * @return the source with the chain inserted
     */
    public static String insertAfter(String dslSource, String upstreamReference,
            List<String> statements, String outputReference) {
        String[] lines = dslSource.split(LINE_BREAK, -1);
        int target = bindingLine(lines, upstreamReference.substring(0,
                upstreamReference.indexOf('.')));
        List<String> out = new ArrayList<>();
        for (int line = 0; line < lines.length; line++) {
            out.add(line > target ? rewired(lines[line], upstreamReference, outputReference)
                    : lines[line]);
            if (line == target) {
                out.addAll(statements);
            }
        }
        return String.join(LINE_BREAK, out);
    }

    /**
     * The DSL source with a block of statements swapped for another, the new block where the
     * first old statement was and the readers of every old one reading its output; with none
     * left, it goes after its upstream.
     *
     * @param dslSource         working graph holding the block
     * @param blockIds          ids of the statements that make up the old block, in any order
     * @param block             statement lines of the new block, in chain order; may be empty
     * @param upstreamReference {@code node.port} the block reads its geometry from
     * @param outputReference   {@code node.port} the new block produces, its upstream when empty
     * @throws IllegalArgumentException when a statement outside the block reads an output that
     *                                  nothing produces afterwards
     * @return the source with the block swapped
     */
    public static String replaceBlock(String dslSource, Collection<String> blockIds,
            List<String> block, String upstreamReference, String outputReference) {
        String source = dslSource;
        String anchor = null;
        for (PythonParser.ParsedNode statement : NodeGraphRuntime.fromSource(dslSource).statements) {
            if (!blockIds.contains(statement.id)) {
                continue;
            }
            if (anchor == null) {
                anchor = statement.id;
            } else {
                source = remove(source, statement.id);
            }
        }
        if (anchor != null) {
            return replace(source, anchor, block, outputReference);
        }
        return block.isEmpty() ? source
                : insertAfter(source, upstreamReference, block, outputReference);
    }

    /**
     * The DSL source with every spline ring wired to what it reads besides geometry: {@code metric=}
     * names the {@code surface_metric} its chain runs back to, one made before it when there is
     * none, and a crease-mode ring's {@code creases=} the {@code surface_creases} on that surface.
     *
     * @param dslSource working graph
     * @throws IllegalArgumentException when a statement the wiring rewrites is bound twice
     * @return the wired source, or the source itself when every spline ring is already wired
     */
    public static String wireRingInputs(String dslSource) {
        List<PythonParser.ParsedNode> statements = NodeGraphRuntime.fromSource(dslSource).statements;
        Set<String> bound = new HashSet<>();
        Map<String, String> creasesIdBySurface = new HashMap<>();
        for (PythonParser.ParsedNode statement : statements) {
            bound.add(statement.id);
            if (SURFACE_CREASES_NODE.equals(statement.type) && geometryInput(statement) != null) {
                creasesIdBySurface.putIfAbsent(geometryInput(statement), statement.id);
            }
        }
        List<String> lines = new ArrayList<>(List.of(dslSource.split(LINE_BREAK, -1)));
        // Only rings pass the measured mesh through untouched, so a metric reaches a statement
        // only along an unbroken chain of rings reading geometry from geometry.
        Map<String, String> metricIdByStatement = new HashMap<>();
        for (PythonParser.ParsedNode statement : statements) {
            if (SURFACE_METRIC_NODE.equals(statement.type)) {
                metricIdByStatement.put(statement.id, statement.id);
                continue;
            }
            if (!MESH_PRESERVING_RING_NODES.contains(statement.type)) {
                continue;
            }
            String upstream = geometryInput(statement);
            String metricId = statement.arguments.get(DEFAULT_UPSTREAM_PORT)
                    instanceof PythonParser.NodeReference reference
                    && DEFAULT_UPSTREAM_PORT.equals(reference.portName)
                    ? metricIdByStatement.get(reference.nodeId) : null;
            if (statement.arguments.get(SplineRingNode.METRIC.name)
                    instanceof PythonParser.NodeReference wired) {
                metricId = wired.nodeId;
            } else if (SPLINE_RING_NODE.equals(statement.type) && upstream != null) {
                int target = bindingLine(lines.toArray(new String[0]), statement.id);
                if (metricId == null) {
                    metricId = METRIC_STATEMENT_ID;
                    for (int suffix = 2; bound.contains(metricId); suffix++) {
                        metricId = METRIC_STATEMENT_ID + "_" + suffix;
                    }
                    bound.add(metricId);
                    upstream = metricId + "." + SurfaceMetricNode.GEOMETRY_OUT.name;
                    lines.set(target, rewired(lines.get(target), geometryInput(statement),
                            upstream));
                    lines.add(target, metricId + " = " + SURFACE_METRIC_NODE + "(geometry="
                            + geometryInput(statement) + ")");
                    target++;
                }
                String geometryArgument = DEFAULT_UPSTREAM_PORT + "=" + upstream;
                int at = tokenIndex(lines.get(target), geometryArgument, 0);
                if (at < 0) {
                    throw new IllegalArgumentException("statement " + statement.id
                            + " does not spell its input as " + geometryArgument
                            + ", so its metric could not be wired");
                }
                int end = at + geometryArgument.length();
                lines.set(target, lines.get(target).substring(0, end) + ", "
                        + SplineRingNode.METRIC.name + "=" + metricId + "."
                        + SurfaceMetricNode.METRIC.name + lines.get(target).substring(end));
            }
            if (metricId != null) {
                metricIdByStatement.put(statement.id, metricId);
            }
            Object mode = statement.arguments.get(SplineRingNode.MODE.name);
            if (metricId == null || !SPLINE_RING_NODE.equals(statement.type)
                    || statement.arguments.get(SplineRingNode.CREASES.name)
                            instanceof PythonParser.NodeReference
                    || RingSegmentMode.named(mode instanceof String text ? text : null)
                            != RingSegmentMode.CREASE) {
                continue;
            }
            String surface = metricId + "." + SurfaceMetricNode.GEOMETRY_OUT.name;
            String creasesId = creasesIdBySurface.get(surface);
            if (creasesId == null) {
                creasesId = CREASES_STATEMENT_ID;
                for (int suffix = 2; bound.contains(creasesId); suffix++) {
                    creasesId = CREASES_STATEMENT_ID + "_" + suffix;
                }
                bound.add(creasesId);
                creasesIdBySurface.put(surface, creasesId);
                lines.add(bindingLine(lines.toArray(new String[0]), metricId) + 1, creasesId
                        + " = " + SURFACE_CREASES_NODE + "(" + SurfaceCreasesNode.GEOMETRY.name
                        + "=" + surface + ")");
            }
            int target = bindingLine(lines.toArray(new String[0]), statement.id);
            String metricArgument = SplineRingNode.METRIC.name + "=" + metricId + "."
                    + SurfaceMetricNode.METRIC.name;
            int at = tokenIndex(lines.get(target), metricArgument, 0);
            if (at < 0) {
                throw new IllegalArgumentException("statement " + statement.id
                        + " does not spell its metric as " + metricArgument
                        + ", so its creases could not be wired");
            }
            int end = at + metricArgument.length();
            lines.set(target, lines.get(target).substring(0, end) + ", "
                    + SplineRingNode.CREASES.name + "=" + creasesId + "."
                    + SurfaceCreasesNode.CREASES.name + lines.get(target).substring(end));
        }
        return String.join(LINE_BREAK, lines);
    }

    /**
     * The DSL source with one spline ring's segment mode written in place: {@code mode=} after its
     * anchors and normal, or dropped for the geodesic mode, which is the default.
     *
     * @param dslSource working graph holding the statement
     * @param id        statement id to rewrite
     * @param mode      the ring's segment mode
     * @throws IllegalArgumentException when no line, or more than one line, binds that id, or when
     *                                  the line carries no points argument
     * @return the source with that one argument written
     */
    public static String withMode(String dslSource, String id, RingSegmentMode mode) {
        String[] lines = dslSource.split(LINE_BREAK, -1);
        int target = bindingLine(lines, id);
        String line = lines[target];
        int opening = tokenIndex(line, MODE_ARGUMENT, 0);
        if (opening >= 0) {
            int closing = line.indexOf('"', opening + MODE_ARGUMENT.length()) + 1;
            int cut = opening;
            while (cut > 0 && line.charAt(cut - 1) == ' ') {
                cut--;
            }
            cut -= cut > 0 && line.charAt(cut - 1) == ',' ? 1 : 0;
            line = line.substring(0, cut) + line.substring(closing);
        }
        if (mode != RingSegmentMode.GEODESIC) {
            int argument = Math.max(line.indexOf(NORMAL_ARGUMENT), line.indexOf(POINTS_ARGUMENT));
            if (argument < 0) {
                throw new IllegalArgumentException("statement " + id
                        + " carries no points argument to write its mode after");
            }
            int end = line.indexOf('"', line.indexOf('"', argument) + 1) + 1;
            line = line.substring(0, end) + ", " + MODE_ARGUMENT + mode.dslName
                    + "\"" + line.substring(end);
        }
        lines[target] = line;
        return String.join(LINE_BREAK, lines);
    }

    /**
     * The text of the one line binding a statement id.
     *
     * @param dslSource working graph holding the statement
     * @param id        statement id
     * @throws IllegalArgumentException when no line, or more than one line, binds the id
     * @return the line, without its line break
     */
    public static String statementLine(String dslSource, String id) {
        String[] lines = dslSource.split(LINE_BREAK, -1);
        return lines[bindingLine(lines, id)];
    }

    /**
     * The {@code node.port} a statement reads its geometry from.
     *
     * @param statement parsed statement, or {@code null}
     * @return the reference, or {@code null} when the statement has no node-valued geometry input
     */
    public static String geometryInput(PythonParser.ParsedNode statement) {
        return statement != null
                && statement.arguments.get(DEFAULT_UPSTREAM_PORT)
                        instanceof PythonParser.NodeReference reference
                ? reference.nodeId + "." + reference.portName
                : null;
    }

    /**
     * The statement that writes a ring mark label: the one whose {@code label} argument names it.
     *
     * @param statements a graph's parsed statements
     * @param label      ring mark label
     * @return the last statement labelling it, or {@code null} when none does
     */
    public static PythonParser.ParsedNode labelledStatement(
            List<PythonParser.ParsedNode> statements, String label) {
        PythonParser.ParsedNode labelled = null;
        for (PythonParser.ParsedNode statement : statements) {
            if (label.equals(statement.arguments.get(LABEL_ARGUMENT))) {
                labelled = statement;
            }
        }
        return labelled;
    }

    /**
     * The graph's one {@code ring_candidates} statement, the owner of every proposed ring.
     *
     * @param statements a graph's parsed statements
     * @throws IllegalArgumentException when the graph has more than one
     * @return the statement, or {@code null} when the graph has none
     */
    public static PythonParser.ParsedNode candidatesStatement(
            List<PythonParser.ParsedNode> statements) {
        PythonParser.ParsedNode found = null;
        for (PythonParser.ParsedNode statement : statements) {
            if (!CANDIDATES_NODE.equals(statement.type)) {
                continue;
            }
            if (found != null) {
                throw new IllegalArgumentException("the working graph has more than one "
                        + CANDIDATES_NODE + " statement, so a proposed ring has no one owner");
            }
            found = statement;
        }
        return found;
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
        return splineStatement(id, geometryInput, packedXyz, anchorCount, null);
    }

    /**
     * One spline ring statement through authored anchors, with the base normal its plane leans
     * toward when one is given, written with fixed-precision coordinates.
     *
     * @param id            statement id to bind the ring to
     * @param geometryInput reference the ring reads its geometry from, as {@code node.port}
     * @param packedXyz     authored anchors as packed xyz
     * @param anchorCount   anchors to write from the front of the array
     * @param baseNormal    the base normal as packed xyz, or {@code null} to write none
     * @return the statement text, without a trailing newline
     */
    public static String splineStatement(String id, String geometryInput, float[] packedXyz,
            int anchorCount, float[] baseNormal) {
        return id + " = spline_ring(geometry=" + geometryInput
                + ", " + POINTS_ARGUMENT + SurfaceWaypoints.format(packedXyz, anchorCount)
                + (baseNormal == null ? ""
                        : "\", " + NORMAL_ARGUMENT + SurfaceWaypoints.format(baseNormal, 1))
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
     * One ring statement that reproduces an edge loop element-exact: every loop vertex is a
     * pinned waypoint, so each arc is the single mesh edge between neighbours and FlipOut has no
     * free vertex to move.
     *
     * @param id            statement id, also the mark label
     * @param geometryInput reference the ring reads its geometry from, as {@code node.port}
     * @param loopXyz       the loop's vertex positions in walking order, packed xyz
     * @param vertexCount   loop vertices to write from the front of the array
     * @return the statement text, without a trailing newline
     */
    public static String exactLoopStatement(String id, String geometryInput, float[] loopXyz,
            int vertexCount) {
        return statement(id, geometryInput, loopXyz, vertexCount, true, true);
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
     * The one line binding a statement id.
     *
     * @param lines the graph's lines
     * @param id    statement id
     * @throws IllegalArgumentException when no line, or more than one line, binds the id
     * @return the line's index
     */
    private static int bindingLine(String[] lines, String id) {
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
        return target;
    }

    /**
     * One line with every whole-token occurrence of a reference swapped for another.
     *
     * @param text          line to rewrite
     * @param reference     {@code node.port} to replace, or {@code null} to leave the line alone
     * @param replacementTo {@code node.port} to put in its place, or {@code null} to leave it
     * @return the rewritten line
     */
    private static String rewired(String text, String reference, String replacementTo) {
        if (reference == null || replacementTo == null) {
            return text;
        }
        String out = text;
        for (int at = tokenIndex(out, reference, 0); at >= 0;
                at = tokenIndex(out, reference, at + replacementTo.length())) {
            out = out.substring(0, at) + replacementTo + out.substring(at + reference.length());
        }
        return out;
    }

    /**
     * Where a reference such as {@code ring_05.geometry} appears as a whole token, not as the
     * tail of a longer identifier.
     *
     * @param text  line to search
     * @param token reference to find
     * @param from  index to search from
     * @return the token's index, or -1 when it does not appear
     */
    private static int tokenIndex(String text, String token, int from) {
        boolean closedByIdentifier = isIdentifierPart(token.charAt(token.length() - 1));
        for (int at = text.indexOf(token, from); at >= 0; at = text.indexOf(token, at + 1)) {
            int end = at + token.length();
            boolean openEdge = at == 0 || !isIdentifierPart(text.charAt(at - 1));
            boolean closeEdge = !closedByIdentifier || end >= text.length()
                    || !isIdentifierPart(text.charAt(end));
            if (openEdge && closeEdge) {
                return at;
            }
        }
        return -1;
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
