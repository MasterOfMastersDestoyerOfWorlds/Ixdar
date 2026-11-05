package ixdar.geometry.mesh.data.analysis.curvature;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Crest points found on a mesh's edges, joined through its triangles into polylines (OBS04 section 3):
 * two points of one kind in a triangle by a segment, three each to their centroid.
 */
public final class CrestTrace {

    public static final int CURVATURE_FIELD = 3;

    public static final int KIND_FIELD = 4;

    public static final int KINDS = 2;

    /** Each point: xyz, the curvature its strength integrates, its kind (0 or 1), then the caller's fields. */
    public final List<double[]> points = new ArrayList<>();

    /** Point index by {@link #edgeKey}; -1 marks an edge tested and found to hold none. */
    public final Map<Long, Integer> pointByEdge = new HashMap<>();

    /**
     * The key of one kind's point on an edge, whichever way the edge is given.
     *
     * @param from        one end, dense vertex index
     * @param to          the other end
     * @param vertexCount the mesh's vertex count
     * @param kind        0 or 1
     * @return the key
     */
    public static long edgeKey(int from, int to, int vertexCount, int kind) {
        return ((long) Math.min(from, to) * vertexCount + Math.max(from, to)) * KINDS + kind;
    }

    /**
     * Join the points through the triangles and walk them into chains, from ends and junctions first,
     * then closed loops.
     *
     * @param corners          the mesh's triangles, three vertex ids each
     * @param activeVertexById dense vertex index by vertex id
     * @param vertexCount      the mesh's vertex count
     * @return each chain's point indices in order, a loop ending where it started
     */
    public List<int[]> chains(int[] corners, int[] activeVertexById, int vertexCount) {
        List<int[]> links = new ArrayList<>();
        for (int corner = 0; corner + 2 < corners.length; corner += 3) {
            for (int kind = 0; kind < KINDS; kind++) {
                int[] found = new int[3];
                int count = 0;
                for (int side = 0; side < 3; side++) {
                    Integer point = pointByEdge.get(edgeKey(activeVertexById[corners[corner + side]],
                            activeVertexById[corners[corner + (side + 1) % 3]], vertexCount, kind));
                    if (point != null && point >= 0) {
                        found[count++] = point;
                    }
                }
                if (count == 2) {
                    links.add(new int[] { found[0], found[1] });
                } else if (count == 3) {
                    double[] centroid = new double[points.get(found[0]).length];
                    for (int index = 0; index < 3; index++) {
                        for (int field = 0; field < centroid.length; field++) {
                            centroid[field] += points.get(found[index])[field] / 3.0;
                        }
                    }
                    centroid[KIND_FIELD] = kind;
                    points.add(centroid);
                    for (int index = 0; index < 3; index++) {
                        links.add(new int[] { found[index], points.size() - 1 });
                    }
                }
            }
        }
        int[][] adjacent = new int[points.size()][0];
        for (int[] link : links) {
            for (int end = 0; end < 2; end++) {
                int[] list = adjacent[link[end]];
                adjacent[link[end]] = Arrays.copyOf(list, list.length + 1);
                adjacent[link[end]][list.length] = link[1 - end];
            }
        }
        List<int[]> chains = new ArrayList<>();
        Map<Long, Boolean> walked = new HashMap<>();
        long size = points.size();
        int[] chain = new int[points.size() + 1];
        for (int pass = 0; pass < 2; pass++) {
            for (int start = 0; start < points.size(); start++) {
                if (pass == 0 && adjacent[start].length == 2) {
                    continue;
                }
                for (int next : adjacent[start]) {
                    if (walked.containsKey(Math.min(start, next) * size + Math.max(start, next))) {
                        continue;
                    }
                    int length = 0;
                    chain[length++] = start;
                    int previous = start;
                    int current = next;
                    while (true) {
                        walked.put(Math.min(previous, current) * size + Math.max(previous, current), true);
                        chain[length++] = current;
                        if (adjacent[current].length != 2 || current == start) {
                            break;
                        }
                        int after = adjacent[current][0] == previous ? adjacent[current][1] : adjacent[current][0];
                        previous = current;
                        current = after;
                    }
                    chains.add(Arrays.copyOf(chain, length));
                }
            }
        }
        return chains;
    }

    /**
     * A chain's strength: the integral of its points' curvature magnitude along it (OBS04 eq. 5),
     * by the trapezoid rule.
     *
     * @param chain point indices in order
     * @return the strength
     */
    public double strength(int[] chain) {
        double strength = 0.0;
        for (int index = 0; index + 1 < chain.length; index++) {
            double[] from = points.get(chain[index]);
            double[] to = points.get(chain[index + 1]);
            double squared = 0.0;
            for (int axis = 0; axis < 3; axis++) {
                squared += (to[axis] - from[axis]) * (to[axis] - from[axis]);
            }
            strength += Math.sqrt(squared) * (Math.abs(from[CURVATURE_FIELD]) + Math.abs(to[CURVATURE_FIELD])) / 2.0;
        }
        return strength;
    }
}
