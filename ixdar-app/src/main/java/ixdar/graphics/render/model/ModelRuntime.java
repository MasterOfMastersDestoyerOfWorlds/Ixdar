package ixdar.graphics.render.model;

import ixdar.graphics.cameras.Camera3D;

public interface ModelRuntime {
    /**
     * Load and upload an asset-repo model, returning a handle that owns
     * the GL buffers and any generated textures.
     *
     * @param modelFileName asset-relative model path.
     * @throws Exception if the file cannot be resolved, parsed, or uploaded.
     * @return new handle wrapping the uploaded resources.
     */
    ModelHandle loadFromAssetRepo(String modelFileName) throws Exception;

    /**
     * Position {@code camera} so the model fits comfortably in view —
     * typically by targeting the model center and pulling back along +Z
     * by a multiple of the bounding radius.
     *
     * @param handle model whose bounds drive the framing.
     * @param camera camera mutated in-place.
     */
    void frameCamera(ModelHandle handle, Camera3D camera);

    /**
     * Issue the GL draw calls that render {@code handle} from the perspective
     * of {@code camera}. Implementations bind their own shader, upload
     * model/view/projection uniforms, and may switch state such as textures
     * and lighting.
     *
     * @param handle uploaded model resources to draw.
     * @param camera supplies view matrix, fov, and orientation.
     */
    void render(ModelHandle handle, Camera3D camera);

    /**
     * Release the GL resources owned by {@code handle}. Implementations
     * should be safe to call with {@code null}.
     *
     * @param handle model whose resources should be freed.
     */
    void dispose(ModelHandle handle);
}
