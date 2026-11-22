package ixdar.geometry.mesh.data.fields.crossfield;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

/**
 * KCP13 Appendix B's geometric curvature of a triangle, {@code κ_t = Σ s_v θ_v − π} with
 * {@code s_v = 2π / angle sum} at interior vertices and 1 on the boundary, measured on one mesh.
 */
public final class TriangleCurvature {

    public static final int TRIANGLE_CORNERS = 3;

    public static final int VIOLATING_TRIANGLES_NAMED = 8;

    private static final double FULL_TURN = 2.0 * Math.PI;

    public final HalfEdgeMesh mesh;

    /** Per vertex id, the sum of its corner angles. */
    public final double[] angleSumByVertexId;

    /** Corner vertex ids of the triangle {@link #measure} read last. */
    public final int[] corners = new int[TRIANGLE_CORNERS];

    /** Interior angle at each corner of the triangle {@link #measure} read last. */
    public final double[] cornerAngle = new double[TRIANGLE_CORNERS];

    /** Each corner's term {@code (s_v − 1) θ_v} of the triangle {@link #measure} read last. */
    public final double[] term = new double[TRIANGLE_CORNERS];

    private final Vector3f origin = new Vector3f();
    private final Vector3f toNext = new Vector3f();
    private final Vector3f toPrevious = new Vector3f();

    /**
     * Measures every vertex's angle sum, ready for {@link #measure}.
     *
     * @param mesh triangle mesh to measure
     */
    public TriangleCurvature(HalfEdgeMesh mesh) {
        this.mesh = mesh;
        this.angleSumByVertexId = new double[mesh.vertexIdBound()];
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            readCorners(mesh.faceIdAt(activeFace));
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                angleSumByVertexId[corners[corner]] += cornerAngle[corner];
            }
        }
    }

    /**
     * The triangle's {@code κ_t}, written as {@code Σ (s_v − 1) θ_v}, leaving its corners and
     * terms in {@link #corners} and {@link #term}.
     *
     * @param faceId triangle to measure
     * @return its geometric curvature
     */
    public double measure(int faceId) {
        readCorners(faceId);
        double curvature = 0.0;
        for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
            int vertexId = corners[corner];
            double scale = mesh.isBoundaryVertex(vertexId) ? 1.0 : FULL_TURN / angleSumByVertexId[vertexId];
            term[corner] = (scale - 1.0) * cornerAngle[corner];
            curvature += term[corner];
        }
        return curvature;
    }

    /**
     * Whether a curvature meets the n-smooth bound {@code |n κ_t| < π}.
     *
     * @param curvature a triangle's {@code κ_t}
     * @param symmetry  the field's {@code n}
     * @return true when it does
     */
    public static boolean isNSmooth(double curvature, int symmetry) {
        return Math.abs(symmetry * curvature) < Math.PI;
    }

    /**
     * Refuses a mesh that is not n-smooth, on which KCP13's triangle indices need not sum to
     * {@code nχ}. See also: KCP13 Appendix B
     *
     * @param mesh     triangle mesh the field is built on
     * @param symmetry the field's {@code n}
     * @throws IllegalStateException naming the violating count and the first few triangles
     */
    public static void requireNSmooth(HalfEdgeMesh mesh, int symmetry) {
        TriangleCurvature curvature = new TriangleCurvature(mesh);
        int violating = 0;
        List<String> named = new ArrayList<>();
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            double kappa = curvature.measure(faceId);
            if (isNSmooth(kappa, symmetry)) {
                continue;
            }
            violating++;
            if (named.size() < VIOLATING_TRIANGLES_NAMED) {
                named.add(String.format("face %d (vertices %d, %d, %d) n*kappa=%.4f", faceId,
                        curvature.corners[0], curvature.corners[1], curvature.corners[2],
                        symmetry * kappa));
            }
        }
        if (violating > 0) {
            throw new IllegalStateException("cross field: " + violating + " triangles break KCP13"
                    + " Appendix B's n-smooth condition |n kappa_t| < pi (n = " + symmetry + "), so"
                    + " its triangle indices need not obey Poincare-Hopf; refine the mesh first"
                    + " (refine_n_smooth): " + named);
        }
    }

    /**
     * Reads a triangle's corner vertices and the interior angle at each.
     *
     * @param faceId triangle to read
     */
    private void readCorners(int faceId) {
        for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
            corners[corner] = mesh.faceVertexAt(faceId, corner);
        }
        for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
            mesh.vertexPosition(corners[corner], origin);
            mesh.vertexPosition(corners[(corner + 1) % TRIANGLE_CORNERS], toNext).sub(origin);
            mesh.vertexPosition(corners[(corner + 2) % TRIANGLE_CORNERS], toPrevious).sub(origin);
            cornerAngle[corner] = Math.atan2(toNext.cross(toPrevious, origin).length(),
                    toNext.dot(toPrevious));
        }
    }
}
