package ixdar.geometry.mesh.quadlayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.HalfEdgeMesh;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.quadlayout.vectorfield.Singularity;

/**
 * § A. CROSS FIELD GENERATION (Bommes–Zimmer–Kobbelt 2009 — BZK09)
 * <p>
 * Pipeline (each step's gaps from the audit are explicitly resolved):
 * <ol>
 * <li>A1. Per-face local frame (x = first half-edge direction projected to
 * tangent plane; y = n × x). Per-edge transport angle κ_ij computed by
 * parallel-transporting face i's frame across the dihedral about the shared
 * edge into face j's frame.</li>
 * <li>A2. Directional constraints from principal curvature (Cohen-Steiner /
 * Alliez 2003 §2.1) and from feature/boundary edges. Boundary/feature
 * constraints OVERRIDE curvature constraints.</li>
 * <li>A3. Voronoi spanning forest in the dual graph rooted at constrained
 * faces; fix p_e := 0 on every forest edge and p_e := round(...) on dual edges
 * between two constrained faces.</li>
 * <li>A4. Greedy mixed-integer least squares solve of E_smooth = Σ (θ_i + κ_ij
 * + (π/2)·p_ij − θ_j)² using Conjugate Gradient on the SPD reduced Hessian (no
 * external solver required).</li>
 * <li>B. Singularity index per interior vertex from angle defect + signed
 * period walk.</li>
 * </ol>
 * <p>
 * All arrays are indexed by ACTIVE-INDEX into the mesh (i.e. position in
 * {@code mesh.vertexIdAt / faceIdAt / edgeIdAt}), not by raw entity id.
 */
public class CrossField {

    public final HalfEdgeMesh mesh;

    /**
     * θ_f : per-face angle of the cross w.r.t. that face's local x-axis, radians.
     */
    public float[] theta;

    /**
     * p_e : per-edge period jump in {0,1,2,3}, oriented in the direction of
     * {@code edgeHalfEdge(e)}. Reading the period jump in the opposite direction
     * means {@code (4 - p) mod 4}.
     */
    public int[] periodJump;

    /** Per-face local x-axis (3D unit vector). */
    public Vector3f[] faceX;
    /** Per-face local y-axis = n_f × x. */
    public Vector3f[] faceY;

    /**
     * Per-edge transport angle κ_ij (radians, in (−π, π]). Defined only for
     * non-boundary edges; oriented in the direction of {@code edgeHalfEdge(e)}.
     */
    public float[] kappa;

    /**
     * Per-vertex singularity index times 4 (kept integer to avoid float
     * comparisons). +1 = +π/2 = valence-3, −1 = −π/2 = valence-5. Boundary vertices
     * left at 0.
     */
    public int[] singularityIndexQuarter;
    public List<Singularity> singularities = new ArrayList<>();

    /** Anisotropy threshold τ_min. */
    public float tauMin = 0.5f;

    /**
     * Mean-curvature magnitude threshold K = curvatureScaleK / averageEdgeLength.
     */
    public float curvatureScaleK = 1.0f / 50.0f;

    /**
     * Geometric series of disk radii r ∈ [startMul·h … endMul·h], factor = ratio.
     */
    public float radiusStartMul = 1.0f;
    public float radiusEndMul = 4.0f;
    public float radiusRatio = (float) Math.sqrt(2.0);

    /** Direction-jitter tolerance (radians). */
    public float jitterTolerance = (float) Math.toRadians(15.0);

    public CrossField(HalfEdgeMesh mesh) {
        this.mesh = mesh;
    }

    public CrossField build() {
        mesh.computeNormals();

        int faceCount = mesh.faceCount();
        int edgeCount = mesh.edgeCount();

        this.theta = new float[faceCount];
        this.periodJump = new int[edgeCount];
        this.kappa = new float[edgeCount];
        this.faceX = new Vector3f[faceCount];
        this.faceY = new Vector3f[faceCount];

        // Build id -> active-index maps once (used everywhere downstream).
        rebuildActiveIndexMaps();

        // ---- A1. local frames + transport angles -----------------------
        buildLocalFaceFrames();
        buildTransportAngles();

        // ---- A2. directional constraints --------------------------------
        boolean[] faceConstrained = new boolean[faceCount];
        float[] faceConstraintAngle = new float[faceCount];
        Arrays.fill(faceConstraintAngle, Float.NaN);

        float averageEdgeLength = computeAverageEdgeLength();
        float curvatureK = curvatureScaleK / Math.max(averageEdgeLength, 1e-9f);

        applyCurvatureConstraints(faceConstrained, faceConstraintAngle, averageEdgeLength, curvatureK);
        applyBoundaryConstraints(faceConstrained, faceConstraintAngle);

        // ---- A3. Voronoi forest fixes one period jump per non-constrained face --
        boolean[] periodFixed = new boolean[edgeCount];
        int[] periodValue = new int[edgeCount];

        Set<Integer> forestEdgeAi = buildVoronoiSpanningForest(faceConstrained);
        for (int eAi : forestEdgeAi) {
            periodFixed[eAi] = true;
            periodValue[eAi] = 0;
        }

        // For dual edges between two constrained faces (and not in the forest),
        // fix p_ij to round( (θ̂_j − θ̂_i − κ_ij) / (π/2) ).
        for (int eAi = 0; eAi < edgeCount; eAi++) {
            if (periodFixed[eAi])
                continue;
            int eId = mesh.edgeIdAt(eAi);
            if (mesh.isBoundaryEdge(eId)) {
                periodFixed[eAi] = true;
                periodValue[eAi] = 0; // boundary edges contribute nothing to E_smooth
                continue;
            }
            int he = mesh.edgeHalfEdge(eId);
            int twin = mesh.halfEdgeTwin(he);
            int fiId = mesh.halfEdgeFace(he);
            int fjId = mesh.halfEdgeFace(twin);
            int fiAi = faceIdToActive.get(fiId);
            int fjAi = faceIdToActive.get(fjId);
            if (faceConstrained[fiAi] && faceConstrained[fjAi]) {
                float diff = faceConstraintAngle[fjAi] - faceConstraintAngle[fiAi] - kappa[eAi];
                int p = Math.floorMod(Math.round(diff / (float) (Math.PI / 2.0)), 4);
                periodFixed[eAi] = true;
                periodValue[eAi] = p;
            }
        }

        // ---- A4. Greedy mixed-integer least squares ---------------------
        SmoothEnergySystem system = new SmoothEnergySystem(faceCount, edgeCount,
                faceConstrained, faceConstraintAngle, periodFixed, periodValue);
        system.assemble();
        system.solveGreedyMIP();
        system.unpackInto(this);

        // ---- B. Singularities -------------------------------------------
        extractSingularities();
        return this;
    }

    /*
     * A1. Local face frames Convention: x_f = first half-edge of f, projected onto
     * the tangent plane. y_f = n_f × x_f. Right-handed.
     */

    private void buildLocalFaceFrames() {
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        Vector3f n = new Vector3f();

        for (int fAi = 0; fAi < mesh.faceCount(); fAi++) {
            int fId = mesh.faceIdAt(fAi);
            int he = mesh.faceHalfEdge(fId);
            int v0 = mesh.halfEdgeVertex(he);
            int v1 = mesh.halfEdgeEndVertex(he);

            mesh.vertexPosition(v0, a);
            mesh.vertexPosition(v1, b);
            Vector3f xAxis = new Vector3f(b).sub(a);

            mesh.faceNormal(fId, n);
            // Project x onto the tangent plane in case of a slightly off normal.
            float xDotN = xAxis.dot(n);
            xAxis.x -= xDotN * n.x;
            xAxis.y -= xDotN * n.y;
            xAxis.z -= xDotN * n.z;
            float xLen = xAxis.length();
            if (xLen < 1e-20f) {
                arbitraryTangent(n, xAxis);
            } else {
                xAxis.div(xLen);
            }
            Vector3f yAxis = new Vector3f();
            n.cross(xAxis, yAxis).normalize();

            faceX[fAi] = xAxis;
            faceY[fAi] = yAxis;
        }
    }

    private static void arbitraryTangent(Vector3f n, Vector3f out) {
        if (Math.abs(n.x) < 0.9f)
            out.set(1f, 0f, 0f);
        else
            out.set(0f, 1f, 0f);
        float dotN = out.dot(n);
        out.x -= dotN * n.x;
        out.y -= dotN * n.y;
        out.z -= dotN * n.z;
        out.normalize();
    }

    /*
     * ============================================================ A1. Transport
     * angles κ_ij ============================================================ 1.
     * Rotate face-i's x-axis about the shared edge by the dihedral angle so it lies
     * in face-j's tangent plane. 2. Express the rotated vector in face-j's frame
     * (faceX[j], faceY[j]): κ_ij = atan2(y-component, x-component).
     */

    private void buildTransportAngles() {
        Vector3f v0 = new Vector3f();
        Vector3f v1 = new Vector3f();
        Vector3f edgeDir = new Vector3f();
        Vector3f ni = new Vector3f();
        Vector3f nj = new Vector3f();
        Vector3f cross = new Vector3f();
        Vector3f xiTransported = new Vector3f();

        for (int eAi = 0; eAi < mesh.edgeCount(); eAi++) {
            int eId = mesh.edgeIdAt(eAi);
            if (mesh.isBoundaryEdge(eId)) {
                kappa[eAi] = 0f;
                continue;
            }
            int he = mesh.edgeHalfEdge(eId);
            int twin = mesh.halfEdgeTwin(he);
            int fiId = mesh.halfEdgeFace(he);
            int fjId = mesh.halfEdgeFace(twin);
            int fiAi = faceIdToActive.get(fiId);
            int fjAi = faceIdToActive.get(fjId);

            int v0Id = mesh.halfEdgeVertex(he);
            int v1Id = mesh.halfEdgeEndVertex(he);
            mesh.vertexPosition(v0Id, v0);
            mesh.vertexPosition(v1Id, v1);
            edgeDir.set(v1).sub(v0);
            float edgeLen = edgeDir.length();
            if (edgeLen < 1e-20f) {
                kappa[eAi] = 0f;
                continue;
            }
            edgeDir.div(edgeLen);

            mesh.faceNormal(fiId, ni);
            mesh.faceNormal(fjId, nj);

            // Signed dihedral: cos = ni·nj, sin = (ni × nj)·edgeDir.
            float cosD = Math.max(-1f, Math.min(1f, ni.dot(nj)));
            ni.cross(nj, cross);
            float sinD = cross.dot(edgeDir);
            float dihedral = (float) Math.atan2(sinD, cosD);

            xiTransported.set(faceX[fiAi]);
            rotateAboutAxis(xiTransported, edgeDir, dihedral);

            float ax = xiTransported.dot(faceX[fjAi]);
            float ay = xiTransported.dot(faceY[fjAi]);
            kappa[eAi] = (float) Math.atan2(ay, ax);
        }
    }

    /** Rodrigues rotation: rotate v about unit axis k by angle θ, in place. */
    private static void rotateAboutAxis(Vector3f v, Vector3f k, float theta) {
        float c = (float) Math.cos(theta);
        float s = (float) Math.sin(theta);
        Vector3f kCrossV = new Vector3f();
        k.cross(v, kCrossV);
        float kDotV = k.dot(v);
        float oneMinusC = 1f - c;
        v.x = v.x * c + kCrossV.x * s + k.x * kDotV * oneMinusC;
        v.y = v.y * c + kCrossV.y * s + k.y * kDotV * oneMinusC;
        v.z = v.z * c + kCrossV.z * s + k.z * kDotV * oneMinusC;
    }

    /**
     * A2. Directional constraints from principal curvature
     * 
     * @param faceConstrained
     * @param faceConstraintAngle
     * @param averageEdgeLength
     * @param curvatureK
     */

    private void applyCurvatureConstraints(boolean[] faceConstrained,
            float[] faceConstraintAngle,
            float averageEdgeLength,
            float curvatureK) {
        Vector3f vPos = new Vector3f();
        Vector3f vNormal = new Vector3f();
        Vector3f e1 = new Vector3f();
        Vector3f e2 = new Vector3f();

        // Geometric radius series.
        List<Float> radii = new ArrayList<>();
        if (radiusRatio <= 1.001f) {
            radii.add(radiusStartMul * averageEdgeLength);
        } else {
            for (float r = radiusStartMul * averageEdgeLength; r <= radiusEndMul * averageEdgeLength
                    + 1e-6f; r *= radiusRatio) {
                radii.add(r);
            }
        }

        for (int vAi = 0; vAi < mesh.vertexCount(); vAi++) {
            int vId = mesh.vertexIdAt(vAi);
            if (mesh.isBoundaryVertex(vId))
                continue;
            mesh.vertexPosition(vId, vPos);
            mesh.vertexNormal(vId, vNormal);
            tangentFrame(vNormal, e1, e2);

            // Per-radius results.
            List<Float> anglesMaxDir = new ArrayList<>();
            List<Float> kappaMaxList = new ArrayList<>();
            List<Float> kappaMinList = new ArrayList<>();

            for (float r : radii) {
                float[] T = integrateCurvatureTensor(vId, vPos, vNormal, e1, e2, r);
                if (T == null)
                    continue;
                float[] eig = eigSym2x2(T[0], T[1], T[2]);
                kappaMaxList.add(eig[0]);
                kappaMinList.add(eig[1]);
                anglesMaxDir.add(eig[2]);
            }
            if (anglesMaxDir.isEmpty())
                continue;

            // Pick the radius with the smallest direction jitter that also passes
            // thresholds.
            int bestIdx = -1;
            float bestJitter = Float.POSITIVE_INFINITY;
            for (int k = 0; k < anglesMaxDir.size(); k++) {
                float kmax = kappaMaxList.get(k);
                float kmin = kappaMinList.get(k);
                if (Math.abs(kmax) < 1e-12f)
                    continue;
                float tau = (Math.abs(kmax) - Math.abs(kmin)) / Math.abs(kmax);
                float meanH = 0.5f * (kmax + kmin);
                if (tau <= tauMin)
                    continue;
                if (Math.abs(meanH) <= curvatureK)
                    continue;
                float jitter = directionJitter(anglesMaxDir, k);
                if (jitter < bestJitter) {
                    bestJitter = jitter;
                    bestIdx = k;
                }
            }
            if (bestIdx < 0 || bestJitter > jitterTolerance)
                continue;

            float constraintAngleAtV = anglesMaxDir.get(bestIdx);
            float c = (float) Math.cos(constraintAngleAtV);
            float s = (float) Math.sin(constraintAngleAtV);
            Vector3f constraintDirWorld = new Vector3f(
                    e1.x * c + e2.x * s,
                    e1.y * c + e2.y * s,
                    e1.z * c + e2.z * s);

            // Apply to each incident face. Curvature constraints are first-write-wins;
            // boundary/feature constraints applied later override them.
            int adj = mesh.vertexFaceCount(vId);
            for (int i = 0; i < adj; i++) {
                int fId = mesh.vertexFaceAt(vId, i);
                int fAi = faceIdToActive.get(fId);
                if (faceConstrained[fAi])
                    continue;
                float angleInFace = projectDirectionToFaceAngle(constraintDirWorld, fAi);
                faceConstrained[fAi] = true;
                faceConstraintAngle[fAi] = canonicalizeMod(angleInFace, (float) (Math.PI / 2.0));
            }
        }
    }

    /**
     * Project a 3D direction into a face's local (x, y) frame and return atan2(y,
     * x).
     */
    private float projectDirectionToFaceAngle(Vector3f dirWorld, int fAi) {
        Vector3f n = new Vector3f();
        mesh.faceNormal(mesh.faceIdAt(fAi), n);
        float dotN = dirWorld.dot(n);
        Vector3f planar = new Vector3f(
                dirWorld.x - dotN * n.x,
                dirWorld.y - dotN * n.y,
                dirWorld.z - dotN * n.z);
        return (float) Math.atan2(planar.dot(faceY[fAi]), planar.dot(faceX[fAi]));
    }

    /** Build a tangent-frame (e1, e2) for normal n. */
    private static void tangentFrame(Vector3f n, Vector3f e1, Vector3f e2) {
        arbitraryTangent(n, e1);
        n.cross(e1, e2).normalize();
    }

    /**
     * Cohen-Steiner integrated curvature tensor over a geodesic disk. Returns [T00,
     * T01, T11] in basis (e1, e2), or null if the disk is empty.
     */
    private float[] integrateCurvatureTensor(int vId, Vector3f vPos, Vector3f vNormal,
            Vector3f e1, Vector3f e2, float radius) {
        Map<Integer, Float> dist = dijkstraWithinRadius(vId, radius);
        if (dist.isEmpty())
            return null;
        Set<Integer> verticesInDisk = dist.keySet();

        // Triangle subset B = triangles whose all 3 vertices are within the disk.
        Set<Integer> B = new HashSet<>();
        for (int u : verticesInDisk) {
            int faces = mesh.vertexFaceCount(u);
            for (int i = 0; i < faces; i++) {
                int fId = mesh.vertexFaceAt(u, i);
                if (B.contains(fId))
                    continue;
                int v0 = mesh.faceVertexAt(fId, 0);
                int v1 = mesh.faceVertexAt(fId, 1);
                int v2 = mesh.faceVertexAt(fId, 2);
                if (verticesInDisk.contains(v0)
                        && verticesInDisk.contains(v1)
                        && verticesInDisk.contains(v2)) {
                    B.add(fId);
                }
            }
        }
        if (B.isEmpty())
            return null;

        float A = 0f;
        for (int fId : B)
            A += faceArea(fId);
        if (A < 1e-20f)
            return null;

        float T00 = 0f, T01 = 0f, T11 = 0f;
        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f edge = new Vector3f();
        Vector3f ni = new Vector3f();
        Vector3f nj = new Vector3f();
        Vector3f cross = new Vector3f();
        Set<Integer> edgesProcessed = new HashSet<>();

        for (int u : verticesInDisk) {
            int eCount = mesh.vertexEdgeCount(u);
            for (int i = 0; i < eCount; i++) {
                int eId = mesh.vertexEdgeAt(u, i);
                if (!edgesProcessed.add(eId))
                    continue;
                int he = mesh.edgeHalfEdge(eId);
                int v0Id = mesh.halfEdgeVertex(he);
                int v1Id = mesh.halfEdgeEndVertex(he);
                if (!verticesInDisk.contains(v0Id) || !verticesInDisk.contains(v1Id))
                    continue;
                if (mesh.isBoundaryEdge(eId))
                    continue;
                int twin = mesh.halfEdgeTwin(he);
                int fiId = mesh.halfEdgeFace(he);
                int fjId = mesh.halfEdgeFace(twin);
                if (!B.contains(fiId) || !B.contains(fjId))
                    continue;

                mesh.vertexPosition(v0Id, p0);
                mesh.vertexPosition(v1Id, p1);
                edge.set(p1).sub(p0);
                float edgeLen = edge.length();
                if (edgeLen < 1e-20f)
                    continue;

                mesh.faceNormal(fiId, ni);
                mesh.faceNormal(fjId, nj);
                float cosD = Math.max(-1f, Math.min(1f, ni.dot(nj)));
                ni.cross(nj, cross);
                float sinD = cross.dot(edge) / edgeLen;
                float beta = (float) Math.atan2(sinD, cosD);

                // Project edge direction into v's tangent plane.
                Vector3f eUnit = new Vector3f(edge).div(edgeLen);
                float dotN = eUnit.dot(vNormal);
                eUnit.x -= dotN * vNormal.x;
                eUnit.y -= dotN * vNormal.y;
                eUnit.z -= dotN * vNormal.z;
                float ex = eUnit.dot(e1);
                float ey = eUnit.dot(e2);
                // Note: don't renormalize after projection — projection length is the weight.

                float w = beta * edgeLen;
                T00 += w * ex * ex;
                T01 += w * ex * ey;
                T11 += w * ey * ey;
            }
        }
        T00 /= A;
        T01 /= A;
        T11 /= A;
        return new float[] { T00, T01, T11 };
    }

    /**
     * Eigendecomposition of [[t00, t01], [t01, t11]]. Returns [eigBig, eigSmall,
     * angleOfBigEigenvector]; eigenvalues sorted by absolute value.
     */
    private static float[] eigSym2x2(float t00, float t01, float t11) {
        float trace = t00 + t11;
        float diff = t00 - t11;
        float disc = (float) Math.sqrt(diff * diff + 4f * t01 * t01);
        float lambda1 = 0.5f * (trace + disc);
        float lambda2 = 0.5f * (trace - disc);
        float eigBig, eigSmall;
        if (Math.abs(lambda1) >= Math.abs(lambda2)) {
            eigBig = lambda1;
            eigSmall = lambda2;
        } else {
            eigBig = lambda2;
            eigSmall = lambda1;
        }
        float vx, vy;
        if (Math.abs(t01) > 1e-20f) {
            vx = eigBig - t11;
            vy = t01;
        } else if (Math.abs(eigBig - t00) < Math.abs(eigBig - t11)) {
            vx = 1f;
            vy = 0f;
        } else {
            vx = 0f;
            vy = 1f;
        }
        float angle = (float) Math.atan2(vy, vx);
        return new float[] { eigBig, eigSmall, angle };
    }

    /** Dijkstra over the 1-skeleton from v, edge weights = Euclidean lengths. */
    private Map<Integer, Float> dijkstraWithinRadius(int vId, float radius) {
        Map<Integer, Float> dist = new HashMap<>();
        dist.put(vId, 0f);
        PriorityQueue<DijkstraNode> pq = new PriorityQueue<>();
        pq.offer(new DijkstraNode(0f, vId));
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        while (!pq.isEmpty()) {
            DijkstraNode node = pq.poll();
            Float bestD = dist.get(node.vertexOrFace);
            if (bestD == null || node.distance > bestD + 1e-9f)
                continue;
            int outCount = mesh.vertexOutgoingHalfEdgeCount(node.vertexOrFace);
            mesh.vertexPosition(node.vertexOrFace, a);
            for (int i = 0; i < outCount; i++) {
                int he = mesh.vertexOutgoingHalfEdgeAt(node.vertexOrFace, i);
                int w = mesh.halfEdgeEndVertex(he);
                mesh.vertexPosition(w, b);
                float wLen = (float) Math.sqrt(
                        (b.x - a.x) * (b.x - a.x) +
                                (b.y - a.y) * (b.y - a.y) +
                                (b.z - a.z) * (b.z - a.z));
                float nd = node.distance + wLen;
                if (nd > radius)
                    continue;
                Float cur = dist.get(w);
                if (cur == null || nd < cur) {
                    dist.put(w, nd);
                    pq.offer(new DijkstraNode(nd, w));
                }
            }
        }
        return dist;
    }

    private static final class DijkstraNode implements Comparable<DijkstraNode> {
        final float distance;
        final int vertexOrFace;

        DijkstraNode(float distance, int vertexOrFace) {
            this.distance = distance;
            this.vertexOrFace = vertexOrFace;
        }

        @Override
        public int compareTo(DijkstraNode other) {
            return Float.compare(this.distance, other.distance);
        }
    }

    /**
     * Direction jitter at index k: angular std deviation between angles[k] and its
     * (up to 2) neighbours, modulo π (κ_max direction is invariant under +π).
     */
    private static float directionJitter(List<Float> angles, int k) {
        float ak = angles.get(k);
        float sumSq = 0f;
        int count = 0;
        for (int j = Math.max(0, k - 1); j <= Math.min(angles.size() - 1, k + 1); j++) {
            if (j == k)
                continue;
            float alpha = angles.get(j) - ak;
            /** Wrap an angle to (−π/2, π/2]. */
            float diff = (float) (alpha - Math.PI * Math.floor((alpha + Math.PI / 2.0) / Math.PI));
            sumSq += diff * diff;
            count++;
        }
        if (count == 0)
            return 0f;
        return (float) Math.sqrt(sumSq / count);
    }

    private void applyBoundaryConstraints(boolean[] faceConstrained, float[] faceConstraintAngle) {
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        for (int eAi = 0; eAi < mesh.edgeCount(); eAi++) {
            int eId = mesh.edgeIdAt(eAi);
            if (!mesh.isBoundaryEdge(eId))
                continue;
            int he = mesh.edgeHalfEdge(eId);
            int v0 = mesh.halfEdgeVertex(he);
            int v1 = mesh.halfEdgeEndVertex(he);
            mesh.vertexPosition(v0, a);
            mesh.vertexPosition(v1, b);
            Vector3f edgeDir = new Vector3f(b).sub(a);
            constrainBothFacesOfEdge(eId, edgeDir, faceConstrained, faceConstraintAngle);
        }
    }

    private void constrainBothFacesOfEdge(int eId, Vector3f edgeDirWorld,
            boolean[] faceConstrained,
            float[] faceConstraintAngle) {
        int he = mesh.edgeHalfEdge(eId);
        int twin = mesh.halfEdgeTwin(he);
        for (int hePick : new int[] { he, twin }) {
            int fId = mesh.halfEdgeFace(hePick);
            if (fId == MeshTopology.NONE)
                continue; // boundary side of a boundary edge
            int fAi = faceIdToActive.get(fId);
            float angle = projectDirectionToFaceAngle(edgeDirWorld, fAi);
            faceConstrained[fAi] = true;
            faceConstraintAngle[fAi] = canonicalizeMod(angle, (float) (Math.PI / 2.0));
        }
    }

    /*
     * A3. Voronoi spanning forest in the dual graph
     */

    private Set<Integer> buildVoronoiSpanningForest(boolean[] faceConstrained) {
        int faceCount = mesh.faceCount();
        float[] dist = new float[faceCount];
        int[] parentEdgeAi = new int[faceCount];
        Arrays.fill(dist, Float.POSITIVE_INFINITY);
        Arrays.fill(parentEdgeAi, -1);

        PriorityQueue<DijkstraNode> pq = new PriorityQueue<>();
        for (int fAi = 0; fAi < faceCount; fAi++) {
            if (faceConstrained[fAi]) {
                dist[fAi] = 0f;
                pq.offer(new DijkstraNode(0f, fAi));
            }
        }
        Vector3f va = new Vector3f();
        Vector3f vb = new Vector3f();

        while (!pq.isEmpty()) {
            DijkstraNode node = pq.poll();
            int fAi = node.vertexOrFace;
            if (node.distance > dist[fAi] + 1e-9f)
                continue;
            int fId = mesh.faceIdAt(fAi);
            int adj = mesh.faceHalfEdgeCount(fId);
            for (int i = 0; i < adj; i++) {
                int he = mesh.faceHalfEdgeAt(fId, i);
                int twin = mesh.halfEdgeTwin(he);
                int gId = mesh.halfEdgeFace(twin);
                if (gId == MeshTopology.NONE)
                    continue;
                int gAi = faceIdToActive.get(gId);
                int eId = mesh.halfEdgeEdge(he);
                int eAi = edgeIdToActive.get(eId);

                int v0 = mesh.halfEdgeVertex(he);
                int v1 = mesh.halfEdgeEndVertex(he);
                mesh.vertexPosition(v0, va);
                mesh.vertexPosition(v1, vb);
                float w = (float) Math.sqrt(
                        (vb.x - va.x) * (vb.x - va.x) +
                                (vb.y - va.y) * (vb.y - va.y) +
                                (vb.z - va.z) * (vb.z - va.z));
                float nd = node.distance + w;
                if (nd < dist[gAi]) {
                    dist[gAi] = nd;
                    parentEdgeAi[gAi] = eAi;
                    pq.offer(new DijkstraNode(nd, gAi));
                }
            }
        }

        Set<Integer> forest = new HashSet<>();
        for (int fAi = 0; fAi < faceCount; fAi++) {
            if (parentEdgeAi[fAi] >= 0 && !faceConstrained[fAi]) {
                forest.add(parentEdgeAi[fAi]);
            }
        }
        return forest;
    }

    /*
     * A4. Smooth-energy MIP solver (greedy rounding of period jumps)
     *
     * Energy: E = Σ_e (θ_i + κ_ij + (π/2)·p_ij − θ_j)² non-boundary edges
     *
     * Each row r of the design matrix A: A[r, θ_i] = +1 A[r, θ_j] = −1 A[r, p_e] =
     * +π/2 with rhs b[r] = −κ_ij so that residual = A·x − b.
     *
     * Hard constraints (θ̂_f for f ∈ F_c, p̂_e for e ∈ fixed_edges) are applied by
     * elimination — we move them to the rhs and solve the reduced normal equations
     * Hᵣ · x_free = gᵣ via Conjugate Gradient.
     *
     * Greedy MIP rounding (BZK09 §2): while any free p variable remains:
     * relax-solve, then fix the free p with the smallest |p − round(p)|
     */

    private final class SmoothEnergySystem {
        final int faceCount;
        final int edgeCount;
        final boolean[] faceConstrained;
        final float[] faceConstraintAngle;
        final boolean[] periodFixedInit;
        final int[] periodValue;

        // Energy rows (one per non-boundary edge).
        int[] rowFaceI; // active-index of face i
        int[] rowFaceJ; // active-index of face j
        int[] rowEdgeAi; // active-index of the edge
        float[] rowKappa;
        int rowCount;

        // Solution storage.
        float[] solutionTheta;
        float[] solutionPeriod;
        boolean[] periodFixed;

        SmoothEnergySystem(int faceCount, int edgeCount,
                boolean[] faceConstrained, float[] faceConstraintAngle,
                boolean[] periodFixed, int[] periodValue) {
            this.faceCount = faceCount;
            this.edgeCount = edgeCount;
            this.faceConstrained = faceConstrained;
            this.faceConstraintAngle = faceConstraintAngle;
            this.periodFixedInit = periodFixed;
            this.periodValue = periodValue;
        }

        void assemble() {
            // Count non-boundary edges.
            int nbCount = 0;
            for (int eAi = 0; eAi < edgeCount; eAi++) {
                if (!mesh.isBoundaryEdge(mesh.edgeIdAt(eAi)))
                    nbCount++;
            }
            rowFaceI = new int[nbCount];
            rowFaceJ = new int[nbCount];
            rowEdgeAi = new int[nbCount];
            rowKappa = new float[nbCount];
            rowCount = 0;
            for (int eAi = 0; eAi < edgeCount; eAi++) {
                int eId = mesh.edgeIdAt(eAi);
                if (mesh.isBoundaryEdge(eId))
                    continue;
                int he = mesh.edgeHalfEdge(eId);
                int twin = mesh.halfEdgeTwin(he);
                rowFaceI[rowCount] = faceIdToActive.get(mesh.halfEdgeFace(he));
                rowFaceJ[rowCount] = faceIdToActive.get(mesh.halfEdgeFace(twin));
                rowEdgeAi[rowCount] = eAi;
                rowKappa[rowCount] = kappa[eAi];
                rowCount++;
            }

            solutionTheta = new float[faceCount];
            solutionPeriod = new float[edgeCount];
            for (int fAi = 0; fAi < faceCount; fAi++) {
                solutionTheta[fAi] = faceConstrained[fAi] ? faceConstraintAngle[fAi] : 0f;
            }
            periodFixed = periodFixedInit.clone();
            for (int eAi = 0; eAi < edgeCount; eAi++) {
                solutionPeriod[eAi] = periodFixed[eAi] ? periodValue[eAi] : 0f;
            }
        }

        void solveGreedyMIP() {
            while (true) {
                solveRelaxed();
                int bestE = -1;
                float bestRoundoff = Float.POSITIVE_INFINITY;
                for (int eAi = 0; eAi < edgeCount; eAi++) {
                    if (periodFixed[eAi])
                        continue;
                    float p = solutionPeriod[eAi];
                    float roundoff = Math.abs(p - Math.round(p));
                    if (roundoff < bestRoundoff) {
                        bestRoundoff = roundoff;
                        bestE = eAi;
                    }
                }
                if (bestE < 0)
                    break;
                int rounded = Math.floorMod((int) Math.round(solutionPeriod[bestE]), 4);
                periodFixed[bestE] = true;
                periodValue[bestE] = rounded;
                solutionPeriod[bestE] = rounded;
            }
            solveRelaxed(); // final solve with all p fixed
        }

        /**
         * Continuous L2 solve with currently-fixed variables held constant. Uses CG on
         * the SPD reduced Hessian H_free = Aᵀ_free · A_free.
         */
        void solveRelaxed() {
            int[] faceFreeMap = new int[faceCount];
            int[] edgeFreeMap = new int[edgeCount];
            Arrays.fill(faceFreeMap, -1);
            Arrays.fill(edgeFreeMap, -1);
            int faceFreeCount = 0;
            int edgeFreeCount = 0;
            for (int fAi = 0; fAi < faceCount; fAi++) {
                if (!faceConstrained[fAi])
                    faceFreeMap[fAi] = faceFreeCount++;
            }
            for (int eAi = 0; eAi < edgeCount; eAi++) {
                if (!periodFixed[eAi])
                    edgeFreeMap[eAi] = edgeFreeCount++;
            }
            int n = faceFreeCount + edgeFreeCount;
            if (n == 0)
                return;

            float halfPi = (float) (Math.PI / 2.0);

            // rhs[r] = (b - A_fixed · x_fixed) for row r
            float[] rhs = new float[rowCount];
            for (int r = 0; r < rowCount; r++) {
                int fi = rowFaceI[r];
                int fj = rowFaceJ[r];
                int eAi = rowEdgeAi[r];
                float val = -rowKappa[r];
                if (faceConstrained[fi])
                    val -= faceConstraintAngle[fi];
                if (faceConstrained[fj])
                    val += faceConstraintAngle[fj];
                if (periodFixed[eAi])
                    val -= halfPi * periodValue[eAi];
                rhs[r] = val;
            }
            // g = Aᵀ_free · rhs
            float[] g = new float[n];
            for (int r = 0; r < rowCount; r++) {
                int fi = rowFaceI[r];
                int fj = rowFaceJ[r];
                int eAi = rowEdgeAi[r];
                if (faceFreeMap[fi] >= 0)
                    g[faceFreeMap[fi]] += rhs[r];
                if (faceFreeMap[fj] >= 0)
                    g[faceFreeMap[fj]] -= rhs[r];
                if (edgeFreeMap[eAi] >= 0)
                    g[faceFreeCount + edgeFreeMap[eAi]] += halfPi * rhs[r];
            }

            // CG on H_free · x = g.
            float[] x = new float[n];
            float[] Ap = new float[n];
            float[] r0 = g.clone();
            float[] p0 = g.clone();
            float rsOld = dot(r0, r0);
            float tol = 1e-12f * Math.max(1f, rsOld);
            int maxIter = Math.max(1000, 4 * n);
            for (int iter = 0; iter < maxIter; iter++) {
                applyHessianFree(p0, Ap, faceFreeMap, edgeFreeMap, faceFreeCount, halfPi);
                float pAp = dot(p0, Ap);
                if (pAp < 1e-30f)
                    break;
                float alpha = rsOld / pAp;
                for (int i = 0; i < n; i++) {
                    x[i] += alpha * p0[i];
                    r0[i] -= alpha * Ap[i];
                }
                float rsNew = dot(r0, r0);
                if (rsNew < tol)
                    break;
                float beta = rsNew / rsOld;
                for (int i = 0; i < n; i++)
                    p0[i] = r0[i] + beta * p0[i];
                rsOld = rsNew;
            }

            for (int fAi = 0; fAi < faceCount; fAi++) {
                int idx = faceFreeMap[fAi];
                if (idx >= 0)
                    solutionTheta[fAi] = x[idx];
            }
            for (int eAi = 0; eAi < edgeCount; eAi++) {
                int idx = edgeFreeMap[eAi];
                if (idx >= 0)
                    solutionPeriod[eAi] = x[faceFreeCount + idx];
            }
        }

        private void applyHessianFree(float[] x, float[] y,
                int[] faceFreeMap, int[] edgeFreeMap,
                int faceFreeCount, float halfPi) {
            Arrays.fill(y, 0f);
            for (int r = 0; r < rowCount; r++) {
                int fi = rowFaceI[r];
                int fj = rowFaceJ[r];
                int eAi = rowEdgeAi[r];
                float ax = 0f;
                if (faceFreeMap[fi] >= 0)
                    ax += x[faceFreeMap[fi]];
                if (faceFreeMap[fj] >= 0)
                    ax -= x[faceFreeMap[fj]];
                if (edgeFreeMap[eAi] >= 0)
                    ax += halfPi * x[faceFreeCount + edgeFreeMap[eAi]];
                if (faceFreeMap[fi] >= 0)
                    y[faceFreeMap[fi]] += ax;
                if (faceFreeMap[fj] >= 0)
                    y[faceFreeMap[fj]] -= ax;
                if (edgeFreeMap[eAi] >= 0)
                    y[faceFreeCount + edgeFreeMap[eAi]] += halfPi * ax;
            }
        }

        void unpackInto(CrossField cf) {
            cf.theta = solutionTheta.clone();
            cf.periodJump = new int[edgeCount];
            for (int eAi = 0; eAi < edgeCount; eAi++) {
                if (periodFixed[eAi]) {
                    cf.periodJump[eAi] = periodValue[eAi];
                } else {
                    cf.periodJump[eAi] = Math.floorMod((int) Math.round(solutionPeriod[eAi]), 4);
                }
            }
        }
    }

    private static float dot(float[] a, float[] b) {
        float s = 0f;
        for (int i = 0; i < a.length; i++)
            s += a[i] * b[i];
        return s;
    }

    /*
     * B. Singularities
     *
     * Walk the 1-ring of v in the order given by vertexOutgoingHalfEdges (CCW). For
     * each outgoing half-edge `he`, look up its edge eId. If `he` is the "natural"
     * half-edge of eId (edgeHalfEdge(eId) == he), contribute +periodJump[eAi];
     * otherwise contribute (4 − periodJump[eAi]) mod 4.
     *
     * I(v) = (1/(2π)) · angleDefect(v) + (1/4) · periodWalk (stored as 4·I, so an
     * integer)
     */

    public List<Singularity> extractSingularities() {
        int vertexCount = mesh.vertexCount();
        singularityIndexQuarter = new int[vertexCount];
        singularities.clear();
        Vector3f vPos = new Vector3f();
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();

        for (int vAi = 0; vAi < vertexCount; vAi++) {
            int vId = mesh.vertexIdAt(vAi);
            if (mesh.isBoundaryVertex(vId))
                continue;
            mesh.vertexPosition(vId, vPos);

            float angleSum = 0f;
            int faces = mesh.vertexFaceCount(vId);
            for (int i = 0; i < faces; i++) {
                int fId = mesh.vertexFaceAt(vId, i);
                angleSum += interiorAngleAtVertex(fId, vId, vPos, a, b);
            }
            float defect = (float) (2.0 * Math.PI) - angleSum;

            int periodWalk = 0;
            int outCount = mesh.vertexOutgoingHalfEdgeCount(vId);
            for (int i = 0; i < outCount; i++) {
                int he = mesh.vertexOutgoingHalfEdgeAt(vId, i);
                int eId = mesh.halfEdgeEdge(he);
                int eAi = edgeIdToActive.get(eId);
                int p = periodJump[eAi];
                int natural = mesh.edgeHalfEdge(eId);
                if (he == natural)
                    periodWalk += p;
                else
                    periodWalk += Math.floorMod(-p, 4);
            }

            // 4·I(v) = (defect · 2/π) + periodWalk; round to int
            float iTimes4 = (float) ((defect * 2.0) / Math.PI) + periodWalk;
            int iQuarter = Math.round(iTimes4);
            singularityIndexQuarter[vAi] = iQuarter;
            if (iQuarter != 0) {
                singularities.add(new Singularity(vId, iQuarter));
            }
        }
        return singularities;
    }

    private float interiorAngleAtVertex(int fId, int vId, Vector3f vPos, Vector3f a, Vector3f b) {
        int adj = mesh.faceHalfEdgeCount(fId);
        for (int i = 0; i < adj; i++) {
            int he = mesh.faceHalfEdgeAt(fId, i);
            if (mesh.halfEdgeVertex(he) == vId) {
                int prev = mesh.halfEdgePrev(he);
                int prevStart = mesh.halfEdgeVertex(prev);
                int nextEnd = mesh.halfEdgeEndVertex(he);
                mesh.vertexPosition(prevStart, a);
                mesh.vertexPosition(nextEnd, b);
                a.sub(vPos);
                b.sub(vPos);
                float la = a.length();
                float lb = b.length();
                if (la < 1e-20f || lb < 1e-20f)
                    return 0f;
                float c = a.dot(b) / (la * lb);
                c = Math.max(-1f, Math.min(1f, c));
                return (float) Math.acos(c);
            }
        }
        return 0f;
    }

    private float computeAverageEdgeLength() {
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        double sum = 0;
        int count = 0;
        for (int eAi = 0; eAi < mesh.edgeCount(); eAi++) {
            int eId = mesh.edgeIdAt(eAi);
            int he = mesh.edgeHalfEdge(eId);
            mesh.vertexPosition(mesh.halfEdgeVertex(he), a);
            mesh.vertexPosition(mesh.halfEdgeEndVertex(he), b);
            sum += Math.sqrt(
                    (b.x - a.x) * (b.x - a.x) +
                            (b.y - a.y) * (b.y - a.y) +
                            (b.z - a.z) * (b.z - a.z));
            count++;
        }
        return count == 0 ? 1f : (float) (sum / count);
    }

    private float faceArea(int fId) {
        int he = mesh.faceHalfEdge(fId);
        int v0 = mesh.halfEdgeVertex(he);
        int v1 = mesh.halfEdgeEndVertex(he);
        int v2 = mesh.halfEdgeEndVertex(mesh.halfEdgeNext(he));
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        Vector3f c = new Vector3f();
        mesh.vertexPosition(v0, a);
        mesh.vertexPosition(v1, b);
        mesh.vertexPosition(v2, c);
        b.sub(a);
        c.sub(a);
        Vector3f cross = new Vector3f();
        b.cross(c, cross);
        return 0.5f * cross.length();
    }

    private static float canonicalizeMod(float angle, float mod) {
        float r = (float) (angle - mod * Math.floor(angle / mod));
        if (r < 0)
            r += mod;
        return r;
    }

    private void rebuildActiveIndexMaps() {
        faceIdToActive = new HashMap<>(mesh.faceCount() * 2);
        for (int i = 0; i < mesh.faceCount(); i++) {
            faceIdToActive.put(mesh.faceIdAt(i), i);
        }
        edgeIdToActive = new HashMap<>(mesh.edgeCount() * 2);
        for (int i = 0; i < mesh.edgeCount(); i++) {
            edgeIdToActive.put(mesh.edgeIdAt(i), i);
        }
    }

    private Map<Integer, Integer> faceIdToActive;
    private Map<Integer, Integer> edgeIdToActive;
}
