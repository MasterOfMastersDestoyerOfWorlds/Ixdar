package ixdar.geometry.mesh.data.sculpt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

import ixdar.geometry.mesh.data.ops.MeshFolds;
import ixdar.geometry.mesh.data.paths.query.TriangleBvh;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.nodegraph.nodes.modifier.StrokeSculptNode;

/**
 * Matching pursuit (MZ93) of key strokes over a candidate dictionary, each atom the candidate's move
 * at unit depth under the real stroke engine ({@link DisplacementSum}), read along the normals.
 */
public final class StrokePursuit {

    public static final float FINEST_EDGE_FLOOR = 0.005f;

    public static final float REPLACE_MARGIN = 1e-3f;

    public static final int WORKERS = 6;

    public static final int FOLD_BISECTIONS = 6;

    /** The base the candidates are held on. */
    public final MeshTopology base;

    /** The template whose cell cameras the candidates are lifted through. */
    public final StrokeSketch template;

    /** The surface atoms are measured on. */
    public final MeshTopology surface;

    /** Axis the pass mirrors across, -1 for none. */
    public final int symmetryAxis;

    /** Offset still to cover along {@link #normalXyz} by dense surface vertex, updated as strokes are chosen. */
    public final float[] residual;

    /** Area each dense surface vertex stands for, the inner product's weight. */
    public final float[] weight;

    /** The surface's stroke directions, packed xyz, as the stroke engine smooths them. */
    public final float[] normalXyz;

    /**
     * Deepest |depth| per unit squared radius before a stroke terraces, as the stroke_sculpt report
     * flags TOO STEEP: depth may reach radius^2 times this.
     */
    public final float depthPerSquaredRadius;

    /** The dictionary: every candidate stroke at unit depth. */
    public final List<AnchoredStroke> candidates = new ArrayList<>();

    /** Each candidate's atom: packed pairs of dense surface vertex index and move along the normal. */
    public final List<float[]> atoms = new ArrayList<>();

    /** Each candidate's weighted squared norm. */
    public float[] energy = new float[0];

    /** Each candidate's falloff radius. */
    public float[] radius = new float[0];

    /**
     * Each candidate's deepest raise and sink, packed in pairs: the terrace limit, lowered to the
     * largest |depth| each way at which its own move folds no face once that is measured.
     */
    public float[] depthLimits = new float[0];

    /**
     * Whether each candidate's fold limit is measured: lazily, for those about to be chosen, since a
     * lower limit only lowers a gain and an unmeasured one bounds it from above.
     */
    public boolean[] foldsMeasured = new boolean[0];

    /** Each chosen candidate's depth, 0 for the rest. */
    public float[] depth = new float[0];

    /** Whether each candidate is chosen. */
    public boolean[] chosen = new boolean[0];

    /** Chosen candidates, by index, in the order they were chosen. */
    public final List<Integer> selection = new ArrayList<>();

    /** Candidates whose atom was computed, over the pursuit's life. */
    public int atomsComputed;

    /**
     * Each candidate's move at unit depth in full: packed quadruples of dense vertex index and xyz
     * offset, kept so its fold limit is measured without lifting and summing it again.
     */
    private final List<float[]> moves = new ArrayList<>();

    private final ThreadLocal<DisplacementSum> sums;

    private final int[] activeOfSurface;

    private final float[] restXyz;

    private final ThreadLocal<MeshFolds> foldTests;

    private final ThreadLocal<float[]> trialXyz;

    private final ForkJoinPool workers = new ForkJoinPool(WORKERS);

    /**
     * A pursuit over a surface and its offsets to the goal.
     *
     * @param base         the base the candidates are held on
     * @param template     the template whose cells they are drawn in
     * @param surface      the surface atoms are measured on
     * @param projection   the surface's offsets to the goal, their normals and areas
     * @param symmetryAxis axis the pass mirrors across, -1 for none
     */
    public StrokePursuit(MeshTopology base, StrokeSketch template, MeshTopology surface, GoalProjection projection,
            int symmetryAxis) {
        this.base = base;
        this.template = template;
        this.surface = surface;
        this.symmetryAxis = symmetryAxis;
        residual = projection.offset.clone();
        weight = projection.area;
        normalXyz = projection.normalXyz;
        double meanEdge = 0.0;
        for (int activeEdge = 0; activeEdge < base.edgeCount(); activeEdge++) {
            meanEdge += base.edgeLength(base.edgeIdAt(activeEdge)) / (double) base.edgeCount();
        }
        float finestEdge = Math.max(FINEST_EDGE_FLOOR, (float) meanEdge / (1 << StrokeRefinement.MAXIMUM_ROUNDS));
        depthPerSquaredRadius = 1f / (StrokeSculptNode.STEEPNESS_LIMIT * finestEdge);
        // One triangle tree for every worker's sum: they only read it.
        TriangleBvh tree = TriangleBvh.over(surface, new int[surface.triangleCount()]);
        sums = ThreadLocal.withInitial(() -> new DisplacementSum(surface, true, 0f, 1, tree));
        activeOfSurface = new int[surface.vertexIdBound()];
        restXyz = surface.packedPositions(activeOfSurface);
        foldTests = ThreadLocal.withInitial(() -> new MeshFolds(surface, activeOfSurface, restXyz));
        trialXyz = ThreadLocal.withInitial(restXyz::clone);
    }

    /**
     * Add candidates to the dictionary, their atoms computed in parallel; a candidate that moves
     * nothing is left out.
     *
     * @param strokes the candidates, at unit depth
     * @return how many joined
     */
    public int add(List<AnchoredStroke> strokes) {
        float[][] lifted = new float[strokes.size()][];
        float[][] computed = new float[strokes.size()][];
        parallel(strokes.size(), index -> {
            lifted[index] = lift(strokes.get(index));
            computed[index] = atom(lifted[index]);
        });
        atomsComputed += strokes.size();
        int joined = 0;
        int start = candidates.size();
        energy = Arrays.copyOf(energy, start + strokes.size());
        radius = Arrays.copyOf(radius, start + strokes.size());
        depthLimits = Arrays.copyOf(depthLimits, (start + strokes.size()) * 2);
        foldsMeasured = Arrays.copyOf(foldsMeasured, start + strokes.size());
        depth = Arrays.copyOf(depth, start + strokes.size());
        chosen = Arrays.copyOf(chosen, start + strokes.size());
        for (int index = 0; index < strokes.size(); index++) {
            float norm = norm(computed[index]);
            if (norm <= 0f) {
                continue;
            }
            int at = start + joined++;
            candidates.add(strokes.get(index));
            atoms.add(computed[index]);
            moves.add(lifted[index]);
            energy[at] = norm;
            radius[at] = KeyCandidates.radius(strokes.get(index));
            depthLimits[at * 2] = radius[at] * radius[at] * depthPerSquaredRadius;
            depthLimits[at * 2 + 1] = depthLimits[at * 2];
            foldsMeasured[at] = false;
        }
        energy = Arrays.copyOf(energy, start + joined);
        radius = Arrays.copyOf(radius, start + joined);
        depthLimits = Arrays.copyOf(depthLimits, (start + joined) * 2);
        foldsMeasured = Arrays.copyOf(foldsMeasured, start + joined);
        depth = Arrays.copyOf(depth, start + joined);
        chosen = Arrays.copyOf(chosen, start + joined);
        return joined;
    }

    /**
     * Take strokes already chosen (a key so far) into the dictionary and the selection at their
     * depths, their moves taken out of the residual; a stroke that moves nothing is dropped.
     *
     * @param strokes the strokes, at unit depth
     * @param depths  each one's depth
     */
    public void choose(List<AnchoredStroke> strokes, float[] depths) {
        int start = candidates.size();
        add(strokes);
        for (int index = 0; index < strokes.size(); index++) {
            int at = candidates.subList(start, candidates.size()).indexOf(strokes.get(index)) + start;
            if (at >= start) {
                depth[at] = depths[index];
                chosen[at] = true;
                selection.add(at);
                move(atoms.get(at), -depths[index]);
            }
        }
    }

    /**
     * One stroke's move at unit depth: lifted through its cell exactly as the stroke_sculpt node
     * lifts a sidecar's stroke, and its mirror copies added to this thread's sum at depth 1.
     *
     * @param stroke the stroke, its attributes at depth 1
     * @return packed quadruples of dense vertex index and xyz offset at every vertex it reached,
     *         empty when it lifts nothing
     */
    private float[] lift(AnchoredStroke stroke) {
        StrokeAnchors single = new StrokeAnchors();
        single.strokes.add(stroke);
        String text = single.sketch(template, base).lift(base);
        if (text.isEmpty()) {
            return new float[0];
        }
        DisplacementSum sum = sums.get();
        for (SculptStroke copy : StrokeSculpt.copies(new String[] { text }, symmetryAxis)) {
            sum.add(copy);
        }
        int count = sum.touchedLength;
        int[] touched = Arrays.copyOf(sum.touched, count);
        sum.finishStroke();
        float[] packed = new float[count * 4];
        for (int index = 0; index < count; index++) {
            int at = touched[index] * 3;
            packed[index * 4] = touched[index];
            System.arraycopy(sum.offsetXyz, at, packed, index * 4 + 1, 3);
            Arrays.fill(sum.offsetXyz, at, at + 3, 0f);
        }
        return packed;
    }

    /**
     * A move read along the normals: the atom the pursuit correlates.
     *
     * @param move packed quadruples from {@link #lift}
     * @return packed pairs of dense vertex index and move along its normal, zero moves left out
     */
    private float[] atom(float[] move) {
        float[] packed = new float[move.length / 2];
        int length = 0;
        for (int quad = 0; quad < move.length; quad += 4) {
            int at = (int) move[quad] * 3;
            float along = move[quad + 1] * normalXyz[at] + move[quad + 2] * normalXyz[at + 1]
                    + move[quad + 3] * normalXyz[at + 2];
            if (along != 0f) {
                packed[length++] = move[quad];
                packed[length++] = along;
            }
        }
        return Arrays.copyOf(packed, length);
    }

    /**
     * The deepest raise and sink of a move before it folds a face, bisected below its terrace limit.
     *
     * <p>The engine's limiter pulls back a move that folds a face, which facets the surface, so the
     * key keeps each stroke shallow enough that its own move folds none.
     *
     * @param move    packed quadruples from {@link #lift}
     * @param terrace the deepest the stroke may be before it terraces
     * @param limits  receives the deepest raise and sink ({@link #depthLimits})
     */
    private void foldLimits(float[] move, float terrace, float[] limits) {
        int count = move.length / 4;
        int[] touched = new int[count];
        for (int index = 0; index < count; index++) {
            touched[index] = (int) move[index * 4];
        }
        MeshFolds folds = foldTests.get();
        float[] xyz = trialXyz.get();
        for (int side = 0; side < 2; side++) {
            float sign = side == 0 ? 1f : -1f;
            float low = 0f;
            float high = terrace;
            for (int trial = 0; trial <= FOLD_BISECTIONS && high - low > 0f; trial++) {
                float tried = trial == 0 ? high : (low + high) * 0.5f;
                for (int index = 0; index < count; index++) {
                    for (int axis = 0; axis < 3; axis++) {
                        xyz[touched[index] * 3 + axis] = restXyz[touched[index] * 3 + axis]
                                + sign * tried * move[index * 4 + 1 + axis];
                    }
                }
                folds.update(xyz, touched, count);
                boolean folded = false;
                for (int index = 0; index < count && !folded; index++) {
                    int vertexId = surface.vertexIdAt(touched[index]);
                    for (int face = 0; face < surface.vertexFaceCount(vertexId) && !folded; face++) {
                        int faceId = surface.vertexFaceAt(vertexId, face);
                        folded = folds.failedByFaceId[faceId] && !folds.foldedAtStart[faceId];
                    }
                }
                if (folded) {
                    high = tried;
                } else {
                    low = tried;
                }
                if (trial == 0 && !folded) {
                    break;
                }
            }
            limits[side] = low;
        }
        for (int activeVertex : touched) {
            System.arraycopy(restXyz, activeVertex * 3, xyz, activeVertex * 3, 3);
        }
        folds.update(xyz, touched, count);
    }

    /**
     * Measure the fold limits of candidates not measured yet, in parallel, from their kept moves.
     *
     * @param indices the candidates
     */
    public void measureFolds(List<Integer> indices) {
        List<Integer> unmeasured = indices.stream().filter(index -> !foldsMeasured[index]).toList();
        parallel(unmeasured.size(), at -> {
            int index = unmeasured.get(at);
            float[] limits = new float[2];
            foldLimits(moves.get(index), radius[index] * radius[index] * depthPerSquaredRadius, limits);
            depthLimits[index * 2] = limits[0];
            depthLimits[index * 2 + 1] = limits[1];
            foldsMeasured[index] = true;
        });
    }

    /**
     * Choose strokes until the selection holds {@code target} or no candidate lowers the residual:
     * each round the candidate whose best depth (clamped to its {@link #depthLimits}) removes the most
     * weighted squared residual, its move then taken out of the residual (MZ93, eq. 12).
     *
     * @param target selection size to reach
     * @return how many strokes this call chose
     */
    public int pursue(int target) {
        int chose = 0;
        int count = candidates.size();
        float[] gains = new float[count];
        float[] depths = new float[count];
        float[] correlation = new float[count];
        parallel(count, index -> correlation[index] = chosen[index] ? 0f : correlate(atoms.get(index)));
        // Choosing a stroke changes the residual only where its atom reaches, so only the candidates
        // whose atoms share one of those vertices correlate differently next round.
        int[] candidateStart = new int[residual.length + 1];
        for (float[] atom : atoms) {
            for (int pair = 0; pair < atom.length; pair += 2) {
                candidateStart[(int) atom[pair] + 1]++;
            }
        }
        for (int vertex = 0; vertex < residual.length; vertex++) {
            candidateStart[vertex + 1] += candidateStart[vertex];
        }
        int[] candidateByVertex = new int[candidateStart[residual.length]];
        int[] cursor = candidateStart.clone();
        for (int index = 0; index < count; index++) {
            float[] atom = atoms.get(index);
            for (int pair = 0; pair < atom.length; pair += 2) {
                candidateByVertex[cursor[(int) atom[pair]]++] = index;
            }
        }
        boolean[] stale = new boolean[count];
        int[] staleList = new int[count];
        while (selection.size() < target) {
            for (int index = 0; index < count; index++) {
                gains[index] = 0f;
                if (!chosen[index]) {
                    depths[index] = clamp(index, correlation[index] / energy[index]);
                    gains[index] = 2f * depths[index] * correlation[index]
                            - depths[index] * depths[index] * energy[index];
                }
            }
            int best = -1;
            for (int index = 0; index < gains.length; index++) {
                best = gains[index] > 0f && (best < 0 || gains[index] > gains[best]) ? index : best;
            }
            if (best < 0) {
                break;
            }
            if (!foldsMeasured[best]) {
                // Gains not yet fold-limited bound the true ones from above: measure the few
                // unmeasured contenders with the highest bounds together, on the pursuit's workers.
                List<Integer> contenders = IntStream.range(0, gains.length)
                        .filter(index -> !foldsMeasured[index] && gains[index] > 0f).boxed()
                        .sorted((left, right) -> Float.compare(gains[right], gains[left])).limit(WORKERS).toList();
                measureFolds(contenders);
                continue;
            }
            move(atoms.get(best), -depths[best]);
            depth[best] = depths[best];
            chosen[best] = true;
            selection.add(best);
            chose++;
            int staleLength = 0;
            float[] chosenAtom = atoms.get(best);
            for (int pair = 0; pair < chosenAtom.length; pair += 2) {
                int vertex = (int) chosenAtom[pair];
                for (int slot = candidateStart[vertex]; slot < candidateStart[vertex + 1]; slot++) {
                    int index = candidateByVertex[slot];
                    if (!stale[index] && !chosen[index]) {
                        stale[index] = true;
                        staleList[staleLength++] = index;
                    }
                }
            }
            int[] refresh = Arrays.copyOf(staleList, staleLength);
            parallel(staleLength, at -> correlation[refresh[at]] = correlate(atoms.get(refresh[at])));
            for (int index : refresh) {
                stale[index] = false;
            }
        }
        return chose;
    }

    /**
     * Re-fit every chosen stroke's depth to the residual the others leave, in selection order, as an
     * orthogonal pursuit projects onto all chosen atoms.
     *
     * @param sweeps passes over the selection
     */
    public void sweepDepths(int sweeps) {
        measureFolds(selection);
        for (int sweep = 0; sweep < sweeps; sweep++) {
            for (int index : selection) {
                float refit = clamp(index, correlate(atoms.get(index)) / energy[index] + depth[index]);
                move(atoms.get(index), depth[index] - refit);
                depth[index] = refit;
            }
        }
    }

    /**
     * Coordinate descent on the shape of chosen strokes: each one's variants (radius and position)
     * scored against the residual without it, the best kept at its best depth when it beats the
     * stroke.
     *
     * @param from   first selection position to refine
     * @param shapes the candidate builder that makes the variants
     * @return how many strokes changed shape
     */
    public int refineShapes(int from, KeyCandidates shapes) {
        int changed = 0;
        for (int position = from; position < selection.size(); position++) {
            int index = selection.get(position);
            measureFolds(List.of(index));
            move(atoms.get(index), depth[index]);
            float correlation = correlate(atoms.get(index));
            float keptDepth = clamp(index, correlation / energy[index]);
            float keptGain = 2f * keptDepth * correlation - keptDepth * keptDepth * energy[index];
            List<AnchoredStroke> variants = shapes.variants(candidates.get(index));
            float[][] variantMoves = new float[variants.size()][];
            float[][] variantAtoms = new float[variants.size()][];
            float[][] variantLimits = new float[variants.size()][];
            parallel(variants.size(), variant -> {
                variantMoves[variant] = lift(variants.get(variant));
                variantAtoms[variant] = atom(variantMoves[variant]);
            });
            atomsComputed += variants.size();
            int best = -1;
            float bestDepth = 0f;
            float bestGain = keptGain * (1f + REPLACE_MARGIN);
            // A variant's gain at its terrace limit bounds its gain at its fold limit from above, so
            // only the variants that bound could still win have their folds measured, best first.
            float[] bound = new float[variants.size()];
            for (int variant = 0; variant < variants.size(); variant++) {
                float norm = norm(variantAtoms[variant]);
                float terrace = KeyCandidates.radius(variants.get(variant))
                        * KeyCandidates.radius(variants.get(variant)) * depthPerSquaredRadius;
                float variantDepth = norm <= 0f ? 0f
                        : Math.max(-terrace, Math.min(terrace, correlate(variantAtoms[variant]) / norm));
                bound[variant] = 2f * variantDepth * correlate(variantAtoms[variant])
                        - variantDepth * variantDepth * norm;
            }
            for (;;) {
                int next = -1;
                for (int variant = 0; variant < variants.size(); variant++) {
                    next = variantLimits[variant] == null && bound[variant] > bestGain
                            && (next < 0 || bound[variant] > bound[next]) ? variant : next;
                }
                if (next < 0) {
                    break;
                }
                variantLimits[next] = new float[2];
                foldLimits(variantMoves[next], KeyCandidates.radius(variants.get(next))
                        * KeyCandidates.radius(variants.get(next)) * depthPerSquaredRadius, variantLimits[next]);
                float norm = norm(variantAtoms[next]);
                float variantCorrelation = correlate(variantAtoms[next]);
                float variantDepth = Math.max(-variantLimits[next][1],
                        Math.min(variantLimits[next][0], variantCorrelation / norm));
                float gain = 2f * variantDepth * variantCorrelation - variantDepth * variantDepth * norm;
                if (gain > bestGain) {
                    best = next;
                    bestGain = gain;
                    bestDepth = variantDepth;
                }
            }
            if (best >= 0) {
                candidates.set(index, variants.get(best));
                atoms.set(index, variantAtoms[best]);
                moves.set(index, variantMoves[best]);
                energy[index] = norm(variantAtoms[best]);
                radius[index] = KeyCandidates.radius(variants.get(best));
                System.arraycopy(variantLimits[best], 0, depthLimits, index * 2, 2);
                foldsMeasured[index] = true;
                keptDepth = bestDepth;
                changed++;
            }
            move(atoms.get(index), -keptDepth);
            depth[index] = keptDepth;
        }
        return changed;
    }

    /**
     * Drop every chosen stroke whose removal would raise the weighted squared residual by no more
     * than a tolerance.
     *
     * @param tolerance the largest rise a dropped stroke may cost
     * @return how many were dropped
     */
    public int prune(float tolerance) {
        int dropped = 0;
        for (int position = selection.size() - 1; position >= 0; position--) {
            int index = selection.get(position);
            float rise = 2f * depth[index] * correlate(atoms.get(index)) + depth[index] * depth[index] * energy[index];
            if (rise <= tolerance) {
                move(atoms.get(index), depth[index]);
                depth[index] = 0f;
                chosen[index] = false;
                selection.remove(position);
                dropped++;
            }
        }
        return dropped;
    }

    /**
     * Forget the candidates not chosen, keeping chains when asked, so a new set of fills can be
     * drawn from the current residual.
     *
     * @param keepChains whether unchosen chains stay
     */
    public void forgetUnchosen(boolean keepChains) {
        int kept = 0;
        int[] newIndex = new int[candidates.size()];
        for (int index = 0; index < candidates.size(); index++) {
            boolean keep = chosen[index] || keepChains && !candidates.get(index).polygon;
            newIndex[index] = keep ? kept : -1;
            if (keep) {
                candidates.set(kept, candidates.get(index));
                atoms.set(kept, atoms.get(index));
                moves.set(kept, moves.get(index));
                energy[kept] = energy[index];
                radius[kept] = radius[index];
                depthLimits[kept * 2] = depthLimits[index * 2];
                depthLimits[kept * 2 + 1] = depthLimits[index * 2 + 1];
                foldsMeasured[kept] = foldsMeasured[index];
                depth[kept] = depth[index];
                chosen[kept] = chosen[index];
                kept++;
            }
        }
        candidates.subList(kept, candidates.size()).clear();
        atoms.subList(kept, atoms.size()).clear();
        moves.subList(kept, moves.size()).clear();
        energy = Arrays.copyOf(energy, kept);
        radius = Arrays.copyOf(radius, kept);
        depthLimits = Arrays.copyOf(depthLimits, kept * 2);
        foldsMeasured = Arrays.copyOf(foldsMeasured, kept);
        depth = Arrays.copyOf(depth, kept);
        chosen = Arrays.copyOf(chosen, kept);
        selection.replaceAll(index -> newIndex[index]);
    }

    /**
     * The weighted squared residual still to cover.
     *
     * @return the sum over vertices of area times residual squared
     */
    public double residualEnergy() {
        double total = 0.0;
        for (int activeVertex = 0; activeVertex < residual.length; activeVertex++) {
            total += weight[activeVertex] * residual[activeVertex] * residual[activeVertex];
        }
        return total;
    }

    /**
     * Run a body over indices on the pursuit's own few workers, each holding a displacement sum over
     * the surface, so the sums' memory stays bounded whatever the machine's core count.
     *
     * @param count how many indices
     * @param body  the work for one index
     */
    private void parallel(int count, IntConsumer body) {
        workers.submit(() -> IntStream.range(0, count).parallel().forEach(body)).join();
    }

    /**
     * The depth a candidate would take against the current residual, unclamped: its sign says
     * whether it would sink or raise, cut a valley or a ridge.
     *
     * @param index the candidate
     * @return the depth
     */
    public float fitDepth(int index) {
        return correlate(atoms.get(index)) / energy[index];
    }

    /**
     * An atom's weighted inner product with the residual.
     *
     * @param atom packed pairs of vertex and move
     * @return the inner product
     */
    private float correlate(float[] atom) {
        double total = 0.0;
        for (int pair = 0; pair < atom.length; pair += 2) {
            int activeVertex = (int) atom[pair];
            total += weight[activeVertex] * residual[activeVertex] * atom[pair + 1];
        }
        return (float) total;
    }

    /**
     * An atom's weighted squared norm.
     *
     * @param atom packed pairs of vertex and move
     * @return the norm, 0 for an empty atom
     */
    private float norm(float[] atom) {
        double total = 0.0;
        for (int pair = 0; pair < atom.length; pair += 2) {
            total += weight[(int) atom[pair]] * atom[pair + 1] * atom[pair + 1];
        }
        return (float) total;
    }

    /**
     * Add a multiple of an atom to the residual.
     *
     * @param atom  packed pairs of vertex and move
     * @param scale the multiple; minus a stroke's depth takes its move out
     */
    private void move(float[] atom, float scale) {
        for (int pair = 0; pair < atom.length; pair += 2) {
            residual[(int) atom[pair]] += scale * atom[pair + 1];
        }
    }

    /**
     * A depth held within a candidate's limits: no deeper than it can be drawn without folding a
     * face or terracing.
     *
     * @param index  the candidate
     * @param wanted the depth asked for
     * @return the depth, within {@link #depthLimits}
     */
    private float clamp(int index, float wanted) {
        return Math.max(-depthLimits[index * 2 + 1], Math.min(depthLimits[index * 2], wanted));
    }
}
