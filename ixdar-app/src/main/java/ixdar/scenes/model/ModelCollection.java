package ixdar.scenes.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.platform.json.JsonValue;

/**
 * A directory of scans treated as one named group: its members held as parallel arrays sorted by
 * name, a cursor for browsing them, the {@code collection.dsl} manifest recording membership and
 * keep/reject, and a bounded cache of the members parsed so far.
 */
public final class ModelCollection {

    /** File name of the manifest written for every collection. */
    public static final String MANIFEST_NAME = "collection.dsl";

    /** Staging subdirectory holding manifests for scan directories that cannot be written to. */
    public static final String MANIFEST_FALLBACK_DIR = "collections";

    /** Members held parsed at once, whatever the collection's size; a scan is tens of megabytes. */
    public static final int MAX_CACHED_MEMBERS = 4;

    /** Count value meaning a member has not been loaded yet, so its size is unknown. */
    public static final int UNCOUNTED = -1;

    /** Rendering of an {@link #UNCOUNTED} count in {@link #countSummary}. */
    public static final String UNKNOWN_COUNT = "?";

    /** Collection name: the scanned directory's own name. */
    public final String name;

    /** Directory the members were scanned from. */
    public final Path directory;

    /** Where {@link CollectionManifest} reads and writes this collection's manifest. */
    public final Path manifestPath;

    /** Member names, the mesh files' stems, sorted; the index into every other member array. */
    public final String[] memberNames;

    /** Absolute mesh path per member. */
    public final String[] memberPaths;

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
     */
    public final Map<String, ArrayMesh> parsedMeshes = new LinkedHashMap<>();

    private int index;

    /**
     * Bind a scanned directory to its members and manifest location.
     *
     * @param name collection name, normally the directory's own name
     * @param directory directory the members were scanned from
     * @param manifestPath file the manifest is read from and written to
     * @param memberNames member names in sorted order
     * @param memberPaths absolute mesh path of each member, in the same order
     * @param memberSettings parsed sidecar of each member, {@code null} entries where absent
     */
    public ModelCollection(String name, Path directory, Path manifestPath, String[] memberNames,
            String[] memberPaths, JsonValue[] memberSettings) {
        this.name = name;
        this.directory = directory;
        this.manifestPath = manifestPath;
        this.memberNames = memberNames;
        this.memberPaths = memberPaths;
        this.memberSettings = memberSettings;
        this.memberKeep = new boolean[memberNames.length];
        this.memberVertexCount = new int[memberNames.length];
        this.memberTriangleCount = new int[memberNames.length];
        for (int member = 0; member < memberNames.length; member++) {
            memberKeep[member] = true;
            memberVertexCount[member] = UNCOUNTED;
            memberTriangleCount[member] = UNCOUNTED;
        }
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
        return memberNames.length;
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
     * The parsed mesh for a member path, reading and caching it on a miss.
     *
     * @param memberPath absolute mesh file path of a member
     * @return the parsed mesh
     * @throws IOException if the mesh file cannot be read
     */
    public ArrayMesh loadMesh(String memberPath) throws IOException {
        ArrayMesh cached = parsedMeshes.remove(memberPath);
        if (cached != null) {
            parsedMeshes.put(memberPath, cached);
            return cached;
        }
        ArrayMesh parsed = MeshLoader.load(memberPath);
        cacheMesh(memberPath, parsed);
        return parsed;
    }

    /**
     * Put a parsed mesh at the head of the cache, evicting least-recently-used entries past
     * {@link #meshCacheCapacity}.
     *
     * @param memberPath absolute mesh file path of a member
     * @param parsed mesh to cache
     */
    public void cacheMesh(String memberPath, ArrayMesh parsed) {
        parsedMeshes.remove(memberPath);
        parsedMeshes.put(memberPath, parsed);
        while (parsedMeshes.size() > meshCacheCapacity()) {
            parsedMeshes.remove(parsedMeshes.keySet().iterator().next());
        }
    }

    /**
     * Whether a member's mesh is already parsed and would come back without touching disk.
     *
     * @param memberPath absolute mesh file path of a member
     * @return {@code true} when the mesh is cached
     */
    public boolean isMeshCached(String memberPath) {
        return parsedMeshes.containsKey(memberPath);
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
            if (memberPaths[member].equals(memberPath)) {
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
            if (memberNames[member].equals(memberName)) {
                return member;
            }
        }
        return -1;
    }
}
