package ixdar.graphics.render.model;

import ixdar.graphics.cameras.Camera3D;

public interface ModelRuntime {
    ModelHandle loadFromAssetRepo(String modelFileName) throws Exception;

    void frameCamera(ModelHandle handle, Camera3D camera);

    void render(ModelHandle handle, Camera3D camera);

    void dispose(ModelHandle handle);
}
