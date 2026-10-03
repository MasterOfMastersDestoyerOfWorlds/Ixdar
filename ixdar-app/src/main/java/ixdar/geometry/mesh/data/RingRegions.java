package ixdar.geometry.mesh.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.joml.Vector3f;

/**
 * The regions closed ring mark loops cut a surface into, each with its bounding rings and its
 * side of every ring; {@link #SIDE_DISTAL} is a ring's smaller side.
 *
 * <p>An open ring is reported and left out of the walls, so it never leaks a region.
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

    /** Surface the regions partition. */
    public final MeshTopology mesh;

    /** Mark label of each ring, in the order the rings were given. */
    public final String[] ringLabels;

    /** Each ring's edge-id-indexed mark mask, as given. */
    public final boolean[][] ringMarksByEdgeId;

    /** Whether each ring closed and so walls the flood; an open or empty ring does not. */
    public final boolean[] ringIsWall;

    /** Whether each walling ring splits its shell in two; a ring around a handle does not. */
    public final boolean[] ringSeparates;

    /** Marked edge count of each ring on this surface. */
    public final int[] ringEdgeCount;

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

    /**
     * Side of each separating ring each region lies on, indexed {@code [ring][region]}:
     * {@link #SIDE_DISTAL}, {@link #SIDE_PROXIMAL}, or {@link #SIDE_NONE} for a ring that does not
     * separate or a region on another shell.
     */
    public byte[][] sideByRingRegion = new byte[0][];

    /** Problems found, one line each, naming the ring: open, empty, crossing, non-separating. */
    public final List<String> problems = new ArrayList<>();

    private int[] activeFaceByFaceId = new int[0];

    private int[] frontier = new int[0];

    private double[] faceArea = new double[0];

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
     * Classifies every ring, floods the regions between the closed ones, and records each region's
     * bounding rings and its side of every separating ring.
     *
     * @return this, populated
     */
    public RingRegions build() {
        int faceCount = mesh.faceCount();
        int faceIdCeiling = 0;
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            faceIdCeiling = Math.max(faceIdCeiling, mesh.faceIdAt(activeFace) + 1);
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
        frontier = new int[faceCount];

        int ringCount = ringLabels.length;
        int edgeIdCeiling = RingBundle.edgeIdCeiling(mesh);
        boolean[] wallByEdgeId = new boolean[edgeIdCeiling];
        int vertexIdCeiling = 0;
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            vertexIdCeiling = Math.max(vertexIdCeiling, mesh.vertexIdAt(activeVertex) + 1);
        }
        // A ring walls the flood only when no vertex on it has a single ring edge: a loose end
        // means the loop never closed, and walling it would only scar the region it lies in.
        int[] ringDegreeByVertexId = new int[vertexIdCeiling];
        boolean[][] onRingByVertexId = new boolean[ringCount][vertexIdCeiling];
        Vector3f position = new Vector3f();
        for (int ring = 0; ring < ringCount; ring++) {
            boolean[] marks = ringMarksByEdgeId[ring];
            Arrays.fill(ringDegreeByVertexId, 0);
            int edges = 0;
            for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
                int edgeId = mesh.edgeIdAt(activeEdge);
                if (edgeId >= marks.length || !marks[edgeId]) {
                    continue;
                }
                edges++;
                int halfEdge = mesh.edgeHalfEdge(edgeId);
                ringDegreeByVertexId[mesh.halfEdgeVertex(halfEdge)]++;
                ringDegreeByVertexId[mesh.halfEdgeEndVertex(halfEdge)]++;
                onRingByVertexId[ring][mesh.halfEdgeVertex(halfEdge)] = true;
                onRingByVertexId[ring][mesh.halfEdgeEndVertex(halfEdge)] = true;
            }
            ringEdgeCount[ring] = edges;
            if (edges == 0) {
                problems.add(ringLabels[ring] + " marks no edge of this surface");
                continue;
            }
            int looseEnds = 0;
            int firstLooseEnd = MeshTopology.NONE;
            int junctions = 0;
            for (int vertexId = 0; vertexId < vertexIdCeiling; vertexId++) {
                if (ringDegreeByVertexId[vertexId] == 1) {
                    looseEnds++;
                    firstLooseEnd = firstLooseEnd == MeshTopology.NONE ? vertexId : firstLooseEnd;
                } else if (ringDegreeByVertexId[vertexId] > 2) {
                    junctions++;
                }
            }
            if (looseEnds > 0) {
                mesh.vertexPosition(firstLooseEnd, position);
                problems.add(String.format(Locale.ROOT,
                        "%s is open: %d loose end(s), the first at %.4f,%.4f,%.4f; it walls "
                                + "nothing",
                        ringLabels[ring], looseEnds, position.x, position.y, position.z));
                continue;
            }
            if (junctions > 0) {
                problems.add(ringLabels[ring] + " meets itself at " + junctions
                        + " vertex(es); it still walls, but is not one simple loop");
            }
            ringIsWall[ring] = true;
            for (int edgeId = 0; edgeId < marks.length && edgeId < edgeIdCeiling; edgeId++) {
                wallByEdgeId[edgeId] |= marks[edgeId];
            }
        }

        regionByActiveFace = new int[faceCount];
        regionCount = flood(wallByEdgeId, regionByActiveFace);
        regionFaceCount = new int[regionCount];
        regionArea = new double[regionCount];
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            regionFaceCount[regionByActiveFace[activeFace]]++;
            regionArea[regionByActiveFace[activeFace]] += faceArea[activeFace];
        }

        boolean[][] boundedByRegionRing = new boolean[regionCount][ringCount];
        sideByRingRegion = new byte[ringCount][regionCount];
        int[] componentByActiveFace = new int[faceCount];
        boolean[] singleWall = new boolean[edgeIdCeiling];
        for (int ring = 0; ring < ringCount; ring++) {
            Arrays.fill(sideByRingRegion[ring], SIDE_NONE);
            if (!ringIsWall[ring]) {
                continue;
            }
            boolean[] marks = ringMarksByEdgeId[ring];
            Arrays.fill(singleWall, false);
            for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
                int edgeId = mesh.edgeIdAt(activeEdge);
                singleWall[edgeId] = edgeId < marks.length && marks[edgeId];
            }
            int componentCount = flood(singleWall, componentByActiveFace);
            double[] componentArea = new double[componentCount];
            for (int activeFace = 0; activeFace < faceCount; activeFace++) {
                componentArea[componentByActiveFace[activeFace]] += faceArea[activeFace];
            }
            // The ring's two sides are the two largest pieces it borders; any further piece is a
            // pocket its edges enclose on their own, such as a face a jagged snap walled off.
            boolean[] adjacent = new boolean[componentCount];
            int sameSideEdges = 0;
            for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
                int edgeId = mesh.edgeIdAt(activeEdge);
                if (!singleWall[edgeId]) {
                    continue;
                }
                int leftFace = activeFaceOf(mesh.edgeFace(edgeId, 0));
                int rightFace = activeFaceOf(mesh.edgeFace(edgeId, 1));
                for (int activeFace : new int[] { leftFace, rightFace }) {
                    if (activeFace != MeshTopology.NONE) {
                        boundedByRegionRing[regionByActiveFace[activeFace]][ring] = true;
                        adjacent[componentByActiveFace[activeFace]] = true;
                    }
                }
                if (leftFace != MeshTopology.NONE && rightFace != MeshTopology.NONE
                        && componentByActiveFace[leftFace] == componentByActiveFace[rightFace]) {
                    sameSideEdges++;
                }
            }
            int firstSide = MeshTopology.NONE;
            int secondSide = MeshTopology.NONE;
            int pockets = 0;
            for (int component = 0; component < componentCount; component++) {
                if (!adjacent[component]) {
                    continue;
                }
                pockets++;
                if (firstSide == MeshTopology.NONE
                        || componentArea[component] > componentArea[firstSide]) {
                    secondSide = firstSide;
                    firstSide = component;
                } else if (secondSide == MeshTopology.NONE
                        || componentArea[component] > componentArea[secondSide]) {
                    secondSide = component;
                }
            }
            pockets = Math.max(0, pockets - 2);
            boolean separates = sameSideEdges == 0 && secondSide != MeshTopology.NONE;
            ringSeparates[ring] = separates;
            if (!separates) {
                problems.add(ringLabels[ring] + " does not separate the surface: " + sameSideEdges
                        + " of its " + ringEdgeCount[ring] + " edges have the same piece on both "
                        + "sides (it loops a handle), so it bounds regions but has no distal side");
                continue;
            }
            if (pockets > 0) {
                problems.add(ringLabels[ring] + " also walls off " + pockets + " stray pocket(s) "
                        + "beside its loop; their faces lie on neither side");
            }
            int distal = componentArea[firstSide] <= componentArea[secondSide] ? firstSide
                    : secondSide;
            int proximal = distal == firstSide ? secondSide : firstSide;
            for (int activeFace = 0; activeFace < faceCount; activeFace++) {
                int component = componentByActiveFace[activeFace];
                byte side = component == distal ? SIDE_DISTAL
                        : component == proximal ? SIDE_PROXIMAL : SIDE_NONE;
                sideByRingRegion[ring][regionByActiveFace[activeFace]] = side;
            }
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
        boolean[][] crossed = new boolean[ringCount][ringCount];
        for (int ring = 0; ring < ringCount; ring++) {
            for (int other = 0; ringSeparates[ring] && other < ringCount; other++) {
                if (other == ring || !ringIsWall[other] || ringSeparates[other] && other < ring) {
                    continue;
                }
                if (sidesReached(other, ring) == (SIDE_MASK_DISTAL | SIDE_MASK_PROXIMAL)) {
                    problems.add(ringLabels[ring] + " and " + ringLabels[other] + " cross");
                    crossed[ring][other] = true;
                    crossed[other][ring] = true;
                }
            }
        }
        // Rings that touch without crossing still pinch slivers of a face or two between them.
        for (int ring = 0; ring < ringCount; ring++) {
            for (int other = ring + 1; ringIsWall[ring] && other < ringCount; other++) {
                if (!ringIsWall[other] || crossed[ring][other]) {
                    continue;
                }
                int shared = 0;
                for (int vertexId = 0; vertexId < vertexIdCeiling; vertexId++) {
                    shared += onRingByVertexId[ring][vertexId] && onRingByVertexId[other][vertexId]
                            ? 1 : 0;
                }
                if (shared > 0) {
                    problems.add(ringLabels[ring] + " and " + ringLabels[other]
                            + " touch or cross at " + shared + " vertex(es); slivers between "
                            + "them are regions of their own");
                }
            }
        }
        return this;
    }

    /**
     * Labels the connected components of faces joined across every edge not in the wall mask.
     *
     * @param wallByEdgeId     edge-id-indexed walls the flood may not cross
     * @param componentByActiveFace receives each face's component, by dense face index
     * @return the number of components, numbered in order of their lowest dense face index
     */
    private int flood(boolean[] wallByEdgeId, int[] componentByActiveFace) {
        Arrays.fill(componentByActiveFace, MeshTopology.NONE);
        int components = 0;
        for (int seed = 0; seed < componentByActiveFace.length; seed++) {
            if (componentByActiveFace[seed] != MeshTopology.NONE) {
                continue;
            }
            int head = 0;
            int tail = 0;
            frontier[tail++] = seed;
            componentByActiveFace[seed] = components;
            while (head < tail) {
                int faceId = mesh.faceIdAt(frontier[head++]);
                for (int slot = 0; slot < mesh.faceHalfEdgeCount(faceId); slot++) {
                    int halfEdge = mesh.faceHalfEdgeAt(faceId, slot);
                    int edgeId = mesh.halfEdgeEdge(halfEdge);
                    if (edgeId < wallByEdgeId.length && wallByEdgeId[edgeId]) {
                        continue;
                    }
                    int twin = mesh.halfEdgeTwin(halfEdge);
                    int neighbour = twin == MeshTopology.NONE ? MeshTopology.NONE
                            : activeFaceOf(mesh.halfEdgeFace(twin));
                    if (neighbour == MeshTopology.NONE
                            || componentByActiveFace[neighbour] != MeshTopology.NONE) {
                        continue;
                    }
                    componentByActiveFace[neighbour] = components;
                    frontier[tail++] = neighbour;
                }
            }
            components++;
        }
        return components;
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
        boolean[] marks = ringMarksByEdgeId[ring];
        boolean[] cutting = ringMarksByEdgeId[sidesOf];
        int reached = 0;
        for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
            int edgeId = mesh.edgeIdAt(activeEdge);
            if (edgeId >= marks.length || !marks[edgeId]
                    || edgeId < cutting.length && cutting[edgeId]) {
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

    private int activeFaceOf(int faceId) {
        return faceId < 0 || faceId >= activeFaceByFaceId.length ? MeshTopology.NONE
                : activeFaceByFaceId[faceId];
    }
}
