package ixdar.graphics.render.model;

import java.nio.IntBuffer;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.w3c.dom.css.RGBColor;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.Singularity;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.color.ColorRGB;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;

/**
 * Iso-line overlay for a {@link SeamlessParameterization} result: renders the
 * mesh as a triangle-soup with per-corner {@code (u, v)} attached, fed through
 * the {@code mesh_uv} shader that draws constant-u and constant-v iso-lines as
 * anti-aliased coloured stripes. Coloured spheres at the cross field's
 * singularity vertices use the same icosphere geometry the
 * {@link CrossFieldRuntime} uses, with the same valence-3/valence-5 colour
 * convention from BZK09 figure 4.
 *
 * <p>
 * The parametrization viewer needs a triangle-soup mesh (no vertex sharing)
 * because per-corner {@code (u, v)} is discontinuous across BZK09 §5 cut edges;
 * a shared vertex would receive multiple {@code (u, v)} values from
 * neighbouring faces and the interpolated iso-line would be garbage. We
 * therefore upload {@code 3 * faceCount} GPU vertices and a trivial
 * {@code [0, 1, 2, 3, ...]} element buffer.
 */
public class ParametrizationRuntime extends HalfEdgeMeshRuntime {

    /**
     * Default fragment-pixel half-width of an iso-line, picked so the line is
     * roughly 2 px wide at typical zoom levels.
     */
    public static final float DEFAULT_LINE_HALF_WIDTH = 1.0f;
    /** Fraction of the bounding-box diagonal used for singularity sphere radius. */
    public static final float SPHERE_RADIUS_FRACTION_OF_BBOX = 0.005f;
    /** Three corners per triangle face. */
    public static final int CORNERS_PER_FACE = 3;
    /** Floats per corner in the triangle-soup VBO: pos(3) + normal(3) + uv(2). */
    public static final int FLOATS_PER_CORNER = 9;
    private static final int FLIP_OFFSET = 8; // float index within a corner
    private static final int FLIP_OFFSET_BYTES = FLIP_OFFSET * Float.BYTES;
    private static final int ATTR_FLIP = 4; // layout(location = 4)
    /** Byte offset to the normal attribute within a corner stride. */
    public static final int NORMAL_OFFSET_BYTES = 3 * Float.BYTES;
    /** Byte offset to the uv attribute within a corner stride. */
    public static final int UV_OFFSET_BYTES = 6 * Float.BYTES;
    /** Vertex attribute layout location for position. */
    public static final int ATTR_POSITION = 0;
    /** Vertex attribute layout location for normal. */
    public static final int ATTR_NORMAL = 1;
    /** Vertex attribute layout location for the per-corner {@code (u, v)}. */
    public static final int ATTR_UV = 3;
    /** Icosahedron vertex count for the singularity sphere geometry. */
    public static final int ICOSAHEDRON_VERTEX_COUNT = 12;
    /** Position floats per icosahedron vertex. */
    public static final int FLOATS_PER_SPHERE_VERTEX = 3;
    /** Index slots {@code [0..3)} for vertex-component access. */
    public static final int COMPONENT_X = 0;
    /** Sibling of {@link #COMPONENT_X}. */
    public static final int COMPONENT_Y = 1;
    /** Sibling of {@link #COMPONENT_X}. */
    public static final int COMPONENT_Z = 2;
    /** Float offset to the normal x-component within a corner stride. */
    public static final int NORMAL_X_OFFSET = 3;
    /** Float offset to the normal y-component within a corner stride. */
    public static final int NORMAL_Y_OFFSET = 4;
    /** Float offset to the normal z-component within a corner stride. */
    public static final int NORMAL_Z_OFFSET = 5;
    /** Float offset to the u-coordinate within a corner stride. */
    public static final int U_OFFSET = 6;
    /** Float offset to the v-coordinate within a corner stride. */
    public static final int V_OFFSET = 7;
    /** Vec3 component count — re-used for {@code vertexAttribPointer} size args. */
    public static final int VEC3_SIZE = 3;
    /** Fallback aspect ratio when the framebuffer reports zero dimensions. */
    public static final float ASPECT_FALLBACK = 1f;
    /** Far-plane fallback when the bounding-box diagonal is degenerate. */
    public static final float SPHERE_FAR_FALLBACK = 1000f;
    /** Near plane for the local overlay projection. */
    public static final float NEAR_PLANE = 0.01f;
    /** Multiplier on the bounding-box diagonal that sets the far plane. */
    public static final float FAR_PLANE_DIAG_MUL = 20f;
    /**
     * Sphere tint primary channel for the brighter end of the singularity palette.
     */
    public static final float SPHERE_TINT_PRIMARY = 0.95f;
    /**
     * Sphere tint dim channel — used to push the sphere toward saturated red/cyan.
     */
    public static final float SPHERE_TINT_OFFSET = 0.2f;
    /**
     * Sphere tint secondary channel — slightly under primary for hue separation.
     */
    public static final float SPHERE_TINT_SECONDARY_LOW = 0.85f;
    /** Default base tint used for the surface fill behind the iso-lines. */
    public static final Vector4f DEFAULT_BASE_COLOR = new Vector4f(0.55f, 0.55f, 0.60f, 1f);
    /** Cyan-leaning tint for the constant-u iso-line family. */
    public static final Vector4f DEFAULT_U_LINE_COLOR = new Vector4f(0.35f, 0.85f, 0.95f, 1f);
    /** Yellow-leaning tint for the constant-v iso-line family. */
    public static final Vector4f DEFAULT_V_LINE_COLOR = new Vector4f(0.95f, 0.85f, 0.30f, 1f);
    /** Uniform name for the surface fill colour. */
    public static final String BASE_COLOR = "baseColor";
    /** Uniform name for the constant-u iso-line colour. */
    public static final String U_LINE_COLOR = "uLineColor";
    /** Uniform name for the constant-v iso-line colour. */
    public static final String V_LINE_COLOR = "vLineColor";
    /** Uniform name for the iso-line half-width. */
    public static final String LINE_HALF_WIDTH = "lineHalfWidth";
    /** Golden ratio φ = (1 + √5) / 2 for the icosahedron vertex coordinates. */
    public static final float PHI = (1f + 2.2360679774997896964091736687747f) * 0.5f;

    /** Cyan for {@code index4 > 0} (valence-3, +π/2) per BZK09 fig. 4 caption. */
    private static final Vector4f COLOR_POSITIVE_INDEX = new Vector4f(
            SPHERE_TINT_OFFSET, SPHERE_TINT_SECONDARY_LOW, SPHERE_TINT_PRIMARY, 0.5f);
    /** Red for {@code index4 < 0} (valence-5, -π/2) per BZK09 fig. 4 caption. */
    private static final Vector4f COLOR_NEGATIVE_INDEX = new Vector4f(
            SPHERE_TINT_PRIMARY, SPHERE_TINT_OFFSET, SPHERE_TINT_OFFSET, 0.5f);

    /** 12 unit-icosahedron vertices in xyz layout (flat). */
    private static final float[] ICO_VERTICES = {
            -1, PHI, 0, 1, PHI, 0, -1, -PHI, 0, 1, -PHI, 0,
            0, -1, PHI, 0, 1, PHI, 0, -1, -PHI, 0, 1, -PHI,
            PHI, 0, -1, PHI, 0, 1, -PHI, 0, -1, -PHI, 0, 1
    };
    /** 20 icosahedron triangles, ccw. */
    private static final int[] ICO_TRIANGLES = {
            0, 11, 5, 0, 5, 1, 0, 1, 7, 0, 7, 10, 0, 10, 11,
            1, 5, 9, 5, 11, 4, 11, 10, 2, 10, 7, 6, 7, 1, 8,
            3, 9, 4, 3, 4, 2, 3, 2, 6, 3, 6, 8, 3, 8, 9,
            4, 9, 5, 2, 4, 11, 6, 2, 10, 8, 6, 7, 9, 8, 1
    };

    /** Iso-line shader (vec2 vUv → cyan u-lines + yellow v-lines). */
    public final ShaderProgram uvShader;
    /** Unlit shader reused for singularity spheres (solid colour). */
    public final ShaderProgram unlitShader;
    /** Iso-line half-width passed as the {@link #LINE_HALF_WIDTH} uniform. */
    public float lineHalfWidth = DEFAULT_LINE_HALF_WIDTH;
    /** Surface fill behind the iso-lines. */
    public Vector4f baseColor = new Vector4f(DEFAULT_BASE_COLOR);
    /** Constant-u iso-line tint. */
    public Vector4f uLineColor = new Vector4f(DEFAULT_U_LINE_COLOR);
    /** Constant-v iso-line tint. */
    public Vector4f vLineColor = new Vector4f(DEFAULT_V_LINE_COLOR);
    /** Flipped triangle tint. */
    public Vector4f flippedColor = ColorRGB.MAGENTA.toVector4f();

    /** Triangle-soup VAO for the parametrized surface. */
    public int isoSurfaceVao;
    /** Interleaved (position, normal, uv) VBO. */
    public int isoSurfaceVbo;
    /** Trivial EBO {@code [0, 1, 2, ...]} for the triangle-soup. */
    public int isoSurfaceEbo;
    /** Number of indices in {@link #isoSurfaceEbo}; {@code 3 * faceCount}. */
    public int isoSurfaceIndexCount;

    /** Shared unit-icosahedron VAO for singularity spheres. */
    public int singularityVao;
    /** Vertex buffer for {@link #singularityVao}. */
    public int singularityVbo;
    /** Element buffer for {@link #singularityVao}. */
    public int singularityEbo;
    /** Index count of one icosahedron — {@link #ICO_TRIANGLES}{@code .length}. */
    public int singularityIndexCount;
    /**
     * Flat XYZ positions of each singularity (length {@code 3 * singularities}).
     */
    public float[] singularityPositions;
    /** Per-singularity {@code index4} value, used to pick the sphere colour. */
    public int[] singularityIndex4;
    /** Sphere radius derived from the mesh bounding-box diagonal. */
    public float sphereRadius;

    private final Matrix4f sphereModel = new Matrix4f();
    private final Matrix4f localProjection = new Matrix4f();

    /**
     * Build the runtime; defers parametrization upload to
     * {@link #setSeamlessParametrization(SeamlessParameterization)}.
     *
     * @throws Exception if the inherited {@link HalfEdgeMeshRuntime} or the
     *                   {@code MeshUv} shader fails to initialise
     */
    public ParametrizationRuntime() throws Exception {
        super();
        this.uvShader = ShaderProgram.ShaderType.MeshUv.getShader();
        this.uvShader.init();
        this.unlitShader = ShaderProgram.ShaderType.MeshUnlit.getShader();
    }

    /**
     * Upload (or replace) the iso-line surface and singularity-sphere buffers from
     * {@code seamless}. Safe to call repeatedly — frees the previous buffers before
     * re-uploading. The seamless parametrization must have had
     * {@link SeamlessParameterization#build()} run so {@code uCorner},
     * {@code vCorner}, and the cross-field's singularity list are populated.
     *
     * @param seamless the built parametrization whose iso-lines to render
     */
    public void setSeamlessParametrization(SeamlessParameterization seamless) {
        if (seamless == null || seamless.uCorner == null || seamless.vCorner == null) {
            return;
        }
        HalfEdgeMesh mesh = seamless.mesh;
        CrossField crossField = seamless.crossField;
        int faceCount = mesh.faceCount();
        float[] interleaved = new float[faceCount * CORNERS_PER_FACE * FLOATS_PER_CORNER];
        int[] indices = new int[faceCount * CORNERS_PER_FACE];
        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f p2 = new Vector3f();
        Vector3f normal = new Vector3f();
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            int vertex0 = mesh.faceVertexAt(faceId, 0);
            int vertex1 = mesh.faceVertexAt(faceId, 1);
            int vertex2 = mesh.faceVertexAt(faceId, COMPONENT_Z);
            mesh.vertexPosition(vertex0, p0);
            mesh.vertexPosition(vertex1, p1);
            mesh.vertexPosition(vertex2, p2);
            computeFaceNormal(p0, p1, p2, normal);
            int cornerBase = activeFace * CORNERS_PER_FACE;
            int baseFloat = cornerBase * FLOATS_PER_CORNER;
            float flipped = seamless.uvSignedArea(faceId) <= 0f ? 1f : 0f;
            writeCorner(interleaved, baseFloat, p0, normal,
                    seamless.uCorner[cornerBase], seamless.vCorner[cornerBase], flipped);
            writeCorner(interleaved, baseFloat + FLOATS_PER_CORNER, p1, normal,
                    seamless.uCorner[cornerBase + COMPONENT_Y],
                    seamless.vCorner[cornerBase + COMPONENT_Y], flipped);
            writeCorner(interleaved, baseFloat + COMPONENT_Z * FLOATS_PER_CORNER, p2, normal,
                    seamless.uCorner[cornerBase + COMPONENT_Z],
                    seamless.vCorner[cornerBase + COMPONENT_Z], flipped);
            indices[cornerBase] = cornerBase;
            indices[cornerBase + COMPONENT_Y] = cornerBase + COMPONENT_Y;
            indices[cornerBase + COMPONENT_Z] = cornerBase + COMPONENT_Z;
        }
        uploadIsoSurfaceBuffers(interleaved, indices);
        if (singularityVao == 0) {
            buildIcosphereBuffers();
        }
        updateSphereRadius();
        captureSingularities(crossField, mesh);
    }

    /**
     * Normalised face normal (counter-clockwise winding).
     *
     * @param p0     first vertex position
     * @param p1     second vertex position
     * @param p2     third vertex position
     * @param result destination — receives the normalised normal
     */
    private static void computeFaceNormal(Vector3f p0, Vector3f p1, Vector3f p2,
            Vector3f result) {
        float ax = p1.x - p0.x;
        float ay = p1.y - p0.y;
        float az = p1.z - p0.z;
        float bx = p2.x - p0.x;
        float by = p2.y - p0.y;
        float bz = p2.z - p0.z;
        result.set(
                ay * bz - az * by,
                az * bx - ax * bz,
                ax * by - ay * bx);
        float length = result.length();
        if (length > 0f) {
            result.div(length);
        }
    }

    /**
     * Write one corner's {@code (position, normal, uv)} into the interleaved
     * triangle-soup buffer.
     *
     * @param buffer   interleaved float buffer
     * @param offset   starting index in {@code buffer}
     * @param position corner position
     * @param normal   face normal (same for all three corners of a face)
     * @param u        u-coordinate
     * @param v        v-coordinate
     */
    private static void writeCorner(float[] buffer, int offset, Vector3f position,
            Vector3f normal, float u, float v, float flipped) {
        buffer[offset] = position.x;
        buffer[offset + COMPONENT_Y] = position.y;
        buffer[offset + COMPONENT_Z] = position.z;
        buffer[offset + NORMAL_X_OFFSET] = normal.x;
        buffer[offset + NORMAL_Y_OFFSET] = normal.y;
        buffer[offset + NORMAL_Z_OFFSET] = normal.z;
        buffer[offset + U_OFFSET] = u;
        buffer[offset + V_OFFSET] = v;
        buffer[offset + FLIP_OFFSET] = flipped;
    }

    /**
     * Replace the iso-surface VAO/VBO/EBO with freshly-allocated buffers containing
     * {@code interleaved} attributes and {@code indices}.
     *
     * @param interleaved per-corner (pos, normal, uv) data
     * @param indices     trivial {@code [0, 1, 2, ...]} corner index sequence
     */
    private void uploadIsoSurfaceBuffers(float[] interleaved, int[] indices) {
        GL gl = Platforms.gl();
        if (isoSurfaceVao != 0) {
            gl.deleteVertexArrays(isoSurfaceVao);
        }
        if (isoSurfaceVbo != 0) {
            gl.deleteBuffers(isoSurfaceVbo);
        }
        if (isoSurfaceEbo != 0) {
            gl.deleteBuffers(isoSurfaceEbo);
        }
        isoSurfaceVao = gl.genVertexArrays();
        isoSurfaceVbo = gl.genBuffers();
        isoSurfaceEbo = gl.genBuffers();
        gl.bindVertexArray(isoSurfaceVao);
        gl.bindBuffer(gl.ARRAY_BUFFER(), isoSurfaceVbo);
        gl.bufferData(gl.ARRAY_BUFFER(), interleaved, gl.STATIC_DRAW());
        int strideBytes = FLOATS_PER_CORNER * Float.BYTES;
        gl.vertexAttribPointer(ATTR_POSITION, VEC3_SIZE, gl.FLOAT(), false, strideBytes, 0);
        gl.enableVertexAttribArray(ATTR_POSITION);
        gl.vertexAttribPointer(ATTR_NORMAL, VEC3_SIZE, gl.FLOAT(), false, strideBytes,
                NORMAL_OFFSET_BYTES);
        gl.enableVertexAttribArray(ATTR_NORMAL);
        gl.vertexAttribPointer(ATTR_UV, 2, gl.FLOAT(), false, strideBytes, UV_OFFSET_BYTES);
        gl.enableVertexAttribArray(ATTR_UV);
        gl.vertexAttribPointer(ATTR_FLIP, 1, gl.FLOAT(), false, strideBytes, FLIP_OFFSET_BYTES);
        gl.enableVertexAttribArray(ATTR_FLIP);
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), isoSurfaceEbo);
        IntBuffer ib = BufferUtils.createIntBuffer(indices.length);
        ib.put(indices).flip();
        gl.bufferData(gl.ELEMENT_ARRAY_BUFFER(), ib, gl.STATIC_DRAW());
        isoSurfaceIndexCount = indices.length;
    }

    /**
     * Lazily allocate the shared unit-icosahedron buffers used by every singularity
     * sphere — the same geometry that {@link CrossFieldRuntime#renderCrossField}
     * uses.
     */
    private void buildIcosphereBuffers() {
        GL gl = Platforms.gl();
        float[] verts = new float[ICO_VERTICES.length];
        for (int i = 0; i < ICOSAHEDRON_VERTEX_COUNT; i++) {
            int base = FLOATS_PER_SPHERE_VERTEX * i;
            float x = ICO_VERTICES[base];
            float y = ICO_VERTICES[base + COMPONENT_Y];
            float z = ICO_VERTICES[base + COMPONENT_Z];
            float length = (float) Math.sqrt(x * x + y * y + z * z);
            verts[base] = x / length;
            verts[base + COMPONENT_Y] = y / length;
            verts[base + COMPONENT_Z] = z / length;
        }
        singularityVao = gl.genVertexArrays();
        singularityVbo = gl.genBuffers();
        singularityEbo = gl.genBuffers();
        gl.bindVertexArray(singularityVao);
        gl.bindBuffer(gl.ARRAY_BUFFER(), singularityVbo);
        gl.bufferData(gl.ARRAY_BUFFER(), verts, gl.STATIC_DRAW());
        gl.vertexAttribPointer(ATTR_POSITION, FLOATS_PER_SPHERE_VERTEX, gl.FLOAT(), false,
                FLOATS_PER_SPHERE_VERTEX * Float.BYTES, 0);
        gl.enableVertexAttribArray(ATTR_POSITION);
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), singularityEbo);
        IntBuffer ib = BufferUtils.createIntBuffer(ICO_TRIANGLES.length);
        ib.put(ICO_TRIANGLES).flip();
        gl.bufferData(gl.ELEMENT_ARRAY_BUFFER(), ib, gl.STATIC_DRAW());
        singularityIndexCount = ICO_TRIANGLES.length;
    }

    /**
     * Recompute {@link #sphereRadius} from the mesh's bounding box diagonal.
     */
    private void updateSphereRadius() {
        Vector3f bMin = getBoundingBoxMin();
        Vector3f bMax = getBoundingBoxMax();
        float bbx = bMax.x - bMin.x;
        float bby = bMax.y - bMin.y;
        float bbz = bMax.z - bMin.z;
        float bboxDiag = (float) Math.sqrt(bbx * bbx + bby * bby + bbz * bbz);
        sphereRadius = SPHERE_RADIUS_FRACTION_OF_BBOX * bboxDiag;
    }

    /**
     * Capture singularity vertex positions and their {@code index4} values so
     * {@link #renderParametrization(Camera3D)} can draw a coloured sphere at each.
     *
     * @param crossField cross field whose singularities to render
     * @param mesh       the underlying triangle mesh
     */
    private void captureSingularities(CrossField crossField, HalfEdgeMesh mesh) {
        int n = crossField.singularities.size();
        singularityPositions = new float[FLOATS_PER_SPHERE_VERTEX * n];
        singularityIndex4 = new int[n];
        Vector3f position = new Vector3f();
        for (int i = 0; i < n; i++) {
            Singularity singularity = crossField.singularities.get(i);
            mesh.vertexPosition(singularity.vertexId(), position);
            int posBase = FLOATS_PER_SPHERE_VERTEX * i;
            singularityPositions[posBase] = position.x;
            singularityPositions[posBase + COMPONENT_Y] = position.y;
            singularityPositions[posBase + COMPONENT_Z] = position.z;
            singularityIndex4[i] = singularity.index4();
        }
    }

    /**
     * Whether {@link #setSeamlessParametrization(SeamlessParameterization)} has
     * populated the GPU buffers and there's something to render.
     *
     * @return {@code true} when iso-surface geometry has been uploaded
     */
    public boolean hasParametrization() {
        return isoSurfaceIndexCount > 0;
    }

    /**
     * Render the iso-line surface and singularity spheres on top of the current
     * framebuffer. Call {@link #render(Camera3D)} (inherited) first if you want the
     * translucent base surface drawn underneath.
     *
     * @param camera 3D camera supplying view + fov for the projection matrix
     */
    public void renderParametrization(Camera3D camera) {
        if (!hasParametrization() || uvShader.ID < 0) {
            return;
        }
        GL gl = Platforms.gl();
        int width = Platforms.get().getFrameBufferWidth();
        int height = Platforms.get().getFrameBufferHeight();
        float aspect = (width <= 0 || height <= 0) ? ASPECT_FALLBACK
                : ((float) width / (float) height);
        Vector3f bMin = getBoundingBoxMin();
        Vector3f bMax = getBoundingBoxMax();
        float diag = bMax.distance(bMin);
        float far = Math.max(SPHERE_FAR_FALLBACK, diag * FAR_PLANE_DIAG_MUL);
        localProjection.identity().perspective(
                (float) Math.toRadians((float) camera.fov),
                aspect, NEAR_PLANE, far);

        uvShader.use();
        uvShader.setMat4(VIEW, camera.view);
        uvShader.setMat4(PROJECTION, localProjection);
        sphereModel.identity();
        uvShader.setMat4(MODEL, sphereModel);
        uvShader.setFloat(DEPTHBIAS, 0f);
        uvShader.setVec4(BASE_COLOR, baseColor);
        uvShader.setVec4(U_LINE_COLOR, uLineColor);
        uvShader.setVec4(V_LINE_COLOR, vLineColor);
        uvShader.setFloat(LINE_HALF_WIDTH, lineHalfWidth);
        uvShader.setVec4("flippedColor", flippedColor);
        gl.bindVertexArray(isoSurfaceVao);
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), isoSurfaceEbo);
        gl.drawElements(gl.TRIANGLES(), isoSurfaceIndexCount, gl.UNSIGNED_INT(), 0);

        renderSingularitySpheres(camera, gl);
    }

    /**
     * Draw one coloured sphere per singularity over the iso-line surface with a
     * small depth bias so the spheres aren't z-fought by the surface.
     *
     * @param camera the 3D camera (used only for the inherited view matrix)
     * @param gl     active GL platform handle
     */
    private void renderSingularitySpheres(Camera3D camera, GL gl) {
        if (singularityIndex4 == null || singularityIndex4.length == 0) {
            return;
        }
        unlitShader.use();
        unlitShader.setMat4(VIEW, camera.view);
        unlitShader.setMat4(PROJECTION, localProjection);
        unlitShader.setFloat(DEPTHBIAS, 0f);
        gl.bindVertexArray(singularityVao);
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), singularityEbo);
        for (int i = 0; i < singularityIndex4.length; i++) {
            int posBase = FLOATS_PER_SPHERE_VERTEX * i;
            float px = singularityPositions[posBase];
            float py = singularityPositions[posBase + COMPONENT_Y];
            float pz = singularityPositions[posBase + COMPONENT_Z];
            sphereModel.identity()
                    .translate(px, py, pz)
                    .scale(sphereRadius);
            unlitShader.setMat4(MODEL, sphereModel);
            Vector4f color = singularityIndex4[i] > 0
                    ? COLOR_POSITIVE_INDEX
                    : COLOR_NEGATIVE_INDEX;
            unlitShader.setVec4(SOLIDCOLOR, color);
            gl.drawElements(gl.TRIANGLES(), singularityIndexCount, gl.UNSIGNED_INT(), 0);
        }
    }

    @Override
    public void dispose() {
        super.dispose();
        GL gl = Platforms.gl();
        if (isoSurfaceVao != 0) {
            gl.deleteVertexArrays(isoSurfaceVao);
            isoSurfaceVao = 0;
        }
        if (isoSurfaceVbo != 0) {
            gl.deleteBuffers(isoSurfaceVbo);
            isoSurfaceVbo = 0;
        }
        if (isoSurfaceEbo != 0) {
            gl.deleteBuffers(isoSurfaceEbo);
            isoSurfaceEbo = 0;
        }
        if (singularityVao != 0) {
            gl.deleteVertexArrays(singularityVao);
            singularityVao = 0;
        }
        if (singularityVbo != 0) {
            gl.deleteBuffers(singularityVbo);
            singularityVbo = 0;
        }
        if (singularityEbo != 0) {
            gl.deleteBuffers(singularityEbo);
            singularityEbo = 0;
        }
        isoSurfaceIndexCount = 0;
        singularityIndexCount = 0;
    }
}
