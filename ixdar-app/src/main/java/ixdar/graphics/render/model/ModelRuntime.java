package ixdar.graphics.render.model;

import ixdar.graphics.cameras.Camera3D;

public interface ModelRuntime {
    /**
     * TODO: document {@code loadFromAssetRepo}.
     *
     * @param modelFileName TODO: describe
     * @throws Exception TODO: describe
     * @return TODO: describe
     */
    ModelHandle loadFromAssetRepo(String modelFileName) throws Exception;

    /**
     * TODO: document {@code frameCamera}.
     *
     * @param handle TODO: describe
     * @param camera TODO: describe
     */
    void frameCamera(ModelHandle handle, Camera3D camera);

    /**
     * TODO: document {@code render}.
     *
     * @param handle TODO: describe
     * @param camera TODO: describe
     */
    void render(ModelHandle handle, Camera3D camera);

    /**
     * TODO: document {@code dispose}.
     *
     * @param handle TODO: describe
     */
    void dispose(ModelHandle handle);
}
