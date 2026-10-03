package benchmark;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.csg.MeshBooleanBackend;
import ixdar.geometry.mesh.csg.QuadTriangulation;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.RingRegionExtraction;
import ixdar.geometry.mesh.data.RingRegions;
import ixdar.geometry.mesh.data.paths.SurfaceSpline;
import ixdar.geometry.mesh.graph.NodeGraphRuntime;
import ixdar.geometry.mesh.nodes.api.MapNodeContext;
import ixdar.geometry.mesh.nodes.geometry.MeshBooleanNode;
import ixdar.parsing.python.PythonParser;
import ixdar.platform.Platforms;

/**
 * Extracts the largest limb of a ring graph, fertility's curated rings by default, and its
 * smallest neighbour, then unions them. Run it explicitly:
 *
 * <pre>
 * mvn test -Dtest=RingRegionExtractionBenchmark -Dbenchmark.ringGraph=path/to/rings.dsl
 * </pre>
 */
public final class RingRegionExtractionBenchmark {

    private static final String RING_GRAPH_PROPERTY = "benchmark.ringGraph";

    private static final String DEFAULT_RING_GRAPH =
            "src/main/resources/dsl/fixtures/fertility_rings.dsl";

    private static final double NEAR_DUPLICATE_CENTROID_SHARE_OF_RADIUS = 0.5;

    private static final String GEOMETRY_PORT = "geometry";

    private static final String REGION_TERM = "region ";

    private static final int XYZ = 3;

    /**
     * Cuts out the largest limb and its neighbour, checks each is a closed solid the kernel takes,
     * unions them and checks the source surface and its regions did not change.
     *
     * @throws Exception when the graph fails to run
     */
    @Test
    public void extractTwoRegionsAndUnionThem() throws Exception {
        String graphPath = System.getProperty(RING_GRAPH_PROPERTY, DEFAULT_RING_GRAPH);
        NodeGraphRuntime graph = NodeGraphRuntime.fromSource(
                new String(Files.readAllBytes(Path.of(graphPath)), StandardCharsets.UTF_8));
        List<PythonParser.ParsedNode> statements = graph.statements;
        GeometryBundle bundle = (GeometryBundle) graph.executeGraphResult(statements,
                statements.get(statements.size() - 1).id, GEOMETRY_PORT);
        MeshTopology surface = bundle.mesh();
        float[] positionsBefore = positionsOf(surface);
        int[] cornersBefore = cornersOf(surface);
        System.out.printf(Locale.ROOT, "[extract] %s: %d vertices, %d faces%n", graphPath,
                surface.vertexCount(), surface.faceCount());

        RingRegions regions = RingRegions.ofBundle(bundle, "");
        int[] regionsBefore = regions.regionByActiveFace.clone();
        for (String line : regions.reportLines()) {
            System.out.println("[extract] " + line);
        }
        SurfaceSpline[] splines = new SurfaceSpline[regions.ringLabels.length];
        for (int ring = 0; ring < splines.length; ring++) {
            splines[ring] = SurfaceSpline.inBundle(bundle, regions.ringLabels[ring]);
        }
        for (int ring = 0; ring < splines.length; ring++) {
            for (int other = ring + 1; other < splines.length; other++) {
                SurfaceSpline first = splines[ring];
                SurfaceSpline second = splines[other];
                double gap = new Vector3f(first.centroidX, first.centroidY, first.centroidZ)
                        .distance(second.centroidX, second.centroidY, second.centroidZ);
                if (gap < NEAR_DUPLICATE_CENTROID_SHARE_OF_RADIUS * 0.5
                        * (first.meanRadius + second.meanRadius)) {
                    System.out.printf(Locale.ROOT, "[extract] near-duplicate rings %s and %s: "
                            + "centroids %.3f apart, radii %.3f and %.3f%n",
                            regions.ringLabels[ring], regions.ringLabels[other], gap,
                            first.meanRadius, second.meanRadius);
                }
            }
        }

        // Fertility's limbs are handles, so a limb is a tube two rings bound; the body, the largest
        // region, is left out, and the neighbour is the smallest region sharing a ring with it.
        int body = 0;
        for (int region = 0; region < regions.regionCount; region++) {
            body = regions.regionArea[region] > regions.regionArea[body] ? region : body;
        }
        int limb = -1;
        for (int region = 0; region < regions.regionCount; region++) {
            if (region != body && regions.boundingRingsByRegion[region].length == 2
                    && (limb < 0 || regions.regionArea[region] > regions.regionArea[limb])) {
                limb = region;
            }
        }
        assertTrue(limb >= 0, "no region is a tube between two rings");
        int neighbour = -1;
        for (int region = 0; region < regions.regionCount; region++) {
            boolean adjacent = false;
            for (int ring : regions.boundingRingsByRegion[region]) {
                for (int limbRing : regions.boundingRingsByRegion[limb]) {
                    adjacent |= ring == limbRing && region != limb;
                }
            }
            if (adjacent && (neighbour < 0
                    || regions.regionArea[region] < regions.regionArea[neighbour])) {
                neighbour = region;
            }
        }
        assertTrue(neighbour >= 0, "the limb's rings bound no other region");

        MeshBooleanBackend kernel = Platforms.get().meshBooleanBackend();
        GeometryBundle[] pieces = new GeometryBundle[2];
        double[] volumes = new double[2];
        int[] picked = { limb, neighbour };
        for (int piece = 0; piece < 2; piece++) {
            long start = System.nanoTime();
            RingRegionExtraction extraction = new RingRegionExtraction(regions, splines,
                    regions.select(REGION_TERM + picked[piece]));
            extraction.solidCheck = kernel;
            extraction.build();
            System.out.printf(Locale.ROOT, "[extract] region %d (bounded by %s) in %.0f ms%n",
                    picked[piece], regions.boundingRingText(picked[piece]),
                    (System.nanoTime() - start) / 1e6);
            for (String line : extraction.reportLines()) {
                System.out.println("[extract]   " + line);
            }
            assertTrue(extraction.closed, String.join("\n", extraction.reportLines()));
            assertEquals(MeshBooleanBackend.ACCEPTED_SOLID, extraction.solidStatus);
            assertTrue(extraction.boundaryToSplineDistance
                    < extraction.snappedLoopToSplineDistance);
            pieces[piece] = GeometryBundle.ofMesh(extraction.closedMesh);
            volumes[piece] = signedVolume(extraction.closedMesh);
        }

        GeometryBundle union = new MapNodeContext(new MeshBooleanNode())
                .with(MeshBooleanNode.MESH_A, pieces[0])
                .with(MeshBooleanNode.MESH_B, pieces[1])
                .with(MeshBooleanNode.OPERATION, MeshBooleanNode.UNION)
                .eval().output(MeshBooleanNode.GEOMETRY, GeometryBundle.class);
        double unionVolume = signedVolume(union.mesh());
        System.out.printf(Locale.ROOT, "[extract] union of regions %d and %d: %d faces, volume "
                + "%.1f from %.1f and %.1f, manifold %s%n", limb, neighbour,
                union.mesh().faceCount(), unionVolume, volumes[0], volumes[1],
                kernel.solidStatus(new QuadTriangulation(union.mesh()).build()));
        assertTrue(unionVolume > Math.max(volumes[0], volumes[1]));

        assertArrayEquals(positionsBefore, positionsOf(surface), "the source surface moved");
        assertArrayEquals(cornersBefore, cornersOf(surface), "the source's faces changed");
        assertArrayEquals(regionsBefore, regions.regionByActiveFace, "the regions changed");
    }

    private static double signedVolume(MeshTopology mesh) {
        double volume = 0;
        Vector3f first = new Vector3f();
        Vector3f second = new Vector3f();
        Vector3f third = new Vector3f();
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            mesh.vertexPosition(mesh.faceVertexAt(faceId, 0), first);
            for (int corner = 2; corner < mesh.faceVertexCount(faceId); corner++) {
                mesh.vertexPosition(mesh.faceVertexAt(faceId, corner - 1), second);
                mesh.vertexPosition(mesh.faceVertexAt(faceId, corner), third);
                volume += first.dot(new Vector3f(second).cross(third)) / 6.0;
            }
        }
        return volume;
    }

    private static float[] positionsOf(MeshTopology mesh) {
        float[] xyz = new float[XYZ * mesh.vertexCount()];
        Vector3f position = new Vector3f();
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            mesh.vertexPosition(mesh.vertexIdAt(activeVertex), position);
            xyz[XYZ * activeVertex] = position.x;
            xyz[XYZ * activeVertex + 1] = position.y;
            xyz[XYZ * activeVertex + 2] = position.z;
        }
        return xyz;
    }

    private static int[] cornersOf(MeshTopology mesh) {
        int[] corners = new int[XYZ * mesh.faceCount()];
        for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
            for (int corner = 0; corner < XYZ; corner++) {
                corners[XYZ * activeFace + corner] = mesh.faceVertexAt(mesh.faceIdAt(activeFace),
                        corner);
            }
        }
        return corners;
    }
}
