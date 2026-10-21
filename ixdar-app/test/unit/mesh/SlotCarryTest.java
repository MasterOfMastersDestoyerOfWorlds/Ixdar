package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.CornerUvField;
import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.nodegraph.api.MapNodeContext;
import ixdar.nodegraph.field.BoolField;
import ixdar.nodegraph.nodes.data.TagGeometryNode;
import ixdar.nodegraph.nodes.geometry.JoinGeometryNode;
import ixdar.nodegraph.nodes.modifier.ExtrudeMeshNode;
import ixdar.nodegraph.nodes.modifier.LoopCutNode;
import ixdar.nodegraph.nodes.modifier.MirrorGeometryNode;
import ixdar.nodegraph.nodes.patch.AssignBezierHandlesNode;

/**
 * Slots follow the provenance layers the topology-changing nodes record on their output meshes, on
 * hand-built strips and triangles: tags and marks land on the geometry they described, corner UVs
 * on the mirrored corners, and a weld ORs the tags it fuses.
 */
class SlotCarryTest {

    private static final float EPSILON = 1e-6f;

    private static final float EXTRUDE_OFFSET = 0.5f;

    private static final float WELD_DISTANCE = 1e-3f;

    private static final int QUAD_CORNERS = 4;

    private static final int TRIANGLE_CORNERS = 3;

    private static final String TAG = "t";

    private static final String OTHER_TAG = "u";

    private static final String CREASE = "crease";

    private static final float HANDLE_X = 1f;

    private static final float HANDLE_Y = 2f;

    private static final float HANDLE_Z = 3f;

    @Test
    void extrudeCarriesTagsAndMarksOntoTheGeometryTheyDescribed() {
        HalfEdgeMesh strip = quadStrip();
        boolean[] tagged = new boolean[strip.vertexIdBound()];
        tagged[0] = true;
        boolean[] crease = new boolean[strip.edgeIdBound()];
        crease[strip.edgeBetween(0, 1)] = true;
        GeometryBundle base = EdgeMarks.with(GeometryBundle.ofMesh(strip)
                .withSlot(TagGeometryNode.TAGS_SLOT, Map.of(TAG, tagged)), CREASE, crease);

        GeometryBundle out = new MapNodeContext(new ExtrudeMeshNode())
                .with(ExtrudeMeshNode.GEOMETRY, base)
                .with(ExtrudeMeshNode.OFFSET, EXTRUDE_OFFSET)
                .with(ExtrudeMeshNode.SELECTION, new BoolField(new boolean[] {true, false}))
                .eval()
                .output(ExtrudeMeshNode.GEOMETRY_OUT, GeometryBundle.class);

        MeshTopology mesh = out.mesh();
        boolean[] carried = TagGeometryNode.getTags(out).get(TAG);
        Vector3f position = new Vector3f();
        int taggedCount = 0;
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            mesh.vertexPosition(vertexId, position);
            boolean overOrigin = Math.abs(position.x) < EPSILON && Math.abs(position.y) < EPSILON;
            assertEquals(overOrigin, carried[vertexId],
                    "only the tagged vertex and its extruded copy keep the tag");
            taggedCount += carried[vertexId] ? 1 : 0;
        }
        assertEquals(2, taggedCount, "the tagged corner and its moved copy");

        boolean[] marks = EdgeMarks.bools(out, CREASE);
        int markedCount = 0;
        for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
            int edgeId = mesh.edgeIdAt(activeEdge);
            if (!marks[edgeId]) {
                continue;
            }
            markedCount++;
            for (int end = 0; end < 2; end++) {
                int halfEdge = mesh.edgeHalfEdge(edgeId);
                mesh.vertexPosition(end == 0 ? mesh.halfEdgeVertex(halfEdge)
                        : mesh.halfEdgeEndVertex(halfEdge), position);
                assertEquals(0f, position.y, EPSILON, "a marked edge lies along y = 0");
                assertTrue(position.x <= 1f + EPSILON, "a marked edge ends at x <= 1");
            }
        }
        assertEquals(2, markedCount, "the creased edge and the cap's copy of it");
    }

    @Test
    void loopCutKeepsAMarkOnBothHalvesOfTheEdgeItSplits() {
        HalfEdgeMesh strip = quadStrip();
        boolean[] crease = new boolean[strip.edgeIdBound()];
        crease[strip.edgeBetween(0, 1)] = true;
        GeometryBundle base = EdgeMarks.with(GeometryBundle.ofMesh(strip), CREASE, crease);

        GeometryBundle out = new MapNodeContext(new LoopCutNode())
                .with(LoopCutNode.GEOMETRY_IN, base)
                .with(LoopCutNode.CUTS, 1)
                .with(LoopCutNode.AXIS, "X")
                .eval()
                .output(LoopCutNode.GEOMETRY_OUT, GeometryBundle.class);

        MeshTopology mesh = out.mesh();
        boolean[] marks = EdgeMarks.bools(out, CREASE);
        Vector3f tail = new Vector3f();
        Vector3f head = new Vector3f();
        int markedCount = 0;
        for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
            int edgeId = mesh.edgeIdAt(activeEdge);
            int halfEdge = mesh.edgeHalfEdge(edgeId);
            mesh.vertexPosition(mesh.halfEdgeVertex(halfEdge), tail);
            mesh.vertexPosition(mesh.halfEdgeEndVertex(halfEdge), head);
            boolean onCreasedEdge = Math.abs(tail.y) < EPSILON && Math.abs(head.y) < EPSILON
                    && Math.max(tail.x, head.x) <= 1f + EPSILON;
            assertEquals(onCreasedEdge, marks[edgeId], "the mark covers exactly the cut edge");
            markedCount += marks[edgeId] ? 1 : 0;
        }
        assertEquals(2, markedCount, "both halves of the creased edge");
    }

    @Test
    void mirrorMatchesEachCornerUvThroughTheReversedWindingAndReflectsHandles() {
        float[] positions = {1f, 0f, 0f, 2f, 0f, 0f, 1f, 1f, 0f};
        HalfEdgeMesh triangle = HalfEdgeMeshOps.bulkAllocate(positions, new int[] {0, 1, 2},
                TRIANGLE_CORNERS);
        triangle.computeNormals();
        double[] cornerU = {0.1, 0.2, 0.3};
        double[] cornerV = {0.4, 0.5, 0.6};
        float[] handle = new float[triangle.edgeIdBound() * TRIANGLE_CORNERS];
        for (int edgeId = 0; edgeId < triangle.edgeIdBound(); edgeId++) {
            handle[edgeId * TRIANGLE_CORNERS] = HANDLE_X;
            handle[edgeId * TRIANGLE_CORNERS + 1] = HANDLE_Y;
            handle[edgeId * TRIANGLE_CORNERS + 2] = HANDLE_Z;
        }
        GeometryBundle base = GeometryBundle.ofMesh(triangle)
                .withSlot(CornerUvField.SLOT, new CornerUvField(cornerU, cornerV))
                .withSlot(AssignBezierHandlesNode.SLOT_HANDLES_START, handle)
                .withSlot(AssignBezierHandlesNode.SLOT_HANDLES_END, handle.clone());

        GeometryBundle out = new MapNodeContext(new MirrorGeometryNode())
                .with(MirrorGeometryNode.GEOMETRY, base)
                .with(MirrorGeometryNode.AXIS, MirrorGeometryNode.X)
                .with(MirrorGeometryNode.MERGE_DISTANCE, 0f)
                .eval()
                .output(MirrorGeometryNode.GEOMETRY_OUT, GeometryBundle.class);

        MeshTopology mesh = out.mesh();
        CornerUvField uv = CornerUvField.of(out);
        Vector3f position = new Vector3f();
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            for (int corner = 0; corner < TRIANGLE_CORNERS; corner++) {
                mesh.vertexPosition(mesh.faceVertexAt(faceId, corner), position);
                int source = -1;
                for (int inputCorner = 0; inputCorner < TRIANGLE_CORNERS; inputCorner++) {
                    if (Math.abs(positions[inputCorner * TRIANGLE_CORNERS] - Math.abs(position.x))
                            < EPSILON
                            && Math.abs(positions[inputCorner * TRIANGLE_CORNERS + 1] - position.y)
                            < EPSILON) {
                        source = inputCorner;
                    }
                }
                assertTrue(source >= 0, "every output corner reflects an input corner");
                assertEquals(cornerU[source], uv.u(faceId, corner), EPSILON,
                        "a mirrored corner samples the u of the corner it reflects");
                assertEquals(cornerV[source], uv.v(faceId, corner), EPSILON,
                        "a mirrored corner samples the v of the corner it reflects");
            }
        }

        float[] start = (float[]) out.slots().get(AssignBezierHandlesNode.SLOT_HANDLES_START);
        for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
            int edgeId = mesh.edgeIdAt(activeEdge);
            mesh.vertexPosition(mesh.halfEdgeVertex(mesh.edgeHalfEdge(edgeId)), position);
            float expectedX = position.x < 0f ? -HANDLE_X : HANDLE_X;
            assertArrayEquals(new float[] {expectedX, HANDLE_Y, HANDLE_Z},
                    Arrays.copyOfRange(start, edgeId * TRIANGLE_CORNERS,
                            edgeId * TRIANGLE_CORNERS + TRIANGLE_CORNERS),
                    EPSILON, "the mirrored half's handles reflect across the plane");
        }
    }

    @Test
    void joinWithAWeldOrsTheTagsOfTheVerticesItFuses() {
        HalfEdgeMesh left = HalfEdgeMeshOps.bulkAllocate(
                new float[] {0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f}, new int[] {0, 1, 2},
                TRIANGLE_CORNERS);
        HalfEdgeMesh right = HalfEdgeMeshOps.bulkAllocate(
                new float[] {1f, 0f, 0f, 2f, 0f, 0f, 1f, 1f, 0f}, new int[] {0, 1, 2},
                TRIANGLE_CORNERS);
        GeometryBundle a = taggedEverywhere(left, TAG);
        GeometryBundle b = taggedEverywhere(right, OTHER_TAG);

        GeometryBundle out = new MapNodeContext(new JoinGeometryNode())
                .with(JoinGeometryNode.A, a)
                .with(JoinGeometryNode.B, b)
                .with(JoinGeometryNode.MERGE_DISTANCE, WELD_DISTANCE)
                .eval()
                .output(JoinGeometryNode.GEOMETRY, GeometryBundle.class);

        MeshTopology mesh = out.mesh();
        assertEquals(QUAD_CORNERS, mesh.vertexCount(), "the shared edge's two pairs welded");
        Map<String, boolean[]> tags = TagGeometryNode.getTags(out);
        Vector3f position = new Vector3f();
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            mesh.vertexPosition(vertexId, position);
            boolean onSeam = Math.abs(position.x - 1f) < EPSILON;
            assertEquals(position.x < 1f + EPSILON, tags.get(TAG)[vertexId],
                    "the left triangle's vertices, seam included, keep its tag");
            assertEquals(position.x > 1f - EPSILON, tags.get(OTHER_TAG)[vertexId],
                    "the right triangle's vertices, seam included, keep its tag");
            if (onSeam) {
                assertTrue(tags.get(TAG)[vertexId] && tags.get(OTHER_TAG)[vertexId],
                        "a welded seam vertex holds both tags");
            }
        }
    }

    @Test
    void tagGeometryLeavesTheUpstreamTagArrayUntouched() {
        HalfEdgeMesh strip = quadStrip();
        boolean[] upstream = new boolean[strip.vertexCount()];
        Map<String, boolean[]> tags = new HashMap<>();
        tags.put(TAG, upstream);
        GeometryBundle base = GeometryBundle.ofMesh(strip).withSlot(TagGeometryNode.TAGS_SLOT, tags);

        GeometryBundle out = new MapNodeContext(new TagGeometryNode())
                .with(TagGeometryNode.GEOMETRY, base)
                .with(TagGeometryNode.TAGS, TAG)
                .eval()
                .output(TagGeometryNode.GEOMETRY_OUT, GeometryBundle.class);

        boolean[] tagged = TagGeometryNode.getTags(out).get(TAG);
        for (boolean flag : tagged) {
            assertTrue(flag, "the output tags every vertex");
        }
        for (boolean flag : upstream) {
            assertFalse(flag, "the input bundle's array, an upstream node's output, is not written");
        }
    }

    /**
     * Two unit quads side by side in the z = 0 plane, x in [0, 2], y in [0, 1].
     *
     * @return the strip, vertices 0..2 along y = 0 and 3..5 along y = 1
     */
    private static HalfEdgeMesh quadStrip() {
        float[] positions = {
            0f, 0f, 0f, 1f, 0f, 0f, 2f, 0f, 0f,
            0f, 1f, 0f, 1f, 1f, 0f, 2f, 1f, 0f,
        };
        HalfEdgeMesh strip = HalfEdgeMeshOps.bulkAllocate(positions,
                new int[] {0, 1, 4, TRIANGLE_CORNERS, 1, 2, 5, 4}, QUAD_CORNERS);
        strip.computeNormals();
        return strip;
    }

    /**
     * A bundle tagging every vertex of a mesh.
     *
     * @param mesh mesh to wrap
     * @param tag  tag name
     * @return the bundle
     */
    private static GeometryBundle taggedEverywhere(HalfEdgeMesh mesh, String tag) {
        mesh.computeNormals();
        boolean[] all = new boolean[mesh.vertexCount()];
        Arrays.fill(all, true);
        return GeometryBundle.ofMesh(mesh).withSlot(TagGeometryNode.TAGS_SLOT, Map.of(tag, all));
    }
}
