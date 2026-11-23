package ixdar.geometry.mesh.data.paths.geodesic;

import java.util.Arrays;
import java.util.PriorityQueue;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.MeshTopology;

/**
 * Every face's air taking its shortest way over the faces to a seam, never across a wall, and
 * only as much through a gap in a wall as the gap is wide enough for.
 */
public final class DirectAirRoutes {

    /** The edge each face's air leaves it by, a seam edge for a face beside a seam; -1 where no way leads out. */
    public final int[] exitEdge;

    /** The face each face's air goes to next, by face id; -1 beside a seam and where no way leads out. */
    public final int[] nextFace;

    /** The air leaving each face, its own and what reached it from behind, by face id. */
    public final double[] carried;

    /** How crowded each face's way out is: the air leaving over what the exit's width lets by. */
    public final double[] crowding;

    /**
     * Route every face's air to the nearest seam edge and add it up along the way.
     *
     * @param mesh         the surface
     * @param faceArea     the area of every face, by face id
     * @param faceCentroid the centre of every face, three coordinates by face id
     * @param regionOfFace the region of every face, by face id
     * @param borderEdge   the seam edges, by edge id
     * @param wallVertex   the wall vertices, by vertex id; null for none
     * @param rateOfRegion the air a unit of length lets by, by region
     */
    public DirectAirRoutes(MeshTopology mesh, double[] faceArea, float[] faceCentroid, int[] regionOfFace,
            boolean[] borderEdge, boolean[] wallVertex, double[] rateOfRegion) {
        int bound = mesh.faceIdBound();
        exitEdge = new int[bound];
        nextFace = new int[bound];
        carried = new double[bound];
        crowding = new double[bound];
        Arrays.fill(exitEdge, -1);
        Arrays.fill(nextFace, -1);
        double[] far = new double[bound];
        Arrays.fill(far, Double.POSITIVE_INFINITY);
        PriorityQueue<double[]> open = new PriorityQueue<>((one, other) -> Double.compare(one[0], other[0]));
        Vector3f from = new Vector3f();
        Vector3f to = new Vector3f();
        // A face beside a seam starts its way at the seam edge, half an edge off.
        for (int active = 0; active < mesh.edgeCount(); active++) {
            int edge = mesh.edgeIdAt(active);
            for (int side = 0; side < 2 && borderEdge[edge]; side++) {
                int face = mesh.edgeFace(edge, side);
                if (face >= 0 && exitEdge[face] < 0) {
                    exitEdge[face] = edge;
                    far[face] = 0;
                    open.add(new double[] { 0, face });
                }
            }
        }
        int[] order = new int[bound];
        int reached = 0;
        boolean[] done = new boolean[bound];
        while (!open.isEmpty()) {
            double[] top = open.poll();
            int face = (int) top[1];
            if (done[face]) {
                continue;
            }
            done[face] = true;
            order[reached++] = face;
            int xyz = SurfaceLifetime.COORDINATES;
            from.set(faceCentroid[xyz * face], faceCentroid[xyz * face + 1], faceCentroid[xyz * face + 2]);
            for (int at = 0; at < mesh.faceEdgeCount(face); at++) {
                int edge = mesh.faceEdgeAt(face, at);
                int other = mesh.edgeFace(edge, 0) == face ? mesh.edgeFace(edge, 1) : mesh.edgeFace(edge, 0);
                if (borderEdge[edge] || other < 0 || done[other] || openEnds(mesh, edge, wallVertex) == 0) {
                    continue;
                }
                to.set(faceCentroid[xyz * other], faceCentroid[xyz * other + 1], faceCentroid[xyz * other + 2]);
                double through = far[face] + from.distance(to);
                if (through < far[other]) {
                    far[other] = through;
                    nextFace[other] = face;
                    exitEdge[other] = edge;
                    open.add(new double[] { through, other });
                }
            }
        }
        // Farthest first, so a face has all the air from behind it before it passes its own on.
        for (int at = reached - 1; at >= 0; at--) {
            int face = order[at];
            int edge = exitEdge[face];
            int halfEdge = mesh.edgeHalfEdge(edge);
            double length = mesh.vertexPosition(mesh.halfEdgeVertex(halfEdge), from)
                    .distance(mesh.vertexPosition(mesh.halfEdgeEndVertex(halfEdge), to));
            int ends = borderEdge[edge] ? 2 : openEnds(mesh, edge, wallVertex);
            double letsBy = length * rateOfRegion[regionOfFace[face]] * ends / 2;
            carried[face] += faceArea[face];
            crowding[face] = letsBy > 0 ? carried[face] / letsBy : 0;
            if (nextFace[face] >= 0) {
                // Only a gap in a wall holds air back; open surface lets all of it on.
                carried[nextFace[face]] += ends < 2 ? Math.min(carried[face], letsBy) : carried[face];
            }
        }
    }

    /**
     * How many ends of an edge are not wall vertices.
     *
     * @param mesh       the surface
     * @param edge       the edge
     * @param wallVertex the wall vertices, by vertex id; null for none
     * @return 0 for an edge along a wall, 1 for an edge beside a gap in one, 2 for an open edge
     */
    public static int openEnds(MeshTopology mesh, int edge, boolean[] wallVertex) {
        int halfEdge = mesh.edgeHalfEdge(edge);
        return (wallVertex != null && wallVertex[mesh.halfEdgeVertex(halfEdge)] ? 0 : 1)
                + (wallVertex != null && wallVertex[mesh.halfEdgeEndVertex(halfEdge)] ? 0 : 1);
    }
}
