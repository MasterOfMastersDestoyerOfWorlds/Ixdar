package ixdar.graphics.render.model;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.IntIdList;
import ixdar.geometry.mesh.nodes.api.BoolField;
import ixdar.geometry.mesh.nodes.api.IntField;
import ixdar.geometry.mesh.nodes.api.UvField;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.crossfield.constraint.ConstraintSource;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedNode;
import ixdar.geometry.mesh.quadlayout.extraction.PatchSurfaceGeometry;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorRGB;
import ixdar.graphics.render.color.PatchColorHash;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;

/**
 * Quad-layout inspection overlays behind public {@code show*} toggles. Each setter converts one
 * port-typed value into a {@link LineSet}, a {@link PointSet}, or a corner array, and
 * {@link VertexBuffer#upload} is the one upload path per {@link VertexLayout}. The iso-surface
 * is triangle soup: per-corner UV is discontinuous across cut edges (BZK09 Section 5).
 */
public class QuadLayoutRuntime extends HalfEdgeMeshRuntime {

    /** Colour names of the group palette in assignment order, for log lines. */
    public static final String GROUP_PALETTE_ORDER =
            "yellow, green, magenta, cyan, purple, white, red, azure";

    private static final VertexLayout POSITION_LAYOUT =
            new VertexLayout(new int[] { 0 }, new int[] { 3 });
    /** pos, normal, uv, flip, trace0..3, patch id: the mesh_uv / mesh_uv_traces attributes. */
    private static final VertexLayout ISO_CORNER_LAYOUT = new VertexLayout(
            new int[] { 0, 1, 3, 4, 5, 6, 7, 8, 9 }, new int[] { 3, 3, 2, 1, 4, 4, 4, 4, 1 });
    /** pos, normal, centroid, dirU, dirV, arm length: the mesh_cross_field attributes. */
    private static final VertexLayout CROSS_GLYPH_LAYOUT = new VertexLayout(
            new int[] { 0, 1, 3, 4, 5, 6 }, new int[] { 3, 3, 3, 3, 3, 1 });
    private static final int CORNERS_PER_FACE = 3;
    private static final int TRACE_FLOATS_PER_FACE =
            MotorcycleGraph.MAX_TRACE_RECORDS_PER_FACE * 4;
    private static final int UV_OFFSET = 6;
    private static final int FLIP_OFFSET = 8;
    private static final int TRACE_OFFSET = 9;
    private static final int CENTROID_OFFSET = 6;
    private static final int DIR_U_OFFSET = 9;
    private static final int DIR_V_OFFSET = 12;
    private static final int ARM_LENGTH_OFFSET = 15;
    private static final int INDICES_PER_GRID_CELL = 6;
    private static final float ONE_THIRD = 1.0f / 3.0f;
    /**
     * Fraction of a face's arm length by which the constraint glyph is floated along the face
     * normal, so it wins the depth test against the coincident cross-field glyph.
     */
    private static final float CONSTRAINT_NORMAL_LIFT = 0.25f;
    private static final float DEFAULT_LINE_HALF_WIDTH = 1.0f;
    private static final float SPHERE_RADIUS_FRACTION_OF_BBOX = 0.005f;
    private static final float ASPECT_FALLBACK = 1f;
    private static final float LAYOUT_DEPTH_BIAS = 0.0003f;
    private static final float HIGHLIGHT_DEPTH_BIAS = 0.0015f;
    private static final float LAYOUT_LINE_WIDTH = 2.5f;
    private static final float HIGHLIGHT_LINE_WIDTH = 5f;
    private static final float DEFAULT_GL_LINE_WIDTH = 1f;
    private static final float LAYOUT_PATCH_ALPHA = 0.55f;
    private static final float CORNER_SPHERE_SCALE = 0.7f;
    private static final float HIGHLIGHT_REGION_SCALE = 0.22f;
    private static final float HIGHLIGHT_MARKER_SCALE = 2.2f;
    private static final float PATCH_CLOUD_SCALE = 1f;
    /** Cap on the shared sphere radius inside a diagnostic spotlight, in region radii. */
    private static final float TEAR_SPHERE_REGION_FRACTION = 0.02f;

    private static final String BASE_COLOR = "baseColor";
    private static final String U_LINE_COLOR = "uLineColor";
    private static final String V_LINE_COLOR = "vLineColor";
    private static final String LINE_HALF_WIDTH = "lineHalfWidth";
    private static final String FLIPPED_COLOR_UNIFORM = "flippedColor";
    private static final String DRAW_FULL_ISO_GRID_UNIFORM = "drawFullIsoGrid";
    private static final String USE_PATCH_COLOR_UNIFORM = "usePatchColor";

    private static final Color COLOR_U_ARM = Color.YELLOW;
    private static final Color COLOR_V_ARM = Color.CYAN;
    private static final Color COLOR_INTERSECTION_NODE = Color.WHITE;
    private static final Color COLOR_BOUNDARY_NODE = Color.YELLOW;
    private static final Color COLOR_TRUNCATED_NODE = Color.ORANGE;
    /** Cyan for {@code index4 > 0}, red for {@code index4 < 0}, per BZK09 fig. 4. */
    private static final Color COLOR_POSITIVE_INDEX = new ColorRGB(Color.CYAN, 0.5f);
    private static final Color COLOR_NEGATIVE_INDEX = new ColorRGB(Color.RED, 0.5f);
    private static final Color COLOR_CONSTRAINT_BOUNDARY = Color.GREEN;
    private static final Color COLOR_CONSTRAINT_FEATURE = Color.ORANGE;
    private static final Color COLOR_CONSTRAINT_CURVATURE = Color.MAGENTA;
    private static final Color COLOR_CONSTRAINT_ANCHOR = Color.WHITE;
    private static final ConstraintSource[] CONSTRAINT_DRAW_ORDER = {
            ConstraintSource.BOUNDARY, ConstraintSource.FEATURE,
            ConstraintSource.CURVATURE, ConstraintSource.ANCHOR
    };
    private static final Color COLOR_LAYOUT_BOUNDARY = Color.WHITE;
    private static final Color COLOR_LAYOUT_CORNER = Color.SOFT_RED;
    private static final Color COLOR_QUAD_GRID = Color.DARK_GRAY;
    private static final Color COLOR_EMBEDDED_ARC = Color.BRIGHT_ORANGE;
    /** Zero-quantized arcs are red like LCBK19 Figure 9, so collapse targets stand out. */
    private static final Color COLOR_EMBEDDED_ZERO_ARC = Color.RED;
    private static final Color COLOR_COPY_WIREFRAME = new ColorRGB(Color.WHITE, 0.25f);
    private static final Color COLOR_EMBEDDED_NODE = Color.SKY_BLUE;
    private static final Color COLOR_EMBEDDED_NODE_CRITICAL = Color.GOLD;
    /** Colours assigned to dot clouds and diagnostic groups, cycled in order. */
    private static final Color[] GROUP_PALETTE = { Color.YELLOW, Color.BRIGHT_GREEN,
            Color.MAGENTA, Color.CYAN, Color.LIGHT_PURPLE, Color.WHITE, Color.RED, Color.AZURE };

    private static final float PHI = (1f + (float) Math.sqrt(5f)) * 0.5f;
    /** The 12 golden-ratio icosahedron vertices, flat xyz, unnormalized. */
    private static final float[] ICO_VERTICES = {
            -1, PHI, 0, 1, PHI, 0, -1, -PHI, 0, 1, -PHI, 0,
            0, -1, PHI, 0, 1, PHI, 0, -1, -PHI, 0, 1, -PHI,
            PHI, 0, -1, PHI, 0, 1, -PHI, 0, -1, -PHI, 0, 1
    };
    /** The 20 icosahedron triangles over {@link #ICO_VERTICES}, counter-clockwise. */
    private static final int[] ICO_TRIANGLES = {
            0, 11, 5, 0, 5, 1, 0, 1, 7, 0, 7, 10, 0, 10, 11,
            1, 5, 9, 5, 11, 4, 11, 10, 2, 10, 7, 6, 7, 1, 8,
            3, 9, 4, 3, 4, 2, 3, 2, 6, 3, 6, 8, 3, 8, 9,
            4, 9, 5, 2, 4, 11, 6, 2, 10, 8, 6, 7, 9, 8, 1
    };

    public final ShaderProgram uvShader;
    public final ShaderProgram traceUvShader;
    public final ShaderProgram crossFieldShader;
    public final ShaderProgram unlitShader;

    public float lineHalfWidth = DEFAULT_LINE_HALF_WIDTH;
    public Color baseColor = Color.BLUE_WHITE;
    public Color uLineColor = Color.CYAN;
    public Color vLineColor = Color.YELLOW;
    public Color flippedColor = Color.MAGENTA;

    public boolean showIsoLines;
    public boolean showSingularities;
    public boolean showCrossField;
    public boolean showConstraints;
    public boolean showTraces;
    public boolean showNodes;
    public boolean showFullIsoGrid;
    public boolean showLayoutPatches;
    public boolean showLayoutBoundaries;
    public boolean showQuadGrid;
    public boolean showEmbeddedArcs;
    public boolean showEmbeddedNodes = true;
    public boolean showCopyWireframe;
    public boolean showPatchClouds;
    public boolean showDiagnostic;

    /** Sphere radius derived from the mesh bounding-box diagonal, under {@link #sphereRadiusCap}. */
    public float sphereRadius;
    /** Ceiling on {@link #sphereRadius} surviving every recompute; zero leaves it uncapped. */
    public float sphereRadiusCap;
    /** Radius of the last uploaded diagnostic's geometry around its centroid. */
    public float diagnosticRegionRadius;

    private ArcNetwork arrangement;
    private UvField seamlessParametrization;
    private HalfEdgeMesh seamlessMesh;

    private final VertexBuffer isoSurface = new VertexBuffer();
    private final VertexBuffer crossField = new VertexBuffer();
    private final VertexBuffer constraints = new VertexBuffer();
    private final VertexBuffer sphere = new VertexBuffer();
    /** Patch boundary edges first, then the interior quad grid edges. */
    private final VertexBuffer layoutLines = new VertexBuffer();
    private final VertexBuffer layoutFill = new VertexBuffer();
    private final VertexBuffer copyWireframe = new VertexBuffer();
    private final VertexBuffer embeddedArcs = new VertexBuffer();
    private final VertexBuffer embeddedZeroArcs = new VertexBuffer();
    private final List<VertexBuffer> diagnosticLines = new ArrayList<>();
    private final List<Color> diagnosticLineColors = new ArrayList<>();
    private final List<PointSet> diagnosticRegions = new ArrayList<>();
    private final List<PointSet> diagnosticMarkers = new ArrayList<>();
    private final List<PointSet> patchClouds = new ArrayList<>();
    private PointSet singularities;
    private PointSet graphNodes;
    private PointSet layoutCorners;
    private PointSet embeddedNodes;
    private int layoutBoundaryVertexCount;
    private int[] constraintRangeStart;
    private int[] constraintRangeCount;
    private int[] layoutPatchIndexStart;
    private int[] layoutPatchIndexCount;
    private Color[] layoutPatchColors;

    private final Matrix4f model = new Matrix4f();
    private final Matrix4f localProjection = new Matrix4f();

    /** Build the runtime and initialise its overlay shaders. */
    public QuadLayoutRuntime() {
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
     * Upload (or replace) the iso-line surface from {@code seamless}, with the attached
     * arrangement's trace records when one is set.
     *
     * @param seamless per-corner UV field whose iso-lines to render
     * @param mesh     the mesh the parametrization covers
     */
    public void setSeamlessParametrization(UvField seamless, HalfEdgeMesh mesh) {
        seamlessParametrization = seamless;
        seamlessMesh = mesh;
        if (seamless != null) {
            uploadSeamlessSurface();
        }
    }

    /**
     * Attach a traced arrangement: refreshes the surface's trace records and captures the
     * arrangement nodes as markers.
     *
     * @param graph traced arrangement sharing the seamless parametrization
     */
    public void setMotorcycleGraph(ArcNetwork graph) {
        arrangement = graph;
        if (graph == null || seamlessParametrization == null) {
            return;
        }
        uploadSeamlessSurface();
        graphNodes = new PointSet(graph.nodes.size(), 1f);
        for (EmbeddedNode node : graph.nodes) {
            if (node.position != null) {
                graphNodes.add(node.position, nodeColor(node), 0f);
            }
        }
    }

    /**
     * Upload a per-patch rectangle parametrization as the iso-surface, shading each flagged
     * folded face with {@link #flippedColor}. Shows the embedded T-mesh's per-patch maps, whose
     * fold is a chart-orientation question the field answers, not the UV signed area.
     *
     * @param gridMap     per-corner grid coordinates over {@code copy}
     * @param copy        the copy mesh the map covers
     * @param faceFlipped whether each face folds in its patch's map, indexed by active face
     */
    public void setGridMapParametrization(UvField gridMap, HalfEdgeMesh copy,
            BoolField faceFlipped) {
        uploadIsoSurface(copy, gridMap, faceFlipped, null);
    }

    /**
     * Upload cross-field glyphs and capture the singularity markers.
     *
     * @param field      built cross field
     * @param crossScale arm length scale relative to incircle radius
     */
    public void setCrossField(CrossField field, float crossScale) {
        if (field != null && field.theta != null && field.faceX != null && field.faceY != null) {
            int faceCount = field.mesh.faceCount();
            float[] glyphs = new float[faceCount * CORNERS_PER_FACE
                    * CROSS_GLYPH_LAYOUT.floatsPerVertex];
            for (int activeFace = 0; activeFace < faceCount; activeFace++) {
                writeFaceGlyph(field, activeFace, field.theta[activeFace], crossScale, 0f, glyphs,
                        activeFace * CORNERS_PER_FACE);
            }
            crossField.upload(CROSS_GLYPH_LAYOUT, glyphs,
                    identityIndices(faceCount * CORNERS_PER_FACE));
        }
        if (field == null || field.mesh == null) {
            return;
        }
        setSingularities(field.singularityIndex4, field.mesh);
    }

    /**
     * Upload constraint glyphs: the cross primitive oriented at each constrained face's
     * {@code faceConstraintAngle}, grouped by source in {@link #CONSTRAINT_DRAW_ORDER}.
     *
     * @param field      built cross field carrying the constraint arrays
     * @param crossScale arm length scale relative to incircle radius
     */
    public void setConstraints(CrossField field, float crossScale) {
        if (field == null || field.faceConstrained == null || field.faceConstraintAngle == null
                || field.faceConstraintSource == null || field.faceX == null
                || field.faceY == null) {
            return;
        }
        int faceCount = field.mesh.faceCount();
        int constrainedCount = 0;
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            if (field.faceConstrained[activeFace]) {
                constrainedCount++;
            }
        }
        float[] glyphs = new float[constrainedCount * CORNERS_PER_FACE
                * CROSS_GLYPH_LAYOUT.floatsPerVertex];
        int sourceCount = ConstraintSource.values().length;
        constraintRangeStart = new int[sourceCount];
        constraintRangeCount = new int[sourceCount];
        int glyph = 0;
        for (ConstraintSource source : CONSTRAINT_DRAW_ORDER) {
            int rangeStart = glyph * CORNERS_PER_FACE;
            constraintRangeStart[source.ordinal()] = rangeStart;
            for (int activeFace = 0; activeFace < faceCount; activeFace++) {
                if (!field.faceConstrained[activeFace]
                        || field.faceConstraintSource[activeFace] != source) {
                    continue;
                }
                writeFaceGlyph(field, activeFace, field.faceConstraintAngle[activeFace],
                        crossScale, CONSTRAINT_NORMAL_LIFT, glyphs, glyph * CORNERS_PER_FACE);
                glyph++;
            }
            constraintRangeCount[source.ordinal()] = glyph * CORNERS_PER_FACE - rangeStart;
        }
        constraints.upload(CROSS_GLYPH_LAYOUT, glyphs,
                identityIndices(constrainedCount * CORNERS_PER_FACE));
    }

    /**
     * Capture singularity vertices as coloured sphere markers.
     *
     * @param index4 per-vertex singularity index4 in dense active-vertex order; nonzero entries
     *               are rendered
     * @param mesh   the underlying triangle mesh
     */
    public void setSingularities(IntField index4, HalfEdgeMesh mesh) {
        ensureSphere();
        updateSphereRadius();
        singularities = new PointSet(index4.length(), 1f);
        Vector3f position = new Vector3f();
        for (int vertex = 0; vertex < index4.length(); vertex++) {
            if (index4.get(vertex) == 0) {
                continue;
            }
            mesh.vertexPosition(mesh.vertexIdAt(vertex), position);
            singularities.add(position,
                    index4.get(vertex) > 0 ? COLOR_POSITIVE_INDEX : COLOR_NEGATIVE_INDEX, 0f);
        }
    }

    /**
     * Upload the finished layout: patch boundary edges then interior quad grid edges in one
     * line buffer, every patch's grid as triangles with per-patch index ranges and
     * palette-hashed colours, and a corner marker at every vertex with one incident face.
     *
     * @param patchGrid per-patch quad-grid geometry with the {@code patch_id} per-face slot
     */
    public void setLayoutPatchSurfaces(GeometryBundle patchGrid) {
        MeshTopology mesh = patchGrid.mesh();
        IntField patchIds = PatchSurfaceGeometry.patchIds(patchGrid);
        int vertexCount = mesh.vertexCount();
        int maxVertexId = 0;
        for (int dense = 0; dense < vertexCount; dense++) {
            maxVertexId = Math.max(maxVertexId, mesh.vertexIdAt(dense));
        }
        int[] denseOf = new int[maxVertexId + 1];
        float[] gridVertices = new float[vertexCount * 3];
        layoutCorners = new PointSet(vertexCount, CORNER_SPHERE_SCALE);
        Vector3f position = new Vector3f();
        for (int dense = 0; dense < vertexCount; dense++) {
            int vertexId = mesh.vertexIdAt(dense);
            denseOf[vertexId] = dense;
            mesh.vertexPosition(vertexId, position);
            gridVertices[dense * 3] = position.x;
            gridVertices[dense * 3 + 1] = position.y;
            gridVertices[dense * 3 + 2] = position.z;
            if (mesh.vertexFaceCount(vertexId) == 1) {
                layoutCorners.add(position, COLOR_LAYOUT_CORNER, 0f);
            }
        }

        LineSet lines = new LineSet(mesh.edgeCount());
        writeEdgeLines(lines, mesh, denseOf, gridVertices, true);
        layoutBoundaryVertexCount = lines.vertexCount();
        writeEdgeLines(lines, mesh, denseOf, gridVertices, false);
        layoutLines.upload(POSITION_LAYOUT, lines.xyz, null);

        int faceCount = mesh.faceCount();
        int patchCount = 0;
        int previousId = -1;
        for (int face = 0; face < faceCount; face++) {
            if (face == 0 || patchIds.get(face) != previousId) {
                patchCount++;
            }
            previousId = patchIds.get(face);
        }
        int[] fillIndices = new int[faceCount * INDICES_PER_GRID_CELL];
        layoutPatchIndexStart = new int[patchCount];
        layoutPatchIndexCount = new int[patchCount];
        layoutPatchColors = new Color[patchCount];
        int patchCursor = -1;
        int cursor = 0;
        previousId = -1;
        for (int face = 0; face < faceCount; face++) {
            int faceId = mesh.faceIdAt(face);
            int patchId = patchIds.get(face);
            if (patchCursor < 0 || patchId != previousId) {
                patchCursor++;
                layoutPatchIndexStart[patchCursor] = cursor;
                layoutPatchColors[patchCursor] =
                        PatchColorHash.colorForPatch(patchId, LAYOUT_PATCH_ALPHA);
            }
            previousId = patchId;
            int corner00 = denseOf[mesh.faceVertexAt(faceId, 0)];
            int corner10 = denseOf[mesh.faceVertexAt(faceId, 1)];
            int corner11 = denseOf[mesh.faceVertexAt(faceId, 2)];
            int corner01 = denseOf[mesh.faceVertexAt(faceId, 3)];
            fillIndices[cursor++] = corner00;
            fillIndices[cursor++] = corner10;
            fillIndices[cursor++] = corner01;
            fillIndices[cursor++] = corner10;
            fillIndices[cursor++] = corner11;
            fillIndices[cursor++] = corner01;
            layoutPatchIndexCount[patchCursor] = cursor - layoutPatchIndexStart[patchCursor];
        }
        layoutFill.upload(POSITION_LAYOUT, gridVertices, fillIndices);
        ensureSphere();
        updateSphereRadius();
    }

    private static void writeEdgeLines(LineSet lines, MeshTopology mesh, int[] denseOf,
            float[] gridVertices, boolean boundary) {
        for (int edge = 0; edge < mesh.edgeCount(); edge++) {
            int edgeId = mesh.edgeIdAt(edge);
            if (mesh.isBoundaryEdge(edgeId) != boundary) {
                continue;
            }
            int halfEdge = mesh.edgeHalfEdge(edgeId);
            lines.point(gridVertices, denseOf[mesh.halfEdgeVertex(halfEdge)]);
            lines.point(gridVertices, denseOf[mesh.halfEdgeEndVertex(halfEdge)]);
        }
    }

    /**
     * Upload every triangle of the working copy as outlines, so refinement density is visible.
     *
     * @param copy refined working copy to outline; {@code null} drops the buffer
     */
    public void setCopyWireframe(HalfEdgeMesh copy) {
        showCopyWireframe = copy != null;
        if (copy == null) {
            copyWireframe.delete();
            return;
        }
        LineSet outlines = new LineSet(copy.faceCount() * CORNERS_PER_FACE);
        Vector3f corner = new Vector3f();
        Vector3f nextCorner = new Vector3f();
        for (int activeFace = 0; activeFace < copy.faceCount(); activeFace++) {
            int faceId = copy.faceIdAt(activeFace);
            for (int index = 0; index < CORNERS_PER_FACE; index++) {
                copy.vertexPosition(copy.faceVertexAt(faceId, index), corner);
                copy.vertexPosition(
                        copy.faceVertexAt(faceId, (index + 1) % CORNERS_PER_FACE), nextCorner);
                outlines.point(corner);
                outlines.point(nextCorner);
            }
        }
        copyWireframe.upload(POSITION_LAYOUT, outlines.xyz, null);
    }

    /**
     * Upload an embedded T-mesh: arcs as lines split positive/zero, live nodes as spheres capped
     * to half their shortest incident copy edge, the sphere radius capped to the mean arc hop,
     * and the copy wireframe refreshed when shown. One call per mutation.
     *
     * <p>See also: LCBK19 Figure 9
     *
     * @param tmesh embedded T-mesh to draw; retired elements are skipped
     */
    public void setEmbeddedTMesh(ArcNetwork tmesh) {
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
        LineSet positive = new LineSet(positiveSegments);
        LineSet zero = new LineSet(zeroSegments);
        Vector3f segmentStart = new Vector3f();
        Vector3f segmentEnd = new Vector3f();
        double totalHopLength = 0;
        int hopCount = 0;
        for (EmbeddedArc arc : tmesh.arcs) {
            List<Integer> path = arc.path.copyVertexPath;
            if (!arc.alive || path.size() < 2) {
                continue;
            }
            LineSet target = arc.quantizedLength == 0 ? zero : positive;
            for (int index = 1; index < path.size(); index++) {
                copy.vertexPosition(path.get(index - 1), segmentStart);
                copy.vertexPosition(path.get(index), segmentEnd);
                target.point(segmentStart);
                target.point(segmentEnd);
                totalHopLength += segmentStart.distance(segmentEnd);
                hopCount++;
            }
        }
        embeddedArcs.upload(POSITION_LAYOUT, positive.xyz, null);
        embeddedZeroArcs.upload(POSITION_LAYOUT, zero.xyz, null);

        embeddedNodes = new PointSet(tmesh.nodes.size(), 1f);
        Vector3f nodePosition = new Vector3f();
        for (EmbeddedNode node : tmesh.nodes) {
            if (!node.alive) {
                continue;
            }
            copy.vertexPosition(node.copyVertex, nodePosition);
            float shortestEdge = Float.MAX_VALUE;
            for (int adjacency = 0; adjacency < copy.vertexEdgeCount(node.copyVertex);
                    adjacency++) {
                int halfEdge = copy.edgeHalfEdge(copy.vertexEdgeAt(node.copyVertex, adjacency));
                copy.vertexPosition(copy.halfEdgeVertex(halfEdge), segmentStart);
                copy.vertexPosition(copy.halfEdgeVertex(copy.halfEdgeTwin(halfEdge)), segmentEnd);
                shortestEdge = Math.min(shortestEdge, segmentStart.distance(segmentEnd));
            }
            embeddedNodes.add(nodePosition,
                    node.critical ? COLOR_EMBEDDED_NODE_CRITICAL : COLOR_EMBEDDED_NODE,
                    shortestEdge == Float.MAX_VALUE ? 0f : shortestEdge / 2f);
        }
        ensureSphere();
        setSphereRadiusCap(hopCount == 0 ? 0f : (float) (totalHopLength / hopCount));
        updateSphereRadius();
        if (showCopyWireframe) {
            setCopyWireframe(copy);
        }
        showEmbeddedArcs = true;
    }

    /**
     * Upload one diagnostic's geometry groups, palette-coloured in this order: face groups as
     * dot clouds, path groups as lines, marker groups as large markers. Every argument is
     * world-space flat xyz, as {@code ArrangementDiagnostic}'s resolve methods produce.
     *
     * @param faceGroupCenters one dot cloud per face group
     * @param markerPositions  one point set per marker group
     * @param polylines        one point sequence per path group
     */
    public void setDiagnostic(List<float[]> faceGroupCenters, List<float[]> markerPositions,
            List<float[]> polylines) {
        clearDiagnostic();
        List<float[]> regionClouds = new ArrayList<>(faceGroupCenters);
        regionClouds.addAll(markerPositions);
        int palette = 0;
        for (float[] centers : faceGroupCenters) {
            diagnosticRegions.add(PointSet.cloud(centers, paletteColor(palette++),
                    HIGHLIGHT_REGION_SCALE, 0f));
        }
        for (float[] polyline : polylines) {
            LineSet segments = LineSet.polyline(polyline);
            VertexBuffer buffer = new VertexBuffer();
            buffer.upload(POSITION_LAYOUT, segments.xyz, null);
            diagnosticLines.add(buffer);
            diagnosticLineColors.add(paletteColor(palette++));
            regionClouds.add(segments.xyz);
        }
        for (float[] markers : markerPositions) {
            diagnosticMarkers.add(PointSet.cloud(markers, paletteColor(palette++),
                    HIGHLIGHT_MARKER_SCALE, 0f));
        }
        diagnosticRegionRadius = cloudRadius(regionClouds, new Vector3f());
        ensureSphere();
        updateSphereRadius();
        showDiagnostic = true;
    }

    /** Hides the diagnostic overlay and frees its line buffers. */
    public void clearDiagnostic() {
        for (VertexBuffer buffer : diagnosticLines) {
            buffer.delete();
        }
        diagnosticLines.clear();
        diagnosticLineColors.clear();
        diagnosticRegions.clear();
        diagnosticMarkers.clear();
        showDiagnostic = false;
    }

    /**
     * Caps the shared sphere radius so diagnostic dots stay legible inside a spotlighted region
     * far smaller than the mesh.
     *
     * @param regionRadius world-space radius of the region, or non-positive to keep the cap
     */
    public void capDiagnosticRegion(float regionRadius) {
        if (regionRadius > 0) {
            float regionCap = regionRadius * TEAR_SPHERE_REGION_FRACTION;
            setSphereRadiusCap(sphereRadiusCap > 0
                    ? Math.min(sphereRadiusCap, regionCap)
                    : regionCap);
        }
        updateSphereRadius();
    }

    /**
     * Shows the cover of each given patch as a face-centre dot cloud, the dot radius half the
     * shortest covered edge so a dot never swallows an edge.
     *
     * @param tmesh    embedded T-mesh whose covers are shown
     * @param patchIds live patches to show, coloured in palette order
     */
    public void showPatchCovers(ArcNetwork tmesh, List<Integer> patchIds) {
        HalfEdgeMesh copy = tmesh.topology.copy;
        List<float[]> clouds = new ArrayList<>();
        float[] dotRadii = new float[patchIds.size()];
        Vector3f corner = new Vector3f();
        Vector3f nextCorner = new Vector3f();
        Vector3f center = new Vector3f();
        for (int index = 0; index < patchIds.size(); index++) {
            IntIdList faces = tmesh.corridor.patchFaces(patchIds.get(index));
            float[] centers = new float[faces.size() * 3];
            float smallest = Float.MAX_VALUE;
            for (int face = 0; face < faces.size(); face++) {
                int faceId = faces.get(face);
                int corners = copy.faceHalfEdgeCount(faceId);
                center.zero();
                for (int cornerIndex = 0; cornerIndex < corners; cornerIndex++) {
                    copy.vertexPosition(copy.faceVertexAt(faceId, cornerIndex), corner);
                    copy.vertexPosition(copy.faceVertexAt(faceId, (cornerIndex + 1) % corners),
                            nextCorner);
                    center.add(corner);
                    smallest = Math.min(smallest, corner.distance(nextCorner));
                }
                center.div(Math.max(1, corners));
                centers[face * 3] = center.x;
                centers[face * 3 + 1] = center.y;
                centers[face * 3 + 2] = center.z;
            }
            clouds.add(centers);
            dotRadii[index] = smallest == Float.MAX_VALUE ? 0f : smallest / 2f;
        }
        setPatchClouds(clouds, dotRadii);
    }

    /**
     * Shows one dot cloud per updated patch cover, coloured in palette order; an empty list
     * hides the clouds.
     *
     * @param clouds   one flat-xyz dot cloud per patch, in the order to colour
     * @param dotRadii world-space dot radius per cloud, parallel to {@code clouds}
     */
    public void setPatchClouds(List<float[]> clouds, float[] dotRadii) {
        patchClouds.clear();
        for (int index = 0; index < clouds.size(); index++) {
            patchClouds.add(PointSet.cloud(clouds.get(index), paletteColor(index),
                    PATCH_CLOUD_SCALE, index < dotRadii.length ? dotRadii[index] : 0f));
        }
        ensureSphere();
        updateSphereRadius();
        showPatchClouds = !patchClouds.isEmpty();
    }

    /**
     * Render the enabled overlay layers. Call {@link #render(Camera3D)} first when the
     * translucent base mesh should appear underneath.
     *
     * @param camera active 3D camera
     */
    public void renderOverlays(Camera3D camera) {
        boolean drawSurface = isoSurface.indexCount > 0
                && (showIsoLines || showTraces || showFullIsoGrid);
        boolean drawCross = showCrossField && crossField.indexCount > 0;
        boolean drawConstraints = showConstraints && constraints.indexCount > 0
                && constraintRangeCount != null;
        boolean drawSingularities = showSingularities && singularities != null
                && singularities.count > 0;
        boolean drawNodes = showNodes && graphNodes != null && graphNodes.count > 0;
        boolean drawLayoutFill = showLayoutPatches
                && layoutPatchIndexCount != null && layoutPatchIndexCount.length > 0;
        boolean drawLayoutBoundaries = showLayoutBoundaries && layoutBoundaryVertexCount > 0;
        boolean drawQuadGrid = showQuadGrid && layoutLines.vertexCount > layoutBoundaryVertexCount;
        boolean drawEmbeddedArcs = showEmbeddedArcs && (embeddedArcs.vertexCount > 0
                || embeddedZeroArcs.vertexCount > 0 || embeddedNodes != null);
        boolean drawCopyWireframe = showCopyWireframe && copyWireframe.vertexCount > 0;
        if (!drawSurface && !drawCross && !drawConstraints && !drawSingularities && !drawNodes
                && !drawLayoutFill && !drawLayoutBoundaries && !drawQuadGrid && !drawEmbeddedArcs
                && !drawCopyWireframe) {
            return;
        }
        setupOverlayProjection(camera);
        if (drawCopyWireframe && beginUnlit(camera)) {
            drawLines(copyWireframe, 0, copyWireframe.vertexCount, COLOR_COPY_WIREFRAME,
                    DEFAULT_GL_LINE_WIDTH, LAYOUT_DEPTH_BIAS);
        }
        if (drawSurface) {
            renderSurface(camera);
        }
        if (drawLayoutFill && beginUnlit(camera)) {
            for (int patch = 0; patch < layoutPatchIndexCount.length; patch++) {
                unlitShader.setVec4(SOLIDCOLOR, layoutPatchColors[patch]);
                drawTriangles(layoutFill, layoutPatchIndexStart[patch],
                        layoutPatchIndexCount[patch]);
            }
        }
        if (drawQuadGrid && beginUnlit(camera)) {
            drawLines(layoutLines, layoutBoundaryVertexCount,
                    layoutLines.vertexCount - layoutBoundaryVertexCount, COLOR_QUAD_GRID,
                    DEFAULT_GL_LINE_WIDTH, LAYOUT_DEPTH_BIAS);
        }
        if (drawLayoutBoundaries && beginUnlit(camera)) {
            drawLines(layoutLines, 0, layoutBoundaryVertexCount, COLOR_LAYOUT_BOUNDARY,
                    LAYOUT_LINE_WIDTH, LAYOUT_DEPTH_BIAS);
            drawSpheres(layoutCorners);
        }
        if (drawEmbeddedArcs && beginUnlit(camera)) {
            drawLines(embeddedArcs, 0, embeddedArcs.vertexCount, COLOR_EMBEDDED_ARC,
                    LAYOUT_LINE_WIDTH, LAYOUT_DEPTH_BIAS);
            drawLines(embeddedZeroArcs, 0, embeddedZeroArcs.vertexCount, COLOR_EMBEDDED_ZERO_ARC,
                    LAYOUT_LINE_WIDTH, LAYOUT_DEPTH_BIAS);
            if (showEmbeddedNodes) {
                drawSpheres(embeddedNodes);
            }
        }
        if (drawCross && beginCrossField(camera)) {
            crossFieldShader.setVec4(U_LINE_COLOR, COLOR_U_ARM);
            crossFieldShader.setVec4(V_LINE_COLOR, COLOR_V_ARM);
            drawTriangles(crossField, 0, crossField.indexCount);
        }
        if (drawConstraints && beginCrossField(camera)) {
            for (ConstraintSource source : CONSTRAINT_DRAW_ORDER) {
                int count = constraintRangeCount[source.ordinal()];
                if (count <= 0) {
                    continue;
                }
                Color color = switch (source) {
                case BOUNDARY -> COLOR_CONSTRAINT_BOUNDARY;
                case FEATURE -> COLOR_CONSTRAINT_FEATURE;
                case CURVATURE -> COLOR_CONSTRAINT_CURVATURE;
                default -> COLOR_CONSTRAINT_ANCHOR;
                };
                crossFieldShader.setVec4(U_LINE_COLOR, color);
                crossFieldShader.setVec4(V_LINE_COLOR, color);
                drawTriangles(constraints, constraintRangeStart[source.ordinal()], count);
            }
        }
        if (drawSingularities && beginUnlit(camera)) {
            drawSpheres(singularities);
        }
        if (drawNodes && beginUnlit(camera)) {
            drawSpheres(graphNodes);
        }
    }

    /**
     * Draw the patch-cover dot clouds and the diagnostic overlay's groups, after
     * {@link #renderOverlays(Camera3D)} has set the overlay projection this frame.
     *
     * @param camera active 3D camera
     */
    public void renderHighlights(Camera3D camera) {
        if ((!showDiagnostic && !showPatchClouds) || sphere.vao == 0 || !beginUnlit(camera)) {
            return;
        }
        if (showPatchClouds) {
            for (PointSet cloud : patchClouds) {
                drawSpheres(cloud);
            }
        }
        if (!showDiagnostic) {
            return;
        }
        for (PointSet region : diagnosticRegions) {
            drawSpheres(region);
        }
        for (int index = 0; index < diagnosticLines.size(); index++) {
            VertexBuffer lines = diagnosticLines.get(index);
            drawLines(lines, 0, lines.vertexCount, diagnosticLineColors.get(index),
                    HIGHLIGHT_LINE_WIDTH, HIGHLIGHT_DEPTH_BIAS);
        }
        for (PointSet markers : diagnosticMarkers) {
            drawSpheres(markers);
        }
    }

    @Override
    public void dispose() {
        super.dispose();
        clearDiagnostic();
        isoSurface.delete();
        crossField.delete();
        constraints.delete();
        sphere.delete();
        layoutLines.delete();
        layoutFill.delete();
        copyWireframe.delete();
        embeddedArcs.delete();
        embeddedZeroArcs.delete();
    }

    private void uploadSeamlessSurface() {
        uploadIsoSurface(seamlessMesh, seamlessParametrization, null,
                arrangement != null ? arrangement.traceRecordsByFace : null);
    }

    /**
     * Build and upload the triangle-soup iso-surface: a flip flag from {@code faceFlipped} when
     * given, else from the sign of the UV area, and the face's trace records when given.
     */
    private void uploadIsoSurface(HalfEdgeMesh mesh, UvField uv, BoolField faceFlipped,
            float[][] traceRows) {
        int faceCount = mesh.faceCount();
        int floatsPerCorner = ISO_CORNER_LAYOUT.floatsPerVertex;
        float[] corners = new float[faceCount * CORNERS_PER_FACE * floatsPerCorner];
        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f p2 = new Vector3f();
        Vector3f normal = new Vector3f();
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            mesh.vertexPosition(mesh.faceVertexAt(faceId, 0), p0);
            mesh.vertexPosition(mesh.faceVertexAt(faceId, 1), p1);
            mesh.vertexPosition(mesh.faceVertexAt(faceId, 2), p2);
            faceNormal(p0, p1, p2, normal);
            double u0 = uv.u(faceId, 0);
            double v0 = uv.v(faceId, 0);
            double u1 = uv.u(faceId, 1);
            double v1 = uv.v(faceId, 1);
            double u2 = uv.u(faceId, 2);
            double v2 = uv.v(faceId, 2);
            boolean flipped = faceFlipped != null ? faceFlipped.get(activeFace)
                    : (u1 - u0) * (v2 - v0) - (u2 - u0) * (v1 - v0) <= 0.0;
            float flip = flipped ? 1f : 0f;
            float[] traceRow = traceRows != null ? traceRows[activeFace] : null;
            int base = activeFace * CORNERS_PER_FACE * floatsPerCorner;
            writeIsoCorner(corners, base, p0, normal, (float) u0, (float) v0, flip, traceRow);
            writeIsoCorner(corners, base + floatsPerCorner, p1, normal, (float) u1, (float) v1,
                    flip, traceRow);
            writeIsoCorner(corners, base + 2 * floatsPerCorner, p2, normal, (float) u2,
                    (float) v2, flip, traceRow);
        }
        isoSurface.upload(ISO_CORNER_LAYOUT, corners,
                identityIndices(faceCount * CORNERS_PER_FACE));
    }

    private static void writeIsoCorner(float[] buffer, int offset, Vector3f position,
            Vector3f normal, float u, float v, float flipped, float[] traceRow) {
        writePositionNormal(buffer, offset, position, normal);
        buffer[offset + UV_OFFSET] = u;
        buffer[offset + UV_OFFSET + 1] = v;
        buffer[offset + FLIP_OFFSET] = flipped;
        if (traceRow != null) {
            System.arraycopy(traceRow, 0, buffer, offset + TRACE_OFFSET, TRACE_FLOATS_PER_FACE);
        }
    }

    /**
     * Write one face's cross glyph as three triangle-soup corners: a unit pair of perpendicular
     * arms in the face plane, the first along {@code angle} in the face's {@code (faceX, faceY)}
     * frame, sized to the incircle and floated {@code normalLiftFraction} of an arm along the
     * normal.
     */
    private static void writeFaceGlyph(CrossField field, int activeFace, float angle,
            float crossScale, float normalLiftFraction, float[] glyphs, int cornerBase) {
        HalfEdgeMesh mesh = field.mesh;
        int faceId = mesh.faceIdAt(activeFace);
        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f p2 = new Vector3f();
        Vector3f normal = new Vector3f();
        mesh.vertexPosition(mesh.faceVertexAt(faceId, 0), p0);
        mesh.vertexPosition(mesh.faceVertexAt(faceId, 1), p1);
        mesh.vertexPosition(mesh.faceVertexAt(faceId, 2), p2);
        faceNormal(p0, p1, p2, normal);
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
        int floatsPerCorner = CROSS_GLYPH_LAYOUT.floatsPerVertex;
        Vector3f[] positions = { p0, p1, p2 };
        for (int corner = 0; corner < CORNERS_PER_FACE; corner++) {
            int offset = (cornerBase + corner) * floatsPerCorner;
            writePositionNormal(glyphs, offset, positions[corner], normal);
            glyphs[offset + CENTROID_OFFSET] = cx;
            glyphs[offset + CENTROID_OFFSET + 1] = cy;
            glyphs[offset + CENTROID_OFFSET + 2] = cz;
            glyphs[offset + DIR_U_OFFSET] = dirUx;
            glyphs[offset + DIR_U_OFFSET + 1] = dirUy;
            glyphs[offset + DIR_U_OFFSET + 2] = dirUz;
            glyphs[offset + DIR_V_OFFSET] = dirVx;
            glyphs[offset + DIR_V_OFFSET + 1] = dirVy;
            glyphs[offset + DIR_V_OFFSET + 2] = dirVz;
            glyphs[offset + ARM_LENGTH_OFFSET] = armLength;
        }
    }

    private static void writePositionNormal(float[] buffer, int offset, Vector3f position,
            Vector3f normal) {
        buffer[offset] = position.x;
        buffer[offset + 1] = position.y;
        buffer[offset + 2] = position.z;
        buffer[offset + 3] = normal.x;
        buffer[offset + 4] = normal.y;
        buffer[offset + 5] = normal.z;
    }

    private static void faceNormal(Vector3f p0, Vector3f p1, Vector3f p2, Vector3f result) {
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

    private static int[] identityIndices(int count) {
        int[] indices = new int[count];
        for (int index = 0; index < count; index++) {
            indices[index] = index;
        }
        return indices;
    }

    private static Color paletteColor(int index) {
        return GROUP_PALETTE[index % GROUP_PALETTE.length];
    }

    private static Color nodeColor(EmbeddedNode node) {
        if (node.critical) {
            return node.singularityIndex4 > 0 ? COLOR_POSITIVE_INDEX : COLOR_NEGATIVE_INDEX;
        }
        if (node.border) {
            return COLOR_BOUNDARY_NODE;
        }
        if (node.truncated) {
            return COLOR_TRUNCATED_NODE;
        }
        return COLOR_INTERSECTION_NODE;
    }

    private void ensureSphere() {
        if (sphere.vao != 0) {
            return;
        }
        float[] vertices = new float[ICO_VERTICES.length];
        for (int base = 0; base < ICO_VERTICES.length; base += 3) {
            float x = ICO_VERTICES[base];
            float y = ICO_VERTICES[base + 1];
            float z = ICO_VERTICES[base + 2];
            float length = (float) Math.sqrt(x * x + y * y + z * z);
            vertices[base] = x / length;
            vertices[base + 1] = y / length;
            vertices[base + 2] = z / length;
        }
        sphere.upload(POSITION_LAYOUT, vertices, ICO_TRIANGLES);
    }

    private void updateSphereRadius() {
        Vector3f bMin = getBoundingBoxMin();
        Vector3f bMax = getBoundingBoxMax();
        float bbx = bMax.x - bMin.x;
        float bby = bMax.y - bMin.y;
        float bbz = bMax.z - bMin.z;
        float bboxDiag = (float) Math.sqrt(bbx * bbx + bby * bby + bbz * bbz);
        sphereRadius = SPHERE_RADIUS_FRACTION_OF_BBOX * bboxDiag;
        if (sphereRadiusCap > 0) {
            sphereRadius = Math.min(sphereRadius, sphereRadiusCap);
        }
    }

    private void setSphereRadiusCap(float cap) {
        sphereRadiusCap = cap;
        if (cap > 0) {
            sphereRadius = Math.min(sphereRadius, cap);
        }
    }

    private void setupOverlayProjection(Camera3D camera) {
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

    private boolean beginUnlit(Camera3D camera) {
        if (unlitShader.ID < 0) {
            return false;
        }
        unlitShader.use();
        unlitShader.setMat4(VIEW, camera.view);
        unlitShader.setMat4(PROJECTION, localProjection);
        model.identity();
        unlitShader.setMat4(MODEL, model);
        unlitShader.setFloat(DEPTHBIAS, 0f);
        return true;
    }

    private boolean beginCrossField(Camera3D camera) {
        if (crossFieldShader.ID < 0) {
            return false;
        }
        crossFieldShader.use();
        crossFieldShader.setMat4(VIEW, camera.view);
        crossFieldShader.setMat4(PROJECTION, localProjection);
        model.identity();
        crossFieldShader.setMat4(MODEL, model);
        crossFieldShader.setFloat(DEPTHBIAS, 0f);
        crossFieldShader.setFloat(LINE_HALF_WIDTH, lineHalfWidth);
        return true;
    }

    private void renderSurface(Camera3D camera) {
        boolean traced = showTraces || showFullIsoGrid;
        ShaderProgram shader = traced ? traceUvShader : uvShader;
        if (shader.ID < 0) {
            return;
        }
        shader.use();
        shader.setMat4(VIEW, camera.view);
        shader.setMat4(PROJECTION, localProjection);
        model.identity();
        shader.setMat4(MODEL, model);
        shader.setFloat(DEPTHBIAS, 0f);
        shader.setVec4(BASE_COLOR, baseColor);
        shader.setVec4(U_LINE_COLOR, uLineColor);
        shader.setVec4(V_LINE_COLOR, vLineColor);
        shader.setFloat(LINE_HALF_WIDTH, lineHalfWidth);
        shader.setVec4(FLIPPED_COLOR_UNIFORM, flippedColor);
        if (traced) {
            shader.setFloat(DRAW_FULL_ISO_GRID_UNIFORM, showFullIsoGrid ? 1f : 0f);
            shader.setFloat(USE_PATCH_COLOR_UNIFORM, 0f);
        }
        drawTriangles(isoSurface, 0, isoSurface.indexCount);
    }

    private static void drawTriangles(VertexBuffer buffer, int firstIndex, int indexCount) {
        GL gl = Platforms.gl();
        gl.bindVertexArray(buffer.vao);
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), buffer.ebo);
        gl.drawElements(gl.TRIANGLES(), indexCount, gl.UNSIGNED_INT(), firstIndex * Integer.BYTES);
    }

    private void drawLines(VertexBuffer buffer, int firstVertex, int vertexCount, Color color,
            float width, float depthBias) {
        if (buffer.vao == 0 || vertexCount <= 0) {
            return;
        }
        GL gl = Platforms.gl();
        unlitShader.setFloat(DEPTHBIAS, depthBias);
        unlitShader.setVec4(SOLIDCOLOR, color);
        model.identity();
        unlitShader.setMat4(MODEL, model);
        gl.lineWidth(width);
        gl.bindVertexArray(buffer.vao);
        gl.drawArrays(gl.LINES(), firstVertex, vertexCount);
        gl.lineWidth(DEFAULT_GL_LINE_WIDTH);
    }

    private void drawSpheres(PointSet points) {
        if (points == null || points.count == 0 || sphere.vao == 0) {
            return;
        }
        GL gl = Platforms.gl();
        unlitShader.setFloat(DEPTHBIAS, 0f);
        gl.bindVertexArray(sphere.vao);
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), sphere.ebo);
        for (int index = 0; index < points.count; index++) {
            int base = index * 3;
            float radius = sphereRadius * points.scale;
            if (points.radiusCaps[index] > 0) {
                radius = Math.min(radius, points.radiusCaps[index]);
            }
            model.identity()
                    .translate(points.xyz[base], points.xyz[base + 1], points.xyz[base + 2])
                    .scale(radius);
            unlitShader.setMat4(MODEL, model);
            unlitShader.setVec4(SOLIDCOLOR, points.colors[index]);
            gl.drawElements(gl.TRIANGLES(), sphere.indexCount, gl.UNSIGNED_INT(), 0);
        }
    }
}
