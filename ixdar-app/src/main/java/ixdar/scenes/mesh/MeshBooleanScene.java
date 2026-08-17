package ixdar.scenes.mesh;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.csg.MeshBooleanResult;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.nodes.geometry.MeshBooleanNode;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.gui.ui.menu.MenuBox;
import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.platform.input.OrbitCameraKeyGuy;
import ixdar.platform.input.OrbitMouseTrap;
import ixdar.platform.input.SceneInputFrameUpdater;
import ixdar.scenes.Scene;
import ixdar.scenes.model.ControlHint;

/**
 * Booleans two unit cubes placed with one cube's corner on the other's centre, tinting the result
 * by which cube each face came from and which faces the intersection curve created.
 *
 * <p>See also: NHE*19 Section 3.1
 */
@SceneAnnotation(id = "mesh-boolean")
public class MeshBooleanScene extends Scene {

    /** Resource folder holding the DSL graphs. */
    public static final String DSL_FOLDER = "dsl";

    /** Graph this scene renders: two cubes and a boolean. */
    public static final String DSL_NAME = "cube_boolean.dsl";

    /** Statement in {@link #DSL_NAME} whose output is displayed. */
    public static final String BOOLEAN_STATEMENT = "blended";

    /** Output port read from {@link #BOOLEAN_STATEMENT}. */
    public static final String GEOMETRY_PORT = "geometry";

    /** Tag for faces carried over from the first cube. */
    public static final String TAG_FROM_A = "from_a";

    /** Tag for faces carried over from the second cube. */
    public static final String TAG_FROM_B = "from_b";

    /** Tag for faces the boolean created along the intersection curve. */
    public static final String TAG_INTERSECTION = "intersection";

    /**
     * Orbit azimuth. Deliberately across the cubes' shared diagonal rather than along it: at 45° the
     * view direction nearly matches the (1, 1, 1) offset, so the second cube hides behind the first.
     */
    private static final float CAMERA_AZIMUTH = (float) Math.toRadians(135.0);

    /** Orbit elevation, high enough to show the notch the boolean cuts. */
    private static final float CAMERA_ELEVATION = (float) Math.toRadians(20.0);

    /** Orbit distance used until a mesh's own radius supplies one. */
    private static final float CAMERA_DISTANCE = 3.5f;

    /** Framing distance as a multiple of the mesh radius. */
    private static final float RADIUS_TO_DISTANCE = 2.5f;

    /** DSL source, held so an operation change can re-run the graph without re-reading it. */
    public String dslSource;

    /** Operation the graph runs, as the {@code mesh_boolean} node's mode token. */
    public String operation = MeshBooleanNode.UNION;

    private HalfEdgeMeshRuntime meshRuntime;
    private OrbitMouseTrap orbitMouse;

    @Override
    public void initGL() {
        super.initGL();
        initCameraControls();
        Platforms.get().loadSourceAsync(DSL_FOLDER, DSL_NAME, Platforms.gl().getPlatformID(), source -> {
            dslSource = source;
            rebuild();
        });
    }

    @Override
    public void setControls() {
        super.setControls();
        controls.add(new ControlHint(Keys.U, "U", "union", () -> setOperation(MeshBooleanNode.UNION)));
        controls.add(new ControlHint(Keys.D, "D", "difference",
                () -> setOperation(MeshBooleanNode.DIFFERENCE)));
        controls.add(new ControlHint(Keys.I, "I", "intersect",
                () -> setOperation(MeshBooleanNode.INTERSECT)));
        controls.add(new ControlHint(Keys.W, "W", "toggle wireframe", this::toggleWireframe));
    }

    /**
     * Switch the boolean operation and re-run the graph.
     *
     * @param mode one of the {@code mesh_boolean} node's mode tokens
     */
    void setOperation(String mode) {
        operation = mode;
        rebuild();
    }

    /** Toggle the wireframe overlay that shows how the intersection curve split the faces. */
    void toggleWireframe() {
        if (meshRuntime != null) {
            meshRuntime.setWireframe(!meshRuntime.isWireframe());
        }
    }

    /**
     * Run the DSL graph for the current operation, upload the result, and tint its faces by origin.
     *
     * <p>The operation is a per-node literal override rather than an edit of the source, so the
     * shipped graph stays the one {@code ixdar-cli mesh-dsl} loads.
     */
    void rebuild() {
        if (dslSource == null) {
            return;
        }
        PythonParser parser = new PythonParser(new PythonLexer(dslSource));
        List<PythonParser.ParsedNode> ast = parser.parseGraph();
        NodeGraphRuntime runtime = new NodeGraphRuntime();
        runtime.registerAllFromAnnotationRegistry();
        runtime.registerFunctionDefs(parser.functionDefs());

        Object result;
        try {
            result = runtime.executeGraphResult(ast, BOOLEAN_STATEMENT, GEOMETRY_PORT,
                    Map.of(BOOLEAN_STATEMENT + "." + MeshBooleanNode.OPERATION_2, operation));
        } catch (Exception failure) {
            Platforms.get().log("[mesh-boolean] " + DSL_NAME + " failed: " + failure.getMessage());
            return;
        }

        GeometryBundle bundle = GeometryBundles.bundlePart(result);
        if (bundle == null || bundle.mesh() == null) {
            Platforms.get().log("[mesh-boolean] " + DSL_NAME + " produced no mesh");
            return;
        }
        MeshTopology mesh = bundle.mesh();
        if (meshRuntime == null) {
            meshRuntime = new HalfEdgeMeshRuntime();
            meshRuntime.setWireframe(true);
        }
        meshRuntime.upload(mesh);
        orbitMouse.setTarget(mesh.center(new Vector3f()));
        orbitMouse.setOrbit(CAMERA_AZIMUTH, CAMERA_ELEVATION,
                Math.max(CAMERA_DISTANCE, mesh.radius() * RADIUS_TO_DISTANCE));
        applyProvenanceTags(bundle, mesh.faceCount());

        Platforms.get().log("[mesh-boolean] " + operation + " V=" + mesh.vertexCount()
                + " F=" + mesh.faceCount());
    }

    /**
     * Tint faces by the operand they came from, leaving the intersection-region faces their own
     * colour so the seam the boolean cut is visible at a glance.
     *
     * @param bundle boolean output carrying the provenance slots
     * @param faceCount number of faces in the uploaded mesh
     */
    private void applyProvenanceTags(GeometryBundle bundle, int faceCount) {
        Object origins = bundle.slots().get(MeshBooleanNode.FACE_ORIGIN_SLOT);
        if (!(origins instanceof int[] faceOrigin) || faceOrigin.length != faceCount) {
            meshRuntime.clearTags();
            return;
        }
        boolean[] fromA = new boolean[faceCount];
        boolean[] fromB = new boolean[faceCount];
        boolean[] intersection = new boolean[faceCount];
        int newFaces = 0;
        for (int face = 0; face < faceCount; face++) {
            switch (faceOrigin[face]) {
                case MeshBooleanResult.ORIGIN_A -> fromA[face] = true;
                case MeshBooleanResult.ORIGIN_B -> fromB[face] = true;
                default -> {
                    intersection[face] = true;
                    newFaces++;
                }
            }
        }
        Map<String, boolean[]> tags = new HashMap<>();
        tags.put(TAG_FROM_A, fromA);
        tags.put(TAG_FROM_B, fromB);
        tags.put(TAG_INTERSECTION, intersection);
        meshRuntime.setTags(tags);
        meshRuntime.setTagColor(TAG_FROM_A, Color.BLUE_WHITE.toVector4f());
        meshRuntime.setTagColor(TAG_FROM_B, Color.LIGHT_PURPLE.toVector4f());
        meshRuntime.setTagColor(TAG_INTERSECTION, Color.YELLOW.toVector4f());
        Platforms.get().log("[mesh-boolean] faces new=" + newFaces + "/" + faceCount);
    }

    @Override
    public void drawScene() {
        updateCameraControls();
        if (meshRuntime == null) {
            return;
        }
        camera.resetView();
        meshRuntime.render(camera);
    }

    @Override
    public void activate(boolean state) {
        super.activate(state);
        if (!state) {
            meshRuntime = null;
        }
    }

    @Override
    public void shutdown() {
        meshRuntime = null;
        super.shutdown();
    }

    private void initCameraControls() {
        MenuBox.menuVisible = false;
        orbitMouse = new OrbitMouseTrap(camera, this);
        orbitMouse.setOrbit(CAMERA_AZIMUTH, CAMERA_ELEVATION, CAMERA_DISTANCE);
        mouse = orbitMouse;
        keys = new OrbitCameraKeyGuy(orbitMouse, camera, this, controls);
        bindAutomationIfAvailable(Platforms.get(), keys, mouse);
    }

    /**
     * Pump input for the frame. The view itself is not recomputed here: the orbit trap writes the
     * camera whenever the orbit changes, and a first-person update would overwrite that every frame.
     */
    private void updateCameraControls() {
        SceneInputFrameUpdater.update(keys, mouse);
    }
}
