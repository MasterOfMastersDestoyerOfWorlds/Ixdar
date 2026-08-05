package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.List;

/**
 * Triangulates a simple polygon given in barycentric coordinates of one source face,
 * clipping ears the exact orientation predicate certifies.
 *
 * <p>
 * See also: LCBK19 Section 6.1
 */
public final class EarClipping {

    /** Corners of a triangle. */
    public static final int CORNERS = 3;

    /** Local vertex index meaning "no vertex". */
    public static final int NONE = -1;

    /** Barycentric of each local vertex in the source face, indexed by local index. */
    public final double[][] barycentric;

    /** Local indices around the polygon, in the source face's winding. */
    public final List<Integer> cycle;

    /** Triangles produced, as local index triples wound like the face. */
    public final List<int[]> triangles = new ArrayList<>();

    /**
     * Stores one polygon and the coordinates its corners are measured in.
     *
     * @param barycentric barycentric of each local vertex in the source face
     * @param cycle       local indices around the polygon, in the face's winding
     */
    public EarClipping(double[][] barycentric, List<Integer> cycle) {
        this.barycentric = barycentric;
        this.cycle = cycle;
    }

    /**
     * Clips ears until the polygon is a triangle, so that a region bent around an interior
     * vertex is triangulated without inverting anything.
     *
     * @throws IllegalStateException when no ear can be found, so the polygon is not simple
     * @return this, triangulated
     */
    public EarClipping build() {
        List<Integer> remaining = new ArrayList<>(cycle);
        while (remaining.size() > CORNERS) {
            int clipped = NONE;
            for (int corner = 0; corner < remaining.size(); corner++) {
                int previous = remaining.get((corner + remaining.size() - 1) % remaining.size());
                int at = remaining.get(corner);
                int next = remaining.get((corner + 1) % remaining.size());
                if (!isEar(remaining, previous, at, next)) {
                    continue;
                }
                triangles.add(new int[] { previous, at, next });
                remaining.remove(corner);
                clipped = at;
                break;
            }
            if (clipped == NONE) {
                throw new IllegalStateException("region " + remaining + " has no ear; the cut"
                        + " produced a polygon that is not simple");
            }
        }
        if (remaining.size() == CORNERS) {
            triangles.add(new int[] { remaining.get(0), remaining.get(1), remaining.get(2) });
        }
        return this;
    }

    /**
     * Whether three consecutive polygon vertices form an ear: wound like the face, and
     * containing no other vertex of the polygon.
     *
     * @param remaining polygon vertices still to be clipped
     * @param previous  local index before the candidate
     * @param at        candidate local index
     * @param next      local index after the candidate
     * @return true when the triangle may be clipped off
     */
    private boolean isEar(List<Integer> remaining, int previous, int at, int next) {
        if (ExactBarycentricOrient.sign(barycentric[previous], barycentric[at],
                barycentric[next]) <= 0) {
            return false;
        }
        for (int other : remaining) {
            if (other == previous || other == at || other == next) {
                continue;
            }
            if (ExactBarycentricOrient.sign(barycentric[previous], barycentric[at],
                            barycentric[other]) >= 0
                    && ExactBarycentricOrient.sign(barycentric[at], barycentric[next],
                            barycentric[other]) >= 0
                    && ExactBarycentricOrient.sign(barycentric[next], barycentric[previous],
                            barycentric[other]) >= 0) {
                return false;
            }
        }
        return true;
    }
}
