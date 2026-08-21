package ixdar.geometry.mesh.csg;

import java.lang.foreign.MemorySegment;

import com.cadoodlecad.manifold.ManifoldBindings;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

/**
 * {@link MeshBooleanBackend} backed by the Manifold CSG kernel, whose exact predicates guarantee a
 * closed two-manifold result. The only class that names {@code manifold3d}.
 *
 * <p>Reports no face provenance: the FFM bindings expose geometry but not the run tables, so
 * {@link MeshBooleanResult#faceOrigin} comes back empty.
 */
public final class ManifoldMeshBooleanBackend implements MeshBooleanBackend {

    /** Position properties per vertex, and equally vertices per triangle. */
    public static final int THREE = 3;

    /** Shared binding; constructing it loads the Manifold natives out of the jar. */
    public static final ManifoldBindings BINDINGS;

    static {
        try {
            BINDINGS = new ManifoldBindings();
        } catch (Exception failure) {
            throw new IllegalStateException("Manifold natives failed to load", failure);
        }
    }

    /** {@inheritDoc}. */
    @Override
    public MeshBooleanResult compute(QuadTriangulation operandA, QuadTriangulation operandB,
            BooleanOperation operation) {
        int operationType = switch (operation) {
            case UNION -> ManifoldBindings.OPTYPE_UNION;
            case DIFFERENCE -> ManifoldBindings.OPTYPE_DIFFERENCE;
            case INTERSECTION -> ManifoldBindings.OPTYPE_INTERSECTION;
        };

        ManifoldBindings.MeshData64 solved;
        try {
            MemorySegment result = BINDINGS.booleanOp(toManifold(operandA), toManifold(operandB),
                    operationType);
            ManifoldBindings.ManifoldError status = BINDINGS.status(result);
            if (status != ManifoldBindings.ManifoldError.NO_ERROR) {
                throw new IllegalStateException("Manifold boolean failed with status " + status);
            }
            solved = BINDINGS.exportMeshGL64(result);
        } catch (Throwable failure) {
            throw new IllegalStateException("Manifold boolean failed", failure);
        }

        double[] vertices = solved.vertices();
        long[] corners = solved.triangles();
        HalfEdgeMesh mesh = new HalfEdgeMesh();
        for (int vertex = 0; vertex < solved.vertCount(); vertex++) {
            mesh.addVertex((float) vertices[vertex * THREE],
                    (float) vertices[vertex * THREE + 1],
                    (float) vertices[vertex * THREE + 2]);
        }
        for (int triangle = 0; triangle < solved.triCount(); triangle++) {
            mesh.addFace((int) corners[triangle * THREE], (int) corners[triangle * THREE + 1],
                    (int) corners[triangle * THREE + 2]);
        }
        mesh.computeNormals();

        return new MeshBooleanResult(mesh, new int[0], new int[0]);
    }

    /**
     * Hand a triangulation to the kernel as a solid.
     *
     * @param operand triangulated solid to convert
     * @return the kernel's handle on that solid
     * @throws Throwable if the native call fails
     */
    private static MemorySegment toManifold(QuadTriangulation operand) throws Throwable {
        double[] positions = new double[operand.positions.length];
        for (int index = 0; index < positions.length; index++) {
            positions[index] = operand.positions[index];
        }
        long[] corners = new long[operand.triangles.length];
        for (int index = 0; index < corners.length; index++) {
            corners[index] = operand.triangles[index];
        }
        return BINDINGS.importMeshGL64(positions, corners, positions.length / THREE,
                corners.length / THREE);
    }
}
