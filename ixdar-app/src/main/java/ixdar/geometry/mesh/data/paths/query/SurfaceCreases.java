package ixdar.geometry.mesh.data.paths.query;

import java.util.Arrays;
import java.util.stream.IntStream;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.analysis.curvature.PrincipalDirectionField;
import ixdar.geometry.mesh.data.analysis.segmentation.SemanticPatchDecomposer;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.TriangleSoup;
import ixdar.geometry.mesh.data.representation.TriangulationRule;
import ixdar.numeric.graph.Dijkstra;
import ixdar.numeric.graph.ShortestPathForest;

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
        int vertexCount = mesh.vertexCount();
        // Every vertex's spokes as flat arrays, in the mesh's spoke order the sums follow; each
        // pass below then reads only primitive arrays, one vertex or edge per parallel step.
        int[] vertexIds = new int[vertexCount];
        int[] spokeStart = new int[vertexCount + 1];
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            vertexIds[activeVertex] = mesh.vertexIdAt(activeVertex);
            spokeStart[activeVertex + 1] = spokeStart[activeVertex]
                    + mesh.vertexEdgeCount(vertexIds[activeVertex]);
        }
        int[] spokeVertex = new int[spokeStart[vertexCount]];
        IntStream.range(0, vertexCount).parallel().forEach(activeVertex -> {
            int vertexId = vertexIds[activeVertex];
            for (int spoke = spokeStart[activeVertex]; spoke < spokeStart[activeVertex + 1]; spoke++) {
                spokeVertex[spoke] = mesh.edgeOtherVertex(
                        mesh.vertexEdgeAt(vertexId, spoke - spokeStart[activeVertex]), vertexId);
            }
        });
        // Jacobi neighbour averaging spreads a value about one edge per step, so a disc of r edges
        // takes r squared steps.
        int steps = (int) Math.ceil(SMOOTHING_EDGES * SMOOTHING_EDGES);
        double[] smoothed = new double[vertexIdBound];
        double[] next = new double[vertexIdBound];
        for (int vertexId = 0; vertexId < vertexIdBound; vertexId++) {
            smoothed[vertexId] = Math.max(0.0, valleySignal[vertexId]);
        }
        for (int step = 0; step < steps; step++) {
            double[] source = smoothed;
            double[] target = next;
            IntStream.range(0, vertexCount).parallel().forEach(activeVertex -> {
                int vertexId = vertexIds[activeVertex];
                double sum = source[vertexId];
                for (int spoke = spokeStart[activeVertex]; spoke < spokeStart[activeVertex + 1];
                        spoke++) {
                    sum += source[spokeVertex[spoke]];
                }
                target[vertexId] = sum / (spokeStart[activeVertex + 1] - spokeStart[activeVertex] + 1);
            });
            smoothed = target;
            next = source;
        }
        double[] positive = new double[vertexCount];
        int positiveCount = 0;
        for (int vertexId : vertexIds) {
            double value = smoothed[vertexId];
            if (value > 0.0) {
                positive[positiveCount++] = value;
            }
        }
        saturation = Math.max(saturationFloor, positiveCount == 0 ? 0.0
                : smallest(positive, positiveCount, (int) Math.min(positiveCount - 1,
                        Math.floor(SATURATION_QUANTILE * positiveCount))));
        valleyStrength = new float[vertexIdBound];
        valleyCurvature = new float[vertexIdBound];
        double[] signal = smoothed;
        IntStream.range(0, vertexCount).parallel().forEach(activeVertex -> {
            int vertexId = vertexIds[activeVertex];
            valleyCurvature[vertexId] = (float) signal[vertexId];
            valleyStrength[vertexId] = (float) Math.min(1.0,
                    saturation > 0.0 ? signal[vertexId] / saturation : 0.0);
        });
        float[] positions = SemanticPatchDecomposer.positionsById(mesh);
        int edgeIdBound = 0;
        for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
            edgeIdBound = Math.max(edgeIdBound, mesh.edgeIdAt(activeEdge) + 1);
        }
        edgeCost = new double[edgeIdBound];
        IntStream.range(0, mesh.edgeCount()).parallel().forEach(activeEdge -> {
            int edgeId = mesh.edgeIdAt(activeEdge);
            int halfEdge = mesh.edgeHalfEdge(edgeId);
            int fromVertexId = mesh.halfEdgeVertex(halfEdge);
            int toVertexId = mesh.halfEdgeEndVertex(halfEdge);
            double length = Vector3f.distance(positions[3 * fromVertexId],
                    positions[3 * fromVertexId + 1], positions[3 * fromVertexId + 2],
                    positions[3 * toVertexId], positions[3 * toVertexId + 1],
                    positions[3 * toVertexId + 2]);
            double groove = HALF * (valleyStrength[fromVertexId] + valleyStrength[toVertexId]);
            edgeCost[edgeId] = length
                    * (GROOVE_COST_OF_LENGTH + (1.0 - GROOVE_COST_OF_LENGTH) * (1.0 - groove));
        });
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
        int[] triangles = mesh.triangulate(TriangulationRule.FAN, null);
        float[] positions = SemanticPatchDecomposer.positionsById(mesh);
        PrincipalDirectionField curvature = PrincipalDirectionField.compute(mesh, positions,
                triangles, SemanticPatchDecomposer.triangleNormals(triangles, positions),
                TriangleSoup.oneRings(triangles, mesh.vertexIdBound()));
        // The estimate counts bending away from the normal as negative, so with outward normals
        // a groove is the positive maximum curvature; a surface wound inward, whose fan triangles
        // enclose a negative signed volume, is read with the signs swapped.
        boolean outward = mesh.signedVolume() >= 0.0;
        float[] signal = new float[mesh.vertexIdBound()];
        double[] magnitude = new double[mesh.vertexCount()];
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            signal[vertexId] = outward ? curvature.kappaMax(vertexId)
                    : -curvature.kappaMin(vertexId);
            magnitude[activeVertex] = Math.max(Math.abs(curvature.kappaMax(vertexId)),
                    Math.abs(curvature.kappaMin(vertexId)));
        }
        SurfaceCreases creases = new SurfaceCreases(mesh, signal, magnitude.length == 0 ? 0.0
                : smallest(magnitude, magnitude.length, magnitude.length / 2));
        creases.buildMillis = (System.nanoTime() - start) / 1e6;
        return creases;
    }

    /**
     * The value a sort of the first {@code count} values would put at {@code rank}, found by
     * quickselect in linear time; those values are reordered.
     *
     * @param values values to select among, partly reordered on return
     * @param count  how many leading values take part
     * @param rank   index into their ascending order, in {@code [0, count)}
     * @return the value of that rank, in {@link Double#compare} order as a sort orders them
     */
    private static double smallest(double[] values, int count, int rank) {
        int low = 0;
        int high = count - 1;
        while (low < high) {
            double pivot = values[(low + high) >>> 1];
            int left = low;
            int right = high;
            while (left <= right) {
                while (Double.compare(values[left], pivot) < 0) {
                    left++;
                }
                while (Double.compare(values[right], pivot) > 0) {
                    right--;
                }
                if (left <= right) {
                    double swap = values[left];
                    values[left++] = values[right];
                    values[right--] = swap;
                }
            }
            if (rank <= right) {
                high = right;
            } else if (rank >= left) {
                low = left;
            } else {
                return values[rank];
            }
        }
        return values[rank];
    }

    /**
     * The cheapest path over {@link #edgeCost} between two vertices, the one hugging the groove
     * that joins them.
     *
     * @param fromVertexId vertex the path starts on
     * @param toVertexId   vertex the path ends on
     * @return the path's vertex ids from one end to the other, both included, or an empty array
     *         when no path joins them
     */
    public int[] path(int fromVertexId, int toVertexId) {
        ShortestPathForest forest = Dijkstra.forest(sourceMesh, new int[] { fromVertexId }, edgeCost,
                new int[] { toVertexId });
        if (toVertexId >= forest.parent.length || forest.parent[toVertexId] < 0) {
            return new int[0];
        }
        int steps = 1;
        for (int vertexId = toVertexId; vertexId != fromVertexId; vertexId = forest.parent[vertexId]) {
            steps++;
        }
        int[] path = new int[steps];
        int slot = steps;
        for (int vertexId = toVertexId; slot > 0; vertexId = forest.parent[vertexId]) {
            path[--slot] = vertexId;
        }
        return path;
    }
}
