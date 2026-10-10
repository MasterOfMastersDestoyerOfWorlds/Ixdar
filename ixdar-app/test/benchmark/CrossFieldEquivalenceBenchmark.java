package benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.paths.SurfaceMetric;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.crossfield.NDirectionField;

/**
 * Same-field check for cross-field solver changes: records per-vertex field angles (from each
 * vertex's first outgoing half-edge) and singularity indices of the {@code -Dbenchmark.dsl} graph
 * as {@code <dsl>.angles} and per-face theta as {@code <dsl>.theta}, or compares against them and
 * writes {@code <dsl>.angles.report} with the graph's node times.
 */
public final class CrossFieldEquivalenceBenchmark {

    public static final String DSL_PROPERTY = "benchmark.dsl";

    public static final double MAX_ANGLE_DIFFERENCE_RADIANS = 1.0e-3;

    private static final double QUARTER_TURN = Math.PI / 2.0;

    private static final int CROSS_FIELD_DEGREE = 4;

    private static final int ANGLE_COLUMN = 3;

    private static final int SINGULARITY_INDEX_COLUMN = 4;

    @Test
    void crossFieldMatchesTheRecordedReference() throws IOException {
        String dsl = System.getProperty(DSL_PROPERTY);
        assertTrue(dsl != null, "set -D" + DSL_PROPERTY + " to a graph ending in cross_field");
        NodeGraphRuntime runtime = NodeGraphRuntime.executeResource(dsl, Map.of());
        CrossField field = (CrossField) runtime.lastOutput(NDirectionField.FIELD.name);

        double[] solution = field.system.solution;
        SurfaceMetric metric = SurfaceMetric.of(field.mesh);
        List<String> rows = new ArrayList<>(field.mesh.vertexCount());
        Vector3f position = new Vector3f();
        for (int activeVertex = 0; activeVertex < field.mesh.vertexCount(); activeVertex++) {
            int vertexId = field.mesh.vertexIdAt(activeVertex);
            field.mesh.vertexPosition(vertexId, position);
            // Measured from the vertex's first outgoing half-edge, so the reference half-edge the
            // solve's frame starts at drops out.
            int outgoing = field.mesh.vertexOutgoingHalfEdgeAt(vertexId, 0);
            int edgeId = field.mesh.halfEdgeEdge(outgoing);
            int metricHalfEdge = field.edgeIdToActive[edgeId] << 1
                    | (field.mesh.edgeHalfEdge(edgeId) == outgoing ? 0 : 1);
            double angleSum = metric.vertexAngleSum[activeVertex];
            double outgoingAngle = metric.vertexIsBoundary[activeVertex]
                    ? metric.signpostAngle[metricHalfEdge]
                    : metric.signpostAngle[metricHalfEdge] * (2.0 * Math.PI / angleSum);
            double angle = Math.atan2(solution[2 * activeVertex + 1], solution[2 * activeVertex])
                    / CROSS_FIELD_DEGREE - outgoingAngle;
            rows.add(String.format(Locale.ROOT, "%.6f %.6f %.6f %.17g %d", position.x, position.y,
                    position.z, angle, field.singularityIndex4.get(activeVertex)));
        }

        List<String> faceRows = new ArrayList<>(field.faceCount);
        for (int activeFace = 0; activeFace < field.faceCount; activeFace++) {
            faceRows.add(String.format(Locale.ROOT, "%.17g", field.theta[activeFace]));
        }
        Path faceReference = Path.of(dsl + ".theta");
        if (!Files.exists(faceReference)) {
            Files.write(faceReference, faceRows, StandardCharsets.UTF_8);
        }
        List<String> faceReferenceRows = Files.readAllLines(faceReference, StandardCharsets.UTF_8);

        Path reference = Path.of(dsl + ".angles");
        if (!Files.exists(reference)) {
            Files.write(reference, rows, StandardCharsets.UTF_8);
            return;
        }
        Files.write(Path.of(dsl + ".angles.new"), rows, StandardCharsets.UTF_8);
        Files.write(Path.of(dsl + ".theta.new"), faceRows, StandardCharsets.UTF_8);
        List<String> referenceRows = Files.readAllLines(reference, StandardCharsets.UTF_8);
        assertEquals(referenceRows.size(), rows.size(), "the same vertex count");

        double maxDifference = 0.0;
        double sumDifference = 0.0;
        int worstVertex = -1;
        List<String> singularityMismatches = new ArrayList<>();
        int referenceSingularities = 0;
        int newSingularities = 0;
        for (int activeVertex = 0; activeVertex < rows.size(); activeVertex++) {
            String[] before = referenceRows.get(activeVertex).split(" ");
            String[] after = rows.get(activeVertex).split(" ");
            double difference = Math.abs(Double.parseDouble(after[ANGLE_COLUMN])
                    - Double.parseDouble(before[ANGLE_COLUMN])) % QUARTER_TURN;
            difference = Math.min(difference, QUARTER_TURN - difference);
            sumDifference += difference;
            if (difference > maxDifference) {
                maxDifference = difference;
                worstVertex = activeVertex;
            }
            int beforeIndex = Integer.parseInt(before[SINGULARITY_INDEX_COLUMN]);
            int afterIndex = Integer.parseInt(after[SINGULARITY_INDEX_COLUMN]);
            referenceSingularities += beforeIndex != 0 ? 1 : 0;
            newSingularities += afterIndex != 0 ? 1 : 0;
            if (beforeIndex != afterIndex) {
                singularityMismatches.add("vertex " + activeVertex + " index4 " + beforeIndex
                        + " -> " + afterIndex);
            }
        }
        assertEquals(faceReferenceRows.size(), faceRows.size(), "the same face count");
        double maxFaceDifference = 0.0;
        double sumFaceDifference = 0.0;
        int worstFace = -1;
        int undefinedThetaMismatches = 0;
        for (int activeFace = 0; activeFace < faceRows.size(); activeFace++) {
            double after = Double.parseDouble(faceRows.get(activeFace));
            double before = Double.parseDouble(faceReferenceRows.get(activeFace));
            if (Double.isNaN(after) || Double.isNaN(before)) {
                undefinedThetaMismatches += Double.isNaN(after) == Double.isNaN(before) ? 0 : 1;
                continue;
            }
            double difference = Math.abs(after - before) % QUARTER_TURN;
            difference = Math.min(difference, QUARTER_TURN - difference);
            sumFaceDifference += difference;
            if (difference > maxFaceDifference) {
                maxFaceDifference = difference;
                worstFace = activeFace;
            }
        }
        List<String> report = new ArrayList<>();
        report.add(String.format(Locale.ROOT, "vertices %d", rows.size()));
        report.add(String.format(Locale.ROOT, "angle difference mod pi/2: max %.3e rad (vertex %d)"
                + " mean %.3e rad", maxDifference, worstVertex, sumDifference / rows.size()));
        report.add(String.format(Locale.ROOT, "face theta difference mod pi/2: max %.3e rad"
                + " (face %d) mean %.3e rad, %d faces defined on one side only",
                maxFaceDifference, worstFace, sumFaceDifference / faceRows.size(),
                undefinedThetaMismatches));
        report.add(String.format(Locale.ROOT, "singular vertices: reference %d new %d,"
                + " mismatched %d", referenceSingularities, newSingularities,
                singularityMismatches.size()));
        report.addAll(singularityMismatches);
        runtime.lastTimingMs().forEach((statement, milliseconds) -> report.add(
                String.format(Locale.ROOT, "node %s %d ms", statement, milliseconds)));
        Files.write(Path.of(dsl + ".angles.report"), report, StandardCharsets.UTF_8);

        String summary = String.join(System.lineSeparator(), report);
        assertTrue(singularityMismatches.isEmpty(), summary);
        assertTrue(maxDifference < MAX_ANGLE_DIFFERENCE_RADIANS, summary);
    }
}
