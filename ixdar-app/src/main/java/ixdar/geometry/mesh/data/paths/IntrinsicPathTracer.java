package ixdar.geometry.mesh.data.paths;

import java.util.Arrays;

import org.joml.Vector3f;

/**
 * Traces an intrinsic path back onto its surface, unfolding the metric's own triangles along each
 * intrinsic half-edge to find the source edges it crosses and the polygon splits it passes.
 */
public final class IntrinsicPathTracer {

    public static final double LENGTH_EPSILON = 1e-9;

    public static final int MINIMUM_UNFOLD_STEPS = 64;

    /** Unflipped triangulation the walk unfolds; the trace lands on its source mesh. */
    public SurfaceMetric metric;

    /** Steps the unfolding walk may take along one intrinsic half-edge before it gives up. */
    public int maxUnfoldSteps;

    private double[] positions = new double[0];
    private int[] pointVertexId = new int[0];
    private int[] pointEdgeId = new int[0];
    private int[] pointFaceId = new int[0];
    private double[] pointFraction = new double[0];
    private int pointCount;
    private final Vector3f scratchPosition = new Vector3f();

    private IntrinsicPathTracer() {
    }

    /**
     * A tracer over a metric, holding only its own point buffers.
     *
     * @param metric unflipped triangulation every traced path was flipped from
     * @return a tracer landing paths on the metric's source mesh
     */
    public static IntrinsicPathTracer over(SurfaceMetric metric) {
        IntrinsicPathTracer tracer = new IntrinsicPathTracer();
        tracer.metric = metric;
        tracer.maxUnfoldSteps = Math.max(metric.faceHalfEdge.length, MINIMUM_UNFOLD_STEPS);
        return tracer;
    }

    /**
     * Traces a tightened intrinsic path onto the source surface as a polyline.
     *
     * @param intrinsic the flipped triangulation the path lives on
     * @param pathHalfEdges intrinsic half-edges in travel order
     * @param closed whether the path is a closed loop
     * @return the polyline with its per-point vertex, edge-crossing or inside-face correspondence
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
            appendVertex(intrinsic.halfEdgeTail(halfEdge));
            if (!intrinsic.edgeIsOriginal(halfEdge >> 1)) {
                walkHalfEdge(intrinsic, halfEdge);
            }
        }
        if (!closed) {
            appendVertex(intrinsic.halfEdgeHead(pathHalfEdges[pathHalfEdges.length - 1]));
        }
        double[] packed = Arrays.copyOf(positions, 3 * pointCount);
        return new TracedSurfacePath(packed, packed, Arrays.copyOf(pointVertexId, pointCount),
                Arrays.copyOf(pointEdgeId, pointCount), Arrays.copyOf(pointFaceId, pointCount),
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
        int tailVertex = intrinsic.halfEdgeTail(halfEdge);
        double targetAngle = intrinsic.signpostAngle(halfEdge);
        double targetLength = intrinsic.edgeLength(halfEdge >> 1);
        int[] inputHalfEdgeNext = metric.halfEdgeNext;
        double[] inputEdgeLength = metric.edgeLength;

        int reference = metric.vertexReferenceHalfEdge[tailVertex];
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
            double start = metric.signpostAngle[current];
            double corner = metric.cornerAngle(current);
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
        int vertexId = metric.sourceVertexId[intrinsicVertex];
        metric.sourceMesh.vertexPosition(vertexId, scratchPosition);
        int base = 3 * pointCount;
        positions[base] = scratchPosition.x;
        positions[base + 1] = scratchPosition.y;
        positions[base + 2] = scratchPosition.z;
        pointVertexId[pointCount] = vertexId;
        pointEdgeId[pointCount] = -1;
        pointFaceId[pointCount] = -1;
        pointFraction[pointCount] = -1.0;
        pointCount++;
    }

    /**
     * Appends where the walk leaves a starting triangle: a crossing of the source edge under it,
     * or, on an edge splitting a source polygon, a point inside that polygon.
     */
    private void appendCrossing(int inputHalfEdge, double parameter) {
        ensureCapacity();
        int edge = inputHalfEdge >> 1;
        int tailVertexId = metric.sourceVertexId[metric.halfEdgeTail[inputHalfEdge]];
        int headVertexId = metric.sourceVertexId[metric.halfEdgeTail[inputHalfEdge ^ 1]];
        metric.sourceMesh.vertexPosition(tailVertexId, scratchPosition);
        double tailX = scratchPosition.x;
        double tailY = scratchPosition.y;
        double tailZ = scratchPosition.z;
        metric.sourceMesh.vertexPosition(headVertexId, scratchPosition);
        int base = 3 * pointCount;
        positions[base] = tailX + parameter * (scratchPosition.x - tailX);
        positions[base + 1] = tailY + parameter * (scratchPosition.y - tailY);
        positions[base + 2] = tailZ + parameter * (scratchPosition.z - tailZ);
        pointVertexId[pointCount] = -1;
        pointEdgeId[pointCount] = metric.sourceEdgeId[edge];
        pointFaceId[pointCount] = -1;
        pointFraction[pointCount] = (inputHalfEdge & 1) == 0 ? parameter : 1.0 - parameter;
        if (metric.sourceEdgeId[edge] < 0) {
            pointFaceId[pointCount] = metric.sourceFaceId[metric.halfEdgeFace[inputHalfEdge]];
            pointFraction[pointCount] = -1.0;
        }
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
        pointFaceId = Arrays.copyOf(pointFaceId, grown);
        pointFraction = Arrays.copyOf(pointFraction, grown);
    }
}
