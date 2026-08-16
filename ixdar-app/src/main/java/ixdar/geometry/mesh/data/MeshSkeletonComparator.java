package ixdar.geometry.mesh.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import ixdar.geometry.mesh.data.MeshSkeletonExtractor.SkeletonBranch;
import ixdar.geometry.mesh.data.MeshSkeletonExtractor.SkeletonJoint;
import ixdar.geometry.mesh.data.MeshSkeletonExtractor.SkeletonResult;

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
    public static final String STR = ", ";
    public static final float NUM_1e_8 = 1e-8f;
    public static final float NUM_1e_6 = 1e-6f;
    public static final int NUM_5 = 5;
    public static final float NUM_0_05 = 0.05f;
    public static final int NUM_100 = 100;
    public static final float NUM_0_02 = 0.02f;
    public static final float NUM_0_03 = 0.03f;
    public static final float NUM_5_0 = 5.0f;
    public static final int NUM_3 = 3;
    public static final int NUM_4 = 4;
    public static final float NUM_0_01 = 0.01f;
    public static final float NUM_90_0 = 90.0f;
    public static final double NUM_5_0_2 = 5.0;
    public static final float NUM_0_4 = 0.4f;
    public static final float NUM_0_3 = 0.3f;
    public static final float NUM_100_0 = 100.0f;
    public static final float NUM_0_5 = 0.5f;

    // ─── Finger identity ───

    /** Known hand finger labels ordered by expected Z position (negative to positive). */
    private static final String[] FINGER_LABELS = {"pinky", "ring", "middle", "index"};
    private static final String[][] FINGER_PARAMS = {
        {"pinky_1", "pinky_2", "pinky_3", "pk_mcp_curl", "pk_pip_curl", "pk_dip_curl"},
        {"ring_1",  "ring_2",  "ring_3",  "rg_mcp_curl", "rg_pip_curl", "rg_dip_curl"},
        {"middle_1","middle_2","middle_3", "md_mcp_curl", "md_pip_curl", "md_dip_curl"},
        {"index_1", "index_2", "index_3",  "ix_mcp_curl", "ix_pip_curl", "ix_dip_curl"},
    };
    private static final String[] THUMB_PARAMS = {"thumb_1", "thumb_2", "thumb_3", "th_mcp_curl", "th_pip_curl", "th_dip_curl"};

    // ─── Public entry point ───

    /**
     * Match the generated skeleton against a reference, producing per-branch
     * error metrics, DSL parameter recommendations, and a 0-100 similarity score.
     *
     * @param gen generated TEASAR skeleton
     * @param ref reference TEASAR skeleton
     * @return summary including matches, recommendations, and scalar score
     */
    public static ComparisonResult compare(SkeletonResult gen, SkeletonResult ref) {
        // Normalize both skeletons to the same coordinate frame
        NormalizedSkeleton nGen = normalize(gen);
        NormalizedSkeleton nRef = normalize(ref);

        List<SkeletonBranch> genBranches = nGen.branches;
        List<SkeletonBranch> refBranches = nRef.branches;

        // Identify trunk (branch 0 — always the first, longest path from root)
        // and non-trunk (finger) branches in each skeleton
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

        // Match trunk branches
        if (genTrunk != null && refTrunk != null) {
            matches.add(matchBranches(genTrunk, refTrunk, TRUNK));
        }

        // Match finger branches by greedy nearest tip position
        boolean[] refUsed = new boolean[refFingers.size()];
        // Sort gen fingers by Z of their tip (first joint, since TEASAR traces tip→root)
        List<SkeletonBranch> sortedGenFingers = new ArrayList<>(genFingers);
        sortedGenFingers.sort(Comparator.comparing(b -> tipZ(b)));

        for (SkeletonBranch gb : sortedGenFingers) {
            float[] gTip = tipPosition(gb);
            int bestRef = -1;
            float bestDist = Float.MAX_VALUE;
            for (int ri = 0; ri < refFingers.size(); ri++) {
                if (refUsed[ri]) continue;
                float d = dist3(gTip, tipPosition(refFingers.get(ri)));
                if (d < bestDist) { bestDist = d; bestRef = ri; }
            }
            if (bestRef >= 0) {
                refUsed[bestRef] = true;
                String label = labelFinger(gb, genFingers);
                matches.add(matchBranches(gb, refFingers.get(bestRef), label));
            }
        }

        // Unmatched reference branches
        for (int ri = 0; ri < refFingers.size(); ri++) {
            if (!refUsed[ri]) {
                SkeletonBranch rb = refFingers.get(ri);
                matches.add(new BranchMatch(-1, rb.id(), UNMATCHED_REF, 0, rb.length(), -rb.length(),
                        0, 0, avgRadius(rb), 0, avgRadius(rb), 0));
            }
        }

        // Generate parameter recommendations
        List<ParameterRecommendation> recs = generateRecommendations(matches);

        // Compute skeleton score (0-100)
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

        // Match trunk
        if (genTrunk != null && refTrunk != null) {
            BranchMatch bm = matchBranches(genTrunk, refTrunk, TRUNK);
            matches.add(bm);
            List<JointDelta> deltas = computeJointDeltas(genTrunk.joints(), refTrunk.joints());
            detailedMatches.add(new DetailedBranchMatch(bm, deltas, deltas.size()));
        }

        // Match fingers by greedy nearest tip
        boolean[] refUsed = new boolean[refFingers.size()];
        List<SkeletonBranch> sortedGenFingers = new ArrayList<>(genFingers);
        sortedGenFingers.sort(Comparator.comparing(b -> tipZ(b)));

        for (SkeletonBranch gb : sortedGenFingers) {
            float[] gTip = tipPosition(gb);
            int bestRef = -1;
            float bestDist = Float.MAX_VALUE;
            for (int ri = 0; ri < refFingers.size(); ri++) {
                if (refUsed[ri]) continue;
                float d = dist3(gTip, tipPosition(refFingers.get(ri)));
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

        // Unmatched reference branches
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

    // ─── Branch matching ───

    private static BranchMatch matchBranches(SkeletonBranch gen, SkeletonBranch ref, String label) {
        float lengthError = gen.length() - ref.length();
        float dirError = angleBetweenDeg(gen.direction(), ref.direction());

        float genBaseR = gen.joints().isEmpty() ? 0 : gen.joints().get(gen.joints().size() - 1).radius();
        float refBaseR = ref.joints().isEmpty() ? 0 : ref.joints().get(ref.joints().size() - 1).radius();
        float genTipR = gen.joints().isEmpty() ? 0 : gen.joints().get(0).radius();
        float refTipR = ref.joints().isEmpty() ? 0 : ref.joints().get(0).radius();

        // Joint position error: average distance between corresponding joints
        // Resample to same number of points if needed
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
        float[][] genSampled = resampleJoints(genJoints, n);
        float[][] refSampled = resampleJoints(refJoints, n);

        List<JointDelta> deltas = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            float[] g = genSampled[i];
            float[] r = refSampled[i];
            float[] delta = {r[0] - g[0], r[1] - g[1], r[2] - g[2]};
            deltas.add(new JointDelta(i, g.clone(), r.clone(), delta, dist3(g, r)));
        }
        return deltas;
    }

    private static float[][] resampleJoints(List<SkeletonJoint> joints, int n) {
        if (joints.size() == 1) {
            float[][] result = new float[n][];
            Arrays.fill(result, joints.get(0).position());
            return result;
        }

        // Compute cumulative arc length
        float[] arcLen = new float[joints.size()];
        for (int i = 1; i < joints.size(); i++) {
            arcLen[i] = arcLen[i - 1] + dist3(joints.get(i - 1).position(), joints.get(i).position());
        }
        float totalLen = arcLen[joints.size() - 1];
        if (totalLen < NUM_1e_8) {
            float[][] result = new float[n][];
            Arrays.fill(result, joints.get(0).position());
            return result;
        }

        float[][] result = new float[n][];
        for (int i = 0; i < n; i++) {
            float targetLen = totalLen * i / (n - 1);
            // Find segment
            int seg = 0;
            for (int s = 1; s < joints.size(); s++) {
                if (arcLen[s] >= targetLen) { seg = s - 1; break; }
                if (s == joints.size() - 1) seg = s - 1;
            }
            float segLen = arcLen[seg + 1] - arcLen[seg];
            float t = segLen < NUM_1e_8 ? 0 : (targetLen - arcLen[seg]) / segLen;
            float[] a = joints.get(seg).position();
            float[] b = joints.get(seg + 1).position();
            result[i] = new float[]{
                    a[0] + t * (b[0] - a[0]),
                    a[1] + t * (b[1] - a[1]),
                    a[2] + t * (b[2] - a[2])
            };
        }
        return result;
    }

    // ─── Finger labeling ───

    /**
     * Label a finger branch by its Z-position among all finger branches.
     *
     * @param branch branch to label
     * @param allFingers full list of finger branches used to derive Z-ranking and thumb heuristic
     * @return one of {@link #FINGER_LABELS}, {@value #THUMB}, or {@code finger_<rank>} fallback
     */
    private static String labelFinger(SkeletonBranch branch, List<SkeletonBranch> allFingers) {
        float z = tipZ(branch);
        // Sort all fingers by tip Z
        List<Float> allZ = new ArrayList<>();
        for (SkeletonBranch b : allFingers) allZ.add(tipZ(b));
        allZ.sort(Float::compare);

        int rank = 0;
        for (int i = 0; i < allZ.size(); i++) {
            if (Math.abs(allZ.get(i) - z) < NUM_1e_6) { rank = i; break; }
        }

        // Thumb detection: the finger with the most divergent direction from the trunk
        // (typically extends along a different axis). Use X-component of direction as heuristic.
        boolean likelyThumb = false;
        if (allFingers.size() >= NUM_5) {
            // If this branch has the highest |X direction| among all fingers, it's probably the thumb
            float maxXDir = 0;
            SkeletonBranch thumbCandidate = null;
            for (SkeletonBranch b : allFingers) {
                float bx = Math.abs(b.direction()[0]);
                if (bx > maxXDir) { maxXDir = bx; thumbCandidate = b; }
            }
            if (thumbCandidate == branch) likelyThumb = true;
        }

        if (likelyThumb) return THUMB;
        // Remove thumb from ranking if identified
        if (rank < FINGER_LABELS.length) return FINGER_LABELS[rank];
        return "finger_" + rank;
    }

    // ─── Parameter recommendations ───

    private static List<ParameterRecommendation> generateRecommendations(List<BranchMatch> matches) {
        List<ParameterRecommendation> recs = new ArrayList<>();

        for (BranchMatch m : matches) {
            if (m.genBranchId < 0) continue; // unmatched ref branch

            if (m.label.equals(TRUNK)) {
                // Trunk length → forearm params
                if (Math.abs(m.lengthError) > NUM_0_05) {
                    float ratio = m.refLength > 0 ? m.genLength / m.refLength : 1;
                    float scaleFactor = m.refLength > 0 ? m.refLength / m.genLength : 1;
                    recs.add(new ParameterRecommendation("forearm_1..4",
                            m.genLength, m.refLength,
                            String.format("trunk length %.2f vs %.2f (%.0f%%) — scale forearm params by %.2fx",
                                    m.genLength, m.refLength, (ratio - 1) * NUM_100, scaleFactor)));
                }
                // Trunk radius → palm dimensions
                if (Math.abs(m.genBaseRadius - m.refBaseRadius) > NUM_0_02) {
                    float radiusRatio = m.refBaseRadius > 0 ? m.refBaseRadius / m.genBaseRadius : 1;
                    recs.add(new ParameterRecommendation("palm_x, palm_z",
                            m.genBaseRadius, m.refBaseRadius,
                            String.format("trunk base radius %.3f vs %.3f — scale palm by %.2fx",
                                    m.genBaseRadius, m.refBaseRadius, radiusRatio)));
                }
                continue;
            }

            // Finger branches
            String[] params = paramsForLabel(m.label);
            if (params == null) continue;

            // Length error → segment length params
            if (Math.abs(m.lengthError) > NUM_0_03) {
                float ratio = m.refLength > 0 ? m.genLength / m.refLength : 1;
                recs.add(new ParameterRecommendation(
                        params[0] + STR + params[1] + STR + params[2],
                        m.genLength, m.refLength,
                        String.format("%s length %.2f vs %.2f (%.0f%%) — distribute across segments",
                                m.label, m.genLength, m.refLength, (ratio - 1) * NUM_100)));
            }

            // Direction error → curl params
            if (m.directionErrorDeg > NUM_5_0) {
                recs.add(new ParameterRecommendation(
                        params[NUM_3] + STR + params[NUM_4] + STR + params[NUM_5],
                        m.directionErrorDeg, 0,
                        String.format("%s direction off by %.1f° — adjust curl params",
                                m.label, m.directionErrorDeg)));
            }

            // Base radius error → finger_rx, finger_ry
            if (Math.abs(m.genBaseRadius - m.refBaseRadius) > NUM_0_01) {
                float radiusRatio = m.refBaseRadius > 0 ? m.refBaseRadius / m.genBaseRadius : 1;
                recs.add(new ParameterRecommendation("finger_rx, finger_ry",
                        m.genBaseRadius, m.refBaseRadius,
                        String.format("%s base radius %.3f vs %.3f — scale cross-section by %.2fx",
                                m.label, m.genBaseRadius, m.refBaseRadius, radiusRatio)));
            }

            // Taper error → finger_taper, finger_tip_taper
            if (m.genBaseRadius > NUM_0_01 && m.refBaseRadius > NUM_0_01) {
                float genTaper = m.genTipRadius / m.genBaseRadius;
                float refTaper = m.refTipRadius / m.refBaseRadius;
                if (Math.abs(genTaper - refTaper) > NUM_0_05) {
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

    // ─── Scoring ───

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
                // Unmatched reference branch — penalty
                totalWeight += m.refLength;
                // Score 0 for unmatched
                continue;
            }

            float weight = Math.max(m.genLength, m.refLength);
            totalWeight += weight;

            // Length component (0-1): 1 when perfect, decays with error
            float lengthRatio = m.refLength > 0 ? Math.min(m.genLength, m.refLength) / Math.max(m.genLength, m.refLength) : 0;

            // Direction component (0-1): 1 when aligned, 0 at 90+°
            float dirScore = Math.max(0, 1.0f - m.directionErrorDeg / NUM_90_0);

            // Joint position component (0-1): exponential decay
            float jointScore = (float) Math.exp(-m.jointPositionError * NUM_5_0_2);

            // Combined per-branch score
            float branchScore = NUM_0_4 * lengthRatio + NUM_0_3 * dirScore + NUM_0_3 * jointScore;
            weightedScore += branchScore * weight;
        }

        return totalWeight > 0 ? NUM_100_0 * weightedScore / totalWeight : 0;
    }

    /**
     * Center and scale a skeleton so all joint positions are in [-0.5, 0.5]^3.
     *
     * @param skel input skeleton
     * @return normalized branches with recomputed direction and length, plus the center/scale used
     */
    private static NormalizedSkeleton normalize(SkeletonResult skel) {
        // Compute bounding box of all joints
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (SkeletonBranch b : skel.branches()) {
            for (SkeletonJoint j : b.joints()) {
                for (int i = 0; i < NUM_3; i++) {
                    min[i] = Math.min(min[i], j.position()[i]);
                    max[i] = Math.max(max[i], j.position()[i]);
                }
            }
        }
        if (min[0] == Float.MAX_VALUE) return new NormalizedSkeleton(skel.branches(), new float[NUM_3], 1);

        float[] center = new float[NUM_3];
        float maxExt = 0;
        for (int i = 0; i < NUM_3; i++) {
            center[i] = (min[i] + max[i]) * NUM_0_5;
            maxExt = Math.max(maxExt, max[i] - min[i]);
        }
        float scale = maxExt > NUM_1e_8 ? maxExt : 1.0f;

        // Transform all branches
        List<SkeletonBranch> normalized = new ArrayList<>();
        for (SkeletonBranch b : skel.branches()) {
            List<SkeletonJoint> normJoints = new ArrayList<>();
            for (SkeletonJoint j : b.joints()) {
                float[] p = new float[NUM_3];
                for (int i = 0; i < NUM_3; i++) p[i] = (j.position()[i] - center[i]) / scale;
                normJoints.add(new SkeletonJoint(p, j.radius() / scale));
            }
            // Recompute direction and length
            float[] dir = new float[NUM_3];
            float length = 0;
            if (normJoints.size() >= 2) {
                float[] first = normJoints.get(0).position();
                float[] last = normJoints.get(normJoints.size() - 1).position();
                float dx = last[0] - first[0], dy = last[1] - first[1], dz = last[2] - first[2];
                float mag = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (mag > NUM_1e_8) { dir[0] = dx / mag; dir[1] = dy / mag; dir[2] = dz / mag; }
                for (int j = 1; j < normJoints.size(); j++) {
                    length += dist3(normJoints.get(j - 1).position(), normJoints.get(j).position());
                }
            }
            normalized.add(new SkeletonBranch(b.id(), b.label(), b.parentBranch(), normJoints, dir, length));
        }

        return new NormalizedSkeleton(normalized, center, scale);
    }

    // ─── Utilities ───

    private static float tipZ(SkeletonBranch b) {
        if (b.joints().isEmpty()) return 0;
        return b.joints().get(0).position()[2]; // first joint = tip (TEASAR traces tip→root)
    }

    private static float[] tipPosition(SkeletonBranch b) {
        if (b.joints().isEmpty()) return new float[NUM_3];
        return b.joints().get(0).position();
    }

    private static float avgRadius(SkeletonBranch b) {
        if (b.joints().isEmpty()) return 0;
        float sum = 0;
        for (SkeletonJoint j : b.joints()) sum += j.radius();
        return sum / b.joints().size();
    }

    private static float angleBetweenDeg(float[] a, float[] b) {
        float dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
        dot = Math.max(-1, Math.min(1, dot));
        return (float) Math.toDegrees(Math.acos(Math.abs(dot))); // abs to ignore direction flip
    }

    private static float dist3(float[] a, float[] b) {
        float dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ─── Output records ───

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

    // ─── Detailed output records (for sensitivity analysis) ───

    /** Per-joint 3D position delta between generated and reference skeleton. */
    public record JointDelta(int jointIndex, float[] genPosition, float[] refPosition, float[] delta, float distance) {}

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

    // ─── Normalization ───

    private record NormalizedSkeleton(List<SkeletonBranch> branches, float[] center, float scale) {}
}
