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
     * constraint at the vertex pins the face. Below this, V_C's tangent plane
     * differs enough from F's plane that V_C's curvature direction can't be
     * consistently projected into F's frame relative to its neighbors — adjacent
     * pinned faces from the same source then have a forced smoothness residual that
     * emits ± period-jump pairs. cos(15°) ≈ 0.966.
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
        float suppressionRadius = averageEdgeLength * CANDIDATE_SUPPRESSION_RADIUS_MULTIPLE;
        float suppressionRadiusSquared = suppressionRadius * suppressionRadius;
        float conflictRadius = averageEdgeLength * CANDIDATE_CONFLICT_RADIUS_MULTIPLE;
        float conflictRadiusSquared = conflictRadius * conflictRadius;
        float curvatureK = CURVATURE_SCALE_K / Math.max(mesh.computeBoundingSphereRadius(), CrossField.EPSILON);
        Vector3f vPos = new Vector3f();
        Vector3f vNormal = new Vector3f();
        Vector3f e1 = new Vector3f();
        Vector3f e2 = new Vector3f();
        List<CurvatureConstraintCandidate> candidates = new ArrayList<>();

        List<Float> radii = new ArrayList<>();
        float startRadius = averageEdgeLength;
        float endRadius = crossField.targetQuadEdgeLength;
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

            int adjacentFaceCount = mesh.vertexFaceCount(vertexId);
            int[] candidateFaceActiveIds = new int[adjacentFaceCount];
            float[] candidateAnglesInFace = new float[adjacentFaceCount];
            int candidateFaceCount = 0;
            int bestLocalFaceIndex = -1;
            float bestFaceScore = Float.NEGATIVE_INFINITY;
            float bestNormalAlignment = 0f;
            for (int i = 0; i < adjacentFaceCount; i++) {
                int faceId = mesh.vertexFaceAt(vertexId, i);
                int faceActiveId = crossField.faceIdToActive.get(faceId);
                if (crossField.faceConstrained[faceActiveId]) {
                    continue;
                }
                Vector3f n = mesh.faceNormal(faceId);
                float normalAlignment = n.dot(vNormal);
                if (normalAlignment < FACE_VERTEX_NORMAL_ALIGNMENT_FLOOR) {
                    continue;
                }
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
                float faceScore = normalAlignment + projectionLength;
                if (faceScore > bestFaceScore) {
                    bestFaceScore = faceScore;
                    bestLocalFaceIndex = candidateFaceCount;
                    bestNormalAlignment = normalAlignment;
                }
                candidateFaceActiveIds[candidateFaceCount] = faceActiveId;
                candidateAnglesInFace[candidateFaceCount] = CrossField.canonicalizeMod(angleInFace);
                candidateFaceCount++;
            }
            if (bestLocalFaceIndex < 0) {
                continue;
            }

            int[] selectedFaceActiveIds = new int[candidateFaceCount];
            float[] selectedAnglesInFace = new float[candidateFaceCount];
            int selectedCount = 0;
            int bestFaceActiveId = candidateFaceActiveIds[bestLocalFaceIndex];
            float bestAngleInFace = candidateAnglesInFace[bestLocalFaceIndex];
            selectedFaceActiveIds[selectedCount] = bestFaceActiveId;
            selectedAnglesInFace[selectedCount] = bestAngleInFace;
            selectedCount++;
            selectedFaceActiveIds = Arrays.copyOf(selectedFaceActiveIds, selectedCount);
            selectedAnglesInFace = Arrays.copyOf(selectedAnglesInFace, selectedCount);

            float kmax = kappaMaxList.get(bestIdx);
            float kmin = kappaMinList.get(bestIdx);
            float curvatureContrast = (Math.abs(kmax) - Math.abs(kmin)) / Math.abs(kmax);
            float meanCurvature = Math.abs(0.5f * (kmax + kmin));
            float confidence = curvatureContrast * meanCurvature * bestNormalAlignment
                    * selectedCount / (1f + bestJitter);
            int[] footprintVertexIds = collectVerticesWithinRadius(vertexId, endRadius);
            int[] footprintFaceActiveIds = collectFootprintFaces(footprintVertexIds);
            candidates.add(new CurvatureConstraintCandidate(vertexId, selectedFaceActiveIds,
                    selectedAnglesInFace, footprintVertexIds, footprintFaceActiveIds,
                    constraintDirWorld, vPos, confidence));
        }

        candidates.sort((a, b) -> Float.compare(b.confidence, a.confidence));
        lastCandidateCount = candidates.size();
        lastAcceptedCount = applySparseCandidates(candidates, suppressionRadius,
                suppressionRadiusSquared, conflictRadiusSquared);
        return lastAcceptedCount;
    }

    /**
     * Apply candidate hard pins in confidence order while suppressing nearby
     * duplicates or conflicts. This keeps BZK09's hard-constraint energy model, but
     * makes the curvature-derived constrained set sparse and reliable.
     *
     * @param candidates               candidate pins sorted or unsorted
     * @param suppressionRadius        world-space radius for local transport checks
     * @param suppressionRadiusSquared squared world-space radius for duplicate
     *                                 non-max suppression
     * @param conflictRadiusSquared    squared world-space radius for rejecting
     *                                 local incompatible candidates
     * @return number of accepted hard pins
     */
    private int applySparseCandidates(List<CurvatureConstraintCandidate> candidates,
            float suppressionRadius, float suppressionRadiusSquared,
            float conflictRadiusSquared) {
        List<CurvatureConstraintCandidate> accepted = new ArrayList<>();
        for (CurvatureConstraintCandidate candidate : candidates) {
            if (hasConstrainedFace(candidate) || hasConstrainedFootprintFace(candidate)
                    || hasAcceptedFootprintConflict(candidate, accepted)) {
                continue;
            }
            if (hasNearbyAcceptedCandidate(candidate, accepted, suppressionRadius,
                    suppressionRadiusSquared,
                    conflictRadiusSquared)) {
                continue;
            }
            accepted.add(candidate);
        }
        int addedConstraints = 0;
        for (CurvatureConstraintCandidate candidate : accepted) {
            for (int i = 0; i < candidate.faceActiveIds.length; i++) {
                int faceActiveId = candidate.faceActiveIds[i];
                if (crossField.faceConstrained[faceActiveId]) {
                    continue;
                }
                crossField.faceConstrained[faceActiveId] = true;
                crossField.faceConstraintAngle[faceActiveId] = candidate.anglesInFace[i];
                addedConstraints++;
            }
        }
        return addedConstraints;
    }

    /**
     * Check whether a candidate would overwrite an already constrained face.
     *
     * @param candidate candidate being tested
     * @return true when any candidate face is already constrained
     */
    private boolean hasConstrainedFace(CurvatureConstraintCandidate candidate) {
        for (int faceActiveId : candidate.faceActiveIds) {
            if (crossField.faceConstrained[faceActiveId]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check whether a candidate footprint touches a previously constrained patch.
     *
     * @param candidate candidate being tested
     * @return true when any footprint face is already constrained
     */
    private boolean hasConstrainedFootprintFace(CurvatureConstraintCandidate candidate) {
        for (int faceActiveId : candidate.footprintFaceActiveIds) {
            if (crossField.faceConstrained[faceActiveId]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reject curvature candidates whose footprint contains another accepted source
     * vertex, or whose source vertex lies in an accepted footprint.
     *
     * @param candidate candidate being tested
     * @param accepted  stronger curvature candidates already selected
     * @return true when the candidate violates footprint exclusivity
     */
    private boolean hasAcceptedFootprintConflict(CurvatureConstraintCandidate candidate,
            List<CurvatureConstraintCandidate> accepted) {
        for (CurvatureConstraintCandidate other : accepted) {
            if (containsVertex(candidate.footprintVertexIds, other.sourceVertexId)
                    || containsVertex(other.footprintVertexIds, candidate.sourceVertexId)
                    || containsAnyVertex(candidate.footprintVertexIds, other.footprintVertexIds)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check whether a vertex id is present in a small footprint array.
     *
     * @param vertexIds vertex ids to scan
     * @param vertexId  target vertex id
     * @return true when {@code vertexId} is present
     */
    private boolean containsVertex(int[] vertexIds, int vertexId) {
        for (int currentVertexId : vertexIds) {
            if (currentVertexId == vertexId) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check whether two footprint arrays share at least one mesh vertex id.
     *
     * @param firstVertexIds  first vertex id set
     * @param secondVertexIds second vertex id set
     * @return true when the two sets overlap
     */
    private boolean containsAnyVertex(int[] firstVertexIds, int[] secondVertexIds) {
        for (int firstVertexId : firstVertexIds) {
            if (containsVertex(secondVertexIds, firstVertexId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a stronger accepted candidate already covers the local surface patch
     * around {@code candidate}. Nearby compatible candidates are duplicates; nearby
     * incompatible candidates would force local period jumps.
     *
     * @param candidate                candidate being tested
     * @param accepted                 stronger candidates already accepted
     * @param suppressionRadius        world-space radius for local transport checks
     * @param suppressionRadiusSquared squared local duplicate suppression radius
     * @param conflictRadiusSquared    squared local conflict radius
     * @return true when the candidate should be skipped
     */
    private boolean hasNearbyAcceptedCandidate(CurvatureConstraintCandidate candidate,
            List<CurvatureConstraintCandidate> accepted, float suppressionRadius,
            float suppressionRadiusSquared,
            float conflictRadiusSquared) {
        for (CurvatureConstraintCandidate other : accepted) {
            float dx = candidate.sourcePosition.x - other.sourcePosition.x;
            float dy = candidate.sourcePosition.y - other.sourcePosition.y;
            float dz = candidate.sourcePosition.z - other.sourcePosition.z;
            float distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared > suppressionRadiusSquared) {
                continue;
            }
            float transportResidual = transportResidualBetween(other.faceActiveId,
                    other.angleInFace, candidate.faceActiveId, candidate.angleInFace,
                    suppressionRadius);
            if (!Float.isFinite(transportResidual)) {
                transportResidual = crossAxisAngleDifference(candidate.directionWorld, other.directionWorld);
            }
            if (transportResidual <= TRANSPORT_DUPLICATE_THRESHOLD) {
                return true;
            }
            if (distanceSquared <= conflictRadiusSquared
                    && transportResidual > TRANSPORT_CONFLICT_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    /**
     * Transport {@code sourceAngle} from {@code sourceFaceActiveId} to
     * {@code targetFaceActiveId} through the local dual graph, then compare it to
     * {@code targetAngle} modulo cross symmetry.
     *
     * @param sourceFaceActiveId active source face index
     * @param sourceAngle        source face angle
     * @param targetFaceActiveId active target face index
     * @param targetAngle        target face angle
     * @param maxDistance        maximum dual distance to search
     * @return residual modulo {@code pi/2}, or positive infinity if no path is
     *         found within {@code maxDistance}
     */
    private float transportResidualBetween(int sourceFaceActiveId, float sourceAngle,
            int targetFaceActiveId, float targetAngle, float maxDistance) {
        if (sourceFaceActiveId == targetFaceActiveId) {
            return angleDifferenceModHalfPi(sourceAngle - targetAngle);
        }

        float[] distance = new float[crossField.faceCount];
        float[] transportedAngle = new float[crossField.faceCount];
        Arrays.fill(distance, Float.POSITIVE_INFINITY);
        PriorityQueue<DijkstraNode> queue = new PriorityQueue<>();
        distance[sourceFaceActiveId] = 0f;
        transportedAngle[sourceFaceActiveId] = sourceAngle;
        queue.offer(new DijkstraNode(0f, sourceFaceActiveId));

        Vector3f startPosition = new Vector3f();
        Vector3f endPosition = new Vector3f();
        while (!queue.isEmpty()) {
            DijkstraNode node = queue.poll();
            int faceActiveId = node.vertexOrFace;
            if (node.distance > distance[faceActiveId] + CrossField.EPSILON) {
                continue;
            }
            if (faceActiveId == targetFaceActiveId) {
                return angleDifferenceModHalfPi(transportedAngle[faceActiveId] - targetAngle);
            }
            int faceId = mesh.faceIdAt(faceActiveId);
            int halfEdgeCount = mesh.faceHalfEdgeCount(faceId);
            for (int i = 0; i < halfEdgeCount; i++) {
                int halfEdge = mesh.faceHalfEdgeAt(faceId, i);
                int twin = mesh.halfEdgeTwin(halfEdge);
                int neighborFaceId = mesh.halfEdgeFace(twin);
                if (neighborFaceId < 0) {
                    continue;
                }
                int neighborFaceActiveId = crossField.faceIdToActive.get(neighborFaceId);
                int edgeId = mesh.halfEdgeEdge(halfEdge);
                int edgeActiveId = crossField.edgeIdToActive.get(edgeId);
                mesh.vertexPosition(mesh.halfEdgeVertex(halfEdge), startPosition);
                mesh.vertexPosition(mesh.halfEdgeEndVertex(halfEdge), endPosition);
                float edgeLength = endPosition.sub(startPosition).length();
                float newDistance = node.distance + edgeLength;
                if (newDistance > maxDistance || newDistance >= distance[neighborFaceActiveId]) {
                    continue;
                }
                distance[neighborFaceActiveId] = newDistance;
                transportedAngle[neighborFaceActiveId] = transportAngleAcrossEdge(
                        transportedAngle[faceActiveId], faceId, edgeActiveId);
                queue.offer(new DijkstraNode(newDistance, neighborFaceActiveId));
            }
        }
        return Float.POSITIVE_INFINITY;
    }

    /**
     * Transport a face angle across an edge using the same signed {@code kappa}
     * convention as the smoothness residual.
     *
     * @param angle        angle in {@code fromFaceId}
     * @param fromFaceId   source face id
     * @param edgeActiveId active edge index crossed from source to neighbor
     * @return transported angle, canonicalized modulo {@code pi/2}
     */
    private float transportAngleAcrossEdge(float angle, int fromFaceId, int edgeActiveId) {
        HalfEdgeMesh.EdgeFaceIds edgeFaces = mesh.edgeFaceIds(edgeActiveId);
        float signedKappa = edgeFaces.faceA == fromFaceId
                ? crossField.kappa[edgeActiveId]
                : -crossField.kappa[edgeActiveId];
        return CrossField.canonicalizeMod(angle + signedKappa);
    }

    /**
     * Smallest absolute angle difference under cross-field quarter-turn symmetry.
     *
     * @param angleDifference raw angle difference
     * @return difference in {@code [0, pi/4]}
     */
    private float angleDifferenceModHalfPi(float angleDifference) {
        float halfPi = (float) (Math.PI / 2.0);
        float wrapped = CrossField.canonicalizeMod(angleDifference);
        if (wrapped > halfPi / 2f) {
            wrapped = halfPi - wrapped;
        }
        return Math.abs(wrapped);
    }

    /**
     * Compare two world-space cross axes modulo the cross-field quarter-turn
     * symmetry.
     *
     * @param first  first unit direction
     * @param second second unit direction
     * @return smallest angle difference to either parallel or perpendicular axes
     */
    private float crossAxisAngleDifference(Vector3f first, Vector3f second) {
        float dot = Math.max(-1f, Math.min(1f, Math.abs(first.dot(second))));
        float parallelDifference = (float) Math.acos(dot);
        float perpendicularDifference = Math.abs((float) (Math.PI / 2.0) - parallelDifference);
        return Math.min(parallelDifference, perpendicularDifference);
    }

    /**
     * Collect vertices reached by a geodesic disk around a curvature source.
     *
     * @param centerVertexId center vertex id
     * @param geodesicRadius maximum 1-skeleton distance
     * @return vertex ids inside the disk
     */
    private int[] collectVerticesWithinRadius(int centerVertexId, float geodesicRadius) {
        Arrays.fill(vertexInDiskStamp, 0);
        Arrays.fill(vertexDistance, Float.POSITIVE_INFINITY);
        Arrays.fill(visitedVertexIds, 0);
        final int stamp = 1;

        PriorityQueue<DijkstraNode> frontier = new PriorityQueue<>();
        frontier.offer(new DijkstraNode(0f, centerVertexId));
        vertexInDiskStamp[centerVertexId] = stamp;
        vertexDistance[centerVertexId] = 0f;
        int visitedCount = 0;
        visitedVertexIds[visitedCount++] = centerVertexId;

        Vector3f currentPosition = new Vector3f();
        Vector3f nextPosition = new Vector3f();
        while (!frontier.isEmpty()) {
            DijkstraNode node = frontier.poll();
            int currentVertexId = node.vertexOrFace;
            if (node.distance > vertexDistance[currentVertexId] + CrossField.EPSILON) {
                continue;
            }
            mesh.vertexPosition(currentVertexId, currentPosition);
            int outgoingCount = mesh.vertexOutgoingHalfEdgeCount(currentVertexId);
            for (int i = 0; i < outgoingCount; i++) {
                int halfEdge = mesh.vertexOutgoingHalfEdgeAt(currentVertexId, i);
                int nextVertexId = mesh.halfEdgeEndVertex(halfEdge);
                mesh.vertexPosition(nextVertexId, nextPosition);
                float edgeLength = nextPosition.sub(currentPosition).length();
                float newDistance = node.distance + edgeLength;
                if (newDistance > geodesicRadius || newDistance >= vertexDistance[nextVertexId]) {
                    continue;
                }
                if (vertexInDiskStamp[nextVertexId] != stamp) {
                    vertexInDiskStamp[nextVertexId] = stamp;
                    visitedVertexIds[visitedCount++] = nextVertexId;
                }
                vertexDistance[nextVertexId] = newDistance;
                frontier.offer(new DijkstraNode(newDistance, nextVertexId));
            }
        }
        return Arrays.copyOf(visitedVertexIds, visitedCount);
    }

    /**
     * Collect active faces incident to any vertex in a footprint.
     *
     * @param footprintVertexIds vertex ids in the curvature footprint
     * @return active face ids touched by the footprint
     */
    private int[] collectFootprintFaces(int[] footprintVertexIds) {
        boolean[] faceSeen = new boolean[crossField.faceCount];
        int[] faceActiveIds = new int[crossField.faceCount];
        int faceCount = 0;
        for (int vertexId : footprintVertexIds) {
            int adjacentFaceCount = mesh.vertexFaceCount(vertexId);
            for (int i = 0; i < adjacentFaceCount; i++) {
                int faceId = mesh.vertexFaceAt(vertexId, i);
                int faceActiveId = crossField.faceIdToActive.get(faceId);
                if (faceSeen[faceActiveId]) {
                    continue;
                }
                faceSeen[faceActiveId] = true;
                faceActiveIds[faceCount] = faceActiveId;
                faceCount++;
            }
        }
        return Arrays.copyOf(faceActiveIds, faceCount);
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
