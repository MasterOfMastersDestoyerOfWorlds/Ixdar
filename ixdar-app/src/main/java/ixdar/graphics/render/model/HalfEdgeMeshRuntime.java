package ixdar.graphics.render.model;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;

import ixdar.geometry.mesh.data.ArrayMesh;
import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.graphics.render.shaders.VertexArrayObject;
import ixdar.graphics.render.shaders.VertexBufferObject;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;

public class HalfEdgeMeshRuntime {

    /**
     * Shading mode for the main mesh draw.
     * <ul>
     *   <li>{@link #LAMBERT} — default lit look.</li>
     *   <li>{@link #FLAT} — unlit; every fragment writes its tag's exact color
     *       so VLMs / pixel samplers can map (x,y) → patch deterministically.</li>
     *   <li>{@link #STAGES} — LAMBERT base + feature-edge overlay showing
     *       which detector fires where (dihedral / principal / crest /
     *       saddle / multi-source).</li>
     *   <li>{@link #CREST_VS_BOUNDARY} — FLAT base + overlay of patch
     *       boundaries vs crest edges, categorized as boundary-only (not
     *       crest-backed), crest-ignored, or crest-honored.</li>
     *   <li>{@link #SCALAR} — per-vertex scalar value mapped to a thermal
     *       ramp (deep indigo → orange → pale yellow). Value comes from
     *       {@link #setPerVertexScalar(float[])} and is interpolated
     *       across the triangle. Used for heat-map diagnostics such as
     *       Coons reconstruction error or curvature magnitude.</li>
     *   <li>{@link #MSC} — Morse-Smale complex overlay (PATCH-23): arcs
     *       drawn as black polylines through the feature-edge overlay
     *       infrastructure so they get PATCH-17 depth-aware occlusion.
     *       Critical-point dots are still CPU-only for now.</li>
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
    public record FeatureEdgeCategory(int colorRgb, java.util.Collection<Long> edgeKeys) {}

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
    private java.util.List<FeatureEdgeRange> featureEdgeRanges = java.util.List.of();
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

    public void frameCamera(Camera3D camera) {
        if (compiledMesh == null) {
            return;
        }
        float distance = Math.max(1.5f, compiledMesh.radius * 2.5f);
        camera.position.set(compiledMesh.center.x, compiledMesh.center.y, compiledMesh.center.z + distance);
        camera.target.set(compiledMesh.center);
        camera.fov = 45f;
        camera.updateViewFirstPerson();
    }

    public void render(Camera3D camera) {
        if (compiledMesh == null || compiledMesh.indices.length == 0) {
            return;
        }
        if (meshShader.ID < 0) {
            return;
        }

        int width = Platforms.get().getFrameBufferWidth();
        int height = Platforms.get().getFrameBufferHeight();
        float aspect = width <= 0 || height <= 0 ? 1f : ((float) width / (float) height);

        float far = Math.max(1000f, compiledMesh.radius * 20f);
        if (orthographic) {
            float dist = camera.position.distance(camera.target);
            float halfH = dist * (float) Math.tan(Math.toRadians(camera.fov / 2.0));
            float halfW = halfH * aspect;
            projectionMatrix.identity().ortho(-halfW, halfW, -halfH, halfH, 0.01f, far);
        } else {
            projectionMatrix.identity().perspective(
                    (float) Math.toRadians((float) camera.fov),
                    aspect,
                    0.01f,
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
        active.setMat4("model", modelMatrix);
        active.setMat4("view", camera.view);
        active.setMat4("projection", projectionMatrix);
        active.setVec4("solidColor", solidColor);
        // PATCH-17: faces always render at zero depth bias; only the
        // overlay pass in renderFeatureEdgeOverlay sets a positive bias.
        active.setFloat("depthBias", 0f);

        if (shaderMode == ShaderMode.LAMBERT || shaderMode == ShaderMode.STAGES) {
            // Light follows camera so visible faces are always lit.
            // lightDir convention: points INTO scene (shader uses -lightDir for surface→light)
            float dx = camera.target.x - camera.position.x;
            float dy = camera.target.y - camera.position.y;
            float dz = camera.target.z - camera.position.z;
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len > 0.001f) {
                lightDir.set(dx / len, dy / len, dz / len);
            }
            active.setVec3("lightDir", lightDir);
            active.setBool("useTexture", false);
            active.setVec3("emissiveColor", emissiveColor);
            active.setFloat("emissiveStrength", 0.08f);
            active.setFloat("rimStrength", 0.16f);
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
                active.setVec4("solidColor", range.color);
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
     * Upload a feature-edge overlay to be drawn in STAGES or CREST_VS_BOUNDARY
     * modes. Categories draw in the order provided, so later ones overpaint
     * earlier ones where they share an edge. Colors are 0x00RRGGBB — see
     * {@link ixdar.geometry.mesh.data.FeatureEdgeColors}.
     *
     * <p>Callers usually derive categories from {@code
     * SemanticPatchDecomposer.DecompositionDiagnostics} so the on-screen
     * colors match the offline PNG diagnostic pixel-for-pixel.
     */
    public void setFeatureEdgeOverlay(java.util.List<FeatureEdgeCategory> categories) {
        if (categories == null || categories.isEmpty() || compiledMesh == null) {
            featureEdgeRanges = java.util.List.of();
            return;
        }
        int totalIndices = 0;
        for (FeatureEdgeCategory cat : categories) {
            totalIndices += cat.edgeKeys().size() * 2;
        }
        if (totalIndices == 0) {
            featureEdgeRanges = java.util.List.of();
            return;
        }
        int[] indices = new int[totalIndices];
        java.util.List<FeatureEdgeRange> ranges = new java.util.ArrayList<>(categories.size());
        int cursor = 0;
        for (FeatureEdgeCategory cat : categories) {
            int start = cursor;
            for (long key : cat.edgeKeys()) {
                int u = (int) (key >> 32);
                int v = (int) (key & 0xffffffffL);
                indices[cursor++] = u;
                indices[cursor++] = v;
            }
            int count = cursor - start;
            if (count > 0) {
                int rgb = cat.colorRgb();
                Vector4f color = new Vector4f(
                        ((rgb >> 16) & 0xff) / 255f,
                        ((rgb >> 8) & 0xff) / 255f,
                        (rgb & 0xff) / 255f,
                        1f);
                ranges.add(new FeatureEdgeRange(color, start, count));
            }
        }
        GL gl = Platforms.gl();
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), featureEdgeEbo);
        IntBuffer buffer = BufferUtils.createIntBuffer(indices.length);
        buffer.put(indices).flip();
        gl.bufferData(gl.ELEMENT_ARRAY_BUFFER(), buffer, gl.STATIC_DRAW());
        featureEdgeRanges = java.util.List.copyOf(ranges);
    }

    /** Clear the feature-edge overlay (nothing drawn until setFeatureEdgeOverlay called again). */
    public void clearFeatureEdgeOverlay() {
        featureEdgeRanges = java.util.List.of();
    }

    /**
     * Upload a per-vertex scalar buffer for the SCALAR shader mode.
     * Values are passed verbatim to the GPU; the fragment shader maps the
     * per-vertex interpolated result through a dark→bright ramp using the
     * provided {@code min} / {@code max} as the normalization range. To
     * autoscale, pass {@code min=Float.NaN} and the runtime will compute
     * the min/max from the array.
     *
     * <p>The VBO is bound to attribute location 3 in the VAO; other
     * shader modes ignore the attribute (their vertex shaders don't declare
     * a location=3 input).
     *
     * <p>Size must equal the current mesh vertex count; shorter arrays are
     * ignored with a no-op.
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
        gl.vertexAttribPointer(3, 1, gl.FLOAT(), false, Float.BYTES, 0);
        gl.enableVertexAttribArray(3);
        scalarUploaded = true;
    }

    /** Clear the per-vertex scalar; SCALAR mode renders fall back to meshShader until another upload. */
    public void clearPerVertexScalar() {
        scalarUploaded = false;
    }

    public boolean hasPerVertexScalar() { return scalarUploaded; }
    public float getScalarMin() { return scalarMin; }
    public float getScalarMax() { return scalarMax; }

    private void renderFeatureEdgeOverlay(Camera3D camera) {
        if (featureEdgeRanges.isEmpty() || meshUnlitShader.ID < 0) return;
        meshUnlitShader.use();
        meshUnlitShader.setMat4("model", modelMatrix);
        meshUnlitShader.setMat4("view", camera.view);
        meshUnlitShader.setMat4("projection", projectionMatrix);
        // PATCH-17: leave depth test on so back-facing overlay edges get
        // occluded by front-facing faces. A small clip-space bias shifts
        // overlay vertices toward the camera just enough to beat z-fight
        // against the coplanar face triangles they sit on.
        meshUnlitShader.setFloat("depthBias", 0.0003f);
        meshVao.bind();
        GL gl = Platforms.gl();
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), featureEdgeEbo);
        gl.lineWidth(2.5f);
        for (FeatureEdgeRange r : featureEdgeRanges) {
            meshUnlitShader.setVec4("solidColor", r.color());
            gl.drawElements(gl.LINES(), r.indexCount(), gl.UNSIGNED_INT(),
                    r.indexStart() * Integer.BYTES);
        }
        // Reset so a subsequent face draw in the same frame doesn't
        // inherit the overlay bias.
        meshUnlitShader.setFloat("depthBias", 0f);
    }

    public void renderEdges(Camera3D camera) {
        if (meshUnlitShader.ID < 0 || edgeCount <= 0) {
            return;
        }
        meshUnlitShader.use();
        meshUnlitShader.setMat4("model", modelMatrix);
        meshUnlitShader.setMat4("view", camera.view);
        meshUnlitShader.setMat4("projection", projectionMatrix);
        meshVao.bind();
        meshUnlitShader.setVec4("solidColor", edgeFaintColor);
        Platforms.gl().bindBuffer(Platforms.gl().ELEMENT_ARRAY_BUFFER(), edgeEbo);
        Platforms.gl().disable(Platforms.gl().DEPTH_TEST());
        Platforms.gl().lineWidth(1.5f);
        Platforms.gl().drawElements(Platforms.gl().LINES(), edgeCount, Platforms.gl().UNSIGNED_INT(), 0);
        Platforms.gl().enable(Platforms.gl().DEPTH_TEST());

        meshUnlitShader.setVec4("solidColor", edgeColor);
        Platforms.gl().lineWidth(2.0f);
        Platforms.gl().drawElements(Platforms.gl().LINES(), edgeCount , Platforms.gl().UNSIGNED_INT(), 0);
        

    }

    public int getVertexCount() {
        return compiledMesh == null ? 0 : compiledMesh.vertexCount;
    }

    public int getFaceCount() {
        return compiledMesh == null ? 0 : compiledMesh.faceCount;
    }

    public Vector3f getBoundingBoxMin() {
        return compiledMesh == null ? new Vector3f() : new Vector3f(minBounds);
    }

    public Vector3f getBoundingBoxMax() {
        return compiledMesh == null ? new Vector3f() : new Vector3f(maxBounds);
    }

    public Vector3f getCenter() {
        return compiledMesh == null ? new Vector3f() : new Vector3f(center);
    }

    public void setSolidColor(float r, float g, float b, float a) {
        solidColor.set(r, g, b, a);
    }

    /** Per-instance world transform applied to mesh vertices. Defaults to identity. */
    public void setModelMatrix(Matrix4f m) {
        modelMatrix.set(m);
    }

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
        int triCount = originalIndices.length / 3;
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
            int v0 = originalIndices[t * 3];
            int v1 = originalIndices[t * 3 + 1];
            int v2 = originalIndices[t * 3 + 2];
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
                newIndices[cursor++] = originalIndices[t * 3];
                newIndices[cursor++] = originalIndices[t * 3 + 1];
                newIndices[cursor++] = originalIndices[t * 3 + 2];
            }
            int count = cursor - start;
            ranges.add(new TagRange(name, resolveColor(name), start, count));
        }
        // Untagged triangles go at the end — rendered with the global
        // solidColor in a trailing untagged range (name "" marks it).
        if (!untagged.isEmpty()) {
            int start = cursor;
            for (int t : untagged) {
                newIndices[cursor++] = originalIndices[t * 3];
                newIndices[cursor++] = originalIndices[t * 3 + 1];
                newIndices[cursor++] = originalIndices[t * 3 + 2];
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

    public ShaderMode getShaderMode() {
        return shaderMode;
    }

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
     */
    public static Vector4f stableTagColor(String tagName) {
        int pid = -1;
        if (tagName != null && tagName.startsWith("patch_")) {
            try {
                pid = Integer.parseInt(tagName.substring("patch_".length()));
            } catch (NumberFormatException ignored) {}
        }
        float h;
        if (pid >= 0) {
            h = (float) ((pid * 0.6180339887498949) % 1.0);
        } else {
            int hash = stableHash(tagName == null ? "" : tagName);
            h = ((hash & 0x7FFFFFFF) % 10000) / 10000f;
        }
        float[] rgb = hslToRgb(h, 0.65f, 0.55f);
        return new Vector4f(rgb[0], rgb[1], rgb[2], 1f);
    }

    private static int stableHash(String s) {
        int h = 0;
        for (int i = 0; i < s.length(); i++) {
            h = 31 * h + s.charAt(i);
        }
        return h;
    }

    private static float[] hslToRgb(float h, float s, float l) {
        float c = (1f - Math.abs(2f * l - 1f)) * s;
        float hp = h * 6f;
        float x = c * (1f - Math.abs(hp % 2f - 1f));
        float r1 = 0f, g1 = 0f, b1 = 0f;
        if (hp < 1f)      { r1 = c; g1 = x; }
        else if (hp < 2f) { r1 = x; g1 = c; }
        else if (hp < 3f) { g1 = c; b1 = x; }
        else if (hp < 4f) { g1 = x; b1 = c; }
        else if (hp < 5f) { r1 = x; b1 = c; }
        else              { r1 = c; b1 = x; }
        float m = l - c * 0.5f;
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

    public boolean isWireframe() {
        return wireframe;
    }

    public void setWireframe(boolean wireframe) {
        this.wireframe = wireframe;
    }

    public boolean isOrthographic() {
        return orthographic;
    }

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
        gl.vertexAttribPointer(0, 3, gl.FLOAT(), false, 8 * Float.BYTES, 0);
        gl.enableVertexAttribArray(0);
        gl.vertexAttribPointer(1, 3, gl.FLOAT(), false, 8 * Float.BYTES, 3 * Float.BYTES);
        gl.enableVertexAttribArray(1);
        gl.vertexAttribPointer(2, 2, gl.FLOAT(), false, 8 * Float.BYTES, 6 * Float.BYTES);
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
}
