package ixdar.geometry.mesh.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.joml.Vector3f;

/**
 * The regions closed ring mark loops cut a surface into, each with its bounding rings and its
 * side of every ring; {@link #SIDE_DISTAL} is a ring's smaller side. An open ring walls nothing.
 * {@link #update} re-floods only the regions a changed ring bounds.
 */
public final class RingRegions {

    public static final String SLOT = "_ring_regions";

    public static final byte SIDE_NONE = -1;

    public static final byte SIDE_DISTAL = 0;

    public static final byte SIDE_PROXIMAL = 1;

    public static final int SIDE_MASK_DISTAL = 1;

    public static final int SIDE_MASK_PROXIMAL = 2;

    public static final String[] SIDE_NAMES = { "distal", "proximal" };

    public static final String TERM_SEPARATOR = ";";

    public static final String REGION_TERM = "region";

    public static final String POINT_TERM = "point";

    public static final String LABEL_SEPARATORS = "[,\\s]+";

    public static final int SLIVER_FACES = 100;

    private static final int PAIR_SHIFT = 32;

    private static final long LOW_HALF = 0xFFFFFFFFL;

    /** Surface the regions partition. */
    public final MeshTopology mesh;

    /** Mark label of each ring, in the order the rings were given. */
    public String[] ringLabels;

    /** Each ring's edge-id-indexed mark mask, as given. */
    public boolean[][] ringMarksByEdgeId;

    /** Whether each ring closed and so walls the flood; an open or empty ring does not. */
    public boolean[] ringIsWall;

    /** Whether each walling ring splits its shell in two; a ring around a handle does not. */
    public boolean[] ringSeparates;

    /**
     * Whether each walling ring has one region on both sides of every edge, so that with all the
     * rings in place it parts no region from another.
     */
    public boolean[] ringSplitsNothing;

    /** Marked edge count of each ring on this surface. */
    public int[] ringEdgeCount;

    /** Marked edges of each ring on this surface, ascending edge id. */
    public int[][] ringEdgeIds = new int[0][];

    /**
     * Regions with fewer faces than this are merged into their largest neighbour; 0 keeps every
     * region the flood makes. Takes effect on the next {@link #build} or {@link #update}.
     */
    public int absorbBelowFaces;

    /** Region of each face, by dense face index. */
    public int[] regionByActiveFace = new int[0];

    /** Number of regions. */
    public int regionCount;

    /** Face count of each region. */
    public int[] regionFaceCount = new int[0];

    /** Surface area of each region. */
    public double[] regionArea = new double[0];

    /** Rings whose marked edges border each region, ascending ring index. */
    public int[][] boundingRingsByRegion = new int[0][];

    /** Regions across a walling ring edge from each region, ascending. */
    public int[][] neighboursByRegion = new int[0][];

    /**
     * Side of each separating ring each region lies on, indexed {@code [ring][region]}:
     * {@link #SIDE_DISTAL}, {@link #SIDE_PROXIMAL}, or {@link #SIDE_NONE} for a ring that does not
     * separate or a region on another shell.
     */
    public byte[][] sideByRingRegion = new byte[0][];

    /**
     * Region the previous build or update gave each region's lowest face, or
     * {@link MeshTopology#NONE} after a build.
     */
    public int[] formerRegionByRegion = new int[0];

    /** Whether each region holds exactly the faces of its former region. */
    public boolean[] regionKeptFaces = new boolean[0];

    /** Faces the last build or update flooded: the whole surface on a build. */
    public int refloodedFaces;

    /** Problems found, one line each, naming the ring: open, empty, crossing, non-separating. */
    public final List<String> problems = new ArrayList<>();

    private boolean built;

    private int[] activeFaceByFaceId = new int[0];

    private double[] faceArea = new double[0];

    private int[] slotStart = new int[0];

    private int[] slotEdgeId = new int[0];

    private int[] slotNeighbour = new int[0];

    private boolean[] edgeActive = new boolean[0];

    private int[] wallsByEdgeId = new int[0];

    private int[] edgeStamp = new int[0];

    private int edgeEpoch;

    private int[] vertexDegree = new int[0];

    private int[] vertexStamp = new int[0];

    private int vertexEpoch;

    private int[] frontier = new int[0];

    private int[] componentByActiveFace = new int[0];

    private int componentCount;

    private int[][] ringVertexIds = new int[0][];

    private int[] ringLooseEnds = new int[0];

    private int[] ringFirstLooseEnd = new int[0];

    private int[] ringJunctions = new int[0];

    /**
     * Stores the surface and rings whose regions {@link #build} computes.
     *
     * @param mesh              surface to partition
     * @param ringLabels        one label per ring, used to name it in reports and queries
     * @param ringMarksByEdgeId one edge-id-indexed mask per ring, parallel to {@code ringLabels}
     * @throws IllegalArgumentException when the label and mask counts differ
     */
    public RingRegions(MeshTopology mesh, String[] ringLabels, boolean[][] ringMarksByEdgeId) {
        if (ringLabels.length != ringMarksByEdgeId.length) {
            throw new IllegalArgumentException(ringLabels.length + " ring labels for "
                    + ringMarksByEdgeId.length + " ring masks");
        }
        this.mesh = mesh;
        this.ringLabels = ringLabels;
        this.ringMarksByEdgeId = ringMarksByEdgeId;
        this.ringIsWall = new boolean[ringLabels.length];
        this.ringSeparates = new boolean[ringLabels.length];
        this.ringSplitsNothing = new boolean[ringLabels.length];
        this.ringEdgeCount = new int[ringLabels.length];
    }

    /**
     * The regions of a bundle's surface cut by the boolean edge marks it carries.
     *
     * @param bundle bundle holding the surface and the {@link EdgeMarks#SLOT} masks
     * @param labels comma-separated mark labels to use as rings; blank takes every boolean label
     * @throws IllegalArgumentException when a named label is missing or not a boolean mask
     * @return the built regions
     */
    public static RingRegions ofBundle(GeometryBundle bundle, String labels) {
        List<String> names = new ArrayList<>();
        List<boolean[]> masks = new ArrayList<>();
        if (labels == null || labels.isBlank()) {
            if (bundle.slots().get(EdgeMarks.SLOT) instanceof Map<?, ?> marks) {
                for (Map.Entry<?, ?> entry : marks.entrySet()) {
                    if (entry.getValue() instanceof boolean[] mask) {
                        names.add(String.valueOf(entry.getKey()));
                        masks.add(mask);
                    }
                }
            }
        } else {
            for (String raw : labels.split(LABEL_SEPARATORS)) {
                String label = raw.strip();
                if (label.isEmpty()) {
                    continue;
                }
                boolean[] mask = EdgeMarks.bools(bundle, label);
                if (mask == null) {
                    throw new IllegalArgumentException("ring_regions: the bundle carries no edge "
                            + "marks labelled " + label);
                }
                names.add(label);
                masks.add(mask);
            }
        }
        return new RingRegions(bundle.mesh(), names.toArray(new String[0]),
                masks.toArray(new boolean[0][])).build();
    }

    /**
     * Floods the whole surface between the closed rings, as if no regions had been built before.
     *
     * @return this, populated
     */
    public RingRegions build() {
        built = false;
        return update(ringLabels, ringMarksByEdgeId);
    }

    /**
     * Moves to a new ring set: a ring whose mask is the same array as before, or equal under the
     * same label, keeps its classification, and only regions next to an edge that gained or lost
     * its wall are re-flooded. Masks must not change in place once given.
     *
     * @param labels one label per ring
     * @param masks  one edge-id-indexed mask per ring, parallel to {@code labels}
     * @throws IllegalArgumentException when the label and mask counts differ
     * @return this, holding exactly what a fresh build of the new rings holds
     */
    public RingRegions update(String[] labels, boolean[][] masks) {
        if (labels.length != masks.length) {
            throw new IllegalArgumentException(labels.length + " ring labels for "
                    + masks.length + " ring masks");
        }
        int faceCount = mesh.faceCount();
        int edgeIdCeiling = RingBundle.edgeIdCeiling(mesh);
        if (!built) {
            int faceIdCeiling = 0;
            int slots = 0;
            for (int activeFace = 0; activeFace < faceCount; activeFace++) {
                int faceId = mesh.faceIdAt(activeFace);
                faceIdCeiling = Math.max(faceIdCeiling, faceId + 1);
                slots += mesh.faceHalfEdgeCount(faceId);
            }
            int vertexIdCeiling = 0;
            for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
                vertexIdCeiling = Math.max(vertexIdCeiling, mesh.vertexIdAt(activeVertex) + 1);
            }
            activeFaceByFaceId = new int[faceIdCeiling];
            Arrays.fill(activeFaceByFaceId, MeshTopology.NONE);
            faceArea = new double[faceCount];
            Vector3f anchor = new Vector3f();
            Vector3f first = new Vector3f();
            Vector3f second = new Vector3f();
            for (int activeFace = 0; activeFace < faceCount; activeFace++) {
                int faceId = mesh.faceIdAt(activeFace);
                activeFaceByFaceId[faceId] = activeFace;
                mesh.vertexPosition(mesh.faceVertexAt(faceId, 0), anchor);
                for (int corner = 2; corner < mesh.faceVertexCount(faceId); corner++) {
                    mesh.vertexPosition(mesh.faceVertexAt(faceId, corner - 1), first).sub(anchor);
                    mesh.vertexPosition(mesh.faceVertexAt(faceId, corner), second).sub(anchor);
                    faceArea[activeFace] += 0.5 * first.cross(second).length();
                }
            }
            // Each face's sides as flat slots: the edge crossed and the face across it, so the
            // floods below never go back through the half-edge accessors.
            slotStart = new int[faceCount + 1];
            slotEdgeId = new int[slots];
            slotNeighbour = new int[slots];
            int slot = 0;
            for (int activeFace = 0; activeFace < faceCount; activeFace++) {
                int faceId = mesh.faceIdAt(activeFace);
                slotStart[activeFace] = slot;
                for (int side = 0; side < mesh.faceHalfEdgeCount(faceId); side++) {
                    int halfEdge = mesh.faceHalfEdgeAt(faceId, side);
                    int twin = mesh.halfEdgeTwin(halfEdge);
                    slotEdgeId[slot] = mesh.halfEdgeEdge(halfEdge);
                    slotNeighbour[slot++] = twin == MeshTopology.NONE ? MeshTopology.NONE
                            : activeFaceOf(mesh.halfEdgeFace(twin));
                }
            }
            slotStart[faceCount] = slot;
            edgeActive = new boolean[edgeIdCeiling];
            for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
                edgeActive[mesh.edgeIdAt(activeEdge)] = true;
            }
            wallsByEdgeId = new int[edgeIdCeiling];
            edgeStamp = new int[edgeIdCeiling];
            edgeEpoch = 0;
            vertexDegree = new int[vertexIdCeiling];
            vertexStamp = new int[vertexIdCeiling];
            vertexEpoch = 0;
            frontier = new int[faceCount];
            componentByActiveFace = new int[faceCount];
            componentCount = 0;
            regionByActiveFace = new int[0];
        }

        // Classify each ring, reusing the classification of a ring that did not change.
        int ringCount = labels.length;
        int formerRings = built ? ringLabels.length : 0;
        boolean[] formerClaimed = new boolean[formerRings];
        int[] formerRingOf = new int[ringCount];
        boolean[] isWall = new boolean[ringCount];
        int[] edgeCount = new int[ringCount];
        int[][] edgeIds = new int[ringCount][];
        int[][] vertexIds = new int[ringCount][];
        int[] looseEnds = new int[ringCount];
        int[] firstLooseEnd = new int[ringCount];
        int[] junctions = new int[ringCount];
        for (int ring = 0; ring < ringCount; ring++) {
            formerRingOf[ring] = MeshTopology.NONE;
            for (int former = 0; former < formerRings && formerRingOf[ring] < 0; former++) {
                if (!formerClaimed[former] && (ringMarksByEdgeId[former] == masks[ring]
                        || ringLabels[former].equals(labels[ring])
                                && Arrays.equals(ringMarksByEdgeId[former], masks[ring]))) {
                    formerClaimed[former] = true;
                    formerRingOf[ring] = former;
                }
            }
            int former = formerRingOf[ring];
            if (former != MeshTopology.NONE) {
                isWall[ring] = ringIsWall[former];
                edgeCount[ring] = ringEdgeCount[former];
                edgeIds[ring] = ringEdgeIds[former];
                vertexIds[ring] = ringVertexIds[former];
                looseEnds[ring] = ringLooseEnds[former];
                firstLooseEnd[ring] = ringFirstLooseEnd[former];
                junctions[ring] = ringJunctions[former];
                continue;
            }
            boolean[] marks = masks[ring];
            int scanned = Math.min(marks.length, edgeIdCeiling);
            int edges = 0;
            for (int edgeId = 0; edgeId < scanned; edgeId++) {
                edges += marks[edgeId] && edgeActive[edgeId] ? 1 : 0;
            }
            int[] ringEdges = new int[edges];
            int[] ringVertices = new int[2 * edges];
            int vertices = 0;
            int cursor = 0;
            for (int edgeId = 0; edgeId < scanned; edgeId++) {
                if (!marks[edgeId] || !edgeActive[edgeId]) {
                    continue;
                }
                ringEdges[cursor++] = edgeId;
                int halfEdge = mesh.edgeHalfEdge(edgeId);
                for (int end = 0; end < 2; end++) {
                    int vertexId = end == 0 ? mesh.halfEdgeVertex(halfEdge)
                            : mesh.halfEdgeEndVertex(halfEdge);
                    if (vertexDegree[vertexId]++ == 0) {
                        ringVertices[vertices++] = vertexId;
                    }
                }
            }
            // A ring walls the flood only when no vertex on it has a single ring edge: a loose end
            // means the loop never closed, and walling it would only scar the region it lies in.
            firstLooseEnd[ring] = MeshTopology.NONE;
            for (int index = 0; index < vertices; index++) {
                int vertexId = ringVertices[index];
                if (vertexDegree[vertexId] == 1) {
                    looseEnds[ring]++;
                    firstLooseEnd[ring] = firstLooseEnd[ring] == MeshTopology.NONE ? vertexId
                            : Math.min(firstLooseEnd[ring], vertexId);
                } else if (vertexDegree[vertexId] > 2) {
                    junctions[ring]++;
                }
                vertexDegree[vertexId] = 0;
            }
            edgeCount[ring] = edges;
            edgeIds[ring] = ringEdges;
            vertexIds[ring] = Arrays.copyOf(ringVertices, vertices);
            isWall[ring] = edges > 0 && looseEnds[ring] == 0;
        }

        // Count each edge's walling rings; an edge whose count crosses zero changed its wall.
        int[] changedEdges = new int[0];
        int changed = 0;
        for (int pass = 0; pass < 2; pass++) {
            int rings = pass == 0 ? formerRings : ringCount;
            for (int ring = 0; ring < rings; ring++) {
                boolean leaving = pass == 0 && !formerClaimed[ring] && ringIsWall[ring];
                boolean arriving = pass == 1 && formerRingOf[ring] == MeshTopology.NONE
                        && isWall[ring];
                if (!leaving && !arriving) {
                    continue;
                }
                int[] walls = leaving ? ringEdgeIds[ring] : edgeIds[ring];
                if (changedEdges.length < changed + walls.length) {
                    changedEdges = Arrays.copyOf(changedEdges,
                            Math.max(2 * changedEdges.length, changed + walls.length));
                }
                for (int edgeId : walls) {
                    wallsByEdgeId[edgeId] += leaving ? -1 : 1;
                    if (wallsByEdgeId[edgeId] == (leaving ? 0 : 1)) {
                        changedEdges[changed++] = edgeId;
                    }
                }
            }
        }

        // Clear the components beside a changed edge and flood them again; every other component
        // keeps its faces, since each of its borders is still a wall and nothing inside became one.
        int[] formerRegionByActiveFace = regionByActiveFace;
        int[] formerRegionFaceCount = regionFaceCount;
        refloodedFaces = 0;
        if (!built || changed > 0) {
            if (!built) {
                Arrays.fill(componentByActiveFace, MeshTopology.NONE);
            } else {
                boolean[] cleared = new boolean[componentCount];
                for (int index = 0; index < changed; index++) {
                    for (int side = 0; side < 2; side++) {
                        int activeFace = activeFaceOf(mesh.edgeFace(changedEdges[index], side));
                        if (activeFace != MeshTopology.NONE) {
                            cleared[componentByActiveFace[activeFace]] = true;
                        }
                    }
                }
                for (int activeFace = 0; activeFace < faceCount; activeFace++) {
                    if (cleared[componentByActiveFace[activeFace]]) {
                        componentByActiveFace[activeFace] = MeshTopology.NONE;
                    }
                }
            }
            int nextComponent = componentCount;
            for (int seed = 0; seed < faceCount; seed++) {
                if (componentByActiveFace[seed] != MeshTopology.NONE) {
                    continue;
                }
                int head = 0;
                int tail = 0;
                frontier[tail++] = seed;
                componentByActiveFace[seed] = nextComponent;
                while (head < tail) {
                    int activeFace = frontier[head++];
                    for (int slot = slotStart[activeFace]; slot < slotStart[activeFace + 1];
                            slot++) {
                        int neighbour = slotNeighbour[slot];
                        if (neighbour == MeshTopology.NONE || wallsByEdgeId[slotEdgeId[slot]] > 0
                                || componentByActiveFace[neighbour] != MeshTopology.NONE) {
                            continue;
                        }
                        componentByActiveFace[neighbour] = nextComponent;
                        frontier[tail++] = neighbour;
                    }
                }
                refloodedFaces += tail;
                nextComponent++;
            }
            // Number components in order of their lowest face, as a single flood would.
            int[] canonical = new int[nextComponent];
            Arrays.fill(canonical, MeshTopology.NONE);
            componentCount = 0;
            for (int activeFace = 0; activeFace < faceCount; activeFace++) {
                int component = componentByActiveFace[activeFace];
                if (canonical[component] == MeshTopology.NONE) {
                    canonical[component] = componentCount++;
                }
                componentByActiveFace[activeFace] = canonical[component];
            }
        }
        int[] componentFaces = new int[componentCount];
        double[] componentArea = new double[componentCount];
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            componentFaces[componentByActiveFace[activeFace]]++;
            componentArea[componentByActiveFace[activeFace]] += faceArea[activeFace];
        }

        // Every walling edge once, with the components on its two sides.
        edgeEpoch++;
        int wallEdges = 0;
        for (int ring = 0; ring < ringCount; ring++) {
            for (int edgeId : isWall[ring] ? edgeIds[ring] : new int[0]) {
                if (edgeStamp[edgeId] != edgeEpoch) {
                    edgeStamp[edgeId] = edgeEpoch;
                    wallEdges++;
                }
            }
        }
        int[] wallEdgeIds = new int[wallEdges];
        int[] wallLeftComponent = new int[wallEdges];
        int[] wallRightComponent = new int[wallEdges];
        edgeEpoch++;
        int wallCursor = 0;
        for (int ring = 0; ring < ringCount; ring++) {
            for (int edgeId : isWall[ring] ? edgeIds[ring] : new int[0]) {
                if (edgeStamp[edgeId] == edgeEpoch) {
                    continue;
                }
                edgeStamp[edgeId] = edgeEpoch;
                int leftFace = activeFaceOf(mesh.edgeFace(edgeId, 0));
                int rightFace = activeFaceOf(mesh.edgeFace(edgeId, 1));
                wallEdgeIds[wallCursor] = edgeId;
                wallLeftComponent[wallCursor] = leftFace == MeshTopology.NONE ? MeshTopology.NONE
                        : componentByActiveFace[leftFace];
                wallRightComponent[wallCursor++] = rightFace == MeshTopology.NONE
                        ? MeshTopology.NONE : componentByActiveFace[rightFace];
            }
        }

        // Regions are the components, or with absorption each sliver joined to its largest
        // neighbour, numbered by their lowest face.
        int[] parent = new int[componentCount];
        for (int component = 0; component < componentCount; component++) {
            parent[component] = component;
        }
        if (absorbBelowFaces > 0) {
            int[] largestNeighbour = new int[componentCount];
            Arrays.fill(largestNeighbour, MeshTopology.NONE);
            for (int wall = 0; wall < wallEdges; wall++) {
                for (int side = 0; side < 2; side++) {
                    int sliver = side == 0 ? wallLeftComponent[wall] : wallRightComponent[wall];
                    int across = side == 0 ? wallRightComponent[wall] : wallLeftComponent[wall];
                    if (sliver == MeshTopology.NONE || across == MeshTopology.NONE
                            || sliver == across || componentFaces[sliver] >= absorbBelowFaces) {
                        continue;
                    }
                    int best = largestNeighbour[sliver];
                    if (best == MeshTopology.NONE || componentFaces[across] > componentFaces[best]
                            || componentFaces[across] == componentFaces[best] && across < best) {
                        largestNeighbour[sliver] = across;
                    }
                }
            }
            for (int component = 0; component < componentCount; component++) {
                if (largestNeighbour[component] != MeshTopology.NONE) {
                    parent[root(parent, component)] = root(parent, largestNeighbour[component]);
                }
            }
        }
        int[] regionOfComponent = new int[componentCount];
        int[] regionOfRoot = new int[componentCount];
        Arrays.fill(regionOfRoot, MeshTopology.NONE);
        regionCount = 0;
        for (int component = 0; component < componentCount; component++) {
            int root = root(parent, component);
            if (regionOfRoot[root] == MeshTopology.NONE) {
                regionOfRoot[root] = regionCount++;
            }
            regionOfComponent[component] = regionOfRoot[root];
        }
        regionByActiveFace = new int[faceCount];
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            regionByActiveFace[activeFace] = regionOfComponent[componentByActiveFace[activeFace]];
        }
        regionFaceCount = new int[regionCount];
        regionArea = new double[regionCount];
        int[] largestComponent = new int[regionCount];
        Arrays.fill(largestComponent, MeshTopology.NONE);
        for (int component = 0; component < componentCount; component++) {
            int region = regionOfComponent[component];
            regionFaceCount[region] += componentFaces[component];
            regionArea[region] += componentArea[component];
            if (largestComponent[region] == MeshTopology.NONE
                    || componentFaces[component] > componentFaces[largestComponent[region]]) {
                largestComponent[region] = component;
            }
        }

        // Each region's former region is the one its lowest face was in; it kept its faces when
        // every face came from there and the counts agree.
        formerRegionByRegion = new int[regionCount];
        Arrays.fill(formerRegionByRegion, MeshTopology.NONE);
        regionKeptFaces = new boolean[regionCount];
        if (formerRegionByActiveFace.length == faceCount) {
            boolean[] mixed = new boolean[regionCount];
            boolean[] seen = new boolean[regionCount];
            for (int activeFace = 0; activeFace < faceCount; activeFace++) {
                int region = regionByActiveFace[activeFace];
                if (!seen[region]) {
                    seen[region] = true;
                    formerRegionByRegion[region] = formerRegionByActiveFace[activeFace];
                }
                mixed[region] |= formerRegionByActiveFace[activeFace]
                        != formerRegionByRegion[region];
            }
            for (int region = 0; region < regionCount; region++) {
                regionKeptFaces[region] = !mixed[region] && regionFaceCount[region]
                        == formerRegionFaceCount[formerRegionByRegion[region]];
            }
        }

        // Neighbours, bounding rings, and the rings with one region on both sides.
        long[] pairs = new long[wallEdges];
        int pairCount = 0;
        for (int wall = 0; wall < wallEdges; wall++) {
            if (wallLeftComponent[wall] == MeshTopology.NONE
                    || wallRightComponent[wall] == MeshTopology.NONE) {
                continue;
            }
            int left = regionOfComponent[wallLeftComponent[wall]];
            int right = regionOfComponent[wallRightComponent[wall]];
            if (left != right) {
                pairs[pairCount++] = (long) Math.min(left, right) << PAIR_SHIFT
                        | Math.max(left, right);
            }
        }
        Arrays.sort(pairs, 0, pairCount);
        int[] neighbourTotal = new int[regionCount];
        for (int pair = 0; pair < pairCount; pair++) {
            if (pair == 0 || pairs[pair] != pairs[pair - 1]) {
                neighbourTotal[(int) (pairs[pair] >>> PAIR_SHIFT)]++;
                neighbourTotal[(int) (pairs[pair] & LOW_HALF)]++;
            }
        }
        neighboursByRegion = new int[regionCount][];
        for (int region = 0; region < regionCount; region++) {
            neighboursByRegion[region] = new int[neighbourTotal[region]];
            neighbourTotal[region] = 0;
        }
        for (int pair = 0; pair < pairCount; pair++) {
            if (pair == 0 || pairs[pair] != pairs[pair - 1]) {
                int low = (int) (pairs[pair] >>> PAIR_SHIFT);
                int high = (int) (pairs[pair] & LOW_HALF);
                neighboursByRegion[low][neighbourTotal[low]++] = high;
                neighboursByRegion[high][neighbourTotal[high]++] = low;
            }
        }
        for (int[] neighbours : neighboursByRegion) {
            Arrays.sort(neighbours);
        }
        boolean[][] boundedByRegionRing = new boolean[regionCount][ringCount];
        boolean[] splitsNothing = new boolean[ringCount];
        for (int ring = 0; ring < ringCount; ring++) {
            int twoSided = 0;
            int oneRegion = 0;
            for (int edgeId : isWall[ring] ? edgeIds[ring] : new int[0]) {
                int leftFace = activeFaceOf(mesh.edgeFace(edgeId, 0));
                int rightFace = activeFaceOf(mesh.edgeFace(edgeId, 1));
                for (int activeFace : new int[] { leftFace, rightFace }) {
                    if (activeFace != MeshTopology.NONE) {
                        boundedByRegionRing[regionByActiveFace[activeFace]][ring] = true;
                    }
                }
                if (leftFace != MeshTopology.NONE && rightFace != MeshTopology.NONE) {
                    twoSided++;
                    oneRegion += regionByActiveFace[leftFace] == regionByActiveFace[rightFace]
                            ? 1 : 0;
                }
            }
            splitsNothing[ring] = isWall[ring] && twoSided > 0 && oneRegion == twoSided;
        }
        boundingRingsByRegion = new int[regionCount][];
        for (int region = 0; region < regionCount; region++) {
            int bounding = 0;
            for (int ring = 0; ring < ringCount; ring++) {
                bounding += boundedByRegionRing[region][ring] ? 1 : 0;
            }
            boundingRingsByRegion[region] = new int[bounding];
            int cursor = 0;
            for (int ring = 0; ring < ringCount; ring++) {
                if (boundedByRegionRing[region][ring]) {
                    boundingRingsByRegion[region][cursor++] = ring;
                }
            }
        }

        ringLabels = labels;
        ringMarksByEdgeId = masks;
        ringIsWall = isWall;
        ringEdgeCount = edgeCount;
        ringEdgeIds = edgeIds;
        ringVertexIds = vertexIds;
        ringLooseEnds = looseEnds;
        ringFirstLooseEnd = firstLooseEnd;
        ringJunctions = junctions;
        ringSplitsNothing = splitsNothing;
        ringSeparates = new boolean[ringCount];
        built = true;

        problems.clear();
        Vector3f position = new Vector3f();
        for (int ring = 0; ring < ringCount; ring++) {
            if (edgeCount[ring] == 0) {
                problems.add(labels[ring] + " marks no edge of this surface");
            } else if (looseEnds[ring] > 0) {
                mesh.vertexPosition(firstLooseEnd[ring], position);
                problems.add(String.format(Locale.ROOT,
                        "%s is open: %d loose end(s), the first at %.4f,%.4f,%.4f; it walls "
                                + "nothing",
                        labels[ring], looseEnds[ring], position.x, position.y, position.z));
            } else if (junctions[ring] > 0) {
                problems.add(labels[ring] + " meets itself at " + junctions[ring]
                        + " vertex(es); it still walls, but is not one simple loop");
            }
        }

        // A ring's sides are the pieces the surface falls into with only that ring as a wall:
        // components joined across every other walling edge, the flood each ring alone would make.
        sideByRingRegion = new byte[ringCount][regionCount];
        int[] pieceOf = new int[componentCount];
        double[] pieceArea = new double[componentCount];
        boolean[] adjacent = new boolean[componentCount];
        for (int ring = 0; ring < ringCount; ring++) {
            Arrays.fill(sideByRingRegion[ring], SIDE_NONE);
            if (!isWall[ring]) {
                continue;
            }
            boolean[] marks = masks[ring];
            for (int component = 0; component < componentCount; component++) {
                pieceOf[component] = component;
            }
            for (int wall = 0; wall < wallEdges; wall++) {
                int edgeId = wallEdgeIds[wall];
                if (edgeId < marks.length && marks[edgeId]
                        || wallLeftComponent[wall] == MeshTopology.NONE
                        || wallRightComponent[wall] == MeshTopology.NONE) {
                    continue;
                }
                pieceOf[root(pieceOf, wallLeftComponent[wall])] =
                        root(pieceOf, wallRightComponent[wall]);
            }
            Arrays.fill(pieceArea, 0.0);
            Arrays.fill(adjacent, false);
            for (int component = 0; component < componentCount; component++) {
                pieceOf[component] = root(pieceOf, component);
                pieceArea[pieceOf[component]] += componentArea[component];
            }
            int sameSideEdges = 0;
            for (int edgeId : edgeIds[ring]) {
                int leftFace = activeFaceOf(mesh.edgeFace(edgeId, 0));
                int rightFace = activeFaceOf(mesh.edgeFace(edgeId, 1));
                for (int activeFace : new int[] { leftFace, rightFace }) {
                    if (activeFace != MeshTopology.NONE) {
                        adjacent[pieceOf[componentByActiveFace[activeFace]]] = true;
                    }
                }
                if (leftFace != MeshTopology.NONE && rightFace != MeshTopology.NONE
                        && pieceOf[componentByActiveFace[leftFace]]
                                == pieceOf[componentByActiveFace[rightFace]]) {
                    sameSideEdges++;
                }
            }
            // The ring's two sides are the two largest pieces it borders; any further piece is a
            // pocket its edges enclose on their own, such as a face a jagged snap walled off.
            // Pieces are visited in order of their lowest face, as the single flood numbers them.
            int firstSide = MeshTopology.NONE;
            int secondSide = MeshTopology.NONE;
            int pockets = 0;
            for (int component = 0; component < componentCount; component++) {
                int piece = pieceOf[component];
                if (!adjacent[piece]) {
                    continue;
                }
                adjacent[piece] = false;
                pockets++;
                if (firstSide == MeshTopology.NONE || pieceArea[piece] > pieceArea[firstSide]) {
                    secondSide = firstSide;
                    firstSide = piece;
                } else if (secondSide == MeshTopology.NONE
                        || pieceArea[piece] > pieceArea[secondSide]) {
                    secondSide = piece;
                }
            }
            pockets = Math.max(0, pockets - 2);
            boolean separates = sameSideEdges == 0 && secondSide != MeshTopology.NONE;
            ringSeparates[ring] = separates;
            if (!separates) {
                problems.add(labels[ring] + " does not separate the surface: " + sameSideEdges
                        + " of its " + edgeCount[ring] + " edges have the same piece on both "
                        + "sides (it loops a handle), so it bounds regions but has no distal side");
                continue;
            }
            if (pockets > 0) {
                problems.add(labels[ring] + " also walls off " + pockets + " stray pocket(s) "
                        + "beside its loop; their faces lie on neither side");
            }
            int distal = pieceArea[firstSide] <= pieceArea[secondSide] ? firstSide : secondSide;
            int proximal = distal == firstSide ? secondSide : firstSide;
            for (int region = 0; region < regionCount; region++) {
                int piece = pieceOf[largestComponent[region]];
                sideByRingRegion[ring][region] = piece == distal ? SIDE_DISTAL
                        : piece == proximal ? SIDE_PROXIMAL : SIDE_NONE;
            }
        }

        boolean[][] crossed = new boolean[ringCount][ringCount];
        for (int ring = 0; ring < ringCount; ring++) {
            for (int other = 0; ringSeparates[ring] && other < ringCount; other++) {
                if (other == ring || !isWall[other] || ringSeparates[other] && other < ring) {
                    continue;
                }
                if (sidesReached(other, ring) == (SIDE_MASK_DISTAL | SIDE_MASK_PROXIMAL)) {
                    problems.add(labels[ring] + " and " + labels[other] + " cross");
                    crossed[ring][other] = true;
                    crossed[other][ring] = true;
                }
            }
        }
        // Rings that touch without crossing still pinch slivers of a face or two between them.
        for (int ring = 0; ring < ringCount; ring++) {
            int stamp = ++vertexEpoch;
            for (int vertexId : isWall[ring] ? vertexIds[ring] : new int[0]) {
                vertexStamp[vertexId] = stamp;
            }
            for (int other = ring + 1; isWall[ring] && other < ringCount; other++) {
                if (!isWall[other] || crossed[ring][other]) {
                    continue;
                }
                int shared = 0;
                for (int vertexId : vertexIds[other]) {
                    shared += vertexStamp[vertexId] == stamp ? 1 : 0;
                }
                if (shared > 0) {
                    problems.add(labels[ring] + " and " + labels[other]
                            + " touch or cross at " + shared + " vertex(es); slivers between "
                            + "them are regions of their own");
                }
            }
        }
        return this;
    }

    /**
     * The sides of a separating ring another ring's own edges reach; edges the two share do not
     * count, so a ring that only touches reaches one side.
     *
     * @param ring    ring whose edges are walked
     * @param sidesOf separating ring whose sides are asked
     * @return {@link #SIDE_MASK_DISTAL} and {@link #SIDE_MASK_PROXIMAL} or-ed, 0 for neither
     */
    public int sidesReached(int ring, int sidesOf) {
        boolean[] cutting = ringMarksByEdgeId[sidesOf];
        int reached = 0;
        for (int edgeId : ringEdgeIds[ring]) {
            if (edgeId < cutting.length && cutting[edgeId]) {
                continue;
            }
            int activeFace = activeFaceOf(mesh.edgeFace(edgeId, 0));
            if (activeFace == MeshTopology.NONE) {
                continue;
            }
            byte side = sideByRingRegion[sidesOf][regionByActiveFace[activeFace]];
            reached |= side == SIDE_DISTAL ? SIDE_MASK_DISTAL
                    : side == SIDE_PROXIMAL ? SIDE_MASK_PROXIMAL : 0;
        }
        return reached;
    }

    /**
     * Whether a region is a sliver: fewer than {@link #SLIVER_FACES} faces, which near-parallel
     * or touching rings pinch off.
     *
     * @param region region index
     * @return true for a sliver
     */
    public boolean isSliver(int region) {
        return regionFaceCount[region] < SLIVER_FACES;
    }

    /**
     * The face whose centroid lies nearest a point, the region a surface point names.
     *
     * @param x point x
     * @param y point y
     * @param z point z
     * @return dense index of the nearest face, or {@link MeshTopology#NONE} on an empty surface
     */
    public int nearestActiveFace(float x, float y, float z) {
        int nearest = MeshTopology.NONE;
        double nearestDistance = Double.POSITIVE_INFINITY;
        Vector3f corner = new Vector3f();
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            float sumX = 0f;
            float sumY = 0f;
            float sumZ = 0f;
            int corners = mesh.faceVertexCount(faceId);
            for (int slot = 0; slot < corners; slot++) {
                mesh.vertexPosition(mesh.faceVertexAt(faceId, slot), corner);
                sumX += corner.x;
                sumY += corner.y;
                sumZ += corner.z;
            }
            double dx = sumX / corners - x;
            double dy = sumY / corners - y;
            double dz = sumZ / corners - z;
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = activeFace;
            }
        }
        return nearest;
    }

    /**
     * The regions a selection query names, the union of its {@code ;}-separated terms:
     * {@code region N} and {@code point x,y,z} (the region holding the nearest face).
     *
     * @param query selection query; blank selects nothing
     * @throws IllegalArgumentException on an unknown term or a region out of range
     * @return one flag per region
     */
    public boolean[] select(String query) {
        boolean[] selected = new boolean[regionCount];
        if (query == null || query.isBlank()) {
            return selected;
        }
        for (String rawTerm : query.split(TERM_SEPARATOR)) {
            String term = rawTerm.strip();
            if (term.isEmpty()) {
                continue;
            }
            int space = term.indexOf(' ');
            String verb = space < 0 ? term : term.substring(0, space);
            String argument = space < 0 ? "" : term.substring(space + 1).strip();
            if (REGION_TERM.equals(verb)) {
                int region = Integer.parseInt(argument);
                if (region < 0 || region >= regionCount) {
                    throw new IllegalArgumentException("ring_regions: region " + region
                            + " is out of range; the rings make " + regionCount);
                }
                selected[region] = true;
            } else if (POINT_TERM.equals(verb)) {
                String[] coordinates = argument.split(LABEL_SEPARATORS);
                if (coordinates.length != 2 + 1) {
                    throw new IllegalArgumentException("ring_regions: point takes x,y,z, not "
                            + argument);
                }
                int activeFace = nearestActiveFace(Float.parseFloat(coordinates[0]),
                        Float.parseFloat(coordinates[1]), Float.parseFloat(coordinates[2]));
                if (activeFace != MeshTopology.NONE) {
                    selected[regionByActiveFace[activeFace]] = true;
                }
            } else {
                throw new IllegalArgumentException("ring_regions: unknown selection term '"
                        + term + "'; use region N or point x,y,z");
            }
        }
        return selected;
    }

    /**
     * Per-face form of a region selection, the mask a face-domain selection input takes.
     *
     * @param selectedRegions one flag per region
     * @return one flag per dense face index
     */
    public boolean[] selectionByActiveFace(boolean[] selectedRegions) {
        boolean[] selection = new boolean[regionByActiveFace.length];
        for (int activeFace = 0; activeFace < selection.length; activeFace++) {
            selection[activeFace] = selectedRegions[regionByActiveFace[activeFace]];
        }
        return selection;
    }

    /**
     * Region of a face given by sparse id.
     *
     * @param faceId face handle
     * @return the region, or {@link MeshTopology#NONE} for a face not on the surface
     */
    public int regionOfFace(int faceId) {
        int activeFace = activeFaceOf(faceId);
        return activeFace == MeshTopology.NONE ? MeshTopology.NONE
                : regionByActiveFace[activeFace];
    }

    /**
     * One region's bounding rings with the side it lies on, e.g.
     * {@code ring_06 (distal), ring_15 (proximal)}.
     *
     * @param region region index
     * @return the rings, comma-separated, or {@code none} for a region no ring bounds
     */
    public String boundingRingText(int region) {
        if (boundingRingsByRegion[region].length == 0) {
            return "none";
        }
        StringBuilder text = new StringBuilder();
        for (int ring : boundingRingsByRegion[region]) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(ringLabels[ring]);
            byte side = sideByRingRegion[ring][region];
            text.append(side == SIDE_NONE ? " (both sides)" : " (" + SIDE_NAMES[side] + ")");
        }
        return text.toString();
    }

    /**
     * The report a reader checks: the region count, one line per region with its face count, area
     * share and bounding rings, then one line per problem.
     *
     * @return the report lines
     */
    public List<String> reportLines() {
        List<String> lines = new ArrayList<>();
        int walls = 0;
        for (boolean wall : ringIsWall) {
            walls += wall ? 1 : 0;
        }
        lines.add(regionCount + " region(s) from " + walls + " closed ring(s) of "
                + ringLabels.length);
        double total = 0;
        for (double area : regionArea) {
            total += area;
        }
        for (int region = 0; region < regionCount; region++) {
            lines.add(String.format(Locale.ROOT, "region %d: %d faces, %.1f%% of the area, "
                    + "bounded by %s", region, regionFaceCount[region],
                    total <= 0 ? 0 : 100.0 * regionArea[region] / total,
                    boundingRingText(region)));
        }
        for (String problem : problems) {
            lines.add("problem: " + problem);
        }
        return lines;
    }

    /**
     * Root of a node in a union-find forest, halving the path on the way.
     *
     * @param parent parent of each node; a root is its own parent
     * @param node   node whose root is asked
     * @return the root
     */
    private static int root(int[] parent, int node) {
        int current = node;
        while (parent[current] != current) {
            parent[current] = parent[parent[current]];
            current = parent[current];
        }
        return current;
    }

    private int activeFaceOf(int faceId) {
        return faceId < 0 || faceId >= activeFaceByFaceId.length ? MeshTopology.NONE
                : activeFaceByFaceId[faceId];
    }
}
