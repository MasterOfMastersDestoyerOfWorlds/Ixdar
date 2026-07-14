package ixdar.scenes;

import org.joml.Vector3f;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.canvas.Canvas3D;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.QuadLayoutEngine;
import ixdar.geometry.mesh.quadlayout.quantization.LayoutExtraction;
import ixdar.geometry.mesh.quadlayout.quantization.LayoutPatchGeometry;
import ixdar.geometry.mesh.quadlayout.quantization.LayoutSeamAudit;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.graphics.cameras.Camera;
import ixdar.graphics.render.model.QuadLayoutRuntime;
import ixdar.platform.Platforms;
import ixdar.platform.gl.Platform;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.Keys;
import ixdar.platform.input.MouseTrap;
import ixdar.platform.input.OrbitCameraKeyGuy;
import ixdar.platform.input.OrbitMouseTrap;

/**
 * Final-output view of the quad-layout pipeline: runs the staged
 * {@link QuadLayoutEngine} to its deepest implemented stage and shows the
 * resulting coarse layout structure (T-mesh patches and arcs; quantized /
 * extracted layout once those stages produce it). Earlier inspector scenes
 * (cross field, parametrization, motorcycle graph) examine the intermediate
 * stages of the same engine.
 */
@SceneAnnotation(id = "quad-layout")
public class QuadLayoutScene extends Scene {

    public static final String DEFAULT_OFF = "test/resources/quadlayout/figure_8/rockerarm_in_tri.off";
    public static final String OFF_PROPERTY = "quadLayoutScene.off";
    public static final String ALPHA_PROPERTY = "quadLayoutScene.alpha";
    public static final String SCENE_TITLE = "Ixdar : Quad Layout";
    public static final float CAMERA_AZIMUTH = (float) Math.toRadians(45.0);
    public static final float CAMERA_ELEVATION = (float) Math.toRadians(24.0);
    public static final float CAMERA_DISTANCE_MIN = 1.5f;
    public static final float CAMERA_DISTANCE_RADIUS_MUL = 2.5f;
    public static final float CAMERA_DISTANCE_DEFAULT = 3.5f;
    public static final float ZOOM_MIN_RADIUS_FRACTION = 0.01f;
    public static final float ZOOM_MAX_RADIUS_MUL = 5.0f;
    public static final float ZOOM_MIN_FLOOR = 0.0001f;
    public static final float DEFAULT_ALPHA_DEGREES = 15f;

    private OrbitMouseTrap orbitMouse;
    private QuadLayoutRuntime runtime;
    private HalfEdgeMesh mesh;
    private float alphaDegrees = DEFAULT_ALPHA_DEGREES;
    private final Vector3f meshCenter = new Vector3f();
    private String hudLine = "";

    /**
     * Default constructor wired by the scene annotation processor.
     */
    public QuadLayoutScene() {
        super();
    }

    @Override
    public void initGL() {
        super.initGL();
        Platforms.gl().setWindowTitle(SCENE_TITLE);

        orbitMouse = new OrbitMouseTrap(camera, this);
        keys = new ToggleKeyGuy(this, orbitMouse, camera, this);
        orbitMouse.setTarget(meshCenter);
        orbitMouse.setOrbit(CAMERA_AZIMUTH, CAMERA_ELEVATION, CAMERA_DISTANCE_DEFAULT);
        mouse = orbitMouse;
        bindAutomationIfAvailable(Platforms.get(), keys, mouse);
        bindInputDirect(Platforms.get(), keys, mouse);

        String offPath = System.getProperty(OFF_PROPERTY, DEFAULT_OFF);
        alphaDegrees = parseAlphaDegrees(System.getProperty(ALPHA_PROPERTY));
        try {
            ArrayMesh arrayMesh = MeshLoader.load(offPath);
            mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(
                    arrayMesh.copyPositions(), arrayMesh.copyFaceIndices());
            runtime = new QuadLayoutRuntime();
            runtime.upload(mesh);
            runtime.frameCamera(camera);
            rebuildLayout();

            meshCenter.set(mesh.center(new Vector3f()));
            float meshRadius = mesh.radius();
            float minZoom = Math.max(ZOOM_MIN_FLOOR, meshRadius * ZOOM_MIN_RADIUS_FRACTION);
            float maxZoom = Math.max(CAMERA_DISTANCE_MIN, meshRadius * ZOOM_MAX_RADIUS_MUL);
            orbitMouse.setDistanceBounds(minZoom, maxZoom);
            float orbitDist = Math.max(CAMERA_DISTANCE_MIN, meshRadius * CAMERA_DISTANCE_RADIUS_MUL);
            orbitMouse.setTarget(meshCenter);
            orbitMouse.setOrbit(CAMERA_AZIMUTH, CAMERA_ELEVATION, orbitDist);

            Platforms.get().log(hudLine);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to initialize quad layout scene from " + offPath, ex);
        }
    }

    /**
     * Run the full pipeline (through quantization and zero-arc collapse) and
     * display the resulting layout: the positive-quantized separatrix skeleton
     * replaces the full trace web in the runtime's trace records, drawn over
     * the patch fill.
     */
    private void rebuildLayout() {
        float alphaRadians = (float) Math.toRadians(alphaDegrees);
        QuadLayoutEngine engine = new QuadLayoutEngine(mesh, alphaRadians);
        engine.buildLayoutEmbedding();
        LayoutPatchGeometry patchGeometry = new LayoutPatchGeometry(engine.conforming).build();
        new LayoutSeamAudit(patchGeometry).build();
        LayoutExtraction layout = engine.layout;
        MotorcycleGraph graph = engine.motorcycleGraph;
        graph.traceRecordsByFace = layout.layoutRecordsByFace;
        runtime.setSeamlessParametrization(engine.seamless);
        runtime.setMotorcycleGraph(graph);
        runtime.setLayoutPatchGeometry(patchGeometry);
        runtime.setLayoutEmbedding(engine.embedding);
        runtime.showTraces = true;
        runtime.showNodes = false;
        runtime.showCrossField = false;
        runtime.showFullIsoGrid = false;
        runtime.showLayoutPatches = true;
        runtime.showLayoutBoundaries = true;
        runtime.showEmbeddedArcs = true;
        hudLine = String.format(
                "[quad-layout] α=%.0f° skeletonArcs=%d layoutNodes=%d #P=%d"
                        + " tJunctions=%d cleanQuads=%d embedArcs=%d/%d",
                alphaDegrees, layout.layoutArcs.size(), layout.singularClusterCount, engine.conforming.finalPatchCount,
                engine.conforming.remainingTJunctionCount, patchGeometry.cleanQuadCount,
                engine.embedding.arcsRouted, engine.embedding.pathByArc.length);
        Platforms.get().log(hudLine);
    }

    void stepAlpha(float deltaDegrees) {
        alphaDegrees = Math.max(1f, alphaDegrees + deltaDegrees);
        rebuildLayout();
    }

    @Override
    public void drawScene() {
        if (runtime == null) {
            return;
        }
        camera.resetView();
        runtime.renderOverlays(camera);
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

    private static float parseAlphaDegrees(String property) {
        if (property == null || property.isBlank()) {
            return DEFAULT_ALPHA_DEGREES;
        }
        try {
            return Float.parseFloat(property.trim());
        } catch (NumberFormatException ex) {
            return DEFAULT_ALPHA_DEGREES;
        }
    }

    private static void bindInputDirect(Platform platform, KeyGuy keys, MouseTrap mouseTrap) {
        platform.setCursorPosCallback((window, x, y) -> mouseTrap.moveOrDrag(window, (float) x, (float) y));
        platform.setMouseButtonCallback((button, action, mods) -> mouseTrap.mouseButton(button, action, mods));
        platform.setScrollCallback((xoff, yoff) -> mouseTrap.scrollCallback(yoff));
        platform.setKeyCallback((key, scancode, action, mods) -> keys.keyCallback(0L, key, scancode, action, mods));
    }

    private final class ToggleKeyGuy extends OrbitCameraKeyGuy {
        private final QuadLayoutScene scene;

        ToggleKeyGuy(QuadLayoutScene scene, OrbitMouseTrap orbitMouse,
                Camera camera, Canvas3D canvas) {
            super(orbitMouse, camera, canvas);
            this.scene = scene;
        }

        @Override
        protected void handleSceneKeys(int key, int mods) {
            if (key == Keys.P) {
            } else if (key == Keys.C) {
                scene.runtime.showLayoutPatches = !scene.runtime.showLayoutPatches;
            } else if (key == Keys.B) {
                scene.runtime.showLayoutBoundaries = !scene.runtime.showLayoutBoundaries;
            } else if (key == Keys.T) {
                scene.runtime.showTraces = !scene.runtime.showTraces;
            } else if (key == Keys.E) {
                scene.runtime.showEmbeddedArcs = !scene.runtime.showEmbeddedArcs;
            } else if (key == Keys.N) {
                scene.runtime.showNodes = !scene.runtime.showNodes;
            } else if (key == Keys.COMMA) {
                scene.stepAlpha(-1f);
            } else if (key == Keys.PERIOD) {
                scene.stepAlpha(1f);
            }
        }
    }
}
