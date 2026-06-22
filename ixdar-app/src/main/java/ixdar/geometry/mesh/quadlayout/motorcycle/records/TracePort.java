package ixdar.geometry.mesh.quadlayout.motorcycle.records;

import java.util.ArrayList;
import java.util.List;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.Singularity;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.motorcycle.ChartWalker;
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
            for (int fanIndex = 0; fanIndex < faceCount; fanIndex++) {
                int faceId = mesh.vertexFaceAt(vertexId, fanIndex);
                int activeFace = crossField.faceIdToActive.get(faceId);
                int cornerIndex = cornerOfVertex(mesh, faceId, vertexId);
                double[] cornerUv = new double[ChartWalker.CORNER_UV_FLOATS];
                seamless.faceCornerUv(activeFace, cornerUv);
                int nextCorner = (cornerIndex + 1) % SeamlessParameterization.CORNERS_PER_FACE;
                int thirdCorner = (cornerIndex + 2) % SeamlessParameterization.CORNERS_PER_FACE;
                double uu = cornerUv[cornerIndex * 2];
                double uv = cornerUv[cornerIndex * 2 + 1];
                double vu = cornerUv[nextCorner * 2];
                double vv = cornerUv[nextCorner * 2 + 1];
                double wu = cornerUv[thirdCorner * 2];
                double wv = cornerUv[thirdCorner * 2 + 1];
                double orientation = orient2d(uu, uv, vu, vv, wu, wv);
                if (orientation > 0.0) {
                    for (int r = 0; r < SeamlessParameterization.BRANCH_COUNT; r++) {
                        double[] dir = switch (((r % 4) + 4) % 4) {
                        case 0 -> new double[] { 1.0, 0.0 };
                        case 1 -> new double[] { 0.0, 1.0 };
                        case 2 -> new double[] { -1.0, 0.0 };
                        default -> new double[] { 0.0, -1.0 };
                        };
                        boolean acceptCandidate = false;

                        double edgeU = vu - uu;
                        double edgeV = vv - uv;
                        if (orient2d(uu, uv, vu, vv, uu + dir[0], uv + dir[1]) > 0
                                && orient2d(uu, uv, uu + dir[0], uv + dir[1], wu, wv) > 0) {
                            acceptCandidate = true;
                        } else if (!(Math.abs(orient2d(0.0, 0.0, edgeU, edgeV, dir[0], dir[1])) <= 0)) {
                            acceptCandidate = false;
                        } else {
                            acceptCandidate = edgeU * dir[0] + edgeV * dir[1] > 0.0;
                        }
                        if (acceptCandidate) {
                            TraceAxis axis = TraceAxis.fromDirection(dir[0], dir[1]);
                            int sign = TraceAxis.signFor(axis, dir[0], dir[1]);
                            ports.add(new TracePort(vertexId, activeFace, cornerIndex, axis, sign));
                        }
                    }
                }
            }
        }
        return ports;
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
}
