package ixdar.scenes;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.joml.Vector3f;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.PatchRectangleMap;
import ixdar.geometry.mesh.quadlayout.embedding.PatchRegionMapper;
import ixdar.geometry.mesh.quadlayout.embedding.PatchRegions;
import ixdar.geometry.mesh.quadlayout.embedding.ThreeConnectivityRefinement;
import ixdar.graphics.render.model.QuadLayoutRuntime;
import ixdar.platform.Platforms;
import ixdar.platform.gl.Platform;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.MouseTrap;
import ixdar.platform.input.OrbitMouseTrap;

/**
 * Debug view of an embedded T-mesh: arcs as edge paths, positive orange and
 * zero red, nodes as spheres. Keys are bound by {@link EmbeddedTMeshSceneKeys}.
 *
 * <p>
 * Builds from {@code -DembeddedTMesh.off}, defaulting to
 * {@link #DEFAULT_TEST_MODEL}.
 *
 * <p>
 * See also: LCBK19 Figure 9
 */
@SceneAnnotation(id = "embedded-tmesh")
public class EmbeddedTMeshScene extends Scene {

    /** Window title. */
    public static final String SCENE_TITLE = "Ixdar : Embedded T-Mesh";

    /**
     * System property selecting the mesh file the T-mesh is built from; unset falls
     * back to {@link #DEFAULT_TEST_MODEL}.
     */
    public static final String OFF_PROPERTY = "embeddedTMesh.off";

    /**
     * System property for the pipeline's separation angle in degrees; defaults to
     * 15.
     */
    public static final String ALPHA_PROPERTY = "embeddedTMesh.alpha";

    /** Request value meaning "apply as many as possible". */
    public static final String ALL = "all";

    /** Log prefix for a count of operator steps applied at startup. */
    public static final String APPLIED_PREFIX = "[embedded-tmesh] applied ";

    /**
     * Default pipeline separation angle, in degrees, when the mesh is built from a
     * file.
     */
    public static final double DEFAULT_ALPHA_DEGREES = 15.0;

    /** Orbit azimuth the camera starts at, looking down onto the mesh. */
    public static final float CAMERA_AZIMUTH = (float) Math.toRadians(35.0);

    /** Orbit elevation the camera starts at. */
    public static final float CAMERA_ELEVATION = (float) Math.toRadians(35.0);

    /** Nearest the camera may zoom, as a floor independent of mesh size. */
    public static final float CAMERA_DISTANCE_MIN = 0.5f;

    /** Camera distance as a multiple of the mesh radius. */
    public static final float CAMERA_DISTANCE_RADIUS_MUL = 2.5f;

    /** Farthest the camera may zoom, as a multiple of the mesh radius. */
    public static final float ZOOM_MAX_RADIUS_MUL = 5.0f;

    /** Nearest zoom as a fraction of the mesh radius. */
    public static final float ZOOM_MIN_RADIUS_FRACTION = 0.02f;

    /**
     * Camera distance, as a multiple of mesh radius, when framing a captured
     * reroute failure.
     */
    public static final float FAILURE_VIEW_DISTANCE_MUL = 0.55f;

    /** How many patches to map between fold-check progress log lines. */
    private static final int PATCH_PROGRESS_INTERVAL = 64;

    /** Bit shift packing a dense edge's low vertex into an undirected-edge key. */
    private static final int FOLD_EDGE_KEY_SHIFT = 32;

    /** Low-word mask recovering a dense edge's high vertex from its key. */
    private static final long FOLD_EDGE_KEY_MASK = 0xFFFFFFFFL;

    private static final String DEFAULT_TEST_MODEL = "test/resources/quadlayout/figure_8/fertility_in_tri.off";

    /**
     * Whether a full contraction (all three operators to a fixed point) was
     * requested by keypress.
     */
    public volatile boolean pendingContract;

    /** Whether the folded-patch magenta view was toggled by keypress. */
    public volatile boolean pendingFoldFlip;

    /** The angle to stop motorcycle crashes at. */
    public double alphaDegrees;

    private OrbitMouseTrap orbitMouse;
    private QuadLayoutRuntime runtime;
    private String offPath;
    private EmbeddedTMesh tmesh;
    private HalfEdgeMesh surfaceMesh;
    private final Vector3f meshCenter = new Vector3f();

    /**
     * Default constructor wired by the scene annotation processor.
     */
    public EmbeddedTMeshScene() {
        super();
    }

    @Override
    public void initGL() {
        super.initGL();
        Platforms.gl().setWindowTitle(SCENE_TITLE);

        orbitMouse = new OrbitMouseTrap(camera, this);
        keys = new EmbeddedTMeshSceneKeys(this, orbitMouse, camera, this);
        mouse = orbitMouse;
        bindAutomationIfAvailable(Platforms.get(), keys, mouse);
        bindInputDirect(Platforms.get(), keys, mouse);

        offPath = System.getProperty(OFF_PROPERTY);
        if (offPath == null) {
            offPath = DEFAULT_TEST_MODEL;
        }
        alphaDegrees = Double.parseDouble(
                System.getProperty(ALPHA_PROPERTY, Double.toString(DEFAULT_ALPHA_DEGREES)));

        try {
            assembleLayout();

            runtime = new QuadLayoutRuntime();
            runtime.upload(surfaceMesh);
            runtime.frameCamera(camera);
            runtime.setEmbeddedTMesh(tmesh);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize embedded T-mesh scene", ex);
        }

        meshCenter.set(surfaceMesh.center(new Vector3f()));
        float meshRadius = surfaceMesh.radius();
        float minZoom = Math.max(CAMERA_DISTANCE_MIN, meshRadius * ZOOM_MIN_RADIUS_FRACTION);
        float maxZoom = Math.max(CAMERA_DISTANCE_MIN, meshRadius * ZOOM_MAX_RADIUS_MUL);
        orbitMouse.setDistanceBounds(minZoom, maxZoom);
        float orbitDistance = Math.max(CAMERA_DISTANCE_MIN,
                meshRadius * CAMERA_DISTANCE_RADIUS_MUL);
        orbitMouse.setTarget(meshCenter);
        orbitMouse.setOrbit(CAMERA_AZIMUTH, CAMERA_ELEVATION, orbitDistance);
        Platforms.get().log(String.format(
                "[embedded-tmesh] source=%s nodes=%d arcs=%d patches=%d",
                offPath, tmesh.nodes.size(),
                tmesh.arcs.size(), tmesh.patches.size()));
    }

    /**
     * Builds the T-mesh, its surface mesh, and its Euler characteristic: from the
     * real pipeline when {@link #OFF_PROPERTY} names a mesh, otherwise from the
     * hand-authored torus fixture. Also wires the operators for the interactive
     * steps.
     * 
     * @throws IOException thows if we couldnt load the mesh.
     */
    private void assembleLayout() throws IOException {
        ArrayMesh arrayMesh = MeshLoader.load(offPath);
        surfaceMesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());
        QuadLayoutEngine engine = new QuadLayoutEngine(
                surfaceMesh, (float) Math.toRadians(alphaDegrees));
        engine.buildLayoutEmbedding();
        tmesh = new EmbeddedTMesh(engine.embedding.topology).build(engine.embedding);
        tmesh.validate();
        tmesh.contract();
    }

    /**
     * Judges the contracted layout and shows it: refines to 3-connectivity, builds
     * patch regions, maps every patch, logs the fold count, and paints each folded
     * patch magenta on the iso-surface. Only meaningful once no zero-patches
     * remain.
     */
    private void showFoldFlips() {
        int sameSidePatchArcs = 0;
        for (EmbeddedArc arc : tmesh.arcs) {
            if (arc.alive && arc.leftPatchId == arc.rightPatchId) {
                sameSidePatchArcs++;
            }
        }
        try {
            new PatchRegions(tmesh).build();
            Platforms.get().log("[foldcheck] pre-refine: regions OK");
        } catch (IllegalStateException tornBeforeRefine) {
            Platforms.get().log("[foldcheck] pre-refine: TORN: " + tornBeforeRefine.getMessage());
        }
        Platforms.get().log("[foldcheck] refining to 3-connectivity (a large mesh takes a minute)...");
        int chords = new ThreeConnectivityRefinement(tmesh).refine();
        Platforms.get().log("[foldcheck] refined chords=" + chords + " faces="
                + tmesh.topology.copy.faceCount() + "; mapping patches...");
        PatchRegions regions;
        try {
            regions = new PatchRegions(tmesh).build();
        } catch (IllegalStateException torn) {
            StringBuilder patchArcs = new StringBuilder();
            for (EmbeddedPatch patch : tmesh.patches) {
                if (!patch.alive) {
                    continue;
                }
                Set<Integer> boundary = new TreeSet<>();
                for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
                    boundary.addAll(patch.sideArcIds.get(side));
                }
                patchArcs.append(" P").append(patch.patchId).append(boundary);
            }
            Platforms.get().log("[foldcheck] TORN after refine (chords=" + chords
                    + " sameSidePatchArcs=" + sameSidePatchArcs + "): " + torn.getMessage()
                    + " | live patches:" + patchArcs);
            return;
        }
        PatchRegionMapper mapper = new PatchRegionMapper(tmesh, regions);
        HalfEdgeMesh copy = tmesh.topology.copy;
        int faceCount = copy.faceCount();
        Map<Integer, Integer> activeByFaceId = new HashMap<>();
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            activeByFaceId.put(copy.faceIdAt(activeFace), activeFace);
        }
        double[] cornerU = new double[faceCount * PatchRegionMapper.TRIANGLE_CORNERS];
        double[] cornerV = new double[faceCount * PatchRegionMapper.TRIANGLE_CORNERS];
        boolean[] faceFlipped = new boolean[faceCount];
        int mapped = 0;
        int folded = 0;
        int flippedTotal = 0;
        StringBuilder foldedIds = new StringBuilder();
        for (EmbeddedPatch patch : tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            mapped++;
            if (mapped % PATCH_PROGRESS_INTERVAL == 0) {
                Platforms.get().log("[foldcheck] mapped " + mapped + " patches...");
            }
            PatchRectangleMap map = mapper.mapPatch(patch.patchId);
            int flipped = map.flippedTriangleCount();
            if (flipped > 0) {
                folded++;
                flippedTotal += flipped;
                foldedIds.append(' ').append(patch.patchId).append('(').append(flipped).append(')');
                Platforms.get().log("[foldcheck] fold P" + patch.patchId + " flips=" + flipped
                        + diagnosePatchFold(map));
            }
            List<Integer> regionFaces = regions.copyFacesByPatch.get(patch.patchId);
            for (int faceIndex = 0; faceIndex < regionFaces.size(); faceIndex++) {
                Integer activeFace = activeByFaceId.get(regionFaces.get(faceIndex));
                if (activeFace == null) {
                    continue;
                }
                int[] triangle = map.triangles[faceIndex];
                faceFlipped[activeFace] = flipped > 0;
                int base = activeFace * PatchRegionMapper.TRIANGLE_CORNERS;
                for (int corner = 0; corner < PatchRegionMapper.TRIANGLE_CORNERS; corner++) {
                    cornerU[base + corner] = map.rectangleU[triangle[corner]];
                    cornerV[base + corner] = map.rectangleV[triangle[corner]];
                }
            }
        }
        runtime.uploadPatchParametrization(copy, cornerU, cornerV, faceFlipped);
        runtime.showTraces = false;
        runtime.showIsoLines = true;
        Platforms.get().log("[foldcheck] flip-surface uploaded isoIdx=" + runtime.isoSurfaceIndexCount
                + " showIsoLines=" + runtime.showIsoLines);
        Platforms.get().log("[foldcheck] regions OK: " + mapped + " patches, chords=" + chords
                + " sameSidePatchArcs=" + sameSidePatchArcs + " folded=" + folded
                + (folded == 0 ? " (all fold-free)"
                        : " flippedTriangles=" + flippedTotal
                                + " patches[" + foldedIds.toString().trim() + "]"));
    }

    /**
     * Tests Tutte's fold-free preconditions on a folded patch's region: a repeated
     * boundary-loop vertex is a non-simple boundary pinned to two rectangle spots,
     * a vertex with more than two boundary edges is a non-manifold pinch, and an
     * Euler characteristic other than one means the region is not a disk.
     *
     * @param map the folded patch's solved rectangle map
     * @return a compact report of which preconditions the region violates
     */
    private static String diagnosePatchFold(PatchRectangleMap map) {
        int vertexCount = map.positions.length;
        int[] boundaryLoopCount = new int[vertexCount];
        for (int dense : map.boundaryLoop) {
            boundaryLoopCount[dense]++;
        }
        int repeatedBoundary = 0;
        for (int count : boundaryLoopCount) {
            if (count > 1) {
                repeatedBoundary++;
            }
        }
        Map<Long, Integer> edgeUse = new HashMap<>();
        for (int[] triangle : map.triangles) {
            countEdge(edgeUse, triangle[0], triangle[1]);
            countEdge(edgeUse, triangle[1], triangle[2]);
            countEdge(edgeUse, triangle[2], triangle[0]);
        }
        int[] boundaryEdgesAt = new int[vertexCount];
        int nonManifoldEdges = 0;
        for (Map.Entry<Long, Integer> entry : edgeUse.entrySet()) {
            if (entry.getValue() == 1) {
                long key = entry.getKey();
                boundaryEdgesAt[(int) (key >>> FOLD_EDGE_KEY_SHIFT)]++;
                boundaryEdgesAt[(int) (key & FOLD_EDGE_KEY_MASK)]++;
            } else if (entry.getValue() > 2) {
                nonManifoldEdges++;
            }
        }
        int pinchVertices = 0;
        for (int count : boundaryEdgesAt) {
            if (count > 2) {
                pinchVertices++;
            }
        }
        int euler = vertexCount - edgeUse.size() + map.triangles.length;
        return " repeatedBoundaryVerts=" + repeatedBoundary + " pinchVerts=" + pinchVertices
                + " nonManifoldEdges=" + nonManifoldEdges + " euler=" + euler + " (disk=1)"
                + " V=" + vertexCount + " E=" + edgeUse.size() + " F=" + map.triangles.length;
    }

    /**
     * Increments the shared use-count of the undirected dense edge between two
     * vertices.
     *
     * @param edgeUse map from packed undirected edge key to the number of triangles
     *                using it
     * @param first   one dense vertex of the edge
     * @param second  the other dense vertex
     */
    private static void countEdge(Map<Long, Integer> edgeUse, int first, int second) {
        int low = Math.min(first, second);
        int high = Math.max(first, second);
        edgeUse.merge(((long) low << FOLD_EDGE_KEY_SHIFT) | high, 1, Integer::sum);
    }

    /**
     * Apply any keypress-requested edit on the render thread, where the GL context
     * is current, and re-upload the changed T-mesh. Doing this here rather than in
     * the key callback keeps every GL call on the thread that owns the context.
     *
     * @throws Exception when a requested edit or its re-upload fails
     */
    private void applyPendingEdits() throws Exception {

        if (pendingContract) {
            pendingContract = false;
            tmesh.contract();
            runtime.setEmbeddedTMesh(tmesh);
            Platforms.get().log("[embedded-tmesh] contracted to fixed point: "
                    + tmesh.arcCollapseCount + " collapse(s), "
                    + tmesh.patchSplitCount + " split(s), "
                    + tmesh.patchCollapseCount + " patch-collapse(s)");
            return;
        }
        if (pendingFoldFlip) {
            pendingFoldFlip = false;
            if (runtime.showIsoLines) {
                runtime.showIsoLines = false;
                Platforms.get().log("[foldcheck] flip view off");
            } else {
                try {
                    showFoldFlips();
                } catch (IllegalStateException notReady) {
                    Platforms.get().log("[foldcheck] cannot show flips (contract to a fixed point"
                            + " first with C or F): " + notReady.getMessage());
                }
            }
            return;
        }
    }

    @Override
    public void drawScene() {
        if (runtime == null) {
            return;
        }
        try {
            applyPendingEdits();
        } catch (Exception e) {
            e.printStackTrace();
        }
        camera.resetView();
        if (!runtime.showIsoLines) {
            runtime.render(camera);
        }
        runtime.renderOverlays(camera);
        runtime.renderHighlights(camera);
    }

    @Override
    public void activate(boolean state) {
        super.activate(state);
        if (!state) {
            disposeRuntime();
        }
    }

    @Override
    public void shutdown() {
        disposeRuntime();
        super.shutdown();
    }

    private void disposeRuntime() {
        if (runtime != null) {
            runtime.dispose();
            runtime = null;
        }
    }

    /**
     * Route raw platform input to the scene's key and mouse handlers.
     *
     * @param platform  active platform
     * @param keyGuy    key handler
     * @param mouseTrap mouse handler
     */
    private static void bindInputDirect(Platform platform, KeyGuy keyGuy, MouseTrap mouseTrap) {
        platform.setCursorPosCallback(
                (window, x, y) -> mouseTrap.moveOrDrag(window, (float) x, (float) y));
        platform.setMouseButtonCallback(
                (button, action, mods) -> mouseTrap.mouseButton(button, action, mods));
        platform.setScrollCallback((xoff, yoff) -> mouseTrap.scrollCallback(yoff));
        platform.setKeyCallback(
                (key, scancode, action, mods) -> keyGuy.keyCallback(0L, key, scancode, action, mods));
    }
}
