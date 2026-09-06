package ixdar.geometry.mesh.data.load;

import ixdar.geometry.mesh.data.CornerUvField;
import ixdar.geometry.mesh.data.GeometryBundle;

/**
 * Everything {@link GltfMeshParser} read from one glTF file, held as parallel arrays indexed by
 * image, material, texture and primitive: the flattened geometry plus the material data a caller
 * needs to build its own textures. {@link GltfMeshParser} is the only producer and fills the arrays
 * directly.
 */
public final class GltfModel {

    /** Index value meaning a material leaves that texture channel unset. */
    public static final int NO_TEXTURE = -1;

    /** Components of each material's base colour factor: red, green, blue, alpha. */
    public static final int COLOR_COMPONENTS = 4;

    /** Components of each material's emissive factor: red, green, blue. */
    public static final int EMISSIVE_COMPONENTS = 3;

    /** Welded triangle mesh, with {@link CornerUvField#SLOT} when the file carried UVs. */
    public GeometryBundle bundle;

    /** Encoded image file bytes per image, empty when the parse was asked to skip images. */
    public byte[][] imageBytes = new byte[0][];

    /** MIME type per image, e.g. {@code image/png}; empty string when the file declared none. */
    public String[] imageMimeType = new String[0];

    /** Name per image, empty string when unnamed. */
    public String[] imageName = new String[0];

    /** First triangle of each primitive in the flattened mesh. */
    public int[] primitiveFaceStart = new int[0];

    /** Triangles each primitive contributed. */
    public int[] primitiveFaceCount = new int[0];

    /** Material index per primitive, or {@link #NO_TEXTURE} when the primitive names none. */
    public int[] primitiveMaterial = new int[0];

    /** Name per material, empty string when unnamed. */
    public String[] materialName = new String[0];

    /** RGBA base colour factor, {@link #COLOR_COMPONENTS} entries per material. */
    public float[] baseColorFactor = new float[0];

    /** RGB emissive factor, {@link #EMISSIVE_COMPONENTS} entries per material. */
    public float[] emissiveFactor = new float[0];

    /** {@code pbrMetallicRoughness.metallicFactor} per material. */
    public double[] metallicFactor = new double[0];

    /** {@code pbrMetallicRoughness.roughnessFactor} per material. */
    public double[] roughnessFactor = new double[0];

    /** Texture index of each material's base colour map, or {@link #NO_TEXTURE}. */
    public int[] baseColorTexture = new int[0];

    /** Texture index of each material's metallic-roughness map, or {@link #NO_TEXTURE}. */
    public int[] metallicRoughnessTexture = new int[0];

    /** Texture index of each material's normal map, or {@link #NO_TEXTURE}. */
    public int[] normalTexture = new int[0];

    /** Texture index of each material's occlusion map, or {@link #NO_TEXTURE}. */
    public int[] occlusionTexture = new int[0];

    /** Texture index of each material's emissive map, or {@link #NO_TEXTURE}. */
    public int[] emissiveTexture = new int[0];

    /** Image index each texture samples, or {@code -1} where the texture names no source. */
    public int[] textureImage = new int[0];

    /** Vertices the file's accessors declared, before duplicate positions were welded. */
    public int sourceVertexCount;

    /** Vertices the mesh has after the weld; equals {@code bundle.mesh().vertexCount()}. */
    public int weldedVertexCount;

    /**
     * Welded groups whose source vertices disagreed on a normal by more than
     * {@link GltfMeshParser#NORMAL_CONFLICT_EPSILON}. Non-zero means an exporter that wants the
     * file's original shading has to split on normals as well as on UVs.
     */
    public int normalConflicts;

    /**
     * Images the file carried.
     *
     * @return image count
     */
    public int imageCount() {
        return imageBytes.length;
    }

    /**
     * Materials the file declared.
     *
     * @return material count
     */
    public int materialCount() {
        return materialName.length;
    }

    /**
     * Primitives that contributed triangles.
     *
     * @return primitive count
     */
    public int primitiveCount() {
        return primitiveFaceStart.length;
    }
}
