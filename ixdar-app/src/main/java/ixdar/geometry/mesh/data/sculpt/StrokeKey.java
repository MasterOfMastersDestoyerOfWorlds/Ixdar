package ixdar.geometry.mesh.data.sculpt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.analysis.MeshDistance;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * A stroke "answer key": the strokes that move a base onto a goal, at several stroke counts. The base
 * is refined, projected onto the goal, and decomposed into strokes by pursuit, with periodic depth
 * and shape refinement and pruning; each count's key is sculpted by the real pass and scored.
 */
public final class StrokeKey {

    public static final int DEPTH_SWEEPS = 2;

    public static final double PRUNE_FRACTION = 1e-6;

    public static final int STALLS = 3;

    public static final int[] DEFAULT_COUNTS = { 25, 50, 100, 200, 400 };

    public static final List<String> LAYER_ORDER = List.of(KeyCandidates.SINK_LAYER, KeyCandidates.RAISE_LAYER,
            KeyCandidates.RIDGE_LAYER, KeyCandidates.VALLEY_LAYER);

    public static final String SCULPT_STAGE = "sculpt pass";

    public static final String CHAMFER_STAGE = "chamfer";

    public static final List<String> STAGES = List.of("refine and project", "candidate atoms", "pursue",
            "sweep depths", "refine shapes", "prune and fresh fills", SCULPT_STAGE, CHAMFER_STAGE);

    /** Seconds the last {@link #compute} spent in each of {@link #STAGES}, summed over counts. */
    public double[] secondsByStage = new double[0];

    /** Axis the key's pass mirrors across, as the sculpt's stroke_sculpt symmetry; -1 for none. */
    public int symmetryAxis = 0;

    /** Stroke counts a key is kept at, ascending. */
    public int[] counts = DEFAULT_COUNTS;

    /** Strokes chosen between refinements, prunes and fresh fill candidates. */
    public int refineEvery = 25;

    /** Rounds of midpoint subdivision of the whole base the offsets are measured on. */
    public int surfaceRounds = 2;

    /**
     * The goal's own ridge and valley lines in its own frame, packed xyz each, offered as the key's
     * chains; null to offer the offset field's lines instead.
     */
    public List<float[]> goalLines;

    /** The candidate builder, its scales and limits public to tune before {@link #compute}. */
    public KeyCandidates shapes;

    /** The surface the offsets were measured on: the base, refined. */
    public MeshTopology surface;

    /** The base's offsets to the goal over {@link #surface}. */
    public final GoalProjection projection = new GoalProjection();

    /** The pursuit, its dictionary and selection as the last count left them. */
    public StrokePursuit pursuit;

    /** Each count's key, its strokes held on the base in layers, ready to write beside an SVG. */
    public final List<StrokeAnchors> keyByCount = new ArrayList<>();

    /**
     * Every candidate the pursuit was offered, in the order it was offered, each layered by the sign
     * of the depth it fitted when offered; refined shapes and resumed strokes are not candidates.
     */
    public final StrokeAnchors candidateStrokes = new StrokeAnchors();

    /** The largest count's key sculpted by the stroke pass, its {@link StrokeSculpt} in the sculpt slot. */
    public GeometryBundle lastSculpt;

    /** Each count's chamfer to the reference as mesh-compare --normalize measures it; NaN without one. */
    public float[] chamferByCount = new float[0];

    /** Each count's Hausdorff distance to the reference, normalised; NaN without one. */
    public float[] hausdorffByCount = new float[0];

    /** Each count's root mean square offset left over the surface, in the base's units. */
    public float[] residualByCount = new float[0];

    /** The root mean square offset before any stroke. */
    public float initialResidual;

    /** The base's own chamfer to the reference, NaN without one. */
    public float baseChamfer = Float.NaN;

    private long lastLap;

    /**
     * Keep the candidates the pursuit took in from {@code from} on in {@link #candidateStrokes}, each
     * in the layer the sign of its fitted depth puts it in, at that depth.
     *
     * @param from the first new candidate's index
     */
    private void recordCandidates(int from) {
        for (int index = from; index < pursuit.candidates.size(); index++) {
            AnchoredStroke candidate = pursuit.candidates.get(index);
            float depth = pursuit.fitDepth(index);
            AnchoredStroke stroke = new AnchoredStroke();
            stroke.layer = KeyCandidates.layer(candidate, depth);
            stroke.id = "candidate" + StrokeAnchors.LAYER_ID_SEPARATOR + (candidateStrokes.strokes.size() + 1);
            stroke.cell = candidate.cell;
            stroke.polygon = candidate.polygon;
            stroke.anchors = candidate.anchors;
            stroke.attributes = KeyCandidates.withDepth(candidate, depth);
            candidateStrokes.strokes.add(stroke);
        }
    }

    /**
     * Compute the key: refine the base, project it onto the goal, and pursue strokes up to each
     * count, keeping each count's key, its sculpt and its scores.
     *
     * @param base      the sculpt's base, as its load_mesh gives it
     * @param goal      the goal mesh, in any frame (it is moved into the base's)
     * @param reference the mesh chamfer is scored against, or null to skip scoring
     * @param template  the multiview template the strokes are drawn in
     * @param start     a key so far to continue from, its strokes kept chosen at their depths, or null
     */
    public void compute(GeometryBundle base, MeshTopology goal, MeshTopology reference, StrokeSketch template,
            StrokeAnchors start) {
        secondsByStage = new double[STAGES.size()];
        lastLap = System.nanoTime();
        MeshTopology baseMesh = base.mesh();
        surface = baseMesh;
        for (int round = 0; round < surfaceRounds; round++) {
            boolean[] all = new boolean[surface.faceIdBound()];
            Arrays.fill(all, true);
            surface = new MidpointSplit().split(surface, all);
        }
        projection.project(surface, new DisplacementSum(surface, false, 0f, 1).normalXyz, goal, baseMesh);
        lap(0);
        if (shapes == null) {
            shapes = new KeyCandidates(baseMesh, template);
        }
        shapes.symmetryAxis = symmetryAxis;
        pursuit = new StrokePursuit(baseMesh, template, surface, projection, symmetryAxis);
        double totalArea = 0.0;
        for (float area : projection.area) {
            totalArea += area;
        }
        initialResidual = (float) Math.sqrt(pursuit.residualEnergy() / totalArea);
        double tolerance = PRUNE_FRACTION * pursuit.residualEnergy();
        if (start != null) {
            List<AnchoredStroke> kept = new ArrayList<>();
            float[] depths = new float[start.strokes.size()];
            for (AnchoredStroke stroke : start.strokes) {
                Matcher depth = StrokeSketch.DEPTH_ATTRIBUTE.matcher(stroke.attributes);
                depths[kept.size()] = depth.find() ? Float.parseFloat(depth.group(1)) : 0f;
                AnchoredStroke unit = new AnchoredStroke();
                unit.cell = stroke.cell;
                unit.polygon = stroke.polygon;
                unit.anchors = stroke.anchors;
                unit.attributes = stroke.attributes;
                unit.attributes = KeyCandidates.withDepth(unit, 1f);
                kept.add(unit);
            }
            pursuit.choose(kept, depths);
        }
        candidateStrokes.strokes.clear();
        int offered = pursuit.candidates.size();
        // The goal's lines into the base's frame the way the projection relates them: bounding-box
        // centres and diagonals.
        List<float[]> baseLines = goalLines == null ? null : goalLines.stream()
                .map(line -> MeshDistance.inFrame(MeshDistance.normalized(line.clone(), goal),
                        baseMesh.center(new Vector3f()), baseMesh.boundsDiagonal()))
                .toList();
        // The goal's own lines wait until the fills have taken the broad shape, then go on top of them;
        // the offset field's lines compete with the fills from the start.
        List<AnchoredStroke> lineStrokes = baseLines == null ? null
                : shapes.chains(surface, projection.offset, projection.normalXyz, baseLines);
        if (lineStrokes == null) {
            pursuit.add(shapes.chains(surface, projection.offset, projection.normalXyz, null));
        }
        pursuit.add(shapes.fills(surface, pursuit.residual, projection.area));
        recordCandidates(offered);
        lap(1);
        float[] referenceXyz = reference == null ? null : MeshDistance.normalizedPositions(reference);
        baseChamfer = referenceXyz == null ? Float.NaN : MeshDistance.computeAllMetrics(
                MeshDistance.normalizedPositions(baseMesh), referenceXyz, 1f).chamferDistance;
        lap(STAGES.indexOf(CHAMFER_STAGE));
        chamferByCount = new float[counts.length];
        hausdorffByCount = new float[counts.length];
        residualByCount = new float[counts.length];
        int lineCount = 0;
        for (int countIndex = 0; countIndex < counts.length; countIndex++) {
            // With the goal's lines, a count is the fills' alone; the lines come on top.
            int count = counts[countIndex] + lineCount;
            int stalls = 0;
            while (pursuit.selection.size() < count && stalls < STALLS) {
                int before = pursuit.selection.size();
                pursuit.pursue(Math.min(count, (before / refineEvery + 1) * refineEvery));
                lap(2);
                pursuit.sweepDepths(DEPTH_SWEEPS);
                lap(3);
                pursuit.refineShapes(before, shapes);
                lap(4);
                pursuit.sweepDepths(1);
                lap(3);
                pursuit.prune((float) tolerance);
                if (pursuit.selection.size() < count) {
                    pursuit.forgetUnchosen(true);
                    int fresh = pursuit.candidates.size();
                    pursuit.add(shapes.fills(surface, pursuit.residual, projection.area));
                    recordCandidates(fresh);
                }
                lap(5);
                stalls = pursuit.selection.size() > before ? 0 : stalls + 1;
            }
            if (lineStrokes != null && lineCount == 0) {
                // Every line at its carried place, its depth fitted to what the fills leave, then all
                // depths refitted together and each line tried narrower and wider, never moved.
                int from = pursuit.selection.size();
                pursuit.choose(lineStrokes, new float[lineStrokes.size()]);
                pursuit.sweepDepths(DEPTH_SWEEPS);
                lap(3);
                pursuit.refineShapes(from, shapes);
                lap(4);
                pursuit.sweepDepths(1);
                lap(3);
                pursuit.prune((float) tolerance);
                lineCount = pursuit.selection.size() - from;
                lap(5);
            }
            residualByCount[countIndex] = (float) Math.sqrt(pursuit.residualEnergy() / totalArea);
            // The key at this count, layer by layer, sculpted exactly as stroke_sculpt sculpts a
            // sidecar's strokes.
            StrokeAnchors key = new StrokeAnchors();
            key.baseVertices = baseMesh.vertexCount();
            key.baseFaces = baseMesh.faceCount();
            int[] placeByLayer = new int[LAYER_ORDER.size()];
            List<Integer> order = new ArrayList<>(pursuit.selection);
            order.sort(Comparator.comparingInt(index -> LAYER_ORDER.indexOf(KeyCandidates.layer(
                    pursuit.candidates.get(index), pursuit.depth[index]))));
            for (int index : order) {
                AnchoredStroke candidate = pursuit.candidates.get(index);
                float depth = pursuit.depth[index];
                AnchoredStroke stroke = new AnchoredStroke();
                stroke.layer = KeyCandidates.layer(candidate, depth);
                stroke.id = stroke.layer + StrokeAnchors.LAYER_ID_SEPARATOR + ++placeByLayer[LAYER_ORDER.indexOf(
                        stroke.layer)];
                stroke.cell = candidate.cell;
                stroke.polygon = candidate.polygon;
                stroke.anchors = candidate.anchors;
                stroke.attributes = KeyCandidates.withDepth(candidate, depth);
                key.strokes.add(stroke);
            }
            lap(2);
            GeometryBundle sculpted = sculpt(key, base, template);
            lastSculpt = sculpted;
            keyByCount.add(key);
            lap(STAGES.indexOf(SCULPT_STAGE));
            MeshDistance.MeshMetrics metrics = referenceXyz == null ? null : MeshDistance.computeAllMetrics(
                    MeshDistance.normalizedPositions(sculpted.mesh()), referenceXyz, 1f);
            chamferByCount[countIndex] = metrics == null ? Float.NaN : metrics.chamferDistance;
            hausdorffByCount[countIndex] = metrics == null ? Float.NaN : metrics.hausdorffDistance;
            lap(STAGES.indexOf(CHAMFER_STAGE));
        }
        pursuit.forgetUnchosen(false);
    }

    /**
     * Add the time since the last lap to one stage's total and start the next lap.
     *
     * @param stage index into {@link #STAGES}
     */
    private void lap(int stage) {
        long now = System.nanoTime();
        secondsByStage[stage] += (now - lastLap) / 1e9;
        lastLap = now;
    }

    /**
     * A key's sculpt, exactly as stroke_sculpt sculpts a sidecar's strokes: lifted through their
     * cells onto the base and summed in one pass with the key's symmetry.
     *
     * @param key      the key's strokes
     * @param base     the base
     * @param template the template whose cells the strokes are drawn in
     * @return the sculpted geometry, the pass's {@link StrokeSculpt} (its refined surface) in
     *         {@link StrokeSculpt#SCULPT_SLOT}
     */
    public GeometryBundle sculpt(StrokeAnchors key, GeometryBundle base, StrokeSketch template) {
        StrokeSculpt sculpt = new StrokeSculpt();
        sculpt.symmetryAxis = symmetryAxis;
        return sculpt.apply(base, key.sketch(template, base.mesh()).lift(base.mesh()))
                .withSlot(StrokeSculpt.SCULPT_SLOT, sculpt);
    }
}
