package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.paths.AuthoredSplineRing;
import ixdar.geometry.mesh.data.paths.NearestVertex;
import ixdar.geometry.mesh.data.paths.SurfaceGeodesics;
import ixdar.geometry.mesh.data.paths.SurfaceMetric;
import ixdar.geometry.mesh.data.paths.SurfaceSpline;
import ixdar.geometry.mesh.data.paths.SurfaceSplineTracer;
import ixdar.geometry.mesh.data.paths.SurfaceWaypoints;
import ixdar.geometry.mesh.data.representation.ArrayMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.graphics.render.model.LineSet;
import ixdar.graphics.render.model.SurfaceFaceLocator;
import ixdar.scenes.ring.RingTool;

/**
 * The user's spline rings on fertility draw on the surface: every segment carries a face normal,
 * so the line shader drops the far half, and every endpoint sits on the surface it ties depth with.
 */
class RingLineNormalsTest {

    private static final String FERTILITY = "test/resources/quadlayout/figure_8/fertility_in_tri.off";

    private static final String RINGS = "src/main/resources/dsl/fixtures/fertility_rings.dsl";

    private static final Pattern SPLINE_RING = Pattern.compile(
            "(ring_\\d+) = spline_ring\\(.*?points=\"([^\"]*)\", normal=\"([^\"]*)\"");

    private static final int NORMAL_OFFSET = 3;

    private static final int EXPECTED_SPLINE_RINGS = 13;

    @Test
    void everyRingSegmentCarriesAFaceNormalAndLiesOnTheSurface() throws Exception {
        ArrayMesh loaded = MeshLoader.load(FERTILITY);
        MeshTopology mesh = HalfEdgeMeshEngine.buildFromIndexedMesh(loaded.copyPositions(),
                loaded.copyFaceIndices());
        SurfaceGeodesics geodesics = SurfaceGeodesics.over(SurfaceMetric.of(mesh));
        SurfaceFaceLocator locator = new SurfaceFaceLocator(mesh);
        Matcher statement = SPLINE_RING.matcher(Files.readString(Path.of(RINGS)));
        List<String> problems = new ArrayList<>();
        int rings = 0;
        NearestVertex grid = NearestVertex.over(mesh);
        while (statement.find()) {
            float[] points = SurfaceWaypoints.parse(statement.group(2));
            int[] anchors = SurfaceWaypoints.snap(grid, points,
                    points.length / SurfaceSpline.COORDINATES_PER_POINT);
            AuthoredSplineRing ring = new AuthoredSplineRing(geodesics);
            assertTrue(ring.trace(anchors, anchors.length,
                    SurfaceWaypoints.parse(statement.group(NORMAL_OFFSET)),
                    SurfaceSplineTracer.DEFAULT_MAXIMUM_DEPTH), statement.group(1));
            LineSet lines = RingTool.splineLines(mesh, SurfaceSpline.of(ring.tracer));
            int unknownSurface = 0;
            int offSurface = 0;
            float[] endpoint = new float[2 * SurfaceSpline.COORDINATES_PER_POINT];
            for (int vertex = 0; vertex < lines.vertexCount(); vertex++) {
                int base = vertex * LineSet.FLOATS_PER_VERTEX;
                if (lines.vertices[base + NORMAL_OFFSET] == 0f
                        && lines.vertices[base + NORMAL_OFFSET + 1] == 0f
                        && lines.vertices[base + NORMAL_OFFSET + 2] == 0f) {
                    unknownSurface++;
                }
                // A zero-length segment at the endpoint finds a face only if the point is on one.
                for (int coordinate = 0; coordinate < endpoint.length; coordinate++) {
                    endpoint[coordinate] = lines.vertices[base
                            + coordinate % SurfaceSpline.COORDINATES_PER_POINT];
                }
                LineSet located = new LineSet(1);
                locator.appendSegments(endpoint, located);
                if (located.vertices[NORMAL_OFFSET] == 0f
                        && located.vertices[NORMAL_OFFSET + 1] == 0f
                        && located.vertices[NORMAL_OFFSET + 2] == 0f) {
                    offSurface++;
                }
            }
            if (unknownSurface > 0 || offSurface > 0) {
                problems.add(statement.group(1) + ": " + unknownSurface
                        + " endpoints without a face normal, " + offSurface + " off the surface");
            }
            rings++;
        }
        assertEquals(EXPECTED_SPLINE_RINGS, rings);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }
}
