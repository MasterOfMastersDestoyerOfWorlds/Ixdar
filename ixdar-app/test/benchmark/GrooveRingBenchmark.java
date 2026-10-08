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

import ixdar.geometry.mesh.data.CrestLineDetector;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.LimbAxis;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.PrincipalDirectionField;
import ixdar.geometry.mesh.data.SemanticPatchDecomposer;
import ixdar.geometry.mesh.data.paths.AuthoredSplineRing;
import ixdar.geometry.mesh.data.paths.Dijkstra;
import ixdar.geometry.mesh.data.paths.GirdlingPlane;
import ixdar.geometry.mesh.data.paths.RingSegmentMode;
import ixdar.geometry.mesh.data.paths.SplineAnchorFit;
import ixdar.geometry.mesh.data.paths.SurfaceCreases;
import ixdar.geometry.mesh.data.paths.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.SurfaceMetric;
import ixdar.geometry.mesh.data.paths.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.SurfaceSplineTracer;
import ixdar.geometry.mesh.data.paths.SurfaceWaypoints;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.nodes.data.SurfaceMetricNode;
import ixdar.geometry.mesh.nodes.selection.RingDslWriter;
import ixdar.geometry.mesh.nodes.selection.SplineRingNode;
import ixdar.geometry.mesh.quadlayout.crossfield.NDirectionField;
import ixdar.parsing.python.PythonParser;
import ixdar.scenes.ring.RingTool;

/**
 * One click at each saved ring's first anchor: how far the groove ring of every candidate crease
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
        List<PythonParser.ParsedNode> rings = new ArrayList<>();
        for (PythonParser.ParsedNode statement : graph.statements) {
            metricId = RingDslWriter.SURFACE_METRIC_NODE.equals(statement.type) && metricId == null
                    ? statement.id : metricId;
            if (SplineRingNode.DEFAULT_MARK_LABEL.equals(statement.type)) {
                rings.add(statement);
            }
        }
        assertNotNull(metricId, "the graph measures no surface");
        GeometryBundle bundle = (GeometryBundle) graph.executeGraphResult(graph.statements,
                metricId, GEOMETRY_PORT);
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
        ArrayMesh dense = SemanticPatchDecomposer.toArrayMesh(surface);
        int[] triangles = dense.copyFaceIndices();
        float[] positions = dense.copyPositions();
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
        ArrayMesh inward = new ArrayMesh(positions, null, triangles, XYZ);
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
        // Every candidate rings in the plane across its groove that the curvature gives, so the
        // table compares the signals alone.
        float[] across = new float[XYZ * vertexIdBound];
        float[] acrossRidge = new float[XYZ * vertexIdBound];
        float[] direction = new float[XYZ];
        for (int activeVertex = 0; activeVertex < surface.vertexCount(); activeVertex++) {
            curvature.dirMin(activeVertex, direction);
            System.arraycopy(direction, 0, across, XYZ * surface.vertexIdAt(activeVertex), XYZ);
            curvature.dirMax(activeVertex, direction);
            System.arraycopy(direction, 0, acrossRidge, XYZ * surface.vertexIdAt(activeVertex),
                    XYZ);
            convex[surface.vertexIdAt(activeVertex)] = curvature.kappaMax(activeVertex);
            magnitude[activeVertex] = Math.max(Math.abs(curvature.kappaMax(activeVertex)),
                    Math.abs(curvature.kappaMin(activeVertex)));
        }
        Arrays.sort(magnitude);
        candidates.put("valley crest lines", new SurfaceCreases(surface, onValley, 0.0, across));
        candidates.put("convex curvature (ridges)", new SurfaceCreases(surface, convex,
                magnitude[magnitude.length / 2], acrossRidge));
        start = System.nanoTime();
        NDirectionField field = new NDirectionField();
        field.build((HalfEdgeMesh) surface);
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
                bendSorted[bendSorted.length / 2], across));
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
        for (PythonParser.ParsedNode statement : rings) {
            float[] points = SurfaceWaypoints.parse(
                    String.valueOf(statement.arguments.get(SplineRingNode.POINTS.name)));
            float[] normal = SurfaceWaypoints.parse(
                    String.valueOf(statement.arguments.get(SplineRingNode.NORMAL.name)));
            int[] anchors = SurfaceWaypoints.snap(metric.nearestVertex, points,
                    points.length / XYZ);
            AuthoredSplineRing saved = new AuthoredSplineRing(geodesics);
            if (!saved.trace(anchors, anchors.length, normal.length == XYZ ? normal : null,
                    SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH)) {
                rows.add(statement.id + "\tthe saved ring does not trace: " + saved.failure);
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
                    statement.id, radius, savedValley / radius));
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
                    ring.creases = candidates.get(names.get(signal));
                    ok = ring.traceGroove(clicked, plane, SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH);
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
