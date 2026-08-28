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
import ixdar.geometry.mesh.quadlayout.solver.system.DofSystem;

/**
 * Per-face angles and per-edge period jumps describing a 4-symmetric direction
 * field that follows mesh curvature. Each face carries two direction vectors in
 * its local x, y basis; singular vertices are those whose incident crosses
 * close up to a turn other than a full one.
 */
public class CrossField {
    /**
     * A small value used to avoid division by zero and other numerical issues.
     */
    public static final float EPSILON = 1e-12f;
    public static final float BASIS_AXIS_PICK_THRESHOLD = 0.9f;
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
     * {@code normalA.dot(normalB) < featureDihedralCos} becomes an alignment edge.
     * The default 0.2 (≈ 78° between normals) only captures very sharp creases.
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

    /** The field solve's DOF system, set by the stage that built this field. */
    public DofSystem system;

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
