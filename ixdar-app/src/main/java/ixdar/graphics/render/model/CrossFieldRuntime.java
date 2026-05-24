package ixdar.graphics.render.model;

import java.nio.IntBuffer;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.Singularity;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.color.ColorRGB;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;

/**
 * Cross-field overlay on top of a {@link HalfEdgeMeshRuntime}: per-triangle
 * cross glyph (two perpendicular line segments through the centroid) plus
 * coloured spheres at singularity vertices. Inherits the surface render path
 * from {@link HalfEdgeMeshRuntime}; adds two dedicated VAO/VBO/EBO sets for
 * the cross arms (GL_LINES) and the singularity icosahedron (GL_TRIANGLES).
 * Uses the same {@code mesh_unlit} shader the wireframe overlay uses, so no
 * new GLSL files are required.
 */
public class CrossFieldRuntime extends HalfEdgeMeshRuntime {

    public static final float DEFAULT_CROSS_SCALE = 0.7f;
    public static final float CROSS_LINE_WIDTH = 1.5f;
    public static final float SPHERE_RADIUS_FRACTION_OF_BBOX = 0.005f;
    /** Two perpendicular segments through the centroid → 2 lines × 2 endpoints. */
    public static final int VERTS_PER_FACE = 4;
    public static final int LINES_PER_FACE = 2;
    public static final int INDICES_PER_FACE = LINES_PER_FACE * 2;
    public static final int INDICES_PER_AXIS = 2;
    public static final int FLOATS_PER_VERTEX = 3;
    public static final int FLOATS_PER_FACE = VERTS_PER_FACE * FLOATS_PER_VERTEX;
    public static final int ICOSAHEDRON_VERTEX_COUNT = 12;
    public static final float ONE_THIRD = 1.0f / 3.0f;
    public static final float SPHERE_TINT_OFFSET = 0.2f;
    public static final float SPHERE_TINT_PRIMARY = 0.95f;
    public static final float SPHERE_TINT_SECONDARY_LOW = 0.85f;
    public static final float SPHERE_FAR_FALLBACK = 1000f;
    public static final float ASPECT_FALLBACK = 1f;
    /** Golden ratio φ = (1 + √5) / 2 */
    public static final float PHI = (1f + ((float) Math.sqrt(5))) / 2f;
    public static final int IDX_VBASE_PLUS_1 = 1;
    public static final int IDX_VBASE_PLUS_2 = 2;
    public static final int IDX_VBASE_PLUS_3 = 3;

    /** Cyan for {@code index4 > 0} (valence-3, +π/2)*/
    private static final Vector4f COLOR_POSITIVE_INDEX = ColorRGB.CYAN.toVector4f();
    /** Red for {@code index4 < 0} (valence-5, -π/2) */
    private static final Vector4f COLOR_NEGATIVE_INDEX = ColorRGB.RED.toVector4f();
    /** Yellow for the u-axis arm (cos θ · f_x + sin θ · f_y). */
    private static final Vector4f COLOR_U_ARM = ColorRGB.YELLOW.toVector4f();
    /** Cyan for the v-axis arm (−sin θ · f_x + cos θ · f_y). */
    private static final Vector4f COLOR_V_ARM = ColorRGB.CYAN.toVector4f();

    /** 12 unit-icosahedron vertices in xyz layout (flat). */
    private static final float[] ICO_VERTICES = {
            -1, PHI, 0,    1, PHI, 0,    -1, -PHI, 0,    1, -PHI, 0,
            0, -1, PHI,    0, 1, PHI,    0, -1, -PHI,    0, 1, -PHI,
            PHI, 0, -1,    PHI, 0, 1,    -PHI, 0, -1,    -PHI, 0, 1
    };
    /** 20 icosahedron triangles, ccw. */
    private static final int[] ICO_TRIANGLES = {
            0, 11, 5,   0, 5, 1,    0, 1, 7,    0, 7, 10,   0, 10, 11,
            1, 5, 9,    5, 11, 4,   11, 10, 2,  10, 7, 6,   7, 1, 8,
            3, 9, 4,    3, 4, 2,    3, 2, 6,    3, 6, 8,    3, 8, 9,
            4, 9, 5,    2, 4, 11,   6, 2, 10,   8, 6, 7,    9, 8, 1
    };

    private final ShaderProgram unlit;

    // Cross-arm geometry (xyz only, attribute location 0). U-axis and v-axis
    // arms share a single VBO but live in separate EBOs so each axis can be
    // drawn with its own solid colour, making field rotation across cuts
    // visually obvious.
    private int crossArmsVao;
    private int crossArmsVbo;
    private int crossArmsUEbo;
    private int crossArmsVEbo;
    private int crossArmsIndexCountPerAxis;

    // Shared icosphere unit mesh (radius=1, no subdiv — 12 verts, 20 tris). Each
    // singularity gets one draw call with its own model matrix translating to
    // the vertex and scaling to {@link #sphereRadius}.
    private int singularityVao;
    private int singularityVbo;
    private int singularityEbo;
    private int singularityIndexCount;

    private float[] singularityPositions;
    private int[] singularityIndex4;
    private float sphereRadius;

    private final Matrix4f sphereModel = new Matrix4f();
    private final Matrix4f localProjection = new Matrix4f();

    /**
     * Build the runtime; defers {@link CrossField} setup to
     * {@link #setCrossField(CrossField, float)}.
     *
     * @throws Exception if the inherited {@link HalfEdgeMeshRuntime} fails to allocate its shaders
     */
    public CrossFieldRuntime() throws Exception {
        super();
        this.unlit = ShaderProgram.ShaderType.MeshUnlit.getShader();
    }

    /**
     * Upload (or replace) the cross-glyph and singularity-sphere buffers from
     * {@code field}. Safe to call repeatedly — frees the previous buffers
     * before re-uploading. {@code field.theta}, {@code field.faceX} and
     * {@code field.faceY} must be populated (i.e. {@link CrossField#build()}
     * has run, or the geometric arrays have been borrowed from a built
     * instance the way {@code CrossFieldBuildProfileTest} does for an NDF
     * loaded via {@link ixdar.geometry.mesh.data.load.CrossFieldLoader}).
     *
     * @param field      cross field to visualise on the currently-uploaded mesh
     * @param crossScale fraction of each triangle's incircle radius used as
     *                   the cross half-arm length (paper-faithful default 0.7)
     */
    public void setCrossField(CrossField field, float crossScale) {
        if (field == null || field.theta == null || field.faceX == null || field.faceY == null) {
            return;
        }
        HalfEdgeMesh mesh = field.mesh;
        GL gl = Platforms.gl();
        int faceCount = mesh.faceCount();
        float[] arms = new float[faceCount * FLOATS_PER_FACE];
        int[] uArmsIndices = new int[faceCount * INDICES_PER_AXIS];
        int[] vArmsIndices = new int[faceCount * INDICES_PER_AXIS];
        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f p2 = new Vector3f();
        for (int fAi = 0; fAi < faceCount; fAi++) {
            int fId = mesh.faceIdAt(fAi);
            int v0 = mesh.faceVertexAt(fId, 0);
            int v1 = mesh.faceVertexAt(fId, 1);
            int v2 = mesh.faceVertexAt(fId, IDX_VBASE_PLUS_2);
            mesh.vertexPosition(v0, p0);
            mesh.vertexPosition(v1, p1);
            mesh.vertexPosition(v2, p2);
            float cx = (p0.x + p1.x + p2.x) * ONE_THIRD;
            float cy = (p0.y + p1.y + p2.y) * ONE_THIRD;
            float cz = (p0.z + p1.z + p2.z) * ONE_THIRD;
            float ax = p1.x - p0.x;
            float ay = p1.y - p0.y;
            float az = p1.z - p0.z;
            float bx = p2.x - p0.x;
            float by = p2.y - p0.y;
            float bz = p2.z - p0.z;
            float crX = ay * bz - az * by;
            float crY = az * bx - ax * bz;
            float crZ = ax * by - ay * bx;
            float twoArea = (float) Math.sqrt(crX * crX + crY * crY + crZ * crZ);
            float lenA = (float) Math.sqrt(ax * ax + ay * ay + az * az);
            float lenB = (float) Math.sqrt(bx * bx + by * by + bz * bz);
            float ex = p2.x - p1.x;
            float ey = p2.y - p1.y;
            float ez = p2.z - p1.z;
            float lenC = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
            float perim = lenA + lenB + lenC;
            float incircleR = perim > 0f ? twoArea / perim : 0f;
            float s = crossScale * incircleR;

            float cosT = (float) Math.cos(field.theta[fAi]);
            float sinT = (float) Math.sin(field.theta[fAi]);
            Vector3f fx = field.faceX[fAi];
            Vector3f fy = field.faceY[fAi];
            float dx = cosT * fx.x + sinT * fy.x;
            float dy = cosT * fx.y + sinT * fy.y;
            float dz = cosT * fx.z + sinT * fy.z;
            float pdx = -sinT * fx.x + cosT * fy.x;
            float pdy = -sinT * fx.y + cosT * fy.y;
            float pdz = -sinT * fx.z + cosT * fy.z;

            // 4 endpoints per face: -d, +d, -dp, +dp (centered at centroid).
            int base = fAi * FLOATS_PER_FACE;
            writeVertex(arms, base, cx - s * dx, cy - s * dy, cz - s * dz);
            writeVertex(arms, base + FLOATS_PER_VERTEX, cx + s * dx, cy + s * dy, cz + s * dz);
            writeVertex(arms, base + IDX_VBASE_PLUS_2 * FLOATS_PER_VERTEX,
                    cx - s * pdx, cy - s * pdy, cz - s * pdz);
            writeVertex(arms, base + IDX_VBASE_PLUS_3 * FLOATS_PER_VERTEX,
                    cx + s * pdx, cy + s * pdy, cz + s * pdz);

            int idxBase = fAi * INDICES_PER_AXIS;
            int vBase = fAi * VERTS_PER_FACE;
            uArmsIndices[idxBase] = vBase;
            uArmsIndices[idxBase + IDX_VBASE_PLUS_1] = vBase + IDX_VBASE_PLUS_1;
            vArmsIndices[idxBase] = vBase + IDX_VBASE_PLUS_2;
            vArmsIndices[idxBase + IDX_VBASE_PLUS_1] = vBase + IDX_VBASE_PLUS_3;
        }

        if (crossArmsVao != 0) {
            gl.deleteVertexArrays(crossArmsVao);
        }
        if (crossArmsVbo != 0) {
            gl.deleteBuffers(crossArmsVbo);
        }
        if (crossArmsUEbo != 0) {
            gl.deleteBuffers(crossArmsUEbo);
        }
        if (crossArmsVEbo != 0) {
            gl.deleteBuffers(crossArmsVEbo);
        }
        crossArmsVao = gl.genVertexArrays();
        crossArmsVbo = gl.genBuffers();
        crossArmsUEbo = gl.genBuffers();
        crossArmsVEbo = gl.genBuffers();
        gl.bindVertexArray(crossArmsVao);
        gl.bindBuffer(gl.ARRAY_BUFFER(), crossArmsVbo);
        gl.bufferData(gl.ARRAY_BUFFER(), arms, gl.STATIC_DRAW());
        gl.vertexAttribPointer(0, FLOATS_PER_VERTEX, gl.FLOAT(), false,
                FLOATS_PER_VERTEX * Float.BYTES, 0);
        gl.enableVertexAttribArray(0);
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), crossArmsUEbo);
        IntBuffer uIb = BufferUtils.createIntBuffer(uArmsIndices.length);
        uIb.put(uArmsIndices).flip();
        gl.bufferData(gl.ELEMENT_ARRAY_BUFFER(), uIb, gl.STATIC_DRAW());
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), crossArmsVEbo);
        IntBuffer vIb = BufferUtils.createIntBuffer(vArmsIndices.length);
        vIb.put(vArmsIndices).flip();
        gl.bufferData(gl.ELEMENT_ARRAY_BUFFER(), vIb, gl.STATIC_DRAW());
        crossArmsIndexCountPerAxis = uArmsIndices.length;

        if (singularityVao == 0) {
            buildIcosphereBuffers(gl);
        }
        Vector3f bMin = getBoundingBoxMin();
        Vector3f bMax = getBoundingBoxMax();
        float bbx = bMax.x - bMin.x;
        float bby = bMax.y - bMin.y;
        float bbz = bMax.z - bMin.z;
        float bboxDiag = (float) Math.sqrt(bbx * bbx + bby * bby + bbz * bbz);
        sphereRadius = SPHERE_RADIUS_FRACTION_OF_BBOX * bboxDiag;

        int n = field.singularities.size();
        singularityPositions = new float[FLOATS_PER_VERTEX * n];
        singularityIndex4 = new int[n];
        Vector3f svp = new Vector3f();
        for (int i = 0; i < n; i++) {
            Singularity s = field.singularities.get(i);
            mesh.vertexPosition(s.vertexId(), svp);
            int posBase = FLOATS_PER_VERTEX * i;
            singularityPositions[posBase] = svp.x;
            singularityPositions[posBase + IDX_VBASE_PLUS_1] = svp.y;
            singularityPositions[posBase + IDX_VBASE_PLUS_2] = svp.z;
            singularityIndex4[i] = s.index4();
        }
    }

    private static void writeVertex(float[] arr, int base, float x, float y, float z) {
        arr[base] = x;
        arr[base + IDX_VBASE_PLUS_1] = y;
        arr[base + IDX_VBASE_PLUS_2] = z;
    }

    private void buildIcosphereBuffers(GL gl) {
        // Normalise to unit length — render scales by sphereRadius per draw.
        float[] verts = new float[ICO_VERTICES.length];
        for (int i = 0; i < ICOSAHEDRON_VERTEX_COUNT; i++) {
            int base = FLOATS_PER_VERTEX * i;
            float x = ICO_VERTICES[base];
            float y = ICO_VERTICES[base + IDX_VBASE_PLUS_1];
            float z = ICO_VERTICES[base + IDX_VBASE_PLUS_2];
            float len = (float) Math.sqrt(x * x + y * y + z * z);
            verts[base] = x / len;
            verts[base + IDX_VBASE_PLUS_1] = y / len;
            verts[base + IDX_VBASE_PLUS_2] = z / len;
        }
        singularityVao = gl.genVertexArrays();
        singularityVbo = gl.genBuffers();
        singularityEbo = gl.genBuffers();
        gl.bindVertexArray(singularityVao);
        gl.bindBuffer(gl.ARRAY_BUFFER(), singularityVbo);
        gl.bufferData(gl.ARRAY_BUFFER(), verts, gl.STATIC_DRAW());
        gl.vertexAttribPointer(0, FLOATS_PER_VERTEX, gl.FLOAT(), false,
                FLOATS_PER_VERTEX * Float.BYTES, 0);
        gl.enableVertexAttribArray(0);
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), singularityEbo);
        IntBuffer ib = BufferUtils.createIntBuffer(ICO_TRIANGLES.length);
        ib.put(ICO_TRIANGLES).flip();
        gl.bufferData(gl.ELEMENT_ARRAY_BUFFER(), ib, gl.STATIC_DRAW());
        singularityIndexCount = ICO_TRIANGLES.length;
    }

    /**
     * Whether {@link #setCrossField(CrossField, float)} has populated the
     * GPU buffers and there's something to render.
     *
     * @return {@code true} when cross-arm geometry has been uploaded
     */
    public boolean hasCrossField() {
        return crossArmsIndexCountPerAxis > 0;
    }

    /**
     * Render the cross-arm overlay and the singularity spheres on top of
     * the current GL framebuffer. Call {@link #render(Camera3D)} (inherited)
     * first to draw the surface itself.
     *
     * @param camera 3D camera supplying view + fov for the projection matrix
     */
    public void renderCrossField(Camera3D camera) {
        if (!hasCrossField() || unlit.ID < 0) {
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
        float far = Math.max(SPHERE_FAR_FALLBACK, diag * NUM_20);
        localProjection.identity().perspective(
                (float) Math.toRadians((float) camera.fov),
                aspect, NUM_0_01, far);

        unlit.use();
        unlit.setMat4(VIEW, camera.view);
        unlit.setMat4(PROJECTION, localProjection);
        unlit.setFloat(DEPTHBIAS, 0f);

        sphereModel.identity();
        unlit.setMat4(MODEL, sphereModel);
        gl.bindVertexArray(crossArmsVao);
        gl.lineWidth(CROSS_LINE_WIDTH);
        unlit.setVec4(SOLIDCOLOR, COLOR_U_ARM);
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), crossArmsUEbo);
        gl.drawElements(gl.LINES(), crossArmsIndexCountPerAxis, gl.UNSIGNED_INT(), 0);
        unlit.setVec4(SOLIDCOLOR, COLOR_V_ARM);
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), crossArmsVEbo);
        gl.drawElements(gl.LINES(), crossArmsIndexCountPerAxis, gl.UNSIGNED_INT(), 0);

        gl.bindVertexArray(singularityVao);
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), singularityEbo);
        for (int i = 0; i < singularityIndex4.length; i++) {
            int posBase = FLOATS_PER_VERTEX * i;
            float px = singularityPositions[posBase];
            float py = singularityPositions[posBase + IDX_VBASE_PLUS_1];
            float pz = singularityPositions[posBase + IDX_VBASE_PLUS_2];
            sphereModel.identity()
                    .translate(px, py, pz)
                    .scale(sphereRadius);
            unlit.setMat4(MODEL, sphereModel);
            Vector4f color = singularityIndex4[i] > 0 ? COLOR_POSITIVE_INDEX : COLOR_NEGATIVE_INDEX;
            unlit.setVec4(SOLIDCOLOR, color);
            gl.drawElements(gl.TRIANGLES(), singularityIndexCount, gl.UNSIGNED_INT(), 0);
        }
    }

    @Override
    public void dispose() {
        super.dispose();
        GL gl = Platforms.gl();
        if (crossArmsVao != 0) {
            gl.deleteVertexArrays(crossArmsVao);
            crossArmsVao = 0;
        }
        if (crossArmsVbo != 0) {
            gl.deleteBuffers(crossArmsVbo);
            crossArmsVbo = 0;
        }
        if (crossArmsUEbo != 0) {
            gl.deleteBuffers(crossArmsUEbo);
            crossArmsUEbo = 0;
        }
        if (crossArmsVEbo != 0) {
            gl.deleteBuffers(crossArmsVEbo);
            crossArmsVEbo = 0;
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
        crossArmsIndexCountPerAxis = 0;
        singularityIndexCount = 0;
    }
}
