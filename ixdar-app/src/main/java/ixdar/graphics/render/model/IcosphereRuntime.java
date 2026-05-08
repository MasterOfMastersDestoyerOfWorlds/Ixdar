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
    public static final float NUM_4_2 = 4.2f;
    public static final float NUM_0 = 0f;
    public static final float NUM_33 = 33f;
    public static final float NUM_1 = 1f;
    public static final float NUM_0_01 = 0.01f;
    public static final float NUM_200 = 200f;
    public static final float NUM_30 = 30f;
    public static final float NUM_0_24 = 0.24f;
    public static final float NUM_0_08 = 0.08f;
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_0_26 = 0.26f;
    public static final float NUM_0_9 = 0.9f;
    public static final float NUM_0_25 = 0.25f;
    public static final float NUM_0_45 = 0.45f;
    public static final float NUM_0_18 = 0.18f;
    public static final int NUM_3 = 3;
    public static final int NUM_8 = 8;
    public static final int NUM_6 = 6;

    private final ShaderProgram meshShader;
    private final ArrayList<FaceHandle> faceHandles;
    private final ArrayList<FaceState> faceStates;
    private final Matrix4f model = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private final Vector4f solidColor = new Vector4f(0.27f, 0.53f, 1.0f, 0.92f);
    private final Vector3f lightDir = new Vector3f(0.2f, -1.0f, 0.4f);
    private final Vector3f emissiveColor = new Vector3f(0.15f, 0.35f, 1.0f);
    private final float radius;

    /**
     * Build a per-face GPU runtime for {@code geometry}: each triangle face
     * gets its own VAO/VBO/EBO so it can be transformed and recolored
     * independently. The mesh shader is initialized once and shared.
     *
     * @param geometry icosphere whose faces drive the per-face draw handles
     * @throws Exception if the mesh shader fails to compile or link
     */
    public IcosphereRuntime(Icosphere geometry) throws Exception {
        this.meshShader = ShaderProgram.ShaderType.Mesh.getShader();
        this.meshShader.init();
        this.faceHandles = new ArrayList<>();
        this.faceStates = new ArrayList<>();
        this.radius = geometry.radius();
        for (Face template : geometry.faces()) {
            faceHandles.add(createFaceHandle(template));
        }
    }

    /**
     * Live per-face state list (position, base rotation, current render
     * transform). Mutating elements directly is the intended way to animate
     * the icosphere.
     *
     * @return mutable list of face states, parallel to the source faces
     */
    public List<FaceState> faceStates() {
        return faceStates;
    }

    /**
     * Position {@code camera} so the entire icosphere fits in view: pulled
     * back along +Z by 4.2x the radius, aimed at the origin, with a 33-degree
     * vertical field of view.
     *
     * @param camera camera to reposition and re-aim
     */
    public void frameCamera(Camera3D camera) {
        float distance = radius * NUM_4_2;
        camera.position.set(NUM_0, NUM_0, distance);
        camera.target.set(NUM_0, NUM_0, NUM_0);
        camera.fov = NUM_33;
        camera.updateViewFirstPerson();
    }

    /**
     * Render every face under the mesh shader with alpha blending enabled.
     * The {@code glowStrength} parameter brightens the solid color and
     * boosts the emissive and rim terms, producing the pulsing-glow effect.
     *
     * @param camera 3D camera supplying the view matrix
     * @param glowStrength glow factor in [0, 1]; 0 = base look, 1 = max glow
     */
    public void render(Camera3D camera, float glowStrength) {
        int width = Platforms.get().getFrameBufferWidth();
        int height = Platforms.get().getFrameBufferHeight();
        float aspect = width <= 0 || height <= 0 ? NUM_1 : ((float) width / (float) height);

        camera.updateViewFirstPerson();
        projection.identity().perspective((float) Math.toRadians((float) camera.fov), aspect, NUM_0_01,
                Math.max(NUM_200, radius * NUM_30));
        solidColor.set(NUM_0_24 + NUM_0_08 * glowStrength, NUM_0_5 + NUM_0_26 * glowStrength, 1.0f, NUM_0_9);
        Platforms.gl().enable(Platforms.gl().BLEND());
        Platforms.gl().blendFunc(Platforms.gl().SRC_ALPHA(), Platforms.gl().ONE_MINUS_SRC_ALPHA());

        meshShader.use();
        meshShader.setMat4("view", camera.view);
        meshShader.setMat4("projection", projection);
        meshShader.setVec3("lightDir", lightDir);
        meshShader.setVec3("emissiveColor", emissiveColor);
        meshShader.setFloat("emissiveStrength", NUM_0_25 + NUM_0_45 * glowStrength);
        meshShader.setFloat("rimStrength", NUM_0_18 + NUM_0_25 * glowStrength);
        meshShader.setBool("useTexture", false);

        for (FaceHandle handle : faceHandles) {
            model.identity().translate(handle.state.renderPos).rotate(handle.state.baseRot);
            meshShader.setMat4("model", model);
            meshShader.setVec4("solidColor", solidColor);

            handle.vao.bind();
            Platforms.gl().bindBuffer(Platforms.gl().ELEMENT_ARRAY_BUFFER(), handle.ebo);
            Platforms.gl().drawElements(Platforms.gl().TRIANGLES(), handle.indexCount, Platforms.gl().UNSIGNED_INT(),
                    0);
        }
    }

    /**
     * Rotate the listed faces' base position and base rotation by
     * {@code angleRadians} around {@code axis}. Affects {@code basePos} and
     * {@code baseRot} only — call {@link #applyExpansion(float, float)} or
     * read {@code state.renderPos} elsewhere to refresh the render transform.
     *
     * @param faceIndices indices into {@link #faceStates()} to rotate
     * @param axis rotation axis in world space (need not be normalized)
     * @param angleRadians rotation amount in radians
     */
    public void applyRotation(List<Integer> faceIndices, Vector3f axis, float angleRadians) {
        Quaternionf rot = new Quaternionf().fromAxisAngleRad(axis.x, axis.y, axis.z, angleRadians);
        for (int index : faceIndices) {
            FaceState state = faceStates.get(index);
            state.basePos.rotate(rot);
            rot.mul(state.baseRot, state.baseRot);
        }
    }

    /**
     * Push every face outward along its base position direction by
     * {@code expandDistance * expand01}, writing the result into
     * {@code state.renderPos}. The base position is left untouched so the
     * expansion can be retracted by another call with {@code expand01 = 0}.
     *
     * @param expand01 expansion factor in [0, 1]
     * @param expandDistance maximum outward offset at {@code expand01 == 1}
     */
    public void applyExpansion(float expand01, float expandDistance) {
        for (FaceState state : faceStates) {
            Vector3f outward = new Vector3f(state.basePos).normalize().mul(expandDistance * expand01);
            state.renderPos.set(state.basePos).add(outward);
        }
    }

    /**
     * Restore every face's base position, base rotation, and render position
     * to the original geometry from construction. Use to undo
     * {@link #applyRotation(List, Vector3f, float)} and
     * {@link #applyExpansion(float, float)}.
     */
    public void resetToIdeal() {
        for (FaceState state : faceStates) {
            state.basePos.set(state.position);
            state.baseRot.set(state.rotation);
            state.renderPos.set(state.basePos);
        }
    }

    /**
     * For each face, snap its base position and rotation to the nearest
     * ideal state from {@code idealStates} (nearest by squared distance of
     * the base position). Used to clean up tiny accumulated rotation drift.
     *
     * @param idealStates pool of canonical face poses to snap toward
     */
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

    /**
     * Release every face's GPU resources (EBO, VBO, VAO) and clear the face
     * handle and state lists. Idempotent — safe to call once after the last
     * render pass.
     */
    public void dispose() {
        for (FaceHandle handle : faceHandles) {
            Platforms.gl().deleteBuffers(handle.ebo);
            handle.vbo.delete();
            handle.vao.delete();
        }
        faceHandles.clear();
        faceStates.clear();
    }

    private FaceHandle createFaceHandle(Face template) {
        VertexArrayObject vao = new VertexArrayObject();
        VertexBufferObject vbo = new VertexBufferObject();
        vao.bind();
        vbo.bind(Platforms.gl().ARRAY_BUFFER());

        Vector3f localNormal = new Vector3f(NUM_0, NUM_0, NUM_1);
        float[] vertices = new float[] {
                template.localV1.x, template.localV1.y, template.localV1.z, localNormal.x, localNormal.y, localNormal.z,
                NUM_0, NUM_0,
                template.localV2.x, template.localV2.y, template.localV2.z, localNormal.x, localNormal.y, localNormal.z,
                NUM_1, NUM_0,
                template.localV3.x, template.localV3.y, template.localV3.z, localNormal.x, localNormal.y, localNormal.z,
                NUM_0_5, NUM_1,
        };
        vbo.uploadData(Platforms.gl().ARRAY_BUFFER(), vertices, Platforms.gl().STATIC_DRAW());

        int[] indices = new int[] { 0, 1, 2 };
        int ebo = Platforms.gl().genBuffers();
        Platforms.gl().bindBuffer(Platforms.gl().ELEMENT_ARRAY_BUFFER(), ebo);
        IntBuffer ib = BufferUtils.createIntBuffer(indices.length);
        ib.put(indices).flip();
        Platforms.gl().bufferData(Platforms.gl().ELEMENT_ARRAY_BUFFER(), ib, Platforms.gl().STATIC_DRAW());

        Platforms.gl().vertexAttribPointer(0, NUM_3, Platforms.gl().FLOAT(), false, NUM_8 * Float.BYTES, 0);
        Platforms.gl().enableVertexAttribArray(0);
        Platforms.gl().vertexAttribPointer(1, NUM_3, Platforms.gl().FLOAT(), false, NUM_8 * Float.BYTES, NUM_3 * Float.BYTES);
        Platforms.gl().enableVertexAttribArray(1);
        Platforms.gl().vertexAttribPointer(2, 2, Platforms.gl().FLOAT(), false, NUM_8 * Float.BYTES, NUM_6 * Float.BYTES);
        Platforms.gl().enableVertexAttribArray(2);

        FaceState state = new FaceState(
                template.index,
                new Vector3f(template.normal),
                new Quaternionf(template.rotation),
                new Vector3f(template.position));
        faceStates.add(state);
        return new FaceHandle(vao, vbo, ebo, indices.length, state);
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
}
