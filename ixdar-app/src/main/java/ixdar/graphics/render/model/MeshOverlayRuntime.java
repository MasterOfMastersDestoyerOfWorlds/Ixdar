package ixdar.graphics.render.model;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector3f;

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
 * General overlays drawn over a half-edge mesh surface: points, lines and markers.
 * Each setter converts one value into a {@link LineSet}, a {@link PointSet} or a corner array,
 * and {@link VertexBuffer#upload} is the one upload path per {@link VertexLayout}.
 */
public class MeshOverlayRuntime extends HalfEdgeMeshRuntime {

    public static final String GROUP_PALETTE_ORDER =
            "yellow, green, magenta, cyan, purple, white, red, azure";

    public static final VertexLayout POSITION_LAYOUT =
            new VertexLayout(new int[] { 0 }, new int[] { 3 });
    public static final float LAYOUT_LINE_WIDTH = 2.5f;
    public static final float DEFAULT_GL_LINE_WIDTH = 1f;

    private static final float SPHERE_RADIUS_FRACTION_OF_BBOX = 0.005f;
    private static final float HIGHLIGHT_LINE_WIDTH = 5f;
    private static final float HIGHLIGHT_REGION_SCALE = 0.22f;
    private static final float HIGHLIGHT_MARKER_SCALE = 2.2f;
    private static final float PATCH_CLOUD_SCALE = 1f;
    private static final VertexLayout ANCHOR_DISC_LAYOUT = new VertexLayout(
            new int[] { 0, 1, 2, 3, 4 }, new int[] { 3, 2, 3, 3, 1 });
    private static final float[] DISC_CORNERS = { -1, -1, 1, -1, 1, 1, -1, -1, 1, 1, -1, 1 };
    private static final int DISC_CORNER_COUNT = 6;
    private static final float TEAR_SPHERE_REGION_FRACTION = 0.02f;

    private static final float PHI = (1f + (float) Math.sqrt(5f)) * 0.5f;
    private static final float[] ICO_VERTICES = {
            -1, PHI, 0, 1, PHI, 0, -1, -PHI, 0, 1, -PHI, 0,
            0, -1, PHI, 0, 1, PHI, 0, -1, -PHI, 0, 1, -PHI,
            PHI, 0, -1, PHI, 0, 1, -PHI, 0, -1, -PHI, 0, 1
    };
    private static final int[] ICO_TRIANGLES = {
            0, 11, 5, 0, 5, 1, 0, 1, 7, 0, 7, 10, 0, 10, 11,
            1, 5, 9, 5, 11, 4, 11, 10, 2, 10, 7, 6, 7, 1, 8,
            3, 9, 4, 3, 4, 2, 3, 2, 6, 3, 6, 8, 3, 8, 9,
            4, 9, 5, 2, 4, 11, 6, 2, 10, 8, 6, 7, 9, 8, 1
    };

    public final ShaderProgram unlitShader;

    /** Draws the anchor discs: a fixed pixel size, depth-tested like the overlay lines. */
    public final MeshLineShader anchorShader;

    public boolean showPatchClouds;
    public boolean showDiagnostic;
    public boolean showLineGroups = true;

    /** Sphere radius derived from the mesh bounding-box diagonal, under {@link #sphereRadiusCap}. */
    public float sphereRadius;
    /** Ceiling on {@link #sphereRadius} surviving every recompute; zero leaves it uncapped. */
    public float sphereRadiusCap;
    /** Radius of the last uploaded diagnostic's geometry around its centroid. */
    public float diagnosticRegionRadius;

    private final VertexBuffer sphere = new VertexBuffer();
    /** Every colour group's line segments end to end, two vertices per segment. */
    private final VertexBuffer lineGroups = new VertexBuffer();
    private final List<VertexBuffer> diagnosticLines = new ArrayList<>();
    private final List<Color> diagnosticLineColors = new ArrayList<>();
    private final List<PointSet> diagnosticRegions = new ArrayList<>();
    private final List<PointSet> diagnosticMarkers = new ArrayList<>();
    private final List<PointSet> patchClouds = new ArrayList<>();
    /** One screen-space disc per anchor and face around it, six corners each. */
    private final VertexBuffer anchorDiscs = new VertexBuffer();
    private int[] lineGroupSegmentStart = new int[0];
    private Color[] lineGroupColors = new Color[0];

    private final Matrix4f model = new Matrix4f();

    /** Build the runtime and initialise its overlay shaders. */
    public MeshOverlayRuntime() {
        super();
        this.unlitShader = ShaderProgram.ShaderType.MeshUnlit.getShader();
        this.anchorShader = (MeshLineShader) ShaderProgram.ShaderType.MeshAnchor.getShader();
        this.anchorShader.init();
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
     * Drops every overlay tied to one mesh: a tool's diagnostic, line groups, markers and patch
     * clouds, and the sphere radius cap.
     */
    public void clearMeshState() {
        clearDiagnostic();
        clearLineGroups();
        clearMarkers();
        patchClouds.clear();
        showPatchClouds = false;
        sphereRadiusCap = 0f;
        diagnosticRegionRadius = 0f;
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
     * Show one flat disc per surface vertex, a fixed number of framebuffer pixels across at any
     * zoom and hidden by nearer surface exactly as the overlay lines are. Later anchors win ties.
     *
     * @param vertexId       vertex of {@link #surfaceMesh} each disc sits on
     * @param colors         one colour per disc
     * @param diameterPixels one diameter per disc, in framebuffer pixels
     */
    public void setAnchorDiscs(int[] vertexId, Color[] colors, float[] diameterPixels) {
        MeshTopology surface = surfaceMesh;
        if (vertexId == null || vertexId.length == 0 || surface == null) {
            clearMarkers();
            return;
        }
        int copies = 0;
        for (int anchor : vertexId) {
            copies += Math.max(1, surface.vertexFaceCount(anchor));
        }
        int floats = ANCHOR_DISC_LAYOUT.floatsPerVertex;
        float[] vertices = new float[copies * DISC_CORNER_COUNT * floats];
        Vector3f center = new Vector3f();
        Vector3f normal = new Vector3f();
        int cursor = 0;
        for (int anchor = 0; anchor < vertexId.length; anchor++) {
            surface.vertexPosition(vertexId[anchor], center);
            Vector3f color = colors[anchor].toVector3f();
            int faces = surface.vertexFaceCount(vertexId[anchor]);
            for (int face = 0; face < Math.max(1, faces); face++) {
                if (faces == 0) {
                    normal.zero();
                } else {
                    surface.faceNormal(surface.vertexFaceAt(vertexId[anchor], face), normal);
                }
                for (int corner = 0; corner < DISC_CORNER_COUNT; corner++) {
                    float[] row = { center.x, center.y, center.z, DISC_CORNERS[2 * corner],
                        DISC_CORNERS[2 * corner + 1], normal.x, normal.y, normal.z,
                        color.x, color.y, color.z,
                        diameterPixels[anchor] };
                    System.arraycopy(row, 0, vertices, cursor, floats);
                    cursor += floats;
                }
            }
        }
        anchorDiscs.upload(ANCHOR_DISC_LAYOUT, vertices, null);
    }

    /** Drop the anchor discs. */
    public void clearMarkers() {
        anchorDiscs.delete();
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
        List<float[]> regionClouds = new ArrayList<>(faceGroupCenters);
        regionClouds.addAll(markerPositions);
        int palette = 0;
        for (float[] centers : faceGroupCenters) {
            diagnosticRegions.add(PointSet.cloud(centers, groupColors.get(palette++),
                    HIGHLIGHT_REGION_SCALE, 0f));
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
            regionClouds.add(polyline);
        }
        for (float[] markerGroup : markerPositions) {
            diagnosticMarkers.add(PointSet.cloud(markerGroup, groupColors.get(palette++),
                    HIGHLIGHT_MARKER_SCALE, 0f));
        }
        diagnosticRegionRadius = cloudRadius(regionClouds, new Vector3f());
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
     * Shows one dot cloud per updated patch cover, coloured in palette order; an empty list
     * hides the clouds.
     *
     * @param clouds   one flat-xyz dot cloud per patch, in the order to colour
     * @param dotRadii world-space dot radius per cloud, parallel to {@code clouds}
     */
    public void setPatchClouds(List<float[]> clouds, float[] dotRadii) {
        patchClouds.clear();
        Color[] cycle = Scene.activePalette().overlayGroup;
        for (int index = 0; index < clouds.size(); index++) {
            patchClouds.add(PointSet.cloud(clouds.get(index), Palette.entry(cycle, index),
                    PATCH_CLOUD_SCALE, index < dotRadii.length ? dotRadii[index] : 0f));
        }
        updateSphereRadius();
        showPatchClouds = !patchClouds.isEmpty();
    }

    /**
     * Render the coloured line groups and the anchor discs. Call {@link #render(Camera3D)} first
     * when the translucent base mesh should appear underneath.
     *
     * @param camera active 3D camera
     */
    public void renderOverlays(Camera3D camera) {
        boolean drawLineGroups = showLineGroups && lineGroups.vertexCount > 0
                && lineGroupSegmentStart.length > 1;
        boolean drawMarkers = anchorDiscs.vertexCount > 0;
        if (!drawLineGroups && !drawMarkers) {
            return;
        }
        updateOverlayProjection(camera);
        if (drawLineGroups) {
            // The groups test against the surface but leave its depth alone, so the anchor discs
            // drawn next are not lost to a ring line tying them at the vertex they share.
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
        if (drawMarkers && anchorShader.ID >= 0) {
            // The discs take their faces' depth themselves; the fill offset would only push the
            // flat disc behind the surface it marks.
            GL gl = Platforms.gl();
            gl.disable(gl.POLYGON_OFFSET_FILL());
            anchorShader.use(camera.view, camera.projection, model.identity());
            gl.bindVertexArray(anchorDiscs.vao);
            gl.drawArrays(gl.TRIANGLES(), 0, anchorDiscs.vertexCount);
            gl.enable(gl.POLYGON_OFFSET_FILL());
        }
    }

    /**
     * Draw the patch-cover dot clouds and the diagnostic overlay's groups, after
     * {@link #renderOverlays(Camera3D)} has set the overlay projection this frame.
     *
     * @param camera active 3D camera
     */
    public void renderHighlights(Camera3D camera) {
        if ((!showDiagnostic && !showPatchClouds) || sphere.vao == 0) {
            return;
        }
        updateOverlayProjection(camera);
        if (!beginUnlit(camera)) {
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
            drawLines(camera, lines, 0, lines.vertexCount, diagnosticLineColors.get(index),
                    HIGHLIGHT_LINE_WIDTH);
        }
        beginUnlit(camera);
        for (PointSet markerGroup : diagnosticMarkers) {
            drawSpheres(markerGroup);
        }
    }

    @Override
    public void dispose() {
        super.dispose();
        clearDiagnostic();
        sphere.delete();
        lineGroups.delete();
        anchorDiscs.delete();
    }

    /**
     * Recompute {@link #sphereRadius} from the mesh bounding-box diagonal under
     * {@link #sphereRadiusCap}, uploading the shared marker sphere on first use.
     */
    public void updateSphereRadius() {
        if (sphere.vao == 0) {
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

    /**
     * Set the ceiling every later {@link #updateSphereRadius} keeps the marker radius under.
     *
     * @param cap world-space radius ceiling, zero for none
     */
    public void setSphereRadiusCap(float cap) {
        sphereRadiusCap = cap;
        if (cap > 0) {
            sphereRadius = Math.min(sphereRadius, cap);
        }
    }

    /**
     * Bind the unlit shader with the camera's matrices, ready for {@link #drawSpheres} or a
     * solid-colour triangle draw.
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

    /**
     * Draw one shared-sphere marker per point, each in its colour at {@link #sphereRadius}
     * times the set's scale under the point's cap. Call {@link #beginUnlit} first.
     *
     * @param points markers to draw, or {@code null} for none
     */
    public void drawSpheres(PointSet points) {
        if (points == null || points.count == 0 || sphere.vao == 0) {
            return;
        }
        GL gl = Platforms.gl();
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
