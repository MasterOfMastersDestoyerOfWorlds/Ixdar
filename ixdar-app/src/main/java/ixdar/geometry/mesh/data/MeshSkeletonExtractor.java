package ixdar.geometry.mesh.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.ArrayMesh;

/**
 * TEASAR-based skeleton extraction from a triangle mesh.
 * <ol>
 *   <li>Voxelize mesh interior via X-axis scanline even-odd fill</li>
 *   <li>Compute distance-from-boundary (DFB) field via weighted BFS</li>
 *   <li>Penalized Dijkstra from deepest interior point (medial axis root)</li>
 *   <li>Iteratively extract branches: farthest point → trace back → invalidate</li>
 *   <li>Simplify branches via Ramer-Douglas-Peucker, annotate with DFB radii</li>
 * </ol>
 */
public final class MeshSkeletonExtractor {
    public static final int NUM_26 = 26;
    public static final float NUM_0_5 = 0.5f;
    public static final float NUM_1e_6 = 1e-6f;
    public static final float NUM_1e_12 = 1e-12f;
    public static final int NUM_15 = 15;
    public static final float NUM_2_0 = 2.0f;
    public static final float NUM_1e_8 = 1e-8f;
    public static final int NUM_5 = 5;
    public static final int NUM_128 = 128;

    private static final int MAX_BRANCHES = 50;
    private static final int MIN_PATH_LENGTH = 3;
    private static final float INVALIDATION_SCALE = 0.3f;
    private static final float MAX_INVALIDATION_RADIUS = 4.0f; // voxel units
    private static final double TEASAR_EXPONENT = 1.0 / 16.0;

    // 26-connected neighbor offsets and distances
    private static final int[][] NBR;
    private static final float[] NBR_DIST;

    // Voxel states
    private static final byte EXTERIOR = 0;
    private static final byte INTERIOR = 1;
    private static final byte SURFACE = 2;
    static {
        NBR = new int[NUM_26][MIN_PATH_LENGTH];
        NBR_DIST = new float[NUM_26];
        int i = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    NBR[i] = new int[]{dx, dy, dz};
                    NBR_DIST[i] = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    i++;
                }
    }

    private final int rx, ry, rz;
    private final float vs;
    private final float ox, oy, oz;
    private final byte[] state;
    private final float[] dfb;

    private MeshSkeletonExtractor(int rx, int ry, int rz, float vs, float ox, float oy, float oz) {
        this.rx = rx; this.ry = ry; this.rz = rz;
        this.vs = vs;
        this.ox = ox; this.oy = oy; this.oz = oz;
        int n = rx * ry * rz;
        this.state = new byte[n];
        this.dfb = new float[n];
    }

    private int idx(int x, int y, int z) { return x + y * rx + z * rx * ry; }
    private boolean ok(int x, int y, int z) { return x >= 0 && x < rx && y >= 0 && y < ry && z >= 0 && z < rz; }
    private float[] toWorld(int x, int y, int z) {
        return new float[]{ox + (x + NUM_0_5) * vs, oy + (y + NUM_0_5) * vs, oz + (z + NUM_0_5) * vs};
    }

    // ───────── public entry point ─────────

    /**
     * Run the full TEASAR pipeline on {@code mesh}: voxelize, compute distance-from-boundary,
     * extract branches via penalized Dijkstra, then simplify with RDP.
     *
     * @param mesh input triangle mesh
     * @param resolution voxel-grid resolution along the longest bounding-box axis
     * @return skeleton result with branches, branch points, and root position in world space
     */
    public static SkeletonResult extract(ArrayMesh mesh, int resolution) {
        Vector3f bmin = mesh.boundsMin(new Vector3f());
        Vector3f bmax = mesh.boundsMax(new Vector3f());
        float extX = bmax.x - bmin.x, extY = bmax.y - bmin.y, extZ = bmax.z - bmin.z;
        float maxExt = Math.max(extX, Math.max(extY, extZ));
        if (maxExt < NUM_1e_6) return empty(resolution);

        float vs = maxExt / resolution;
        float pad = 2 * vs;
        float ox = bmin.x - pad, oy = bmin.y - pad, oz = bmin.z - pad;
        int rx = (int) Math.ceil((extX + 2 * pad) / vs) + 1;
        int ry = (int) Math.ceil((extY + 2 * pad) / vs) + 1;
        int rz = (int) Math.ceil((extZ + 2 * pad) / vs) + 1;

        MeshSkeletonExtractor ext = new MeshSkeletonExtractor(rx, ry, rz, vs, ox, oy, oz);
        ext.voxelizeScanline(mesh);
        ext.markSurface();
        ext.computeDFB();
        List<List<int[]>> rawPaths = ext.teasarExtract();
        return ext.buildResult(rawPaths, resolution);
    }

    // ───────── Step 1: Voxelize via X-axis scanline ─────────

    private void voxelizeScanline(ArrayMesh mesh) {
        float[] pos = mesh.copyPositions();
        int[] fi = mesh.copyFaceIndices();
        int vpf = mesh.getVertsPerFace();

        // Collect X-axis crossing values per (iy, iz) column
        @SuppressWarnings("unchecked")
        ArrayList<Float>[] crossings = new ArrayList[ry * rz];

        for (int f = 0; f < fi.length; f += vpf) {
            // Fan triangulation for quads/ngons
            for (int t = 1; t + 1 < vpf; t++) {
                int i0 = fi[f] * MIN_PATH_LENGTH, i1 = fi[f + t] * MIN_PATH_LENGTH, i2 = fi[f + t + 1] * MIN_PATH_LENGTH;
                float ay = pos[i0 + 1], az = pos[i0 + 2];
                float by = pos[i1 + 1], bz = pos[i1 + 2];
                float cy = pos[i2 + 1], cz = pos[i2 + 2];

                // YZ bounding box in voxel space
                int iyMin = Math.max(0, (int) Math.floor((Math.min(ay, Math.min(by, cy)) - oy) / vs));
                int iyMax = Math.min(ry - 1, (int) Math.floor((Math.max(ay, Math.max(by, cy)) - oy) / vs));
                int izMin = Math.max(0, (int) Math.floor((Math.min(az, Math.min(bz, cz)) - oz) / vs));
                int izMax = Math.min(rz - 1, (int) Math.floor((Math.max(az, Math.max(bz, cz)) - oz) / vs));

                // 2x2 solve: ray at (rayY, rayZ) in +X → triangle barycentric
                float e1y = by - ay, e1z = bz - az;
                float e2y = cy - ay, e2z = cz - az;
                float det = e1y * e2z - e1z * e2y;
                if (Math.abs(det) < NUM_1e_12) continue;
                float invDet = 1.0f / det;

                for (int iy = iyMin; iy <= iyMax; iy++) {
                    float rayY = oy + (iy + NUM_0_5) * vs;
                    for (int iz = izMin; iz <= izMax; iz++) {
                        float rayZ = oz + (iz + NUM_0_5) * vs;
                        float dy = rayY - ay, dz = rayZ - az;
                        float u = (dy * e2z - dz * e2y) * invDet;
                        float v = (e1y * dz - e1z * dy) * invDet;
                        if (u >= 0 && v >= 0 && u + v <= 1.0f) {
                            float xHit = (1 - u - v) * pos[i0] + u * pos[i1] + v * pos[i2];
                            int col = iy * rz + iz;
                            if (crossings[col] == null) crossings[col] = new ArrayList<>();
                            crossings[col].add(xHit);
                        }
                    }
                }
            }
        }

        // Even-odd fill: inside between crossing pairs
        for (int iy = 0; iy < ry; iy++) {
            for (int iz = 0; iz < rz; iz++) {
                int col = iy * rz + iz;
                if (crossings[col] == null || crossings[col].size() < 2) continue;
                crossings[col].sort(Float::compare);
                List<Float> cx = crossings[col];
                for (int p = 0; p + 1 < cx.size(); p += 2) {
                    int ixLo = Math.max(0, (int) Math.ceil((cx.get(p) - ox) / vs - NUM_0_5));
                    int ixHi = Math.min(rx - 1, (int) Math.floor((cx.get(p + 1) - ox) / vs - NUM_0_5));
                    for (int ix = ixLo; ix <= ixHi; ix++) {
                        state[idx(ix, iy, iz)] = INTERIOR;
                    }
                }
            }
        }
    }

    // ───────── Step 1b: Mark surface voxels ─────────

    private void markSurface() {
        int n = rx * ry * rz;
        boolean[] isSurf = new boolean[n];
        for (int z = 0; z < rz; z++) {
            for (int y = 0; y < ry; y++) {
                for (int x = 0; x < rx; x++) {
                    int i = idx(x, y, z);
                    if (state[i] != INTERIOR) continue;
                    for (int[] nb : NBR) {
                        int nx = x + nb[0], ny = y + nb[1], nz = z + nb[2];
                        if (!ok(nx, ny, nz) || state[idx(nx, ny, nz)] == EXTERIOR) {
                            isSurf[i] = true;
                            break;
                        }
                    }
                }
            }
        }
        for (int i = 0; i < n; i++) {
            if (isSurf[i]) state[i] = SURFACE;
        }
    }

    // ───────── Step 2: Distance from boundary ─────────

    private void computeDFB() {
        Arrays.fill(dfb, Float.MAX_VALUE);
        PriorityQueue<VDist> pq = new PriorityQueue<>();

        // Seed from surface voxels
        for (int z = 0; z < rz; z++)
            for (int y = 0; y < ry; y++)
                for (int x = 0; x < rx; x++) {
                    int i = idx(x, y, z);
                    if (state[i] == SURFACE) {
                        dfb[i] = 0;
                        pq.add(new VDist(i, 0));
                    }
                }

        while (!pq.isEmpty()) {
            VDist cur = pq.poll();
            if (cur.dist > dfb[cur.index]) continue;
            int ci = cur.index;
            int cx = ci % rx, cy = (ci / rx) % ry, cz = ci / (rx * ry);

            for (int ni = 0; ni < NUM_26; ni++) {
                int nx = cx + NBR[ni][0], ny = cy + NBR[ni][1], nz = cz + NBR[ni][2];
                if (!ok(nx, ny, nz)) continue;
                int nIdx = idx(nx, ny, nz);
                if (state[nIdx] != INTERIOR) continue;
                float nd = dfb[ci] + NBR_DIST[ni];
                if (nd < dfb[nIdx]) {
                    dfb[nIdx] = nd;
                    pq.add(new VDist(nIdx, nd));
                }
            }
        }
    }

    // ───────── Step 3: TEASAR path extraction ─────────

    private List<List<int[]>> teasarExtract() {
        int n = rx * ry * rz;

        // Find root: interior/surface voxel with max DFB
        int root = -1;
        float maxDfb = 0;
        for (int i = 0; i < n; i++) {
            if (state[i] != INTERIOR && state[i] != SURFACE) continue;
            if (dfb[i] != Float.MAX_VALUE && dfb[i] > maxDfb) {
                maxDfb = dfb[i];
                root = i;
            }
        }
        if (root < 0 || maxDfb < NUM_1e_6) return List.of();

        // Penalized Dijkstra from root
        float[] dist = new float[n];
        int[] parent = new int[n];
        Arrays.fill(dist, Float.MAX_VALUE);
        Arrays.fill(parent, -1);

        dist[root] = 0;
        PriorityQueue<VDist> pq = new PriorityQueue<>();
        pq.add(new VDist(root, 0));

        while (!pq.isEmpty()) {
            VDist cur = pq.poll();
            if (cur.dist > dist[cur.index]) continue;
            int ci = cur.index;
            int cx = ci % rx, cy = (ci / rx) % ry, cz = ci / (rx * ry);

            for (int ni = 0; ni < NUM_26; ni++) {
                int nx = cx + NBR[ni][0], ny = cy + NBR[ni][1], nz = cz + NBR[ni][2];
                if (!ok(nx, ny, nz)) continue;
                int nIdx = idx(nx, ny, nz);
                if (state[nIdx] == EXTERIOR) continue;

                float edgeLen = NBR_DIST[ni];
                float penalty = (float) Math.pow(maxDfb - dfb[nIdx] + 1.0, TEASAR_EXPONENT);
                float nd = dist[ci] + edgeLen * penalty;
                if (nd < dist[nIdx]) {
                    dist[nIdx] = nd;
                    parent[nIdx] = ci;
                    pq.add(new VDist(nIdx, nd));
                }
            }
        }

        // Iterative branch extraction
        boolean[] processed = new boolean[n];
        List<List<int[]>> branches = new ArrayList<>();

        for (int iter = 0; iter < MAX_BRANCHES; iter++) {
            // Find farthest unprocessed voxel
            int farthest = -1;
            float maxDist = 0;
            for (int i = 0; i < n; i++) {
                if (processed[i] || state[i] == EXTERIOR) continue;
                if (dist[i] != Float.MAX_VALUE && dist[i] > maxDist) {
                    maxDist = dist[i];
                    farthest = i;
                }
            }
            if (farthest < 0) break;

            // Trace back to root (or to an already-processed voxel)
            List<int[]> path = new ArrayList<>();
            int cur = farthest;
            while (cur != -1 && !processed[cur]) {
                int cx = cur % rx, cy = (cur / rx) % ry, cz = cur / (rx * ry);
                path.add(new int[]{cx, cy, cz, cur});
                cur = parent[cur];
            }
            // Include the connection point if it was already processed (branch point)
            if (cur != -1 && processed[cur]) {
                int cx = cur % rx, cy = (cur / rx) % ry, cz = cur / (rx * ry);
                path.add(new int[]{cx, cy, cz, cur});
            }

            if (path.size() < MIN_PATH_LENGTH) {
                for (int[] p : path) processed[p[MIN_PATH_LENGTH]] = true;
                continue;
            }

            branches.add(path);

            // Invalidate neighborhood around path
            for (int[] p : path) {
                processed[p[MIN_PATH_LENGTH]] = true;
                float radius = Math.min(MAX_INVALIDATION_RADIUS,
                        Math.max(1.0f, dfb[p[MIN_PATH_LENGTH]] * INVALIDATION_SCALE));
                int r = (int) Math.ceil(radius);
                for (int dx = -r; dx <= r; dx++)
                    for (int dy = -r; dy <= r; dy++)
                        for (int dz = -r; dz <= r; dz++) {
                            int px = p[0] + dx, py = p[1] + dy, pz = p[2] + dz;
                            if (!ok(px, py, pz)) continue;
                            if (dx * dx + dy * dy + dz * dz <= radius * radius) {
                                processed[idx(px, py, pz)] = true;
                            }
                        }
            }
        }

        return branches;
    }

    // ───────── Step 4: Post-processing ─────────

    private SkeletonResult buildResult(List<List<int[]>> rawPaths, int resolution) {
        // Find root position
        int rootIdx = -1;
        float maxDfb = 0;
        for (int i = 0; i < state.length; i++) {
            if (dfb[i] != Float.MAX_VALUE && dfb[i] > maxDfb) {
                maxDfb = dfb[i];
                rootIdx = i;
            }
        }
        float[] rootPos = rootIdx >= 0
                ? toWorld(rootIdx % rx, (rootIdx / rx) % ry, rootIdx / (rx * ry))
                : new float[MIN_PATH_LENGTH];

        List<SkeletonBranch> branches = new ArrayList<>();
        List<List<int[]>> validBranches = new ArrayList<>();
        for (int bi = 0; bi < rawPaths.size(); bi++) {
            List<int[]> path = rawPaths.get(bi);

            // Convert to world positions
            float[][] worldPos = new float[path.size()][];
            for (int i = 0; i < path.size(); i++) {
                worldPos[i] = toWorld(path.get(i)[0], path.get(i)[1], path.get(i)[2]);
            }

            // Compute raw path length and skip noise branches
            float rawLength = 0;
            for (int j = 1; j < worldPos.length; j++) rawLength += dist3(worldPos[j - 1], worldPos[j]);
            if (rawLength < vs * NUM_15) continue;  // skip branches shorter than ~15 voxels

            // RDP simplification
            List<Integer> kept = rdpSimplify(worldPos, vs * NUM_2_0);

            // Build joints
            List<SkeletonJoint> joints = new ArrayList<>();
            for (int k : kept) {
                float r = dfb[path.get(k)[MIN_PATH_LENGTH]];
                if (r == Float.MAX_VALUE) r = 0;
                joints.add(new SkeletonJoint(worldPos[k], r * vs));
            }

            // Direction and length
            float[] dir = new float[]{0, 0, 0};
            float length = 0;
            if (joints.size() >= 2) {
                float[] first = joints.get(0).position;
                float[] last = joints.get(joints.size() - 1).position;
                float dx = last[0] - first[0], dy = last[1] - first[1], dz = last[2] - first[2];
                float mag = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (mag > NUM_1e_8) { dir[0] = dx / mag; dir[1] = dy / mag; dir[2] = dz / mag; }
                for (int j = 1; j < joints.size(); j++) {
                    float[] a = joints.get(j - 1).position;
                    float[] b = joints.get(j).position;
                    float sx = b[0] - a[0], sy = b[1] - a[1], sz = b[2] - a[2];
                    length += (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
                }
            }

            // Detect parent branch by proximity (closest voxel on any earlier branch)
            int parentBranch = -1;
            if (path.size() > 0) {
                float[] connPos = worldPos[worldPos.length - 1];
                float minD = Float.MAX_VALUE;
                for (int pi = 0; pi < validBranches.size(); pi++) {
                    List<int[]> prevPath = validBranches.get(pi);
                    for (int[] pv : prevPath) {
                        float d = dist3(connPos, toWorld(pv[0], pv[1], pv[2]));
                        if (d < minD) { minD = d; parentBranch = pi; }
                    }
                }
                if (minD > vs * NUM_5) parentBranch = -1;
            }

            int branchId = validBranches.size();
            validBranches.add(path);
            branches.add(new SkeletonBranch(branchId, "branch_" + branchId, parentBranch, joints, dir, length));
        }

        // Branch points: where child branches connect to parent branches
        List<BranchPoint> branchPoints = new ArrayList<>();
        for (int bi = 0; bi < branches.size(); bi++) {
            SkeletonBranch b = branches.get(bi);
            if (b.parentBranch >= 0 && !b.joints.isEmpty()) {
                float[] bpPos = b.joints.get(b.joints.size() - 1).position;
                // Check if a branch point already exists near here
                boolean found = false;
                for (BranchPoint bp : branchPoints) {
                    if (dist3(bp.position, bpPos) < vs * MIN_PATH_LENGTH) {
                        bp.connectedBranches.add(bi);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    List<Integer> connected = new ArrayList<>();
                    connected.add(b.parentBranch);
                    connected.add(bi);
                    branchPoints.add(new BranchPoint(bpPos, connected));
                }
            }
        }

        return new SkeletonResult(resolution, vs, rootPos, branches, branchPoints);
    }

    // ───────── Utilities ─────────

    private static List<Integer> rdpSimplify(float[][] points, float epsilon) {
        if (points.length <= 2) {
            List<Integer> r = new ArrayList<>();
            for (int i = 0; i < points.length; i++) r.add(i);
            return r;
        }
        boolean[] keep = new boolean[points.length];
        keep[0] = true;
        keep[points.length - 1] = true;
        rdpRecurse(points, 0, points.length - 1, epsilon, keep);
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < points.length; i++) {
            if (keep[i]) result.add(i);
        }
        return result;
    }

    private static void rdpRecurse(float[][] pts, int lo, int hi, float eps, boolean[] keep) {
        if (hi - lo < 2) return;
        float[] a = pts[lo], b = pts[hi];
        float abx = b[0] - a[0], aby = b[1] - a[1], abz = b[2] - a[2];
        float abLen = (float) Math.sqrt(abx * abx + aby * aby + abz * abz);

        float maxDist = 0;
        int maxIdx = lo;
        for (int i = lo + 1; i < hi; i++) {
            float d;
            if (abLen < NUM_1e_8) {
                float dx = pts[i][0] - a[0], dy = pts[i][1] - a[1], dz = pts[i][2] - a[2];
                d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            } else {
                float t = ((pts[i][0] - a[0]) * abx + (pts[i][1] - a[1]) * aby + (pts[i][2] - a[2]) * abz) / (abLen * abLen);
                t = Math.max(0, Math.min(1, t));
                float px = a[0] + t * abx - pts[i][0];
                float py = a[1] + t * aby - pts[i][1];
                float pz = a[2] + t * abz - pts[i][2];
                d = (float) Math.sqrt(px * px + py * py + pz * pz);
            }
            if (d > maxDist) { maxDist = d; maxIdx = i; }
        }

        if (maxDist > eps) {
            keep[maxIdx] = true;
            rdpRecurse(pts, lo, maxIdx, eps, keep);
            rdpRecurse(pts, maxIdx, hi, eps, keep);
        }
    }

    private static float dist3(float[] a, float[] b) {
        float dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static SkeletonResult empty(int resolution) {
        return new SkeletonResult(resolution, 0, new float[MIN_PATH_LENGTH], List.of(), List.of());
    }

    /**
     * CLI test: java MeshSkeletonExtractor path/to/mesh.obj [resolution].
     *
     * @param args {@code args[0]} = obj path, optional {@code args[1]} = voxel resolution (default 128)
     * @throws Exception if mesh loading fails
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("Usage: MeshSkeletonExtractor <obj-path> [resolution]"); return; }
        int res = args.length > 1 ? Integer.parseInt(args[1]) : NUM_128;
        long t0 = System.currentTimeMillis();
        ArrayMesh mesh = MeshLoader.load(args[0]);
        System.err.printf("Loaded mesh: %d verts, %d faces%n", mesh.vertexCount(), mesh.faceCount());
        SkeletonResult result = extract(mesh, res);
        long elapsed = System.currentTimeMillis() - t0;
        System.err.printf("Extracted %d branches in %dms%n", result.branches.size(), elapsed);
        System.out.println(new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result));
    }

    // Output records
    public record SkeletonJoint(float[] position, float radius) {}
    public record SkeletonBranch(int id, String label, int parentBranch,
                                 List<SkeletonJoint> joints, float[] direction, float length) {}
    public record BranchPoint(float[] position, List<Integer> connectedBranches) {}
    public record SkeletonResult(int resolution, float voxelSize, float[] root,
                                 List<SkeletonBranch> branches, List<BranchPoint> branchPoints) {}

    private record VDist(int index, float dist) implements Comparable<VDist> {
        /**
         * Order by ascending distance for use in a min-heap.
         *
         * @param o other entry
         * @return signum of {@code this.dist - o.dist}
         */
        @Override public int compareTo(VDist o) { return Float.compare(dist, o.dist); }
    }
}
