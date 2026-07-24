package ixdar.geometry.mesh.quadlayout.crossfield.constraint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh.EdgeFaceIds;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.crossfield.DijkstraNode;

public class CurvatureConstraints {

    public static final float CURVATURE_RADIUS_MULTIPLE = 10.0f;
    public static final float CANDIDATE_SUPPRESSION_RADIUS_MULTIPLE = 4.0f;
    public static final float CANDIDATE_CONFLICT_RADIUS_MULTIPLE = 4.0f;
    public static final float TRANSPORT_DUPLICATE_THRESHOLD = (float) (Math.PI / 16.0);
    public static final float TRANSPORT_CONFLICT_THRESHOLD = (float) (Math.PI / 8.0);

    /**
     * Geometric ratio between consecutive radii in the radius series.
     */
    public static final float RADIUS_RATIO = (float) Math.sqrt(2.0);

    /**
     * Scale used to reject nearly flat regions before adding curvature-based
     * cross-field constraints. The actual threshold is this value divided by the
     * mesh bounding-sphere radius, so it scales with model size.
     */
    public static final float CURVATURE_SCALE_K = 0.1f;

    /**
     * Minimum 0-to-1 bending contrast before the strongest bend direction is
     * trusted as a cross-field constraint. A value near 0 means the surface bends
     * similarly in every direction; a value near 1 means one direction dominates.
     */
    public static final float MINIMUM_CURVATURE_CONTRAST = 0.8f;

    /**
     * Minimum {@code faceNormal · vertexNormal} required before a curvature
     * constraint at the vertex pins the face. Below this the tangent planes diverge
     * enough that the projected direction is inconsistent between adjacent pinned
     * faces. cos(15°) ≈ 0.966.
     */
    public static final float FACE_VERTEX_NORMAL_ALIGNMENT_FLOOR = 0.966f;

    /**
     * Maximum angle (radians) between a candidate's curvature direction and a
     * nearby pinned source's direction, measured modulo the cross-field's π/2
     * symmetry, before they're considered to conflict. π/8 ≈ 22.5° — half a
     * quarter-turn, so any axis pair within this tolerance is "the same"
     * cross-field axis up to one quarter-turn.
     */
    public static final float CONFLICT_ANGLE_THRESHOLD = (float) (Math.PI / 8.0);

    public int lastCandidateCount;
    public int lastAcceptedCount;

    /**
     * Reusable scratch for curvature-disk searches. Each search increments
     * {@code curvatureStamp}; arrays holding that stamp are treated as part of the
     * current disk without clearing all mesh-sized arrays between searches.
     */
    int[] vertexInDiskStamp;
    int[] faceInDiskStamp;
    int[] edgeProcessedStamp;
    float[] vertexDistance;
    int[] visitedVertexIds;

    /**
     * Per-disk geometry recorded once during the single max-radius walk so every
     * radius in the series reuses it. Each is guarded by the same stamp as its
     * index domain ({@code faceInDiskStamp} for faces, {@code edgeProcessedStamp}
     * for edges), so stale entries from earlier walks are never read.
     */
    float[] faceInclusionDistance;
    int[] diskFaceIds;
    float[] edgeInclusionDistance;
    float[] edgeContribution00;
    float[] edgeContribution01;
    float[] edgeContribution11;
    int[] diskEdgeIds;

    /**
     * Flat per-vertex/face/edge adjacency and geometry built once by
     * {@link #buildAdjacencyCaches()}. Neighbor lists are compressed-sparse-row:
     * {@code neighborStart[v]..neighborStart[v + 1]} is vertex {@code v}'s slice of
     * the parallel neighbor arrays. The per-edge {@code edgeWeight} and unit
     * {@code edgeDir*} hold the center-independent part of the Cohen-Steiner
     * contribution.
     */
    int[] neighborStart;
    int[] neighborVertexId;
    float[] neighborEdgeLength;
    int[] vertexFaceStart;
    int[] vertexFaceId;
    int[] vertexEdgeStart;
    int[] vertexEdgeId;
    int[] faceVertex0;
    int[] faceVertex1;
    int[] faceVertex2;
    float[] faceArea;
    int[] edgeStartVertex;
    int[] edgeEndVertex;
    int[] edgeFaceA;
    int[] edgeFaceB;
    boolean[] edgeContributes;
    float[] edgeWeight;
    float[] edgeDirX;
    float[] edgeDirY;
    float[] edgeDirZ;

    int faceCount;
    int vertexCount;
    int edgeCount;

    /**
     * The mesh that the curvature constraints are applied to.
     */
    HalfEdgeMesh mesh;

    /**
     * The cross field that the curvature constraints are applied to.
     */
    CrossField crossField;

    long walkNanos;
    long faceCollectNanos;
    long edgeCollectNanos;
    long bucketNanos;
    int integrateCalls;

    private int curvatureStamp = 0;

    /**
     * Allocate per-vertex/face/edge scratch buffers sized to the cross field.
     *
     * @param mesh       half-edge mesh the disks are walked on
     * @param crossField cross field whose vertex/face/edge counts size the scratch
     *                   buffers
     */
    public CurvatureConstraints(HalfEdgeMesh mesh, CrossField crossField) {
        this.faceCount = mesh.faceCount();
        this.edgeCount = mesh.edgeCount();
        this.vertexCount = mesh.vertexCount();
        this.vertexInDiskStamp = new int[vertexCount];
        this.faceInDiskStamp = new int[faceCount];
        this.edgeProcessedStamp = new int[edgeCount];
        this.vertexDistance = new float[vertexCount];
        this.visitedVertexIds = new int[vertexCount];
        this.faceInclusionDistance = new float[faceCount];
        this.diskFaceIds = new int[faceCount];
        this.edgeInclusionDistance = new float[edgeCount];
        this.edgeContribution00 = new float[edgeCount];
        this.edgeContribution01 = new float[edgeCount];
        this.edgeContribution11 = new float[edgeCount];
        this.diskEdgeIds = new int[edgeCount];
        this.mesh = mesh;
        this.crossField = crossField;
    }

    /**
     * Directional constraints from principal curvature.
     *
     * @param targetQuadEdgeLength target quad edge length
     *
     * @return number of newly constrained faces
     */
    public int applyCurvatureConstraints(float targetQuadEdgeLength) {

        int addedConstraints = 0;
        neighborStart = new int[vertexCount + 1];
        vertexFaceStart = new int[vertexCount + 1];
        vertexEdgeStart = new int[vertexCount + 1];
        for (int v = 0; v < vertexCount; v++) {
            neighborStart[v + 1] = neighborStart[v] + mesh.vertexOutgoingHalfEdgeCount(v);
            vertexFaceStart[v + 1] = vertexFaceStart[v] + mesh.vertexFaceCount(v);
            vertexEdgeStart[v + 1] = vertexEdgeStart[v] + mesh.vertexEdgeCount(v);
        }
        neighborVertexId = new int[neighborStart[vertexCount]];
        neighborEdgeLength = new float[neighborStart[vertexCount]];
        vertexFaceId = new int[vertexFaceStart[vertexCount]];
        vertexEdgeId = new int[vertexEdgeStart[vertexCount]];

        Vector3f from = new Vector3f();
        Vector3f to = new Vector3f();
        for (int v = 0; v < vertexCount; v++) {
            mesh.vertexPosition(v, from);
            int outCount = mesh.vertexOutgoingHalfEdgeCount(v);
            for (int i = 0; i < outCount; i++) {
                int halfEdge = mesh.vertexOutgoingHalfEdgeAt(v, i);
                int neighbor = mesh.halfEdgeEndVertex(halfEdge);
                mesh.vertexPosition(neighbor, to);
                float dx = to.x - from.x;
                float dy = to.y - from.y;
                float dz = to.z - from.z;
                neighborVertexId[neighborStart[v] + i] = neighbor;
                neighborEdgeLength[neighborStart[v] + i] = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
            int incidentFaceCount = mesh.vertexFaceCount(v);
            for (int i = 0; i < incidentFaceCount; i++) {
                vertexFaceId[vertexFaceStart[v] + i] = mesh.vertexFaceAt(v, i);
            }
            int incidentEdgeCount = mesh.vertexEdgeCount(v);
            for (int i = 0; i < incidentEdgeCount; i++) {
                vertexEdgeId[vertexEdgeStart[v] + i] = mesh.vertexEdgeAt(v, i);
            }
        }

        faceVertex0 = new int[faceCount];
        faceVertex1 = new int[faceCount];
        faceVertex2 = new int[faceCount];
        faceArea = new float[faceCount];
        for (int activeFace = 0; activeFace < faceCount; activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            faceVertex0[faceId] = mesh.faceVertexAt(faceId, 0);
            faceVertex1[faceId] = mesh.faceVertexAt(faceId, 1);
            faceVertex2[faceId] = mesh.faceVertexAt(faceId, 2);
            faceArea[faceId] = mesh.faceArea(faceId);
        }

        edgeStartVertex = new int[edgeCount];
        edgeEndVertex = new int[edgeCount];
        edgeFaceA = new int[edgeCount];
        edgeFaceB = new int[edgeCount];
        edgeContributes = new boolean[edgeCount];
        edgeWeight = new float[edgeCount];
        edgeDirX = new float[edgeCount];
        edgeDirY = new float[edgeCount];
        edgeDirZ = new float[edgeCount];
        Vector3f start = new Vector3f();
        Vector3f end = new Vector3f();
        for (int activeEdge = 0; activeEdge < edgeCount; activeEdge++) {
            EdgeFaceIds edge = mesh.edgeFaceIds(activeEdge);
            int edgeId = edge.edgeId;
            edgeStartVertex[edgeId] = edge.edgeStartVertex;
            edgeEndVertex[edgeId] = edge.edgeEndVertex;
            if (mesh.isBoundaryEdge(edgeId)) {
                continue;
            }
            mesh.vertexPosition(edge.edgeStartVertex, start);
            mesh.vertexPosition(edge.edgeEndVertex, end);
            float dx = end.x - start.x;
            float dy = end.y - start.y;
            float dz = end.z - start.z;
            float edgeLength = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (edgeLength < CrossField.EPSILON) {
                continue;
            }
            Vector3f leftNormal = mesh.faceNormal(edge.faceA);
            Vector3f rightNormal = mesh.faceNormal(edge.faceB);
            float cosDihedral = Math.max(-1f, Math.min(1f, leftNormal.dot(rightNormal)));
            float crossX = leftNormal.y * rightNormal.z - leftNormal.z * rightNormal.y;
            float crossY = leftNormal.z * rightNormal.x - leftNormal.x * rightNormal.z;
            float crossZ = leftNormal.x * rightNormal.y - leftNormal.y * rightNormal.x;
            float sinDihedral = (crossX * dx + crossY * dy + crossZ * dz) / edgeLength;
            float dihedralAngle = (float) Math.atan2(sinDihedral, cosDihedral);
            edgeFaceA[edgeId] = edge.faceA;
            edgeFaceB[edgeId] = edge.faceB;
            edgeContributes[edgeId] = true;
            edgeWeight[edgeId] = dihedralAngle * edgeLength;
            edgeDirX[edgeId] = dx / edgeLength;
            edgeDirY[edgeId] = dy / edgeLength;
            edgeDirZ[edgeId] = dz / edgeLength;
        }

        float averageEdgeLength = mesh.computeAverageEdgeLength();
        float curvatureK = CURVATURE_SCALE_K / Math.max(mesh.computeBoundingSphereRadius(), CrossField.EPSILON);
        Vector3f e1 = new Vector3f();
        Vector3f e2 = new Vector3f();

        List<Float> radii = new ArrayList<>();
        float startRadius = averageEdgeLength;
        float endRadius = targetQuadEdgeLength;
        float stabilityWindow = endRadius / 4.0f;
        for (float r = startRadius; r <= endRadius + CrossField.EPSILON; r *= RADIUS_RATIO) {
            radii.add(r);
        }
        float[] radiusValues = new float[radii.size()];
        for (int i = 0; i < radiusValues.length; i++) {
            radiusValues[i] = radii.get(i);
        }

        System.err.printf("[radii] count=%d stabilityWindow=%.4g%n", radiusValues.length, stabilityWindow);
        for (int k = 0; k < radiusValues.length; k++) {
            int inWin = 0;
            for (int j = 0; j < radiusValues.length; j++) {
                if (Math.abs(radiusValues[j] - radiusValues[k]) <= stabilityWindow)
                    inWin++;
            }
            System.err.printf("  r[%d]=%.4g inWindow=%d%n", k, radiusValues[k], inWin);
        }

        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f p2 = new Vector3f();
        Vector3f centroid = new Vector3f();
        Vector3f fNormal = new Vector3f();
        int[] seedVerts = new int[3];
        float[] seedDists = new float[3];

        List<Float> faceMeanH = new ArrayList<>();
        List<Float> pinnedGap = new ArrayList<>();

        for (int faceAi = 0; faceAi < faceCount; faceAi++) {
            int faceId = mesh.faceIdAt(faceAi);
            if (crossField.faceConstrained[faceAi]) {
                continue; // leave feature/boundary hard pins alone
            }
            int fv0 = faceVertex0[faceId];
            int fv1 = faceVertex1[faceId];
            int fv2 = faceVertex2[faceId];
            if (mesh.isBoundaryVertex(fv0) || mesh.isBoundaryVertex(fv1) || mesh.isBoundaryVertex(fv2)) {
                continue;
            }
            mesh.vertexPosition(fv0, p0);
            mesh.vertexPosition(fv1, p1);
            mesh.vertexPosition(fv2, p2);
            centroid.set(p0).add(p1).add(p2).mul(1f / 3f);
            fNormal.set(mesh.faceNormal(faceId));
            CrossField.arbitraryTangent(fNormal, e1);
            fNormal.cross(e1, e2).normalize();
            seedVerts[0] = fv0;
            seedVerts[1] = fv1;
            seedVerts[2] = fv2;
            seedDists[0] = centroid.distance(p0);
            seedDists[1] = centroid.distance(p1);
            seedDists[2] = centroid.distance(p2);

            List<Float> anglesMaxDir = new ArrayList<>();
            List<Float> kappaMaxList = new ArrayList<>();
            List<Float> kappaMinList = new ArrayList<>();
            List<Float> validRadii = new ArrayList<>();

            float[][] tensors = integrateCurvatureTensorAllRadii(seedVerts, seedDists, fNormal, e1, e2, radiusValues);
            for (int radiusIndex = 0; radiusIndex < radiusValues.length; radiusIndex++) {
                float[] T = tensors[radiusIndex];
                if (T == null)
                    continue;

                float t00 = T[0];
                float t01 = T[1];
                float t11 = T[2];
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
                if (Math.abs(t01) > CrossField.EPSILON) {
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
                kappaMaxList.add(eigBig);
                kappaMinList.add(eigSmall);
                anglesMaxDir.add(angle);
                validRadii.add(radiusValues[radiusIndex]);
            }
            if (!kappaMaxList.isEmpty()) {
                int li = kappaMaxList.size() - 1; // largest radius = most averaged
                faceMeanH.add(0.5f * (kappaMaxList.get(li) + kappaMinList.get(li)));
            }
            if (anglesMaxDir.isEmpty()) {
                continue;
            }

            int bestIdx = -1;
            float bestJitter = Float.POSITIVE_INFINITY;
            for (int k = 0; k < anglesMaxDir.size(); k++) {
                float center = validRadii.get(k);
                boolean isValid = true;
                for (int j = 0; j < validRadii.size(); j++) {
                    if (Math.abs(validRadii.get(j) - center) > stabilityWindow) {
                        continue;
                    }
                    float kmax = kappaMaxList.get(j);
                    float kmin = kappaMinList.get(j);
                    if (Math.abs(kmax) < CrossField.EPSILON) {
                        isValid = false;
                        break;
                    }
                    float curvatureConstrast = (Math.abs(kmax) - Math.abs(kmin)) / Math.abs(kmax);
                    float meanH = 0.5f * (kmax + kmin);
                    if (curvatureConstrast <= MINIMUM_CURVATURE_CONTRAST || Math.abs(meanH) <= curvatureK) {
                        isValid = false;
                        break;
                    }
                }
                if (!isValid) {
                    continue;
                }

                float ak = anglesMaxDir.get(k);
                float sumSq = 0f;
                int count = 0;
                for (int j = 0; j < anglesMaxDir.size(); j++) {
                    if (j == k)
                        continue;
                    if (Math.abs(validRadii.get(j) - center) > stabilityWindow)
                        continue;
                    float alpha = anglesMaxDir.get(j) - ak;
                    /** Wrap an angle to (−π/2, π/2]. */
                    float diff = (float) (alpha - Math.PI * Math.floor((alpha + Math.PI / 2.0) / Math.PI));
                    sumSq += diff * diff;
                    count++;
                }
                float jitter = 0f;
                if (count == 0) {
                    jitter = Float.POSITIVE_INFINITY;
                } else {
                    jitter = (float) Math.sqrt(sumSq / count);
                }
                if (jitter < bestJitter) {
                    bestJitter = jitter;
                    bestIdx = k;
                }
            }
            if (bestIdx < 0) {
                continue;
            }
            float constraintAngleInFrame = anglesMaxDir.get(bestIdx);
            float c = (float) Math.cos(constraintAngleInFrame);
            float s = (float) Math.sin(constraintAngleInFrame);
            Vector3f dirWorld = new Vector3f(
                    e1.x * c + e2.x * s,
                    e1.y * c + e2.y * s,
                    e1.z * c + e2.z * s);
            float angleInFace = mesh.projectDirectionToFaceAngle(dirWorld, faceAi,
                    crossField.faceY[faceAi], crossField.faceX[faceAi]);
            crossField.faceConstrained[faceAi] = true;
            crossField.faceConstraintAngle[faceAi] = CrossField.canonicalizeMod(angleInFace);
            crossField.faceConstraintSource[faceAi] = ConstraintSource.CURVATURE;
            pinnedGap.add(kappaMaxList.get(bestIdx) - kappaMinList.get(bestIdx)); // disc = absolute gap
            addedConstraints++;
        }
        if (!faceMeanH.isEmpty()) {
            float[] mh = new float[faceMeanH.size()];
            int above = 0;
            for (int i = 0; i < mh.length; i++) {
                mh[i] = Math.abs(faceMeanH.get(i));
                if (mh[i] > curvatureK)
                    above++;
            }
            Arrays.sort(mh);
            System.err.printf(
                    "[meanH] faces=%d curvatureK=%.4g  |meanH| min=%.4g median=%.4g max=%.4g  aboveK=%d (%.1f%%)%n",
                    mh.length, curvatureK, mh[0], mh[mh.length / 2], mh[mh.length - 1],
                    above, 100.0 * above / mh.length);
        }
        if (!pinnedGap.isEmpty()) {
            float[] g = new float[pinnedGap.size()];
            for (int i = 0; i < g.length; i++)
                g[i] = Math.abs(pinnedGap.get(i));
            Arrays.sort(g);
            System.err.printf("[pinned-gap] min=%.4g median=%.4g max=%.4g  (curvatureK=%.4g)%n",
                    g[0], g[g.length / 2], g[g.length - 1], curvatureK);
        }
        System.err.printf("[constraints] addedConstraints=%d / faces=%d (%.1f%%)%n",
                addedConstraints, faceCount, 100.0 * addedConstraints / faceCount);
        return addedConstraints;

    }

    /**
     * Cohen-Steiner integrated curvature tensor over geodesic disks at every radius
     * in {@code radiiAscending}, from one walk out to the largest radius. Entry
     * {@code i} is {@code [T00, T01, T11]} in the tangent basis, normalized by disk
     * triangle area, or {@code null} when that disk has no usable triangles.
     *
     * @param seedVertexIds  seed vertex ids the multi-source geodesic walk starts from
     * @param seedDistances  initial geodesic distance for each seed, parallel to {@code seedVertexIds}
     * @param centerNormal   center vertex normal
     * @param tangentE1      tangent basis vector 1
     * @param tangentE2      tangent basis vector 2 (= centerNormal × tangentE1)
     * @param radiiAscending geodesic-disk radii in ascending order; the last is the
     *                       walk's cutoff
     * @return one {@code [T00, T01, T11]} per radius, parallel to
     *         {@code radiiAscending}, each {@code null} when its disk has no usable
     *         triangles
     */
    public float[][] integrateCurvatureTensorAllRadii(int[] seedVertexIds, float[] seedDistances,
            Vector3f centerNormal, Vector3f tangentE1, Vector3f tangentE2, float[] radiiAscending) {
        int radiusCount = radiiAscending.length;
        if (radiusCount == 0) {
            return new float[0][];
        }
        float maxRadius = radiiAscending[radiusCount - 1];
        final int stamp = ++curvatureStamp;
        integrateCalls++;
        long sectionTime = System.nanoTime();

        PriorityQueue<DijkstraNode> pq = new PriorityQueue<>();
        int visitedCount = 0;
        for (int s = 0; s < seedVertexIds.length; s++) {
            int sv = seedVertexIds[s];
            float sd = seedDistances[s];
            if (vertexInDiskStamp[sv] != stamp) {
                vertexInDiskStamp[sv] = stamp;
                vertexDistance[sv] = sd;
                visitedVertexIds[visitedCount++] = sv;
                pq.offer(new DijkstraNode(sd, sv));
            } else if (sd < vertexDistance[sv]) {
                vertexDistance[sv] = sd;
                pq.offer(new DijkstraNode(sd, sv));
            }
        }

        while (!pq.isEmpty()) {
            DijkstraNode node = pq.poll();
            int u = node.vertexOrFace;
            if (node.distance > vertexDistance[u] + CrossField.EPSILON) {
                continue;
            }
            for (int j = neighborStart[u]; j < neighborStart[u + 1]; j++) {
                int w = neighborVertexId[j];
                float nd = node.distance + neighborEdgeLength[j];
                if (nd > maxRadius) {
                    continue;
                }
                if (vertexInDiskStamp[w] != stamp) {
                    vertexInDiskStamp[w] = stamp;
                    vertexDistance[w] = nd;
                    visitedVertexIds[visitedCount++] = w;
                    pq.offer(new DijkstraNode(nd, w));
                } else if (nd < vertexDistance[w]) {
                    vertexDistance[w] = nd;
                    pq.offer(new DijkstraNode(nd, w));
                }
            }
        }
        long sectionNow = System.nanoTime();
        walkNanos += sectionNow - sectionTime;
        sectionTime = sectionNow;

        // Faces fully inside the max disk; inclusion radius = the farthest of the
        // three vertices, so the face appears in exactly the disks at least that wide.
        int diskFaceCount = 0;
        for (int vi = 0; vi < visitedCount; vi++) {
            int vertexId = visitedVertexIds[vi];
            for (int j = vertexFaceStart[vertexId]; j < vertexFaceStart[vertexId + 1]; j++) {
                int faceId = vertexFaceId[j];
                if (faceInDiskStamp[faceId] == stamp) {
                    continue;
                }
                int v0 = faceVertex0[faceId];
                int v1 = faceVertex1[faceId];
                int v2 = faceVertex2[faceId];
                if (vertexInDiskStamp[v0] == stamp
                        && vertexInDiskStamp[v1] == stamp
                        && vertexInDiskStamp[v2] == stamp) {
                    faceInDiskStamp[faceId] = stamp;
                    faceInclusionDistance[faceId] = Math.max(vertexDistance[v0],
                            Math.max(vertexDistance[v1], vertexDistance[v2]));
                    diskFaceIds[diskFaceCount++] = faceId;
                }
            }
        }

        sectionNow = System.nanoTime();
        faceCollectNanos += sectionNow - sectionTime;
        sectionTime = sectionNow;

        float[][] tensors = new float[radiusCount][];
        if (diskFaceCount == 0) {
            return tensors;
        }

        // Interior edges with both faces in the disk; inclusion radius = the larger of
        // the two faces'. The dihedral weight and edge direction are precomputed once
        // in buildAdjacencyCaches; only the projection onto this disk's center frame is
        // done here.
        int diskEdgeCount = 0;
        for (int vi = 0; vi < visitedCount; vi++) {
            int vertexId = visitedVertexIds[vi];
            for (int j = vertexEdgeStart[vertexId]; j < vertexEdgeStart[vertexId + 1]; j++) {
                int edgeId = vertexEdgeId[j];
                if (edgeProcessedStamp[edgeId] == stamp) {
                    continue;
                }
                edgeProcessedStamp[edgeId] = stamp;
                if (!edgeContributes[edgeId]) {
                    continue;
                }
                if (vertexInDiskStamp[edgeStartVertex[edgeId]] != stamp
                        || vertexInDiskStamp[edgeEndVertex[edgeId]] != stamp) {
                    continue;
                }
                int faceA = edgeFaceA[edgeId];
                int faceB = edgeFaceB[edgeId];
                if (faceInDiskStamp[faceA] != stamp || faceInDiskStamp[faceB] != stamp) {
                    continue;
                }

                float dirX = edgeDirX[edgeId];
                float dirY = edgeDirY[edgeId];
                float dirZ = edgeDirZ[edgeId];
                float normalComponent = dirX * centerNormal.x + dirY * centerNormal.y + dirZ * centerNormal.z;
                float planarX = dirX - normalComponent * centerNormal.x;
                float planarY = dirY - normalComponent * centerNormal.y;
                float planarZ = dirZ - normalComponent * centerNormal.z;
                float edgeComponentE1 = planarX * tangentE1.x + planarY * tangentE1.y + planarZ * tangentE1.z;
                float edgeComponentE2 = planarX * tangentE2.x + planarY * tangentE2.y + planarZ * tangentE2.z;

                float weight = edgeWeight[edgeId];
                edgeContribution00[edgeId] = weight * edgeComponentE1 * edgeComponentE1;
                edgeContribution01[edgeId] = weight * edgeComponentE1 * edgeComponentE2;
                edgeContribution11[edgeId] = weight * edgeComponentE2 * edgeComponentE2;
                edgeInclusionDistance[edgeId] = Math.max(faceInclusionDistance[faceA],
                        faceInclusionDistance[faceB]);
                diskEdgeIds[diskEdgeCount++] = edgeId;
            }
        }
        sectionNow = System.nanoTime();
        edgeCollectNanos += sectionNow - sectionTime;
        sectionTime = sectionNow;

        // Bucket each face/edge at the smallest radius that includes it, then prefix
        // sum up the series so every radius reuses the single walk's geometry.
        float[] bucketArea = new float[radiusCount];
        float[] bucketTensor00 = new float[radiusCount];
        float[] bucketTensor01 = new float[radiusCount];
        float[] bucketTensor11 = new float[radiusCount];
        for (int fi = 0; fi < diskFaceCount; fi++) {
            int faceId = diskFaceIds[fi];
            int radiusIndex = 0;
            while (radiusIndex < radiusCount && radiiAscending[radiusIndex] < faceInclusionDistance[faceId]) {
                radiusIndex++;
            }
            if (radiusIndex < radiusCount) {
                bucketArea[radiusIndex] += faceArea[faceId];
            }
        }
        for (int ei = 0; ei < diskEdgeCount; ei++) {
            int edgeId = diskEdgeIds[ei];
            int radiusIndex = 0;
            while (radiusIndex < radiusCount && radiiAscending[radiusIndex] < edgeInclusionDistance[edgeId]) {
                radiusIndex++;
            }
            if (radiusIndex < radiusCount) {
                bucketTensor00[radiusIndex] += edgeContribution00[edgeId];
                bucketTensor01[radiusIndex] += edgeContribution01[edgeId];
                bucketTensor11[radiusIndex] += edgeContribution11[edgeId];
            }
        }

        float cumulativeArea = 0f;
        float cumulativeTensor00 = 0f;
        float cumulativeTensor01 = 0f;
        float cumulativeTensor11 = 0f;
        for (int radiusIndex = 0; radiusIndex < radiusCount; radiusIndex++) {
            cumulativeArea += bucketArea[radiusIndex];
            cumulativeTensor00 += bucketTensor00[radiusIndex];
            cumulativeTensor01 += bucketTensor01[radiusIndex];
            cumulativeTensor11 += bucketTensor11[radiusIndex];
            if (cumulativeArea < CrossField.EPSILON) {
                continue;
            }
            tensors[radiusIndex] = new float[] {
                    cumulativeTensor00 / cumulativeArea,
                    cumulativeTensor01 / cumulativeArea,
                    cumulativeTensor11 / cumulativeArea };
        }
        bucketNanos += System.nanoTime() - sectionTime;
        return tensors;
    }
}
