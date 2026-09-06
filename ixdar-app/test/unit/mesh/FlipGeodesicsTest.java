package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.paths.FlipGeodesics;
import ixdar.geometry.mesh.data.paths.GeodesicSeedPath;
import ixdar.geometry.mesh.data.paths.IntrinsicPathTracer;
import ixdar.geometry.mesh.data.paths.IntrinsicTriangulation;
import ixdar.geometry.mesh.data.paths.NearestVertex;
import ixdar.geometry.mesh.data.paths.TracedSurfacePath;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.nodes.primitives.IcosphereMeshNode;
import ixdar.geometry.mesh.nodes.primitives.TorusMeshNode;

/**
 * FlipOut on procedurally built surfaces: a torus tube loop reaches the meridian, a sphere path
 * reaches the great circle, repeat runs agree exactly, and a sliver strip still terminates.
 */
class FlipGeodesicsTest {

    /** Torus centre-line radius. */
    private static final float MAJOR_RADIUS = 1.0f;

    /** Torus tube radius; the minimal meridian is {@code 2 * pi * MINOR_RADIUS}. */
    private static final float MINOR_RADIUS = 0.35f;

    /** Faces the long way around the torus. */
    private static final int MAJOR_SEGMENTS = 64;

    /** Faces around the torus tube. */
    private static final int MINOR_SEGMENTS = 48;

    /** Icosphere radius; a great circle arc is {@code SPHERE_RADIUS * angle}. */
    private static final float SPHERE_RADIUS = 1.0f;

    /** Icosphere subdivision rounds. */
    private static final int SPHERE_SUBDIVISIONS = 4;

    /**
     * Residual bend a closed FlipOut loop keeps where it closes on a vertex.
     *
     * <p>
     * A tightened loop always retains at least one vertex, and on a polyhedral surface that vertex
     * carries an {@code O(h^2)} kink: measured 3.28e-3, 1.45e-3, 8.1e-4 and 3.6e-4 rad at 48, 72,
     * 96 and 144 tube segments. Only an open path reaches pi exactly, by keeping no vertex at all.
     */
    private static final double CLOSURE_KINK_LIMIT = 5e-3;

    @Test
    void torusLoopTightensToTheMinimalMeridian() {
        MeshTopology torus = torus();
        IntrinsicTriangulation intrinsic = IntrinsicTriangulation.over(torus);
        int[] seed = GeodesicSeedPath.throughVertices(intrinsic, wobblyTubeWaypoints(torus), true);
        FlipGeodesics flipper = new FlipGeodesics();
        double seedLength = 0.0;
        for (int halfEdge : seed) {
            seedLength += intrinsic.edgeLength[halfEdge >> 1];
        }

        flipper.shorten(intrinsic, seed, true, FlipGeodesics.UNBOUNDED_ITERATIONS);

        double analytic = 2.0 * Math.PI * MINOR_RADIUS;
        double tightened = flipper.pathLength();
        System.out.println("[torus] seed=" + seedLength + " tightened=" + tightened
                + " analytic=" + analytic + " flips=" + flipper.flipCount
                + " shortens=" + flipper.shortenCount
                + " minWedge=" + flipper.minimumWedgeAngle() + " pi=" + Math.PI);
        assertTrue(Math.abs(tightened - analytic) / analytic < 0.005,
                "tightened loop " + tightened + " is not within 0.5% of the analytic meridian "
                        + analytic);
        assertTrue(Math.PI - flipper.minimumWedgeAngle() < CLOSURE_KINK_LIMIT,
                "the tightest wedge is " + flipper.minimumWedgeAngle() + ", further than "
                        + CLOSURE_KINK_LIMIT + " from pi");
    }

    @Test
    void spherePathTightensToTheGreatCircle() {
        MeshTopology sphere = icosphere();
        IntrinsicTriangulation intrinsic = IntrinsicTriangulation.over(sphere);
        int startVertexId = NearestVertex.find(sphere, 0f, SPHERE_RADIUS, 0f);
        int endVertexId = NearestVertex.find(sphere, 0.83f, 0.21f, 0.51f);
        Vector3f startPosition = sphere.vertexPosition(startVertexId, new Vector3f());
        Vector3f endPosition = sphere.vertexPosition(endVertexId, new Vector3f());
        int[] seed = GeodesicSeedPath.throughVertices(intrinsic,
                new int[] { startVertexId, endVertexId }, false);
        FlipGeodesics flipper = new FlipGeodesics();

        flipper.shorten(intrinsic, seed, false, FlipGeodesics.UNBOUNDED_ITERATIONS);

        double cosine = startPosition.dot(endPosition)
                / (startPosition.length() * endPosition.length());
        double greatCircle = SPHERE_RADIUS * Math.acos(Math.max(-1.0, Math.min(1.0, cosine)));
        double tightened = flipper.pathLength();
        System.out.println("[sphere] tightened=" + tightened + " greatCircle=" + greatCircle
                + " flips=" + flipper.flipCount + " minWedge=" + flipper.minimumWedgeAngle());
        assertTrue(Math.abs(tightened - greatCircle) / greatCircle < 0.005,
                "tightened path " + tightened + " is not within 0.5% of the great-circle distance "
                        + greatCircle);
        double minimumWedge = flipper.minimumWedgeAngle();
        assertTrue(Double.isInfinite(minimumWedge) || Math.abs(minimumWedge - Math.PI) < 1e-6,
                "the tightest wedge on the open path is " + minimumWedge + ", not pi");
    }

    @Test
    void repeatRunsProduceAnIdenticalPolyline() {
        double[] first = tightenedTorusPolyline();
        double[] second = tightenedTorusPolyline();
        assertEquals(first.length, second.length, "the two runs traced a different point count");
        assertArrayEquals(first, second, 0.0,
                "the two runs traced different polyline coordinates");
    }

    @Test
    void tracedPolylineFollowsTheIntrinsicLoop() {
        MeshTopology torus = torus();
        IntrinsicTriangulation intrinsic = IntrinsicTriangulation.over(torus);
        IntrinsicPathTracer tracer = IntrinsicPathTracer.snapshotOf(intrinsic);
        int[] seed = GeodesicSeedPath.throughVertices(intrinsic, wobblyTubeWaypoints(torus), true);
        FlipGeodesics flipper = new FlipGeodesics();

        int[] tightened = flipper.shorten(intrinsic, seed, true,
                FlipGeodesics.UNBOUNDED_ITERATIONS);
        TracedSurfacePath traced = tracer.trace(intrinsic, tightened, true);

        System.out.println("[trace] points=" + traced.pointCount + " polyline="
                + traced.polylineLength() + " intrinsic=" + flipper.pathLength());
        assertTrue(traced.pointCount > tightened.length,
                "the trace crossed no original edges: " + traced.pointCount + " points for "
                        + tightened.length + " intrinsic edges");
        double relativeError = Math.abs(traced.polylineLength() - flipper.pathLength())
                / flipper.pathLength();
        assertTrue(relativeError < 1e-6,
                "the traced polyline is " + traced.polylineLength()
                        + " but the intrinsic loop is " + flipper.pathLength());
    }

    @Test
    void sliverStripStillTerminatesAndShortens() {
        MeshTopology strip = perturbedSliverStrip();
        IntrinsicTriangulation intrinsic = IntrinsicTriangulation.over(strip);
        int startVertexId = strip.vertexIdAt(0);
        int endVertexId = strip.vertexIdAt(strip.vertexCount() - 1);
        int[] seed = GeodesicSeedPath.throughVertices(intrinsic,
                new int[] { startVertexId, endVertexId }, false);
        double seedLength = 0.0;
        for (int halfEdge : seed) {
            seedLength += intrinsic.edgeLength[halfEdge >> 1];
        }
        FlipGeodesics flipper = new FlipGeodesics();

        int[] tightened = flipper.shorten(intrinsic, seed, false,
                FlipGeodesics.UNBOUNDED_ITERATIONS);

        System.out.println("[sliver] seed=" + seedLength + " tightened=" + flipper.pathLength()
                + " flips=" + flipper.flipCount);
        assertTrue(tightened.length > 0, "the sliver strip path vanished");
        assertTrue(flipper.pathLength() <= seedLength + 1e-9,
                "tightening lengthened the sliver strip path: " + flipper.pathLength() + " > "
                        + seedLength);
    }

    private static double[] tightenedTorusPolyline() {
        MeshTopology torus = torus();
        IntrinsicTriangulation intrinsic = IntrinsicTriangulation.over(torus);
        IntrinsicPathTracer tracer = IntrinsicPathTracer.snapshotOf(intrinsic);
        int[] seed = GeodesicSeedPath.throughVertices(intrinsic, wobblyTubeWaypoints(torus), true);
        FlipGeodesics flipper = new FlipGeodesics();
        int[] tightened = flipper.shorten(intrinsic, seed, true,
                FlipGeodesics.UNBOUNDED_ITERATIONS);
        TracedSurfacePath traced = tracer.trace(intrinsic, tightened, true);
        return traced.positions;
    }

    /**
     * Three points spread around the tube at different angles the long way round, so the seed
     * walk between them wobbles instead of following one meridian.
     */
    private static int[] wobblyTubeWaypoints(MeshTopology torus) {
        List<Integer> waypoints = new ArrayList<>();
        double[] tubeAngles = { 0.0, 2.0 * Math.PI / 3.0, 4.0 * Math.PI / 3.0 };
        double[] ringAngles = { 0.0, 0.35, -0.3 };
        for (int index = 0; index < tubeAngles.length; index++) {
            double distanceFromAxis = MAJOR_RADIUS + MINOR_RADIUS * Math.cos(tubeAngles[index]);
            float x = (float) (distanceFromAxis * Math.cos(ringAngles[index]));
            float y = (float) (MINOR_RADIUS * Math.sin(tubeAngles[index]));
            float z = (float) (distanceFromAxis * Math.sin(ringAngles[index]));
            waypoints.add(NearestVertex.find(torus, x, y, z));
        }
        int[] ids = new int[waypoints.size()];
        for (int index = 0; index < ids.length; index++) {
            ids[index] = waypoints.get(index);
        }
        return ids;
    }

    private static MeshTopology torus() {
        TorusMeshNode node = new TorusMeshNode();
        MapNodeContext ctx = new MapNodeContext(node);
        ctx.setInput("major_radius", MAJOR_RADIUS);
        ctx.setInput("minor_radius", MINOR_RADIUS);
        ctx.setInput("major_segments", MAJOR_SEGMENTS);
        ctx.setInput("minor_segments", MINOR_SEGMENTS);
        ctx.setInput("triangulate", true);
        node.evaluate(ctx);
        return ctx.getOutput("mesh", GeometryBundle.class).mesh();
    }

    private static MeshTopology icosphere() {
        IcosphereMeshNode node = new IcosphereMeshNode();
        MapNodeContext ctx = new MapNodeContext(node);
        ctx.setInput("radius", SPHERE_RADIUS);
        ctx.setInput("subdivisions", SPHERE_SUBDIVISIONS);
        node.evaluate(ctx);
        return ctx.getOutput("mesh", GeometryBundle.class).mesh();
    }

    /**
     * A long thin grid strip whose interior vertices are jittered enough to make near-degenerate
     * triangles, the robustness case the paper calls out for degenerate wedges.
     */
    private static MeshTopology perturbedSliverStrip() {
        HalfEdgeMesh mesh = new HalfEdgeMesh();
        int columns = 40;
        int rows = 3;
        long randomState = 0x5DEECE66DL;
        int[][] grid = new int[columns][rows];
        for (int column = 0; column < columns; column++) {
            for (int row = 0; row < rows; row++) {
                randomState = randomState * 6364136223846793005L + 1442695040888963407L;
                double jitter = ((randomState >>> 11) / (double) (1L << 53)) - 0.5;
                boolean interior = column > 0 && column < columns - 1 && row > 0 && row < rows - 1;
                float x = (float) (column + (interior ? 0.98 * jitter : 0.0));
                float z = (float) (0.02 * row + (interior ? 0.0015 * jitter : 0.0));
                grid[column][row] = mesh.addVertex(x, 0f, z);
            }
        }
        for (int column = 0; column < columns - 1; column++) {
            for (int row = 0; row < rows - 1; row++) {
                mesh.addFace(grid[column][row], grid[column + 1][row], grid[column + 1][row + 1]);
                mesh.addFace(grid[column][row], grid[column + 1][row + 1], grid[column][row + 1]);
            }
        }
        mesh.computeNormals();
        return mesh;
    }
}
