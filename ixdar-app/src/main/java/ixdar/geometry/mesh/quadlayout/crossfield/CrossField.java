package ixdar.geometry.mesh.quadlayout.crossfield;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh.EdgeFaceIds;
import ixdar.geometry.mesh.quadlayout.Singularity;
import ixdar.geometry.mesh.quadlayout.crossfield.constraint.ConstraintSource;
import ixdar.geometry.mesh.quadlayout.crossfield.constraint.CurvatureConstraints;

/**
 * Per-face angles and per-edge period jumps describing a 4-symmetric direction
 * field that follows mesh curvature. Each face carries two direction vectors in
 * its local x, y basis; singular vertices are those whose incident crosses close
 * up to a turn other than a full one.
 */
public class CrossField {
    /**
     * A small value used to avoid division by zero and other numerical issues.
     */
    public static final float EPSILON = 1e-12f;
    public static final float BASIS_AXIS_PICK_THRESHOLD = 0.9f;
    public static final double NANOS_PER_SECOND = 1.0e9;
    public static final float HALF_PI = (float) (Math.PI / 2.0);

    /**
     * The mesh that the cross field is built from.
     */
    public final HalfEdgeMesh mesh;

    /**
     * Angle from this face's local x-axis to the cross field's representative
     * x-axis, in radians.
     */
    public float[] theta;

    /**
     * Number of quarter-turns needed to match this edge's two neighboring cross
     * fields after transporting them into a common frame.
     */
    public int[] periodJump;

    /**
     * Per-face representative x-axis of the cross field.
     */
    public Vector3f[] faceX;
    /**
     * Per-face representative y-axis of the cross field.
     */
    public Vector3f[] faceY;

    /**
     * Per-edge angle from the source triangle's transported local x-axis to the
     * neighboring triangle's local x-axis, in radians.
     */
    public float[] kappa;

    /**
     * All of the vertices in the cross field that have more or less than 4 edges
     * incident to them.
     */
    public List<Singularity> singularities = new ArrayList<>();

    /**
     * Sharp-feature threshold, stored as the cosine of the angle between the two
     * incident face normals: an interior edge with
     * {@code normalA.dot(normalB) < featureDihedralCos} becomes an alignment
     * edge. The default 0.2 (≈ 78° between normals) only captures very sharp
     * creases.
     */
    public float featureDihedralCos = 0.2f;

    /**
     * Map from face id to active index.
     */
    public Map<Integer, Integer> faceIdToActive;

    /**
     * Map from edge id to active index.
     */
    public Map<Integer, Integer> edgeIdToActive;

    /**
     * Raw mesh edge ids that should become quad edges: sharp feature edges and
     * boundary edges. The seamless stage uses them to keep cuts away from these
     * edges and to pin the matching parameter coordinate to an integer iso-line.
     */
    public Set<Integer> alignmentEdgeIds = new HashSet<>();

    public boolean[] faceConstrained;
    public float[] faceConstraintAngle;
    public ConstraintSource[] faceConstraintSource;

    public int edgeCount;
    public int faceCount;
    public int vertexCount;

    /**
     * The curvature constraints that are applied to the cross field. builds a multi
     * radius geodesic disk and integrates the curvature tensor over the disk to get
     * the principal curvatures.
     */
    public CurvatureConstraints curvatureConstraints;

    public final int[] rowFaceA;
    public final int[] rowFaceB;
    public final double[] rowKappaPlusHalfPiP;
    public final int[] rowOfEdge;

    public int interiorRowCount;

    /**
     *
     * Cross field construction.
     *
     * @param mesh half-edge mesh providing geometry, topology, and active-id
     *             mapping
     */
    public CrossField(HalfEdgeMesh mesh) {
        this.mesh = mesh;
        this.edgeCount = mesh.edgeCount();
        this.faceCount = mesh.faceCount();
        this.vertexCount = mesh.vertexCount();

        this.faceConstrained = new boolean[faceCount];
        this.faceConstraintAngle = new float[faceCount];
        Arrays.fill(faceConstraintAngle, Float.NaN);
        this.faceConstraintSource = new ConstraintSource[faceCount];
        Arrays.fill(faceConstraintSource, ConstraintSource.NONE);

        this.theta = new float[faceCount];
        this.periodJump = new int[edgeCount];
        this.kappa = new float[edgeCount];
        this.faceX = new Vector3f[faceCount];
        this.faceY = new Vector3f[faceCount];

        this.alignmentEdgeIds = new HashSet<>();

        this.interiorRowCount = 0;
        for (int i = 0; i < edgeCount; i++) {
            if (!mesh.isBoundaryEdge(mesh.edgeIdAt(i))) {
                this.interiorRowCount++;
            }
        }
        this.rowFaceA = new int[interiorRowCount];
        this.rowFaceB = new int[interiorRowCount];
        this.rowKappaPlusHalfPiP = new double[interiorRowCount];
        this.rowOfEdge = new int[edgeCount];
        Arrays.fill(rowOfEdge, -1);
        this.curvatureConstraints = new CurvatureConstraints(mesh, this);
    }

    /**
     * Run the BZK09 pipeline (local face frames + edge transport angles κ,
     * directional constraints, Voronoi spanning forest, greedy mixed-integer
     * least-squares solve) and extract singularities.
     *
     * @return {@code this}, with field arrays populated and singularities filled
     */
    public CrossField build() {
        long sectionStart = System.nanoTime();

        faceIdToActive = new HashMap<>(mesh.faceCount() * 2);
        for (int i = 0; i < mesh.faceCount(); i++) {
            faceIdToActive.put(mesh.faceIdAt(i), i);
        }
        edgeIdToActive = new HashMap<>(mesh.edgeCount() * 2);
        for (int i = 0; i < mesh.edgeCount(); i++) {
            edgeIdToActive.put(mesh.edgeIdAt(i), i);
        }
        System.out.printf("[cross-field timing] active-id maps %.3fs%n",
                (System.nanoTime() - sectionStart) / NANOS_PER_SECOND);
        sectionStart = System.nanoTime();
        /*
         * Local face frames. Convention: x_f = first half-edge of f, projected onto the
         * tangent plane. y_f = n_f × x_f. Right-handed.
         */

        for (int faceIndex = 0; faceIndex < mesh.faceCount(); faceIndex++) {
            int faceId = mesh.faceIdAt(faceIndex);
            int halfEdge = mesh.faceHalfEdge(faceId);
            int v0 = mesh.halfEdgeVertex(halfEdge);
            int v1 = mesh.halfEdgeEndVertex(halfEdge);

            Vector3f position1 = mesh.vertexPosition(v0);
            Vector3f position2 = mesh.vertexPosition(v1);
            Vector3f xAxis = new Vector3f(position2).sub(position1);

            Vector3f normal = mesh.faceNormal(faceId);

            float xDotN = xAxis.dot(normal);
            xAxis.x -= xDotN * normal.x;
            xAxis.y -= xDotN * normal.y;
            xAxis.z -= xDotN * normal.z;
            float xLen = xAxis.length();
            if (xLen < EPSILON) {
                arbitraryTangent(normal, xAxis);
            } else {
                xAxis.div(xLen);
            }
            Vector3f yAxis = new Vector3f();
            normal.cross(xAxis, yAxis).normalize();

            faceX[faceIndex] = xAxis;
            faceY[faceIndex] = yAxis;
        }
        System.out.printf("[cross-field timing] local face frames %.3fs%n",
                (System.nanoTime() - sectionStart) / NANOS_PER_SECOND);
        sectionStart = System.nanoTime();

        /*
         * Edge transport angles κ_ij. Rotate face-i's x-axis about the shared edge by
         * the dihedral angle so it lies in face-j's tangent plane. Express the rotated
         * vector in face-j's frame (faceX[j], faceY[j]): κ_ij = atan2(y-component,
         * x-component).
         */

        for (int i = 0; i < mesh.edgeCount(); i++) {
            EdgeFaceIds edgeFaceIds = mesh.edgeFaceIds(i);
            if (mesh.isBoundaryEdge(edgeFaceIds.edgeId)) {
                kappa[i] = 0f;
                continue;
            }
            Vector3f position1 = mesh.vertexPosition(edgeFaceIds.edgeStartVertex);
            Vector3f position2 = mesh.vertexPosition(edgeFaceIds.edgeEndVertex);
            Vector3f edgeDir = new Vector3f(position2).sub(position1);
            float edgeLen = edgeDir.length();
            if (edgeLen < EPSILON) {
                kappa[i] = 0f;
                continue;
            }
            edgeDir.div(edgeLen);

            Vector3f faceNormalU = mesh.faceNormal(edgeFaceIds.faceA);
            Vector3f faceNormalV = mesh.faceNormal(edgeFaceIds.faceB);

            Vector3f cross = new Vector3f(faceNormalU).cross(faceNormalV);
            float dihedral = (float) Math.atan2(cross.dot(edgeDir),
                    Math.max(-1f, Math.min(1f, faceNormalU.dot(faceNormalV))));
            float dihedralCos = (float) Math.cos(dihedral);
            float dihedralSin = (float) Math.sin(dihedral);
            Vector3f xiTransported = new Vector3f(faceX[edgeFaceIds.faceA]);
            Vector3f kCrossV = new Vector3f(edgeDir).cross(xiTransported);
            float kDotV = edgeDir.dot(xiTransported);
            float oneMinusC = 1f - dihedralCos;
            xiTransported.x = xiTransported.x * dihedralCos + kCrossV.x * dihedralSin + edgeDir.x * kDotV * oneMinusC;
            xiTransported.y = xiTransported.y * dihedralCos + kCrossV.y * dihedralSin + edgeDir.y * kDotV * oneMinusC;
            xiTransported.z = xiTransported.z * dihedralCos + kCrossV.z * dihedralSin + edgeDir.z * kDotV * oneMinusC;
            float crossDirX = xiTransported.dot(faceX[edgeFaceIds.faceB]);
            float crossDirY = xiTransported.dot(faceY[edgeFaceIds.faceB]);
            kappa[i] = (float) Math.atan2(crossDirY, crossDirX);
        }
        System.out.printf("[cross-field timing] edge transport angles kappa %.3fs%n",
                (System.nanoTime() - sectionStart) / NANOS_PER_SECOND);
        sectionStart = System.nanoTime();

        detectAlignmentEdges();
        return this;
    }

    /**
     * Fills {@link #alignmentEdgeIds} with boundary edges and sharp feature edges,
     * the latter by a dihedral test against {@link #featureDihedralCos}.
     *
     * <p>See also: BZK09 Section 5.2
     */
    private void detectAlignmentEdges() {
        for (int activeEdge = 0; activeEdge < edgeCount; activeEdge++) {
            EdgeFaceIds edgeFaceIds = mesh.edgeFaceIds(activeEdge);
            if (mesh.isBoundaryEdge(edgeFaceIds.edgeId)) {
                alignmentEdgeIds.add(edgeFaceIds.edgeId);
                continue;
            }
            Vector3f faceANormal = mesh.faceNormal(edgeFaceIds.faceA);
            Vector3f faceBNormal = mesh.faceNormal(edgeFaceIds.faceB);
            if (faceANormal.dot(faceBNormal) < featureDihedralCos) {
                alignmentEdgeIds.add(edgeFaceIds.edgeId);
            }
        }
    }

    /**
     * Pick an arbitrary unit vector in the tangent plane defined by {@code n}. Used
     * as a fallback when the natural x-axis collapses to zero length.
     *
     * @param normal unit face normal
     * @param out    scratch vector overwritten with a unit tangent perpendicular to
     *               {@code n}
     */
    public static void arbitraryTangent(Vector3f normal, Vector3f out) {
        if (Math.abs(normal.x) < BASIS_AXIS_PICK_THRESHOLD)
            out.set(1f, 0f, 0f);
        else
            out.set(0f, 1f, 0f);
        float dot = out.dot(normal);
        out.x -= dot * normal.x;
        out.y -= dot * normal.y;
        out.z -= dot * normal.z;
        out.normalize();
    }

    /*
     * B. Singularities
     *
     * Walk the 1-ring of v. For each outgoing half-edge `he`, look up its edge eId.
     * If `he` is the "natural" half-edge of eId (edgeHalfEdge(eId) == he),
     * contribute +(κ_e, p_e); otherwise contribute −(κ_e, p_e).
     *
     * BZK09/Ray08: I(v) = 1/(2π) · (angleDefect(v) + signed κ-walk) + 1/4 · signed
     * p-walk. We store 4·I as an integer.
     */

    /**
     * Compute per-vertex singularity index (×4 to keep integer) from the angle
     * defect plus signed κ- and period-walks around the 1-ring; populates
     * {@link #singularityIndexQuarter} and {@link #singularities}.
     *
     * @return singularity list (also stored in {@link #singularities}); excludes
     *         boundary vertices and zero-index interior vertices
     */
    public List<Singularity> extractSingularities() {
        int vertexCount = mesh.vertexCount();
        singularities.clear();
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();

        for (int vAi = 0; vAi < vertexCount; vAi++) {
            int vId = mesh.vertexIdAt(vAi);
            if (mesh.isBoundaryVertex(vId))
                continue;
            Vector3f vPos = mesh.vertexPosition(vId);

            float angleSum = 0f;
            int faces = mesh.vertexFaceCount(vId);
            for (int i = 0; i < faces; i++) {
                int fId = mesh.vertexFaceAt(vId, i);
                angleSum += mesh.interiorAngleAtVertex(fId, vId, vPos, a, b);
            }
            float defect = (float) (2.0 * Math.PI) - angleSum;

            float signedKappaSum = 0f;
            int signedPeriodSum = 0;
            int outCount = mesh.vertexOutgoingHalfEdgeCount(vId);
            for (int i = 0; i < outCount; i++) {
                int he = mesh.vertexOutgoingHalfEdgeAt(vId, i);
                int eId = mesh.halfEdgeEdge(he);
                if (mesh.isBoundaryEdge(eId))
                    continue;
                int eAi = edgeIdToActive.get(eId);
                int sign = (he == mesh.edgeHalfEdge(eId)) ? 1 : -1;
                signedKappaSum += sign * kappa[eAi];
                signedPeriodSum += sign * periodJump[eAi];
            }

            float iTimes4 = (float) (((defect + signedKappaSum) * 2.0) / Math.PI) + signedPeriodSum;
            int iQuarter = Math.round(iTimes4);
            if (iQuarter != 0) {
                singularities.add(new Singularity(vId, iQuarter));
            }
        }
        return singularities;
    }

    /**
     * Reduce {@code angle} into the half-open interval {@code [0, PI/2)}.
     *
     * @param angle angle in radians
     * @return canonical representative of {@code angle} modulo PI/2
     */
    public static float canonicalizeMod(float angle) {
        float halfPi = (float) (Math.PI / 2.0);
        float r = (float) (angle - halfPi * Math.floor(angle / halfPi));
        if (r < 0)
            r += halfPi;
        return r;
    }

}
