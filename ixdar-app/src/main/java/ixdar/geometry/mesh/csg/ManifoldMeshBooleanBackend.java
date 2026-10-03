package ixdar.geometry.mesh.csg;

import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;

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

    public static final int THREE = 3;

    public static final ManifoldBindings BINDINGS;

    public static final ManifoldProvenanceBindings PROVENANCE;

    static {
        // The vendored loader announces each library it extracts and then prints its entire symbol
        // table to System.out, which lands in every build and test log. Discard it for the duration
        // of the load only; the restore is in the finally, so a load failure still reports normally.
        PrintStream console = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        try {
            BINDINGS = new ManifoldBindings();
            PROVENANCE = new ManifoldProvenanceBindings(BINDINGS);
        } catch (Throwable failure) {
            throw new IllegalStateException("Manifold natives failed to load", failure);
        } finally {
            System.setOut(console);
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

        int channels = operandA.cornerU != null || operandB.cornerU != null
                ? BooleanVertexProperties.POSITION_AND_UV_CHANNELS
                : THREE;

        ManifoldMeshExport originalA;
        ManifoldMeshExport originalB;
        int originalIdA;
        int originalIdB;
        ManifoldMeshExport solved;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment solidA = stampedOriginal(operandA, channels, arena);
            MemorySegment solidB = stampedOriginal(operandB, channels, arena);
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

        int vertexCount = solved.vertexCount();
        int stride = solved.propertiesPerVertex;
        // A UV seam gives one point of the surface several property vertices, and the kernel's
        // merge vectors name them. Welding by those, not by position, keeps the mesh closed without
        // fusing distinct vertices that merely coincide, such as the copies repair_mesh splits a
        // pinched vertex into; the per-corner UV field then carries the seam.
        int[] weldTarget = new int[vertexCount];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            weldTarget[vertex] = vertex;
        }
        if (channels > THREE) {
            for (int merge = 0; merge < solved.mergeFromVertex.length; merge++) {
                weldTarget[(int) solved.mergeFromVertex[merge]] = (int) solved.mergeToVertex[merge];
            }
        }
        HalfEdgeMesh mesh = new HalfEdgeMesh();
        int[] meshVertex = new int[vertexCount];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int root = vertex;
            while (weldTarget[root] != root) {
                root = weldTarget[root];
            }
            weldTarget[vertex] = root;
            if (root == vertex) {
                meshVertex[vertex] = mesh.addVertex((float) solved.vertexProperties[vertex * stride],
                        (float) solved.vertexProperties[vertex * stride + 1],
                        (float) solved.vertexProperties[vertex * stride + 2]);
            }
        }
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            meshVertex[vertex] = meshVertex[weldTarget[vertex]];
        }
        int triangleCount = solved.triangleCount();
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            mesh.addFace(meshVertex[(int) solved.triangleCorners[triangle * THREE]],
                    meshVertex[(int) solved.triangleCorners[triangle * THREE + 1]],
                    meshVertex[(int) solved.triangleCorners[triangle * THREE + 2]]);
        }
        mesh.computeNormals();

        double[] cornerU = null;
        double[] cornerV = null;
        if (channels > THREE && stride >= channels) {
            cornerU = new double[triangleCount * THREE];
            cornerV = new double[triangleCount * THREE];
            for (int triangle = 0; triangle < triangleCount; triangle++) {
                for (int corner = 0; corner < THREE; corner++) {
                    int index = triangle * THREE + corner;
                    cornerU[index] = solved.cornerCoordinate(triangle, corner, THREE);
                    cornerV[index] = solved.cornerCoordinate(triangle, corner, THREE + 1);
                }
            }
        }

        BooleanFaceProvenance provenance = new BooleanFaceProvenance(operandA, originalA,
                originalIdA, operandB, originalB, originalIdB, solved).build();
        if (cornerU != null) {
            clearUntexturedCorners(cornerU, cornerV, provenance,
                    new QuadTriangulation[] {operandA, operandB});
        }
        return new MeshBooleanResult(mesh, provenance.faceOrigin, provenance.faceSourceOperand,
                provenance.faceSourceQuad, cornerU, cornerV);
    }

    /**
     * Put NaN back on every output face whose source face had no texture coordinates, an
     * untextured operand's or a hole-fill triangle's: the kernel interpolated only the finite
     * stand-in {@link BooleanVertexProperties} gave those corners.
     *
     * @param cornerU interpolated {@code u} per output corner, cleared in place
     * @param cornerV interpolated {@code v} per output corner, cleared in place
     * @param provenance source operand and source face of every output face
     * @param operands the operands, indexed by {@link MeshBooleanResult#ORIGIN_A} and
     *     {@link MeshBooleanResult#ORIGIN_B}
     */
    private static void clearUntexturedCorners(double[] cornerU, double[] cornerV,
            BooleanFaceProvenance provenance, QuadTriangulation[] operands) {
        boolean[][] untexturedFace = new boolean[operands.length][];
        for (int operand = 0; operand < operands.length; operand++) {
            QuadTriangulation source = operands[operand];
            if (source.cornerU == null) {
                continue;
            }
            int maximumFaceId = -1;
            for (int faceId : source.triangleSourceFace) {
                maximumFaceId = Math.max(maximumFaceId, faceId);
            }
            untexturedFace[operand] = new boolean[maximumFaceId + 1];
            for (int corner = 0; corner < source.cornerU.length; corner++) {
                if (!Double.isFinite(source.cornerU[corner])
                        || !Double.isFinite(source.cornerV[corner])) {
                    untexturedFace[operand][source.triangleSourceFace[corner / THREE]] = true;
                }
            }
        }
        for (int face = 0; face < provenance.faceSourceOperand.length; face++) {
            int operand = provenance.faceSourceOperand[face];
            int sourceFace = provenance.faceSourceQuad[face];
            if (operand < 0 || sourceFace < 0) {
                continue;
            }
            boolean[] untextured = untexturedFace[operand];
            if (untextured == null || untextured[sourceFace]) {
                Arrays.fill(cornerU, face * THREE, face * THREE + THREE, Double.NaN);
                Arrays.fill(cornerV, face * THREE, face * THREE + THREE, Double.NaN);
            }
        }
    }

    /**
     * Hand a triangulation to the kernel as a solid stamped with its own original id, so the
     * boolean's run table names it.
     *
     * @param operand triangulated solid to convert
     * @param channels property channels per vertex, which both operands must agree on
     * @param arena arena owning the solid; release it with
     *     {@link ManifoldProvenanceBindings#destructSolid}
     * @return the stamped solid
     * @throws Throwable if a native call fails
     * @throws IllegalStateException when the kernel rejects the operand as a solid
     */
    private static MemorySegment stampedOriginal(QuadTriangulation operand, int channels,
            Arena arena) throws Throwable {
        BooleanVertexProperties properties =
                new BooleanVertexProperties(operand, channels).build();
        MemorySegment imported = PROVENANCE.importProperties(properties.vertexProperties,
                properties.triangleCorners, channels, arena);
        try {
            ManifoldBindings.ManifoldError status = BINDINGS.status(imported);
            if (status != ManifoldBindings.ManifoldError.NO_ERROR) {
                throw new IllegalStateException("Manifold rejected a boolean operand with status "
                        + status);
            }
            return PROVENANCE.asOriginal(imported, arena);
        } finally {
            PROVENANCE.destructSolid(imported);
        }
    }
}
