package ixdar.scenes;

import static ixdar.platform.input.Keys.ACTION_PRESS;

import org.joml.Vector3f;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.canvas.Canvas3D;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.motorcycle.MotorcycleGraph;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;
import ixdar.graphics.cameras.Camera;
import ixdar.graphics.render.model.QuadLayoutRuntime;
import ixdar.platform.Platforms;
import ixdar.platform.gl.Platform;
import ixdar.platform.input.KeyGuy;
import ixdar.platform.input.Keys;
import ixdar.platform.input.MouseTrap;
import ixdar.platform.input.OrbitMouseTrap;

/**
 * Inspector for Lyon 2021 modified motorcycle graphs: trace iso-lines, T-mesh
 * nodes, patch fills, and live α stepping.
 */
@SceneAnnotation(id = "mcg-exam")
public class MotorcycleGraphExaminationScene extends Scene {

    public static final String DEFAULT_OFF = "test/resources/quadlayout/figure_8/rockerarm_in_tri.off";
    public static final String OFF_PROPERTY = "mcgScene.off";
    public static final String ALPHA_PROPERTY = "mcgScene.alpha";
    public static final String SCENE_TITLE = "Ixdar : Motorcycle Graph Examination";
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
    private CrossField crossField;
    private SeamlessParameterization seamless;
    private float alphaDegrees = DEFAULT_ALPHA_DEGREES;
    private final Vector3f meshCenter = new Vector3f();
    private String hudLine = "";

    /**
     * Default constructor wired by the scene annotation processor.
     */
    public MotorcycleGraphExaminationScene() {
        super();
    }

    @Override
    public void initGL() {
        super.initGL();
        Platforms.gl().setWindowTitle(SCENE_TITLE);

        keys = new ToggleKeyGuy(this, camera, this);
        orbitMouse = new OrbitMouseTrap(camera, this);
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
            rebuildMotorcycleGraph();

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
                    "Failed to initialize motorcycle graph scene from " + offPath, ex);
        }
    }

    private void rebuildMotorcycleGraph() {
        crossField = new CrossField(mesh).build();
        seamless = new SeamlessParameterization(crossField);
        seamless.build();
        float alphaRadians = (float) Math.toRadians(alphaDegrees);
        MotorcycleGraph graph = new MotorcycleGraph(seamless, alphaRadians).build();
        runtime.setSeamlessParametrization(seamless);
        runtime.setMotorcycleGraph(graph);
        runtime.showTraces = true;
        runtime.showNodes = true;
        runtime.showPatches = false;
        runtime.showCrossField = false;
        runtime.showFullIsoGrid = false;
        hudLine = String.format("[mcg-exam] α=%.0f° traces=%d arcs=%d nodes=%d alive=%d/%d",
                alphaDegrees, graph.traces.size(), graph.arcs.size(), graph.nodes.size(),
                graph.aliveTraceCount(), graph.traces.size());
        Platforms.get().log(hudLine);
    }

    void stepAlpha(float deltaDegrees) {
        alphaDegrees = Math.max(1f, alphaDegrees + deltaDegrees);
        rebuildMotorcycleGraph();
    }

    void togglePatches() {
        runtime.showPatches = !runtime.showPatches;
    }

    void toggleTraces() {
        runtime.showTraces = !runtime.showTraces;
    }

    void toggleNodes() {
        runtime.showNodes = !runtime.showNodes;
    }

    void toggleWitnesses() {
        runtime.showWitnesses = !runtime.showWitnesses;
    }

    void toggleEppsteinMarkers() {
        runtime.showEppsteinMarkers = !runtime.showEppsteinMarkers;
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

    private final class ToggleKeyGuy extends KeyGuy {
        private final MotorcycleGraphExaminationScene scene;

        ToggleKeyGuy(MotorcycleGraphExaminationScene scene, Camera camera, Canvas3D canvas) {
            super(camera, canvas);
            this.scene = scene;
        }

        @Override
        public void keyCallback(long window, int key, int scancode, int action, int mods) {
            if (active && action == ACTION_PRESS) {
                if (key == Keys.P) {
                    scene.togglePatches();
                } else if (key == Keys.T) {
                    scene.toggleTraces();
                } else if (key == Keys.N) {
                    scene.toggleNodes();
                } else if (key == Keys.W) {
                    scene.toggleWitnesses();
                } else if (key == Keys.E) {
                    scene.toggleEppsteinMarkers();
                } else if (key == Keys.COMMA) {
                    scene.stepAlpha(-1f);
                } else if (key == Keys.PERIOD) {
                    scene.stepAlpha(1f);
                }
            }
            super.keyCallback(window, key, scancode, action, mods);
        }
    }
}
