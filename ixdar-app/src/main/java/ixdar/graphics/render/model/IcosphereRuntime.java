package ixdar.graphics.render.model;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;

import ixdar.geometry.mesh.data.Face;
import ixdar.geometry.mesh.data.FaceState;
import ixdar.geometry.mesh.standalone.Icosphere;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.shaders.MeshShader;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.graphics.render.shaders.VertexArrayObject;
import ixdar.graphics.render.shaders.VertexBufferObject;
import ixdar.platform.Platforms;

public class IcosphereRuntime {

    private static class FaceHandle {
        final VertexArrayObject vao;
        final VertexBufferObject vbo;
        final int ebo;
        final int indexCount;
        final FaceState state;

        FaceHandle(VertexArrayObject vao, VertexBufferObject vbo, int ebo, int indexCount, FaceState state) {
            this.vao = vao;
            this.vbo = vbo;
            this.ebo = ebo;
            this.indexCount = indexCount;
            this.state = state;
        }
    }

    private final ShaderProgram meshShader;
    private final ShaderProgram meshUnlitShader;
    private final ArrayList<FaceHandle> faceHandles;
    private final ArrayList<FaceState> faceStates;
    private final Matrix4f model = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private final Vector4f solidColor = new Vector4f(0.27f, 0.53f, 1.0f, 0.92f);
    private final Vector3f lightDir = new Vector3f(0.2f, -1.0f, 0.4f);
    private final Vector3f emissiveColor = new Vector3f(0.15f, 0.35f, 1.0f);
    private final Vector3f wireframeColor = new Vector3f(0.8f, 0.9f, 1.0f);
    private final float radius;
    private boolean wireframe = false;
    private boolean additiveBlending = true;
    private final Vector3f center = new Vector3f(0f, 0f, 0f);

    public IcosphereRuntime(Icosphere geometry) throws Exception {
        this.meshShader = ShaderProgram.ShaderType.Mesh.getShader();
        this.meshUnlitShader = ShaderProgram.ShaderType.MeshUnlit.getShader();
        this.meshShader.init();
        this.meshUnlitShader.init();
        this.faceHandles = new ArrayList<>();
        this.faceStates = new ArrayList<>();
        this.radius = geometry.radius();
        for (Face template : geometry.faces()) {
            faceHandles.add(createFaceHandle(template));
        }
    }

    public List<FaceState> faceStates() {
        return faceStates;
    }

    public void frameCamera(Camera3D camera) {
        float distance = radius * 4.2f;
        camera.position.set(0f, 0f, distance);
        camera.target.set(0f, 0f, 0f);
        camera.fov = 33f;
        camera.updateViewFirstPerson();
    }

    public void render(Camera3D camera, float glowStrength) {
        int width = Platforms.get().getFrameBufferWidth();
        int height = Platforms.get().getFrameBufferHeight();
        float aspect = width <= 0 || height <= 0 ? 1f : ((float) width / (float) height);

        camera.updateViewFirstPerson();
        projection.identity().perspective((float) Math.toRadians((float) camera.fov), aspect, 0.01f,
                Math.max(200f, radius * 30f));
        solidColor.set(0.24f + 0.08f * glowStrength, 0.5f + 0.26f * glowStrength, 1.0f, 0.9f);
        
        // Render main faces with emissive glow
        meshShader.use();
        meshShader.setMat4("model", model.identity());
        meshShader.setMat4("view", camera.view);
        meshShader.setMat4("projection", projection);
        meshShader.setVec3("lightDir", lightDir);
        meshShader.setVec3("emissiveColor", emissiveColor);
        meshShader.setFloat("emissiveStrength", 0.25f + 0.45f * glowStrength);
        meshShader.setFloat("rimStrength", 0.18f + 0.25f * glowStrength);
        meshShader.setBool("useTexture", false);
        meshShader.setBool("additiveBlending", additiveBlending);

        // Enable blending for additive emissive effect
        Platforms.gl().enable(Platforms.gl().BLEND());
        Platforms.gl().blendFunc(Platforms.gl().SRC_ALPHA(), Platforms.gl().ONE_MINUS_SRC_ALPHA());

        for (FaceHandle handle : faceHandles) {
            model.identity().translate(handle.state.renderPos).rotate(handle.state.baseRot);
            meshShader.setMat4("model", model);
            meshShader.setVec4("solidColor", solidColor);

            handle.vao.bind();
            Platforms.gl().bindBuffer(Platforms.gl().ELEMENT_ARRAY_BUFFER(), handle.ebo);
            Platforms.gl().drawElements(Platforms.gl().TRIANGLES(), handle.indexCount, Platforms.gl().UNSIGNED_INT(),
                    0);
        }

        // Render wireframe overlay
        if (wireframe) {
            renderWireframe(camera, glowStrength);
        }
        
        // Disable blending after rendering
        Platforms.gl().disable(Platforms.gl().BLEND());
    }

    private void renderWireframe(Camera3D camera, float glowStrength) {
        if (meshUnlitShader.ID < 0) {
            return;
        }
        
        meshUnlitShader.use();
        meshUnlitShader.setMat4("model", model.identity());
        meshUnlitShader.setMat4("view", camera.view);
        meshUnlitShader.setMat4("projection", projection);
        
        // Brighter wireframe color for visibility
        wireframeColor.set(0.9f + 0.1f * glowStrength, 0.95f + 0.05f * glowStrength, 1.0f);
        meshUnlitShader.setVec4("solidColor", new Vector4f(wireframeColor, 1.0f));
        
        Platforms.gl().disable(Platforms.gl().DEPTH_TEST());
        Platforms.gl().lineWidth(1.5f);
        
        for (FaceHandle handle : faceHandles) {
            model.identity().translate(handle.state.renderPos).rotate(handle.state.baseRot);
            meshUnlitShader.setMat4("model", model);
            
            handle.vao.bind();
            Platforms.gl().bindBuffer(Platforms.gl().ELEMENT_ARRAY_BUFFER(), handle.ebo);
            Platforms.gl().drawElements(Platforms.gl().TRIANGLES(), handle.indexCount, Platforms.gl().UNSIGNED_INT(),
                    0);
        }
        
        Platforms.gl().enable(Platforms.gl().DEPTH_TEST());
    }

    public void applyRotation(List<Integer> faceIndices, Vector3f axis, float angleRadians) {
        Quaternionf rot = new Quaternionf().fromAxisAngleRad(axis.x, axis.y, axis.z, angleRadians);
        for (int index : faceIndices) {
            FaceState state = faceStates.get(index);
            state.basePos.rotate(rot);
            rot.mul(state.baseRot, state.baseRot);
        }
    }

    public void applyExpansion(float expand01, float expandDistance) {
        for (FaceState state : faceStates) {
            Vector3f outward = new Vector3f(state.basePos).normalize().mul(expandDistance * expand01);
            state.renderPos.set(state.basePos).add(outward);
        }
    }

    public void resetToIdeal() {
        for (FaceState state : faceStates) {
            state.basePos.set(state.position);
            state.baseRot.set(state.rotation);
            state.renderPos.set(state.basePos);
        }
    }

    public void snapToIdeal(List<FaceState> idealStates) {
        for (FaceState state : faceStates) {
            float best = Float.MAX_VALUE;
            FaceState winner = null;
            for (FaceState ideal : idealStates) {
                float dist = state.basePos.distanceSquared(ideal.position);
                if (dist < best) {
                    best = dist;
                    winner = ideal;
                }
            }
            if (winner != null) {
                state.basePos.set(winner.position);
                state.baseRot.set(winner.rotation);
            }
        }
    }

    public void dispose() {
        for (FaceHandle handle : faceHandles) {
            Platforms.gl().deleteBuffers(handle.ebo);
            handle.vbo.delete();
            handle.vao.delete();
        }
        faceHandles.clear();
        faceStates.clear();
    }

    public boolean isWireframe() {
        return wireframe;
    }

    public void setWireframe(boolean wireframe) {
        this.wireframe = wireframe;
    }

    public boolean isAdditiveBlending() {
        return additiveBlending;
    }

    public void setAdditiveBlending(boolean additiveBlending) {
        this.additiveBlending = additiveBlending;
    }

    public Vector3f getCenter() {
        return new Vector3f(center);
    }

    public float getRadius() {
        return radius;
    }

    private FaceHandle createFaceHandle(Face template) {
        VertexArrayObject vao = new VertexArrayObject();
        VertexBufferObject vbo = new VertexBufferObject();
        vao.bind();
        vbo.bind(Platforms.gl().ARRAY_BUFFER());

        Vector3f localNormal = new Vector3f(0f, 0f, 1f);
        float[] vertices = new float[] {
                template.localV1.x, template.localV1.y, template.localV1.z, localNormal.x, localNormal.y, localNormal.z,
                0f, 0f,
                template.localV2.x, template.localV2.y, template.localV2.z, localNormal.x, localNormal.y, localNormal.z,
                1f, 0f,
                template.localV3.x, template.localV3.y, template.localV3.z, localNormal.x, localNormal.y, localNormal.z,
                0.5f, 1f,
        };
        vbo.uploadData(Platforms.gl().ARRAY_BUFFER(), vertices, Platforms.gl().STATIC_DRAW());

        int[] indices = new int[] { 0, 1, 2 };
        int ebo = Platforms.gl().genBuffers();
        Platforms.gl().bindBuffer(Platforms.gl().ELEMENT_ARRAY_BUFFER(), ebo);
        IntBuffer ib = BufferUtils.createIntBuffer(indices.length);
        ib.put(indices).flip();
        Platforms.gl().bufferData(Platforms.gl().ELEMENT_ARRAY_BUFFER(), ib, Platforms.gl().STATIC_DRAW());

        Platforms.gl().vertexAttribPointer(0, 3, Platforms.gl().FLOAT(), false, 8 * Float.BYTES, 0);
        Platforms.gl().enableVertexAttribArray(0);
        Platforms.gl().vertexAttribPointer(1, 3, Platforms.gl().FLOAT(), false, 8 * Float.BYTES, 3 * Float.BYTES);
        Platforms.gl().enableVertexAttribArray(1);
        Platforms.gl().vertexAttribPointer(2, 2, Platforms.gl().FLOAT(), false, 8 * Float.BYTES, 6 * Float.BYTES);
        Platforms.gl().enableVertexAttribArray(2);

        FaceState state = new FaceState(
                template.index,
                new Vector3f(template.normal),
                new Quaternionf(template.rotation),
                new Vector3f(template.position));
        faceStates.add(state);
        return new FaceHandle(vao, vbo, ebo, indices.length, state);
    }
}
