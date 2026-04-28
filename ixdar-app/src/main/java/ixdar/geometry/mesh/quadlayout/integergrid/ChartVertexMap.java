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
     * {@code combed}. Non-seam interior edges glue their two faces into the
     * same chart; seam edges leave them in distinct charts.
     */
    static ChartVertexMap build(ArrayMesh mesh, FaceRosyField field, CombedField combed) {
        int F = mesh.faceCount();
        int Ei = field.interiorEdgeCount();

        // 1. Union-find over faces with non-seam interior edges.
        int[] parent = new int[F];
        int[] rank = new int[F];
        for (int i = 0; i < F; i++) parent[i] = i;
        for (int e = 0; e < Ei; e++) {
            if (combed.isSeamEdge(e)) continue;
            int fa = field.edgeFaceA(e);
            int fb = field.edgeFaceB(e);
            unite(parent, rank, fa, fb);
        }

        // 2. Compact chart ids.
        int[] faceChart = new int[F];
        int[] rootToChart = new int[F];
        java.util.Arrays.fill(rootToChart, -1);
        int chartCount = 0;
        for (int f = 0; f < F; f++) {
            int r = find(parent, f);
            if (rootToChart[r] < 0) rootToChart[r] = chartCount++;
            faceChart[f] = rootToChart[r];
        }

        // 3. Allocate chart-vertex id per (mesh_vertex, chart) pair as we
        //    walk every face corner. HashMap<long, int> keyed by packed
        //    ((long) meshVert << 32) | chart.
        HashMap<Long, Integer> dedupe = new HashMap<>();
        int[] cornerChartVertex = new int[F * 3];
        // Worst case: every corner is unique. Right-size lazily via ArrayList.
        java.util.ArrayList<Integer> cvMesh = new java.util.ArrayList<>(F * 3);
        java.util.ArrayList<Integer> cvChart = new java.util.ArrayList<>(F * 3);
        for (int f = 0; f < F; f++) {
            int chart = faceChart[f];
            for (int c = 0; c < 3; c++) {
                int mv = mesh.faceVertexAt(f, c);
                long key = ((long) mv << 32) | (chart & 0xffffffffL);
                Integer existing = dedupe.get(key);
                if (existing == null) {
                    int id = cvMesh.size();
                    dedupe.put(key, id);
                    cvMesh.add(mv);
                    cvChart.add(chart);
                    cornerChartVertex[f * 3 + c] = id;
                } else {
                    cornerChartVertex[f * 3 + c] = existing;
                }
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

    /** Convenience: chart-vertex id at {@code corner} of face {@code f}. */
    int chartVertexAt(int faceId, int cornerIdx) {
        return cornerChartVertex[faceId * 3 + cornerIdx];
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
