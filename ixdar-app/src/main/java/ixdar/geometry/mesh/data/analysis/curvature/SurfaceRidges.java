package ixdar.geometry.mesh.data.analysis.curvature;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.mesh.data.representation.TriangulationRule;

/**
 * A surface's ridge and valley lines from its own curvature, after OBS04 (Ohtake, Belyaev and Seidel,
 * "Ridge-valley lines on meshes via implicit surface fitting", SIGGRAPH 2004).
 */
public final class SurfaceRidges {

    public static final double SCALED_DIAGONAL = 20.0 * Math.sqrt(3.0);

    public static final double FIRST_CELL_OF_DIAGONAL = 0.25;

    public static final double FINEST_CELL_IN_EDGES = 2.5;

    /** Most levels fitted; the finest clusters on a lattice 1/4 of the scaled diagonal over 2^(levels - 1). */
    public int levels = 6;

    /** Levels the last {@link #detect} fitted: {@link #levels}, less any whose lattice step falls below
     * {@link #FINEST_CELL_IN_EDGES} mean edges, where a level would fit the sampling rather than the shape. */
    public int levelsFitted;

    /** OBS04's lambda, added to each level's basis system. */
    public double regularization = 0.1;

    /** Lines whose curvature integral (OBS04 eq. 5) at the scaled diagonal is below this are dropped. */
    public double minimumStrength = 2.0;

    /** Each kept line's points, in the mesh's own units. */
    public final List<PackedPoints> lines = new ArrayList<>();

    /** Whether each kept line is a valley (most concave) rather than a ridge (most convex). */
    public final List<Boolean> valleyByLine = new ArrayList<>();

    /** Each kept line's curvature integral at the scaled diagonal. */
    public final List<Float> strengthByLine = new ArrayList<>();

    /** Larger principal curvature by dense vertex index at the scaled diagonal, + where convex. */
    public float[] maximumCurvature = new float[0];

    /** Smaller principal curvature by dense vertex index at the scaled diagonal. */
    public float[] minimumCurvature = new float[0];

    /** Cluster centres each level fitted. */
    public int[] centresByLevel = new int[0];

    /** Wall seconds fitting, projecting for curvatures, and tracing took. */
    public final double[] seconds = new double[3];

    /**
     * Fit the mesh, find its curvatures and trace its ridge and valley lines, replacing earlier results.
     *
     * @param mesh the surface, any polygon faces
     */
    public void detect(MeshTopology mesh) {
        long started = System.nanoTime();
        lines.clear();
        valleyByLine.clear();
        strengthByLine.clear();
        int vertexCount = mesh.vertexCount();
        Vector3f low = mesh.boundsMin(new Vector3f());
        Vector3f high = mesh.boundsMax(new Vector3f());
        double scale = SCALED_DIAGONAL / Math.max(ImplicitFit.SMALLEST, high.distance(low));
        Vector3f middle = new Vector3f(low).add(high).mul(0.5f);
        int[] activeVertexById = new int[mesh.vertexIdBound()];
        PackedPoints vertexXyz = mesh.packedPositions(activeVertexById);
        vertexXyz.transformPositions(new Matrix4f().translation(new Vector3f(middle).negate()));
        vertexXyz.transformPositions(new Matrix4f().scaling((float) scale));
        PackedPoints normalXyz = new PackedPoints(vertexCount);
        Vector3f normal = new Vector3f();
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            normalXyz.set(activeVertex, mesh.vertexNormal(mesh.vertexIdAt(activeVertex), normal).normalize());
        }
        double meanEdge = mesh.meanEdgeLength() * scale;
        double firstCell = FIRST_CELL_OF_DIAGONAL * SCALED_DIAGONAL;
        levelsFitted = levels;
        while (levelsFitted > 1 && firstCell / (1 << levelsFitted - 1) < FINEST_CELL_IN_EDGES * meanEdge) {
            levelsFitted--;
        }
        ImplicitFit fit = new ImplicitFit(vertexXyz, normalXyz, firstCell, levelsFitted, regularization);
        centresByLevel = fit.levels.stream().mapToInt(level -> level.weight.length).toArray();
        seconds[0] = (System.nanoTime() - started) / 1e9;
        started = System.nanoTime();
        maximumCurvature = new float[vertexCount];
        minimumCurvature = new float[vertexCount];
        double[] curvatures = new double[vertexCount * ImplicitFit.CURVATURE_LENGTH];
        IntStream.range(0, vertexCount).parallel().forEach(activeVertex -> {
            double[] out = new double[ImplicitFit.CURVATURE_LENGTH];
            if (fit.curvature(vertexXyz.at(activeVertex), out)) {
                System.arraycopy(out, 0, curvatures, activeVertex * ImplicitFit.CURVATURE_LENGTH, out.length);
                maximumCurvature[activeVertex] = (float) out[0];
                minimumCurvature[activeVertex] = (float) out[1];
            }
        });
        seconds[1] = (System.nanoTime() - started) / 1e9;
        started = System.nanoTime();
        CrestTrace trace = new CrestTrace();
        int[] corners = mesh.triangulate(TriangulationRule.FAN, null);
        for (int corner = 0; corner < corners.length; corner++) {
            int from = activeVertexById[corners[corner]];
            int to = activeVertexById[corners[corner % 3 == 2 ? corner - 2 : corner + 1]];
            for (int kind = 0; kind < CrestTrace.KINDS; kind++) {
                long key = CrestTrace.edgeKey(from, to, vertexCount, kind);
                if (trace.pointByEdge.putIfAbsent(key, -1) != null) {
                    continue;
                }
                double sign = kind == 0 ? 1.0 : -1.0;
                int fromAt = from * ImplicitFit.CURVATURE_LENGTH;
                int toAt = to * ImplicitFit.CURVATURE_LENGTH;
                double fromCurvature = sign * curvatures[fromAt + kind];
                double toCurvature = sign * curvatures[toAt + kind];
                if (fromCurvature <= Math.abs(curvatures[fromAt + 1 - kind])
                        || toCurvature <= Math.abs(curvatures[toAt + 1 - kind])) {
                    continue;
                }
                double agree = 0.0;
                double fromAlong = 0.0;
                double toAlong = 0.0;
                int direction = ImplicitFit.DIRECTIONS_START + kind * 3;
                for (int axis = 0; axis < 3; axis++) {
                    double edge = (double) vertexXyz.get(to, axis) - vertexXyz.get(from, axis);
                    agree += curvatures[fromAt + direction + axis] * curvatures[toAt + direction + axis];
                    fromAlong += edge * curvatures[fromAt + direction + axis];
                    toAlong -= edge * curvatures[toAt + direction + axis];
                }
                double fromSlope = sign * curvatures[fromAt + ImplicitFit.DERIVATIVES_START + kind];
                double toSlope = sign * curvatures[toAt + ImplicitFit.DERIVATIVES_START + kind];
                if (agree < 0.0) {
                    toSlope = -toSlope;
                    toAlong = -toAlong;
                }
                if (fromSlope * toSlope >= 0.0 || fromSlope * fromAlong <= 0.0 && toSlope * toAlong <= 0.0) {
                    continue;
                }
                double fromShare = Math.abs(toSlope) / (Math.abs(fromSlope) + Math.abs(toSlope));
                double[] point = new double[CrestTrace.KIND_FIELD + 1];
                for (int axis = 0; axis < 3; axis++) {
                    point[axis] = fromShare * vertexXyz.get(from, axis) + (1.0 - fromShare) * vertexXyz.get(to, axis);
                }
                point[CrestTrace.CURVATURE_FIELD] = fromShare * fromCurvature + (1.0 - fromShare) * toCurvature;
                point[CrestTrace.KIND_FIELD] = kind;
                trace.pointByEdge.put(key, trace.points.size());
                trace.points.add(point);
            }
        }
        for (int[] chain : trace.chains(corners, activeVertexById, vertexCount)) {
            double strength = trace.strength(chain);
            if (strength < minimumStrength) {
                continue;
            }
            PackedPoints line = new PackedPoints(chain.length);
            for (int index = 0; index < chain.length; index++) {
                double[] point = trace.points.get(chain[index]);
                line.set(index, (float) (point[0] / scale + middle.x), (float) (point[1] / scale + middle.y),
                        (float) (point[2] / scale + middle.z));
            }
            lines.add(line);
            valleyByLine.add(trace.points.get(chain[0])[CrestTrace.KIND_FIELD] > 0.5);
            strengthByLine.add((float) strength);
        }
        seconds[2] = (System.nanoTime() - started) / 1e9;
    }
}
