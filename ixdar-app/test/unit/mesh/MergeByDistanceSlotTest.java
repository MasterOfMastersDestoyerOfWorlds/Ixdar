package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.CornerUvField;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.ops.MeshMergeByDistance;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.nodes.data.TagGeometryNode;
import ixdar.geometry.mesh.nodes.modifier.MergeByDistanceNode;
import ixdar.geometry.mesh.nodes.modifier.SetBoneWeightNode;

/**
 * {@code merge_by_distance} on a hand-built strip whose two triangles nearly touch: the weld map it
 * now records carries per-vertex tags and bone weights onto the survivors, and the per-corner UV
 * field follows the faces the weld kept.
 */
class MergeByDistanceSlotTest {

    private static final float EPSILON = 1e-6f;

    /** Gap between the two triangles' shared edge, closed by a weld wider than it. */
    private static final float GAP = 0.001f;

    private static final float WELD_DISTANCE = 0.01f;

    private static final int SOURCE_VERTICES = 6;

    private static final int WELDED_VERTICES = 4;

    private static final int TRIANGLES = 2;

    private static final String TAG = "strip";

    private static final String BONE_SLOT = SetBoneWeightNode.BONE_WEIGHT_PREFIX + "spine";

    @Test
    void weldRecordsTheVertexAndFaceMaps() {
        MeshMergeByDistance welder = new MeshMergeByDistance();

        ArrayMesh welded = (ArrayMesh) welder.weld(splitStrip(), WELD_DISTANCE);

        assertEquals(WELDED_VERTICES, welded.vertexCount(), "the near-duplicate pairs welded");
        assertEquals(TRIANGLES, welded.faceCount(), "both triangles survive");
        assertEquals(SOURCE_VERTICES, welder.weldedVertex.length, "one entry per source vertex");
        assertEquals(welder.weldedVertex[1], welder.weldedVertex[3], "the shared pair welded together");
        assertEquals(welder.weldedVertex[2], welder.weldedVertex[4], "so did the second pair");
        assertArrayEquals(new int[] { 0, 1 }, welder.sourceFace, "no face was dropped");
    }

    @Test
    void tagsOrTogetherAndBoneWeightsTakeTheFirstSource() {
        boolean[] tagged = new boolean[SOURCE_VERTICES];
        tagged[3] = true;
        float[] weights = new float[SOURCE_VERTICES];
        weights[1] = 0.5f;
        weights[3] = 0.25f;
        Map<String, boolean[]> tags = new HashMap<>();
        tags.put(TAG, tagged);
        GeometryBundle base = new GeometryBundle(splitStrip(), Map.of(
                TagGeometryNode.TAGS_SLOT, tags,
                BONE_SLOT, weights,
                CornerUvField.SLOT, stripUv()));

        GeometryBundle out = merge(base);

        MeshMergeByDistance welder = new MeshMergeByDistance();
        welder.weld(splitStrip(), WELD_DISTANCE);
        int shared = welder.weldedVertex[1];
        boolean[] carriedTags = TagGeometryNode.getTags(out).get(TAG);
        assertEquals(out.mesh().vertexCount(), carriedTags.length, "one flag per welded vertex");
        assertTrue(carriedTags[shared], "a tag on either source vertex reaches the survivor");
        float[] carriedWeights = (float[]) out.slots().get(BONE_SLOT);
        assertEquals(out.mesh().vertexCount(), carriedWeights.length, "one weight per welded vertex");
        assertEquals(0.5f, carriedWeights[shared], EPSILON, "the first source's weight wins");
    }

    @Test
    void cornerUvsSurviveTheWeld() {
        GeometryBundle base = new GeometryBundle(splitStrip(),
                Map.of(CornerUvField.SLOT, stripUv()));

        GeometryBundle out = merge(base);

        CornerUvField uv = (CornerUvField) out.slots().get(CornerUvField.SLOT);
        assertEquals(out.mesh().faceCount(), uv.faceCount(), "one UV triple per surviving face");
        CornerUvField source = stripUv();
        for (int face = 0; face < TRIANGLES; face++) {
            for (int corner = 0; corner < CornerUvField.CORNERS_PER_FACE; corner++) {
                assertEquals(source.u(face, corner), uv.u(face, corner), EPSILON,
                        "u of face " + face + " corner " + corner);
                assertEquals(source.v(face, corner), uv.v(face, corner), EPSILON,
                        "v of face " + face + " corner " + corner);
            }
        }
    }

    /**
     * Run {@code merge_by_distance} over a bundle.
     *
     * @param base input bundle
     * @return the node's output bundle
     */
    private static GeometryBundle merge(GeometryBundle base) {
        MergeByDistanceNode node = new MergeByDistanceNode();
        return new MapNodeContext(node)
                .with(MergeByDistanceNode.GEOMETRY, base)
                .with(MergeByDistanceNode.DISTANCE, WELD_DISTANCE)
                .eval()
                .output(MergeByDistanceNode.GEOMETRY_OUT, GeometryBundle.class);
    }

    /**
     * Two triangles that share an edge only up to {@link #GAP}, so a weld collapses six vertices
     * into four without dropping either face.
     *
     * @return the unwelded strip
     */
    private static ArrayMesh splitStrip() {
        float[] positions = {
            0f, 0f, 0f,
            1f, 0f, 0f,
            1f, 1f, 0f,
            1f + GAP, 0f, 0f,
            1f, 1f + GAP, 0f,
            0f, 1f, 0f,
        };
        int[] indices = { 0, 1, 2, 3, 4, 5 };
        ArrayMesh mesh = new ArrayMesh(positions, null, indices, CornerUvField.CORNERS_PER_FACE);
        mesh.computeNormals();
        return mesh;
    }

    /**
     * Distinct UVs per corner of the strip, so a misindexed carry is visible.
     *
     * @return the strip's per-corner UV field
     */
    private static CornerUvField stripUv() {
        return new CornerUvField(
                new double[] { 0.1, 0.2, 0.3, 0.4, 0.5, 0.6 },
                new double[] { 0.9, 0.8, 0.7, 0.6, 0.5, 0.4 });
    }
}
