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

    public static volatile String lastDiagnostics = "[cross-field] no diagnostics recorded";

    public final HalfEdgeMesh mesh;

    /**
     * θ_f : per-face angle of the cross w.r.t. that face's local x-axis, radians.
     */
    public float[] theta;

    /**
     * p_e : signed per-edge period jump, oriented in the direction of
     * {@code edgeHalfEdge(e)}. Reading the period jump in the opposite direction
     * means {@code -p_e}. Reduce modulo 4 only when selecting a rendered cross
     * branch.
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

    /** BZK09 §3 relative anisotropy threshold τ_min. */
    public float tauMin = 0.8f;

    /**
     * BZK09 §3 mean-curvature threshold K = curvatureScaleK / boundingSphereRadius.
     */
    public float curvatureScaleK = 0.1f;

    /**
     * Geometric series of disk radii r ∈ [startMul·h … endMul·h], factor = ratio.
     */
    public float radiusStartMul = 1.0f;
    public float radiusEndMul = 4.0f;
    public float radiusRatio = (float) Math.sqrt(2.0);

    /** Direction-jitter tolerance (radians). */
    public float jitterTolerance = (float) Math.toRadians(15.0);

    /** BZK09 target quad edge length h as 1% of the bounding-box diagonal. */
    public float targetEdgeLengthFractionOfBounds = 0.04f;

    public CurvatureConstraintStats lastCurvatureStats = new CurvatureConstraintStats();

    public Map<Integer, Integer> faceIdToActive;
    public Map<Integer, Integer> edgeIdToActive;

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
        faceIdToActive = new HashMap<>(mesh.faceCount() * 2);
        for (int i1 = 0; i1 < mesh.faceCount(); i1++) {
            faceIdToActive.put(mesh.faceIdAt(i1), i1);
        }
        edgeIdToActive = new HashMap<>(mesh.edgeCount() * 2);
        for (int i = 0; i < mesh.edgeCount(); i++) {
            edgeIdToActive.put(mesh.edgeIdAt(i), i);
        }

        // ---- A1. local frames + transport angles -----------------------
        /*
         * A1. Local face frames Convention: x_f = first half-edge of f, projected onto
         * the tangent plane. y_f = n_f × x_f. Right-handed.
         */
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

        /*
         * A1. Transport angles κ_ij Rotate face-i's x-axis about the shared edge by the
         * dihedral angle so it lies in face-j's tangent plane. Express the rotated
         * vector in face-j's frame (faceX[j], faceY[j]): κ_ij = atan2(y-component,
         * x-component).
         */

        Vector3f v0 = new Vector3f();
        Vector3f v1 = new Vector3f();
        Vector3f edgeDir = new Vector3f();
        Vector3f ni = new Vector3f();
        Vector3f nj = new Vector3f();
        Vector3f cross = new Vector3f();
        Vector3f xiTransported = new Vector3f();

        for (int eAi1 = 0; eAi1 < mesh.edgeCount(); eAi1++) {
            int eId1 = mesh.edgeIdAt(eAi1);
            if (mesh.isBoundaryEdge(eId1)) {
                kappa[eAi1] = 0f;
                continue;
            }
            int he1 = mesh.edgeHalfEdge(eId1);
            int twin1 = mesh.halfEdgeTwin(he1);
            int fiId1 = mesh.halfEdgeFace(he1);
            int fjId1 = mesh.halfEdgeFace(twin1);
            int fiAi1 = faceIdToActive.get(fiId1);
            int fjAi1 = faceIdToActive.get(fjId1);

            int v0Id = mesh.halfEdgeVertex(he1);
            int v1Id = mesh.halfEdgeEndVertex(he1);
            mesh.vertexPosition(v0Id, v0);
            mesh.vertexPosition(v1Id, v1);
            edgeDir.set(v1).sub(v0);
            float edgeLen = edgeDir.length();
            if (edgeLen < 1e-20f) {
                kappa[eAi1] = 0f;
                continue;
            }
            edgeDir.div(edgeLen);

            mesh.faceNormal(fiId1, ni);
            mesh.faceNormal(fjId1, nj);

            // Signed dihedral: cos = ni·nj, sin = (ni × nj)·edgeDir.
            float cosD = Math.max(-1f, Math.min(1f, ni.dot(nj)));
            ni.cross(nj, cross);
            float sinD = cross.dot(edgeDir);
            float dihedral = (float) Math.atan2(sinD, cosD);

            xiTransported.set(faceX[fiAi1]);
            rotateAboutAxis(xiTransported, edgeDir, dihedral);

            float ax = xiTransported.dot(faceX[fjAi1]);
            float ay = xiTransported.dot(faceY[fjAi1]);
            kappa[eAi1] = (float) Math.atan2(ay, ax);
        }

        // ---- A2. directional constraints --------------------------------
        boolean[] faceConstrained = new boolean[faceCount];
        float[] faceConstraintAngle = new float[faceCount];
        Arrays.fill(faceConstraintAngle, Float.NaN);

        float averageEdgeLength = computeAverageEdgeLength();
        float boundingSphereRadius = computeBoundingSphereRadius();
        float curvatureK = curvatureScaleK / Math.max(boundingSphereRadius, 1e-9f);

        int curvatureConstraints = applyCurvatureConstraints(
                faceConstrained, faceConstraintAngle, averageEdgeLength, curvatureK);
        int boundaryConstraints = applyBoundaryConstraints(faceConstrained, faceConstraintAngle);
        int totalConstraints = countTrue(faceConstrained);

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
        int boundaryPeriodFixes = 0;
        int constrainedPeriodFixes = 0;
        for (int eAi = 0; eAi < edgeCount; eAi++) {
            if (periodFixed[eAi])
                continue;
            int eId = mesh.edgeIdAt(eAi);
            if (mesh.isBoundaryEdge(eId)) {
                periodFixed[eAi] = true;
                periodValue[eAi] = 0; // boundary edges contribute nothing to E_smooth
                boundaryPeriodFixes++;
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
                int p = Math.round(diff / (float) (Math.PI / 2.0));
                periodFixed[eAi] = true;
                periodValue[eAi] = p;
                constrainedPeriodFixes++;
            }
        }

        // ---- A4. Greedy mixed-integer least squares ---------------------
        SmoothEnergySystem system = new SmoothEnergySystem(faceCount, edgeCount,
                faceConstrained, faceConstraintAngle, periodFixed, periodValue);
        system.assemble();
        printProblemDiagnostics(averageEdgeLength, boundingSphereRadius, curvatureK,
                curvatureConstraints, boundaryConstraints, totalConstraints,
                forestEdgeAi.size(), boundaryPeriodFixes, constrainedPeriodFixes,
                periodFixed, system);
        system.solveGreedyMIP();
        system.unpackInto(this);

        // ---- B. Singularities -------------------------------------------
        extractSingularities();
        printSolutionDiagnostics(system);
        return this;
    }

    public static void arbitraryTangent(Vector3f n, Vector3f out) {
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

    public void buildTransportAngles() {
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
    public static void rotateAboutAxis(Vector3f v, Vector3f k, float theta) {
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

    public int applyCurvatureConstraints(boolean[] faceConstrained,
            float[] faceConstraintAngle,
            float averageEdgeLength,
            float curvatureK) {
        Vector3f vPos = new Vector3f();
        Vector3f vNormal = new Vector3f();
        Vector3f e1 = new Vector3f();
        Vector3f e2 = new Vector3f();
        int addedConstraints = 0;
        CurvatureConstraintStats stats = new CurvatureConstraintStats();
        float h = resolvedTargetEdgeLength(averageEdgeLength);
        float stabilityWindow = 0.25f * h;

        // Geometric radius series.
        List<Float> radii = new ArrayList<>();
        float startRadius = radiusStartMul * averageEdgeLength;
        if (radiusRatio <= 1.001f || h <= startRadius) {
            radii.add(h);
        } else {
            for (float r = startRadius; r <= h + 1e-6f; r *= radiusRatio) {
                radii.add(r);
            }
        }

        for (int vAi = 0; vAi < mesh.vertexCount(); vAi++) {
            stats.verticesVisited++;
            int vId = mesh.vertexIdAt(vAi);
            if (mesh.isBoundaryVertex(vId)) {
                stats.boundaryVertices++;
                continue;
            }
            mesh.vertexPosition(vId, vPos);
            mesh.vertexNormal(vId, vNormal);
            tangentFrame(vNormal, e1, e2);

            // Per-radius results.
            List<Float> anglesMaxDir = new ArrayList<>();
            List<Float> kappaMaxList = new ArrayList<>();
            List<Float> kappaMinList = new ArrayList<>();
            List<Float> validRadii = new ArrayList<>();

            for (float r : radii) {
                float[] T = integrateCurvatureTensor(vId, vPos, vNormal, e1, e2, r);
                if (T == null)
                    continue;
                float[] eig = eigSym2x2(T[0], T[1], T[2]);
                kappaMaxList.add(eig[0]);
                kappaMinList.add(eig[1]);
                anglesMaxDir.add(eig[2]);
                validRadii.add(r);
            }
            if (anglesMaxDir.isEmpty()) {
                stats.noCurvatureSamples++;
                continue;
            }

            // BZK09 §3 accepts a shape-operator radius only if the whole
            // interval [r - h/4, r + h/4] remains anisotropic and non-flat.
            int bestIdx = -1;
            float bestJitter = Float.POSITIVE_INFINITY;
            boolean failedTau = false;
            boolean failedMean = false;
            for (int k = 0; k < anglesMaxDir.size(); k++) {
                int intervalStatus = curvatureIntervalStatus(validRadii, kappaMaxList, kappaMinList, k,
                        stabilityWindow, curvatureK);
                if (intervalStatus == CURVATURE_INTERVAL_FAIL_TAU) {
                    failedTau = true;
                    continue;
                }
                if (intervalStatus == CURVATURE_INTERVAL_FAIL_MEAN) {
                    failedMean = true;
                    continue;
                }
                if (intervalStatus != CURVATURE_INTERVAL_VALID) {
                    continue;
                }
                float jitter = directionJitter(anglesMaxDir, validRadii, k, stabilityWindow);
                if (jitter < bestJitter) {
                    bestJitter = jitter;
                    bestIdx = k;
                }
            }
            if (bestIdx < 0) {
                if (failedTau) {
                    stats.failedAnisotropy++;
                } else if (failedMean) {
                    stats.failedMeanCurvature++;
                } else {
                    stats.failedInterval++;
                }
                continue;
            }
            stats.validIntervals++;
            stats.jitterSum += bestJitter;
            stats.maxJitter = Math.max(stats.maxJitter, bestJitter);
            if (bestJitter > jitterTolerance) {
                stats.failedJitter++;
                continue;
            }
            stats.acceptedVertices++;

            float constraintAngleAtV = anglesMaxDir.get(bestIdx);
            float c = (float) Math.cos(constraintAngleAtV);
            float s = (float) Math.sin(constraintAngleAtV);
            Vector3f constraintDirWorld = new Vector3f(
                    e1.x * c + e2.x * s,
                    e1.y * c + e2.y * s,
                    e1.z * c + e2.z * s);

            // BZK09 solves per-face theta constraints. A reliable curvature sample
            // contributes one sparse face constraint, not a whole one-ring flood.
            int adj = mesh.vertexFaceCount(vId);
            int bestFaceAi = -1;
            float bestProjectionLength = -1f;
            for (int i = 0; i < adj; i++) {
                int fId = mesh.vertexFaceAt(vId, i);
                int fAi = faceIdToActive.get(fId);
                if (faceConstrained[fAi]) {
                    stats.faceCollisionCandidates++;
                    continue;
                }
                float projectionLength = projectedDirectionLength(constraintDirWorld, fAi);
                if (projectionLength > bestProjectionLength) {
                    bestProjectionLength = projectionLength;
                    bestFaceAi = fAi;
                }
            }
            if (bestFaceAi >= 0) {
                float angleInFace = projectDirectionToFaceAngle(constraintDirWorld, bestFaceAi);
                faceConstrained[bestFaceAi] = true;
                faceConstraintAngle[bestFaceAi] = canonicalizeMod(angleInFace, (float) (Math.PI / 2.0));
                addedConstraints++;
            } else {
                stats.allIncidentFacesConstrained++;
            }
        }
        stats.addedConstraints = addedConstraints;
        lastCurvatureStats = stats;
        return addedConstraints;
    }

    public float projectedDirectionLength(Vector3f dirWorld, int fAi) {
        Vector3f n = new Vector3f();
        mesh.faceNormal(mesh.faceIdAt(fAi), n);
        float dotN = dirWorld.dot(n);
        float x = dirWorld.x - dotN * n.x;
        float y = dirWorld.y - dotN * n.y;
        float z = dirWorld.z - dotN * n.z;
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    public float resolvedTargetEdgeLength(float averageEdgeLength) {
        float target = targetEdgeLengthFractionOfBounds * computeBoundingBoxDiagonal();
        return target > 0f ? target : averageEdgeLength;
    }

    public float computeBoundingBoxDiagonal() {
        if (mesh.vertexCount() == 0) {
            return 0f;
        }
        Vector3f p = new Vector3f();
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        for (int vAi = 0; vAi < mesh.vertexCount(); vAi++) {
            mesh.vertexPosition(mesh.vertexIdAt(vAi), p);
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            minZ = Math.min(minZ, p.z);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
            maxZ = Math.max(maxZ, p.z);
        }
        float dx = maxX - minX;
        float dy = maxY - minY;
        float dz = maxZ - minZ;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static final int CURVATURE_INTERVAL_VALID = 0;
    public static final int CURVATURE_INTERVAL_FAIL_EMPTY = 1;
    public static final int CURVATURE_INTERVAL_FAIL_TAU = 2;
    public static final int CURVATURE_INTERVAL_FAIL_MEAN = 3;

    /**
     * Project a 3D direction into a face's local (x, y) frame and return atan2(y,
     * x).
     */
    public float projectDirectionToFaceAngle(Vector3f dirWorld, int fAi) {
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
    public static void tangentFrame(Vector3f n, Vector3f e1, Vector3f e2) {
        arbitraryTangent(n, e1);
        n.cross(e1, e2).normalize();
    }

    /**
     * Cohen-Steiner integrated curvature tensor over a geodesic disk. Returns [T00,
     * T01, T11] in basis (e1, e2), or null if the disk is empty.
     */
    public float[] integrateCurvatureTensor(int vId, Vector3f vPos, Vector3f vNormal,
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
    public static float[] eigSym2x2(float t00, float t01, float t11) {
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
    public Map<Integer, Float> dijkstraWithinRadius(int vId, float radius) {
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

    public static final class DijkstraNode implements Comparable<DijkstraNode> {
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

    public static final class CurvatureConstraintStats {
        int verticesVisited;
        int boundaryVertices;
        int noCurvatureSamples;
        int failedAnisotropy;
        int failedMeanCurvature;
        int failedInterval;
        int validIntervals;
        int failedJitter;
        int acceptedVertices;
        int faceCollisionCandidates;
        int allIncidentFacesConstrained;
        int addedConstraints;
        double jitterSum;
        float maxJitter;

        double averageJitter() {
            return validIntervals > 0 ? jitterSum / validIntervals : 0.0;
        }
    }

    public int curvatureIntervalStatus(List<Float> radii,
            List<Float> kappaMaxList,
            List<Float> kappaMinList,
            int k,
            float stabilityWindow,
            float curvatureK) {
        float center = radii.get(k);
        boolean hasSample = false;
        for (int j = 0; j < radii.size(); j++) {
            if (Math.abs(radii.get(j) - center) > stabilityWindow)
                continue;
            hasSample = true;
            float kmax = kappaMaxList.get(j);
            float kmin = kappaMinList.get(j);
            if (Math.abs(kmax) < 1e-12f)
                return CURVATURE_INTERVAL_FAIL_TAU;
            float tau = (Math.abs(kmax) - Math.abs(kmin)) / Math.abs(kmax);
            float meanH = 0.5f * (kmax + kmin);
            if (tau <= tauMin || Math.abs(meanH) <= curvatureK)
                return tau <= tauMin
                        ? CURVATURE_INTERVAL_FAIL_TAU
                        : CURVATURE_INTERVAL_FAIL_MEAN;
        }
        return hasSample ? CURVATURE_INTERVAL_VALID : CURVATURE_INTERVAL_FAIL_EMPTY;
    }

    /**
     * Direction jitter at index k: angular std deviation inside the BZK09 stability
     * interval, modulo π (κ_max direction is invariant under +π).
     */
    public static float directionJitter(List<Float> angles, List<Float> radii, int k, float stabilityWindow) {
        float ak = angles.get(k);
        float center = radii.get(k);
        float sumSq = 0f;
        int count = 0;
        for (int j = 0; j < angles.size(); j++) {
            if (j == k)
                continue;
            if (Math.abs(radii.get(j) - center) > stabilityWindow)
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

    public int applyBoundaryConstraints(boolean[] faceConstrained, float[] faceConstraintAngle) {
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        int addedConstraints = 0;
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
            addedConstraints += constrainBothFacesOfEdge(eId, edgeDir, faceConstrained, faceConstraintAngle);
        }
        return addedConstraints;
    }

    public int constrainBothFacesOfEdge(int eId, Vector3f edgeDirWorld,
            boolean[] faceConstrained,
            float[] faceConstraintAngle) {
        int he = mesh.edgeHalfEdge(eId);
        int twin = mesh.halfEdgeTwin(he);
        int addedConstraints = 0;
        for (int hePick : new int[] { he, twin }) {
            int fId = mesh.halfEdgeFace(hePick);
            if (fId == MeshTopology.NONE)
                continue; // boundary side of a boundary edge
            int fAi = faceIdToActive.get(fId);
            float angle = projectDirectionToFaceAngle(edgeDirWorld, fAi);
            if (!faceConstrained[fAi])
                addedConstraints++;
            faceConstrained[fAi] = true;
            faceConstraintAngle[fAi] = canonicalizeMod(angle, (float) (Math.PI / 2.0));
        }
        return addedConstraints;
    }

    /*
     * A3. Voronoi spanning forest in the dual graph
     */

    public Set<Integer> buildVoronoiSpanningForest(boolean[] faceConstrained) {
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

    public final class SmoothEnergySystem {
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
        int[] chordOfEdge;
        int[] edgeOfChord;
        int chordCount;

        // Solution storage.
        float[] solutionTheta;
        float[] solutionPeriod;
        boolean[] periodFixed;
        boolean[] fixedVariables;
        double[] solution;
        NormalMatrix normalMatrix;
        AdaptiveSolver.Options adaptiveOptions;
        boolean batchRoundingEnabled;
        int roundBatchSize;
        double roundBatchTol;
        int localGsConverged;
        int cgConverged;
        int directFallbacks;
        int failedSolves;
        int totalLocalGsIterations;
        int totalCgIterations;
        int localGsCapHits;
        int totalLocalInitialQueueSize;
        int maxLocalQueueSize;
        double maxLocalCapResidual;
        int localCapFaceRows;
        int localCapChordRows;
        int roundedPeriods;
        int batchCount;
        int totalBatchSize;
        int maxBatchSize;
        int batchRejectedByOverlap;
        int batchRejectedByRoundoff;
        String lastAdaptiveMethod = "none";
        double lastAdaptiveResidual;

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

            periodFixed = periodFixedInit.clone();
            chordOfEdge = new int[edgeCount];
            Arrays.fill(chordOfEdge, -1);
            chordCount = 0;
            for (int eAi = 0; eAi < edgeCount; eAi++) {
                if (!periodFixed[eAi]) {
                    chordOfEdge[eAi] = chordCount++;
                }
            }
            edgeOfChord = new int[chordCount];
            for (int eAi = 0; eAi < edgeCount; eAi++) {
                int chord = chordOfEdge[eAi];
                if (chord >= 0) {
                    edgeOfChord[chord] = eAi;
                }
            }

            solutionTheta = new float[faceCount];
            solutionPeriod = new float[edgeCount];
            solution = new double[faceCount + chordCount];
            fixedVariables = new boolean[faceCount + chordCount];
            for (int fAi = 0; fAi < faceCount; fAi++) {
                solutionTheta[fAi] = faceConstrained[fAi] ? faceConstraintAngle[fAi] : 0f;
                solution[fAi] = solutionTheta[fAi];
                fixedVariables[fAi] = faceConstrained[fAi];
            }
            for (int eAi = 0; eAi < edgeCount; eAi++) {
                solutionPeriod[eAi] = periodFixed[eAi] ? periodValue[eAi] : 0f;
                int chord = chordOfEdge[eAi];
                if (chord >= 0) {
                    solution[faceCount + chord] = solutionPeriod[eAi];
                }
            }
            normalMatrix = new NormalMatrix();
            adaptiveOptions = new AdaptiveSolver.Options();
            adaptiveOptions.localMaxIterations = 5000;
            adaptiveOptions.localTolerance = 1e-6;
            adaptiveOptions.cgMaxIterations = 100;
            adaptiveOptions.cgTolerance = 1e-7;
            adaptiveOptions.useDirectFallback = true;
            batchRoundingEnabled = true;
            roundBatchSize = 1;
            roundBatchTol = 1e-3;
        }

        void solveGreedyMIP() {
            lastAdaptiveMethod = "BOOTSTRAP_DIRECT_PENDING";
            lastAdaptiveResidual = Double.NaN;
            updateLastDiagnostics();
            solveRelaxed(-1);
            updateLastDiagnostics();
            int[] roundedVariables = new int[roundBatchSize];
            int[] roundedEdges = new int[roundBatchSize];
            int[] patch = new int[normalMatrix.size()];
            boolean[] patchMarked = new boolean[normalMatrix.size()];
            boolean[] candidateMarked = new boolean[normalMatrix.size()];
            BatchCandidate[] candidates = new BatchCandidate[chordCount];
            while (true) {
                int candidateCount = buildBatchCandidates(candidates);
                if (candidateCount == 0)
                    break;
                int batchSize = selectRoundingBatch(candidates, candidateCount,
                        roundedVariables, roundedEdges, patch, patchMarked, candidateMarked);
                if (batchSize == 0) {
                    break;
                }
                for (int i = 0; i < batchSize; i++) {
                    int variable = roundedVariables[i];
                    int eAi = roundedEdges[i];
                    int rounded = (int) Math.round(solution[variable]);
                    double roundoffAmount = Math.abs(solution[variable] - rounded);
                    // System.err.printf("[round] var=%d roundoff=%.6f%n", variable,
                    // roundoffAmount);
                    periodFixed[eAi] = true;
                    periodValue[eAi] = rounded;
                    solutionPeriod[eAi] = rounded;
                    solution[variable] = rounded;
                    fixedVariables[variable] = true;
                }
                roundedPeriods += batchSize;
                batchCount++;
                totalBatchSize += batchSize;
                maxBatchSize = Math.max(maxBatchSize, batchSize);
                lastAdaptiveMethod = batchSize == 1 ? "ROUND_LOCAL_GS_PENDING" : "BATCH_LOCAL_GS_PENDING";
                lastAdaptiveResidual = Double.NaN;
                updateLastDiagnostics();
                solveRelaxed(roundedVariables, batchSize);
                updateLastDiagnostics();
            }
            updateLastDiagnostics();
        }

        public int buildBatchCandidates(BatchCandidate[] candidates) {
            int candidateCount = 0;
            int rejectedByRoundoff = 0;
            BatchCandidate best = null;
            for (int chord = 0; chord < chordCount; chord++) {
                int eAi = edgeOfChord[chord];
                if (periodFixed[eAi]) {
                    continue;
                }
                double value = solution[faceCount + chord];
                BatchCandidate candidate = new BatchCandidate(
                        chord, eAi, Math.abs(value - Math.rint(value)));
                if (best == null || candidate.roundoff < best.roundoff) {
                    best = candidate;
                }
                if (roundBatchSize == 1 || candidate.roundoff <= roundBatchTol) {
                    candidates[candidateCount++] = candidate;
                } else {
                    rejectedByRoundoff++;
                }
            }
            if (roundBatchSize == 1) {
                if (best == null) {
                    return 0;
                }
                candidates[0] = best;
                return 1;
            }
            if (candidateCount == 0) {
                if (best == null) {
                    return 0;
                }
                candidates[0] = best;
                return 1;
            }
            Arrays.sort(candidates, 0, candidateCount,
                    (a, b) -> Double.compare(a.roundoff, b.roundoff));
            batchRejectedByRoundoff += rejectedByRoundoff;
            return candidateCount;
        }

        public int selectRoundingBatch(BatchCandidate[] candidates,
                int candidateCount,
                int[] roundedVariables,
                int[] roundedEdges,
                int[] candidatePatch,
                boolean[] selectedPatch,
                boolean[] candidateMarked) {
            Arrays.fill(selectedPatch, false);
            int batchSize = 0;
            for (int i = 0; i < candidateCount && batchSize < roundBatchSize; i++) {
                BatchCandidate candidate = candidates[i];
                int variable = faceCount + candidate.chord;
                int patchCount = AdaptiveSolver.collectAffectedPatch(
                        normalMatrix, variable, fixedVariables, candidatePatch, candidateMarked);
                boolean overlaps = false;
                for (int p = 0; p < patchCount; p++) {
                    if (selectedPatch[candidatePatch[p]]) {
                        overlaps = true;
                        break;
                    }
                }
                if (overlaps) {
                    batchRejectedByOverlap++;
                } else {
                    roundedVariables[batchSize] = variable;
                    roundedEdges[batchSize] = candidate.edgeAi;
                    batchSize++;
                    for (int p = 0; p < patchCount; p++) {
                        selectedPatch[candidatePatch[p]] = true;
                    }
                }
                for (int p = 0; p < patchCount; p++) {
                    candidateMarked[candidatePatch[p]] = false;
                }
            }
            return batchSize;
        }

        /**
         * Continuous L2 solve with currently-fixed variables held constant. Uses the
         * BZK09 adaptive ladder: local GS, then CG, then direct fallback.
         */
        void solveRelaxed(int roundedVariable) {
            if (roundedVariable < 0) {
                solveRelaxed((int[]) null, 0);
                return;
            }
            solveRelaxed(new int[] { roundedVariable }, 1);
        }

        void solveRelaxed(int[] roundedVariables, int roundedCount) {
            AdaptiveSolver.Result result = AdaptiveSolver.solveAfterRounding(
                    normalMatrix, normalMatrix.rhs, solution, fixedVariables,
                    roundedVariables, roundedCount, adaptiveOptions);
            solution = result.x();
            AdaptiveSolver.Stats stats = result.stats();
            switch (stats.method()) {
            case LOCAL_GAUSS_SEIDEL -> localGsConverged++;
            case CONJUGATE_GRADIENT -> cgConverged++;
            case DIRECT -> directFallbacks++;
            case FAILED -> failedSolves++;
            }
            if (!stats.converged() && stats.method() != AdaptiveSolver.Method.FAILED) {
                failedSolves++;
            }
            if (stats.localHitCap()) {
                localGsCapHits++;
                if (stats.capResidualRow() >= 0 && stats.capResidualRow() < faceCount) {
                    localCapFaceRows++;
                } else if (stats.capResidualRow() >= faceCount) {
                    localCapChordRows++;
                }
                maxLocalCapResidual = Math.max(maxLocalCapResidual, stats.capResidualNorm());
            }
            totalLocalInitialQueueSize += stats.initialQueueSize();
            maxLocalQueueSize = Math.max(maxLocalQueueSize, stats.maxQueueSize());
            lastAdaptiveMethod = stats.method().name();
            lastAdaptiveResidual = stats.residualNorm();
            totalLocalGsIterations += stats.localIterations();
            totalCgIterations += stats.cgIterations();
            for (int fAi = 0; fAi < faceCount; fAi++) {
                solutionTheta[fAi] = (float) solution[fAi];
            }
            for (int eAi = 0; eAi < edgeCount; eAi++) {
                int chord = chordOfEdge[eAi];
                if (chord >= 0) {
                    solutionPeriod[eAi] = (float) solution[faceCount + chord];
                } else {
                    solutionPeriod[eAi] = periodValue[eAi];
                }
            }
        }

        public void updateLastDiagnostics() {
            int remaining = 0;
            for (int eAi = 0; eAi < edgeCount; eAi++) {
                if (!periodFixed[eAi]) {
                    remaining++;
                }
            }
            double avgBatch = batchCount > 0 ? (double) totalBatchSize / batchCount : 0.0;
            double avgInitialQueue = batchCount > 0
                    ? (double) totalLocalInitialQueueSize / batchCount
                    : 0.0;
            lastDiagnostics = String.format(
                    "[cross-field] failure-diagnostics rounded=%d remaining=%d batches=%d avgBatch=%.3f maxBatch=%d rejectOverlap=%d rejectRoundoff=%d localGS=%d cg=%d direct=%d failed=%d localIters=%d cgIters=%d localCapHits=%d capFace=%d capChord=%d maxCapResidual=%.6g avgSeedQueue=%.3f maxQueue=%d lastMethod=%s lastResidual=%.6g",
                    roundedPeriods, remaining, batchCount, avgBatch, maxBatchSize,
                    batchRejectedByOverlap, batchRejectedByRoundoff, localGsConverged,
                    cgConverged, directFallbacks, failedSolves, totalLocalGsIterations,
                    totalCgIterations, localGsCapHits, localCapFaceRows, localCapChordRows,
                    maxLocalCapResidual, avgInitialQueue, maxLocalQueueSize,
                    lastAdaptiveMethod, lastAdaptiveResidual);
        }

        public final class BatchCandidate {
            final int chord;
            final int edgeAi;
            final double roundoff;

            BatchCandidate(int chord, int edgeAi, double roundoff) {
                this.chord = chord;
                this.edgeAi = edgeAi;
                this.roundoff = roundoff;
            }
        }

        public final class NormalMatrix implements AdaptiveSolver.Matrix {
            final int variableCount;
            final double[] diag;
            final double[] rhs;
            final int[] rowStart;
            final int[] rowCol;
            final double[] rowVal;

            NormalMatrix() {
                variableCount = faceCount + chordCount;
                diag = new double[variableCount];
                rhs = new double[variableCount];

                int[] degree = new int[variableCount];
                for (int r = 0; r < rowCount; r++) {
                    int fi = rowFaceI[r];
                    int fj = rowFaceJ[r];
                    int chord = chordOfEdge[rowEdgeAi[r]];
                    if (chord >= 0) {
                        int pe = faceCount + chord;
                        degree[fi] += 2;
                        degree[fj] += 2;
                        degree[pe] += 2;
                    } else {
                        degree[fi] += 1;
                        degree[fj] += 1;
                    }
                }

                rowStart = new int[variableCount + 1];
                for (int i = 0; i < variableCount; i++) {
                    rowStart[i + 1] = rowStart[i] + degree[i];
                }
                rowCol = new int[rowStart[variableCount]];
                rowVal = new double[rowStart[variableCount]];
                int[] cursor = rowStart.clone();
                double halfPi = Math.PI * 0.5;

                for (int r = 0; r < rowCount; r++) {
                    int fi = rowFaceI[r];
                    int fj = rowFaceJ[r];
                    int eAi = rowEdgeAi[r];
                    int chord = chordOfEdge[eAi];
                    int pe = chord >= 0 ? faceCount + chord : -1;
                    double k = rowKappa[r] + (chord < 0 ? halfPi * periodValue[eAi] : 0.0);

                    diag[fi] += 1.0;
                    diag[fj] += 1.0;

                    addOffDiagonal(cursor, fi, fj, -1.0);
                    addOffDiagonal(cursor, fj, fi, -1.0);

                    rhs[fi] -= k;
                    rhs[fj] += k;
                    if (pe >= 0) {
                        diag[pe] += halfPi * halfPi;

                        addOffDiagonal(cursor, fi, pe, halfPi);
                        addOffDiagonal(cursor, pe, fi, halfPi);
                        addOffDiagonal(cursor, fj, pe, -halfPi);
                        addOffDiagonal(cursor, pe, fj, -halfPi);

                        rhs[pe] -= halfPi * k;
                    }
                }
            }

            public void addOffDiagonal(int[] cursor, int row, int col, double value) {
                int i = cursor[row]++;
                rowCol[i] = col;
                rowVal[i] = value;
            }

            @Override
            public int size() {
                return variableCount;
            }

            @Override
            public double diag(int row) {
                return diag[row];
            }

            @Override
            public int rowStart(int row) {
                return rowStart[row];
            }

            @Override
            public int rowEnd(int row) {
                return rowStart[row + 1];
            }

            @Override
            public int column(int cursor) {
                return rowCol[cursor];
            }

            @Override
            public double value(int cursor) {
                return rowVal[cursor];
            }
        }

        void unpackInto(CrossField cf) {
            cf.theta = solutionTheta.clone();
            cf.periodJump = new int[edgeCount];
            for (int eAi = 0; eAi < edgeCount; eAi++) {
                int eId = mesh.edgeIdAt(eAi);
                if (mesh.isBoundaryEdge(eId)) {
                    cf.periodJump[eAi] = 0;
                    continue;
                }
                int chord = chordOfEdge[eAi];
                if (chord >= 0) {
                    // Free chord: use the value we actually solved/rounded for
                    cf.periodJump[eAi] = (int) Math.round(solutionPeriod[eAi]);
                } else {
                    // Pre-fixed (forest tree or constrained pair)
                    cf.periodJump[eAi] = periodValue[eAi];
                }
            }
        }
    }

    /*
     * B. Singularities
     *
     * Walk the 1-ring of v. For each outgoing half-edge `he`, look up its edge eId.
     * If `he` is the "natural" half-edge of eId (edgeHalfEdge(eId) == he),
     * contribute +(κ_e, p_e); otherwise contribute −(κ_e, p_e).
     *
     * BZK09/Ray08: I(v) = 1/(2π) · (angleDefect(v) + signed κ-walk) + 1/4 · signed
     * p-walk. We store 4·I as an integer.
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

            float signedKappaSum = 0f;
            int signedPeriodSum = 0;
            int outCount = mesh.vertexOutgoingHalfEdgeCount(vId);
            for (int i = 0; i < outCount; i++) {
                int he = mesh.vertexOutgoingHalfEdgeAt(vId, i);
                int eId = mesh.halfEdgeEdge(he);
                if (mesh.isBoundaryEdge(eId))
                    continue;
                int eAi = edgeIdToActive.get(eId);
                int sign = (he == mesh.edgeHalfEdge(eId)) ? 1 : -1;
                signedKappaSum += sign * kappa[eAi];
                signedPeriodSum += sign * periodJump[eAi];
            }

            float iTimes4 = (float) (((defect + signedKappaSum) * 2.0) / Math.PI) + signedPeriodSum;
            int iQuarter = Math.round(iTimes4);
            singularityIndexQuarter[vAi] = iQuarter;
            if (iQuarter != 0) {
                singularities.add(new Singularity(vId, iQuarter));
            }
        }
        return singularities;
    }

    public float interiorAngleAtVertex(int fId, int vId, Vector3f vPos, Vector3f a, Vector3f b) {
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

    public void printProblemDiagnostics(float averageEdgeLength,
            float boundingSphereRadius,
            float curvatureK,
            int curvatureConstraints,
            int boundaryConstraints,
            int totalConstraints,
            int forestEdgeCount,
            int boundaryPeriodFixes,
            int constrainedPeriodFixes,
            boolean[] initiallyFixedPeriods,
            SmoothEnergySystem system) {
        int freePeriods = initiallyFixedPeriods.length - countTrue(initiallyFixedPeriods);
        int freeTheta = mesh.faceCount() - totalConstraints;
        int fullDim = mesh.faceCount() + freePeriods;
        int reducedDim = freeTheta + freePeriods;
        int expectedForestEdges = mesh.faceCount() - totalConstraints;
        System.out.printf(
                "[cross-field] constraints curvature=%d boundary=%d totalFaces=%d/%d%n",
                curvatureConstraints, boundaryConstraints, totalConstraints, mesh.faceCount());
        CurvatureConstraintStats cs = lastCurvatureStats;
        System.out.printf(
                "[cross-field] curvatureStats vertices=%d boundary=%d noSamples=%d failTau=%d failMean=%d failInterval=%d valid=%d failJitter=%d acceptedVertices=%d addedFaces=%d faceCollisions=%d fullOneRings=%d avgJitter=%.6g maxJitter=%.6g%n",
                cs.verticesVisited, cs.boundaryVertices, cs.noCurvatureSamples,
                cs.failedAnisotropy, cs.failedMeanCurvature, cs.failedInterval,
                cs.validIntervals, cs.failedJitter, cs.acceptedVertices,
                cs.addedConstraints, cs.faceCollisionCandidates,
                cs.allIncidentFacesConstrained, cs.averageJitter(), cs.maxJitter);
        System.out.printf(
                "[cross-field] bzk tauMin=%.3f K=%.6g avgEdge=%.6g targetH=%.6g bboxDiag=%.6g hFraction=%.3g boundingRadius=%.6g r0=%.6g r1=%.6g%n",
                tauMin, curvatureK, averageEdgeLength, resolvedTargetEdgeLength(averageEdgeLength),
                computeBoundingBoxDiagonal(), targetEdgeLengthFractionOfBounds,
                boundingSphereRadius, radiusStartMul * averageEdgeLength,
                resolvedTargetEdgeLength(averageEdgeLength));
        System.out.printf(
                "[cross-field] mip rows=%d fixedPeriods=%d freePeriods=%d forestEdges=%d%n",
                system.rowCount, countTrue(initiallyFixedPeriods), freePeriods, forestEdgeCount);
        System.out.printf(
                "[cross-field] bzk reduction constrainedFaces=%d expectedForest=%d actualForest=%d constrainedEdgeFixes=%d boundaryFixes=%d%n",
                totalConstraints, expectedForestEdges, forestEdgeCount,
                constrainedPeriodFixes, boundaryPeriodFixes);
        System.out.printf(
                "[cross-field] solver localMaxIter=%d localTol=%.3g cgMaxIter=%d cgTol=%.3g directAllowed=%s bootstrapDirect=true directUsed=%d%n",
                system.adaptiveOptions.localMaxIterations,
                system.adaptiveOptions.localTolerance,
                system.adaptiveOptions.cgMaxIterations,
                system.adaptiveOptions.cgTolerance,
                system.adaptiveOptions.useDirectFallback,
                system.directFallbacks);
        System.out.printf(
                "[cross-field] rounding batchEnabled=%s batchSize=%d batchTol=%.3g%n",
                system.batchRoundingEnabled, system.roundBatchSize, system.roundBatchTol);
        System.out.printf(
                "[cross-field] bzk09-rocker-target dim=32843 int=12064 is=385 ds=7 time=7.6s; ours fullDim=%d reducedDim=%d int=%d%n",
                fullDim, reducedDim, freePeriods);
    }

    public void printSolutionDiagnostics(SmoothEnergySystem system) {
        SmoothnessStats stats = smoothnessStats();
        double avgBatch = system.batchCount > 0
                ? (double) system.totalBatchSize / system.batchCount
                : 0.0;
        double avgInitialQueue = system.batchCount > 0
                ? (double) system.totalLocalInitialQueueSize / system.batchCount
                : 0.0;
        System.out.printf(
                "[cross-field] adaptive localGS=%d cg=%d direct=%d failed=%d localIters=%d cgIters=%d localCapHits=%d capFace=%d capChord=%d maxCapResidual=%.6g avgSeedQueue=%.3f maxQueue=%d batches=%d avgBatch=%.3f maxBatch=%d rejectOverlap=%d rejectRoundoff=%d%n",
                system.localGsConverged, system.cgConverged, system.directFallbacks,
                system.failedSolves, system.totalLocalGsIterations, system.totalCgIterations,
                system.localGsCapHits, system.localCapFaceRows, system.localCapChordRows,
                system.maxLocalCapResidual, avgInitialQueue, system.maxLocalQueueSize,
                system.batchCount, avgBatch, system.maxBatchSize,
                system.batchRejectedByOverlap, system.batchRejectedByRoundoff);
        System.out.printf(
                "[cross-field] smoothEnergy=%.6g maxResidual=%.6g singularityHistogram=%s%n",
                stats.energy, stats.maxResidual, singularityHistogram());
    }

    public SmoothnessStats smoothnessStats() {
        float halfPi = (float) (Math.PI / 2.0);
        double energy = 0.0;
        double maxResidual = 0.0;
        int[] buckets = new int[8];
        for (int eAi = 0; eAi < mesh.edgeCount(); eAi++) {
            int eId = mesh.edgeIdAt(eAi);
            if (mesh.isBoundaryEdge(eId))
                continue;
            int he = mesh.edgeHalfEdge(eId);
            int twin = mesh.halfEdgeTwin(he);
            int fi = faceIdToActive.get(mesh.halfEdgeFace(he));
            int fj = faceIdToActive.get(mesh.halfEdgeFace(twin));
            double residual = theta[fi] + kappa[eAi] + halfPi * periodJump[eAi] - theta[fj];
            energy += residual * residual;
            maxResidual = Math.max(maxResidual, Math.abs(residual));
            int b = Math.min(7, (int) (Math.abs(residual) / (Math.PI / 32)));
            buckets[b]++;
        }
        System.err.println("[res] " + Arrays.toString(buckets));
        return new SmoothnessStats(energy, maxResidual);
    }

    public String singularityHistogram() {
        Map<Integer, Integer> histogram = new HashMap<>();
        for (Singularity s : singularities) {
            histogram.merge(s.index4(), 1, Integer::sum);
        }
        return histogram.toString();
    }

    public static int countTrue(boolean[] values) {
        int count = 0;
        for (boolean value : values) {
            if (value)
                count++;
        }
        return count;
    }

    public static final class SmoothnessStats {
        final double energy;
        final double maxResidual;
        int[] buckets = new int[8];

        SmoothnessStats(double energy, double maxResidual) {
            this.energy = energy;
            this.maxResidual = maxResidual;
        }
    }

    public float computeAverageEdgeLength() {
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

    public float computeBoundingSphereRadius() {
        int vertexCount = mesh.vertexCount();
        if (vertexCount == 0)
            return 1f;
        Vector3f p = new Vector3f();
        mesh.vertexPosition(mesh.vertexIdAt(0), p);
        float minX = p.x, minY = p.y, minZ = p.z;
        float maxX = p.x, maxY = p.y, maxZ = p.z;
        for (int vAi = 1; vAi < vertexCount; vAi++) {
            mesh.vertexPosition(mesh.vertexIdAt(vAi), p);
            if (p.x < minX)
                minX = p.x;
            if (p.y < minY)
                minY = p.y;
            if (p.z < minZ)
                minZ = p.z;
            if (p.x > maxX)
                maxX = p.x;
            if (p.y > maxY)
                maxY = p.y;
            if (p.z > maxZ)
                maxZ = p.z;
        }
        float dx = maxX - minX;
        float dy = maxY - minY;
        float dz = maxZ - minZ;
        return 0.5f * (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public float faceArea(int fId) {
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

    public static float canonicalizeMod(float angle, float mod) {
        float r = (float) (angle - mod * Math.floor(angle / mod));
        if (r < 0)
            r += mod;
        return r;
    }
}
