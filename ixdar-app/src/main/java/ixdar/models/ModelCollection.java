package ixdar.models;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.platform.json.JsonValue;

/**
 * The models a scene has open and the overrides it runs them with: a directory of scans with its
 * {@code collection.dsl} manifest, or one model opened alone. Members are parallel arrays.
 */
public final class ModelCollection {

    public static final String MANIFEST_NAME = "collection.dsl";

    public static final String MANIFEST_FALLBACK_DIR = "collections";

    public static final int MAX_CACHED_MEMBERS = 4;

    public static final int UNCOUNTED = -1;

    public static final String UNKNOWN_COUNT = "?";

    /** Collection name: the scanned directory's own name, or the lone model's display name. */
    public final String name;

    /** Directory the members were scanned from, {@code null} for a model opened alone. */
    public final Path directory;

    /** Where {@link CollectionManifest} keeps the manifest, {@code null} for a model opened alone. */
    public final Path manifestPath;

    /** The members, sorted by display name (a scan's file stem); the index into every array. */
    public final ModelChoice[] members;

    /** Parsed {@code *.settings.json} sidecar per member, {@code null} when there is none. */
    public final JsonValue[] memberSettings;

    /** Whether each member is kept; {@code false} marks it rejected. Persisted in the manifest. */
    public final boolean[] memberKeep;

    /** Vertices of each member's parsed mesh, {@link #UNCOUNTED} until it has been loaded. */
    public final int[] memberVertexCount;

    /** Triangles of each member's parsed mesh, {@link #UNCOUNTED} until it has been loaded. */
    public final int[] memberTriangleCount;

    /**
     * Members parsed so far, keyed by mesh path, least recently used first. Bounded by
     * {@link #meshCacheCapacity}, so stepping back and forth costs no parse and no unbounded heap.
     * Whole bundles, not bare meshes, so a member's UV and material slots survive the cache.
     */
    public final Map<String, GeometryBundle> parsedBundles = new LinkedHashMap<>();

    /**
     * Overrides by path ({@code layout/field.curvature_bias}) applied to every graph the scene runs
     * over these members; {@link #open} carries them to the next collection, so they survive model
     * switches and can be set before the first run.
     */
    public final Map<String, Object> overrides = new LinkedHashMap<>();

    /** The models the showing scene can switch to; the scene supplies it and {@link #open} carries it. */
    public Supplier<List<ModelChoice>> offered = List::of;

    /**
     * Asks the showing scene to load a model on its next frame; the scene supplies it and
     * {@link #open} carries it.
     */
    public Consumer<ModelChoice> requestLoad = choice -> { };

    /**
     * Checks an override key against the graph the current model runs, throwing
     * {@link IllegalArgumentException} for a path or port it lacks; the scene supplies it.
     */
    public Consumer<String> validateOverrideKey = key -> { };

    private int index;

    /**
     * Bind members to their collection and manifest location.
     *
     * @param name collection name, normally the directory's own name
     * @param directory directory the members were scanned from, {@code null} for none
     * @param manifestPath file the manifest is read from and written to, {@code null} for none
     * @param members members in sorted order
     * @param memberSettings parsed sidecar of each member, {@code null} entries where absent
     */
    public ModelCollection(String name, Path directory, Path manifestPath, ModelChoice[] members,
            JsonValue[] memberSettings) {
        this.name = name;
        this.directory = directory;
        this.manifestPath = manifestPath;
        this.members = members;
        this.memberSettings = memberSettings;
        this.memberKeep = new boolean[members.length];
        this.memberVertexCount = new int[members.length];
        this.memberTriangleCount = new int[members.length];
        for (int member = 0; member < members.length; member++) {
            memberKeep[member] = true;
            memberVertexCount[member] = UNCOUNTED;
            memberTriangleCount[member] = UNCOUNTED;
        }
    }

    /**
     * Scanned files as members, each named by its stem: a {@code .dsl} file is a graph member, any
     * other a mesh file. Keep flags come from {@code keepFlags}; a member it never names is kept.
     *
     * @param name collection name
     * @param directory directory the files came from
     * @param manifestPath file the manifest is read from and written to
     * @param files member files, sorted by stem
     * @param keepFlags keep flag by member name, as a manifest records them
     * @return the collection
     */
    public static ModelCollection scanned(String name, Path directory, Path manifestPath,
            List<Path> files, Map<String, Boolean> keepFlags) {
        ModelChoice[] members = new ModelChoice[files.size()];
        JsonValue[] settings = new JsonValue[files.size()];
        for (int member = 0; member < files.size(); member++) {
            Path file = files.get(member);
            ModelChoice.Kind kind = file.getFileName().toString().endsWith(ModelCatalog.DSL_EXTENSION)
                    ? ModelChoice.Kind.DSL : ModelChoice.Kind.MESH_FILE;
            members[member] = new ModelChoice(CollectionManifest.stemOf(file), file.toString(), kind);
            settings[member] = CollectionManifest.settingsBesideMember(file);
        }
        ModelCollection collection =
                new ModelCollection(name, directory, manifestPath, members, settings);
        for (int member = 0; member < members.length; member++) {
            Boolean keep = keepFlags.get(members[member].displayName);
            collection.memberKeep[member] = keep == null || keep;
        }
        return collection;
    }

    /**
     * Where a scan directory's manifest belongs: beside the scans when that directory can be
     * written, otherwise under the staging root, so a read-only corpus still records decisions.
     *
     * @param directory directory of scans
     * @return absolute path of the manifest file for that directory
     */
    public static Path manifestFor(Path directory) {
        Path absolute = directory.toAbsolutePath();
        if (Files.isWritable(absolute)) {
            return absolute.resolve(MANIFEST_NAME);
        }
        Path fileName = absolute.getFileName();
        String folder = fileName == null ? MANIFEST_FALLBACK_DIR : fileName.toString();
        return ModelCatalog.stagingRoot().resolve(MANIFEST_FALLBACK_DIR).resolve(folder)
                .resolve(MANIFEST_NAME);
    }

    /**
     * One-line rendering of a member's sidecar: every scalar it holds as {@code key=value}, keys
     * sorted so the text is stable whatever order the document listed them in.
     *
     * @param settings a member's parsed sidecar, possibly {@code null}
     * @return the summary, empty when there is no sidecar or it holds no scalars
     */
    public static String settingsSummary(JsonValue settings) {
        if (settings == null || !settings.isObject()) {
            return "";
        }
        List<String> keys = new ArrayList<>(settings.members.keySet());
        Collections.sort(keys);
        StringBuilder text = new StringBuilder();
        for (String key : keys) {
            String value = settings.get(key).asString(null);
            if (value == null) {
                continue;
            }
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(key).append('=').append(value);
        }
        return text.toString();
    }

    /**
     * Members in this collection.
     *
     * @return member count
     */
    public int memberCount() {
        return members.length;
    }

    /**
     * Whether this is a collection of scans that keeps a manifest, rather than a model opened alone.
     *
     * @return {@code true} when {@link #manifestPath} is set
     */
    public boolean hasManifest() {
        return manifestPath != null;
    }

    /**
     * The member under the cursor: the model the scene has requested.
     *
     * @return the current member, or {@code null} when the collection is empty
     */
    public ModelChoice current() {
        return memberCount() == 0 ? null : members[index];
    }

    /**
     * The collection that holds {@code choice} next, carrying {@link #overrides} and the scene's
     * callbacks across: this one
     * with its cursor moved when {@code choice} is a member of these scans, the directory's scans
     * for a collection choice, else {@code choice} opened alone: one member, no directory and no
     * manifest.
     *
     * @param choice model to open
     * @return the collection now holding the requested model
     */
    public ModelCollection open(ModelChoice choice) {
        if (hasManifest() && selectPath(choice.path) >= 0) {
            return this;
        }
        ModelCollection next = choice.kind == ModelChoice.Kind.COLLECTION
                ? choice.openCollection()
                : new ModelCollection(choice.displayName, null, null, new ModelChoice[] {choice},
                        new JsonValue[1]);
        next.overrides.putAll(overrides);
        next.offered = offered;
        next.requestLoad = requestLoad;
        next.validateOverrideKey = validateOverrideKey;
        return next;
    }

    /**
     * The overrides a graph member runs with: its own literal overrides, then this collection's,
     * which replace them key by key.
     *
     * @param choice the graph being run
     * @return the merged overrides, a fresh map
     */
    public Map<String, Object> overridesFor(ModelChoice choice) {
        Map<String, Object> merged = new LinkedHashMap<>(choice.overrides);
        merged.putAll(overrides);
        return merged;
    }

    /**
     * Vertex and triangle counts of one member, for the menu and the terminal.
     *
     * @param member member index
     * @return {@code "V=… T=…"}, or {@code "V=? T=?"} before the member has been loaded
     */
    public String countSummary(int member) {
        String vertices = memberVertexCount[member] == UNCOUNTED
                ? UNKNOWN_COUNT : Integer.toString(memberVertexCount[member]);
        String triangles = memberTriangleCount[member] == UNCOUNTED
                ? UNKNOWN_COUNT : Integer.toString(memberTriangleCount[member]);
        return "V=" + vertices + " T=" + triangles;
    }

    /**
     * The settings summary every member shares. The ten crawfish scans were generated in one run,
     * so their sidecars are identical and the menu can show the summary once instead of per row.
     *
     * @return the common summary, or empty when members differ or have no sidecars
     */
    public String sharedSettingsSummary() {
        String shared = null;
        for (JsonValue settings : memberSettings) {
            String summary = settingsSummary(settings);
            if (shared == null) {
                shared = summary;
            } else if (!shared.equals(summary)) {
                return "";
            }
        }
        return shared == null ? "" : shared;
    }

    /**
     * How many members are kept.
     *
     * @return count of members whose {@link #memberKeep} flag is set
     */
    public int keptCount() {
        int kept = 0;
        for (boolean keep : memberKeep) {
            if (keep) {
                kept++;
            }
        }
        return kept;
    }

    /**
     * How many parsed members this collection keeps at once.
     *
     * @return {@link #MAX_CACHED_MEMBERS}, or the member count when the collection is smaller
     */
    public int meshCacheCapacity() {
        return Math.max(1, Math.min(memberCount(), MAX_CACHED_MEMBERS));
    }

    /**
     * The parsed bundle for a member path, reading and caching it on a miss.
     *
     * @param memberPath absolute mesh file path of a member
     * @return the parsed bundle, carrying whatever slots the file's format supplies
     * @throws IOException if the mesh file cannot be read
     */
    public GeometryBundle loadBundle(String memberPath) throws IOException {
        GeometryBundle cached = parsedBundles.remove(memberPath);
        if (cached != null) {
            parsedBundles.put(memberPath, cached);
            return cached;
        }
        GeometryBundle parsed = MeshLoader.loadBundle(memberPath);
        cacheBundle(memberPath, parsed);
        return parsed;
    }

    /**
     * Put a parsed bundle at the head of the cache, evicting least-recently-used entries past
     * {@link #meshCacheCapacity}.
     *
     * @param memberPath absolute mesh file path of a member
     * @param parsed bundle to cache
     */
    public void cacheBundle(String memberPath, GeometryBundle parsed) {
        parsedBundles.remove(memberPath);
        parsedBundles.put(memberPath, parsed);
        while (parsedBundles.size() > meshCacheCapacity()) {
            parsedBundles.remove(parsedBundles.keySet().iterator().next());
        }
    }

    /**
     * Whether a member's bundle is already parsed and would come back without touching disk.
     *
     * @param memberPath absolute mesh file path of a member
     * @return {@code true} when the bundle is cached
     */
    public boolean isBundleCached(String memberPath) {
        return parsedBundles.containsKey(memberPath);
    }

    /**
     * Cursor position.
     *
     * @return zero-based member index, or {@code -1} when the collection is empty
     */
    public int index() {
        return memberCount() == 0 ? -1 : index;
    }

    /**
     * Step the cursor forward one, wrapping at the end.
     *
     * @return the member index now under the cursor, or {@code -1} when the collection is empty
     */
    public int next() {
        return memberCount() == 0 ? -1 : select((index + 1) % memberCount());
    }

    /**
     * Step the cursor back one, wrapping at the start.
     *
     * @return the member index now under the cursor, or {@code -1} when the collection is empty
     */
    public int prev() {
        return memberCount() == 0 ? -1 : select((index - 1 + memberCount()) % memberCount());
    }

    /**
     * Move the cursor to {@code target} if it is in range.
     *
     * @param target member index to move to
     * @return the member index now under the cursor, or {@code -1} when the index is out of range
     */
    public int select(int target) {
        if (target < 0 || target >= memberCount()) {
            return -1;
        }
        index = target;
        return index;
    }

    /**
     * Move the cursor onto the member loading this path.
     *
     * @param memberPath absolute mesh path to look for
     * @return the member index now under the cursor, or {@code -1} when no member has that path
     */
    public int selectPath(String memberPath) {
        return select(indexOfPath(memberPath));
    }

    /**
     * Find a member by its mesh path.
     *
     * @param memberPath absolute mesh path to look for
     * @return the member index, or {@code -1} when no member has that path
     */
    public int indexOfPath(String memberPath) {
        for (int member = 0; member < memberCount(); member++) {
            if (members[member].path.equals(memberPath)) {
                return member;
            }
        }
        return -1;
    }

    /**
     * Find a member by name.
     *
     * @param memberName file stem naming the member
     * @return the member index, or {@code -1} when the collection has no member of that name
     */
    public int indexOfName(String memberName) {
        for (int member = 0; member < memberCount(); member++) {
            if (members[member].displayName.equals(memberName)) {
                return member;
            }
        }
        return -1;
    }
}
