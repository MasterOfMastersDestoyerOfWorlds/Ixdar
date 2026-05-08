package ixdar.geometry.mesh.quadlayout.solver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import ixdar.geometry.mesh.data.ArrayMesh;

/**
 * Short-Term Vector Dijkstra (STVD) for anisotropic geodesic distance
 * computation. Implementation of Campen 2014 thesis §9.6.
 *
 * <p>Standard Dijkstra overestimates geodesic distance on triangulations
 * because it forces paths to follow mesh edges (a "zig-zag effect"). STVD
 * keeps a window of the last {@code k} predecessors per vertex and, when
 * relaxing edge {@code (v,w)}, considers ALL chains {@code w ← v ← p1 ← p2
 * ← ...} of length 1..k, "unfolding" each chain into a common 2D plane
 * (preserving edge lengths and incident-face angles), summing the unfolded
 * edge vectors, and using the resulting Euclidean magnitude as the candidate
 * distance.
 *
 * <p>Practical settings (Campen):
 * <ul>
 *   <li>{@code k = 1} reduces to classical Dijkstra (overestimates).
 *   <li>{@code k = 4} is sufficient for visually smooth elastica (stage-2
 *       loop computation).
 *   <li>{@code k = 10} for high anisotropy (γ &ge; 20) including the α=30
 *       stage-2 metric.
 *   <li>{@code k → ∞} approaches a vector-valued underestimate that ignores
 *       holes — undesirable.
 * </ul>
 *
 * <p>Importantly, STVD does <em>not</em> require an intrinsic Delaunay
 * triangulation (iDT) and does <em>not</em> require triangle-inequality
 * fixing — its main practical advantage over fast-marching on raw meshes.
 *
 * <p>This implementation works on a {@link ArrayMesh} using its half-edge /
 * vertex adjacency. Edge weights are user-supplied via an
 * {@link EdgeWeight} callback, which enables the anisotropic metric of
 * Eq. 5.1 (cos²θ + α² sin²θ).
 */
public final class Stvd {
    public static final int NUM_3 = 3;
    public static final double NUM_1e_15 = 1e-15;
    public static final int NUM_6 = 6;
    public static final int NUM_4 = 4;

    private final ArrayMesh mesh;
    private final int k;
    private final EdgeWeight weight;
    private final int[][] adjacency;
    private final float[] positions;

    /**
     * TODO: document {@code Stvd}.
     *
     * @param mesh TODO: describe
     * @param k TODO: describe
     * @param weight TODO: describe
     * @throws IllegalArgumentException TODO: describe
     */
    public Stvd(ArrayMesh mesh, int k, EdgeWeight weight) {
        if (k < 1) throw new IllegalArgumentException("k must be >= 1");
        this.mesh = mesh;
        this.k = k;
        this.weight = weight;
        this.positions = mesh.copyPositions();
        this.adjacency = buildAdjacency(mesh);
    }

    /**
     * TODO: document {@code compute}.
     *
     * @param startVertices TODO: describe
     * @throws IllegalStateException TODO: describe
     * @return TODO: describe
     */
    public Result compute(int[] startVertices) {
        int n = positions.length / NUM_3;
        // mesh field referenced for adjacency rebuild only; silence unused warning.
        if (mesh == null) throw new IllegalStateException("mesh required");
        double[] dist = new double[n];
        int[] pred = new int[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(pred, -1);

        PriorityQueue<long[]> pq = new PriorityQueue<>((a, b) -> Double.compare(
                Double.longBitsToDouble(a[0]), Double.longBitsToDouble(b[0])));
        for (int s : startVertices) {
            dist[s] = 0.0;
            pq.add(new long[] { Double.doubleToRawLongBits(0.0), s });
        }

        while (!pq.isEmpty()) {
            long[] top = pq.poll();
            double d = Double.longBitsToDouble(top[0]);
            int u = (int) top[1];
            if (d > dist[u]) continue;
            for (int v : adjacency[u]) {
                double cand = relax(u, v, dist, pred);
                if (cand < dist[v] - NUM_1e_15) {
                    dist[v] = cand;
                    pred[v] = u;
                    pq.add(new long[] { Double.doubleToRawLongBits(cand), v });
                }
            }
        }
        return new Result(dist, pred);
    }

    /**
     * STVD relaxation: try all chains of length 1..k ending at u, prepend the
     * (u→v) edge, unfold into 2D, take the Euclidean norm of the summed
     * vectors.
     *
     * @param u TODO: describe
     * @param v TODO: describe
     * @param dist TODO: describe
     * @param pred TODO: describe
     * @return TODO: describe
     */
    private double relax(int u, int v, double[] dist, int[] pred) {
        double best = Double.POSITIVE_INFINITY;
        // Chain length i = 1: classical Dijkstra step.
        double w0 = weight.weight(u, v);
        double cand1 = dist[u] + w0;
        if (cand1 < best) best = cand1;

        // Chain length i = 2..k: unfold the chain (pred^{i-1}, ..., pred^0=u, v) into 2D.
        // We accumulate the chain in reverse: start from u with edge u→v, then walk back via pred.
        int[] chain = new int[k + 1];
        int len = 1;
        chain[0] = v;
        chain[1] = u;
        int cur = u;
        for (int i = 2; i <= k; i++) {
            int p = pred[cur];
            if (p < 0) break;
            chain[i] = p;
            len = i;
            cur = p;
            // chain[len].dist is the anchor of the unfolded chain
            double anchorDist = dist[chain[len]];
            if (!Double.isFinite(anchorDist)) break;
            double summed = unfoldedChainLength(chain, len);
            double cand = anchorDist + summed;
            if (cand < best) best = cand;
        }
        return best;
    }

    /**
     * Unfold a chain of vertices into a common 2D plane preserving each edge
     * length and angles at incident vertices, then return the magnitude of the
     * summed 2D edge vectors.
     *
     * <p>Implementation: PCA-flatten the chain's 3D points onto their
     * best-fit plane, compute 2D edge vectors there, and sum. On a flat
     * triangulation this gives the exact Euclidean distance between anchor
     * and head; on a curved surface, the SVD direction reasonably
     * approximates the unfolded chain. Crucially this avoids the
     * sign-of-rotation ambiguity inherent in the per-vertex unfolding
     * formulation when chains revisit headings on flat grids.
     *
     * @param chain TODO: describe
     * @param len TODO: describe
     * @return TODO: describe
     */
    private double unfoldedChainLength(int[] chain, int len) {
        int m = len + 1;
        // Centroid.
        double cx = 0, cy = 0, cz = 0;
        for (int i = 0; i < m; i++) {
            int v = chain[i];
            cx += positions[NUM_3 * v];
            cy += positions[NUM_3 * v + 1];
            cz += positions[NUM_3 * v + 2];
        }
        cx /= m; cy /= m; cz /= m;
        // Covariance matrix.
        double xx = 0, xy = 0, xz = 0, yy = 0, yz = 0, zz = 0;
        for (int i = 0; i < m; i++) {
            int v = chain[i];
            double dx = positions[NUM_3 * v] - cx;
            double dy = positions[NUM_3 * v + 1] - cy;
            double dz = positions[NUM_3 * v + 2] - cz;
            xx += dx * dx; xy += dx * dy; xz += dx * dz;
            yy += dy * dy; yz += dy * dz; zz += dz * dz;
        }
        // The chain's dominant axis u1 = direction of largest variance.
        // Power iteration is overkill for k≤10; use Jacobi-like extraction
        // by picking u1 = anchor→head direction (good first guess) then
        // orthogonalizing.
        int va = chain[len], vh = chain[0];
        double ux = positions[NUM_3 * vh] - positions[NUM_3 * va];
        double uy = positions[NUM_3 * vh + 1] - positions[NUM_3 * va + 1];
        double uz = positions[NUM_3 * vh + 2] - positions[NUM_3 * va + 2];
        double uLen = Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (uLen < NUM_1e_15) return 0.0;
        ux /= uLen; uy /= uLen; uz /= uLen;
        // Second axis from covariance perpendicular to u1.
        // v = covariance(u) - (u·covariance(u))u, normalized.
        double cux = xx * ux + xy * uy + xz * uz;
        double cuy = xy * ux + yy * uy + yz * uz;
        double cuz = xz * ux + yz * uy + zz * uz;
        double proj = cux * ux + cuy * uy + cuz * uz;
        double vx = cux - proj * ux;
        double vy = cuy - proj * uy;
        double vz = cuz - proj * uz;
        double vLen = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (vLen > NUM_1e_15) {
            vx /= vLen; vy /= vLen; vz /= vLen;
        }
        // 2D coords for anchor and head; sum 2D edge vectors equals (head - anchor) projected.
        double[] u2 = new double[m];
        double[] v2 = new double[m];
        for (int i = 0; i < m; i++) {
            int vId = chain[i];
            double dx = positions[NUM_3 * vId] - cx;
            double dy = positions[NUM_3 * vId + 1] - cy;
            double dz = positions[NUM_3 * vId + 2] - cz;
            u2[i] = dx * ux + dy * uy + dz * uz;
            v2[i] = dx * vx + dy * vy + dz * vz;
        }
        // Compute total path length and chord length; STVD geodesic estimator
        // is the chord length (anchor → head in 2D), but we scale to preserve
        // each edge length individually rather than the projected length.
        // Approach: scale each 2D edge to its true 3D length, then sum.
        double sumX = 0, sumY = 0;
        for (int i = len; i >= 1; i--) {
            int a = chain[i];
            int b = chain[i - 1];
            double ex2 = u2[i - 1] - u2[i];
            double ey2 = v2[i - 1] - v2[i];
            double e2Len = Math.sqrt(ex2 * ex2 + ey2 * ey2);
            double dx = positions[NUM_3 * b] - positions[NUM_3 * a];
            double dy = positions[NUM_3 * b + 1] - positions[NUM_3 * a + 1];
            double dz = positions[NUM_3 * b + 2] - positions[NUM_3 * a + 2];
            double e3Len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (e2Len > NUM_1e_15) {
                double s = e3Len / e2Len;
                sumX += s * ex2;
                sumY += s * ey2;
            } else {
                sumX += e3Len;
            }
        }
        return Math.sqrt(sumX * sumX + sumY * sumY);
    }

    /**
     * Build CSR-ish vertex adjacency from face indices.
     *
     * @param mesh TODO: describe
     * @return TODO: describe
     */
    private static int[][] buildAdjacency(ArrayMesh mesh) {
        int n = mesh.copyPositions().length / NUM_3;
        int[] faces = mesh.copyFaceIndices();
        int vpf = mesh.getVertsPerFace();
        // Dedup with a small per-vertex set.
        @SuppressWarnings("unchecked")
        List<Integer>[] tmp = new List[n];
        for (int i = 0; i < n; i++) tmp[i] = new ArrayList<>(NUM_6);
        Map<Long, Boolean> seen = new HashMap<>();
        for (int f = 0; f < faces.length; f += vpf) {
            for (int j = 0; j < vpf; j++) {
                int a = faces[f + j];
                int b = faces[f + (j + 1) % vpf];
                long key = (long) Math.min(a, b) * (long) n + Math.max(a, b);
                if (seen.putIfAbsent(key, Boolean.TRUE) == null) {
                    tmp[a].add(b);
                    tmp[b].add(a);
                }
            }
        }
        int[][] out = new int[n][];
        for (int i = 0; i < n; i++) {
            int[] arr = new int[tmp[i].size()];
            for (int j = 0; j < arr.length; j++) arr[j] = tmp[i].get(j);
            out[i] = arr;
        }
        return out;
    }

    /**
     * Convenience entry point with k=4 (Campen's stage-2 default).
     *
     * @param mesh TODO: describe
     * @param startVertices TODO: describe
     * @param w TODO: describe
     * @return TODO: describe
     */
    public static Result computeK4(ArrayMesh mesh, int[] startVertices, EdgeWeight w) {
        return new Stvd(mesh, NUM_4, w).compute(startVertices);
    }

    /** Per-edge cost: (u, v) → positive scalar. */
    @FunctionalInterface
    public interface EdgeWeight {
        /**
         * TODO: document {@code weight}.
         *
         * @param u TODO: describe
         * @param v TODO: describe
         * @return TODO: describe
         */
        double weight(int u, int v);
    }

    public static final class Result {
        public final double[] distance;
        public final int[] predecessor;
        /**
         * TODO: document {@code Result}.
         *
         * @param distance TODO: describe
         * @param predecessor TODO: describe
         */
        public Result(double[] distance, int[] predecessor) {
            this.distance = distance;
            this.predecessor = predecessor;
        }
    }
}
