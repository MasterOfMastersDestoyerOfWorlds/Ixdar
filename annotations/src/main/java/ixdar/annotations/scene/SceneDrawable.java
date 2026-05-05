package ixdar.annotations.scene;

public abstract class SceneDrawable {
    /**
     * Render this drawable for the current frame on the GL thread.
     *
     * @throws UnsupportedOperationException if the subclass has not provided a render implementation
     */
    public void paintGL() {
        throw new UnsupportedOperationException("paintGL not implemented");
    }

    /**
     * One-time GL initialization (allocate buffers, compile shaders, etc.); called on the GL thread before the first {@link #paintGL()}.
     *
     * @throws UnsupportedOperationException if the subclass has not provided an init implementation
     */
    public void initGL() {
        throw new UnsupportedOperationException("initGL not implemented");
    }

    /**
     * Optional lifecycle hook for releasing GPU/scene resources when the drawable is removed.
     */
    public void shutdown() {
        // Optional lifecycle hook for releasing GPU/scene resources.
    }
}
