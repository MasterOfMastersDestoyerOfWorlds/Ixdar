package ixdar.scenes.mesh;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.geometry.mesh.csg.MeshBooleanResult;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.nodes.api.IntField;
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
 * by which cube each face is an untouched copy from and which faces the intersection curve cut.
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

    /** Corners per triangle, and equally coordinates per display vertex. */
    public static final int CORNERS_PER_TRIANGLE = 3;

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
        NodeGraphRuntime graphRuntime = NodeGraphRuntime.fromSource(dslSource);
        List<PythonParser.ParsedNode> ast = graphRuntime.statements;

        Object result;
        try {
            result = graphRuntime.executeGraphResult(ast, BOOLEAN_STATEMENT, GEOMETRY_PORT,
                    Map.of(BOOLEAN_STATEMENT + "." + MeshBooleanNode.OPERATION.name, operation));
        } catch (Exception failure) {
            Platforms.get().log(LOG_PREFIX + DSL_NAME + " failed: " + failure.getMessage());
            return;
        }
        graphRuntime.logTimings("[mesh-boolean]");

        if (!(result instanceof GeometryBundle bundle) || bundle.mesh() == null) {
            Platforms.get().log(LOG_PREFIX + DSL_NAME + " produced no mesh");
            return;
        }
        MeshTopology mesh = bundle.mesh();
        if (runtime == null) {
            runtime = createRuntime();
        }
        frameMesh(mesh);
        applyProvenanceTags(bundle, mesh);

        Platforms.get().log(LOG_PREFIX + operation + " V=" + mesh.vertexCount()
                + " F=" + mesh.faceCount());
    }

    /**
     * Upload the mesh tinted by provenance: untouched faces in their operand's colour, cut faces
     * in a third. Tags are per vertex, so the display copy gives every face its own corners.
     *
     * @param bundle boolean output carrying the provenance slots
     * @param mesh the boolean's mesh
     */
    private void applyProvenanceTags(GeometryBundle bundle, MeshTopology mesh) {
        int faceCount = mesh.faceCount();
        Object origins = bundle.slots().get(MeshBooleanNode.FACE_ORIGIN_SLOT);
        if (!(origins instanceof IntField faceOrigin) || faceOrigin.length() != faceCount) {
            runtime.upload(mesh);
            runtime.clearTags();
            return;
        }
        int cornerCount = faceCount * CORNERS_PER_TRIANGLE;
        float[] positions = new float[cornerCount * CORNERS_PER_TRIANGLE];
        int[] triangles = new int[cornerCount];
        boolean[] fromA = new boolean[cornerCount];
        boolean[] fromB = new boolean[cornerCount];
        boolean[] intersection = new boolean[cornerCount];
        Vector3f position = new Vector3f();
        int newFaces = 0;
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            int origin = faceOrigin.get(activeFace);
            if (origin == MeshBooleanResult.ORIGIN_NEW) {
                newFaces++;
            }
            for (int corner = 0; corner < CORNERS_PER_TRIANGLE; corner++) {
                int displayVertex = activeFace * CORNERS_PER_TRIANGLE + corner;
                mesh.vertexPosition(mesh.faceVertexAt(faceId, corner), position);
                positions[displayVertex * CORNERS_PER_TRIANGLE] = position.x;
                positions[displayVertex * CORNERS_PER_TRIANGLE + 1] = position.y;
                positions[displayVertex * CORNERS_PER_TRIANGLE + 2] = position.z;
                triangles[displayVertex] = displayVertex;
                fromA[displayVertex] = origin == MeshBooleanResult.ORIGIN_A;
                fromB[displayVertex] = origin == MeshBooleanResult.ORIGIN_B;
                intersection[displayVertex] = origin == MeshBooleanResult.ORIGIN_NEW;
            }
        }
        HalfEdgeMesh display = HalfEdgeMesh.bulkAllocate(positions, triangles,
                CORNERS_PER_TRIANGLE);
        display.computeNormals();
        runtime.upload(display);

        Map<String, boolean[]> tags = new HashMap<>();
        tags.put(TAG_FROM_A, fromA);
        tags.put(TAG_FROM_B, fromB);
        tags.put(TAG_INTERSECTION, intersection);
        runtime.setTagColor(TAG_FROM_A, Color.BLUE_WHITE.toVector4f());
        runtime.setTagColor(TAG_FROM_B, Color.BRIGHT_ORANGE.toVector4f());
        runtime.setTagColor(TAG_INTERSECTION, Color.BRIGHT_GREEN.toVector4f());
        runtime.setTags(tags);
        Platforms.get().log(LOG_PREFIX + "faces new=" + newFaces + "/" + faceCount);
    }
}
