package ixdar.graphics.render.model;

import java.nio.IntBuffer;
import java.util.Collection;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.Singularity;
import ixdar.geometry.mesh.quadlayout.embedding.ArcEdgePath;
import ixdar.geometry.mesh.quadlayout.embedding.ArcRerouteFailure;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedNode;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.LayoutEmbedding;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.crossfield.constraint.ConstraintSource;
import ixdar.geometry.mesh.quadlayout.quantization.LayoutPatchCurves;
import ixdar.geometry.mesh.quadlayout.quantization.LayoutPatchGeometry;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.motorcycle.records.TMeshNode;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorRGB;
import ixdar.graphics.render.color.PatchColorHash;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;

/**
 * Shared quad-layout inspection runtime: iso-lines, cross-field glyphs, singularity spheres,
 * and motorcycle traces, each enabled by a public {@code show*} toggle.
 *
 * <p>The uploaded mesh must be triangle soup, because per-corner {@code (u, v)} is
 * discontinuous across cut edges and a shared vertex would interpolate incompatible values.
 *
 * <p>See also: BZK09 Section 5
 */
public class QuadLayoutRuntime extends HalfEdgeMeshRuntime {

    public static final float DEFAULT_CROSS_SCALE = 3f;
    public static final int TRACE_RECORDS_PER_FACE = MotorcycleGraph.MAX_TRACE_RECORDS_PER_FACE;
    public static final int FLOATS_PER_TRACE_RECORD = 4;
    public static final int FLOATS_PER_CORNER_CROSS = 16;
    public static final int CENTROID_OFFSET = 6;
    public static final int DIR_U_OFFSET = 9;
    public static final int DIR_V_OFFSET = 12;
    public static final int ARM_LENGTH_OFFSET = 15;
    public static final int ATTR_CENTROID = 3;
    public static final int ATTR_DIR_U = 4;
    public static final int ATTR_DIR_V = 5;
    public static final int ATTR_ARM_LENGTH = 6;
    public static final float ONE_THIRD = 1.0f / 3.0f;
    /**
     * Fraction of a face's arm length by which the constraint glyph is floated along the face
     * normal, so that it wins the depth test against the exactly coincident cross-field glyph on
     * a constrained face.
     */
    public static final float CONSTRAINT_NORMAL_LIFT = 0.25f;
    public static final int ATTR_TRACE0 = 5;
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
    public static final int FLOATS_PER_CORNER_WITH_TRACES = FLOATS_PER_CORNER
            + TRACE_RECORDS_PER_FACE * FLOATS_PER_TRACE_RECORD + 1;
    public static final int ATTR_TRACE1 = 6;
    public static final int ATTR_TRACE2 = 7;
    public static final int ATTR_TRACE3 = 8;
    /** Vertex attribute layout location for the per-corner patch id. */
    public static final int ATTR_PATCH_ID = 9;
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
    /** Uniform name for the surface fill colour. */
    public static final String BASE_COLOR = "baseColor";
    /** Uniform name for the constant-u iso-line colour. */
    public static final String U_LINE_COLOR = "uLineColor";
    /** Uniform name for the constant-v iso-line colour. */
    public static final String V_LINE_COLOR = "vLineColor";
    /** Uniform name for the iso-line half-width. */
    public static final String LINE_HALF_WIDTH = "lineHalfWidth";
    /** Golden ratio φ = (1 + √5) / 2 for the icosahedron vertex coordinates. */
    public static final float PHI = (1f + ((float) Math.sqrt(5))) / 2f;

    /** Depth bias lifting layout boundary curves over the surface fill. */
    public static final float LAYOUT_DEPTH_BIAS = 0.0003f;
    /** Pixel width of layout boundary curves. */
    public static final float LAYOUT_LINE_WIDTH = 2.5f;
    /** Alpha of the Coons patch fill. */
    public static final float LAYOUT_PATCH_ALPHA = 0.55f;
    /** Corner marker sphere scale relative to {@link #sphereRadius}. */
    public static final float CORNER_SPHERE_SCALE = 0.7f;
    /** Indices emitted per Coons grid cell (two triangles). */
    public static final int INDICES_PER_GRID_CELL = 6;

    protected static final int FLIP_OFFSET = 8;
    protected static final int FLIP_OFFSET_BYTES = FLIP_OFFSET * Float.BYTES;
    protected static final int ATTR_FLIP = 4;

    private static final Color COLOR_V_ARM = Color.CYAN;
    private static final Color COLOR_INTERSECTION_NODE = Color.WHITE;
    private static final Color COLOR_BOUNDARY_NODE = Color.YELLOW;
    private static final Color COLOR_FEATURE_NODE = Color.MAGENTA;
    private static final Color COLOR_TRUNCATED_NODE = Color.ORANGE;
    private static final String FLIPPED_COLOR_UNIFORM = "flippedColor";
    private static final String DRAW_FULL_ISO_GRID_UNIFORM = "drawFullIsoGrid";
    private static final String USE_PATCH_COLOR_UNIFORM = "usePatchColor";
    private static final int TRACE0_OFFSET = FLOATS_PER_CORNER;
    private static final int TRACE1_OFFSET = TRACE0_OFFSET + FLOATS_PER_TRACE_RECORD;
    private static final int TRACE2_OFFSET = TRACE1_OFFSET + FLOATS_PER_TRACE_RECORD;
    private static final int TRACE3_OFFSET = TRACE2_OFFSET + FLOATS_PER_TRACE_RECORD;
    private static final int PATCH_ID_OFFSET = TRACE3_OFFSET + FLOATS_PER_TRACE_RECORD;
    private static final int PATCH_ID_OFFSET_BYTES = PATCH_ID_OFFSET * Float.BYTES;
    private static final Color COLOR_U_ARM = Color.YELLOW;
    /** Cyan for {@code index4 > 0} (valence-3, +π/2) per BZK09 fig. 4 caption. */
    private static final Color COLOR_POSITIVE_INDEX = new ColorRGB(Color.CYAN, 0.5f);
    /** Red for {@code index4 < 0} (valence-5, -π/2) per BZK09 fig. 4 caption. */
    private static final Color COLOR_NEGATIVE_INDEX = new ColorRGB(Color.RED, 0.5f);
    /** Constraint glyph colour for boundary-edge pins. */
    private static final Color COLOR_CONSTRAINT_BOUNDARY = Color.GREEN;
    /** Constraint glyph colour for sharp-crease (feature-edge) pins. */
    private static final Color COLOR_CONSTRAINT_FEATURE = Color.ORANGE;
    /** Constraint glyph colour for principal-curvature pins. */
    private static final Color COLOR_CONSTRAINT_CURVATURE = Color.MAGENTA;
    /** Constraint glyph colour for the arbitrary gauge anchor. */
    private static final Color COLOR_CONSTRAINT_ANCHOR = Color.WHITE;
    /**
     * Source groups drawn by {@link #renderConstraintOverlay(Camera3D)}, in upload
     * order.
     */
    private static final ConstraintSource[] CONSTRAINT_DRAW_ORDER = {
            ConstraintSource.BOUNDARY, ConstraintSource.FEATURE,
            ConstraintSource.CURVATURE, ConstraintSource.ANCHOR
    };

    /** Layout boundary curve tint. */
    private static final Color COLOR_LAYOUT_BOUNDARY = Color.WHITE;
    /** Layout corner marker tint. */
    private static final Color COLOR_LAYOUT_CORNER = Color.SOFT_RED;
    /** Embedded arc edge-path tint (stage-8 re-embedding). */
    private static final Color COLOR_EMBEDDED_ARC = Color.BRIGHT_ORANGE;

    /** Tint for zero-quantized arcs, red like LCBK19 Figure 9, so collapse targets stand out. */
    private static final Color COLOR_EMBEDDED_ZERO_ARC = Color.RED;

    /** Tint for an ordinary embedded T-mesh node. */
    private static final Color COLOR_EMBEDDED_NODE = Color.SKY_BLUE;

    /** Tint for a critical embedded T-mesh node, which the operators may never move. */
    private static final Color COLOR_EMBEDDED_NODE_CRITICAL = Color.GOLD;

    /** Failure highlight: the stranded arc's body region (one of the two disconnected regions). */
    private static final Color COLOR_HIGHLIGHT_BODY = Color.AZURE;

    /** Failure highlight: the survivor's channel region (the other disconnected region). */
    private static final Color COLOR_HIGHLIGHT_CHANNEL = Color.BRIGHT_GREEN;

    /** Failure highlight: the stranded arc's own edge path. */
    private static final Color COLOR_HIGHLIGHT_ARC = Color.YELLOW;

    /** Failure highlight: the freed collapse channel. */
    private static final Color COLOR_HIGHLIGHT_CHANNEL_LINE = Color.CYAN;

    /** Failure highlight: the collapsing pivot node the router could not pass. */
    private static final Color COLOR_HIGHLIGHT_PIVOT = Color.RED;

    /** Failure highlight: the survivor node the arc could not reach. */
    private static final Color COLOR_HIGHLIGHT_SURVIVOR = Color.MAGENTA;

    /** Failure highlight: the claimed arc-edges fencing the body region — the wall. */
    private static final Color COLOR_HIGHLIGHT_FENCE = Color.WHITE;

    /** Failure highlight: the pivot's free spokes — where the router may legally step off it. */
    private static final Color COLOR_HIGHLIGHT_SPOKE = Color.AMBER;

    /** Line width for the wall/spoke highlight, thicker than an ordinary arc. */
    private static final float HIGHLIGHT_WALL_LINE_WIDTH = 3f;

    /** Line width for the channel and stranded-arc highlight lines. */
    private static final float HIGHLIGHT_LINE_WIDTH = 5f;

    /** Depth bias for highlight lines — larger than the arc bias so they draw in front of arcs. */
    private static final float HIGHLIGHT_DEPTH_BIAS = 0.0015f;

    /** Sphere scale for a region-membership dot in the failure highlight. */
    private static final float HIGHLIGHT_REGION_SCALE = 0.22f;

    /** Sphere scale for the pivot/survivor markers in the failure highlight. */
    private static final float HIGHLIGHT_MARKER_SCALE = 2.2f;

    /** Line width restored after drawing layout boundary curves. */
    private static final float DEFAULT_GL_LINE_WIDTH = 1f;

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
    /** Trace iso-line shader with per-face motorcycle records. */
    public final ShaderProgram traceUvShader;
    /** Cross-field iso-line shader (per-face chart, screen-space AA). */
    public final ShaderProgram crossFieldShader;
    /** Unlit shader reused for singularity spheres (solid colour). */
    public final ShaderProgram unlitShader;

    /** Iso-line half-width passed as the {@link #LINE_HALF_WIDTH} uniform. */
    public float lineHalfWidth = DEFAULT_LINE_HALF_WIDTH;
    /** Surface fill behind the iso-lines. */
    public Color baseColor = Color.BLUE_WHITE;
    /** Constant-u iso-line tint. */
    public Color uLineColor = Color.CYAN;
    /** Constant-v iso-line tint. */
    public Color vLineColor = Color.YELLOW;
    /** Flipped triangle tint. */
    public Color flippedColor = Color.MAGENTA;

    public boolean showIsoLines = false;
    public boolean showSingularities = false;
    public boolean showCrossField = false;
    public boolean showConstraints = false;
    public boolean showTraces = false;
    public boolean showNodes = false;
    public boolean showFullIsoGrid = false;
    public boolean showWitnesses = false;
    public boolean showEppsteinMarkers = false;
    /** Draw the per-patch Coons fill of the conforming layout. */
    public boolean showLayoutPatches = false;
    /** Draw layout boundary curves and corner marker spheres. */
    public boolean showLayoutBoundaries = false;
    /** Draw the stage-8 embedded arc edge paths over the surface. */
    public boolean showEmbeddedArcs = false;

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

    public MotorcycleGraph motorcycleGraph;
    public SeamlessParameterization seamlessParametrization;

    /** VAO of the layout boundary GL_LINES buffer. */
    public int layoutLineVao;
    /** VBO of the layout boundary GL_LINES buffer. */
    public int layoutLineVbo;
    /** Vertex count of the layout boundary GL_LINES buffer. */
    public int layoutLineVertexCount;
    /** VAO of the Coons patch fill mesh. */
    public int layoutCoonsVao;
    /** VBO of the Coons patch fill mesh. */
    public int layoutCoonsVbo;
    /** EBO of the Coons patch fill mesh. */
    public int layoutCoonsEbo;
    /** Per-patch first index into the Coons EBO. */
    public int[] layoutPatchIndexStart;
    /** Per-patch index count in the Coons EBO. */
    public int[] layoutPatchIndexCount;
    /** Per-patch fill color, matching the surface palette hash. */
    public Color[] layoutPatchColors;
    /** Flat xyz positions of layout corner markers. */
    public float[] layoutCornerPositions;
    /** VAO of the embedded arc GL_LINES buffer. */
    public int embeddedLineVao;
    /** VBO of the embedded arc GL_LINES buffer. */
    public int embeddedLineVbo;
    /** Vertex count of the embedded arc GL_LINES buffer. */
    public int embeddedLineVertexCount;
    /** VAO of the zero-quantized arc GL_LINES buffer. */
    public int embeddedZeroLineVao;
    /** VBO of the zero-quantized arc GL_LINES buffer. */
    public int embeddedZeroLineVbo;
    /** Vertex count of the zero-quantized arc GL_LINES buffer. */
    public int embeddedZeroLineVertexCount;
    /** Flat xyz of each live embedded T-mesh node, for sphere markers. */
    public float[] embeddedNodePositions;
    /** Whether each live embedded T-mesh node is critical, parallel to {@link #embeddedNodePositions}. */
    public boolean[] embeddedNodeCritical;

    /** Draw the reroute-failure highlight (two disconnected regions, pivot, survivor, arc, channel). */
    public boolean showFailureHighlight;
    /** Flat xyz of the stranded arc's body-region vertices. */
    public float[] highlightBodyPositions;
    /** Flat xyz of the survivor's channel-region vertices. */
    public float[] highlightChannelPositions;
    /** Flat xyz of the pivot and survivor markers, in that order. */
    public float[] highlightMarkerPositions;
    /** VAO of the stranded arc's edge-path GL_LINES buffer. */
    public int highlightArcLineVao;
    /** VBO of the stranded arc's edge-path GL_LINES buffer. */
    public int highlightArcLineVbo;
    /** Vertex count of the stranded arc's edge-path GL_LINES buffer. */
    public int highlightArcLineVertexCount;
    /** VAO of the freed channel's GL_LINES buffer. */
    public int highlightChannelLineVao;
    /** VBO of the freed channel's GL_LINES buffer. */
    public int highlightChannelLineVbo;
    /** Vertex count of the freed channel's GL_LINES buffer. */
    public int highlightChannelLineVertexCount;
    /** Flat xyz of the claimed vertices ringing the body region — the wall. */
    public float[] highlightFencePositions;
    /** VAO of the pivot free-spoke GL_LINES buffer. */
    public int highlightSpokeLineVao;
    /** VBO of the pivot free-spoke GL_LINES buffer. */
    public int highlightSpokeLineVbo;
    /** Vertex count of the pivot free-spoke GL_LINES buffer. */
    public int highlightSpokeLineVertexCount;

    protected final Matrix4f sphereModel = new Matrix4f();
    protected final Matrix4f localProjection = new Matrix4f();

    protected int crossFieldIndexCount;

    private int crossFieldVao;
    private int crossFieldVbo;
    private int crossFieldEbo;
    private int constraintVao;
    private int constraintVbo;
    private int constraintEbo;
    private int constraintIndexCount;
    private int[] constraintRangeStart;
    private int[] constraintRangeCount;
    private float[] graphNodePositions;
    private Color[] graphNodeColors;
    private int graphNodeCount;

    /**
     * Build the runtime; defers parametrization upload to
     * {@link #setSeamlessParametrization(SeamlessParameterization)}.
     *
     * @throws Exception if the inherited {@link HalfEdgeMeshRuntime} or the
     *                   {@code MeshUv} shader fails to initialise
     */
    public QuadLayoutRuntime() throws Exception {
        super();
        this.uvShader = ShaderProgram.ShaderType.MeshUv.getShader();
        this.uvShader.init();
        this.traceUvShader = ShaderProgram.ShaderType.MeshUvTraces.getShader();
        this.traceUvShader.init();
        this.crossFieldShader = ShaderProgram.ShaderType.MeshCrossField.getShader();
        this.crossFieldShader.init();
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
        this.seamlessParametrization = seamless;
        if (seamless == null || seamless.uCorner == null || seamless.vCorner == null) {
            return;
        }
        uploadSeamlessSurface(seamless, motorcycleGraph);
        if (singularityVao == 0) {
            buildIcosphereBuffers();
        }
        updateSphereRadius();
        captureSingularities(seamless.crossField, seamless.mesh);
    }

    /**
     * Attach a built motorcycle graph and refresh trace GPU buffers.
     *
     * @param graph built motorcycle graph sharing the same seamless parametrization
     */
    public void setMotorcycleGraph(MotorcycleGraph graph) {
        this.motorcycleGraph = graph;
        if (graph == null || seamlessParametrization == null) {
            return;
        }
        uploadSeamlessSurface(seamlessParametrization, graph);
        captureGraphNodes(graph);
    }

    /**
     * Upload cross-field glyph geometry and singularity markers.
     *
     * @param field      built cross field
     * @param crossScale arm length scale relative to incircle radius
     */
    public void setCrossField(CrossField field, float crossScale) {
        uploadCrossField(field, crossScale);
        if (field == null || field.mesh == null) {
            return;
        }
        if (singularityVao == 0) {
            buildIcosphereBuffers();
        }
        updateSphereRadius();
        captureSingularities(field, field.mesh);
    }

    /**
     * Whether cross-arm geometry has been uploaded.
     *
     * @return {@code true} when glyph buffers are ready to draw
     */
    public boolean hasCrossField() {
        return crossFieldIndexCount > 0;
    }

    /**
     * Normalised face normal (counter-clockwise winding).
     *
     * @param p0     first vertex position
     * @param p1     second vertex position
     * @param p2     third vertex position
     * @param result destination — receives the normalised normal
     */
    protected static void computeFaceNormal(Vector3f p0, Vector3f p1, Vector3f p2,
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
     * Lazily allocate the shared unit-icosahedron buffers used by singularity and
     * motorcycle graph node spheres.
     */
    protected void buildIcosphereBuffers() {
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
    protected void updateSphereRadius() {
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
     * {@link #renderOverlays(Camera3D)} can draw a coloured sphere at each.
     *
     * @param crossField cross field whose singularities to render
     * @param mesh       the underlying triangle mesh
     */
    protected void captureSingularities(CrossField crossField, HalfEdgeMesh mesh) {
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
     * Render enabled quad-layout overlay layers. Call {@link #render(Camera3D)}
     * first when the translucent base mesh should appear underneath.
     *
     * @param camera active 3D camera
     */
    public void renderOverlays(Camera3D camera) {
        boolean drawSurface = hasParametrization()
                && (showIsoLines || showTraces || showFullIsoGrid);
        boolean drawCross = showCrossField && crossFieldIndexCount > 0;
        boolean drawConstraints = showConstraints && constraintIndexCount > 0;
        boolean drawSingularities = showSingularities
                && singularityIndex4 != null && singularityIndex4.length > 0;
        boolean drawNodes = showNodes && graphNodeCount > 0;
        boolean drawLayoutFill = showLayoutPatches
                && layoutPatchIndexCount != null && layoutPatchIndexCount.length > 0;
        boolean drawLayoutBoundaries = showLayoutBoundaries && layoutLineVertexCount > 0;
        boolean drawEmbeddedArcs = showEmbeddedArcs && (embeddedLineVertexCount > 0
                || embeddedZeroLineVertexCount > 0 || embeddedNodePositions != null);
        if (!drawSurface && !drawCross && !drawConstraints && !drawSingularities && !drawNodes
                && !drawLayoutFill && !drawLayoutBoundaries && !drawEmbeddedArcs) {
            return;
        }
        setupOverlayProjection(camera);
        GL gl = Platforms.gl();
        if (drawSurface) {
            if (showTraces || showFullIsoGrid) {
                renderTraceSurface(camera);
            } else if (showIsoLines) {
                renderIsoLines(camera);
            }
        }
        if (drawLayoutFill) {
            renderLayoutPatchFill(camera);
        }
        if (drawLayoutBoundaries) {
            renderLayoutBoundaries(camera);
        }
        if (drawEmbeddedArcs) {
            renderEmbeddedArcs(camera);
        }
        if (drawCross) {
            renderCrossFieldOverlay(camera);
        }
        if (drawConstraints) {
            renderConstraintOverlay(camera);
        }
        if (drawSingularities) {
            renderSingularitySpheres(camera, gl);
        }
        if (drawNodes) {
            renderGraphNodes(camera);
        }
    }

    protected void setupOverlayProjection(Camera3D camera) {
        int width = Platforms.get().getFrameBufferWidth();
        int height = Platforms.get().getFrameBufferHeight();
        float aspect = (width <= 0 || height <= 0) ? ASPECT_FALLBACK
                : ((float) width / (float) height);
        Vector3f bMin = getBoundingBoxMin();
        Vector3f bMax = getBoundingBoxMax();
        float diag = bMax.distance(bMin);
        localProjection.identity().perspective(
                (float) Math.toRadians((float) camera.fov),
                aspect, nearPlaneFor(camera), farPlaneFor(camera, diag));
    }

    private void renderIsoLines(Camera3D camera) {
        if (uvShader.ID < 0) {
            return;
        }
        GL gl = Platforms.gl();
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
        uvShader.setVec4(FLIPPED_COLOR_UNIFORM, flippedColor);
        gl.bindVertexArray(isoSurfaceVao);
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), isoSurfaceEbo);
        gl.drawElements(gl.TRIANGLES(), isoSurfaceIndexCount, gl.UNSIGNED_INT(), 0);
    }

    /**
     * Upload triangle-soup cross-field charts: per-face centroid, arm directions,
     * and arm length for screen-space iso-line rendering in
     * {@link #crossFieldShader}.
     *
     * @param field      built cross field
     * @param crossScale arm length scale relative to incircle radius
     */
    public void uploadCrossField(CrossField field, float crossScale) {
        if (field == null || field.theta == null || field.faceX == null || field.faceY == null) {
            return;
        }
        HalfEdgeMesh mesh = field.mesh;
        int faceCount = mesh.faceCount();
        float[] interleaved = new float[faceCount * CORNERS_PER_FACE * FLOATS_PER_CORNER_CROSS];
        int[] indices = new int[faceCount * CORNERS_PER_FACE];
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            int cornerBase = activeFace * CORNERS_PER_FACE;
            appendFaceGlyph(field, activeFace, field.theta[activeFace], crossScale, 0f, interleaved, cornerBase);
            indices[cornerBase] = cornerBase;
            indices[cornerBase + COMPONENT_Y] = cornerBase + COMPONENT_Y;
            indices[cornerBase + COMPONENT_Z] = cornerBase + COMPONENT_Z;
        }
        uploadCrossFieldBuffers(interleaved, indices);
    }

    /**
     * Upload constraint glyphs: the same cross primitive as
     * {@link #uploadCrossField(CrossField, float)} but oriented at each face's
     * {@code faceConstraintAngle} and emitted only for faces where
     * {@code faceConstrained} is set. Renders into the constraint buffers drawn by
     * {@link #renderConstraintOverlay(Camera3D)}.
     *
     * @param field      built cross field carrying {@code faceConstrained},
     *                   {@code faceConstraintAngle}, and
     *                   {@code faceConstraintSource}
     * @param crossScale arm length scale relative to incircle radius
     */
    public void uploadConstraints(CrossField field, float crossScale) {
        if (field == null || field.faceConstrained == null || field.faceConstraintAngle == null
                || field.faceConstraintSource == null || field.faceX == null || field.faceY == null) {
            return;
        }
        HalfEdgeMesh mesh = field.mesh;
        int faceCount = mesh.faceCount();
        int constrainedCount = 0;
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            if (field.faceConstrained[activeFace]) {
                constrainedCount++;
            }
        }
        float[] interleaved = new float[constrainedCount * CORNERS_PER_FACE * FLOATS_PER_CORNER_CROSS];
        int[] indices = new int[constrainedCount * CORNERS_PER_FACE];
        int sourceCount = ConstraintSource.values().length;
        constraintRangeStart = new int[sourceCount];
        constraintRangeCount = new int[sourceCount];
        int glyph = 0;
        for (ConstraintSource source : CONSTRAINT_DRAW_ORDER) {
            int rangeStart = glyph * CORNERS_PER_FACE;
            constraintRangeStart[source.ordinal()] = rangeStart;
            for (int activeFace = 0; activeFace < faceCount; activeFace++) {
                if (!field.faceConstrained[activeFace] || field.faceConstraintSource[activeFace] != source) {
                    continue;
                }
                int cornerBase = glyph * CORNERS_PER_FACE;
                appendFaceGlyph(field, activeFace, field.faceConstraintAngle[activeFace], crossScale,
                        CONSTRAINT_NORMAL_LIFT, interleaved, cornerBase);
                indices[cornerBase] = cornerBase;
                indices[cornerBase + COMPONENT_Y] = cornerBase + COMPONENT_Y;
                indices[cornerBase + COMPONENT_Z] = cornerBase + COMPONENT_Z;
                glyph++;
            }
            constraintRangeCount[source.ordinal()] = glyph * CORNERS_PER_FACE - rangeStart;
        }
        uploadConstraintBuffers(interleaved, indices);
    }

    /**
     * Compute the cross glyph for one face and write its three triangle-soup
     * corners. The glyph is a unit-length pair of perpendicular arms in the face
     * plane; the first arm points along {@code angle} measured in the face's local
     * {@code (faceX, faceY)} frame, the second is its in-plane perpendicular.
     *
     * @param field              cross field supplying the mesh and per-face frame
     * @param activeFace         dense active-face index of the face to glyph
     * @param angle              first-arm direction in the face's local frame,
     *                           radians
     * @param crossScale         arm length scale relative to the face incircle
     *                           radius
     * @param normalLiftFraction fraction of arm length to float the glyph along the
     *                           face normal (0 keeps it on the surface)
     * @param interleaved        destination vertex buffer
     * @param cornerBase         index of this glyph's first corner within the
     *                           buffer
     */
    private static void appendFaceGlyph(CrossField field, int activeFace, float angle, float crossScale,
            float normalLiftFraction, float[] interleaved, int cornerBase) {
        HalfEdgeMesh mesh = field.mesh;
        int faceId = mesh.faceIdAt(activeFace);
        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f p2 = new Vector3f();
        Vector3f normal = new Vector3f();
        mesh.vertexPosition(mesh.faceVertexAt(faceId, 0), p0);
        mesh.vertexPosition(mesh.faceVertexAt(faceId, COMPONENT_Y), p1);
        mesh.vertexPosition(mesh.faceVertexAt(faceId, COMPONENT_Z), p2);
        computeFaceNormal(p0, p1, p2, normal);
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
        float armLength = Math.max(crossScale * incircleR, 1.0e-8f);
        float lift = normalLiftFraction * armLength;
        cx += normal.x * lift;
        cy += normal.y * lift;
        cz += normal.z * lift;
        p0.add(normal.x * lift, normal.y * lift, normal.z * lift);
        p1.add(normal.x * lift, normal.y * lift, normal.z * lift);
        p2.add(normal.x * lift, normal.y * lift, normal.z * lift);
        float cosT = (float) Math.cos(angle);
        float sinT = (float) Math.sin(angle);
        Vector3f fx = field.faceX[activeFace];
        Vector3f fy = field.faceY[activeFace];
        float dirUx = cosT * fx.x + sinT * fy.x;
        float dirUy = cosT * fx.y + sinT * fy.y;
        float dirUz = cosT * fx.z + sinT * fy.z;
        float dirVx = -sinT * fx.x + cosT * fy.x;
        float dirVy = -sinT * fx.y + cosT * fy.y;
        float dirVz = -sinT * fx.z + cosT * fy.z;
        float dirULen = (float) Math.sqrt(dirUx * dirUx + dirUy * dirUy + dirUz * dirUz);
        float dirVLen = (float) Math.sqrt(dirVx * dirVx + dirVy * dirVy + dirVz * dirVz);
        if (dirULen > 0f) {
            dirUx /= dirULen;
            dirUy /= dirULen;
            dirUz /= dirULen;
        }
        if (dirVLen > 0f) {
            dirVx /= dirVLen;
            dirVy /= dirVLen;
            dirVz /= dirVLen;
        }
        writeCrossCorner(interleaved, cornerBase * FLOATS_PER_CORNER_CROSS, p0, normal,
                cx, cy, cz, dirUx, dirUy, dirUz, dirVx, dirVy, dirVz, armLength);
        writeCrossCorner(interleaved, (cornerBase + COMPONENT_Y) * FLOATS_PER_CORNER_CROSS, p1, normal,
                cx, cy, cz, dirUx, dirUy, dirUz, dirVx, dirVy, dirVz, armLength);
        writeCrossCorner(interleaved, (cornerBase + COMPONENT_Z) * FLOATS_PER_CORNER_CROSS, p2, normal,
                cx, cy, cz, dirUx, dirUy, dirUz, dirVx, dirVy, dirVz, armLength);
    }

    private static void writeCrossCorner(float[] buffer, int offset, Vector3f position, Vector3f normal,
            float cx, float cy, float cz,
            float dirUx, float dirUy, float dirUz, float dirVx, float dirVy, float dirVz,
            float armLength) {
        buffer[offset] = position.x;
        buffer[offset + COMPONENT_Y] = position.y;
        buffer[offset + COMPONENT_Z] = position.z;
        buffer[offset + NORMAL_X_OFFSET] = normal.x;
        buffer[offset + NORMAL_Y_OFFSET] = normal.y;
        buffer[offset + NORMAL_Z_OFFSET] = normal.z;
        buffer[offset + CENTROID_OFFSET] = cx;
        buffer[offset + CENTROID_OFFSET + COMPONENT_Y] = cy;
        buffer[offset + CENTROID_OFFSET + COMPONENT_Z] = cz;
        buffer[offset + DIR_U_OFFSET] = dirUx;
        buffer[offset + DIR_U_OFFSET + COMPONENT_Y] = dirUy;
        buffer[offset + DIR_U_OFFSET + COMPONENT_Z] = dirUz;
        buffer[offset + DIR_V_OFFSET] = dirVx;
        buffer[offset + DIR_V_OFFSET + COMPONENT_Y] = dirVy;
        buffer[offset + DIR_V_OFFSET + COMPONENT_Z] = dirVz;
        buffer[offset + ARM_LENGTH_OFFSET] = armLength;
    }

    private void uploadCrossFieldBuffers(float[] interleaved, int[] indices) {
        int[] handles = uploadGlyphBuffers(crossFieldVao, crossFieldVbo, crossFieldEbo, interleaved, indices);
        crossFieldVao = handles[COMPONENT_X];
        crossFieldVbo = handles[COMPONENT_Y];
        crossFieldEbo = handles[COMPONENT_Z];
        crossFieldIndexCount = indices.length;
    }

    private void uploadConstraintBuffers(float[] interleaved, int[] indices) {
        int[] handles = uploadGlyphBuffers(constraintVao, constraintVbo, constraintEbo, interleaved, indices);
        constraintVao = handles[COMPONENT_X];
        constraintVbo = handles[COMPONENT_Y];
        constraintEbo = handles[COMPONENT_Z];
        constraintIndexCount = indices.length;
    }

    /**
     * Free the previous VAO/VBO/EBO triple, allocate fresh ones, and upload the
     * cross-glyph vertex layout shared by the cross-field and constraint overlays.
     *
     * @param prevVao     existing VAO handle to free, or {@code 0} if none
     * @param prevVbo     existing VBO handle to free, or {@code 0} if none
     * @param prevEbo     existing EBO handle to free, or {@code 0} if none
     * @param interleaved interleaved corner vertex data to upload
     * @param indices     element indices to upload
     * @return the freshly allocated {@code [vao, vbo, ebo]} handles
     */
    private int[] uploadGlyphBuffers(int prevVao, int prevVbo, int prevEbo, float[] interleaved, int[] indices) {
        GL gl = Platforms.gl();
        if (prevVao != 0) {
            gl.deleteVertexArrays(prevVao);
        }
        if (prevVbo != 0) {
            gl.deleteBuffers(prevVbo);
        }
        if (prevEbo != 0) {
            gl.deleteBuffers(prevEbo);
        }
        int vao = gl.genVertexArrays();
        int vbo = gl.genBuffers();
        int ebo = gl.genBuffers();
        gl.bindVertexArray(vao);
        gl.bindBuffer(gl.ARRAY_BUFFER(), vbo);
        gl.bufferData(gl.ARRAY_BUFFER(), interleaved, gl.STATIC_DRAW());
        int strideBytes = FLOATS_PER_CORNER_CROSS * Float.BYTES;
        gl.vertexAttribPointer(ATTR_POSITION, VEC3_SIZE, gl.FLOAT(), false, strideBytes, 0);
        gl.enableVertexAttribArray(ATTR_POSITION);
        gl.vertexAttribPointer(ATTR_NORMAL, VEC3_SIZE, gl.FLOAT(), false, strideBytes, NORMAL_OFFSET_BYTES);
        gl.enableVertexAttribArray(ATTR_NORMAL);
        gl.vertexAttribPointer(ATTR_CENTROID, VEC3_SIZE, gl.FLOAT(), false, strideBytes,
                CENTROID_OFFSET * Float.BYTES);
        gl.enableVertexAttribArray(ATTR_CENTROID);
        gl.vertexAttribPointer(ATTR_DIR_U, VEC3_SIZE, gl.FLOAT(), false, strideBytes,
                DIR_U_OFFSET * Float.BYTES);
        gl.enableVertexAttribArray(ATTR_DIR_U);
        gl.vertexAttribPointer(ATTR_DIR_V, VEC3_SIZE, gl.FLOAT(), false, strideBytes,
                DIR_V_OFFSET * Float.BYTES);
        gl.enableVertexAttribArray(ATTR_DIR_V);
        gl.vertexAttribPointer(ATTR_ARM_LENGTH, 1, gl.FLOAT(), false, strideBytes,
                ARM_LENGTH_OFFSET * Float.BYTES);
        gl.enableVertexAttribArray(ATTR_ARM_LENGTH);
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), ebo);
        IntBuffer ib = BufferUtils.createIntBuffer(indices.length);
        ib.put(indices).flip();
        gl.bufferData(gl.ELEMENT_ARRAY_BUFFER(), ib, gl.STATIC_DRAW());
        return new int[] { vao, vbo, ebo };
    }

    private void uploadSeamlessSurface(SeamlessParameterization seamless, MotorcycleGraph graph) {
        HalfEdgeMesh mesh = seamless.mesh;
        int faceCount = mesh.faceCount();
        float[] interleaved = new float[faceCount * CORNERS_PER_FACE * FLOATS_PER_CORNER_WITH_TRACES];
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
            int baseFloat = cornerBase * FLOATS_PER_CORNER_WITH_TRACES;
            float flipped = seamless.uvSignedArea(faceId) <= 0f ? 1f : 0f;
            float[] traceRow = graph != null && graph.traceRecordsByFace != null
                    ? graph.traceRecordsByFace[activeFace]
                    : null;
            writeCornerWithTraces(interleaved, baseFloat, p0, normal,
                    (float) seamless.uCorner[cornerBase], (float) seamless.vCorner[cornerBase],
                    flipped, traceRow);
            writeCornerWithTraces(interleaved, baseFloat + FLOATS_PER_CORNER_WITH_TRACES, p1, normal,
                    (float) seamless.uCorner[cornerBase + COMPONENT_Y],
                    (float) seamless.vCorner[cornerBase + COMPONENT_Y], flipped, traceRow);
            writeCornerWithTraces(interleaved, baseFloat + COMPONENT_Z * FLOATS_PER_CORNER_WITH_TRACES, p2, normal,
                    (float) seamless.uCorner[cornerBase + COMPONENT_Z],
                    (float) seamless.vCorner[cornerBase + COMPONENT_Z], flipped, traceRow);
            indices[cornerBase] = cornerBase;
            indices[cornerBase + COMPONENT_Y] = cornerBase + COMPONENT_Y;
            indices[cornerBase + COMPONENT_Z] = cornerBase + COMPONENT_Z;
        }
        uploadTraceSurfaceBuffers(interleaved, indices);
    }

    /**
     * Upload a per-patch rectangle parametrization as the iso-surface, shading each caller-flagged
     * folded face with {@link #flippedColor}. Takes raw per-corner UVs and per-face flip flags, so it
     * shows the embedded T-mesh's per-patch maps, which have no {@link CrossField}.
     *
     * @param mesh        the copy mesh whose faces the arrays index, one corner triple per face
     * @param cornerU     rectangle x per face corner, length {@code 3 * mesh.faceCount()}
     * @param cornerV     rectangle y per face corner, parallel to {@code cornerU}
     * @param faceFlipped whether each face folds in its patch's map, indexed by active face
     */
    public void uploadPatchParametrization(HalfEdgeMesh mesh, double[] cornerU, double[] cornerV,
            boolean[] faceFlipped) {
        int faceCount = mesh.faceCount();
        float[] interleaved = new float[faceCount * CORNERS_PER_FACE * FLOATS_PER_CORNER_WITH_TRACES];
        int[] indices = new int[faceCount * CORNERS_PER_FACE];
        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f p2 = new Vector3f();
        Vector3f normal = new Vector3f();
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            mesh.vertexPosition(mesh.faceVertexAt(faceId, 0), p0);
            mesh.vertexPosition(mesh.faceVertexAt(faceId, 1), p1);
            mesh.vertexPosition(mesh.faceVertexAt(faceId, COMPONENT_Z), p2);
            computeFaceNormal(p0, p1, p2, normal);
            int cornerBase = activeFace * CORNERS_PER_FACE;
            int baseFloat = cornerBase * FLOATS_PER_CORNER_WITH_TRACES;
            float flipped = faceFlipped[activeFace] ? 1f : 0f;
            writeCornerWithTraces(interleaved, baseFloat, p0, normal,
                    (float) cornerU[cornerBase], (float) cornerV[cornerBase], flipped, null);
            writeCornerWithTraces(interleaved, baseFloat + FLOATS_PER_CORNER_WITH_TRACES, p1, normal,
                    (float) cornerU[cornerBase + COMPONENT_Y], (float) cornerV[cornerBase + COMPONENT_Y],
                    flipped, null);
            writeCornerWithTraces(interleaved, baseFloat + COMPONENT_Z * FLOATS_PER_CORNER_WITH_TRACES,
                    p2, normal, (float) cornerU[cornerBase + COMPONENT_Z],
                    (float) cornerV[cornerBase + COMPONENT_Z], flipped, null);
            indices[cornerBase] = cornerBase;
            indices[cornerBase + COMPONENT_Y] = cornerBase + COMPONENT_Y;
            indices[cornerBase + COMPONENT_Z] = cornerBase + COMPONENT_Z;
        }
        uploadTraceSurfaceBuffers(interleaved, indices);
    }

    private static void writeCornerWithTraces(float[] buffer, int offset, Vector3f position,
            Vector3f normal, float u, float v, float flipped, float[] traceRow) {
        buffer[offset] = position.x;
        buffer[offset + COMPONENT_Y] = position.y;
        buffer[offset + COMPONENT_Z] = position.z;
        buffer[offset + NORMAL_X_OFFSET] = normal.x;
        buffer[offset + NORMAL_Y_OFFSET] = normal.y;
        buffer[offset + NORMAL_Z_OFFSET] = normal.z;
        buffer[offset + U_OFFSET] = u;
        buffer[offset + V_OFFSET] = v;
        buffer[offset + FLIP_OFFSET] = flipped;
        for (int record = 0; record < TRACE_RECORDS_PER_FACE; record++) {
            int traceOffset = offset + TRACE0_OFFSET + record * FLOATS_PER_TRACE_RECORD;
            if (traceRow != null) {
                int base = record * FLOATS_PER_TRACE_RECORD;
                buffer[traceOffset] = traceRow[base];
                buffer[traceOffset + 1] = traceRow[base + 1];
                buffer[traceOffset + 2] = traceRow[base + 2];
                buffer[traceOffset + 3] = traceRow[base + 3];
            } else {
                buffer[traceOffset] = 0f;
                buffer[traceOffset + 1] = 0f;
                buffer[traceOffset + 2] = 0f;
                buffer[traceOffset + 3] = 0f;
            }
        }
    }

    private void uploadTraceSurfaceBuffers(float[] interleaved, int[] indices) {
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
        int strideBytes = FLOATS_PER_CORNER_WITH_TRACES * Float.BYTES;
        gl.vertexAttribPointer(ATTR_POSITION, VEC3_SIZE, gl.FLOAT(), false, strideBytes, 0);
        gl.enableVertexAttribArray(ATTR_POSITION);
        gl.vertexAttribPointer(ATTR_NORMAL, VEC3_SIZE, gl.FLOAT(), false, strideBytes, NORMAL_OFFSET_BYTES);
        gl.enableVertexAttribArray(ATTR_NORMAL);
        gl.vertexAttribPointer(ATTR_UV, 2, gl.FLOAT(), false, strideBytes, UV_OFFSET_BYTES);
        gl.enableVertexAttribArray(ATTR_UV);
        gl.vertexAttribPointer(ATTR_FLIP, 1, gl.FLOAT(), false, strideBytes, FLIP_OFFSET_BYTES);
        gl.enableVertexAttribArray(ATTR_FLIP);
        gl.vertexAttribPointer(ATTR_TRACE0, 4, gl.FLOAT(), false, strideBytes, TRACE0_OFFSET * Float.BYTES);
        gl.enableVertexAttribArray(ATTR_TRACE0);
        gl.vertexAttribPointer(ATTR_TRACE1, 4, gl.FLOAT(), false, strideBytes, TRACE1_OFFSET * Float.BYTES);
        gl.enableVertexAttribArray(ATTR_TRACE1);
        gl.vertexAttribPointer(ATTR_TRACE2, 4, gl.FLOAT(), false, strideBytes, TRACE2_OFFSET * Float.BYTES);
        gl.enableVertexAttribArray(ATTR_TRACE2);
        gl.vertexAttribPointer(ATTR_TRACE3, 4, gl.FLOAT(), false, strideBytes, TRACE3_OFFSET * Float.BYTES);
        gl.enableVertexAttribArray(ATTR_TRACE3);
        gl.vertexAttribPointer(ATTR_PATCH_ID, 1, gl.FLOAT(), false, strideBytes, PATCH_ID_OFFSET_BYTES);
        gl.enableVertexAttribArray(ATTR_PATCH_ID);
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), isoSurfaceEbo);
        IntBuffer ib = BufferUtils.createIntBuffer(indices.length);
        ib.put(indices).flip();
        gl.bufferData(gl.ELEMENT_ARRAY_BUFFER(), ib, gl.STATIC_DRAW());
        isoSurfaceIndexCount = indices.length;
    }

    private void captureGraphNodes(MotorcycleGraph graph) {
        graphNodeCount = graph.nodes.size();
        graphNodePositions = new float[FLOATS_PER_SPHERE_VERTEX * graphNodeCount];
        graphNodeColors = new Color[graphNodeCount];
        for (int i = 0; i < graphNodeCount; i++) {
            TMeshNode node = graph.nodes.get(i);
            int posBase = FLOATS_PER_SPHERE_VERTEX * i;
            graphNodePositions[posBase] = node.position.x;
            graphNodePositions[posBase + COMPONENT_Y] = node.position.y;
            graphNodePositions[posBase + COMPONENT_Z] = node.position.z;
            graphNodeColors[i] = nodeColor(node);
        }
    }

    private static Color nodeColor(TMeshNode node) {
        if (node.type == TMeshNode.Type.SINGULARITY) {
            return node.singularityIndex4 > 0 ? COLOR_POSITIVE_INDEX : COLOR_NEGATIVE_INDEX;
        }
        if (node.type == TMeshNode.Type.INTERSECTION) {
            return COLOR_INTERSECTION_NODE;
        }
        if (node.type == TMeshNode.Type.FEATURE) {
            return COLOR_FEATURE_NODE;
        }
        if (node.type == TMeshNode.Type.TRUNCATED) {
            return COLOR_TRUNCATED_NODE;
        }
        return COLOR_BOUNDARY_NODE;
    }

    private void renderTraceSurface(Camera3D camera) {
        if (traceUvShader.ID < 0) {
            return;
        }
        GL gl = Platforms.gl();
        traceUvShader.use();
        traceUvShader.setMat4(VIEW, camera.view);
        traceUvShader.setMat4(PROJECTION, localProjection);
        sphereModel.identity();
        traceUvShader.setMat4(MODEL, sphereModel);
        traceUvShader.setFloat(DEPTHBIAS, 0f);
        traceUvShader.setVec4(BASE_COLOR, baseColor);
        traceUvShader.setVec4(U_LINE_COLOR, uLineColor);
        traceUvShader.setVec4(V_LINE_COLOR, vLineColor);
        traceUvShader.setFloat(LINE_HALF_WIDTH, lineHalfWidth);
        traceUvShader.setVec4(FLIPPED_COLOR_UNIFORM, flippedColor);
        traceUvShader.setFloat(DRAW_FULL_ISO_GRID_UNIFORM, showFullIsoGrid ? 1f : 0f);
        traceUvShader.setFloat(USE_PATCH_COLOR_UNIFORM, 0f);
        gl.bindVertexArray(isoSurfaceVao);
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), isoSurfaceEbo);
        gl.drawElements(gl.TRIANGLES(), isoSurfaceIndexCount, gl.UNSIGNED_INT(), 0);
    }

    private void renderCrossFieldOverlay(Camera3D camera) {
        if (crossFieldIndexCount <= 0 || crossFieldShader.ID < 0) {
            return;
        }
        GL gl = Platforms.gl();
        crossFieldShader.use();
        crossFieldShader.setMat4(VIEW, camera.view);
        crossFieldShader.setMat4(PROJECTION, localProjection);
        sphereModel.identity();
        crossFieldShader.setMat4(MODEL, sphereModel);
        crossFieldShader.setFloat(DEPTHBIAS, 0f);
        crossFieldShader.setVec4(U_LINE_COLOR, COLOR_U_ARM);
        crossFieldShader.setVec4(V_LINE_COLOR, COLOR_V_ARM);
        crossFieldShader.setFloat(LINE_HALF_WIDTH, lineHalfWidth);
        gl.bindVertexArray(crossFieldVao);
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), crossFieldEbo);
        gl.drawElements(gl.TRIANGLES(), crossFieldIndexCount, gl.UNSIGNED_INT(), 0);
    }

    private void renderConstraintOverlay(Camera3D camera) {
        if (constraintIndexCount <= 0 || crossFieldShader.ID < 0 || constraintRangeCount == null) {
            return;
        }
        GL gl = Platforms.gl();
        crossFieldShader.use();
        crossFieldShader.setMat4(VIEW, camera.view);
        crossFieldShader.setMat4(PROJECTION, localProjection);
        sphereModel.identity();
        crossFieldShader.setMat4(MODEL, sphereModel);
        crossFieldShader.setFloat(DEPTHBIAS, 0f);
        crossFieldShader.setFloat(LINE_HALF_WIDTH, lineHalfWidth);
        gl.bindVertexArray(constraintVao);
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), constraintEbo);
        for (ConstraintSource source : CONSTRAINT_DRAW_ORDER) {
            int count = constraintRangeCount[source.ordinal()];
            if (count <= 0) {
                continue;
            }
            Color color = constraintSourceColor(source);
            crossFieldShader.setVec4(U_LINE_COLOR, color);
            crossFieldShader.setVec4(V_LINE_COLOR, color);
            int offsetBytes = constraintRangeStart[source.ordinal()] * Integer.BYTES;
            gl.drawElements(gl.TRIANGLES(), count, gl.UNSIGNED_INT(), offsetBytes);
        }
    }

    private static Color constraintSourceColor(ConstraintSource source) {
        return switch (source) {
        case BOUNDARY -> COLOR_CONSTRAINT_BOUNDARY;
        case FEATURE -> COLOR_CONSTRAINT_FEATURE;
        case CURVATURE -> COLOR_CONSTRAINT_CURVATURE;
        default -> COLOR_CONSTRAINT_ANCHOR;
        };
    }

    private void renderGraphNodes(Camera3D camera) {
        if (graphNodeCount <= 0 || singularityVao == 0 || unlitShader.ID < 0) {
            return;
        }
        GL gl = Platforms.gl();
        unlitShader.use();
        unlitShader.setMat4(VIEW, camera.view);
        unlitShader.setMat4(PROJECTION, localProjection);
        unlitShader.setFloat(DEPTHBIAS, 0f);
        gl.bindVertexArray(singularityVao);
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), singularityEbo);
        for (int i = 0; i < graphNodeCount; i++) {
            int posBase = FLOATS_PER_SPHERE_VERTEX * i;
            float px = graphNodePositions[posBase];
            float py = graphNodePositions[posBase + COMPONENT_Y];
            float pz = graphNodePositions[posBase + COMPONENT_Z];
            sphereModel.identity().translate(px, py, pz).scale(sphereRadius);
            unlitShader.setMat4(MODEL, sphereModel);
            unlitShader.setVec4(SOLIDCOLOR, graphNodeColors[i]);
            gl.drawElements(gl.TRIANGLES(), singularityIndexCount, gl.UNSIGNED_INT(), 0);
        }
    }

    /**
     * Draw one coloured sphere per singularity over the iso-line surface with a
     * small depth bias so the spheres aren't z-fought by the surface.
     *
     * @param camera the 3D camera (used only for the inherited view matrix)
     * @param gl     active GL platform handle
     */
    protected void renderSingularitySpheres(Camera3D camera, GL gl) {
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
            Color color = singularityIndex4[i] > 0
                    ? COLOR_POSITIVE_INDEX
                    : COLOR_NEGATIVE_INDEX;
            unlitShader.setVec4(SOLIDCOLOR, color);
            gl.drawElements(gl.TRIANGLES(), singularityIndexCount, gl.UNSIGNED_INT(), 0);
        }
    }

    /**
     * Upload the explicit layout geometry: boundary polylines as a GL_LINES buffer,
     * clean patches' Coons grids as a triangle mesh with per-patch index ranges and
     * palette-hashed colors, and corner marker positions. Re-uploading frees the
     * previous buffers first.
     *
     * @param geometry traced and validated patch geometry
     */
    public void setLayoutPatchGeometry(LayoutPatchGeometry geometry) {
        GL gl = Platforms.gl();
        deleteLayoutBuffers(gl);

        int lineFloatCount = 0;
        int coonsPatchCount = 0;
        int cornerCount = 0;
        for (LayoutPatchCurves patch : geometry.patches) {
            for (List<Vector3f> polyline : patch.sidePolylines) {
                if (polyline.size() > 1) {
                    lineFloatCount += (polyline.size() - 1) * 2 * VEC3_SIZE;
                }
            }
            if (patch.coonsGrid != null) {
                coonsPatchCount++;
            }
            for (Vector3f corner : patch.cornerPositions) {
                if (corner != null) {
                    cornerCount++;
                }
            }
        }

        float[] lineVertices = new float[lineFloatCount];
        layoutCornerPositions = new float[cornerCount * VEC3_SIZE];
        int lineCursor = 0;
        int cornerCursor = 0;
        for (LayoutPatchCurves patch : geometry.patches) {
            for (List<Vector3f> polyline : patch.sidePolylines) {
                for (int index = 1; index < polyline.size(); index++) {
                    lineCursor = writePoint(lineVertices, lineCursor, polyline.get(index - 1));
                    lineCursor = writePoint(lineVertices, lineCursor, polyline.get(index));
                }
            }
            for (Vector3f corner : patch.cornerPositions) {
                if (corner != null) {
                    cornerCursor = writePoint(layoutCornerPositions, cornerCursor, corner);
                }
            }
        }
        layoutLineVertexCount = lineCursor / VEC3_SIZE;
        if (layoutLineVertexCount > 0) {
            layoutLineVao = gl.genVertexArrays();
            layoutLineVbo = gl.genBuffers();
            gl.bindVertexArray(layoutLineVao);
            gl.bindBuffer(gl.ARRAY_BUFFER(), layoutLineVbo);
            gl.bufferData(gl.ARRAY_BUFFER(), lineVertices, gl.STATIC_DRAW());
            gl.vertexAttribPointer(ATTR_POSITION, VEC3_SIZE, gl.FLOAT(), false,
                    VEC3_SIZE * Float.BYTES, 0);
            gl.enableVertexAttribArray(ATTR_POSITION);
        }

        int samples = LayoutPatchGeometry.COONS_SAMPLES;
        int verticesPerPatch = samples * samples;
        int indicesPerPatch = (samples - 1) * (samples - 1) * INDICES_PER_GRID_CELL;
        float[] coonsVertices = new float[coonsPatchCount * verticesPerPatch * VEC3_SIZE];
        IntBuffer coonsIndices = BufferUtils.createIntBuffer(coonsPatchCount * indicesPerPatch);
        layoutPatchIndexStart = new int[coonsPatchCount];
        layoutPatchIndexCount = new int[coonsPatchCount];
        layoutPatchColors = new Color[coonsPatchCount];
        int patchCursor = 0;
        int vertexBase = 0;
        for (LayoutPatchCurves patch : geometry.patches) {
            if (patch.coonsGrid == null) {
                continue;
            }
            System.arraycopy(patch.coonsGrid, 0, coonsVertices,
                    vertexBase * VEC3_SIZE, patch.coonsGrid.length);
            layoutPatchIndexStart[patchCursor] = coonsIndices.position();
            for (int row = 0; row < samples - 1; row++) {
                for (int column = 0; column < samples - 1; column++) {
                    int corner00 = vertexBase + row * samples + column;
                    int corner10 = corner00 + 1;
                    int corner01 = corner00 + samples;
                    int corner11 = corner01 + 1;
                    coonsIndices.put(corner00).put(corner10).put(corner01);
                    coonsIndices.put(corner10).put(corner11).put(corner01);
                }
            }
            layoutPatchIndexCount[patchCursor] = indicesPerPatch;
            layoutPatchColors[patchCursor] = PatchColorHash.colorForPatch(
                    patch.rootPatchId, LAYOUT_PATCH_ALPHA);
            patchCursor++;
            vertexBase += verticesPerPatch;
        }
        if (coonsPatchCount > 0) {
            coonsIndices.flip();
            layoutCoonsVao = gl.genVertexArrays();
            layoutCoonsVbo = gl.genBuffers();
            layoutCoonsEbo = gl.genBuffers();
            gl.bindVertexArray(layoutCoonsVao);
            gl.bindBuffer(gl.ARRAY_BUFFER(), layoutCoonsVbo);
            gl.bufferData(gl.ARRAY_BUFFER(), coonsVertices, gl.STATIC_DRAW());
            gl.vertexAttribPointer(ATTR_POSITION, VEC3_SIZE, gl.FLOAT(), false,
                    VEC3_SIZE * Float.BYTES, 0);
            gl.enableVertexAttribArray(ATTR_POSITION);
            gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), layoutCoonsEbo);
            gl.bufferData(gl.ELEMENT_ARRAY_BUFFER(), coonsIndices, gl.STATIC_DRAW());
        }

        if (singularityVao == 0) {
            buildIcosphereBuffers();
        }
        updateSphereRadius();
    }

    /**
     * Upload the stage-8 embedded arc edge paths as a GL_LINES buffer: every
     * routed arc contributes one segment per copy edge of its path, with vertex
     * positions read from the embedding's working copy mesh.
     *
     * @param embedding built layout embedding with routed arc paths
     */
    public void setLayoutEmbedding(LayoutEmbedding embedding) {
        GL gl = Platforms.gl();
        deleteEmbeddedBuffers(gl);
        int segmentCount = 0;
        for (ArcEdgePath path : embedding.pathByArc) {
            if (path != null && path.copyVertexPath.size() > 1) {
                segmentCount += path.copyVertexPath.size() - 1;
            }
        }
        if (segmentCount == 0) {
            return;
        }
        float[] vertices = new float[segmentCount * 2 * VEC3_SIZE];
        int cursor = 0;
        Vector3f position = new Vector3f();
        for (ArcEdgePath path : embedding.pathByArc) {
            if (path == null || path.copyVertexPath.size() < 2) {
                continue;
            }
            for (int index = 1; index < path.copyVertexPath.size(); index++) {
                embedding.topology.copy.vertexPosition(path.copyVertexPath.get(index - 1), position);
                cursor = writePoint(vertices, cursor, position);
                embedding.topology.copy.vertexPosition(path.copyVertexPath.get(index), position);
                cursor = writePoint(vertices, cursor, position);
            }
        }
        embeddedLineVertexCount = cursor / VEC3_SIZE;
        embeddedLineVao = gl.genVertexArrays();
        embeddedLineVbo = gl.genBuffers();
        gl.bindVertexArray(embeddedLineVao);
        gl.bindBuffer(gl.ARRAY_BUFFER(), embeddedLineVbo);
        gl.bufferData(gl.ARRAY_BUFFER(), vertices, gl.STATIC_DRAW());
        gl.vertexAttribPointer(ATTR_POSITION, VEC3_SIZE, gl.FLOAT(), false,
                VEC3_SIZE * Float.BYTES, 0);
        gl.enableVertexAttribArray(ATTR_POSITION);
    }

    /**
     * Upload an embedded T-mesh for the debug overlay: live arcs as edge-path lines, positive
     * and zero arcs in separate buffers, and live nodes as sphere markers coloured by
     * criticality.
     *
     * <p>See also: LCBK19 Figure 9
     *
     * @param tmesh embedded T-mesh to draw; retired elements are skipped
     */
    public void setEmbeddedTMesh(EmbeddedTMesh tmesh) {
        GL gl = Platforms.gl();
        deleteEmbeddedBuffers(gl);
        HalfEdgeMesh copy = tmesh.topology.copy;
        int positiveSegments = 0;
        int zeroSegments = 0;
        for (EmbeddedArc arc : tmesh.arcs) {
            if (!arc.alive || arc.path.copyVertexPath.size() < 2) {
                continue;
            }
            int segments = arc.path.copyVertexPath.size() - 1;
            if (arc.quantizedLength == 0) {
                zeroSegments += segments;
            } else {
                positiveSegments += segments;
            }
        }
        float[] positive = new float[positiveSegments * 2 * VEC3_SIZE];
        float[] zero = new float[zeroSegments * 2 * VEC3_SIZE];
        int positiveCursor = 0;
        int zeroCursor = 0;
        Vector3f position = new Vector3f();
        for (EmbeddedArc arc : tmesh.arcs) {
            List<Integer> path = arc.path.copyVertexPath;
            if (!arc.alive || path.size() < 2) {
                continue;
            }
            boolean isZero = arc.quantizedLength == 0;
            for (int index = 1; index < path.size(); index++) {
                copy.vertexPosition(path.get(index - 1), position);
                positiveCursor = isZero ? positiveCursor : writePoint(positive, positiveCursor, position);
                zeroCursor = isZero ? writePoint(zero, zeroCursor, position) : zeroCursor;
                copy.vertexPosition(path.get(index), position);
                positiveCursor = isZero ? positiveCursor : writePoint(positive, positiveCursor, position);
                zeroCursor = isZero ? writePoint(zero, zeroCursor, position) : zeroCursor;
            }
        }
        int[] positiveBuffers = uploadLineBuffer(gl, positive);
        embeddedLineVao = positiveBuffers[0];
        embeddedLineVbo = positiveBuffers[1];
        embeddedLineVertexCount = positiveCursor / VEC3_SIZE;
        int[] zeroBuffers = uploadLineBuffer(gl, zero);
        embeddedZeroLineVao = zeroBuffers[0];
        embeddedZeroLineVbo = zeroBuffers[1];
        embeddedZeroLineVertexCount = zeroCursor / VEC3_SIZE;

        int liveNodes = 0;
        for (EmbeddedNode node : tmesh.nodes) {
            if (node.alive) {
                liveNodes++;
            }
        }
        embeddedNodePositions = new float[liveNodes * VEC3_SIZE];
        embeddedNodeCritical = new boolean[liveNodes];
        int nodeCursor = 0;
        int nodeIndex = 0;
        for (EmbeddedNode node : tmesh.nodes) {
            if (!node.alive) {
                continue;
            }
            copy.vertexPosition(node.copyVertex, position);
            nodeCursor = writePoint(embeddedNodePositions, nodeCursor, position);
            embeddedNodeCritical[nodeIndex] = node.critical;
            nodeIndex++;
        }
        if (singularityVao == 0) {
            buildIcosphereBuffers();
        }
        updateSphereRadius();
        showEmbeddedArcs = true;
    }

    /**
     * Upload a flat xyz array as a position-only GL_LINES buffer.
     *
     * @param gl       active GL platform handle
     * @param vertices flat {@code x, y, z} triples, two per line segment
     * @return the {@code {vao, vbo}} handles, both zero when there is nothing to upload
     */
    private int[] uploadLineBuffer(GL gl, float[] vertices) {
        if (vertices.length == 0) {
            return new int[] { 0, 0 };
        }
        int vao = gl.genVertexArrays();
        int vbo = gl.genBuffers();
        gl.bindVertexArray(vao);
        gl.bindBuffer(gl.ARRAY_BUFFER(), vbo);
        gl.bufferData(gl.ARRAY_BUFFER(), vertices, gl.STATIC_DRAW());
        gl.vertexAttribPointer(ATTR_POSITION, VEC3_SIZE, gl.FLOAT(), false,
                VEC3_SIZE * Float.BYTES, 0);
        gl.enableVertexAttribArray(ATTR_POSITION);
        return new int[] { vao, vbo };
    }

    /**
     * Write one point's xyz into a flat float array.
     *
     * @param target flat float array
     * @param cursor write position
     * @param point  point to write
     * @return cursor advanced past the written floats
     */
    private static int writePoint(float[] target, int cursor, Vector3f point) {
        target[cursor] = point.x;
        target[cursor + 1] = point.y;
        target[cursor + 2] = point.z;
        return cursor + VEC3_SIZE;
    }

    /**
     * Draw the Coons patch fill: one ranged indexed draw per patch with its
     * palette-hashed solid color.
     *
     * @param camera active 3D camera
     */
    private void renderLayoutPatchFill(Camera3D camera) {
        if (layoutPatchIndexCount == null || layoutPatchIndexCount.length == 0
                || unlitShader.ID < 0) {
            return;
        }
        GL gl = Platforms.gl();
        unlitShader.use();
        unlitShader.setMat4(VIEW, camera.view);
        unlitShader.setMat4(PROJECTION, localProjection);
        sphereModel.identity();
        unlitShader.setMat4(MODEL, sphereModel);
        unlitShader.setFloat(DEPTHBIAS, 0f);
        gl.bindVertexArray(layoutCoonsVao);
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), layoutCoonsEbo);
        for (int patch = 0; patch < layoutPatchIndexCount.length; patch++) {
            unlitShader.setVec4(SOLIDCOLOR, layoutPatchColors[patch]);
            gl.drawElements(gl.TRIANGLES(), layoutPatchIndexCount[patch], gl.UNSIGNED_INT(),
                    layoutPatchIndexStart[patch] * Integer.BYTES);
        }
    }

    /**
     * Draw the layout boundary curves as biased GL_LINES, then a marker sphere at
     * every patch corner — four per patch is the visual confirmation that a patch
     * really is a quad.
     *
     * @param camera active 3D camera
     */
    private void renderLayoutBoundaries(Camera3D camera) {
        if (unlitShader.ID < 0) {
            return;
        }
        GL gl = Platforms.gl();
        unlitShader.use();
        unlitShader.setMat4(VIEW, camera.view);
        unlitShader.setMat4(PROJECTION, localProjection);
        sphereModel.identity();
        unlitShader.setMat4(MODEL, sphereModel);
        if (layoutLineVertexCount > 0) {
            unlitShader.setFloat(DEPTHBIAS, LAYOUT_DEPTH_BIAS);
            unlitShader.setVec4(SOLIDCOLOR, COLOR_LAYOUT_BOUNDARY);
            gl.lineWidth(LAYOUT_LINE_WIDTH);
            gl.bindVertexArray(layoutLineVao);
            gl.drawArrays(gl.LINES(), 0, layoutLineVertexCount);
            gl.lineWidth(DEFAULT_GL_LINE_WIDTH);
        }
        if (layoutCornerPositions == null || singularityVao == 0) {
            return;
        }
        unlitShader.setFloat(DEPTHBIAS, 0f);
        unlitShader.setVec4(SOLIDCOLOR, COLOR_LAYOUT_CORNER);
        gl.bindVertexArray(singularityVao);
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), singularityEbo);
        for (int corner = 0; corner < layoutCornerPositions.length / VEC3_SIZE; corner++) {
            int base = corner * VEC3_SIZE;
            sphereModel.identity()
                    .translate(layoutCornerPositions[base],
                            layoutCornerPositions[base + COMPONENT_Y],
                            layoutCornerPositions[base + COMPONENT_Z])
                    .scale(sphereRadius * CORNER_SPHERE_SCALE);
            unlitShader.setMat4(MODEL, sphereModel);
            gl.drawElements(gl.TRIANGLES(), singularityIndexCount, gl.UNSIGNED_INT(), 0);
        }
    }

    /**
     * Draw the embedded arc edge paths as biased GL_LINES in the embedding tint.
     *
     * @param camera active 3D camera
     */
    private void renderEmbeddedArcs(Camera3D camera) {
        if (unlitShader.ID < 0) {
            return;
        }
        GL gl = Platforms.gl();
        unlitShader.use();
        unlitShader.setMat4(VIEW, camera.view);
        unlitShader.setMat4(PROJECTION, localProjection);
        sphereModel.identity();
        unlitShader.setMat4(MODEL, sphereModel);
        unlitShader.setFloat(DEPTHBIAS, LAYOUT_DEPTH_BIAS);
        gl.lineWidth(LAYOUT_LINE_WIDTH);
        if (embeddedLineVertexCount > 0) {
            unlitShader.setVec4(SOLIDCOLOR, COLOR_EMBEDDED_ARC);
            gl.bindVertexArray(embeddedLineVao);
            gl.drawArrays(gl.LINES(), 0, embeddedLineVertexCount);
        }
        if (embeddedZeroLineVertexCount > 0) {
            unlitShader.setVec4(SOLIDCOLOR, COLOR_EMBEDDED_ZERO_ARC);
            gl.bindVertexArray(embeddedZeroLineVao);
            gl.drawArrays(gl.LINES(), 0, embeddedZeroLineVertexCount);
        }
        gl.lineWidth(DEFAULT_GL_LINE_WIDTH);
        renderEmbeddedNodes(gl);
    }

    /**
     * Draw the embedded T-mesh nodes as spheres, critical ones tinted apart.
     *
     * @param gl active GL platform handle
     */
    private void renderEmbeddedNodes(GL gl) {
        if (embeddedNodePositions == null || singularityVao == 0) {
            return;
        }
        unlitShader.setFloat(DEPTHBIAS, 0f);
        gl.bindVertexArray(singularityVao);
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), singularityEbo);
        for (int node = 0; node < embeddedNodeCritical.length; node++) {
            int base = node * VEC3_SIZE;
            unlitShader.setVec4(SOLIDCOLOR, embeddedNodeCritical[node]
                    ? COLOR_EMBEDDED_NODE_CRITICAL : COLOR_EMBEDDED_NODE);
            sphereModel.identity()
                    .translate(embeddedNodePositions[base],
                            embeddedNodePositions[base + COMPONENT_Y],
                            embeddedNodePositions[base + COMPONENT_Z])
                    .scale(sphereRadius);
            unlitShader.setMat4(MODEL, sphereModel);
            gl.drawElements(gl.TRIANGLES(), singularityIndexCount, gl.UNSIGNED_INT(), 0);
        }
    }

    /**
     * Prepares the reroute-failure highlight from a captured failure: the two disconnected
     * unclaimed regions as coloured dot clouds, the collapsing pivot and the survivor as markers,
     * and the stranded arc's body and the freed channel as lines. Resolves every copy vertex
     * through the same {@code copy.vertexPosition} the arc/node render uses.
     *
     * @param copy    the working copy the failure's vertex ids index into
     * @param failure the captured reroute failure
     */
    public void setFailureHighlight(HalfEdgeMesh copy, ArcRerouteFailure failure) {
        GL gl = Platforms.gl();
        deleteHighlightBuffers(gl);
        highlightBodyPositions = resolvePositions(copy, failure.bodyComponent);
        highlightChannelPositions = resolvePositions(copy, failure.channelComponent);
        highlightMarkerPositions = resolvePositions(copy,
                List.of(failure.pivotVertex, failure.survivorVertex));
        int[] arcBuffers = uploadLineBuffer(gl, pathLineVertices(copy, failure.arcBody));
        highlightArcLineVao = arcBuffers[0];
        highlightArcLineVbo = arcBuffers[1];
        highlightArcLineVertexCount = Math.max(0, failure.arcBody.size() - 1) * 2;
        int[] channelBuffers = uploadLineBuffer(gl, pathLineVertices(copy, failure.channel));
        highlightChannelLineVao = channelBuffers[0];
        highlightChannelLineVbo = channelBuffers[1];
        highlightChannelLineVertexCount = Math.max(0, failure.channel.size() - 1) * 2;
        highlightFencePositions = resolvePositions(copy, failure.fenceVertices);
        int[] spokeBuffers = uploadLineBuffer(gl, resolvePositions(copy, failure.pivotSpokes));
        highlightSpokeLineVao = spokeBuffers[0];
        highlightSpokeLineVbo = spokeBuffers[1];
        highlightSpokeLineVertexCount = failure.pivotSpokes.size();
        if (singularityVao == 0) {
            buildIcosphereBuffers();
        }
        updateSphereRadius();
        showFailureHighlight = true;
    }

    /**
     * The flat xyz positions of a set of copy vertices.
     *
     * @param copy     the working copy
     * @param vertices the copy vertex ids
     * @return their positions, three floats each
     */
    private float[] resolvePositions(HalfEdgeMesh copy, Collection<Integer> vertices) {
        float[] positions = new float[vertices.size() * VEC3_SIZE];
        Vector3f point = new Vector3f();
        int cursor = 0;
        for (int vertexId : vertices) {
            copy.vertexPosition(vertexId, point);
            cursor = writePoint(positions, cursor, point);
        }
        return positions;
    }

    /**
     * The GL_LINES vertices for a copy-vertex path — each consecutive pair a segment.
     *
     * @param copy the working copy
     * @param path the copy vertex ids in order
     * @return flat xyz, two points per segment
     */
    private float[] pathLineVertices(HalfEdgeMesh copy, List<Integer> path) {
        float[] vertices = new float[Math.max(0, path.size() - 1) * 2 * VEC3_SIZE];
        Vector3f point = new Vector3f();
        int cursor = 0;
        for (int index = 1; index < path.size(); index++) {
            copy.vertexPosition(path.get(index - 1), point);
            cursor = writePoint(vertices, cursor, point);
            copy.vertexPosition(path.get(index), point);
            cursor = writePoint(vertices, cursor, point);
        }
        return vertices;
    }

    /**
     * Draw the reroute-failure highlight: the two disconnected regions as coloured dots, the
     * stranded arc and freed channel as lines, and the pivot and survivor as large markers.
     *
     * @param camera active 3D camera
     */
    public void renderHighlights(Camera3D camera) {
        if (!showFailureHighlight || unlitShader.ID < 0 || singularityVao == 0) {
            return;
        }
        GL gl = Platforms.gl();
        unlitShader.use();
        unlitShader.setMat4(VIEW, camera.view);
        unlitShader.setMat4(PROJECTION, localProjection);
        drawHighlightRegion(gl, highlightBodyPositions, COLOR_HIGHLIGHT_BODY,
                HIGHLIGHT_REGION_SCALE);
        drawHighlightRegion(gl, highlightChannelPositions, COLOR_HIGHLIGHT_CHANNEL,
                HIGHLIGHT_REGION_SCALE);
        drawHighlightRegion(gl, highlightFencePositions, COLOR_HIGHLIGHT_FENCE,
                HIGHLIGHT_REGION_SCALE);
        drawHighlightLine(gl, highlightSpokeLineVao, highlightSpokeLineVertexCount,
                COLOR_HIGHLIGHT_SPOKE, HIGHLIGHT_WALL_LINE_WIDTH);
        drawHighlightLine(gl, highlightChannelLineVao, highlightChannelLineVertexCount,
                COLOR_HIGHLIGHT_CHANNEL_LINE, HIGHLIGHT_LINE_WIDTH);
        drawHighlightLine(gl, highlightArcLineVao, highlightArcLineVertexCount,
                COLOR_HIGHLIGHT_ARC, HIGHLIGHT_LINE_WIDTH);
        drawHighlightMarker(gl, highlightMarkerPositions, 0, COLOR_HIGHLIGHT_PIVOT);
        drawHighlightMarker(gl, highlightMarkerPositions, VEC3_SIZE, COLOR_HIGHLIGHT_SURVIVOR);
    }

    /**
     * Draw a flat position array as a cloud of small coloured spheres.
     *
     * @param gl        active GL platform handle
     * @param positions flat xyz positions
     * @param color     sphere colour
     * @param scale     sphere scale relative to the shared sphere radius
     */
    private void drawHighlightRegion(GL gl, float[] positions, Color color, float scale) {
        if (positions == null) {
            return;
        }
        unlitShader.setFloat(DEPTHBIAS, 0f);
        unlitShader.setVec4(SOLIDCOLOR, color);
        gl.bindVertexArray(singularityVao);
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), singularityEbo);
        for (int base = 0; base < positions.length; base += VEC3_SIZE) {
            sphereModel.identity()
                    .translate(positions[base], positions[base + COMPONENT_Y],
                            positions[base + COMPONENT_Z])
                    .scale(sphereRadius * scale);
            unlitShader.setMat4(MODEL, sphereModel);
            gl.drawElements(gl.TRIANGLES(), singularityIndexCount, gl.UNSIGNED_INT(), 0);
        }
    }

    /**
     * Draw one large marker sphere at a position within a flat array.
     *
     * @param gl        active GL platform handle
     * @param positions flat xyz positions
     * @param base      index of the first float of the marker
     * @param color     sphere colour
     */
    private void drawHighlightMarker(GL gl, float[] positions, int base, Color color) {
        if (positions == null || base + VEC3_SIZE > positions.length) {
            return;
        }
        unlitShader.setFloat(DEPTHBIAS, 0f);
        unlitShader.setVec4(SOLIDCOLOR, color);
        gl.bindVertexArray(singularityVao);
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), singularityEbo);
        sphereModel.identity()
                .translate(positions[base], positions[base + COMPONENT_Y],
                        positions[base + COMPONENT_Z])
                .scale(sphereRadius * HIGHLIGHT_MARKER_SCALE);
        unlitShader.setMat4(MODEL, sphereModel);
        gl.drawElements(gl.TRIANGLES(), singularityIndexCount, gl.UNSIGNED_INT(), 0);
    }

    /**
     * Draw a highlight GL_LINES buffer in a solid colour, biased forward.
     *
     * @param gl          active GL platform handle
     * @param vao         line buffer VAO
     * @param vertexCount line buffer vertex count
     * @param color       line colour
     * @param width       line width
     */
    private void drawHighlightLine(GL gl, int vao, int vertexCount, Color color, float width) {
        if (vao == 0 || vertexCount == 0) {
            return;
        }
        unlitShader.setFloat(DEPTHBIAS, HIGHLIGHT_DEPTH_BIAS);
        unlitShader.setVec4(SOLIDCOLOR, color);
        sphereModel.identity();
        unlitShader.setMat4(MODEL, sphereModel);
        gl.lineWidth(width);
        gl.bindVertexArray(vao);
        gl.drawArrays(gl.LINES(), 0, vertexCount);
        gl.lineWidth(DEFAULT_GL_LINE_WIDTH);
    }

    /**
     * Free the failure-highlight line buffers and clear its position arrays.
     *
     * @param gl active GL platform handle
     */
    private void deleteHighlightBuffers(GL gl) {
        if (highlightArcLineVao != 0) {
            gl.deleteVertexArrays(highlightArcLineVao);
            highlightArcLineVao = 0;
        }
        if (highlightArcLineVbo != 0) {
            gl.deleteBuffers(highlightArcLineVbo);
            highlightArcLineVbo = 0;
        }
        if (highlightChannelLineVao != 0) {
            gl.deleteVertexArrays(highlightChannelLineVao);
            highlightChannelLineVao = 0;
        }
        if (highlightChannelLineVbo != 0) {
            gl.deleteBuffers(highlightChannelLineVbo);
            highlightChannelLineVbo = 0;
        }
        if (highlightSpokeLineVao != 0) {
            gl.deleteVertexArrays(highlightSpokeLineVao);
            highlightSpokeLineVao = 0;
        }
        if (highlightSpokeLineVbo != 0) {
            gl.deleteBuffers(highlightSpokeLineVbo);
            highlightSpokeLineVbo = 0;
        }
        highlightArcLineVertexCount = 0;
        highlightChannelLineVertexCount = 0;
        highlightSpokeLineVertexCount = 0;
        highlightBodyPositions = null;
        highlightChannelPositions = null;
        highlightMarkerPositions = null;
        highlightFencePositions = null;
    }

    /**
     * Free the embedded arc line buffer if it exists, zeroing handles and count.
     *
     * @param gl active GL platform handle
     */
    private void deleteEmbeddedBuffers(GL gl) {
        if (embeddedLineVao != 0) {
            gl.deleteVertexArrays(embeddedLineVao);
            embeddedLineVao = 0;
        }
        if (embeddedLineVbo != 0) {
            gl.deleteBuffers(embeddedLineVbo);
            embeddedLineVbo = 0;
        }
        embeddedLineVertexCount = 0;
        if (embeddedZeroLineVao != 0) {
            gl.deleteVertexArrays(embeddedZeroLineVao);
            embeddedZeroLineVao = 0;
        }
        if (embeddedZeroLineVbo != 0) {
            gl.deleteBuffers(embeddedZeroLineVbo);
            embeddedZeroLineVbo = 0;
        }
        embeddedZeroLineVertexCount = 0;
        embeddedNodePositions = null;
        embeddedNodeCritical = null;
    }

    /**
     * Free the layout overlay buffers (boundary lines and Coons mesh) if they
     * exist, zeroing handles and counts.
     *
     * @param gl active GL platform handle
     */
    private void deleteLayoutBuffers(GL gl) {
        if (layoutLineVao != 0) {
            gl.deleteVertexArrays(layoutLineVao);
            layoutLineVao = 0;
        }
        if (layoutLineVbo != 0) {
            gl.deleteBuffers(layoutLineVbo);
            layoutLineVbo = 0;
        }
        if (layoutCoonsVao != 0) {
            gl.deleteVertexArrays(layoutCoonsVao);
            layoutCoonsVao = 0;
        }
        if (layoutCoonsVbo != 0) {
            gl.deleteBuffers(layoutCoonsVbo);
            layoutCoonsVbo = 0;
        }
        if (layoutCoonsEbo != 0) {
            gl.deleteBuffers(layoutCoonsEbo);
            layoutCoonsEbo = 0;
        }
        layoutLineVertexCount = 0;
        layoutPatchIndexStart = null;
        layoutPatchIndexCount = null;
        layoutPatchColors = null;
        layoutCornerPositions = null;
    }

    @Override
    public void dispose() {
        super.dispose();
        GL gl = Platforms.gl();
        deleteLayoutBuffers(gl);
        deleteEmbeddedBuffers(gl);
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
        if (crossFieldVao != 0) {
            gl.deleteVertexArrays(crossFieldVao);
            crossFieldVao = 0;
        }
        if (crossFieldVbo != 0) {
            gl.deleteBuffers(crossFieldVbo);
            crossFieldVbo = 0;
        }
        if (crossFieldEbo != 0) {
            gl.deleteBuffers(crossFieldEbo);
            crossFieldEbo = 0;
        }
        if (constraintVao != 0) {
            gl.deleteVertexArrays(constraintVao);
            constraintVao = 0;
        }
        if (constraintVbo != 0) {
            gl.deleteBuffers(constraintVbo);
            constraintVbo = 0;
        }
        if (constraintEbo != 0) {
            gl.deleteBuffers(constraintEbo);
            constraintEbo = 0;
        }
    }
}
