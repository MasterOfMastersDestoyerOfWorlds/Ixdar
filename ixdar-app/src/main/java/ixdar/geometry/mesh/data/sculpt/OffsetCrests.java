package ixdar.geometry.mesh.data.sculpt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.joml.Matrix3f;
import org.joml.Vector3f;

import ixdar.geometry.mesh.data.analysis.curvature.CrestTrace;
import ixdar.geometry.mesh.data.paths.query.NearestVertex;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.geometry.mesh.data.representation.TriangleGeometry;
import ixdar.geometry.mesh.data.representation.TriangulationRule;
import ixdar.numeric.DenseLinearSystem;

/**
 * Ridge and valley lines of a scalar field over a surface, after OBS04: where the field's dominant
 * second derivative is extremal across the line, found as zero crossings on edges of the
 * derivative along its direction, joined through triangles and kept when strong enough.
 */
public final class OffsetCrests {

    public static final int FIT_UNKNOWNS = 5;

    public static final float SINGULAR = 1e-12f;

    public static final int FIELD_VALUE = CrestTrace.KIND_FIELD + 1;

    public static final float WIDTH_SEARCH_REACH = 1.5f;

    public static final double SMALLEST_WIDTH_FRACTION = 0.05;

    public static final int WIDTH_SEARCH_STEPS = 40;

    public static final double GOLDEN = 0.6180339887498949;

    /** Lines whose strength, the integral of |second derivative| along them (OBS04, eq. 5), is below this are dropped. */
    public float minimumStrength = 1.5f;

    /** Each kept line's points on the surface. */
    public final List<PackedPoints> lines = new ArrayList<>();

    /** Whether each kept line is a valley of the field (a minimum across it): a sinking stroke. */
    public final List<Boolean> valleyByLine = new ArrayList<>();

    /** Each kept line's feature half-width: the falloff radius whose bump has its depth and curvature. */
    public final List<Float> widthByLine = new ArrayList<>();

    /**
     * A field smoothed by one-ring averaging, each vertex with its neighbours.
     *
     * @param rings      neighbours by dense vertex index
     * @param field      the field by dense vertex index; left unchanged
     * @param iterations how many averaging passes
     * @return the smoothed field, a new array
     */
    public static float[] smoothed(int[][] rings, float[] field, int iterations) {
        float[] current = field.clone();
        for (int iteration = 0; iteration < iterations; iteration++) {
            float[] next = new float[current.length];
            for (int activeVertex = 0; activeVertex < current.length; activeVertex++) {
                float sum = current[activeVertex];
                for (int neighbour : rings[activeVertex]) {
                    sum += current[neighbour];
                }
                next[activeVertex] = sum / (rings[activeVertex].length + 1);
            }
            current = next;
        }
        return current;
    }

    /**
     * Find the field's ridge and valley lines: per vertex a two-ring quadratic gives the dominant
     * second derivative; edges where the slope along it crosses zero at an extremum hold line points
     * (OBS04, eqs. 3 and 4).
     *
     * @param surface    the surface the field lives on
     * @param field      the field by dense vertex index, smoothed as wanted for finding lines
     * @param depthField the unsmoothed offsets each line's width is fitted to, by dense vertex index
     * @param normalXyz  unit normals by dense vertex index
     */
    public void detect(MeshTopology surface, float[] field, float[] depthField, PackedPoints normalXyz) {
        int vertexCount = surface.vertexCount();
        int[] activeVertexById = new int[surface.vertexIdBound()];
        PackedPoints vertices = surface.packedPositions(activeVertexById);
        int[][] rings = surface.oneRings();
        float[] curvature = new float[vertexCount];
        PackedPoints directions = new PackedPoints(vertexCount);
        float[] slope = new float[vertexCount];
        Vector3f first = new Vector3f();
        Vector3f second = new Vector3f();
        int[] mark = new int[vertexCount];
        Arrays.fill(mark, -1);
        int[] twoRing = new int[vertexCount];
        for (int activeVertex = 0; activeVertex < vertexCount; activeVertex++) {
            Vector3f centre = vertices.at(activeVertex);
            Matrix3f frame = MeshTopology.tangentFrame(normalXyz.at(activeVertex));
            frame.getColumn(0, first);
            frame.getColumn(1, second);
            int size = 0;
            mark[activeVertex] = activeVertex;
            for (int neighbour : rings[activeVertex]) {
                for (int index = -1; index < rings[neighbour].length; index++) {
                    int member = index < 0 ? neighbour : rings[neighbour][index];
                    if (mark[member] != activeVertex) {
                        mark[member] = activeVertex;
                        twoRing[size++] = member;
                    }
                }
            }
            float reach = SINGULAR;
            for (int index = 0; index < size; index++) {
                reach = Math.max(reach, (float) Math.sqrt(vertices.distanceSquared(twoRing[index], centre)));
            }
            float[][] normalMatrix = new float[FIT_UNKNOWNS][FIT_UNKNOWNS];
            float[] rightSide = new float[FIT_UNKNOWNS];
            for (int index = 0; index < size; index++) {
                Vector3f offset = vertices.at(twoRing[index]).sub(centre).div(reach);
                float x = offset.dot(first);
                float y = offset.dot(second);
                float[] row = { x * x, x * y, y * y, x, y };
                float value = field[twoRing[index]] - field[activeVertex];
                for (int column = 0; column < FIT_UNKNOWNS; column++) {
                    for (int other = 0; other < FIT_UNKNOWNS; other++) {
                        normalMatrix[column][other] += row[column] * row[other];
                    }
                    rightSide[column] += row[column] * value;
                }
            }
            float[] scaled = size >= FIT_UNKNOWNS ? DenseLinearSystem.solve(normalMatrix,
                    rightSide, FIT_UNKNOWNS) : new float[FIT_UNKNOWNS];
            double[] fit = { scaled[0] / (reach * reach), scaled[1] / (reach * reach), scaled[2] / (reach * reach),
                scaled[3] / reach, scaled[4] / reach };
            double mean = fit[0] + fit[2];
            double spread = Math.sqrt((fit[0] - fit[2]) * (fit[0] - fit[2]) + fit[1] * fit[1]);
            double dominant = Math.abs(mean + spread) >= Math.abs(mean - spread) ? mean + spread : mean - spread;
            double tangentX = fit[1];
            double tangentY = dominant - 2.0 * fit[0];
            if (Math.abs(tangentX) + Math.abs(tangentY) < SINGULAR) {
                tangentX = fit[0] >= fit[2] == dominant >= mean ? 1.0 : 0.0;
                tangentY = 1.0 - tangentX;
            }
            double length = Math.hypot(tangentX, tangentY);
            tangentX /= length;
            tangentY /= length;
            curvature[activeVertex] = (float) dominant;
            slope[activeVertex] = (float) (fit[3] * tangentX + fit[4] * tangentY);
            directions.set(activeVertex, new Vector3f(first).mul((float) tangentX).fma((float) tangentY, second));
        }
        CrestTrace trace = new CrestTrace();
        int[] corners = surface.triangulate(TriangulationRule.FAN, null);
        for (int corner = 0; corner < corners.length; corner++) {
            int from = activeVertexById[corners[corner]];
            int to = activeVertexById[corners[corner % 3 == 2 ? corner - 2 : corner + 1]];
            int kind = curvature[from] > 0f ? 1 : 0;
            long key = CrestTrace.edgeKey(from, to, vertexCount, kind);
            if (trace.pointByEdge.containsKey(key) || curvature[from] * curvature[to] <= 0f) {
                continue;
            }
            float agree = directions.at(from).dot(directions.at(to));
            float fromSlope = slope[from];
            float toSlope = agree < 0f ? -slope[to] : slope[to];
            boolean valley = kind == 1;
            float along = vertices.at(to).sub(vertices.at(from)).dot(directions.at(from));
            if (fromSlope * toSlope >= 0f || (fromSlope * along > 0f) == valley) {
                trace.pointByEdge.put(key, -1);
                continue;
            }
            float share = Math.abs(fromSlope) / (Math.abs(fromSlope) + Math.abs(toSlope));
            double[] point = new double[FIELD_VALUE + 1];
            Vector3f crest = vertices.at(from).lerp(vertices.at(to), share);
            for (int axis = 0; axis < 3; axis++) {
                point[axis] = crest.get(axis);
            }
            point[CrestTrace.CURVATURE_FIELD] = curvature[from] + share * (curvature[to] - curvature[from]);
            point[CrestTrace.KIND_FIELD] = kind;
            point[FIELD_VALUE] = field[from] + share * (field[to] - field[from]);
            trace.pointByEdge.put(key, trace.points.size());
            trace.points.add(point);
        }
        NearestVertex index = NearestVertex.over(surface);
        for (int[] chain : trace.chains(corners, activeVertexById, vertexCount)) {
            if (trace.strength(chain) < minimumStrength) {
                continue;
            }
            PackedPoints line = new PackedPoints(chain.length);
            float[] widths = new float[chain.length];
            for (int point = 0; point < chain.length; point++) {
                double[] values = trace.points.get(chain[point]);
                line.set(point, (float) values[0], (float) values[1], (float) values[2]);
                widths[point] = 2f * (float) Math.sqrt(Math.abs(values[FIELD_VALUE])
                        / Math.max(SINGULAR, Math.abs(values[CrestTrace.CURVATURE_FIELD])));
            }
            lines.add(line);
            valleyByLine.add(trace.points.get(chain[0])[CrestTrace.KIND_FIELD] > 0.5);
            Arrays.sort(widths);
            widthByLine.add(fittedWidth(line, WIDTH_SEARCH_REACH * widths[chain.length / 2], surface, index,
                    activeVertexById, depthField));
        }
    }

    /**
     * The half-width of the chain stroke that best sculpts the offsets around a line: the w whose
     * bump d (1 - (s / w)^2)^2 at its best depth leaves the least squared error, s being each nearby
     * vertex's distance to the line. Found by golden-section search, at most {@code widest}.
     *
     * @param line             the line's points
     * @param widest           the widest half-width tried
     * @param surface          the surface the offsets live on
     * @param index            vertex grid over the surface
     * @param activeVertexById dense vertex index by vertex id
     * @param offset           the offsets the stroke must sculpt, by dense vertex index
     * @return the fitted half-width
     */
    public static float fittedWidth(PackedPoints line, float widest, MeshTopology surface, NearestVertex index,
            int[] activeVertexById, float[] offset) {
        int pointCount = line.size();
        int[] near = new int[0];
        for (int point = 0; point < pointCount; point++) {
            int[] found = index.within(line.at(point), widest);
            int had = near.length;
            near = Arrays.copyOf(near, had + found.length);
            System.arraycopy(found, 0, near, had, found.length);
        }
        near = Arrays.stream(near).distinct().toArray();
        float[] across = new float[near.length];
        float[] value = new float[near.length];
        Vector3f position = new Vector3f();
        for (int vertex = 0; vertex < near.length; vertex++) {
            surface.vertexPosition(near[vertex], position);
            double nearest = Double.POSITIVE_INFINITY;
            for (int span = 0; span < Math.max(1, pointCount - 1); span++) {
                nearest = Math.min(nearest,
                        TriangleGeometry.segmentDistanceSquared(line, span, (span + 1) % pointCount, position,
                                nearest));
            }
            across[vertex] = (float) Math.sqrt(nearest);
            value[vertex] = offset[activeVertexById[near[vertex]]];
        }
        double low = widest * SMALLEST_WIDTH_FRACTION;
        double high = widest;
        double inner = high - GOLDEN * (high - low);
        double outer = low + GOLDEN * (high - low);
        double innerScore = explained(inner, across, value);
        double outerScore = explained(outer, across, value);
        for (int step = 0; step < WIDTH_SEARCH_STEPS; step++) {
            if (innerScore >= outerScore) {
                high = outer;
                outer = inner;
                outerScore = innerScore;
                inner = high - GOLDEN * (high - low);
                innerScore = explained(inner, across, value);
            } else {
                low = inner;
                inner = outer;
                innerScore = outerScore;
                outer = low + GOLDEN * (high - low);
                outerScore = explained(outer, across, value);
            }
        }
        return (float) ((low + high) * 0.5);
    }

    /**
     * How much of the offsets' energy a bump of one half-width explains at its best depth.
     *
     * @param width  the bump's half-width
     * @param across each gathered vertex's distance to the line
     * @param value  each one's offset
     * @return (sum f b)^2 / sum b^2, 0 when the bump reaches none of them
     */
    private static double explained(double width, float[] across, float[] value) {
        double product = 0.0;
        double squared = 0.0;
        for (int vertex = 0; vertex < across.length; vertex++) {
            double fraction = across[vertex] / width;
            double bump = fraction < 1.0 ? (1.0 - fraction * fraction) * (1.0 - fraction * fraction) : 0.0;
            product += value[vertex] * bump;
            squared += bump * bump;
        }
        return squared > 0.0 ? product * product / squared : 0.0;
    }
}
