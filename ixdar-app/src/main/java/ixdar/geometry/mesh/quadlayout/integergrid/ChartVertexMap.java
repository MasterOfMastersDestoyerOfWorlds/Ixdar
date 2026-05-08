package ixdar.geometry.mesh.quadlayout.integergrid;

import java.util.HashMap;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.vectorfield.CombedField;
import ixdar.geometry.mesh.quadlayout.vectorfield.FaceRosyField;

/**
 * Variable-layout map for the per-vertex IGM (PATCH-54). Builds the
 * "chart-vertex" set: every (mesh_vertex, chart) pair that's actually
 * referenced by a face corner gets one variable index.
 *
 * <p>A <i>chart</i> is a connected component of the surface after cutting
 * along seam edges. Two faces are in the same chart iff they're connected
 * by a path of non-seam edges. Inside a single chart, all corners on the
 * same mesh vertex collapse to ONE variable; across charts (i.e. across
 * the seam), the same mesh vertex shows up as TWO different variables
 * with a seam-translation row {@code (u, v)_t = R(r)(u, v)_s + (j, k)}
 * gluing them.
 *
 * <p>For a closed surface with {@code E_seam} seam edges, the variable
 * count is {@code 2V + 2*duplicate_seam_corners} where the duplicate count
 * is bounded by {@code 2*E_seam} (each seam edge can split up to 2
 * vertices). On rocker-arm-20k that's ~21k unknowns vs 120k under the
 * old per-corner scheme — a 6x reduction that fits ojAlgo's SparseStore
 * direct-solver path.
 *
 * <p>Construction: union-find faces by non-seam interior edges → chart
 * id per face → walk every corner, dedupe (mesh_vertex, chart) pairs,
 * assign chart-vertex ids in insertion order.
 */
final class ChartVertexMap {
    public static final int NUM_3 = 3;

    /** chart id per face, 0..chartCount-1. */
    final int[] faceChart;
    final int chartCount;

    /** chart-vertex id per corner = face*3 + cornerIdx. */
    final int[] cornerChartVertex;

    /** Total number of distinct (mesh_vertex, chart) pairs. */
    final int chartVertexCount;

    /** mesh vertex id for each chart-vertex; chartVertexMesh[cv] = mesh_vertex. */
    final int[] chartVertexMesh;

    /** chart id for each chart-vertex; chartVertexChart[cv] = chart. */
    final int[] chartVertexChart;

    private ChartVertexMap(int[] faceChart, int chartCount,
                           int[] cornerChartVertex,
                           int chartVertexCount,
                           int[] chartVertexMesh,
                           int[] chartVertexChart) {
        this.faceChart = faceChart;
        this.chartCount = chartCount;
        this.cornerChartVertex = cornerChartVertex;
        this.chartVertexCount = chartVertexCount;
        this.chartVertexMesh = chartVertexMesh;
        this.chartVertexChart = chartVertexChart;
    }

    /**
     * Build the chart-vertex map for {@code mesh} using the seam edges from
     * {@code combed}. The chart is the connected component of FACE-CORNERS
     * around each mesh vertex under non-seam edges (PATCH-133 wedge-split):
     * two corners at the same mesh vertex on two faces sharing a non-seam edge
     * belong to the same wedge; corners on faces separated by seam edges (or
     * with no edge between them at that vertex) are in different wedges.
     *
     * <p>For non-singular vertices, all incident faces are connected by
     * non-seam edges and produce ONE chart-vertex per mesh-vertex (same as
     * the pre-PATCH-133 layout). For singular vertices, the seams incident
     * to the vertex split it into multiple wedges, each with its own chart
     * vertex — giving the parametrization the DOFs needed to encode the cone
     * winding (the source of PATCH-127's 40-50% flipped-triangle rate on
     * cube and rocker-arm).
     */
    static ChartVertexMap build(ArrayMesh mesh, FaceRosyField field, CombedField combed) {
        int F = mesh.faceCount();
        int Ei = field.interiorEdgeCount();

        // 1. Face-level union-find over non-seam edges → faceChart.
        //    Pre-PATCH-133 semantics; preserved for callers that ask "are
        //    these two faces in the same connected component after seam cuts?"
        int[] faceParent = new int[F];
        int[] faceRank = new int[F];
        for (int i = 0; i < F; i++) faceParent[i] = i;
        for (int e = 0; e < Ei; e++) {
            if (combed.isSeamEdge(e)) continue;
            unite(faceParent, faceRank, field.edgeFaceA(e), field.edgeFaceB(e));
        }
        int[] faceChart = new int[F];
        int[] rootToChart = new int[F];
        java.util.Arrays.fill(rootToChart, -1);
        int chartCount = 0;
        for (int f = 0; f < F; f++) {
            int r = find(faceParent, f);
            if (rootToChart[r] < 0) rootToChart[r] = chartCount++;
            faceChart[f] = rootToChart[r];
        }

        // 2. Corner-level union-find over non-seam edges → cornerChartVertex.
        //    PATCH-133: corners at the SAME mesh vertex on faces sharing a
        //    non-seam edge are in the SAME wedge → SAME chart-vertex. Seam
        //    edges leave them in distinct wedges. This gives singular vertices
        //    multiple chart-vertices (one per cone wedge), which is the DOF
        //    the parametrization needs to encode the cone winding.
        int C = F * NUM_3;
        int[] cornerParent = new int[C];
        int[] cornerRank = new int[C];
        for (int i = 0; i < C; i++) cornerParent[i] = i;
        for (int e = 0; e < Ei; e++) {
            if (combed.isSeamEdge(e)) continue;
            int fa = field.edgeFaceA(e);
            int fb = field.edgeFaceB(e);
            int meshEdge = field.edgeMeshId(e);
            int he = mesh.edgeHalfEdge(meshEdge);
            int v0 = mesh.halfEdgeVertex(he);
            int v1 = mesh.halfEdgeEndVertex(he);
            int cA0 = cornerOfVertex(mesh, fa, v0);
            int cA1 = cornerOfVertex(mesh, fa, v1);
            int cB0 = cornerOfVertex(mesh, fb, v0);
            int cB1 = cornerOfVertex(mesh, fb, v1);
            if (cA0 >= 0 && cB0 >= 0) unite(cornerParent, cornerRank, fa * NUM_3 + cA0, fb * NUM_3 + cB0);
            if (cA1 >= 0 && cB1 >= 0) unite(cornerParent, cornerRank, fa * NUM_3 + cA1, fb * NUM_3 + cB1);
        }
        int[] cornerChartVertex = new int[C];
        int[] rootToCv = new int[C];
        java.util.Arrays.fill(rootToCv, -1);
        java.util.ArrayList<Integer> cvMesh = new java.util.ArrayList<>(C);
        java.util.ArrayList<Integer> cvChart = new java.util.ArrayList<>(C);
        for (int f = 0; f < F; f++) {
            for (int c = 0; c < NUM_3; c++) {
                int corner = f * NUM_3 + c;
                int r = find(cornerParent, corner);
                int cv = rootToCv[r];
                if (cv < 0) {
                    cv = cvMesh.size();
                    rootToCv[r] = cv;
                    cvMesh.add(mesh.faceVertexAt(f, c));
                    cvChart.add(faceChart[f]);
                }
                cornerChartVertex[corner] = cv;
            }
        }
        int chartVertexCount = cvMesh.size();
        int[] chartVertexMesh = new int[chartVertexCount];
        int[] chartVertexChart = new int[chartVertexCount];
        for (int i = 0; i < chartVertexCount; i++) {
            chartVertexMesh[i] = cvMesh.get(i);
            chartVertexChart[i] = cvChart.get(i);
        }

        return new ChartVertexMap(faceChart, chartCount, cornerChartVertex,
                chartVertexCount, chartVertexMesh, chartVertexChart);
    }

    /**
     * Find the corner index c in face f such that faceVertexAt(f, c) == v.
     */
    private static int cornerOfVertex(ArrayMesh mesh, int f, int v) {
        for (int c = 0; c < NUM_3; c++) {
            if (mesh.faceVertexAt(f, c) == v) return c;
        }
        return -1;
    }

    /** Convenience: chart-vertex id at {@code corner} of face {@code f}. */
    int chartVertexAt(int faceId, int cornerIdx) {
        return cornerChartVertex[faceId * NUM_3 + cornerIdx];
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    private static void unite(int[] parent, int[] rank, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra == rb) return;
        if (rank[ra] < rank[rb]) { int t = ra; ra = rb; rb = t; }
        parent[rb] = ra;
        if (rank[ra] == rank[rb]) rank[ra]++;
    }
}
