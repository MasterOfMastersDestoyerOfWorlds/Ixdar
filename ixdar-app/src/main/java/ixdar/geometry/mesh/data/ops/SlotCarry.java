package ixdar.geometry.mesh.data.ops;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import ixdar.geometry.mesh.data.CornerUvField;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MaterialData;
import ixdar.geometry.mesh.nodes.data.TagGeometryNode;
import ixdar.geometry.mesh.nodes.modifier.SetBoneWeightNode;
import ixdar.platform.Platforms;

/**
 * Carries a bundle's slots across the geometry ops that rebuild a mesh: the whole-bundle material,
 * the per-corner UV field, per-vertex tags and per-vertex bone weights. Each op says how its
 * elements moved and this decides what that means for every slot.
 */
public final class SlotCarry {

    private SlotCarry() {
    }

    /**
     * Concatenate two inputs' corner UVs the way {@link MeshAppend} concatenates their faces:
     * {@code a}'s corners first, then {@code b}'s. A side with no UVs contributes zeros so the
     * field still covers every face. The material comes from {@code a}, or from {@code b} when
     * {@code a} has none.
     *
     * @param a first input bundle
     * @param b second input bundle
     * @param joined output bundle whose mesh already holds both inputs' faces, in that order
     * @return {@code joined} with the texture slots filled in
     */
    public static GeometryBundle join(GeometryBundle a, GeometryBundle b, GeometryBundle joined) {
        GeometryBundle result = withMaterial(joined, MaterialData.of(a) != null ? a : b);
        CornerUvField uvA = uvOf(a);
        CornerUvField uvB = uvOf(b);
        if (uvA == null && uvB == null) {
            return result;
        }
        int cornersA = a.mesh().faceCount() * CornerUvField.CORNERS_PER_FACE;
        int cornersB = b.mesh().faceCount() * CornerUvField.CORNERS_PER_FACE;
        if (joined.mesh().faceCount() * CornerUvField.CORNERS_PER_FACE != cornersA + cornersB) {
            Platforms.log("[slots] join_geometry dropped " + CornerUvField.SLOT
                    + ": its weld changed the face count, which corner UVs cannot follow");
            return result;
        }
        double[] cornerU = new double[cornersA + cornersB];
        double[] cornerV = new double[cornersA + cornersB];
        if (uvA != null) {
            System.arraycopy(uvA.cornerU, 0, cornerU, 0, cornersA);
            System.arraycopy(uvA.cornerV, 0, cornerV, 0, cornersA);
        }
        if (uvB != null) {
            System.arraycopy(uvB.cornerU, 0, cornerU, cornersA, cornersB);
            System.arraycopy(uvB.cornerV, 0, cornerV, cornersA, cornersB);
        }
        return result.withSlot(CornerUvField.SLOT, new CornerUvField(cornerU, cornerV));
    }

    /**
     * Duplicate the corner UVs for a mirrored copy. The mirror appends one reversed-winding face
     * per input face, so the mirrored corners are the input's corners read backwards, and a
     * mirrored corner samples the same texel as the one it came from.
     *
     * @param base input bundle
     * @param mirrored output bundle holding the original faces then their mirrored copies
     * @return {@code mirrored} with the texture slots filled in
     */
    public static GeometryBundle mirror(GeometryBundle base, GeometryBundle mirrored) {
        GeometryBundle result = withMaterial(mirrored, base);
        CornerUvField uv = uvOf(base);
        if (uv == null) {
            return result;
        }
        int corners = uv.cornerU.length;
        if (mirrored.mesh().faceCount() * CornerUvField.CORNERS_PER_FACE != corners * 2) {
            Platforms.log("[slots] mirror_geometry dropped " + CornerUvField.SLOT
                    + ": its seam weld changed the face count, which corner UVs cannot follow");
            return result;
        }
        double[] cornerU = new double[corners * 2];
        double[] cornerV = new double[corners * 2];
        System.arraycopy(uv.cornerU, 0, cornerU, 0, corners);
        System.arraycopy(uv.cornerV, 0, cornerV, 0, corners);
        for (int face = 0; face < uv.faceCount(); face++) {
            for (int corner = 0; corner < CornerUvField.CORNERS_PER_FACE; corner++) {
                int source = uv.offset(face, CornerUvField.CORNERS_PER_FACE - 1 - corner);
                int target = corners + uv.offset(face, corner);
                cornerU[target] = uv.cornerU[source];
                cornerV[target] = uv.cornerV[source];
            }
        }
        return result.withSlot(CornerUvField.SLOT, new CornerUvField(cornerU, cornerV));
    }

    /**
     * Follow a weld: corner UVs re-index onto the faces it kept, tags OR together over every source
     * vertex that landed on the same output vertex, and each bone weight takes its first source's
     * value. The material is whole-bundle data and is already on {@code welded}.
     *
     * @param base input bundle
     * @param welded output bundle carrying the input's slots verbatim
     * @param welder the welder that produced {@code welded}, holding its element maps
     * @return {@code welded} with every per-element slot re-indexed onto the welded mesh
     */
    @SuppressWarnings("unchecked")
    public static GeometryBundle weld(
            GeometryBundle base, GeometryBundle welded, MeshMergeByDistance welder) {
        GeometryBundle result = weldCornerUv(base, welded, welder.sourceFace);

        int[] weldedVertex = welder.weldedVertex;
        int outputVertices = welded.mesh().vertexCount();
        Object tagSlot = base.slots().get(TagGeometryNode.TAGS_SLOT);
        if (tagSlot instanceof Map<?, ?> tags) {
            Map<String, boolean[]> carried = new HashMap<>();
            for (Map.Entry<String, boolean[]> tag : ((Map<String, boolean[]>) tags).entrySet()) {
                boolean[] source = tag.getValue();
                boolean[] target = new boolean[outputVertices];
                for (int vertex = 0; vertex < weldedVertex.length && vertex < source.length; vertex++) {
                    target[weldedVertex[vertex]] |= source[vertex];
                }
                carried.put(tag.getKey(), target);
            }
            result = result.withSlot(TagGeometryNode.TAGS_SLOT, carried);
        }

        boolean[] written = new boolean[outputVertices];
        for (Map.Entry<String, Object> slot : base.slots().entrySet()) {
            if (!slot.getKey().startsWith(SetBoneWeightNode.BONE_WEIGHT_PREFIX)
                    || !(slot.getValue() instanceof float[] source)) {
                continue;
            }
            float[] target = new float[outputVertices];
            Arrays.fill(written, false);
            for (int vertex = 0; vertex < weldedVertex.length && vertex < source.length; vertex++) {
                int output = weldedVertex[vertex];
                if (!written[output]) {
                    target[output] = source[vertex];
                    written[output] = true;
                }
            }
            result = result.withSlot(slot.getKey(), target);
        }
        return result;
    }

    /**
     * Re-index only the corner UVs onto the faces a weld kept. The nodes that weld as a step of a
     * bigger rebuild call this directly, so the weld does not also rewrite the tags and bone
     * weights they are assembling themselves.
     *
     * @param base bundle whose UV field covers the pre-weld faces
     * @param welded output bundle
     * @param sourceFace input face each output face came from, as
     *     {@link MeshMergeByDistance#sourceFace} records it
     * @return {@code welded} with its UV slot re-indexed, or unchanged when {@code base} had none
     */
    public static GeometryBundle weldCornerUv(
            GeometryBundle base, GeometryBundle welded, int[] sourceFace) {
        CornerUvField uv = uvOf(base);
        if (uv == null) {
            return welded;
        }
        double[] cornerU = new double[sourceFace.length * CornerUvField.CORNERS_PER_FACE];
        double[] cornerV = new double[cornerU.length];
        for (int face = 0; face < sourceFace.length; face++) {
            for (int corner = 0; corner < CornerUvField.CORNERS_PER_FACE; corner++) {
                int source = uv.offset(sourceFace[face], corner);
                int target = face * CornerUvField.CORNERS_PER_FACE + corner;
                cornerU[target] = uv.cornerU[source];
                cornerV[target] = uv.cornerV[source];
            }
        }
        return welded.withSlot(CornerUvField.SLOT, new CornerUvField(cornerU, cornerV));
    }

    /**
     * The per-corner UV field a bundle carries, when it has one of the expected size.
     *
     * @param bundle bundle to read, may be null
     * @return the field in {@link CornerUvField#SLOT}, or null
     */
    private static CornerUvField uvOf(GeometryBundle bundle) {
        if (bundle == null) {
            return null;
        }
        return bundle.slots().get(CornerUvField.SLOT) instanceof CornerUvField field ? field : null;
    }

    /**
     * Copy {@code source}'s material onto {@code out} when it has one.
     *
     * @param out bundle to receive the material
     * @param source bundle to read the material from
     * @return {@code out}, or a copy carrying the material
     */
    private static GeometryBundle withMaterial(GeometryBundle out, GeometryBundle source) {
        MaterialData material = MaterialData.of(source);
        return material == null ? out : out.withSlot(MaterialData.SLOT, material);
    }
}
