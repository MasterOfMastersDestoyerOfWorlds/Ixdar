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

    private static final int PORTS_PER_CORNER_SLICE = 3;

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
            ports.addAll(spawnAtVertex(seamless, singularity));
        }
        return ports;
    }

    /**
     * QEx §4.3 Algorithm 4 vertex-q-vertex port enumeration at one singularity.
     *
     * @param seamless    built seamless parametrization
     * @param singularity singularity to spawn ports for
     * @return ports in clockwise surface order
     */
    public static List<TracePort> spawnAtVertex(SeamlessParameterization seamless, Singularity singularity) {
        HalfEdgeMesh mesh = seamless.mesh;
        CrossField crossField = seamless.crossField;
        int vertexId = singularity.vertexId();
        int faceCount = mesh.vertexFaceCount(vertexId);
        List<TracePort> ports = new ArrayList<>();
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
            double orientation = UvPredicates.orient2d(uu, uv, vu, vv, wu, wv);
            if (Math.abs(orientation) <= UvPredicates.ORIENT_COLLINEAR_EPSILON) {
                continue;
            }
            if (orientation > 0.0) {
                appendPortsCcw(ports, vertexId, activeFace, cornerIndex, uu, uv, vu, vv, wu, wv);
            } else {
                appendPortsFlipped(ports, vertexId, activeFace, cornerIndex, uu, uv, vu, vv, wu, wv);
            }
        }
        int expected = SeamlessParameterization.BRANCH_COUNT - singularity.index4();
        if (ports.size() != expected) {
            System.out.printf(
                    "[motorcycle] port count mismatch at vertex %d: got %d expected %d (index4=%d)%n",
                    vertexId, ports.size(), expected, singularity.index4());
        }
        return ports;
    }

    private static void appendPortsCcw(List<TracePort> ports, int vertexId, int activeFace, int cornerIndex,
            double uu, double uv, double vu, double vv, double wu, double wv) {
        int rotation = 0;
        double[] direction = UvPredicates.directionR90(rotation);
        while (UvPredicates.pointsInto(direction[0], direction[1], uu, uv, vu, vv, wu, wv)) {
            rotation++;
            direction = UvPredicates.directionR90(rotation);
        }
        for (int slice = 1; slice <= PORTS_PER_CORNER_SLICE; slice++) {
            double[] rawDirection = UvPredicates.directionR90(rotation - slice);
            if (UvPredicates.pointsInto(rawDirection[0], rawDirection[1], uu, uv, vu, vv, wu, wv)
                    || UvPredicates.isCollinear(vu - uu, vv - uv, rawDirection[0], rawDirection[1])) {
                double[] portDirection = alignCollinearPortDirection(rawDirection, uu, uv, vu, vv);
                ports.add(portFromDirection(vertexId, activeFace, cornerIndex, portDirection));
            }
        }
    }

    private static void appendPortsFlipped(List<TracePort> ports, int vertexId, int activeFace, int cornerIndex,
            double uu, double uv, double vu, double vv, double wu, double wv) {
        int rotation = 0;
        double[] direction = UvPredicates.directionR90(rotation);
        while (UvPredicates.pointsInto(direction[0], direction[1], uu, uv, wu, wv, vu, vv)) {
            rotation++;
            direction = UvPredicates.directionR90(rotation);
        }
        for (int slice = 1; slice <= PORTS_PER_CORNER_SLICE; slice++) {
            double[] rawDirection = UvPredicates.directionR90(rotation + slice);
            if (UvPredicates.pointsInto(rawDirection[0], rawDirection[1], uu, uv, wu, wv, vu, vv)
                    || UvPredicates.isCollinear(vu - uu, vv - uv, rawDirection[0], rawDirection[1])) {
                double[] portDirection = alignCollinearPortDirection(rawDirection, uu, uv, vu, vv);
                ports.add(portFromDirection(vertexId, activeFace, cornerIndex, portDirection));
            }
        }
    }

    private static double[] alignCollinearPortDirection(double[] direction,
            double uu, double uv, double vu, double vv) {
        double edgeU = vu - uu;
        double edgeV = vv - uv;
        if (!UvPredicates.isCollinear(edgeU, edgeV, direction[0], direction[1])) {
            return direction;
        }
        if (edgeU * direction[0] + edgeV * direction[1] < 0.0) {
            return new double[] { -direction[0], -direction[1] };
        }
        return direction;
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
}
