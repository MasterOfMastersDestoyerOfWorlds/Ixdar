package ixdar.geometry.mesh.csg;

import java.util.Arrays;

import manifold3d.FloatVector;
import manifold3d.Manifold;
import manifold3d.UIntVector;
import manifold3d.manifold.MeshGL;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;

/**
 * {@link MeshBooleanBackend} backed by the Manifold CSG kernel, whose exact predicates guarantee a
 * closed two-manifold result and report which input triangle every output triangle came from.
 *
 * <p>The only class in the codebase that names {@code manifold3d}; reached solely through the
 * desktop and headless platforms.
 */
public final class ManifoldMeshBooleanBackend implements MeshBooleanBackend {

    /** Position properties per vertex, and equally vertices per triangle. */
    public static final int THREE = 3;

    /** {@inheritDoc}. */
    @Override
    public MeshBooleanResult compute(QuadTriangulation operandA, QuadTriangulation operandB,
            BooleanOperation operation) {
        Manifold solidA = toManifold(operandA).asOriginal();
        Manifold solidB = toManifold(operandB).asOriginal();
        int originalIdA = solidA.originalID();
        int originalIdB = solidB.originalID();

        Manifold solved = switch (operation) {
            case UNION -> solidA.add(solidB);
            case DIFFERENCE -> solidA.subtract(solidB);
            case INTERSECTION -> solidA.intersect(solidB);
        };
        if (solved.status() != 0) {
            throw new IllegalStateException("Manifold boolean failed with status " + solved.status());
        }

        MeshGL result = solved.getMeshGL();
        float[] vertexProperties = result.vertProperties().toFloatArray();
        int[] corners = result.triVerts().toIntArray();
        int propertyCount = result.numProp();
        int triangleCount = corners.length / THREE;

        HalfEdgeMesh mesh = new HalfEdgeMesh();
        int vertexCount = vertexProperties.length / propertyCount;
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            mesh.addVertex(vertexProperties[vertex * propertyCount],
                    vertexProperties[vertex * propertyCount + 1],
                    vertexProperties[vertex * propertyCount + 2]);
        }
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            mesh.addFace(corners[triangle * THREE], corners[triangle * THREE + 1],
                    corners[triangle * THREE + 2]);
        }
        mesh.computeNormals();

        int[] faceOrigin = new int[triangleCount];
        int[] faceSourceQuad = new int[triangleCount];
        decodeProvenance(result, triangleCount, originalIdA, originalIdB, operandA, operandB,
                faceOrigin, faceSourceQuad);
        return new MeshBooleanResult(mesh, faceOrigin, faceSourceQuad);
    }

    /**
     * Attribute every output triangle to an operand and, through that operand's triangulation, to
     * one of its original faces.
     *
     * <p>Manifold groups output triangles into runs, one per contributing input solid. Triangles
     * created along the intersection belong to no input triangle.
     *
     * @param result the boolean's mesh, carrying the run tables
     * @param triangleCount number of output triangles
     * @param originalIdA the first operand's stable Manifold id
     * @param originalIdB the second operand's stable Manifold id
     * @param operandA first operand, supplying its triangle-to-face map
     * @param operandB second operand, supplying its triangle-to-face map
     * @param faceOrigin receives the operand per output triangle
     * @param faceSourceQuad receives the source face id per output triangle
     */
    private static void decodeProvenance(MeshGL result, int triangleCount, int originalIdA,
            int originalIdB, QuadTriangulation operandA, QuadTriangulation operandB,
            int[] faceOrigin, int[] faceSourceQuad) {
        Arrays.fill(faceOrigin, MeshBooleanResult.ORIGIN_NEW);
        Arrays.fill(faceSourceQuad, -1);

        int[] runIndex = result.runIndex().toIntArray();
        int[] runOriginalId = result.runOriginalID().toIntArray();
        int[] sourceTriangle = result.faceID().toIntArray();
        if (runIndex.length < 2 || runOriginalId.length == 0) {
            return;
        }
        // runIndex is expressed in corners for a triVerts-indexed run table, triangles otherwise.
        int cornersPerEntry = runIndex[runIndex.length - 1] == triangleCount ? 1 : THREE;

        for (int run = 0; run < runOriginalId.length; run++) {
            int origin;
            QuadTriangulation operand;
            if (runOriginalId[run] == originalIdA) {
                origin = MeshBooleanResult.ORIGIN_A;
                operand = operandA;
            } else if (runOriginalId[run] == originalIdB) {
                origin = MeshBooleanResult.ORIGIN_B;
                operand = operandB;
            } else {
                continue;
            }
            int firstTriangle = runIndex[run] / cornersPerEntry;
            int endTriangle = Math.min(triangleCount, runIndex[run + 1] / cornersPerEntry);
            for (int triangle = firstTriangle; triangle < endTriangle; triangle++) {
                faceOrigin[triangle] = origin;
                if (triangle < sourceTriangle.length
                        && sourceTriangle[triangle] < operand.triangleSourceFace.length) {
                    faceSourceQuad[triangle] = operand.triangleSourceFace[sourceTriangle[triangle]];
                }
            }
        }
    }

    /**
     * Wrap a triangulation as a Manifold solid.
     *
     * @param operand triangulated solid to hand to the kernel
     * @return the solid, not yet stamped with an original id
     */
    private static Manifold toManifold(QuadTriangulation operand) {
        long[] corners = new long[operand.triangles.length];
        for (int index = 0; index < corners.length; index++) {
            corners[index] = operand.triangles[index];
        }
        MeshGL mesh = new MeshGL();
        mesh.numProp(THREE);
        mesh.vertProperties(FloatVector.FromArray(operand.positions));
        mesh.triVerts(UIntVector.FromArray(corners));
        return new Manifold(mesh);
    }
}
