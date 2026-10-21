package benchmark;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.analysis.curvature.CrestLineDetector;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.analysis.curvature.LimbAxis;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.analysis.curvature.PrincipalDirectionField;
import ixdar.geometry.mesh.data.analysis.segmentation.SemanticPatchDecomposer;
import ixdar.geometry.mesh.data.rings.AuthoredSplineRing;
import ixdar.numeric.graph.Dijkstra;
import ixdar.geometry.mesh.data.paths.query.GirdlingPlane;
import ixdar.geometry.mesh.data.rings.RingSegmentMode;
import ixdar.geometry.mesh.data.paths.spline.SplineAnchorFit;
import ixdar.geometry.mesh.data.paths.query.SurfaceCreases;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceMetric;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSplineTracer;
import ixdar.geometry.mesh.data.paths.spline.SurfaceWaypoints;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;
import ixdar.nodegraph.runtime.GraphNodeContext;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.nodegraph.nodes.data.SurfaceMetricNode;
import ixdar.nodegraph.nodes.selection.SplineRingNode;
import ixdar.geometry.mesh.data.fields.crossfield.NDirectionField;
import ixdar.tools.ring.RingDslWriter;
import ixdar.tools.ring.RingTool;

/**
 * One click at each saved ring's first anchor: how far the crease ring of every candidate crease
 * signal, and the geodesic preview, lands from that ring and from the valley crest lines.
 *
 * <pre>
 * mvn test -Dtest=GrooveRingBenchmark -Dbenchmark.ringGraph=tmp/crawfish_rings.dsl \
 *     -Dbenchmark.table=tmp/groove_rings.tsv
 * </pre>
 */
public final class GrooveRingBenchmark {

    private static final String RING_GRAPH_PROPERTY = "benchmark.ringGraph";

    private static final String TABLE_PROPERTY = "benchmark.table";

    private static final String GEOMETRY_PORT = "geometry";

    private static final String PREVIEW = "geodesic preview";

    // The signal SurfaceCreases.of builds, which the saved-plane row rings with.
    private static final String CHOSEN = "concave curvature";

    // The preview and the two saved-plane rows come before the candidate signals.
    private static final int FIRST_CANDIDATE = 3;

    // A one-click ring lands on the saved ring when their symmetric mean distance is under this
    // fraction of the saved ring's mean radius.
    private static final double LANDED_OF_RADIUS = 0.1;

    private static final double NEAR_OF_RADIUS = 0.2;

    private static final double HALF = 0.5;

    private static final int XYZ = 3;

    private static final String ROW_END = "\n";

    private static final String NONE = "-";

    private static final String NO_SURFACE = "the graph measures no surface";

    // The crossing sweep clicks about this many surface vertices, evenly spaced by index.
    private static final int CURSOR_SAMPLES = 400;

    /**
     * Writes the per-ring table and a summary per signal: rings landed, near, refused, mean
     * distance over radius, mean distance to the valley crest lines, and trace times.
     *
     * @throws Exception when the graph fails to run or the table cannot be written
     */
    @Test
    public void ringEverySavedRingFromOneClick() throws Exception {
        String graphPath = System.getProperty(RING_GRAPH_PROPERTY);
        assertNotNull(graphPath, "pass -D" + RING_GRAPH_PROPERTY + "=<a copy of a ring graph>");
        NodeGraphRuntime graph = NodeGraphRuntime.fromSource(
                new String(Files.readAllBytes(Path.of(graphPath)), StandardCharsets.UTF_8));
        String metricId = null;
        List<GraphNodeContext> rings = new ArrayList<>();
        for (GraphNodeContext statement : graph.root.declared) {
            metricId = RingDslWriter.SURFACE_METRIC_NODE.equals(statement.type) && metricId == null
                    ? statement.nodeAssignmentId() : metricId;
            if (SplineRingNode.DEFAULT_MARK_LABEL.equals(statement.type)) {
                rings.add(statement);
            }
        }
        assertNotNull(metricId, NO_SURFACE);
        GeometryBundle bundle = (GeometryBundle) graph.executeGraphResult(metricId, GEOMETRY_PORT);
        MeshTopology surface = bundle.mesh();
        SurfaceMetric metric = (SurfaceMetric) graph.getNodeOutput(metricId,
                SurfaceMetricNode.METRIC.name);
        SurfaceGeodesics geodesics = SurfaceGeodesics.over(metric);
        List<String> summary = new ArrayList<>();
        summary.add(String.format(Locale.ROOT, "surface: %d vertices, %d faces, mean edge %.5f",
                surface.vertexCount(), surface.faceCount(), metric.meanEdgeLength));

        // Candidate signals. The curvature estimate counts bending away from the normal as
        // negative, so the crest-line detector reads concave valleys only on an inward-wound
        // mesh; the dense copy is wound inward whatever the scan's winding.
        Map<String, SurfaceCreases> candidates = new LinkedHashMap<>();
        long start = System.nanoTime();
        candidates.put(CHOSEN, SurfaceCreases.of(surface));
        // The copy numbers vertices by active index and fans each face from its first corner.
        int[] activeVertexOfId = new int[surface.vertexIdBound()];
        float[] positions = new float[surface.vertexCount() * XYZ];
        Vector3f position = new Vector3f();
        for (int activeVertex = 0; activeVertex < surface.vertexCount(); activeVertex++) {
            int vertexId = surface.vertexIdAt(activeVertex);
            activeVertexOfId[vertexId] = activeVertex;
            surface.vertexPosition(vertexId, position);
            positions[XYZ * activeVertex] = position.x;
            positions[XYZ * activeVertex + 1] = position.y;
            positions[XYZ * activeVertex + 2] = position.z;
        }
        int triangleCount = 0;
        for (int activeFace = 0; activeFace < surface.faceCount(); activeFace++) {
            triangleCount += Math.max(0, surface.faceVertexCount(surface.faceIdAt(activeFace)) - 2);
        }
        int[] triangles = new int[triangleCount * XYZ];
        int cursor = 0;
        for (int activeFace = 0; activeFace < surface.faceCount(); activeFace++) {
            int faceId = surface.faceIdAt(activeFace);
            int firstCorner = activeVertexOfId[surface.faceVertexAt(faceId, 0)];
            for (int fan = 1; fan + 1 < surface.faceVertexCount(faceId); fan++) {
                triangles[cursor++] = firstCorner;
                triangles[cursor++] = activeVertexOfId[surface.faceVertexAt(faceId, fan)];
                triangles[cursor++] = activeVertexOfId[surface.faceVertexAt(faceId, fan + 1)];
            }
        }
        double signedVolume = 0.0;
        for (int corner = 0; corner < triangles.length; corner += XYZ) {
            Vector3f first = new Vector3f(positions[XYZ * triangles[corner]],
                    positions[XYZ * triangles[corner] + 1], positions[XYZ * triangles[corner] + 2]);
            Vector3f second = new Vector3f(positions[XYZ * triangles[corner + 1]],
                    positions[XYZ * triangles[corner + 1] + 1],
                    positions[XYZ * triangles[corner + 1] + 2]);
            Vector3f third = new Vector3f(positions[XYZ * triangles[corner + 2]],
                    positions[XYZ * triangles[corner + 2] + 1],
                    positions[XYZ * triangles[corner + 2] + 2]);
            signedVolume += first.dot(second.cross(third));
        }
        for (int corner = 0; signedVolume > 0.0 && corner < triangles.length; corner += XYZ) {
            int swap = triangles[corner + 1];
            triangles[corner + 1] = triangles[corner + 2];
            triangles[corner + 2] = swap;
        }
        HalfEdgeMesh inward = HalfEdgeMeshOps.buildFromIndexedMesh(positions, triangles);
        SemanticPatchDecomposer.EdgeDihedrals dihedrals =
                SemanticPatchDecomposer.computeEdgeDihedrals(inward);
        PrincipalDirectionField curvature = PrincipalDirectionField.compute(inward, dihedrals);
        CrestLineDetector.CrestLines crest = CrestLineDetector.detect(inward, dihedrals,
                curvature);
        double curvatureMillis = (System.nanoTime() - start) / 1e6;
        int vertexIdBound = metric.vertexIdBound;
        float[] onValley = new float[vertexIdBound];
        float[] convex = new float[vertexIdBound];
        float[] magnitude = new float[surface.vertexCount()];
        List<Integer> valleyVertexIds = new ArrayList<>();
        for (int[] line : crest.valleyPolylines) {
            for (int activeVertex : line) {
                onValley[surface.vertexIdAt(activeVertex)] = 1f;
                valleyVertexIds.add(surface.vertexIdAt(activeVertex));
            }
        }
        for (int activeVertex = 0; activeVertex < surface.vertexCount(); activeVertex++) {
            convex[surface.vertexIdAt(activeVertex)] = curvature.kappaMax(activeVertex);
            magnitude[activeVertex] = Math.max(Math.abs(curvature.kappaMax(activeVertex)),
                    Math.abs(curvature.kappaMin(activeVertex)));
        }
        Arrays.sort(magnitude);
        candidates.put("valley crest lines", new SurfaceCreases(surface, onValley, 0.0));
        candidates.put("convex curvature (ridges)", new SurfaceCreases(surface, convex,
                magnitude[magnitude.length / 2]));
        start = System.nanoTime();
        NDirectionField field = new NDirectionField();
        field.build((HalfEdgeMesh) surface, metric);
        float[] bend = new float[vertexIdBound];
        float[] bendSorted = new float[surface.vertexCount()];
        for (int activeVertex = 0; activeVertex < surface.vertexCount(); activeVertex++) {
            bendSorted[activeVertex] = (float) Math.hypot(field.hopfField[2 * activeVertex],
                    field.hopfField[2 * activeVertex + 1]);
            bend[surface.vertexIdAt(activeVertex)] = bendSorted[activeVertex];
        }
        Arrays.sort(bendSorted);
        double bendFieldMillis = (System.nanoTime() - start) / 1e6;
        candidates.put("Hopf bend (unsigned)", new SurfaceCreases(surface, bend,
                bendSorted[bendSorted.length / 2]));
        summary.add(String.format(Locale.ROOT, "curvature + crest lines %.0f ms, cross field for "
                + "the bend %.0f ms", curvatureMillis, bendFieldMillis));
        for (Map.Entry<String, SurfaceCreases> candidate : candidates.entrySet()) {
            summary.add(String.format(Locale.ROOT, "%s: creases built in %.0f ms",
                    candidate.getKey(), candidate.getValue().buildMillis));
        }

        // Distance over the mesh's edges from every valley crest-line vertex.
        double[] edgeLength = new double[0];
        Vector3f from = new Vector3f();
        Vector3f to = new Vector3f();
        for (int activeEdge = 0; activeEdge < surface.edgeCount(); activeEdge++) {
            int edgeId = surface.edgeIdAt(activeEdge);
            int halfEdge = surface.edgeHalfEdge(edgeId);
            surface.vertexPosition(surface.halfEdgeVertex(halfEdge), from);
            surface.vertexPosition(surface.halfEdgeEndVertex(halfEdge), to);
            edgeLength = edgeLength.length > edgeId ? edgeLength
                    : Arrays.copyOf(edgeLength, Math.max(edgeId + 1, 2 * edgeLength.length));
            edgeLength[edgeId] = from.distance(to);
        }
        int[] valleySources = new int[valleyVertexIds.size()];
        for (int source = 0; source < valleySources.length; source++) {
            valleySources[source] = valleyVertexIds.get(source);
        }
        double[] toValley = Dijkstra.forest(surface, valleySources, edgeLength).distance;

        LimbAxis limbAxis = new LimbAxis();
        limbAxis.cacheFor(surface);
        GirdlingPlane girdle = new GirdlingPlane();
        List<String> names = new ArrayList<>();
        names.add(PREVIEW);
        names.add("geodesic, saved plane");
        names.add(CHOSEN + ", saved plane");
        names.addAll(candidates.keySet());
        int signals = names.size();
        int[] landed = new int[signals];
        int[] near = new int[signals];
        int[] refused = new int[signals];
        double[] ratioSum = new double[signals];
        double[] valleySum = new double[signals];
        double[] millisSum = new double[signals];
        double[] millisWorst = new double[signals];
        int[] traced = new int[signals];
        double savedValleySum = 0.0;
        int measured = 0;
        StringBuilder header = new StringBuilder("ring\tradius\tsaved to valley");
        for (String name : names) {
            header.append('\t').append(name).append(" off saved / radius\t").append(name)
                    .append(" radius / saved radius\t").append(name)
                    .append(" anchor off saved / radius\t").append(name)
                    .append(" plane tilt from saved deg\t").append(name).append(" to valley\t")
                    .append(name).append(" ms");
        }
        List<String> rows = new ArrayList<>();
        rows.add(header.toString());
        for (GraphNodeContext statement : rings) {
            float[] points = SurfaceWaypoints.parse(
                    String.valueOf(statement.inputSources.get(SplineRingNode.POINTS.name)));
            float[] normal = SurfaceWaypoints.parse(
                    String.valueOf(statement.inputSources.get(SplineRingNode.NORMAL.name)));
            int[] anchors = SurfaceWaypoints.snap(metric.nearestVertex, points,
                    points.length / XYZ);
            AuthoredSplineRing saved = new AuthoredSplineRing(geodesics);
            if (!saved.trace(anchors, anchors.length, normal.length == XYZ ? normal : null,
                    SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH)) {
                rows.add(statement.nodeAssignmentId() + "\tthe saved ring does not trace: "
                        + saved.failure);
                continue;
            }
            float[] savedLine = SurfaceSpline.of(saved.tracer).polyline;
            double radius = SplineAnchorFit.meanRadiusOf(savedLine, savedLine.length / XYZ);
            double savedValley = 0.0;
            for (int point = 0; point < savedLine.length; point += XYZ) {
                savedValley += valleyDistance(metric, toValley, savedLine, point)
                        / (savedLine.length / XYZ);
            }
            measured++;
            savedValleySum += savedValley / radius;
            // The click: the saved ring's first authored anchor, on the plane the ring tool's
            // hover finds girdling the part there.
            int clicked = saved.authoredVertexId[0];
            Vector3f click = new Vector3f();
            surface.vertexPosition(clicked, click);
            float[] clickXyz = { click.x, click.y, click.z };
            int faceId = surface.vertexFaceAt(clicked, 0);
            float[] axis = new float[XYZ];
            boolean seeded = limbAxis.skeletonAxisAt(click.x, click.y, click.z, axis)
                    || limbAxis.curvatureAxisAt(faceId, axis);
            StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "%s\t%.5f\t%.3f",
                    statement.nodeAssignmentId(), radius, savedValley / radius));
            if (!girdle.find(surface, faceId, clickXyz, seeded ? axis : null)) {
                rows.add(row.append("\tno girdling plane at the click").toString());
                continue;
            }
            float[] plane = RingTool.written(girdle.normal);
            for (int signal = 0; signal < signals; signal++) {
                AuthoredSplineRing ring = new AuthoredSplineRing(geodesics);
                long traceStart = System.nanoTime();
                boolean ok;
                if (signal == 0) {
                    ok = ring.trace(new int[] { clicked }, 1, plane,
                            SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH);
                } else if (signal < FIRST_CANDIDATE) {
                    // The saved ring's own plane, which no click knows: how much of the miss is
                    // the plane and how much the path.
                    ring.mode = signal == 1 ? RingSegmentMode.GEODESIC : RingSegmentMode.CREASE;
                    ring.creases = candidates.get(CHOSEN);
                    ok = ring.trace(new int[] { clicked }, 1, RingTool.written(saved.planeNormal),
                            SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH);
                } else {
                    // The crease ring a click drafts: the click on the hover's girdling plane.
                    ring.mode = RingSegmentMode.CREASE;
                    ring.creases = candidates.get(names.get(signal));
                    ok = ring.trace(new int[] { clicked }, 1, plane,
                            SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH);
                }
                double millis = (System.nanoTime() - traceStart) / 1e6;
                if (!ok) {
                    refused[signal]++;
                    row.append("\trefused: ").append(ring.failure).append('\t').append(NONE)
                            .append('\t').append(NONE).append('\t').append(NONE).append('\t')
                            .append(NONE)
                            .append('\t').append(String.format(Locale.ROOT, "%.0f", millis));
                    continue;
                }
                float[] line = SurfaceSpline.of(ring.tracer).polyline;
                int linePoints = line.length / XYZ;
                double away = 0.0;
                double valley = 0.0;
                for (int point = 0; point < line.length; point += XYZ) {
                    away += SurfaceSpline.distanceToPolyline(savedLine, savedLine.length / XYZ,
                            line[point], line[point + 1], line[point + 2]) / linePoints;
                    valley += valleyDistance(metric, toValley, line, point) / linePoints;
                }
                double back = 0.0;
                for (int point = 0; point < savedLine.length; point += XYZ) {
                    back += SurfaceSpline.distanceToPolyline(line, linePoints, savedLine[point],
                            savedLine[point + 1], savedLine[point + 2]) / (savedLine.length / XYZ);
                }
                double ratio = HALF * (away + back) / radius;
                traced[signal]++;
                landed[signal] += ratio < LANDED_OF_RADIUS ? 1 : 0;
                near[signal] += ratio < NEAR_OF_RADIUS ? 1 : 0;
                ratioSum[signal] += ratio;
                valleySum[signal] += valley / radius;
                millisSum[signal] += millis;
                millisWorst[signal] = Math.max(millisWorst[signal], millis);
                Vector3f anchor = new Vector3f();
                surface.vertexPosition(ring.authoredVertexId[0], anchor);
                double tilt = Math.toDegrees(Math.acos(Math.min(1.0, Math.abs(
                        ring.planeNormal[0] * saved.planeNormal[0]
                                + ring.planeNormal[1] * saved.planeNormal[1]
                                + ring.planeNormal[2] * saved.planeNormal[2]))));
                row.append(String.format(Locale.ROOT, "\t%.3f\t%.3f\t%.3f\t%.1f\t%.3f\t%.0f",
                        ratio, SplineAnchorFit.meanRadiusOf(line, linePoints) / radius,
                        SurfaceSpline.distanceToPolyline(savedLine, savedLine.length / XYZ,
                                anchor.x, anchor.y, anchor.z) / radius, tilt, valley / radius,
                        millis));
            }
            rows.add(row.toString());
        }
        summary.add(String.format(Locale.ROOT, "%d saved rings measured; saved rings lie %.3f "
                + "radii from the valley crest lines on average", measured,
                savedValleySum / Math.max(1, measured)));
        summary.add("signal\tlanded (< " + LANDED_OF_RADIUS + " r)\tnear (< " + NEAR_OF_RADIUS
                + " r)\trefused\tmean off saved / r\tmean to valley / r\tmean ms\tworst ms");
        for (int signal = 0; signal < signals; signal++) {
            int count = Math.max(1, traced[signal]);
            summary.add(String.format(Locale.ROOT, "%s\t%d\t%d\t%d\t%.3f\t%.3f\t%.0f\t%.0f",
                    names.get(signal), landed[signal], near[signal], refused[signal],
                    ratioSum[signal] / count, valleySum[signal] / count, millisSum[signal] / count,
                    millisWorst[signal]));
        }
        Path table = Path.of(System.getProperty(TABLE_PROPERTY, "target/groove-rings.tsv"));
        List<String> written = new ArrayList<>(summary);
        written.add("");
        written.addAll(rows);
        Files.write(table, (String.join(ROW_END, written) + ROW_END)
                .getBytes(StandardCharsets.UTF_8));
        assertTrue(measured > 0, "no saved ring could be measured");
    }

    /**
     * Crease and geodesic rings at a spread of cursor vertices on each one's girdling plane: how
     * often each crosses itself, how much of its length runs in a groove (valley strength at
     * least one half), and how long it takes.
     *
     * <pre>
     * mvn test -Dtest=GrooveRingBenchmark#crossingsOverManyCursorVertices \
     *     -Dbenchmark.ringGraph=$PWD/tmp/crawfish_rings.dsl -Dbenchmark.table=$PWD/tmp/x.txt
     * </pre>
     *
     * @throws Exception when the graph fails to run or the table cannot be written
     */
    @Test
    public void crossingsOverManyCursorVertices() throws Exception {
        String graphPath = System.getProperty(RING_GRAPH_PROPERTY);
        assertNotNull(graphPath, "pass -D" + RING_GRAPH_PROPERTY + "=<a copy of a ring graph>");
        NodeGraphRuntime graph = NodeGraphRuntime.fromSource(
                new String(Files.readAllBytes(Path.of(graphPath)), StandardCharsets.UTF_8));
        String metricId = null;
        for (GraphNodeContext statement : graph.root.declared) {
            metricId = RingDslWriter.SURFACE_METRIC_NODE.equals(statement.type) && metricId == null
                    ? statement.nodeAssignmentId() : metricId;
        }
        assertNotNull(metricId, NO_SURFACE);
        GeometryBundle bundle = (GeometryBundle) graph.executeGraphResult(metricId, GEOMETRY_PORT);
        MeshTopology surface = bundle.mesh();
        SurfaceMetric metric = (SurfaceMetric) graph.getNodeOutput(metricId,
                SurfaceMetricNode.METRIC.name);
        SurfaceGeodesics geodesics = SurfaceGeodesics.over(metric);
        SurfaceCreases creases = SurfaceCreases.of(surface);
        LimbAxis limbAxis = new LimbAxis();
        limbAxis.cacheFor(surface);
        GirdlingPlane girdle = new GirdlingPlane();
        int stride = Math.max(1, surface.vertexCount() / CURSOR_SAMPLES);
        AuthoredSplineRing geodesic = new AuthoredSplineRing(geodesics);
        AuthoredSplineRing crease = new AuthoredSplineRing(geodesics);
        crease.mode = RingSegmentMode.CREASE;
        crease.creases = creases;
        int planes = 0;
        int[] tracedCount = new int[2];
        int[] crossing = new int[2];
        double[] grooveSum = new double[2];
        double[] millisSum = new double[2];
        double[] millisWorst = new double[2];
        Vector3f click = new Vector3f();
        float[] axis = new float[XYZ];
        for (int activeVertex = 0; activeVertex < surface.vertexCount();
                activeVertex += stride) {
            int clicked = surface.vertexIdAt(activeVertex);
            if (surface.vertexFaceCount(clicked) == 0) {
                continue;
            }
            surface.vertexPosition(clicked, click);
            float[] clickXyz = { click.x, click.y, click.z };
            int faceId = surface.vertexFaceAt(clicked, 0);
            boolean seeded = limbAxis.skeletonAxisAt(click.x, click.y, click.z, axis)
                    || limbAxis.curvatureAxisAt(faceId, axis);
            if (!girdle.find(surface, faceId, clickXyz, seeded ? axis : null)) {
                continue;
            }
            planes++;
            float[] plane = RingTool.written(girdle.normal);
            for (int mode = 0; mode < 2; mode++) {
                AuthoredSplineRing ring = mode == 0 ? geodesic : crease;
                long start = System.nanoTime();
                boolean ok = ring.trace(new int[] { clicked }, 1, plane,
                        SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH);
                double millis = (System.nanoTime() - start) / 1e6;
                if (!ok) {
                    continue;
                }
                tracedCount[mode]++;
                millisSum[mode] += millis;
                millisWorst[mode] = Math.max(millisWorst[mode], millis);
                grooveSum[mode] += grooveFraction(surface, creases, ring.tracer);
                crossing[mode] += ring.simple ? 0 : 1;
            }
        }
        List<String> summary = new ArrayList<>();
        summary.add(String.format(Locale.ROOT, "%d cursor vertices with a girdling plane (every "
                + "%d of %d)", planes, stride, surface.vertexCount()));
        String[] names = { "geodesic", "crease" };
        for (int mode = 0; mode < 2; mode++) {
            int count = Math.max(1, tracedCount[mode]);
            summary.add(String.format(Locale.ROOT, "%s: traced %d, crossing %d (%.1f%%), mean "
                    + "in-groove length %.3f, mean %.0f ms, worst %.0f ms", names[mode],
                    tracedCount[mode], crossing[mode], 100.0 * crossing[mode] / count,
                    grooveSum[mode] / count, millisSum[mode] / count, millisWorst[mode]));
        }
        Path table = Path.of(System.getProperty(TABLE_PROPERTY, "target/crease-crossings.txt"));
        Files.write(table, (String.join(ROW_END, summary) + ROW_END)
                .getBytes(StandardCharsets.UTF_8));
        assertTrue(planes > 0, "no cursor vertex found a girdling plane");
    }

    /**
     * Fraction of a traced ring's surface length whose both ends have valley strength of at least
     * one half, a crossed edge's strength interpolated along it.
     */
    private static double grooveFraction(MeshTopology surface, SurfaceCreases creases,
            SurfaceSplineTracer tracer) {
        double total = 0.0;
        double inGroove = 0.0;
        double previousStrength = -1.0;
        double[] previous = null;
        double firstStrength = -1.0;
        double[] first = null;
        for (int segment = 0; segment <= tracer.anchorCount; segment++) {
            int which = segment % tracer.anchorCount;
            int count = segment == tracer.anchorCount ? 1 : tracer.segmentVertexId[which].length;
            for (int point = 0; point < count; point++) {
                double strength;
                int vertexId = tracer.segmentVertexId[which][point];
                int edgeId = tracer.segmentEdgeId[which][point];
                if (vertexId >= 0) {
                    strength = creases.valleyStrength[vertexId];
                } else if (edgeId >= 0) {
                    int halfEdge = surface.edgeHalfEdge(edgeId);
                    double fraction = tracer.segmentFraction[which][point];
                    strength = (1.0 - fraction)
                            * creases.valleyStrength[surface.halfEdgeVertex(halfEdge)]
                            + fraction * creases.valleyStrength[surface.halfEdgeEndVertex(halfEdge)];
                } else {
                    int faceId = tracer.segmentFaceId[which][point];
                    strength = 0.0;
                    for (int corner = 0; corner < XYZ; corner++) {
                        strength += creases.valleyStrength[surface.faceVertexAt(faceId, corner)]
                                / XYZ;
                    }
                }
                double[] here = { tracer.segmentSurfaceXyz[which][XYZ * point],
                    tracer.segmentSurfaceXyz[which][XYZ * point + 1],
                    tracer.segmentSurfaceXyz[which][XYZ * point + 2] };
                if (previous != null) {
                    double length = Math.sqrt((here[0] - previous[0]) * (here[0] - previous[0])
                            + (here[1] - previous[1]) * (here[1] - previous[1])
                            + (here[2] - previous[2]) * (here[2] - previous[2]));
                    total += length;
                    inGroove += previousStrength >= HALF && strength >= HALF ? length : 0.0;
                }
                previous = here;
                previousStrength = strength;
            }
        }
        return total > 0.0 ? inGroove / total : 0.0;
    }

    /**
     * Distance over the mesh's edges from the vertex nearest one polyline point to the valley
     * crest lines.
     */
    private static double valleyDistance(SurfaceMetric metric, double[] toValley,
            float[] polyline, int coordinate) {
        try {
            int vertexId = metric.nearestVertex.find(polyline[coordinate],
                    polyline[coordinate + 1], polyline[coordinate + 2]);
            return vertexId < 0 ? 0.0 : toValley[vertexId];
        } catch (IllegalStateException tie) {
            // The repaired scan splits a few hundred vertices into coincident copies, which tie
            // for every point; such a point is counted on the crest line.
            return 0.0;
        }
    }
}
