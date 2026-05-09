package ixdar.geometry.mesh.quadlayout.lyon2021;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.quadlayout.extraction.TransitionMatrix;
import ixdar.geometry.mesh.quadlayout.tmesh.TArc;
import ixdar.geometry.mesh.quadlayout.tmesh.TMesh;
import ixdar.geometry.mesh.quadlayout.tmesh.TPatch;

/**
 * Lyon 2021 §6 ¶1 — extract a conforming {@link QuadLayout} from a
 * quantized T-mesh by iteratively extending T-junctions.
 *
 * <p>Algorithm: while any T-mesh patch has a side with &gt; 1 arc, pick a
 * T-junction (an interior node on a multi-arc side), walk across the
 * patch to the opposite side at the same parametric distance, and either
 * (a) connect to an existing node if {@code q} matches (PATCH-77), or
 * (b) split the opposing arc to create one (PATCH-79). Each extension
 * splits the patch into two halves; both halves are re-checked.
 * Terminate when every patch has 4 single-arc sides.
 *
 * <p><b>Scope (PATCH-73 + PATCH-77).</b> Handles the no-T-junction fast
 * path AND the matching-opposite-node case. Patches needing arc splitting
 * (i.e. the T-junction's mirror distance falls inside an opposite arc
 * rather than at one of its endpoints) are returned via
 * {@code skippedPatchIds} pending PATCH-79.
 */
public final class QuadLayoutExtractor {
    public static final int NUM_3 = 3;
    public static final int NUM_4 = 4;
    public static final int NUM__2 = -2;
    public static final float NUM_0 = 0f;
    public static final float NUM_1 = 1f;

    /**
     * PATCH-92 diagnostic: per-bail counters in {@link #extractImpl}.
     */
    public static int statTPatchesIn;
    public static int statAllSingleArcEmit;
    public static int statBailEmptySide;
    public static int statBailNoMultiArcSide;
    public static int statBailEqTwoViolated;       // sideLen != oppLen
    public static int statBailNoMatchNoSplit;
    public static int statBailDegenerateSplit;
    public static int statTJunctionExactMatch;     // PATCH-77 success path
    public static int statTJunctionArcSplit;

    private QuadLayoutExtractor() {}

    /**
     * Build a conforming layout from {@code tmesh} and {@code q} (one int
     * per T-arc).
     *
     * <p>Mesh-aware overload: requires the underlying {@link ArrayMesh},
     * per-corner UV arrays, and {@link TransitionMatrix} for tracing
     * INTERIOR layout arcs across face boundaries. Use this for real meshes
     * where T-junction extension may fire.
     *
     * @param tmesh   T-mesh
     * @param q       per-T-arc integer quantization (length must equal
     *                {@code tmesh.arcs().size()})
     * @param mesh    underlying triangle mesh
     * @param uCorner per-corner u
     * @param vCorner per-corner v
     * @param trs     transition matrix for cross-seam interior arc tracing
     * @return extraction result with the conforming {@link QuadLayout},
     *         per-source-TPatch resolution counters, and skipped-patch ids
     */
    public static Result extract(TMesh tmesh, int[] q, ArrayMesh mesh,
                                  float[] uCorner, float[] vCorner,
                                  TransitionMatrix trs) {
        return extractImpl(tmesh, q, mesh, uCorner, vCorner, trs);
    }

    /**
     * Convenience overload for callers that don't have the mesh + TRS handy
     * (e.g. synthetic toy fixtures with no T-junctions). Throws if any
     * T-junction extension is actually attempted.
     *
     * @param tmesh T-mesh
     * @param q     per-T-arc integer quantization
     * @return extraction result; throws if any T-junction extension is
     *         attempted without the mesh + TRS context
     */
    public static Result extract(TMesh tmesh, int[] q) {
        return extractImpl(tmesh, q, null, null, null, null);
    }

    private static Result extractImpl(TMesh tmesh, int[] q,
                                       ArrayMesh mesh,
                                       float[] uCorner, float[] vCorner,
                                       TransitionMatrix trs) {
        if (q.length != tmesh.arcs().size()) {
            throw new IllegalArgumentException(
                    "q length " + q.length + " != arc count " + tmesh.arcs().size());
        }

        // Seed the LayoutArc registry: every TArc starts as one INHERITED
        // LayoutArc with id == TArc.id. This makes the side-rewrite from
        // T-arc-ids → LayoutArc-ids a no-op for the initial state.
        ArrayList<LayoutArc> layoutArcs = new ArrayList<>(tmesh.arcs().size());
        ArrayList<Integer> layoutArcQ = new ArrayList<>(tmesh.arcs().size());
        for (TArc tarc : tmesh.arcs()) {
            layoutArcs.add(LayoutArc.inherited(tarc.id(), tarc));
            layoutArcQ.add(q[tarc.id()]);
        }

        // Build worklist of WorkPatch entries (one per TPatch initially).
        Deque<WorkPatch> work = new ArrayDeque<>();
        ArrayList<Integer> skipped = new ArrayList<>();
        // PATCH-92 diagnostic: reset counters.
        statTPatchesIn = 0;
        statAllSingleArcEmit = 0;
        statBailEmptySide = 0;
        statBailNoMultiArcSide = 0;
        statBailEqTwoViolated = 0;
        statBailNoMatchNoSplit = 0;
        statBailDegenerateSplit = 0;
        statTJunctionExactMatch = 0;
        statTJunctionArcSplit = 0;

        ArrayList<TrianglePatch> triangles = new ArrayList<>();
        for (TPatch tp : tmesh.patches()) {
            int[][] tSides = tp.arcsBySide();
            if (tSides == null) {
                skipped.add(tp.id());
                continue;
            }
            if (tSides.length == NUM_3) {
                // PATCH-79 triangle wedge — emit directly, no extension needed.
                int[][] triSides = new int[NUM_3][];
                for (int s = 0; s < NUM_3; s++) triSides[s] = tSides[s].clone();
                triangles.add(new TrianglePatch(triangles.size(), triSides,
                        tp.cornerNodeIds().clone()));
                continue;
            }
            if (tSides.length != NUM_4) {
                skipped.add(tp.id());
                continue;
            }
            int[][] sides = new int[NUM_4][];
            for (int s = 0; s < NUM_4; s++) sides[s] = tSides[s].clone();
            work.push(new WorkPatch(tp.id(), sides, tp.cornerNodeIds().clone()));
            statTPatchesIn++;
        }

        // Resolve.
        ArrayList<QuadLayoutPatch> patches = new ArrayList<>();
        int tJunctionsResolved = 0;
        // Synthetic node ID allocator for arc-splitting (PATCH-80). New nodes
        // get IDs >= tmesh.nodes().size(); their position is implicit in the
        // underlying TArc + split fraction.
        int[] nextSyntheticNodeId = { tmesh.nodes().size() };

        while (!work.isEmpty()) {
            WorkPatch p = work.pop();
            if (allSingleArc(p.sides)) {
                patches.add(new QuadLayoutPatch(patches.size(),
                        deepCopy(p.sides), p.corners.clone()));
                statAllSingleArcEmit++;
                continue;
            }
            // Find first multi-arc side. If any side is length 0 (malformed
            // input), skip the patch.
            if (hasEmptySide(p.sides)) {
                skipped.add(p.sourceTPatchId);
                statBailEmptySide++;
                continue;
            }
            int s = firstMultiArcSide(p.sides);
            if (s < 0) {
                // allSingleArc() was false but no side has >1 arc — shouldn't
                // happen given the empty-side guard above, but be defensive.
                skipped.add(p.sourceTPatchId);
                statBailNoMultiArcSide++;
                continue;
            }
            int opp = (s + 2) % NUM_4;
            int sideLen = sumQ(p.sides[s], layoutArcQ);
            int oppLen = sumQ(p.sides[opp], layoutArcQ);
            if (sideLen != oppLen) {
                // Inconsistent quantization for this patch — bail.
                skipped.add(p.sourceTPatchId);
                statBailEqTwoViolated++;
                continue;
            }

            // T-junction = node at end of first arc on side s walking from corner s.
            int firstArcOnS = p.sides[s][0];
            int tJunctionNodeId = endNodeOf(layoutArcs.get(firstArcOnS), p.corners[s]);
            int targetDistFromS = layoutArcQ.get(firstArcOnS);

            // Walk side opp REVERSED from corner sPrev=(s+3)%4 (= corner at
            // the END of the natural-walk along opp). The T-junction's
            // mirror distance from corner sPrev equals targetDistFromS.
            //
            // matchIdx is the FORWARD index of the LAST arc walked before
            // reaching the matching node M (i.e. M is the END of arc
            // opp[matchIdx] when walking side opp FORWARD from corner opp).
            // Valid range: [0, oppCount-2]. matchIdx == -1 means no match.
            int matchIdx = NUM__2;  // sentinel: no exact match found
            int splitForwardIdx = -1;  // arc to split for PATCH-80 mid-arc case
            int splitSubDist = 0;       // q-portion to assign to the LEFT half
            int cum = 0;
            int oppCount = p.sides[opp].length;
            for (int k = 0; k < oppCount; k++) {
                int forwardIdx = oppCount - 1 - k;
                int prevCum = cum;
                cum += layoutArcQ.get(p.sides[opp][forwardIdx]);
                if (cum == targetDistFromS) {
                    matchIdx = forwardIdx - 1;
                    break;
                }
                if (cum > targetDistFromS) {
                    // Mid-arc: matching point falls inside p.sides[opp][forwardIdx].
                    // The reversed-walk has consumed (cum - prevCum) of the arc,
                    // overshooting by (cum - targetDistFromS). The split point
                    // measured from the arc's FORWARD-walk start is
                    // q[arc] - (cum - target) = target - prevCum.
                    splitForwardIdx = forwardIdx;
                    splitSubDist = targetDistFromS - prevCum;
                    break;
                }
            }

            if (matchIdx < -1 && splitForwardIdx < 0) {
                // No exact match and no candidate to split — degenerate.
                skipped.add(p.sourceTPatchId);
                statBailNoMatchNoSplit++;
                continue;
            }

            // PATCH-80: arc-splitting case. Cut p.sides[opp][splitForwardIdx]
            // into two DERIVED LayoutArcs at parametric distance splitSubDist
            // from the arc's FORWARD-walk start.
            if (splitForwardIdx >= 0) {
                int splitArcId = p.sides[opp][splitForwardIdx];
                LayoutArc orig = layoutArcs.get(splitArcId);
                int origQ = layoutArcQ.get(splitArcId);
                int leftQ = splitSubDist;
                int rightQ = origQ - leftQ;
                if (leftQ <= 0 || rightQ <= 0) {
                    // Degenerate split — bail.
                    skipped.add(p.sourceTPatchId);
                    statBailDegenerateSplit++;
                    continue;
                }
                statTJunctionArcSplit++;
                // FORWARD-walk start node of this arc on side opp:
                int forwardWalkStart = (splitForwardIdx == 0)
                        ? p.corners[opp]
                        : walkSide(layoutArcs, p.sides[opp], p.corners[opp],
                                splitForwardIdx - 1);
                int forwardWalkEnd = endNodeOf(orig, forwardWalkStart);
                int newNodeId = nextSyntheticNodeId[0]++;

                // The underlying TArc — DERIVED arcs reference it via
                // underlyingTArcId. For an INHERITED parent, that's just
                // orig.underlyingTArcId(); for a DERIVED parent we already
                // had a ratio, so we'd compose t-ranges. For PATCH-80 v1 we
                // only support splitting INHERITED arcs (DERIVED-of-DERIVED
                // would arise only from cascading splits within one solve,
                // rare; track as PATCH-81 if it bites).
                if (orig.variant() != LayoutArc.Variant.INHERITED) {
                    skipped.add(p.sourceTPatchId);
                    continue;
                }
                ixdar.geometry.mesh.quadlayout.tmesh.TArc tarc =
                        tmesh.arcs().get(orig.underlyingTArcId());

                // Determine fraction t and direction (FORWARD vs reversed in
                // tarc's own frame). FORWARD-walk of the side from corner opp
                // matches tarc's intrinsic direction iff
                // tarc.startNode == forwardWalkStart.
                boolean forward = tarc.startNode() == forwardWalkStart;
                float tFraction = (float) leftQ / (float) origQ;

                LayoutArc leftArc, rightArc;
                int leftId = layoutArcs.size();
                if (forward) {
                    leftArc = LayoutArc.derived(leftId, tarc,
                            forwardWalkStart, newNodeId, NUM_0, tFraction);
                } else {
                    leftArc = LayoutArc.derived(leftId, tarc,
                            forwardWalkStart, newNodeId, NUM_1, NUM_1 - tFraction);
                }
                layoutArcs.add(leftArc);
                layoutArcQ.add(leftQ);

                int rightId = layoutArcs.size();
                if (forward) {
                    rightArc = LayoutArc.derived(rightId, tarc,
                            newNodeId, forwardWalkEnd, tFraction, NUM_1);
                } else {
                    rightArc = LayoutArc.derived(rightId, tarc,
                            newNodeId, forwardWalkEnd, NUM_1 - tFraction, NUM_0);
                }
                layoutArcs.add(rightArc);
                layoutArcQ.add(rightQ);

                // Splice the new arcs into p.sides[opp] in place of the
                // original. After splicing, matchIdx = splitForwardIdx (M is
                // at end of leftArc, which now sits at forward index
                // splitForwardIdx).
                int[] newOppSide = new int[oppCount + 1];
                for (int i = 0; i < splitForwardIdx; i++) {
                    newOppSide[i] = p.sides[opp][i];
                }
                newOppSide[splitForwardIdx] = leftId;
                newOppSide[splitForwardIdx + 1] = rightId;
                for (int i = splitForwardIdx + 1; i < oppCount; i++) {
                    newOppSide[i + 1] = p.sides[opp][i];
                }
                p.sides[opp] = newOppSide;
                matchIdx = splitForwardIdx;
            }

            if (matchIdx < 0) {
                // Match at corner — degenerate (target = 0 or = sideLen).
                skipped.add(p.sourceTPatchId);
                continue;
            }

            // The matching node M = end of arc opp[matchIdx] walking FORWARD
            // from corner opp.
            int matchNodeId = walkSide(layoutArcs, p.sides[opp], p.corners[opp],
                    matchIdx);

            // Trace the INTERIOR LayoutArc T → M, if we have the mesh + TRS.
            // Without them (toy fixtures) we synthesize an empty polyline —
            // the layout structure is correct, only metrics/rendering loses.
            int direction = layoutArcs.get(firstArcOnS).direction();
            // Direction of the new INTERIOR arc is perpendicular to side s,
            // which == direction of side (s+1)%4 / (s+3)%4. Use side s+1's
            // first arc's direction as proxy.
            int midSide = (s + 1) % NUM_4;
            if (p.sides[midSide].length > 0) {
                direction = layoutArcs.get(p.sides[midSide][0]).direction();
            }

            List<SplitEdge> polyline = new ArrayList<>();
            if (mesh != null && trs != null && uCorner != null && vCorner != null) {
                polyline = traceInterior(tmesh, mesh, layoutArcs, p, s,
                        firstArcOnS, p.corners[s],
                        opp, matchIdx, p.corners[opp],
                        uCorner, vCorner, trs);
            }

            int newArcId = layoutArcs.size();
            LayoutArc interior = LayoutArc.interior(newArcId,
                    tJunctionNodeId, matchNodeId, direction, polyline);
            layoutArcs.add(interior);
            layoutArcQ.add(targetDistFromS);
            tJunctionsResolved++;
            // PATCH-92: track exact-match path separately from arc-split path.
            if (splitForwardIdx < 0) statTJunctionExactMatch++;

            // Split p into two children on the new interior arc.
            WorkPatch[] children = splitPatch(p, s, opp, matchIdx, newArcId,
                    tJunctionNodeId, matchNodeId);
            work.push(children[0]);
            work.push(children[1]);
        }

        // Convert layoutArcQ (ArrayList<Integer>) to int[].
        int[] qLayoutArcArr = new int[layoutArcQ.size()];
        for (int i = 0; i < qLayoutArcArr.length; i++) qLayoutArcArr[i] = layoutArcQ.get(i);

        QuadLayout layout = new QuadLayout(patches, triangles, layoutArcs,
                q.clone(), qLayoutArcArr, tJunctionsResolved);
        // Dedup skippedPatchIds: a single TPatch can spawn multiple
        // sub-patches, and only some may fail to resolve; we want one entry
        // per source TPatch.
        ArrayList<Integer> uniqueSkipped = new ArrayList<>(
                new java.util.LinkedHashSet<>(skipped));
        // conformingPatches counts both quads and triangles (PATCH-79).
        return new Result(layout, tmesh.patches().size(),
                patches.size() + triangles.size(),
                uniqueSkipped);
    }

    /**
     * Split patch {@code p} along the new interior arc connecting the
     * T-junction (end of first arc on side {@code s}) to the matching node
     * (end of arc {@code matchIdx} on side {@code opp} walked from corner
     * opp). See plan §B5 for the corner labelling.
     *
     * @param p             parent worklist entry
     * @param s             multi-arc side
     * @param opp           opposite side {@code (s + 2) % 4}
     * @param matchIdx      forward index of the last arc walked on side
     *                      {@code opp} before reaching M
     * @param interiorArcId id of the freshly created interior LayoutArc
     * @param tNodeId       T-junction node id
     * @param mNodeId       matching node id on the opposite side
     * @return two-element array {@code [halfA, halfB]} of child WorkPatches
     */
    private static WorkPatch[] splitPatch(WorkPatch p, int s, int opp,
                                           int matchIdx, int interiorArcId,
                                           int tNodeId, int mNodeId) {
        int sNext = (s + 1) % NUM_4;
        int sPrev = (s + NUM_3) % NUM_4;

        // Half A — contains corner `s`.
        // Walking CCW: corner s -> T (end of sides[s][0]) -> M (along interior)
        //              -> ... -> corner s_prev -> back to corner s.
        // Sides:
        //  side 0: [first arc on s]
        //  side 1: [interior]
        //  side 2: opp[matchIdx+1 .. end] reversed (M → corner sPrev)
        //  side 3: full sides[sPrev]
        int[][] sidesA = new int[NUM_4][];
        sidesA[0] = new int[]{ p.sides[s][0] };
        sidesA[1] = new int[]{ interiorArcId };
        // side 2: walk corner sPrev → M means walk opp's tail reversed.
        // But corners walk:  half A's side 2 starts at M, ends at corner sPrev.
        // Forward opp walks: corner opp (== corner sNext+1 == corner s+2) →
        // corner sNext (== corner s+1+1 wait this is wrong, opp = s+2 so
        // opp+1 = s+3 = sPrev). So sides[opp] walks corner s+2 → corner sPrev.
        // M is at boundary between opp[matchIdx] and opp[matchIdx+1].
        // Walking opp forward from corner s+2: arc 0 ends at first interior node,
        //   ..., arc matchIdx ends at M, arc matchIdx+1 starts at M.
        // Half A's side-2 = M → corner sPrev = forward opp arcs [matchIdx+1 .. last].
        // Half A's side-2 in CCW order needs to walk from M to corner sPrev,
        // which is the same direction as forward opp from M onward. Reverse?
        // Actually no — forward opp from M to corner sPrev is opp arcs
        // [matchIdx+1 .. last]. But Half A's side 2 walks from corner-of-side-2
        // (which is M, since corners[2] of half A == M) to corner-of-side-3
        // (which is corner sPrev). That's exactly forward opp [matchIdx+1..last].
        // No reversal needed.
        int oppLen = p.sides[opp].length;
        int[] sideA2 = new int[Math.max(0, oppLen - matchIdx - 1)];
        for (int i = 0; i < sideA2.length; i++) {
            sideA2[i] = p.sides[opp][matchIdx + 1 + i];
        }
        sidesA[2] = sideA2;
        sidesA[NUM_3] = p.sides[sPrev].clone();
        // Corners A walking CCW: [corner s, T, M, corner sPrev].
        int[] cornersA = new int[]{
                p.corners[s], tNodeId, mNodeId, p.corners[sPrev]
        };

        // Half B — contains corner `sNext` (= s+1).
        // Walking CCW: T → corner sNext → corner opp → M → back to T (along interior).
        // Sides:
        //  side 0: sides[s][1..end]
        //  side 1: full sides[sNext]
        //  side 2: opp[0..matchIdx]
        //  side 3: [interior]
        int[][] sidesB = new int[NUM_4][];
        int sCount = p.sides[s].length;
        int[] sideB0 = new int[sCount - 1];
        for (int i = 0; i < sideB0.length; i++) sideB0[i] = p.sides[s][1 + i];
        sidesB[0] = sideB0;
        sidesB[1] = p.sides[sNext].clone();
        int[] sideB2 = new int[matchIdx + 1];
        for (int i = 0; i < sideB2.length; i++) sideB2[i] = p.sides[opp][i];
        sidesB[2] = sideB2;
        sidesB[NUM_3] = new int[]{ interiorArcId };
        int[] cornersB = new int[]{ tNodeId, p.corners[sNext], p.corners[opp], mNodeId };

        return new WorkPatch[]{
                new WorkPatch(p.sourceTPatchId, sidesA, cornersA),
                new WorkPatch(p.sourceTPatchId, sidesB, cornersB),
        };
    }

    /**
     * Trace an INTERIOR layout arc from the T-junction (end of arc
     * {@code firstArcOnS} on side {@code s} walked from corner {@code cornerS})
     * to the matching node M (end of arc {@code matchIdx} on side {@code opp}
     * walked from corner {@code cornerOpp}) using {@link SplitArcTracer}.
     *
     * @param tmesh        T-mesh
     * @param mesh         underlying triangle mesh
     * @param layoutArcs   live LayoutArc registry
     * @param p            current worklist entry
     * @param s            multi-arc side
     * @param firstArcOnS  layoutArc id of the first arc on side {@code s}
     * @param cornerS      corner node at the start of side {@code s}
     * @param opp          opposite side
     * @param matchIdx     index of the matching arc on side {@code opp}
     *                     walking forward from {@code cornerOpp}
     * @param cornerOpp    corner node at the start of side {@code opp}
     * @param uCorner      per-corner u
     * @param vCorner      per-corner v
     * @param trs          transition matrix
     * @return polyline of {@link SplitEdge}s realizing the traced INTERIOR
     *         layout arc; empty if {@link SplitArcTracer} couldn't reach M
     */
    private static List<SplitEdge> traceInterior(TMesh tmesh, ArrayMesh mesh,
                                                  List<LayoutArc> layoutArcs,
                                                  WorkPatch p, int s,
                                                  int firstArcOnS, int cornerS,
                                                  int opp, int matchIdx,
                                                  int cornerOpp,
                                                  float[] uCorner, float[] vCorner,
                                                  TransitionMatrix trs) {
        // Build sideRSum from the underlying T-arcs of each side's LayoutArcs.
        double[] sideRSum = new double[NUM_4];
        for (int i = 0; i < NUM_4; i++) {
            for (int laId : p.sides[i]) {
                LayoutArc la = layoutArcs.get(laId);
                sideRSum[i] += la.parametricLength();
            }
        }

        // SplitElem for the T-junction at the end of arc firstArcOnS.
        TArc tArcFromS = tmesh.arcs().get(layoutArcs.get(firstArcOnS).underlyingTArcId());
        boolean fromCanonical = tArcFromS.startNode() == cornerS;
        int fromStepIdx, fromFace;
        float fromU, fromV;
        if (fromCanonical) {
            fromStepIdx = tArcFromS.stepUvs().size() - 1;
            float[] last = tArcFromS.stepUvs().get(fromStepIdx);
            fromU = last[2]; fromV = last[NUM_3];
            fromFace = tArcFromS.meshFaceCrossings().get(fromStepIdx)[0];
        } else {
            fromStepIdx = 0;
            float[] first = tArcFromS.stepUvs().get(0);
            fromU = first[0]; fromV = first[1];
            fromFace = tArcFromS.meshFaceCrossings().get(0)[0];
        }
        // 3D position not needed for tracer; pass null/0 vector.
        SplitElem from = new SplitElem(tArcFromS.id(), fromStepIdx, fromU, fromV,
                new org.joml.Vector3f(),
                tArcFromS.parametricLength());

        // SplitElem for the matching node M = end of opp[matchIdx] walked
        // forward from corner opp.
        int matchTArcId = layoutArcs.get(p.sides[opp][matchIdx]).underlyingTArcId();
        TArc matchTArc = tmesh.arcs().get(matchTArcId);
        // Determine M as the end-of-arc walking from corner opp. If matchIdx==0,
        // walk-start is cornerOpp; else walk-start is whatever node ended
        // opp[matchIdx-1]. Either way, M is the node opposite to walk-start
        // along matchTArc.
        int matchWalkStart = (matchIdx == 0)
                ? cornerOpp
                : walkSide(layoutArcs, p.sides[opp], cornerOpp, matchIdx - 1);
        boolean matchCanonical = matchTArc.startNode() == matchWalkStart;
        int matchStepIdx;
        float matchU, matchV;
        if (matchCanonical) {
            matchStepIdx = matchTArc.stepUvs().size() - 1;
            float[] last = matchTArc.stepUvs().get(matchStepIdx);
            matchU = last[2]; matchV = last[NUM_3];
        } else {
            matchStepIdx = 0;
            float[] first = matchTArc.stepUvs().get(0);
            matchU = first[0]; matchV = first[1];
        }
        // For SplitElem.distance — distance along side opp from corner opp.
        // For PATCH-77 the tracer uses (sideRSum[endSide] - to.distance) so
        // pass cumulative parametric length up to and including arc matchIdx.
        double matchDist = 0;
        for (int i = 0; i <= matchIdx; i++) {
            matchDist += layoutArcs.get(p.sides[opp][i]).parametricLength();
        }
        SplitElem to = new SplitElem(matchTArcId, matchStepIdx, matchU, matchV,
                new org.joml.Vector3f(),
                (float) matchDist);

        // SplitElem.distance for `from` — parametric distance from corner s
        // to the T-junction along side s = parametric length of firstArcOnS.
        double fromDist = layoutArcs.get(firstArcOnS).parametricLength();
        from = new SplitElem(from.arcId(), from.stepIndex(), from.u(), from.v(),
                from.position(), (float) fromDist);

        SplitArcTracer.SplitArc traced = SplitArcTracer.traceFromArc(
                tmesh, mesh, tArcFromS, cornerS,
                sideRSum, uCorner, vCorner, trs, s, from, to);
        return traced.edges();
    }

    /**
     * Walk side from {@code cornerStart}, returning the node at the end of
     *  arc index {@code arcIdx}.
     *
     * @param layoutArcs  live LayoutArc registry
     * @param sideArcs    forward-walk arc id sequence on one side of a patch
     * @param cornerStart corner node at the start of the walk
     * @param arcIdx      index of the last arc to traverse (inclusive)
     * @return node id at the end of arc {@code sideArcs[arcIdx]} after
     *         walking forward from {@code cornerStart}
     */
    private static int walkSide(List<LayoutArc> layoutArcs, int[] sideArcs,
                                 int cornerStart, int arcIdx) {
        int cur = cornerStart;
        for (int i = 0; i <= arcIdx; i++) {
            cur = endNodeOf(layoutArcs.get(sideArcs[i]), cur);
        }
        return cur;
    }

    /**
     * Given a LayoutArc and one of its endpoint TNode ids, return the other.
     *
     * @param la         LayoutArc
     * @param fromNodeId one of the arc's two endpoint TNode ids
     * @return the other endpoint of the arc
     */
    private static int endNodeOf(LayoutArc la, int fromNodeId) {
        if (la.startNodeId() == fromNodeId) return la.endNodeId();
        return la.startNodeId();
    }

    private static boolean allSingleArc(int[][] sides) {
        for (int s = 0; s < NUM_4; s++) {
            if (sides[s] == null || sides[s].length != 1) return false;
        }
        return true;
    }

    private static int firstMultiArcSide(int[][] sides) {
        for (int s = 0; s < NUM_4; s++) {
            if (sides[s].length > 1) return s;
        }
        return -1;
    }

    private static boolean hasEmptySide(int[][] sides) {
        for (int s = 0; s < NUM_4; s++) {
            if (sides[s] == null || sides[s].length == 0) return true;
        }
        return false;
    }

    private static int sumQ(int[] arcs, List<Integer> qLayoutArc) {
        int sum = 0;
        for (int a : arcs) sum += qLayoutArc.get(a);
        return sum;
    }

    private static int[][] deepCopy(int[][] sides) {
        int[][] out = new int[sides.length][];
        for (int i = 0; i < sides.length; i++) out[i] = sides[i].clone();
        return out;
    }

    /** Extended result: the layout plus diagnostics. */
    public record Result(QuadLayout layout,
                         int tMeshPatches,
                         int conformingPatches,
                         List<Integer> skippedPatchIds) {}       // PATCH-80 success path

    /**
     * Worklist entry: a (possibly partially-resolved) patch under.
     */
    private record WorkPatch(int sourceTPatchId,
                              int[][] sides,
                              int[] corners) {}
}
