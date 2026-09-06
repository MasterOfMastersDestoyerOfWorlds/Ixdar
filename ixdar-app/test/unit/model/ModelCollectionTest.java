package unit.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.platform.Platforms;
import ixdar.platform.json.JsonValue;
import ixdar.scenes.model.CollectionManifest;
import ixdar.scenes.model.ModelCatalog;
import ixdar.scenes.model.ModelCollection;

/**
 * A directory of scans scans into a {@link ModelCollection} whose membership and keep flags survive
 * a {@code collection.dsl} round trip byte for byte. The fixtures are copies of the two-triangle
 * glTF, so no mesh is ever parsed.
 */
class ModelCollectionTest {

    private static final Path FIXTURE = Path.of("test/resources/gltf/two_triangles.gltf");

    private static final String SIDECAR = """
            {
              "backend": "trellis2",
              "resolution": "1024",
              "steps": 12,
              "seed": 1234,
              "scale": 0.5,
              "texture": true
            }
            """;

    @Test
    void scansMembersSortedByStemWithTheirSidecarSettings(@TempDir Path directory) throws IOException {
        writeMember(directory, "charlie");
        writeMember(directory, "alpha");
        writeMember(directory, "bravo");
        Files.writeString(directory.resolve("alpha" + CollectionManifest.SETTINGS_SUFFIX), SIDECAR,
                StandardCharsets.UTF_8);

        ModelCollection collection = ModelCatalog.collection(directory);

        assertEquals(List.of("alpha", "bravo", "charlie"), names(collection),
                "members are named by file stem and sorted");
        assertEquals(directory.getFileName().toString(), collection.name,
                "the collection is named after its directory");
        assertEquals(3, collection.keptCount(), "a fresh member starts out kept");
        JsonValue alphaSettings = collection.memberSettings[collection.indexOfName("alpha")];
        assertNotNull(alphaSettings, "the sidecar was read");
        assertEquals("trellis2", alphaSettings.getString("backend", ""), "sidecar values are parsed");
        assertNull(collection.memberSettings[collection.indexOfName("bravo")],
                "a member without a sidecar has no settings");
    }

    @Test
    void manifestLivesBesideTheScansAndRoundTripsMembersAndKeepFlags(@TempDir Path directory)
            throws IOException {
        writeMember(directory, "alpha");
        writeMember(directory, "bravo");
        writeMember(directory, "charlie");

        ModelCollection collection = ModelCatalog.collection(directory);
        assertEquals(directory.resolve(ModelCollection.MANIFEST_NAME), collection.manifestPath,
                "a writable scan directory holds its own manifest");
        collection.memberKeep[collection.indexOfName("bravo")] = false;
        CollectionManifest.write(collection);

        ModelCollection reloaded = CollectionManifest.read(collection.manifestPath);

        assertEquals(names(collection), names(reloaded), "the member list round trips");
        assertEquals(List.of(true, false, true), keeps(reloaded), "the keep flags round trip");
        assertEquals(2, reloaded.keptCount(), "the kept count round trips");
        assertEquals(collection.memberPaths[collection.indexOfName("alpha")],
                reloaded.memberPaths[reloaded.indexOfName("alpha")], "member paths round trip");
        assertEquals(directory.toAbsolutePath(), reloaded.directory.toAbsolutePath(),
                "the scan directory is recovered from the member paths");
    }

    @Test
    void rewritingAnUnchangedCollectionProducesIdenticalBytes(@TempDir Path directory)
            throws IOException {
        writeMember(directory, "alpha");
        writeMember(directory, "bravo");

        ModelCollection collection = ModelCatalog.collection(directory);
        collection.memberKeep[collection.indexOfName("alpha")] = false;
        CollectionManifest.write(collection);
        byte[] first = Files.readAllBytes(collection.manifestPath);

        ModelCollection rescanned = ModelCatalog.collection(directory);
        CollectionManifest.write(rescanned);
        byte[] second = Files.readAllBytes(rescanned.manifestPath);

        assertEquals(new String(first, StandardCharsets.UTF_8),
                new String(second, StandardCharsets.UTF_8),
                "a rescan with no changes rewrites the same manifest");
    }

    @Test
    void keepFlagsSurviveARescanOfTheDirectory(@TempDir Path directory) throws IOException {
        writeMember(directory, "alpha");
        writeMember(directory, "bravo");

        ModelCollection collection = ModelCatalog.collection(directory);
        collection.memberKeep[collection.indexOfName("bravo")] = false;
        CollectionManifest.write(collection);

        ModelCollection rescanned = ModelCatalog.collection(directory);

        assertTrue(rescanned.memberKeep[rescanned.indexOfName("alpha")],
                "an untouched member stays kept");
        assertFalse(rescanned.memberKeep[rescanned.indexOfName("bravo")],
                "a rejected member stays rejected");

        writeMember(directory, "delta");
        ModelCollection grown = ModelCatalog.collection(directory);
        assertEquals(List.of("alpha", "bravo", "delta"), names(grown), "the new member joins sorted");
        assertTrue(grown.memberKeep[grown.indexOfName("delta")],
                "a member the manifest never mentioned starts kept");
    }

    @Test
    void manifestStatementsAreSortedAndEndWithAMeshLoad(@TempDir Path directory) throws IOException {
        ModelCollection collection = new ModelCollection("scans", directory,
                directory.resolve(ModelCollection.MANIFEST_NAME),
                new String[] {"zulu", "alpha"},
                new String[] {
                    directory.resolve("zulu.gltf").toString(),
                    directory.resolve("alpha.gltf").toString(),
                },
                new JsonValue[2]);
        collection.memberKeep[collection.indexOfName("zulu")] = false;

        List<String> statements = new ArrayList<>();
        for (String line : CollectionManifest.render(collection).split("\n")) {
            if (!line.isBlank() && !line.startsWith("#")) {
                statements.add(line);
            }
        }

        assertEquals(4, statements.size(), "one keep flag and one load per member");
        assertTrue(statements.get(0).startsWith(CollectionManifest.keepStatementId("alpha")),
                "keep flags come first, sorted by member name");
        assertTrue(statements.get(1).contains("\"" + CollectionManifest.KEEP_PREFIX + "zulu\""),
                "the rejected member's flag names it verbatim");
        assertTrue(statements.get(1).endsWith("default=false)"), "a rejected member writes false");
        assertTrue(statements.get(2).contains(CollectionManifest.LOAD_MESH),
                "the loads follow the flags");
        assertTrue(statements.get(3).contains("zulu.gltf"),
                "the last statement loads a mesh, so the graph output is geometry");
    }

    @Test
    void theParsedMeshCacheIsBoundedAndEvictsLeastRecentlyUsed(@TempDir Path directory)
            throws IOException {
        for (String stem : new String[] {"alpha", "bravo", "charlie", "delta", "echo", "foxtrot"}) {
            writeMember(directory, stem);
        }
        ModelCollection collection = ModelCatalog.collection(directory);
        assertEquals(ModelCollection.MAX_CACHED_MEMBERS, collection.meshCacheCapacity(),
                "a collection larger than the bound caches only the bound");

        for (String path : collection.memberPaths) {
            collection.cacheMesh(path, triangle());
        }
        assertEquals(ModelCollection.MAX_CACHED_MEMBERS, collection.parsedMeshes.size(),
                "the cache never grows past the bound");
        assertFalse(collection.isMeshCached(collection.memberPaths[0]),
                "the oldest member was evicted");
        assertTrue(collection.isMeshCached(collection.memberPaths[5]),
                "the newest member is cached");

        collection.cacheMesh(collection.memberPaths[2], triangle());
        collection.cacheMesh(collection.memberPaths[1], triangle());
        assertTrue(collection.isMeshCached(collection.memberPaths[2]),
                "re-caching a member moves it back to the head instead of evicting it");
    }

    @Test
    void aSmallCollectionCachesEveryMember(@TempDir Path directory) throws IOException {
        writeMember(directory, "alpha");
        writeMember(directory, "bravo");
        ModelCollection collection = ModelCatalog.collection(directory);

        assertEquals(2, collection.meshCacheCapacity(), "the bound is the member count when smaller");
        for (String path : collection.memberPaths) {
            collection.cacheMesh(path, triangle());
        }
        assertEquals(2, collection.parsedMeshes.size(), "both members stay cached");
    }

    @Test
    void theSettingsSummaryWalksTheSidecarsKeysInSortedOrder() {
        JsonValue settings = Platforms.get().parseJson(SIDECAR);

        assertEquals("backend=trellis2 resolution=1024 scale=0.5 seed=1234 steps=12 texture=true",
                ModelCollection.settingsSummary(settings),
                "every scalar appears, keys sorted, numbers as the document spelled them");
        assertEquals("", ModelCollection.settingsSummary(null), "no sidecar renders as nothing");
    }

    /**
     * A one-triangle mesh built in memory, standing in for a parsed scan. The unit suite never
     * parses a mesh file, so the cache is exercised with a mesh it makes itself.
     *
     * @return a fresh triangle
     */
    private static ArrayMesh triangle() {
        return new ArrayMesh(
                new float[] {0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f},
                new float[] {0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f},
                new int[] {0, 1, 2}, 3);
    }

    /**
     * Copy the two-triangle glTF fixture into a collection directory under a new stem.
     *
     * @param directory collection directory
     * @param stem member name to write the fixture as
     * @throws IOException if the fixture cannot be copied
     */
    private static void writeMember(Path directory, String stem) throws IOException {
        Files.copy(FIXTURE, directory.resolve(stem + ".gltf"));
    }

    /**
     * Member names in collection order.
     *
     * @param collection collection to read
     * @return the names
     */
    private static List<String> names(ModelCollection collection) {
        return List.of(collection.memberNames);
    }

    /**
     * Keep flags in collection order.
     *
     * @param collection collection to read
     * @return the flags
     */
    private static List<Boolean> keeps(ModelCollection collection) {
        List<Boolean> flags = new ArrayList<>();
        for (boolean keep : collection.memberKeep) {
            flags.add(keep);
        }
        return flags;
    }
}
