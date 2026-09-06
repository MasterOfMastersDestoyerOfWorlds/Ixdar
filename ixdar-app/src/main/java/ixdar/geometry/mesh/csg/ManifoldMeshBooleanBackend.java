package ixdar.geometry.mesh.csg;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import com.cadoodlecad.manifold.ManifoldBindings;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

/**
 * {@link MeshBooleanBackend} backed by the Manifold CSG kernel, whose exact predicates guarantee a
 * closed two-manifold result. The only class that names {@code manifold3d}.
 *
 * <p>Each operand is stamped as an original before the boolean so the output's run table names
 * it; the run and face tables then drive {@link BooleanFaceProvenance}.
 */
public final class ManifoldMeshBooleanBackend implements MeshBooleanBackend {

    /** Position properties per vertex, and equally vertices per triangle. */
    public static final int THREE = 3;

    /** Shared binding; constructing it loads the Manifold natives out of the jar. */
    public static final ManifoldBindings BINDINGS;

    /** Downcalls the vendored binding lacks: original stamping and the run and face tables. */
    public static final ManifoldProvenanceBindings PROVENANCE;

    static {
        try {
            BINDINGS = new ManifoldBindings();
            PROVENANCE = new ManifoldProvenanceBindings(BINDINGS);
        } catch (Throwable failure) {
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

        ManifoldMeshExport originalA;
        ManifoldMeshExport originalB;
        int originalIdA;
        int originalIdB;
        ManifoldMeshExport solved;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment importedA = toManifold(operandA);
            MemorySegment importedB = toManifold(operandB);
            MemorySegment solidA = PROVENANCE.asOriginal(importedA, arena);
            MemorySegment solidB = PROVENANCE.asOriginal(importedB, arena);
            BINDINGS.delete(importedA);
            BINDINGS.delete(importedB);
            try {
                originalIdA = PROVENANCE.originalId(solidA);
                originalIdB = PROVENANCE.originalId(solidB);
                originalA = PROVENANCE.export(solidA);
                originalB = PROVENANCE.export(solidB);
                MemorySegment result = BINDINGS.booleanOp(solidA, solidB, operationType);
                try {
                    ManifoldBindings.ManifoldError status = BINDINGS.status(result);
                    if (status != ManifoldBindings.ManifoldError.NO_ERROR) {
                        throw new IllegalStateException("Manifold boolean failed with status "
                                + status);
                    }
                    solved = PROVENANCE.export(result);
                } finally {
                    BINDINGS.delete(result);
                }
            } finally {
                PROVENANCE.destructSolid(solidA);
                PROVENANCE.destructSolid(solidB);
            }
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Manifold boolean failed", failure);
        }

        HalfEdgeMesh mesh = new HalfEdgeMesh();
        int vertexCount = solved.vertexCount();
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            mesh.addVertex((float) solved.vertexPositions[vertex * THREE],
                    (float) solved.vertexPositions[vertex * THREE + 1],
                    (float) solved.vertexPositions[vertex * THREE + 2]);
        }
        int triangleCount = solved.triangleCount();
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            mesh.addFace((int) solved.triangleCorners[triangle * THREE],
                    (int) solved.triangleCorners[triangle * THREE + 1],
                    (int) solved.triangleCorners[triangle * THREE + 2]);
        }
        mesh.computeNormals();

        BooleanFaceProvenance provenance = new BooleanFaceProvenance(operandA, originalA,
                originalIdA, operandB, originalB, originalIdB, solved).build();
        return new MeshBooleanResult(mesh, provenance.faceOrigin, provenance.faceSourceOperand,
                provenance.faceSourceQuad);
    }

    /**
     * Hand a triangulation to the kernel as a solid.
     *
     * @param operand triangulated solid to convert
     * @return the kernel's handle on that solid, released with {@link ManifoldBindings#delete}
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
