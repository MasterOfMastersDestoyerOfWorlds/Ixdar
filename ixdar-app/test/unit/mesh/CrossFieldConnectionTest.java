package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.paths.SurfaceMetric;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh.EdgeFaceIds;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.nodes.data.SurfaceMetricNode;
import ixdar.geometry.mesh.quadlayout.crossfield.CrossField;
import ixdar.geometry.mesh.quadlayout.crossfield.NDirectionField;

/**
 * The cross field reads its frames and connection from the surface metric: its face-to-face
 * transport is the face frame rotated about the shared edge, its spoke angles are the rescaled
 * corner sums from the metric's reference half-edge, and a wired metric gives the field a built
 * one gives.
 */
class CrossFieldConnectionTest {

    private static final String TORUS_SOURCE =
            "carrier = torus(major_radius=1.0, minor_radius=0.35, major_segments=12,"
                    + " minor_segments=8, triangulate=true)";

    private static final String DISK_SOURCE =
            "carrier = mesh_disk(rings=4, angular_segments=12, radius=4.0, triangulate=true)";

    private static final double CURVATURE_BIAS = -1.0;

    // Float positions and a float kappa: the extrinsic rotation is good to a few float ulps.
    private static final double KAPPA_TOLERANCE = 1.0e-5;

    private static final double SPOKE_TOLERANCE = 1.0e-5;

    @Test
    void kappaIsTheFaceFrameRotatedAboutTheSharedEdge() throws Exception {
        HalfEdgeMesh mesh = carrier(TORUS_SOURCE);
        NDirectionField solve = new NDirectionField();
        solve.curvatureBias = CURVATURE_BIAS;
        CrossField field = solve.build(mesh, SurfaceMetric.of(mesh));
        int compared = 0;
        for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
            EdgeFaceIds ids = mesh.edgeFaceIds(activeEdge);
            if (mesh.isBoundaryEdge(ids.edgeId)) {
                continue;
            }
            Vector3f edge = new Vector3f(mesh.vertexPosition(ids.edgeEndVertex))
                    .sub(mesh.vertexPosition(ids.edgeStartVertex)).normalize();
            Vector3f normalA = mesh.faceNormal(ids.faceA);
            Vector3f normalB = mesh.faceNormal(ids.faceB);
            double dihedral = Math.atan2(new Vector3f(normalA).cross(normalB).dot(edge),
                    Math.max(-1f, Math.min(1f, normalA.dot(normalB))));
            Vector3f axisA = new Vector3f(field.faceX[field.faceIdToActive[ids.faceA]]);
            Vector3f rotated = new Vector3f(axisA).mul((float) Math.cos(dihedral))
                    .add(new Vector3f(edge).cross(axisA).mul((float) Math.sin(dihedral)))
                    .add(new Vector3f(edge).mul(edge.dot(axisA) * (float) (1.0 - Math.cos(dihedral))));
            int faceB = field.faceIdToActive[ids.faceB];
            double expected = Math.atan2(rotated.dot(field.faceY[faceB]),
                    rotated.dot(field.faceX[faceB]));
            double difference = Math.IEEEremainder(field.kappa[activeEdge] - expected,
                    2.0 * Math.PI);
            assertEquals(0.0, difference, KAPPA_TOLERANCE, "kappa of edge " + ids.edgeId);
            compared++;
        }
        assertTrue(compared > 0, "the torus has interior edges");
    }

    @Test
    void spokeAnglesAreRescaledCornerSumsFromTheReferenceHalfEdge() throws Exception {
        assertSpokeAngles(TORUS_SOURCE);
        assertSpokeAngles(DISK_SOURCE);
    }

    @Test
    void aWiredMetricGivesTheFieldABuiltOneGives() throws Exception {
        GeometryBundle bundle = GeometryBundle.ofMesh(carrier(TORUS_SOURCE));
        MapNodeContext measured = new MapNodeContext(new SurfaceMetricNode())
                .with(SurfaceMetricNode.GEOMETRY, bundle).eval();
        GeometryBundle surface = measured.output(SurfaceMetricNode.GEOMETRY_OUT,
                GeometryBundle.class);
        CrossField wired = new MapNodeContext(new NDirectionField())
                .with(NDirectionField.GEOMETRY, surface)
                .with(NDirectionField.METRIC,
                        measured.output(SurfaceMetricNode.METRIC, SurfaceMetric.class))
                .eval().output(NDirectionField.FIELD, CrossField.class);
        CrossField built = new MapNodeContext(new NDirectionField())
                .with(NDirectionField.GEOMETRY, surface)
                .eval().output(NDirectionField.FIELD, CrossField.class);
        assertArrayEquals(built.theta, wired.theta, "per-face angles");
        assertArrayEquals(built.kappa, wired.kappa, "transport angles");
        assertArrayEquals(built.singularityIndex4.data(), wired.singularityIndex4.data(),
                "singularities");
    }

    @Test
    void aMetricMeasuredOnAnotherMeshIsRefused() throws Exception {
        SurfaceMetric elsewhere = SurfaceMetric.of(carrier(TORUS_SOURCE));
        MapNodeContext context = new MapNodeContext(new NDirectionField())
                .with(NDirectionField.GEOMETRY, GeometryBundle.ofMesh(carrier(TORUS_SOURCE)))
                .with(NDirectionField.METRIC, elsewhere);
        assertThrows(IllegalArgumentException.class, context::eval);
    }

    /**
     * Walks every vertex fan counter-clockwise from the metric's reference half-edge and checks
     * each spoke's angle against corner angles measured on the positions, rescaled to a full turn
     * at interior vertices.
     *
     * @param source DSL statement producing the carrier mesh
     * @throws Exception when the primitive graph fails
     */
    private static void assertSpokeAngles(String source) throws Exception {
        HalfEdgeMesh mesh = carrier(source);
        SurfaceMetric metric = SurfaceMetric.of(mesh);
        NDirectionField solve = new NDirectionField();
        solve.curvatureBias = CURVATURE_BIAS;
        solve.build(mesh, metric);
        Vector3f toNext = new Vector3f();
        Vector3f toPrevious = new Vector3f();
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            int first = metric.vertexReferenceHalfEdge[vertex];
            double fullTurn = 0.0;
            int spoke = first;
            do {
                if (metric.halfEdgeNext[spoke] < 0) {
                    break;
                }
                fullTurn += measuredCorner(mesh, metric, spoke, toNext, toPrevious);
                spoke = metric.halfEdgeNext[metric.halfEdgeNext[spoke]] ^ 1;
            } while (spoke != first);
            double scale = metric.vertexIsBoundary[vertex] ? 1.0 : 2.0 * Math.PI / fullTurn;
            double expected = 0.0;
            spoke = first;
            do {
                int edgeHalfEdge = mesh.edgeHalfEdge(metric.sourceEdgeId[spoke >> 1]);
                int halfEdgeId = (spoke & 1) == 0 ? edgeHalfEdge : mesh.halfEdgeTwin(edgeHalfEdge);
                assertEquals(expected, solve.angleInFrame[halfEdgeId], SPOKE_TOLERANCE,
                        "spoke " + spoke + " of vertex " + vertex);
                if (metric.halfEdgeNext[spoke] < 0) {
                    break;
                }
                expected += scale * measuredCorner(mesh, metric, spoke, toNext, toPrevious);
                spoke = metric.halfEdgeNext[metric.halfEdgeNext[spoke]] ^ 1;
            } while (spoke != first);
        }
    }

    private static double measuredCorner(HalfEdgeMesh mesh, SurfaceMetric metric, int halfEdge,
            Vector3f toNext, Vector3f toPrevious) {
        int corner = metric.sourceVertexId[metric.halfEdgeTail[halfEdge]];
        int next = metric.sourceVertexId[metric.halfEdgeTail[metric.halfEdgeNext[halfEdge]]];
        int previous = metric.sourceVertexId[
                metric.halfEdgeTail[metric.halfEdgeNext[metric.halfEdgeNext[halfEdge]]]];
        mesh.vertexPosition(next, toNext).sub(mesh.vertexPosition(corner));
        mesh.vertexPosition(previous, toPrevious).sub(mesh.vertexPosition(corner));
        return toNext.angle(toPrevious);
    }

    private static HalfEdgeMesh carrier(String source) throws Exception {
        NodeGraphRuntime runtime = NodeGraphRuntime.fromSource(source);
        GeometryBundle bundle = (GeometryBundle) runtime.executeGraphResult(runtime.statements,
                "carrier", "geometry");
        return HalfEdgeMeshEngine.fromMeshTopology(bundle.mesh());
    }
}
