package ixdar.geometry.mesh.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ixdar.geometry.mesh.data.MeshSkeletonExtractor.SkeletonBranch;
import ixdar.geometry.mesh.data.MeshSkeletonExtractor.SkeletonJoint;
import ixdar.geometry.mesh.data.MeshSkeletonExtractor.SkeletonResult;

/**
 * Feature-edge + concavity patch decomposer.
 *
 * <ol>
 *   <li>Skeleton partition: each vertex assigned to the nearest skeleton
 *       branch (joint radius weighted).</li>
 *   <li>Per-edge dihedral map: shared basis for vertex curvature, feature
 *       detection, and face adjacency.</li>
 *   <li>Concavity carve-out: per-vertex Gaussian curvature via angle-defect
 *       locates bowls/sockets; connected components of strongly-negative
 *       curvature become dedicated patches before the region-growing pass
 *       runs. Stops eye sockets, nasal cavity, and foramen magnum from being
 *       absorbed by surrounding bone.</li>
 *   <li>Feature-edge-aware region growing: face-face adjacency where edges
 *       with dihedral above {@code T_FEATURE_RAD} break the connection. A
 *       BFS within each skeleton bucket then finds connected components —
 *       each one is a patch co-bounded by skeleton buckets and feature
 *       ridges. Replaces the prior k-means-on-[curvature,position] Step 2
 *       whose cuts landed at positional centroids rather than on actual
 *       geometric features.</li>
 *   <li>Small-component merge: anything under {@code MIN_PATCH_FACES} folds
 *       into its dominant non-small neighbour via the full (non-feature-cut)
 *       adjacency, so a tiny island can cross back through a ridge to merge
 *       with the region on the far side.</li>
 * </ol>
 */
public final class SemanticPatchDecomposer {
    public static final int NUM_3 = 3;
    public static final float NUM_0 = 0f;
    public static final float NUM_0_95 = 0.95f;
    public static final float NUM_1 = 1f;
    public static final float NUM_0_85 = 0.85f;
    public static final int NUM__2 = -2;
    public static final int NUM_6 = 6;
    public static final float NUM_0_10 = 0.10f;
    public static final float NUM_1e_4 = 1e-4f;
    public static final float NUM_1e_20 = 1e-20f;
    public static final int NUM_32 = 32;
    public static final long NUM_0xffffffff = 0xffffffffL;
    public static final float NUM_3_2 = 3f;
    public static final float NUM_1e_12 = 1e-12f;
    public static final float NUM_2 = 2f;
    public static final float NUM_0_5 = 0.5f;
    public static final double NUM_2500_0 = 2500.0;
    public static final float NUM_1e_6 = 1e-6f;
    public static final long NUM_0x53D5 = 0x53D5L;
    public static final double NUM_0_5_2 = 0.5;
    public static final int NUM_30 = 30;

    private static final float T_FEATURE_RAD = 0.20f;       // ~11°, top ~20% of edges on smooth meshes
    private static final float T_CONCAVE_RAD = -0.08f;      // angle defect below p5
    private static final float T_PRINCIPAL = 12.0f;         // |κ| above this (units of 1/mesh-extent)
                                                            // promotes the edge to a feature cut
                                                            // (PATCH-8 principal curvature lines).
                                                            // Tuned on the skull: p95 of |κ| is ≈5
                                                            // in 1/extent units, so this catches the
                                                            // top ~2-3% of edges, the real ridges
                                                            // and valleys rather than ambient noise.
    private static final int MIN_CONCAVITY_VERTS = 20;
    private static final int CONCAVITY_RING_EXPAND = 0;
    private static final int MIN_PATCH_FACES = 150;

    // PATCH-6 quality-score thresholds that decide whether a patch needs
    // further subdivision. A patch is "done" when all three agree: flat
    // enough (T_FLAT), close to 4 sides, and with a reasonable isoperimetric
    // ratio. Vertex-count is a hard safety ceiling that overrides the scorer
    // so pathological inputs can't produce a single 50k-vert patch.
    private static final float T_FLAT = 0.45f;              // stddev of mean dihedral per vertex;
                                                            // below = smooth patch, keep
    private static final float T_CORNER_RAD = 1.22f;        // 70°, boundary direction change
                                                            // that counts as a real corner
    private static final int IDEAL_SIDES = 4;               // Coons target
    private static final int MAX_SIDES_BEFORE_SPLIT = 8;
    private static final float T_ISO_RATIO = 0.03f;         // 4π·area / perimeter² below = elongated
    private static final int MAX_K_PER_SPLIT = 4;           // cap split branching so one
                                                            // patch can't balloon into 20
    private static final int HARD_MAX_PATCH_VERTS = 5000;   // absolute safety ceiling

    // PATCH-16 Coons reconstruction-error base case. A 4-sided patch is
    // "done" iff a cubic Coons fit to its boundary sits within this
    // fraction of the mesh bounding-sphere diameter at p95 per-vertex
    // distance. Raised to 0.025 after PATCH-19 recursive split landed:
    // at 0.008 the recursion over-fragments the skull into ~5600 sub-
    // patches because organic curvature can't be Coons-fit that tightly
    // without cutting down to ~20-face tiles. 0.025 ≈ 2.5% of mesh
    // diameter lets anatomical patches settle at 4-sided Coons-able
    // scale (~100-500 patches on a skull).
    private static final float T_COONS_ERROR_FRAC = 0.025f;
    // Coons UV grid resolution. 16×16 = 256 samples per patch; linear
    // nearest-neighbour scan per mesh vertex, fine for skull-scale
    // meshes (~25k verts). Bump if we see quantization artifacts.
    private static final int COONS_UV_SAMPLES = 16;

    // 20-colour distinguishable palette (hex without leading #).
    private static final String[] PALETTE = {
        "E84A5F", "FECEAB", "99B898", "2A363B", "FF847C",
        "6C5B7B", "355C7D", "F8B195", "C06C84", "F67280",
        "AD8CAE", "8FB1CB", "B6E0D4", "F3C77A", "D26F5C",
        "7B9ACC", "A4C3A2", "E7A3B6", "5D4E60", "C7B5D9"
    };

    private SemanticPatchDecomposer() {}

    /**
     * Overload that accepts any {@link MeshTopology} (e.g. a DSL-produced
     * {@link HalfEdgeMesh}) and converts to an {@link ArrayMesh} of flat
     * triangles before running the main decomposer. Fan-triangulates any
     * n-gon face.
     *
     * @param mesh source topology (any {@link MeshTopology}); converted to triangles when not already an {@link ArrayMesh}
     * @param resolution TEASAR voxel resolution forwarded to skeleton extraction
     * @return decomposition; empty when {@code mesh} is null
     */
    public static PatchDecomposition decompose(MeshTopology mesh, int resolution) {
        if (mesh == null) return new PatchDecomposition(0, List.of());
        if (mesh instanceof ArrayMesh am) return decompose(am, resolution);
        return decompose(toArrayMesh(mesh), resolution);
    }

    /**
     * Build an {@link ArrayMesh} from any {@link MeshTopology}. Uses only the
     * interface API — works on {@link HalfEdgeMesh}, {@link ArrayMesh},
     * and any future MeshTopology implementation.
     *
     * @param mesh source topology
     * @return triangle {@link ArrayMesh}; n-gons are fan-triangulated
     */
    public static ArrayMesh toArrayMesh(MeshTopology mesh) {
        int nv = mesh.vertexCount();
        int nf = mesh.faceCount();
        float[] positions = new float[nv * NUM_3];
        float[] normals = new float[nv * NUM_3];
        // Build id → compact-index mapping via vertexIdAt.
        int[] idToIndex = new int[nv > 0 ? maxVertexId(mesh) + 1 : 1];
        java.util.Arrays.fill(idToIndex, -1);
        org.joml.Vector3f v = new org.joml.Vector3f();
        for (int i = 0; i < nv; i++) {
            int id = mesh.vertexIdAt(i);
            idToIndex[id] = i;
            mesh.vertexPosition(id, v);
            positions[i * NUM_3] = v.x;
            positions[i * NUM_3 + 1] = v.y;
            positions[i * NUM_3 + 2] = v.z;
            mesh.vertexNormal(id, v);
            normals[i * NUM_3] = v.x;
            normals[i * NUM_3 + 1] = v.y;
            normals[i * NUM_3 + 2] = v.z;
        }
        // Walk faces, fan-triangulate.
        int triIndexCount = 0;
        for (int i = 0; i < nf; i++) {
            int fid = mesh.faceIdAt(i);
            int fv = mesh.faceVertexCount(fid);
            if (fv >= NUM_3) triIndexCount += (fv - 2) * NUM_3;
        }
        int[] faceIndices = new int[triIndexCount];
        int cursor = 0;
        for (int i = 0; i < nf; i++) {
            int fid = mesh.faceIdAt(i);
            int fv = mesh.faceVertexCount(fid);
            if (fv < NUM_3) continue;
            int v0 = idToIndex[mesh.faceVertexAt(fid, 0)];
            for (int k = 1; k + 1 < fv; k++) {
                faceIndices[cursor++] = v0;
                faceIndices[cursor++] = idToIndex[mesh.faceVertexAt(fid, k)];
                faceIndices[cursor++] = idToIndex[mesh.faceVertexAt(fid, k + 1)];
            }
        }
        return new ArrayMesh(positions, normals, faceIndices, NUM_3);
    }

    private static int maxVertexId(MeshTopology mesh) {
        int max = -1;
        int nv = mesh.vertexCount();
        for (int i = 0; i < nv; i++) {
            int id = mesh.vertexIdAt(i);
            if (id > max) max = id;
        }
        return max;
    }

    /**
     * Run the full skeleton + curvature pipeline and return only the patch result.
     *
     * @param mesh triangle mesh
     * @param resolution TEASAR voxel resolution
     * @return patch decomposition (per-vertex/face patch ids and palette colours)
     */
    public static PatchDecomposition decompose(ArrayMesh mesh, int resolution) {
        return decomposeWithDiagnostics(mesh, resolution).decomposition();
    }

    /**
     * Same as {@link #decompose(ArrayMesh, int)} but also exposes per-stage diagnostics
     * (feature edge sets, Morse-Smale complex, principal-curvature field, etc.) for the
     * overlay renderers.
     *
     * @param mesh triangle mesh
     * @param resolution TEASAR voxel resolution
     * @return decomposition plus diagnostic intermediates
     */
    public static DecompositionDiagnostics decomposeWithDiagnostics(ArrayMesh mesh, int resolution) {
        int nv = mesh.vertexCount();
        if (nv == 0) {
            PatchDecomposition empty = new PatchDecomposition(0, List.of());
            return new DecompositionDiagnostics(empty, new int[0],
                    java.util.Collections.emptySet(), java.util.Collections.emptySet(),
                    java.util.Collections.emptySet(), java.util.Collections.emptySet(),
                    java.util.Collections.emptySet(), java.util.Collections.emptySet(),
                    new float[0], NUM_0, null);
        }

        int[] faceIdx = mesh.copyFaceIndices();
        int faceCount = faceIdx.length / NUM_3;
        float[] positions = mesh.copyPositions();

        // Step 1: skeleton partition.
        SkeletonResult skel = MeshSkeletonExtractor.extract(mesh, resolution);
        int[] vertexBranchId = assignByNearestBranch(mesh, skel);

        // Step 2: per-edge dihedral map + principal-curvature features.
        EdgeDihedrals ed = computeEdgeDihedrals(mesh);

        // Per-vertex principal curvatures (Meyer 2003 cotangent Laplacian for
        // H, angle-defect over barycentric area for K, then κ₁,₂ = H ± √(H² - K)).
        // Edges where BOTH endpoints have strongly-positive κ₁ (convex ridge)
        // or strongly-negative κ₂ (concave valley) are promoted to feature cuts
        // alongside the high-dihedral set. This catches ridge/valley lines
        // whose individual edge dihedrals fall below T_FEATURE_RAD but whose
        // aggregate bending at the vertex is significant — notably tooth
        // crown ridges and inter-tooth valleys.
        float meshExtent = computeMeshExtent(positions);
        float[] barycentricArea = computeBarycentricAreas(mesh, positions, faceIdx);
        float[] meanH = computeMeanCurvature(mesh, positions, faceIdx, ed, barycentricArea);
        float[] gaussK = computeGaussianCurvature(mesh, positions, faceIdx, barycentricArea);
        float[][] principal = computePrincipalCurvatures(meanH, gaussK);
        float[] kappa1 = principal[0];
        float[] kappa2 = principal[1];
        // Adaptive principal-magnitude threshold: the hardcoded
        // T_PRINCIPAL=12/extent fired on ~34% of skull edges (25k of 75k),
        // flooding the face with spurious feature cuts that fragment region
        // growing into chaos. Use p95 of the per-vertex |κ₁| / −κ₂
        // distributions so we only promote genuine top-tail curvature.
        float ridgeT = percentileAbs(kappa1, NUM_0_95, /*positive=*/true);
        float valleyT = percentileAbs(kappa2, NUM_0_95, /*positive=*/false);
        // Safety floor so a mostly-flat mesh doesn't lose the signal.
        float floor = T_PRINCIPAL * (NUM_1 / meshExtent);
        ridgeT = Math.max(ridgeT, floor);
        valleyT = Math.max(valleyT, floor);
        java.util.Set<Long> principalFeatureEdges = principalCurvatureFeatureEdges(
                ed, kappa1, kappa2, ridgeT, valleyT);

        // PATCH-11: crest-line detection via Yoshizawa-style NMS along the
        // principal direction eigenvector. Produces continuous polylines
        // along real ridges (nose rim, eye-socket rim, tooth gaps) that the
        // magnitude-only principalFeatureEdges test was missing.
        PrincipalDirectionField pdf = PrincipalDirectionField.compute(mesh, ed);
        CrestLineDetector.CrestLines crest = CrestLineDetector.detect(mesh, ed, pdf);

        // PATCH-13: saddle-point separator detection. At inter-tooth saddles
        // where κ₁ > 0 and κ₂ < 0 with both magnitudes above p90, emit a
        // short cut across the valley along dirMax. Yoshizawa tracing walks
        // valleys in the dirMin direction (along the gum line) and so
        // cannot produce these perpendicular cuts on its own.
        SaddlePointDetector.SaddleSeparators saddle = SaddlePointDetector.detect(mesh, ed, pdf);

        // PATCH-11: adaptive dihedral threshold — use the p85 of the per-mesh
        // dihedral distribution instead of a hard-coded 0.20 rad. Safety-floored
        // at T_FEATURE_RAD so a near-flat mesh doesn't completely eliminate the
        // dihedral signal.
        float adaptiveFeatureRad = Math.max(T_FEATURE_RAD, percentileDihedral(ed, NUM_0_85));

        // Collect the dihedral feature-edge set for diagnostics.
        Set<Long> dihedralFeatureEdges = new HashSet<>();
        for (Map.Entry<Long, Float> e : ed.dihedralByEdge().entrySet()) {
            if (e.getValue() > adaptiveFeatureRad) dihedralFeatureEdges.add(e.getKey());
        }

        // PATCH-14 agreement filter. Empirically, dihedral-only and crest-only
        // edges are noise (visible as blue/red scatter in the STAGES overlay)
        // and fragment region-growing into spurious cuts (horizontal strips
        // through teeth). An edge is promoted into the cut-set iff:
        //   (a) it is a saddle separator — topology-directed, always trusted
        //       (PATCH-13 spec), OR
        //   (b) it appears in ≥2 of {dihedral, principal, crest} — agreement
        //       across independent detectors.
        // All four source sets remain in DecompositionDiagnostics so the
        // STAGES overlay keeps showing the raw per-source signal for
        // diagnosis; only the cut-set fed into region-growing is filtered.
        java.util.Set<Long> allFeatureEdges = new java.util.HashSet<>(saddle.separatorEdges);
        java.util.Set<Long> candidates = new java.util.HashSet<>(principalFeatureEdges);
        candidates.addAll(crest.crestEdges);
        candidates.addAll(dihedralFeatureEdges);
        for (long key : candidates) {
            int sources = 0;
            if (principalFeatureEdges.contains(key)) sources++;
            if (crest.crestEdges.contains(key)) sources++;
            if (dihedralFeatureEdges.contains(key)) sources++;
            if (sources >= 2) allFeatureEdges.add(key);
        }

        // Saddle separators must survive the small-patch merge pass 2 just
        // like crest edges — without this, adjacent teeth would be merged
        // back together across the short separator bars.
        java.util.Set<Long> highConfidenceEdges = new java.util.HashSet<>(crest.crestEdges);
        highConfidenceEdges.addAll(saddle.separatorEdges);

        // Face adjacency (full + feature-cut variants). Pass +infinity as the
        // dihedral threshold because PATCH-14 already gates dihedral-only
        // edges out of allFeatureEdges — letting featureCutAdjacency cut on
        // raw dihedral would resurrect the noise we just filtered.
        int[][] adj = buildFaceAdjacency(faceIdx, faceCount, ed);
        int[][] adjCut = featureCutAdjacency(adj, faceIdx, ed, Float.POSITIVE_INFINITY, allFeatureEdges);
        // High-confidence-only cut adjacency: crest edges + saddle separators
        // (PATCH-13) are the only signals severed. Used by the small-patch
        // merge pass 2 so tiny fragments can absorb into neighbors across
        // the over-permissive principal/dihedral signals (which flag ~34%
        // of edges on the skull) but cannot cross high-confidence crest
        // edges or inter-tooth saddle cuts, preserving anatomical
        // boundaries like orbital rims and individual teeth.
        int[][] adjCrestOnly = featureCutAdjacency(
                adj, faceIdx, ed, Float.POSITIVE_INFINITY, highConfidenceEdges);

        // Face → skeleton bucket (majority vote over the 3 vertices).
        int[] faceBranches = new int[faceCount];
        for (int f = 0; f < faceCount; f++) {
            faceBranches[f] = faceBranch(f, faceIdx, vertexBranchId);
        }

        // Step 3: concavity carve-out. Lifts sockets/cavities into their own
        // patches before the region-growing pass runs.
        int[] facePatchId = new int[faceCount];
        Arrays.fill(facePatchId, -1);
        int nextPatchId = 0;

        float[] angleDefect = computeAngleDefect(mesh, positions, faceIdx);
        int[] concavityVertexCC = concavityVertexComponents(
                angleDefect, mesh, faceIdx, faceCount, nv, adj);
        // concavityVertexCC[v] = component id (size ≥ MIN_CONCAVITY_VERTS) or -1.
        int[] concavityFaceCC = expandConcavityToFaces(
                concavityVertexCC, faceIdx, faceCount, adj, CONCAVITY_RING_EXPAND);
        Map<Integer, Integer> ccToPatch = new HashMap<>();
        for (int f = 0; f < faceCount; f++) {
            int cc = concavityFaceCC[f];
            if (cc < 0) continue;
            Integer pid = ccToPatch.get(cc);
            if (pid == null) {
                pid = nextPatchId++;
                ccToPatch.put(cc, pid);
            }
            facePatchId[f] = pid;
        }

        // Step 4+5: feature-edge-aware connected components over the
        // remaining faces, restricted to the starting face's skeleton bucket.
        int[] compId = new int[faceCount];
        Arrays.fill(compId, -1);
        for (int f = 0; f < faceCount; f++) {
            if (facePatchId[f] != -1) compId[f] = NUM__2;  // concavity-owned
        }
        int[] queue = new int[faceCount];
        for (int start = 0; start < faceCount; start++) {
            if (compId[start] != -1) continue;
            int startBranch = faceBranches[start];
            int head = 0, tail = 0;
            queue[tail++] = start;
            int pid = nextPatchId++;
            compId[start] = pid;
            facePatchId[start] = pid;
            while (head < tail) {
                int f = queue[head++];
                for (int nb : adjCut[f]) {
                    if (nb < 0) continue;
                    if (compId[nb] != -1) continue;
                    if (faceBranches[nb] != startBranch) continue;
                    compId[nb] = pid;
                    facePatchId[nb] = pid;
                    queue[tail++] = nb;
                }
            }
        }

        // Step 6: small-component merge. Two passes: first try to merge via
        // adjCut (non-feature-crossing) neighbours only — preserves feature-
        // isolated components like individual teeth, which would otherwise be
        // swallowed by the surrounding mandible across their high-curvature
        // gap edges. Remaining still-small components merge via the full adj
        // as a second pass so truly orphan islands find a home.
        int[] patchFaceCount = new int[nextPatchId];
        for (int f = 0; f < faceCount; f++) patchFaceCount[facePatchId[f]]++;

        int[] remap = new int[nextPatchId];
        for (int i = 0; i < nextPatchId; i++) remap[i] = i;

        for (int pass = 0; pass < 2; pass++) {
            // Pass 0: absolutely no feature crossings. Pass 1: allow crossing
            // over-permissive dihedral/principal signals to clean up orphan
            // fragments, but still not across high-confidence crest edges.
            int[][] walkAdj = pass == 0 ? adjCut : adjCrestOnly;
            for (int pid = 0; pid < nextPatchId; pid++) {
                if (follow(remap, pid) != pid) continue;  // already merged
                if (patchFaceCount[pid] >= MIN_PATCH_FACES) continue;
                Map<Integer, Integer> votes = new HashMap<>();
                for (int f = 0; f < faceCount; f++) {
                    if (facePatchId[f] != pid) continue;
                    for (int nb : walkAdj[f]) {
                        if (nb < 0) continue;
                        int nbPid = follow(remap, facePatchId[nb]);
                        if (nbPid == pid) continue;
                        votes.merge(nbPid, 1, Integer::sum);
                    }
                }
                int best = -1, bestVotes = 0;
                for (Map.Entry<Integer, Integer> e : votes.entrySet()) {
                    if (e.getValue() > bestVotes) {
                        bestVotes = e.getValue();
                        best = e.getKey();
                    }
                }
                if (best != -1) {
                    remap[pid] = follow(remap, best);
                    patchFaceCount[follow(remap, pid)] += patchFaceCount[pid];
                    patchFaceCount[pid] = 0;
                }
            }
        }

        // Apply remap and compact ids.
        int[] finalId = new int[nextPatchId];
        Arrays.fill(finalId, -1);
        int compacted = 0;
        for (int pid = 0; pid < nextPatchId; pid++) {
            int root = follow(remap, pid);
            if (finalId[root] == -1) finalId[root] = compacted++;
            finalId[pid] = finalId[root];
        }

        int[] compactedFacePatch = new int[faceCount];
        for (int f = 0; f < faceCount; f++) compactedFacePatch[f] = finalId[facePatchId[f]];

        // Compute per-vertex curvature once, shared between split-quality
        // scoring and the final Patch records.
        float[] vertexCurvature = vertexCurvatureFrom(ed, nv);

        // PATCH-6 quality-based split: keep patches that are flat enough AND
        // have ≈4 sides AND aren't too elongated. Split the rest with k
        // chosen from whichever metric deviated most. The cranial vault is
        // smooth (low curvature variance) but has many boundary sides where
        // it meets face/temporal/occipital — the side-count metric forces it
        // to split into left/right parietal etc.
        // PATCH-19: recursive split. splitByQuality only runs once per call
        // — a patch that fails Coons gets k-split into sub-patches that may
        // themselves still fail. Loop until no additional splits happen or
        // we hit the safety cap. In practice 3-5 passes converge for a
        // skull; the cap prevents pathological infinite recursion.
        final int MAX_SPLIT_PASSES = NUM_6;
        int[] splitPatchId = compactedFacePatch;
        int currentPatchCount = compacted;
        for (int pass = 0; pass < MAX_SPLIT_PASSES; pass++) {
            int[] next = splitByQuality(
                    splitPatchId, currentPatchCount, faceIdx, faceCount,
                    positions, vertexCurvature, adj, adjCrestOnly, meshExtent);
            int nextCount = 0;
            for (int pid : next) if (pid + 1 > nextCount) nextCount = pid + 1;
            if (nextCount <= currentPatchCount) break;  // converged
            splitPatchId = next;
            currentPatchCount = nextCount;
        }
        int newCompacted = currentPatchCount;

        // Welsh-Powell 4-coloring over the patch-adjacency graph. Neighboring
        // patches always get different palette indices, so the human render
        // never merges two touching patches into one visual blob. The palette
        // still has 20 colours; Welsh-Powell typically uses 4-6 of them on a
        // planar patch arrangement.
        int[] colorIdx = welshPowellColoring(splitPatchId, adj, faceCount, newCompacted);

        // Step 7: assemble Patch records.
        List<List<Integer>> facesByPatch = new ArrayList<>();
        for (int i = 0; i < newCompacted; i++) facesByPatch.add(new ArrayList<>());
        for (int f = 0; f < faceCount; f++) {
            facesByPatch.get(splitPatchId[f]).add(f);
        }

        List<Patch> out = new ArrayList<>();
        for (int pid = 0; pid < newCompacted; pid++) {
            List<Integer> faceList = facesByPatch.get(pid);
            if (faceList.isEmpty()) continue;

            boolean[] seenVert = new boolean[nv];
            int[] faces = new int[faceList.size()];
            float[] centroid = new float[NUM_3];
            float curvSum = NUM_0;
            int curvSamples = 0;
            int vertCount = 0;
            int branchSample = -1;
            for (int i = 0; i < faces.length; i++) {
                int f = faceList.get(i);
                faces[i] = f;
                if (branchSample == -1) branchSample = faceBranches[f];
                for (int k = 0; k < NUM_3; k++) {
                    int v = faceIdx[f * NUM_3 + k];
                    if (!seenVert[v]) {
                        seenVert[v] = true;
                        centroid[0] += positions[v * NUM_3];
                        centroid[1] += positions[v * NUM_3 + 1];
                        centroid[2] += positions[v * NUM_3 + 2];
                        curvSum += vertexCurvature[v];
                        curvSamples++;
                        vertCount++;
                    }
                }
            }
            if (vertCount == 0) continue;
            int[] verts = new int[vertCount];
            int vi = 0;
            for (int v = 0; v < nv; v++) {
                if (seenVert[v]) verts[vi++] = v;
            }
            centroid[0] /= vertCount;
            centroid[1] /= vertCount;
            centroid[2] /= vertCount;
            String color = PALETTE[colorIdx[pid] % PALETTE.length];
            out.add(new Patch(
                    pid,
                    verts,
                    faces,
                    branchSample,
                    centroid,
                    curvSamples > 0 ? curvSum / curvSamples : NUM_0,
                    color));
        }

        PatchDecomposition decomposition = new PatchDecomposition(nv, out);

        // Patch-boundary edges: any edge whose two adjacent faces end up in
        // different final patches. Compared against the three feature-edge
        // sources, this is what lets a diagnostic caller see which signals
        // were honored and which were overridden by downstream stages
        // (concavity carve-out, skeleton bucketing, small-patch merge, PATCH-6
        // quality split, Welsh-Powell coloring is cosmetic and doesn't affect
        // this).
        Set<Long> patchBoundaryEdges = new HashSet<>();
        for (Map.Entry<Long, int[]> e : ed.edgeFaces().entrySet()) {
            int[] faces = e.getValue();
            if (faces[0] < 0 || faces[1] < 0) continue;
            int p0 = splitPatchId[faces[0]];
            int p1 = splitPatchId[faces[1]];
            if (p0 != p1) patchBoundaryEdges.add(e.getKey());
        }

        // PATCH-16: compute per-vertex Coons reconstruction error across
        // the final patch layout. For each patch that has 4 boundary
        // sides, fit cubic Bezier edges + build a Coons grid + measure
        // nearest-grid-point distance per vertex. Vertices in non-4-sided
        // patches stay at 0 (no error metric defined for them).
        float[] coonsError = new float[nv];
        List<List<Integer>> finalFacesByPatch = new ArrayList<>();
        for (int i = 0; i < newCompacted; i++) finalFacesByPatch.add(new ArrayList<>());
        for (int f = 0; f < faceCount; f++) {
            finalFacesByPatch.get(splitPatchId[f]).add(f);
        }
        for (int pid = 0; pid < newCompacted; pid++) {
            List<Integer> patchFaces = finalFacesByPatch.get(pid);
            if (patchFaces.isEmpty()) continue;
            CoonsReconstructionError.PatchError perr = CoonsReconstructionError.compute(
                    patchFaces, pid, splitPatchId, faceIdx, adj, positions, nv, COONS_UV_SAMPLES);
            if (!perr.fourSided()) continue;
            float[] pe = perr.vertexError();
            for (int v = 0; v < nv; v++) {
                if (pe[v] > coonsError[v]) coonsError[v] = pe[v];
            }
        }
        float coonsThreshold = T_COONS_ERROR_FRAC * meshExtent;

        // PATCH-22 Phase A: compute Morse-Smale critical points + arcs
        // on the already-computed mean-curvature field for diagnostic
        // overlay. Does NOT affect the decomposition itself (Phase B
        // will build a parallel pipeline that does).
        MorseSmaleComplex.Result mscResult = MorseSmaleComplex.compute(
                mesh, meanH, gaussK, ed, NUM_0_10);

        return new DecompositionDiagnostics(
                decomposition,
                splitPatchId,
                dihedralFeatureEdges,
                principalFeatureEdges,
                crest.crestEdges,
                saddle.separatorEdges,
                allFeatureEdges,
                patchBoundaryEdges,
                coonsError,
                coonsThreshold,
                mscResult);
    }

    // ---------- Step 1: nearest-branch vertex assignment ----------

    private static int[] assignByNearestBranch(ArrayMesh mesh, SkeletonResult skel) {
        int nv = mesh.vertexCount();
        float[] positions = mesh.copyPositions();
        int[] out = new int[nv];
        if (skel.branches().isEmpty()) {
            Arrays.fill(out, 0);
            return out;
        }
        List<SkeletonBranch> branches = skel.branches();
        int totalJoints = 0;
        for (SkeletonBranch b : branches) totalJoints += b.joints().size();
        float[] jx = new float[totalJoints];
        float[] jy = new float[totalJoints];
        float[] jz = new float[totalJoints];
        float[] jr = new float[totalJoints];
        int[] jb = new int[totalJoints];
        int idx = 0;
        for (SkeletonBranch b : branches) {
            for (SkeletonJoint j : b.joints()) {
                jx[idx] = j.position()[0];
                jy[idx] = j.position()[1];
                jz[idx] = j.position()[2];
                jr[idx] = Math.max(j.radius(), NUM_1e_4);
                jb[idx] = b.id();
                idx++;
            }
        }
        for (int v = 0; v < nv; v++) {
            float px = positions[v * NUM_3];
            float py = positions[v * NUM_3 + 1];
            float pz = positions[v * NUM_3 + 2];
            int bestBranch = jb[0];
            float bestScore = Float.MAX_VALUE;
            for (int k = 0; k < totalJoints; k++) {
                float dx = px - jx[k];
                float dy = py - jy[k];
                float dz = pz - jz[k];
                float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                float slack = Math.max(NUM_0, d - jr[k]);
                float score = d + slack;
                if (score < bestScore) {
                    bestScore = score;
                    bestBranch = jb[k];
                }
            }
            out[v] = bestBranch;
        }
        return out;
    }

    /**
     * Build the per-edge / per-face structures used by curvature and feature-edge
     * detection: face normals, the edge → incident-faces map, and the edge dihedral angle.
     *
     * @param mesh triangle mesh
     * @return {@link EdgeDihedrals} bundle
     */
    public static EdgeDihedrals computeEdgeDihedrals(ArrayMesh mesh) {
        int[] faceIdx = mesh.copyFaceIndices();
        int faceCount = faceIdx.length / NUM_3;
        float[] positions = mesh.copyPositions();
        float[] faceN = new float[faceCount * NUM_3];
        for (int f = 0; f < faceCount; f++) {
            int a = faceIdx[f * NUM_3] * NUM_3;
            int b = faceIdx[f * NUM_3 + 1] * NUM_3;
            int c = faceIdx[f * NUM_3 + 2] * NUM_3;
            float ax = positions[b] - positions[a];
            float ay = positions[b + 1] - positions[a + 1];
            float az = positions[b + 2] - positions[a + 2];
            float bx = positions[c] - positions[a];
            float by = positions[c + 1] - positions[a + 1];
            float bz = positions[c + 2] - positions[a + 2];
            float nx = ay * bz - az * by;
            float ny = az * bx - ax * bz;
            float nz = ax * by - ay * bx;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > NUM_1e_20) {
                faceN[f * NUM_3] = nx / len;
                faceN[f * NUM_3 + 1] = ny / len;
                faceN[f * NUM_3 + 2] = nz / len;
            }
        }
        Map<Long, int[]> edgeFaces = new HashMap<>();
        for (int f = 0; f < faceCount; f++) {
            for (int e = 0; e < NUM_3; e++) {
                int u = faceIdx[f * NUM_3 + e];
                int v = faceIdx[f * NUM_3 + (e + 1) % NUM_3];
                long key = edgeKey(u, v);
                int[] arr = edgeFaces.get(key);
                if (arr == null) {
                    edgeFaces.put(key, new int[]{f, -1});
                } else if (arr[1] == -1) {
                    arr[1] = f;
                }
            }
        }
        Map<Long, Float> dihedrals = new HashMap<>();
        for (Map.Entry<Long, int[]> e : edgeFaces.entrySet()) {
            int[] pair = e.getValue();
            if (pair[1] == -1) continue;
            int f1 = pair[0];
            int f2 = pair[1];
            float dot = faceN[f1 * NUM_3] * faceN[f2 * NUM_3]
                    + faceN[f1 * NUM_3 + 1] * faceN[f2 * NUM_3 + 1]
                    + faceN[f1 * NUM_3 + 2] * faceN[f2 * NUM_3 + 2];
            dot = Math.max(-NUM_1, Math.min(NUM_1, dot));
            float dihedral = (float) Math.acos(dot);
            dihedrals.put(e.getKey(), dihedral);
        }
        return new EdgeDihedrals(edgeFaces, dihedrals, faceN);
    }

    /**
     * Average dihedral angle per vertex, computed from a precomputed {@link EdgeDihedrals}.
     *
     * @param ed precomputed edge / dihedral data
     * @param vertexCount mesh vertex count (defines the output length)
     * @return per-vertex mean dihedral, zero for vertices with no incident interior edge
     */
    public static float[] vertexCurvatureFrom(EdgeDihedrals ed, int vertexCount) {
        float[] accum = new float[vertexCount];
        int[] count = new int[vertexCount];
        for (Map.Entry<Long, Float> e : ed.dihedralByEdge().entrySet()) {
            long key = e.getKey();
            int u = (int) (key >> NUM_32);
            int v = (int) (key & NUM_0xffffffff);
            float d = e.getValue();
            accum[u] += d;
            accum[v] += d;
            count[u]++;
            count[v]++;
        }
        float[] out = new float[vertexCount];
        for (int i = 0; i < vertexCount; i++) {
            out[i] = count[i] > 0 ? accum[i] / count[i] : NUM_0;
        }
        return out;
    }

    /**
     * Backward-compatible entry point: produces per-vertex mean dihedral,
     * same shape and values as the pre-refactor computeVertexCurvature.
     *
     * @param mesh triangle mesh
     * @return per-vertex mean dihedral angle (length {@code mesh.vertexCount()})
     */
    public static float[] computeVertexCurvature(ArrayMesh mesh) {
        return vertexCurvatureFrom(computeEdgeDihedrals(mesh), mesh.vertexCount());
    }

    // ---------- Step 3: concavity detection ----------

    /**
     * Per-vertex Gaussian curvature approximation: the angle defect.
     * K(v) = 2π - Σ(interior triangle angles incident to v).
     * Strongly negative K marks a concave saddle/bowl vertex; strongly
     * positive marks a sharp convex point.
     *
     * @param mesh triangle mesh (used only for vertex count)
     * @param positions packed XYZ vertex positions
     * @param faceIdx flat triangle index buffer
     * @return per-vertex angle defect (signed, in radians)
     */
    private static float[] computeAngleDefect(ArrayMesh mesh, float[] positions, int[] faceIdx) {
        int nv = mesh.vertexCount();
        int faceCount = faceIdx.length / NUM_3;
        float[] defect = new float[nv];
        Arrays.fill(defect, (float) (2 * Math.PI));
        for (int f = 0; f < faceCount; f++) {
            int v0 = faceIdx[f * NUM_3];
            int v1 = faceIdx[f * NUM_3 + 1];
            int v2 = faceIdx[f * NUM_3 + 2];
            defect[v0] -= triangleAngle(positions, v0, v1, v2);
            defect[v1] -= triangleAngle(positions, v1, v2, v0);
            defect[v2] -= triangleAngle(positions, v2, v0, v1);
        }
        return defect;
    }

    private static float triangleAngle(float[] positions, int at, int toB, int toC) {
        float ax = positions[toB * NUM_3] - positions[at * NUM_3];
        float ay = positions[toB * NUM_3 + 1] - positions[at * NUM_3 + 1];
        float az = positions[toB * NUM_3 + 2] - positions[at * NUM_3 + 2];
        float bx = positions[toC * NUM_3] - positions[at * NUM_3];
        float by = positions[toC * NUM_3 + 1] - positions[at * NUM_3 + 1];
        float bz = positions[toC * NUM_3 + 2] - positions[at * NUM_3 + 2];
        float la = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        float lb = (float) Math.sqrt(bx * bx + by * by + bz * bz);
        if (la < NUM_1e_20 || lb < NUM_1e_20) return NUM_0;
        float dot = (ax * bx + ay * by + az * bz) / (la * lb);
        dot = Math.max(-NUM_1, Math.min(NUM_1, dot));
        return (float) Math.acos(dot);
    }

    /**
     * PATCH-11 helper: the p-th percentile of the per-edge dihedral
     * distribution for adaptive feature thresholding.
     *
     * @param ed precomputed edge / dihedral data
     * @param p percentile in [0, 1] (e.g. 0.85 for p85)
     * @return dihedral angle (radians) at the requested percentile, 0 if the mesh has no edges
     */
    private static float percentileDihedral(EdgeDihedrals ed, float p) {
        Map<Long, Float> dihedrals = ed.dihedralByEdge();
        int n = dihedrals.size();
        if (n == 0) return NUM_0;
        float[] values = new float[n];
        int i = 0;
        for (Float d : dihedrals.values()) values[i++] = d;
        java.util.Arrays.sort(values);
        int idx = Math.min(n - 1, Math.max(0, (int) (n * p)));
        return values[idx];
    }

    private static float computeMeshExtent(float[] positions) {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < positions.length; i += NUM_3) {
            float x = positions[i], y = positions[i + 1], z = positions[i + 2];
            if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z;
            if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z;
        }
        return Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
    }

    /**
     * Per-vertex barycentric area: ⅓ · (sum of incident triangle areas). Used
     * to normalize the cotangent Laplacian mean curvature and the angle-defect
     * Gaussian curvature so both have units of 1/length.
     *
     * @param mesh triangle mesh (used only for vertex count)
     * @param positions packed XYZ vertex positions
     * @param faceIdx flat triangle index buffer
     * @return per-vertex barycentric area
     */
    private static float[] computeBarycentricAreas(ArrayMesh mesh, float[] positions, int[] faceIdx) {
        int nv = mesh.vertexCount();
        int faceCount = faceIdx.length / NUM_3;
        float[] A = new float[nv];
        for (int f = 0; f < faceCount; f++) {
            int a = faceIdx[f * NUM_3], b = faceIdx[f * NUM_3 + 1], c = faceIdx[f * NUM_3 + 2];
            float tri = (float) triangleArea(positions, a, b, c);
            A[a] += tri / NUM_3_2;
            A[b] += tri / NUM_3_2;
            A[c] += tri / NUM_3_2;
        }
        return A;
    }

    /**
     * Meyer 2003 cotangent-Laplacian mean curvature:
     * <pre>
     *   H_v · n_v = (1/(2A_v)) · Σ_{j ∈ N(v)} (cot α_ij + cot β_ij) · (p_j - p_i)
     *   H_v = ½ · |H_v · n_v|,   signed positive when the curvature vector points
     *   along the outward normal (convex) and negative when it points against
     *   (concave).
     * </pre>
     * α_ij, β_ij are the two angles opposite edge ij in the pair of triangles
     * sharing that edge. On a boundary edge only one cotangent contributes.
     *
     * @param mesh triangle mesh (used only for vertex count)
     * @param positions packed XYZ vertex positions
     * @param faceIdx flat triangle index buffer
     * @param ed precomputed edge / dihedral data; supplies face normals for the sign convention
     * @param barycentricArea per-vertex barycentric area (already normalized)
     * @return signed per-vertex mean curvature {@code H} in 1/length units
     */
    private static float[] computeMeanCurvature(
            ArrayMesh mesh, float[] positions, int[] faceIdx,
            EdgeDihedrals ed, float[] barycentricArea) {
        int nv = mesh.vertexCount();
        int faceCount = faceIdx.length / NUM_3;
        // Per-vertex mean-curvature vector components.
        float[] hx = new float[nv];
        float[] hy = new float[nv];
        float[] hz = new float[nv];
        // For each triangle, contribute (cot α) · (p_j - p_i) to the accumulator
        // for each of its three edges, with α being the angle at the third
        // vertex. Each edge collects contributions from both incident triangles.
        for (int f = 0; f < faceCount; f++) {
            int a = faceIdx[f * NUM_3], b = faceIdx[f * NUM_3 + 1], c = faceIdx[f * NUM_3 + 2];
            // Cotangent of angle at each vertex of this triangle.
            float cotA = cotAtVertex(positions, a, b, c);
            float cotB = cotAtVertex(positions, b, c, a);
            float cotC = cotAtVertex(positions, c, a, b);
            // Edge (b,c) opposite a contributes cotA · (b - c) to b's accum
            // and cotA · (c - b) to c's accum.
            accumCot(hx, hy, hz, positions, b, c, cotA);
            accumCot(hx, hy, hz, positions, c, a, cotB);
            accumCot(hx, hy, hz, positions, a, b, cotC);
        }
        // Normalize by 1/(2 A_v) and compute signed magnitude via dot with
        // vertex normal (we reuse face normals averaged per vertex).
        float[] vertexNormals = averageFaceNormalsPerVertex(mesh, faceIdx, ed.faceNormals());
        float[] meanH = new float[nv];
        for (int v = 0; v < nv; v++) {
            float Av = Math.max(barycentricArea[v], NUM_1e_12);
            float nx = hx[v] / (NUM_2 * Av);
            float ny = hy[v] / (NUM_2 * Av);
            float nz = hz[v] / (NUM_2 * Av);
            float magnitude = (float) Math.sqrt(nx * nx + ny * ny + nz * nz) * NUM_0_5;
            // Sign via dot product with vertex normal: positive = convex.
            float dot = nx * vertexNormals[v * NUM_3]
                      + ny * vertexNormals[v * NUM_3 + 1]
                      + nz * vertexNormals[v * NUM_3 + 2];
            meanH[v] = dot > 0 ? magnitude : -magnitude;
        }
        return meanH;
    }

    private static void accumCot(float[] hx, float[] hy, float[] hz,
                                 float[] positions, int u, int w, float cotVal) {
        float dx = positions[w * NUM_3]     - positions[u * NUM_3];
        float dy = positions[w * NUM_3 + 1] - positions[u * NUM_3 + 1];
        float dz = positions[w * NUM_3 + 2] - positions[u * NUM_3 + 2];
        // (p_w - p_u) contributes to u's accum, (p_u - p_w) to w's.
        hx[u] += cotVal * dx;
        hy[u] += cotVal * dy;
        hz[u] += cotVal * dz;
        hx[w] -= cotVal * dx;
        hy[w] -= cotVal * dy;
        hz[w] -= cotVal * dz;
    }

    private static float cotAtVertex(float[] positions, int at, int b, int c) {
        float ax = positions[b * NUM_3]     - positions[at * NUM_3];
        float ay = positions[b * NUM_3 + 1] - positions[at * NUM_3 + 1];
        float az = positions[b * NUM_3 + 2] - positions[at * NUM_3 + 2];
        float bx = positions[c * NUM_3]     - positions[at * NUM_3];
        float by = positions[c * NUM_3 + 1] - positions[at * NUM_3 + 1];
        float bz = positions[c * NUM_3 + 2] - positions[at * NUM_3 + 2];
        float dot = ax * bx + ay * by + az * bz;
        float crossX = ay * bz - az * by;
        float crossY = az * bx - ax * bz;
        float crossZ = ax * by - ay * bx;
        float crossLen = (float) Math.sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ);
        if (crossLen < NUM_1e_20) return NUM_0;
        return dot / crossLen;
    }

    private static float[] averageFaceNormalsPerVertex(ArrayMesh mesh, int[] faceIdx, float[] faceNormals) {
        int nv = mesh.vertexCount();
        int faceCount = faceIdx.length / NUM_3;
        float[] out = new float[nv * NUM_3];
        for (int f = 0; f < faceCount; f++) {
            for (int k = 0; k < NUM_3; k++) {
                int v = faceIdx[f * NUM_3 + k];
                out[v * NUM_3]     += faceNormals[f * NUM_3];
                out[v * NUM_3 + 1] += faceNormals[f * NUM_3 + 1];
                out[v * NUM_3 + 2] += faceNormals[f * NUM_3 + 2];
            }
        }
        for (int v = 0; v < nv; v++) {
            float nx = out[v * NUM_3], ny = out[v * NUM_3 + 1], nz = out[v * NUM_3 + 2];
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > NUM_1e_20) {
                out[v * NUM_3]     = nx / len;
                out[v * NUM_3 + 1] = ny / len;
                out[v * NUM_3 + 2] = nz / len;
            }
        }
        return out;
    }

    /**
     * Per-vertex Gaussian curvature K = (2π - Σθ)/A_v, using the same
     * barycentric area normalization as mean curvature so the κ₁,₂ formula
     * behaves dimensionally correctly.
     *
     * @param mesh triangle mesh (used only for vertex count)
     * @param positions packed XYZ vertex positions
     * @param faceIdx flat triangle index buffer
     * @param barycentricArea per-vertex barycentric area (denominator)
     * @return per-vertex Gaussian curvature {@code K} in 1/length^2 units
     */
    private static float[] computeGaussianCurvature(
            ArrayMesh mesh, float[] positions, int[] faceIdx, float[] barycentricArea) {
        float[] defect = computeAngleDefect(mesh, positions, faceIdx);
        int nv = mesh.vertexCount();
        float[] K = new float[nv];
        for (int v = 0; v < nv; v++) {
            float Av = Math.max(barycentricArea[v], NUM_1e_12);
            K[v] = defect[v] / Av;
        }
        return K;
    }

    /**
     * Principal curvatures from mean H and Gaussian K:
     * κ₁,₂ = H ± √(max(0, H² - K)). Returns [kappa1[], kappa2[]] with
     * κ₁ ≥ κ₂ pointwise.
     *
     * @param H per-vertex mean curvature
     * @param K per-vertex Gaussian curvature
     * @return two-row array {@code [κ₁[], κ₂[]]} with {@code κ₁ >= κ₂} pointwise
     */
    private static float[][] computePrincipalCurvatures(float[] H, float[] K) {
        int nv = H.length;
        float[] k1 = new float[nv];
        float[] k2 = new float[nv];
        for (int v = 0; v < nv; v++) {
            float discriminant = Math.max(NUM_0, H[v] * H[v] - K[v]);
            float s = (float) Math.sqrt(discriminant);
            k1[v] = H[v] + s;
            k2[v] = H[v] - s;
        }
        return new float[][]{k1, k2};
    }

    /**
     * Promote edges to feature cuts when both endpoints have strong principal
     * curvature of matching sign — convex ridges (κ₁ > T) or concave valleys
     * (κ₂ < -T). Threshold is in 1/length units, so the caller should pass
     * {@code T / mesh_extent} to stay scale-invariant.
     *
     * @param ed precomputed edge data (supplies the edge enumeration)
     * @param kappa1 per-vertex maximum principal curvature
     * @param kappa2 per-vertex minimum principal curvature
     * @param ridgeThreshold positive threshold on κ₁ for ridge promotion (1/length)
     * @param valleyThreshold positive threshold on −κ₂ for valley promotion (1/length)
     * @return set of edge keys that satisfy the ridge or valley test on both endpoints
     */
    private static java.util.Set<Long> principalCurvatureFeatureEdges(
            EdgeDihedrals ed, float[] kappa1, float[] kappa2,
            float ridgeThreshold, float valleyThreshold) {
        java.util.Set<Long> out = new java.util.HashSet<>();
        for (Map.Entry<Long, int[]> e : ed.edgeFaces().entrySet()) {
            long key = e.getKey();
            int u = (int) (key >> NUM_32);
            int v = (int) (key & NUM_0xffffffff);
            boolean ridge = kappa1[u] > ridgeThreshold && kappa1[v] > ridgeThreshold;
            boolean valley = kappa2[u] < -valleyThreshold && kappa2[v] < -valleyThreshold;
            if (ridge || valley) out.add(key);
        }
        return out;
    }

    /**
     * Percentile over either the positive side ({@code positive=true}, for
     * {@code |κ₁|} / ridge magnitude) or the negated values ({@code
     * positive=false}, for {@code −κ₂} / valley depth) of a per-vertex
     * curvature array. Used to derive mesh-adaptive thresholds.
     *
     * @param values per-vertex curvature array
     * @param pct percentile in [0, 1]
     * @param positive {@code true} to take {@code max(value, 0)}, {@code false} to take {@code max(-value, 0)}
     * @return value at the requested percentile, 0 for an empty input
     */
    private static float percentileAbs(float[] values, float pct, boolean positive) {
        int n = values.length;
        if (n == 0) return NUM_0;
        float[] copy = new float[n];
        for (int i = 0; i < n; i++) {
            copy[i] = positive ? Math.max(values[i], NUM_0) : Math.max(-values[i], NUM_0);
        }
        java.util.Arrays.sort(copy);
        int idx = Math.min(n - 1, Math.max(0, Math.round((n - 1) * pct)));
        return copy[idx];
    }

    /**
     * Connected components of strongly-concave vertices via mesh adjacency.
     * Components smaller than {@link #MIN_CONCAVITY_VERTS} are discarded
     * (set to -1); survivors get a unique non-negative component id.
     *
     * @param defect per-vertex angle defect (used to identify seed vertices)
     * @param mesh source triangle mesh
     * @param faceIdx flat triangle index buffer
     * @param faceCount number of triangles
     * @param vertexCount number of vertices
     * @param faceAdj per-face adjacency table (kept for parity with other helpers; not used directly)
     * @return per-vertex component id, or {@code -1} for vertices not in any kept component
     */
    private static int[] concavityVertexComponents(
            float[] defect, ArrayMesh mesh, int[] faceIdx, int faceCount,
            int vertexCount, int[][] faceAdj) {
        // Build vertex → vertex adjacency (undirected) from triangle edges.
        boolean[] isSeed = new boolean[vertexCount];
        for (int v = 0; v < vertexCount; v++) {
            if (defect[v] < T_CONCAVE_RAD) isSeed[v] = true;
        }
        // Compressed vertex-neighbour CSR.
        int[] degree = new int[vertexCount];
        for (int f = 0; f < faceCount; f++) {
            int a = faceIdx[f * NUM_3];
            int b = faceIdx[f * NUM_3 + 1];
            int c = faceIdx[f * NUM_3 + 2];
            degree[a] += 2; degree[b] += 2; degree[c] += 2;
        }
        int[] offsets = new int[vertexCount + 1];
        for (int i = 0; i < vertexCount; i++) offsets[i + 1] = offsets[i] + degree[i];
        int[] neigh = new int[offsets[vertexCount]];
        int[] cursor = new int[vertexCount];
        for (int f = 0; f < faceCount; f++) {
            int a = faceIdx[f * NUM_3];
            int b = faceIdx[f * NUM_3 + 1];
            int c = faceIdx[f * NUM_3 + 2];
            neigh[offsets[a] + cursor[a]++] = b;
            neigh[offsets[a] + cursor[a]++] = c;
            neigh[offsets[b] + cursor[b]++] = a;
            neigh[offsets[b] + cursor[b]++] = c;
            neigh[offsets[c] + cursor[c]++] = a;
            neigh[offsets[c] + cursor[c]++] = b;
        }

        int[] labels = new int[vertexCount];
        Arrays.fill(labels, -1);
        int[] queue = new int[vertexCount];
        int nextCC = 0;
        List<Integer> kept = new ArrayList<>();
        int[] ccSize = new int[vertexCount];

        for (int v = 0; v < vertexCount; v++) {
            if (!isSeed[v] || labels[v] != -1) continue;
            int cc = nextCC++;
            int head = 0, tail = 0;
            queue[tail++] = v;
            labels[v] = cc;
            int size = 0;
            while (head < tail) {
                int u = queue[head++];
                size++;
                for (int p = offsets[u]; p < offsets[u + 1]; p++) {
                    int w = neigh[p];
                    if (!isSeed[w] || labels[w] != -1) continue;
                    labels[w] = cc;
                    queue[tail++] = w;
                }
            }
            ccSize[cc] = size;
        }
        // Drop CCs smaller than the minimum.
        for (int v = 0; v < vertexCount; v++) {
            int l = labels[v];
            if (l != -1 && ccSize[l] < MIN_CONCAVITY_VERTS) labels[v] = -1;
        }
        return labels;
    }

    /**
     * Promote a per-vertex concavity labelling to per-face, then expand by
     * {@code ringExpand} face-rings so the patch includes a thin halo of
     * surrounding surface (the rim renders as a colour change visible in the
     * multiview; without halo the patch boundary sits exactly on the crease
     * and visually merges with the neighbour).
     *
     * @param vertexCC per-vertex concavity component id ({@code -1} for none)
     * @param faceIdx flat triangle index buffer
     * @param faceCount number of triangles
     * @param adj per-face adjacency used for halo expansion
     * @param ringExpand number of face rings to flood outward from the seeded faces
     * @return per-face component id ({@code -1} for faces outside any halo)
     */
    private static int[] expandConcavityToFaces(
            int[] vertexCC, int[] faceIdx, int faceCount, int[][] adj, int ringExpand) {
        int[] faceCC = new int[faceCount];
        Arrays.fill(faceCC, -1);
        for (int f = 0; f < faceCount; f++) {
            int a = vertexCC[faceIdx[f * NUM_3]];
            int b = vertexCC[faceIdx[f * NUM_3 + 1]];
            int c = vertexCC[faceIdx[f * NUM_3 + 2]];
            int pick = -1;
            int aCount = 0, bCount = 0, cCount = 0;
            if (a != -1) { aCount = 1; pick = a; }
            if (b == a && a != -1) aCount++;
            if (c == a && a != -1) aCount++;
            if (b != -1) {
                bCount = 1;
                if (a == b) bCount++;
                if (c == b) bCount++;
            }
            if (c != -1) {
                cCount = 1;
                if (a == c) cCount++;
                if (b == c) cCount++;
            }
            if (aCount >= 2) pick = a;
            else if (bCount >= 2) pick = b;
            else if (cCount >= 2) pick = c;
            else if (a != -1) pick = a;
            else if (b != -1) pick = b;
            else if (c != -1) pick = c;
            faceCC[f] = pick;
        }
        for (int ring = 0; ring < ringExpand; ring++) {
            int[] snapshot = faceCC.clone();
            for (int f = 0; f < faceCount; f++) {
                if (snapshot[f] != -1) continue;
                // Inherit a neighbour's cc id if any neighbour is tagged.
                for (int nb : adj[f]) {
                    if (nb < 0) continue;
                    if (snapshot[nb] != -1) {
                        faceCC[f] = snapshot[nb];
                        break;
                    }
                }
            }
        }
        return faceCC;
    }

    // ---------- Step 4: face adjacency ----------

    /**
     * Full face-face adjacency: each face has up to three neighbours (one per
     * edge). Built from the edge→faces map so it's O(E) not
     * O(F · vertex_ring²).
     *
     * @param faceIdx flat triangle index buffer
     * @param faceCount number of triangles
     * @param ed precomputed edge / face data
     * @return {@code adj[f][e]} = id of the face across edge {@code e} of face {@code f}, or {@code -1} on a boundary
     */
    static int[][] buildFaceAdjacency(int[] faceIdx, int faceCount, EdgeDihedrals ed) {
        int[][] adj = new int[faceCount][NUM_3];
        for (int f = 0; f < faceCount; f++) {
            Arrays.fill(adj[f], -1);
        }
        for (Map.Entry<Long, int[]> e : ed.edgeFaces().entrySet()) {
            int[] pair = e.getValue();
            if (pair[1] == -1) continue;
            long key = e.getKey();
            int u = (int) (key >> NUM_32);
            int v = (int) (key & NUM_0xffffffff);
            attachNeighbour(adj, faceIdx, pair[0], pair[1], u, v);
            attachNeighbour(adj, faceIdx, pair[1], pair[0], u, v);
        }
        return adj;
    }

    private static void attachNeighbour(int[][] adj, int[] faceIdx, int f, int neighbour, int u, int v) {
        for (int e = 0; e < NUM_3; e++) {
            int a = faceIdx[f * NUM_3 + e];
            int b = faceIdx[f * NUM_3 + (e + 1) % NUM_3];
            if ((a == u && b == v) || (a == v && b == u)) {
                adj[f][e] = neighbour;
                return;
            }
        }
    }

    /**
     * Feature-cut adjacency: clone of {@code adj}, but when the shared edge's
     * dihedral exceeds {@code thresholdRad} OR the edge is in the supplied
     * principal-curvature feature set, the neighbour slot is cleared to
     * {@code -1}. Region growing on this graph can't cross a feature ridge.
     *
     * @param adj source face-face adjacency
     * @param faceIdx flat triangle index buffer (used to identify each adjacency edge)
     * @param ed precomputed edge / dihedral data
     * @param thresholdRad dihedral threshold above which an edge is severed
     * @param principalFeatureEdges optional set of edge keys to also sever; may be null
     * @return adjacency clone with severed edges replaced by {@code -1}
     */
    private static int[][] featureCutAdjacency(
            int[][] adj, int[] faceIdx, EdgeDihedrals ed, float thresholdRad,
            java.util.Set<Long> principalFeatureEdges) {
        int faceCount = adj.length;
        int[][] out = new int[faceCount][NUM_3];
        for (int f = 0; f < faceCount; f++) {
            for (int e = 0; e < NUM_3; e++) {
                int nb = adj[f][e];
                if (nb == -1) {
                    out[f][e] = -1;
                    continue;
                }
                int u = faceIdx[f * NUM_3 + e];
                int v = faceIdx[f * NUM_3 + (e + 1) % NUM_3];
                long key = edgeKey(u, v);
                Float d = ed.dihedralByEdge().get(key);
                boolean dihedralCut = d != null && d > thresholdRad;
                boolean principalCut = principalFeatureEdges != null
                        && principalFeatureEdges.contains(key);
                out[f][e] = (dihedralCut || principalCut) ? -1 : nb;
            }
        }
        return out;
    }

    // ---------- shared helpers ----------

    static long edgeKey(int u, int v) {
        return u < v ? ((long) u << NUM_32) | (v & NUM_0xffffffff) : ((long) v << NUM_32) | (u & NUM_0xffffffff);
    }

    private static int faceBranch(int f, int[] faceIdx, int[] vertexBranchId) {
        int a = vertexBranchId[faceIdx[f * NUM_3]];
        int b = vertexBranchId[faceIdx[f * NUM_3 + 1]];
        int c = vertexBranchId[faceIdx[f * NUM_3 + 2]];
        if (a == b) return a;
        if (a == c) return a;
        if (b == c) return b;
        return a;
    }

    private static int follow(int[] remap, int i) {
        while (remap[i] != i) i = remap[i];
        return i;
    }

    /**
     * Welsh-Powell greedy coloring over the patch-adjacency graph. Each patch
     * gets a colour index distinct from every neighbour. Returns
     * {@code colorIdx[pid]} ∈ [0, 5] or so (4-6 colours suffice on planar
     * surface patches by the four-colour theorem).
     *
     * @param facePatch per-face patch id
     * @param adj per-face adjacency table
     * @param faceCount number of triangles
     * @param patchCount number of patches
     * @return per-patch palette index in {@code [0, PALETTE.length)}
     */
    private static int[] welshPowellColoring(
            int[] facePatch, int[][] adj, int faceCount, int patchCount) {
        @SuppressWarnings("unchecked")
        java.util.Set<Integer>[] neighbours = new java.util.HashSet[patchCount];
        for (int i = 0; i < patchCount; i++) neighbours[i] = new java.util.HashSet<>();
        for (int f = 0; f < faceCount; f++) {
            int pa = facePatch[f];
            for (int nb : adj[f]) {
                if (nb < 0) continue;
                int pb = facePatch[nb];
                if (pb == pa) continue;
                neighbours[pa].add(pb);
                neighbours[pb].add(pa);
            }
        }
        Integer[] order = new Integer[patchCount];
        for (int i = 0; i < patchCount; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Integer.compare(neighbours[b].size(), neighbours[a].size()));
        int[] colorIdx = new int[patchCount];
        Arrays.fill(colorIdx, -1);
        for (int p : order) {
            boolean[] used = new boolean[PALETTE.length];
            for (int nb : neighbours[p]) {
                int c = colorIdx[nb];
                if (c >= 0 && c < used.length) used[c] = true;
            }
            int pick = 0;
            while (pick < used.length && used[pick]) pick++;
            colorIdx[p] = pick;
        }
        return colorIdx;
    }

    /**
     * PATCH-6 quality-based split. For each candidate patch we score three
     * geometric metrics:
     *
     * <ul>
     *   <li>curvature_stddev — stddev of per-vertex mean dihedral across the
     *       patch. Low = flat surface, good Coons candidate. High = bumpy.</li>
     *   <li>side_count — number of boundary vertices where the two adjacent
     *       boundary edges turn by more than {@link #T_CORNER_RAD}. ≈4 = Coons
     *       ideal; much higher means the boundary has too many "sides".</li>
     *   <li>iso_ratio — 4π·area / perimeter², measuring how circular the
     *       patch is. Low = long/snaking/concave.</li>
     * </ul>
     *
     * A patch passes (no split) when it is flat AND close to 4 sides AND not
     * pathologically elongated. Otherwise we split via positional k-means with
     * k chosen from whichever metric deviated most (side count → ceil(sides/4);
     * bumpy + big → ceil(verts / 2000)).
     *
     * {@link #HARD_MAX_PATCH_VERTS} is an absolute safety ceiling that
     * overrides the scorer: no patch ever survives with more than that many
     * vertices, no matter how flat and Coons-shaped the scorer claims it is.
     *
     * @param facePatch per-face patch id
     * @param patchCount number of patches
     * @param faceIdx flat triangle index buffer
     * @param faceCount number of triangles
     * @param positions packed XYZ vertex positions
     * @param vertexCurvature per-vertex curvature scalar (mean dihedral)
     * @param adj face-face adjacency
     * @param adjCrestOnly adjacency severed only along crest edges (used during k-means seeding/expansion)
     * @param meshExtent largest axis span of the mesh; calibrates the Coons error budget
     * @return new per-face patch id assignment (some patches subdivided)
     */
    private static int[] splitByQuality(
            int[] facePatch, int patchCount, int[] faceIdx, int faceCount,
            float[] positions, float[] vertexCurvature, int[][] adj,
            int[][] adjCrestOnly, float meshExtent) {
        // 1. Group faces + vertices by patch id.
        List<java.util.BitSet> vertsByPatch = new ArrayList<>();
        List<List<Integer>> facesByPatch = new ArrayList<>();
        for (int i = 0; i < patchCount; i++) {
            vertsByPatch.add(new java.util.BitSet());
            facesByPatch.add(new ArrayList<>());
        }
        for (int f = 0; f < faceCount; f++) {
            int p = facePatch[f];
            facesByPatch.get(p).add(f);
            java.util.BitSet bs = vertsByPatch.get(p);
            bs.set(faceIdx[f * NUM_3]);
            bs.set(faceIdx[f * NUM_3 + 1]);
            bs.set(faceIdx[f * NUM_3 + 2]);
        }

        int[] outPatch = facePatch.clone();
        int nextId = patchCount;

        for (int pid = 0; pid < patchCount; pid++) {
            List<Integer> faces = facesByPatch.get(pid);
            if (faces.isEmpty()) continue;
            int vertCount = vertsByPatch.get(pid).cardinality();

            float curvStddev = curvatureStddev(vertsByPatch.get(pid), vertexCurvature);
            int sides = boundarySideCount(faces, facePatch, pid, faceIdx, adj, positions);
            float isoRatio = isoperimetricRatio(faces, facePatch, pid, faceIdx, adj, positions);

            // PATCH-16: direct Coons reconstruction-error base case. The
            // walker picks the 4 strongest corners regardless of the raw
            // side count, so we try the Coons fit on anything whose
            // boundary is a simple manifold ring of ≥4 vertices. For
            // non-simply-connected boundaries the fit returns fourSided
            // = false and we keep shape-proxy-only behavior.
            float coonsP95 = NUM_0;
            boolean coonsOk = true;
            int meshVertCount = positions.length / NUM_3;
            CoonsReconstructionError.PatchError err = CoonsReconstructionError.compute(
                    faces, pid, facePatch, faceIdx, adj, positions,
                    meshVertCount, COONS_UV_SAMPLES);
            if (err.fourSided()) {
                coonsP95 = err.p95Error();
                coonsOk = coonsP95 <= T_COONS_ERROR_FRAC * meshExtent;
            }

            // Pass criteria: when a Coons fit exists and passes, accept
            // the patch immediately — shape proxies (sides, iso ratio,
            // flatness) are SUBORDINATE to the direct reconstruction
            // question per PATCH-16's design. They still act as belt-and-
            // braces when Coons isn't applicable (e.g. boundary too
            // broken to walk).
            boolean flat       = curvStddev <= T_FLAT;
            boolean goodSides  = sides >= NUM_3 && sides <= MAX_SIDES_BEFORE_SPLIT;
            boolean compact    = isoRatio >= T_ISO_RATIO;
            boolean withinSize = vertCount <= HARD_MAX_PATCH_VERTS;

            if (err.fourSided()) {
                // Coons metric available → it's authoritative. Still enforce
                // the absolute vertex-count ceiling so a huge patch with
                // low error doesn't balloon memory downstream.
                if (coonsOk && withinSize) continue;
            } else {
                // Fall back to the classical shape-proxy stack.
                if (flat && goodSides && compact && withinSize) continue;
            }

            // Split. k is picked from whichever metric is loudest, then
            // capped at MAX_K_PER_SPLIT so a pathological patch can't
            // balloon into 20 sub-patches in one pass.
            int kBySides = sides > MAX_SIDES_BEFORE_SPLIT
                    ? (int) Math.ceil(sides / (double) IDEAL_SIDES)
                    : 2;
            int kBySize  = vertCount > HARD_MAX_PATCH_VERTS
                    ? (int) Math.ceil(vertCount / NUM_2500_0)
                    : 2;
            // PATCH-16: Coons error drives k when shape-proxies pass but
            // the patch can't be Coons-fit. Ratio of p95 error to the
            // acceptable threshold = how many bands over budget we are.
            int kByCoons = 2;
            if (!coonsOk && meshExtent > NUM_1e_6) {
                float ratio = coonsP95 / (T_COONS_ERROR_FRAC * meshExtent);
                kByCoons = Math.max(2, (int) Math.ceil(ratio));
            }
            int k = Math.max(2, Math.max(kBySides, Math.max(kBySize, kByCoons)));
            k = Math.min(k, MAX_K_PER_SPLIT);
            if (k < 2) continue;

            // Collect face centroids.
            int n = faces.size();
            float[] centroids = new float[n * NUM_3];
            float[] faceError = new float[n];  // PATCH-19: per-face max-vertex error
            for (int i = 0; i < n; i++) {
                int f = faces.get(i);
                int a = faceIdx[f * NUM_3], b = faceIdx[f * NUM_3 + 1], c = faceIdx[f * NUM_3 + 2];
                centroids[i * NUM_3]     = (positions[a * NUM_3]     + positions[b * NUM_3]     + positions[c * NUM_3])     / NUM_3_2;
                centroids[i * NUM_3 + 1] = (positions[a * NUM_3 + 1] + positions[b * NUM_3 + 1] + positions[c * NUM_3 + 1]) / NUM_3_2;
                centroids[i * NUM_3 + 2] = (positions[a * NUM_3 + 2] + positions[b * NUM_3 + 2] + positions[c * NUM_3 + 2]) / NUM_3_2;
                if (err.fourSided()) {
                    float e = Math.max(err.vertexError()[a],
                                       Math.max(err.vertexError()[b], err.vertexError()[c]));
                    faceError[i] = e;
                }
            }

            // PATCH-19 + PATCH-21: when the split is Coons-triggered, seed
            // at the k highest-error face centroids (with spatial stride to
            // avoid clustering seeds in one bright spot), then region-grow
            // by BFS across `adjCrestOnly` — the feature-cut adjacency
            // that severs saddle + crest edges. Sub-patches therefore
            // respect anatomical boundaries by construction: saddle-
            // separated tooth gaps, orbital rims, nose aperture rings all
            // stop the growth. Orphans (faces unreachable from any seed
            // because every path crosses a crest/saddle) fall back to
            // nearest-seed-by-Euclidean so they still get assigned.
            //
            // When Coons didn't trigger (or fit wasn't possible for this
            // patch), fall back to the positional k-means++ path — the
            // patch's badness is shape-proxy driven and feature edges may
            // not be informative.
            int[] labels;
            if (!coonsOk && err.fourSided()) {
                Integer[] sortedIdx = new Integer[n];
                for (int i = 0; i < n; i++) sortedIdx[i] = i;
                Arrays.sort(sortedIdx, (a, b) -> Float.compare(faceError[b], faceError[a]));
                int[] seedFaceIds = new int[k];
                int stride = Math.max(1, n / (k * NUM_3));
                for (int c = 0; c < k; c++) {
                    int srcIdx = sortedIdx[Math.min(c * stride, n - 1)];
                    seedFaceIds[c] = faces.get(srcIdx);
                }
                labels = bfsRegionGrow(faces, seedFaceIds, adjCrestOnly, centroids);
            } else {
                labels = kmeansXyz(centroids, n, k, NUM_0x53D5 ^ pid);
            }
            int[] idMap = new int[k];
            idMap[0] = pid;  // first cluster keeps the original id
            for (int c = 1; c < k; c++) idMap[c] = nextId++;
            for (int i = 0; i < n; i++) {
                outPatch[faces.get(i)] = idMap[labels[i]];
            }
        }
        return outPatch;
    }

    private static float curvatureStddev(java.util.BitSet verts, float[] curvature) {
        int n = 0;
        double sum = 0, sumSq = 0;
        for (int v = verts.nextSetBit(0); v >= 0; v = verts.nextSetBit(v + 1)) {
            double c = curvature[v];
            sum += c;
            sumSq += c * c;
            n++;
        }
        if (n == 0) return NUM_0;
        double mean = sum / n;
        double variance = Math.max(0.0, sumSq / n - mean * mean);
        return (float) Math.sqrt(variance);
    }

    /**
     * Counts boundary vertices of {@code patchId} where the two incident
     * boundary edges turn by more than {@link #T_CORNER_RAD}. Works by
     * collecting each boundary vertex's two incident boundary edges and
     * measuring the angle between their outgoing direction vectors.
     *
     * @param faces face ids belonging to this patch
     * @param facePatch per-face patch id (used to detect interior edges)
     * @param patchId patch id under inspection
     * @param faceIdx flat triangle index buffer
     * @param adj per-face adjacency
     * @param positions packed XYZ vertex positions
     * @return number of corner vertices on this patch's boundary
     */
    private static int boundarySideCount(
            List<Integer> faces, int[] facePatch, int patchId,
            int[] faceIdx, int[][] adj, float[] positions) {
        // For each vertex that lies on the patch boundary, remember its two
        // boundary-edge endpoints (the other vertex of each boundary edge).
        Map<Integer, int[]> neighbours = new HashMap<>();
        for (int f : faces) {
            for (int e = 0; e < NUM_3; e++) {
                int nb = adj[f][e];
                if (nb >= 0 && facePatch[nb] == patchId) continue;  // interior edge
                int u = faceIdx[f * NUM_3 + e];
                int v = faceIdx[f * NUM_3 + (e + 1) % NUM_3];
                addNeighbour(neighbours, u, v);
                addNeighbour(neighbours, v, u);
            }
        }
        int corners = 0;
        for (Map.Entry<Integer, int[]> e : neighbours.entrySet()) {
            int[] nbs = e.getValue();
            if (nbs[0] < 0 || nbs[1] < 0) continue;  // degenerate
            int at = e.getKey();
            float[] da = unitDir(positions, at, nbs[0]);
            float[] db = unitDir(positions, at, nbs[1]);
            float dot = da[0] * db[0] + da[1] * db[1] + da[2] * db[2];
            dot = Math.max(-NUM_1, Math.min(NUM_1, dot));
            // nbs[0]↔at↔nbs[1] goes STRAIGHT through "at" when dot ≈ -1 (the
            // two outgoing directions are opposite). A corner turn bends the
            // path — when the outgoing directions deviate from opposite by
            // more than T_CORNER_RAD, we score a corner.
            float straightDeviation = (float) Math.acos(dot) - (float) Math.PI;
            if (Math.abs(straightDeviation) > T_CORNER_RAD) corners++;
        }
        return corners;
    }

    private static void addNeighbour(Map<Integer, int[]> m, int at, int other) {
        int[] pair = m.get(at);
        if (pair == null) {
            m.put(at, new int[]{other, -1});
        } else if (pair[1] == -1 && pair[0] != other) {
            pair[1] = other;
        }
        // (ignore 3rd+ — treat degenerate boundary junctions as corners implicitly
        // via their neighbours)
    }

    private static float[] unitDir(float[] positions, int from, int to) {
        float dx = positions[to * NUM_3] - positions[from * NUM_3];
        float dy = positions[to * NUM_3 + 1] - positions[from * NUM_3 + 1];
        float dz = positions[to * NUM_3 + 2] - positions[from * NUM_3 + 2];
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < NUM_1e_20) return new float[]{0, 0, 0};
        return new float[]{dx / len, dy / len, dz / len};
    }

    /**
     * Isoperimetric ratio: 4π·area / perimeter². 1.0 for a circle, lower
     * values indicate elongated or concave patches. Clamped to [0, 1].
     *
     * @param faces face ids belonging to this patch
     * @param facePatch per-face patch id (used to detect interior edges)
     * @param patchId patch id under inspection
     * @param faceIdx flat triangle index buffer
     * @param adj per-face adjacency
     * @param positions packed XYZ vertex positions
     * @return isoperimetric ratio in [0, 1]; 1 means perfectly circular
     */
    private static float isoperimetricRatio(
            List<Integer> faces, int[] facePatch, int patchId,
            int[] faceIdx, int[][] adj, float[] positions) {
        double area = 0, perimeter = 0;
        for (int f : faces) {
            area += triangleArea(positions, faceIdx[f * NUM_3], faceIdx[f * NUM_3 + 1], faceIdx[f * NUM_3 + 2]);
            for (int e = 0; e < NUM_3; e++) {
                int nb = adj[f][e];
                if (nb >= 0 && facePatch[nb] == patchId) continue;
                int u = faceIdx[f * NUM_3 + e];
                int v = faceIdx[f * NUM_3 + (e + 1) % NUM_3];
                double dx = positions[v * NUM_3] - positions[u * NUM_3];
                double dy = positions[v * NUM_3 + 1] - positions[u * NUM_3 + 1];
                double dz = positions[v * NUM_3 + 2] - positions[u * NUM_3 + 2];
                perimeter += Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
        }
        if (perimeter <= 0) return NUM_1;
        double ratio = IDEAL_SIDES * Math.PI * area / (perimeter * perimeter);
        return (float) Math.max(0, Math.min(1, ratio));
    }

    private static double triangleArea(float[] positions, int a, int b, int c) {
        double ax = positions[b * NUM_3]     - positions[a * NUM_3];
        double ay = positions[b * NUM_3 + 1] - positions[a * NUM_3 + 1];
        double az = positions[b * NUM_3 + 2] - positions[a * NUM_3 + 2];
        double bx = positions[c * NUM_3]     - positions[a * NUM_3];
        double by = positions[c * NUM_3 + 1] - positions[a * NUM_3 + 1];
        double bz = positions[c * NUM_3 + 2] - positions[a * NUM_3 + 2];
        double cx = ay * bz - az * by;
        double cy = az * bx - ax * bz;
        double cz = ax * by - ay * bx;
        return NUM_0_5_2 * Math.sqrt(cx * cx + cy * cy + cz * cz);
    }

    /**
     * PATCH-21: feature-aware multi-source BFS that replaces Euclidean
     * k-means when the split is Coons-error-triggered. Each face in the
     * patch is assigned to the seed face that reaches it first via
     * {@code adjCrestOnly} (face adjacency with saddle + crest edges
     * severed). Grown regions therefore cannot cross high-confidence
     * anatomical boundaries, so the split respects ridges and tooth gaps
     * by construction rather than cutting perpendicular to them.
     *
     * <p>Faces the BFS can't reach from any seed (orphans — fully
     * enclosed by saddle/crest walls inside the patch) fall back to the
     * nearest seed by face-centroid Euclidean distance, so every face
     * still gets labeled.
     *
     * @param faces patch's faces in list order; {@code labels[i]}
     *              corresponds to {@code faces.get(i)}.
     * @param seedFaceIds k seed face ids (global mesh face indices).
     * @param adjCrestOnly face-face adjacency with saddle/crest cut to -1.
     * @param centroids flat float[n*3] of face centroids in list order.
     * @return int[n] labels 0..k-1.
     */
    private static int[] bfsRegionGrow(List<Integer> faces, int[] seedFaceIds,
                                        int[][] adjCrestOnly, float[] centroids) {
        int n = faces.size();
        int k = seedFaceIds.length;
        Map<Integer, Integer> faceToListIdx = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) faceToListIdx.put(faces.get(i), i);

        int[] labels = new int[n];
        Arrays.fill(labels, -1);
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        for (int c = 0; c < k; c++) {
            Integer seedListIdx = faceToListIdx.get(seedFaceIds[c]);
            if (seedListIdx == null) continue;
            if (labels[seedListIdx] < 0) {
                labels[seedListIdx] = c;
                queue.add(new int[]{seedListIdx, c});
            }
        }
        while (!queue.isEmpty()) {
            int[] head = queue.poll();
            int listIdx = head[0];
            int label = head[1];
            int faceId = faces.get(listIdx);
            for (int nb : adjCrestOnly[faceId]) {
                if (nb < 0) continue;  // saddle/crest cut — don't cross
                Integer nbListIdx = faceToListIdx.get(nb);
                if (nbListIdx == null) continue;  // neighbor outside this patch
                if (labels[nbListIdx] >= 0) continue;  // already claimed
                labels[nbListIdx] = label;
                queue.add(new int[]{nbListIdx, label});
            }
        }

        // Orphan fallback — faces enclosed by saddle/crest walls in every
        // direction. Assign to the nearest seed by Euclidean distance of
        // centroid so we don't lose them; they may still sit inside one
        // of the seed regions once Welsh-Powell coloring happens.
        for (int i = 0; i < n; i++) {
            if (labels[i] >= 0) continue;
            int bestSeed = 0;
            float bestDist = Float.MAX_VALUE;
            for (int c = 0; c < k; c++) {
                Integer seedListIdx = faceToListIdx.get(seedFaceIds[c]);
                if (seedListIdx == null) continue;
                float dx = centroids[i * NUM_3]     - centroids[seedListIdx * NUM_3];
                float dy = centroids[i * NUM_3 + 1] - centroids[seedListIdx * NUM_3 + 1];
                float dz = centroids[i * NUM_3 + 2] - centroids[seedListIdx * NUM_3 + 2];
                float d = dx * dx + dy * dy + dz * dz;
                if (d < bestDist) { bestDist = d; bestSeed = c; }
            }
            labels[i] = bestSeed;
        }
        return labels;
    }

    /**
     * Variant of {@link #kmeansXyz} that accepts pre-computed seed
     * centroid positions instead of k-means++ sampling. Used by PATCH-19
     * to seed the split at the highest-Coons-error face centroids so
     * the split is informed by "where the fit fails" rather than
     * arbitrary spatial bisection.
     *
     * @param pts flat XYZ point coordinates ({@code 3 * n} entries)
     * @param n number of points
     * @param k number of clusters
     * @param seedXyz flat XYZ seed centroids ({@code 3 * k} entries)
     * @return cluster label per point in {@code [0, k)}
     */
    private static int[] kmeansXyzWithSeeds(float[] pts, int n, int k, float[] seedXyz) {
        float[] centroids = seedXyz.clone();
        int[] labels = new int[n];
        float[] newCentroids = new float[k * NUM_3];
        int[] counts = new int[k];
        for (int iter = 0; iter < NUM_30; iter++) {
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                int best = 0;
                float bestD = Float.MAX_VALUE;
                for (int c = 0; c < k; c++) {
                    float dx = pts[i * NUM_3]     - centroids[c * NUM_3];
                    float dy = pts[i * NUM_3 + 1] - centroids[c * NUM_3 + 1];
                    float dz = pts[i * NUM_3 + 2] - centroids[c * NUM_3 + 2];
                    float dd = dx * dx + dy * dy + dz * dz;
                    if (dd < bestD) { bestD = dd; best = c; }
                }
                if (labels[i] != best) { changed = true; labels[i] = best; }
            }
            if (!changed && iter > 0) break;
            Arrays.fill(newCentroids, NUM_0);
            Arrays.fill(counts, 0);
            for (int i = 0; i < n; i++) {
                int c = labels[i];
                counts[c]++;
                newCentroids[c * NUM_3]     += pts[i * NUM_3];
                newCentroids[c * NUM_3 + 1] += pts[i * NUM_3 + 1];
                newCentroids[c * NUM_3 + 2] += pts[i * NUM_3 + 2];
            }
            for (int c = 0; c < k; c++) {
                if (counts[c] == 0) continue;
                centroids[c * NUM_3]     = newCentroids[c * NUM_3]     / counts[c];
                centroids[c * NUM_3 + 1] = newCentroids[c * NUM_3 + 1] / counts[c];
                centroids[c * NUM_3 + 2] = newCentroids[c * NUM_3 + 2] / counts[c];
            }
        }
        return labels;
    }

    private static int[] kmeansXyz(float[] pts, int n, int k, long seed) {
        java.util.Random rnd = new java.util.Random(seed);
        float[] centroids = new float[k * NUM_3];
        int first = rnd.nextInt(n);
        System.arraycopy(pts, first * NUM_3, centroids, 0, NUM_3);
        float[] d2 = new float[n];
        Arrays.fill(d2, Float.MAX_VALUE);
        for (int ci = 1; ci < k; ci++) {
            double total = 0;
            for (int i = 0; i < n; i++) {
                float dx = pts[i * NUM_3]     - centroids[(ci - 1) * NUM_3];
                float dy = pts[i * NUM_3 + 1] - centroids[(ci - 1) * NUM_3 + 1];
                float dz = pts[i * NUM_3 + 2] - centroids[(ci - 1) * NUM_3 + 2];
                float dd = dx * dx + dy * dy + dz * dz;
                if (dd < d2[i]) d2[i] = dd;
                total += d2[i];
            }
            double target = rnd.nextDouble() * total;
            double acc = 0;
            int pick = n - 1;
            for (int i = 0; i < n; i++) {
                acc += d2[i];
                if (acc >= target) { pick = i; break; }
            }
            System.arraycopy(pts, pick * NUM_3, centroids, ci * NUM_3, NUM_3);
        }
        int[] labels = new int[n];
        float[] newCentroids = new float[k * NUM_3];
        int[] counts = new int[k];
        for (int iter = 0; iter < NUM_30; iter++) {
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                int best = 0;
                float bestD = Float.MAX_VALUE;
                for (int c = 0; c < k; c++) {
                    float dx = pts[i * NUM_3]     - centroids[c * NUM_3];
                    float dy = pts[i * NUM_3 + 1] - centroids[c * NUM_3 + 1];
                    float dz = pts[i * NUM_3 + 2] - centroids[c * NUM_3 + 2];
                    float dd = dx * dx + dy * dy + dz * dz;
                    if (dd < bestD) { bestD = dd; best = c; }
                }
                if (labels[i] != best) { changed = true; labels[i] = best; }
            }
            if (!changed && iter > 0) break;
            Arrays.fill(newCentroids, NUM_0);
            Arrays.fill(counts, 0);
            for (int i = 0; i < n; i++) {
                int c = labels[i];
                counts[c]++;
                newCentroids[c * NUM_3]     += pts[i * NUM_3];
                newCentroids[c * NUM_3 + 1] += pts[i * NUM_3 + 1];
                newCentroids[c * NUM_3 + 2] += pts[i * NUM_3 + 2];
            }
            for (int c = 0; c < k; c++) {
                if (counts[c] == 0) continue;
                centroids[c * NUM_3]     = newCentroids[c * NUM_3]     / counts[c];
                centroids[c * NUM_3 + 1] = newCentroids[c * NUM_3 + 1] / counts[c];
                centroids[c * NUM_3 + 2] = newCentroids[c * NUM_3 + 2] / counts[c];
            }
        }
        return labels;
    }

    /**
     * Per-stage edge sets and the final face→patch mapping, for diagnosis.
     * Edge encoding matches {@link #edgeKey(int, int)}: low-endpoint in high
     * 32 bits, high-endpoint in low 32 bits.
     *
     * <ul>
     *   <li>{@code dihedralFeatureEdges} — edges whose dihedral exceeds the
     *       adaptive feature threshold used by {@code featureCutAdjacency}.</li>
     *   <li>{@code principalFeatureEdges} — edges promoted by the per-vertex
     *       principal-curvature magnitude test (both endpoints κ₁ or κ₂ above
     *       {@code T_PRINCIPAL}).</li>
     *   <li>{@code crestEdges} — edges on a traced ridge/valley polyline from
     *       {@link CrestLineDetector}.</li>
     *   <li>{@code unionFeatureEdges} — the union of the three sets above,
     *       i.e. everything {@code featureCutAdjacency} will cut on.</li>
     *   <li>{@code patchBoundaryEdges} — edges where the two adjacent faces
     *       ended up in different final patches. Overlaying this with the
     *       three above shows which feature signals were honored by the
     *       decomposition and which were overridden downstream.</li>
     * </ul>
     */
    public static record DecompositionDiagnostics(
            PatchDecomposition decomposition,
            int[] facePatchId,
            Set<Long> dihedralFeatureEdges,
            Set<Long> principalFeatureEdges,
            Set<Long> crestEdges,
            Set<Long> saddleSeparatorEdges,
            Set<Long> unionFeatureEdges,
            Set<Long> patchBoundaryEdges,
            /**
             * Per-vertex Coons reconstruction error (world units). 0 for
             */
            float[] coonsError,
            /**
             * {@link #T_COONS_ERROR_FRAC} × mesh bounding-sphere diameter.
             *  Use as "above this value is failing" when displaying the
             *  error vector — typically feed to {@code
             *  HalfEdgeMeshRuntime.setPerVertexScalar(err, 0, 2*threshold)}
             */
            float coonsErrorThreshold,
            /**
             * PATCH-22 Phase A: Morse-Smale critical points + integral arcs
             *  computed from the signed mean-curvature field. Diagnostic-only
             *  for this phase; Phase B will build a parallel decomposer on
             */
            MorseSmaleComplex.Result morseSmale) {}

    // ---------- Step 2: edge dihedrals + vertex curvature ----------

    /**
     * Per-edge dihedral angle and the pair of faces incident to each edge,
     * plus per-face normals. Shared input to feature detection, vertex
     * curvature, and face adjacency.
     */
    public static record EdgeDihedrals(
            Map<Long, int[]> edgeFaces,
            Map<Long, Float> dihedralByEdge,
            float[] faceNormals) {}
}
