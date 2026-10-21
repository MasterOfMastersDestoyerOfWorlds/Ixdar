package ixdar.nodegraph.nodes.modifier;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.bundle.GeometryBundles;
import ixdar.geometry.mesh.data.ops.MeshVertexOffset;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.nodegraph.field.FieldBroadcast;

/**
 * Adds edge loops along a world-space axis, splitting each quad's axis-aligned edges; faces of any
 * corner count with no aligned edge pass through unchanged.
 *
 * <p>Cuts land on straight edge midpoints, so Bezier handles on a cage go stale; use
 * {@code coons_loop_cut} there.
 */
@MeshNodeAnnotation(id = "loop_cut", category = NodeCategory.MODIFIERS)
public class LoopCutNode implements MeshNode {
    public static final String Z = "Z";
    public static final float AXIS_ALIGNMENT_THRESHOLD = 0.5f;
    public static final float MIN_EDGE_LENGTH = 1e-9f;
    public static final int STRIPS_ACROSS_FIRST_EDGE = 1;
    public static final int STRIPS_ACROSS_SECOND_EDGE = 2;

    public static final InputPort MESH_IN = new InputPort("mesh", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort GEOMETRY_IN = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort CUTS = new InputPort("cuts", PortType.INT, 1, 1f, 32f);
    public static final InputPort AXIS = new InputPort("axis", PortType.STRING, Z);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY_IN.name, PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(MESH_IN, GEOMETRY_IN, CUTS, AXIS);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Inserts edge loops along a world-space axis (X, Y, or Z), splitting aligned quad faces to add resolution where needed.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                MESH_IN.name, "Topology-only input (alternative to geometry). Used when no bundle slots need preservation.",
                GEOMETRY_IN.name, "Geometry bundle input/output. If it carries bezier handles, de Casteljau subdivision is used and handles survive the cut.",
                CUTS.name, "Number of new edge loops to insert, 1..32. Each cut subdivides aligned edges.",
                AXIS.name, "World-space axis the new loops run PERPENDICULAR to — i.e. cuts=1, axis=X inserts one loop across the X direction. Accepted: X, Y, Z."
        );
    }

    /**
     * Cuts every edge aligned with the axis (|dot| above {@link #AXIS_ALIGNMENT_THRESHOLD}) into
     * {@code cuts + 1} even pieces, read in place by vertex and edge id; a quad with two cut
     * opposite edges becomes {@code cuts + 1} strip quads, every other face passes through.
     *
     * @param ctx node context
     * @throws IllegalArgumentException if a face with a cut edge is not a quad
     */
    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle bundle = ctx.getInput(GEOMETRY_IN.name, GeometryBundle.class);
        MeshTopology mesh = GeometryBundles.meshPart(bundle);
        if (mesh == null) {
            mesh = GeometryBundles.meshPart(ctx.getInput(MESH_IN.name, GeometryBundle.class));
        }
        if (mesh == null || mesh.vertexCount() == 0) {
            ctx.setOutput(GEOMETRY_IN.name, GeometryBundle.empty());
            return;
        }

        Object cutsObj = FieldBroadcast.getInputOrDefault(ctx, CUTS.name, CUTS.defaultValue);
        int cuts = FieldBroadcast.intScalarOrDefault(cutsObj, 1);

        String axisStr = String.valueOf(ctx.getInputValue(AXIS.name));
        if (axisStr == null || axisStr.isEmpty()) axisStr = Z;

        int axisIndex = switch (axisStr.toUpperCase()) {
            case "X" -> 0;
            case "Y" -> 1;
            default -> 2; // Z
        };

        GeometryBundle target = bundle != null ? bundle : GeometryBundle.ofMesh(mesh);
        if (cuts <= 0) {
            ctx.setOutput(GEOMETRY_IN.name, target.withMesh(MeshVertexOffset.apply(mesh, null)));
            return;
        }
        int vertexCount = mesh.vertexCount();
        int faceCount = mesh.faceCount();
        float ax = axisIndex == 0 ? 1f : 0f;
        float ay = axisIndex == 1 ? 1f : 0f;
        float az = axisIndex == 2 ? 1f : 0f;
        int[] activeVertexOfId = new int[mesh.vertexIdBound()];
        float[] sourcePositions = new float[vertexCount * 3];
        Vector3f position = new Vector3f();
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            activeVertexOfId[vertexId] = activeVertex;
            mesh.vertexPosition(vertexId, position);
            sourcePositions[activeVertex * 3] = position.x;
            sourcePositions[activeVertex * 3 + 1] = position.y;
            sourcePositions[activeVertex * 3 + 2] = position.z;
        }

        // Cut vertices are numbered after the source vertices in the order their edges first
        // appear walking the faces, each edge's run ordered from its lower active vertex.
        int[] firstCutVertexOfEdge = new int[mesh.edgeIdBound()];
        Arrays.fill(firstCutVertexOfEdge, MeshTopology.NONE);
        boolean[] classified = new boolean[mesh.edgeIdBound()];
        float[] cutPositions = new float[0];
        int cutVertexCount = 0;
        Vector3f direction = new Vector3f();
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            int corners = mesh.faceVertexCount(faceId);
            for (int slot = 0; slot < corners; slot++) {
                int vertexId = mesh.faceVertexAt(faceId, slot);
                int nextVertexId = mesh.faceVertexAt(faceId, (slot + 1) % corners);
                int edgeId = mesh.edgeBetween(vertexId, nextVertexId);
                if (classified[edgeId]) {
                    continue;
                }
                classified[edgeId] = true;
                int start = activeVertexOfId[vertexId] * 3;
                int end = activeVertexOfId[nextVertexId] * 3;
                direction.set(sourcePositions[end] - sourcePositions[start],
                        sourcePositions[end + 1] - sourcePositions[start + 1],
                        sourcePositions[end + 2] - sourcePositions[start + 2]);
                float length = direction.length();
                if (length < MIN_EDGE_LENGTH) {
                    continue;
                }
                direction.div(length);
                if (Math.abs(direction.x * ax + direction.y * ay + direction.z * az) <= AXIS_ALIGNMENT_THRESHOLD) {
                    continue;
                }
                firstCutVertexOfEdge[edgeId] = vertexCount + cutVertexCount;
                int low = Math.min(start, end);
                int high = Math.max(start, end);
                if ((cutVertexCount + cuts) * 3 > cutPositions.length) {
                    cutPositions = Arrays.copyOf(cutPositions, Math.max(cutPositions.length * 2,
                            (cutVertexCount + cuts) * 3));
                }
                for (int cut = 0; cut < cuts; cut++) {
                    float t = (float) (cut + 1) / (cuts + 1);
                    int offset = (cutVertexCount + cut) * 3;
                    for (int axis = 0; axis < 3; axis++) {
                        cutPositions[offset + axis] = sourcePositions[low + axis]
                                + t * (sourcePositions[high + axis] - sourcePositions[low + axis]);
                    }
                }
                cutVertexCount += cuts;
            }
        }
        if (cutVertexCount == 0) {
            ctx.setOutput(GEOMETRY_IN.name, target.withMesh(MeshVertexOffset.apply(mesh, null)));
            return;
        }
        float[] positions = Arrays.copyOf(sourcePositions, (vertexCount + cutVertexCount) * 3);
        System.arraycopy(cutPositions, 0, positions, vertexCount * 3, cutVertexCount * 3);

        int[] stripsOfFace = new int[faceCount];
        int outputFaceCount = 0;
        int outputCornerCount = 0;
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            int corners = mesh.faceVertexCount(faceId);
            boolean[] cutSide = new boolean[corners];
            boolean anyCut = false;
            for (int slot = 0; slot < corners; slot++) {
                int edgeId = mesh.edgeBetween(mesh.faceVertexAt(faceId, slot),
                        mesh.faceVertexAt(faceId, (slot + 1) % corners));
                cutSide[slot] = firstCutVertexOfEdge[edgeId] != MeshTopology.NONE;
                anyCut |= cutSide[slot];
            }
            if (anyCut && corners != 4) {
                throw new IllegalArgumentException("loop_cut needs quads along the loops it cuts; face "
                        + faceId + " has " + corners + " corners and an axis-aligned edge");
            }
            if (anyCut) {
                if (cutSide[0] && cutSide[2]) {
                    stripsOfFace[activeFace] = STRIPS_ACROSS_FIRST_EDGE;
                } else if (cutSide[1] && cutSide[3]) {
                    stripsOfFace[activeFace] = STRIPS_ACROSS_SECOND_EDGE;
                }
            }
            int pieces = stripsOfFace[activeFace] == 0 ? 1 : cuts + 1;
            outputFaceCount += pieces;
            outputCornerCount += stripsOfFace[activeFace] == 0 ? corners : pieces * 4;
        }

        int[] faceSizes = new int[outputFaceCount];
        int[] faceIndices = new int[outputCornerCount];
        int faceCursor = 0;
        int cornerCursor = 0;
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            int corners = mesh.faceVertexCount(faceId);
            if (stripsOfFace[activeFace] == 0) {
                faceSizes[faceCursor++] = corners;
                for (int slot = 0; slot < corners; slot++) {
                    faceIndices[cornerCursor++] = activeVertexOfId[mesh.faceVertexAt(faceId, slot)];
                }
                continue;
            }
            int vertex0 = mesh.faceVertexAt(faceId, 0);
            int vertex1 = mesh.faceVertexAt(faceId, 1);
            int vertex2 = mesh.faceVertexAt(faceId, 2);
            int vertex3 = mesh.faceVertexAt(faceId, 3);
            int out0 = activeVertexOfId[vertex0];
            int out1 = activeVertexOfId[vertex1];
            int out2 = activeVertexOfId[vertex2];
            int out3 = activeVertexOfId[vertex3];
            for (int strip = 0; strip <= cuts; strip++) {
                faceSizes[faceCursor++] = 4;
                if (stripsOfFace[activeFace] == STRIPS_ACROSS_FIRST_EDGE) {
                    int bottomEdge = firstCutVertexOfEdge[mesh.edgeBetween(vertex0, vertex1)];
                    int topEdge = firstCutVertexOfEdge[mesh.edgeBetween(vertex3, vertex2)];
                    faceIndices[cornerCursor++] = strip == 0 ? out0
                            : cutVertex(bottomEdge, cuts, out0, out1, strip - 1);
                    faceIndices[cornerCursor++] = strip == cuts ? out1
                            : cutVertex(bottomEdge, cuts, out0, out1, strip);
                    faceIndices[cornerCursor++] = strip == cuts ? out2
                            : cutVertex(topEdge, cuts, out3, out2, strip);
                    faceIndices[cornerCursor++] = strip == 0 ? out3
                            : cutVertex(topEdge, cuts, out3, out2, strip - 1);
                } else {
                    int rightEdge = firstCutVertexOfEdge[mesh.edgeBetween(vertex1, vertex2)];
                    int leftEdge = firstCutVertexOfEdge[mesh.edgeBetween(vertex0, vertex3)];
                    faceIndices[cornerCursor++] = strip == 0 ? out0
                            : cutVertex(leftEdge, cuts, out0, out3, strip - 1);
                    faceIndices[cornerCursor++] = strip == 0 ? out1
                            : cutVertex(rightEdge, cuts, out1, out2, strip - 1);
                    faceIndices[cornerCursor++] = strip == cuts ? out2
                            : cutVertex(rightEdge, cuts, out1, out2, strip);
                    faceIndices[cornerCursor++] = strip == cuts ? out3
                            : cutVertex(leftEdge, cuts, out0, out3, strip);
                }
            }
        }
        HalfEdgeMesh out = HalfEdgeMeshOps.bulkAllocateMixed(positions, faceSizes, faceIndices);
        out.computeNormals();
        ctx.setOutput(GEOMETRY_IN.name, target.withMesh(out));
    }

    /**
     * The {@code step}-th cut vertex of an edge counted from {@code fromVertex} toward
     * {@code toVertex}; an edge's run is stored from its lower output vertex.
     *
     * @param firstCutVertex output index of the edge's first stored cut vertex
     * @param cuts           cut vertices per edge
     * @param fromVertex     output index of the endpoint counted from
     * @param toVertex       output index of the other endpoint
     * @param step           cut number from {@code fromVertex}, in {@code [0, cuts)}
     * @return output index of that cut vertex
     */
    private static int cutVertex(int firstCutVertex, int cuts, int fromVertex, int toVertex, int step) {
        return fromVertex <= toVertex ? firstCutVertex + step : firstCutVertex + cuts - 1 - step;
    }
}
