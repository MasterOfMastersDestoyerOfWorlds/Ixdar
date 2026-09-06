package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.CornerUvField;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MaterialData;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.nodes.api.Vector3Value;
import ixdar.geometry.mesh.nodes.modifier.MirrorGeometryNode;
import ixdar.geometry.mesh.nodes.transform.TransformGeometryNode;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;

/**
 * The hand-written {@code textured_square.gltf} fixture embeds a 2x2 base-colour PNG and a 1x1
 * metallic-roughness PNG, so the material slot's pixels, dimensions and factors are all checkable
 * without a GPU or a scan on disk.
 */
class GltfMaterialSlotTest {

    private static final String TEXTURED_GLTF = "test/resources/gltf/textured_square.gltf";

    private static final String PLAIN_GLTF = "test/resources/gltf/two_triangles.gltf";

    private static final float EPSILON = 1e-6f;

    private static final int SQUARE_VERTICES = 4;

    private static final int SQUARE_TRIANGLES = 2;

    private static final int BASE_COLOR_SIZE = 2;

    /**
     * The fixture's PNG rows top-down are red/green then blue/white; stb flips to the OpenGL
     * bottom-left origin, so the decoded bytes start with the PNG's bottom row.
     */
    private static final byte[] EXPECTED_BASE_COLOR = {
        0, 0, -1, -1,
        -1, -1, -1, -1,
        -1, 0, 0, -1,
        0, -1, 0, -1,
    };

    /** The fixture's 1x1 metallic-roughness texel: occlusion 0, roughness 128, metalness 64. */
    private static final byte[] EXPECTED_METALLIC_ROUGHNESS = { 0, -128, 64, -1 };

    private static final float[] EXPECTED_BASE_COLOR_FACTOR = { 0.25f, 0.5f, 0.75f, 1f };

    private static final float EXPECTED_METALLIC_FACTOR = 0.25f;

    private static final float EXPECTED_ROUGHNESS_FACTOR = 0.5f;

    @Test
    void baseColorPngDecodesToTheExpectedPixelsAndSize() throws IOException {
        MaterialData material = MaterialData.of(MeshLoader.loadBundle(TEXTURED_GLTF));

        assertNotNull(material, "the textured fixture carries a material slot");
        assertTrue(material.hasBaseColorTexture(), "the base colour decoded");
        assertEquals(BASE_COLOR_SIZE, material.baseColorWidth, "base colour width");
        assertEquals(BASE_COLOR_SIZE, material.baseColorHeight, "base colour height");
        assertArrayEquals(EXPECTED_BASE_COLOR, material.baseColorRgba, "base colour pixels, bottom row first");
    }

    @Test
    void metallicRoughnessPngAndFactorsRideTheSameSlot() throws IOException {
        MaterialData material = MaterialData.of(MeshLoader.loadBundle(TEXTURED_GLTF));

        assertNotNull(material, "the textured fixture carries a material slot");
        assertEquals(1, material.metallicRoughnessWidth, "metallic-roughness width");
        assertEquals(1, material.metallicRoughnessHeight, "metallic-roughness height");
        assertArrayEquals(EXPECTED_METALLIC_ROUGHNESS, material.metallicRoughnessRgba,
                "metallic-roughness texel");
        assertArrayEquals(EXPECTED_BASE_COLOR_FACTOR, material.baseColorFactor, EPSILON, "base colour factor");
        assertEquals(EXPECTED_METALLIC_FACTOR, material.metallicFactor, EPSILON, "metallic factor");
        assertEquals(EXPECTED_ROUGHNESS_FACTOR, material.roughnessFactor, EPSILON, "roughness factor");
    }

    @Test
    void bothTextureSlotsReachTheBundleTogether() throws IOException {
        GeometryBundle bundle = MeshLoader.loadBundle(TEXTURED_GLTF);

        assertTrue(bundle.slots().containsKey(CornerUvField.SLOT), "the UV slot is present");
        assertTrue(bundle.slots().containsKey(MaterialData.SLOT), "the material slot is present");
        assertEquals(bundle.mesh().faceCount(), uvOf(bundle).faceCount(), "one UV triple per face");
    }

    @Test
    void aFixtureWithoutAMaterialHasNoMaterialSlot() throws IOException {
        GeometryBundle bundle = MeshLoader.loadBundle(PLAIN_GLTF);

        assertNull(MaterialData.of(bundle), "the untextured fixture carries no material");
        assertFalse(bundle.slots().containsKey(MaterialData.SLOT), "no material slot either");
    }

    /**
     * The TEXTURED draw is chosen by {@link HalfEdgeMeshRuntime#samplesTexture} alone, so the
     * fallback to the solid colour is checkable without a GL context.
     */
    @Test
    void texturedModeFallsBackToSolidWhenTheDrawIsNotReady() {
        assertTrue(HalfEdgeMeshRuntime.samplesTexture(HalfEdgeMeshRuntime.ShaderMode.TEXTURED, true),
                "TEXTURED with texture and split geometry samples it");
        assertFalse(HalfEdgeMeshRuntime.samplesTexture(HalfEdgeMeshRuntime.ShaderMode.TEXTURED, false),
                "TEXTURED without them falls back to the solid colour");
        assertFalse(HalfEdgeMeshRuntime.samplesTexture(HalfEdgeMeshRuntime.ShaderMode.LAMBERT, true),
                "LAMBERT never samples the base colour");
    }

    @Test
    void transformGeometryCarriesBothSlotsUnchanged() {
        GeometryBundle base = texturedSquare();

        TransformGeometryNode node = new TransformGeometryNode();
        GeometryBundle out = new MapNodeContext(node)
                .with(TransformGeometryNode.GEOMETRY, base)
                .with(TransformGeometryNode.TRANSLATION, new Vector3Value(1f, 2f, 3f))
                .eval()
                .output(TransformGeometryNode.GEOMETRY_OUT, GeometryBundle.class);

        assertSame(MaterialData.of(base), MaterialData.of(out), "the material rides through");
        assertArrayEquals(uvOf(base).cornerU, uvOf(out).cornerU, EPSILON, "translation leaves u alone");
        assertArrayEquals(uvOf(base).cornerV, uvOf(out).cornerV, EPSILON, "translation leaves v alone");
    }

    /**
     * The mirror appends one reversed-winding face per input face, so the mirrored corners hold the
     * input's corner UVs read backwards and every mirrored corner samples the same texel.
     */
    @Test
    void mirrorGeometryReversesTheCornerUvsForTheMirroredHalf() {
        GeometryBundle base = texturedSquare();

        MirrorGeometryNode node = new MirrorGeometryNode();
        GeometryBundle out = new MapNodeContext(node)
                .with(MirrorGeometryNode.GEOMETRY, base)
                .with(MirrorGeometryNode.AXIS, "X")
                .with(MirrorGeometryNode.MERGE_DISTANCE, 0f)
                .eval()
                .output(MirrorGeometryNode.GEOMETRY_OUT, GeometryBundle.class);

        assertSame(MaterialData.of(base), MaterialData.of(out), "the material rides through");
        CornerUvField source = uvOf(base);
        CornerUvField mirrored = uvOf(out);
        assertEquals(out.mesh().faceCount(), mirrored.faceCount(), "one UV triple per mirrored face");
        assertEquals(SQUARE_TRIANGLES * 2, mirrored.faceCount(), "the mirror doubled the face count");
        for (int face = 0; face < SQUARE_TRIANGLES; face++) {
            for (int corner = 0; corner < CornerUvField.CORNERS_PER_FACE; corner++) {
                int reversed = CornerUvField.CORNERS_PER_FACE - 1 - corner;
                assertEquals(source.u(face, reversed), mirrored.u(SQUARE_TRIANGLES + face, corner),
                        EPSILON, "mirrored u of face " + face + " corner " + corner);
                assertEquals(source.v(face, reversed), mirrored.v(SQUARE_TRIANGLES + face, corner),
                        EPSILON, "mirrored v of face " + face + " corner " + corner);
            }
        }
    }

    /**
     * Mirroring welds the seam vertices by default, which renumbers vertices but keeps every face,
     * so the per-corner UVs still follow.
     */
    @Test
    void mirrorGeometryKeepsTheCornerUvsAcrossTheSeamWeld() {
        GeometryBundle base = texturedSquare();

        MirrorGeometryNode node = new MirrorGeometryNode();
        GeometryBundle out = new MapNodeContext(node)
                .with(MirrorGeometryNode.GEOMETRY, base)
                .with(MirrorGeometryNode.AXIS, "X")
                .eval()
                .output(MirrorGeometryNode.GEOMETRY_OUT, GeometryBundle.class);

        CornerUvField mirrored = uvOf(out);
        assertNotNull(mirrored, "the weld did not drop the UV slot");
        assertEquals(out.mesh().faceCount(), mirrored.faceCount(), "one UV triple per surviving face");
        assertEquals(SQUARE_TRIANGLES * 2, mirrored.faceCount(), "every face survived the seam weld");
        assertTrue(out.mesh().vertexCount() < SQUARE_VERTICES * 2, "the seam vertices welded");
        CornerUvField source = uvOf(base);
        for (int corner = 0; corner < CornerUvField.CORNERS_PER_FACE; corner++) {
            assertEquals(source.u(0, corner), mirrored.u(0, corner), EPSILON,
                    "the original half keeps its u at corner " + corner);
        }
    }

    /**
     * The UV field a bundle carries.
     *
     * @param bundle bundle to read
     * @return the per-corner field on {@link CornerUvField#SLOT}
     */
    private static CornerUvField uvOf(GeometryBundle bundle) {
        return (CornerUvField) bundle.slots().get(CornerUvField.SLOT);
    }

    /**
     * A hand-built unit square with corner UVs and a 1x1 white material — no file, no GPU.
     *
     * @return bundle carrying both texture slots
     */
    private static GeometryBundle texturedSquare() {
        float[] positions = {
            0f, 0f, 0f,
            1f, 0f, 0f,
            1f, 1f, 0f,
            0f, 1f, 0f,
        };
        int[] indices = { 0, 1, 2, 0, 2, 3 };
        double[] cornerU = { 0, 1, 1, 0, 1, 0 };
        double[] cornerV = { 0, 0, 1, 0, 1, 1 };
        ArrayMesh mesh = new ArrayMesh(positions, null, indices, CornerUvField.CORNERS_PER_FACE);
        mesh.computeNormals();
        MaterialData material = new MaterialData(
                new byte[] { -1, -1, -1, -1 }, 1, 1, null, 0, 0,
                new float[] { 1f, 1f, 1f, 1f }, 1f, 1f);
        assertEquals(SQUARE_VERTICES, mesh.vertexCount(), "the fixture is a unit square");
        return new GeometryBundle(mesh, Map.of(
                CornerUvField.SLOT, new CornerUvField(cornerU, cornerV),
                MaterialData.SLOT, material));
    }
}
