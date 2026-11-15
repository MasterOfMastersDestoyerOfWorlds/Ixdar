package ixdar.geometry.mesh.data.analysis.skeleton;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.bundle.CurveGeometry;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.TriangulationRule;
import ixdar.numeric.graph.Dijkstra;

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
    public static final int NEIGHBOR_COUNT = 26;
    public static final float EPSILON = 1e-6f;
    public static final float DET_EPSILON = 1e-12f;
    public static final int MIN_BRANCH_VOXELS = 15;
    public static final float RDP_EPSILON_VOXELS = 2.0f;
    public static final float ZERO_LENGTH_EPSILON = 1e-8f;
    public static final int MAX_PARENT_DISTANCE_VOXELS = 5;
    public static final int DEFAULT_RESOLUTION = 128;

    public static final int DEFAULT_BRANCH_BUDGET = 50;

    public static final String RADIUS = "radius";

    private static final int MIN_PATH_LENGTH = 3;
    private static final float INVALIDATION_SCALE = 0.3f;
    private static final float MAX_INVALIDATION_RADIUS = 4.0f; // voxel units
    private static final double TEASAR_EXPONENT = 1.0 / 16.0;

    private static final int[][] NBR;
    private static final float[] NBR_DIST;

    private static final byte EXTERIOR = 0;
    private static final byte INTERIOR = 1;
    private static final byte SURFACE = 2;
    static {
        NBR = new int[NEIGHBOR_COUNT][MIN_PATH_LENGTH];
        NBR_DIST = new float[NEIGHBOR_COUNT];
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
    private Vector3f toWorld(int x, int y, int z) {
        return new Vector3f(ox + (x + 0.5f) * vs, oy + (y + 0.5f) * vs, oz + (z + 0.5f) * vs);
    }


    /**
     * Run the full TEASAR pipeline on {@code mesh}: voxelize, compute distance-from-boundary,
     * extract branches via penalized Dijkstra, then simplify with RDP.
     *
     * @param mesh input triangle mesh
     * @param resolution voxel-grid resolution along the longest bounding-box axis
     * @return skeleton result with branches, branch points, and root position in world space
     */
    public static SkeletonResult extract(MeshTopology mesh, int resolution) {
        return extract(mesh, resolution, DEFAULT_BRANCH_BUDGET);
    }

    /**
     * Run the full TEASAR pipeline, spending at most {@code branchBudget} extraction rounds.
     *
     * @param mesh input triangle mesh
     * @param resolution voxel-grid resolution along the longest bounding-box axis
     * @param branchBudget rounds of farthest-point extraction to allow; see
     *                     {@link #DEFAULT_BRANCH_BUDGET} for why this exceeds the branch count
     * @return skeleton result with branches, branch points, and root position in world space
     */
    public static SkeletonResult extract(MeshTopology mesh, int resolution, int branchBudget) {
        Vector3f bmin = mesh.boundsMin(new Vector3f());
        Vector3f bmax = mesh.boundsMax(new Vector3f());
        float extX = bmax.x - bmin.x, extY = bmax.y - bmin.y, extZ = bmax.z - bmin.z;
        float maxExt = Math.max(extX, Math.max(extY, extZ));
        if (maxExt < EPSILON) return new SkeletonResult(resolution, 0, new Vector3f(), List.of(), List.of());

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
        List<List<int[]>> rawPaths = ext.teasarExtract(branchBudget);
        return ext.buildResult(rawPaths, resolution);
    }


    private void voxelizeScanline(MeshTopology mesh) {
        float[] pos = new float[mesh.vertexIdBound() * MIN_PATH_LENGTH];
        Vector3f position = new Vector3f();
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            int vertexId = mesh.vertexIdAt(activeVertex);
            mesh.vertexPosition(vertexId, position);
            PackedPoints.store(pos, vertexId * MIN_PATH_LENGTH, position);
        }

        @SuppressWarnings("unchecked")
        ArrayList<Float>[] crossings = new ArrayList[ry * rz];

        int[] triangles = mesh.triangulate(TriangulationRule.FAN, null);
        for (int triangleCorner = 0; triangleCorner < triangles.length; triangleCorner += 3) {
            int i0 = triangles[triangleCorner] * MIN_PATH_LENGTH;
            int i1 = triangles[triangleCorner + 1] * MIN_PATH_LENGTH;
            int i2 = triangles[triangleCorner + 2] * MIN_PATH_LENGTH;
            float ay = pos[i0 + 1], az = pos[i0 + 2];
            float by = pos[i1 + 1], bz = pos[i1 + 2];
            float cy = pos[i2 + 1], cz = pos[i2 + 2];

            int iyMin = Math.max(0, (int) Math.floor((Math.min(ay, Math.min(by, cy)) - oy) / vs));
            int iyMax = Math.min(ry - 1, (int) Math.floor((Math.max(ay, Math.max(by, cy)) - oy) / vs));
            int izMin = Math.max(0, (int) Math.floor((Math.min(az, Math.min(bz, cz)) - oz) / vs));
            int izMax = Math.min(rz - 1, (int) Math.floor((Math.max(az, Math.max(bz, cz)) - oz) / vs));

            float e1y = by - ay, e1z = bz - az;
            float e2y = cy - ay, e2z = cz - az;
            float det = e1y * e2z - e1z * e2y;
            if (Math.abs(det) < DET_EPSILON) continue;
            float invDet = 1.0f / det;

            for (int iy = iyMin; iy <= iyMax; iy++) {
                float rayY = oy + (iy + 0.5f) * vs;
                for (int iz = izMin; iz <= izMax; iz++) {
                    float rayZ = oz + (iz + 0.5f) * vs;
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

        for (int iy = 0; iy < ry; iy++) {
            for (int iz = 0; iz < rz; iz++) {
                int col = iy * rz + iz;
                if (crossings[col] == null || crossings[col].size() < 2) continue;
                crossings[col].sort(Float::compare);
                List<Float> cx = crossings[col];
                for (int p = 0; p + 1 < cx.size(); p += 2) {
                    int ixLo = Math.max(0, (int) Math.ceil((cx.get(p) - ox) / vs - 0.5f));
                    int ixHi = Math.min(rx - 1, (int) Math.floor((cx.get(p + 1) - ox) / vs - 0.5f));
                    for (int ix = ixLo; ix <= ixHi; ix++) {
                        state[idx(ix, iy, iz)] = INTERIOR;
                    }
                }
            }
        }
    }


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


    private void computeDFB() {
        int n = rx * ry * rz;
        Dijkstra walk = new Dijkstra();
        walk.start(n);

        for (int z = 0; z < rz; z++)
            for (int y = 0; y < ry; y++)
                for (int x = 0; x < rx; x++) {
                    int i = idx(x, y, z);
                    if (state[i] == SURFACE) {
                        walk.relax(i, 0f, -1);
                    }
                }

        for (int ci = walk.settle(); ci >= 0; ci = walk.settle()) {
            float here = (float) walk.distance(ci);
            int cx = ci % rx, cy = (ci / rx) % ry, cz = ci / (rx * ry);

            for (int ni = 0; ni < NEIGHBOR_COUNT; ni++) {
                int nx = cx + NBR[ni][0], ny = cy + NBR[ni][1], nz = cz + NBR[ni][2];
                if (!ok(nx, ny, nz)) continue;
                int nIdx = idx(nx, ny, nz);
                if (state[nIdx] != INTERIOR) continue;
                walk.relax(nIdx, here + NBR_DIST[ni], ci);
            }
        }
        for (int i = 0; i < n; i++) {
            dfb[i] = walk.isReached(i) ? (float) walk.distance(i) : Float.MAX_VALUE;
        }
    }


    private List<List<int[]>> teasarExtract(int branchBudget) {
        int n = rx * ry * rz;

        int root = -1;
        float maxDfb = 0;
        for (int i = 0; i < n; i++) {
            if (state[i] != INTERIOR && state[i] != SURFACE) continue;
            if (dfb[i] != Float.MAX_VALUE && dfb[i] > maxDfb) {
                maxDfb = dfb[i];
                root = i;
            }
        }
        if (root < 0 || maxDfb < EPSILON) return List.of();

        Dijkstra walk = new Dijkstra();
        walk.start(n);
        walk.relax(root, 0f, -1);
        for (int ci = walk.settle(); ci >= 0; ci = walk.settle()) {
            float here = (float) walk.distance(ci);
            int cx = ci % rx, cy = (ci / rx) % ry, cz = ci / (rx * ry);

            for (int ni = 0; ni < NEIGHBOR_COUNT; ni++) {
                int nx = cx + NBR[ni][0], ny = cy + NBR[ni][1], nz = cz + NBR[ni][2];
                if (!ok(nx, ny, nz)) continue;
                int nIdx = idx(nx, ny, nz);
                if (state[nIdx] == EXTERIOR) continue;

                float edgeLen = NBR_DIST[ni];
                float penalty = (float) Math.pow(maxDfb - dfb[nIdx] + 1.0, TEASAR_EXPONENT);
                walk.relax(nIdx, here + edgeLen * penalty, ci);
            }
        }
        float[] dist = new float[n];
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            dist[i] = walk.isReached(i) ? (float) walk.distance(i) : Float.MAX_VALUE;
            parent[i] = walk.via(i);
        }

        boolean[] processed = new boolean[n];
        List<List<int[]>> branches = new ArrayList<>();

        for (int iter = 0; iter < branchBudget; iter++) {
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

            List<int[]> path = new ArrayList<>();
            int cur = farthest;
            while (cur != -1 && !processed[cur]) {
                int cx = cur % rx, cy = (cur / rx) % ry, cz = cur / (rx * ry);
                path.add(new int[]{cx, cy, cz, cur});
                cur = parent[cur];
            }
            if (cur != -1 && processed[cur]) {
                int cx = cur % rx, cy = (cur / rx) % ry, cz = cur / (rx * ry);
                path.add(new int[]{cx, cy, cz, cur});
            }

            if (path.size() < MIN_PATH_LENGTH) {
                for (int[] p : path) processed[p[MIN_PATH_LENGTH]] = true;
                continue;
            }

            branches.add(path);

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


    private SkeletonResult buildResult(List<List<int[]>> rawPaths, int resolution) {
        int rootIdx = -1;
        float maxDfb = 0;
        for (int i = 0; i < state.length; i++) {
            if (dfb[i] != Float.MAX_VALUE && dfb[i] > maxDfb) {
                maxDfb = dfb[i];
                rootIdx = i;
            }
        }
        Vector3f rootPos = rootIdx >= 0
                ? toWorld(rootIdx % rx, (rootIdx / rx) % ry, rootIdx / (rx * ry))
                : new Vector3f();

        List<SkeletonBranch> branches = new ArrayList<>();
        List<List<int[]>> validBranches = new ArrayList<>();
        for (int bi = 0; bi < rawPaths.size(); bi++) {
            List<int[]> path = rawPaths.get(bi);

            Vector3f[] worldPos = new Vector3f[path.size()];
            for (int i = 0; i < path.size(); i++) {
                worldPos[i] = toWorld(path.get(i)[0], path.get(i)[1], path.get(i)[2]);
            }

            float rawLength = 0;
            for (int j = 1; j < worldPos.length; j++) rawLength += worldPos[j - 1].distance(worldPos[j]);
            if (rawLength < vs * MIN_BRANCH_VOXELS) continue;  // skip branches shorter than ~15 voxels

            float[] packedPath = new float[worldPos.length * 3];
            float[] radius = new float[worldPos.length];
            for (int i = 0; i < worldPos.length; i++) {
                PackedPoints.store(packedPath, i * 3, worldPos[i]);
                float r = dfb[path.get(i)[MIN_PATH_LENGTH]];
                radius[i] = (r == Float.MAX_VALUE ? 0 : r) * vs;
            }
            CurveGeometry simplified = new CurveGeometry(packedPath, new int[] { 0, worldPos.length },
                    new boolean[1], Map.of(RADIUS, radius), Map.of()).simplified(vs * RDP_EPSILON_VOXELS);

            List<SkeletonJoint> joints = new ArrayList<>();
            for (int k = 0; k < simplified.pointCount(); k++) {
                joints.add(new SkeletonJoint(PackedPoints.at(simplified.positions, k * 3),
                        simplified.pointAttributes.get(RADIUS)[k]));
            }

            Vector3f dir = new Vector3f();
            float length = 0;
            if (joints.size() >= 2) {
                dir.set(joints.get(joints.size() - 1).position).sub(joints.get(0).position);
                float mag = dir.length();
                if (mag > ZERO_LENGTH_EPSILON) {
                    dir.div(mag);
                } else {
                    dir.zero();
                }
                for (int j = 1; j < joints.size(); j++) {
                    length += joints.get(j - 1).position.distance(joints.get(j).position);
                }
            }

            int parentBranch = -1;
            if (path.size() > 0) {
                Vector3f connPos = worldPos[worldPos.length - 1];
                float minD = Float.MAX_VALUE;
                for (int pi = 0; pi < validBranches.size(); pi++) {
                    List<int[]> prevPath = validBranches.get(pi);
                    for (int[] pv : prevPath) {
                        float d = connPos.distance(toWorld(pv[0], pv[1], pv[2]));
                        if (d < minD) { minD = d; parentBranch = pi; }
                    }
                }
                if (minD > vs * MAX_PARENT_DISTANCE_VOXELS) parentBranch = -1;
            }

            int branchId = validBranches.size();
            validBranches.add(path);
            branches.add(new SkeletonBranch(branchId, "branch_" + branchId, parentBranch, joints, dir, length));
        }

        List<BranchPoint> branchPoints = new ArrayList<>();
        for (int bi = 0; bi < branches.size(); bi++) {
            SkeletonBranch b = branches.get(bi);
            if (b.parentBranch >= 0 && !b.joints.isEmpty()) {
                Vector3f bpPos = b.joints.get(b.joints.size() - 1).position;
                boolean found = false;
                for (BranchPoint bp : branchPoints) {
                    if (bp.position.distance(bpPos) < vs * MIN_PATH_LENGTH) {
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


    public record SkeletonJoint(Vector3f position, float radius) {}
    public record SkeletonBranch(int id, String label, int parentBranch,
                                 List<SkeletonJoint> joints, Vector3f direction, float length) {}
    public record BranchPoint(Vector3f position, List<Integer> connectedBranches) {}
    public record SkeletonResult(int resolution, float voxelSize, Vector3f root,
                                 List<SkeletonBranch> branches, List<BranchPoint> branchPoints) {}
}
