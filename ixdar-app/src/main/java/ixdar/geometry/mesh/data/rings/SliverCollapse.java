package ixdar.geometry.mesh.data.rings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.bundle.RingBundle;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.query.SurfaceCreases;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSplineTracer;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.numeric.graph.UnionFind;

/**
 * Removes the lens slivers two closed rings pinch: one ring keeps each run of lenses and the other
 * is dropped, each stretch of it outside the runs a bisector on its old route.
 */
public final class SliverCollapse {

    // Lenses of one pair join into a run while the rings stay this close between them: a zipper's
    // rings drift a vertex or two apart between touches.
    public static final double RUN_GAP_MEAN_EDGES = 2.0;

    public static final String REASON_SEPARATOR = "; ";

    /** Mean edge length of the surface, the scale {@link #RUN_GAP_MEAN_EDGES} is in. */
    public final double meanEdgeLength;

    /** Geodesics the bisectors are traced with. */
    public final SurfaceGeodesics geodesics;

    /** Creases a crease ring's bisectors follow, or {@code null} when no ring is a crease ring. */
    public final SurfaceCreases creases;

    /** Whether each ring is dropped: its statement goes, its stretches outside runs become bisectors. */
    public boolean[] ringDropped = new boolean[0];

    /**
     * Each new bisector's anchors end to end: its dropped ring's own over the stretch, in the
     * ring's order, and one past each wall.
     */
    public final List<int[]> bisectorAnchors = new ArrayList<>();

    /**
     * Each new bisector's stretch vertices: its ring's anchor before its first, where it meets its
     * first wall and its last, and its ring's anchor after its last.
     */
    public final List<int[]> bisectorStretches = new ArrayList<>();

    /**
     * Each new bisector's two walls: a ring index, or the ring count plus the index of a new
     * bisector listed before it.
     */
    public final List<int[]> bisectorWalls = new ArrayList<>();

    /** Each new bisector's trace. */
    public final List<SurfaceSpline> bisectorSplines = new ArrayList<>();

    /** The dropped ring each new bisector is a stretch of. */
    public final List<Integer> bisectorSource = new ArrayList<>();

    /**
     * Each existing bisector's walls once moved off the rings dropped under its ends, coded as
     * {@link #bisectorWalls}; {@code null} for a ring or a bisector left as it was.
     */
    public int[][] movedWalls = new int[0][];

    /** Each moved bisector's anchors, its ends re-attached to its new walls. */
    public int[][] movedAnchors = new int[0][];

    /** Each moved bisector's new trace. */
    public SurfaceSpline[] movedSplines = new SurfaceSpline[0];

    /**
     * Each new bisector whose edges are not its dropped ring's old edges over its stretch, one
     * line each; empty when every route is the ring's own.
     */
    public final List<String> routeDifferences = new ArrayList<>();

    /** Slivers the last {@link #collapse} removed. */
    public int collapsedSlivers;

    /** Why each sliver the last {@link #collapse} left in place stayed, one line each. */
    public final List<String> refusals = new ArrayList<>();

    /**
     * Slivers one ring walls alone, left on purpose: a ring round a small piece of geometry is
     * meant to separate it, so no ring is cut for them. One line each.
     */
    public final List<String> singleRingRegions = new ArrayList<>();

    /** Keeper choices the last {@link #collapse} refused because they closed a keeper cycle. */
    public int keeperCycles;

    /** Keeper choices the last {@link #collapse} refused because two runs overlapped on a ring. */
    public int overlappingRuns;

    /**
     * Binds the collapse to the surface its bisectors are traced on.
     *
     * @param geodesics geodesics of the surface the regions cover
     * @param creases   its creases, or {@code null} when no ring runs in crease mode
     */
    public SliverCollapse(SurfaceGeodesics geodesics, SurfaceCreases creases) {
        this.geodesics = geodesics;
        this.creases = creases;
        meanEdgeLength = geodesics.metric.meanEdgeLength;
    }

    /**
     * Finds every lens sliver, joins a pair's lenses into runs, picks each run's keeper (the
     * crease ring, else the ring with more authored anchors along it) and drops the other ring,
     * each of its stretches outside the runs becoming a bisector on its own route. The regions
     * are only read.
     *
     * @param regions         regions of the rings as they stand
     * @param anchors         each ring's authored anchors in ring order, a bisector's end to end
     * @param splineAnchors   each ring's spline anchors in ring order, authored and supporting,
     *                        the points its curve passes through, which its bisectors carry
     * @param modes           each ring's segment mode
     * @param closed          whether each ring is a closed ring that may keep or be dropped
     * @param wallsOfBisector each existing bisector's two wall ring indices, {@code null} for a ring
     * @param stretchOfBisector each existing bisector's stretch vertices when it is a ring
     *                          stretch, as {@link #bisectorStretches} holds them, else {@code null}
     * @return the number of slivers removed
     */
    public int collapse(RingRegions regions, int[][] anchors, int[][] splineAnchors,
            RingSegmentMode[] modes, boolean[] closed, int[][] wallsOfBisector,
            int[][] stretchOfBisector) {
        MeshTopology mesh = regions.mesh;
        int ringCount = regions.ringLabels.length;
        String[] labels = regions.ringLabels;
        boolean[][] marks = regions.ringMarksByEdgeId;
        singleRingRegions.clear();
        refusals.clear();
        routeDifferences.clear();
        bisectorAnchors.clear();
        bisectorStretches.clear();
        bisectorWalls.clear();
        bisectorSplines.clear();
        bisectorSource.clear();
        collapsedSlivers = 0;
        ringDropped = new boolean[ringCount];
        movedWalls = new int[ringCount][];
        movedAnchors = new int[ringCount][];
        movedSplines = new SurfaceSpline[ringCount];

        // Every sliver's border edges, from one walk over the faces.
        int[] sliverOfRegion = new int[regions.regionCount];
        int sliverCount = 0;
        for (int region = 0; region < regions.regionCount; region++) {
            sliverOfRegion[region] = regions.isSliver(region) ? sliverCount++ : MeshTopology.NONE;
        }
        int[][] borderEdgeIds = new int[sliverCount][];
        int[] borderEdges = new int[sliverCount];
        boolean[] reachesEdge = new boolean[sliverCount];
        int[] lowestFace = new int[sliverCount];
        Arrays.fill(lowestFace, MeshTopology.NONE);
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            int region = regions.regionByActiveFace[activeFace];
            int sliver = sliverOfRegion[region];
            if (sliver == MeshTopology.NONE) {
                continue;
            }
            lowestFace[sliver] = lowestFace[sliver] == MeshTopology.NONE ? activeFace
                    : lowestFace[sliver];
            int faceId = mesh.faceIdAt(activeFace);
            for (int side = 0; side < mesh.faceHalfEdgeCount(faceId); side++) {
                int halfEdge = mesh.faceHalfEdgeAt(faceId, side);
                int twin = mesh.halfEdgeTwin(halfEdge);
                if (twin == MeshTopology.NONE) {
                    reachesEdge[sliver] = true;
                    continue;
                }
                if (regions.regionOfFace(mesh.halfEdgeFace(twin)) == region) {
                    continue;
                }
                if (borderEdgeIds[sliver] == null) {
                    borderEdgeIds[sliver] = new int[2 + 2];
                } else if (borderEdges[sliver] == borderEdgeIds[sliver].length) {
                    borderEdgeIds[sliver] = Arrays.copyOf(borderEdgeIds[sliver],
                            2 * borderEdges[sliver]);
                }
                borderEdgeIds[sliver][borderEdges[sliver]++] = mesh.halfEdgeEdge(halfEdge);
            }
        }

        // Each lens: its two rings and each one's stretch along it, one run of its loop from one
        // meeting point to the other.
        int[][] loopByRing = new int[ringCount][];
        int[][] anchorIndexByRing = new int[ringCount][];
        boolean[] onBorder = new boolean[RingBundle.edgeIdCeiling(mesh)];
        int[][] lensRings = new int[sliverCount][];
        int[][] lensStarts = new int[sliverCount][];
        int[][] lensLengths = new int[sliverCount][];
        String[] lensWhere = new String[sliverCount];
        int lenses = 0;
        Vector3f position = new Vector3f();
        Vector3f loopPosition = new Vector3f();
        for (int sliver = 0; sliver < sliverCount; sliver++) {
            String where = "sliver at face " + lowestFace[sliver] + ": ";
            if (reachesEdge[sliver] || borderEdges[sliver] == 0) {
                refusals.add(where + "it reaches the edge of the surface or has no wall");
                continue;
            }
            // The walling rings that border it, as the regions counted them.
            int[] walling = regions.boundingRingsByRegion[regions.regionByActiveFace[
                    lowestFace[sliver]]];
            int bounding = walling.length;
            int[] pair = { bounding > 0 ? walling[0] : MeshTopology.NONE,
                bounding > 1 ? walling[1] : MeshTopology.NONE };
            if (bounding == 1) {
                singleRingRegions.add(where + labels[pair[0]] + " walls it alone");
                continue;
            }
            if (bounding != 2 || !closed[pair[0]] || !closed[pair[1]]) {
                refusals.add(where + "it is walled by " + bounding
                        + " ring(s), not by two closed rings");
                continue;
            }
            for (int index = 0; index < borderEdges[sliver]; index++) {
                onBorder[borderEdgeIds[sliver][index]] = true;
            }
            int[] start = new int[2];
            int[] length = new int[2];
            int[][] ends = new int[2][];
            String problem = "";
            for (int side = 0; side < 2 && problem.isEmpty(); side++) {
                int ring = pair[side];
                if (loopByRing[ring] == null) {
                    // The loop, and where each anchor lies along it: on it, or beside the loop
                    // vertex nearest it in space.
                    int[] walked = EdgeMarks.orderedLoop(mesh, marks[ring]);
                    loopByRing[ring] = walked;
                    anchorIndexByRing[ring] = new int[anchors[ring].length];
                    for (int anchor = 0; anchor < anchors[ring].length; anchor++) {
                        mesh.vertexPosition(anchors[ring][anchor], position);
                        double nearest = Double.POSITIVE_INFINITY;
                        for (int index = 0; index < walked.length && nearest > 0.0; index++) {
                            double distance = walked[index] == anchors[ring][anchor] ? 0.0
                                    : mesh.vertexPosition(walked[index], loopPosition)
                                            .distanceSquared(position);
                            if (distance < nearest) {
                                nearest = distance;
                                anchorIndexByRing[ring][anchor] = index;
                            }
                        }
                    }
                }
                int[] loop = loopByRing[ring];
                int loopLength = loop.length;
                int runs = 0;
                for (int index = 0; index < loopLength; index++) {
                    boolean here = onBorder[mesh.edgeBetween(loop[index],
                            loop[(index + 1) % loopLength])];
                    boolean before = onBorder[mesh.edgeBetween(
                            loop[(index + loopLength - 1) % loopLength], loop[index])];
                    if (here) {
                        length[side]++;
                    }
                    if (here && !before) {
                        start[side] = index;
                        runs++;
                    }
                }
                if (loopLength == 0) {
                    problem = labels[ring] + " is not one simple loop";
                } else if (runs == 0) {
                    problem = labels[pair[0]] + " and " + labels[pair[1]]
                            + " run alongside each other without crossing or touching";
                } else if (runs > 1) {
                    problem = labels[ring] + " meets the sliver in " + runs + " stretches";
                } else {
                    int first = loop[start[side]];
                    int last = loop[(start[side] + length[side]) % loopLength];
                    ends[side] = new int[] { Math.min(first, last), Math.max(first, last) };
                    problem = first == last ? labels[ring] + " meets the other ring at one point"
                            : "";
                }
            }
            for (int index = 0; index < borderEdges[sliver]; index++) {
                onBorder[borderEdgeIds[sliver][index]] = false;
            }
            // Each end of one ring's stretch meets an end of the other's: the same vertex, or across
            // a stretch the two share where they run together before parting.
            boolean meet = problem.isEmpty() && stretchEndsMeet(mesh,
                    new int[] { ends[0][0], ends[0][1], ends[1][0], ends[1][1] }, marks[pair[0]],
                    marks[pair[1]]);
            if (problem.isEmpty() && !meet) {
                problem = labels[pair[0]] + " and " + labels[pair[1]]
                        + " do not meet at both ends of the sliver";
            }
            if (!problem.isEmpty()) {
                refusals.add(where + problem);
                continue;
            }
            lensRings[lenses] = pair;
            lensStarts[lenses] = start;
            lensLengths[lenses] = length;
            lensWhere[lenses++] = where;
        }

        // Lenses of one pair that zigzag along each other are one run: on the first ring's loop,
        // a lens joins the next while every vertex between them stays within RUN_GAP_MEAN_EDGES of
        // the second ring.
        int[] runOfLens = UnionFind.singletons(lenses);
        double gapReach = RUN_GAP_MEAN_EDGES * meanEdgeLength;
        for (int lens = 0; lens < lenses; lens++) {
            for (int other = lens + 1; other < lenses; other++) {
                int[] pair = lensRings[lens];
                int[] otherPair = lensRings[other];
                if (pair[0] != otherPair[0] || pair[1] != otherPair[1]) {
                    continue;
                }
                int[] loop = loopByRing[pair[0]];
                int[] otherLoop = loopByRing[pair[1]];
                int loopLength = loop.length;
                boolean joined = false;
                for (int order = 0; order < 2 && !joined; order++) {
                    int first = order == 0 ? lens : other;
                    int second = order == 0 ? other : lens;
                    int from = (lensStarts[first][0] + lensLengths[first][0]) % loopLength;
                    int gap = Math.floorMod(lensStarts[second][0] - from, loopLength);
                    boolean close = true;
                    for (int step = 0; close && step <= gap; step++) {
                        mesh.vertexPosition(loop[(from + step) % loopLength], position);
                        double nearest = Double.POSITIVE_INFINITY;
                        for (int vertexId : otherLoop) {
                            nearest = Math.min(nearest, mesh.vertexPosition(vertexId,
                                    loopPosition).distance(position));
                        }
                        close = nearest <= gapReach;
                        // The region on this gap edge's side toward the other ring, when it is no
                        // sliver, is real: a run across the gap would cut it away too. An edge
                        // both rings mark has no such side.
                        int edgeId = step < gap ? mesh.edgeBetween(loop[(from + step) % loopLength],
                                loop[(from + step + 1) % loopLength]) : MeshTopology.NONE;
                        boolean shared = edgeId != MeshTopology.NONE
                                && edgeId < marks[pair[1]].length && marks[pair[1]][edgeId];
                        int inner = MeshTopology.NONE;
                        double innerDistance = Double.POSITIVE_INFINITY;
                        for (int side = 0; close && !shared && edgeId != MeshTopology.NONE
                                && side < 2; side++) {
                            int faceId = mesh.edgeFace(edgeId, side);
                            if (faceId == MeshTopology.NONE) {
                                continue;
                            }
                            mesh.vertexPosition(mesh.faceVertexAt(faceId, 0), position);
                            for (int corner = 1; corner < mesh.faceVertexCount(faceId); corner++) {
                                position.add(mesh.vertexPosition(mesh.faceVertexAt(faceId, corner),
                                        loopPosition));
                            }
                            position.div(mesh.faceVertexCount(faceId));
                            double toOther = Double.POSITIVE_INFINITY;
                            for (int vertexId : otherLoop) {
                                toOther = Math.min(toOther, mesh.vertexPosition(vertexId,
                                        loopPosition).distance(position));
                            }
                            if (toOther < innerDistance) {
                                innerDistance = toOther;
                                inner = regions.regionOfFace(faceId);
                            }
                        }
                        close &= inner == MeshTopology.NONE || regions.isSliver(inner);
                    }
                    joined = close;
                }
                if (joined) {
                    UnionFind.union(runOfLens, lens, other);
                }
            }
        }

        // Each run spans its lenses on each ring, the loop less its largest uncovered gap; a run
        // whose two rings do not end at the same two points falls back to its lenses.
        int[] units = new int[lenses];
        int[][] unitStart = new int[lenses][];
        int[][] unitLength = new int[lenses][];
        int[] unitPreferredKeeper = new int[lenses];
        int[] unitSlivers = new int[lenses];
        int unitCount = 0;
        boolean[] alone = new boolean[lenses];
        for (int lens = 0; lens < lenses; lens++) {
            runOfLens[lens] = UnionFind.find(runOfLens, lens);
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int lens = 0; lens < lenses; lens++) {
                boolean asRun = pass == 0;
                if (asRun && runOfLens[lens] != lens || !asRun && !alone[lens]) {
                    continue;
                }
                int[] pair = lensRings[lens];
                int[] start = new int[2];
                int[] length = new int[2];
                int[] along = new int[2];
                int[] ends = new int[2 + 2];
                int members = 0;
                for (int side = 0; side < 2; side++) {
                    int[] loop = loopByRing[pair[side]];
                    int loopLength = loop.length;
                    boolean[] covered = new boolean[loopLength];
                    members = 0;
                    for (int member = 0; member < lenses; member++) {
                        boolean inUnit = asRun ? runOfLens[member] == lens : member == lens;
                        if (!inUnit) {
                            continue;
                        }
                        members++;
                        for (int step = 0; step < lensLengths[member][side]; step++) {
                            covered[(lensStarts[member][side] + step) % loopLength] = true;
                        }
                    }
                    int gapStart = 0;
                    int gapLength = 0;
                    for (int index = 0; index < loopLength; index++) {
                        if (covered[index] || !covered[(index + loopLength - 1) % loopLength]) {
                            continue;
                        }
                        int run = 0;
                        while (run < loopLength && !covered[(index + run) % loopLength]) {
                            run++;
                        }
                        if (run > gapLength) {
                            gapLength = run;
                            gapStart = index;
                        }
                    }
                    start[side] = (gapStart + gapLength) % loopLength;
                    length[side] = loopLength - gapLength;
                    ends[2 * side] = loop[start[side]];
                    ends[2 * side + 1] = loop[(start[side] + length[side]) % loopLength];
                    for (int at : anchorIndexByRing[pair[side]]) {
                        int offset = Math.floorMod(at - start[side], loopLength);
                        along[side] += offset > 0 && offset < length[side] ? 1 : 0;
                    }
                }
                if (!stretchEndsMeet(mesh, ends, marks[pair[0]], marks[pair[1]])) {
                    // Its lenses go on alone in the second pass.
                    for (int member = 0; asRun && member < lenses; member++) {
                        alone[member] |= runOfLens[member] == lens;
                    }
                    if (!asRun) {
                        refusals.add(lensWhere[lens] + labels[pair[0]] + " and " + labels[pair[1]]
                                + " do not meet at both ends of it");
                    }
                    continue;
                }
                units[unitCount] = lens;
                unitStart[unitCount] = start;
                unitLength[unitCount] = length;
                unitSlivers[unitCount] = members;
                // The crease ring keeps the run; with the modes alike, the ring with more anchors
                // along it does.
                unitPreferredKeeper[unitCount++] = modes[pair[0]] != modes[pair[1]]
                        ? (modes[pair[0]] == RingSegmentMode.CREASE ? 0 : 1)
                        : along[1] > along[0] ? 1 : 0;
            }
        }

        // Keepers, runs with most slivers first, every run measured on the rings as traced. A ring
        // may keep one run and be cut along another while the two stay apart on its own loop, so
        // each cut still lands on edges its keeper marks. Refused: a cut overlapping another cut
        // or a kept run on the same ring (three rings zipping along one stretch, the run with more
        // slivers wins), and a keeper that is, through its own keepers, cut along the ring it would
        // keep from (a cycle, broken by trying the other keeper, then leaving the run).
        Integer[] order = new Integer[unitCount];
        for (int unit = 0; unit < unitCount; unit++) {
            order[unit] = unit;
        }
        Arrays.sort(order, (first, second) -> Integer.compare(unitSlivers[second],
                unitSlivers[first]));
        boolean[][] keptAt = new boolean[ringCount][];
        boolean[][] cutAt = new boolean[ringCount][];
        boolean[][] cutAlong = new boolean[ringCount][ringCount];
        boolean[] dropping = new boolean[ringCount];
        int[] unitKeeper = new int[unitCount];
        int[] unitCutRing = new int[unitCount];
        Arrays.fill(unitKeeper, MeshTopology.NONE);
        int[] reach = new int[ringCount];
        keeperCycles = 0;
        overlappingRuns = 0;
        for (int unit : order) {
            int[] pair = lensRings[units[unit]];
            int keeperSide = unitPreferredKeeper[unit];
            String why = "";
            for (int attempt = 0; attempt < 2; attempt++, keeperSide = 1 - keeperSide) {
                int keeper = pair[keeperSide];
                int cut = pair[1 - keeperSide];
                boolean overlap = false;
                for (int side = 0; side < 2; side++) {
                    int ring = pair[side];
                    int[] loop = loopByRing[ring];
                    keptAt[ring] = keptAt[ring] == null ? new boolean[loop.length] : keptAt[ring];
                    cutAt[ring] = cutAt[ring] == null ? new boolean[loop.length] : cutAt[ring];
                    // Two rings keeping one stretch is fine; anything else sharing an edge of it
                    // is not. Index i is the loop edge from vertex i to i + 1, so stretches that
                    // only touch at an end vertex, as a zigzag's lenses do, do not overlap.
                    for (int step = 0; step < unitLength[unit][side]; step++) {
                        int index = (unitStart[unit][side] + step) % loop.length;
                        overlap |= cutAt[ring][index] || side != keeperSide && keptAt[ring][index];
                    }
                }
                // Does the keeper, through the rings it is cut along, reach the cut ring?
                boolean cycle = false;
                int head = 0;
                int tail = 0;
                boolean[] seen = new boolean[ringCount];
                reach[tail++] = keeper;
                seen[keeper] = true;
                while (head < tail && !cycle) {
                    int ring = reach[head++];
                    cycle = ring == cut;
                    for (int along = 0; along < ringCount; along++) {
                        if (cutAlong[ring][along] && !seen[along]) {
                            seen[along] = true;
                            reach[tail++] = along;
                        }
                    }
                }
                overlappingRuns += overlap ? 1 : 0;
                keeperCycles += cycle ? 1 : 0;
                if (overlap || cycle) {
                    why += (why.isEmpty() ? "" : REASON_SEPARATOR) + "with " + labels[keeper] + " keeping, "
                            + (overlap ? "it overlaps another run on the same ring"
                                    : labels[keeper] + " is already cut along " + labels[cut]);
                    continue;
                }
                for (int side = 0; side < 2; side++) {
                    int ring = pair[side];
                    for (int step = 0; step < unitLength[unit][side]; step++) {
                        int index = (unitStart[unit][side] + step) % loopByRing[ring].length;
                        (side == keeperSide ? keptAt : cutAt)[ring][index] = true;
                    }
                }
                dropping[cut] = true;
                cutAlong[cut][keeper] = true;
                unitKeeper[unit] = keeper;
                unitCutRing[unit] = cut;
                break;
            }
            if (unitKeeper[unit] == MeshTopology.NONE) {
                refusals.add(lensWhere[units[unit]] + "neither " + labels[pair[0]] + " nor "
                        + labels[pair[1]] + " can be cut: " + why);
            }
        }

        // Drop the rings keepers first, so a ring's bisectors end on what its keepers became. Each
        // drop is whole or not at all: every stretch of the ring outside its runs becomes a
        // bisector and every bisector ending on it moves, or the ring stays as it was.
        boolean[][] standingMarks = marks.clone();
        int[][] wallsNow = new int[ringCount][];
        int[][] anchorsNow = new int[ringCount][];
        for (int ring = 0; ring < ringCount; ring++) {
            wallsNow[ring] = wallsOfBisector[ring] == null ? null : wallsOfBisector[ring].clone();
            anchorsNow[ring] = anchors[ring];
        }
        boolean[] placed = new boolean[ringCount];
        Vector3f endPosition = new Vector3f();
        for (boolean progressed = true; progressed;) {
            progressed = false;
            for (int ring = 0; ring < ringCount; ring++) {
                boolean ready = dropping[ring] && !placed[ring];
                for (int keeper = 0; ready && keeper < ringCount; keeper++) {
                    ready = !cutAlong[ring][keeper] || !dropping[keeper] || placed[keeper];
                }
                if (!ready) {
                    continue;
                }
                placed[ring] = true;
                progressed = true;
                int[] loop = loopByRing[ring];
                int loopLength = loop.length;
                int[] runAt = new int[loopLength];
                Arrays.fill(runAt, MeshTopology.NONE);
                int slivers = 0;
                for (int unit = 0; unit < unitCount; unit++) {
                    if (unitKeeper[unit] == MeshTopology.NONE || unitCutRing[unit] != ring) {
                        continue;
                    }
                    int side = lensRings[units[unit]][0] == ring ? 0 : 1;
                    for (int step = 0; step < unitLength[unit][side]; step++) {
                        runAt[(unitStart[unit][side] + step) % loopLength] = unit;
                    }
                    slivers += unitSlivers[unit];
                }
                // Where each of the ring's spline anchors, authored and supporting, lies on its
                // loop, and which way round the loop they run.
                int[] own = splineAnchors[ring];
                int anchorCount = own.length;
                int[] anchorAt = new int[anchorCount];
                int forward = 0;
                for (int anchor = 0; anchor < anchorCount; anchor++) {
                    mesh.vertexPosition(own[anchor], position);
                    double nearest = Double.POSITIVE_INFINITY;
                    for (int index = 0; index < loopLength && nearest > 0.0; index++) {
                        double away = loop[index] == own[anchor] ? 0.0
                                : mesh.vertexPosition(loop[index], loopPosition).distance(position);
                        if (away < nearest) {
                            nearest = away;
                            anchorAt[anchor] = index;
                        }
                    }
                }
                for (int anchor = 0; anchor < anchorCount; anchor++) {
                    int step = Math.floorMod(anchorAt[(anchor + 1) % anchorCount]
                            - anchorAt[anchor], loopLength);
                    forward += step == 0 ? 0 : 2 * step < loopLength ? 1 : -1;
                }
                int direction = forward >= 0 ? 1 : -1;
                int firstBisector = bisectorSplines.size();
                String refused = anchorCount < SurfaceSplineTracer.MINIMUM_ANCHORS
                        ? labels[ring] + " is not dropped: it has no spline anchors to carry" : "";
                // Each stretch outside the runs, from the end of one run to the start of the next,
                // less its ends where the ring still runs along that run's keeper.
                for (int index = 0; index < loopLength && refused.isEmpty(); index++) {
                    if (runAt[index] != MeshTopology.NONE
                            || runAt[(index + loopLength - 1) % loopLength] == MeshTopology.NONE) {
                        continue;
                    }
                    int from = index;
                    int to = index;
                    while (runAt[to % loopLength] == MeshTopology.NONE) {
                        to++;
                    }
                    int before = unitKeeper[runAt[(from + loopLength - 1) % loopLength]];
                    int after = unitKeeper[runAt[to % loopLength]];
                    while (from < to && marks[before][mesh.edgeBetween(loop[from % loopLength],
                            loop[(from + 1) % loopLength])]) {
                        from++;
                    }
                    while (to > from && marks[after][mesh.edgeBetween(
                            loop[(to - 1) % loopLength], loop[to % loopLength])]) {
                        to--;
                    }
                    if (from == to) {
                        continue;
                    }
                    // The ring's anchors over the stretch in the ring's own order, and one past
                    // each end, inside the runs, so the trace crosses where the ring met its
                    // keepers; with none inside, the two either side of it.
                    int origin = direction > 0 ? from : to;
                    int length = to - from;
                    int firstInner = -1;
                    int behindMost = 0;
                    for (int anchor = 0; anchor < anchorCount; anchor++) {
                        int ahead = Math.floorMod(direction * (anchorAt[anchor] - origin),
                                loopLength);
                        int behind = Math.floorMod(-direction * (anchorAt[anchor] - origin),
                                loopLength);
                        if (ahead > 0 && ahead < length && (firstInner < 0 || ahead < Math.floorMod(
                                direction * (anchorAt[firstInner] - origin), loopLength))) {
                            firstInner = anchor;
                        }
                        behindMost = behind < Math.floorMod(-direction * (anchorAt[behindMost]
                                - origin), loopLength) ? anchor : behindMost;
                    }
                    int lead = firstInner >= 0 ? Math.floorMod(firstInner - 1, anchorCount)
                            : behindMost;
                    // A stretch running all round the ring between two anchors carries every
                    // anchor and the two either side of its run again: one more than the ring.
                    List<Integer> carried = new ArrayList<>(List.of(lead));
                    for (int anchor = (lead + 1) % anchorCount; firstInner >= 0
                            && carried.size() <= anchorCount + 1;
                            anchor = (anchor + 1) % anchorCount) {
                        int ahead = Math.floorMod(direction * (anchorAt[anchor] - origin),
                                loopLength);
                        carried.add(anchor);
                        if (ahead <= 0 || ahead >= length) {
                            break;
                        }
                    }
                    if (firstInner < 0) {
                        carried.add((lead + 1) % anchorCount);
                    }
                    int[] stretchAnchors = new int[carried.size()];
                    for (int slot = 0; slot < stretchAnchors.length; slot++) {
                        stretchAnchors[slot] = own[carried.get(slot)];
                    }
                    int startVertex = loop[(direction > 0 ? from : to) % loopLength];
                    int endVertex = loop[(direction > 0 ? to : from) % loopLength];
                    int[] stretch = { own[Math.floorMod(carried.get(0) - 1, anchorCount)],
                        startVertex, endVertex,
                        own[(carried.get(carried.size() - 1) + 1) % anchorCount] };
                    int[] walls = {
                        finalWall(direction > 0 ? before : after, startVertex, ringCount,
                                endPosition),
                        finalWall(direction > 0 ? after : before, endVertex, ringCount,
                                endPosition) };
                    SurfaceSpline spline = SurfaceSpline.ringStretch(geodesics, stretchAnchors,
                            stretch, wallMarks(walls[0], standingMarks),
                            wallMarks(walls[1], standingMarks));
                    if (spline.marks.markedEdgeCount == 0) {
                        refused = labels[ring] + " is not dropped: its stretch from "
                                + labels[before] + " to " + labels[after] + " traces no edge";
                        continue;
                    }
                    // The route must be the ring's own: the edges it marks are the old stretch's.
                    boolean[] oldEdges = new boolean[spline.marks.markedByEdgeId.length];
                    int onlyOld = 0;
                    String missing = "";
                    for (int along = from; along < to; along++) {
                        int edgeId = mesh.edgeBetween(loop[along % loopLength],
                                loop[(along + 1) % loopLength]);
                        oldEdges[edgeId] = true;
                        boolean kept = spline.marks.markedByEdgeId[edgeId];
                        onlyOld += kept ? 0 : 1;
                        missing += kept ? "" : " " + (along - from);
                    }
                    int onlyNew = 0;
                    for (int edgeId : spline.marks.markedEdgeIds) {
                        onlyNew += oldEdges[edgeId] ? 0 : 1;
                    }
                    if (onlyOld + onlyNew > 0) {
                        routeDifferences.add(labels[ring] + " from " + labels[before] + " to "
                                + labels[after] + ": " + onlyNew + " edge(s) only on the bisector, "
                                + onlyOld + " only on the old ring, of " + length
                                + (missing.isEmpty() ? "" : ", missing at steps" + missing)
                                + " (anchors run " + (direction > 0 ? "with" : "against")
                                + " the loop)");
                    }
                    bisectorAnchors.add(stretchAnchors);
                    bisectorStretches.add(stretch);
                    bisectorWalls.add(walls);
                    bisectorSplines.add(spline);
                    bisectorSource.add(ring);
                }
                // Every bisector ending on the ring moves to the new wall nearest that end: one of
                // the ring's bisectors, or what a keeper of its runs now is.
                int[][] movedTo = new int[ringCount][];
                int[][] movedEnds = new int[ringCount][];
                SurfaceSpline[] retraced = new SurfaceSpline[ringCount];
                for (int other = 0; other < ringCount && refused.isEmpty(); other++) {
                    int[] walls = wallsNow[other];
                    if (walls == null || walls[0] != ring && walls[1] != ring) {
                        continue;
                    }
                    int[] moved = walls.clone();
                    int[] ends = anchorsNow[other].clone();
                    for (int end = 0; end < 2; end++) {
                        if (moved[end] != ring) {
                            continue;
                        }
                        // A ring stretch's end anchor lies past its wall; its end is where it met
                        // the wall, which stays its meeting point on the new one.
                        int[] stretch = stretchOfBisector[other];
                        int vertexId = stretch != null ? stretch[1 + end]
                                : ends[end == 0 ? 0 : ends.length - 1];
                        mesh.vertexPosition(vertexId, endPosition);
                        double nearest = Double.POSITIVE_INFINITY;
                        List<Integer> candidates = new ArrayList<>();
                        for (int bisector = firstBisector; bisector < bisectorSplines.size();
                                bisector++) {
                            candidates.add(ringCount + bisector);
                        }
                        for (int unit = 0; unit < unitCount; unit++) {
                            if (unitKeeper[unit] != MeshTopology.NONE && unitCutRing[unit] == ring) {
                                candidates.add(finalWall(unitKeeper[unit], vertexId, ringCount,
                                        endPosition));
                            }
                        }
                        for (int candidate : candidates) {
                            int attached = RingAttachment.attach(mesh,
                                    wallMarks(candidate, standingMarks), endPosition.x,
                                    endPosition.y, endPosition.z);
                            double away = attached < 0 ? Double.POSITIVE_INFINITY
                                    : mesh.vertexPosition(attached, new Vector3f())
                                            .distance(endPosition);
                            if (away < nearest) {
                                nearest = away;
                                moved[end] = candidate;
                                ends[end == 0 ? 0 : ends.length - 1] = stretch != null
                                        ? ends[end == 0 ? 0 : ends.length - 1] : attached;
                            }
                        }
                    }
                    SurfaceSpline spline = moved[0] == ring || moved[1] == ring ? null
                            : stretchOfBisector[other] != null
                                    ? SurfaceSpline.ringStretch(geodesics, ends.clone(),
                                            stretchOfBisector[other],
                                            wallMarks(moved[0], standingMarks),
                                            wallMarks(moved[1], standingMarks))
                                    : SurfaceSpline.bisector(geodesics,
                                            modes[other] == RingSegmentMode.CREASE ? creases
                                                    : null,
                                            ends.clone(), wallMarks(moved[0], standingMarks),
                                            wallMarks(moved[1], standingMarks));
                    if (spline == null || spline.marks.markedEdgeCount == 0) {
                        refused = labels[ring] + " is not dropped: bisector " + labels[other]
                                + " ending on it would wall nothing once moved";
                        continue;
                    }
                    movedTo[other] = moved;
                    retraced[other] = spline;
                    movedEnds[other] = ends;
                }
                if (!refused.isEmpty()) {
                    for (List<?> list : List.of(bisectorAnchors, bisectorStretches, bisectorWalls,
                            bisectorSplines, bisectorSource)) {
                        list.subList(firstBisector, list.size()).clear();
                    }
                    refusals.add(refused + " (" + slivers + " sliver(s) left)");
                    continue;
                }
                for (int other = 0; other < ringCount; other++) {
                    if (movedTo[other] == null) {
                        continue;
                    }
                    standingMarks[other] = retraced[other].marks.markedByEdgeId;
                    wallsNow[other] = movedTo[other];
                    anchorsNow[other] = movedEnds[other];
                    movedWalls[other] = movedTo[other];
                    movedAnchors[other] = movedEnds[other];
                    movedSplines[other] = retraced[other];
                }
                ringDropped[ring] = true;
                collapsedSlivers += slivers;
            }
        }
        return collapsedSlivers;
    }

    /**
     * What a keeper is once the rings before it dropped: itself while it stands, else its new
     * bisector nearest a vertex.
     *
     * @param keeper    ring index of the keeper
     * @param vertexId  the vertex an end sits at, on or beside the keeper's old path
     * @param ringCount rings the regions hold, the offset new bisectors are coded from
     * @param scratch   scratch position
     * @return the wall code, as {@link #bisectorWalls} holds it
     */
    private int finalWall(int keeper, int vertexId, int ringCount, Vector3f scratch) {
        if (!ringDropped[keeper]) {
            return keeper;
        }
        MeshTopology mesh = geodesics.mesh;
        mesh.vertexPosition(vertexId, scratch);
        int nearest = keeper;
        double nearestDistance = Double.POSITIVE_INFINITY;
        Vector3f attachedPosition = new Vector3f();
        for (int bisector = 0; bisector < bisectorSplines.size(); bisector++) {
            if (bisectorSource.get(bisector) != keeper) {
                continue;
            }
            int attached = RingAttachment.attach(mesh,
                    bisectorSplines.get(bisector).marks.markedByEdgeId, scratch.x, scratch.y,
                    scratch.z);
            double away = attached < 0 ? Double.POSITIVE_INFINITY
                    : mesh.vertexPosition(attached, attachedPosition).distance(scratch);
            if (away < nearestDistance) {
                nearestDistance = away;
                nearest = ringCount + bisector;
            }
        }
        return nearest;
    }

    /**
     * A wall's edges by its code: a ring's as it stands, or a new bisector's.
     *
     * @param wall          the code, as {@link #bisectorWalls} holds it
     * @param standingMarks each ring's marks as it stands, a moved bisector's retraced
     * @return the edge-id-indexed marks
     */
    private boolean[] wallMarks(int wall, boolean[][] standingMarks) {
        return wall < standingMarks.length ? standingMarks[wall]
                : bisectorSplines.get(wall - standingMarks.length).marks.markedByEdgeId;
    }

    /**
     * Whether two rings' stretches end together: each end of one meets an end of the other, the
     * same vertex or joined by edges both rings mark, where they run together before parting.
     *
     * @param mesh        surface the marks index by edge id
     * @param ends        the first ring's two stretch ends, then the second ring's two
     * @param firstMarks  the first ring's edge-id-indexed marks
     * @param secondMarks the second ring's
     * @return true when the ends pair up, in either order
     */
    private static boolean stretchEndsMeet(MeshTopology mesh, int[] ends, boolean[] firstMarks,
            boolean[] secondMarks) {
        boolean[] meets = new boolean[2 + 2];
        for (int pairing = 0; pairing < meets.length; pairing++) {
            int first = ends[pairing / 2];
            int second = ends[2 + pairing % 2];
            List<Integer> frontier = new ArrayList<>(List.of(first));
            for (int head = 0; head < frontier.size() && !meets[pairing]; head++) {
                int vertexId = frontier.get(head);
                meets[pairing] = vertexId == second;
                for (int spoke = 0; spoke < mesh.vertexEdgeCount(vertexId); spoke++) {
                    int edgeId = mesh.vertexEdgeAt(vertexId, spoke);
                    boolean shared = edgeId < firstMarks.length && firstMarks[edgeId]
                            && edgeId < secondMarks.length && secondMarks[edgeId];
                    int other = mesh.edgeOtherVertex(edgeId, vertexId);
                    if (shared && !frontier.contains(other)) {
                        frontier.add(other);
                    }
                }
            }
        }
        // Pairings: 0 is first-first, 1 first-second, 2 second-first, 3 second-second.
        return meets[0] && meets[3] || meets[1] && meets[2];
    }
}
