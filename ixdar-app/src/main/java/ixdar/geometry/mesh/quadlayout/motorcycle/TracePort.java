package ixdar.geometry.mesh.quadlayout.motorcycle;

import java.util.ArrayList;
import java.util.List;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.Singularity;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

/**
 * Outgoing parametric port at a singularity: a face corner plus axis-aligned
 * chart direction (QEx §4.3 Algorithm 4).
 */
public final class TracePort {

    public final int singularityVertexId;
    public final int activeFace;
    public final int cornerIndex;
    public final TraceAxis axis;
    public final int sign;

    public static final double ORIENT_COLLINEAR_EPSILON = 1.0e-12;

    /**
     * Describes an outgoing iso-line port at a singularity corner.
     *
     * @param singularityVertexId mesh vertex id of the singularity
     * @param activeFace          active face index containing the port
     * @param cornerIndex         corner index in {@code [0, 3)} on that face
     * @param axis                parametric axis of the outgoing trace
     * @param sign                +1 or -1 along the axis
     */
    public TracePort(int singularityVertexId, int activeFace, int cornerIndex, TraceAxis axis, int sign) {
        this.singularityVertexId = singularityVertexId;
        this.activeFace = activeFace;
        this.cornerIndex = cornerIndex;
        this.axis = axis;
        this.sign = sign;
    }

    /**
     * Enumerate QEx Algorithm 4 ports at every cross-field singularity.
     *
     * @param seamless built seamless parametrization with populated UV corners
     * @return ports for every singularity; valence 3/5 counts emerge from geometry
     */
    public static List<TracePort> spawnFromSingularities(SeamlessParameterization seamless) {
        List<TracePort> ports = new ArrayList<>();
        for (Singularity singularity : seamless.crossField.singularities) {
            HalfEdgeMesh mesh = seamless.mesh;
            CrossField crossField = seamless.crossField;
            int vertexId = singularity.vertexId();
            int faceCount = mesh.vertexFaceCount(vertexId);
            ChartWalker walker = new ChartWalker(seamless);
            for (int fanIndex = 0; fanIndex < faceCount; fanIndex++) {
                int faceId = mesh.vertexFaceAt(vertexId, fanIndex);
                int activeFace = crossField.faceIdToActive.get(faceId);
                int cornerIndex = cornerOfVertex(mesh, faceId, vertexId);
                float[] cornerUv = new float[ChartWalker.CORNER_UV_FLOATS];
                walker.faceCornerUv(activeFace, cornerUv);
                int nextCorner = (cornerIndex + 1) % SeamlessParameterization.CORNERS_PER_FACE;
                int thirdCorner = (cornerIndex + 2) % SeamlessParameterization.CORNERS_PER_FACE;
                double uu = cornerUv[cornerIndex * 2];
                double uv = cornerUv[cornerIndex * 2 + 1];
                double vu = cornerUv[nextCorner * 2];
                double vv = cornerUv[nextCorner * 2 + 1];
                double wu = cornerUv[thirdCorner * 2];
                double wv = cornerUv[thirdCorner * 2 + 1];
                double orientation = orient2d(uu, uv, vu, vv, wu, wv);
                if (Math.abs(orientation) <= ORIENT_COLLINEAR_EPSILON) {
                    continue;
                }
                if (orientation > 0.0) {
                    for (int r = 0; r < SeamlessParameterization.BRANCH_COUNT; r++) {
                        double[] dir = directionR90(r);
                        if (acceptCandidate(dir, uu, uv, vu, vv, wu, wv)) {
                            ports.add(portFromDirection(vertexId, activeFace, cornerIndex, dir));
                        }
                    }
                } else {
                    for (int r = 0; r < SeamlessParameterization.BRANCH_COUNT; r++) {
                        double[] dir = directionR90(r);
                        if (acceptCandidate(dir, uu, uv, wu, wv, vu, vv)) {
                            ports.add(portFromDirection(vertexId, activeFace, cornerIndex, dir));
                        }
                    }
                }
            }
            int expected = SeamlessParameterization.BRANCH_COUNT - singularity.index4();
            if (ports.size() != expected) {
                System.out.printf(
                        "[motorcycle] port count mismatch at vertex %d: got %d expected %d (index4=%d)%n",
                        vertexId, ports.size(), expected, singularity.index4());
                for (int fanIndex = 0; fanIndex < faceCount; fanIndex++) {
                    int faceId = mesh.vertexFaceAt(vertexId, fanIndex);
                    int activeFace = crossField.faceIdToActive.get(faceId);
                    int cornerIndex = cornerOfVertex(mesh, faceId, vertexId);
                    float[] cornerUv = new float[ChartWalker.CORNER_UV_FLOATS];
                    walker.faceCornerUv(activeFace, cornerUv);
                    int nc = (cornerIndex + 1) % SeamlessParameterization.CORNERS_PER_FACE;
                    int tc = (cornerIndex + 2) % SeamlessParameterization.CORNERS_PER_FACE;
                    System.out.printf("  fan=%d face=%d corner=%d uv: u=(%.4f,%.4f) v=(%.4f,%.4f) w=(%.4f,%.4f)%n",
                            fanIndex, activeFace, cornerIndex,
                            cornerUv[cornerIndex * 2], cornerUv[cornerIndex * 2 + 1],
                            cornerUv[nc * 2], cornerUv[nc * 2 + 1],
                            cornerUv[tc * 2], cornerUv[tc * 2 + 1]);
                }
                for (TracePort p : ports) {
                    System.out.printf("  port face=%d corner=%d axis=%s sign=%+d%n",
                            p.activeFace, p.cornerIndex, p.axis, p.sign);
                }
            }
        }
        return ports;
    }

    /**
     * A slice direction is a real port from this face iff it strictly enters the
     * wedge from u toward v→w, or it runs along the outgoing edge u→v IN THE
     * MATCHING DIRECTION. The opposite-direction collinear half of the pair belongs
     * to the other face that shares this edge (where it is the outgoing-from-its-u
     * edge), not to this face — counting it here would duplicate a port at
     * high-valence singularities whose seam edges happen to be axis-aligned.
     *
     * @param dir candidate direction (axis-aligned unit vector)
     * @param uu  u-coordinate of corner u
     * @param uv  v-coordinate of corner u
     * @param vu  u-coordinate of corner v (the outgoing-edge endpoint)
     * @param vv  v-coordinate of corner v
     * @param wu  u-coordinate of corner w (the wedge's far corner)
     * @param wv  v-coordinate of corner w
     * @return whether {@code dir} is a real port from this face's wedge at u
     */
    private static boolean acceptCandidate(double[] dir, double uu, double uv,
            double vu, double vv, double wu, double wv) {
        if (orient2d(uu, uv, vu, vv, uu + dir[0], uv + dir[1]) > ORIENT_COLLINEAR_EPSILON
                && orient2d(uu, uv, uu + dir[0], uv + dir[1], wu, wv) > ORIENT_COLLINEAR_EPSILON) {
            return true;
        }
        double edgeU = vu - uu;
        double edgeV = vv - uv;
        if (!(Math.abs(orient2d(0.0, 0.0, edgeU, edgeV, dir[0], dir[1])) <= ORIENT_COLLINEAR_EPSILON)) {
            return false;
        }
        return edgeU * dir[0] + edgeV * dir[1] > 0.0;
    }

    private static TracePort portFromDirection(int vertexId, int activeFace, int cornerIndex, double[] direction) {
        TraceAxis axis = TraceAxis.fromDirection(direction[0], direction[1]);
        int sign = TraceAxis.signFor(axis, direction[0], direction[1]);
        return new TracePort(vertexId, activeFace, cornerIndex, axis, sign);
    }

    private static int cornerOfVertex(HalfEdgeMesh mesh, int faceId, int vertexId) {
        for (int corner = 0; corner < SeamlessParameterization.CORNERS_PER_FACE; corner++) {
            if (mesh.faceVertexAt(faceId, corner) == vertexId) {
                return corner;
            }
        }
        return 0;
    }

    /**
     * Signed area of triangle {@code (a, b, c)}; positive iff {@code c} lies to the
     * left of directed line {@code a → b}.
     *
     * @param ax x-coordinate of point a
     * @param ay y-coordinate of point a
     * @param bx x-coordinate of point b
     * @param by y-coordinate of point b
     * @param cx x-coordinate of point c
     * @param cy y-coordinate of point c
     * @return signed doubled triangle area
     */
    public static double orient2d(double ax, double ay, double bx, double by, double cx, double cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    /**
     * QEx direction {@code d(r) = R_90^r · (1, 0)^T}.
     *
     * @param rotation quarter-turn count r
     * @return {@code [dx, dy]}
     */
    public static double[] directionR90(int rotation) {
        int r = ((rotation % 4) + 4) % 4;
        return switch (r) {
        case 0 -> new double[] { 1.0, 0.0 };
        case 1 -> new double[] { 0.0, 1.0 };
        case 2 -> new double[] { -1.0, 0.0 };
        default -> new double[] { 0.0, -1.0 };
        };
    }
}
