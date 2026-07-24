package ixdar.scenes;

import java.io.IOException;
import java.util.Arrays;
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
import ixdar.geometry.mesh.quadlayout.embedding.ArcRerouteFailure;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedArc;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedContraction;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedMeshTopology;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedNode;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMesh;
import ixdar.geometry.mesh.quadlayout.embedding.EmbeddedTMeshBuilder;
import ixdar.geometry.mesh.quadlayout.embedding.PatchRectangleMap;
import ixdar.geometry.mesh.quadlayout.embedding.PatchRegionMapper;
import ixdar.geometry.mesh.quadlayout.embedding.PatchRegions;
import ixdar.geometry.mesh.quadlayout.embedding.ThreeConnectivityRefinement;
import ixdar.geometry.mesh.quadlayout.embedding.TorusLayoutFixture;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroArcCollapseOperator;
import ixdar.geometry.mesh.quadlayout.embedding.ZeroPatchSplitOperator;
import ixdar.graphics.render.model.QuadLayoutRuntime;
import ixdar.platform.Platforms;
import ixdar.platform.gl.Platform;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.MouseTrap;
import ixdar.platform.input.OrbitMouseTrap;

/**
 * Debug view of an embedded T-mesh: arcs as edge paths, positive orange and zero red, nodes as
 * spheres. SPACE collapses, PERIOD splits, C runs to a fixed point, F runs to the first reroute
 * failure, R rebuilds.
 *
 * <p>Renders {@link TorusLayoutFixture} unless {@code -DembeddedTMesh.off} names a mesh.
 *
 * <p>See also: LCBK19 Figure 9
 */
@SceneAnnotation(id = "embedded-tmesh")
public class EmbeddedTMeshScene extends Scene {

    /** Window title. */
    public static final String SCENE_TITLE = "Ixdar : Embedded T-Mesh";

    /**
     * System property selecting a mesh file to build the T-mesh from the real pipeline; unset
     * renders the hand-authored torus fixture instead.
     */
    public static final String OFF_PROPERTY = "embeddedTMesh.off";

    /** System property for the pipeline's separation angle in degrees; defaults to 15. */
    public static final String ALPHA_PROPERTY = "embeddedTMesh.alpha";

    /**
     * System property for how many zero-arc collapses (LCBK19 operator 1) to apply before
     * rendering: an integer, or {@code all} to collapse every collapsible zero arc. Lets a
     * screenshot show the layout at a chosen point in the collapse.
     */
    public static final String COLLAPSE_PROPERTY = "tmesh.collapse";

    /** System property for how many operator-(2) splits to apply at startup: integer or {@code all}. */
    public static final String SPLIT_PROPERTY = "tmesh.split";

    /**
     * System property that, when set to {@code all}, drives all three operators to a fixed point
     * at startup via {@link EmbeddedContraction}, leaving no zero arcs and no zero patches. In
     * the window, C does the same on demand.
     */
    public static final String CONTRACT_PROPERTY = "tmesh.contract";

    /**
     * System property that, when {@code true}, drives the operators until the first reroute
     * failure and highlights the wall it hit. In the window, H toggles the highlight.
     */
    public static final String CONTRACT_FAIL_PROPERTY = "embeddedTMesh.contractFail";

    /**
     * System property that, when {@code true}, judges a clean fixed point: refine to
     * 3-connectivity, build patch regions, and map every patch to its rectangle, logging whether
     * the regions partition the surface and whether every patch is fold-free.
     */
    public static final String FOLD_CHECK_PROPERTY = "embeddedTMesh.foldCheck";

    /** Request value meaning "apply as many as possible". */
    public static final String ALL = "all";

    /** Log prefix for a count of operator steps applied at startup. */
    public static final String APPLIED_PREFIX = "[embedded-tmesh] applied ";

    /** Default pipeline separation angle, in degrees, when the mesh is built from a file. */
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

    /** Camera distance, as a multiple of mesh radius, when framing a captured reroute failure. */
    public static final float FAILURE_VIEW_DISTANCE_MUL = 0.55f;

    /** Separator introducing the live arc count in a contraction log line. */
    private static final String ARC_COUNT_TAG = "; arcs=";

    /** Boolean-property default meaning the feature is off unless explicitly enabled. */
    private static final String FALSE = "false";

    /** Fold-check field naming arcs whose two neighbour patches coincide. */
    private static final String SAME_SIDE_TAG = " sameSidePatchArcs=";

    /** Fold-check per-node prefix in the torn-layout diagnostics. */
    private static final String NODE_TAG = " n";

    /** How many patches to map between fold-check progress log lines. */
    private static final int PATCH_PROGRESS_INTERVAL = 64;

    /** Bit shift packing a dense edge's low vertex into an undirected-edge key. */
    private static final int FOLD_EDGE_KEY_SHIFT = 32;

    /** Low-word mask recovering a dense edge's high vertex from its key. */
    private static final long FOLD_EDGE_KEY_MASK = 0xFFFFFFFFL;

    private OrbitMouseTrap orbitMouse;
    private QuadLayoutRuntime runtime;
    private String offPath;
    private EmbeddedTMesh tmesh;
    private HalfEdgeMesh surfaceMesh;
    private int eulerCharacteristic;
    private ZeroArcCollapseOperator collapseOperator;
    private ZeroPatchSplitOperator splitOperator;
    private ArcRerouteFailure failure;
    private final Vector3f meshCenter = new Vector3f();

    /** Zero-arc collapses (operator 1) requested by keypress, applied on the render thread. */
    private volatile int pendingCollapseSteps;

    /** Zero-patch splits (operator 2) requested by keypress, applied on the render thread. */
    private volatile int pendingSplitSteps;

    /** Whether a rebuild of the layout was requested by keypress. */
    private volatile boolean pendingReset;

    /** Whether a full contraction (all three operators to a fixed point) was requested by keypress. */
    private volatile boolean pendingContract;

    /** Whether a contract-to-failure run was requested by keypress. */
    private volatile boolean pendingContractToFailure;

    /** Whether the folded-patch magenta view was toggled by keypress. */
    private volatile boolean pendingFoldFlip;

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
        try {
            assembleLayout();
            applyInitialSplits();
            applyInitialCollapses();
            applyInitialContraction();

            runtime = new QuadLayoutRuntime();
            runtime.upload(surfaceMesh);
            runtime.frameCamera(camera);
            applyContractToFailure();
            runtime.setEmbeddedTMesh(tmesh);
            if (failure != null) {
                runtime.setFailureHighlight(tmesh.topology.copy, failure);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize embedded T-mesh scene", ex);
        }

        meshCenter.set(surfaceMesh.center(new Vector3f()));
        float meshRadius = surfaceMesh.radius();
        float minZoom = Math.max(CAMERA_DISTANCE_MIN, meshRadius * ZOOM_MIN_RADIUS_FRACTION);
        float maxZoom = Math.max(CAMERA_DISTANCE_MIN, meshRadius * ZOOM_MAX_RADIUS_MUL);
        orbitMouse.setDistanceBounds(minZoom, maxZoom);
        if (failure != null) {
            aimAtFailure(meshRadius);
        } else {
            float orbitDistance = Math.max(CAMERA_DISTANCE_MIN,
                    meshRadius * CAMERA_DISTANCE_RADIUS_MUL);
            orbitMouse.setTarget(meshCenter);
            orbitMouse.setOrbit(CAMERA_AZIMUTH, CAMERA_ELEVATION, orbitDistance);
        }

        Platforms.get().log(String.format(
                "[embedded-tmesh] source=%s nodes=%d arcs=%d patches=%d euler=%d",
                offPath == null ? "torus-fixture" : offPath, tmesh.nodes.size(),
                tmesh.arcs.size(), tmesh.patches.size(), eulerCharacteristic));
    }

    /**
     * Builds the T-mesh, its surface mesh, and its Euler characteristic: from the real pipeline
     * when {@link #OFF_PROPERTY} names a mesh, otherwise from the hand-authored torus fixture.
     * Also wires the operators for the interactive steps.
     */
    private void assembleLayout() {
        if (offPath == null) {
            TorusLayoutFixture fixture = new TorusLayoutFixture();
            tmesh = fixture.tmesh;
            surfaceMesh = fixture.torus;
            eulerCharacteristic = TorusLayoutFixture.TORUS_EULER_CHARACTERISTIC;
        } else {
            double alphaDegrees = Double.parseDouble(
                    System.getProperty(ALPHA_PROPERTY, Double.toString(DEFAULT_ALPHA_DEGREES)));
            ArrayMesh arrayMesh;
            try {
                arrayMesh = MeshLoader.load(offPath);
            } catch (IOException ex) {
                throw new IllegalStateException("could not read mesh " + offPath, ex);
            }
            surfaceMesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                    arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());
            QuadLayoutEngine engine = new QuadLayoutEngine(
                    surfaceMesh, (float) Math.toRadians(alphaDegrees));
            engine.buildLayoutEmbedding();
            EmbeddedTMeshBuilder builder = new EmbeddedTMeshBuilder(engine.embedding);
            tmesh = builder.build();
            eulerCharacteristic = builder.expectedEulerCharacteristic;
        }
        tmesh.validate(eulerCharacteristic);
        collapseOperator = new ZeroArcCollapseOperator(tmesh);
        splitOperator = new ZeroPatchSplitOperator(tmesh);
    }

    /**
     * Apply the number of zero-arc collapses requested at startup by
     * {@link #COLLAPSE_PROPERTY}: an integer, or {@code all}. This is the headless entry
     * point (a screenshot then shows that point in the collapse); once the window is open,
     * SPACE steps one more collapse and R rebuilds.
     */
    private void applyInitialCollapses() {
        String request = System.getProperty(COLLAPSE_PROPERTY);
        if (request == null || request.isBlank()) {
            return;
        }
        int limit = ALL.equalsIgnoreCase(request.trim())
                ? Integer.MAX_VALUE : Integer.parseInt(request.trim());
        int applied = 0;
        while (applied < limit && collapseOneZeroArc()) {
            applied++;
        }
        Platforms.get().log(APPLIED_PREFIX + applied + " zero-arc collapse(s)");
    }

    /**
     * Drive all three operators to a fixed point at startup when {@link #CONTRACT_PROPERTY} is
     * {@code all}, via {@link EmbeddedContraction}, so a headless screenshot shows the fully
     * re-embedded T-mesh. Runs after any requested splits and collapses.
     */
    private void applyInitialContraction() {
        String request = System.getProperty(CONTRACT_PROPERTY);
        if (request == null || !ALL.equalsIgnoreCase(request.trim())) {
            return;
        }
        EmbeddedContraction contraction =
                new EmbeddedContraction(tmesh, eulerCharacteristic).contract();
        Platforms.get().log(APPLIED_PREFIX + contractionSummary(contraction));
    }

    /**
     * Frames the captured reroute failure: targets the midpoint of the pivot and survivor and
     * looks straight in along the surface normal there, close enough to see the wall.
     *
     * @param meshRadius the surface's radius, for the camera distance
     */
    private void aimAtFailure(float meshRadius) {
        Vector3f pivot = tmesh.topology.copy.vertexPosition(failure.pivotVertex, new Vector3f());
        Vector3f survivor = tmesh.topology.copy.vertexPosition(failure.survivorVertex,
                new Vector3f());
        Vector3f target = new Vector3f(pivot).add(survivor).mul(0.5f);
        Vector3f outward = new Vector3f(target).sub(meshCenter).normalize();
        float elevation = (float) Math.asin(outward.y);
        float azimuth = (float) Math.atan2(outward.z, outward.x);
        float distance = Math.max(CAMERA_DISTANCE_MIN, meshRadius * FAILURE_VIEW_DISTANCE_MUL);
        orbitMouse.setTarget(target);
        orbitMouse.setOrbit(azimuth, elevation, distance);
    }

    /**
     * When {@link #CONTRACT_FAIL_PROPERTY} is set, drive the operators until the first reroute
     * failure and, if one occurs, refresh the partial T-mesh render and highlight the wall.
     *
     * <p>Must run before the layout reaches the runtime: building patch regions from a layout
     * that still holds zero-patches throws.
     */
    private void applyContractToFailure() {
        if (!Boolean.parseBoolean(System.getProperty(CONTRACT_FAIL_PROPERTY, FALSE))) {
            return;
        }
        EmbeddedContraction contraction = new EmbeddedContraction(tmesh, eulerCharacteristic);
        failure = contraction.contractToFailure();
        if (failure == null) {
            Platforms.get().log("[embedded-tmesh] contracted to a fixed point with no failure: "
                    + contractionSummary(contraction));
            if (Boolean.parseBoolean(System.getProperty(FOLD_CHECK_PROPERTY, FALSE))) {
                showFoldFlips();
            }
        } else {
            Platforms.get().log("[embedded-tmesh] stopped at reroute failure after "
                    + contractionSummary(contraction) + " | fenceVertices="
                    + failure.fenceVertices.size() + " pivotSpokes="
                    + (failure.pivotSpokes.size() / 2) + " | " + failure.getMessage());
        }
    }

    /**
     * Judges the contracted layout and shows it: refines to 3-connectivity, builds patch regions,
     * maps every patch, logs the fold count, and paints each folded patch magenta on the
     * iso-surface. Only meaningful once no zero-patches remain.
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
            Platforms.get().log("[foldcheck] pre-refine: TORN: " + tornBeforeRefine.getMessage()
                    + reportArcIntegrity() + reportNodeFans() + reportNodeRotation());
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
                    + SAME_SIDE_TAG + sameSidePatchArcs + "): " + torn.getMessage()
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
            assemblePatchFlipSurface(map, flipped > 0, regions.copyFacesByPatch.get(patch.patchId),
                    activeByFaceId, cornerU, cornerV, faceFlipped);
        }
        runtime.uploadPatchParametrization(copy, cornerU, cornerV, faceFlipped);
        runtime.showTraces = false;
        runtime.showIsoLines = true;
        Platforms.get().log("[foldcheck] flip-surface uploaded isoIdx=" + runtime.isoSurfaceIndexCount
                + " showIsoLines=" + runtime.showIsoLines);
        Platforms.get().log("[foldcheck] regions OK: " + mapped + " patches, chords=" + chords
                + SAME_SIDE_TAG + sameSidePatchArcs + " folded=" + folded
                + (folded == 0 ? " (all fold-free)" : " flippedTriangles=" + flippedTotal
                        + " patches[" + foldedIds.toString().trim() + "]"));
    }

    /**
     * Fills one patch's contribution to the flip-render arrays: each face gets its three corner
     * rectangle coordinates, and when the patch folds its whole region is flagged so it shows as one
     * magenta area, since single folded triangles are too small to see.
     *
     * @param map            the patch's solved rectangle map
     * @param patchHasFold   whether the patch has any folded triangle
     * @param regionFaces    the patch's copy face ids, parallel to {@code map.triangles}
     * @param activeByFaceId copy face id to active face index
     * @param cornerU        per-corner rectangle x to fill, indexed by active face
     * @param cornerV        per-corner rectangle y to fill, parallel to {@code cornerU}
     * @param faceFlipped    per-face fold flag to fill, indexed by active face
     */
    private void assemblePatchFlipSurface(PatchRectangleMap map, boolean patchHasFold,
            List<Integer> regionFaces, Map<Integer, Integer> activeByFaceId, double[] cornerU,
            double[] cornerV, boolean[] faceFlipped) {
        for (int faceIndex = 0; faceIndex < regionFaces.size(); faceIndex++) {
            Integer activeFace = activeByFaceId.get(regionFaces.get(faceIndex));
            if (activeFace == null) {
                continue;
            }
            int[] triangle = map.triangles[faceIndex];
            faceFlipped[activeFace] = patchHasFold;
            int base = activeFace * PatchRegionMapper.TRIANGLE_CORNERS;
            for (int corner = 0; corner < PatchRegionMapper.TRIANGLE_CORNERS; corner++) {
                cornerU[base + corner] = map.rectangleU[triangle[corner]];
                cornerV[base + corner] = map.rectangleV[triangle[corner]];
            }
        }
    }

    /**
     * Tests Tutte's fold-free preconditions on a folded patch's region: a repeated boundary-loop
     * vertex is a non-simple boundary pinned to two rectangle spots, a vertex with more than two
     * boundary edges is a non-manifold pinch, and an Euler characteristic other than one means the
     * region is not a disk.
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
                + " V=" + vertexCount + " E=" + edgeUse.size() + " F=" + map.triangles.length
                + diagnoseFoldAreas(map);
    }

    /**
     * Splits a folded patch's flipped triangles into degenerate slivers (rectangle area exactly
     * zero) and true inversions (opposite winding), and sizes the worst inversion against the median
     * triangle area — so a tiny ratio means numerical slivers, a ratio near one a real overlap.
     *
     * @param map the folded patch's solved rectangle map
     * @return a compact report of the flip area distribution
     */
    private static String diagnoseFoldAreas(PatchRectangleMap map) {
        double referenceArea = 0.0;
        for (int[] triangle : map.triangles) {
            double area = triangleRectangleArea(map, triangle);
            if (Math.abs(area) > Math.abs(referenceArea)) {
                referenceArea = area;
            }
        }
        boolean referencePositive = referenceArea > 0.0;
        double[] absAreas = new double[map.triangles.length];
        int zeroSlivers = 0;
        double worstInversion = 0.0;
        for (int index = 0; index < map.triangles.length; index++) {
            double area = triangleRectangleArea(map, map.triangles[index]);
            absAreas[index] = Math.abs(area);
            if (area == 0.0) {
                zeroSlivers++;
            } else if ((area > 0.0) != referencePositive) {
                worstInversion = Math.max(worstInversion, Math.abs(area));
            }
        }
        Arrays.sort(absAreas);
        double medianArea = absAreas[absAreas.length / 2];
        double inversionRatio = medianArea > 0.0 ? worstInversion / medianArea : 0.0;
        return " zeroSlivers=" + zeroSlivers
                + String.format(" worstInversion/median=%.2e medianArea=%.2e", inversionRatio,
                        medianArea);
    }

    /**
     * Twice the signed area of a map triangle in its rectangle; the sign gives the winding.
     *
     * @param map      the patch's rectangle map
     * @param triangle three dense vertex indices in winding order
     * @return the signed area measure
     */
    private static double triangleRectangleArea(PatchRectangleMap map, int[] triangle) {
        double ux = map.rectangleU[triangle[0]];
        double uy = map.rectangleV[triangle[0]];
        double vx = map.rectangleU[triangle[1]];
        double vy = map.rectangleV[triangle[1]];
        double wx = map.rectangleU[triangle[2]];
        double wy = map.rectangleV[triangle[2]];
        return (vx - ux) * (wy - uy) - (wx - ux) * (vy - uy);
    }

    /**
     * Increments the shared use-count of the undirected dense edge between two vertices.
     *
     * @param edgeUse map from packed undirected edge key to the number of triangles using it
     * @param first   one dense vertex of the edge
     * @param second  the other dense vertex
     */
    private static void countEdge(Map<Long, Integer> edgeUse, int first, int second) {
        int low = Math.min(first, second);
        int high = Math.max(first, second);
        edgeUse.merge(((long) low << FOLD_EDGE_KEY_SHIFT) | high, 1, Integer::sum);
    }

    /**
     * Each live arc whose claimed edge-chain is broken — a path step whose edge is unclaimed or
     * owned by another arc — so a sealing gap that leaves an arc not fencing its patches shows up.
     *
     * @return a compact report, or a note that every arc's chain is fully self-claimed
     */
    private String reportArcIntegrity() {
        StringBuilder broken = new StringBuilder();
        for (EmbeddedArc arc : tmesh.arcs) {
            if (!arc.alive) {
                continue;
            }
            for (int step = 1; step < arc.path.copyVertexPath.size(); step++) {
                int edgeId = tmesh.topology.edgeBetween(arc.path.copyVertexPath.get(step - 1),
                        arc.path.copyVertexPath.get(step));
                if (edgeId == EmbeddedMeshTopology.UNCLAIMED
                        || tmesh.topology.ownerArcByCopyEdge[edgeId] != arc.arcId) {
                    broken.append(" a").append(arc.arcId).append('@').append(step);
                }
            }
        }
        return broken.length() == 0 ? " | arcs:all-self-claimed" : " | brokenArcEdges:" + broken;
    }

    /**
     * Each live node's degree and incident arc ids, so a boundary cycle that fails to close at a
     * node — the shape of a sealing gap when no single arc's chain is broken — is visible.
     *
     * @return a compact per-node report
     */
    private String reportNodeFans() {
        StringBuilder fans = new StringBuilder();
        for (EmbeddedNode node : tmesh.nodes) {
            if (node.alive) {
                fans.append(NODE_TAG).append(node.nodeId).append('d').append(tmesh.degree(node.nodeId))
                        .append(tmesh.arcEndsByNode.get(node.nodeId));
            }
        }
        return " | nodeFans:" + fans;
    }

    /**
     * The cyclic order of incident arcs around each live node's copy vertex, read by rotating its
     * half-edge fan. A scramble versus the node's patch cycles is the fingerprint of a reroute that
     * left a node in the wrong angular sector, merging patch corners with no arc crossing.
     *
     * @return a compact per-node cyclic arc order
     */
    private String reportNodeRotation() {
        StringBuilder rotation = new StringBuilder();
        HalfEdgeMesh copy = tmesh.topology.copy;
        for (EmbeddedNode node : tmesh.nodes) {
            if (!node.alive) {
                continue;
            }
            rotation.append(NODE_TAG).append(node.nodeId).append(':');
            int startHalfEdge = copy.vertexOutgoingHalfEdge(node.copyVertex);
            int halfEdge = startHalfEdge;
            int lastArc = EmbeddedTMesh.NONE;
            for (int step = 0; step < copy.vertexEdgeCount(node.copyVertex) + 2; step++) {
                int owner = tmesh.topology.ownerArcByCopyEdge[copy.halfEdgeEdge(halfEdge)];
                if (owner != EmbeddedMeshTopology.UNCLAIMED && owner != lastArc) {
                    rotation.append(owner).append(',');
                    lastArc = owner;
                }
                halfEdge = copy.halfEdgeTwin(copy.halfEdgePrev(halfEdge));
                if (halfEdge == startHalfEdge) {
                    break;
                }
            }
        }
        return " | nodeRotation:" + rotation;
    }

    /**
     * A one-line summary of how many of each operator a contraction applied.
     *
     * @param contraction a finished contraction
     * @return "{@code N collapse(s), M split(s), K patch-collapse(s)}"
     */
    private static String contractionSummary(EmbeddedContraction contraction) {
        return contraction.arcCollapseCount + " collapse(s), "
                + contraction.patchSplitCount + " split(s), "
                + contraction.patchCollapseCount + " patch-collapse(s)";
    }

    /**
     * Apply the number of non-simple zero-patch splits requested at startup by
     * {@code -Dtmesh.split} (an integer, or {@code all}), before any collapses, so a headless
     * screenshot can show operator (2)'s effect. In the window, PERIOD steps one split.
     */
    private void applyInitialSplits() {
        String request = System.getProperty(SPLIT_PROPERTY);
        if (request == null || request.isBlank()) {
            return;
        }
        int limit = ALL.equalsIgnoreCase(request.trim())
                ? Integer.MAX_VALUE : Integer.parseInt(request.trim());
        int applied = 0;
        while (applied < limit) {
            int patchId = splitOperator.nextNonSimpleZeroPatch();
            if (patchId == EmbeddedTMesh.NONE) {
                break;
            }
            splitOperator.split(patchId);
            tmesh.validate(eulerCharacteristic);
            applied++;
        }
        Platforms.get().log(APPLIED_PREFIX + applied + " zero-patch split(s)");
    }

    /**
     * Collapse the next collapsible zero arc, validating the result, and report whether one
     * was found.
     *
     * @return true when an arc was collapsed, false when none remains
     */
    private boolean collapseOneZeroArc() {
        int arcId = collapseOperator.nextCollapsibleArc();
        if (arcId == EmbeddedTMesh.NONE) {
            return false;
        }
        collapseOperator.collapse(arcId);
        tmesh.validate(eulerCharacteristic);
        return true;
    }

    /**
     * Ask for one more zero-arc collapse; applied on the next frame, on the render thread.
     */
    public void requestCollapseStep() {
        pendingCollapseSteps++;
    }

    /**
     * Ask for one more non-simple zero-patch split; applied on the next frame, on the render
     * thread.
     */
    public void requestSplitStep() {
        pendingSplitSteps++;
    }

    /**
     * Ask to rebuild the layout; applied on the next frame, on the render thread.
     */
    public void requestReset() {
        pendingReset = true;
    }

    /**
     * Ask to drive all three operators to a fixed point; applied on the next frame, on the
     * render thread.
     */
    public void requestFullContraction() {
        pendingContract = true;
    }

    /**
     * Ask to drive all three operators to a fixed point without the termination-measure check,
     * stopping at the first reroute failure instead of throwing; applied on the next frame.
     *
     * <p>This is the {@code embeddedTMesh.contractFail} startup path, on a key.
     */
    public void requestContractToFailure() {
        pendingContractToFailure = true;
    }

    /**
     * Ask to toggle the folded-patch magenta view on the current layout; applied on the next frame,
     * on the render thread. Contract to a fixed point (C or F) first, or it reports it cannot map.
     */
    public void requestFoldFlipView() {
        pendingFoldFlip = true;
    }

    /**
     * Apply any keypress-requested edit on the render thread, where the GL context is current,
     * and re-upload the changed T-mesh. Doing this here rather than in the key callback keeps
     * every GL call on the thread that owns the context.
     */
    private void applyPendingEdits() {
        if (pendingReset) {
            pendingReset = false;
            pendingCollapseSteps = 0;
            pendingSplitSteps = 0;
            assembleLayout();
            runtime.setEmbeddedTMesh(tmesh);
            Platforms.get().log("[embedded-tmesh] rebuilt");
            return;
        }
        if (pendingContractToFailure) {
            pendingContractToFailure = false;
            EmbeddedContraction contraction = new EmbeddedContraction(tmesh, eulerCharacteristic);
            failure = contraction.contractToFailure();
            runtime.setEmbeddedTMesh(tmesh);
            Platforms.get().log("[embedded-tmesh] contract-to-failure: "
                    + contractionSummary(contraction) + ARC_COUNT_TAG + countLiveArcs()
                    + (failure == null ? "; no failure" : "; " + failure.getMessage()));
            return;
        }
        if (pendingContract) {
            pendingContract = false;
            EmbeddedContraction contraction =
                    new EmbeddedContraction(tmesh, eulerCharacteristic).contract();
            runtime.setEmbeddedTMesh(tmesh);
            Platforms.get().log("[embedded-tmesh] contracted to fixed point: "
                    + contractionSummary(contraction) + ARC_COUNT_TAG + countLiveArcs());
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
        boolean changed = false;
        while (pendingSplitSteps > 0) {
            pendingSplitSteps--;
            int patchId = splitOperator.nextNonSimpleZeroPatch();
            if (patchId == EmbeddedTMesh.NONE) {
                Platforms.get().log("[embedded-tmesh] no non-simple zero-patch remains");
                break;
            }
            splitOperator.split(patchId);
            tmesh.validate(eulerCharacteristic);
            changed = true;
        }
        while (pendingCollapseSteps > 0) {
            pendingCollapseSteps--;
            if (collapseOneZeroArc()) {
                changed = true;
            } else {
                Platforms.get().log("[embedded-tmesh] no collapsible zero arc remains");
                break;
            }
        }
        if (changed) {
            runtime.setEmbeddedTMesh(tmesh);
            Platforms.get().log("[embedded-tmesh] collapsed " + collapseOperator.collapsedCount
                    + " total; arcs=" + countLiveArcs());
        }
    }

    /**
     * The number of live arcs in the T-mesh, for the status log.
     *
     * @return count of arcs still part of the layout
     */
    private int countLiveArcs() {
        int count = 0;
        for (int arcId = 0; arcId < tmesh.arcs.size(); arcId++) {
            if (tmesh.arcs.get(arcId).alive) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void drawScene() {
        if (runtime == null) {
            return;
        }
        applyPendingEdits();
        camera.resetView();
        if (!runtime.showIsoLines) {
            runtime.render(camera);
        }
        runtime.renderOverlays(camera);
        runtime.renderHighlights(camera);
    }

    /**
     * Toggle the reroute-failure highlight, applied on the render thread.
     */
    public void toggleFailureHighlight() {
        if (runtime != null) {
            runtime.showFailureHighlight = !runtime.showFailureHighlight;
        }
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
