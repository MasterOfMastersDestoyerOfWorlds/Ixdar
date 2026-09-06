package ixdar.geometry.mesh.data.paths;

import java.util.Arrays;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.MeshTopology;

/**
 * Traces an intrinsic path back onto its surface, unfolding source triangles along each intrinsic
 * half-edge to find the original edges it crosses.
 *
 * <p>
 * {@link #snapshotOf} must be called while the triangulation is still unflipped.
 */
public final class IntrinsicPathTracer {

    /** Relative slack on the traced distance before a walk is declared finished. */
    public static final double LENGTH_EPSILON = 1e-9;

    /** Source mesh the trace lands on. */
    public MeshTopology mesh;

    /** Unflipped {@code halfEdgeNext}, indexed by intrinsic half-edge. */
    public int[] inputHalfEdgeNext;

    /** Unflipped {@code halfEdgeTail}, indexed by intrinsic half-edge. */
    public int[] inputHalfEdgeTail;

    /** Unflipped edge lengths, indexed by intrinsic edge. */
    public double[] inputEdgeLength;

    /** Unflipped signpost angles, indexed by intrinsic half-edge. */
    public double[] inputSignpostAngle;

    /** Mesh vertex id per dense intrinsic vertex index. */
    public int[] sourceVertexId;

    /** Mesh edge id per dense intrinsic edge index. */
    public int[] sourceEdgeId;

    /** Outgoing half-edge whose angular coordinate is zero, per vertex. */
    public int[] vertexReferenceHalfEdge;

    /** Steps the unfolding walk may take along one intrinsic half-edge before it gives up. */
    public int maxUnfoldSteps;

    private double[] positions = new double[0];
    private int[] pointVertexId = new int[0];
    private int[] pointEdgeId = new int[0];
    private double[] pointFraction = new double[0];
    private int pointCount;
    private final Vector3f scratchPosition = new Vector3f();

    private IntrinsicPathTracer() {
    }

    /**
     * Snapshots an unflipped triangulation so later traces can unfold the original triangles.
     *
     * @param intrinsic triangulation that has not been flipped yet
     * @return a tracer bound to the same source mesh
     */
    public static IntrinsicPathTracer snapshotOf(IntrinsicTriangulation intrinsic) {
        IntrinsicPathTracer tracer = new IntrinsicPathTracer();
        tracer.mesh = intrinsic.sourceMesh;
        tracer.inputHalfEdgeNext = intrinsic.halfEdgeNext.clone();
        tracer.inputHalfEdgeTail = intrinsic.halfEdgeTail.clone();
        tracer.inputEdgeLength = intrinsic.edgeLength.clone();
        tracer.inputSignpostAngle = intrinsic.signpostAngle.clone();
        tracer.sourceVertexId = intrinsic.sourceVertexId;
        tracer.sourceEdgeId = intrinsic.sourceEdgeId;
        tracer.vertexReferenceHalfEdge = intrinsic.vertexReferenceHalfEdge.clone();
        tracer.maxUnfoldSteps = Math.max(intrinsic.faceCount, 64);
        return tracer;
    }

    /**
     * Traces a tightened intrinsic path onto the source surface as a polyline.
     *
     * @param intrinsic the flipped triangulation the path lives on
     * @param pathHalfEdges intrinsic half-edges in travel order
     * @param closed whether the path is a closed loop
     * @return the polyline with its per-point vertex and edge-crossing correspondence
     */
    public TracedSurfacePath trace(IntrinsicTriangulation intrinsic, int[] pathHalfEdges,
            boolean closed) {
        pointCount = 0;
        if (pathHalfEdges.length == 0) {
            return new TracedSurfacePath(new double[0], new int[0], new int[0], new double[0], 0,
                    closed);
        }
        for (int index = 0; index < pathHalfEdges.length; index++) {
            int halfEdge = pathHalfEdges[index];
            appendVertex(intrinsic.halfEdgeTail[halfEdge]);
            if (!intrinsic.edgeIsOriginal[halfEdge >> 1]) {
                walkHalfEdge(intrinsic, halfEdge);
            }
        }
        if (!closed) {
            appendVertex(intrinsic.halfEdgeHead(pathHalfEdges[pathHalfEdges.length - 1]));
        }
        return new TracedSurfacePath(Arrays.copyOf(positions, 3 * pointCount),
                Arrays.copyOf(pointVertexId, pointCount), Arrays.copyOf(pointEdgeId, pointCount),
                Arrays.copyOf(pointFraction, pointCount), pointCount, closed);
    }

    /**
     * Walks one intrinsic half-edge across the original triangles, appending every edge crossing.
     *
     * <p>
     * The half-edge is a straight geodesic leaving its tail at the signpost angle, so the walk
     * unfolds each triangle it enters into a single plane and follows one straight ray.
     */
    private void walkHalfEdge(IntrinsicTriangulation intrinsic, int halfEdge) {
        int tailVertex = intrinsic.halfEdgeTail[halfEdge];
        double targetAngle = intrinsic.signpostAngle[halfEdge];
        double targetLength = intrinsic.edgeLength[halfEdge >> 1];

        int reference = vertexReferenceHalfEdge[tailVertex];
        if (reference < 0) {
            return;
        }
        int cornerHalfEdge = -1;
        double cornerStart = 0.0;
        int current = reference;
        do {
            if (inputHalfEdgeNext[current] < 0) {
                break;
            }
            double start = inputSignpostAngle[current];
            double corner = inputCornerAngle(current);
            if (targetAngle >= start - LENGTH_EPSILON
                    && targetAngle < start + corner + LENGTH_EPSILON) {
                cornerHalfEdge = current;
                cornerStart = start;
                break;
            }
            current = inputHalfEdgeNext[inputHalfEdgeNext[current]] ^ 1;
        } while (current != reference);
        if (cornerHalfEdge < 0) {
            return;
        }

        double rayAngle = targetAngle - cornerStart;
        double directionX = Math.cos(rayAngle);
        double directionY = Math.sin(rayAngle);

        int forward = cornerHalfEdge;
        int far = inputHalfEdgeNext[forward];
        int back = inputHalfEdgeNext[far];
        double firstX = 0.0;
        double firstY = 0.0;
        double secondX = inputEdgeLength[forward >> 1];
        double secondY = 0.0;
        double[] apex = new double[2];
        layOutOpposite(firstX, firstY, secondX, secondY, inputEdgeLength[far >> 1],
                inputEdgeLength[back >> 1], apex);
        double thirdX = apex[0];
        double thirdY = apex[1];

        int exitHalfEdge = far;
        double exitFromX = secondX;
        double exitFromY = secondY;
        double exitToX = thirdX;
        double exitToY = thirdY;
        double travelled = 0.0;
        for (int step = 0; step < maxUnfoldSteps; step++) {
            double crossingParameter = edgeCrossing(exitFromX, exitFromY, exitToX, exitToY,
                    directionX, directionY);
            if (!Double.isFinite(crossingParameter)) {
                return;
            }
            double hitX = exitFromX + crossingParameter * (exitToX - exitFromX);
            double hitY = exitFromY + crossingParameter * (exitToY - exitFromY);
            travelled = hitX * directionX + hitY * directionY;
            if (travelled >= targetLength * (1.0 - LENGTH_EPSILON)) {
                return;
            }
            appendCrossing(exitHalfEdge, crossingParameter);

            int entry = exitHalfEdge ^ 1;
            if (inputHalfEdgeNext[entry] < 0) {
                return;
            }
            int nextFar = inputHalfEdgeNext[entry];
            int nextBack = inputHalfEdgeNext[nextFar];
            double entryFromX = exitToX;
            double entryFromY = exitToY;
            double entryToX = exitFromX;
            double entryToY = exitFromY;
            layOutOpposite(entryFromX, entryFromY, entryToX, entryToY,
                    inputEdgeLength[nextFar >> 1], inputEdgeLength[nextBack >> 1], apex);
            double newApexX = apex[0];
            double newApexY = apex[1];

            double sideFrom = directionX * entryFromY - directionY * entryFromX;
            double sideApex = directionX * newApexY - directionY * newApexX;
            if (sideFrom * sideApex <= 0.0) {
                exitHalfEdge = nextBack;
                exitFromX = newApexX;
                exitFromY = newApexY;
                exitToX = entryFromX;
                exitToY = entryFromY;
            } else {
                exitHalfEdge = nextFar;
                exitFromX = entryToX;
                exitFromY = entryToY;
                exitToX = newApexX;
                exitToY = newApexY;
            }
        }
    }

    private double inputCornerAngle(int halfEdge) {
        double adjacent = inputEdgeLength[halfEdge >> 1];
        double other = inputEdgeLength[inputHalfEdgeNext[inputHalfEdgeNext[halfEdge]] >> 1];
        double opposite = inputEdgeLength[inputHalfEdgeNext[halfEdge] >> 1];
        double denominator = 2.0 * adjacent * other;
        if (denominator <= 0.0) {
            return 0.0;
        }
        double cosine = (adjacent * adjacent + other * other - opposite * opposite) / denominator;
        return Math.acos(Math.max(-1.0, Math.min(1.0, cosine)));
    }

    private static void layOutOpposite(double fromX, double fromY, double toX, double toY,
            double toApexLength, double apexToFromLength, double[] apex) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double base = Math.hypot(dx, dy);
        if (base <= 0.0) {
            apex[0] = fromX;
            apex[1] = fromY;
            return;
        }
        double along = (apexToFromLength * apexToFromLength + base * base
                - toApexLength * toApexLength) / (2.0 * base);
        double across = Math.sqrt(
                Math.max(0.0, apexToFromLength * apexToFromLength - along * along));
        double unitX = dx / base;
        double unitY = dy / base;
        apex[0] = fromX + unitX * along - unitY * across;
        apex[1] = fromY + unitY * along + unitX * across;
    }

    private static double edgeCrossing(double fromX, double fromY, double toX, double toY,
            double directionX, double directionY) {
        double spanX = toX - fromX;
        double spanY = toY - fromY;
        double denominator = spanX * directionY - spanY * directionX;
        if (denominator == 0.0) {
            return Double.NaN;
        }
        double parameter = -(fromX * directionY - fromY * directionX) / denominator;
        return Math.max(0.0, Math.min(1.0, parameter));
    }

    private void appendVertex(int intrinsicVertex) {
        ensureCapacity();
        int vertexId = sourceVertexId[intrinsicVertex];
        mesh.vertexPosition(vertexId, scratchPosition);
        int base = 3 * pointCount;
        positions[base] = scratchPosition.x;
        positions[base + 1] = scratchPosition.y;
        positions[base + 2] = scratchPosition.z;
        pointVertexId[pointCount] = vertexId;
        pointEdgeId[pointCount] = -1;
        pointFraction[pointCount] = -1.0;
        pointCount++;
    }

    private void appendCrossing(int inputHalfEdge, double parameter) {
        ensureCapacity();
        int edge = inputHalfEdge >> 1;
        int tailVertexId = sourceVertexId[inputHalfEdgeTail[inputHalfEdge]];
        int headVertexId = sourceVertexId[inputHalfEdgeTail[inputHalfEdge ^ 1]];
        mesh.vertexPosition(tailVertexId, scratchPosition);
        double tailX = scratchPosition.x;
        double tailY = scratchPosition.y;
        double tailZ = scratchPosition.z;
        mesh.vertexPosition(headVertexId, scratchPosition);
        int base = 3 * pointCount;
        positions[base] = tailX + parameter * (scratchPosition.x - tailX);
        positions[base + 1] = tailY + parameter * (scratchPosition.y - tailY);
        positions[base + 2] = tailZ + parameter * (scratchPosition.z - tailZ);
        pointVertexId[pointCount] = -1;
        pointEdgeId[pointCount] = sourceEdgeId[edge];
        pointFraction[pointCount] = (inputHalfEdge & 1) == 0 ? parameter : 1.0 - parameter;
        pointCount++;
    }

    private void ensureCapacity() {
        if (pointCount < pointVertexId.length) {
            return;
        }
        int grown = Math.max(64, pointVertexId.length * 2);
        positions = Arrays.copyOf(positions, 3 * grown);
        pointVertexId = Arrays.copyOf(pointVertexId, grown);
        pointEdgeId = Arrays.copyOf(pointEdgeId, grown);
        pointFraction = Arrays.copyOf(pointFraction, grown);
    }
}
