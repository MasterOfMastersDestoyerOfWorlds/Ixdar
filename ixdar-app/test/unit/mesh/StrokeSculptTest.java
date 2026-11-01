package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.ops.MeshVertexOffset;
import ixdar.geometry.mesh.data.paths.query.NearestVertex;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.sculpt.StrokeSculpt;
import ixdar.geometry.mesh.data.sculpt.StrokeSketch;
import ixdar.graphics.cameras.Camera3D;
import ixdar.nodegraph.api.MapNodeContext;
import ixdar.nodegraph.field.Vector3Field;
import ixdar.nodegraph.nodes.modifier.MergeByDistanceNode;
import ixdar.nodegraph.nodes.modifier.StrokeSculptNode;
import ixdar.nodegraph.nodes.primitives.QuadSphereMeshNode;

/**
 * The stroke_sculpt node lifts an SVG through the camera its root records, with no viewer, moves
 * what each stroke's own world-unit depth and radius say, and reports what every stroke did.
 */
class StrokeSculptTest {

    private static final String UNIT_SPHERE_CAMERA_ROOT = "<svg xmlns=\"http://www.w3.org/2000/svg\" "
            + "width=\"5120\" height=\"2560\" viewBox=\"0 0 5120 2560\" data-center=\"0 0 0\" "
            + "data-distance=\"3.906623\" data-fov=\"45\" data-projection=\"perspective\" "
            + "data-cell-width=\"1280\" data-cell-height=\"1280\" data-columns=\"4\" "
            + "data-views=\"1.570796 0;0 0;-1.570796 0;3.141593 0;1.570796 1.570796;"
            + "1.570796 -1.570796;0.785398 0.4;2.356194 0.4\">\n";

    private static final String THREE_TEETH_RIGHT_OF_MIDLINE = "<polyline data-kind=\"ridge\" "
            + "data-depth=\"DEPTH\" data-radius=\"0.03\" points=\"658,800 658,818 658,836\"/>\n"
            + "<polyline data-kind=\"ridge\" data-depth=\"DEPTH\" data-radius=\"0.03\" "
            + "points=\"694,798 694,816 694,834\"/>\n<polyline data-kind=\"ridge\" "
            + "data-depth=\"DEPTH\" data-radius=\"0.03\" points=\"730,794 730,812 730,830\"/>\n";

    private static final String ONE_SIDED_CHEEK_RIDGE = "<polyline data-kind=\"ridge\" data-depth=\"0.12\" "
            + "data-radius=\"0.12\" points=\"800,600 860,640 900,700\"/>\n";

    private static final String WIDER_RIGHT_OUTLINE = "<polyline data-kind=\"silhouette\" data-radius=\"0.25\" "
            + "points=\"1040,450 1090,560 1100,640 1090,720 1040,830\"/>\n";

    private static final String NARROWER_RIGHT_OUTLINE = "<polyline data-kind=\"silhouette\" data-radius=\"0.25\" "
            + "points=\"1040,450 1070,560 1075,640 1070,720 1040,830\"/>\n";

    private static final String WIDE_FRONT_FILL = "<polygon data-depth=\"-0.2\" data-radius=\"0.55\" "
            + "points=\"660,500 940,500 940,780 660,780\"/>\n";

    private static final String RIDGE_ON_THE_OUTLINE = "<polyline data-kind=\"ridge\" data-depth=\"0.1\" "
            + "data-radius=\"0.05\" points=\"1048,600 1049,640 1048,680\"/>\n";

    private static final String MIDLINE_RIDGE = "<polyline data-kind=\"ridge\" data-depth=\"0.08\" "
            + "data-radius=\"0.08\" MIRROR points=\"640,420 640,470 640,520\"/>\n";

    private static final String SOCKET = "<polygon data-depth=\"-0.15\" data-radius=\"0.05\" PROFILE "
            + "points=\"560,560 720,560 720,720 560,720\"/>\n";

    private static final String BROAD_SMOOTH = "<polyline data-op=\"smooth\" data-radius=\"0.3\" "
            + "points=\"560,640 640,640 720,640\"/>\n";

    private static final String LEGACY_STROKE = "<polyline data-kind=\"ridge\" data-strength=\"0.6\" "
            + "stroke-width=\"60\" points=\"800,600 860,640 900,700\"/>\n";

    private static final String MIRROR_PLACEHOLDER = "MIRROR";

    private static final String PROFILE_PLACEHOLDER = "PROFILE";

    private static final float FRONT_HORIZON_Z = 0.15f;

    private static final int PIT_SAMPLES = 3;

    private static final float PIT_SPACING = 0.05f;

    private static final float PIT_DEPTH = 0.4f;

    private static final float FLOOR_FRACTION = 0.75f;

    private static final float FAR_RIM_X = -0.25f;

    private static final Vector3f GRAZING_EYE = new Vector3f(0.651f, 0.389f, 0.651f).mul(3.9f);

    private static final String DEPTH_PLACEHOLDER = "DEPTH";

    private static final String FAINT_DEPTH = "0.002";

    private static final String SYMMETRY_X = "x";

    private static final String CHEEK_FILE = "cheek.svg";

    private static final String TEETH_FILE = "teeth.svg";

    private static final float MIRROR_TOLERANCE = 1e-4f;

    private static final float EDGE_SLACK = 1.25f;

    private static final float MINIMUM_EDGE = 0.005f;

    private static final float MINIMUM_CHEEK_HEIGHT = 0.02f;

    private static final float PEAK_TOLERANCE = 0.1f;

    private static final float BOWL_RIM_SHARE = 0.75f;

    private static final float SOCKET_CENTER_SHARE = 0.8f;

    private static final float SOCKET_HALF_WIDTH = 0.15f;

    private static final int LIVE_WIDTH = 1280;

    private static final int LIVE_HEIGHT = 960;

    private static final float[] LIVE_STROKE_PIXELS = { 600f, 440f, 640f, 480f, 680f, 520f };

    private static final float RAY_TOLERANCE = 1e-3f;

    private static final float SPHERE_TOLERANCE = 1e-3f;

    @TempDir
    Path folder;

    @Test
    void symmetricPassMirrorsAOneSidedStroke() throws IOException {
        String path = svg(CHEEK_FILE, ONE_SIDED_CHEEK_RIDGE + WIDER_RIGHT_OUTLINE);
        float mirror = mirrorDistance(sculpt(sphere(), path, SYMMETRY_X).mesh());
        assertTrue(mirror < MIRROR_TOLERANCE, "mean mirror distance over the diagonal is " + mirror);
        float lopsided = mirrorDistance(sculpt(sphere(), path, StrokeSculptNode.NO_SYMMETRY).mesh());
        assertTrue(lopsided > MIRROR_TOLERANCE * 10, "without symmetry the cheek stays one-sided: " + lopsided);
    }

    @Test
    void fineStrokesRefineOnlyTheFacesTheyReach() throws IOException {
        GeometryBundle ball = sphere();
        String path = svg(TEETH_FILE, THREE_TEETH_RIGHT_OF_MIDLINE.replace(DEPTH_PLACEHOLDER, FAINT_DEPTH));
        MeshTopology refined = sculpt(ball, path, SYMMETRY_X).mesh();
        assertEquals(0, refined.boundaryEdgeCount(), "refinement leaves no open boundary");
        assertTrue(refined.faceCount() > ball.mesh().faceCount(), "the teeth refine the cage");
        assertEquals(backFaces(ball.mesh()), backFaces(refined), "faces behind the head are untouched");

        StrokeSketch sketch = StrokeSketch.read(path);
        sketch.lift(ball.mesh());
        Vector3f from = new Vector3f();
        Vector3f to = new Vector3f();
        for (int shape = 0; shape < sketch.shapes.size(); shape++) {
            float radius = sketch.radiusByShape[shape];
            float[] lifted = sketch.liftedXyzByShape[shape];
            assertTrue(lifted.length > 0, "tooth " + shape + " lifts onto the sphere");
            float target = Math.max(MINIMUM_EDGE, radius / 3f) * EDGE_SLACK;
            for (int index = 0; index < refined.edgeCount(); index++) {
                int halfEdge = refined.edgeHalfEdge(refined.edgeIdAt(index));
                refined.vertexPosition(refined.halfEdgeVertex(halfEdge), from);
                refined.vertexPosition(refined.halfEdgeEndVertex(halfEdge), to);
                for (int point = 0; point < lifted.length; point += 3) {
                    if (from.distance(lifted[point], lifted[point + 1], lifted[point + 2]) < radius / 2
                            && to.distance(lifted[point], lifted[point + 1], lifted[point + 2]) < radius / 2) {
                        assertTrue(from.distance(to) <= target, "edge of length " + from.distance(to)
                                + " under a tooth of radius " + radius);
                    }
                }
            }
        }
    }

    @Test
    void refinementKeepsTheCurvedSurfaceItSplits() throws IOException {
        String path = svg(TEETH_FILE, THREE_TEETH_RIGHT_OF_MIDLINE.replace(DEPTH_PLACEHOLDER, "0"));
        MeshTopology refined = sculpt(sphere(), path, SYMMETRY_X).mesh();
        Vector3f position = new Vector3f();
        float worst = 0f;
        for (int index = 0; index < refined.vertexCount(); index++) {
            worst = Math.max(worst, Math.abs(refined.vertexPosition(refined.vertexIdAt(index), position).length() - 1f));
        }
        assertTrue(worst < SPHERE_TOLERANCE, "refined vertices leave the unit sphere by up to " + worst
                + "; flat splits read as patches in curvature shading");
    }

    @Test
    void chainedPassRelandsOnAnEditedEarlierPass() throws IOException {
        String outline = svg("outline.svg", WIDER_RIGHT_OUTLINE);
        String cheek = svg(CHEEK_FILE, ONE_SIDED_CHEEK_RIDGE);
        MeshTopology first = sculpt(sculpt(sphere(), outline, SYMMETRY_X), cheek, SYMMETRY_X).mesh();
        MeshTopology again = sculpt(sculpt(sphere(), outline, SYMMETRY_X), cheek, SYMMETRY_X).mesh();
        assertArrayEquals(positions(first), positions(again), "re-running the passes replays them exactly");
        GeometryBundle edited = sculpt(sphere(), svg("narrower.svg", NARROWER_RIGHT_OUTLINE), SYMMETRY_X);
        float[] before = positions(edited.mesh());
        float[] after = positions(sculpt(edited, cheek, SYMMETRY_X).mesh());
        float cheekHeight = 0f;
        for (int index = 0; index < Math.min(before.length, after.length); index++) {
            cheekHeight = Math.max(cheekHeight, Math.abs(after[index] - before[index]));
        }
        assertTrue(cheekHeight > MINIMUM_CHEEK_HEIGHT, "the cheek pass still sculpts the edited outline, moving it "
                + cheekHeight);
    }

    @Test
    void aFillMovesNothingItsCameraCannotSee() throws IOException {
        String path = svg("fill.svg", WIDE_FRONT_FILL);
        for (String symmetry : new String[] { StrokeSculptNode.NO_SYMMETRY, SYMMETRY_X }) {
            float[] xyz = positions(sculpt(sphere(), path, symmetry).mesh());
            float hiddenMoved = 0f;
            float frontMoved = 0f;
            for (int vertex = 0; vertex < xyz.length; vertex += 3) {
                float offSphere = Math.abs(new Vector3f(xyz[vertex], xyz[vertex + 1], xyz[vertex + 2]).length() - 1f);
                if (xyz[vertex + 2] < FRONT_HORIZON_Z) {
                    hiddenMoved = Math.max(hiddenMoved, offSphere);
                } else {
                    frontMoved = Math.max(frontMoved, offSphere);
                }
            }
            assertTrue(hiddenMoved < SPHERE_TOLERANCE, "with symmetry " + symmetry + " the fill moved the far side "
                    + "and underside, hidden from the front camera, by up to " + hiddenMoved);
            assertTrue(frontMoved > MINIMUM_CHEEK_HEIGHT, "the fill still sinks the front, by " + frontMoved);
        }
    }

    @Test
    void aRidgeLeavesTheFloorOfACavityItsCameraCannotSee() {
        StringBuilder pitStroke = new StringBuilder(-PIT_DEPTH + " r=0.06 fill:");
        for (int row = -PIT_SAMPLES; row <= PIT_SAMPLES; row++) {
            for (int column = -PIT_SAMPLES; column <= PIT_SAMPLES; column++) {
                float x = column * PIT_SPACING;
                float y = row * PIT_SPACING;
                pitStroke.append(pitStroke.charAt(pitStroke.length() - 1) == ':' ? " " : StrokeSketch.POINT_SEPARATOR)
                        .append(x).append(' ').append(y).append(' ').append((float) Math.sqrt(1 - x * x - y * y));
            }
        }
        GeometryBundle carved = new StrokeSculpt().apply(sphere(), pitStroke.toString());
        // The eye looks down onto the pit from 49 degrees off its axis, so the near wall hides the floor.
        Vector3f forward = new Vector3f(GRAZING_EYE).negate().normalize();
        StringBuilder ridgeStroke = new StringBuilder(String.format(Locale.ROOT, "0.2 r=0.35 eye=%f/%f/%f/%f/%f/%f:",
                GRAZING_EYE.x, GRAZING_EYE.y, GRAZING_EYE.z, forward.x, forward.y, forward.z));
        for (int point = -1; point <= 1; point++) {
            float y = point * PIT_SPACING * 2;
            ridgeStroke.append(point == -1 ? " " : StrokeSketch.POINT_SEPARATOR).append(FAR_RIM_X).append(' ').append(y).append(' ')
                    .append((float) Math.sqrt(1 - FAR_RIM_X * FAR_RIM_X - y * y));
        }
        float[] before = positions(carved.mesh());
        float[] after = positions(new StrokeSculpt().apply(carved, ridgeStroke.toString()).mesh());
        int floor = 0;
        float floorMoved = 0f;
        float ridgeHeight = 0f;
        for (int vertex = 0; vertex < before.length; vertex += 3) {
            float moved = Vector3f.distance(before[vertex], before[vertex + 1], before[vertex + 2], after[vertex],
                    after[vertex + 1], after[vertex + 2]);
            ridgeHeight = Math.max(ridgeHeight, moved);
            if (before[vertex + 2] < 1f - PIT_DEPTH * FLOOR_FRACTION) {
                floor++;
                floorMoved = Math.max(floorMoved, moved);
            }
        }
        assertTrue(floor > 0, "the pit has a floor");
        assertTrue(floorMoved < MIRROR_TOLERANCE, "the ridge lifted the hidden floor by " + floorMoved);
        assertTrue(ridgeHeight > MINIMUM_CHEEK_HEIGHT, "the ridge still rises, by " + ridgeHeight);
    }

    @Test
    void commentedTagsAreNotStrokes() throws IOException {
        String template = "<!-- Add one <polyline points=\"x,y ...\"> or <polygon> per stroke -->\n"
                + "<!--" + ONE_SIDED_CHEEK_RIDGE + "-->\n" + WIDER_RIGHT_OUTLINE;
        StrokeSketch sketch = StrokeSketch.read(svg("template.svg", template));
        assertEquals(1, sketch.shapes.size(), "only the outline outside the comments is a stroke");
        sketch.lift(sphere().mesh());
        assertTrue(sketch.liftedByShape[0] > 0, "the outline lifts");
    }

    @Test
    void liveStrokesLiftThroughTheViewsTheyWereDrawnOn() {
        MeshTopology ball = sphere().mesh();
        float[][] orbits = { { 1.2f, 0.3f, 3.2f, 0.3f, 30f }, { -0.4f, -0.2f, 4f, 0f, 50f } };
        Camera3D[] views = new Camera3D[orbits.length];
        StrokeSketch drawn = new StrokeSketch();
        for (int view = 0; view < orbits.length; view++) {
            views[view] = new Camera3D(new Vector3f(), 0f, 0f);
            views[view].setDistanceBounds(0f, Float.MAX_VALUE);
            views[view].fov = orbits[view][4];
            views[view].roll = orbits[view][3];
            views[view].moveTarget(new Vector3f(0.1f, 0.05f, 0f));
            views[view].setOrbit(orbits[view][0], orbits[view][1], orbits[view][2]);
            drawn.addStroke(StrokeSketch.RIDGE, 0.5f, 30f, LIVE_STROKE_PIXELS, views[view], false, LIVE_WIDTH,
                    LIVE_HEIGHT);
        }
        StrokeSketch saved = StrokeSketch.parse(drawn.toSvg(), "live strokes");
        saved.lift(ball);
        float[] origin = new float[3];
        float[] direction = new float[3];
        for (int view = 0; view < orbits.length; view++) {
            assertArrayEquals(drawn.viewCameras[view], saved.viewCameras[view], 1e-4f, "view " + view + " round trips");
            assertEquals(saved.radiusByShape[view] * 0.5f, saved.depthByShape[view], 1e-4f,
                    "a live stroke's strength is its depth over its world radius");
            float[] lifted = saved.liftedXyzByShape[view];
            assertTrue(lifted.length > 0, "the stroke on view " + view + " lifts onto the sphere");
            views[view].updateProjection(false, LIVE_WIDTH / (float) LIVE_HEIGHT, 0.01f, 100f);
            for (int point = 0; point < lifted.length; point += 3) {
                Vector3f hit = new Vector3f(lifted[point], lifted[point + 1], lifted[point + 2]);
                float nearestRay = Float.POSITIVE_INFINITY;
                for (int pixel = 0; pixel < LIVE_STROKE_PIXELS.length; pixel += 2) {
                    views[view].rayThroughPixel(new Matrix4f(), LIVE_STROKE_PIXELS[pixel], LIVE_STROKE_PIXELS[pixel + 1],
                            LIVE_WIDTH, LIVE_HEIGHT, origin, direction);
                    Vector3f along = new Vector3f(direction[0], direction[1], direction[2]).normalize();
                    nearestRay = Math.min(nearestRay, new Vector3f(hit).sub(origin[0], origin[1], origin[2])
                            .cross(along).length());
                }
                assertTrue(nearestRay < RAY_TOLERANCE, "a point lifted from view " + view + " lies "
                        + nearestRay + " off the rays of the pixels drawn there");
            }
        }
    }

    @Test
    void theReportNamesAStrokeOnTheOutlineAsDropped() throws IOException {
        MapNodeContext pass = sculptContext(sphere(), svg("edge.svg", RIDGE_ON_THE_OUTLINE + ONE_SIDED_CHEEK_RIDGE),
                StrokeSculptNode.NO_SYMMETRY);
        String report = pass.output(StrokeSculptNode.REPORT, String.class);
        String[] lines = report.split("\n");
        assertEquals(3, lines.length, report);
        assertTrue(lines[1].contains(StrokeSculptNode.DROPPED) && lines[1].contains("silhouette"),
                "the ridge drawn on the sphere's outline lands on edge-on surface, is dropped and the report "
                        + "points at a silhouette stroke: " + lines[1]);
        assertTrue(!lines[2].contains(StrokeSculptNode.DROPPED), "the cheek is not dropped: " + lines[2]);
        StrokeSculpt sculpt = new StrokeSculpt();
        StrokeSketch sketch = StrokeSketch.read(svg(CHEEK_FILE, ONE_SIDED_CHEEK_RIDGE));
        sculpt.apply(sphere(), sketch.lift(sphere().mesh()));
        assertEquals(sketch.depthByShape[0], sculpt.peakDisplacementByStroke[0], sketch.depthByShape[0] * PEAK_TOLERANCE,
                "a fully seen ridge's peak displacement is its data-depth, nothing multiplying it");
        assertTrue(lines[2].contains(String.format(Locale.ROOT, "radius %.4f", sketch.radiusByShape[0])), lines[2]);
    }

    @Test
    void aStrokeStillCarryingDataStrengthIsRefusedWithTheMigration() throws IOException {
        String path = svg("legacy.svg", LEGACY_STROKE);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> StrokeSketch.read(path).lift(sphere().mesh()));
        assertTrue(refused.getMessage().contains("sculpt-migrate"), refused.getMessage());
    }

    @Test
    void aMirroredStrokeOnTheMirrorPlaneRunsOnce() throws IOException {
        float[] once = positions(sculpt(sphere(), svg("midline.svg", MIDLINE_RIDGE.replace(MIRROR_PLACEHOLDER, "")),
                StrokeSculptNode.NO_SYMMETRY).mesh());
        float[] mirrored = positions(sculpt(sphere(), svg("midline_mirror.svg",
                MIDLINE_RIDGE.replace(MIRROR_PLACEHOLDER, "data-mirror=\"x\"")), StrokeSculptNode.NO_SYMMETRY).mesh());
        assertArrayEquals(once, mirrored, "a ridge on the plane and its own reflection are one stroke");
    }

    @Test
    void mirrorTwinsTolerateASurfaceThatIsNotQuiteSymmetric() {
        HalfEdgeMesh triangle = new HalfEdgeMesh();
        triangle.addVertex(-1f, 0f, 0f);
        triangle.addVertex(1f, 0f, 0f);
        triangle.addVertex(0f, 5f, 0f);
        triangle.addFace(0, 1, 2);
        NearestVertex index = NearestVertex.over(triangle);
        assertThrows(IllegalStateException.class, () -> index.find(0f, 0f, 0f), "an authored point on a tie is refused");
        assertTrue(index.nearest(0f, 0f, 0f, Float.POSITIVE_INFINITY) >= 0, "a sculpt sample on a tie takes either");
        assertEquals(-1, index.nearest(0f, 0f, 0f, 0.5f), "no vertex lies within half a unit");

        // A sphere turned a little about y is no longer its own mirror image: every stroke still lifts
        // and symmetrises without refusing a tie, and a stroke's peak is not averaged away.
        MeshTopology ball = sphere().mesh();
        float[] turn = positions(ball);
        for (int vertex = 0; vertex < turn.length; vertex += 3) {
            Vector3f turned = new Vector3f(turn[vertex], turn[vertex + 1], turn[vertex + 2]).rotateY(0.03f);
            turn[vertex] = turned.x - turn[vertex];
            turn[vertex + 1] = turned.y - turn[vertex + 1];
            turn[vertex + 2] = turned.z - turn[vertex + 2];
        }
        MeshTopology turnedMesh = MeshVertexOffset.apply(ball, Vector3Field.copyOf(turn));
        StrokeSculpt sculpt = new StrokeSculpt();
        sculpt.symmetryAxis = 0;
        StrokeSketch cheek = StrokeSketch.parse(UNIT_SPHERE_CAMERA_ROOT + ONE_SIDED_CHEEK_RIDGE + "</svg>", "cheek");
        sculpt.apply(GeometryBundle.ofMesh(turnedMesh), cheek.lift(turnedMesh));
        assertEquals(cheek.depthByShape[0], sculpt.peakDisplacementByStroke[0], cheek.depthByShape[0] * PEAK_TOLERANCE,
                "the cheek's peak on the turned sphere");
    }

    @Test
    void aBowlFillCurvesItsFloorUpToTheRim() throws IOException {
        MapNodeContext flatPass = sculptContext(sphere(), svg("flat.svg", SOCKET.replace(PROFILE_PLACEHOLDER, "")),
                StrokeSculptNode.NO_SYMMETRY);
        float[] flat = positions(flatPass.output(StrokeSculptNode.GEOMETRY_OUT, GeometryBundle.class).mesh());
        String flatReport = flatPass.output(StrokeSculptNode.REPORT, String.class);
        float[] bowl = positions(sculpt(sphere(), svg("bowl.svg",
                SOCKET.replace(PROFILE_PLACEHOLDER, "data-profile=\"bowl\"")),
                StrokeSculptNode.NO_SYMMETRY).mesh());
        float[] ball = positions(sphere().mesh());
        float flatCenter = 0f;
        float bowlCenter = 0f;
        float flatNearRim = 0f;
        float bowlNearRim = 0f;
        for (int vertex = 0; vertex < ball.length; vertex += 3) {
            if (ball[vertex + 2] < 0f) {
                continue;
            }
            float across = Math.max(Math.abs(ball[vertex]), Math.abs(ball[vertex + 1]));
            float flatSink = 1f - new Vector3f(flat[vertex], flat[vertex + 1], flat[vertex + 2]).length();
            float bowlSink = 1f - new Vector3f(bowl[vertex], bowl[vertex + 1], bowl[vertex + 2]).length();
            if (across < SOCKET_HALF_WIDTH * 0.2f) {
                flatCenter = Math.max(flatCenter, flatSink);
                bowlCenter = Math.max(bowlCenter, bowlSink);
            } else if (across > SOCKET_HALF_WIDTH * 0.6f && across < SOCKET_HALF_WIDTH * 0.9f) {
                flatNearRim = Math.max(flatNearRim, flatSink);
                bowlNearRim = Math.max(bowlNearRim, bowlSink);
            }
        }
        assertTrue(flatCenter > 0f && bowlCenter > SOCKET_CENTER_SHARE * flatCenter, "the bowl is as deep at its "
                + "centre: " + bowlCenter + " against the flat fill's " + flatCenter + "; " + flatReport);
        assertTrue(flatNearRim > 0f && bowlNearRim < BOWL_RIM_SHARE * flatNearRim, "near its rim the bowl is "
                + "shallower: " + bowlNearRim + " against the flat fill's " + flatNearRim);
    }

    @Test
    void aSmoothStrokeDoesNotRefine() throws IOException {
        GeometryBundle ball = sphere();
        assertEquals(ball.mesh().faceCount(), sculpt(ball, svg("smooth.svg", BROAD_SMOOTH), StrokeSculptNode.NO_SYMMETRY)
                .mesh().faceCount(), "smoothing adds no faces for its umbrella weights to write streaks with");
    }

    @Test
    void loadMeshAndSvgResolveOneResourcePath() {
        assertTrue(Files.isRegularFile(MeshLoader.resolve("dsl/stroke_sculpt_head/pass1.svg")),
                "a resources-relative path resolves for load_mesh as for stroke_sculpt's svg");
    }

    private GeometryBundle sphere() {
        MapNodeContext ball = new MapNodeContext(new QuadSphereMeshNode())
                .with(QuadSphereMeshNode.SIZE, 2f).with(QuadSphereMeshNode.RESOLUTION, 64).eval();
        return new MapNodeContext(new MergeByDistanceNode())
                .with(MergeByDistanceNode.GEOMETRY, ball.output(QuadSphereMeshNode.MESH, GeometryBundle.class))
                .with(MergeByDistanceNode.DISTANCE, 1e-4f).eval()
                .output(MergeByDistanceNode.GEOMETRY_OUT, GeometryBundle.class);
    }

    private MapNodeContext sculptContext(GeometryBundle input, String svgPath, String symmetry) {
        return new MapNodeContext(new StrokeSculptNode()).with(StrokeSculptNode.GEOMETRY, input)
                .with(StrokeSculptNode.SVG, svgPath).with(StrokeSculptNode.SYMMETRY, symmetry).eval();
    }

    private GeometryBundle sculpt(GeometryBundle input, String svgPath, String symmetry) {
        return sculptContext(input, svgPath, symmetry).output(StrokeSculptNode.GEOMETRY_OUT, GeometryBundle.class);
    }

    private String svg(String name, String shapes) throws IOException {
        Path file = folder.resolve(name);
        Files.write(file, (UNIT_SPHERE_CAMERA_ROOT + shapes + "</svg>\n").getBytes(StandardCharsets.UTF_8));
        return file.toString();
    }

    private static float mirrorDistance(MeshTopology mesh) {
        float[] xyz = positions(mesh);
        float diagonal = mesh.boundsMax(new Vector3f()).distance(mesh.boundsMin(new Vector3f()));
        double total = 0.0;
        for (int vertex = 0; vertex < xyz.length; vertex += 3) {
            float nearest = Float.POSITIVE_INFINITY;
            for (int other = 0; other < xyz.length; other += 3) {
                nearest = Math.min(nearest, Vector3f.distanceSquared(-xyz[vertex], xyz[vertex + 1], xyz[vertex + 2],
                        xyz[other], xyz[other + 1], xyz[other + 2]));
            }
            total += Math.sqrt(nearest);
        }
        return (float) (total / (xyz.length / 3) / diagonal);
    }

    private static int backFaces(MeshTopology mesh) {
        int faces = 0;
        Vector3f position = new Vector3f();
        for (int index = 0; index < mesh.faceCount(); index++) {
            int faceId = mesh.faceIdAt(index);
            boolean behind = true;
            for (int corner = 0; corner < mesh.faceVertexCount(faceId); corner++) {
                behind &= mesh.vertexPosition(mesh.faceVertexAt(faceId, corner), position).z < 0f;
            }
            faces += behind ? 1 : 0;
        }
        return faces;
    }

    private static float[] positions(MeshTopology mesh) {
        float[] xyz = new float[mesh.vertexCount() * 3];
        Vector3f position = new Vector3f();
        for (int index = 0; index < mesh.vertexCount(); index++) {
            mesh.vertexPosition(mesh.vertexIdAt(index), position);
            xyz[index * 3] = position.x;
            xyz[index * 3 + 1] = position.y;
            xyz[index * 3 + 2] = position.z;
        }
        return xyz;
    }
}
