package ixdar.geometry.mesh.quadlayout.crossfield.constraint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh.VertexFaceIds;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.crossfield.DijkstraNode;

public class CurvatureConstraints {

    public static final float CURVATURE_RADIUS_MULTIPLE = 10.0f;

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
    public static final float MINIMUM_CURVATURE_CONTRAST = 0.9f;

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
     * The mesh that the curvature constraints are applied to.
     */
    HalfEdgeMesh mesh;

    /**
     * The cross field that the curvature constraints are applied to.
     */
    CrossField crossField;

    /**
     * Allocate per-vertex/face/edge scratch buffers sized to the cross field.
     *
     * @param mesh       half-edge mesh the disks are walked on
     * @param crossField cross field whose vertex/face/edge counts size the scratch
     *                   buffers
     */
    public CurvatureConstraints(HalfEdgeMesh mesh, CrossField crossField) {
        this.vertexInDiskStamp = new int[crossField.vertexCount];
        this.faceInDiskStamp = new int[crossField.faceCount];
        this.edgeProcessedStamp = new int[crossField.edgeCount];
        this.vertexDistance = new float[crossField.vertexCount];
        this.visitedVertexIds = new int[crossField.vertexCount];
        this.mesh = mesh;
        this.crossField = crossField;
    }

    /**
     * A2. Directional constraints from principal curvature
     *
     * @return number of newly constrained faces
     */

    public int applyCurvatureConstraints() {
        float averageEdgeLength = mesh.computeAverageEdgeLength();
        float curvatureK = CURVATURE_SCALE_K / Math.max(mesh.computeBoundingSphereRadius(), CrossField.EPSILON);
        Vector3f vPos = new Vector3f();
        Vector3f vNormal = new Vector3f();
        Vector3f e1 = new Vector3f();
        Vector3f e2 = new Vector3f();
        int addedConstraints = 0;

        List<Float> radii = new ArrayList<>();
        float startRadius = averageEdgeLength;
        float endRadius = averageEdgeLength * CURVATURE_RADIUS_MULTIPLE;
        float stabilityWindow = endRadius / 4.0f;
        for (float r = startRadius; r <= endRadius + CrossField.EPSILON; r *= RADIUS_RATIO) {
            radii.add(r);
        }

        for (int vAi = 0; vAi < mesh.vertexCount(); vAi++) {
            int vertexId = mesh.vertexIdAt(vAi);
            if (mesh.isBoundaryVertex(vertexId)) {
                continue;
            }
            mesh.vertexPosition(vertexId, vPos);
            mesh.vertexNormal(vertexId, vNormal);
            CrossField.arbitraryTangent(vNormal, e1);
            vNormal.cross(e1, e2).normalize();

            List<Float> anglesMaxDir = new ArrayList<>();
            List<Float> kappaMaxList = new ArrayList<>();
            List<Float> kappaMinList = new ArrayList<>();
            List<Float> validRadii = new ArrayList<>();

            for (float r : radii) {
                float[] T = integrateCurvatureTensor(vertexId, vPos, vNormal, e1, e2, r);
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
                validRadii.add(r);
            }
            if (anglesMaxDir.isEmpty()) {
                continue;
            }

            int bestIdx = -1;
            float bestJitter = Float.POSITIVE_INFINITY;
            for (int k = 0; k < anglesMaxDir.size(); k++) {

                float center = validRadii.get(k);
                int intervalStatus = CrossField.CURVATURE_INTERVAL_VALID;
                for (int j = 0; j < validRadii.size(); j++) {
                    if (Math.abs(validRadii.get(j) - center) > stabilityWindow) {
                        continue;
                    }
                    float kmax = kappaMaxList.get(j);
                    float kmin = kappaMinList.get(j);
                    if (Math.abs(kmax) < CrossField.EPSILON) {
                        intervalStatus = CrossField.CURVATURE_INTERVAL_FAIL_TAU;
                        break;
                    }
                    float curvatureConstrast = (Math.abs(kmax) - Math.abs(kmin)) / Math.abs(kmax);
                    float meanH = 0.5f * (kmax + kmin);
                    if (curvatureConstrast <= MINIMUM_CURVATURE_CONTRAST || Math.abs(meanH) <= curvatureK) {
                        intervalStatus = curvatureConstrast <= MINIMUM_CURVATURE_CONTRAST
                                ? CrossField.CURVATURE_INTERVAL_FAIL_TAU
                                : CrossField.CURVATURE_INTERVAL_FAIL_MEAN;
                        break;
                    }
                }
                if (intervalStatus != CrossField.CURVATURE_INTERVAL_VALID) {
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

            float constraintAngleAtV = anglesMaxDir.get(bestIdx);
            float c = (float) Math.cos(constraintAngleAtV);
            float s = (float) Math.sin(constraintAngleAtV);
            Vector3f constraintDirWorld = new Vector3f(
                    e1.x * c + e2.x * s,
                    e1.y * c + e2.y * s,
                    e1.z * c + e2.z * s);
            int adj = mesh.vertexFaceCount(vertexId);
            int newlyConstrained = 0;
            for (int i = 0; i < adj; i++) {
                int faceId = mesh.vertexFaceAt(vertexId, i);
                int faceActiveId = crossField.faceIdToActive.get(faceId);
                if (crossField.faceConstrained[faceActiveId]) {
                    continue;
                }
                Vector3f n = mesh.faceNormal(mesh.faceIdAt(faceActiveId));
                float dotN = constraintDirWorld.dot(n);
                float px = constraintDirWorld.x - dotN * n.x;
                float py = constraintDirWorld.y - dotN * n.y;
                float pz = constraintDirWorld.z - dotN * n.z;
                float projectionLength = (float) Math.sqrt(px * px + py * py + pz * pz);
                if (projectionLength < CrossField.EPSILON) {
                    continue;
                }
                float angleInFace = mesh.projectDirectionToFaceAngle(constraintDirWorld, faceActiveId,
                        crossField.faceY[faceActiveId],
                        crossField.faceX[faceActiveId]);
                crossField.faceConstrained[faceActiveId] = true;
                crossField.faceConstraintAngle[faceActiveId] = CrossField.canonicalizeMod(angleInFace);
                newlyConstrained++;
            }
            addedConstraints += newlyConstrained;
        }
        return addedConstraints;
    }

    /**
     * Cohen-Steiner integrated curvature tensor over a geodesic disk around
     * {@code centerVertexId}. Walks all triangles whose three vertices fall within
     * the disk and accumulates per-interior-edge dihedral contributions
     * {@code β·|e|·(ē⊗ē)}, normalized by total triangle area. Returns
     * {@code [T00, T01, T11]} expressed in the local tangent basis (e1, e2), or
     * {@code null} when the disk is empty or has no usable area.
     *
     * @param centerVertexId center vertex id
     * @param centerPosition center vertex position
     * @param centerNormal   center vertex normal
     * @param tangentE1      tangent basis vector 1
     * @param tangentE2      tangent basis vector 2 (= centerNormal × tangentE1)
     * @param geodesicRadius geodesic-disk radius (Dijkstra over 1-skeleton)
     * @return three-element {@code [T00, T01, T11]} tensor entries, or {@code null}
     *         when the disk has no usable triangles
     */
    public float[] integrateCurvatureTensor(int centerVertexId, Vector3f centerPosition,
            Vector3f centerNormal, Vector3f tangentE1, Vector3f tangentE2, float geodesicRadius) {
        Arrays.fill(vertexInDiskStamp, 0);
        Arrays.fill(faceInDiskStamp, 0);
        Arrays.fill(edgeProcessedStamp, 0);
        Arrays.fill(vertexDistance, 0f);
        Arrays.fill(visitedVertexIds, 0);
        int curvatureStamp = 0;
        final int stamp = ++curvatureStamp;

        PriorityQueue<DijkstraNode> pq = new PriorityQueue<>();
        pq.offer(new DijkstraNode(0f, centerVertexId));
        vertexInDiskStamp[centerVertexId] = stamp;
        vertexDistance[centerVertexId] = 0f;
        int visitedCount = 0;
        visitedVertexIds[visitedCount++] = centerVertexId;

        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        while (!pq.isEmpty()) {
            DijkstraNode node = pq.poll();
            int u = node.vertexOrFace;
            if (node.distance > vertexDistance[u] + CrossField.EPSILON) {
                continue;
            }
            mesh.vertexPosition(u, a);
            int outCount = mesh.vertexOutgoingHalfEdgeCount(u);
            for (int i = 0; i < outCount; i++) {
                int he = mesh.vertexOutgoingHalfEdgeAt(u, i);
                int w = mesh.halfEdgeEndVertex(he);
                mesh.vertexPosition(w, b);
                float dx = b.x - a.x;
                float dy = b.y - a.y;
                float dz = b.z - a.z;
                float wLen = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                float nd = node.distance + wLen;
                if (nd > geodesicRadius) {
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
        if (visitedCount == 0) {
            return null;
        }

        // Collect faces fully inside the disk.
        int facesFound = 0;
        float totalDiskArea = 0f;
        for (int vi = 0; vi < visitedCount; vi++) {
            int vertexId = visitedVertexIds[vi];
            int adjacentFaceCount = mesh.vertexFaceCount(vertexId);
            for (int i = 0; i < adjacentFaceCount; i++) {
                int faceId = mesh.vertexFaceAt(vertexId, i);
                if (faceInDiskStamp[faceId] == stamp) {
                    continue;
                }
                int faceVertex0 = mesh.faceVertexAt(faceId, 0);
                int faceVertex1 = mesh.faceVertexAt(faceId, 1);
                int faceVertex2 = mesh.faceVertexAt(faceId, 2);
                if (vertexInDiskStamp[faceVertex0] == stamp
                        && vertexInDiskStamp[faceVertex1] == stamp
                        && vertexInDiskStamp[faceVertex2] == stamp) {
                    faceInDiskStamp[faceId] = stamp;
                    totalDiskArea += mesh.faceArea(faceId);
                    facesFound++;
                }
            }
        }
        if (facesFound == 0 || totalDiskArea < CrossField.EPSILON) {
            return null;
        }

        float tensor00 = 0f;
        float tensor01 = 0f;
        float tensor11 = 0f;

        for (int vi = 0; vi < visitedCount; vi++) {
            int vertexId = visitedVertexIds[vi];
            int incidentEdgeCount = mesh.vertexEdgeCount(vertexId);
            for (int activeVertexIndex = 0; activeVertexIndex < incidentEdgeCount; activeVertexIndex++) {
                VertexFaceIds vertexEdgeIds = mesh.vertexFaceIds(vertexId, activeVertexIndex);
                if (edgeProcessedStamp[vertexEdgeIds.edgeId] == stamp) {
                    continue;
                }
                edgeProcessedStamp[vertexEdgeIds.edgeId] = stamp;

                if (vertexInDiskStamp[vertexEdgeIds.edgeStartVertex] != stamp
                        || vertexInDiskStamp[vertexEdgeIds.edgeEndVertex] != stamp) {
                    continue;
                }
                if (mesh.isBoundaryEdge(vertexEdgeIds.edgeId)) {
                    continue;
                }
                if (faceInDiskStamp[vertexEdgeIds.faceA] != stamp
                        || faceInDiskStamp[vertexEdgeIds.faceB] != stamp) {
                    continue;
                }

                Vector3f position0 = mesh.vertexPosition(vertexEdgeIds.edgeStartVertex);
                Vector3f position1 = mesh.vertexPosition(vertexEdgeIds.edgeEndVertex);
                Vector3f edgeVector = new Vector3f(position1).sub(position0);
                float edgeLength = edgeVector.length();
                if (edgeLength < CrossField.EPSILON) {
                    continue;
                }

                Vector3f leftFaceNormal = mesh.faceNormal(vertexEdgeIds.faceA);
                Vector3f rightFaceNormal = mesh.faceNormal(vertexEdgeIds.faceB);
                float cosDihedral = Math.max(-1f, Math.min(1f, leftFaceNormal.dot(rightFaceNormal)));
                Vector3f normalCross = new Vector3f(leftFaceNormal).cross(rightFaceNormal);
                float sinDihedral = normalCross.dot(edgeVector) / edgeLength;
                float dihedralAngle = (float) Math.atan2(sinDihedral, cosDihedral);

                Vector3f edgeDirInTangentPlane = new Vector3f(edgeVector).div(edgeLength);
                float normalComponent = edgeDirInTangentPlane.dot(centerNormal);
                edgeDirInTangentPlane.x -= normalComponent * centerNormal.x;
                edgeDirInTangentPlane.y -= normalComponent * centerNormal.y;
                edgeDirInTangentPlane.z -= normalComponent * centerNormal.z;
                float edgeComponentE1 = edgeDirInTangentPlane.dot(tangentE1);
                float edgeComponentE2 = edgeDirInTangentPlane.dot(tangentE2);

                float weight = dihedralAngle * edgeLength;
                tensor00 += weight * edgeComponentE1 * edgeComponentE1;
                tensor01 += weight * edgeComponentE1 * edgeComponentE2;
                tensor11 += weight * edgeComponentE2 * edgeComponentE2;
            }
        }

        tensor00 = tensor00 / totalDiskArea;
        tensor01 = tensor01 / totalDiskArea;
        tensor11 = tensor11 / totalDiskArea;
        return new float[] { tensor00, tensor01, tensor11 };
    }
}
