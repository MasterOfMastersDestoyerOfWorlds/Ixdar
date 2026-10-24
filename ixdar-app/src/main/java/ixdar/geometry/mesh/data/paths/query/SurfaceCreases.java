package ixdar.geometry.mesh.data.paths.query;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.analysis.curvature.PrincipalDirectionField;
import ixdar.geometry.mesh.data.analysis.segmentation.SemanticPatchDecomposer;
import ixdar.geometry.mesh.data.analysis.segmentation.SemanticPatchDecomposer.EdgeDihedrals;
import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * A surface's grooves as a cost over its edges: each vertex's valley strength in [0, 1], and each
 * edge's length scaled down where both ends lie in a groove, so the cheapest path between two
 * points hugs the groove joining them. Never written after it is built.
 */
public final class SurfaceCreases {

    // The valley signal is diffused over a disc of this many mean edges before it is ranked, so
    // scan noise at the edge scale averages out while a groove a few edges wide survives.
    public static final double SMOOTHING_EDGES = 3.0;

    // Strengths are signal over this quantile of the positive signal, clamped to one, so the
    // strongest grooves saturate whatever the scan's scale.
    public static final double SATURATION_QUANTILE = 0.95;

    // An edge wholly inside a saturated groove costs this fraction of its length, so following a
    // groove is never free and the path still prefers the shorter of two grooves.
    public static final double GROOVE_COST_OF_LENGTH = 0.05;

    public static final double HALF = 0.5;

    // A face whose corners' mean valley strength reaches this lies in a crease: at least half
    // the saturating signal, the grooves a crease ring follows rather than scan noise.
    public static final float CREASE_STRENGTH = 0.5f;

    /** Mesh the creases were measured on. */
    public final MeshTopology sourceMesh;

    /** Valley strength in [0, 1] per mesh vertex id: one deep in a groove, zero off any. */
    public final float[] valleyStrength;

    /** Diffused valley signal per mesh vertex id, never negative: the curvature strength ranks. */
    public final float[] valleyCurvature;

    /** Signal at which {@link #valleyStrength} saturates to one. */
    public final double saturation;

    /** Path cost per mesh edge id: its length, scaled down toward the groove fraction. */
    public final double[] edgeCost;

    /** Wall time building the creases took, the signal included, in milliseconds. */
    public double buildMillis;

    /**
     * Ranks a raw valley signal into strengths and edge costs: the signal is diffused over
     * {@link #SMOOTHING_EDGES} mean edges, then divided by its {@link #SATURATION_QUANTILE}, or by
     * the floor when that is larger, so a surface without grooves saturates nowhere.
     *
     * @param mesh            surface the signal lives on
     * @param valleySignal    per mesh vertex id, larger in a groove; negative values count as none
     * @param saturationFloor smallest signal a saturated groove may have
     */
    public SurfaceCreases(MeshTopology mesh, float[] valleySignal, double saturationFloor) {
        long start = System.nanoTime();
        this.sourceMesh = mesh;
        int vertexIdBound = valleySignal.length;
        Vector3f from = new Vector3f();
        Vector3f to = new Vector3f();
        double[] edgeLength = new double[0];
        for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
            int edgeId = mesh.edgeIdAt(activeEdge);
            int halfEdge = mesh.edgeHalfEdge(edgeId);
            mesh.vertexPosition(mesh.halfEdgeVertex(halfEdge), from);
            mesh.vertexPosition(mesh.halfEdgeEndVertex(halfEdge), to);
            if (edgeLength.length <= edgeId) {
                edgeLength = Arrays.copyOf(edgeLength, Math.max(edgeId + 1, 2 * edgeLength.length));
            }
            edgeLength[edgeId] = from.distance(to);
        }
        // Jacobi neighbour averaging spreads a value about one edge per step, so a disc of r edges
        // takes r squared steps.
        int steps = (int) Math.ceil(SMOOTHING_EDGES * SMOOTHING_EDGES);
        double[] smoothed = new double[vertexIdBound];
        double[] next = new double[vertexIdBound];
        for (int vertexId = 0; vertexId < vertexIdBound; vertexId++) {
            smoothed[vertexId] = Math.max(0.0, valleySignal[vertexId]);
        }
        for (int step = 0; step < steps; step++) {
            for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
                int vertexId = mesh.vertexIdAt(activeVertex);
                int spokes = mesh.vertexEdgeCount(vertexId);
                double sum = smoothed[vertexId];
                for (int spoke = 0; spoke < spokes; spoke++) {
                    sum += smoothed[mesh.edgeOtherVertex(mesh.vertexEdgeAt(vertexId, spoke),
                            vertexId)];
                }
                next[vertexId] = sum / (spokes + 1);
            }
            double[] swap = smoothed;
            smoothed = next;
            next = swap;
        }
        double[] positive = new double[mesh.vertexCount()];
        int positiveCount = 0;
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            double value = smoothed[mesh.vertexIdAt(activeVertex)];
            if (value > 0.0) {
                positive[positiveCount++] = value;
            }
        }
        Arrays.sort(positive, 0, positiveCount);
        saturation = Math.max(saturationFloor, positiveCount == 0 ? 0.0
                : positive[(int) Math.min(positiveCount - 1,
                        Math.floor(SATURATION_QUANTILE * positiveCount))]);
        valleyStrength = new float[vertexIdBound];
        valleyCurvature = new float[vertexIdBound];
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            valleyCurvature[vertexId] = (float) smoothed[vertexId];
            valleyStrength[vertexId] = (float) Math.min(1.0,
                    saturation > 0.0 ? smoothed[vertexId] / saturation : 0.0);
        }
        edgeCost = new double[edgeLength.length];
        for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
            int edgeId = mesh.edgeIdAt(activeEdge);
            int halfEdge = mesh.edgeHalfEdge(edgeId);
            double groove = HALF * (valleyStrength[mesh.halfEdgeVertex(halfEdge)]
                    + valleyStrength[mesh.halfEdgeEndVertex(halfEdge)]);
            edgeCost[edgeId] = edgeLength[edgeId]
                    * (GROOVE_COST_OF_LENGTH + (1.0 - GROOVE_COST_OF_LENGTH) * (1.0 - groove));
        }
        buildMillis = (System.nanoTime() - start) / 1e6;
    }

    /**
     * Measures a surface's grooves from its most concave principal curvature, the curvature
     * across a valley, so concave grooves attract and convex ridges do not. Saturation is floored
     * at the median curvature magnitude.
     *
     * @param mesh surface to measure, of any polygon sizes, wound either way
     * @return the creases, with {@link #buildMillis} covering the curvature estimate
     */
    public static SurfaceCreases of(MeshTopology mesh) {
        long start = System.nanoTime();
        EdgeDihedrals dihedrals = SemanticPatchDecomposer.computeEdgeDihedrals(mesh);
        PrincipalDirectionField curvature = PrincipalDirectionField.compute(mesh, dihedrals);
        // The estimate counts bending away from the normal as negative, so with outward normals
        // a groove is the positive maximum curvature; a surface wound inward, whose fan triangles
        // enclose a negative signed volume, is read with the signs swapped.
        float[] positions = dihedrals.positions();
        int[] triangles = dihedrals.triangles();
        double signedVolume = 0.0;
        for (int corner = 0; corner < triangles.length; corner += 3) {
            int first = triangles[corner] * 3;
            int second = triangles[corner + 1] * 3;
            int third = triangles[corner + 2] * 3;
            signedVolume += positions[first] * (positions[second + 1] * positions[third + 2]
                    - positions[second + 2] * positions[third + 1])
                    + positions[first + 1] * (positions[second + 2] * positions[third]
                    - positions[second] * positions[third + 2])
                    + positions[first + 2] * (positions[second] * positions[third + 1]
                    - positions[second + 1] * positions[third]);
        }
        boolean outward = signedVolume >= 0.0;
        float[] signal = new float[mesh.vertexIdBound()];
        float[] magnitude = new float[mesh.vertexCount()];
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            signal[vertexId] = outward ? curvature.kappaMax(vertexId)
                    : -curvature.kappaMin(vertexId);
            magnitude[activeVertex] = Math.max(Math.abs(curvature.kappaMax(vertexId)),
                    Math.abs(curvature.kappaMin(vertexId)));
        }
        Arrays.sort(magnitude);
        SurfaceCreases creases = new SurfaceCreases(mesh, signal,
                magnitude.length == 0 ? 0.0 : magnitude[magnitude.length / 2]);
        creases.buildMillis = (System.nanoTime() - start) / 1e6;
        return creases;
    }
}
