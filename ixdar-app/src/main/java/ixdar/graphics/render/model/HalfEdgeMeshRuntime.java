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

import ixdar.geometry.mesh.data.CornerUvField;
import ixdar.geometry.mesh.data.CornerUvSplit;
import ixdar.geometry.mesh.data.EdgeKey;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MaterialData;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.Texture;
import ixdar.graphics.render.color.Color;
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

    /** Near plane as a fraction of the camera's distance to its target. */
    public static final float NEAR_PLANE_DISTANCE_FRACTION = 0.01f;

    /** Smallest near plane, for a camera sitting on its target. */
    public static final float NEAR_PLANE_FLOOR = 1e-4f;

    /** Far plane margin beyond the target, as a multiple of the model's extent. */
    public static final float FAR_PLANE_EXTENT_MUL = 3f;
    public static final float NUM_0 = 0f;
    public static final float NUM_0_001 = 0.001f;
    public static final float NUM_0_08 = 0.08f;
    public static final float NUM_0_16 = 0.16f;
    public static final int NUM_16 = 16;
    public static final int NUM_0xf = 0xff;
    public static final float NUM_255 = 255f;
    public static final int NUM_8 = 8;
    public static final int NUM_7 = 7;
    public static final int NUM_3 = 3;

    /** Descriptive name the base-color {@link Texture} carries; nothing looks it up. */
    public static final String BASE_COLOR_TEXTURE_NAME = "mesh_base_color";
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

    /** Coordinates per vertex in the face pick buffer. */
    public static final int COORDINATES_PER_VERTEX = 3;

    /** Highest value a face-id colour channel can carry. */
    public static final int PICK_CHANNEL_MAX = 255;

    /** Bits a face-id colour channel is worth. */
    public static final int PICK_CHANNEL_BITS = 8;

    /** Mask of the three colour channels a face id is split across. */
    public static final int PICK_ID_MASK = 0xFFFFFF;

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
    private int featureEdgeEbo;
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
    private Texture baseColorTexture;
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
     * Build the runtime: allocate the three mesh shader programs (lit,
     * unlit, scalar), the shared VAO/VBO, and the EBO names for triangles,
     * wireframe edges, feature-edge overlays, and the per-vertex scalar
     * attribute. No mesh is uploaded yet — call {@link #upload(MeshTopology)}.
     */
    public HalfEdgeMeshRuntime() {
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
        uploadBaseColorTexture(MaterialData.of(bundle));
        uploadTexturedGeometry(bundle);
    }

    /**
     * Build the textured draw's own vertex buffers. UVs are per corner, so {@link CornerUvSplit}
     * gives every distinct corner UV its own GPU vertex, uploaded beside the welded buffers the
     * other modes keep drawing from.
     *
     * @param bundle source bundle, whose mesh must be a triangle {@link ArrayMesh} to be split
     */
    private void uploadTexturedGeometry(GeometryBundle bundle) {
        if (!(bundle.mesh() instanceof ArrayMesh mesh)
                || mesh.getVertsPerFace() != CornerUvField.CORNERS_PER_FACE
                || !(bundle.slots().get(CornerUvField.SLOT) instanceof CornerUvField uv)
                || uv.faceCount() != mesh.faceCount()) {
            return;
        }
        float[] splitUv = new float[CornerUvSplit.maxSplitUvLength(mesh)];
        ArrayMesh split = CornerUvSplit.split(mesh, uv, splitUv);
        float[] positions = split.copyPositions();
        float[] normals = split.copyNormals();
        int[] indices = split.copyFaceIndices();
        float[] interleaved = new float[split.vertexCount() * NUM_8];
        for (int vertex = 0; vertex < split.vertexCount(); vertex++) {
            int target = vertex * NUM_8;
            int source = vertex * NUM_3;
            interleaved[target] = positions[source];
            interleaved[target + 1] = positions[source + 1];
            interleaved[target + 2] = positions[source + 2];
            interleaved[target + NUM_3] = normals[source];
            interleaved[target + NUM_3 + 1] = normals[source + 1];
            interleaved[target + NUM_3 + 2] = normals[source + 2];
            interleaved[target + NUM_6_2] = splitUv[vertex * CornerUvSplit.COMPONENTS_PER_VERTEX];
            interleaved[target + NUM_7] = splitUv[vertex * CornerUvSplit.COMPONENTS_PER_VERTEX + 1];
        }

        GL gl = Platforms.gl();
        texturedVao.bind();
        texturedVbo.bind(gl.ARRAY_BUFFER());
        texturedVbo.uploadData(gl.ARRAY_BUFFER(), interleaved, gl.STATIC_DRAW());
        gl.vertexAttribPointer(0, NUM_3, gl.FLOAT(), false, NUM_8 * Float.BYTES, 0);
        gl.enableVertexAttribArray(0);
        gl.vertexAttribPointer(1, NUM_3, gl.FLOAT(), false, NUM_8 * Float.BYTES, NUM_3 * Float.BYTES);
        gl.enableVertexAttribArray(1);
        gl.vertexAttribPointer(2, 2, gl.FLOAT(), false, NUM_8 * Float.BYTES, NUM_6_2 * Float.BYTES);
        gl.enableVertexAttribArray(2);
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), texturedEbo);
        IntBuffer uploadBuffer = BufferUtils.createIntBuffer(indices.length);
        uploadBuffer.put(indices).flip();
        gl.bufferData(gl.ELEMENT_ARRAY_BUFFER(), uploadBuffer, gl.STATIC_DRAW());
        texturedIndexCount = indices.length;
        meshVao.bind();
    }

    /**
     * Upload the material's base-color image as the texture {@link ShaderMode#TEXTURED} samples.
     *
     * @param material bundle material, or {@code null} when the bundle carries none
     */
    private void uploadBaseColorTexture(MaterialData material) {
        if (material == null || !material.hasBaseColorTexture()) {
            return;
        }
        Texture texture = new Texture(BASE_COLOR_TEXTURE_NAME, new DecodedImage(
                material.baseColorRgba, material.baseColorWidth, material.baseColorHeight));
        texture.initGL();
        if (!texture.initialized) {
            Platforms.log("[mesh] base-colour texture upload failed");
            return;
        }
        baseColorTexture = texture;
    }

    /** Release the base-color texture and the split geometry the textured draw uses. */
    public void clearTexturedDraw() {
        if (baseColorTexture != null) {
            baseColorTexture.delete();
            baseColorTexture = null;
        }
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
        return baseColorTexture != null && texturedIndexCount > 0;
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
     * Near plane for a camera, scaled to its distance from its target.
     *
     * <p>A fixed near plane leaves the model's whole depth extent in a sliver of the range, small
     * enough that the bias lifting overlay lines above the surface pushes far-side lines through.
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
        // PATCH-17: faces always render at zero depth bias; only the
        // overlay pass in renderFeatureEdgeOverlay sets a positive bias.
        active.setFloat(DEPTHBIAS, NUM_0);

        boolean sampleTexture = samplesTexture(shaderMode, hasTexturedDraw());
        if (shaderMode == ShaderMode.LAMBERT || shaderMode == ShaderMode.STAGES
                || shaderMode == ShaderMode.TEXTURED) {
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
            active.setBool("useTexture", sampleTexture);
            active.setVec3("emissiveColor", emissiveColor);
            active.setFloat("emissiveStrength", NUM_0_08);
            active.setFloat("rimStrength", NUM_0_16);
        }
        if (sampleTexture) {
            active.setTexture("albedoTex", baseColorTexture, Platforms.gl().TEXTURE0(), 0);
        }

        if (sampleTexture) {
            // The textured draw has its own UV-split vertices, so it ignores the welded mesh's tag
            // ranges: the texture is what colours the surface.
            texturedVao.bind();
            Platforms.gl().bindBuffer(Platforms.gl().ELEMENT_ARRAY_BUFFER(), texturedEbo);
            Platforms.gl().drawElements(
                    Platforms.gl().TRIANGLES(),
                    texturedIndexCount,
                    Platforms.gl().UNSIGNED_INT(),
                    0);
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
     * Rebuild {@link #projectionMatrix} for a camera, perspective or orthographic per
     * {@link #isOrthographic()}, with the near and far planes the model's size asks for.
     *
     * @param camera active camera
     */
    private void updateProjection(Camera3D camera) {
        int width = Platforms.get().getFrameBufferWidth();
        int height = Platforms.get().getFrameBufferHeight();
        float aspect = width <= 0 || height <= 0 ? NUM_1 : ((float) width / (float) height);
        float near = nearPlaneFor(camera);
        float far = farPlaneFor(camera, compiledMesh == null ? NUM_1 : compiledMesh.radius * 2f);
        if (orthographic) {
            float dist = camera.position.distance(camera.target);
            float halfH = dist * (float) Math.tan(Math.toRadians(camera.fov / NUM_2_0));
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
                int u = EdgeKey.minVertex(key);
                int v = EdgeKey.maxVertex(key);
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

    /**
     * Build the face-id draw: every face's corners repeated with that face's index encoded as a
     * colour, so one pixel of {@link #faceIndexAtPixel} names the face under the cursor. This is a
     * second, non-indexed copy of the surface, so {@code null} frees it again.
     *
     * @param mesh surface to pick on, or {@code null} to release the copy
     */
    public void uploadFacePickBuffer(MeshTopology mesh) {
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
        int triangles = 0;
        for (int index = 0; index < mesh.faceCount(); index++) {
            triangles += Math.max(0, mesh.faceVertexCount(mesh.faceIdAt(index)) - 2);
        }
        float[] positions = new float[COORDINATES_PER_VERTEX * NUM_3 * triangles];
        float[] ids = new float[COORDINATES_PER_VERTEX * NUM_3 * triangles];
        pickFaceCount = mesh.faceCount();
        Vector3f corner = new Vector3f();
        int vertex = 0;
        for (int index = 0; index < mesh.faceCount(); index++) {
            int faceId = mesh.faceIdAt(index);
            int code = index + 1;
            float red = ((code >> (2 * PICK_CHANNEL_BITS)) & PICK_CHANNEL_MAX) / NUM_255;
            float green = ((code >> PICK_CHANNEL_BITS) & PICK_CHANNEL_MAX) / NUM_255;
            float blue = (code & PICK_CHANNEL_MAX) / NUM_255;
            for (int fan = 2; fan < mesh.faceVertexCount(faceId); fan++) {
                for (int step = 0; step < NUM_3; step++) {
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
     * @return true when {@link #uploadFacePickBuffer} has built a buffer for the live mesh
     */
    public boolean facePickReady() {
        return pickVertexCount > 0;
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
        float cursorNdcX = 2f * (framebufferX + NUM_0_5) / width - NUM_1;
        float cursorNdcY = 2f * (bottomY + NUM_0_5) / height - NUM_1;
        Matrix4f pickProjection = new Matrix4f()
                .scaling(width, height, NUM_1)
                .translate(-cursorNdcX, -cursorNdcY, NUM_0)
                .mul(projectionMatrix);
        gl.clearColor(NUM_0, NUM_0, NUM_0, NUM_1);
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
     * The world-space ray a framebuffer pixel looks along, for the exact barycentric hit inside
     * the face the id pass named.
     *
     * @param camera       active camera
     * @param framebufferX pixel x, measured from the left
     * @param framebufferY pixel y, measured from the top
     * @param origin       receives the ray origin, packed xyz
     * @param direction    receives the ray direction, packed xyz, not normalised
     * @return true when the pixel lies inside the framebuffer
     */
    public boolean rayThroughPixel(Camera3D camera, int framebufferX, int framebufferY,
            float[] origin, float[] direction) {
        int width = Platforms.get().getFrameBufferWidth();
        int height = Platforms.get().getFrameBufferHeight();
        if (width <= 0 || height <= 0) {
            return false;
        }
        updateProjection(camera);
        Matrix4f inverse = new Matrix4f(projectionMatrix).mul(camera.view).mul(modelMatrix)
                .invert();
        float normalisedX = 2f * (framebufferX + NUM_0_5) / width - NUM_1;
        float normalisedY = NUM_1 - 2f * (framebufferY + NUM_0_5) / height;
        Vector4f near = inverse.transform(new Vector4f(normalisedX, normalisedY, -NUM_1, NUM_1));
        Vector4f far = inverse.transform(new Vector4f(normalisedX, normalisedY, NUM_1, NUM_1));
        if (near.w == NUM_0 || far.w == NUM_0) {
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
        projectedPoint.set(x, y, z, NUM_1);
        modelMatrix.transform(projectedPoint);
        camera.view.transform(projectedPoint);
        projectionMatrix.transform(projectedPoint);
        if (projectedPoint.w <= NUM_0) {
            return false;
        }
        pixelDest[0] = (projectedPoint.x / projectedPoint.w * NUM_0_5 + NUM_0_5) * width;
        pixelDest[1] = (NUM_0_5 - projectedPoint.y / projectedPoint.w * NUM_0_5) * height;
        pixelDest[2] = projectedPoint.z / projectedPoint.w * NUM_0_5 + NUM_0_5;
        return true;
    }

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
    public record FeatureEdgeCategory(int colorRgb, Collection<Long> edgeKeys) {}

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
