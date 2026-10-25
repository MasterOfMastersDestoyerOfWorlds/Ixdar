package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import ixdar.nodegraph.api.MapNodeContext;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.nodegraph.nodes.data.LoadMeshNode;

/**
 * The {@code load_mesh} node loads a mesh file through its port interface and
 * matches what {@link MeshLoader} returns directly.
 */
class LoadMeshNodeTest {

    private static final String OFF_PATH =
            "test/resources/quadlayout/figure_7/sphere_base_in_tri.off";

    private static final String DATA_ROOT = "test/resources";

    private static final String BELOW_DATA_ROOT = "quadlayout/figure_7/sphere_base_in_tri.off";

    private static final String DSL_RESOURCES = "src/main/resources/dsl";

    private static final String LOAD_MESH_CALL = "load_mesh(path=\"";

    private static final String SCAN = "crawfish/IMG_4111.glb";

    @Test
    void loadsAnOffFileIntoAGeometryBundle() throws Exception {
        MeshTopology expected = MeshLoader.load(OFF_PATH);

        LoadMeshNode node = new LoadMeshNode();
        MapNodeContext context = new MapNodeContext(node);
        context.setInput(LoadMeshNode.PATH.name, OFF_PATH);
        node.evaluate(context);
        GeometryBundle bundle = context.getOutput(LoadMeshNode.GEOMETRY.name, GeometryBundle.class);

        assertEquals(expected.vertexCount(), bundle.mesh().vertexCount(),
                "the node loads the same vertices as the loader");
        assertEquals(expected.faceCount(), bundle.mesh().faceCount(),
                "the node loads the same faces as the loader");
        assertTrue(bundle.mesh().vertexCount() > 0, "the loaded mesh is not empty");
    }

    @Test
    void emptyPathReturnsAnEmptyBundle() {
        LoadMeshNode node = new LoadMeshNode();
        MapNodeContext context = new MapNodeContext(node);
        node.evaluate(context);
        GeometryBundle bundle = context.getOutput(LoadMeshNode.GEOMETRY.name, GeometryBundle.class);

        assertEquals(0, bundle.mesh().vertexCount(), "no path loads nothing");
    }

    @Test
    void missingFileThrows() {
        LoadMeshNode node = new LoadMeshNode();
        MapNodeContext context = new MapNodeContext(node);
        context.setInput(LoadMeshNode.PATH.name, "no/such/mesh.off");

        assertThrows(UncheckedIOException.class, () -> node.evaluate(context));
    }

    /**
     * TOOL-43: a path that starts with the data-root placeholder loads the file below the data
     * root the JVM property names, wherever the working directory is.
     */
    @Test
    void placeholderPathLoadsBelowTheDataRoot() throws Exception {
        String before = System.getProperty(MeshLoader.DATA_ROOT_PROPERTY);
        System.setProperty(MeshLoader.DATA_ROOT_PROPERTY, Path.of(DATA_ROOT).toAbsolutePath().toString());
        try {
            LoadMeshNode node = new LoadMeshNode();
            MapNodeContext context = new MapNodeContext(node);
            context.setInput(LoadMeshNode.PATH.name,
                    MeshLoader.DATA_ROOT_PLACEHOLDER + "/" + BELOW_DATA_ROOT);
            node.evaluate(context);
            GeometryBundle bundle = context.getOutput(LoadMeshNode.GEOMETRY.name, GeometryBundle.class);

            assertEquals(MeshLoader.load(OFF_PATH).faceCount(), bundle.mesh().faceCount(),
                    "the placeholder path loads the same file as its working-directory path");
        } finally {
            if (before == null) {
                System.clearProperty(MeshLoader.DATA_ROOT_PROPERTY);
            } else {
                System.setProperty(MeshLoader.DATA_ROOT_PROPERTY, before);
            }
        }
    }

    /**
     * TOOL-43: the data-root property wins over the environment, and a placeholder path resolves
     * below it however many separators follow the placeholder.
     */
    @Test
    void placeholderResolvesBelowThePropertyDataRoot() {
        String before = System.getProperty(MeshLoader.DATA_ROOT_PROPERTY);
        Path root = Path.of("some", "data", "root").toAbsolutePath();
        System.setProperty(MeshLoader.DATA_ROOT_PROPERTY, root.toString());
        try {
            assertEquals(root, MeshLoader.dataRoot(), "the property names the data root");
            assertEquals(root.resolve(SCAN), MeshLoader.resolve(MeshLoader.DATA_ROOT_PLACEHOLDER + "/" + SCAN),
                    "the placeholder resolves below the data root");
            assertEquals(root.resolve(SCAN), MeshLoader.resolve(MeshLoader.DATA_ROOT_PLACEHOLDER + "//" + SCAN),
                    "a doubled separator still resolves below the data root");
        } finally {
            if (before == null) {
                System.clearProperty(MeshLoader.DATA_ROOT_PROPERTY);
            } else {
                System.setProperty(MeshLoader.DATA_ROOT_PROPERTY, before);
            }
        }
    }

    /**
     * TOOL-43: with neither the property nor the variable set, the data root is the home
     * directory, where the crawfish scans live on the machines that have them.
     */
    @Test
    void unsetDataRootIsTheHomeDirectory() {
        assumeTrue(System.getenv(MeshLoader.DATA_ROOT_VARIABLE) == null, "the variable is unset");
        String before = System.getProperty(MeshLoader.DATA_ROOT_PROPERTY);
        System.clearProperty(MeshLoader.DATA_ROOT_PROPERTY);
        try {
            assertEquals(Path.of(System.getProperty("user.home")), MeshLoader.dataRoot(),
                    "the home directory is the default data root");
        } finally {
            if (before != null) {
                System.setProperty(MeshLoader.DATA_ROOT_PROPERTY, before);
            }
        }
    }

    /**
     * TOOL-43: every tracked crawfish graph loads its scan through the data root, so the same graph
     * loads on every machine; an absolute path names one machine's home directory.
     *
     * @throws IOException when a graph cannot be read
     */
    @Test
    void trackedCrawfishGraphsLoadTheirScansThroughTheDataRoot() throws IOException {
        List<Path> graphs = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of(DSL_RESOURCES, "crawfish"))) {
            files.filter(file -> file.toString().endsWith(".dsl")).forEach(graphs::add);
        }
        for (String graph : List.of("crawfish_repaired.dsl", "crawfish_union.dsl",
                "crawfish_repair_export.dsl", "fixtures/crawfish_rings.dsl")) {
            graphs.add(Path.of(DSL_RESOURCES, graph));
        }
        assertTrue(graphs.size() > 4, "the crawfish scan graphs are found");
        for (Path graph : graphs) {
            for (String line : Files.readAllLines(graph)) {
                int load = line.indexOf(LOAD_MESH_CALL);
                if (load >= 0) {
                    assertTrue(line.startsWith(MeshLoader.DATA_ROOT_PLACEHOLDER,
                            load + LOAD_MESH_CALL.length()), graph + " loads " + line);
                }
            }
        }
    }
}
