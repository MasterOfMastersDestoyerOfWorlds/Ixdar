package ixdar.geometry.mesh.data.load;

import java.io.IOException;

import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MaterialData;
import ixdar.platform.Platforms;
import ixdar.platform.gl.DecodedImage;

/**
 * Turns a parsed {@link GltfModel}'s material arrays and image bytes into the one
 * {@link MaterialData} a bundle carries, decoding the images through the platform. Plain Java over
 * the parser's output, so the browser build links it and simply gets no pixels back.
 */
public final class GltfMaterialReader {

    private GltfMaterialReader() {
    }

    /**
     * Read a glTF file whole and hand back its bundle with the material slot attached.
     *
     * @param path filesystem path of a {@code .glb} or {@code .gltf} file
     * @throws IOException if the file cannot be read or holds no triangles
     * @return the parser's bundle, carrying {@link MaterialData#SLOT} when the file has a material
     *     whose images decoded
     */
    public static GeometryBundle loadBundle(String path) throws IOException {
        GltfModel model = GltfMeshParser.read(path);
        MaterialData material = read(model, path);
        return material == null ? model.bundle : model.bundle.withSlot(MaterialData.SLOT, material);
    }

    /**
     * Build the bundle's material from the first material that declares a base-colour texture,
     * falling back to material 0. Null when the file declares no materials, or when neither of the
     * chosen material's images decoded.
     *
     * @param model parsed model, read with images
     * @param source file name, for log messages
     * @return the material, or null when there is nothing worth carrying
     */
    public static MaterialData read(GltfModel model, String source) {
        int count = model.materialCount();
        if (count == 0) {
            return null;
        }
        int chosen = 0;
        int textured = 0;
        for (int material = 0; material < count; material++) {
            if (model.baseColorTexture[material] != GltfModel.NO_TEXTURE) {
                if (textured == 0) {
                    chosen = material;
                }
                textured++;
            }
        }
        if (textured > 1) {
            Platforms.log("[gltf] " + source + " has " + textured
                    + " textured materials; carrying the first");
        }
        DecodedImage baseColor = decode(model, model.baseColorTexture[chosen], source);
        DecodedImage metallicRoughness =
                decode(model, model.metallicRoughnessTexture[chosen], source);
        if (baseColor == null && metallicRoughness == null) {
            return null;
        }
        float[] factor = new float[MaterialData.FACTOR_COMPONENTS];
        System.arraycopy(model.baseColorFactor, chosen * GltfModel.COLOR_COMPONENTS, factor, 0,
                MaterialData.FACTOR_COMPONENTS);
        return new MaterialData(
                baseColor == null ? null : baseColor.rgba,
                baseColor == null ? 0 : baseColor.width,
                baseColor == null ? 0 : baseColor.height,
                metallicRoughness == null ? null : metallicRoughness.rgba,
                metallicRoughness == null ? 0 : metallicRoughness.width,
                metallicRoughness == null ? 0 : metallicRoughness.height,
                factor,
                (float) model.metallicFactor[chosen],
                (float) model.roughnessFactor[chosen]);
    }

    /**
     * Resolve a texture index to its image's bytes and decode them through the active platform.
     *
     * @param model parsed model, read with images
     * @param texture index into the file's textures, or {@link GltfModel#NO_TEXTURE}
     * @param source file name, for log messages
     * @return decoded pixels, or null when the channel is unset, empty, or undecodable here
     */
    private static DecodedImage decode(GltfModel model, int texture, String source) {
        if (texture == GltfModel.NO_TEXTURE || texture >= model.textureImage.length) {
            return null;
        }
        int image = model.textureImage[texture];
        if (image < 0 || image >= model.imageCount()) {
            return null;
        }
        byte[] encoded = model.imageBytes[image];
        if (encoded == null || encoded.length == 0) {
            return null;
        }
        if (!Platforms.isInitialized()) {
            Platforms.log("[gltf] no platform registered; skipping the material of " + source);
            return null;
        }
        DecodedImage decoded = Platforms.get().decodeImage(encoded);
        if (decoded == null) {
            Platforms.log("[gltf] could not decode image " + model.imageName[image] + " of " + source);
        }
        return decoded;
    }
}
