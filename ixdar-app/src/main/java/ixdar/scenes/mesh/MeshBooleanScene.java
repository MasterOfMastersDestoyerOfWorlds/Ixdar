package ixdar.scenes.mesh;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.csg.MeshBooleanResult;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.GeometryBundles;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.nodes.geometry.MeshBooleanNode;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.parsing.python.PythonParser;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.scenes.model.ControlHint;
import ixdar.scenes.model.ModelChoice;
import ixdar.scenes.model.ModelScene;

/**
 * Booleans two unit cubes placed with one cube's corner on the other's centre, tinting the result
 * by which cube each face came from and which faces the intersection curve created.
 *
 * <p>See also: NHE*19 Section 3.1
 */
@SceneAnnotation(id = "mesh-boolean")
public class MeshBooleanScene extends ModelScene {

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

    /** Log prefix for this scene's messages. */
    public static final String LOG_PREFIX = "[mesh-boolean] ";

    /** DSL source, held so an operation change can re-run the graph without re-reading it. */
    public String dslSource;

    /** Operation the graph runs, as the {@code mesh_boolean} node's mode token. */
    public String operation = MeshBooleanNode.UNION;

    /**
     * Views the cubes across their shared diagonal rather than along it: at the default 45° the
     * view direction nearly matches the (1, 1, 1) offset, so the second cube hides behind the
     * first. The elevation is high enough to show the notch the boolean cuts.
     */
    public MeshBooleanScene() {
        orbitAzimuth = (float) Math.toRadians(135.0);
        orbitElevation = (float) Math.toRadians(20.0);
    }

    @Override
    public HalfEdgeMeshRuntime createRuntime() {
        HalfEdgeMeshRuntime created = new HalfEdgeMeshRuntime();
        created.setWireframe(true);
        return created;
    }

    /** Creates the runtime, then loads and runs the graph once the DSL source arrives. */
    @Override
    public void initModel() {
        runtime = createRuntime();
        Platforms.get().loadSourceAsync(DSL_FOLDER, DSL_NAME, Platforms.gl().getPlatformID(), source -> {
            dslSource = source;
            rebuild();
        });
    }

    /**
     * No file models: this scene renders one fixed graph, so the ESC menu offers nothing to load.
     *
     * @return an empty list
     */
    @Override
    public List<ModelChoice> availableModels() {
        return List.of();
    }

    @Override
    public void renderScene() {
        camera.resetView();
        runtime.render(camera);
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
        if (runtime != null) {
            runtime.setWireframe(!runtime.isWireframe());
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
        NodeGraphRuntime.ParsedGraph parsedGraph = NodeGraphRuntime.fromSource(dslSource);
        List<PythonParser.ParsedNode> ast = parsedGraph.statements();
        NodeGraphRuntime graphRuntime = parsedGraph.runtime();

        Object result;
        try {
            result = graphRuntime.executeGraphResult(ast, BOOLEAN_STATEMENT, GEOMETRY_PORT,
                    Map.of(BOOLEAN_STATEMENT + "." + MeshBooleanNode.OPERATION_2, operation));
        } catch (Exception failure) {
            Platforms.get().log(LOG_PREFIX + DSL_NAME + " failed: " + failure.getMessage());
            return;
        }
        graphRuntime.logTimings("[mesh-boolean]");

        GeometryBundle bundle = GeometryBundles.bundlePart(result);
        if (bundle == null || bundle.mesh() == null) {
            Platforms.get().log(LOG_PREFIX + DSL_NAME + " produced no mesh");
            return;
        }
        MeshTopology mesh = bundle.mesh();
        if (runtime == null) {
            runtime = createRuntime();
        }
        runtime.upload(mesh);
        frameMesh(mesh);
        applyProvenanceTags(bundle, mesh.faceCount());

        Platforms.get().log(LOG_PREFIX + operation + " V=" + mesh.vertexCount()
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
            runtime.clearTags();
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
        runtime.setTags(tags);
        runtime.setTagColor(TAG_FROM_A, Color.BLUE_WHITE.toVector4f());
        runtime.setTagColor(TAG_FROM_B, Color.LIGHT_PURPLE.toVector4f());
        runtime.setTagColor(TAG_INTERSECTION, Color.YELLOW.toVector4f());
        Platforms.get().log(LOG_PREFIX + "faces new=" + newFaces + "/" + faceCount);
    }
}
