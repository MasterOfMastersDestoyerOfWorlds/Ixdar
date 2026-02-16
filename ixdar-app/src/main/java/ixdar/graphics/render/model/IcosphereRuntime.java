package ixdar.graphics.render.model;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;

import ixdar.geometry.point.IcosphereGeometry;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.shaders.MeshShader;
import ixdar.graphics.render.shaders.VertexArrayObject;
import ixdar.graphics.render.shaders.VertexBufferObject;
import ixdar.platform.Platforms;

public class IcosphereRuntime {

    public static class FaceState {
        public final int index;
        public final Vector3f normal;
        public final Vector3f idealPos;
        public final Quaternionf idealRot;
        public final Vector3f basePos;
        public final Quaternionf baseRot;
        public final Vector3f renderPos;

        public FaceState(int index, Vector3f normal, Vector3f idealPos, Quaternionf idealRot) {
            this.index = index;
            this.normal = normal;
            this.idealPos = idealPos;
            this.idealRot = idealRot;
            this.basePos = new Vector3f(idealPos);
            this.baseRot = new Quaternionf(idealRot);
            this.renderPos = new Vector3f(idealPos);
        }
    }

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

    private final MeshShader meshShader;
    private final ArrayList<FaceHandle> faceHandles;
    private final ArrayList<FaceState> faceStates;
    private final Matrix4f model = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private final Vector4f solidColor = new Vector4f(0.27f, 0.53f, 1.0f, 0.92f);
    private final Vector3f lightDir = new Vector3f(0.2f, -1.0f, 0.4f);
    private final Vector3f emissiveColor = new Vector3f(0.15f, 0.35f, 1.0f);
    private final float radius;

    public IcosphereRuntime(IcosphereGeometry geometry) throws Exception {
        this.meshShader = new MeshShader(new VertexArrayObject(), new VertexBufferObject());
        this.meshShader.init();
        this.faceHandles = new ArrayList<>();
        this.faceStates = new ArrayList<>();
        this.radius = geometry.radius();
        for (IcosphereGeometry.FaceTemplate template : geometry.faces()) {
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
        projection.identity().perspective((float) Math.toRadians((float) camera.fov), aspect, 0.01f, Math.max(200f, radius * 30f));
        solidColor.set(0.24f + 0.08f * glowStrength, 0.5f + 0.26f * glowStrength, 1.0f, 0.9f);
        Platforms.gl().enable(Platforms.gl().BLEND());
        Platforms.gl().blendFunc(Platforms.gl().SRC_ALPHA(), Platforms.gl().ONE_MINUS_SRC_ALPHA());

        meshShader.use();
        meshShader.setMat4("view", camera.view);
        meshShader.setMat4("projection", projection);
        meshShader.setVec3("lightDir", lightDir);
        meshShader.setVec3("emissiveColor", emissiveColor);
        meshShader.setFloat("emissiveStrength", 0.25f + 0.45f * glowStrength);
        meshShader.setFloat("rimStrength", 0.18f + 0.25f * glowStrength);
        meshShader.setBool("useTexture", false);

        for (FaceHandle handle : faceHandles) {
            model.identity().translate(handle.state.renderPos).rotate(handle.state.baseRot);
            meshShader.setMat4("model", model);
            meshShader.setVec4("solidColor", solidColor);

            handle.vao.bind();
            Platforms.gl().bindBuffer(Platforms.gl().ELEMENT_ARRAY_BUFFER(), handle.ebo);
            Platforms.gl().drawElements(Platforms.gl().TRIANGLES(), handle.indexCount, Platforms.gl().UNSIGNED_INT(), 0);
        }
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
            state.basePos.set(state.idealPos);
            state.baseRot.set(state.idealRot);
            state.renderPos.set(state.basePos);
        }
    }

    public void snapToIdeal(List<IcosphereGeometry.IdealFaceState> idealStates) {
        for (FaceState state : faceStates) {
            float best = Float.MAX_VALUE;
            IcosphereGeometry.IdealFaceState winner = null;
            for (IcosphereGeometry.IdealFaceState ideal : idealStates) {
                float dist = state.basePos.distanceSquared(ideal.pos);
                if (dist < best) {
                    best = dist;
                    winner = ideal;
                }
            }
            if (winner != null) {
                state.basePos.set(winner.pos);
                state.baseRot.set(winner.rot);
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

    private FaceHandle createFaceHandle(IcosphereGeometry.FaceTemplate template) {
        VertexArrayObject vao = new VertexArrayObject();
        VertexBufferObject vbo = new VertexBufferObject();
        vao.bind();
        vbo.bind(Platforms.gl().ARRAY_BUFFER());

        Vector3f localNormal = new Vector3f(0f, 0f, 1f);
        float[] vertices = new float[] {
                template.localV1.x, template.localV1.y, template.localV1.z, localNormal.x, localNormal.y, localNormal.z, 0f, 0f,
                template.localV2.x, template.localV2.y, template.localV2.z, localNormal.x, localNormal.y, localNormal.z, 1f, 0f,
                template.localV3.x, template.localV3.y, template.localV3.z, localNormal.x, localNormal.y, localNormal.z, 0.5f, 1f,
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
                new Vector3f(template.idealPos),
                new Quaternionf(template.idealRot));
        faceStates.add(state);
        return new FaceHandle(vao, vbo, ebo, indices.length, state);
    }
}
