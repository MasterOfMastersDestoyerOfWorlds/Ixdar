package ixdar.scenes.anatomy;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.graphics.render.model.AssimpModelRuntime;
import ixdar.graphics.render.model.ModelHandle;
import ixdar.graphics.render.model.ModelRuntime;
import ixdar.platform.Platforms;
import ixdar.platform.file.FileManagement;
import ixdar.scenes.Scene;

import org.joml.Vector3f;

@SceneAnnotation(id = "model-load-canvas")
public class ModelLoadScene extends Scene {

    private static final String MODEL_FILE_NAME = FileManagement.DEFAULT_TEST_MODEL_FILE;
    private ModelRuntime modelRuntime;
    private ModelHandle model;
    private boolean modelLoaded = false;

    @Override
    public void initGL() {
        super.initGL();
        try {
            modelRuntime = new AssimpModelRuntime();
            model = modelRuntime.loadFromAssetRepo(MODEL_FILE_NAME);
            modelRuntime.frameCamera(model, camera);
            String assetPath = FileManagement.resolveAssetPath(MODEL_FILE_NAME);
            Platforms.gl().setWindowTitle("Ixdar : Model Load Test (ASSIMP)");
            Platforms.get().log("[ModelLoadScene] Loaded " + MODEL_FILE_NAME + " via ASSIMP"
                    + " vertices=" + model.vertexCount
                    + " triangles=" + model.triangleCount
                    + " center=" + String.format("(%.3f, %.3f, %.3f)", model.center.x, model.center.y, model.center.z) + " radius=" + model.radius
                    + " path=" + assetPath);
            modelLoaded = true;
        } catch (Exception e) {
            Platforms.get().log("[ModelLoadScene] Failed to load model: " + e.getMessage());
        }
    }

    @Override
    public void drawScene() {
        super.drawScene();
        if (!modelLoaded || model == null || modelRuntime == null) {
            return;
        }
        modelRuntime.render(model, camera);
    }

    @Override
    public void activate(boolean state) {
        super.activate(state);
        if (!state) {
            disposeModel();
        }
    }

    @Override
    public void shutdown() {
        disposeModel();
        super.shutdown();
    }

    private void disposeModel() {
        if (modelRuntime != null && model != null) {
            modelRuntime.dispose(model);
            model = null;
            modelLoaded = false;
        }
    }
}
