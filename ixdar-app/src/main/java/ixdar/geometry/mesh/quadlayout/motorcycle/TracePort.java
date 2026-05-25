package ixdar.geometry.mesh.quadlayout.motorcycle;

import java.util.ArrayList;
import java.util.List;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.quadlayout.Singularity;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.seamless.SeamlessParameterization;

/**
 * Outgoing parametric port at a singularity: a face corner plus axis-aligned
 * chart direction.
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
     * Enumerate all parametric ports at interior singularities by sweeping the
     * vertex fan and emitting a port each time the cumulative cross direction
     * crosses an integer multiple of {@code π/2}.
     *
     * @param seamless built seamless parametrization with populated UV corners
     * @return ports for every singularity; size {@code 4 + index4} per vertex
     */
    public static List<TracePort> spawnFromSingularities(SeamlessParameterization seamless) {
        CrossField crossField = seamless.crossField;
        HalfEdgeMesh mesh = seamless.mesh;
        List<TracePort> ports = new ArrayList<>();
        for (Singularity singularity : crossField.singularities) {
            ports.addAll(spawnAtVertex(seamless, singularity));
        }
        return ports;
    }

    private static List<TracePort> spawnAtVertex(SeamlessParameterization seamless, Singularity singularity) {
        HalfEdgeMesh mesh = seamless.mesh;
        CrossField crossField = seamless.crossField;
        int vertexId = singularity.vertexId();
        int faceCount = mesh.vertexFaceCount(vertexId);
        List<TracePort> ports = new ArrayList<>();
        double cumulative = 0.0;
        int lastK = Integer.MIN_VALUE;
        for (int fanIndex = 0; fanIndex < faceCount; fanIndex++) {
            int faceId = mesh.vertexFaceAt(vertexId, fanIndex);
            int activeFace = crossField.faceIdToActive.get(faceId);
            int cornerIndex = cornerOfVertex(mesh, faceId, vertexId);
            int branch = seamless.cutGraph.faceBranch[activeFace];
            double baseAngle = branch * (Math.PI / 2.0);
            for (int k = 0; k < SeamlessParameterization.BRANCH_COUNT; k++) {
                double angle = baseAngle + k * (Math.PI / 2.0);
                int kQuant = (int) Math.round(angle / (Math.PI / 2.0));
                if (kQuant != lastK) {
                    lastK = kQuant;
                    double dx = Math.cos(angle);
                    double dy = Math.sin(angle);
                    TraceAxis axis = TraceAxis.fromDirection(dx, dy);
                    int sign = TraceAxis.signFor(axis, dx, dy);
                    ports.add(new TracePort(vertexId, activeFace, cornerIndex, axis, sign));
                }
                cumulative += Math.PI / 2.0;
            }
        }
        int expected = SeamlessParameterization.BRANCH_COUNT + singularity.index4();
        if (ports.size() != expected) {
            ports.clear();
            for (int fanIndex = 0; fanIndex < faceCount; fanIndex++) {
                int faceId = mesh.vertexFaceAt(vertexId, fanIndex);
                int activeFace = crossField.faceIdToActive.get(faceId);
                int cornerIndex = cornerOfVertex(mesh, faceId, vertexId);
                int branch = seamless.cutGraph.faceBranch[activeFace];
                for (int k = 0; k < expected; k++) {
                    double angle = branch * (Math.PI / 2.0) + k * (2.0 * Math.PI / expected);
                    double dx = Math.cos(angle);
                    double dy = Math.sin(angle);
                    TraceAxis axis = TraceAxis.fromDirection(dx, dy);
                    int sign = TraceAxis.signFor(axis, dx, dy);
                    ports.add(new TracePort(vertexId, activeFace, cornerIndex, axis, sign));
                }
                break;
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
}
