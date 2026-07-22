package ixdar.graphics.render.model;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.graphics.render.shaders.VertexArrayObject;
import ixdar.graphics.render.shaders.VertexBufferObject;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;

public class HalfEdgeMeshRuntime {
    public static final String MODEL = "model";
    public static final String VIEW = "view";
    public static final String PROJECTION = "projection";
    public static final String SOLIDCOLOR = "solidColor";
    public static final String DEPTHBIAS = "depthBias";
    public static final String PATCH = "patch_";
    public static final float NUM_1_5 = 1.5f;
    public static final float NUM_2_5 = 2.5f;
    public static final float NUM_45 = 45f;
    public static final float NUM_1 = 1f;
    public static final float NUM_1000 = 1000f;
    public static final float NUM_20 = 20f;
    public static final double NUM_2_0 = 2.0;
    public static final float NUM_0_01 = 0.01f;
    public static final float NUM_0 = 0f;
    public static final float NUM_0_001 = 0.001f;
    public static final float NUM_0_08 = 0.08f;
    public static final float NUM_0_16 = 0.16f;
    public static final int NUM_32 = 32;
    public static final long NUM_0xffffffff = 0xffffffffL;
    public static final int NUM_16 = 16;
    public static final int NUM_0xf = 0xff;
    public static final float NUM_255 = 255f;
    public static final int NUM_8 = 8;
    public static final int NUM_3 = 3;
    public static final float NUM_0_0003 = 0.0003f;
    public static final float NUM_2_0_2 = 2.0f;
    public static final double NUM_0_6180339887498949 = 0.6180339887498949;
    public static final int NUM_0x7FFFFFF = 0x7FFFFFFF;
    public static final int NUM_10000 = 10000;
    public static final float NUM_10000_2 = 10000f;
    public static final float NUM_0_65 = 0.65f;
    public static final float NUM_0_55 = 0.55f;
    public static final int NUM_31 = 31;
    public static final float NUM_2 = 2f;
    public static final float NUM_6 = 6f;
    public static final float NUM_3_2 = 3f;
    public static final float NUM_4 = 4f;
    public static final float NUM_5 = 5f;
    public static final float NUM_0_5 = 0.5f;
    public static final int NUM_6_2 = 6;

    private final ShaderProgram meshShader;
    private final ShaderProgram meshUnlitShader;
    private final ShaderProgram meshScalarShader;
    private final Matrix4f modelMatrix = new Matrix4f();
    private final Matrix4f projectionMatrix = new Matrix4f();
    private final Vector4f solidColor = Color.BLUE_GRAY.toVector4f();
    private final Vector4f edgeColor = Color.RED.toVector4f();
    private final Vector4f edgeFaintColor = Color.RED_FAINT.toVector4f();
    private final Vector3f lightDir = new Vector3f(0.4f, -1.0f, 0.25f);
    private final Vector3f emissiveColor = Color.BLUE_WHITE.toVector3f();
    private final Vector3f minBounds = new Vector3f();
    private final Vector3f maxBounds = new Vector3f();
    private final Vector3f center = new Vector3f();

    private IntBuffer indexBuffer;
    private HalfEdgeCompiledMeshData compiledMesh;
    private final VertexArrayObject meshVao;
    private final VertexBufferObject meshVbo;
    private int ebo;
    private int edgeEbo;
    private int edgeCount;
    // Feature-edge overlay buffer: all categories concatenated, ranges recorded.
    private int featureEdgeEbo;
    private List<FeatureEdgeRange> featureEdgeRanges = List.of();
    // PATCH-15: per-vertex scalar for heat-map diagnostics. Populated by
    // setPerVertexScalar; bound at attribute location 3 only when SCALAR
    // shader mode is active.
    private int scalarVbo;
    private boolean scalarUploaded = false;
    private float scalarMin = 0f;
    private float scalarMax = 1f;
    private boolean wireframe = false;
    private volatile boolean orthographic = false;
    private boolean xray = true;

    // Tagged rendering (VIEW-5). When tagRanges is empty, the mesh draws as
    // one draw call with the {@code solidColor} uniform — original behaviour.
    private final Map<String, Vector4f> tagColorOverrides = new HashMap<>();
    private List<TagRange> tagRanges = List.of();
    private ShaderMode shaderMode = ShaderMode.LAMBERT;

    /**
     * Build the runtime: allocate the three mesh shader programs (lit,
     * unlit, scalar), the shared VAO/VBO, and the EBO names for triangles,
     * wireframe edges, feature-edge overlays, and the per-vertex scalar
     * attribute. No mesh is uploaded yet — call {@link #upload(MeshTopology)}.
     *
     * @throws Exception if any of the mesh shader programs fails to compile or link
     */
    public HalfEdgeMeshRuntime() throws Exception {
        this.meshShader = ShaderProgram.ShaderType.Mesh.getShader();
        this.meshUnlitShader = ShaderProgram.ShaderType.MeshUnlit.getShader();
        this.meshScalarShader = ShaderProgram.ShaderType.MeshScalar.getShader();
        this.meshShader.init();
        this.meshUnlitShader.init();
        this.meshScalarShader.init();
        this.meshVao = new VertexArrayObject();
        this.meshVbo = new VertexBufferObject();
        this.ebo = Platforms.gl().genBuffers();
        this.edgeEbo = Platforms.gl().genBuffers();
        this.featureEdgeEbo = Platforms.gl().genBuffers();
        this.scalarVbo = Platforms.gl().genBuffers();
    }

    /**
     * Compile {@code mesh} and upload it as static GPU geometry, replacing
     * any previous mesh. Clears tag partitioning and the per-vertex scalar.
     * Passing {@code null} clears all GPU state without uploading.
     *
     * @param mesh source mesh, or {@code null} to clear
     */
    public void upload(MeshTopology mesh) {
        if (mesh == null) {
            compiledMesh = null;
            edgeCount = 0;
            tagRanges = List.of();
            scalarUploaded = false;
            return;
        }
        compiledMesh = compileSurface(mesh);
        tagRanges = List.of();  // new mesh, any prior tag partitioning is invalid
        scalarUploaded = false; // per-vertex scalar is size-coupled to vertex count
        uploadCompiledMesh(Platforms.gl().STATIC_DRAW());
        uploadEdgeData(mesh);
    }

    /**
     * Like {@link #upload(MeshTopology)} but flagged as dynamic GPU usage,
     * for meshes whose geometry changes frame-to-frame.
     *
     * @param mesh source mesh, or {@code null} to clear
     */
    public void reupload(MeshTopology mesh) {
        if (mesh == null) {
            compiledMesh = null;
            edgeCount = 0;
            tagRanges = List.of();
            scalarUploaded = false;
            return;
        }
        compiledMesh = compileSurface(mesh);
        tagRanges = List.of();
        scalarUploaded = false;
        uploadCompiledMesh(Platforms.gl().DYNAMIC_DRAW());
        uploadEdgeData(mesh);
    }

    private static HalfEdgeCompiledMeshData compileSurface(MeshTopology mesh) {
        if (mesh == null) {
            return null;
        }
        if (mesh instanceof ArrayMesh am) {
            return am.compileSurfaceData();
        }
        if (mesh instanceof HalfEdgeMesh hem) {
            return hem.compileSurfaceData();
        }
        throw new IllegalArgumentException("Unsupported mesh for rendering: " + mesh.getClass().getName());
    }

    /**
     * Position {@code camera} so the current mesh fills the view: targeted
     * on the bounding-sphere center, pulled back along +Z by 2.5x the
     * radius (with a 1.5-unit floor), with a 45-degree field of view. Does
     * nothing if no mesh has been uploaded.
     *
     * @param camera 3D camera to reposition
     */
    public void frameCamera(Camera3D camera) {
        if (compiledMesh == null) {
            return;
        }
        float distance = Math.max(NUM_1_5, compiledMesh.radius * NUM_2_5);
        camera.position.set(compiledMesh.center.x, compiledMesh.center.y, compiledMesh.center.z + distance);
        camera.target.set(compiledMesh.center);
        camera.fov = NUM_45;
        camera.updateViewFirstPerson();
    }

    /**
     * Render the current mesh: builds the projection matrix
     * (perspective or orthographic per {@link #isOrthographic()}), picks the
     * shader for the active {@link ShaderMode}, draws either as a single
     * call or per tag range, then layers the wireframe and feature-edge
     * overlay passes when enabled. No-op when no mesh is uploaded.
     *
     * @param camera 3D camera supplying the view matrix and FOV
     */
    public void render(Camera3D camera) {
        if (compiledMesh == null || compiledMesh.indices.length == 0) {
            return;
        }
        if (meshShader.ID < 0) {
            return;
        }

        int width = Platforms.get().getFrameBufferWidth();
        int height = Platforms.get().getFrameBufferHeight();
        float aspect = width <= 0 || height <= 0 ? NUM_1 : ((float) width / (float) height);

        float far = Math.max(NUM_1000, compiledMesh.radius * NUM_20);
        if (orthographic) {
            float dist = camera.position.distance(camera.target);
            float halfH = dist * (float) Math.tan(Math.toRadians(camera.fov / NUM_2_0));
            float halfW = halfH * aspect;
            projectionMatrix.identity().ortho(-halfW, halfW, -halfH, halfH, NUM_0_01, far);
        } else {
            projectionMatrix.identity().perspective(
                    (float) Math.toRadians((float) camera.fov),
                    aspect,
                    NUM_0_01,
                    far);
        }

        // STAGES uses LAMBERT base; CREST_VS_BOUNDARY uses FLAT unlit base.
        // SCALAR swaps in the heat-map fragment shader.
        boolean useUnlitBase = shaderMode == ShaderMode.FLAT
                || shaderMode == ShaderMode.CREST_VS_BOUNDARY;
        ShaderProgram active;
        if (shaderMode == ShaderMode.SCALAR && scalarUploaded) {
            active = meshScalarShader;
        } else {
            active = useUnlitBase ? meshUnlitShader : meshShader;
        }
        active.use();
        if (shaderMode == ShaderMode.SCALAR && scalarUploaded) {
            active.setFloat("scalarMin", scalarMin);
            active.setFloat("scalarMax", scalarMax);
        }
        active.setMat4(MODEL, modelMatrix);
        active.setMat4(VIEW, camera.view);
        active.setMat4(PROJECTION, projectionMatrix);
        active.setVec4(SOLIDCOLOR, solidColor);
        // PATCH-17: faces always render at zero depth bias; only the
        // overlay pass in renderFeatureEdgeOverlay sets a positive bias.
        active.setFloat(DEPTHBIAS, NUM_0);

        if (shaderMode == ShaderMode.LAMBERT || shaderMode == ShaderMode.STAGES) {
            // Light follows camera so visible faces are always lit.
            // lightDir convention: points INTO scene (shader uses -lightDir for surface→light)
            float dx = camera.target.x - camera.position.x;
            float dy = camera.target.y - camera.position.y;
            float dz = camera.target.z - camera.position.z;
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len > NUM_0_001) {
                lightDir.set(dx / len, dy / len, dz / len);
            }
            active.setVec3("lightDir", lightDir);
            active.setBool("useTexture", false);
            active.setVec3("emissiveColor", emissiveColor);
            active.setFloat("emissiveStrength", NUM_0_08);
            active.setFloat("rimStrength", NUM_0_16);
        }

        meshVao.bind();
        Platforms.gl().bindBuffer(Platforms.gl().ELEMENT_ARRAY_BUFFER(), ebo);

        if (tagRanges.isEmpty()) {
            // Untagged mesh — one draw call with the current solidColor.
            Platforms.gl().drawElements(
                    Platforms.gl().TRIANGLES(),
                    compiledMesh.indices.length,
                    Platforms.gl().UNSIGNED_INT(),
                    0);
        } else {
            // Per-tag draws: set solidColor per range, issue glDrawElements
            // with a byte offset into the EBO.
            for (TagRange range : tagRanges) {
                active.setVec4(SOLIDCOLOR, range.color);
                Platforms.gl().drawElements(
                        Platforms.gl().TRIANGLES(),
                        range.indexCount,
                        Platforms.gl().UNSIGNED_INT(),
                        range.indexStart * Integer.BYTES);
            }
        }
        if (wireframe) {
            renderEdges(camera);
        }
        if (shaderMode == ShaderMode.STAGES
                || shaderMode == ShaderMode.CREST_VS_BOUNDARY
                || shaderMode == ShaderMode.MSC) {
            renderFeatureEdgeOverlay(camera);
        }
    }

    /**
     * Release every GPU buffer owned by this runtime (face EBO, edge EBO,
     * feature-edge EBO, scalar VBO, plus the shared VAO/VBO). Safe to call
     * once after the last render pass; subsequent draw calls are no-ops.
     */
    public void dispose() {
        if (ebo != 0) {
            Platforms.gl().deleteBuffers(ebo);
            ebo = 0;
        }
        if (edgeEbo != 0) {
            Platforms.gl().deleteBuffers(edgeEbo);
            edgeEbo = 0;
        }
        if (featureEdgeEbo != 0) {
            Platforms.gl().deleteBuffers(featureEdgeEbo);
            featureEdgeEbo = 0;
        }
        if (scalarVbo != 0) {
            Platforms.gl().deleteBuffers(scalarVbo);
            scalarVbo = 0;
        }
        meshVbo.delete();
        meshVao.delete();
    }

    private void uploadEdgeData(MeshTopology mesh) {
        if (mesh == null) {
            edgeCount = 0;
            return;
        }
        int[] edgeIndices = edgeIndices(mesh);
        Platforms.gl().bindBuffer(Platforms.gl().ELEMENT_ARRAY_BUFFER(), edgeEbo);
        
        IntBuffer buffer = BufferUtils.createIntBuffer(edgeIndices.length);
        buffer.put(edgeIndices).flip();
        Platforms.gl().bufferData(Platforms.gl().ELEMENT_ARRAY_BUFFER(), buffer, Platforms.gl().STATIC_DRAW());
        edgeCount = edgeIndices.length;
    }

    private static int[] edgeIndices(MeshTopology mesh) {
        if (mesh == null) {
            return new int[0];
        }
        if (mesh instanceof ArrayMesh am) {
            return am.getEdgeIndices();
        }
        if (mesh instanceof HalfEdgeMesh hem) {
            return hem.getEdgeIndices();
        }
        throw new IllegalArgumentException("Unsupported mesh for edge indices: " + mesh.getClass().getName());
    }

    /**
     * Upload a feature-edge overlay to be drawn in STAGES or CREST_VS_BOUNDARY modes. Categories
     * draw in the order given, so later ones overpaint earlier ones on shared edges. Colors are
     * 0x00RRGGBB — see {@link ixdar.geometry.mesh.data.FeatureEdgeColors}.
     *
     * @param categories ordered list of overlay categories, each pairing an
     *                   sRGB color with the edge keys to draw in that color;
     *                   {@code null} or empty clears the overlay
     */
    public void setFeatureEdgeOverlay(List<FeatureEdgeCategory> categories) {
        if (categories == null || categories.isEmpty() || compiledMesh == null) {
            featureEdgeRanges = List.of();
            return;
        }
        int totalIndices = 0;
        for (FeatureEdgeCategory cat : categories) {
            totalIndices += cat.edgeKeys().size() * 2;
        }
        if (totalIndices == 0) {
            featureEdgeRanges = List.of();
            return;
        }
        int[] indices = new int[totalIndices];
        List<FeatureEdgeRange> ranges = new ArrayList<>(categories.size());
        int cursor = 0;
        for (FeatureEdgeCategory cat : categories) {
            int start = cursor;
            for (long key : cat.edgeKeys()) {
                int u = (int) (key >> NUM_32);
                int v = (int) (key & NUM_0xffffffff);
                indices[cursor++] = u;
                indices[cursor++] = v;
            }
            int count = cursor - start;
            if (count > 0) {
                int rgb = cat.colorRgb();
                Vector4f color = new Vector4f(
                        ((rgb >> NUM_16) & NUM_0xf) / NUM_255,
                        ((rgb >> NUM_8) & NUM_0xf) / NUM_255,
                        (rgb & NUM_0xf) / NUM_255,
                        NUM_1);
                ranges.add(new FeatureEdgeRange(color, start, count));
            }
        }
        GL gl = Platforms.gl();
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), featureEdgeEbo);
        IntBuffer buffer = BufferUtils.createIntBuffer(indices.length);
        buffer.put(indices).flip();
        gl.bufferData(gl.ELEMENT_ARRAY_BUFFER(), buffer, gl.STATIC_DRAW());
        featureEdgeRanges = List.copyOf(ranges);
    }

    /** Clear the feature-edge overlay (nothing drawn until setFeatureEdgeOverlay called again). */
    public void clearFeatureEdgeOverlay() {
        featureEdgeRanges = List.of();
    }

    /**
     * Upload a per-vertex scalar buffer for the SCALAR shader mode, normalized to the color ramp
     * by {@code min} and {@code max}; passing {@code Float.NaN} for {@code min} autoscales from
     * the array.
     *
     * <p>The call is a no-op when {@code values} is shorter than the current vertex count.
     *
     * @param values per-vertex scalar values; length must be at least the
     *               current vertex count, or the call is a no-op
     * @param min lower bound of the color ramp; pass {@code Float.NaN} to
     *            autoscale from {@code values}
     * @param max upper bound of the color ramp; pass {@code Float.NaN} to
     *            autoscale from {@code values}
     */
    public void setPerVertexScalar(float[] values, float min, float max) {
        if (values == null || compiledMesh == null || values.length < compiledMesh.vertexCount) {
            scalarUploaded = false;
            return;
        }
        if (Float.isNaN(min) || Float.isNaN(max)) {
            float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < compiledMesh.vertexCount; i++) {
                float v = values[i];
                if (v < lo) lo = v;
                if (v > hi) hi = v;
            }
            this.scalarMin = lo;
            this.scalarMax = hi;
        } else {
            this.scalarMin = min;
            this.scalarMax = max;
        }
        GL gl = Platforms.gl();
        meshVao.bind();
        gl.bindBuffer(gl.ARRAY_BUFFER(), scalarVbo);
        float[] copy;
        if (values.length == compiledMesh.vertexCount) {
            copy = values;
        } else {
            copy = new float[compiledMesh.vertexCount];
            System.arraycopy(values, 0, copy, 0, compiledMesh.vertexCount);
        }
        gl.bufferData(gl.ARRAY_BUFFER(), copy, gl.STATIC_DRAW());
        gl.vertexAttribPointer(NUM_3, 1, gl.FLOAT(), false, Float.BYTES, 0);
        gl.enableVertexAttribArray(NUM_3);
        scalarUploaded = true;
    }

    /** Clear the per-vertex scalar; SCALAR mode renders fall back to meshShader until another upload. */
    public void clearPerVertexScalar() {
        scalarUploaded = false;
    }

    /**
     * Whether a usable per-vertex scalar buffer has been uploaded.
     *
     * @return {@code true} when a per-vertex scalar buffer is currently
     *         uploaded and large enough for the live mesh
     */
    public boolean hasPerVertexScalar() { return scalarUploaded; }
    /**
     * Lower bound used by the SCALAR-mode shader ramp.
     *
     * @return lower bound of the SCALAR-mode color ramp, in scalar units
     */
    public float getScalarMin() { return scalarMin; }
    /**
     * Upper bound used by the SCALAR-mode shader ramp.
     *
     * @return upper bound of the SCALAR-mode color ramp, in scalar units
     */
    public float getScalarMax() { return scalarMax; }

    private void renderFeatureEdgeOverlay(Camera3D camera) {
        if (featureEdgeRanges.isEmpty() || meshUnlitShader.ID < 0) return;
        meshUnlitShader.use();
        meshUnlitShader.setMat4(MODEL, modelMatrix);
        meshUnlitShader.setMat4(VIEW, camera.view);
        meshUnlitShader.setMat4(PROJECTION, projectionMatrix);
        // PATCH-17: leave depth test on so back-facing overlay edges get
        // occluded by front-facing faces. A small clip-space bias shifts
        // overlay vertices toward the camera just enough to beat z-fight
        // against the coplanar face triangles they sit on.
        meshUnlitShader.setFloat(DEPTHBIAS, NUM_0_0003);
        meshVao.bind();
        GL gl = Platforms.gl();
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), featureEdgeEbo);
        gl.lineWidth(NUM_2_5);
        for (FeatureEdgeRange r : featureEdgeRanges) {
            meshUnlitShader.setVec4(SOLIDCOLOR, r.color());
            gl.drawElements(gl.LINES(), r.indexCount(), gl.UNSIGNED_INT(),
                    r.indexStart() * Integer.BYTES);
        }
        // Reset so a subsequent face draw in the same frame doesn't
        // inherit the overlay bias.
        meshUnlitShader.setFloat(DEPTHBIAS, NUM_0);
    }

    /**
     * Wireframe overlay: draw all mesh edges twice — first as faint lines
     * with depth test off (so back edges show through), then as bolder
     * lines with depth test on. Called by {@link #render(Camera3D)} when
     * {@link #isWireframe()} is set.
     *
     * @param camera 3D camera supplying the view matrix
     */
    public void renderEdges(Camera3D camera) {
        if (meshUnlitShader.ID < 0 || edgeCount <= 0) {
            return;
        }
        meshUnlitShader.use();
        meshUnlitShader.setMat4(MODEL, modelMatrix);
        meshUnlitShader.setMat4(VIEW, camera.view);
        meshUnlitShader.setMat4(PROJECTION, projectionMatrix);
        meshVao.bind();
        meshUnlitShader.setVec4(SOLIDCOLOR, edgeFaintColor);
        Platforms.gl().bindBuffer(Platforms.gl().ELEMENT_ARRAY_BUFFER(), edgeEbo);
        Platforms.gl().disable(Platforms.gl().DEPTH_TEST());
        Platforms.gl().lineWidth(NUM_1_5);
        Platforms.gl().drawElements(Platforms.gl().LINES(), edgeCount, Platforms.gl().UNSIGNED_INT(), 0);
        Platforms.gl().enable(Platforms.gl().DEPTH_TEST());

        meshUnlitShader.setVec4(SOLIDCOLOR, edgeColor);
        Platforms.gl().lineWidth(NUM_2_0_2);
        Platforms.gl().drawElements(Platforms.gl().LINES(), edgeCount , Platforms.gl().UNSIGNED_INT(), 0);
        

    }

    /**
     * Vertex count of the currently uploaded mesh.
     *
     * @return number of unique vertices in the currently uploaded mesh, or 0 if none
     */
    public int getVertexCount() {
        return compiledMesh == null ? 0 : compiledMesh.vertexCount;
    }

    /**
     * Triangle count of the currently uploaded mesh.
     *
     * @return number of triangle faces in the currently uploaded mesh, or 0 if none
     */
    public int getFaceCount() {
        return compiledMesh == null ? 0 : compiledMesh.faceCount;
    }

    /**
     * AABB minimum corner of the uploaded mesh.
     *
     * @return defensive copy of the AABB minimum corner, or the zero vector
     *         if no mesh is uploaded
     */
    public Vector3f getBoundingBoxMin() {
        return compiledMesh == null ? new Vector3f() : new Vector3f(minBounds);
    }

    /**
     * AABB maximum corner of the uploaded mesh.
     *
     * @return defensive copy of the AABB maximum corner, or the zero vector
     *         if no mesh is uploaded
     */
    public Vector3f getBoundingBoxMax() {
        return compiledMesh == null ? new Vector3f() : new Vector3f(maxBounds);
    }

    /**
     * Bounding-sphere center of the uploaded mesh.
     *
     * @return defensive copy of the bounding-sphere center, or the zero
     *         vector if no mesh is uploaded
     */
    public Vector3f getCenter() {
        return compiledMesh == null ? new Vector3f() : new Vector3f(center);
    }

    /**
     * Set the fallback face color used when no tag partitioning is active.
     *
     * @param color RGBA channels, each in [0, 1]; copied into the runtime's
     *              internal {@code solidColor}
     */
    public void setSolidColor(Vector4f color) {
        solidColor.set(color);
    }

    /**
     * Per-instance world transform applied to mesh vertices. Defaults to identity.
     *
     * @param m model matrix; copied into the runtime's internal matrix
     */
    public void setModelMatrix(Matrix4f m) {
        modelMatrix.set(m);
    }

    /**
     * Reset the per-instance model matrix to the identity transform.
     */
    public void setModelIdentity() {
        modelMatrix.identity();
    }

    /**
     * Install per-tag vertex memberships on the current mesh. Triangles are
     * partitioned so all triangles belonging to the same winning tag render
     * consecutively with that tag's colour. Must be called after
     * {@link #upload(MeshTopology)} — it rewrites the EBO.
     *
     * @param tags tag name → per-vertex boolean mask (length = vertex count).
     *             A triangle belongs to tag T when all three of its vertices
     *             have {@code tags[T][v] == true}. If multiple tags qualify
     *             for a triangle, the lowest-hash name wins (deterministic).
     */
    public void setTags(Map<String, boolean[]> tags) {
        if (compiledMesh == null || compiledMesh.indices.length == 0) {
            tagRanges = List.of();
            return;
        }
        if (tags == null || tags.isEmpty()) {
            clearTags();
            return;
        }
        int[] originalIndices = compiledMesh.indices;
        int triCount = originalIndices.length / NUM_3;
        int vertexCount = compiledMesh.vertexCount;
        // Validate masks and sort tag names for deterministic priority.
        List<String> tagNames = new ArrayList<>(tags.keySet());
        tagNames.sort((a, b) -> {
            int ha = stableHash(a);
            int hb = stableHash(b);
            if (ha != hb) return Integer.compare(ha, hb);
            return a.compareTo(b);
        });
        Map<String, boolean[]> maskByTag = new HashMap<>();
        for (String name : tagNames) {
            boolean[] mask = tags.get(name);
            if (mask == null || mask.length < vertexCount) continue;
            maskByTag.put(name, mask);
        }
        // Assign each triangle to its winning tag (first in sorted order
        // where all three vertices are members). Untagged triangles sort last.
        String[] triTag = new String[triCount];
        for (int t = 0; t < triCount; t++) {
            int v0 = originalIndices[t * NUM_3];
            int v1 = originalIndices[t * NUM_3 + 1];
            int v2 = originalIndices[t * NUM_3 + 2];
            for (String name : tagNames) {
                boolean[] mask = maskByTag.get(name);
                if (mask == null) continue;
                if (mask[v0] && mask[v1] && mask[v2]) {
                    triTag[t] = name;
                    break;
                }
            }
        }
        // Bucket triangle indices by winning tag, then build a new EBO array
        // in tag order (untagged last) and record per-tag ranges.
        Map<String, List<Integer>> trisByTag = new HashMap<>();
        List<Integer> untagged = new ArrayList<>();
        for (int t = 0; t < triCount; t++) {
            String name = triTag[t];
            if (name == null) {
                untagged.add(t);
            } else {
                trisByTag.computeIfAbsent(name, k -> new ArrayList<>()).add(t);
            }
        }
        int[] newIndices = new int[originalIndices.length];
        List<TagRange> ranges = new ArrayList<>();
        int cursor = 0;
        for (String name : tagNames) {
            List<Integer> tris = trisByTag.get(name);
            if (tris == null || tris.isEmpty()) continue;
            int start = cursor;
            for (int t : tris) {
                newIndices[cursor++] = originalIndices[t * NUM_3];
                newIndices[cursor++] = originalIndices[t * NUM_3 + 1];
                newIndices[cursor++] = originalIndices[t * NUM_3 + 2];
            }
            int count = cursor - start;
            ranges.add(new TagRange(name, resolveColor(name), start, count));
        }
        // Untagged triangles go at the end — rendered with the global
        // solidColor in a trailing untagged range (name "" marks it).
        if (!untagged.isEmpty()) {
            int start = cursor;
            for (int t : untagged) {
                newIndices[cursor++] = originalIndices[t * NUM_3];
                newIndices[cursor++] = originalIndices[t * NUM_3 + 1];
                newIndices[cursor++] = originalIndices[t * NUM_3 + 2];
            }
            int count = cursor - start;
            Vector4f untaggedColor = new Vector4f(solidColor);
            ranges.add(new TagRange("", untaggedColor, start, count));
        }
        tagRanges = List.copyOf(ranges);
        uploadIndexBuffer(newIndices, Platforms.gl().DYNAMIC_DRAW());
    }

    /**
     * Explicit colour override for a tag. Takes precedence over the
     * stable-hash fallback in {@link #resolveColor(String)}. Must be called
     * before {@link #setTags(Map)} to take effect in the computed ranges.
     *
     * @param tag tag name to override
     * @param rgba color the tag should render with; copied
     */
    public void setTagColor(String tag, Vector4f rgba) {
        tagColorOverrides.put(tag, new Vector4f(rgba));
    }

    /** Remove all tag colour overrides. */
    public void clearTagColors() {
        tagColorOverrides.clear();
    }

    /** Restore the untagged single-draw-call rendering. */
    public void clearTags() {
        tagRanges = List.of();
        if (compiledMesh != null && compiledMesh.indices.length > 0) {
            uploadIndexBuffer(compiledMesh.indices, Platforms.gl().DYNAMIC_DRAW());
        }
    }

    /**
     * Currently selected shading mode for the main mesh draw.
     *
     * @return active {@link ShaderMode} driving the main mesh draw
     */
    public ShaderMode getShaderMode() {
        return shaderMode;
    }

    /**
     * Pick the shading mode for the main mesh draw. {@code null} resets to
     * {@link ShaderMode#LAMBERT}.
     *
     * @param mode new shader mode, or {@code null} for the default
     */
    public void setShaderMode(ShaderMode mode) {
        this.shaderMode = mode == null ? ShaderMode.LAMBERT : mode;
    }

    private Vector4f resolveColor(String tag) {
        Vector4f override = tagColorOverrides.get(tag);
        if (override != null) return new Vector4f(override);
        return stableTagColor(tag);
    }

    /**
     * Golden-ratio-hue HSL colour derived from the tag name. Matches
     * {@code PatchRenderer.uniquePatchColor(pid)} for a tag named
     * {@code "patch_<pid>"}, so tags originating from the decomposer render
     * identically in the live viewer and the offline multiview PNGs.
     *
     * @param tagName tag identifier; names matching {@code patch_<int>} are
     *                colored from the patch id, all others fall back to a
     *                stable string hash
     * @return RGBA color with alpha = 1
     */
    public static Vector4f stableTagColor(String tagName) {
        int pid = -1;
        if (tagName != null && tagName.startsWith(PATCH)) {
            try {
                pid = Integer.parseInt(tagName.substring(PATCH.length()));
            } catch (NumberFormatException ignored) {}
        }
        float h;
        if (pid >= 0) {
            h = (float) ((pid * NUM_0_6180339887498949) % 1.0);
        } else {
            int hash = stableHash(tagName == null ? "" : tagName);
            h = ((hash & NUM_0x7FFFFFF) % NUM_10000) / NUM_10000_2;
        }
        float[] rgb = hslToRgb(h, NUM_0_65, NUM_0_55);
        return new Vector4f(rgb[0], rgb[1], rgb[2], NUM_1);
    }

    private static int stableHash(String s) {
        int h = 0;
        for (int i = 0; i < s.length(); i++) {
            h = NUM_31 * h + s.charAt(i);
        }
        return h;
    }

    private static float[] hslToRgb(float h, float s, float l) {
        float c = (NUM_1 - Math.abs(NUM_2 * l - NUM_1)) * s;
        float hp = h * NUM_6;
        float x = c * (NUM_1 - Math.abs(hp % NUM_2 - NUM_1));
        float r1 = NUM_0, g1 = NUM_0, b1 = NUM_0;
        if (hp < NUM_1)      { r1 = c; g1 = x; }
        else if (hp < NUM_2) { r1 = x; g1 = c; }
        else if (hp < NUM_3_2) { g1 = c; b1 = x; }
        else if (hp < NUM_4) { g1 = x; b1 = c; }
        else if (hp < NUM_5) { r1 = x; b1 = c; }
        else              { r1 = c; b1 = x; }
        float m = l - c * NUM_0_5;
        return new float[]{
                Math.max(0, Math.min(1, r1 + m)),
                Math.max(0, Math.min(1, g1 + m)),
                Math.max(0, Math.min(1, b1 + m)),
        };
    }

    private void uploadIndexBuffer(int[] indices, int usage) {
        GL gl = Platforms.gl();
        meshVao.bind();
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), ebo);
        IntBuffer uploadBuffer = ensureIndexBufferCapacity(indices.length);
        uploadBuffer.clear();
        uploadBuffer.put(indices).flip();
        gl.bufferData(gl.ELEMENT_ARRAY_BUFFER(), uploadBuffer, usage);
    }

    /**
     * Whether the wireframe overlay pass is enabled.
     *
     * @return {@code true} when {@link #render(Camera3D)} should layer the
     *         wireframe edge overlay on top of the filled mesh
     */
    public boolean isWireframe() {
        return wireframe;
    }

    /**
     * Toggle the wireframe edge overlay drawn after the filled mesh.
     *
     * @param wireframe {@code true} to enable the overlay
     */
    public void setWireframe(boolean wireframe) {
        this.wireframe = wireframe;
    }

    /**
     * Whether the renderer uses an orthographic projection.
     *
     * @return {@code true} when the projection matrix is built as an
     *         orthographic view instead of a perspective one
     */
    public boolean isOrthographic() {
        return orthographic;
    }

    /**
     * Switch between perspective and orthographic projection. The half-height
     * of the orthographic frustum tracks the distance from the camera to its
     * target, so it visually matches the perspective FOV.
     *
     * @param orthographic {@code true} to render with an orthographic projection
     */
    public void setOrthographic(boolean orthographic) {
        this.orthographic = orthographic;
    }

    private void uploadCompiledMesh(int usage) {
        if (compiledMesh == null) {
            return;
        }

        GL gl = Platforms.gl();
        meshVao.bind();
        meshVbo.bind(gl.ARRAY_BUFFER());
        meshVbo.uploadData(gl.ARRAY_BUFFER(), compiledMesh.vertices, usage);
        gl.vertexAttribPointer(0, NUM_3, gl.FLOAT(), false, NUM_8 * Float.BYTES, 0);
        gl.enableVertexAttribArray(0);
        gl.vertexAttribPointer(1, NUM_3, gl.FLOAT(), false, NUM_8 * Float.BYTES, NUM_3 * Float.BYTES);
        gl.enableVertexAttribArray(1);
        gl.vertexAttribPointer(2, 2, gl.FLOAT(), false, NUM_8 * Float.BYTES, NUM_6_2 * Float.BYTES);
        gl.enableVertexAttribArray(2);

        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), ebo);
        IntBuffer uploadBuffer = ensureIndexBufferCapacity(compiledMesh.indices.length);
        uploadBuffer.clear();
        uploadBuffer.put(compiledMesh.indices).flip();
        gl.bufferData(gl.ELEMENT_ARRAY_BUFFER(), uploadBuffer, usage);

        minBounds.set(compiledMesh.minBounds);
        maxBounds.set(compiledMesh.maxBounds);
        center.set(compiledMesh.center);
    }

    private IntBuffer ensureIndexBufferCapacity(int requiredCapacity) {
        if (indexBuffer == null || indexBuffer.capacity() < requiredCapacity) {
            indexBuffer = BufferUtils.createIntBuffer(requiredCapacity);
        }
        return indexBuffer;
    }

    /**
     * Shading mode for the main mesh draw.
     * <ul>
     *   <li>{@link #LAMBERT} — default lit look.</li>
     *   <li>{@link #FLAT} — unlit, each fragment writing its tag's exact color.</li>
     *   <li>{@link #STAGES} — LAMBERT plus the feature-edge overlay.</li>
     *   <li>{@link #CREST_VS_BOUNDARY} — FLAT plus boundaries against crests.</li>
     *   <li>{@link #SCALAR} — ramp over {@link #setPerVertexScalar(float[])}.</li>
     *   <li>{@link #MSC} — Morse-Smale arcs; critical points stay CPU-only.</li>
     * </ul>
     */
    public enum ShaderMode { LAMBERT, FLAT, STAGES, CREST_VS_BOUNDARY, SCALAR, MSC }

    /**
     * A contiguous range inside the current EBO that all belongs to one tag.
     * The render loop iterates these, setting {@code solidColor} per range.
     */
    public record TagRange(String tagName, Vector4f color, int indexStart, int indexCount) {}

    /** One overlay line-draw pass: color + contiguous range in the feature-edge EBO. */
    public record FeatureEdgeRange(Vector4f color, int indexStart, int indexCount) {}

    /**
     * One category of overlay edges: a color and the edge keys (packed {@code
     * u<<32 | v}, low-endpoint in high bits) that should draw in that color.
     * Categories are drawn in the order supplied, so later ones overpaint
     * earlier ones where they share edges.
     */
    public record FeatureEdgeCategory(int colorRgb, Collection<Long> edgeKeys) {}
}
