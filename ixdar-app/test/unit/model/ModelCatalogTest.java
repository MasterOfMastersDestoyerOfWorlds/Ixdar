package unit.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ixdar.scenes.model.ModelCatalog;
import ixdar.scenes.model.ModelChoice;

/**
 * The mesh-node model list over a temporary resource tree: every repo graph, nested folders
 * included, then the staging directory's entries without the ones that link back to a repo graph.
 */
class ModelCatalogTest {

    private static final String GRAPH = "box = cube(size=1.0)\n";

    private static final String RINGS = "fixtures/rings.dsl";

    private static final String NO_STAGING = "no-staging";

    private static final String ALPHA_ENTRY = "repo: alpha.dsl";

    private static final String ZETA_ENTRY = "repo: fixtures/deep/zeta.dsl";

    private static final String RINGS_ENTRY = "repo: " + RINGS;

    @Test
    void listsEveryRepoGraphWhenTheStagingDirectoryIsAbsent(@TempDir Path temp) throws IOException {
        Path repo = repoTree(temp);

        ModelCatalog catalog = ModelCatalog.repoGraphsAndStaging(repo, temp.resolve(NO_STAGING));

        assertEquals(List.of(ALPHA_ENTRY, ZETA_ENTRY, RINGS_ENTRY), names(catalog),
                "every .dsl below the folder, nested ones included, sorted by relative path");
        for (ModelChoice choice : catalog.choices) {
            assertEquals(ModelChoice.Kind.DSL, choice.kind, "repo graphs load as DSL graphs");
        }
        assertEquals(repo.resolve(RINGS).toAbsolutePath().toString(), catalog.choices.get(2).path,
                "a repo graph's path is the tracked file a save writes");
    }

    @Test
    void bracketKeysStepThroughTheListAndWrap(@TempDir Path temp) throws IOException {
        ModelCatalog catalog =
                ModelCatalog.repoGraphsAndStaging(repoTree(temp), temp.resolve(NO_STAGING));

        assertEquals(ZETA_ENTRY, catalog.next().displayName, "] steps forward");
        assertEquals(RINGS_ENTRY, catalog.next().displayName, "] steps forward again");
        assertEquals(ALPHA_ENTRY, catalog.next().displayName, "] wraps to the first graph");
        assertEquals(RINGS_ENTRY, catalog.prev().displayName, "[ wraps to the last graph");
    }

    @Test
    void appendsStagedEntriesWithoutTheOnesLinkedFromTheRepo(@TempDir Path temp) throws IOException {
        Path repo = repoTree(temp);
        Path staging = temp.resolve("staging");
        Files.createDirectories(staging.resolve("dsl"));
        Files.createSymbolicLink(staging.resolve("dsl/linked_rings.dsl"), repo.resolve(RINGS));
        write(staging.resolve("dsl/own.dsl"));
        write(staging.resolve("obj/voyage/boat.obj"));

        ModelCatalog catalog = ModelCatalog.repoGraphsAndStaging(repo, staging);

        assertEquals(List.of(ALPHA_ENTRY, ZETA_ENTRY, RINGS_ENTRY, "staged DSL: own.dsl",
                "staged OBJ voyage: boat.obj"), names(catalog),
                "repo graphs first, then staged entries, the symlinked repo graph listed once");
        assertEquals(ModelChoice.Kind.MESH_FILE, catalog.choices.get(4).kind,
                "a staged OBJ keeps its mesh-file kind");
    }

    @Test
    void aLaunchPropertyResolvesToTheKindOfChoiceItNames(@TempDir Path temp) throws IOException {
        ModelCatalog catalog =
                ModelCatalog.repoGraphsAndStaging(repoTree(temp), temp.resolve(NO_STAGING));
        Path scans = Files.createDirectories(temp.resolve("scans"));

        ModelChoice collection = ModelCatalog.launchChoice(scans.toString(), catalog);
        assertEquals(ModelChoice.Kind.COLLECTION, collection.kind, "a directory is a collection");
        assertEquals(scans.toAbsolutePath().toString(), collection.path);
        assertEquals(ZETA_ENTRY, ModelCatalog.launchChoice("zeta", catalog).displayName,
                "a token resolves through the catalog");
        String graph = temp.resolve("elsewhere/graph.dsl").toString();
        assertEquals(ModelChoice.Kind.DSL, ModelCatalog.launchChoice(graph, catalog).kind);
        assertEquals(ModelChoice.Kind.MESH_FILE,
                ModelCatalog.launchChoice(temp.resolve("scan.obj").toString(), null).kind);
    }

    @Test
    void aGraphReadsAndSavesItsTrackedCopy(@TempDir Path temp) throws IOException {
        String resource = "skull.dsl";
        Path built = temp.resolve(ModelCatalog.DSL_BUILD_DIRECTORY).resolve(resource);
        Path tracked = temp.resolve(ModelCatalog.DSL_RESOURCE_DIRECTORY).resolve(resource);
        write(built);
        Files.createDirectories(tracked.getParent());
        Files.write(tracked, RINGS.getBytes(StandardCharsets.UTF_8));

        ModelChoice builtGraph =
                new ModelChoice(resource, built.toString(), ModelChoice.Kind.DSL);
        assertEquals(tracked.toString(), builtGraph.workingFile(),
                "a graph under the build output saves into its src/main/resources copy");
        assertEquals(RINGS, builtGraph.readSource(), "and reads that copy back");

        ModelChoice packaged = ModelCatalog.packagedGraph(resource);
        assertEquals(ModelCatalog.trackedDslFile(resource), packaged.workingFile(),
                "a packaged graph saves into the repo's tracked file");
        ModelChoice missing = ModelCatalog.packagedGraph("no_such_graph.dsl");
        assertNull(missing.workingFile(), "a graph with no file on disk has nowhere to save");
        assertThrows(IOException.class, missing::readSource);
    }

    /**
     * Lay out a DSL resource folder with a nested fixtures tree and one file that is not a graph.
     *
     * @param temp test's temporary directory
     * @return the {@code dsl} folder
     * @throws IOException when a file cannot be written
     */
    private static Path repoTree(Path temp) throws IOException {
        Path repo = temp.resolve("resources/dsl");
        write(repo.resolve("alpha.dsl"));
        write(repo.resolve(RINGS));
        write(repo.resolve("fixtures/deep/zeta.dsl"));
        write(repo.resolve("notes.txt"));
        return repo;
    }

    /**
     * Write a one-statement graph, creating parent folders.
     *
     * @param file file to write
     * @throws IOException when the file cannot be written
     */
    private static void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, GRAPH.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Display names in catalog order.
     *
     * @param catalog catalog to read
     * @return its display names
     */
    private static List<String> names(ModelCatalog catalog) {
        List<String> names = new ArrayList<>();
        for (ModelChoice choice : catalog.choices) {
            names.add(choice.displayName);
        }
        return names;
    }
}
