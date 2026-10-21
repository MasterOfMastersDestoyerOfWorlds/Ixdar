package unit.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.models.CollectionManifest;
import ixdar.models.ModelCatalog;
import ixdar.models.ModelChoice;
import ixdar.models.ModelCollection;
import ixdar.platform.Platforms;
import ixdar.platform.json.JsonValue;

/**
 * A directory of scans scans into a {@link ModelCollection} whose membership and keep flags survive
 * a {@code collection.dsl} round trip byte for byte, graph members included, and a model opened
 * alone is a one-member collection that carries the scene's overrides. No mesh is ever parsed.
 */
class ModelCollectionTest {

    private static final Path FIXTURE = Path.of("test/resources/gltf/two_triangles.gltf");

    private static final String ALPHA = "alpha";

    private static final String BRAVO = "bravo";

    private static final String CHARLIE = "charlie";

    private static final String DELTA = "delta";

    private static final String ZULU = "zulu";

    private static final String GLTF = ".gltf";

    private static final String SEGMENTS = "carrier.major_segments";

    private static final String MINOR_SEGMENTS = "carrier.minor_segments";

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

    private static final String GRAPH = "carrier = torus(major_segments=12, minor_segments=8)\n";

    @Test
    void scansMembersSortedByStemWithTheirSidecarSettings(@TempDir Path directory) throws IOException {
        writeMember(directory, CHARLIE);
        writeMember(directory, ALPHA);
        writeMember(directory, BRAVO);
        Files.writeString(directory.resolve(ALPHA + CollectionManifest.SETTINGS_SUFFIX), SIDECAR,
                StandardCharsets.UTF_8);

        ModelCollection collection = ModelCatalog.collection(directory);

        assertEquals(List.of(ALPHA, BRAVO, CHARLIE), names(collection),
                "members are named by file stem and sorted");
        assertEquals(directory.getFileName().toString(), collection.name,
                "the collection is named after its directory");
        assertEquals(3, collection.keptCount(), "a fresh member starts out kept");
        JsonValue alphaSettings = collection.memberSettings[collection.indexOfName(ALPHA)];
        assertNotNull(alphaSettings, "the sidecar was read");
        assertEquals("trellis2", alphaSettings.getString("backend", ""), "sidecar values are parsed");
        assertNull(collection.memberSettings[collection.indexOfName(BRAVO)],
                "a member without a sidecar has no settings");
    }

    @Test
    void manifestLivesBesideTheScansAndRoundTripsMembersAndKeepFlags(@TempDir Path directory)
            throws IOException {
        writeMember(directory, ALPHA);
        writeMember(directory, BRAVO);
        writeMember(directory, CHARLIE);

        ModelCollection collection = ModelCatalog.collection(directory);
        assertEquals(directory.resolve(ModelCollection.MANIFEST_NAME), collection.manifestPath,
                "a writable scan directory holds its own manifest");
        collection.memberKeep[collection.indexOfName(BRAVO)] = false;
        CollectionManifest.write(collection);

        ModelCollection reloaded = CollectionManifest.read(collection.manifestPath);

        assertEquals(names(collection), names(reloaded), "the member list round trips");
        assertEquals(List.of(true, false, true), keeps(reloaded), "the keep flags round trip");
        assertEquals(2, reloaded.keptCount(), "the kept count round trips");
        assertEquals(collection.members[collection.indexOfName(ALPHA)].path,
                reloaded.members[reloaded.indexOfName(ALPHA)].path, "member paths round trip");
        assertEquals(directory.toAbsolutePath(), reloaded.directory.toAbsolutePath(),
                "the scan directory is recovered from the member paths");
    }

    @Test
    void aDslFileBesideTheScansIsAGraphMemberAndTheManifestIsNot(@TempDir Path directory)
            throws IOException {
        writeMember(directory, ALPHA);
        Files.writeString(directory.resolve(BRAVO + ModelCatalog.DSL_EXTENSION), GRAPH,
                StandardCharsets.UTF_8);
        ModelCollection collection = ModelCatalog.collection(directory);
        collection.memberKeep[collection.indexOfName(BRAVO)] = false;
        CollectionManifest.write(collection);

        ModelCollection rescanned = ModelCatalog.collection(directory);

        assertEquals(List.of(ALPHA, BRAVO), names(rescanned),
                "the graph is a member and the collection.dsl beside it is not");
        ModelChoice graph = rescanned.members[rescanned.indexOfName(BRAVO)];
        assertEquals(ModelChoice.Kind.DSL, graph.kind, "a .dsl member loads as a graph");
        assertEquals(ModelChoice.Kind.MESH_FILE,
                rescanned.members[rescanned.indexOfName(ALPHA)].kind, "a scan loads as a mesh");
        assertEquals(GRAPH, graph.readSource(), "the member reads its graph's source");
        assertFalse(rescanned.memberKeep[rescanned.indexOfName(BRAVO)],
                "a graph member's keep flag survives a rescan");
        ModelCollection reloaded = CollectionManifest.read(collection.manifestPath);
        assertEquals(ModelChoice.Kind.DSL, reloaded.members[reloaded.indexOfName(BRAVO)].kind,
                "the manifest records the graph member and reads it back as one");
    }

    @Test
    void aManifestWrittenBeforeGraphMembersStillReads(@TempDir Path directory) {
        String alphaPath = directory.resolve(ALPHA + GLTF).toString();
        String bravoPath = directory.resolve(BRAVO + GLTF).toString();
        String written = "# Ixdar model collection \"" + directory.getFileName()
                + "\" - 2 members, 1 kept\n"
                + "# Scans in " + directory + "\n"
                + "# Membership and keep/reject decisions only; members are merged nowhere yet.\n"
                + "\n"
                + "keep_alpha = input_boolean(name=\"keep:alpha\", default=true)\n"
                + "keep_bravo = input_boolean(name=\"keep:bravo\", default=false)\n"
                + "\n"
                + "alpha = load_mesh(path=\"" + alphaPath + "\")\n"
                + "bravo = load_mesh(path=\"" + bravoPath + "\")\n";

        ModelCollection collection = CollectionManifest.parse(
                directory.resolve(ModelCollection.MANIFEST_NAME), written);

        assertEquals(List.of(ALPHA, BRAVO), names(collection), "both members read back");
        assertEquals(List.of(true, false), keeps(collection), "both keep flags read back");
        assertEquals(bravoPath, collection.members[1].path, "the member's path reads back");
        assertEquals(ModelChoice.Kind.MESH_FILE, collection.members[1].kind,
                "a scan reads back as a mesh file");
        assertEquals(written, CollectionManifest.render(collection),
                "the old manifest renders back to its own bytes");
    }

    @Test
    void rewritingAnUnchangedCollectionProducesIdenticalBytes(@TempDir Path directory)
            throws IOException {
        writeMember(directory, ALPHA);
        writeMember(directory, BRAVO);

        ModelCollection collection = ModelCatalog.collection(directory);
        collection.memberKeep[collection.indexOfName(ALPHA)] = false;
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
        writeMember(directory, ALPHA);
        writeMember(directory, BRAVO);

        ModelCollection collection = ModelCatalog.collection(directory);
        collection.memberKeep[collection.indexOfName(BRAVO)] = false;
        CollectionManifest.write(collection);

        ModelCollection rescanned = ModelCatalog.collection(directory);

        assertTrue(rescanned.memberKeep[rescanned.indexOfName(ALPHA)],
                "an untouched member stays kept");
        assertFalse(rescanned.memberKeep[rescanned.indexOfName(BRAVO)],
                "a rejected member stays rejected");

        writeMember(directory, DELTA);
        ModelCollection grown = ModelCatalog.collection(directory);
        assertEquals(List.of(ALPHA, BRAVO, DELTA), names(grown), "the new member joins sorted");
        assertTrue(grown.memberKeep[grown.indexOfName(DELTA)],
                "a member the manifest never mentioned starts kept");
    }

    @Test
    void manifestStatementsAreSortedAndEndWithAMeshLoad(@TempDir Path directory) {
        ModelCollection collection = new ModelCollection("scans", directory,
                directory.resolve(ModelCollection.MANIFEST_NAME),
                new ModelChoice[] {
                    new ModelChoice(ZULU, directory.resolve(ZULU + GLTF).toString()),
                    new ModelChoice(ALPHA, directory.resolve(ALPHA + GLTF).toString()),
                },
                new JsonValue[2]);
        collection.memberKeep[collection.indexOfName(ZULU)] = false;

        List<String> statements = new ArrayList<>();
        for (String line : CollectionManifest.render(collection).split(CollectionManifest.NEWLINE)) {
            if (!line.isBlank() && !line.startsWith("#")) {
                statements.add(line);
            }
        }

        assertEquals(4, statements.size(), "one keep flag and one load per member");
        assertTrue(statements.get(0).startsWith(CollectionManifest.keepStatementId(ALPHA)),
                "keep flags come first, sorted by member name");
        assertTrue(statements.get(1).contains("\"" + CollectionManifest.KEEP_PREFIX + ZULU + "\""),
                "the rejected member's flag names it verbatim");
        assertTrue(statements.get(1).endsWith("default=false)"), "a rejected member writes false");
        assertTrue(statements.get(2).contains(CollectionManifest.LOAD_MESH),
                "the loads follow the flags");
        assertTrue(statements.get(3).contains(ZULU + GLTF),
                "the last statement loads a mesh, so the graph output is geometry");
    }

    @Test
    void aModelOpenedAloneIsAOneMemberCollectionCarryingTheOverrides(@TempDir Path directory)
            throws IOException {
        writeMember(directory, ALPHA);
        writeMember(directory, BRAVO);
        ModelChoice torus = new ModelChoice("Torus layout", "dsl/fixtures/torus_layout.dsl",
                ModelChoice.Kind.DSL);
        ModelChoice mesh = new ModelChoice(ZULU, directory.resolve(ZULU + GLTF).toString());
        ModelCollection empty = new ModelCollection("", null, null, new ModelChoice[0],
                new JsonValue[0]);
        empty.overrides.put(SEGMENTS, 24f);

        ModelCollection alone = empty.open(torus);
        assertEquals(1, alone.memberCount(), "a model opened alone is a one-member collection");
        assertSame(torus, alone.current(), "the collection holds the requested model");
        assertFalse(alone.hasManifest(), "a model opened alone keeps no manifest");
        assertEquals(Map.of(SEGMENTS, 24f), alone.overrides,
                "an override set before the first run reaches the first model");

        ModelCollection switched = alone.open(mesh);
        assertSame(mesh, switched.current(), "a switch opens the next model alone");
        assertEquals(Map.of(SEGMENTS, 24f), switched.overrides, "the override survives the switch");

        ModelCollection scans = switched.open(new ModelChoice(directory.getFileName().toString(),
                directory.toString(), ModelChoice.Kind.COLLECTION));
        assertTrue(scans.hasManifest(), "a collection choice opens the directory's scans");
        assertEquals(List.of(ALPHA, BRAVO), names(scans), "the scans are the members");
        assertEquals(Map.of(SEGMENTS, 24f), scans.overrides, "the override survives into the scans");
        assertSame(scans, scans.open(scans.members[1]),
                "a member of the open scans keeps the collection");
        assertEquals(1, scans.index(), "and moves its cursor onto the member");
    }

    @Test
    void aGraphRunsWithItsOwnOverridesUnderTheCollections() {
        ModelChoice scaled = new ModelChoice("Scaled torus x4", "dsl/fixtures/scaled_torus.dsl",
                ModelChoice.Kind.DSL, null, Map.of(SEGMENTS, 48, MINOR_SEGMENTS, 32));
        ModelCollection collection = new ModelCollection("", null, null, new ModelChoice[0],
                new JsonValue[0]).open(scaled);

        assertEquals(Map.of(SEGMENTS, 48, MINOR_SEGMENTS, 32),
                collection.overridesFor(scaled), "the graph runs with its literal overrides");
        collection.overrides.put(SEGMENTS, 24f);
        assertEquals(Map.of(SEGMENTS, 24f, MINOR_SEGMENTS, 32),
                collection.overridesFor(scaled), "the collection's override replaces the graph's");
        assertEquals(Map.of(SEGMENTS, 48, MINOR_SEGMENTS, 32), scaled.overrides,
                "the registered graph's own overrides are left alone");
    }

    @Test
    void theParsedBundleCacheIsBoundedAndEvictsLeastRecentlyUsed(@TempDir Path directory)
            throws IOException {
        for (String stem : new String[] {ALPHA, BRAVO, CHARLIE, DELTA, "echo", "foxtrot"}) {
            writeMember(directory, stem);
        }
        ModelCollection collection = ModelCatalog.collection(directory);
        assertEquals(ModelCollection.MAX_CACHED_MEMBERS, collection.meshCacheCapacity(),
                "a collection larger than the bound caches only the bound");

        for (ModelChoice member : collection.members) {
            collection.cacheBundle(member.path, triangle());
        }
        assertEquals(ModelCollection.MAX_CACHED_MEMBERS, collection.parsedBundles.size(),
                "the cache never grows past the bound");
        assertFalse(collection.isBundleCached(collection.members[0].path),
                "the oldest member was evicted");
        assertTrue(collection.isBundleCached(collection.members[5].path),
                "the newest member is cached");

        collection.cacheBundle(collection.members[2].path, triangle());
        collection.cacheBundle(collection.members[1].path, triangle());
        assertTrue(collection.isBundleCached(collection.members[2].path),
                "re-caching a member moves it back to the head instead of evicting it");
    }

    @Test
    void aSmallCollectionCachesEveryMember(@TempDir Path directory) throws IOException {
        writeMember(directory, ALPHA);
        writeMember(directory, BRAVO);
        ModelCollection collection = ModelCatalog.collection(directory);

        assertEquals(2, collection.meshCacheCapacity(), "the bound is the member count when smaller");
        for (ModelChoice member : collection.members) {
            collection.cacheBundle(member.path, triangle());
        }
        assertEquals(2, collection.parsedBundles.size(), "both members stay cached");
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
    private static GeometryBundle triangle() {
        return GeometryBundle.ofMesh(new ArrayMesh(
                new float[] {0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f},
                new float[] {0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f},
                new int[] {0, 1, 2}, 3));
    }

    /**
     * Copy the two-triangle glTF fixture into a collection directory under a new stem.
     *
     * @param directory collection directory
     * @param stem member name to write the fixture as
     * @throws IOException if the fixture cannot be copied
     */
    private static void writeMember(Path directory, String stem) throws IOException {
        Files.copy(FIXTURE, directory.resolve(stem + GLTF));
    }

    /**
     * Member names in collection order.
     *
     * @param collection collection to read
     * @return the names
     */
    private static List<String> names(ModelCollection collection) {
        List<String> names = new ArrayList<>();
        for (ModelChoice member : collection.members) {
            names.add(member.displayName);
        }
        return names;
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
