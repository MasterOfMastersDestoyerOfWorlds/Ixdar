package ixdar.geometry.mesh.data.paths.query;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.stream.IntStream;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.TriangulationRule;

/**
 * A surface's creases read from the largest sphere that rests on its outside at each vertex and
 * holds no other vertex: without limit on flat or bulging surface, small in a crease.
 */
public final class RestingSphere {

    public static final int XYZ = 3;

    public static final double HALF = 0.5;

    private RestingSphere() {
    }

    /**
     * The walls of a surface with no gaps in them. Every vertex whose resting sphere is no bigger
     * than the model's thinnest half-width starts as wall; the widest are then taken away for as
     * long as that neither tears nor pierces the wall.
     *
     * @param mesh      the surface, closed, of any polygon sizes
     * @param keepsEnds whether a wall's loose end is kept, so a groove that stops keeps its wall,
     *                  where otherwise only a band that runs right round something leaves one
     * @return whether each vertex is a wall, by vertex id
     */
    public static boolean[] walls(MeshTopology mesh, boolean keepsEnds) {
        int bound = mesh.vertexIdBound();
        int[] triangles = mesh.triangulate(TriangulationRule.FAN, null);
        double[] position = new double[XYZ * bound];
        double[] normal = new double[XYZ * bound];
        Vector3f first = new Vector3f();
        Vector3f second = new Vector3f();
        Vector3f third = new Vector3f();
        Vector3f least = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f most = new Vector3f(Float.NEGATIVE_INFINITY);
        for (int active = 0; active < mesh.vertexCount(); active++) {
            int vertex = mesh.vertexIdAt(active);
            mesh.vertexPosition(vertex, first);
            position[XYZ * vertex] = first.x;
            position[XYZ * vertex + 1] = first.y;
            position[XYZ * vertex + 2] = first.z;
            least.min(first);
            most.max(first);
        }
        // Each vertex's outward normal, its triangles' weighed by their areas, and round each vertex
        // the pairs of its neighbours that share a triangle with it.
        double outward = mesh.signedVolume() >= 0 ? 1 : -1;
        List<List<int[]>> around = new ArrayList<>();
        for (int vertex = 0; vertex < bound; vertex++) {
            around.add(new ArrayList<>());
        }
        for (int corner = 0; corner < triangles.length; corner += XYZ) {
            mesh.vertexPosition(triangles[corner], first);
            mesh.vertexPosition(triangles[corner + 1], second).sub(first);
            mesh.vertexPosition(triangles[corner + 2], third).sub(first);
            second.cross(third);
            for (int of = 0; of < XYZ; of++) {
                int vertex = triangles[corner + of];
                normal[XYZ * vertex] += outward * second.x;
                normal[XYZ * vertex + 1] += outward * second.y;
                normal[XYZ * vertex + 2] += outward * second.z;
                around.get(vertex).add(
                        new int[] { triangles[corner + (of + 1) % XYZ], triangles[corner + (of + 2) % XYZ] });
            }
        }
        double[] radius = new double[bound];
        IntStream.range(0, mesh.vertexCount()).parallel().forEach(active -> {
            int vertex = mesh.vertexIdAt(active);
            double length = Math.sqrt(normal[XYZ * vertex] * normal[XYZ * vertex]
                    + normal[XYZ * vertex + 1] * normal[XYZ * vertex + 1]
                    + normal[XYZ * vertex + 2] * normal[XYZ * vertex + 2]);
            double smallest = Double.POSITIVE_INFINITY;
            for (int other = 0; length > 0 && other < mesh.vertexCount(); other++) {
                int against = mesh.vertexIdAt(other);
                double x = position[XYZ * against] - position[XYZ * vertex];
                double y = position[XYZ * against + 1] - position[XYZ * vertex + 1];
                double z = position[XYZ * against + 2] - position[XYZ * vertex + 2];
                double above = (x * normal[XYZ * vertex] + y * normal[XYZ * vertex + 1]
                        + z * normal[XYZ * vertex + 2]) / length;
                // A sphere of this radius resting here has the other vertex just on its skin.
                if (above > 0) {
                    smallest = Math.min(smallest, (x * x + y * y + z * z) / (2 * above));
                }
            }
            radius[vertex] = smallest;
        });
        double limit = HALF * Math.min(most.x - least.x, Math.min(most.y - least.y, most.z - least.z));
        boolean[] walls = new boolean[bound];
        PriorityQueue<Integer> widest = new PriorityQueue<>(
                (one, two) -> Double.compare(radius[two], radius[one]));
        for (int active = 0; active < mesh.vertexCount(); active++) {
            int vertex = mesh.vertexIdAt(active);
            walls[vertex] = radius[vertex] <= limit;
            if (walls[vertex]) {
                widest.add(vertex);
            }
        }
        while (!widest.isEmpty()) {
            int vertex = widest.poll();
            if (!walls[vertex]) {
                continue;
            }
            // Its wall neighbours lie in one unbroken run round it, and not all the way round, when
            // the ring of its neighbours changes between wall and not exactly twice.
            int changes = 0;
            for (int[] pair : around.get(vertex)) {
                changes += walls[pair[0]] != walls[pair[1]] ? 1 : 0;
            }
            int beside = 0;
            for (int spoke = 0; spoke < mesh.vertexEdgeCount(vertex); spoke++) {
                beside += walls[mesh.edgeOtherVertex(mesh.vertexEdgeAt(vertex, spoke), vertex)] ? 1 : 0;
            }
            if (beside == 0 || changes == 2 && !(keepsEnds && beside == 1)) {
                walls[vertex] = false;
                for (int spoke = 0; spoke < mesh.vertexEdgeCount(vertex); spoke++) {
                    int other = mesh.edgeOtherVertex(mesh.vertexEdgeAt(vertex, spoke), vertex);
                    if (walls[other]) {
                        widest.add(other);
                    }
                }
            }
        }
        return walls;
    }
}
