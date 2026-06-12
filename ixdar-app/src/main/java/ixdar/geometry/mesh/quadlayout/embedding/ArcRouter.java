package ixdar.geometry.mesh.quadlayout.embedding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import org.joml.Vector3f;

import ixdar.geometry.mesh.quadlayout.crossfield.DijkstraNode;

/**
 * Embeds T-mesh arcs as edge paths on the working copy by <em>carving</em>
 * (LCBK19 §6.1 arc re-embedding): walk the arc's traced polyline and split
 * every crossed copy edge at the crossing point. Splitting the crossed edge of
 * a face that already contains the previous path vertex automatically creates
 * the chord edge between them, so each arc lays its own lane and parallel
 * arcs through one source face never contend for the three original edges.
 * Points landing near an existing vertex hop onto it instead of minting a
 * sliver. A short claims-respecting Dijkstra closes the final hop (or rescues
 * a blocked step) when the carve walk and the endpoint disagree by a face or
 * two.
 */
public final class ArcRouter {

    /** Hop onto an existing vertex when the point is within this edge fraction. */
    public static final float VERTEX_HOP_RATIO = 0.12f;

    /** Skip polyline points closer than this edge fraction to the current vertex. */
    public static final float POINT_SKIP_RATIO = 0.05f;

    /** Frontier cap for the local Dijkstra fallback. */
    public static final int FALLBACK_VISIT_CAP = 4000;

    /** Arc id whose carve walk gets a step-by-step debug trail, or -1. */
    public static final int DEBUG_ARC_ID = Integer.getInteger("embed.debugArc", -1);

    public final EmbeddedMeshTopology topology;
    public final ArcStripIndex strips;

    /** Carve steps that fell back to the local Dijkstra. */
    public int fallbackHopCount;

    /** Fallback searches that exhausted their frontier (walled off by claims). */
    public int fallbackExhaustedCount;

    /** Fallback searches that hit the visit cap. */
    public int fallbackCappedCount;

    /** Arcs whose routing failed even with the fallback. */
    public int routeFailureCount;

    /**
     * Stores inputs for arc routing.
     *
     * @param topology working copy with claims
     * @param strips   per-arc face strips and polylines
     */
    public ArcRouter(EmbeddedMeshTopology topology, ArcStripIndex strips) {
        this.topology = topology;
        this.strips = strips;
    }

    /**
     * Embed one arc between its endpoint node vertices, claiming the path's
     * edges and interior vertices on success.
     *
     * @param arcId           arc to embed
     * @param startCopyVertex copy vertex of the arc's start node
     * @param endCopyVertex   copy vertex of the arc's end node
     * @return the embedded path, or {@code null} when routing failed
     */
    public ArcEdgePath route(int arcId, int startCopyVertex, int endCopyVertex) {
        if (startCopyVertex == endCopyVertex) {
            List<Integer> singleVertex = new ArrayList<>(1);
            singleVertex.add(startCopyVertex);
            return new ArcEdgePath(arcId, singleVertex, new ArrayList<>(0));
        }
        List<Integer> vertices = new ArrayList<>();
        List<Integer> edges = new ArrayList<>();
        vertices.add(startCopyVertex);
        List<Vector3f> polyline = strips.polylineByArc.get(arcId);
        boolean debug = arcId == DEBUG_ARC_ID;
        if (debug) {
            Vector3f startPosition = topology.copy.vertexPosition(startCopyVertex, new Vector3f());
            Vector3f endPosition = topology.copy.vertexPosition(endCopyVertex, new Vector3f());
            System.out.printf("[carve] arc=%d start=%d %s end=%d %s points=%d%n", arcId,
                    startCopyVertex, format(startPosition), endCopyVertex, format(endPosition),
                    polyline.size());
        }
        for (Vector3f point : polyline) {
            int currentVertex = vertices.get(vertices.size() - 1);
            if (currentVertex == endCopyVertex) {
                break;
            }
            boolean advanced = carveStep(vertices, edges, point, endCopyVertex);
            if (debug) {
                int headVertex = vertices.get(vertices.size() - 1);
                System.out.printf("[carve]   point=%s advanced=%b head=%d %s%n", format(point),
                        advanced, headVertex,
                        format(topology.copy.vertexPosition(headVertex, new Vector3f())));
            }
        }
        if (!closeFinalHop(vertices, edges, endCopyVertex)) {
            unclaimPartial(vertices, edges);
            routeFailureCount++;
            return null;
        }
        ArcEdgePath path = new ArcEdgePath(arcId, vertices, edges);
        claimPath(arcId, path);
        return path;
    }

    /**
     * Advance the carve walk toward one polyline point: hop onto a nearby
     * existing vertex, or split the crossed opposite edge of a face incident
     * to the current vertex at the point's projection.
     *
     * @param vertices      path vertices so far (extended on success)
     * @param edges         path edges so far (extended on success)
     * @param point         next traced point
     * @param endCopyVertex final target (always hoppable)
     * @return whether the walk advanced
     */
    private boolean carveStep(List<Integer> vertices, List<Integer> edges, Vector3f point,
            int endCopyVertex) {
        int currentVertex = vertices.get(vertices.size() - 1);
        Vector3f currentPosition = topology.copy.vertexPosition(currentVertex, new Vector3f());
        Vector3f candidatePosition = new Vector3f();
        Vector3f projection = new Vector3f();
        Vector3f bestProjection = new Vector3f();

        float localEdgeLength = Float.POSITIVE_INFINITY;
        for (int index = 0; index < topology.copy.vertexEdgeCount(currentVertex); index++) {
            int edgeId = topology.copy.vertexEdgeAt(currentVertex, index);
            int neighbor = topology.otherEndpoint(edgeId, currentVertex);
            topology.copy.vertexPosition(neighbor, candidatePosition);
            localEdgeLength = Math.min(localEdgeLength, currentPosition.distance(candidatePosition));
        }
        if (point.distance(currentPosition) <= POINT_SKIP_RATIO * localEdgeLength) {
            return false;
        }

        int hopVertex = EmbeddedMeshTopology.UNCLAIMED;
        float hopDistance = Float.POSITIVE_INFINITY;
        int bestEdge = EmbeddedMeshTopology.UNCLAIMED;
        float bestEdgeDistance = Float.POSITIVE_INFINITY;
        for (int faceIndex = 0; faceIndex < topology.copy.vertexFaceCount(currentVertex); faceIndex++) {
            int faceId = topology.copy.vertexFaceAt(currentVertex, faceIndex);
            for (int corner = 0; corner < 3; corner++) {
                int vertex = topology.copy.faceVertexAt(faceId, corner);
                if (vertex == currentVertex) {
                    continue;
                }
                topology.copy.vertexPosition(vertex, candidatePosition);
                float toVertex = candidatePosition.distance(point);
                boolean hoppable = vertex == endCopyVertex
                        || (topology.ownerNodeByCopyVertex[vertex] == EmbeddedMeshTopology.UNCLAIMED
                                && topology.ownerArcByCopyVertex[vertex] == EmbeddedMeshTopology.UNCLAIMED);
                if (hoppable && toVertex < hopDistance) {
                    hopDistance = toVertex;
                    hopVertex = vertex;
                }
            }
            for (int corner = 0; corner < 3; corner++) {
                int edgeId = topology.copy.faceEdgeAt(faceId, corner);
                int halfEdge = topology.copy.edgeHalfEdge(edgeId);
                int edgeStart = topology.copy.halfEdgeVertex(halfEdge);
                int edgeEnd = topology.copy.halfEdgeEndVertex(halfEdge);
                if (edgeStart == currentVertex || edgeEnd == currentVertex
                        || topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED) {
                    continue;
                }
                Vector3f startPosition = topology.copy.vertexPosition(edgeStart, new Vector3f());
                Vector3f endPosition = topology.copy.vertexPosition(edgeEnd, new Vector3f());
                float toEdge = projectOntoSegment(point, startPosition, endPosition, projection);
                if (toEdge < bestEdgeDistance) {
                    bestEdgeDistance = toEdge;
                    bestEdge = edgeId;
                    bestProjection.set(projection);
                }
            }
        }

        if (hopVertex != EmbeddedMeshTopology.UNCLAIMED
                && hopDistance <= VERTEX_HOP_RATIO * localEdgeLength) {
            return appendHop(vertices, edges, currentVertex, hopVertex);
        }
        if (bestEdge != EmbeddedMeshTopology.UNCLAIMED) {
            int newVertex = topology.splitEdgeAtPoint(bestEdge, new Vector3f(bestProjection));
            return appendHop(vertices, edges, currentVertex, newVertex);
        }
        if (hopVertex != EmbeddedMeshTopology.UNCLAIMED) {
            return appendHop(vertices, edges, currentVertex, hopVertex);
        }
        return false;
    }

    /**
     * Append one hop to the path when an edge between the vertices exists.
     *
     * @param vertices path vertices
     * @param edges    path edges
     * @param from     current vertex
     * @param to       next vertex
     * @return whether the hop was appended
     */
    private boolean appendHop(List<Integer> vertices, List<Integer> edges, int from, int to) {
        int edgeId = topology.edgeBetween(from, to);
        if (edgeId == EmbeddedMeshTopology.UNCLAIMED) {
            return false;
        }
        vertices.add(to);
        edges.add(edgeId);
        return true;
    }

    /**
     * Close the path onto the end vertex: direct edge when adjacent, else a
     * short claims-respecting Dijkstra over unclaimed elements.
     *
     * @param vertices      path vertices (extended)
     * @param edges         path edges (extended)
     * @param endCopyVertex final target
     * @return whether the path now ends at the target
     */
    private boolean closeFinalHop(List<Integer> vertices, List<Integer> edges, int endCopyVertex) {
        int currentVertex = vertices.get(vertices.size() - 1);
        if (currentVertex == endCopyVertex) {
            return true;
        }
        if (appendHop(vertices, edges, currentVertex, endCopyVertex)) {
            return true;
        }
        fallbackHopCount++;
        return dijkstraHop(vertices, edges, currentVertex, endCopyVertex);
    }

    /**
     * Bounded local Dijkstra over unclaimed edges and vertices from the
     * current vertex to the target, appending the found path.
     *
     * @param vertices      path vertices (extended on success)
     * @param edges         path edges (extended on success)
     * @param startVertex   search source
     * @param endCopyVertex search target
     * @return whether the target was reached
     */
    private boolean dijkstraHop(List<Integer> vertices, List<Integer> edges, int startVertex,
            int endCopyVertex) {
        Map<Integer, Float> distance = new HashMap<>();
        Map<Integer, Integer> parentVertex = new HashMap<>();
        Map<Integer, Integer> parentEdge = new HashMap<>();
        PriorityQueue<DijkstraNode> frontier = new PriorityQueue<>();
        distance.put(startVertex, 0f);
        frontier.add(new DijkstraNode(0f, startVertex));
        Vector3f positionHere = new Vector3f();
        Vector3f positionOther = new Vector3f();
        int visited = 0;
        while (!frontier.isEmpty() && visited < FALLBACK_VISIT_CAP) {
            DijkstraNode head = frontier.poll();
            int vertex = head.vertexOrFace;
            if (head.distance > distance.getOrDefault(vertex, Float.POSITIVE_INFINITY)) {
                continue;
            }
            visited++;
            if (vertex == endCopyVertex) {
                List<Integer> hopVertices = new ArrayList<>();
                List<Integer> hopEdges = new ArrayList<>();
                int walk = endCopyVertex;
                while (walk != startVertex) {
                    hopVertices.add(walk);
                    hopEdges.add(parentEdge.get(walk));
                    walk = parentVertex.get(walk);
                }
                Collections.reverse(hopVertices);
                Collections.reverse(hopEdges);
                vertices.addAll(hopVertices);
                edges.addAll(hopEdges);
                return true;
            }
            topology.copy.vertexPosition(vertex, positionHere);
            for (int index = 0; index < topology.copy.vertexEdgeCount(vertex); index++) {
                int edgeId = topology.copy.vertexEdgeAt(vertex, index);
                if (topology.ownerArcByCopyEdge[edgeId] != EmbeddedMeshTopology.UNCLAIMED) {
                    continue;
                }
                int neighbor = topology.otherEndpoint(edgeId, vertex);
                if (neighbor != endCopyVertex
                        && (topology.ownerNodeByCopyVertex[neighbor] != EmbeddedMeshTopology.UNCLAIMED
                                || topology.ownerArcByCopyVertex[neighbor] != EmbeddedMeshTopology.UNCLAIMED)) {
                    continue;
                }
                topology.copy.vertexPosition(neighbor, positionOther);
                float newDistance = head.distance + positionHere.distance(positionOther);
                if (newDistance < distance.getOrDefault(neighbor, Float.POSITIVE_INFINITY)) {
                    distance.put(neighbor, newDistance);
                    parentVertex.put(neighbor, vertex);
                    parentEdge.put(neighbor, edgeId);
                    frontier.add(new DijkstraNode(newDistance, neighbor));
                }
            }
        }
        if (frontier.isEmpty()) {
            fallbackExhaustedCount++;
        } else {
            fallbackCappedCount++;
        }
        return false;
    }

    /**
     * Release interior-vertex bookkeeping of a failed partial path (edges were
     * never claimed; split vertices stay as harmless refinement).
     *
     * @param vertices partial path vertices
     * @param edges    partial path edges
     */
    private void unclaimPartial(List<Integer> vertices, List<Integer> edges) {
        vertices.clear();
        edges.clear();
    }

    /**
     * Claim a routed path's edges and interior vertices for its arc.
     *
     * @param arcId routed arc
     * @param path  reconstructed path
     */
    private void claimPath(int arcId, ArcEdgePath path) {
        for (int edgeId : path.copyEdgePath) {
            topology.ownerArcByCopyEdge[edgeId] = arcId;
        }
        for (int index = 1; index < path.copyVertexPath.size() - 1; index++) {
            topology.ownerArcByCopyVertex[path.copyVertexPath.get(index)] = arcId;
        }
    }

    /**
     * Compact position formatting for the debug trail.
     *
     * @param position position to format
     * @return "(x,y,z)" with 4 decimals
     */
    private static String format(Vector3f position) {
        return String.format("(%.4f,%.4f,%.4f)", position.x, position.y, position.z);
    }

    /**
     * Project a point onto a segment, clamped to its extent.
     *
     * @param point      query point
     * @param start      segment start
     * @param end        segment end
     * @param projection output: the clamped projection
     * @return distance from the point to the projection
     */
    private float projectOntoSegment(Vector3f point, Vector3f start, Vector3f end,
            Vector3f projection) {
        Vector3f direction = new Vector3f(end).sub(start);
        float lengthSquared = direction.lengthSquared();
        if (lengthSquared < Float.MIN_NORMAL) {
            projection.set(start);
            return start.distance(point);
        }
        float t = new Vector3f(point).sub(start).dot(direction) / lengthSquared;
        t = Math.max(0f, Math.min(1f, t));
        projection.set(start).fma(t, direction);
        return projection.distance(point);
    }
}
