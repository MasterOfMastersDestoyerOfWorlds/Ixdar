package ixdar.scenes.anatomy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIVector3D;
import org.lwjgl.assimp.Assimp;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.sdf.SDFCircleSimple;
import ixdar.platform.Platforms;
import ixdar.platform.file.FileManagement;
import ixdar.scenes.Scene;

@SceneAnnotation(id = "model-load-canvas")
public class ModelLoadScene extends Scene {

    private static final String MODEL_FILE_NAME = FileManagement.DEFAULT_TEST_MODEL_FILE;
    private static final int MAX_DRAW_POINTS = 12000;
    private static final float POINT_RADIUS = 1.3f;
    private final List<Vector3f> vertices = new ArrayList<>();
    private final Vector3f modelCenter = new Vector3f();
    private float modelRadius = 1.0f;
    private boolean modelLoaded = false;
    private boolean visibilityLogged = false;
    private SDFCircleSimple pointRenderer;

    @Override
    public void initGL() {
        super.initGL();
        pointRenderer = new SDFCircleSimple();
        try {
            String assetPath = FileManagement.resolveAssetPath(MODEL_FILE_NAME);
            loadWithAssimp(assetPath);
            frameCamera();
            Platforms.gl().setWindowTitle("Ixdar : Model Load Test (ASSIMP)");
            Platforms.get().log("[ModelLoadScene] Loaded " + MODEL_FILE_NAME + " via ASSIMP"
                    + " vertices=" + vertices.size() + " center=" + vec3(modelCenter) + " radius=" + modelRadius
                    + " path=" + assetPath);
            modelLoaded = true;
        } catch (Exception e) {
            Platforms.get().log("[ModelLoadScene] Failed to load model: " + e.getMessage());
        }
    }

    @Override
    public void drawScene() {
        super.drawScene();
        if (!modelLoaded || vertices.isEmpty()) {
            return;
        }

        int width = Platforms.get().getFrameBufferWidth();
        int height = Platforms.get().getFrameBufferHeight();
        float aspect = width <= 0 || height <= 0 ? 1f : ((float) width / (float) height);

        camera.updateViewFirstPerson();
        Matrix4f projection = new Matrix4f()
                .perspective((float) Math.toRadians((float) camera.fov), aspect, 0.01f, Math.max(1000f, modelRadius * 20f));
        Matrix4f mvp = new Matrix4f(projection).mul(camera.view);

        int step = Math.max(1, vertices.size() / MAX_DRAW_POINTS);
        int visiblePoints = 0;
        Vector4f clip = new Vector4f();
        for (int i = 0; i < vertices.size(); i += step) {
            Vector3f v = vertices.get(i);
            clip.set(v.x, v.y, v.z, 1f).mul(mvp);
            if (clip.w <= 0.0001f) {
                continue;
            }
            float invW = 1f / clip.w;
            float ndcX = clip.x * invW;
            float ndcY = clip.y * invW;
            float ndcZ = clip.z * invW;
            if (ndcX < -1f || ndcX > 1f || ndcY < -1f || ndcY > 1f || ndcZ < -1f || ndcZ > 1f) {
                continue;
            }
            float sx = (ndcX * 0.5f + 0.5f) * width;
            float sy = (ndcY * 0.5f + 0.5f) * height;
            pointRenderer.draw(new Vector2f(sx, sy), POINT_RADIUS, Color.CYAN, camera2D);
            visiblePoints++;
        }

        if (!visibilityLogged) {
            visibilityLogged = true;
            Platforms.get().log("[ModelLoadScene] cameraPos=" + vec3(camera.position)
                    + " target=" + vec3(camera.target)
                    + " drawnPoints=" + visiblePoints
                    + " totalVertices=" + vertices.size()
                    + " sampledStep=" + step);
        }
    }

    private void loadWithAssimp(String absoluteModelPath) throws IOException {
        int flags = Assimp.aiProcess_Triangulate
                | Assimp.aiProcess_JoinIdenticalVertices
                | Assimp.aiProcess_GenSmoothNormals;
        AIScene scene = Assimp.aiImportFile(absoluteModelPath, flags);
        if (scene == null || scene.mMeshes() == null || scene.mNumMeshes() == 0) {
            throw new IOException("Assimp failed to import model: " + Assimp.aiGetErrorString());
        }
        try {
            Vector3f min = new Vector3f(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
            Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);
            for (int meshIndex = 0; meshIndex < scene.mNumMeshes(); meshIndex++) {
                AIMesh mesh = AIMesh.create(scene.mMeshes().get(meshIndex));
                AIVector3D.Buffer verts = mesh.mVertices();
                if (verts == null) {
                    continue;
                }
                for (int i = 0; i < verts.remaining(); i++) {
                    AIVector3D p = verts.get(i);
                    Vector3f v = new Vector3f(p.x(), p.y(), p.z());
                    vertices.add(v);
                    min.min(v);
                    max.max(v);
                }
            }
            if (vertices.isEmpty()) {
                throw new IOException("Imported model has no vertices: " + Path.of(absoluteModelPath).getFileName());
            }
            modelCenter.set(min).add(max).mul(0.5f);
            modelRadius = 0f;
            for (Vector3f v : vertices) {
                modelRadius = Math.max(modelRadius, new Vector3f(v).sub(modelCenter).length());
            }
            if (modelRadius < 0.001f) {
                modelRadius = 1f;
            }
        } finally {
            Assimp.aiReleaseImport(scene);
        }
    }

    private void frameCamera() {
        float distance = modelRadius * 2.8f;
        camera.position.set(modelCenter.x, modelCenter.y, modelCenter.z + distance);
        camera.target.set(modelCenter);
        camera.updateViewFirstPerson();
    }

    private String vec3(Vector3f v) {
        return String.format("(%.3f, %.3f, %.3f)", v.x, v.y, v.z);
    }
}
