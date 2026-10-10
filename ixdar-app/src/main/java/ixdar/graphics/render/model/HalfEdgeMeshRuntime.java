package ixdar.graphics.render.model;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;

import ixdar.geometry.mesh.data.CornerUvField;
import ixdar.geometry.mesh.data.CornerUvSplit;
import ixdar.geometry.mesh.data.EdgeKey;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MaterialData;
import ixdar.geometry.mesh.data.MaterialSet;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.SemanticPatchDecomposer;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.api.IntField;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.Texture;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.shaders.MeshLineShader;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.graphics.render.shaders.VertexArrayObject;
import ixdar.graphics.render.shaders.VertexBufferObject;
import ixdar.platform.Platforms;
import ixdar.platform.gl.DecodedImage;
import ixdar.platform.gl.GL;

public class HalfEdgeMeshRuntime {
    public static final String MODEL = "model";
    public static final String VIEW = "view";
    public static final String PROJECTION = "projection";
    public static final String SOLIDCOLOR = "solidColor";
    public static final String PATCH = "patch_";
    public static final float FRAME_DISTANCE_FLOOR = 1.5f;
    public static final float FRAME_DISTANCE_MULTIPLIER = 2.5f;
    public static final float FEATURE_EDGE_LINE_WIDTH = 2.5f;
    public static final float DEFAULT_FOV = 45f;
    public static final float EMISSIVE_STRENGTH = 0.08f;
    public static final float RIM_STRENGTH = 0.16f;
    public static final float CHANNEL_NORMALIZE = 255f;
    public static final int VEC3_SIZE = 3;
    public static final int VERTEX_STRIDE = 8;
    public static final int UV_OFFSET = 6;
    public static final int SCALAR_ATTRIB_LOCATION = 3;
    public static final float EDGE_LINE_WIDTH = 2.0f;
    public static final double GOLDEN_RATIO_CONJUGATE = 0.6180339887498949;
    public static final int HASH_MASK = 0x7FFFFFFF;
    public static final int HUE_QUANTIZATION = 10000;
    public static final float TAG_SATURATION = 0.65f;
    public static final float TAG_LIGHTNESS = 0.55f;
    public static final int HASH_PRIME = 31;

    public static final float NEAR_PLANE_DISTANCE_FRACTION = 0.01f;

    public static final float NEAR_PLANE_FLOOR = 1e-4f;

    public static final float FAR_PLANE_EXTENT_MUL = 3f;

    public static final String BASE_COLOR_TEXTURE_NAME = "mesh_base_color";

    public static final String USE_TEXTURE_UNIFORM = "useTexture";

    public static final int COORDINATES_PER_VERTEX = 3;

    public static final int PICK_CHANNEL_MAX = 255;

    public static final int PICK_CHANNEL_BITS = 8;

    public static final int PICK_ID_MASK = 0xFFFFFF;

    /** Draws every overlay line: discards fragments on surface facing away from the camera. */
    public final MeshLineShader lineShader;

    /** The mesh last uploaded, whose edges and faces the overlay lines are drawn on. */
    public MeshTopology surfaceMesh;

    /** Finds the faces under free-form lines on {@link #surfaceMesh}; built on first use. */
    public SurfaceFaceLocator surfaceFaceLocator;

    /** Every mesh edge as a surface line, for the wireframe. */
    public final VertexBuffer edgeLines = new VertexBuffer();

    /** The feature-edge categories' edges as surface lines, one vertex range per category. */
    public final VertexBuffer featureEdgeLines = new VertexBuffer();

    /**
     * Model-space translation each tag's triangles are drawn with, by tag name; a tag without one
     * draws in place. A view offset only: the uploaded geometry and the picking pass ignore it.
     */
    public final Map<String, Vector3f> tagOffsets = new HashMap<>();

    /** Colour of an untagged draw, the surface's own colour; {@link #setSolidColor} sets it. */
    public final Vector4f solidColor = Color.BLUE_GRAY.toVector4f();

    private final ShaderProgram meshShader;
    private final ShaderProgram meshUnlitShader;
    private final ShaderProgram meshScalarShader;
    private final Matrix4f modelMatrix = new Matrix4f();
    private final Matrix4f tagModelMatrix = new Matrix4f();
    private final Matrix4f projectionMatrix = new Matrix4f();
    private final Vector4f edgeColor = Color.RED.toVector4f();
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
    private List<FeatureEdgeRange> featureEdgeRanges = List.of();
    private int scalarVbo;
    private boolean scalarUploaded = false;
    private float scalarMin = 0f;
    private float scalarMax = 1f;
    private boolean wireframe = false;
    private volatile boolean orthographic = false;
    private boolean xray = true;
    private final Map<String, Vector4f> tagColorOverrides = new HashMap<>();
    private List<TagRange> tagRanges = List.of();
    private ShaderMode shaderMode = ShaderMode.LAMBERT;
    private Texture[] materialTextures = new Texture[0];
    private int[] materialRangeStart = new int[0];
    private int[] materialRangeCount = new int[0];
    private final VertexArrayObject texturedVao;
    private final VertexBufferObject texturedVbo;
    private int texturedEbo;
    private int texturedIndexCount;

    private final ShaderProgram meshPickShader;
    private final VertexArrayObject pickVao;
    private final VertexBufferObject pickPositionVbo;
    private final VertexBufferObject pickIdVbo;
    private int pickVertexCount;
    private int pickFaceCount;
    private final Vector4f projectedPoint = new Vector4f();

    /**
     * Build the runtime: allocate the mesh shader programs (lit, unlit, scalar, line), the
     * shared VAO/VBO, and the buffer names for triangles and the per-vertex scalar attribute. No
     * mesh is uploaded yet — call {@link #upload(MeshTopology)}.
     */
    public HalfEdgeMeshRuntime() {
        this.meshShader = ShaderProgram.ShaderType.Mesh.getShader();
        this.meshUnlitShader = ShaderProgram.ShaderType.MeshUnlit.getShader();
        this.meshScalarShader = ShaderProgram.ShaderType.MeshScalar.getShader();
        this.lineShader = (MeshLineShader) ShaderProgram.ShaderType.MeshLine.getShader();
        this.meshShader.init();
        this.meshUnlitShader.init();
        this.meshScalarShader.init();
        this.lineShader.init();
        this.meshVao = new VertexArrayObject();
        this.meshVbo = new VertexBufferObject();
        this.ebo = Platforms.gl().genBuffers();
        this.scalarVbo = Platforms.gl().genBuffers();
        this.texturedVao = new VertexArrayObject();
        this.texturedVbo = new VertexBufferObject();
        this.texturedEbo = Platforms.gl().genBuffers();
        this.meshPickShader = ShaderProgram.ShaderType.MeshPick.getShader();
        this.meshPickShader.init();
        this.pickVao = new VertexArrayObject();
        this.pickPositionVbo = new VertexBufferObject();
        this.pickIdVbo = new VertexBufferObject();
    }

    /**
     * Compile {@code mesh} and upload it as static GPU geometry, replacing
     * any previous mesh. Clears tag partitioning and the per-vertex scalar.
     * Passing {@code null} clears all GPU state without uploading.
     *
     * @param mesh source mesh, or {@code null} to clear
     */
    public void upload(MeshTopology mesh) {
        clearTexturedDraw();
        if (mesh == null) {
            compiledMesh = null;
            uploadEdgeData(null);
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
     * Upload a whole bundle: the mesh as {@link #upload(MeshTopology)} does, plus a second set of
     * buffers for the textured draw and the base-color texture, when the bundle carries both. An
     * absent slot leaves {@link ShaderMode#TEXTURED} in its solid-colour fallback.
     *
     * @param bundle source bundle, or {@code null} to clear
     */
    public void uploadBundle(GeometryBundle bundle) {
        clearTexturedDraw();
        if (bundle == null) {
            upload(null);
            return;
        }
        compiledMesh = compileSurface(bundle.mesh());
        tagRanges = List.of();
        scalarUploaded = false;
        uploadCompiledMesh(Platforms.gl().STATIC_DRAW());
        uploadEdgeData(bundle.mesh());

        // One base-color texture per material, plus a trailing null entry for the faces that name
        // no material.
        MaterialData[] materials = MaterialSet.materialsOf(bundle);
        materialTextures = new Texture[materials.length + 1];
        for (int index = 0; index < materials.length; index++) {
            MaterialData material = materials[index];
            if (!material.hasBaseColorTexture()) {
                continue;
            }
            Texture texture = new Texture(BASE_COLOR_TEXTURE_NAME, new DecodedImage(
                    material.baseColorRgba, material.baseColorWidth, material.baseColorHeight));
            texture.initGL();
            if (!texture.initialized) {
                Platforms.log("[mesh] base-colour texture upload failed");
                continue;
            }
            materialTextures[index] = texture;
        }
        uploadTexturedGeometry(bundle);
    }

    /**
     * Build the textured draw's own vertex buffers, faces grouped into one index range per
     * material. UVs are per corner, so {@link CornerUvSplit} gives every distinct corner UV its own
     * GPU vertex, uploaded beside the welded buffers the other modes keep drawing from.
     *
     * @param bundle source bundle, whose mesh must be triangles for per-corner UVs to cover it
     */
    private void uploadTexturedGeometry(GeometryBundle bundle) {
        CornerUvField uv = CornerUvField.of(bundle);
        if (uv == null) {
            return;
        }
        // A boolean hands over a HalfEdgeMesh; the split needs dense arrays either way.
        ArrayMesh mesh = bundle.mesh() instanceof ArrayMesh dense
                && dense.getVertsPerFace() == CornerUvField.CORNERS_PER_FACE
                ? dense : SemanticPatchDecomposer.toArrayMesh(bundle.mesh());
        if (uv.faceCount() != mesh.faceCount()) {
            return;
        }
        // Group the faces by the material they name, so each material's faces form one contiguous
        // index range. Faces naming no material land in the trailing range, drawn in solid colour.
        int faceCount = mesh.faceCount();
        int rangeCount = Math.max(materialTextures.length, 1);
        int noMaterial = rangeCount - 1;
        IntField faceMaterial = MaterialSet.faceMaterialOf(bundle);
        if (faceMaterial != null && faceMaterial.length() != faceCount) {
            faceMaterial = null;
        }
        materialRangeStart = new int[rangeCount];
        materialRangeCount = new int[rangeCount];
        int[] rangeOf = new int[faceCount];
        for (int face = 0; face < faceCount; face++) {
            int material = faceMaterial == null ? 0 : faceMaterial.get(face);
            rangeOf[face] = material < 0 || material >= noMaterial ? noMaterial : material;
            materialRangeCount[rangeOf[face]]++;
        }
        int start = 0;
        for (int range = 0; range < rangeCount; range++) {
            materialRangeStart[range] = start;
            start += materialRangeCount[range] * CornerUvField.CORNERS_PER_FACE;
        }
        int[] fill = new int[rangeCount];
        int[] faceOrder = new int[faceCount];
        for (int face = 0; face < faceCount; face++) {
            int range = rangeOf[face];
            faceOrder[materialRangeStart[range] / CornerUvField.CORNERS_PER_FACE
                    + fill[range]++] = face;
        }
        for (int range = 0; range < rangeCount; range++) {
            materialRangeCount[range] *= CornerUvField.CORNERS_PER_FACE;
        }

        float[] splitUv = new float[CornerUvSplit.maxSplitUvLength(mesh)];
        ArrayMesh split = CornerUvSplit.split(mesh, uv, faceOrder, splitUv);
        float[] positions = split.copyPositions();
        float[] normals = split.copyNormals();
        int[] indices = split.copyFaceIndices();
        float[] interleaved = new float[split.vertexCount() * VERTEX_STRIDE];
        for (int vertex = 0; vertex < split.vertexCount(); vertex++) {
            int target = vertex * VERTEX_STRIDE;
            int source = vertex * VEC3_SIZE;
            interleaved[target] = positions[source];
            interleaved[target + 1] = positions[source + 1];
            interleaved[target + 2] = positions[source + 2];
            interleaved[target + VEC3_SIZE] = normals[source];
            interleaved[target + VEC3_SIZE + 1] = normals[source + 1];
            interleaved[target + VEC3_SIZE + 2] = normals[source + 2];
            interleaved[target + UV_OFFSET] = splitUv[vertex * CornerUvSplit.COMPONENTS_PER_VERTEX];
            interleaved[target + 7] = splitUv[vertex * CornerUvSplit.COMPONENTS_PER_VERTEX + 1];
        }

        GL gl = Platforms.gl();
        texturedVao.bind();
        texturedVbo.bind(gl.ARRAY_BUFFER());
        texturedVbo.uploadData(gl.ARRAY_BUFFER(), interleaved, gl.STATIC_DRAW());
        gl.vertexAttribPointer(0, VEC3_SIZE, gl.FLOAT(), false, VERTEX_STRIDE * Float.BYTES, 0);
        gl.enableVertexAttribArray(0);
        gl.vertexAttribPointer(1, VEC3_SIZE, gl.FLOAT(), false, VERTEX_STRIDE * Float.BYTES, VEC3_SIZE * Float.BYTES);
        gl.enableVertexAttribArray(1);
        gl.vertexAttribPointer(2, 2, gl.FLOAT(), false, VERTEX_STRIDE * Float.BYTES, UV_OFFSET * Float.BYTES);
        gl.enableVertexAttribArray(2);
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), texturedEbo);
        IntBuffer uploadBuffer = BufferUtils.createIntBuffer(indices.length);
        uploadBuffer.put(indices).flip();
        gl.bufferData(gl.ELEMENT_ARRAY_BUFFER(), uploadBuffer, gl.STATIC_DRAW());
        texturedIndexCount = indices.length;
        meshVao.bind();
    }

    /** Release the base-color textures and the split geometry the textured draw uses. */
    public void clearTexturedDraw() {
        for (Texture texture : materialTextures) {
            if (texture != null) {
                texture.delete();
            }
        }
        materialTextures = new Texture[0];
        materialRangeStart = new int[0];
        materialRangeCount = new int[0];
        texturedIndexCount = 0;
    }

    /**
     * Whether a draw in {@code mode} samples the base-color texture. The one place the TEXTURED
     * fallback is decided, so it can be checked without a GPU.
     *
     * @param mode requested shading mode
     * @param texturedDrawReady whether both the texture and the split geometry are resident
     * @return true only for {@link ShaderMode#TEXTURED} with something to sample
     */
    public static boolean samplesTexture(ShaderMode mode, boolean texturedDrawReady) {
        return mode == ShaderMode.TEXTURED && texturedDrawReady;
    }

    /**
     * Whether the textured draw is ready: a base-color texture and the UV-split geometry both
     * uploaded.
     *
     * @return true once {@link #uploadBundle(GeometryBundle)} found a material and a UV field
     */
    public boolean hasTexturedDraw() {
        if (texturedIndexCount == 0) {
            return false;
        }
        for (Texture texture : materialTextures) {
            if (texture != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Like {@link #upload(MeshTopology)} but flagged as dynamic GPU usage,
     * for meshes whose geometry changes frame-to-frame.
     *
     * @param mesh source mesh, or {@code null} to clear
     */
    public void reupload(MeshTopology mesh) {
        clearTexturedDraw();
        if (mesh == null) {
            compiledMesh = null;
            uploadEdgeData(null);
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
        float distance = Math.max(FRAME_DISTANCE_FLOOR, compiledMesh.radius * FRAME_DISTANCE_MULTIPLIER);
        camera.position.set(compiledMesh.center.x, compiledMesh.center.y, compiledMesh.center.z + distance);
        camera.target.set(compiledMesh.center);
        camera.fov = DEFAULT_FOV;
        camera.updateViewFirstPerson();
    }

    /**
     * Near plane for a camera, scaled to its distance from its target.
     *
     * <p>A fixed near plane leaves the model's whole depth extent in a sliver of the depth range,
     * starving it of precision when zoomed out.
     *
     * @param camera active camera
     * @return the near plane distance
     */
    protected float nearPlaneFor(Camera3D camera) {
        float distance = camera.position.distance(camera.target);
        return Math.max(NEAR_PLANE_FLOOR, distance * NEAR_PLANE_DISTANCE_FRACTION);
    }

    /**
     * Far plane for a camera, placed just beyond the model rather than at a fixed distance.
     *
     * @param camera active camera
     * @param extent the model's full extent, such as its bounding-box diagonal
     * @return the far plane distance
     */
    protected float farPlaneFor(Camera3D camera, float extent) {
        float distance = camera.position.distance(camera.target);
        return distance + Math.max(extent, NEAR_PLANE_FLOOR) * FAR_PLANE_EXTENT_MUL;
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

        updateProjection(camera);

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

        boolean sampleTexture = samplesTexture(shaderMode, hasTexturedDraw());
        if (shaderMode == ShaderMode.LAMBERT || shaderMode == ShaderMode.STAGES
                || shaderMode == ShaderMode.TEXTURED) {
            // The light points along the view direction (into the scene; the shader uses -lightDir),
            // read from the view matrix so it always matches what is drawn.
            camera.view.positiveZ(lightDir).negate();
            active.setVec3("lightDir", lightDir);
            active.setBool(USE_TEXTURE_UNIFORM, sampleTexture);
            active.setVec3("emissiveColor", emissiveColor);
            active.setFloat("emissiveStrength", EMISSIVE_STRENGTH);
            active.setFloat("rimStrength", RIM_STRENGTH);
        }
        if (sampleTexture) {
            // The textured draw has its own UV-split vertices, so it ignores the welded mesh's tag
            // ranges: the texture is what colours the surface. One draw per material, since each
            // carries its own image; faces naming no material fall back to the solid colour.
            texturedVao.bind();
            Platforms.gl().bindBuffer(Platforms.gl().ELEMENT_ARRAY_BUFFER(), texturedEbo);
            for (int range = 0; range < materialRangeCount.length; range++) {
                if (materialRangeCount[range] == 0) {
                    continue;
                }
                Texture texture = materialTextures[range];
                active.setBool(USE_TEXTURE_UNIFORM, texture != null);
                if (texture != null) {
                    active.setTexture("albedoTex", texture, Platforms.gl().TEXTURE0(), 0);
                }
                Platforms.gl().drawElements(
                        Platforms.gl().TRIANGLES(),
                        materialRangeCount[range],
                        Platforms.gl().UNSIGNED_INT(),
                        materialRangeStart[range] * Integer.BYTES);
            }
            if (wireframe) {
                renderEdges(camera);
            }
            return;
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
            // Per-tag draws: set solidColor and any tag offset per range, issue glDrawElements
            // with a byte offset into the EBO.
            for (TagRange range : tagRanges) {
                active.setVec4(SOLIDCOLOR, range.color);
                Vector3f offset = tagOffsets.get(range.tagName);
                active.setMat4(MODEL, offset == null ? modelMatrix
                        : tagModelMatrix.set(modelMatrix).translate(offset));
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
     * Rebuild {@link #projectionMatrix} for a camera, perspective or orthographic per
     * {@link #isOrthographic()}, with the near and far planes the model's size asks for.
     *
     * @param camera active camera
     */
    private void updateProjection(Camera3D camera) {
        float aspect = camera.aspectRatio();
        float near = nearPlaneFor(camera);
        float far = farPlaneFor(camera, compiledMesh == null ? 1f : compiledMesh.radius * 2f);
        if (orthographic) {
            float dist = camera.position.distance(camera.target);
            float halfH = dist * (float) Math.tan(Math.toRadians(camera.fov / 2.0));
            float halfW = halfH * aspect;
            projectionMatrix.identity().ortho(-halfW, halfW, -halfH, halfH, near, far);
        } else {
            projectionMatrix.identity().perspective(
                    (float) Math.toRadians((float) camera.fov), aspect, near, far);
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
        edgeLines.delete();
        featureEdgeLines.delete();
        if (scalarVbo != 0) {
            Platforms.gl().deleteBuffers(scalarVbo);
            scalarVbo = 0;
        }
        clearTexturedDraw();
        if (texturedEbo != 0) {
            Platforms.gl().deleteBuffers(texturedEbo);
            texturedEbo = 0;
        }
        texturedVbo.delete();
        texturedVao.delete();
        pickPositionVbo.delete();
        pickIdVbo.delete();
        pickVao.delete();
        pickVertexCount = 0;
        meshVbo.delete();
        meshVao.delete();
    }

    /**
     * Take {@code mesh} as the surface the overlay lines lie on, and upload its edges as surface
     * lines for the wireframe; {@code null} drops both.
     *
     * @param mesh the mesh just uploaded, or {@code null}
     */
    private void uploadEdgeData(MeshTopology mesh) {
        surfaceMesh = mesh;
        surfaceFaceLocator = null;
        if (mesh == null) {
            edgeLines.delete();
            return;
        }
        LineSet lines = new LineSet(mesh.edgeCount());
        for (int edge = 0; edge < mesh.edgeCount(); edge++) {
            lines.edge(mesh, mesh.edgeIdAt(edge));
        }
        edgeLines.upload(LineSet.LAYOUT, lines.vertices, null);
    }

    /**
     * The locator for free-form lines on {@link #surfaceMesh}, built on first use after an upload.
     *
     * @return the locator, or {@code null} when no mesh is uploaded
     */
    public SurfaceFaceLocator surfaceFaceLocator() {
        if (surfaceFaceLocator == null && surfaceMesh != null) {
            surfaceFaceLocator = new SurfaceFaceLocator(surfaceMesh);
        }
        return surfaceFaceLocator;
    }

    /**
     * Upload a feature-edge overlay to be drawn in STAGES or CREST_VS_BOUNDARY modes. Categories
     * draw in the order given, so later ones overpaint earlier ones on shared edges. The palette is
     * the {@code FEATURE_EDGE_*} constants of {@link Color}.
     *
     * @param categories ordered list of overlay categories, each pairing a
     *                   color with the edge keys to draw in that color;
     *                   {@code null} or empty clears the overlay
     */
    public void setFeatureEdgeOverlay(List<FeatureEdgeCategory> categories) {
        if (categories == null || categories.isEmpty() || surfaceMesh == null) {
            featureEdgeRanges = List.of();
            return;
        }
        int totalEdges = 0;
        for (FeatureEdgeCategory cat : categories) {
            totalEdges += cat.edgeKeys().size();
        }
        if (totalEdges == 0) {
            featureEdgeRanges = List.of();
            return;
        }
        LineSet lines = new LineSet(totalEdges);
        List<FeatureEdgeRange> ranges = new ArrayList<>(categories.size());
        for (FeatureEdgeCategory cat : categories) {
            int start = lines.vertexCount();
            for (long key : cat.edgeKeys()) {
                lines.vertexStep(surfaceMesh, EdgeKey.minVertex(key), EdgeKey.maxVertex(key));
            }
            int count = lines.vertexCount() - start;
            if (count > 0) {
                ranges.add(new FeatureEdgeRange(cat.color().toVector4f(), start, count));
            }
        }
        featureEdgeLines.upload(LineSet.LAYOUT, lines.vertices, null);
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
        gl.vertexAttribPointer(SCALAR_ATTRIB_LOCATION, 1, gl.FLOAT(), false, Float.BYTES, 0);
        gl.enableVertexAttribArray(SCALAR_ATTRIB_LOCATION);
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

    /**
     * Build the face-id draw, each shown face's corners coloured by its index, so one pixel of
     * {@link #faceIndexAtPixel} names the face under the cursor; a pick sees through hidden faces.
     *
     * @param mesh               surface to pick on, or {@code null} to release the copy
     * @param hiddenByActiveFace faces to leave out, by dense index; a mask of another length than
     *                           the face count leaves out none
     */
    public void uploadFacePickBuffer(MeshTopology mesh, boolean[] hiddenByActiveFace) {
        pickVertexCount = 0;
        pickFaceCount = 0;
        if (mesh == null || mesh.faceCount() == 0) {
            GL empty = Platforms.gl();
            pickVao.bind();
            pickPositionVbo.bind(empty.ARRAY_BUFFER());
            pickPositionVbo.uploadData(empty.ARRAY_BUFFER(), new float[0], empty.STATIC_DRAW());
            pickIdVbo.bind(empty.ARRAY_BUFFER());
            pickIdVbo.uploadData(empty.ARRAY_BUFFER(), new float[0], empty.STATIC_DRAW());
            meshVao.bind();
            return;
        }
        boolean[] hidden = hiddenByActiveFace.length == mesh.faceCount() ? hiddenByActiveFace
                : new boolean[mesh.faceCount()];
        int triangles = 0;
        for (int index = 0; index < mesh.faceCount(); index++) {
            triangles += hidden[index] ? 0
                    : Math.max(0, mesh.faceVertexCount(mesh.faceIdAt(index)) - 2);
        }
        float[] positions = new float[COORDINATES_PER_VERTEX * 3 * triangles];
        float[] ids = new float[COORDINATES_PER_VERTEX * 3 * triangles];
        pickFaceCount = mesh.faceCount();
        Vector3f corner = new Vector3f();
        int vertex = 0;
        for (int index = 0; index < mesh.faceCount(); index++) {
            if (hidden[index]) {
                continue;
            }
            int faceId = mesh.faceIdAt(index);
            int code = index + 1;
            float red = ((code >> (2 * PICK_CHANNEL_BITS)) & PICK_CHANNEL_MAX) / CHANNEL_NORMALIZE;
            float green = ((code >> PICK_CHANNEL_BITS) & PICK_CHANNEL_MAX) / CHANNEL_NORMALIZE;
            float blue = (code & PICK_CHANNEL_MAX) / CHANNEL_NORMALIZE;
            for (int fan = 2; fan < mesh.faceVertexCount(faceId); fan++) {
                for (int step = 0; step < 3; step++) {
                    int slot = step == 0 ? 0 : fan - 2 + step;
                    mesh.vertexPosition(mesh.faceVertexAt(faceId, slot), corner);
                    positions[COORDINATES_PER_VERTEX * vertex] = corner.x;
                    positions[COORDINATES_PER_VERTEX * vertex + 1] = corner.y;
                    positions[COORDINATES_PER_VERTEX * vertex + 2] = corner.z;
                    ids[COORDINATES_PER_VERTEX * vertex] = red;
                    ids[COORDINATES_PER_VERTEX * vertex + 1] = green;
                    ids[COORDINATES_PER_VERTEX * vertex + 2] = blue;
                    vertex++;
                }
            }
        }
        GL gl = Platforms.gl();
        pickVao.bind();
        pickPositionVbo.bind(gl.ARRAY_BUFFER());
        pickPositionVbo.uploadData(gl.ARRAY_BUFFER(), positions, gl.STATIC_DRAW());
        gl.vertexAttribPointer(0, COORDINATES_PER_VERTEX, gl.FLOAT(), false,
                COORDINATES_PER_VERTEX * Float.BYTES, 0);
        gl.enableVertexAttribArray(0);
        pickIdVbo.bind(gl.ARRAY_BUFFER());
        pickIdVbo.uploadData(gl.ARRAY_BUFFER(), ids, gl.STATIC_DRAW());
        gl.vertexAttribPointer(1, COORDINATES_PER_VERTEX, gl.FLOAT(), false,
                COORDINATES_PER_VERTEX * Float.BYTES, 0);
        gl.enableVertexAttribArray(1);
        meshVao.bind();
        pickVertexCount = vertex;
    }

    /**
     * Whether a face-id draw is uploaded and can answer a pick.
     *
     * @return true when {@link #uploadFacePickBuffer} has built a buffer for the live mesh, even
     *         one whose faces are all hidden
     */
    public boolean facePickReady() {
        return pickFaceCount > 0;
    }

    /**
     * The face under a framebuffer pixel: renders the id pass into the back buffer, reads that
     * one pixel, then restores the cleared frame the scene is about to draw into.
     *
     * @param camera        active camera
     * @param framebufferX  pixel x, measured from the left
     * @param framebufferY  pixel y, measured from the top
     * @return the face's active index, or {@code -1} when the pixel shows no surface
     */
    public int faceIndexAtPixel(Camera3D camera, int framebufferX, int framebufferY) {
        if (pickVertexCount == 0 || meshPickShader.ID < 0) {
            return -1;
        }
        int height = Platforms.get().getFrameBufferHeight();
        int width = Platforms.get().getFrameBufferWidth();
        if (framebufferX < 0 || framebufferY < 0 || framebufferX >= width
                || framebufferY >= height) {
            return -1;
        }
        GL gl = Platforms.gl();
        updateProjection(camera);
        // The id pass rasterizes one pixel: the viewport is that pixel and the projection is
        // scaled and shifted so the cursor's direction fills it, which keeps the pass off the
        // fragment cost of a full-screen draw of a scan-scale mesh.
        int bottomY = height - 1 - framebufferY;
        float cursorNdcX = 2f * (framebufferX + 0.5f) / width - 1f;
        float cursorNdcY = 2f * (bottomY + 0.5f) / height - 1f;
        Matrix4f pickProjection = new Matrix4f()
                .scaling(width, height, 1f)
                .translate(-cursorNdcX, -cursorNdcY, 0f)
                .mul(projectionMatrix);
        gl.clearColor(0f, 0f, 0f, 1f);
        gl.clear(gl.COLOR_BUFFER_BIT() | gl.DEPTH_BUFFER_BIT());
        gl.viewport(framebufferX, bottomY, 1, 1);
        meshPickShader.use();
        meshPickShader.setMat4(MODEL, modelMatrix);
        meshPickShader.setMat4(VIEW, camera.view);
        meshPickShader.setMat4(PROJECTION, pickProjection);
        pickVao.bind();
        gl.drawArrays(gl.TRIANGLES(), 0, pickVertexCount);
        int[] pixel = gl.readPixels(framebufferX, bottomY, 1, 1, gl.RGBA(),
                gl.UNSIGNED_BYTE(), 0);
        gl.viewport(0, 0, width, height);
        gl.clearColor(Color.DARK_GRAY);
        gl.clear(gl.COLOR_BUFFER_BIT() | gl.DEPTH_BUFFER_BIT());
        meshVao.bind();
        int code = pixel.length == 0 ? 0 : pixel[0] & PICK_ID_MASK;
        int faceIndex = code - 1;
        if (faceIndex < 0 || faceIndex >= pickFaceCount) {
            return -1;
        }
        return faceIndex;
    }

    /**
     * The world-space ray a framebuffer pixel looks along, from the near plane to the far plane,
     * for the exact barycentric hit inside the face the id pass named.
     *
     * @param camera       active camera
     * @param framebufferX pixel x, measured from the left, a whole number the pixel's centre
     * @param framebufferY pixel y, measured from the top, a whole number the pixel's centre
     * @param origin       receives the ray origin, packed xyz
     * @param direction    receives the ray direction, packed xyz, not normalised
     * @return true when the framebuffer has a size
     */
    public boolean rayThroughPixel(Camera3D camera, float framebufferX, float framebufferY,
            float[] origin, float[] direction) {
        int width = Platforms.get().getFrameBufferWidth();
        int height = Platforms.get().getFrameBufferHeight();
        if (width <= 0 || height <= 0) {
            return false;
        }
        updateProjection(camera);
        Matrix4f inverse = new Matrix4f(projectionMatrix).mul(camera.view).mul(modelMatrix)
                .invert();
        float normalisedX = 2f * (framebufferX + 0.5f) / width - 1f;
        float normalisedY = 1f - 2f * (framebufferY + 0.5f) / height;
        Vector4f near = inverse.transform(new Vector4f(normalisedX, normalisedY, -1f, 1f));
        Vector4f far = inverse.transform(new Vector4f(normalisedX, normalisedY, 1f, 1f));
        if (near.w == 0f || far.w == 0f) {
            return false;
        }
        origin[0] = near.x / near.w;
        origin[1] = near.y / near.w;
        origin[2] = near.z / near.w;
        direction[0] = far.x / far.w - origin[0];
        direction[1] = far.y / far.w - origin[1];
        direction[2] = far.z / far.w - origin[2];
        return true;
    }

    /**
     * Project a world point through the model, view and projection the surface draws with, so an
     * overlay can place a 2D mark where a 3D point landed and test it against the depth buffer.
     *
     * @param camera    active camera
     * @param x         world x
     * @param y         world y
     * @param z         world z
     * @param pixelDest receives framebuffer x from the left, y from the top, and the window-space
     *                  depth in {@code [0, 1]} the point would write
     * @return true when the point projects in front of the camera
     */
    public boolean projectToPixels(Camera3D camera, float x, float y, float z, float[] pixelDest) {
        int width = Platforms.get().getFrameBufferWidth();
        int height = Platforms.get().getFrameBufferHeight();
        if (width <= 0 || height <= 0) {
            return false;
        }
        updateProjection(camera);
        projectedPoint.set(x, y, z, 1f);
        modelMatrix.transform(projectedPoint);
        camera.view.transform(projectedPoint);
        projectionMatrix.transform(projectedPoint);
        if (projectedPoint.w <= 0f) {
            return false;
        }
        pixelDest[0] = (projectedPoint.x / projectedPoint.w * 0.5f + 0.5f) * width;
        pixelDest[1] = (0.5f - projectedPoint.y / projectedPoint.w * 0.5f) * height;
        pixelDest[2] = projectedPoint.z / projectedPoint.w * 0.5f + 0.5f;
        return true;
    }

    private void renderFeatureEdgeOverlay(Camera3D camera) {
        if (featureEdgeRanges.isEmpty() || lineShader.ID < 0 || featureEdgeLines.vao == 0) {
            return;
        }
        lineShader.use(camera.view, projectionMatrix, modelMatrix);
        GL gl = Platforms.gl();
        gl.bindVertexArray(featureEdgeLines.vao);
        gl.lineWidth(FEATURE_EDGE_LINE_WIDTH);
        for (FeatureEdgeRange r : featureEdgeRanges) {
            lineShader.setVec4(SOLIDCOLOR, r.color());
            gl.drawArrays(gl.LINES(), r.indexStart(), r.indexCount());
        }
        meshVao.bind();
    }

    /**
     * Wireframe overlay: every mesh edge, dropped where both faces beside it turn away from the
     * camera and otherwise hidden only by nearer surface. Called by {@link #render(Camera3D)}
     * when {@link #isWireframe()} is set.
     *
     * @param camera 3D camera supplying the view matrix
     */
    public void renderEdges(Camera3D camera) {
        if (lineShader.ID < 0 || edgeLines.vao == 0) {
            return;
        }
        lineShader.use(camera.view, projectionMatrix, modelMatrix);
        lineShader.setVec4(SOLIDCOLOR, edgeColor);
        GL gl = Platforms.gl();
        gl.bindVertexArray(edgeLines.vao);
        gl.lineWidth(EDGE_LINE_WIDTH);
        gl.drawArrays(gl.LINES(), 0, edgeLines.vertexCount);
        meshVao.bind();
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
     * Tag the uploaded mesh by face: each face draws in its group's colour, one draw call per
     * group under the group's tag name, the triangles bucketed in one pass; a face in a negative
     * group is not drawn. Call after {@link #upload}.
     *
     * @param mesh              the uploaded surface, whose face order the triangles follow
     * @param groupByActiveFace group of each face, by dense face index, in {@code [0, groups)},
     *                          or negative for a hidden face
     * @param colourByGroup     colour of each group
     * @param tagByGroup        tag name of each group's draw range
     * @throws IllegalArgumentException when {@code mesh} is not the surface uploaded
     */
    public void setFaceGroups(MeshTopology mesh, int[] groupByActiveFace,
            Vector4f[] colourByGroup, String[] tagByGroup) {
        if (compiledMesh == null || compiledMesh.indices.length == 0) {
            tagRanges = List.of();
            return;
        }
        int[] originalIndices = compiledMesh.indices;
        int faceCount = mesh.faceCount();
        int[] triangleStart = new int[faceCount + 1];
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            triangleStart[activeFace + 1] = triangleStart[activeFace]
                    + Math.max(0, mesh.faceVertexCount(mesh.faceIdAt(activeFace)) - 2);
        }
        if (3 * triangleStart[faceCount] != originalIndices.length) {
            throw new IllegalArgumentException(faceCount + " faces make " + triangleStart[faceCount]
                    + " triangles, but the uploaded mesh has " + originalIndices.length / 3);
        }
        int groups = colourByGroup.length;
        int[] groupIndexStart = new int[groups + 1];
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            if (groupByActiveFace[activeFace] >= 0) {
                groupIndexStart[groupByActiveFace[activeFace] + 1] +=
                        3 * (triangleStart[activeFace + 1] - triangleStart[activeFace]);
            }
        }
        for (int group = 0; group < groups; group++) {
            groupIndexStart[group + 1] += groupIndexStart[group];
        }
        int[] cursor = Arrays.copyOf(groupIndexStart, groups);
        int[] newIndices = new int[groupIndexStart[groups]];
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            int from = 3 * triangleStart[activeFace];
            int length = 3 * triangleStart[activeFace + 1] - from;
            int group = groupByActiveFace[activeFace];
            if (group < 0) {
                continue;
            }
            System.arraycopy(originalIndices, from, newIndices, cursor[group], length);
            cursor[group] += length;
        }
        List<TagRange> ranges = new ArrayList<>();
        for (int group = 0; group < groups; group++) {
            int count = groupIndexStart[group + 1] - groupIndexStart[group];
            // With every face hidden the one empty range left keeps render from taking the mesh
            // for untagged and drawing all of it.
            if (count > 0 || group == groups - 1 && ranges.isEmpty()) {
                ranges.add(new TagRange(tagByGroup[group], new Vector4f(colourByGroup[group]),
                        groupIndexStart[group], count));
            }
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
     * {@code PatchColors.uniquePatchColor(pid)} for a tag named
     * {@code "patch_<pid>"}, so a decomposer tag renders in the colour the
     * decomposition JSON reports as its {@code flat_color}.
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
            h = (float) ((pid * GOLDEN_RATIO_CONJUGATE) % 1.0);
        } else {
            int hash = stableHash(tagName == null ? "" : tagName);
            h = ((hash & HASH_MASK) % HUE_QUANTIZATION) / 10000f;
        }
        float[] rgb = hslToRgb(h, TAG_SATURATION, TAG_LIGHTNESS);
        return new Vector4f(rgb[0], rgb[1], rgb[2], 1f);
    }

    private static int stableHash(String s) {
        int h = 0;
        for (int i = 0; i < s.length(); i++) {
            h = HASH_PRIME * h + s.charAt(i);
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
        gl.vertexAttribPointer(0, VEC3_SIZE, gl.FLOAT(), false, VERTEX_STRIDE * Float.BYTES, 0);
        gl.enableVertexAttribArray(0);
        gl.vertexAttribPointer(1, VEC3_SIZE, gl.FLOAT(), false, VERTEX_STRIDE * Float.BYTES, VEC3_SIZE * Float.BYTES);
        gl.enableVertexAttribArray(1);
        gl.vertexAttribPointer(2, 2, gl.FLOAT(), false, VERTEX_STRIDE * Float.BYTES, UV_OFFSET * Float.BYTES);
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
     *   <li>{@link #FLAT} — unlit, each tag's exact color.</li>
     *   <li>{@link #STAGES} — LAMBERT plus feature edges.</li>
     *   <li>{@link #CREST_VS_BOUNDARY} — FLAT plus boundaries.</li>
     *   <li>{@link #SCALAR} — ramp over {@link #setPerVertexScalar(float[])}.</li>
     *   <li>{@link #MSC} — Morse-Smale arcs.</li>
     *   <li>{@link #TEXTURED} — LAMBERT sampling the base-color texture.</li>
     * </ul>
     */
    public enum ShaderMode { LAMBERT, FLAT, STAGES, CREST_VS_BOUNDARY, SCALAR, MSC, TEXTURED }

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
    public record FeatureEdgeCategory(Color color, Collection<Long> edgeKeys) {}

    /**
     * Centre and reach of flat-xyz point clouds: fills {@code centroidDest} with the mean point and
     * returns the farthest point's distance from it, 0 for empty clouds.
     *
     * @param clouds flat-xyz position arrays measured together
     * @param centroidDest scratch vector filled with the centroid
     * @return radius around the centroid containing every point
     */
    public static float cloudRadius(List<float[]> clouds, Vector3f centroidDest) {
        centroidDest.set(0f, 0f, 0f);
        int pointCount = 0;
        for (float[] cloud : clouds) {
            for (int base = 0; base < cloud.length; base += 3) {
                centroidDest.add(cloud[base], cloud[base + 1], cloud[base + 2]);
                pointCount++;
            }
        }
        centroidDest.div(Math.max(1, pointCount));
        float radius = 0f;
        for (float[] cloud : clouds) {
            for (int base = 0; base < cloud.length; base += 3) {
                float dx = cloud[base] - centroidDest.x;
                float dy = cloud[base + 1] - centroidDest.y;
                float dz = cloud[base + 2] - centroidDest.z;
                radius = Math.max(radius, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
        }
        return radius;
    }
}
