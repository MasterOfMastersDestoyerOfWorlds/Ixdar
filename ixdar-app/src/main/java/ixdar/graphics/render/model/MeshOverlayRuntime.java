package ixdar.graphics.render.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;

import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.TriangleGeometry;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.Palette;
import ixdar.graphics.render.shaders.MeshLineShader;
import ixdar.graphics.render.shaders.ShaderProgram;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.geometry.mesh.data.paths.query.SurfaceFaceLocator;
import ixdar.scenes.Scene;

/**
 * General overlays drawn over a half-edge mesh surface: lines, and dots in named layers.
 * Each setter converts one value into a {@link LineSet}, a {@link SurfaceDots} or a corner array,
 * and {@link VertexBuffer#upload} is the one upload path per {@link VertexLayout}.
 */
public class MeshOverlayRuntime extends HalfEdgeMeshRuntime {

    public static final String GROUP_PALETTE_ORDER =
            "yellow, green, magenta, cyan, purple, white, red, azure";

    public static final VertexLayout POSITION_LAYOUT =
            new VertexLayout(new int[] { 0 }, new int[] { 3 });
    public static final float LAYOUT_LINE_WIDTH = 2.5f;
    public static final float DEFAULT_GL_LINE_WIDTH = 1f;

    public static final String DIAGNOSTIC_REGION_DOTS = "diagnostic-regions";
    public static final String DIAGNOSTIC_MARKER_DOTS = "diagnostic-markers";
    public static final String PATCH_CLOUD_DOTS = "patch-clouds";

    private static final float HIGHLIGHT_LINE_WIDTH = 5f;
    private static final float REGION_DOT_PIXELS = 6f;
    private static final float MARKER_DOT_PIXELS = 16f;
    private static final float PATCH_CLOUD_DOT_PIXELS = 5f;

    public final ShaderProgram unlitShader;

    /** Draws every dot layer: a fixed pixel size, depth-tested like the overlay lines. */
    public final MeshLineShader dotShader;

    public boolean showPatchClouds;
    public boolean showDiagnostic;
    public boolean showLineGroups = true;

    /** Every colour group's line segments end to end, two vertices per segment. */
    private final VertexBuffer lineGroups = new VertexBuffer();
    private final List<VertexBuffer> diagnosticLines = new ArrayList<>();
    private final List<Color> diagnosticLineColors = new ArrayList<>();
    /** Each owner's dots by layer name, drawn lowest order first so a higher one wins a tie. */
    private final Map<String, DotLayer> dotLayers = new HashMap<>();
    private int[] lineGroupSegmentStart = new int[0];
    private Color[] lineGroupColors = new Color[0];

    private final Matrix4f model = new Matrix4f();

    /** One owner's uploaded dots, where they draw among the layers, and whether they show. */
    private static final class DotLayer {
        final VertexBuffer discs = new VertexBuffer();
        int order;
        boolean shown = true;
    }

    /** Build the runtime and initialise its overlay shaders. */
    public MeshOverlayRuntime() {
        super();
        this.unlitShader = ShaderProgram.ShaderType.MeshUnlit.getShader();
        this.dotShader = (MeshLineShader) ShaderProgram.ShaderType.MeshAnchor.getShader();
        this.dotShader.init();
    }

    /**
     * Replaces the rendered mesh, dropping every overlay first: each is indexed by the previous
     * mesh's face or vertex count, so one surviving into the next mesh's upload reads out of
     * bounds.
     *
     * @param mesh source mesh, or {@code null} to clear
     */
    @Override
    public void upload(MeshTopology mesh) {
        clearMeshState();
        super.upload(mesh);
    }

    /**
     * Drops every overlay tied to one mesh: a tool's diagnostic, line groups and every dot layer.
     */
    public void clearMeshState() {
        clearDiagnostic();
        clearLineGroups();
        for (DotLayer layer : dotLayers.values()) {
            layer.discs.delete();
        }
        dotLayers.clear();
        showPatchClouds = false;
    }

    /**
     * Upload one line buffer whose groups each draw in their own colour, so a set of curves over
     * the surface is one upload and one draw per colour.
     *
     * @param lines             every group's segments end to end, each already carrying the
     *                          normals of the faces it lies on
     * @param groupSegmentStart first segment of each group plus a closing total, so it is one
     *                          longer than the group count
     * @param groupColors       one colour per group
     */
    public void setLineGroups(LineSet lines, int[] groupSegmentStart, Color[] groupColors) {
        if (lines == null || groupSegmentStart == null || groupSegmentStart.length < 2) {
            clearLineGroups();
            return;
        }
        lineGroupSegmentStart = groupSegmentStart;
        lineGroupColors = groupColors == null ? new Color[0] : groupColors;
        lineGroups.upload(LineSet.LAYOUT, lines.vertices, null);
    }

    /** Drop the coloured line groups; nothing is drawn until {@link #setLineGroups} runs again. */
    public void clearLineGroups() {
        lineGroupSegmentStart = new int[0];
        lineGroupColors = new Color[0];
        lineGroups.delete();
    }

    /**
     * Show one owner's dots as a layer of its own, replacing that layer's previous dots and
     * leaving every other layer alone. Layers draw lowest order first, so where two dots tie on
     * the surface the higher order's shows.
     *
     * @param layer the owner's layer name
     * @param order where the layer draws among the others
     * @param dots  the dots, or {@code null} or empty to clear the layer
     */
    public void setDots(String layer, int order, SurfaceDots dots) {
        if (dots == null || dots.count == 0) {
            clearDots(layer);
            return;
        }
        DotLayer shown = dotLayers.computeIfAbsent(layer, name -> new DotLayer());
        shown.order = order;
        shown.discs.upload(SurfaceDots.DISC_CORNER_LAYOUT, dots.vertices(), null);
    }

    /**
     * Drop one layer's dots.
     *
     * @param layer the owner's layer name
     */
    public void clearDots(String layer) {
        DotLayer dropped = dotLayers.remove(layer);
        if (dropped != null) {
            dropped.discs.delete();
        }
    }

    /**
     * Show or hide one layer's dots without dropping them; a layer not yet set takes the setting
     * when it is.
     *
     * @param layer the owner's layer name
     * @param shown whether its dots draw
     */
    public void showDots(String layer, boolean shown) {
        dotLayers.computeIfAbsent(layer, name -> new DotLayer()).shown = shown;
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
        List<Color> palette = new ArrayList<>();
        Color[] cycle = Scene.activePalette().overlayGroup;
        int groups = faceGroupCenters.size() + polylines.size() + markerPositions.size();
        for (int group = 0; group < groups; group++) {
            palette.add(Palette.entry(cycle, group));
        }
        setDiagnostic(faceGroupCenters, markerPositions, polylines, palette);
    }

    /**
     * Upload one diagnostic's geometry groups in the given colours: face groups as dot clouds,
     * path groups as lines, marker groups as large markers.
     *
     * @param faceGroupCenters one dot cloud per face group
     * @param markerPositions  one point set per marker group
     * @param polylines        one point sequence per path group
     * @param groupColors      one colour per group, face groups first, then paths, then markers
     */
    public void setDiagnostic(List<float[]> faceGroupCenters, List<float[]> markerPositions,
            List<float[]> polylines, List<Color> groupColors) {
        clearDiagnostic();
        int palette = 0;
        SurfaceDots regions = new SurfaceDots();
        for (float[] centers : faceGroupCenters) {
            regions.addCloud(centers, groupColors.get(palette++), REGION_DOT_PIXELS);
        }
        SurfaceFaceLocator locator = surfaceFaceLocator();
        for (float[] polyline : polylines) {
            int points = polyline.length / TriangleGeometry.COMPONENTS;
            float[] endpoints = new float[Math.max(0, points - 1) * 2 * TriangleGeometry.COMPONENTS];
            for (int point = 1; point < points; point++) {
                System.arraycopy(polyline, (point - 1) * TriangleGeometry.COMPONENTS, endpoints,
                        (point - 1) * 2 * TriangleGeometry.COMPONENTS,
                        2 * TriangleGeometry.COMPONENTS);
            }
            LineSet segments = new LineSet(Math.max(0, points - 1));
            if (locator != null) {
                locator.appendSegments(endpoints, segments);
            }
            VertexBuffer buffer = new VertexBuffer();
            buffer.upload(LineSet.LAYOUT, segments.vertices, null);
            diagnosticLines.add(buffer);
            diagnosticLineColors.add(groupColors.get(palette++));
        }
        SurfaceDots markers = new SurfaceDots();
        for (float[] markerGroup : markerPositions) {
            markers.addCloud(markerGroup, groupColors.get(palette++), MARKER_DOT_PIXELS);
        }
        setDots(DIAGNOSTIC_REGION_DOTS, 0, regions);
        setDots(DIAGNOSTIC_MARKER_DOTS, 1, markers);
        showDiagnostic = true;
    }

    /** Hides the diagnostic overlay and frees its line buffers and dots. */
    public void clearDiagnostic() {
        for (VertexBuffer buffer : diagnosticLines) {
            buffer.delete();
        }
        diagnosticLines.clear();
        diagnosticLineColors.clear();
        clearDots(DIAGNOSTIC_REGION_DOTS);
        clearDots(DIAGNOSTIC_MARKER_DOTS);
        showDiagnostic = false;
    }

    /**
     * Shows one dot cloud per updated patch cover, coloured in palette order; an empty list
     * hides the clouds.
     *
     * @param clouds one flat-xyz dot cloud per patch, in the order to colour
     */
    public void setPatchClouds(List<float[]> clouds) {
        Color[] cycle = Scene.activePalette().overlayGroup;
        SurfaceDots dots = new SurfaceDots();
        for (int index = 0; index < clouds.size(); index++) {
            dots.addCloud(clouds.get(index), Palette.entry(cycle, index), PATCH_CLOUD_DOT_PIXELS);
        }
        setDots(PATCH_CLOUD_DOTS, 0, dots);
        showPatchClouds = dots.count > 0;
    }

    /**
     * Render the coloured line groups and every shown dot layer. Call {@link #render(Camera3D)}
     * first when the translucent base mesh should appear underneath.
     *
     * @param camera active 3D camera
     */
    public void renderOverlays(Camera3D camera) {
        showDots(DIAGNOSTIC_REGION_DOTS, showDiagnostic);
        showDots(DIAGNOSTIC_MARKER_DOTS, showDiagnostic);
        showDots(PATCH_CLOUD_DOTS, showPatchClouds);
        boolean drawLineGroups = showLineGroups && lineGroups.vertexCount > 0
                && lineGroupSegmentStart.length > 1;
        List<DotLayer> drawn = new ArrayList<>();
        for (DotLayer layer : dotLayers.values()) {
            if (layer.shown && layer.discs.vertexCount > 0) {
                drawn.add(layer);
            }
        }
        if (!drawLineGroups && drawn.isEmpty()) {
            return;
        }
        updateOverlayProjection(camera);
        if (drawLineGroups) {
            // The groups test against the surface but leave its depth alone, so the dots drawn
            // next are not lost to a ring line tying them at the vertex they share.
            Platforms.gl().depthMask(false);
            for (int group = 0; group + 1 < lineGroupSegmentStart.length; group++) {
                int firstVertex = 2 * lineGroupSegmentStart[group];
                int vertices =
                        2 * (lineGroupSegmentStart[group + 1] - lineGroupSegmentStart[group]);
                drawLines(camera, lineGroups, firstVertex, vertices,
                        group < lineGroupColors.length ? lineGroupColors[group]
                                : Scene.activePalette().line,
                        LAYOUT_LINE_WIDTH);
            }
            Platforms.gl().depthMask(true);
        }
        if (!drawn.isEmpty() && dotShader.ID >= 0) {
            // The discs take their faces' depth themselves; the fill offset would only push the
            // flat disc behind the surface it marks.
            GL gl = Platforms.gl();
            gl.disable(gl.POLYGON_OFFSET_FILL());
            dotShader.use(camera.view, camera.projection, model.identity());
            drawn.sort(Comparator.comparingInt(layer -> layer.order));
            for (DotLayer layer : drawn) {
                gl.bindVertexArray(layer.discs.vao);
                gl.drawArrays(gl.TRIANGLES(), 0, layer.discs.vertexCount);
            }
            gl.enable(gl.POLYGON_OFFSET_FILL());
        }
    }

    /**
     * Draw the diagnostic overlay's path lines, after {@link #renderOverlays(Camera3D)} has set
     * the overlay projection this frame.
     *
     * @param camera active 3D camera
     */
    public void renderHighlights(Camera3D camera) {
        if (!showDiagnostic || diagnosticLines.isEmpty()) {
            return;
        }
        updateOverlayProjection(camera);
        for (int index = 0; index < diagnosticLines.size(); index++) {
            VertexBuffer lines = diagnosticLines.get(index);
            drawLines(camera, lines, 0, lines.vertexCount, diagnosticLineColors.get(index),
                    HIGHLIGHT_LINE_WIDTH);
        }
    }

    @Override
    public void dispose() {
        super.dispose();
        clearDiagnostic();
        lineGroups.delete();
        clearMeshState();
    }

    /**
     * Bind the unlit shader with the camera's matrices, ready for a solid-colour triangle draw.
     *
     * @param camera active 3D camera
     * @return whether the shader compiled and is bound
     */
    public boolean beginUnlit(Camera3D camera) {
        if (unlitShader.ID < 0) {
            return false;
        }
        unlitShader.use();
        unlitShader.setMat4(VIEW, camera.view);
        unlitShader.setMat4(PROJECTION, camera.projection);
        model.identity();
        unlitShader.setMat4(MODEL, model);
        return true;
    }

    /**
     * Draw a range of a {@link LineSet} buffer in one colour, dropping segments whose faces
     * both turn away from the camera.
     *
     * @param camera      active 3D camera
     * @param buffer      line vertices uploaded with {@link LineSet#LAYOUT}
     * @param firstVertex first vertex of the range
     * @param vertexCount vertices in the range, two per segment
     * @param color       line colour
     * @param width       GL line width in pixels
     */
    public void drawLines(Camera3D camera, VertexBuffer buffer, int firstVertex,
            int vertexCount, Color color, float width) {
        if (buffer.vao == 0 || vertexCount <= 0 || lineShader.ID < 0) {
            return;
        }
        GL gl = Platforms.gl();
        lineShader.use(camera.view, camera.projection, model.identity());
        lineShader.setVec4(SOLIDCOLOR, color);
        gl.lineWidth(width);
        gl.bindVertexArray(buffer.vao);
        gl.drawArrays(gl.LINES(), firstVertex, vertexCount);
        gl.lineWidth(DEFAULT_GL_LINE_WIDTH);
    }
}
