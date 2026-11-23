package ixdar.geometry.mesh.data.rings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.paths.geodesic.DirectAirRoutes;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceLifetime;
import ixdar.geometry.mesh.data.paths.geodesic.SurfaceMetric;
import ixdar.geometry.mesh.data.paths.geodesic.WidthLimitedFlow;
import ixdar.geometry.mesh.data.paths.query.SurfaceCreases;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.spline.SurfaceSplineTracer;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.numeric.cluster.EMeans;
import ixdar.numeric.graph.Dijkstra;
import ixdar.numeric.graph.UnionFind;

/**
 * Fits rings by clustering faces into the regions they bound, each ring
 * stepping a face strip at a time to lower the regions' e-means balance plus
 * its pull toward the nearest groove.
 */
public final class RegionClusterFit {

    public static final double DEFAULT_CROSSING_WEIGHT = 100000.0;

    public static final double DEFAULT_PULL_WEIGHT = 2.0;

    public static final int DEFAULT_BATCH_FACES = 1024;

    public static final double DEFAULT_ALPHA0 = 0.5;

    public static final int DEFAULT_MAX_PASSES = 400;

    public static final int STILL_PASSES_BEFORE_STOP = 5;

    public static final int PUSH_ROW_BEFORE_DIRECTIONS = 5;

    public static final long DEFAULT_SEED = 1234;

    public static final double GROOVE_BOTTOM_CURVATURE_TOLERANCE = 1e-3;

    public static final int TRAVEL_FADE_RATES = 8;

    public static final double ALL_BUT_SWALLOWED = 1 - 1e-9;

    private static final double NO_GAIN = 1e-9;

    private static final double WASTE_TIE_SHARE_OF_LIFETIME = 1e-9;

    private static final int MEASURED_MOVES_PER_RING = 4;

    private static final int XYZ = 3;

    private static final int SIDES = 2;

    /** The surface fitted on. */
    public final MeshTopology mesh;

    /** The geometry the fit was built from, its rings as drawn. */
    public final GeometryBundle drawn;

    /** Labels of the rings, in the order the bundle's splines carry them. */
    public final String[] ringLabels;

    /** Each ring's loop of edges, as it stands now. */
    public final int[][] loops;

    /** Whether each ring moves: a closed ring no bisector ends on. */
    public final boolean[] movable;

    /**
     * Whether each ring has one region on both sides, so it feels the pull alone.
     */
    public final boolean[] splitsNothing;

    /** Region of each face, by face id; -1 off the surface. */
    public final int[] regionOfFace;

    /**
     * For each bisector that moves, the two closed rings its first and last end sit
     * on; null for every other ring.
     */
    public final int[][] endRings;

    /** Number of regions. */
    public final int regionCount;

    /** Each region's centroid face id, as of the last pass. */
    public final int[] centroidFace;

    /** Distance of each vertex to the nearest groove bottom, by vertex id. */
    public final double[] grooveDistance;

    /** Weight of the balance distance's groove crossing. */
    public double crossingWeight = DEFAULT_CROSSING_WEIGHT;

    /** Weight of the ring edges' pull toward the grooves. */
    public double pullWeight = DEFAULT_PULL_WEIGHT;

    /** Faces each pass's e-means batch draws. */
    public int batchFaces = DEFAULT_BATCH_FACES;

    /** The e-means step at the first pass, annealed to a tenth by the last. */
    public double alpha0 = DEFAULT_ALPHA0;

    /** The most passes a fit runs. */
    public int maxPasses = DEFAULT_MAX_PASSES;

    /** Seed of the batch draws. */
    public long seed = DEFAULT_SEED;

    /** Passes run by the last {@link #run}. */
    public int passes;

    /** Ring moves made by the last {@link #run}. */
    public int moves;

    /**
     * Whether a ring between two regions first tries the geodesic Voronoi split of
     * the pair before stepping a strip.
     */
    public boolean voronoi = true;

    /**
     * Whether a ring steps by sweeping the faces beside a run of its loop vertices,
     * any length from one vertex to the whole loop, instead of a whole strip.
     */
    public boolean localMoves = true;

    /** Weight of a local move's change in loop length, in mean edge lengths. */
    public double lengthWeight;

    /** Ring moves of the last {@link #run} that were Voronoi splits. */
    public int voronoiMoves;

    /** Moves the topology rule refused in the last {@link #run}. */
    public int refusals;

    /**
     * Each region's regions-and-rings state after every pass of the last run, when
     * recording.
     */
    public final List<int[][]> loopsByPass = new ArrayList<>();

    /** The way the air drifts over each face as it was last measured: three coordinates by face id. */
    public float[] airDrift;

    /** Whether {@link #run} records {@link #loopsByPass}. */
    public boolean recordPasses;

    /** Why the last refused move was refused. */
    public String lastRefusal = "";

    /**
     * Whether the fit runs on walker lifetimes, without weights: rings and groove bottoms absorb
     * a random walker, a ring lowers {@link #waste} plus the lifetime the rings themselves end and,
     * where waste ties, {@link #deathRate}.
     */
    public boolean lifetime;

    /**
     * Whether the fit runs on air flow, without weights: every region holds the same amount of air,
     * which flows to its seams round the groove bottoms, and a seam moves away from the side whose
     * air reaches it harder.
     */
    public boolean airFlow;

    /**
     * Whether, under {@link #airFlow}, a vertex on a valley's low line lets by one minus its crease
     * strength of the air, with no wall picked out and no seam held; otherwise a groove bottom lets
     * none by.
     */
    public boolean gradedWalls;

    /** Whether, under {@link #gradedWalls}, every vertex is graded so, on a valley's low line or off it. */
    public boolean gradedBand;

    /** Whether, under {@link #gradedWalls}, a seam is still held where it runs along a groove bottom. */
    public boolean gradedHeld;

    /**
     * Whether, under {@link #airFlow}, each bit of air pushes a seam in inverse measure of how far
     * it travelled to reach it, so air sent a long way round a crease counts for less.
     */
    public boolean travelWeighted;

    /**
     * Whether, under {@link #travelWeighted}, a region's area still divides its push, as it does
     * when all air counts alike.
     */
    public boolean travelKeepsPressure;

    /**
     * Whether, under {@link #airFlow}, air rises only from the creases, each vertex's crease
     * strength of it, and a seam moves toward the side more of it arrives from. Nothing is a wall
     * and no region's area enters.
     */
    public boolean creaseSource;

    /** Whether, under {@link #creaseSource}, only a valley's low line gives off air, not its flanks. */
    public boolean creaseSourceLineOnly;

    /**
     * Whether, under {@link #airFlow}, the seams give off the air and the creases swallow it, and
     * a seam moves toward the side more of its air leaves by. Nothing is a wall and no region's
     * area enters.
     */
    public boolean ringSource;

    /**
     * Whether, under {@link #airFlow} with air rising evenly and each region's area dividing its
     * push, a crease swallows air crossing it, so less of it arrives. Nothing is a wall or a pin;
     * {@link #ringSourceLineOnly} and {@link #ringSourceByCurvature} set how much is swallowed.
     */
    public boolean creasesSwallow;

    /**
     * Whether a seam lying along a wall is left free to move like any other, so only the air the
     * wall cuts off keeps it there, and a short wall such as a pit's cannot hook it.
     */
    public boolean wallsUnpinned;

    /** Whether, under {@link #ringSource}, only a valley's low line swallows air, not its flanks. */
    public boolean ringSourceLineOnly;

    /**
     * Whether, under {@link #ringSource}, a vertex swallows at the rate of its valley curvature
     * squared, in place of a chance equal to its crease strength each time air steps on it.
     */
    public boolean ringSourceByCurvature;

    /**
     * Under {@link #gradedWalls}, the power the crease strength is raised to before it is taken
     * from one: 1 grades evenly, and the higher it is the more only the strongest creases slow air.
     */
    public double gradedSharpness = 1;

    /**
     * Whether, under {@link #airFlow}, a groove's wall runs on from its strong vertices along the
     * valley's low line, so a groove weaker in places has no gaps.
     */
    public boolean closedWalls;

    /**
     * Whether, under {@link #airFlow}, every vertex on a valley's low line is a wall, whatever its
     * crease strength, so no strength decides what is a groove.
     */
    public boolean ungatedWalls;

    /**
     * Whether, under {@link #airFlow}, a region's air all rises at its centre, the vertex farthest
     * from its seams; otherwise it rises evenly over the region.
     */
    public boolean centreSource;

    /**
     * Whether, under {@link #airFlow}, a region pushes a seam by the share of its air arriving there
     * alone, with no more push for being small.
     */
    public boolean shareOnly;

    /**
     * Whether, under {@link #airFlow}, the shares of air decide a seam beside a region holding a
     * wall no seam lies on, and the push for being small decides only the other seams.
     */
    public boolean shelterFirst;

    /**
     * Whether, under {@link #airFlow}, a wall lets by only the air its gaps are wide enough for, and
     * the side of a seam that such walls starve gives way before any other push is weighed.
     */
    public boolean bottleneck;

    /**
     * Whether, under {@link #bottleneck}, every seam edge drains at once and takes its fairest
     * share, so each seam vertex has shares of its own and not one for its whole free stretch.
     */
    public boolean fairShares;

    /**
     * Whether, under {@link #bottleneck}, each face's air takes its shortest way to a seam and a
     * seam edge's share is the air walls still let reach it over the air open surface would.
     */
    public boolean directRoutes;

    /**
     * Whether, under {@link #directRoutes}, a seam edge's share is the air reaching it over the
     * air its length is due of its region's, so the inside of a bend is starved as a wall's lee is.
     */
    public boolean arrivalShares;

    /**
     * Whether, under {@link #directRoutes}, a side's share multiplies its pressure push and does
     * not replace it, so the pressure's resistance to a bend still acts where a wall starves.
     */
    public boolean sharesScalePressure;

    /**
     * Whether a side's share at a seam edge is how crowded the worst face its air came through
     * would be with no walls, over how crowded it is with them.
     */
    public boolean crowdingShares;

    /**
     * Whether a side's share at a seam edge is how late its air would arrive with no walls, over
     * how late it arrives with them.
     */
    public boolean delayShares;

    /** Whether two sides' shares closer than one face's air makes of them count as a tie. */
    public boolean grainTies;

    /** The air's shortest ways round the walls as it was last measured; null unless {@link #directRoutes}. */
    public DirectAirRoutes routes;

    /**
     * The lifetime the groove bottoms no ring lies on cost: the walkers' total lifetime with only
     * the rings absorbing, less that with the groove bottoms absorbing too.
     */
    public double waste;

    /** The regions' summed death rates: each region's area over its walkers' total lifetime. */
    public double deathRate;

    /**
     * Whether each vertex is a groove bottom, by vertex id: in a groove, with less valley curvature
     * at both its steepest-falling neighbour and the neighbour most opposite that one.
     */
    public final boolean[] grooveBottom;

    /** The groove bottoms run on along the valley's low line from every strong vertex, by vertex id. */
    public final boolean[] closedBottom;

    /** Whether each vertex is the low line across a valley of any strength, by vertex id. */
    public final boolean[] valleyLine;

    private SurfaceLifetime walker;

    private double[] steepness;

    private double[] steepnessFree;

    private double lifetimeFree;

    private double lifetimeHeld;

    /** The seam arrangements the air-flow fit has stood at, by the hash of their edges. */
    private final Map<Integer, Boolean> seenSeams = new LinkedHashMap<>();

    /** The live face ids in active order, as {@link #begin} listed them. */
    private int[] passFaces;

    /** The centre of each of {@link #passFaces}, three coordinates each. */
    private double[] passPoints;

    /** The centroids {@link #stepPass} moves by one batch a pass. */
    private EMeans means;

    /** The region a Voronoi split would give each face, by face id; scratch of {@link #stepPass}. */
    private int[] assigned;

    /** How many passes in a row ended with no ring moved. */
    private int still;

    /** Whether the passes weigh each region's distances, which only the centroid fit does. */
    private boolean balanced;

    /** The recorded pass {@link #standAtPass} last stood the seams at, -1 while they stand live. */
    private int stoodAt = -1;

    /** Whether each region holds a wall no seam lies on, as the air was last measured. */
    private boolean[] sheltered;

    /** The wall vertices no seam lies on, by vertex id, as the air was last measured. */
    private boolean[] airWall;

    /** The edges a seam lies on, by edge id, as the air was last measured. */
    private boolean[] borderEdge;

    /** The length of seam round each region, an edge counted for each side that is the region's. */
    private double[] regionSeamLength;

    /** How hard the air arrives over each face: the size of its flow, by face id. */
    private double[] arrival;

    /**
     * The share of its open-surface air each seam edge still takes from either side once walls
     * limit it, at twice the edge id plus the side; null unless {@link #fairShares}.
     */
    private double[] fairShare;

    /**
     * The share one face's air makes of what each seam edge's pocket is due, as {@link #fairShare}
     * is laid out; shares closer than this tie. Null where any difference counts.
     */
    private double[] shareGrain;

    /**
     * A seam's fans and the push on each of its vertices from either side.
     *
     * @param cycle    the seam's half-edges in order
     * @param fans     the faces round each seam vertex, by side then vertex
     * @param push     the pressure of the region on a side times the flow arriving there
     * @param onBottom whether each seam vertex lies on a groove bottom
     * @param starved  whether the shares of air walls let by decided each vertex's push, not pressure
     */
    private record SeamPush(int[] cycle, int[][][] fans, double[][] push, boolean[] onBottom, boolean[] starved) {
    }

    private final double[] regionArea;

    private final double[] regionLifetime;

    private final double[] faceArea;

    private final float[] faceCentroid;

    private final float[] grooveStrength;

    private final float[] grooveCurvature;

    private final double meanFaceArea;

    private final double meanEdgeLength;

    private final double[][] distanceByRegion;

    private final Dijkstra faceWalk = new Dijkstra();

    private final int[] edgeStamp;

    private int edgeEpoch;

    private final int[] loopStamp;

    private final int[] flipStamp;

    private final boolean[] flipped;

    private final int[] faceStamp;

    private final int[] wallStamp;

    private int wallEpoch;

    private int runEpoch;

    /**
     * A ring move found by {@link #bestRunMove}: the energy change, the loop the ring
     * would stand on, the faces it sweeps and the region they join.
     *
     * @param change  the energy change, negative
     * @param loop    the new loop's edge ids
     * @param faces   the swept face ids
     * @param growing  the region the swept faces join
     * @param followed the new paths of the bisectors ending on the ring, by ring
     *                 index, null where a path stays
     */
    private record RunMove(double change, int[] loop, int[] faces, int growing, int[][] followed) {
    }

    /**
     * A fit of a bundle's rings, as the bundle carries them, over its surface's
     * grooves.
     *
     * @param bundle  the surface with its rings, as spline_ring and spline_cut
     *                leave them
     * @param creases groove strength measured on that surface
     * @throws IllegalArgumentException when the bundle carries no rings
     */
    public RegionClusterFit(GeometryBundle bundle, SurfaceCreases creases) {
        mesh = bundle.mesh();
        drawn = bundle;
        List<String> labels = new ArrayList<>();
        if (bundle.slots().get(SurfaceSpline.SLOT) instanceof Map<?, ?> splines) {
            for (Object label : splines.keySet()) {
                labels.add(String.valueOf(label));
            }
        }
        if (labels.isEmpty()) {
            throw new IllegalArgumentException("region_cluster_fit: the bundle carries no rings");
        }
        ringLabels = labels.toArray(new String[0]);
        RingRegions regions = RingRegions.ofBundle(bundle, String.join(",", ringLabels));
        regionCount = regions.regionCount;
        regionOfFace = new int[mesh.faceIdBound()];
        Arrays.fill(regionOfFace, -1);
        faceArea = new double[mesh.faceIdBound()];
        faceCentroid = new float[XYZ * mesh.faceIdBound()];
        Vector3f at = new Vector3f();
        double areaSum = 0;
        for (int active = 0; active < mesh.faceCount(); active++) {
            int face = mesh.faceIdAt(active);
            regionOfFace[face] = regions.regionByActiveFace[active];
            faceArea[face] = mesh.faceArea(face);
            areaSum += faceArea[face];
            at.set(mesh.faceCentroid(face));
            faceCentroid[XYZ * face] = at.x;
            faceCentroid[XYZ * face + 1] = at.y;
            faceCentroid[XYZ * face + 2] = at.z;
        }
        meanFaceArea = areaSum / Math.max(1, mesh.faceCount());
        meanEdgeLength = mesh.meanEdgeLength();
        grooveStrength = creases.valleyStrength;
        grooveCurvature = creases.valleyCurvature;
        grooveDistance = grooveBottomDistance(creases);
        grooveBottom = new boolean[mesh.vertexIdBound()];
        valleyLine = new boolean[mesh.vertexIdBound()];
        for (int active = 0; active < mesh.vertexCount(); active++) {
            int vertex = mesh.vertexIdAt(active);
            int lowest = -1;
            for (int spoke = 0; spoke < mesh.vertexEdgeCount(vertex); spoke++) {
                int other = mesh.edgeOtherVertex(mesh.vertexEdgeAt(vertex, spoke), vertex);
                lowest = lowest < 0 || creases.valleyCurvature[other] < creases.valleyCurvature[lowest] ? other
                        : lowest;
            }
            if (lowest < 0 || creases.valleyCurvature[lowest] >= creases.valleyCurvature[vertex]) {
                continue;
            }
            // Across the groove is the way its curvature bends where the creases know it, and toward
            // the neighbour that curves least where they do not.
            mesh.vertexPosition(vertex, at);
            Vector3f down = creases.acrossDirection == null
                    ? mesh.vertexPosition(lowest, new Vector3f()).sub(at).normalize()
                    : new Vector3f(creases.acrossDirection[XYZ * vertex], creases.acrossDirection[XYZ * vertex + 1],
                            creases.acrossDirection[XYZ * vertex + 2]);
            Vector3f spokeAlong = new Vector3f();
            int opposite = -1;
            int ahead = -1;
            float mostOpposite = Float.POSITIVE_INFINITY;
            float mostAhead = Float.NEGATIVE_INFINITY;
            for (int spoke = 0; spoke < mesh.vertexEdgeCount(vertex); spoke++) {
                int other = mesh.edgeOtherVertex(mesh.vertexEdgeAt(vertex, spoke), vertex);
                float along = mesh.vertexPosition(other, spokeAlong).sub(at).normalize().dot(down);
                if (along < mostOpposite) {
                    mostOpposite = along;
                    opposite = other;
                }
                if (along > mostAhead) {
                    mostAhead = along;
                    ahead = other;
                }
            }
            // The curvature as measured where the creases kept it: smoothing moves a groove's peak
            // sideways near a crossing.
            float[] depth = creases.valleySignal == null || creases.acrossDirection == null ? creases.valleyCurvature
                    : creases.valleySignal;
            valleyLine[vertex] = depth[opposite] < depth[vertex] && depth[ahead] < depth[vertex];
            grooveBottom[vertex] = valleyLine[vertex]
                    && creases.valleyStrength[vertex] >= SurfaceCreases.CREASE_STRENGTH;
        }
        // A groove's wall runs on from its strong vertices along the valley's low line, so it has no gaps.
        int[] wall = new int[mesh.vertexCount()];
        int walled = 0;
        for (int active = 0; active < mesh.vertexCount(); active++) {
            if (grooveBottom[mesh.vertexIdAt(active)]) {
                wall[walled++] = mesh.vertexIdAt(active);
            }
        }
        closedBottom = grooveBottom.clone();
        for (int next = 0; next < walled; next++) {
            for (int spoke = 0; spoke < mesh.vertexEdgeCount(wall[next]); spoke++) {
                int other = mesh.edgeOtherVertex(mesh.vertexEdgeAt(wall[next], spoke), wall[next]);
                if (valleyLine[other] && !closedBottom[other]) {
                    closedBottom[other] = true;
                    wall[walled++] = other;
                }
            }
        }
        regionArea = new double[regionCount];
        regionLifetime = new double[regionCount];
        loops = new int[ringLabels.length][];
        movable = new boolean[ringLabels.length];
        splitsNothing = new boolean[ringLabels.length];
        int[] degree = new int[mesh.vertexIdBound()];
        boolean[] onOpenRing = new boolean[mesh.vertexIdBound()];
        boolean[] closedRing = new boolean[ringLabels.length];
        for (int ring = 0; ring < ringLabels.length; ring++) {
            loops[ring] = regions.ringEdgeIds[ring].clone();
            splitsNothing[ring] = regions.ringSplitsNothing[ring];
            Arrays.fill(degree, 0);
            for (int edge : loops[ring]) {
                degree[mesh.halfEdgeVertex(mesh.edgeHalfEdge(edge))]++;
                degree[mesh.halfEdgeEndVertex(mesh.edgeHalfEdge(edge))]++;
            }
            boolean closed = loops[ring].length > 0 && Arrays.stream(loops[ring])
                    .allMatch(edge -> degree[mesh.halfEdgeVertex(mesh.edgeHalfEdge(edge))] == 2
                            && degree[mesh.halfEdgeEndVertex(mesh.edgeHalfEdge(edge))] == 2);
            movable[ring] = closed;
            closedRing[ring] = closed;
        }
        endRings = new int[ringLabels.length][];
        loopStamp = new int[mesh.edgeIdBound()];
        wallStamp = new int[mesh.edgeIdBound()];
        for (int ring = 0; ring < ringLabels.length; ring++) {
            int[] path = loops[ring].length == 0 || closedRing[ring] ? null : cycle(loops[ring]);
            if (path == null || mesh.halfEdgeEndVertex(path[path.length - 1]) == mesh.halfEdgeVertex(path[0])) {
                continue;
            }
            int[] ends = { mesh.halfEdgeVertex(path[0]), mesh.halfEdgeEndVertex(path[path.length - 1]) };
            int[] on = { -1, -1 };
            for (int other = 0; other < ringLabels.length; other++) {
                for (int edge : other == ring || !closedRing[other] ? new int[0] : loops[other]) {
                    for (int end = 0; end < SIDES; end++) {
                        on[end] = mesh.halfEdgeVertex(mesh.edgeHalfEdge(edge)) == ends[end]
                                || mesh.halfEdgeEndVertex(mesh.edgeHalfEdge(edge)) == ends[end] ? other : on[end];
                    }
                }
            }
            if (on[0] >= 0 && on[1] >= 0 && on[0] != on[1]) {
                endRings[ring] = on;
                movable[ring] = true;
            }
        }
        for (int ring = 0; ring < ringLabels.length; ring++) {
            for (int edge : closedRing[ring] || endRings[ring] != null ? new int[0] : loops[ring]) {
                onOpenRing[mesh.halfEdgeVertex(mesh.edgeHalfEdge(edge))] = true;
                onOpenRing[mesh.halfEdgeEndVertex(mesh.edgeHalfEdge(edge))] = true;
            }
        }
        for (int ring = 0; ring < ringLabels.length; ring++) {
            for (int edge : closedRing[ring] ? loops[ring] : new int[0]) {
                int halfEdge = mesh.edgeHalfEdge(edge);
                if (onOpenRing[mesh.halfEdgeVertex(halfEdge)] || onOpenRing[mesh.halfEdgeEndVertex(halfEdge)]) {
                    movable[ring] = false;
                }
            }
        }
        centroidFace = new int[regionCount];
        distanceByRegion = new double[regionCount][mesh.faceIdBound()];
        edgeStamp = new int[mesh.edgeIdBound()];
        flipStamp = new int[mesh.edgeIdBound()];
        flipped = new boolean[mesh.edgeIdBound()];
        faceStamp = new int[mesh.faceIdBound()];
    }

    /**
     * Run passes until no ring has moved for {@link #STILL_PASSES_BEFORE_STOP} passes, or
     * {@link #maxPasses}: {@link #begin}, then {@link #stepPass} until none remains.
     *
     * @return this, the loops and regions fitted
     */
    public RegionClusterFit run() {
        begin();
        while (stepPass()) {
            // Each pass moves what it can; the fit ends when a pass moves nothing.
        }
        return this;
    }

    /**
     * Ready the fit for its first pass: the centroids at each region's centre, the counts at
     * zero, the seams as drawn recorded, and under air flow the air measured as they stand.
     */
    public void begin() {
        int[] faces = new int[mesh.faceCount()];
        double[] points = new double[XYZ * faces.length];
        double[] weights = new double[faces.length];
        double[] initialMeans = new double[XYZ * regionCount];
        double[] mass = new double[regionCount];
        for (int active = 0; active < faces.length; active++) {
            faces[active] = mesh.faceIdAt(active);
            int region = regionOfFace[faces[active]];
            weights[active] = faceArea[faces[active]];
            mass[region] += weights[active];
            for (int axis = 0; axis < XYZ; axis++) {
                points[XYZ * active + axis] = faceCentroid[XYZ * faces[active] + axis];
                initialMeans[XYZ * region + axis] += weights[active] * points[XYZ * active + axis];
            }
        }
        for (int coordinate = 0; coordinate < initialMeans.length; coordinate++) {
            initialMeans[coordinate] /= Math.max(mass[coordinate / XYZ], Double.MIN_NORMAL);
        }
        means = new EMeans(XYZ, regionCount);
        means.nearest = (packed, point, centroids, clusters, dimension) -> regionOfFace[faces[point]];
        means.weights = weights;
        means.unusedThreshold = 0;
        means.requestedBatchSize = batchFaces;
        means.requestedAlpha0 = alpha0;
        means.epochs = maxPasses * (double) Math.min(batchFaces, faces.length) / Math.max(1, faces.length);
        means.seed = seed;
        means.begin(points, faces.length, initialMeans);
        loopsByPass.clear();
        passes = 0;
        moves = 0;
        refusals = 0;
        voronoiMoves = 0;
        assigned = new int[mesh.faceIdBound()];
        still = 0;
        stoodAt = -1;
        passFaces = faces;
        passPoints = points;
        if (recordPasses && airFlow) {
            // The seams as drawn stand first, so the passes can be watched from before any move.
            int[][] drawn = new int[ringLabels.length + 1][];
            for (int ring = 0; ring < ringLabels.length; ring++) {
                drawn[ring] = loops[ring].clone();
            }
            drawn[ringLabels.length] = regionOfFace.clone();
            loopsByPass.add(drawn);
        }
        balanced = !lifetime && !airFlow && IntStream.range(0, ringLabels.length)
                .anyMatch(ring -> movable[ring] && !splitsNothing[ring]);
        if (airFlow) {
            walker = new SurfaceLifetime(mesh);
            measureAir();
        } else if (lifetime) {
            walker = new SurfaceLifetime(mesh);
            measureLifetimes();
        }
    }

    /**
     * Run one pass, after {@link #begin}: move the centroids by one e-means batch, measure every
     * face's distance to each centroid, then let each movable ring take the move that lowers the
     * energy most, or under air flow the move the pushes on it ask for.
     *
     * @return false, with nothing done, once a pass has moved no ring for as many passes as end
     *         the fit or {@link #maxPasses} have run
     */
    public boolean stepPass() {
        if (passes >= maxPasses || still >= (lifetime || airFlow ? 1 : STILL_PASSES_BEFORE_STOP)) {
            return false;
        }
        if (stoodAt >= 0 && stoodAt != loopsByPass.size() - 1) {
            // A reader stood the seams at an earlier pass to look at it: back to where the fit is.
            standAtPass(loopsByPass.size() - 1);
        }
        stoodAt = -1;
        int[] faces = passFaces;
        double[] points = passPoints;
        means.step();
        double[] nearest = new double[regionCount];
        Arrays.fill(nearest, Double.POSITIVE_INFINITY);
        for (int active = 0; active < faces.length; active++) {
            int region = regionOfFace[faces[active]];
            double squared = 0;
            for (int axis = 0; axis < XYZ; axis++) {
                double offset = points[XYZ * active + axis] - means.centroids[XYZ * region + axis];
                squared += offset * offset;
            }
            if (squared < nearest[region]) {
                nearest[region] = squared;
                centroidFace[region] = faces[active];
            }
        }
        for (int region = 0; balanced && region < regionCount; region++) {
            faceWalk.start(mesh.faceIdBound());
            faceWalk.relax(centroidFace[region], 0, centroidFace[region]);
            for (int face = faceWalk.settle(); face >= 0; face = faceWalk.settle()) {
                for (int at = 0; at < mesh.faceEdgeCount(face); at++) {
                    int edge = mesh.faceEdgeAt(face, at);
                    int halfEdge = mesh.edgeHalfEdge(edge);
                    double stretch = 1 + crossingWeight * Math.max(grooveStrength[mesh.halfEdgeVertex(halfEdge)],
                            grooveStrength[mesh.halfEdgeEndVertex(halfEdge)]);
                    for (int side = 0; side < SIDES; side++) {
                        int next = mesh.edgeFace(edge, side);
                        if (next >= 0 && next != face) {
                            double step = Vector3f.distance(faceCentroid[XYZ * face], faceCentroid[XYZ * face + 1],
                                    faceCentroid[XYZ * face + 2], faceCentroid[XYZ * next],
                                    faceCentroid[XYZ * next + 1], faceCentroid[XYZ * next + 2]);
                            faceWalk.relax(next, faceWalk.distance(face) + step * stretch, face);
                        }
                    }
                }
            }
            for (int face = 0; face < mesh.faceIdBound(); face++) {
                distanceByRegion[region][face] = faceWalk.distance(face);
            }
        }
        boolean moved = false;
        for (int ring = 0; ring < ringLabels.length; ring++) {
            if (!movable[ring]) {
                continue;
            }
            if (lifetime || airFlow) {
                if (airFlow ? airMove(ring) : lifetimeMove(ring)) {
                    moved = true;
                    moves++;
                }
                continue;
            }
            int[] split = !voronoi || !balanced || splitsNothing[ring] || endRings[ring] != null ? null
                    : voronoiLoop(ring, assigned);
            double splitChange = split == null ? 0 : pullWeight * (pull(split) - pull(loops[ring]));
            for (int face = 0; split != null && face < assigned.length; face++) {
                if (assigned[face] >= 0 && assigned[face] != regionOfFace[face]) {
                    splitChange += faceArea[face] / meanFaceArea * (distanceByRegion[assigned[face]][face]
                            - distanceByRegion[regionOfFace[face]][face]) / meanEdgeLength;
                }
            }
            int[][] splitFollowed = split != null && splitChange < -NO_GAIN ? followers(ring, split) : null;
            if (splitFollowed != null) {
                System.arraycopy(assigned, 0, regionOfFace, 0, assigned.length);
                loops[ring] = split;
                for (int bisector = 0; bisector < loops.length; bisector++) {
                    loops[bisector] = splitFollowed[bisector] == null ? loops[bisector] : splitFollowed[bisector];
                }
                moved = true;
                moves++;
                voronoiMoves++;
                continue;
            }
            if (localMoves) {
                RunMove move = bestRunMove(ring, balanced);
                if (move != null) {
                    for (int face : move.faces()) {
                        regionOfFace[face] = move.growing();
                    }
                    loops[ring] = move.loop();
                    for (int bisector = 0; bisector < loops.length; bisector++) {
                        loops[bisector] = move.followed()[bisector] == null ? loops[bisector]
                                : move.followed()[bisector];
                    }
                    moved = true;
                    moves++;
                }
                continue;
            }
            int[] bestLoop = null;
            int[] bestStrip = null;
            double bestGain = -NO_GAIN;
            for (int side = 0; side < SIDES; side++) {
                int[] strip = strip(ring, side);
                int[] loop = strip == null ? null : proposedLoop(ring, strip);
                if (loop == null) {
                    refusals++;
                    continue;
                }
                double change = pullWeight * (pull(loop) - pull(loops[ring]));
                int eaten = regionOfFace[strip[0]];
                int growing = splitsNothing[ring] ? -1 : regionAcross(ring, eaten);
                for (int face = 0; growing >= 0 && face < strip.length; face++) {
                    change += faceArea[strip[face]] / meanFaceArea * (distanceByRegion[growing][strip[face]]
                            - distanceByRegion[eaten][strip[face]]) / meanEdgeLength;
                }
                if (change < bestGain) {
                    bestGain = change;
                    bestLoop = loop;
                    bestStrip = strip;
                }
            }
            if (bestLoop != null) {
                int eaten = regionOfFace[bestStrip[0]];
                int growing = splitsNothing[ring] ? -1 : regionAcross(ring, eaten);
                for (int face : bestStrip) {
                    regionOfFace[face] = growing < 0 ? eaten : growing;
                }
                loops[ring] = bestLoop;
                moved = true;
                moves++;
            }
        }
        passes++;
        // Under air flow the seams standing as they stood before means the fit is going round.
        still = moved && !(airFlow && seenSeams.put(Arrays.deepHashCode(loops), true) != null) ? 0 : still + 1;
        if (recordPasses) {
            int[][] snapshot = new int[ringLabels.length + 1][];
            for (int ring = 0; ring < ringLabels.length; ring++) {
                snapshot[ring] = loops[ring].clone();
            }
            snapshot[ringLabels.length] = regionOfFace.clone();
            loopsByPass.add(snapshot);
        }
        return true;
    }

    /**
     * The faces on one side of a ring that touch its loop: the strip a move to that
     * side sweeps.
     *
     * @param ring the ring
     * @param side 0 or 1; which side is which is fixed by the lowest face id
     *             touching the loop
     * @return the strip's face ids, or null when the faces round the loop do not
     *         fall into two sides
     */
    public int[] strip(int ring, int side) {
        edgeEpoch++;
        for (int edge : loops[ring]) {
            edgeStamp[edge] = edgeEpoch;
        }
        Map<Integer, Integer> indexOf = new LinkedHashMap<>();
        for (int edge : loops[ring]) {
            int halfEdge = mesh.edgeHalfEdge(edge);
            for (int end = 0; end < SIDES; end++) {
                int vertex = end == 0 ? mesh.halfEdgeVertex(halfEdge) : mesh.halfEdgeEndVertex(halfEdge);
                for (int at = 0; at < mesh.vertexFaceCount(vertex); at++) {
                    indexOf.putIfAbsent(mesh.vertexFaceAt(vertex, at), indexOf.size());
                }
            }
        }
        int[] fan = indexOf.keySet().stream().mapToInt(Integer::intValue).toArray();
        int[] parent = UnionFind.singletons(fan.length);
        for (int index = 0; index < fan.length; index++) {
            for (int at = 0; at < mesh.faceEdgeCount(fan[index]); at++) {
                int edge = mesh.faceEdgeAt(fan[index], at);
                if (edgeStamp[edge] == edgeEpoch) {
                    continue;
                }
                for (int across = 0; across < SIDES; across++) {
                    Integer other = indexOf.get(mesh.edgeFace(edge, across));
                    if (other != null) {
                        UnionFind.union(parent, index, other);
                    }
                }
            }
        }
        int firstRoot = UnionFind.find(parent, 0);
        int secondRoot = -1;
        for (int index = 0; index < fan.length; index++) {
            int root = UnionFind.find(parent, index);
            if (root != firstRoot && secondRoot < 0) {
                secondRoot = root;
            } else if (root != firstRoot && root != secondRoot) {
                return null;
            }
        }
        if (secondRoot < 0) {
            return null;
        }
        int wanted = side == 0 ? firstRoot : secondRoot;
        return Arrays.stream(fan).filter(face -> UnionFind.find(parent, indexOf.get(face)) == wanted).toArray();
    }

    /**
     * The loop a ring would stand on after sweeping a strip, or null when the
     * topology rule refuses it: the strip's far boundary must be one simple cycle
     * off the mesh border, touching no other ring, and leave a face of the region
     * it eats into.
     *
     * @param ring  the ring
     * @param strip the faces it sweeps, from {@link #strip}
     * @return the new loop's edge ids, or null when refused, the reason in
     *         {@link #lastRefusal}
     */
    public int[] proposedLoop(int ring, int[] strip) {
        edgeEpoch++;
        int odd = edgeEpoch;
        edgeEpoch++;
        int even = edgeEpoch;
        List<Integer> touched = new ArrayList<>();
        for (int face : strip) {
            for (int at = 0; at < mesh.faceEdgeCount(face); at++) {
                int edge = mesh.faceEdgeAt(face, at);
                if (edgeStamp[edge] != odd && edgeStamp[edge] != even) {
                    touched.add(edge);
                }
                edgeStamp[edge] = edgeStamp[edge] == odd ? even : odd;
            }
        }
        boolean[] onOldLoop = new boolean[mesh.edgeIdBound()];
        for (int edge : loops[ring]) {
            onOldLoop[edge] = true;
        }
        List<Integer> loop = new ArrayList<>();
        for (int edge : touched) {
            if (edgeStamp[edge] == odd && !onOldLoop[edge]) {
                loop.add(edge);
            }
        }
        if (loop.isEmpty()) {
            lastRefusal = "the strip has no far boundary: the move would empty the region";
            return null;
        }
        int[] checked = checkedLoop(ring, loop);
        if (checked == null) {
            return null;
        }
        int eaten = regionOfFace[strip[0]];
        int left = 0;
        boolean[] inStrip = new boolean[mesh.faceIdBound()];
        for (int face : strip) {
            inStrip[face] = true;
        }
        for (int active = 0; active < mesh.faceCount() && left == 0; active++) {
            int face = mesh.faceIdAt(active);
            left += regionOfFace[face] == eaten && !inStrip[face] ? 1 : 0;
        }
        if (left == 0) {
            lastRefusal = "the move would empty region " + eaten;
            return null;
        }
        return checked;
    }

    /**
     * A candidate loop for a ring, kept when it is one simple cycle off the mesh
     * border that touches no other ring.
     *
     * @param ring the ring the loop would replace
     * @param loop the candidate's edge ids
     * @return the loop's edge ids, or null when refused, the reason in
     *         {@link #lastRefusal}
     */
    private int[] checkedLoop(int ring, List<Integer> loop) {
        int[] degree = new int[mesh.vertexIdBound()];
        for (int edge : loop) {
            if (mesh.isBoundaryEdge(edge)) {
                lastRefusal = "the new loop runs on the mesh border";
                return null;
            }
            degree[mesh.halfEdgeVertex(mesh.edgeHalfEdge(edge))]++;
            degree[mesh.halfEdgeEndVertex(mesh.edgeHalfEdge(edge))]++;
        }
        for (int edge : loop) {
            int halfEdge = mesh.edgeHalfEdge(edge);
            if (degree[mesh.halfEdgeVertex(halfEdge)] != 2 || degree[mesh.halfEdgeEndVertex(halfEdge)] != 2) {
                lastRefusal = "the new loop is not one simple cycle: the move would split or pinch a region";
                return null;
            }
        }
        int start = mesh.halfEdgeVertex(mesh.edgeHalfEdge(loop.get(0)));
        boolean[] onLoop = new boolean[mesh.edgeIdBound()];
        for (int edge : loop) {
            onLoop[edge] = true;
        }
        int walked = 0;
        int vertex = start;
        int cameBy = -1;
        do {
            int next = -1;
            for (int at = 0; at < mesh.vertexEdgeCount(vertex) && next < 0; at++) {
                int edge = mesh.vertexEdgeAt(vertex, at);
                if (onLoop[edge] && edge != cameBy) {
                    next = edge;
                }
            }
            if (next < 0) {
                break;
            }
            walked++;
            cameBy = next;
            vertex = mesh.edgeOtherVertex(next, vertex);
        } while (vertex != start && walked <= loop.size());
        if (walked != loop.size()) {
            lastRefusal = "the new loop falls into several cycles: the move would split a region";
            return null;
        }
        for (int other = 0; other < loops.length; other++) {
            if (other == ring || endsOn(other, ring)) {
                continue;
            }
            for (int edge : loops[other]) {
                int halfEdge = mesh.edgeHalfEdge(edge);
                if (degree[mesh.halfEdgeVertex(halfEdge)] > 0 || degree[mesh.halfEdgeEndVertex(halfEdge)] > 0) {
                    lastRefusal = "the new loop touches " + ringLabels[other] + ": the rings would merge or cross";
                    return null;
                }
            }
        }
        return loop.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * The loop a ring would stand on if the two regions it separates split their
     * faces by the nearer centroid under the groove-stretched distance: a geodesic
     * Voronoi split of the pair.
     *
     * @param ring     a ring with a different region on each side
     * @param assigned filled with the region each face would take, by face id
     * @return the new loop's edge ids, or null when the split leaves a region
     *         empty, moves a face beside another ring, or is refused by
     *         {@link #checkedLoop}
     */
    private int[] voronoiLoop(int ring, int[] assigned) {
        int edge = loops[ring][0];
        int first = regionOfFace[mesh.edgeFace(edge, 0)];
        int second = regionAcross(ring, first);
        if (second < 0) {
            return null;
        }
        System.arraycopy(regionOfFace, 0, assigned, 0, assigned.length);
        int[] count = new int[SIDES];
        for (int active = 0; active < mesh.faceCount(); active++) {
            int face = mesh.faceIdAt(active);
            if (assigned[face] == first || assigned[face] == second) {
                boolean nearerFirst = distanceByRegion[first][face] <= distanceByRegion[second][face];
                assigned[face] = nearerFirst ? first : second;
                count[nearerFirst ? 0 : 1]++;
            }
        }
        if (count[0] == 0 || count[1] == 0) {
            lastRefusal = "the split would empty a region";
            return null;
        }
        for (int other = 0; other < loops.length; other++) {
            for (int otherEdge : other == ring || endsOn(other, ring) ? new int[0] : loops[other]) {
                for (int side = 0; side < SIDES; side++) {
                    int face = mesh.edgeFace(otherEdge, side);
                    if (face >= 0 && assigned[face] != regionOfFace[face]) {
                        lastRefusal = "the split moves a face beside " + ringLabels[other];
                        return null;
                    }
                }
            }
        }
        List<Integer> loop = new ArrayList<>();
        for (int active = 0; active < mesh.edgeCount(); active++) {
            int candidate = mesh.edgeIdAt(active);
            int one = mesh.edgeFace(candidate, 0);
            int two = mesh.edgeFace(candidate, 1);
            if (one >= 0 && two >= 0 && assigned[one] != assigned[two]
                    && (assigned[one] == first || assigned[one] == second)
                    && (assigned[two] == first || assigned[two] == second)) {
                loop.add(candidate);
            }
        }
        return checkedLoop(ring, loop);
    }

    /**
     * The pull a loop feels: its edges' lengths times their mean distance to the
     * nearest groove bottom, over the squared mean edge length.
     *
     * @param edges the loop's edge ids
     * @return the loop's pull, before {@link #pullWeight}
     */
    private double pull(int[] edges) {
        double sum = 0;
        for (int edge : edges) {
            sum += edgePull(edge);
        }
        return sum;
    }

    /**
     * Whether a ring is a moving bisector with an end on another ring.
     *
     * @param bisector the ring that may be a bisector
     * @param ring     the ring its end may sit on
     * @return true when one of the bisector's ends sits on the ring
     */
    private boolean endsOn(int bisector, int ring) {
        return endRings[bisector] != null && (endRings[bisector][0] == ring || endRings[bisector][1] == ring);
    }

    /**
     * The paths the bisectors ending on a ring take when the ring moves to a new
     * loop: an end left off the loop walks the shortest way from the loop to its
     * bisector, which is cut where the walk meets it.
     *
     * @param ring    the ring that moves
     * @param newLoop the loop it moves to
     * @return each bisector's new path by ring index, null where a path stays; or
     *         null when a followed path is refused
     */
    private int[][] followers(int ring, int[] newLoop) {
        int[][] followed = new int[loops.length][];
        boolean[] onNew = new boolean[mesh.vertexIdBound()];
        boolean[] onOld = new boolean[mesh.vertexIdBound()];
        for (int[] edges : new int[][] { newLoop, loops[ring] }) {
            for (int edge : edges) {
                boolean[] on = edges == newLoop ? onNew : onOld;
                on[mesh.halfEdgeVertex(mesh.edgeHalfEdge(edge))] = true;
                on[mesh.halfEdgeEndVertex(mesh.edgeHalfEdge(edge))] = true;
            }
        }
        for (int bisector = 0; bisector < loops.length; bisector++) {
            if (!endsOn(bisector, ring)) {
                continue;
            }
            int[] walk = cycle(loops[bisector]);
            boolean fromStart = onOld[mesh.halfEdgeVertex(walk[0])];
            int[] vertices = new int[walk.length + 1];
            int[] edges = new int[walk.length];
            for (int step = 0; step < walk.length; step++) {
                int halfEdge = walk[fromStart ? step : walk.length - 1 - step];
                vertices[step] = fromStart ? mesh.halfEdgeVertex(halfEdge) : mesh.halfEdgeEndVertex(halfEdge);
                vertices[step + 1] = fromStart ? mesh.halfEdgeEndVertex(halfEdge) : mesh.halfEdgeVertex(halfEdge);
                edges[step] = mesh.halfEdgeEdge(halfEdge);
            }
            if (onNew[vertices[0]]) {
                continue;
            }
            int[] indexOnPath = new int[mesh.vertexIdBound()];
            Arrays.fill(indexOnPath, -1);
            for (int at = 0; at < vertices.length; at++) {
                indexOnPath[vertices[at]] = at;
            }
            Dijkstra reach = new Dijkstra();
            reach.start(mesh.vertexIdBound());
            for (int vertex = 0; vertex < onNew.length; vertex++) {
                if (onNew[vertex]) {
                    reach.relax(vertex, 0, -1);
                }
            }
            int junction = -1;
            for (int vertex = reach.settle(); vertex >= 0 && junction < 0; vertex = reach.settle()) {
                if (indexOnPath[vertex] >= 0) {
                    junction = vertex;
                    break;
                }
                for (int at = 0; at < mesh.vertexEdgeCount(vertex); at++) {
                    int edge = mesh.vertexEdgeAt(vertex, at);
                    reach.relax(mesh.edgeOtherVertex(edge, vertex), reach.distance(vertex) + mesh.edgeLength(edge),
                            vertex);
                }
            }
            if (junction < 0) {
                lastRefusal = "no way from the moved " + ringLabels[ring] + " back to " + ringLabels[bisector];
                return null;
            }
            List<Integer> path = new ArrayList<>();
            for (int vertex = junction; reach.via(vertex) >= 0; vertex = reach.via(vertex)) {
                int from = vertex;
                int to = reach.via(vertex);
                path.add(IntStream.range(0, mesh.vertexEdgeCount(from)).map(at -> mesh.vertexEdgeAt(from, at))
                        .filter(edge -> mesh.edgeOtherVertex(edge, from) == to).findFirst().orElseThrow());
            }
            for (int at = indexOnPath[junction]; at < edges.length; at++) {
                path.add(edges[at]);
            }
            int[] kept = loops[ring];
            loops[ring] = newLoop;
            followed[bisector] = checkedPath(bisector, path);
            loops[ring] = kept;
            if (followed[bisector] == null) {
                return null;
            }
        }
        return followed;
    }

    /**
     * One edge's share of {@link #pull}.
     *
     * @param edge the edge id
     * @return its length times its ends' mean groove distance, over the squared mean
     *         edge length
     */
    private double edgePull(int edge) {
        int halfEdge = mesh.edgeHalfEdge(edge);
        return mesh.edgeLength(edge) * 0.5 * (grooveDistance[mesh.halfEdgeVertex(halfEdge)]
                + grooveDistance[mesh.halfEdgeEndVertex(halfEdge)]) / (meanEdgeLength * meanEdgeLength);
    }

    /**
     * A loop's or open path's edges as one directed walk of half-edges, each
     * starting where the last ended; an open path is walked from one of its ends.
     *
     * @param loop the edge ids
     * @return the half-edges in walk order, or null when the edges are neither one
     *         simple cycle nor one simple path
     */
    private int[] cycle(int[] loop) {
        if (loop.length == 0) {
            return null;
        }
        runEpoch++;
        Map<Integer, Integer> degree = new LinkedHashMap<>();
        for (int edge : loop) {
            loopStamp[edge] = runEpoch;
            degree.merge(mesh.halfEdgeVertex(mesh.edgeHalfEdge(edge)), 1, Integer::sum);
            degree.merge(mesh.halfEdgeEndVertex(mesh.edgeHalfEdge(edge)), 1, Integer::sum);
        }
        int[] halfEdges = new int[loop.length];
        int start = degree.entrySet().stream().filter(entry -> entry.getValue() == 1).map(Map.Entry::getKey)
                .findFirst().orElse(mesh.halfEdgeVertex(mesh.edgeHalfEdge(loop[0])));
        boolean open = degree.get(start) == 1;
        int vertex = start;
        int cameBy = -1;
        for (int walked = 0; walked < loop.length; walked++) {
            int next = -1;
            for (int at = 0; at < mesh.vertexEdgeCount(vertex) && next < 0; at++) {
                int edge = mesh.vertexEdgeAt(vertex, at);
                next = loopStamp[edge] == runEpoch && edge != cameBy ? edge : -1;
            }
            if (next < 0) {
                return null;
            }
            int halfEdge = mesh.edgeHalfEdge(next);
            halfEdges[walked] = mesh.halfEdgeVertex(halfEdge) == vertex ? halfEdge : mesh.halfEdgeTwin(halfEdge);
            cameBy = next;
            vertex = mesh.edgeOtherVertex(next, vertex);
        }
        return (vertex == start) != open ? halfEdges : null;
    }

    /**
     * The faces round one path vertex on one side of the path, found by turning
     * about the vertex from one path half-edge to the other; at an open path's end,
     * from its one half-edge to the wall it ends on.
     *
     * @param incoming the path half-edge arriving at the vertex, or -1 at the first
     *                 end
     * @param outgoing the path half-edge leaving it, or -1 at the last end
     * @param side     0 for the faces left of the path's direction, 1 for those
     *                 right
     * @return the face ids, or null when the turn meets the mesh border
     */
    private int[] fan(int incoming, int outgoing, int side) {
        boolean reverse = incoming < 0 ? side == 1 : outgoing < 0 && side == 0;
        int turn = reverse ? mesh.halfEdgeNext(incoming < 0 ? mesh.halfEdgeTwin(outgoing) : incoming)
                : side == 0 ? outgoing : mesh.halfEdgeTwin(incoming);
        int stop = incoming < 0 || outgoing < 0 ? -1 : side == 0 ? incoming : mesh.halfEdgeTwin(outgoing);
        int vertex = incoming < 0 ? mesh.halfEdgeVertex(outgoing) : mesh.halfEdgeEndVertex(incoming);
        List<Integer> faces = new ArrayList<>();
        for (int guard = 0; guard <= mesh.vertexFaceCount(vertex); guard++) {
            int face = mesh.halfEdgeFace(turn);
            if (face < 0) {
                return null;
            }
            faces.add(face);
            int before = mesh.halfEdgePrev(turn);
            boolean last = reverse ? wallStamp[mesh.halfEdgeEdge(turn)] == wallEpoch
                    : stop >= 0 ? before == stop : wallStamp[mesh.halfEdgeEdge(before)] == wallEpoch;
            if (last) {
                return faces.stream().mapToInt(Integer::intValue).toArray();
            }
            turn = reverse ? mesh.halfEdgeNext(mesh.halfEdgeTwin(turn)) : mesh.halfEdgeTwin(before);
        }
        return null;
    }

    /**
     * A candidate path for a bisector, kept when it is one simple path off the
     * mesh border from a vertex of one end ring to a vertex of the other, touching
     * no ring anywhere else.
     *
     * @param ring the bisector
     * @param path the candidate's edge ids
     * @return the path's edge ids, or null when refused, the reason in
     *         {@link #lastRefusal}
     */
    private int[] checkedPath(int ring, List<Integer> path) {
        int[] edges = path.stream().mapToInt(Integer::intValue).toArray();
        int[] walk = cycle(edges);
        if (walk == null || mesh.halfEdgeEndVertex(walk[walk.length - 1]) == mesh.halfEdgeVertex(walk[0])) {
            lastRefusal = "the new path is not one simple path";
            return null;
        }
        int[] ringOfVertex = new int[mesh.vertexIdBound()];
        Arrays.fill(ringOfVertex, -1);
        for (int other = 0; other < loops.length; other++) {
            for (int edge : other == ring ? new int[0] : loops[other]) {
                ringOfVertex[mesh.halfEdgeVertex(mesh.edgeHalfEdge(edge))] = other;
                ringOfVertex[mesh.halfEdgeEndVertex(mesh.edgeHalfEdge(edge))] = other;
            }
        }
        for (int step = 0; step < walk.length; step++) {
            int vertex = mesh.halfEdgeEndVertex(walk[step]);
            if (mesh.isBoundaryEdge(mesh.halfEdgeEdge(walk[step]))) {
                lastRefusal = "the new path runs on the mesh border";
                return null;
            }
            if (step < walk.length - 1 && ringOfVertex[vertex] >= 0) {
                lastRefusal = "the new path touches " + ringLabels[ringOfVertex[vertex]] + " between its ends";
                return null;
            }
        }
        int first = ringOfVertex[mesh.halfEdgeVertex(walk[0])];
        int last = ringOfVertex[mesh.halfEdgeEndVertex(walk[walk.length - 1])];
        boolean ends = first == endRings[ring][0] && last == endRings[ring][1]
                || first == endRings[ring][1] && last == endRings[ring][0];
        if (!ends) {
            lastRefusal = "the new path no longer ends on " + ringLabels[endRings[ring][0]] + " and "
                    + ringLabels[endRings[ring][1]];
            return null;
        }
        return edges;
    }

    /**
     * The ring's best move sweeping the faces round a run of consecutive loop
     * vertices on one side, from one vertex's fan to the whole strip, that the
     * topology rule keeps.
     *
     * @param ring     the ring
     * @param balanced whether the balance term counts this pass
     * @return the move, or null when no run lowers the energy and is kept
     */
    private RunMove bestRunMove(int ring, boolean balanced) {
        int[] cycle = cycle(loops[ring]);
        if (cycle == null) {
            return null;
        }
        int[] oldLoop = loops[ring];
        boolean open = endRings[ring] != null;
        int count = open ? cycle.length + 1 : cycle.length;
        wallEpoch++;
        for (int end = 0; open && end < SIDES; end++) {
            for (int edge : loops[endRings[ring][end]]) {
                wallStamp[edge] = wallEpoch;
            }
        }
        RunMove best = null;
        for (int side = 0; side < SIDES; side++) {
            int[][] fans = new int[count][];
            boolean border = false;
            for (int at = 0; at < count && !border; at++) {
                int incoming = open ? (at == 0 ? -1 : cycle[at - 1]) : cycle[(at + count - 1) % count];
                int outgoing = open && at == cycle.length ? -1 : cycle[at];
                fans[at] = fan(incoming, outgoing, side);
                border = fans[at] == null;
            }
            if (border) {
                continue;
            }
            int eaten = regionOfFace[fans[0][0]];
            int across = side == 0 ? mesh.halfEdgeFace(mesh.halfEdgeTwin(cycle[0])) : mesh.halfEdgeFace(cycle[0]);
            int growing = splitsNothing[ring] ? eaten : regionOfFace[across];
            boolean scored = balanced && growing != eaten;
            int eatenSize = 0;
            for (int active = 0; active < mesh.faceCount(); active++) {
                eatenSize += regionOfFace[mesh.faceIdAt(active)] == eaten ? 1 : 0;
            }
            List<double[]> candidates = new ArrayList<>();
            for (int start = 0; start < count; start++) {
                runEpoch++;
                for (int edge : oldLoop) {
                    loopStamp[edge] = runEpoch;
                }
                double change = 0;
                int swept = 0;
                boolean stopped = false;
                int longest = open ? count - start : start == 0 ? count : count - 1;
                for (int length = 1; length <= longest && !stopped; length++) {
                    for (int face : fans[(start + length - 1) % count]) {
                        if (faceStamp[face] == runEpoch) {
                            continue;
                        }
                        faceStamp[face] = runEpoch;
                        if (regionOfFace[face] != eaten) {
                            stopped = true;
                            break;
                        }
                        swept++;
                        if (scored) {
                            change += faceArea[face] / meanFaceArea * (distanceByRegion[growing][face]
                                    - distanceByRegion[eaten][face]) / meanEdgeLength;
                        }
                        for (int at = 0; at < mesh.faceEdgeCount(face); at++) {
                            int edge = mesh.faceEdgeAt(face, at);
                            if (wallStamp[edge] == wallEpoch) {
                                continue;
                            }
                            boolean was = (loopStamp[edge] == runEpoch) != (flipStamp[edge] == runEpoch && flipped[edge]);
                            flipped[edge] = flipStamp[edge] != runEpoch || !flipped[edge];
                            flipStamp[edge] = runEpoch;
                            double cost = pullWeight * edgePull(edge) + lengthWeight * mesh.edgeLength(edge) / meanEdgeLength;
                            change += was ? -cost : cost;
                        }
                    }
                    stopped |= swept >= eatenSize;
                    if (!stopped && change < -NO_GAIN) {
                        candidates.add(new double[] { change, start, length });
                    }
                }
            }
            candidates.sort((one, two) -> Double.compare(one[0], two[0]));
            for (double[] candidate : candidates) {
                if (best != null && best.change() <= candidate[0]) {
                    break;
                }
                int start = (int) candidate[1];
                Map<Integer, Boolean> onNewLoop = new LinkedHashMap<>();
                for (int edge : oldLoop) {
                    onNewLoop.put(edge, true);
                }
                List<Integer> faces = new ArrayList<>();
                runEpoch++;
                for (int length = 1; length <= (int) candidate[2]; length++) {
                    for (int face : fans[(start + length - 1) % count]) {
                        if (faceStamp[face] != runEpoch) {
                            faceStamp[face] = runEpoch;
                            faces.add(face);
                            for (int at = 0; at < mesh.faceEdgeCount(face); at++) {
                                if (wallStamp[mesh.faceEdgeAt(face, at)] != wallEpoch) {
                                    onNewLoop.merge(mesh.faceEdgeAt(face, at), true, (was, flip) -> !was);
                                }
                            }
                        }
                    }
                }
                List<Integer> loop = new ArrayList<>();
                onNewLoop.forEach((edge, on) -> {
                    if (on) {
                        loop.add(edge);
                    }
                });
                int[] checked = open ? checkedPath(ring, loop) : checkedLoop(ring, loop);
                int[][] followed = checked == null ? null : open ? new int[loops.length][] : followers(ring, checked);
                if (followed == null) {
                    refusals++;
                    continue;
                }
                best = new RunMove(candidate[0], checked, faces.stream().mapToInt(Integer::intValue).toArray(),
                        growing, followed);
                break;
            }
        }
        return best;
    }

    /**
     * Measure the air flow as the seams stand: air rises everywhere at one rate, leaves through the
     * seams and cannot pass a groove bottom no seam lies on. Sets how hard it arrives over each
     * face and each region's area.
     */
    private void measureAir() {
        boolean[] seam = new boolean[mesh.vertexIdBound()];
        for (int[] loop : loops) {
            for (int edge : loop) {
                seam[mesh.halfEdgeVertex(mesh.edgeHalfEdge(edge))] = true;
                seam[mesh.halfEdgeEndVertex(mesh.edgeHalfEdge(edge))] = true;
            }
        }
        boolean[] blocking = new boolean[seam.length];
        double[] openness = new double[seam.length];
        for (int vertex = 0; vertex < seam.length; vertex++) {
            openness[vertex] = seam[vertex] || creaseSource || ringSource || creasesSwallow ? 1
                    : gradedWalls ? (gradedBand || valleyLine[vertex]
                            ? Math.max(0, 1 - Math.pow(Math.max(0, grooveStrength[vertex]), gradedSharpness)) : 1)
                            : walls()[vertex] ? 0 : 1;
            blocking[vertex] = openness[vertex] <= 0;
        }
        airWall = blocking.clone();
        borderEdge = new boolean[mesh.edgeIdBound()];
        regionSeamLength = new double[regionCount];
        for (int[] loop : loops) {
            for (int edge : loop) {
                borderEdge[edge] = true;
                for (int side = 0; side < SIDES; side++) {
                    int face = mesh.edgeFace(edge, side);
                    regionSeamLength[face < 0 ? 0 : regionOfFace[face]] += face < 0 ? 0 : edgeLength(edge);
                }
            }
        }
        sheltered = new boolean[regionCount];
        for (int active = 0; active < mesh.vertexCount(); active++) {
            int vertex = mesh.vertexIdAt(active);
            if (blocking[vertex] && mesh.vertexFaceCount(vertex) > 0) {
                sheltered[regionOfFace[mesh.vertexFaceAt(vertex, 0)]] = true;
            }
        }
        // From the centre: all a region's air rises at its vertex farthest, as a walker goes, from
        // every seam, walls or no walls.
        double[] starts = null;
        if (creaseSource) {
            starts = new double[seam.length];
            for (int active = 0; active < mesh.vertexCount(); active++) {
                int vertex = mesh.vertexIdAt(active);
                starts[vertex] = seam[vertex] || creaseSourceLineOnly && !valleyLine[vertex] ? 0
                        : walker.massByVertexId[vertex] * Math.max(0, grooveStrength[vertex]);
            }
        } else if (centreSource) {
            double[] far = walker.lifetime(seam);
            int[] centre = new int[regionCount];
            Arrays.fill(centre, -1);
            starts = new double[seam.length];
            for (int active = 0; active < mesh.vertexCount(); active++) {
                int vertex = mesh.vertexIdAt(active);
                if (seam[vertex] || mesh.vertexFaceCount(vertex) == 0) {
                    continue;
                }
                int region = regionOfFace[mesh.vertexFaceAt(vertex, 0)];
                centre[region] = centre[region] < 0 || far[vertex] > far[centre[region]] ? vertex : centre[region];
                starts[vertex] = 0;
            }
            double[] area = new double[regionCount];
            for (int active = 0; active < mesh.faceCount(); active++) {
                area[regionOfFace[mesh.faceIdAt(active)]] += faceArea[mesh.faceIdAt(active)];
            }
            for (int region = 0; region < regionCount; region++) {
                if (centre[region] >= 0) {
                    starts[centre[region]] = area[region];
                }
            }
        }
        // How fast each vertex swallows the air standing on it, where creases swallow. By curvature:
        // a rate of the curvature squared, the one rate curvature has the units of. By strength: air
        // stepping on the vertex is swallowed with that chance.
        int corners = SurfaceLifetime.CORNERS;
        double[] swallow = new double[seam.length];
        double[] stiff = new double[seam.length];
        for (int corner = 0; (ringSource || creasesSwallow) && corner < walker.triangles.length; corner++) {
            int first = corner - corner % corners;
            stiff[walker.triangles[first + (corner + 1) % corners]] += walker.cornerCotangent[corner] / 2;
            stiff[walker.triangles[first + (corner + 2) % corners]] += walker.cornerCotangent[corner] / 2;
        }
        for (int active = 0; (ringSource || creasesSwallow) && active < mesh.vertexCount(); active++) {
            int vertex = mesh.vertexIdAt(active);
            double strength = ringSourceLineOnly && !valleyLine[vertex] ? 0
                    : Math.min(ALL_BUT_SWALLOWED, Math.max(0, grooveStrength[vertex]));
            swallow[vertex] = seam[vertex] && creasesSwallow ? 0
                    : ringSourceByCurvature
                            ? walker.massByVertexId[vertex] * grooveCurvature[vertex] * grooveCurvature[vertex]
                            : stiff[vertex] * strength / (1 - strength);
        }
        double[] life = walker.fadingRound(seam, blocking, openness, starts, 0, creasesSwallow ? swallow : null);
        double[] steep = walker.steepness(life);
        airDrift = walker.drift(life, blocking);
        if (travelWeighted) {
            // One over a walker's distance, the root of its time, is a sum over fade rates of what
            // fading at each rate leaves of it. The rates run from one over the longest lifetime to
            // one over the time to cross a mesh edge, the finest the mesh can tell apart.
            double longest = 0;
            for (double lifetime : life) {
                longest = Math.max(longest, lifetime);
            }
            double slowest = 1 / Math.max(longest, meanEdgeLength * meanEdgeLength);
            double ratio = Math.pow(1 / (meanEdgeLength * meanEdgeLength * slowest), 1.0 / (TRAVEL_FADE_RATES - 1));
            double[] summed = new double[steep.length];
            for (int at = 0; at < TRAVEL_FADE_RATES; at++) {
                double rate = slowest * Math.pow(ratio, at);
                double[] left = walker.steepness(
                        walker.fadingRound(seam, blocking.clone(), openness, starts, rate, null));
                for (int face = 0; face < summed.length; face++) {
                    summed[face] += Math.sqrt(rate) * Math.sqrt(left[face]);
                }
            }
            for (int face = 0; face < summed.length; face++) {
                steep[face] = summed[face] * summed[face];
            }
        }
        arrival = new double[mesh.faceIdBound()];
        Arrays.fill(regionArea, 0);
        for (int active = 0; active < mesh.faceCount(); active++) {
            int face = mesh.faceIdAt(active);
            double open = 1;
            for (int at = 0; at < mesh.faceEdgeCount(face); at++) {
                int halfEdge = mesh.edgeHalfEdge(mesh.faceEdgeAt(face, at));
                for (int vertex : new int[] { mesh.halfEdgeVertex(halfEdge), mesh.halfEdgeEndVertex(halfEdge) }) {
                    open = Math.min(open, blocking[vertex] ? 0 : openness[vertex]);
                }
            }
            arrival[face] = open * Math.sqrt(steep[face]);
            regionArea[regionOfFace[face]] += faceArea[face];
        }
        if (ringSource) {
            // The seams give off the air, each vertex its length of seam, and the creases swallow
            // it. What leaves a seam over a face is what the creases on that side swallow.
            double[] given = new double[seam.length];
            for (int[] loop : loops) {
                for (int edge : loop) {
                    given[mesh.halfEdgeVertex(mesh.edgeHalfEdge(edge))] += edgeLength(edge) / 2;
                    given[mesh.halfEdgeEndVertex(mesh.edgeHalfEdge(edge))] += edgeLength(edge) / 2;
                }
            }
            double[] level = walker.fadingRound(new boolean[seam.length], new boolean[seam.length], null, given, 0,
                    swallow);
            Arrays.fill(arrival, 0);
            for (int corner = 0; corner < walker.triangles.length; corner++) {
                int first = corner - corner % corners;
                int one = walker.triangles[first + (corner + 1) % corners];
                int other = walker.triangles[first + (corner + 2) % corners];
                int face = walker.faceOfTriangle[corner / corners];
                if (seam[one] != seam[other] && faceArea[face] > 0) {
                    arrival[face] += walker.cornerCotangent[corner] / 2
                            * (seam[one] ? level[one] - level[other] : level[other] - level[one]) / faceArea[face];
                }
            }
        }
        // Direct routes: a seam edge's share from a side is the air reaching the face there by the
        // shortest ways round the walls, over the air reaching it with no walls; never more than all.
        fairShare = null;
        shareGrain = null;
        routes = null;
        if (directRoutes) {
            double[] rate = new double[regionCount];
            for (int region = 0; region < regionCount; region++) {
                rate[region] = regionSeamLength[region] > 0 ? regionArea[region] / regionSeamLength[region] : 0;
            }
            routes = new DirectAirRoutes(mesh, faceArea, faceCentroid, regionOfFace, borderEdge, airWall, rate);
            DirectAirRoutes unwalled = new DirectAirRoutes(mesh, faceArea, faceCentroid, regionOfFace, borderEdge,
                    null, rate);
            fairShare = new double[SIDES * mesh.edgeIdBound()];
            Arrays.fill(fairShare, 1);
            for (int[] loop : loops) {
                for (int edge : loop) {
                    for (int side = 0; side < SIDES; side++) {
                        int face = mesh.edgeFace(edge, side);
                        if (face >= 0 && arrivalShares) {
                            // Against the region's average: the air this face's seam edges are due.
                            double seamLength = 0;
                            for (int at = 0; at < mesh.faceEdgeCount(face); at++) {
                                int around = mesh.faceEdgeAt(face, at);
                                seamLength += borderEdge[around] ? edgeLength(around) : 0;
                            }
                            double due = seamLength * rate[regionOfFace[face]];
                            fairShare[SIDES * edge + side] = due > 0 ? Math.min(1, routes.carried[face] / due) : 1;
                        } else if (face >= 0 && unwalled.carried[face] > 0) {
                            fairShare[SIDES * edge + side] = Math.min(1,
                                    routes.carried[face] / unwalled.carried[face]);
                        }
                    }
                }
            }
        }
        // Squeezes and delays: a seam edge's share from a side is how crowded the worst of its air's
        // way is, or how late its air is, with no walls over the same with them; never more than all.
        if (crowdingShares || delayShares) {
            boolean[] noWall = new boolean[seam.length];
            double[] freeLife = walker.lifetimeRound(seam, noWall, null, starts);
            double[] freeSteep = walker.steepness(freeLife);
            double[] walled = delayShares ? walker.lateness(seam, blocking, openness, life, steep)
                    : walker.worstUpstream(life, steep, borderEdge, blocking);
            double[] free = delayShares ? walker.lateness(seam, noWall, null, freeLife, freeSteep)
                    : walker.worstUpstream(freeLife, freeSteep, borderEdge, noWall);
            // A pocket is the faces air can pass between without crossing a seam or a wall. Its air
            // presses on all its seam alike, so what arrives is counted over the whole pocket. A wall
            // a seam lies on still parts the pockets, or a seam half landed would let its sliver out.
            // Graded air has no walls but the vertices that let none by.
            boolean[] parting = gradedWalls ? blocking : walls();
            int[] pocket = UnionFind.singletons(mesh.faceIdBound());
            for (int active = 0; active < mesh.edgeCount(); active++) {
                int edge = mesh.edgeIdAt(active);
                if (!borderEdge[edge] && mesh.edgeFace(edge, 0) >= 0 && mesh.edgeFace(edge, 1) >= 0
                        && DirectAirRoutes.openEnds(mesh, edge, parting) > 0) {
                    UnionFind.union(pocket, mesh.edgeFace(edge, 0), mesh.edgeFace(edge, 1));
                }
            }
            double[] reaching = new double[pocket.length];
            double[] due = new double[pocket.length];
            // The solve leaves out a face touching a wall, but its air is in the pocket all the same.
            for (int active = 0; active < mesh.faceCount(); active++) {
                int face = mesh.faceIdAt(active);
                boolean shut = false;
                for (int at = 0; at < mesh.faceEdgeCount(face); at++) {
                    shut |= DirectAirRoutes.openEnds(mesh, mesh.faceEdgeAt(face, at), blocking) < 2;
                }
                reaching[UnionFind.find(pocket, face)] += shut ? faceArea[face] : 0;
            }
            fairShare = new double[SIDES * mesh.edgeIdBound()];
            Arrays.fill(fairShare, 1);
            shareGrain = new double[fairShare.length];
            for (int pass = 0; pass < 2; pass++) {
                for (int[] loop : loops) {
                    for (int edge : loop) {
                        for (int side = 0; side < SIDES; side++) {
                            int face = mesh.edgeFace(edge, side);
                            int root = face < 0 ? -1 : UnionFind.find(pocket, face);
                            if (root >= 0 && pass == 0) {
                                reaching[root] += edgeLength(edge) * arrival[face];
                                due[root] += edgeLength(edge) * Math.sqrt(freeSteep[face]);
                            } else if (root >= 0 && due[root] > 0) {
                                // What arrives, less what a more crowded or later way than the open
                                // surface's holds back.
                                double throttle = walled[face] > 0 ? Math.min(1, free[face] / walled[face]) : 1;
                                fairShare[SIDES * edge + side] = Math.min(1, reaching[root] / due[root] * throttle);
                                shareGrain[SIDES * edge + side] = faceArea[face] / due[root];
                            }
                        }
                    }
                }
            }
        }
        // Fair shares: all of a region's seam edges drain at once, with its walls and without.
        for (int region = 0; fairShares && region < regionCount; region++) {
            if (fairShare == null) {
                fairShare = new double[SIDES * mesh.edgeIdBound()];
                Arrays.fill(fairShare, 1);
            }
            List<Integer> keys = new ArrayList<>();
            List<Double> drainLengths = new ArrayList<>();
            for (int[] loop : loops) {
                for (int edge : loop) {
                    for (int side = 0; side < SIDES; side++) {
                        int face = mesh.edgeFace(edge, side);
                        if (face >= 0 && regionOfFace[face] == region) {
                            keys.add(SIDES * edge + side);
                            drainLengths.add(edgeLength(edge));
                        }
                    }
                }
            }
            if (!sheltered[region] || keys.isEmpty()) {
                continue;
            }
            int[] faces = keys.stream().mapToInt(key -> mesh.edgeFace(key / SIDES, key % SIDES)).toArray();
            double[] lengths = drainLengths.stream().mapToDouble(Double::doubleValue).toArray();
            double carried = regionArea[region] / regionSeamLength[region];
            double[] unwalled = new WidthLimitedFlow(walker, regionOfFace, region, borderEdge, faces, lengths, null,
                    carried).fairFill();
            double[] walled = new WidthLimitedFlow(walker, regionOfFace, region, borderEdge, faces, lengths, airWall,
                    carried).fairFill();
            for (int drain = 0; drain < faces.length; drain++) {
                fairShare[keys.get(drain)] = unwalled[drain] > 0 ? walled[drain] / unwalled[drain] : 1;
            }
        }
    }

    /**
     * The vertices air cannot pass where grooves are walls: every valley's low line under
     * {@link #ungatedWalls}, else the groove bottoms, run on along the low line under
     * {@link #closedWalls}.
     *
     * @return whether each vertex is a wall, by vertex id
     */
    private boolean[] walls() {
        return ungatedWalls ? valleyLine : closedWalls ? closedBottom : grooveBottom;
    }

    /**
     * The push on a seam from either side: the pressure of the region there, one over its area,
     * times the share of its air arriving at the seam vertex.
     *
     * @param ring the seam
     * @return its fans and pushes, or null when it is not one path or meets the mesh border
     */
    private SeamPush seamPush(int ring) {
        int[] cycle = cycle(loops[ring]);
        if (cycle == null) {
            return null;
        }
        boolean open = endRings[ring] != null;
        int count = open ? cycle.length + 1 : cycle.length;
        wallEpoch++;
        for (int end = 0; open && end < SIDES; end++) {
            for (int edge : loops[endRings[ring][end]]) {
                wallStamp[edge] = wallEpoch;
            }
        }
        int[][][] fans = new int[SIDES][count][];
        double[][] push = new double[SIDES][count];
        boolean[] onBottom = new boolean[count];
        for (int side = 0; side < SIDES; side++) {
            for (int at = 0; at < count; at++) {
                int incoming = open ? (at == 0 ? -1 : cycle[at - 1]) : cycle[(at + count - 1) % count];
                int outgoing = open && at == cycle.length ? -1 : cycle[at];
                fans[side][at] = fan(incoming, outgoing, side);
                if (fans[side][at] == null) {
                    return null;
                }
                onBottom[at] = !wallsUnpinned && !creaseSource && !ringSource && !creasesSwallow && (!gradedWalls || gradedHeld) && walls()[incoming < 0
                        ? mesh.halfEdgeVertex(outgoing) : mesh.halfEdgeEndVertex(incoming)];
                double area = 0;
                for (int face : fans[side][at]) {
                    area += faceArea[face];
                    push[side][at] += faceArea[face] * arrival[face];
                }
                double regionSize = regionArea[regionOfFace[fans[side][at][0]]];
                // Crease air draws a seam where pressure pushes one, so it counts as a push the other way.
                push[side][at] /= creaseSource || ringSource ? -area
                        : travelWeighted && !travelKeepsPressure ? area : area * regionSize;
            }
        }
        // Shelter first: beside a region with a wall no seam lies on, the shares of each side's air
        // decide. Pressure, more for the smaller region, decides only where nothing shelters.
        for (int at = 0; at < count; at++) {
            boolean shares = shareOnly || creaseSource || ringSource || travelWeighted && !travelKeepsPressure
                    || shelterFirst && (sheltered[regionOfFace[fans[0][at][0]]]
                            || sheltered[regionOfFace[fans[1][at][0]]]);
            for (int side = 0; side < SIDES && !shares; side++) {
                push[side][at] /= regionArea[regionOfFace[fans[side][at][0]]];
            }
        }
        // A seam is held where it runs along a wall, not where it only crosses one.
        boolean[] held = new boolean[count];
        for (int at = 0; at < count; at++) {
            boolean before = open && at == 0 ? false : onBottom[(at + count - 1) % count];
            boolean after = open && at == count - 1 ? false : onBottom[(at + 1) % count];
            held[at] = onBottom[at] && (before || after);
        }
        // Bottlenecks: over each stretch no wall holds, a side's push is the share of the air that
        // could reach it over open surface that still can once a wall lets by only what its gaps are
        // wide enough for. Where the two sides' shares tie, the pushes above stand.
        boolean[] starved = new boolean[count];
        int still = -1;
        boolean stretches = bottleneck && fairShare == null;
        for (int at = 0; stretches && !open && at < count; at++) {
            still = held[at] ? at : still;
        }
        int length = 0;
        for (int step = 0; stretches && step <= count; step++) {
            int at = (still + 1 + step) % count;
            if (step < count && !held[at]) {
                length++;
                continue;
            }
            int start = (still + 1 + step - length) % count;
            double[] share = { 1, 1 };
            for (int side = 0; side < SIDES && length > 0; side++) {
                int region = regionOfFace[fans[side][start][0]];
                if (!sheltered[region]) {
                    continue;
                }
                boolean[] taken = new boolean[cycle.length];
                List<Integer> drainFaces = new ArrayList<>();
                List<Double> drainLengths = new ArrayList<>();
                for (int along = 0; along < 2 * length; along++) {
                    int edgeAt = (start + along / 2) % count - 1 + along % 2;
                    edgeAt = open ? edgeAt : (edgeAt + cycle.length) % cycle.length;
                    if (edgeAt < 0 || edgeAt >= cycle.length || taken[edgeAt]) {
                        continue;
                    }
                    taken[edgeAt] = true;
                    int face = mesh.halfEdgeFace(side == 0 ? cycle[edgeAt] : mesh.halfEdgeTwin(cycle[edgeAt]));
                    if (face >= 0 && regionOfFace[face] == region) {
                        drainFaces.add(face);
                        drainLengths.add(edgeLength(mesh.halfEdgeEdge(cycle[edgeAt])));
                    }
                }
                int[] faces = drainFaces.stream().mapToInt(Integer::intValue).toArray();
                double[] lengths = drainLengths.stream().mapToDouble(Double::doubleValue).toArray();
                double carried = regionArea[region] / regionSeamLength[region];
                double unwalled = walker.reaching(regionOfFace, region, borderEdge, faces, lengths, null, carried);
                share[side] = unwalled <= 0 ? 1
                        : walker.reaching(regionOfFace, region, borderEdge, faces, lengths, airWall, carried)
                                / unwalled;
            }
            for (int along = 0; along < length
                    && Math.abs(share[0] - share[1]) > WASTE_TIE_SHARE_OF_LIFETIME; along++) {
                push[0][(start + along) % count] = share[0];
                push[1][(start + along) % count] = share[1];
                starved[(start + along) % count] = true;
            }
            length = 0;
        }
        // Fair shares: a free vertex's share from a side is that of the seam edges it ends, by length.
        for (int at = 0; bottleneck && fairShare != null && at < count; at++) {
            double[] share = new double[SIDES];
            // Shares closer than one face's air makes of them tie: the seam cannot stand any finer.
            double tie = WASTE_TIE_SHARE_OF_LIFETIME;
            for (int side = 0; side < SIDES; side++) {
                double ended = 0;
                for (int near = 0; near < 2; near++) {
                    int edgeAt = open ? at - 1 + near : (at - 1 + near + cycle.length) % cycle.length;
                    if (edgeAt < 0 || edgeAt >= cycle.length) {
                        continue;
                    }
                    int edge = mesh.halfEdgeEdge(cycle[edgeAt]);
                    int face = mesh.halfEdgeFace(side == 0 ? cycle[edgeAt] : mesh.halfEdgeTwin(cycle[edgeAt]));
                    if (face >= 0) {
                        double along = edgeLength(edge);
                        int key = SIDES * edge + (mesh.edgeFace(edge, 0) == face ? 0 : 1);
                        share[side] += along * fairShare[key];
                        tie = shareGrain == null || !grainTies ? tie : Math.max(tie, shareGrain[key]);
                        ended += along;
                    }
                }
                share[side] = ended > 0 ? share[side] / ended : 1;
            }
            if (sharesScalePressure) {
                // The pressure push stands, bends and all, less the share a wall keeps from it.
                push[0][at] *= share[0];
                push[1][at] *= share[1];
                starved[at] = !held[at] && Math.abs(share[0] - share[1]) > tie;
            } else if (!held[at] && Math.abs(share[0] - share[1]) > tie) {
                push[0][at] = share[0];
                push[1][at] = share[1];
                starved[at] = true;
            }
        }
        return new SeamPush(cycle, fans, push, held, starved);
    }

    /**
     * The length of an edge.
     *
     * @param edge the edge
     * @return the distance between its ends
     */
    private double edgeLength(int edge) {
        int halfEdge = mesh.edgeHalfEdge(edge);
        return mesh.vertexPosition(mesh.halfEdgeVertex(halfEdge), new Vector3f())
                .distance(mesh.vertexPosition(mesh.halfEdgeEndVertex(halfEdge), new Vector3f()));
    }

    /**
     * The push on every vertex of a seam as the air was last measured.
     *
     * @param ring the seam
     * @return a row per seam vertex: its vertex id, the push from either side, 1 when a wall holds
     *         it, 1 when the shares of air decided its pushes, then the unit direction from it
     *         into either side; empty when the seam is not one path
     */
    public double[][] pushesAlong(int ring) {
        SeamPush push = seamPush(ring);
        double[][] rows = new double[push == null ? 0 : push.onBottom().length][];
        Vector3f at3 = new Vector3f();
        for (int at = 0; at < rows.length; at++) {
            int vertex = at < push.cycle().length ? mesh.halfEdgeVertex(push.cycle()[at])
                    : mesh.halfEdgeEndVertex(push.cycle()[at - 1]);
            rows[at] = new double[PUSH_ROW_BEFORE_DIRECTIONS + SIDES * XYZ];
            rows[at][0] = vertex;
            rows[at][1] = push.push()[0][at];
            rows[at][2] = push.push()[1][at];
            rows[at][XYZ] = push.onBottom()[at] ? 1 : 0;
            rows[at][XYZ + 1] = push.starved()[at] ? 1 : 0;
            mesh.vertexPosition(vertex, at3);
            for (int side = 0; side < SIDES; side++) {
                Vector3f into = new Vector3f();
                for (int face : push.fans()[side][at]) {
                    into.add(faceCentroid[XYZ * face], faceCentroid[XYZ * face + 1], faceCentroid[XYZ * face + 2]);
                }
                into.div(push.fans()[side][at].length).sub(at3).normalize();
                rows[at][PUSH_ROW_BEFORE_DIRECTIONS + XYZ * side] = into.x;
                rows[at][PUSH_ROW_BEFORE_DIRECTIONS + XYZ * side + 1] = into.y;
                rows[at][PUSH_ROW_BEFORE_DIRECTIONS + XYZ * side + 2] = into.z;
            }
        }
        return rows;
    }

    /**
     * Stand the seams and regions as a recorded pass left them and measure the air again, so
     * {@link #pushesAlong} tells that pass's pushes.
     *
     * @param pass the index into {@link #loopsByPass}
     */
    public void standAtPass(int pass) {
        int[][] snapshot = loopsByPass.get(pass);
        for (int ring = 0; ring < loops.length; ring++) {
            loops[ring] = snapshot[ring].clone();
        }
        System.arraycopy(snapshot[loops.length], 0, regionOfFace, 0, regionOfFace.length);
        stoodAt = pass;
        measureAir();
    }

    /**
     * Move a seam over the stretch where one side pushes hardest, unless the push measured after
     * turns round harder than it pushed. A vertex on a groove bottom stays.
     *
     * @param ring the seam
     * @return whether the seam moved
     */
    private boolean airMove(int ring) {
        SeamPush before = seamPush(ring);
        if (before == null) {
            return false;
        }
        boolean open = endRings[ring] != null;
        int count = before.onBottom().length;
        int[] oldLoop = loops[ring];
        List<double[]> candidates = new ArrayList<>();
        for (int side = 0; side < SIDES; side++) {
            boolean[] pushed = new boolean[count];
            int still = open ? count - 1 : -1;
            for (int at = 0; at < count; at++) {
                pushed[at] = !before.onBottom()[at] && before.push()[1 - side][at] > before.push()[side][at];
                still = pushed[at] || open ? still : at;
            }
            double total = 0;
            int length = 0;
            for (int step = 1; step <= count; step++) {
                int at = (still + step) % count;
                total += pushed[at] ? before.push()[1 - side][at] - before.push()[side][at] : 0;
                length += pushed[at] ? 1 : 0;
                if (length > 0 && (!pushed[at] || step == count)) {
                    candidates.add(new double[] { total, side, (at - length + (pushed[at] ? 1 : 0) + count) % count,
                            length });
                    total = 0;
                    length = 0;
                }
            }
        }
        candidates.sort((one, two) -> Double.compare(two[0], one[0]));
        int measured = 0;
        for (int next = 0; next < candidates.size(); next++) {
            double[] candidate = candidates.get(next);
            if (measured >= MEASURED_MOVES_PER_RING) {
                break;
            }
            int side = (int) candidate[1];
            int eaten = regionOfFace[before.fans()[side][0][0]];
            int across = side == 0 ? mesh.halfEdgeFace(mesh.halfEdgeTwin(before.cycle()[0]))
                    : mesh.halfEdgeFace(before.cycle()[0]);
            int growing = splitsNothing[ring] ? eaten : regionOfFace[across];
            int eatenSize = 0;
            for (int active = 0; active < mesh.faceCount(); active++) {
                eatenSize += regionOfFace[mesh.faceIdAt(active)] == eaten ? 1 : 0;
            }
            Map<Integer, Boolean> onNewLoop = new LinkedHashMap<>();
            for (int edge : oldLoop) {
                onNewLoop.put(edge, true);
            }
            boolean[] swept = new boolean[mesh.faceIdBound()];
            List<Integer> faces = new ArrayList<>();
            for (int length = 0; length < (int) candidate[3]; length++) {
                for (int face : before.fans()[side][((int) candidate[2] + length) % count]) {
                    if (!swept[face] && regionOfFace[face] == eaten) {
                        swept[face] = true;
                        faces.add(face);
                        for (int at = 0; at < mesh.faceEdgeCount(face); at++) {
                            if (wallStamp[mesh.faceEdgeAt(face, at)] != wallEpoch) {
                                onNewLoop.merge(mesh.faceEdgeAt(face, at), true, (was, flip) -> !was);
                            }
                        }
                    }
                }
            }
            List<Integer> loop = new ArrayList<>();
            onNewLoop.forEach((edge, on) -> {
                if (on) {
                    loop.add(edge);
                }
            });
            int[] checked = faces.size() >= eatenSize ? null : open ? checkedPath(ring, loop) : checkedLoop(ring, loop);
            int[][] followed = checked == null ? null : open ? new int[loops.length][] : followers(ring, checked);
            if (followed == null) {
                refusals++;
                halves(before, candidate, candidates);
                continue;
            }
            int[][] loopsBefore = loops.clone();
            for (int face : faces) {
                regionOfFace[face] = growing;
            }
            loops[ring] = checked;
            for (int bisector = 0; bisector < loops.length; bisector++) {
                loops[bisector] = followed[bisector] == null ? loops[bisector] : followed[bisector];
            }
            measureAir();
            measured++;
            SeamPush after = seamPush(ring);
            double turned = 0;
            int moved = 0;
            int landed = 0;
            for (int at = 0; after != null && at < after.onBottom().length; at++) {
                for (int behind = 0; behind < SIDES; behind++) {
                    if (Arrays.stream(after.fans()[behind][at]).anyMatch(face -> swept[face])) {
                        // A seam that arrives on a wall stops there, whatever the sides then push.
                        landed += after.onBottom()[at] ? 1 : 0;
                        turned += after.onBottom()[at] ? 0 : after.push()[1 - behind][at] - after.push()[behind][at];
                        moved += after.onBottom()[at] ? 0 : 1;
                    }
                }
            }
            if (moved == 0 && landed > 0 || moved > 0 && turned / moved < candidate[0] / candidate[3]) {
                return true;
            }
            for (int face : faces) {
                regionOfFace[face] = eaten;
            }
            System.arraycopy(loopsBefore, 0, loops, 0, loops.length);
            halves(before, candidate, candidates);
        }
        if (measured > 0) {
            measureAir();
        }
        return false;
    }

    /**
     * Queue the two halves of a stretch that could not move whole.
     *
     * @param push       the seam's pushes
     * @param candidate  the stretch: its summed push, the side it sweeps, its first seam vertex and
     *                   its length
     * @param candidates the stretches still to try, which gains the halves
     */
    private void halves(SeamPush push, double[] candidate, List<double[]> candidates) {
        int count = push.onBottom().length;
        int side = (int) candidate[1];
        int length = (int) candidate[3];
        for (int half = 0; half < SIDES && length > 1; half++) {
            int start = ((int) candidate[2] + half * (length / 2)) % count;
            int halfLength = half == 0 ? length / 2 : length - length / 2;
            double total = 0;
            for (int step = 0; step < halfLength; step++) {
                int at = (start + step) % count;
                total += push.push()[1 - side][at] - push.push()[side][at];
            }
            candidates.add(new double[] { total, side, start, halfLength });
        }
    }

    /**
     * Measure the walkers' lifetimes as the rings stand: {@link #waste}, {@link #deathRate}, each
     * region's area and lifetime, and how steeply the lifetime falls over each face with and
     * without the groove bottoms absorbing.
     */
    private void measureLifetimes() {
        boolean[] absorbing = new boolean[mesh.vertexIdBound()];
        for (int[] loop : loops) {
            for (int edge : loop) {
                absorbing[mesh.halfEdgeVertex(mesh.edgeHalfEdge(edge))] = true;
                absorbing[mesh.halfEdgeEndVertex(mesh.edgeHalfEdge(edge))] = true;
            }
        }
        boolean[] walled = absorbing.clone();
        boolean uncovered = false;
        for (int vertex = 0; vertex < walled.length; vertex++) {
            if (grooveBottom[vertex] && !absorbing[vertex]) {
                walled[vertex] = true;
                uncovered = true;
            }
        }
        double[] free = walker.lifetime(absorbing);
        double[] held = uncovered ? walker.lifetime(walled) : free;
        steepnessFree = walker.steepness(free);
        steepness = uncovered ? walker.steepness(held) : steepnessFree;
        Arrays.fill(regionArea, 0);
        Arrays.fill(regionLifetime, 0);
        waste = 0;
        lifetimeFree = 0;
        lifetimeHeld = 0;
        for (int active = 0; active < mesh.vertexCount(); active++) {
            int vertex = mesh.vertexIdAt(active);
            double mass = walker.massByVertexId[vertex];
            waste += mass * (free[vertex] - held[vertex]);
            lifetimeFree += mass * free[vertex];
            lifetimeHeld += mass * held[vertex];
            if (!walled[vertex] && mesh.vertexFaceCount(vertex) > 0) {
                regionLifetime[regionOfFace[mesh.vertexFaceAt(vertex, 0)]] += mass * held[vertex];
            }
        }
        for (int active = 0; active < mesh.faceCount(); active++) {
            regionArea[regionOfFace[mesh.faceIdAt(active)]] += faceArea[mesh.faceIdAt(active)];
        }
        deathRate = 0;
        for (int region = 0; region < regionCount; region++) {
            deathRate += regionArea[region] > 0 ? regionArea[region] / regionLifetime[region] : 0;
        }
    }

    /**
     * Move a ring by the run of fans that the lifetimes' steepness beside it says most lowers
     * {@link #waste} plus the lifetime the rings end, or {@link #deathRate} where waste ties,
     * keeping the move only when the lifetimes measured after it agree.
     *
     * @param ring the ring
     * @return whether the ring moved
     */
    private boolean lifetimeMove(int ring) {
        int[] cycle = cycle(loops[ring]);
        if (cycle == null) {
            return false;
        }
        int[] oldLoop = loops[ring];
        boolean open = endRings[ring] != null;
        int count = open ? cycle.length + 1 : cycle.length;
        wallEpoch++;
        for (int end = 0; open && end < SIDES; end++) {
            for (int edge : loops[endRings[ring][end]]) {
                wallStamp[edge] = wallEpoch;
            }
        }
        int[][][] fans = new int[SIDES][count][];
        double[][] push = new double[SIDES][count];
        double[][] pushFree = new double[SIDES][count];
        boolean[] onBottom = new boolean[count];
        for (int side = 0; side < SIDES; side++) {
            for (int at = 0; at < count; at++) {
                int incoming = open ? (at == 0 ? -1 : cycle[at - 1]) : cycle[(at + count - 1) % count];
                int outgoing = open && at == cycle.length ? -1 : cycle[at];
                fans[side][at] = fan(incoming, outgoing, side);
                if (fans[side][at] == null) {
                    return false;
                }
                // A groove bottom the ring leaves goes on absorbing, so the side behind gains nothing there.
                onBottom[at] = grooveBottom[incoming < 0 ? mesh.halfEdgeVertex(outgoing)
                        : mesh.halfEdgeEndVertex(incoming)];
                double area = 0;
                for (int face : fans[side][at]) {
                    area += faceArea[face];
                    push[side][at] += faceArea[face] * steepness[face];
                    pushFree[side][at] += faceArea[face] * steepnessFree[face];
                }
                push[side][at] /= area;
                pushFree[side][at] /= area;
            }
        }
        double tie = WASTE_TIE_SHARE_OF_LIFETIME * lifetimeFree;
        List<double[]> candidates = new ArrayList<>();
        int[] eatenOfSide = new int[SIDES];
        int[] growingOfSide = new int[SIDES];
        for (int side = 0; side < SIDES; side++) {
            int eaten = regionOfFace[fans[side][0][0]];
            int across = side == 0 ? mesh.halfEdgeFace(mesh.halfEdgeTwin(cycle[0])) : mesh.halfEdgeFace(cycle[0]);
            int growing = splitsNothing[ring] ? eaten : regionOfFace[across];
            eatenOfSide[side] = eaten;
            growingOfSide[side] = growing;
            int eatenSize = 0;
            for (int active = 0; active < mesh.faceCount(); active++) {
                eatenSize += regionOfFace[mesh.faceIdAt(active)] == eaten ? 1 : 0;
            }
            for (int start = 0; start < count; start++) {
                runEpoch++;
                double wasteChange = 0;
                double rateChange = 0;
                double lifeChange = 0;
                int swept = 0;
                boolean stopped = false;
                int longest = open ? count - start : start == 0 ? count : count - 1;
                for (int length = 1; length <= longest && !stopped; length++) {
                    int at = (start + length - 1) % count;
                    // A seam stays where a groove bottom holds it: no run sweeps past such a vertex.
                    if (onBottom[at]) {
                        break;
                    }
                    for (int face : fans[side][at]) {
                        if (faceStamp[face] == runEpoch) {
                            continue;
                        }
                        faceStamp[face] = runEpoch;
                        if (regionOfFace[face] != eaten) {
                            stopped = true;
                            break;
                        }
                        swept++;
                        double area = faceArea[face];
                        double gain = onBottom[at] ? 0 : push[1 - side][at];
                        wasteChange += area * (pushFree[1 - side][at] - pushFree[side][at] - gain + push[side][at]);
                        rateChange += area / regionLifetime[growing] - area / regionLifetime[eaten]
                                - regionArea[growing] * area * gain
                                        / (regionLifetime[growing] * regionLifetime[growing])
                                + regionArea[eaten] * area * push[side][at]
                                        / (regionLifetime[eaten] * regionLifetime[eaten]);
                        lifeChange += area * (gain - push[side][at]);
                    }
                    stopped |= swept >= eatenSize;
                    boolean wasteTied = Math.abs(wasteChange) <= tie;
                    double primary = wasteTied ? 0 : wasteChange - lifeChange;
                    if (!stopped && (primary < 0 || wasteTied && rateChange < 0)) {
                        candidates.add(new double[] { primary, rateChange, side, start, length });
                    }
                }
            }
        }
        candidates.sort((one, two) -> one[0] != two[0] ? Double.compare(one[0], two[0])
                : Double.compare(one[1], two[1]));
        double wasteBefore = waste;
        double rateBefore = deathRate;
        double heldBefore = lifetimeHeld;
        int measured = 0;
        for (double[] candidate : candidates) {
            if (measured >= MEASURED_MOVES_PER_RING) {
                break;
            }
            int side = (int) candidate[2];
            int start = (int) candidate[3];
            Map<Integer, Boolean> onNewLoop = new LinkedHashMap<>();
            for (int edge : oldLoop) {
                onNewLoop.put(edge, true);
            }
            List<Integer> faces = new ArrayList<>();
            runEpoch++;
            for (int length = 1; length <= (int) candidate[4]; length++) {
                for (int face : fans[side][(start + length - 1) % count]) {
                    if (faceStamp[face] != runEpoch) {
                        faceStamp[face] = runEpoch;
                        faces.add(face);
                        for (int at = 0; at < mesh.faceEdgeCount(face); at++) {
                            if (wallStamp[mesh.faceEdgeAt(face, at)] != wallEpoch) {
                                onNewLoop.merge(mesh.faceEdgeAt(face, at), true, (was, flip) -> !was);
                            }
                        }
                    }
                }
            }
            List<Integer> loop = new ArrayList<>();
            onNewLoop.forEach((edge, on) -> {
                if (on) {
                    loop.add(edge);
                }
            });
            int[] checked = open ? checkedPath(ring, loop) : checkedLoop(ring, loop);
            int[][] followed = checked == null ? null : open ? new int[loops.length][] : followers(ring, checked);
            if (followed == null) {
                refusals++;
                continue;
            }
            int[][] loopsBefore = loops.clone();
            for (int face : faces) {
                regionOfFace[face] = growingOfSide[side];
            }
            loops[ring] = checked;
            for (int bisector = 0; bisector < loops.length; bisector++) {
                loops[bisector] = followed[bisector] == null ? loops[bisector] : followed[bisector];
            }
            measureLifetimes();
            measured++;
            boolean wasteTies = Math.abs(waste - wasteBefore) <= tie;
            if (!wasteTies && waste - lifetimeHeld < wasteBefore - heldBefore - tie
                    || wasteTies && deathRate < rateBefore) {
                return true;
            }
            for (int face : faces) {
                regionOfFace[face] = eatenOfSide[side];
            }
            System.arraycopy(loopsBefore, 0, loops, 0, loops.length);
        }
        if (measured > 0) {
            measureLifetimes();
        }
        return false;
    }

    /**
     * The geometry with every ring at its fitted loop: the loop as the ring's edge
     * marks, and the ring's authored anchors moved to the loop vertices nearest
     * them and traced again as a spline.
     *
     * @param bundle the bundle the fit was built from
     * @param metric the surface's metric, which the re-trace measures on
     * @return the fitted bundle
     */
    public GeometryBundle fitted(GeometryBundle bundle, SurfaceMetric metric) {
        GeometryBundle out = bundle;
        Vector3f at = new Vector3f();
        Vector3f loopAt = new Vector3f();
        for (int ring = 0; ring < ringLabels.length; ring++) {
            if (!movable[ring]) {
                continue;
            }
            boolean[] marks = new boolean[mesh.edgeIdBound()];
            List<Integer> loopVertices = new ArrayList<>();
            boolean[] seen = new boolean[mesh.vertexIdBound()];
            for (int edge : loops[ring]) {
                marks[edge] = true;
                int halfEdge = mesh.edgeHalfEdge(edge);
                for (int vertex : new int[] { mesh.halfEdgeVertex(halfEdge), mesh.halfEdgeEndVertex(halfEdge) }) {
                    if (!seen[vertex]) {
                        seen[vertex] = true;
                        loopVertices.add(vertex);
                    }
                }
            }
            out = EdgeMarks.with(out, ringLabels[ring], marks);
            SurfaceSpline drawn = SurfaceSpline.inBundle(bundle, ringLabels[ring]);
            if (drawn == null) {
                continue;
            }
            int[] anchors = new int[drawn.authoredCount()];
            int count = 0;
            for (int anchor = 0; anchor < drawn.anchorCount; anchor++) {
                if (!drawn.anchorAuthored[anchor]) {
                    continue;
                }
                mesh.vertexPosition(drawn.anchorVertexId[anchor], at);
                double best = Double.POSITIVE_INFINITY;
                for (int vertex : loopVertices) {
                    double squared = mesh.vertexPosition(vertex, loopAt).distanceSquared(at);
                    if (squared < best) {
                        best = squared;
                        anchors[count] = vertex;
                    }
                }
                count++;
            }
            if (endRings[ring] != null && count >= SIDES) {
                int[] walk = cycle(loops[ring]);
                int[] ends = { mesh.halfEdgeVertex(walk[0]), mesh.halfEdgeEndVertex(walk[walk.length - 1]) };
                mesh.vertexPosition(anchors[0], at);
                int first = mesh.vertexPosition(ends[0], loopAt).distanceSquared(at)
                        <= mesh.vertexPosition(ends[1], loopAt).distanceSquared(at) ? 0 : 1;
                anchors[0] = ends[first];
                anchors[count - 1] = ends[1 - first];
                boolean[][] walls = new boolean[SIDES][];
                for (int end = 0; end < SIDES; end++) {
                    for (int wall : endRings[ring]) {
                        for (int edge : loops[wall]) {
                            int halfEdge = mesh.edgeHalfEdge(edge);
                            if (mesh.halfEdgeVertex(halfEdge) == anchors[end * (count - 1)]
                                    || mesh.halfEdgeEndVertex(halfEdge) == anchors[end * (count - 1)]) {
                                walls[end] = EdgeMarks.bools(out, ringLabels[wall]);
                            }
                        }
                    }
                }
                SurfaceSpline cut = SurfaceSpline.bisector(SurfaceGeodesics.over(metric), null,
                        Arrays.copyOf(anchors, count), walls[0], walls[1]);
                out = SurfaceSpline.with(out, ringLabels[ring], cut);
                continue;
            }
            AuthoredSplineRing traced = new AuthoredSplineRing(SurfaceGeodesics.over(metric));
            if (traced.trace(anchors, count, null, SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH)) {
                out = SurfaceSpline.with(out, ringLabels[ring], SurfaceSpline.of(traced.tracer));
            }
        }
        return out;
    }

    /**
     * The region on the far side of a ring from a given one.
     *
     * @param ring   the ring
     * @param region a region the ring bounds
     * @return the other region, or -1 when the ring has that region on both sides
     */
    private int regionAcross(int ring, int region) {
        for (int edge : loops[ring]) {
            for (int side = 0; side < SIDES; side++) {
                int face = mesh.edgeFace(edge, side);
                if (face >= 0 && regionOfFace[face] != region) {
                    return regionOfFace[face];
                }
            }
        }
        return -1;
    }

    /**
     * Each vertex's distance along edges to the nearest groove bottom: a vertex of full groove
     * strength whose valley curvature is the highest among its neighbours.
     *
     * @param creases groove strength and valley curvature measured on the surface
     * @return distance by vertex id, 0 everywhere on a surface without a groove bottom
     */
    private double[] grooveBottomDistance(SurfaceCreases creases) {
        List<Integer> bottoms = new ArrayList<>();
        for (int active = 0; active < mesh.vertexCount(); active++) {
            int vertex = mesh.vertexIdAt(active);
            boolean bottom = creases.valleyStrength[vertex] >= SurfaceCreases.CREASE_STRENGTH;
            for (int at = 0; at < mesh.vertexEdgeCount(vertex) && bottom; at++) {
                int other = mesh.edgeOtherVertex(mesh.vertexEdgeAt(vertex, at), vertex);
                bottom = creases.valleyCurvature[vertex]
                        >= (1 - GROOVE_BOTTOM_CURVATURE_TOLERANCE) * creases.valleyCurvature[other];
            }
            if (bottom) {
                bottoms.add(vertex);
            }
        }
        double[] edgeCost = new double[mesh.edgeIdBound()];
        for (int active = 0; active < mesh.edgeCount(); active++) {
            edgeCost[mesh.edgeIdAt(active)] = mesh.edgeLength(mesh.edgeIdAt(active));
        }
        double[] reached = Dijkstra.forest(mesh, bottoms.stream().mapToInt(Integer::intValue).toArray(),
                edgeCost).distance;
        double[] distance = new double[mesh.vertexIdBound()];
        for (int vertex = 0; vertex < Math.min(reached.length, distance.length); vertex++) {
            distance[vertex] = Double.isInfinite(reached[vertex]) ? 0 : reached[vertex];
        }
        return distance;
    }
}
