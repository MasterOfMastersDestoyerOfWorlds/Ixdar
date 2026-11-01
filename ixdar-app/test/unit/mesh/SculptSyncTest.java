package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.sculpt.AnchoredStroke;
import ixdar.geometry.mesh.data.sculpt.FaceAnchors;
import ixdar.geometry.mesh.data.sculpt.StrokeAnchors;
import ixdar.geometry.mesh.data.sculpt.StrokeSculpt;
import ixdar.nodegraph.api.MapNodeContext;
import ixdar.nodegraph.nodes.modifier.MergeByDistanceNode;
import ixdar.nodegraph.nodes.modifier.StrokeSculptNode;
import ixdar.nodegraph.nodes.primitives.QuadSphereMeshNode;

/**
 * ixdar-cli sculpt-sync's engine on a sphere base: strokes held on the base under stable ids, drawn
 * into every cell, and plain-SVG edits in any one cell folded back.
 */
class SculptSyncTest {

    private static final String ROOT = "<svg xmlns=\"http://www.w3.org/2000/svg\" "
            + "width=\"5120\" height=\"2560\" viewBox=\"0 0 5120 2560\" data-center=\"0 0 0\" "
            + "data-distance=\"3.906623\" data-fov=\"45\" data-projection=\"perspective\" "
            + "data-cell-width=\"1280\" data-cell-height=\"1280\" data-columns=\"4\" "
            + "data-views=\"1.570796 0;0 0;-1.570796 0;3.141593 0;1.570796 1.570796;"
            + "1.570796 -1.570796;0.785398 0.4;2.356194 0.4\">\n";

    private static final String CHEEK = "<g id=\"face\"><polyline data-kind=\"ridge\" data-depth=\"0.08\" "
            + "data-radius=\"0.1\" points=\"800,600 860,640 900,700\"/></g>\n";

    private static final String BROW = "<polyline data-kind=\"ridge\" data-depth=\"0.05\" data-radius=\"0.08\" "
            + "points=\"420,520 520,500 620,500\"/>\n";

    private static final String BAND = "<polyline data-kind=\"valley\" data-depth=\"-0.03\" data-radius=\"0.05\" "
            + "points=\"360,760 500,780 640,790 780,780 920,760\"/>\n";

    private static final String NOSE = "<g id=\"nose\"><polyline data-kind=\"ridge\" data-depth=\"0.06\" "
            + "data-radius=\"0.06\" points=\"640,560 640,620 640,680\"/></g>\n";

    private static final String PIT = "<g id=\"eyes\"><polygon data-depth=\"-0.3\" data-radius=\"0.1\" "
            + "data-profile=\"bowl\" points=\"760,560 880,560 880,680 760,680\"/></g>\n";

    private static final String ACROSS_PIT = "<polyline data-kind=\"ridge\" data-depth=\"0.02\" data-radius=\"0.05\" "
            + "points=\"740,620 820,620 900,620\"/>\n";

    private static final String CHEEK_ID = "face.1";

    private static final String SECOND_ID = "s1";

    private static final String THIRD_ID = "nose.1";

    private static final String PIT_ID = "eyes.1";

    private static final String STALE_FOLDER = "target/sculpt-sync-stale-sidecar";

    private static final String SVG_END = "</svg>";

    private static final String SIDECAR = "layer.strokes";

    private static final String LAYER_FILE = "layer.svg";

    private static final String PAIR_SEPARATOR = ",";

    private static final int FRONT = 0;

    private static final int RIGHT = 1;

    private static final int BACK = 2;

    private static final int THREE_QUARTER_RIGHT = 6;

    private static final float MOVE_PIXELS = 60f;

    private static final Pattern POINTS = Pattern.compile("points=\"([^\"]*)\"");

    private static final Pattern POINT_INDICES = Pattern.compile("data-pts=\"([^\"]*)\"");

    @TempDir
    Path folder;

    @Test
    void theFirstSyncHoldsEveryStrokeOnTheBaseAndDrawsItInEveryCellThatSeesIt() throws IOException {
        Path svg = layer(CHEEK + BAND);
        List<String> changes = sync(svg);
        assertTrue(changes.get(0).startsWith(CHEEK_ID + ": added")
                && changes.get(1).startsWith(SECOND_ID + ": added"), changes.toString());
        StrokeAnchors anchors = StrokeAnchors.beside(svg.toString());
        assertNotNull(anchors);
        assertEquals("face", anchors.stroke(CHEEK_ID).layer, "the <g id> is the stroke's layer");
        String drawn = read(svg);
        assertTrue(drawn.contains("<g id=\"face\">"), "the layer is drawn as a group");
        for (int cell : new int[] { FRONT, RIGHT }) {
            assertNotNull(span(CHEEK_ID, cell, drawn, false), "the cheek is drawn, solid, in cell " + cell);
        }
        assertNull(span(CHEEK_ID, BACK, drawn, false), "the back cell does not see the cheek");
        assertNotNull(span(SECOND_ID, RIGHT, drawn, true), "the band around the front runs behind the sphere seen "
                + "from the right, drawn dashed and data-hidden there");
        assertEquals(anchors.written, drawn, "the sidecar keeps the SVG it wrote, which the next sync diffs against");
    }

    @Test
    void aPointMovedInTheFrontCellAppearsMovedInTheRightCell() throws IOException {
        Path svg = layer(CHEEK);
        sync(svg);
        String before = read(svg);
        float[] rightBefore = point(span(CHEEK_ID, RIGHT, before, false), 1);
        Files.write(svg, movePoint(before, CHEEK_ID, FRONT, 1, MOVE_PIXELS, 0f).getBytes(StandardCharsets.UTF_8));
        List<String> changes = sync(svg);
        assertTrue(changes.contains(CHEEK_ID + ": moved 1 point(s)"), changes.toString());
        String after = read(svg);
        float[] frontAfter = point(span(CHEEK_ID, FRONT, after, false), 1);
        float[] frontBefore = point(span(CHEEK_ID, FRONT, before, false), 1);
        // It is drawn where the ridge now carries it, along the base's normal, a little past the drag.
        float dragged = frontAfter[0] - frontBefore[0];
        assertTrue(dragged > MOVE_PIXELS * 0.5f && dragged < MOVE_PIXELS * 2f, "the moved point is redrawn near "
                + "where it was dragged in the front cell: " + frontBefore[0] + " to " + frontAfter[0]);
        float[] rightAfter = point(span(CHEEK_ID, RIGHT, after, false), 1);
        assertTrue(Math.hypot(rightAfter[0] - rightBefore[0], rightAfter[1] - rightBefore[1]) > MOVE_PIXELS * 0.1f,
                "the same point moves in the right cell: " + rightBefore[0] + PAIR_SEPARATOR + rightBefore[1] + " to "
                        + rightAfter[0] + PAIR_SEPARATOR + rightAfter[1]);
    }

    @Test
    void aShapeWithNoIdIsAddedAndAMissingIdDeleted() throws IOException {
        Path svg = layer(CHEEK + BROW);
        sync(svg);
        String edited = read(svg).replaceAll("<polyline data-id=\"" + SECOND_ID + "\"[^>]*>\\n?", "")
                .replace(SVG_END, NOSE + SVG_END);
        Files.write(svg, edited.getBytes(StandardCharsets.UTF_8));
        List<String> changes = sync(svg);
        assertTrue(changes.stream().anyMatch(line -> line.startsWith(SECOND_ID + ": deleted")), changes.toString());
        assertTrue(changes.contains(THIRD_ID + ": added from cell 0"), changes.toString());
        StrokeAnchors anchors = StrokeAnchors.beside(svg.toString());
        assertNull(anchors.stroke(SECOND_ID), "the brow is gone");
        assertNotNull(anchors.stroke(THIRD_ID), "the nose is a stroke with the next id");
        assertNotNull(span(THIRD_ID, THREE_QUARTER_RIGHT, read(svg), false), "the new stroke is drawn in the other "
                + "cells that see it too");
    }

    @Test
    void aStrokeEditedInTwoCellsIsRefusedNamingBoth() throws IOException {
        Path svg = layer(CHEEK);
        sync(svg);
        String edited = movePoint(movePoint(read(svg), CHEEK_ID, FRONT, 1, MOVE_PIXELS, 0f), CHEEK_ID, RIGHT, 1, 0f,
                MOVE_PIXELS);
        Files.write(svg, edited.getBytes(StandardCharsets.UTF_8));
        String sidecar = read(folder.resolve(SIDECAR));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> sync(svg));
        assertTrue(refused.getMessage().contains(CHEEK_ID + " was edited in cells [0, 1]")
                && refused.getMessage().contains("undo it in all but one cell"), refused.getMessage());
        assertEquals(sidecar, read(folder.resolve(SIDECAR)), "a refused sync writes nothing");
    }

    @Test
    void anUnknownOrCopiedIdIsRefusedWithTheFix() throws IOException {
        Path svg = layer(CHEEK);
        sync(svg);
        String written = read(svg);
        String copied = span(CHEEK_ID, FRONT, written, false);
        Files.write(svg, written.replace(SVG_END, copied + "\n" + copied.replace("\"" + CHEEK_ID + "\"", "\"s9\"") + "\n" + SVG_END)
                .getBytes(StandardCharsets.UTF_8));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> sync(svg));
        assertTrue(refused.getMessage().contains("stroke " + CHEEK_ID + " is drawn 2 times in cell 0")
                && refused.getMessage().contains("remove data-id and data-pts from the copy"), refused.getMessage());
        assertTrue(refused.getMessage().contains("data-id=\"s9\" names no stroke"), refused.getMessage());
        Files.write(svg, written.replaceFirst("data-pts=\"0 1 2\"", "data-pts=\"0 1\"").getBytes(StandardCharsets.UTF_8));
        refused = assertThrows(IllegalArgumentException.class, () -> sync(svg));
        assertTrue(refused.getMessage().contains("data-pts lists 2"), refused.getMessage());
    }

    @Test
    void aStrokeKeepsItsBaseFaceWhenAnotherStrokeIsEdited() throws IOException {
        Path svg = layer(CHEEK + BROW);
        sync(svg);
        float[] cheekBefore = StrokeAnchors.beside(svg.toString()).stroke(CHEEK_ID).anchors.clone();
        Files.write(svg, movePoint(read(svg), SECOND_ID, FRONT, 0, -MOVE_PIXELS, 0f).getBytes(StandardCharsets.UTF_8));
        sync(svg);
        AnchoredStroke cheek = StrokeAnchors.beside(svg.toString()).stroke(CHEEK_ID);
        assertArrayEquals(cheekBefore, cheek.anchors, 1e-5f, "the cheek is held on the same base faces, whatever "
                + "the brow's edit did to the sculpt");
        for (int point = 0; point < cheek.pointCount(); point++) {
            assertTrue(cheek.anchors[point * FaceAnchors.FIELDS] >= 0, "every point names a base face");
        }
    }

    @Test
    void aStrokeSculptOutputIsRefusedAsInput() throws IOException {
        Path svg = layer(CHEEK);
        GeometryBundle once = sculpt(sphere(), svg.toString());
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> sculpt(once,
                svg.toString()));
        assertTrue(refused.getMessage().contains("save the sculpt as a new base"), refused.getMessage());
    }

    @Test
    void aShapeAddedOverAnEarlierPitIsHeldWhereItLandsOnTheBase() throws IOException {
        Path svg = layer(PIT);
        sync(svg);
        Files.write(svg, read(svg).replace(SVG_END, ACROSS_PIT + SVG_END).getBytes(StandardCharsets.UTF_8));
        List<String> changes = sync(svg);
        assertTrue(changes.contains(SECOND_ID + ": added from cell 0"), changes.toString());
        Path fresh = Files.createDirectories(folder.resolve("fresh")).resolve("alone.svg");
        Files.write(fresh, (ROOT + ACROSS_PIT + SVG_END + "\n").getBytes(StandardCharsets.UTF_8));
        sync(fresh);
        // Cast onto the pitted sculpt, the middle point lands on the pit's floor and is carried to
        // another place on the base; lifted onto the base it is where the line alone lands.
        assertArrayEquals(StrokeAnchors.beside(fresh.toString()).stroke(SECOND_ID).anchors,
                StrokeAnchors.beside(svg.toString()).stroke(SECOND_ID).anchors, 1e-5f,
                "a new shape is lifted onto the base, whatever the last sculpt did under it");
    }

    @Test
    void aDrawingRegeneratedWithoutIdsKeepsItsIdsAndSculptsAsTheCameraLiftDoes() throws IOException {
        String drawing = ROOT + PIT + CHEEK + SVG_END + "\n";
        Path svg = layer(PIT + CHEEK);
        Path unsynced = Files.createDirectories(folder.resolve("unsynced")).resolve(LAYER_FILE);
        Files.write(unsynced, drawing.getBytes(StandardCharsets.UTF_8));
        GeometryBundle base = sphere();
        MapNodeContext lifted = new MapNodeContext(new StrokeSculptNode()).with(StrokeSculptNode.GEOMETRY, base)
                .with(StrokeSculptNode.SVG, unsynced.toString()).eval();
        String report = lifted.output(StrokeSculptNode.REPORT, String.class);
        assertTrue(report.contains("stroke " + PIT_ID + " (fill") && report.contains("stroke " + CHEEK_ID + " (ridge"),
                "with no sidecar the report names strokes by the ids sync will give them: " + report);
        sync(svg);
        Files.write(svg, drawing.getBytes(StandardCharsets.UTF_8));
        List<String> changes = sync(svg);
        assertTrue(changes.contains(PIT_ID + ": redrawn, held on the base anew from cell 0")
                && changes.contains(CHEEK_ID + ": redrawn, held on the base anew from cell 0"), changes.toString());
        float[] camera = StrokeSculpt.packedPositions(lifted.output(StrokeSculptNode.GEOMETRY_OUT,
                GeometryBundle.class).mesh(), null);
        float[] anchored = StrokeSculpt.packedPositions(sculpt(base, svg.toString()).mesh(), null);
        assertEquals(camera.length, anchored.length, "the same refinement");
        float farthest = 0f;
        for (int index = 0; index < camera.length; index++) {
            farthest = Math.max(farthest, Math.abs(camera[index] - anchored[index]));
        }
        assertTrue(farthest < 1e-3f, "the pit held in the sidecar is the pit lifted through the camera, its walls "
                + "where they were: farthest vertex apart " + farthest);
    }

    @Test
    void aSidecarIsReadOnlyBesideTheSvgFileNotFromABuildCopy() throws IOException, URISyntaxException {
        Path svg = Path.of(STALE_FOLDER, LAYER_FILE);
        Path buildCopy = Path.of(SculptSyncTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .resolve(STALE_FOLDER).resolve(SIDECAR);
        try {
            Files.createDirectories(svg.getParent());
            Files.write(svg, (ROOT + CHEEK + SVG_END + "\n").getBytes(StandardCharsets.UTF_8));
            Files.createDirectories(buildCopy.getParent());
            Files.write(buildCopy, "base 1 1\nwritten\n".getBytes(StandardCharsets.UTF_8));
            assertNotNull(SculptSyncTest.class.getClassLoader().getResource(STALE_FOLDER + "/" + SIDECAR),
                    "the stale sidecar is on the classpath, as a build's copy is");
            assertNull(StrokeAnchors.beside(svg.toString()), "the SVG's own folder holds no sidecar, so it has none");
            assertTrue(sculpt(sphere(), svg.toString()).mesh().vertexCount() > 0, "the layer runs from its SVG, not "
                    + "refused for the stale copy's base");
        } finally {
            Files.deleteIfExists(svg);
            Files.deleteIfExists(svg.getParent());
            Files.deleteIfExists(buildCopy);
            Files.deleteIfExists(buildCopy.getParent());
        }
    }

    private List<String> sync(Path svg) throws IOException {
        GeometryBundle base = sphere();
        return StrokeAnchors.sync(svg.toString(), base, () -> sculpt(base, svg.toString()));
    }

    private Path layer(String shapes) throws IOException {
        Path file = folder.resolve(LAYER_FILE);
        Files.write(file, (ROOT + shapes + SVG_END + "\n").getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /**
     * The first span of a stroke drawn in a cell, solid or hidden as asked, or null.
     */
    private static String span(String id, int cell, String svg, boolean hidden) {
        Matcher tag = Pattern.compile("<poly(?:line|gon) data-id=\"" + id + "\" data-cell=\"" + cell + "\"[^>]*>")
                .matcher(svg);
        while (tag.find()) {
            if (tag.group().contains("data-hidden") == hidden) {
                return tag.group();
            }
        }
        return null;
    }

    /**
     * A span's data-pts indices and its points, one entry each.
     */
    private static String[][] indexedPoints(String span) {
        Matcher indices = POINT_INDICES.matcher(span);
        Matcher points = POINTS.matcher(span);
        assertTrue(indices.find() && points.find(), span);
        return new String[][] { indices.group(1).split(" "), points.group(1).split(" ") };
    }

    /**
     * The grid pixel of the point a span lists at a stroke index.
     */
    private static float[] point(String span, int index) {
        String[][] indexed = indexedPoints(span);
        for (int at = 0; at < indexed[0].length; at++) {
            if (Integer.parseInt(indexed[0][at]) == index) {
                String[] xy = indexed[1][at].split(PAIR_SEPARATOR);
                return new float[] { Float.parseFloat(xy[0]), Float.parseFloat(xy[1]) };
            }
        }
        throw new AssertionError("no point " + index + " in " + span);
    }

    /**
     * The SVG with one point of a stroke's solid span in a cell dragged by a pixel offset.
     */
    private static String movePoint(String svg, String id, int cell, int index, float dx, float dy) {
        String span = span(id, cell, svg, false);
        String[][] indexed = indexedPoints(span);
        List<String> moved = new ArrayList<>();
        for (int at = 0; at < indexed[1].length; at++) {
            String[] xy = indexed[1][at].split(PAIR_SEPARATOR);
            boolean target = Integer.parseInt(indexed[0][at]) == index;
            moved.add(String.format(Locale.ROOT, "%.1f,%.1f", Float.parseFloat(xy[0]) + (target ? dx : 0f),
                    Float.parseFloat(xy[1]) + (target ? dy : 0f)));
        }
        return svg.replace(span, span.replace(String.join(" ", indexed[1]), String.join(" ", moved)));
    }

    private static GeometryBundle sphere() {
        MapNodeContext ball = new MapNodeContext(new QuadSphereMeshNode())
                .with(QuadSphereMeshNode.SIZE, 2f).with(QuadSphereMeshNode.RESOLUTION, 48).eval();
        return new MapNodeContext(new MergeByDistanceNode())
                .with(MergeByDistanceNode.GEOMETRY, ball.output(QuadSphereMeshNode.MESH, GeometryBundle.class))
                .with(MergeByDistanceNode.DISTANCE, 1e-4f).eval()
                .output(MergeByDistanceNode.GEOMETRY_OUT, GeometryBundle.class);
    }

    private static GeometryBundle sculpt(GeometryBundle input, String svgPath) {
        return new MapNodeContext(new StrokeSculptNode()).with(StrokeSculptNode.GEOMETRY, input)
                .with(StrokeSculptNode.SVG, svgPath).eval().output(StrokeSculptNode.GEOMETRY_OUT, GeometryBundle.class);
    }
}
