package benchmark;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.EdgeMarks;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.paths.AuthoredSplineRing;
import ixdar.geometry.mesh.data.paths.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.SurfacePathCrossings;
import ixdar.geometry.mesh.data.paths.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.SurfaceSplineTracer;
import ixdar.geometry.mesh.data.paths.SurfaceWaypoints;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.nodes.selection.SplineRingNode;
import ixdar.parsing.python.PythonParser;
import ixdar.scenes.ring.RingTool;

/**
 * Traces one saved spline ring's anchors in shuffled orders, as one list and click by click the
 * way the ring tool adds them, and writes a table of which orders cross themselves. Run it on a
 * copy of a ring graph:
 *
 * <pre>
 * mvn test -Dtest=RingAnchorOrderBenchmark -Dbenchmark.ringGraph=tmp/crawfish_rings.dsl \
 *     -Dbenchmark.ring=ring_03 -Dbenchmark.table=tmp/ring_03_orders.tsv
 * </pre>
 */
public final class RingAnchorOrderBenchmark {

    private static final String RING_GRAPH_PROPERTY = "benchmark.ringGraph";

    private static final String RING_PROPERTY = "benchmark.ring";

    private static final String TABLE_PROPERTY = "benchmark.table";

    private static final String SHAPES_PROPERTY = "benchmark.ringShapes";

    private static final String DEFAULT_RING = "ring_03";

    private static final String GEOMETRY_PORT = "geometry";

    private static final int SHUFFLES_PER_FIRST_ANCHOR = 6;

    private static final int CLICK_SHUFFLES = 6;

    private static final long SEED = 44L;

    private static final int NEAR_TIE_LOOP_STEPS = 2;

    private static final int XYZ = 3;

    private static final String ROW_END = "\n";

    private static final String YES = "yes";

    private static final String NO = "no";

    private final List<String> rows = new ArrayList<>();

    private MeshTopology surface;

    private SurfaceGeodesics geodesics;

    private int[] savedVertexId;

    private float[] baseNormal;

    private String savedRingOrder;

    /**
     * Writes the order table for one ring: the saved order, shuffles keeping its first anchor,
     * one shuffle led by each anchor, and click-by-click adds of shuffled orders.
     *
     * @throws Exception when the graph fails to run or the table cannot be written
     */
    @Test
    public void traceShuffledAnchorOrders() throws Exception {
        String graphPath = System.getProperty(RING_GRAPH_PROPERTY);
        assertNotNull(graphPath, "pass -D" + RING_GRAPH_PROPERTY + "=<a copy of a ring graph>");
        String ringLabel = System.getProperty(RING_PROPERTY, DEFAULT_RING);
        NodeGraphRuntime graph = NodeGraphRuntime.fromSource(
                new String(Files.readAllBytes(Path.of(graphPath)), StandardCharsets.UTF_8));
        PythonParser.ParsedNode ring = null;
        String surfaceNode = null;
        for (PythonParser.ParsedNode statement : graph.statements) {
            if (SplineRingNode.DEFAULT_MARK_LABEL.equals(statement.type) && surfaceNode == null
                    && statement.arguments.get(SplineRingNode.GEOMETRY.name)
                            instanceof PythonParser.NodeReference reference) {
                surfaceNode = reference.nodeId;
            }
            if (statement.id.equals(ringLabel)) {
                ring = statement;
            }
        }
        assertNotNull(ring, "no statement " + ringLabel);
        assertNotNull(surfaceNode, "no spline_ring reads a surface");
        GeometryBundle bundle = (GeometryBundle) graph.executeGraphResult(graph.statements,
                surfaceNode, GEOMETRY_PORT);
        surface = HalfEdgeMeshEngine.fromMeshTopology(bundle.mesh());
        geodesics = SurfaceGeodesics.over(surface);
        float[] points = SurfaceWaypoints.parse(
                String.valueOf(ring.arguments.get(SplineRingNode.POINTS.name)));
        baseNormal = SurfaceWaypoints.parse(
                String.valueOf(ring.arguments.get(SplineRingNode.NORMAL.name)));
        savedVertexId = SurfaceWaypoints.snap(surface, points, points.length / XYZ);
        int anchors = savedVertexId.length;
        rows.add("run\tmode\tclicked\tring order\tsame ring as saved\tskipped adds\tloop crossings"
                + "\tloop length\ttied anchors\tnear-tied anchors\tfurthest off loop (edges)"
                + "\tself-crossings\trevisited vertices\tfirst crossing to nearest anchor (edges)"
                + "\tsnapped loop simple\tms");

        int[] identity = new int[anchors];
        for (int anchor = 0; anchor < anchors; anchor++) {
            identity[anchor] = anchor;
        }
        traceWhole("saved", identity);
        Random random = new Random(SEED);
        for (int shuffle = 0; shuffle < SHUFFLES_PER_FIRST_ANCHOR; shuffle++) {
            traceWhole("first 0 #" + shuffle, shuffledAfter(identity, 0, random));
        }
        for (int first = 1; first < anchors; first++) {
            traceWhole("first " + first, shuffledAfter(identity, first, random));
        }
        // The ring tool's add: the first click opens the ring, every later one is inserted where
        // it adds the least surface length, and a click no slot keeps simple is refused and
        // clicked again after the next one the ring takes; "skipped" counts the refusals.
        for (int shuffle = 0; shuffle <= CLICK_SHUFFLES; shuffle++) {
            int[] clicks = shuffle == 0 ? identity : shuffledAfter(identity,
                    shuffle % 2 == 0 ? 0 : random.nextInt(anchors), random);
            long start = System.nanoTime();
            AuthoredSplineRing clicked = new AuthoredSplineRing(geodesics);
            boolean traced = clicked.trace(new int[] { savedVertexId[clicks[0]] }, 1, baseNormal,
                    SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH);
            int[] pending = Arrays.copyOfRange(clicks, 1, clicks.length);
            int pendingCount = pending.length;
            int refusedInARow = 0;
            int skipped = 0;
            while (traced && pendingCount > 0 && refusedInARow < pendingCount) {
                int click = pending[0];
                System.arraycopy(pending, 1, pending, 0, pendingCount - 1);
                if (clicked.insert(savedVertexId[click], baseNormal,
                        SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH)) {
                    pendingCount--;
                    refusedInARow = 0;
                } else {
                    pending[pendingCount - 1] = click;
                    refusedInARow++;
                    skipped++;
                }
            }
            report("clicks #" + shuffle, "click by click", clicks, clicked, traced, skipped,
                    start);
        }
        Path table = Path.of(System.getProperty(TABLE_PROPERTY, "target/ring-anchor-orders.tsv"));
        Files.write(table, (String.join(ROW_END, rows) + ROW_END).getBytes(StandardCharsets.UTF_8));
        assertTrue(Files.size(table) > 0);
    }

    /**
     * Runs a whole ring graph read-only and writes each spline ring's fingerprint, crossings and
     * shape; the first run keeps each polyline under {@code benchmark.ringShapes}, and later runs
     * report how far each ring moved from it, in mean edges.
     *
     * @throws Exception when the graph fails to run or a file cannot be read or written
     */
    @Test
    public void fingerprintEverySplineRing() throws Exception {
        String graphPath = System.getProperty(RING_GRAPH_PROPERTY);
        assertNotNull(graphPath, "pass -D" + RING_GRAPH_PROPERTY + "=<a copy of a ring graph>");
        Path shapes = Path.of(System.getProperty(SHAPES_PROPERTY, "target/ring-shapes"));
        Files.createDirectories(shapes);
        NodeGraphRuntime graph = NodeGraphRuntime.fromSource(
                new String(Files.readAllBytes(Path.of(graphPath)), StandardCharsets.UTF_8));
        List<PythonParser.ParsedNode> statements = graph.statements;
        GeometryBundle bundle = (GeometryBundle) graph.executeGraphResult(statements,
                statements.get(statements.size() - 1).id, GEOMETRY_PORT);
        MeshTopology mesh = bundle.mesh();
        double meanEdge = SurfaceGeodesics.meanEdgeLengthOf(mesh);
        String graphName = Path.of(graphPath).getFileName().toString().replace(".dsl", "");
        rows.add("ring\tanchors (authored)\tmarked edges\tfingerprint\tself-crossings"
                + "\trevisited vertices\tsnapped loop simple\tlength\tmoved from baseline (edges)");
        for (PythonParser.ParsedNode statement : statements) {
            if (!SplineRingNode.DEFAULT_MARK_LABEL.equals(statement.type)) {
                continue;
            }
            String label = String.valueOf(statement.arguments.get(SplineRingNode.LABEL.name));
            SurfaceSpline spline = SurfaceSpline.inBundle(bundle, label);
            boolean[] marks = EdgeMarks.bools(bundle, label);
            int points = spline.polyline.length / XYZ - 1;
            SurfacePathCrossings crossings = new SurfacePathCrossings();
            crossings.isSimple(mesh, spline.surfacePath());
            Path baseline = shapes.resolve(graphName + "_" + label + ".xyz");
            String moved = "baseline written";
            if (Files.exists(baseline)) {
                float[] old = SurfaceWaypoints.parse(
                        new String(Files.readAllBytes(baseline), StandardCharsets.UTF_8));
                int oldPoints = old.length / XYZ;
                double farthest = 0.0;
                for (int point = 0; point <= points; point++) {
                    farthest = Math.max(farthest, SurfaceSpline.distanceToPolyline(old, oldPoints,
                            spline.polyline[XYZ * point], spline.polyline[XYZ * point + 1],
                            spline.polyline[XYZ * point + 2]));
                }
                for (int point = 0; point < oldPoints; point++) {
                    farthest = Math.max(farthest, SurfaceSpline.distanceToPolyline(
                            spline.polyline, points + 1, old[XYZ * point], old[XYZ * point + 1],
                            old[XYZ * point + 2]));
                }
                moved = String.format(Locale.ROOT, "%.2f", farthest / meanEdge);
            } else {
                Files.write(baseline, SurfaceWaypoints.format(spline.polyline, points + 1)
                        .getBytes(StandardCharsets.UTF_8));
            }
            rows.add(String.format(Locale.ROOT, "%s\t%d (%d)\t%d\t%s\t%d\t%d\t%s\t%.5f\t%s", label,
                    spline.anchorCount, spline.authoredCount(), spline.markedEdgeCount,
                    EdgeMarks.fingerprint(mesh, marks), crossings.crossings,
                    crossings.revisitedVertices,
                    RingTool.orderedLoop(mesh, marks).length > 0 ? YES : NO, spline.length,
                    moved));
        }
        Path table = shapes.resolve(graphName + "_fingerprints.tsv");
        Files.write(table, (String.join(ROW_END, rows) + ROW_END).getBytes(StandardCharsets.UTF_8));
        assertTrue(Files.size(table) > 0);
    }

    private static int[] shuffledAfter(int[] identity, int first, Random random) {
        int[] order = new int[identity.length];
        order[0] = first;
        int next = 1;
        for (int anchor : identity) {
            if (anchor != first) {
                order[next++] = anchor;
            }
        }
        for (int slot = order.length - 1; slot > 1; slot--) {
            int swap = 1 + random.nextInt(slot);
            int held = order[slot];
            order[slot] = order[swap];
            order[swap] = held;
        }
        return order;
    }

    private void traceWhole(String run, int[] order) {
        long start = System.nanoTime();
        AuthoredSplineRing ring = new AuthoredSplineRing(geodesics);
        int[] vertexIds = new int[order.length];
        for (int anchor = 0; anchor < order.length; anchor++) {
            vertexIds[anchor] = savedVertexId[order[anchor]];
        }
        boolean traced = ring.trace(vertexIds, vertexIds.length, baseNormal,
                SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH);
        report(run, "whole list", order, ring, traced, 0, start);
    }

    private void report(String run, String mode, int[] clicked, AuthoredSplineRing ring,
            boolean traced, int skipped, long start) {
        if (!traced) {
            rows.add(run + "\t" + mode + "\t" + joined(clicked) + "\tfailed: " + ring.failure);
            return;
        }
        // The ring order in saved indices, from its smallest entry in whichever direction reads
        // smaller, so one cycle always prints the same.
        int count = ring.authoredVertexId.length;
        int[] savedIndex = new int[count];
        for (int anchor = 0; anchor < count; anchor++) {
            for (int saved = 0; saved < savedVertexId.length; saved++) {
                savedIndex[anchor] = savedVertexId[saved] == ring.authoredVertexId[anchor] ? saved
                        : savedIndex[anchor];
            }
        }
        int lowest = 0;
        for (int index = 1; index < count; index++) {
            lowest = savedIndex[index] < savedIndex[lowest] ? index : lowest;
        }
        int[] forward = new int[count];
        int[] backward = new int[count];
        for (int step = 0; step < count; step++) {
            forward[step] = savedIndex[(lowest + step) % count];
            backward[step] = savedIndex[Math.floorMod(lowest - step, count)];
        }
        String ringOrder = joined(Arrays.compare(forward, backward) <= 0 ? forward : backward);
        if (savedRingOrder == null) {
            savedRingOrder = ringOrder;
        }
        int[] loopPoint = ring.fit.authoredPoint;
        int steps = ring.cut.stepCount;
        int tied = 0;
        int nearTied = 0;
        double furthest = 0.0;
        for (int anchor = 0; anchor < loopPoint.length; anchor++) {
            boolean tie = false;
            boolean near = false;
            for (int other = 0; other < loopPoint.length; other++) {
                int apart = Math.floorMod(loopPoint[other] - loopPoint[anchor], steps);
                apart = Math.min(apart, steps - apart);
                tie |= other != anchor && apart == 0;
                near |= other != anchor && apart <= NEAR_TIE_LOOP_STEPS;
            }
            tied += tie ? 1 : 0;
            nearTied += near ? 1 : 0;
            furthest = Math.max(furthest, ring.fit.authoredAllowance[anchor]);
        }
        SurfacePathCrossings crossings = new SurfacePathCrossings();
        crossings.isSimple(surface, ring.tracer.tracedRing());
        double crossingToAnchor = Double.NaN;
        Vector3f anchorPosition = new Vector3f();
        for (int anchor = 0; crossings.crossings + crossings.revisitedVertices > 0
                && anchor < count; anchor++) {
            surface.vertexPosition(ring.authoredVertexId[anchor], anchorPosition);
            double distance = anchorPosition.distance(crossings.firstCrossingXyz[0],
                    crossings.firstCrossingXyz[1], crossings.firstCrossingXyz[2]);
            crossingToAnchor = Double.isNaN(crossingToAnchor) ? distance
                    : Math.min(crossingToAnchor, distance);
        }
        SurfaceSpline spline = SurfaceSpline.of(ring.tracer);
        boolean snappedSimple = RingTool.orderedLoop(surface, spline.markedByEdgeId).length > 0;
        rows.add(String.format(Locale.ROOT, "%s\t%s\t%s\t%s\t%s\t%d\t%d\t%.4f\t%d\t%d\t%.1f\t%d\t%d"
                + "\t%.1f\t%s\t%.0f", run, mode, joined(clicked), ringOrder,
                ringOrder.equals(savedRingOrder) ? YES : NO, skipped, steps, ring.cut.length,
                tied, nearTied, furthest / geodesics.meanEdgeLength, crossings.crossings,
                crossings.revisitedVertices, crossingToAnchor / geodesics.meanEdgeLength,
                snappedSimple ? YES : NO, (System.nanoTime() - start) / 1e6));
    }

    private static String joined(int[] values) {
        StringBuilder text = new StringBuilder();
        for (int value : values) {
            text.append(text.length() == 0 ? "" : " ").append(value);
        }
        return text.toString();
    }
}
