package ixdar.geometry.mesh.data.analysis.skeleton;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonExtractor.SkeletonBranch;
import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonExtractor.SkeletonJoint;
import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonExtractor.SkeletonResult;
import ixdar.geometry.mesh.data.bundle.CurveGeometry;
import ixdar.geometry.mesh.data.representation.PackedPoints;

import ixdar.nodegraph.optimize.SkeletonSensitivityAnalyzer;
/**
 * Compares two TEASAR skeletons (generated vs reference) and produces
 * branch-level error metrics plus DSL parameter adjustment recommendations.
 *
 * <p>Branch matching uses greedy nearest-tip in a normalized coordinate frame
 * (both skeletons centered and scaled to unit bounding sphere).
 */
public final class MeshSkeletonComparator {
    public static final String TRUNK = "trunk";
    public static final String UNMATCHED_REF = "unmatched_ref";
    public static final String THUMB = "thumb";
    public static final float EPSILON = 1e-8f;
    public static final float Z_MATCH_EPSILON = 1e-6f;
    public static final int MIN_FINGERS_FOR_THUMB_DETECTION = 5;
    public static final float DEFAULT_ERROR_THRESHOLD = 0.05f;
    public static final float PERCENT_SCALE = 100;
    public static final float TRUNK_RADIUS_ERROR_THRESHOLD = 0.02f;
    public static final float FINGER_LENGTH_ERROR_THRESHOLD = 0.03f;
    public static final float DIRECTION_ERROR_THRESHOLD_DEG = 5.0f;
    public static final float FINGER_RADIUS_ERROR_THRESHOLD = 0.01f;
    public static final float MIN_VALID_RADIUS = 0.01f;
    public static final float MAX_DIRECTION_ERROR_DEG = 90.0f;
    public static final double JOINT_ERROR_DECAY_RATE = 5.0;
    public static final float LENGTH_WEIGHT = 0.4f;
    public static final float DIRECTION_WEIGHT = 0.3f;
    public static final float JOINT_WEIGHT = 0.3f;
    public static final float SCORE_SCALE = 100.0f;


    private static final String[] FINGER_LABELS = {"pinky", "ring", "middle", "index"};
    private static final String[][] FINGER_PARAMS = {
        {"pinky_1", "pinky_2", "pinky_3", "pk_mcp_curl", "pk_pip_curl", "pk_dip_curl"},
        {"ring_1",  "ring_2",  "ring_3",  "rg_mcp_curl", "rg_pip_curl", "rg_dip_curl"},
        {"middle_1","middle_2","middle_3", "md_mcp_curl", "md_pip_curl", "md_dip_curl"},
        {"index_1", "index_2", "index_3",  "ix_mcp_curl", "ix_pip_curl", "ix_dip_curl"},
    };
    private static final String[] THUMB_PARAMS = {"thumb_1", "thumb_2", "thumb_3", "th_mcp_curl", "th_pip_curl", "th_dip_curl"};


    /**
     * Match the generated skeleton against a reference, producing per-branch
     * error metrics, DSL parameter recommendations, and a 0-100 similarity score.
     *
     * @param gen generated TEASAR skeleton
     * @param ref reference TEASAR skeleton
     * @return summary including matches, recommendations, and scalar score
     */
    public static ComparisonResult compare(SkeletonResult gen, SkeletonResult ref) {
        NormalizedSkeleton nGen = normalize(gen);
        NormalizedSkeleton nRef = normalize(ref);

        List<SkeletonBranch> genBranches = nGen.branches;
        List<SkeletonBranch> refBranches = nRef.branches;

        List<SkeletonBranch> genFingers = new ArrayList<>();
        List<SkeletonBranch> refFingers = new ArrayList<>();
        SkeletonBranch genTrunk = null, refTrunk = null;

        for (SkeletonBranch b : genBranches) {
            if (b.parentBranch() < 0) { if (genTrunk == null) genTrunk = b; else genFingers.add(b); }
            else genFingers.add(b);
        }
        for (SkeletonBranch b : refBranches) {
            if (b.parentBranch() < 0) { if (refTrunk == null) refTrunk = b; else refFingers.add(b); }
            else refFingers.add(b);
        }

        List<BranchMatch> matches = new ArrayList<>();

        if (genTrunk != null && refTrunk != null) {
            matches.add(matchBranches(genTrunk, refTrunk, TRUNK));
        }

        boolean[] refUsed = new boolean[refFingers.size()];
        List<SkeletonBranch> sortedGenFingers = new ArrayList<>(genFingers);
        sortedGenFingers.sort(Comparator.comparing(b -> tipZ(b)));

        for (SkeletonBranch gb : sortedGenFingers) {
            Vector3f gTip = tipPosition(gb);
            int bestRef = -1;
            float bestDist = Float.MAX_VALUE;
            for (int ri = 0; ri < refFingers.size(); ri++) {
                if (refUsed[ri]) continue;
                float d = gTip.distance(tipPosition(refFingers.get(ri)));
                if (d < bestDist) { bestDist = d; bestRef = ri; }
            }
            if (bestRef >= 0) {
                refUsed[bestRef] = true;
                String label = labelFinger(gb, genFingers);
                matches.add(matchBranches(gb, refFingers.get(bestRef), label));
            }
        }

        for (int ri = 0; ri < refFingers.size(); ri++) {
            if (!refUsed[ri]) {
                SkeletonBranch rb = refFingers.get(ri);
                matches.add(new BranchMatch(-1, rb.id(), UNMATCHED_REF, 0, rb.length(), -rb.length(),
                        0, 0, avgRadius(rb), 0, avgRadius(rb), 0));
            }
        }

        List<ParameterRecommendation> recs = generateRecommendations(matches);

        float score = computeScore(matches);

        return new ComparisonResult(
                genBranches.size(), refBranches.size(), (int) matches.stream().filter(m -> m.genBranchId >= 0).count(),
                matches, recs, score
        );
    }

    /**
     * Detailed comparison that preserves per-joint 3D position deltas for each matched branch.
     * Used by {@link SkeletonSensitivityAnalyzer} for Jacobian computation.
     *
     * @param gen generated TEASAR skeleton
     * @param ref reference TEASAR skeleton
     * @return summary plus per-match resampled joint deltas
     */
    public static DetailedComparisonResult compareDetailed(SkeletonResult gen, SkeletonResult ref) {
        NormalizedSkeleton nGen = normalize(gen);
        NormalizedSkeleton nRef = normalize(ref);

        List<SkeletonBranch> genBranches = nGen.branches;
        List<SkeletonBranch> refBranches = nRef.branches;

        List<SkeletonBranch> genFingers = new ArrayList<>();
        List<SkeletonBranch> refFingers = new ArrayList<>();
        SkeletonBranch genTrunk = null, refTrunk = null;

        for (SkeletonBranch b : genBranches) {
            if (b.parentBranch() < 0) { if (genTrunk == null) genTrunk = b; else genFingers.add(b); }
            else genFingers.add(b);
        }
        for (SkeletonBranch b : refBranches) {
            if (b.parentBranch() < 0) { if (refTrunk == null) refTrunk = b; else refFingers.add(b); }
            else refFingers.add(b);
        }

        List<BranchMatch> matches = new ArrayList<>();
        List<DetailedBranchMatch> detailedMatches = new ArrayList<>();

        if (genTrunk != null && refTrunk != null) {
            BranchMatch bm = matchBranches(genTrunk, refTrunk, TRUNK);
            matches.add(bm);
            List<JointDelta> deltas = computeJointDeltas(genTrunk.joints(), refTrunk.joints());
            detailedMatches.add(new DetailedBranchMatch(bm, deltas, deltas.size()));
        }

        boolean[] refUsed = new boolean[refFingers.size()];
        List<SkeletonBranch> sortedGenFingers = new ArrayList<>(genFingers);
        sortedGenFingers.sort(Comparator.comparing(b -> tipZ(b)));

        for (SkeletonBranch gb : sortedGenFingers) {
            Vector3f gTip = tipPosition(gb);
            int bestRef = -1;
            float bestDist = Float.MAX_VALUE;
            for (int ri = 0; ri < refFingers.size(); ri++) {
                if (refUsed[ri]) continue;
                float d = gTip.distance(tipPosition(refFingers.get(ri)));
                if (d < bestDist) { bestDist = d; bestRef = ri; }
            }
            if (bestRef >= 0) {
                refUsed[bestRef] = true;
                String label = labelFinger(gb, genFingers);
                SkeletonBranch rb = refFingers.get(bestRef);
                BranchMatch bm = matchBranches(gb, rb, label);
                matches.add(bm);
                List<JointDelta> deltas = computeJointDeltas(gb.joints(), rb.joints());
                detailedMatches.add(new DetailedBranchMatch(bm, deltas, deltas.size()));
            }
        }

        for (int ri = 0; ri < refFingers.size(); ri++) {
            if (!refUsed[ri]) {
                SkeletonBranch rb = refFingers.get(ri);
                BranchMatch bm = new BranchMatch(-1, rb.id(), UNMATCHED_REF, 0, rb.length(), -rb.length(),
                        0, 0, avgRadius(rb), 0, avgRadius(rb), 0);
                matches.add(bm);
                detailedMatches.add(new DetailedBranchMatch(bm, List.of(), 0));
            }
        }

        List<ParameterRecommendation> recs = generateRecommendations(matches);
        float score = computeScore(matches);

        ComparisonResult summary = new ComparisonResult(
                genBranches.size(), refBranches.size(),
                (int) matches.stream().filter(m -> m.genBranchId >= 0).count(),
                matches, recs, score);

        return new DetailedComparisonResult(summary, detailedMatches);
    }


    private static BranchMatch matchBranches(SkeletonBranch gen, SkeletonBranch ref, String label) {
        float lengthError = gen.length() - ref.length();
        // abs ignores a direction flip
        float angle = (float) Math.toDegrees(gen.direction().angle(ref.direction()));
        float dirError = Math.min(angle, 180f - angle);

        float genBaseR = gen.joints().isEmpty() ? 0 : gen.joints().get(gen.joints().size() - 1).radius();
        float refBaseR = ref.joints().isEmpty() ? 0 : ref.joints().get(ref.joints().size() - 1).radius();
        float genTipR = gen.joints().isEmpty() ? 0 : gen.joints().get(0).radius();
        float refTipR = ref.joints().isEmpty() ? 0 : ref.joints().get(0).radius();

        float jointPosErr = averageJointPositionError(gen.joints(), ref.joints());

        return new BranchMatch(gen.id(), ref.id(), label,
                gen.length(), ref.length(), lengthError,
                dirError, genBaseR, refBaseR, genTipR, refTipR, jointPosErr);
    }

    private static float averageJointPositionError(List<SkeletonJoint> genJoints, List<SkeletonJoint> refJoints) {
        List<JointDelta> deltas = computeJointDeltas(genJoints, refJoints);
        if (deltas.isEmpty()) return 0;
        float totalErr = 0;
        for (JointDelta d : deltas) totalErr += d.distance;
        return totalErr / deltas.size();
    }

    /**
     * Compute per-joint 3D position deltas between resampled generated and reference joint lists.
     *
     * @param genJoints joints of the generated branch
     * @param refJoints joints of the reference branch
     * @return one delta per resampled index (empty if either input is empty)
     */
    static List<JointDelta> computeJointDeltas(List<SkeletonJoint> genJoints, List<SkeletonJoint> refJoints) {
        if (genJoints.isEmpty() || refJoints.isEmpty()) return List.of();

        int n = Math.max(genJoints.size(), refJoints.size());
        Vector3f[] genSampled = resampleJoints(genJoints, n);
        Vector3f[] refSampled = resampleJoints(refJoints, n);

        List<JointDelta> deltas = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Vector3f g = genSampled[i];
            Vector3f r = refSampled[i];
            deltas.add(new JointDelta(i, g, r, new Vector3f(r).sub(g), g.distance(r)));
        }
        return deltas;
    }

    private static Vector3f[] resampleJoints(List<SkeletonJoint> joints, int n) {
        PackedPoints packed = new PackedPoints(joints.size());
        for (int joint = 0; joint < joints.size(); joint++) {
            packed.set(joint, joints.get(joint).position());
        }
        PackedPoints sampled = CurveGeometry.singlePolyline(packed, false).resampled(n).positions;
        Vector3f[] result = new Vector3f[n];
        for (int sample = 0; sample < n; sample++) {
            result[sample] = sampled.at(sample);
        }
        return result;
    }


    /**
     * Label a finger branch by its Z-position among all finger branches.
     *
     * @param branch branch to label
     * @param allFingers full list of finger branches used to derive Z-ranking and thumb heuristic
     * @return one of {@link #FINGER_LABELS}, {@value #THUMB}, or {@code finger_<rank>} fallback
     */
    private static String labelFinger(SkeletonBranch branch, List<SkeletonBranch> allFingers) {
        float z = tipZ(branch);
        List<Float> allZ = new ArrayList<>();
        for (SkeletonBranch b : allFingers) allZ.add(tipZ(b));
        allZ.sort(Float::compare);

        int rank = 0;
        for (int i = 0; i < allZ.size(); i++) {
            if (Math.abs(allZ.get(i) - z) < Z_MATCH_EPSILON) { rank = i; break; }
        }

        boolean likelyThumb = false;
        if (allFingers.size() >= MIN_FINGERS_FOR_THUMB_DETECTION) {
            float maxXDir = 0;
            SkeletonBranch thumbCandidate = null;
            for (SkeletonBranch b : allFingers) {
                float bx = Math.abs(b.direction().x);
                if (bx > maxXDir) { maxXDir = bx; thumbCandidate = b; }
            }
            if (thumbCandidate == branch) likelyThumb = true;
        }

        if (likelyThumb) return THUMB;
        if (rank < FINGER_LABELS.length) return FINGER_LABELS[rank];
        return "finger_" + rank;
    }


    private static List<ParameterRecommendation> generateRecommendations(List<BranchMatch> matches) {
        List<ParameterRecommendation> recs = new ArrayList<>();

        for (BranchMatch m : matches) {
            if (m.genBranchId < 0) continue; // unmatched ref branch

            if (m.label.equals(TRUNK)) {
                if (Math.abs(m.lengthError) > DEFAULT_ERROR_THRESHOLD) {
                    float ratio = m.refLength > 0 ? m.genLength / m.refLength : 1;
                    float scaleFactor = m.refLength > 0 ? m.refLength / m.genLength : 1;
                    recs.add(new ParameterRecommendation("forearm_1..4",
                            m.genLength, m.refLength,
                            String.format("trunk length %.2f vs %.2f (%.0f%%) — scale forearm params by %.2fx",
                                    m.genLength, m.refLength, (ratio - 1) * PERCENT_SCALE, scaleFactor)));
                }
                if (Math.abs(m.genBaseRadius - m.refBaseRadius) > TRUNK_RADIUS_ERROR_THRESHOLD) {
                    float radiusRatio = m.refBaseRadius > 0 ? m.refBaseRadius / m.genBaseRadius : 1;
                    recs.add(new ParameterRecommendation("palm_x, palm_z",
                            m.genBaseRadius, m.refBaseRadius,
                            String.format("trunk base radius %.3f vs %.3f — scale palm by %.2fx",
                                    m.genBaseRadius, m.refBaseRadius, radiusRatio)));
                }
                continue;
            }

            String[] params = paramsForLabel(m.label);
            if (params == null) continue;

            if (Math.abs(m.lengthError) > FINGER_LENGTH_ERROR_THRESHOLD) {
                float ratio = m.refLength > 0 ? m.genLength / m.refLength : 1;
                recs.add(new ParameterRecommendation(
                        params[0] + ", " + params[1] + ", " + params[2],
                        m.genLength, m.refLength,
                        String.format("%s length %.2f vs %.2f (%.0f%%) — distribute across segments",
                                m.label, m.genLength, m.refLength, (ratio - 1) * PERCENT_SCALE)));
            }

            if (m.directionErrorDeg > DIRECTION_ERROR_THRESHOLD_DEG) {
                recs.add(new ParameterRecommendation(
                        params[3] + ", " + params[4] + ", " + params[5],
                        m.directionErrorDeg, 0,
                        String.format("%s direction off by %.1f° — adjust curl params",
                                m.label, m.directionErrorDeg)));
            }

            if (Math.abs(m.genBaseRadius - m.refBaseRadius) > FINGER_RADIUS_ERROR_THRESHOLD) {
                float radiusRatio = m.refBaseRadius > 0 ? m.refBaseRadius / m.genBaseRadius : 1;
                recs.add(new ParameterRecommendation("finger_rx, finger_ry",
                        m.genBaseRadius, m.refBaseRadius,
                        String.format("%s base radius %.3f vs %.3f — scale cross-section by %.2fx",
                                m.label, m.genBaseRadius, m.refBaseRadius, radiusRatio)));
            }

            if (m.genBaseRadius > MIN_VALID_RADIUS && m.refBaseRadius > MIN_VALID_RADIUS) {
                float genTaper = m.genTipRadius / m.genBaseRadius;
                float refTaper = m.refTipRadius / m.refBaseRadius;
                if (Math.abs(genTaper - refTaper) > DEFAULT_ERROR_THRESHOLD) {
                    recs.add(new ParameterRecommendation("finger_taper, finger_tip_taper",
                            genTaper, refTaper,
                            String.format("%s taper ratio %.2f vs %.2f",
                                    m.label, genTaper, refTaper)));
                }
            }
        }

        return recs;
    }

    private static String[] paramsForLabel(String label) {
        for (int i = 0; i < FINGER_LABELS.length; i++) {
            if (FINGER_LABELS[i].equals(label)) return FINGER_PARAMS[i];
        }
        if (THUMB.equals(label)) return THUMB_PARAMS;
        return null;
    }


    /**
     * Weighted skeleton similarity score (0-100).
     *
     * @param matches all branch matches (including unmatched reference branches)
     * @return length-weighted blend of length ratio (0.4), direction (0.3), and joint position (0.3)
     */
    private static float computeScore(List<BranchMatch> matches) {
        if (matches.isEmpty()) return 0;

        float totalWeight = 0;
        float weightedScore = 0;

        for (BranchMatch m : matches) {
            if (m.genBranchId < 0) {
                totalWeight += m.refLength;
                continue;
            }

            float weight = Math.max(m.genLength, m.refLength);
            totalWeight += weight;

            float lengthRatio = m.refLength > 0 ? Math.min(m.genLength, m.refLength) / Math.max(m.genLength, m.refLength) : 0;

            float dirScore = Math.max(0, 1.0f - m.directionErrorDeg / MAX_DIRECTION_ERROR_DEG);

            float jointScore = (float) Math.exp(-m.jointPositionError * JOINT_ERROR_DECAY_RATE);

            float branchScore = LENGTH_WEIGHT * lengthRatio + DIRECTION_WEIGHT * dirScore + JOINT_WEIGHT * jointScore;
            weightedScore += branchScore * weight;
        }

        return totalWeight > 0 ? SCORE_SCALE * weightedScore / totalWeight : 0;
    }

    /**
     * Center and scale a skeleton so all joint positions are in [-0.5, 0.5]^3.
     *
     * @param skel input skeleton
     * @return normalized branches with recomputed direction and length, plus the center/scale used
     */
    private static NormalizedSkeleton normalize(SkeletonResult skel) {
        Vector3f min = new Vector3f(Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE);
        for (SkeletonBranch b : skel.branches()) {
            for (SkeletonJoint j : b.joints()) {
                min.min(j.position());
                max.max(j.position());
            }
        }
        if (min.x == Float.MAX_VALUE) return new NormalizedSkeleton(skel.branches(), new Vector3f(), 1);

        Vector3f center = new Vector3f(min).add(max).mul(0.5f);
        Vector3f extent = new Vector3f(max).sub(min);
        float maxExt = Math.max(extent.x, Math.max(extent.y, extent.z));
        float scale = maxExt > EPSILON ? maxExt : 1.0f;

        List<SkeletonBranch> normalized = new ArrayList<>();
        for (SkeletonBranch b : skel.branches()) {
            List<SkeletonJoint> normJoints = new ArrayList<>();
            for (SkeletonJoint j : b.joints()) {
                normJoints.add(new SkeletonJoint(new Vector3f(j.position()).sub(center).div(scale),
                        j.radius() / scale));
            }
            Vector3f dir = new Vector3f();
            float length = 0;
            if (normJoints.size() >= 2) {
                Vector3f chord = new Vector3f(normJoints.get(normJoints.size() - 1).position())
                        .sub(normJoints.get(0).position());
                float mag = chord.length();
                if (mag > EPSILON) dir.set(chord).div(mag);
                for (int j = 1; j < normJoints.size(); j++) {
                    length += normJoints.get(j - 1).position().distance(normJoints.get(j).position());
                }
            }
            normalized.add(new SkeletonBranch(b.id(), b.label(), b.parentBranch(), normJoints, dir, length));
        }

        return new NormalizedSkeleton(normalized, center, scale);
    }


    private static float tipZ(SkeletonBranch b) {
        if (b.joints().isEmpty()) return 0;
        return b.joints().get(0).position().z; // first joint = tip (TEASAR traces tip→root)
    }

    private static Vector3f tipPosition(SkeletonBranch b) {
        if (b.joints().isEmpty()) return new Vector3f();
        return b.joints().get(0).position();
    }

    private static float avgRadius(SkeletonBranch b) {
        if (b.joints().isEmpty()) return 0;
        float sum = 0;
        for (SkeletonJoint j : b.joints()) sum += j.radius();
        return sum / b.joints().size();
    }


    public record BranchMatch(
            int genBranchId, int refBranchId,
            String label,
            float genLength, float refLength, float lengthError,
            float directionErrorDeg,
            float genBaseRadius, float refBaseRadius,
            float genTipRadius, float refTipRadius,
            float jointPositionError
    ) {}

    public record ParameterRecommendation(String paramName, float currentImplied, float targetImplied, String reason) {}

    public record ComparisonResult(
            int genBranchCount, int refBranchCount, int matchedCount,
            List<BranchMatch> matches,
            List<ParameterRecommendation> recommendations,
            float skeletonScore
    ) {}


    /** Per-joint 3D position delta between generated and reference skeleton. */
    public record JointDelta(int jointIndex, Vector3f genPosition, Vector3f refPosition, Vector3f delta,
            float distance) {}

    /** Branch match with full per-joint delta vectors (not just the average). */
    public record DetailedBranchMatch(
            BranchMatch summary,
            List<JointDelta> jointDeltas,
            int resampledJointCount
    ) {}

    /** Full comparison result including per-joint position deltas for sensitivity analysis. */
    public record DetailedComparisonResult(
            ComparisonResult summary,
            List<DetailedBranchMatch> detailedMatches
    ) {}


    private record NormalizedSkeleton(List<SkeletonBranch> branches, Vector3f center, float scale) {}
}
