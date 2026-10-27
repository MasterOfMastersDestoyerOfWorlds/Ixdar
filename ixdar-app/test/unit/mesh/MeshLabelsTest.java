package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.bundle.RingBundle;
import ixdar.geometry.mesh.data.labels.MeshLabels;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.rings.RingRegions;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.Palettes;
import ixdar.nodegraph.api.MapNodeContext;
import ixdar.nodegraph.nodes.data.AddLabelNode;
import ixdar.nodegraph.syntax.PythonParser;
import ixdar.tools.ring.RingDslWriter;

/**
 * Part labels on {@link RingRegionsTest#cappedCylinder}: a label kept as a point names the same
 * faces whatever order the rings come in and whatever ring is added elsewhere, and its saved
 * {@code add_label} statement reads back to the same labels.
 */
class MeshLabelsTest {

    private static final String LOWER = "lower";

    private static final String TOP = "top";

    private static final float[] LOWER_POINT = { 1f, 0f, 1.5f };

    private static final float[] TOP_POINT = { 0f, 0f, 4f };

    private static final int[] RING_ROWS = { 2, 4, 6 };

    private static final int ADDED_RING_ROW = 7;

    private static final float LOWER_BAND_FLOOR = 1f;

    private static final float LOWER_BAND_CEILING = 2f;

    private static final float ADDED_RING_HEIGHT = 3.5f;

    private static final float HALF = 0.5f;

    private static final float ROW_HEIGHT = 0.5f;

    private static final String RINGS = "carrier = load_mesh(path=\"x.off\")\n"
            + "ring_00 = spline_ring(geometry=carrier.geometry, points=\"0.000000,0.000000,"
            + "0.000000\", label=\"ring_00\")\n"
            + "label_old = add_label(geometry=ring_00.geometry, label=\"old\", points=\"9.000000,"
            + "9.000000,9.000000\")\n"
            + "ring_01 = spline_ring(geometry=label_old.geometry, points=\"0.000000,0.000000,"
            + "0.000000\", label=\"ring_01\")\n";

    @Test
    void aPointLabelNamesTheSameFacesAfterTheRingsAreReorderedOrAnotherIsAdded() {
        MeshTopology cylinder = RingRegionsTest.cappedCylinder();
        MeshLabels labels = new MeshLabels();
        labels.add(LOWER, LOWER_POINT);
        labels.add(TOP, TOP_POINT);

        int[] inOrder = labelByFace(labels, regions(cylinder, RING_ROWS));
        int[] reordered = labelByFace(labels, regions(cylinder, new int[] { 6, 2, 4 }));
        int[] added = labelByFace(labels, regions(cylinder, new int[] { 2, ADDED_RING_ROW, 4, 6 }));

        assertArrayEquals(inOrder, reordered, "ring order must not move a label");
        int lowerFaces = 0;
        int topFaces = 0;
        Vector3f centroid = new Vector3f();
        Vector3f corner = new Vector3f();
        for (int activeFace = 0; activeFace < cylinder.faceCount(); activeFace++) {
            int faceId = cylinder.faceIdAt(activeFace);
            centroid.zero();
            for (int slot = 0; slot < cylinder.faceVertexCount(faceId); slot++) {
                cylinder.vertexPosition(cylinder.faceVertexAt(faceId, slot), corner);
                centroid.add(corner);
            }
            centroid.div(cylinder.faceVertexCount(faceId));
            boolean lowerBand = centroid.z > LOWER_BAND_FLOOR && centroid.z < LOWER_BAND_CEILING;
            assertEquals(lowerBand, inOrder[activeFace] == 0, "face " + activeFace);
            assertEquals(inOrder[activeFace] == 0, added[activeFace] == 0,
                    "a ring added elsewhere must leave the lower band labelled as it was");
            lowerFaces += lowerBand ? 1 : 0;
            topFaces += added[activeFace] == 1 ? 1 : 0;
            if (centroid.z < ADDED_RING_HEIGHT && centroid.z > LOWER_BAND_CEILING) {
                assertEquals(MeshLabels.UNLABELLED, added[activeFace],
                        "the band the added ring split off the top holds no label point");
            }
        }
        assertEquals(2 * RingRegionsTest.SEGMENTS_AROUND * (RING_ROWS[1] - RING_ROWS[0]),
                lowerFaces);
        assertEquals(2 * RingRegionsTest.SEGMENTS_AROUND * (RingRegionsTest.SIDE_ROWS
                - ADDED_RING_ROW) + RingRegionsTest.SEGMENTS_AROUND, topFaces);
    }

    @Test
    void savedLabelStatementsReadBackToTheSameLabelsAndResaveUnchanged() {
        MeshLabels labels = new MeshLabels();
        labels.add(LOWER, LOWER_POINT);
        labels.add(TOP, TOP_POINT);
        labels.add(LOWER, TOP_POINT);

        String saved = RingDslWriter.withLabels(RINGS, labels);

        assertTrue(saved.contains("ring_01 = spline_ring(geometry=ring_00.geometry"),
                "a ring read the dropped label's geometry: " + saved);
        assertTrue(saved.endsWith("label_lower = add_label(geometry=ring_01.geometry, "
                + "label=\"lower\", points=\"1.000000,0.000000,1.500000; 0.000000,0.000000,"
                + "4.000000\")\nlabel_top = add_label(geometry=label_lower.geometry, "
                + "label=\"top\", points=\"0.000000,0.000000,4.000000\")\n"), saved);
        assertEquals(saved, RingDslWriter.withLabels(saved, labels), "a resave moved the block");

        GeometryBundle bundle = GeometryBundle.ofMesh(RingRegionsTest.cappedCylinder());
        for (PythonParser.ParsedNode statement : PythonParser.parse(saved)) {
            if (!AddLabelNode.NODE_ID.equals(statement.type)) {
                continue;
            }
            bundle = new MapNodeContext(new AddLabelNode())
                    .with(AddLabelNode.GEOMETRY, bundle)
                    .with(AddLabelNode.LABEL, statement.arguments.get(AddLabelNode.LABEL.name))
                    .with(AddLabelNode.POINTS, statement.arguments.get(AddLabelNode.POINTS.name))
                    .eval().output(AddLabelNode.GEOMETRY_OUT, GeometryBundle.class);
        }
        MeshLabels reloaded = MeshLabels.inBundle(bundle);
        assertEquals(labels.names, reloaded.names);
        for (int label = 0; label < labels.names.size(); label++) {
            assertArrayEquals(labels.pointsByLabel.get(label), reloaded.pointsByLabel.get(label));
        }
        assertEquals(List.of(), MeshLabels.inBundle(GeometryBundle.ofMesh(
                RingRegionsTest.cappedCylinder())).names);
    }

    /** Regions of the cylinder cut by one ring around each listed vertex row, in that order. */
    private static RingRegions regions(MeshTopology mesh, int[] rows) {
        String[] labels = new String[rows.length];
        boolean[][] marks = new boolean[rows.length][RingBundle.edgeIdCeiling(mesh)];
        int around = RingRegionsTest.SEGMENTS_AROUND;
        for (int ring = 0; ring < rows.length; ring++) {
            labels[ring] = "ring_row_" + rows[ring];
            for (int segment = 0; segment < around; segment++) {
                marks[ring][mesh.edgeBetween(mesh.vertexIdAt(rows[ring] * around + segment),
                        mesh.vertexIdAt(rows[ring] * around + (segment + 1) % around))] = true;
            }
        }
        return new RingRegions(mesh, labels, marks).build();
    }

    /**
     * Eight bands named in order up the cylinder, more parts than the three-entry palette holds:
     * colouring by part adjacency gives touching bands different entries and never runs past the
     * palette into a darker shade, as handing out entries in label order did.
     */
    @Test
    void touchingPartsTakeDifferentPaletteEntriesAndNoneRunsPastThePalette() {
        MeshTopology cylinder = RingRegionsTest.cappedCylinder();
        int[] rows = new int[RingRegionsTest.SIDE_ROWS - 1];
        for (int ring = 0; ring < rows.length; ring++) {
            rows[ring] = ring + 1;
        }
        RingRegions regions = regions(cylinder, rows);
        MeshLabels labels = new MeshLabels();
        for (int band = 0; band < RingRegionsTest.SIDE_ROWS; band++) {
            labels.add("band_" + band, new float[] { 1f, 0f, (band + HALF) * ROW_HEIGHT });
        }
        Color[] three = Arrays.copyOf(Palettes.DARK.categories, 3);

        labels.resolve(regions, three);

        int[] colourByLabel = labels.colouring.colourByNode;
        assertEquals(RingRegionsTest.SIDE_ROWS, colourByLabel.length);
        for (int band = 0; band < RingRegionsTest.SIDE_ROWS; band++) {
            assertTrue(colourByLabel[band] >= 0 && colourByLabel[band] < three.length,
                    "band " + band + " took entry " + colourByLabel[band] + " past the palette");
            int[] expected = band == 0 ? new int[] { 1 }
                    : band == RingRegionsTest.SIDE_ROWS - 1 ? new int[] { band - 1 }
                            : new int[] { band - 1, band + 1 };
            assertArrayEquals(expected, labels.neighboursByLabel[band], "band " + band);
            for (int neighbour : labels.neighboursByLabel[band]) {
                assertTrue(colourByLabel[band] != colourByLabel[neighbour],
                        "touching bands " + band + " and " + neighbour + " share an entry");
            }
        }
    }

    /** The label of each face's region, by dense face index. */
    private static int[] labelByFace(MeshLabels labels, RingRegions regions) {
        labels.resolve(regions, Palettes.DARK.categories);
        int[] labelOf = labels.labelByRegion;
        int[] byFace = new int[regions.regionByActiveFace.length];
        for (int activeFace = 0; activeFace < byFace.length; activeFace++) {
            byFace[activeFace] = labelOf[regions.regionByActiveFace[activeFace]];
        }
        return byFace;
    }
}
