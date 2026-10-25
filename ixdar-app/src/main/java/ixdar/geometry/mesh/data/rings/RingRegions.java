package ixdar.geometry.mesh.data.rings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.bundle.RingBundle;
import ixdar.geometry.mesh.data.paths.query.SurfaceConnection;
import ixdar.geometry.mesh.data.paths.query.SurfaceCreases;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.TriangulationRule;
import ixdar.graphics.render.color.Palette;
import ixdar.graphics.render.color.RegionColouring;
import ixdar.numeric.graph.UnionFind;


/**
 * The regions closed ring mark loops cut a surface into, each with its bounding rings and its
 * side of every ring; {@link #SIDE_DISTAL} is a ring's smaller side. An open ring walls only as a
 * bisector, its loose ends on other walls.
 * {@link #update} re-floods only the regions a changed ring bounds.
 */
public final class RingRegions {

    public static final String SLOT = "_ring_regions";

    public static final byte SIDE_NONE = -1;

    public static final byte SIDE_DISTAL = 0;

    public static final byte SIDE_PROXIMAL = 1;

    public static final int TRIANGLE_CORNERS = 3;

    public static final int SIDE_MASK_DISTAL = 1;

    public static final int SIDE_MASK_PROXIMAL = 2;

    public static final String[] SIDE_NAMES = { "distal", "proximal" };

    public static final String TERM_SEPARATOR = ";";

    public static final String REGION_TERM = "region";

    public static final String POINT_TERM = "point";

    public static final String LABEL_SEPARATORS = "[,\\s]+";

    public static final int SLIVER_FACES = 100;

    public static final String SPLITS_NOTHING_NOTE = "splits nothing";

    // A face lies near its region's rings within this share of the region's depth, the face steps
    // from its rings to its farthest face: a joint band a ring splits stays within a third of the
    // way into each half, while one left unsplit sits deepest in the merged region.
    public static final double NEAR_RING_DEPTH_SHARE = 1.0 / 3;

    // A face this many face steps from a ring or fewer is near it in any region: about three mean
    // edges, the radius SurfaceCreases diffuses its signal over, so a groove a ring runs in counts
    // as on the ring.
    public static final int NEAR_RING_FACE_STEPS = 6;

    // Interior crease under this share of a region is surface texture, not a joint: on IMG_4111
    // the halves of a split claw joint read under 1%, the region left unsplit about 8%.
    public static final double INTERIOR_JOINT_SHARE = 0.02;

    private static final int PAIR_SHIFT = 32;

    private static final long LOW_HALF = 0xFFFFFFFFL;

    /** Surface the regions partition. */
    public final MeshTopology mesh;

    /** Mark label of each ring, in the order the rings were given. */
    public String[] ringLabels;

    /** Each ring's edge-id-indexed mark mask, as given. */
    public boolean[][] ringMarksByEdgeId;

    /**
     * Whether each ring walls the flood: a closed ring, or a bisector; an empty ring, or an open one with
     * an end off every other wall, does not.
     */
    public boolean[] ringIsWall;

    /** Whether each walling ring splits its shell in two; a ring around a handle does not. */
    public boolean[] ringSeparates;

    /**
     * Whether each walling ring has one region on both sides of every edge, so that with all the
     * rings in place it parts no region from another.
     */
    public boolean[] ringSplitsNothing;

    /**
     * Where each ring that splits nothing stays joined, packed xyz: the middle of the shortest
     * path crossing no ring between the faces either side of one of its edges, a point on the
     * handle the ring cannot cut. NaN for a ring that splits; filled by {@link #measureJoins}.
     */
    public float[] ringJoinPoint = new float[0];

    /** Surface length of the path through each ring's {@link #ringJoinPoint}, 0 where none. */
    public double[] ringJoinPathLength = new double[0];

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

    /** Creases {@link #measureFlatness} measured these regions on; {@code null} until it runs. */
    public SurfaceCreases flatnessCreases;

    /** Share of each region's area whose faces lie in a crease. */
    public double[] regionCreaseShare = new double[0];

    /**
     * Share of each region's area in crease faces far from its rings: beyond
     * {@link #NEAR_RING_DEPTH_SHARE} of its depth and {@link #NEAR_RING_FACE_STEPS}. A joint no ring
     * splits.
     */
    public double[] regionInteriorCreaseShare = new double[0];

    /** Highest face valley curvature in each region, in inverse mesh units. */
    public double[] regionPeakCurvature = new double[0];

    /**
     * Each region's crease point, xyz packed: the most curved face of its largest interior crease
     * patch once interior crease reaches {@link #INTERIOR_JOINT_SHARE}, else of its largest crease
     * patch near its rings, else of any crease, else its most curved face.
     */
    public float[] regionCreasePoint = new float[0];

    /**
     * Whether each region's crease point lies far from its rings, as
     * {@link #regionInteriorCreaseShare} counts it.
     */
    public boolean[] regionCreaseInterior = new boolean[0];

    /**
     * Where each region's label sits, xyz packed: the centroid of its face most face steps from
     * its rings, fixed for the region whatever the camera does.
     */
    public float[] regionLabelPoint = new float[0];

    /** Palette colours of the regions, neighbours always apart, kept across {@link #rebuild}s. */
    public final RegionColouring colouring = new RegionColouring();

    /**
     * The note the ring tool draws beside a ring, by label, as of the last {@link #rebuild}:
     * {@link #SPLITS_NOTHING_NOTE} for a ring with one region on both sides of every edge.
     */
    public Map<String, String> noteByRingLabel = Map.of();

    /** How many regions {@link #isSliver} names, as of the last {@link #rebuild}. */
    public int slivers;

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

    private int[][] ringLooseEnds = new int[0][];

    private int[] ringJunctions = new int[0];

    private boolean[] regionWallsChanged = new boolean[0];

    private SurfaceCreases formerFlatnessCreases;

    private SurfaceCreases faceCurvatureCreases;

    private float[] faceCurvature = new float[0];

    private boolean[] faceInCrease = new boolean[0];

    private int[] ringSteps = new int[0];

    private boolean[] faceInterior = new boolean[0];

    private boolean[] faceVisited = new boolean[0];

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
            int[] faceCorners = new int[0];
            for (int activeFace = 0; activeFace < faceCount; activeFace++) {
                int faceId = mesh.faceIdAt(activeFace);
                activeFaceByFaceId[faceId] = activeFace;
                if (faceCorners.length < TRIANGLE_CORNERS * mesh.faceVertexCount(faceId)) {
                    faceCorners = new int[TRIANGLE_CORNERS * mesh.faceVertexCount(faceId)];
                }
                int triangles = mesh.faceTriangleCorners(faceId, TriangulationRule.FAN,
                        faceCorners);
                for (int slot = 0; slot < TRIANGLE_CORNERS * triangles;
                        slot += TRIANGLE_CORNERS) {
                    mesh.vertexPosition(mesh.faceVertexAt(faceId, faceCorners[slot]), anchor);
                    mesh.vertexPosition(mesh.faceVertexAt(faceId, faceCorners[slot + 1]), first)
                            .sub(anchor);
                    mesh.vertexPosition(mesh.faceVertexAt(faceId, faceCorners[slot + 2]), second)
                            .sub(anchor);
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
        int[][] looseEnds = new int[ringCount][];
        int[] junctions = new int[ringCount];
        int[] ringOfFormer = new int[formerRings];
        for (int ring = 0; ring < ringCount; ring++) {
            formerRingOf[ring] = MeshTopology.NONE;
            for (int former = 0; former < formerRings && formerRingOf[ring] < 0; former++) {
                if (!formerClaimed[former] && (ringMarksByEdgeId[former] == masks[ring]
                        || ringLabels[former].equals(labels[ring])
                                && Arrays.equals(ringMarksByEdgeId[former], masks[ring]))) {
                    formerClaimed[former] = true;
                    formerRingOf[ring] = former;
                    ringOfFormer[former] = ring;
                }
            }
            int former = formerRingOf[ring];
            if (former != MeshTopology.NONE) {
                edgeCount[ring] = ringEdgeCount[former];
                edgeIds[ring] = ringEdgeIds[former];
                vertexIds[ring] = ringVertexIds[former];
                looseEnds[ring] = ringLooseEnds[former];
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
            // A vertex on a single ring edge is a loose end: the loop never closed there.
            int[] ends = new int[vertices];
            int endCount = 0;
            for (int index = 0; index < vertices; index++) {
                int vertexId = ringVertices[index];
                if (vertexDegree[vertexId] == 1) {
                    ends[endCount++] = vertexId;
                } else if (vertexDegree[vertexId] > 2) {
                    junctions[ring]++;
                }
                vertexDegree[vertexId] = 0;
            }
            looseEnds[ring] = Arrays.copyOf(ends, endCount);
            Arrays.sort(looseEnds[ring]);
            edgeCount[ring] = edges;
            edgeIds[ring] = ringEdges;
            vertexIds[ring] = Arrays.copyOf(ringVertices, vertices);
        }

        // A closed ring walls the flood. An open one walls it as a bisector once every loose end
        // lies on another wall, settled until none is added so a bisector may end on an earlier
        // one; any other open ring would only scar the region it lies in. A bisector may cross
        // walls on its way, splitting every region it passes through.
        boolean[] isBisector = new boolean[ringCount];
        int wallStamp = ++vertexEpoch;
        for (boolean grew = true; grew;) {
            grew = false;
            for (int ring = 0; ring < ringCount; ring++) {
                boolean closed = edgeCount[ring] > 0 && looseEnds[ring].length == 0;
                boolean landed = !closed && edgeCount[ring] > 0;
                for (int vertexId : landed ? looseEnds[ring] : new int[0]) {
                    landed &= vertexStamp[vertexId] == wallStamp;
                }
                if (isWall[ring] || !closed && !landed) {
                    continue;
                }
                isWall[ring] = true;
                isBisector[ring] = !closed;
                grew = true;
                for (int vertexId : vertexIds[ring]) {
                    vertexStamp[vertexId] = wallStamp;
                }
            }
        }

        // Count each edge's walling rings; an edge whose count crosses zero changed its wall.
        int[] changedEdges = new int[0];
        int changed = 0;
        for (int pass = 0; pass < 2; pass++) {
            int rings = pass == 0 ? formerRings : ringCount;
            for (int ring = 0; ring < rings; ring++) {
                boolean leaving = pass == 0 && ringIsWall[ring]
                        && (!formerClaimed[ring] || !isWall[ringOfFormer[ring]]);
                boolean arriving = pass == 1 && isWall[ring]
                        && (formerRingOf[ring] == MeshTopology.NONE
                                || !ringIsWall[formerRingOf[ring]]);
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
        int[] parent = UnionFind.singletons(componentCount);
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
                    UnionFind.union(parent, largestNeighbour[component], component);
                }
            }
        }
        int[] regionOfComponent = new int[componentCount];
        int[] regionOfRoot = new int[componentCount];
        Arrays.fill(regionOfRoot, MeshTopology.NONE);
        regionCount = 0;
        for (int component = 0; component < componentCount; component++) {
            int root = UnionFind.find(parent, component);
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
        ringJunctions = junctions;
        ringSplitsNothing = splitsNothing;
        ringSeparates = new boolean[ringCount];
        built = true;
        // The flatness now describes the former regions; measureFlatness keeps it for the ones
        // whose faces and walls this update left alone.
        formerFlatnessCreases = flatnessCreases;
        flatnessCreases = null;
        regionWallsChanged = new boolean[regionCount];
        for (int index = 0; index < changed; index++) {
            for (int side = 0; side < 2; side++) {
                int activeFace = activeFaceOf(mesh.edgeFace(changedEdges[index], side));
                if (activeFace != MeshTopology.NONE) {
                    regionWallsChanged[regionByActiveFace[activeFace]] = true;
                }
            }
        }

        problems.clear();
        Vector3f position = new Vector3f();
        for (int ring = 0; ring < ringCount; ring++) {
            if (edgeCount[ring] == 0) {
                problems.add(labels[ring] + " marks no edge of this surface");
            } else if (!isWall[ring]) {
                mesh.vertexPosition(looseEnds[ring][0], position);
                problems.add(String.format(Locale.ROOT,
                        "%s is open: %d loose end(s), the first at %.4f,%.4f,%.4f, not all on "
                                + "another wall; it walls nothing",
                        labels[ring], looseEnds[ring].length, position.x, position.y,
                        position.z));
            } else if (junctions[ring] > 0) {
                problems.add(labels[ring] + " meets itself at " + junctions[ring]
                        + " vertex(es); it still walls, but is not one simple loop");
            }
        }

        // A ring's sides are the pieces the surface falls into with only that ring as a wall:
        // components joined across every other walling edge, the flood each ring alone would make.
        // A bisector alone parts nothing, so it has no sides.
        sideByRingRegion = new byte[ringCount][regionCount];
        int[] pieceOf = new int[componentCount];
        double[] pieceArea = new double[componentCount];
        boolean[] adjacent = new boolean[componentCount];
        for (int ring = 0; ring < ringCount; ring++) {
            Arrays.fill(sideByRingRegion[ring], SIDE_NONE);
            if (!isWall[ring] || isBisector[ring]) {
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
                UnionFind.union(pieceOf, wallRightComponent[wall], wallLeftComponent[wall]);
            }
            Arrays.fill(pieceArea, 0.0);
            Arrays.fill(adjacent, false);
            for (int component = 0; component < componentCount; component++) {
                pieceOf[component] = UnionFind.find(pieceOf, component);
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
            // A ring that separates only together with others has no sides of its own; that is
            // expected, and only one that splits nothing with every ring in place is a problem.
            if (!separates) {
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
                // A bisector crosses the walls on its way by design.
                if (other == ring || !isWall[other] || isBisector[other]
                        || ringSeparates[other] && other < ring) {
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
        // Rings that share edges share one wall there, so a vertex on an edge both mark is no
        // touch, and neither is a bisector's end, which sits on its wall by design.
        int[] bisectorEdge = new int[2];
        for (int ring = 0; ring < ringCount; ring++) {
            int stamp = ++vertexEpoch;
            for (int vertexId : isWall[ring] ? vertexIds[ring] : new int[0]) {
                vertexStamp[vertexId] = stamp;
            }
            for (int other = ring + 1; isWall[ring] && other < ringCount; other++) {
                if (!isWall[other] || crossed[ring][other]) {
                    continue;
                }
                boolean[] marks = ringMarksByEdgeId[ring];
                boolean[] otherMarks = ringMarksByEdgeId[other];
                int shared = 0;
                for (int vertexId : vertexIds[other]) {
                    boolean pinchesNothing = isBisector[ring]
                            && Arrays.binarySearch(looseEnds[ring], vertexId) >= 0
                            || isBisector[other] && Arrays.binarySearch(looseEnds[other], vertexId) >= 0;
                    for (int slot = 0; vertexStamp[vertexId] == stamp && !pinchesNothing
                            && slot < mesh.vertexEdgeCount(vertexId); slot++) {
                        int edgeId = mesh.vertexEdgeAt(vertexId, slot);
                        pinchesNothing = edgeId < marks.length && edgeId < otherMarks.length
                                && marks[edgeId] && otherMarks[edgeId];
                    }
                    // A bisector passing through a wall at a vertex crosses it there, which pinches
                    // nothing.
                    boolean[] bisectorMarks = isBisector[ring] ? marks : otherMarks;
                    int bisectorEdges = 0;
                    for (int slot = 0; vertexStamp[vertexId] == stamp && !pinchesNothing
                            && (isBisector[ring] || isBisector[other])
                            && slot < mesh.vertexEdgeCount(vertexId); slot++) {
                        int edgeId = mesh.vertexEdgeAt(vertexId, slot);
                        if (edgeId < bisectorMarks.length && bisectorMarks[edgeId]) {
                            bisectorEdge[Math.min(1, bisectorEdges++)] = edgeId;
                        }
                    }
                    pinchesNothing |= bisectorEdges == 2 && RingAttachment.crossesAt(mesh,
                            vertexId, bisectorEdge[0], bisectorEdge[1],
                            isBisector[ring] ? otherMarks : marks);
                    shared += vertexStamp[vertexId] == stamp && !pinchesNothing ? 1 : 0;
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
     * Move to the rings as they stand: re-flood what changed rings bound (everything when the
     * sliver threshold changed), then measure flatness, recolour, note rings splitting nothing and
     * count slivers.
     *
     * @param labels           one label per ring
     * @param masks            one edge-id-indexed mask per ring, parallel to {@code labels}
     * @param absorbBelow      sliver threshold, as {@link #absorbBelowFaces}
     * @param creases          creases of {@link #mesh} flatness is measured on
     * @param palette          the entries {@link #colouring} hands out
     * @return this, rebuilt
     */
    public RingRegions rebuild(String[] labels, boolean[][] masks, int absorbBelow,
            SurfaceCreases creases, Palette palette) {
        if (absorbBelow != absorbBelowFaces) {
            absorbBelowFaces = absorbBelow;
            built = false;
        }
        update(labels, masks);
        measureFlatness(creases);
        colouring.colour(neighboursByRegion, regionFaceCount, formerRegionByRegion, regionKeptFaces,
                palette);
        Map<String, String> notes = new TreeMap<>();
        for (int ring = 0; ring < ringLabels.length; ring++) {
            if (ringSplitsNothing[ring]) {
                notes.put(ringLabels[ring], SPLITS_NOTHING_NOTE);
            }
        }
        noteByRingLabel = notes;
        slivers = 0;
        for (int region = 0; region < regionCount; region++) {
            slivers += isSliver(region) ? 1 : 0;
        }
        return this;
    }

    /**
     * The rings no shown region borders, which are left undrawn while their regions are hidden.
     *
     * @param hiddenByActiveFace hidden faces by dense face index; a mask of another length hides none
     * @return labels of the rings every region they bound is hidden from
     */
    public Set<String> hiddenRingLabels(boolean[] hiddenByActiveFace) {
        boolean hiding = hiddenByActiveFace.length == regionByActiveFace.length;
        boolean[] regionShown = new boolean[regionCount];
        for (int activeFace = 0; activeFace < regionByActiveFace.length; activeFace++) {
            regionShown[regionByActiveFace[activeFace]] |= !hiding || !hiddenByActiveFace[activeFace];
        }
        boolean[] ringShown = new boolean[ringLabels.length];
        for (int region = 0; region < regionCount; region++) {
            for (int ring : boundingRingsByRegion[region]) {
                ringShown[ring] |= regionShown[region];
            }
        }
        Set<String> hidden = new TreeSet<>();
        for (int ring = 0; ring < ringShown.length; ring++) {
            if (!ringShown[ring]) {
                hidden.add(ringLabels[ring]);
            }
        }
        return hidden;
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
     * Fills {@link #ringJoinPoint} and {@link #ringJoinPathLength} after a {@link #build} or
     * {@link #update}: for each ring that splits nothing, the connection's shortest path round it,
     * every walling ring a wall.
     *
     * @param connection face graph of {@link #mesh}, whose walls and path this replaces
     * @throws IllegalArgumentException when the connection runs over another mesh
     */
    public void measureJoins(SurfaceConnection connection) {
        if (connection.mesh != mesh) {
            throw new IllegalArgumentException("the connection runs over another mesh than the regions");
        }
        int ringCount = ringLabels.length;
        ringJoinPoint = new float[TRIANGLE_CORNERS * ringCount];
        Arrays.fill(ringJoinPoint, Float.NaN);
        ringJoinPathLength = new double[ringCount];
        boolean[] walls = new boolean[RingBundle.edgeIdCeiling(mesh)];
        for (int ring = 0; ring < ringCount; ring++) {
            for (int edgeId : ringIsWall[ring] ? ringEdgeIds[ring] : new int[0]) {
                walls[edgeId] = true;
            }
        }
        connection.wallByEdgeId = walls;
        for (int ring = 0; ring < ringCount; ring++) {
            int[] sides = { MeshTopology.NONE, MeshTopology.NONE };
            for (int edgeId : ringSplitsNothing[ring] ? ringEdgeIds[ring] : new int[0]) {
                int left = activeFaceOf(mesh.edgeFace(edgeId, 0));
                int right = activeFaceOf(mesh.edgeFace(edgeId, 1));
                if (sides[0] == MeshTopology.NONE && left != MeshTopology.NONE
                        && right != MeshTopology.NONE) {
                    sides[0] = left;
                    sides[1] = right;
                }
            }
            if (sides[0] == MeshTopology.NONE || !connection.findPath(sides[0], sides[1])) {
                continue;
            }
            int middle = connection.pathPolyline.length / TRIANGLE_CORNERS / 2;
            System.arraycopy(connection.pathPolyline, TRIANGLE_CORNERS * middle, ringJoinPoint,
                    TRIANGLE_CORNERS * ring, TRIANGLE_CORNERS);
            ringJoinPathLength[ring] = connection.pathLength;
        }
    }

    /**
     * Fills the per-region flatness arrays from the surface's creases, after every {@link #build}
     * or {@link #update}; only regions whose faces or walls changed since the last measure are
     * re-measured.
     *
     * @param creases creases measured on {@link #mesh}
     * @throws IllegalArgumentException when the creases lie on another mesh
     */
    public void measureFlatness(SurfaceCreases creases) {
        if (creases.sourceMesh != mesh) {
            throw new IllegalArgumentException("ring_regions: the creases lie on another mesh");
        }
        int faceCount = regionByActiveFace.length;
        if (faceCurvatureCreases != creases) {
            faceCurvature = new float[faceCount];
            faceInCrease = new boolean[faceCount];
            for (int activeFace = 0; activeFace < faceCount; activeFace++) {
                int faceId = mesh.faceIdAt(activeFace);
                int corners = mesh.faceVertexCount(faceId);
                float strength = 0f;
                float curvature = 0f;
                for (int slot = 0; slot < corners; slot++) {
                    int vertexId = mesh.faceVertexAt(faceId, slot);
                    strength += creases.valleyStrength[vertexId];
                    curvature += creases.valleyCurvature[vertexId];
                }
                faceCurvature[activeFace] = curvature / corners;
                faceInCrease[activeFace] = strength / corners >= SurfaceCreases.CREASE_STRENGTH;
            }
            faceCurvatureCreases = creases;
        }
        // A region that kept its faces and walls since the last measure keeps its flatness.
        boolean reusing = formerFlatnessCreases == creases;
        boolean[] measured = new boolean[regionCount];
        for (int region = 0; region < regionCount; region++) {
            measured[region] = !reusing || !regionKeptFaces[region] || regionWallsChanged[region];
        }
        // Face steps from the nearest face beside a ring edge, never crossing a ring; a face no
        // ring reaches keeps Integer.MAX_VALUE.
        if (ringSteps.length != faceCount) {
            ringSteps = new int[faceCount];
            faceInterior = new boolean[faceCount];
            faceVisited = new boolean[faceCount];
        }
        Arrays.fill(ringSteps, Integer.MAX_VALUE);
        Arrays.fill(faceVisited, false);
        int tail = 0;
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            if (!measured[regionByActiveFace[activeFace]]) {
                continue;
            }
            for (int slot = slotStart[activeFace]; slot < slotStart[activeFace + 1]; slot++) {
                if (wallsByEdgeId[slotEdgeId[slot]] > 0) {
                    ringSteps[activeFace] = 0;
                    frontier[tail++] = activeFace;
                    break;
                }
            }
        }
        for (int head = 0; head < tail; head++) {
            int activeFace = frontier[head];
            for (int slot = slotStart[activeFace]; slot < slotStart[activeFace + 1]; slot++) {
                int neighbour = slotNeighbour[slot];
                if (neighbour == MeshTopology.NONE || wallsByEdgeId[slotEdgeId[slot]] > 0
                        || ringSteps[neighbour] != Integer.MAX_VALUE) {
                    continue;
                }
                ringSteps[neighbour] = ringSteps[activeFace] + 1;
                frontier[tail++] = neighbour;
            }
        }
        int[] regionDepth = new int[regionCount];
        // The deepest face holds the region's label; a region no ring bounds takes its lowest face.
        int[] deepestFace = new int[regionCount];
        Arrays.fill(deepestFace, MeshTopology.NONE);
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            int region = regionByActiveFace[activeFace];
            if (ringSteps[activeFace] != Integer.MAX_VALUE) {
                if (deepestFace[region] == MeshTopology.NONE || ringSteps[activeFace] > regionDepth[region]
                        || ringSteps[deepestFace[region]] == Integer.MAX_VALUE) {
                    deepestFace[region] = activeFace;
                }
                regionDepth[region] = Math.max(regionDepth[region], ringSteps[activeFace]);
            } else if (deepestFace[region] == MeshTopology.NONE) {
                deepestFace[region] = activeFace;
            }
        }
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            faceInterior[activeFace] = ringSteps[activeFace] > Math.max(NEAR_RING_FACE_STEPS,
                    NEAR_RING_DEPTH_SHARE * regionDepth[regionByActiveFace[activeFace]]);
        }
        // Crease patches: crease faces on one side of the near distance, joined across edges no
        // ring marks.
        double[] creaseArea = new double[regionCount];
        double[] interiorArea = new double[regionCount];
        double[] largestNearArea = new double[regionCount];
        int[] largestNearFace = new int[regionCount];
        double[] largestInteriorArea = new double[regionCount];
        int[] largestInteriorFace = new int[regionCount];
        int[] peakFace = new int[regionCount];
        Arrays.fill(largestNearFace, MeshTopology.NONE);
        Arrays.fill(largestInteriorFace, MeshTopology.NONE);
        Arrays.fill(peakFace, MeshTopology.NONE);
        for (int seed = 0; seed < faceCount; seed++) {
            int region = regionByActiveFace[seed];
            if (!measured[region]) {
                continue;
            }
            if (peakFace[region] == MeshTopology.NONE
                    || faceCurvature[seed] > faceCurvature[peakFace[region]]) {
                peakFace[region] = seed;
            }
            if (!faceInCrease[seed] || faceVisited[seed]) {
                continue;
            }
            faceVisited[seed] = true;
            frontier[0] = seed;
            tail = 1;
            double area = 0.0;
            int steepest = seed;
            for (int head = 0; head < tail; head++) {
                int activeFace = frontier[head];
                area += faceArea[activeFace];
                steepest = faceCurvature[activeFace] > faceCurvature[steepest] ? activeFace
                        : steepest;
                for (int slot = slotStart[activeFace]; slot < slotStart[activeFace + 1]; slot++) {
                    int neighbour = slotNeighbour[slot];
                    if (neighbour == MeshTopology.NONE || wallsByEdgeId[slotEdgeId[slot]] > 0
                            || !faceInCrease[neighbour] || faceVisited[neighbour]
                            || faceInterior[neighbour] != faceInterior[seed]) {
                        continue;
                    }
                    faceVisited[neighbour] = true;
                    frontier[tail++] = neighbour;
                }
            }
            creaseArea[region] += area;
            if (!faceInterior[seed] && area > largestNearArea[region]) {
                largestNearArea[region] = area;
                largestNearFace[region] = steepest;
            }
            if (faceInterior[seed]) {
                interiorArea[region] += area;
                if (area > largestInteriorArea[region]) {
                    largestInteriorArea[region] = area;
                    largestInteriorFace[region] = steepest;
                }
            }
        }
        double[] formerCreaseShare = regionCreaseShare;
        double[] formerInteriorCreaseShare = regionInteriorCreaseShare;
        double[] formerPeakCurvature = regionPeakCurvature;
        float[] formerCreasePoint = regionCreasePoint;
        boolean[] formerCreaseInterior = regionCreaseInterior;
        float[] formerLabelPoint = regionLabelPoint;
        regionLabelPoint = new float[TRIANGLE_CORNERS * regionCount];
        regionCreaseShare = new double[regionCount];
        regionInteriorCreaseShare = new double[regionCount];
        regionPeakCurvature = new double[regionCount];
        regionCreasePoint = new float[TRIANGLE_CORNERS * regionCount];
        regionCreaseInterior = new boolean[regionCount];
        Vector3f corner = new Vector3f();
        for (int region = 0; region < regionCount; region++) {
            if (!measured[region]) {
                int former = formerRegionByRegion[region];
                regionCreaseShare[region] = formerCreaseShare[former];
                regionInteriorCreaseShare[region] = formerInteriorCreaseShare[former];
                regionPeakCurvature[region] = formerPeakCurvature[former];
                regionCreaseInterior[region] = formerCreaseInterior[former];
                System.arraycopy(formerCreasePoint, TRIANGLE_CORNERS * former, regionCreasePoint,
                        TRIANGLE_CORNERS * region, TRIANGLE_CORNERS);
                System.arraycopy(formerLabelPoint, TRIANGLE_CORNERS * former, regionLabelPoint,
                        TRIANGLE_CORNERS * region, TRIANGLE_CORNERS);
                continue;
            }
            int labelFaceId = mesh.faceIdAt(deepestFace[region]);
            int labelCorners = mesh.faceVertexCount(labelFaceId);
            for (int slot = 0; slot < labelCorners; slot++) {
                mesh.vertexPosition(mesh.faceVertexAt(labelFaceId, slot), corner);
                regionLabelPoint[TRIANGLE_CORNERS * region] += corner.x / labelCorners;
                regionLabelPoint[TRIANGLE_CORNERS * region + 1] += corner.y / labelCorners;
                regionLabelPoint[TRIANGLE_CORNERS * region + 2] += corner.z / labelCorners;
            }
            double area = regionArea[region];
            regionCreaseShare[region] = area > 0.0 ? creaseArea[region] / area : 0.0;
            regionInteriorCreaseShare[region] = area > 0.0 ? interiorArea[region] / area : 0.0;
            boolean joint = regionInteriorCreaseShare[region] >= INTERIOR_JOINT_SHARE;
            int pointFace = joint ? largestInteriorFace[region]
                    : largestNearFace[region] != MeshTopology.NONE ? largestNearFace[region]
                            : largestInteriorFace[region] != MeshTopology.NONE
                                    ? largestInteriorFace[region] : peakFace[region];
            if (pointFace == MeshTopology.NONE) {
                continue;
            }
            regionPeakCurvature[region] = faceCurvature[peakFace[region]];
            regionCreaseInterior[region] = faceInterior[pointFace];
            int faceId = mesh.faceIdAt(pointFace);
            int corners = mesh.faceVertexCount(faceId);
            for (int slot = 0; slot < corners; slot++) {
                mesh.vertexPosition(mesh.faceVertexAt(faceId, slot), corner);
                regionCreasePoint[TRIANGLE_CORNERS * region] += corner.x / corners;
                regionCreasePoint[TRIANGLE_CORNERS * region + 1] += corner.y / corners;
                regionCreasePoint[TRIANGLE_CORNERS * region + 2] += corner.z / corners;
            }
        }
        flatnessCreases = creases;
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

    private int activeFaceOf(int faceId) {
        return faceId < 0 || faceId >= activeFaceByFaceId.length ? MeshTopology.NONE
                : activeFaceByFaceId[faceId];
    }
}
