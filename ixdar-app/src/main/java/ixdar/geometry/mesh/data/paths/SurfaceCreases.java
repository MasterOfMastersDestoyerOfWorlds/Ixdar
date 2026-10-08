package ixdar.geometry.mesh.data.paths;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.PrincipalDirectionField;
import ixdar.geometry.mesh.data.SemanticPatchDecomposer;
import ixdar.geometry.mesh.data.representation.ArrayMesh;

/**
 * A surface's grooves as a cost over its edges: each vertex's valley strength in [0, 1], and each
 * edge's length scaled down where both ends lie in a groove, so the cheapest path between two
 * points hugs the groove joining them. Never written after it is built.
 */
public final class SurfaceCreases {

    public static final int COORDINATES_PER_POINT = 3;

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

    // The diffused signal and the six distinct entries of its across-groove tensor.
    public static final int CHANNELS = 7;

    public static final int POWER_ITERATIONS = 8;

    /** Mesh the creases were measured on. */
    public final MeshTopology sourceMesh;

    /** Valley strength in [0, 1] per mesh vertex id: one deep in a groove, zero off any. */
    public final float[] valleyStrength;

    /** Path cost per mesh edge id: its length, scaled down toward the groove fraction. */
    public final double[] edgeCost;

    /**
     * Unit direction across the groove per mesh vertex id, packed xyz, averaged over the smoothing
     * disc weighted by the signal; a ring along the groove lies in the plane it is normal to. A
     * line, not a vector: its sign is arbitrary.
     */
    public final float[] acrossGroove;

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
     * @param acrossGroove    unit direction across the groove per mesh vertex id, packed xyz,
     *                        averaged into {@link #acrossGroove} and not written
     */
    public SurfaceCreases(MeshTopology mesh, float[] valleySignal, double saturationFloor,
            float[] acrossGroove) {
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
        // The signal diffuses together with its across-groove lines, as the signal-weighted outer
        // product a a^T, whose six entries average without the sign of a line mattering. Jacobi
        // neighbour averaging spreads a value about one edge per step, so a disc of r edges takes
        // r squared steps.
        int steps = (int) Math.ceil(SMOOTHING_EDGES * SMOOTHING_EDGES);
        double[] smoothed = new double[CHANNELS * vertexIdBound];
        double[] next = new double[CHANNELS * vertexIdBound];
        for (int vertexId = 0; vertexId < vertexIdBound; vertexId++) {
            double signal = Math.max(0.0, valleySignal[vertexId]);
            int line = COORDINATES_PER_POINT * vertexId;
            int at = CHANNELS * vertexId;
            smoothed[at] = signal;
            int entry = 1;
            for (int row = 0; row < COORDINATES_PER_POINT; row++) {
                for (int column = row; column < COORDINATES_PER_POINT; column++) {
                    smoothed[at + entry++] = signal * acrossGroove[line + row]
                            * acrossGroove[line + column];
                }
            }
        }
        for (int step = 0; step < steps; step++) {
            for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
                int vertexId = mesh.vertexIdAt(activeVertex);
                int spokes = mesh.vertexEdgeCount(vertexId);
                for (int channel = 0; channel < CHANNELS; channel++) {
                    double sum = smoothed[CHANNELS * vertexId + channel];
                    for (int spoke = 0; spoke < spokes; spoke++) {
                        sum += smoothed[CHANNELS * mesh.edgeOtherVertex(
                                mesh.vertexEdgeAt(vertexId, spoke), vertexId) + channel];
                    }
                    next[CHANNELS * vertexId + channel] = sum / (spokes + 1);
                }
            }
            double[] swap = smoothed;
            smoothed = next;
            next = swap;
        }
        // Each vertex's line is the averaged tensor's dominant eigenvector, by power iteration
        // from the vertex's own line.
        this.acrossGroove = new float[COORDINATES_PER_POINT * vertexIdBound];
        double[] tensor = new double[COORDINATES_PER_POINT * COORDINATES_PER_POINT];
        double[] axis = new double[COORDINATES_PER_POINT];
        double[] product = new double[COORDINATES_PER_POINT];
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            int entry = CHANNELS * vertexId + 1;
            for (int row = 0; row < COORDINATES_PER_POINT; row++) {
                for (int column = row; column < COORDINATES_PER_POINT; column++) {
                    tensor[COORDINATES_PER_POINT * row + column] = smoothed[entry];
                    tensor[COORDINATES_PER_POINT * column + row] = smoothed[entry++];
                }
                axis[row] = acrossGroove[COORDINATES_PER_POINT * vertexId + row];
            }
            for (int iteration = 0; iteration < POWER_ITERATIONS; iteration++) {
                double length = 0.0;
                for (int row = 0; row < COORDINATES_PER_POINT; row++) {
                    product[row] = 0.0;
                    for (int column = 0; column < COORDINATES_PER_POINT; column++) {
                        product[row] += tensor[COORDINATES_PER_POINT * row + column] * axis[column];
                    }
                    length += product[row] * product[row];
                }
                length = Math.sqrt(length);
                for (int row = 0; length > 0.0 && row < COORDINATES_PER_POINT; row++) {
                    axis[row] = product[row] / length;
                }
            }
            for (int row = 0; row < COORDINATES_PER_POINT; row++) {
                this.acrossGroove[COORDINATES_PER_POINT * vertexId + row] = (float) axis[row];
            }
        }
        double[] positive = new double[mesh.vertexCount()];
        int positiveCount = 0;
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            double value = smoothed[CHANNELS * mesh.vertexIdAt(activeVertex)];
            if (value > 0.0) {
                positive[positiveCount++] = value;
            }
        }
        Arrays.sort(positive, 0, positiveCount);
        double saturation = Math.max(saturationFloor, positiveCount == 0 ? 0.0
                : positive[(int) Math.min(positiveCount - 1,
                        Math.floor(SATURATION_QUANTILE * positiveCount))]);
        valleyStrength = new float[vertexIdBound];
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            valleyStrength[vertexId] = (float) Math.min(1.0,
                    saturation > 0.0 ? smoothed[CHANNELS * vertexId] / saturation : 0.0);
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
        ArrayMesh dense = SemanticPatchDecomposer.toArrayMesh(mesh);
        PrincipalDirectionField curvature = PrincipalDirectionField.compute(dense,
                SemanticPatchDecomposer.computeEdgeDihedrals(dense));
        // The estimate counts bending away from the normal as negative, so with outward normals
        // a groove is the positive maximum curvature; a surface wound inward, whose triangles
        // enclose a negative signed volume, is read with the signs swapped.
        int[] triangles = dense.copyFaceIndices();
        float[] positions = dense.copyPositions();
        double signedVolume = 0.0;
        for (int corner = 0; corner < triangles.length; corner += COORDINATES_PER_POINT) {
            int first = COORDINATES_PER_POINT * triangles[corner];
            int second = COORDINATES_PER_POINT * triangles[corner + 1];
            int third = COORDINATES_PER_POINT * triangles[corner + 2];
            signedVolume += positions[first] * (positions[second + 1] * positions[third + 2]
                    - positions[second + 2] * positions[third + 1])
                    + positions[first + 1] * (positions[second + 2] * positions[third]
                            - positions[second] * positions[third + 2])
                    + positions[first + 2] * (positions[second] * positions[third + 1]
                            - positions[second + 1] * positions[third]);
        }
        boolean outward = signedVolume >= 0.0;
        int vertexIdBound = 0;
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            vertexIdBound = Math.max(vertexIdBound, mesh.vertexIdAt(activeVertex) + 1);
        }
        float[] signal = new float[vertexIdBound];
        float[] across = new float[COORDINATES_PER_POINT * vertexIdBound];
        float[] direction = new float[COORDINATES_PER_POINT];
        float[] magnitude = new float[mesh.vertexCount()];
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            signal[vertexId] = outward ? curvature.kappaMax(activeVertex)
                    : -curvature.kappaMin(activeVertex);
            if (outward) {
                curvature.dirMax(activeVertex, direction);
            } else {
                curvature.dirMin(activeVertex, direction);
            }
            System.arraycopy(direction, 0, across, COORDINATES_PER_POINT * vertexId,
                    COORDINATES_PER_POINT);
            magnitude[activeVertex] = Math.max(Math.abs(curvature.kappaMax(activeVertex)),
                    Math.abs(curvature.kappaMin(activeVertex)));
        }
        Arrays.sort(magnitude);
        SurfaceCreases creases = new SurfaceCreases(mesh, signal,
                magnitude.length == 0 ? 0.0 : magnitude[magnitude.length / 2], across);
        creases.buildMillis = (System.nanoTime() - start) / 1e6;
        return creases;
    }
}
