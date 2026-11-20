package ixdar.geometry.mesh.csg;

import java.util.Arrays;

import org.joml.Vector3d;
import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.PackedPoints;

/**
 * Attributes every output triangle of a boolean to the operand it lies on, the input face it was
 * carved from, and whether it is an untouched copy or a piece the intersection curve cut.
 *
 * <p>See also: NHE*19 Section 3.1
 */
public final class BooleanFaceProvenance {

    public static final int THREE = QuadTriangulation.THREE;

    /** First operand as handed to the kernel. */
    public final QuadTriangulation operandA;

    /** First operand read back after stamping, whose face ids the boolean's face table uses. */
    public final ManifoldMeshExport originalA;

    /** Original id the first operand was stamped with. */
    public final int originalIdA;

    /** Second operand as handed to the kernel. */
    public final QuadTriangulation operandB;

    /** Second operand read back after stamping. */
    public final ManifoldMeshExport originalB;

    /** Original id the second operand was stamped with. */
    public final int originalIdB;

    /** The boolean's output with its run and face tables. */
    public final ManifoldMeshExport result;

    /** Per output triangle: an operand for an untouched copy, {@link MeshBooleanResult#ORIGIN_NEW} otherwise. */
    public int[] faceOrigin;

    /** Per output triangle: the operand whose surface it lies on, never new. */
    public int[] faceSourceOperand;

    /** Per output triangle: the face id in its source operand's mesh, or {@code -1} when unresolved. */
    public int[] faceSourceQuad;

    /**
     * Store the operands, their stamped read-backs and the boolean's output.
     *
     * @param operandA first operand as triangulated for the kernel
     * @param originalA first operand read back after stamping as an original
     * @param originalIdA the first operand's original id
     * @param operandB second operand as triangulated for the kernel
     * @param originalB second operand read back after stamping as an original
     * @param originalIdB the second operand's original id
     * @param result the boolean's output
     */
    public BooleanFaceProvenance(QuadTriangulation operandA, ManifoldMeshExport originalA,
            int originalIdA, QuadTriangulation operandB, ManifoldMeshExport originalB,
            int originalIdB, ManifoldMeshExport result) {
        this.operandA = operandA;
        this.originalA = originalA;
        this.originalIdA = originalIdA;
        this.operandB = operandB;
        this.originalB = originalB;
        this.originalIdB = originalIdB;
        this.result = result;
    }

    /**
     * Fill {@link #faceOrigin}, {@link #faceSourceOperand} and {@link #faceSourceQuad}.
     *
     * @return this
     */
    public BooleanFaceProvenance build() {
        int faceCount = result.triangleCount();
        faceOrigin = new int[faceCount];
        faceSourceOperand = new int[faceCount];
        faceSourceQuad = new int[faceCount];
        Arrays.fill(faceOrigin, MeshBooleanResult.ORIGIN_NEW);
        Arrays.fill(faceSourceOperand, MeshBooleanResult.ORIGIN_NEW);
        Arrays.fill(faceSourceQuad, -1);
        attribute(operandA, originalA, originalIdA, MeshBooleanResult.ORIGIN_A);
        attribute(operandB, originalB, originalIdB, MeshBooleanResult.ORIGIN_B);
        return this;
    }

    /**
     * Attribute the output triangles of one operand's runs.
     *
     * @param operand the operand as triangulated for the kernel
     * @param original the operand read back after stamping
     * @param originalId the operand's original id, naming its runs
     * @param origin the {@link MeshBooleanResult} origin value for this operand
     */
    private void attribute(QuadTriangulation operand, ManifoldMeshExport original, int originalId,
            int origin) {
        int vertexCount = operand.positions.size();
        VertexPositionIndex positions = new VertexPositionIndex(vertexCount);
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            positions.put(operand.positions.get(vertex, 0), operand.positions.get(vertex, 1),
                    operand.positions.get(vertex, 2), vertex);
        }

        int triangleCount = operand.triangles.length / THREE;
        int[] incidentStart = new int[vertexCount + 1];
        for (int corner = 0; corner < operand.triangles.length; corner++) {
            incidentStart[operand.triangles[corner] + 1]++;
        }
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            incidentStart[vertex + 1] += incidentStart[vertex];
        }
        int[] incident = new int[operand.triangles.length];
        int[] incidentFill = Arrays.copyOf(incidentStart, vertexCount);
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            for (int corner = 0; corner < THREE; corner++) {
                incident[incidentFill[operand.triangles[triangle * THREE + corner]]++] = triangle;
            }
        }

        int originalTriangleCount = original.triangleCount();
        int[] originalToInput = new int[originalTriangleCount];
        int[] groupStart = new int[originalTriangleCount + 1];
        for (int triangle = 0; triangle < originalTriangleCount; triangle++) {
            originalToInput[triangle] = inputTriangleOf(original, triangle, positions,
                    operand.triangles, incidentStart, incident);
            int group = groupOf(original, triangle, originalTriangleCount);
            if (group >= 0 && originalToInput[triangle] >= 0) {
                groupStart[group + 1]++;
            }
        }
        for (int group = 0; group < originalTriangleCount; group++) {
            groupStart[group + 1] += groupStart[group];
        }
        int[] groupTriangles = new int[groupStart[originalTriangleCount]];
        int[] groupFill = Arrays.copyOf(groupStart, originalTriangleCount);
        for (int triangle = 0; triangle < originalTriangleCount; triangle++) {
            int group = groupOf(original, triangle, originalTriangleCount);
            if (group >= 0 && originalToInput[triangle] >= 0) {
                groupTriangles[groupFill[group]++] = originalToInput[triangle];
            }
        }

        for (int run = 0; run < result.runCount(); run++) {
            if (result.runOriginalId[run] != originalId) {
                continue;
            }
            int endTriangle = result.runEndTriangle(run);
            for (int triangle = result.runFirstTriangle(run); triangle < endTriangle; triangle++) {
                faceSourceOperand[triangle] = origin;
                int inputTriangle = inputTriangleOf(result, triangle, positions, operand.triangles,
                        incidentStart, incident);
                if (inputTriangle >= 0) {
                    faceOrigin[triangle] = origin;
                    faceSourceQuad[triangle] = operand.triangleSourceFace[inputTriangle];
                    continue;
                }
                faceOrigin[triangle] = MeshBooleanResult.ORIGIN_NEW;
                int group = groupOf(result, triangle, originalTriangleCount);
                if (group < 0) {
                    continue;
                }
                int nearest = nearestInputTriangle(triangle, operand, groupTriangles,
                        groupStart[group], groupStart[group + 1]);
                if (nearest >= 0) {
                    faceSourceQuad[triangle] = operand.triangleSourceFace[nearest];
                }
            }
        }
    }

    /**
     * The coplanar face group of an exported triangle, as an index into the stamped operand.
     *
     * @param export the export holding the triangle
     * @param triangle triangle index within the export
     * @param groupCount number of triangles in the stamped operand, bounding valid ids
     * @return the group index, or {@code -1} when the kernel tracked no face or it is out of range
     */
    private static int groupOf(ManifoldMeshExport export, int triangle, int groupCount) {
        if (triangle >= export.faceId.length) {
            return -1;
        }
        long group = export.faceId[triangle];
        return group >= 0 && group < groupCount ? (int) group : -1;
    }

    /**
     * The input triangle an exported triangle copies exactly, corner for corner.
     *
     * @param export the export holding the triangle
     * @param triangle triangle index within the export
     * @param positions exact-position lookup over the input vertices
     * @param inputTriangles input triangle corners, three per triangle
     * @param incidentStart per input vertex, offset of its incident triangles in {@code incident}
     * @param incident input triangles grouped by incident vertex
     * @return the input triangle index, or {@code -1} when the corners are not all input vertices
     *         forming one input triangle
     */
    private static int inputTriangleOf(ManifoldMeshExport export, int triangle,
            VertexPositionIndex positions, int[] inputTriangles, int[] incidentStart,
            int[] incident) {
        int cornerA = positions.find(export.cornerCoordinate(triangle, 0, 0),
                export.cornerCoordinate(triangle, 0, 1), export.cornerCoordinate(triangle, 0, 2));
        int cornerB = positions.find(export.cornerCoordinate(triangle, 1, 0),
                export.cornerCoordinate(triangle, 1, 1), export.cornerCoordinate(triangle, 1, 2));
        int cornerC = positions.find(export.cornerCoordinate(triangle, 2, 0),
                export.cornerCoordinate(triangle, 2, 1), export.cornerCoordinate(triangle, 2, 2));
        if (cornerA < 0 || cornerB < 0 || cornerC < 0) {
            return -1;
        }
        for (int slot = incidentStart[cornerA]; slot < incidentStart[cornerA + 1]; slot++) {
            int candidate = incident[slot];
            boolean hasB = false;
            boolean hasC = false;
            for (int corner = 0; corner < THREE; corner++) {
                int vertex = inputTriangles[candidate * THREE + corner];
                hasB |= vertex == cornerB;
                hasC |= vertex == cornerC;
            }
            if (hasB && hasC) {
                return candidate;
            }
        }
        return -1;
    }

    /**
     * Among a face group's input triangles, the one whose plane best contains an output
     * triangle's centroid, by the least negative smallest barycentric coordinate of the centroid
     * projected onto each plane; degenerate candidates are skipped.
     *
     * @param triangle output triangle index
     * @param operand the operand supplying the candidate triangles' positions
     * @param groupTriangles input triangle indices grouped by face
     * @param start offset of the group's first candidate in {@code groupTriangles}
     * @param end offset one past the group's last candidate
     * @return the best candidate's input triangle index, or {@code -1} for an empty group
     */
    private int nearestInputTriangle(int triangle, QuadTriangulation operand, int[] groupTriangles,
            int start, int end) {
        Vector3d centroid = new Vector3d();
        for (int corner = 0; corner < THREE; corner++) {
            centroid.add(result.cornerCoordinate(triangle, corner, 0), result.cornerCoordinate(triangle, corner, 1),
                    result.cornerCoordinate(triangle, corner, 2));
        }
        centroid.div(THREE);

        PackedPoints positions = operand.positions;
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int slot = start; slot < end; slot++) {
            int candidate = groupTriangles[slot];
            Vector3f origin = positions.at(operand.triangles[candidate * THREE]);
            Vector3d edgeB = new Vector3d(positions.at(operand.triangles[candidate * THREE + 1]).sub(origin));
            Vector3d edgeC = new Vector3d(positions.at(operand.triangles[candidate * THREE + 2]).sub(origin));
            Vector3d toPoint = new Vector3d(centroid).sub(origin.x, origin.y, origin.z);
            double dotBC = edgeB.dot(edgeC);
            double denominator = edgeB.lengthSquared() * edgeC.lengthSquared() - dotBC * dotBC;
            if (denominator == 0) {
                continue;
            }
            double weightB = (edgeC.lengthSquared() * toPoint.dot(edgeB) - dotBC * toPoint.dot(edgeC)) / denominator;
            double weightC = (edgeB.lengthSquared() * toPoint.dot(edgeC) - dotBC * toPoint.dot(edgeB)) / denominator;
            double score = Math.min(1 - weightB - weightC, Math.min(weightB, weightC));
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }
}
