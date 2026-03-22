package ixdar.annotations.scene;

public abstract class SceneDrawable {
    public void paintGL() {
        throw new UnsupportedOperationException("paintGL not implemented");
    }

    public void initGL() {
        throw new UnsupportedOperationException("initGL not implemented");
    }

    public void shutdown() {
        // Optional lifecycle hook for releasing GPU/scene resources.
    }
}
