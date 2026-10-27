package ixdar.graphics.render.model;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.fields.crossfield.CrossField;
import ixdar.geometry.mesh.data.fields.crossfield.constraint.ConstraintSource;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.IntIdList;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.model.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.model.EmbeddedNode;
import ixdar.geometry.mesh.quadlayout.extraction.PatchSurfaceGeometry;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.Palette;
import ixdar.graphics.render.color.RegionColouring;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.nodegraph.field.BoolField;
import ixdar.nodegraph.field.IntField;
import ixdar.nodegraph.field.UvField;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.scenes.Scene;

/**
 * The quad-layout pipeline's debug views (parametrization, traces, cross field, T-mesh, layout
 * patches), each setter turning one pipeline value into a {@link LineSet}, {@link PointSet} or
 * corner array drawn through the generic overlay passes.
 */
public class QuadLayoutOverlay extends MeshOverlayRuntime {

    public static final int CONSTRAINT_BOUNDARY = 1;
    public static final int DIRECTION_V = 3;
    public static final int DIRECTION_U = 4;
    public static final int CONSTRAINT_CURVATURE = 5;
    public static final int CONSTRAINT_FEATURE = 8;
    public static final int CONSTRAINT_ANCHOR = 9;
    public static final float SINGULARITY_ALPHA = 0.5f;
    public static final float COPY_WIREFRAME_ALPHA = 0.25f;

    private static final VertexLayout ISO_CORNER_LAYOUT = new VertexLayout(
            new int[] { 0, 1, 3, 4, 5, 6, 7, 8 }, new int[] { 3, 3, 2, 1, 4, 4, 4, 4 });
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
    private static final float CONSTRAINT_NORMAL_LIFT = 0.25f;
    private static final float DEFAULT_LINE_HALF_WIDTH = 1.0f;
    private static final float LAYOUT_PATCH_ALPHA = 0.55f;
    private static final float CORNER_SPHERE_SCALE = 0.7f;

    private static final String BASE_COLOR = "baseColor";
    private static final String U_LINE_COLOR = "uLineColor";
    private static final String V_LINE_COLOR = "vLineColor";
    private static final String LINE_HALF_WIDTH = "lineHalfWidth";
    private static final String FLIPPED_COLOR_UNIFORM = "flippedColor";
    private static final String DRAW_FULL_ISO_GRID_UNIFORM = "drawFullIsoGrid";

    private static final ConstraintSource[] CONSTRAINT_DRAW_ORDER = {
            ConstraintSource.BOUNDARY, ConstraintSource.FEATURE,
            ConstraintSource.CURVATURE, ConstraintSource.ANCHOR
    };

    public final ShaderProgram uvShader;
    public final ShaderProgram traceUvShader;
    public final ShaderProgram crossFieldShader;

    public float lineHalfWidth = DEFAULT_LINE_HALF_WIDTH;

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

    private ArcNetwork arrangement;
    private UvField seamlessParametrization;
    private HalfEdgeMesh seamlessMesh;

    private final VertexBuffer isoSurface = new VertexBuffer();
    private final VertexBuffer crossField = new VertexBuffer();
    private final VertexBuffer constraints = new VertexBuffer();
    /** Patch boundary edges first, then the interior quad grid edges. */
    private final VertexBuffer layoutLines = new VertexBuffer();
    private final VertexBuffer layoutFill = new VertexBuffer();
    private final VertexBuffer copyWireframe = new VertexBuffer();
    private final VertexBuffer embeddedArcs = new VertexBuffer();
    private final VertexBuffer embeddedZeroArcs = new VertexBuffer();
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

    private final Matrix4f identity = new Matrix4f();

    /** Build the overlay and initialise the parametrization and cross-field shaders. */
    public QuadLayoutOverlay() {
        super();
        this.uvShader = ShaderProgram.ShaderType.MeshUv.getShader();
        this.uvShader.init();
        this.traceUvShader = ShaderProgram.ShaderType.MeshUvTraces.getShader();
        this.traceUvShader.init();
        this.crossFieldShader = ShaderProgram.ShaderType.MeshCrossField.getShader();
        this.crossFieldShader.init();
    }

    /**
     * Drops the generic overlays and every pipeline view tied to the mesh: the parametrization
     * and arrangement traced over it, their buffers and markers, and the per-patch and
     * per-constraint index ranges.
     */
    @Override
    public void clearMeshState() {
        super.clearMeshState();
        arrangement = null;
        seamlessParametrization = null;
        seamlessMesh = null;
        isoSurface.delete();
        crossField.delete();
        constraints.delete();
        layoutLines.delete();
        layoutFill.delete();
        copyWireframe.delete();
        embeddedArcs.delete();
        embeddedZeroArcs.delete();
        singularities = null;
        graphNodes = null;
        layoutCorners = null;
        embeddedNodes = null;
        layoutBoundaryVertexCount = 0;
        constraintRangeStart = null;
        constraintRangeCount = null;
        layoutPatchIndexStart = null;
        layoutPatchIndexCount = null;
        layoutPatchColors = null;
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
        Palette palette = Scene.activePalette();
        for (EmbeddedNode node : graph.nodes) {
            if (node.position == null) {
                continue;
            }
            Color color = palette.text;
            if (node.critical) {
                color = singularityColor(palette, node.singularityIndex4);
            } else if (node.border) {
                color = palette.highlight;
            } else if (node.truncated) {
                color = palette.warning;
            }
            graphNodes.add(node.position, color, 0f);
        }
    }

    /**
     * Upload a per-patch rectangle parametrization as the iso-surface, shading each flagged
     * folded face in the palette's flipped face colour. Shows the embedded T-mesh's per-patch maps, whose
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
        updateSphereRadius();
        singularities = new PointSet(index4.length(), 1f);
        Vector3f position = new Vector3f();
        Palette palette = Scene.activePalette();
        for (int vertex = 0; vertex < index4.length(); vertex++) {
            if (index4.get(vertex) == 0) {
                continue;
            }
            mesh.vertexPosition(mesh.vertexIdAt(vertex), position);
            singularities.add(position, singularityColor(palette, index4.get(vertex)), 0f);
        }
    }

    /**
     * The half-transparent marker colour of a singularity: success for a positive index, error for
     * a negative one.
     *
     * @param palette the palette drawn with
     * @param index4  the singularity's index, times four
     * @return the marker colour
     */
    private static Color singularityColor(Palette palette, int index4) {
        return (index4 > 0 ? palette.success : palette.error).withAlpha(SINGULARITY_ALPHA);
    }

    /**
     * Upload the finished layout: patch boundary edges then interior quad grid edges in one
     * line buffer, every patch's grid as triangles in a bright patch cycle colour no
     * neighbouring patch shares, and a corner marker at every vertex with one incident face.
     *
     * @param patchGrid per-patch quad-grid geometry with the {@code patch_id} per-face slot
     * @param tmesh     the T-mesh whose patch ids the slot holds, giving patch adjacency
     */
    public void setLayoutPatchSurfaces(GeometryBundle patchGrid, ArcNetwork tmesh) {
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
        Palette palette = Scene.activePalette();
        Vector3f position = new Vector3f();
        for (int dense = 0; dense < vertexCount; dense++) {
            int vertexId = mesh.vertexIdAt(dense);
            denseOf[vertexId] = dense;
            mesh.vertexPosition(vertexId, position);
            gridVertices[dense * 3] = position.x;
            gridVertices[dense * 3 + 1] = position.y;
            gridVertices[dense * 3 + 2] = position.z;
            if (mesh.vertexFaceCount(vertexId) == 1) {
                layoutCorners.add(position, palette.accent, 0f);
            }
        }

        // Patch boundary edges first, then the interior grid edges.
        LineSet lines = new LineSet(mesh.edgeCount());
        for (boolean boundary : new boolean[] { true, false }) {
            for (int edge = 0; edge < mesh.edgeCount(); edge++) {
                int edgeId = mesh.edgeIdAt(edge);
                if (mesh.isBoundaryEdge(edgeId) == boundary) {
                    lines.edge(mesh, edgeId);
                }
            }
            if (boundary) {
                layoutBoundaryVertexCount = lines.vertexCount();
            }
        }
        layoutLines.upload(LineSet.LAYOUT, lines.vertices, null);

        int faceCount = mesh.faceCount();
        int[] facesByPatchId = new int[tmesh.patches.size()];
        for (int face = 0; face < faceCount; face++) {
            facesByPatchId[patchIds.get(face)]++;
        }
        RegionColouring colouring = new RegionColouring();
        colouring.colour(tmesh.patchNeighbours(), facesByPatchId, null, null, palette.categories);
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
                        Palette.shadedEntry(palette.categories, colouring.colourByNode[patchId])
                                .withAlpha(LAYOUT_PATCH_ALPHA);
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
        updateSphereRadius();
    }

    /**
     * Upload every edge of the working copy as outlines, so refinement density is visible.
     *
     * @param copy refined working copy to outline; {@code null} drops the buffer
     */
    public void setCopyWireframe(HalfEdgeMesh copy) {
        showCopyWireframe = copy != null;
        if (copy == null) {
            copyWireframe.delete();
            return;
        }
        LineSet outlines = new LineSet(copy.edgeCount());
        for (int edge = 0; edge < copy.edgeCount(); edge++) {
            outlines.edge(copy, copy.edgeIdAt(edge));
        }
        copyWireframe.upload(LineSet.LAYOUT, outlines.vertices, null);
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
                target.vertexStep(copy, path.get(index - 1), path.get(index));
                totalHopLength += target.start.distance(target.end);
                hopCount++;
            }
        }
        embeddedArcs.upload(LineSet.LAYOUT, positive.vertices, null);
        embeddedZeroArcs.upload(LineSet.LAYOUT, zero.vertices, null);

        embeddedNodes = new PointSet(tmesh.nodes.size(), 1f);
        Palette palette = Scene.activePalette();
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
                    node.critical ? palette.highlight : palette.linkText,
                    shortestEdge == Float.MAX_VALUE ? 0f : shortestEdge / 2f);
        }
        setSphereRadiusCap(hopCount == 0 ? 0f : (float) (totalHopLength / hopCount));
        updateSphereRadius();
        if (showCopyWireframe) {
            setCopyWireframe(copy);
        }
        showEmbeddedArcs = true;
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
     * Render the enabled pipeline views around the generic overlays: outlines, surface, patches
     * and arcs beneath them, glyphs and markers above. Call {@link #render(Camera3D)} first when
     * the translucent base mesh should appear underneath.
     *
     * @param camera active 3D camera
     */
    @Override
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
        if (drawSurface || drawCross || drawConstraints || drawSingularities || drawNodes
                || drawLayoutFill || drawLayoutBoundaries || drawQuadGrid || drawEmbeddedArcs
                || drawCopyWireframe) {
            updateOverlayProjection(camera);
        }
        Palette palette = Scene.activePalette();
        Color uDirection = palette.categories[DIRECTION_U];
        Color vDirection = palette.categories[DIRECTION_V];
        if (drawCopyWireframe) {
            drawLines(camera, copyWireframe, 0, copyWireframe.vertexCount,
                    palette.text.withAlpha(COPY_WIREFRAME_ALPHA), DEFAULT_GL_LINE_WIDTH);
        }
        boolean traced = showTraces || showFullIsoGrid;
        ShaderProgram surfaceShader = traced ? traceUvShader : uvShader;
        if (drawSurface && surfaceShader.ID >= 0) {
            surfaceShader.use();
            surfaceShader.setMat4(VIEW, camera.view);
            surfaceShader.setMat4(PROJECTION, camera.projection);
            surfaceShader.setMat4(MODEL, identity.identity());
            surfaceShader.setVec4(BASE_COLOR, palette.surface);
            surfaceShader.setVec4(U_LINE_COLOR, uDirection);
            surfaceShader.setVec4(V_LINE_COLOR, vDirection);
            surfaceShader.setFloat(LINE_HALF_WIDTH, lineHalfWidth);
            surfaceShader.setVec4(FLIPPED_COLOR_UNIFORM, palette.error);
            if (traced) {
                surfaceShader.setFloat(DRAW_FULL_ISO_GRID_UNIFORM, showFullIsoGrid ? 1f : 0f);
            }
            drawTriangles(isoSurface, 0, isoSurface.indexCount);
        }
        if (drawLayoutFill && beginUnlit(camera)) {
            for (int patch = 0; patch < layoutPatchIndexCount.length; patch++) {
                unlitShader.setVec4(SOLIDCOLOR, layoutPatchColors[patch]);
                drawTriangles(layoutFill, layoutPatchIndexStart[patch],
                        layoutPatchIndexCount[patch]);
            }
        }
        if (drawQuadGrid) {
            drawLines(camera, layoutLines, layoutBoundaryVertexCount,
                    layoutLines.vertexCount - layoutBoundaryVertexCount, palette.gridLine,
                    DEFAULT_GL_LINE_WIDTH);
        }
        if (drawLayoutBoundaries) {
            drawLines(camera, layoutLines, 0, layoutBoundaryVertexCount, palette.text,
                    LAYOUT_LINE_WIDTH);
            if (beginUnlit(camera)) {
                drawSpheres(layoutCorners);
            }
        }
        if (drawEmbeddedArcs) {
            drawLines(camera, embeddedArcs, 0, embeddedArcs.vertexCount, palette.accent,
                    LAYOUT_LINE_WIDTH);
            drawLines(camera, embeddedZeroArcs, 0, embeddedZeroArcs.vertexCount,
                    palette.error, LAYOUT_LINE_WIDTH);
            if (showEmbeddedNodes && beginUnlit(camera)) {
                drawSpheres(embeddedNodes);
            }
        }
        super.renderOverlays(camera);
        if (drawCross && beginCrossField(camera)) {
            crossFieldShader.setVec4(U_LINE_COLOR, uDirection);
            crossFieldShader.setVec4(V_LINE_COLOR, vDirection);
            drawTriangles(crossField, 0, crossField.indexCount);
        }
        if (drawConstraints && beginCrossField(camera)) {
            for (ConstraintSource source : CONSTRAINT_DRAW_ORDER) {
                int count = constraintRangeCount[source.ordinal()];
                if (count <= 0) {
                    continue;
                }
                Color color = switch (source) {
                case BOUNDARY -> palette.categories[CONSTRAINT_BOUNDARY];
                case FEATURE -> palette.categories[CONSTRAINT_FEATURE];
                case CURVATURE -> palette.categories[CONSTRAINT_CURVATURE];
                default -> palette.categories[CONSTRAINT_ANCHOR];
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

    @Override
    public void dispose() {
        super.dispose();
        clearMeshState();
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

    private static void drawTriangles(VertexBuffer buffer, int firstIndex, int indexCount) {
        GL gl = Platforms.gl();
        gl.bindVertexArray(buffer.vao);
        gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER(), buffer.ebo);
        gl.drawElements(gl.TRIANGLES(), indexCount, gl.UNSIGNED_INT(), firstIndex * Integer.BYTES);
    }

    private boolean beginCrossField(Camera3D camera) {
        if (crossFieldShader.ID < 0) {
            return false;
        }
        crossFieldShader.use();
        crossFieldShader.setMat4(VIEW, camera.view);
        crossFieldShader.setMat4(PROJECTION, camera.projection);
        crossFieldShader.setMat4(MODEL, identity.identity());
        crossFieldShader.setFloat(LINE_HALF_WIDTH, lineHalfWidth);
        return true;
    }
}
