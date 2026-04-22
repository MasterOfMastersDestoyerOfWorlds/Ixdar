package ixdar.geometry.mesh.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    // 20-colour distinguishable palette (hex without leading #).
    private static final String[] PALETTE = {
        "E84A5F", "FECEAB", "99B898", "2A363B", "FF847C",
        "6C5B7B", "355C7D", "F8B195", "C06C84", "F67280",
        "AD8CAE", "8FB1CB", "B6E0D4", "F3C77A", "D26F5C",
        "7B9ACC", "A4C3A2", "E7A3B6", "5D4E60", "C7B5D9"
    };

    private SemanticPatchDecomposer() {}

    public static PatchDecomposition decompose(ArrayMesh mesh, int resolution) {
        int nv = mesh.vertexCount();
        if (nv == 0) return new PatchDecomposition(0, List.of());

        int[] faceIdx = mesh.copyFaceIndices();
        int faceCount = faceIdx.length / 3;
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
        java.util.Set<Long> principalFeatureEdges = principalCurvatureFeatureEdges(
                ed, kappa1, kappa2, T_PRINCIPAL * (1f / meshExtent));

        // Face adjacency (full + feature-cut variants).
        int[][] adj = buildFaceAdjacency(faceIdx, faceCount, ed);
        int[][] adjCut = featureCutAdjacency(adj, faceIdx, ed, T_FEATURE_RAD, principalFeatureEdges);

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
            if (facePatchId[f] != -1) compId[f] = -2;  // concavity-owned
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
            int[][] walkAdj = pass == 0 ? adjCut : adj;
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
        int[] splitPatchId = splitByQuality(
                compactedFacePatch, compacted, faceIdx, faceCount,
                positions, vertexCurvature, adj);
        int newCompacted = 0;
        for (int pid : splitPatchId) if (pid + 1 > newCompacted) newCompacted = pid + 1;

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
            float[] centroid = new float[3];
            float curvSum = 0f;
            int curvSamples = 0;
            int vertCount = 0;
            int branchSample = -1;
            for (int i = 0; i < faces.length; i++) {
                int f = faceList.get(i);
                faces[i] = f;
                if (branchSample == -1) branchSample = faceBranches[f];
                for (int k = 0; k < 3; k++) {
                    int v = faceIdx[f * 3 + k];
                    if (!seenVert[v]) {
                        seenVert[v] = true;
                        centroid[0] += positions[v * 3];
                        centroid[1] += positions[v * 3 + 1];
                        centroid[2] += positions[v * 3 + 2];
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
                    curvSamples > 0 ? curvSum / curvSamples : 0f,
                    color));
        }

        return new PatchDecomposition(nv, out);
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
                jr[idx] = Math.max(j.radius(), 1e-4f);
                jb[idx] = b.id();
                idx++;
            }
        }
        for (int v = 0; v < nv; v++) {
            float px = positions[v * 3];
            float py = positions[v * 3 + 1];
            float pz = positions[v * 3 + 2];
            int bestBranch = jb[0];
            float bestScore = Float.MAX_VALUE;
            for (int k = 0; k < totalJoints; k++) {
                float dx = px - jx[k];
                float dy = py - jy[k];
                float dz = pz - jz[k];
                float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                float slack = Math.max(0f, d - jr[k]);
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

    public static EdgeDihedrals computeEdgeDihedrals(ArrayMesh mesh) {
        int[] faceIdx = mesh.copyFaceIndices();
        int faceCount = faceIdx.length / 3;
        float[] positions = mesh.copyPositions();
        float[] faceN = new float[faceCount * 3];
        for (int f = 0; f < faceCount; f++) {
            int a = faceIdx[f * 3] * 3;
            int b = faceIdx[f * 3 + 1] * 3;
            int c = faceIdx[f * 3 + 2] * 3;
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
            if (len > 1e-20f) {
                faceN[f * 3] = nx / len;
                faceN[f * 3 + 1] = ny / len;
                faceN[f * 3 + 2] = nz / len;
            }
        }
        Map<Long, int[]> edgeFaces = new HashMap<>();
        for (int f = 0; f < faceCount; f++) {
            for (int e = 0; e < 3; e++) {
                int u = faceIdx[f * 3 + e];
                int v = faceIdx[f * 3 + (e + 1) % 3];
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
            float dot = faceN[f1 * 3] * faceN[f2 * 3]
                    + faceN[f1 * 3 + 1] * faceN[f2 * 3 + 1]
                    + faceN[f1 * 3 + 2] * faceN[f2 * 3 + 2];
            dot = Math.max(-1f, Math.min(1f, dot));
            float dihedral = (float) Math.acos(dot);
            dihedrals.put(e.getKey(), dihedral);
        }
        return new EdgeDihedrals(edgeFaces, dihedrals, faceN);
    }

    public static float[] vertexCurvatureFrom(EdgeDihedrals ed, int vertexCount) {
        float[] accum = new float[vertexCount];
        int[] count = new int[vertexCount];
        for (Map.Entry<Long, Float> e : ed.dihedralByEdge().entrySet()) {
            long key = e.getKey();
            int u = (int) (key >> 32);
            int v = (int) (key & 0xffffffffL);
            float d = e.getValue();
            accum[u] += d;
            accum[v] += d;
            count[u]++;
            count[v]++;
        }
        float[] out = new float[vertexCount];
        for (int i = 0; i < vertexCount; i++) {
            out[i] = count[i] > 0 ? accum[i] / count[i] : 0f;
        }
        return out;
    }

    /**
     * Backward-compatible entry point: produces per-vertex mean dihedral,
     * same shape and values as the pre-refactor computeVertexCurvature.
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
     */
    private static float[] computeAngleDefect(ArrayMesh mesh, float[] positions, int[] faceIdx) {
        int nv = mesh.vertexCount();
        int faceCount = faceIdx.length / 3;
        float[] defect = new float[nv];
        Arrays.fill(defect, (float) (2 * Math.PI));
        for (int f = 0; f < faceCount; f++) {
            int v0 = faceIdx[f * 3];
            int v1 = faceIdx[f * 3 + 1];
            int v2 = faceIdx[f * 3 + 2];
            defect[v0] -= triangleAngle(positions, v0, v1, v2);
            defect[v1] -= triangleAngle(positions, v1, v2, v0);
            defect[v2] -= triangleAngle(positions, v2, v0, v1);
        }
        return defect;
    }

    private static float triangleAngle(float[] positions, int at, int toB, int toC) {
        float ax = positions[toB * 3] - positions[at * 3];
        float ay = positions[toB * 3 + 1] - positions[at * 3 + 1];
        float az = positions[toB * 3 + 2] - positions[at * 3 + 2];
        float bx = positions[toC * 3] - positions[at * 3];
        float by = positions[toC * 3 + 1] - positions[at * 3 + 1];
        float bz = positions[toC * 3 + 2] - positions[at * 3 + 2];
        float la = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        float lb = (float) Math.sqrt(bx * bx + by * by + bz * bz);
        if (la < 1e-20f || lb < 1e-20f) return 0f;
        float dot = (ax * bx + ay * by + az * bz) / (la * lb);
        dot = Math.max(-1f, Math.min(1f, dot));
        return (float) Math.acos(dot);
    }

    private static float computeMeshExtent(float[] positions) {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < positions.length; i += 3) {
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
     */
    private static float[] computeBarycentricAreas(ArrayMesh mesh, float[] positions, int[] faceIdx) {
        int nv = mesh.vertexCount();
        int faceCount = faceIdx.length / 3;
        float[] A = new float[nv];
        for (int f = 0; f < faceCount; f++) {
            int a = faceIdx[f * 3], b = faceIdx[f * 3 + 1], c = faceIdx[f * 3 + 2];
            float tri = (float) triangleArea(positions, a, b, c);
            A[a] += tri / 3f;
            A[b] += tri / 3f;
            A[c] += tri / 3f;
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
     */
    private static float[] computeMeanCurvature(
            ArrayMesh mesh, float[] positions, int[] faceIdx,
            EdgeDihedrals ed, float[] barycentricArea) {
        int nv = mesh.vertexCount();
        int faceCount = faceIdx.length / 3;
        // Per-vertex mean-curvature vector components.
        float[] hx = new float[nv];
        float[] hy = new float[nv];
        float[] hz = new float[nv];
        // For each triangle, contribute (cot α) · (p_j - p_i) to the accumulator
        // for each of its three edges, with α being the angle at the third
        // vertex. Each edge collects contributions from both incident triangles.
        for (int f = 0; f < faceCount; f++) {
            int a = faceIdx[f * 3], b = faceIdx[f * 3 + 1], c = faceIdx[f * 3 + 2];
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
            float Av = Math.max(barycentricArea[v], 1e-12f);
            float nx = hx[v] / (2f * Av);
            float ny = hy[v] / (2f * Av);
            float nz = hz[v] / (2f * Av);
            float magnitude = (float) Math.sqrt(nx * nx + ny * ny + nz * nz) * 0.5f;
            // Sign via dot product with vertex normal: positive = convex.
            float dot = nx * vertexNormals[v * 3]
                      + ny * vertexNormals[v * 3 + 1]
                      + nz * vertexNormals[v * 3 + 2];
            meanH[v] = dot > 0 ? magnitude : -magnitude;
        }
        return meanH;
    }

    private static void accumCot(float[] hx, float[] hy, float[] hz,
                                 float[] positions, int u, int w, float cotVal) {
        float dx = positions[w * 3]     - positions[u * 3];
        float dy = positions[w * 3 + 1] - positions[u * 3 + 1];
        float dz = positions[w * 3 + 2] - positions[u * 3 + 2];
        // (p_w - p_u) contributes to u's accum, (p_u - p_w) to w's.
        hx[u] += cotVal * dx;
        hy[u] += cotVal * dy;
        hz[u] += cotVal * dz;
        hx[w] -= cotVal * dx;
        hy[w] -= cotVal * dy;
        hz[w] -= cotVal * dz;
    }

    private static float cotAtVertex(float[] positions, int at, int b, int c) {
        float ax = positions[b * 3]     - positions[at * 3];
        float ay = positions[b * 3 + 1] - positions[at * 3 + 1];
        float az = positions[b * 3 + 2] - positions[at * 3 + 2];
        float bx = positions[c * 3]     - positions[at * 3];
        float by = positions[c * 3 + 1] - positions[at * 3 + 1];
        float bz = positions[c * 3 + 2] - positions[at * 3 + 2];
        float dot = ax * bx + ay * by + az * bz;
        float crossX = ay * bz - az * by;
        float crossY = az * bx - ax * bz;
        float crossZ = ax * by - ay * bx;
        float crossLen = (float) Math.sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ);
        if (crossLen < 1e-20f) return 0f;
        return dot / crossLen;
    }

    private static float[] averageFaceNormalsPerVertex(ArrayMesh mesh, int[] faceIdx, float[] faceNormals) {
        int nv = mesh.vertexCount();
        int faceCount = faceIdx.length / 3;
        float[] out = new float[nv * 3];
        for (int f = 0; f < faceCount; f++) {
            for (int k = 0; k < 3; k++) {
                int v = faceIdx[f * 3 + k];
                out[v * 3]     += faceNormals[f * 3];
                out[v * 3 + 1] += faceNormals[f * 3 + 1];
                out[v * 3 + 2] += faceNormals[f * 3 + 2];
            }
        }
        for (int v = 0; v < nv; v++) {
            float nx = out[v * 3], ny = out[v * 3 + 1], nz = out[v * 3 + 2];
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1e-20f) {
                out[v * 3]     = nx / len;
                out[v * 3 + 1] = ny / len;
                out[v * 3 + 2] = nz / len;
            }
        }
        return out;
    }

    /**
     * Per-vertex Gaussian curvature K = (2π - Σθ)/A_v, using the same
     * barycentric area normalization as mean curvature so the κ₁,₂ formula
     * behaves dimensionally correctly.
     */
    private static float[] computeGaussianCurvature(
            ArrayMesh mesh, float[] positions, int[] faceIdx, float[] barycentricArea) {
        float[] defect = computeAngleDefect(mesh, positions, faceIdx);
        int nv = mesh.vertexCount();
        float[] K = new float[nv];
        for (int v = 0; v < nv; v++) {
            float Av = Math.max(barycentricArea[v], 1e-12f);
            K[v] = defect[v] / Av;
        }
        return K;
    }

    /**
     * Principal curvatures from mean H and Gaussian K:
     * κ₁,₂ = H ± √(max(0, H² - K)). Returns [kappa1[], kappa2[]] with
     * κ₁ ≥ κ₂ pointwise.
     */
    private static float[][] computePrincipalCurvatures(float[] H, float[] K) {
        int nv = H.length;
        float[] k1 = new float[nv];
        float[] k2 = new float[nv];
        for (int v = 0; v < nv; v++) {
            float discriminant = Math.max(0f, H[v] * H[v] - K[v]);
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
     */
    private static java.util.Set<Long> principalCurvatureFeatureEdges(
            EdgeDihedrals ed, float[] kappa1, float[] kappa2, float threshold) {
        java.util.Set<Long> out = new java.util.HashSet<>();
        for (Map.Entry<Long, int[]> e : ed.edgeFaces().entrySet()) {
            long key = e.getKey();
            int u = (int) (key >> 32);
            int v = (int) (key & 0xffffffffL);
            boolean ridge = kappa1[u] > threshold && kappa1[v] > threshold;
            boolean valley = kappa2[u] < -threshold && kappa2[v] < -threshold;
            if (ridge || valley) out.add(key);
        }
        return out;
    }

    /**
     * Connected components of strongly-concave vertices via mesh adjacency.
     * Components smaller than {@link #MIN_CONCAVITY_VERTS} are discarded
     * (set to -1); survivors get a unique non-negative component id.
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
            int a = faceIdx[f * 3];
            int b = faceIdx[f * 3 + 1];
            int c = faceIdx[f * 3 + 2];
            degree[a] += 2; degree[b] += 2; degree[c] += 2;
        }
        int[] offsets = new int[vertexCount + 1];
        for (int i = 0; i < vertexCount; i++) offsets[i + 1] = offsets[i] + degree[i];
        int[] neigh = new int[offsets[vertexCount]];
        int[] cursor = new int[vertexCount];
        for (int f = 0; f < faceCount; f++) {
            int a = faceIdx[f * 3];
            int b = faceIdx[f * 3 + 1];
            int c = faceIdx[f * 3 + 2];
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
     */
    private static int[] expandConcavityToFaces(
            int[] vertexCC, int[] faceIdx, int faceCount, int[][] adj, int ringExpand) {
        int[] faceCC = new int[faceCount];
        Arrays.fill(faceCC, -1);
        for (int f = 0; f < faceCount; f++) {
            int a = vertexCC[faceIdx[f * 3]];
            int b = vertexCC[faceIdx[f * 3 + 1]];
            int c = vertexCC[faceIdx[f * 3 + 2]];
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
     */
    static int[][] buildFaceAdjacency(int[] faceIdx, int faceCount, EdgeDihedrals ed) {
        int[][] adj = new int[faceCount][3];
        for (int f = 0; f < faceCount; f++) {
            Arrays.fill(adj[f], -1);
        }
        for (Map.Entry<Long, int[]> e : ed.edgeFaces().entrySet()) {
            int[] pair = e.getValue();
            if (pair[1] == -1) continue;
            long key = e.getKey();
            int u = (int) (key >> 32);
            int v = (int) (key & 0xffffffffL);
            attachNeighbour(adj, faceIdx, pair[0], pair[1], u, v);
            attachNeighbour(adj, faceIdx, pair[1], pair[0], u, v);
        }
        return adj;
    }

    private static void attachNeighbour(int[][] adj, int[] faceIdx, int f, int neighbour, int u, int v) {
        for (int e = 0; e < 3; e++) {
            int a = faceIdx[f * 3 + e];
            int b = faceIdx[f * 3 + (e + 1) % 3];
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
     */
    private static int[][] featureCutAdjacency(
            int[][] adj, int[] faceIdx, EdgeDihedrals ed, float thresholdRad,
            java.util.Set<Long> principalFeatureEdges) {
        int faceCount = adj.length;
        int[][] out = new int[faceCount][3];
        for (int f = 0; f < faceCount; f++) {
            for (int e = 0; e < 3; e++) {
                int nb = adj[f][e];
                if (nb == -1) {
                    out[f][e] = -1;
                    continue;
                }
                int u = faceIdx[f * 3 + e];
                int v = faceIdx[f * 3 + (e + 1) % 3];
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
        return u < v ? ((long) u << 32) | (v & 0xffffffffL) : ((long) v << 32) | (u & 0xffffffffL);
    }

    private static int faceBranch(int f, int[] faceIdx, int[] vertexBranchId) {
        int a = vertexBranchId[faceIdx[f * 3]];
        int b = vertexBranchId[faceIdx[f * 3 + 1]];
        int c = vertexBranchId[faceIdx[f * 3 + 2]];
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
     */
    private static int[] splitByQuality(
            int[] facePatch, int patchCount, int[] faceIdx, int faceCount,
            float[] positions, float[] vertexCurvature, int[][] adj) {
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
            bs.set(faceIdx[f * 3]);
            bs.set(faceIdx[f * 3 + 1]);
            bs.set(faceIdx[f * 3 + 2]);
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

            // Pass criteria: a patch is "done" when ALL of these agree.
            // 3..MAX_SIDES_BEFORE_SPLIT covers triangles through mildly
            // irregular Coons candidates.
            boolean flat       = curvStddev <= T_FLAT;
            boolean goodSides  = sides >= 3 && sides <= MAX_SIDES_BEFORE_SPLIT;
            boolean compact    = isoRatio >= T_ISO_RATIO;
            boolean withinSize = vertCount <= HARD_MAX_PATCH_VERTS;

            if (flat && goodSides && compact && withinSize) continue;

            // Split. k is picked from whichever metric is loudest, then
            // capped at MAX_K_PER_SPLIT so a pathological patch can't
            // balloon into 20 sub-patches in one pass.
            int kBySides = sides > MAX_SIDES_BEFORE_SPLIT
                    ? (int) Math.ceil(sides / (double) IDEAL_SIDES)
                    : 2;
            int kBySize  = vertCount > HARD_MAX_PATCH_VERTS
                    ? (int) Math.ceil(vertCount / 2500.0)
                    : 2;
            int k = Math.max(2, Math.max(kBySides, kBySize));
            k = Math.min(k, MAX_K_PER_SPLIT);
            if (k < 2) continue;

            // Collect face centroids and positional k-means.
            int n = faces.size();
            float[] centroids = new float[n * 3];
            for (int i = 0; i < n; i++) {
                int f = faces.get(i);
                int a = faceIdx[f * 3], b = faceIdx[f * 3 + 1], c = faceIdx[f * 3 + 2];
                centroids[i * 3]     = (positions[a * 3]     + positions[b * 3]     + positions[c * 3])     / 3f;
                centroids[i * 3 + 1] = (positions[a * 3 + 1] + positions[b * 3 + 1] + positions[c * 3 + 1]) / 3f;
                centroids[i * 3 + 2] = (positions[a * 3 + 2] + positions[b * 3 + 2] + positions[c * 3 + 2]) / 3f;
            }
            int[] labels = kmeansXyz(centroids, n, k, 0x53D5L ^ pid);
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
        if (n == 0) return 0f;
        double mean = sum / n;
        double variance = Math.max(0.0, sumSq / n - mean * mean);
        return (float) Math.sqrt(variance);
    }

    /**
     * Counts boundary vertices of {@code patchId} where the two incident
     * boundary edges turn by more than {@link #T_CORNER_RAD}. Works by
     * collecting each boundary vertex's two incident boundary edges and
     * measuring the angle between their outgoing direction vectors.
     */
    private static int boundarySideCount(
            List<Integer> faces, int[] facePatch, int patchId,
            int[] faceIdx, int[][] adj, float[] positions) {
        // For each vertex that lies on the patch boundary, remember its two
        // boundary-edge endpoints (the other vertex of each boundary edge).
        Map<Integer, int[]> neighbours = new HashMap<>();
        for (int f : faces) {
            for (int e = 0; e < 3; e++) {
                int nb = adj[f][e];
                if (nb >= 0 && facePatch[nb] == patchId) continue;  // interior edge
                int u = faceIdx[f * 3 + e];
                int v = faceIdx[f * 3 + (e + 1) % 3];
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
            dot = Math.max(-1f, Math.min(1f, dot));
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
        float dx = positions[to * 3] - positions[from * 3];
        float dy = positions[to * 3 + 1] - positions[from * 3 + 1];
        float dz = positions[to * 3 + 2] - positions[from * 3 + 2];
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-20f) return new float[]{0, 0, 0};
        return new float[]{dx / len, dy / len, dz / len};
    }

    /**
     * Isoperimetric ratio: 4π·area / perimeter². 1.0 for a circle, lower
     * values indicate elongated or concave patches. Clamped to [0, 1].
     */
    private static float isoperimetricRatio(
            List<Integer> faces, int[] facePatch, int patchId,
            int[] faceIdx, int[][] adj, float[] positions) {
        double area = 0, perimeter = 0;
        for (int f : faces) {
            area += triangleArea(positions, faceIdx[f * 3], faceIdx[f * 3 + 1], faceIdx[f * 3 + 2]);
            for (int e = 0; e < 3; e++) {
                int nb = adj[f][e];
                if (nb >= 0 && facePatch[nb] == patchId) continue;
                int u = faceIdx[f * 3 + e];
                int v = faceIdx[f * 3 + (e + 1) % 3];
                double dx = positions[v * 3] - positions[u * 3];
                double dy = positions[v * 3 + 1] - positions[u * 3 + 1];
                double dz = positions[v * 3 + 2] - positions[u * 3 + 2];
                perimeter += Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
        }
        if (perimeter <= 0) return 1f;
        double ratio = 4 * Math.PI * area / (perimeter * perimeter);
        return (float) Math.max(0, Math.min(1, ratio));
    }

    private static double triangleArea(float[] positions, int a, int b, int c) {
        double ax = positions[b * 3]     - positions[a * 3];
        double ay = positions[b * 3 + 1] - positions[a * 3 + 1];
        double az = positions[b * 3 + 2] - positions[a * 3 + 2];
        double bx = positions[c * 3]     - positions[a * 3];
        double by = positions[c * 3 + 1] - positions[a * 3 + 1];
        double bz = positions[c * 3 + 2] - positions[a * 3 + 2];
        double cx = ay * bz - az * by;
        double cy = az * bx - ax * bz;
        double cz = ax * by - ay * bx;
        return 0.5 * Math.sqrt(cx * cx + cy * cy + cz * cz);
    }

    private static int[] kmeansXyz(float[] pts, int n, int k, long seed) {
        java.util.Random rnd = new java.util.Random(seed);
        float[] centroids = new float[k * 3];
        int first = rnd.nextInt(n);
        System.arraycopy(pts, first * 3, centroids, 0, 3);
        float[] d2 = new float[n];
        Arrays.fill(d2, Float.MAX_VALUE);
        for (int ci = 1; ci < k; ci++) {
            double total = 0;
            for (int i = 0; i < n; i++) {
                float dx = pts[i * 3]     - centroids[(ci - 1) * 3];
                float dy = pts[i * 3 + 1] - centroids[(ci - 1) * 3 + 1];
                float dz = pts[i * 3 + 2] - centroids[(ci - 1) * 3 + 2];
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
            System.arraycopy(pts, pick * 3, centroids, ci * 3, 3);
        }
        int[] labels = new int[n];
        float[] newCentroids = new float[k * 3];
        int[] counts = new int[k];
        for (int iter = 0; iter < 30; iter++) {
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                int best = 0;
                float bestD = Float.MAX_VALUE;
                for (int c = 0; c < k; c++) {
                    float dx = pts[i * 3]     - centroids[c * 3];
                    float dy = pts[i * 3 + 1] - centroids[c * 3 + 1];
                    float dz = pts[i * 3 + 2] - centroids[c * 3 + 2];
                    float dd = dx * dx + dy * dy + dz * dz;
                    if (dd < bestD) { bestD = dd; best = c; }
                }
                if (labels[i] != best) { changed = true; labels[i] = best; }
            }
            if (!changed && iter > 0) break;
            Arrays.fill(newCentroids, 0f);
            Arrays.fill(counts, 0);
            for (int i = 0; i < n; i++) {
                int c = labels[i];
                counts[c]++;
                newCentroids[c * 3]     += pts[i * 3];
                newCentroids[c * 3 + 1] += pts[i * 3 + 1];
                newCentroids[c * 3 + 2] += pts[i * 3 + 2];
            }
            for (int c = 0; c < k; c++) {
                if (counts[c] == 0) continue;
                centroids[c * 3]     = newCentroids[c * 3]     / counts[c];
                centroids[c * 3 + 1] = newCentroids[c * 3 + 1] / counts[c];
                centroids[c * 3 + 2] = newCentroids[c * 3 + 2] / counts[c];
            }
        }
        return labels;
    }
}
