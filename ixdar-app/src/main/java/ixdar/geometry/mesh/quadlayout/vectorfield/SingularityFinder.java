package ixdar.geometry.mesh.quadlayout.vectorfield;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.ArrayMesh;

/**
 * Vertex-by-vertex singularity detection for a {@link FaceRosyField}.
 *
 * <p>For an interior vertex v, walk the one-ring CCW. The cross-field holonomy
 * (composition of per-edge matching rotations + frame parallel transport)
 * around v equals 0 modulo pi/2 only if v is regular. The deviation, expressed
 * in quarter-turns, is the singularity index times 4:
 *
 * <pre>
 *   index_4(v) = round( 2 K(v) / pi  -  sum_{e in star(v) signed} m_e )
 * </pre>
 *
 * where K(v) is the angle defect (2*pi minus sum of face-corner angles) and
 * m_e is the integer period jump on edge e, signed by traversal orientation.
 * Boundary vertices are skipped in v1 (PATCH-45 capper closes boundaries
 * before this stage runs in the QGP pipeline).
 */
public final class SingularityFinder {
    public static final double NUM_2_0 = 2.0;
    public static final float NUM_1e_30 = 1e-30f;
    public static final double NUM_0_5 = 0.5;

    private SingularityFinder() {}

    /**
     * TODO: document {@code find}.
     *
     * @param field TODO: describe
     * @return TODO: describe
     */
    public static List<Singularity> find(FaceRosyField field) {
        ArrayMesh mesh = field.mesh();
        int V = mesh.vertexCount();

        // Map mesh edge id -> interior edge index in the field.
        int E = field.interiorEdgeCount();
        Map<Integer, Integer> meshEdgeToInterior = new HashMap<>(E * 2);
        for (int i = 0; i < E; i++) {
            meshEdgeToInterior.put(field.edgeMeshId(i), i);
        }

        List<Singularity> out = new ArrayList<>();
        Vector3f p = new Vector3f();
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        Vector3f va = new Vector3f();
        Vector3f vb = new Vector3f();

        for (int v = 0; v < V; v++) {
            if (mesh.isBoundaryVertex(v)) continue;

            double angleDefect = NUM_2_0 * Math.PI;
            double signedMSum = 0.0;
            double signedResidualSum = 0.0;

            // Iterate adjacent faces of v; for each face accumulate corner
            // angle at v, and for each outgoing half-edge from v contribute
            // +m_e (for a CCW traversal of the one-ring, the outgoing
            // half-edge is the one we cross at this step).
            int adjFaces = mesh.vertexFaceCount(v);
            for (int k = 0; k < adjFaces; k++) {
                int f = mesh.vertexFaceAt(v, k);
                int corner = -1;
                int n = mesh.faceVertexCount(f);
                for (int j = 0; j < n; j++) {
                    if (mesh.faceVertexAt(f, j) == v) { corner = j; break; }
                }
                if (corner < 0) continue;
                int prev = (corner + n - 1) % n;
                int next = (corner + 1) % n;
                int va_id = mesh.faceVertexAt(f, prev);
                int vb_id = mesh.faceVertexAt(f, next);
                mesh.vertexPosition(v, p);
                mesh.vertexPosition(va_id, a);
                mesh.vertexPosition(vb_id, b);
                va.set(a).sub(p);
                vb.set(b).sub(p);
                float la = va.length();
                float lb = vb.length();
                if (la > NUM_1e_30 && lb > NUM_1e_30) {
                    double cos = va.dot(vb) / (la * lb);
                    cos = Math.max(-1.0, Math.min(1.0, cos));
                    angleDefect -= Math.acos(cos);
                }
            }

            // Sum signed m and signed residuals over the star of v. Each
            // outgoing half-edge h from v has a face f; the matching edge in
            // the field has canonical direction (face A -> face B). Sign:
            // +1 if A is f (we cross A->B when stepping from f to nbr), else -1.
            int outCount = mesh.vertexOutgoingHalfEdgeCount(v);
            for (int k = 0; k < outCount; k++) {
                int he = mesh.vertexOutgoingHalfEdgeAt(v, k);
                if (mesh.isBoundaryHalfEdge(he)) continue;
                int eId = mesh.halfEdgeEdge(he);
                Integer ie = meshEdgeToInterior.get(eId);
                if (ie == null) continue;
                int f = mesh.halfEdgeFace(he);
                int sign = (field.edgeFaceA(ie) == f) ? +1 : -1;
                int m = field.periodJump(ie);
                signedMSum += sign * m;
                double residual = field.theta(field.edgeFaceA(ie))
                        - field.theta(field.edgeFaceB(ie))
                        + field.kappa(ie)
                        + m * (Math.PI * NUM_0_5);
                signedResidualSum += sign * residual;
            }

            // Holonomy of the cross field around v in radians:
            //   K(v) + sum_signed (theta_A - theta_B + kappa + m*pi/2)
            // The (theta_A - theta_B) part telescopes to 0 around the closed
            // loop. The (kappa + m*pi/2) part is the per-edge field rotation;
            // its signed sum around v measures how much the cross field has
            // rotated after parallel-transport. Divide by pi/2 to get the
            // singularity index in quarter-turn units.
            double rawRadians = angleDefect + signedResidualSum;
            // For a regular vertex this is approximately zero; for a singular
            // vertex it is approximately k * pi/2 with k = index_4(v).
            int index4 = (int) Math.round(rawRadians / (Math.PI * NUM_0_5));
            if (index4 != 0) {
                out.add(new Singularity(v, index4));
            }
        }
        return out;
    }
}
