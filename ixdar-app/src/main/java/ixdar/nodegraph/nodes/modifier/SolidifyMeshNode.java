package ixdar.nodegraph.nodes.modifier;

import java.util.Objects;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.nodegraph.api.InputPort;

import java.util.Map;
import ixdar.nodegraph.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.bundle.SlotCarry;
import ixdar.geometry.mesh.data.ops.MeshVertexOffset;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.nodegraph.field.FieldBroadcast;

@MeshNodeAnnotation(id = "solidify_mesh", category = NodeCategory.MODIFIERS)
public class SolidifyMeshNode implements MeshNode {
    public static final float NORMAL_LENGTH_EPSILON = 1e-20f;
    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort THICKNESS = new InputPort("thickness", PortType.FLOAT, 0.01f, 0.001f, 10f);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, THICKNESS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Gives thickness to an open polygon mesh by duplicating and offsetting it along vertex normals, with side walls on its boundary edges, creating a watertight shell.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input/output. Flat (or near-flat) polygon surface becomes a closed shell wrapped in a GeometryBundle.",
                THICKNESS.name, "Offset distance along the averaged vertex normal. 0 = no thickness; positive = outward shell."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = Objects.requireNonNullElse(ctx.getInput(GEOMETRY.name, GeometryBundle.class), GeometryBundle.empty());
        MeshTopology in = base.mesh();
        if (in == null || in.vertexCount() == 0) {
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            return;
        }
        Object to = FieldBroadcast.getInputOrDefault(ctx, THICKNESS.name, THICKNESS.defaultValue);
        float t = FieldBroadcast.floatScalarOrDefault(to, 0.01f);
        ctx.setOutput(GEOMETRY.name,
                SlotCarry.carry(List.of(base), GeometryBundle.ofMesh(solidify(in, t))));
    }

    /**
     * Gives an open polygon sheet thickness: each vertex copied inward along its summed face
     * normals, each face reversed on the copy, one side quad per boundary edge, sources recorded.
     *
     * @param in        polygon sheet, faces of any corner count
     * @param thickness offset distance; its absolute value is used, and 0 returns a copy
     * @return the solidified mesh with normals computed; output vertex {@code i} is the input's
     *         active vertex {@code i}, its offset copy is {@code vertexCount + i}
     */
    public static MeshTopology solidify(MeshTopology in, float thickness) {
        float depth = Math.abs(thickness);
        if (depth == 0f) {
            return MeshVertexOffset.apply(in, null);
        }
        int vertexCount = in.vertexCount();
        int faceCount = in.faceCount();
        int[] outputVertexOfId = new int[in.vertexIdBound()];
        PackedPoints positions = new PackedPoints(vertexCount * 2);
        Vector3f position = new Vector3f();
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            int vertexId = in.vertexIdAt(activeVertex);
            outputVertexOfId[vertexId] = activeVertex;
            positions.set(activeVertex, in.vertexPosition(vertexId, position));
        }

        PackedPoints normalSums = new PackedPoints(vertexCount);
        int cornerCount = 0;
        int boundaryEdges = 0;
        Vector3f corner = new Vector3f();
        Vector3f edgeA = new Vector3f();
        Vector3f edgeB = new Vector3f();
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            int faceId = in.faceIdAt(activeFace);
            int corners = in.faceVertexCount(faceId);
            in.vertexPosition(in.faceVertexAt(faceId, 0), corner);
            in.vertexPosition(in.faceVertexAt(faceId, 1), edgeA).sub(corner);
            in.vertexPosition(in.faceVertexAt(faceId, 2), edgeB).sub(corner);
            edgeA.cross(edgeB);
            float length = edgeA.length();
            if (length > NORMAL_LENGTH_EPSILON) {
                edgeA.mul(1.0f / length);
            } else {
                edgeA.set(0f, 1f, 0f);
            }
            for (int slot = 0; slot < corners; slot++) {
                int vertexId = in.faceVertexAt(faceId, slot);
                normalSums.add(outputVertexOfId[vertexId], edgeA);
                if (in.isBoundaryEdge(in.edgeBetween(vertexId, in.faceVertexAt(faceId, (slot + 1) % corners)))) {
                    boundaryEdges++;
                }
            }
            cornerCount += corners;
        }
        for (int outputVertex = 0; outputVertex < vertexCount; outputVertex++) {
            Vector3f normal = normalSums.at(outputVertex);
            float inverseLength = 1.0f / Math.max(NORMAL_LENGTH_EPSILON, normal.length());
            positions.set(vertexCount + outputVertex,
                    positions.at(outputVertex).fma(-inverseLength * depth, normal));
        }

        int[] faceSizes = new int[faceCount * 2 + boundaryEdges];
        int[] faceIndices = new int[cornerCount * 2 + boundaryEdges * 4];
        int[] sourceVertex = new int[vertexCount * 2];
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            sourceVertex[activeVertex] = in.vertexIdAt(activeVertex);
            sourceVertex[vertexCount + activeVertex] = sourceVertex[activeVertex];
        }
        int[] sourceFace = new int[faceSizes.length];
        int bottomCursor = cornerCount;
        int sideCursor = cornerCount * 2;
        int topCursor = 0;
        int sideFace = faceCount * 2;
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            int faceId = in.faceIdAt(activeFace);
            int corners = in.faceVertexCount(faceId);
            faceSizes[activeFace] = corners;
            faceSizes[faceCount + activeFace] = corners;
            sourceFace[activeFace] = faceId;
            sourceFace[faceCount + activeFace] = faceId;
            for (int slot = 0; slot < corners; slot++) {
                int vertexId = in.faceVertexAt(faceId, slot);
                int nextVertexId = in.faceVertexAt(faceId, (slot + 1) % corners);
                int start = outputVertexOfId[vertexId];
                faceIndices[topCursor++] = start;
                faceIndices[bottomCursor++] =
                        outputVertexOfId[in.faceVertexAt(faceId, (corners - slot) % corners)] + vertexCount;
                if (in.isBoundaryEdge(in.edgeBetween(vertexId, nextVertexId))) {
                    int end = outputVertexOfId[nextVertexId];
                    sourceFace[sideFace] = faceId;
                    faceSizes[sideFace++] = 4;
                    faceIndices[sideCursor++] = start;
                    faceIndices[sideCursor++] = start + vertexCount;
                    faceIndices[sideCursor++] = end + vertexCount;
                    faceIndices[sideCursor++] = end;
                }
            }
        }
        HalfEdgeMesh out = HalfEdgeMeshOps.bulkAllocateMixed(positions,faceSizes, faceIndices);
        out.computeNormals();
        out.recordSources(sourceVertex, null, sourceFace);
        return out;
    }
}
