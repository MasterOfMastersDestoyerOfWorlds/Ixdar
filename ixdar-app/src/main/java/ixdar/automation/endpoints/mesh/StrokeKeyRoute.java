package ixdar.automation.endpoints.mesh;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.joml.Vector3f;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.automation.AutomationEndpoint;
import ixdar.geometry.mesh.data.analysis.curvature.SurfaceRidges;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.io.MeshLoader;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.sculpt.AnchoredStroke;
import ixdar.geometry.mesh.data.sculpt.FaceAnchors;
import ixdar.geometry.mesh.data.sculpt.GoalProjection;
import ixdar.geometry.mesh.data.sculpt.KeyCandidates;
import ixdar.geometry.mesh.data.sculpt.KeyStrokeMatch;
import ixdar.geometry.mesh.data.sculpt.SculptSync;
import ixdar.geometry.mesh.data.sculpt.StrokeAnchors;
import ixdar.geometry.mesh.data.sculpt.StrokeKey;
import ixdar.geometry.mesh.data.sculpt.StrokeSculpt;
import ixdar.geometry.mesh.data.sculpt.StrokeSketch;
import ixdar.nodegraph.nodes.modifier.StrokeSculptNode;
import ixdar.nodegraph.runtime.NodeGraphRuntime;

/**
 * The judge's stroke answer key: the strokes that move a sculpt's base onto its goal at several
 * counts, each written as a synced stroke layer and scored, with the offset maps and an optional
 * grading of another stroke set against the key.
 */
@AutomationRouteAnnotation(path = "/mesh/stroke-key", method = APIMethod.POST)
public class StrokeKeyRoute extends AutomationEndpoint implements AutomationRoute {

    public static final String BASE = "base";

    public static final String GOAL = "goal";

    public static final String REFERENCE = "reference";

    public static final String TEMPLATE = "template";

    public static final String OUT = "out";

    public static final String COUNTS = "counts";

    public static final String SYMMETRY = "symmetry";

    public static final String COMPARE = "compare";

    public static final String MANIFEST = "manifest";

    public static final String SURFACE_ROUNDS = "surface_rounds";

    public static final String RESUME = "resume";

    public static final String MAPS = "maps";

    public static final String RIDGE_LEVELS = "ridge_levels";

    public static final String GOAL_LINE_RADIUS = "goal_line_radius";

    public static final String DEFAULT_SYMMETRY = "x";

    public static final String SVG_SUFFIX = ".svg";

    public static final String COUNT_SEPARATOR = ",";

    public static final float NEAR_OFFSET = 0.01f;

    public static final float FAR_OFFSET = 0.03f;

    public static final float DOT_EDGE_FRACTION = 0.3f;

    public static final int REFERENCE_PIXELS = 1280;

    public static final long WAIT_SECONDS = 7200;

    /**
     * {@code POST /mesh/stroke-key}: compute the key and write its files under {@code out}.
     *
     * @param body base, goal, template, out; optional reference, counts, symmetry, compare, manifest
     * @return the key's scores and the files written, or the refusal
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) {
        try {
            long received = System.nanoTime();
            String basePath = existingFile("--base", text(body, BASE)).toString();
            Path goalPath = existingFile("--goal", text(body, GOAL));
            Path templatePath = existingFile("--template", text(body, TEMPLATE));
            Path out = Path.of(text(body, OUT)).toAbsolutePath().normalize();
            Files.createDirectories(out);
            StrokeSketch template = StrokeSketch.parse(new String(Files.readAllBytes(templatePath),
                    StandardCharsets.UTF_8), templatePath.toString());
            String symmetry = body.has(SYMMETRY) ? body.get(SYMMETRY).getAsString().trim() : DEFAULT_SYMMETRY;
            StrokeKey key = new StrokeKey();
            key.symmetryAxis = StrokeSculptNode.NO_SYMMETRY.equals(symmetry) ? -1
                    : StrokeSculptNode.AXES.indexOf(symmetry.charAt(0));
            if (body.has(COUNTS)) {
                String[] counts = body.get(COUNTS).getAsString().split(COUNT_SEPARATOR);
                key.counts = new int[counts.length];
                for (int index = 0; index < counts.length; index++) {
                    key.counts[index] = Integer.parseInt(counts[index].trim());
                }
            }
            key.surfaceRounds = body.has(SURFACE_ROUNDS) ? body.get(SURFACE_ROUNDS).getAsInt() : key.surfaceRounds;
            String baseDsl = "base = load_mesh(path=\"" + basePath + "\")\n";
            NodeGraphRuntime baseGraph = NodeGraphRuntime.fromSource(baseDsl);
            GeometryBundle base = (GeometryBundle) baseGraph.executeGraphResult(baseGraph.lastId(), "geometry");
            MeshTopology baseMesh = base.mesh();
            MeshTopology goal = MeshLoader.load(goalPath.toString());
            double loadingSeconds = (System.nanoTime() - received) / 1e9;
            int ridgeLevels = body.has(RIDGE_LEVELS) ? body.get(RIDGE_LEVELS).getAsInt() : 0;
            SurfaceRidges ridges = new SurfaceRidges();
            if (ridgeLevels > 0) {
                ridges.levels = ridgeLevels;
                ridges.detect(goal);
                key.goalLines = ridges.lines;
            }
            if (body.has(GOAL_LINE_RADIUS)) {
                key.shapes = new KeyCandidates(baseMesh, template);
                key.shapes.goalLineRadius = body.get(GOAL_LINE_RADIUS).getAsFloat();
            }
            String referenceText = body.has(REFERENCE) ? body.get(REFERENCE).getAsString() : "";
            MeshTopology reference = referenceText.isBlank() ? goal
                    : MeshLoader.load(existingFile("--reference", referenceText).toString());
            StrokeAnchors start = null;
            if (body.has(RESUME) && !body.get(RESUME).getAsString().isBlank()) {
                String resumed = existingFile("--resume", body.get(RESUME).getAsString()).toString();
                start = StrokeAnchors.beside(resumed);
                if (start == null) {
                    return failure("--resume " + resumed + " has no stroke sidecar beside it to continue from");
                }
                start.checkBase(baseMesh, StrokeAnchors.sidecarPath(resumed));
            }
            long started = System.nanoTime();
            key.compute(base, goal, reference, template, start);
            if (key.goalLines != null) {
                // The goal's lines as the key carried them onto the base, whole, in every cell that sees them.
                FaceAnchors onBase = new FaceAnchors(baseMesh);
                StrokeAnchors carried = new StrokeAnchors();
                for (int line = 0; line < key.shapes.carriedLines.size(); line++) {
                    float[] points = key.shapes.carriedLines.get(line);
                    boolean valley = ridges.valleyByLine.get(key.shapes.carriedFrom.get(line));
                    AnchoredStroke stroke = new AnchoredStroke();
                    stroke.layer = valley ? KeyCandidates.VALLEY_LAYER : KeyCandidates.RIDGE_LAYER;
                    stroke.id = stroke.layer + StrokeAnchors.LAYER_ID_SEPARATOR + (line + 1);
                    stroke.attributes = String.format(Locale.ROOT, "data-kind=\"%s\" data-depth=\"0\" data-radius=\"%.5f\" "
                            + "fill=\"none\"", valley ? StrokeSketch.VALLEY : StrokeSketch.RIDGE, key.shapes.goalLineRadius);
                    stroke.anchors = new float[points.length / 3 * FaceAnchors.FIELDS];
                    for (int point = 0; point * 3 < points.length; point++) {
                        onBase.anchor(points[point * 3], points[point * 3 + 1], points[point * 3 + 2], stroke.anchors,
                                point * FaceAnchors.FIELDS);
                    }
                    carried.strokes.add(stroke);
                }
                Files.write(out.resolve("goal_lines_on_base.svg"), new SculptSync(template, baseMesh, baseMesh, baseMesh)
                        .draw(carried).getBytes(StandardCharsets.UTF_8));
            }
            JsonObject result = success();
            result.addProperty("seconds", (System.nanoTime() - started) / 1e9);
            result.addProperty("seconds_loading_base_and_goal", loadingSeconds);
            if (ridgeLevels > 0) {
                result.addProperty("seconds_goal_ridges", ridges.seconds[0] + ridges.seconds[1] + ridges.seconds[2]);
            }
            JsonObject stages = new JsonObject();
            for (int stage = 0; stage < StrokeKey.STAGES.size(); stage++) {
                stages.addProperty(StrokeKey.STAGES.get(stage), key.secondsByStage[stage]);
            }
            result.add("seconds_by_stage", stages);
            result.addProperty("base_vertices", baseMesh.vertexCount());
            result.addProperty("surface_vertices", key.surface.vertexCount());
            result.addProperty("unreachable_share", key.projection.unreachableShare());
            result.addProperty("initial_residual", key.initialResidual);
            result.addProperty("base_chamfer", key.baseChamfer);
            result.addProperty("atoms_computed", key.pursuit.atomsComputed);
            JsonArray files = new JsonArray();
            JsonArray rows = new JsonArray();
            for (int index = 0; index < key.counts.length; index++) {
                StrokeAnchors strokes = key.keyByCount.get(index);
                GeometryBundle sculpted = index == key.counts.length - 1 ? key.lastSculpt
                        : key.sculpt(strokes, base, template);
                String name = String.format(Locale.ROOT, "key_%03d", key.counts[index]);
                Path svg = out.resolve(name + SVG_SUFFIX);
                strokes.written = new SculptSync(template, baseMesh, ((StrokeSculpt) sculpted.slots().get(
                        StrokeSculpt.SCULPT_SLOT)).refined, sculpted.mesh()).draw(strokes);
                Files.write(svg, strokes.written.getBytes(StandardCharsets.UTF_8));
                Files.write(out.resolve(Path.of(StrokeAnchors.sidecarPath(svg.toString())).getFileName()),
                        strokes.toText().getBytes(StandardCharsets.UTF_8));
                Path dsl = out.resolve(name + ".dsl");
                Files.write(dsl, (baseDsl + "sculpt = stroke_sculpt(geometry=base.geometry, svg=\"" + svg
                        + "\", symmetry=\"" + symmetry + "\")\n").getBytes(StandardCharsets.UTF_8));
                files.add(dsl.toString());
                JsonObject row = new JsonObject();
                row.addProperty("strokes", strokes.strokes.size());
                row.addProperty("chamfer", key.chamferByCount[index]);
                row.addProperty("hausdorff", key.hausdorffByCount[index]);
                row.addProperty("residual", key.residualByCount[index]);
                row.addProperty("sculpt_vertices", sculpted.mesh().vertexCount());
                row.addProperty("dsl", dsl.toString());
                JsonObject layers = new JsonObject();
                for (AnchoredStroke stroke : strokes.strokes) {
                    layers.addProperty(stroke.layer, layers.has(stroke.layer) ? layers.get(stroke.layer).getAsInt() + 1 : 1);
                }
                row.add("layers", layers);
                rows.add(row);
            }
            result.add(COUNTS, rows);
            // Every candidate the pursuit was offered, each in the cell it was drawn in and grouped by
            // layer, for strokes_overlay over the base's capture.
            StrokeSketch offered = key.candidateStrokes.sketch(template, baseMesh);
            List<String> grouped = new ArrayList<>();
            for (String layer : StrokeKey.LAYER_ORDER) {
                grouped.add("<g id=\"" + layer + "\">");
                for (int index = 0; index < offered.shapes.size(); index++) {
                    grouped.add(key.candidateStrokes.strokes.get(index).layer.equals(layer) ? offered.shapes.get(index)
                            : "");
                }
                grouped.add("</g>");
            }
            offered.shapes = grouped;
            Path candidates = out.resolve("candidates.svg");
            Files.write(candidates, offered.toSvg().getBytes(StandardCharsets.UTF_8));
            files.add(candidates.toString());
            result.addProperty("candidates", key.candidateStrokes.strokes.size());
            // The offset and reachability maps: one dot per base vertex, grouped by bin, drawn into
            // every template cell that sees it, for strokes_overlay over the base's capture.
            float diagonal = baseMesh.boundsDiagonal();
            String[] binNames = { "sink_far", "within_near", "sink_near", "raise_far", "raise_near" };
            int[] binByVertex = new int[baseMesh.vertexCount()];
            int[] reachByVertex = new int[baseMesh.vertexCount()];
            for (int activeVertex = 0; activeVertex < binByVertex.length; activeVertex++) {
                float relative = key.projection.offset[activeVertex] / diagonal;
                binByVertex[activeVertex] = relative <= -FAR_OFFSET ? 0 : relative <= -NEAR_OFFSET ? 2
                        : relative < NEAR_OFFSET ? 1 : relative < FAR_OFFSET ? 4 : 3;
                reachByVertex[activeVertex] = key.projection.reachable[activeVertex] ? -1 : 0;
            }
            if (body.has(MAPS) && body.get(MAPS).getAsBoolean()) {
                Path offsetMap = out.resolve("offset_map.svg");
                Files.write(offsetMap, dots(baseMesh, template, binByVertex, binNames).getBytes(StandardCharsets.UTF_8));
                Path reachMap = out.resolve("unreachable_map.svg");
                Files.write(reachMap, dots(baseMesh, template, reachByVertex, new String[] { "unreachable" })
                        .getBytes(StandardCharsets.UTF_8));
                files.add(offsetMap.toString());
                files.add(reachMap.toString());
            }
            StrokeAnchors largest = key.keyByCount.get(key.counts.length - 1);
            if (body.has(MANIFEST) && !body.get(MANIFEST).getAsString().isBlank()) {
                // The largest key drawn through the reference pictures' own cameras, its sculpt carried
                // into the goal's frame the pictures were rendered in.
                JsonObject manifest = JsonParser.parseString(new String(Files.readAllBytes(existingFile("--manifest",
                        body.get(MANIFEST).getAsString())), StandardCharsets.UTF_8)).getAsJsonObject();
                StrokeSketch pictures = new StrokeSketch();
                JsonArray shots = manifest.getAsJsonArray("shots");
                JsonArray center = manifest.getAsJsonArray("center");
                pictures.cellWidth = REFERENCE_PIXELS;
                pictures.cellHeight = REFERENCE_PIXELS;
                pictures.columns = shots.size();
                pictures.viewCameras = new float[shots.size()][];
                for (int axis = 0; axis < 3; axis++) {
                    pictures.center[axis] = center.get(axis).getAsFloat();
                }
                for (int shot = 0; shot < shots.size(); shot++) {
                    JsonObject camera = shots.get(shot).getAsJsonObject();
                    pictures.viewCameras[shot] = new float[] { (float) Math.toRadians(camera.get("azimuth_deg")
                            .getAsDouble()), (float) Math.toRadians(camera.get("elevation_deg").getAsDouble()),
                        camera.get("distance").getAsFloat(), (float) Math.toRadians(camera.get("roll_deg")
                                .getAsDouble()), camera.get("fov_deg").getAsFloat(), pictures.center[0],
                        pictures.center[1], pictures.center[2] };
                }
                pictures.distance = pictures.viewCameras[0][StrokeSketch.VIEW_DISTANCE];
                pictures.fieldOfViewDegrees = pictures.viewCameras[0][StrokeSketch.VIEW_FIELD_OF_VIEW];
                pictures.text = pictures.toSvg();
                GeometryBundle sculpted = key.lastSculpt;
                StrokeAnchors moved = StrokeAnchors.parse(largest.toText());
                Path overReferences = out.resolve(String.format(Locale.ROOT, "key_%03d_references.svg",
                        key.counts[key.counts.length - 1]));
                // The pictures' frame is the goal they were rendered from: its framed centre and radius
                // (the bounding box's half diagonal), which the manifest records.
                Vector3f frameCenter = new Vector3f(pictures.center[0], pictures.center[1], pictures.center[2]);
                float frameRadius = manifest.get("radius").getAsFloat();
                Files.write(overReferences, new SculptSync(pictures, GoalProjection.moved(baseMesh, baseMesh,
                        frameCenter, frameRadius), GoalProjection.moved(((StrokeSculpt) sculpted.slots().get(
                                StrokeSculpt.SCULPT_SLOT)).refined, baseMesh, frameCenter, frameRadius),
                        GoalProjection.moved(sculpted.mesh(), baseMesh, frameCenter, frameRadius)).draw(moved)
                        .getBytes(StandardCharsets.UTF_8));
                files.add(overReferences.toString());
            }
            if (body.has(COMPARE) && !body.get(COMPARE).getAsString().isBlank()) {
                Path comparePath = existingFile("--compare", body.get(COMPARE).getAsString());
                KeyStrokeMatch match = new KeyStrokeMatch();
                match.symmetryAxis = key.symmetryAxis;
                String compareText = new String(Files.readAllBytes(comparePath), StandardCharsets.UTF_8);
                StrokeSketch other = StrokeSketch.parse(compareText, comparePath.toString());
                StrokeAnchors otherAnchors = StrokeAnchors.beside(comparePath.toString());
                match.match(otherAnchors == null ? other : otherAnchors.sketch(other, baseMesh), largest, template,
                        baseMesh);
                Path drawing = out.resolve("match_" + comparePath.getFileName().toString().replace(SVG_SUFFIX, "")
                        + "_key_" + key.counts[key.counts.length - 1] + SVG_SUFFIX);
                Files.write(drawing, match.drawing.getBytes(StandardCharsets.UTF_8));
                files.add(drawing.toString());
                JsonObject grade = new JsonObject();
                grade.addProperty("against", key.counts[key.counts.length - 1]);
                grade.addProperty("silhouettes", match.silhouettes);
                grade.addProperty("unlifted", match.unlifted);
                grade.add("other_by_cell_kind", table(match.otherByCellKind));
                grade.add("matched_other_by_cell_kind", table(match.matchedOtherByCellKind));
                grade.add("placed_other_by_cell", new GsonBuilder().create().toJsonTree(match.placedOtherByCell));
                grade.add("key_by_cell_kind", table(match.keyByCellKind));
                grade.add("matched_key_by_cell_kind", table(match.matchedKeyByCellKind));
                grade.add("kinds", new GsonBuilder().create().toJsonTree(KeyStrokeMatch.KINDS));
                result.add("match", grade);
            }
            result.add("files", files);
            result.addProperty("seconds_with_loading_and_writing", (System.nanoTime() - received) / 1e9);
            Files.write(out.resolve("key.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result)
                    .getBytes(StandardCharsets.UTF_8));
            return result;
        } catch (Exception failed) {
            return failure(failed.getMessage() == null ? failed.toString() : failed.getMessage());
        } catch (OutOfMemoryError exhausted) {
            return failure("the key ran out of heap: give the goal fewer vertices (the judge's decimated copy) or the "
                    + "base fewer surface_rounds");
        }
    }

    /**
     * A map SVG: one short stroke per base vertex at its place, in the group its bin names, drawn
     * into every template cell that sees it on the base.
     *
     * @param base     the base
     * @param template the template whose cells draw the map
     * @param binByVertex each dense base vertex's bin, -1 to leave it out
     * @param binNames the bins' group names, in the order strokes_overlay colours them
     * @return the SVG text
     */
    private static String dots(MeshTopology base, StrokeSketch template, int[] binByVertex, String[] binNames) {
        double meanEdge = 0.0;
        for (int activeEdge = 0; activeEdge < base.edgeCount(); activeEdge++) {
            meanEdge += base.edgeLength(base.edgeIdAt(activeEdge)) / base.edgeCount();
        }
        FaceAnchors anchors = new FaceAnchors(base);
        StrokeAnchors map = new StrokeAnchors();
        Vector3f position = new Vector3f();
        Vector3f normal = new Vector3f();
        for (int bin = 0; bin < binNames.length; bin++) {
            for (int activeVertex = 0; activeVertex < binByVertex.length; activeVertex++) {
                if (binByVertex[activeVertex] != bin) {
                    continue;
                }
                int vertexId = base.vertexIdAt(activeVertex);
                base.vertexPosition(vertexId, position);
                base.vertexNormal(vertexId, normal).normalize();
                Vector3f step = new Vector3f(Math.abs(normal.x) < 0.5f ? 1f : 0f, Math.abs(normal.x) < 0.5f ? 0f : 1f, 0f)
                        .cross(normal).normalize((float) meanEdge * DOT_EDGE_FRACTION).add(position);
                AnchoredStroke dot = new AnchoredStroke();
                dot.id = binNames[bin] + StrokeAnchors.LAYER_ID_SEPARATOR + activeVertex;
                dot.layer = binNames[bin];
                dot.attributes = String.format(Locale.ROOT, "data-kind=\"ridge\" data-depth=\"0\" data-radius=\"%.5f\" "
                        + "fill=\"none\"", meanEdge * DOT_EDGE_FRACTION);
                dot.anchors = new float[2 * FaceAnchors.FIELDS];
                anchors.anchor(position.x, position.y, position.z, dot.anchors, 0);
                anchors.anchor(step.x, step.y, step.z, dot.anchors, FaceAnchors.FIELDS);
                map.strokes.add(dot);
            }
        }
        return new SculptSync(template, base, base, base).draw(map);
    }

    /**
     * A table of counts as nested JSON arrays.
     *
     * @param rows the counts
     * @return the array
     */
    private static JsonElement table(int[][] rows) {
        return new GsonBuilder().create().toJsonTree(rows);
    }

    /**
     * A required string field of the request.
     *
     * @param body  the request
     * @param field the field's name
     * @return its value, empty when absent
     */
    private static String text(JsonObject body, String field) {
        return body.has(field) ? body.get(field).getAsString() : "";
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName("stroke-key")
                .description("Judge-side stroke answer key: refine the sculpt's base, cast each vertex's normal both "
                        + "ways onto the goal (moved into the base's frame as mesh-compare --normalize does) for the "
                        + "offset a stroke must make, marking vertices a miss or back face leaves unreachable, then "
                        + "choose strokes by matching pursuit scored with the stroke engine itself (fills from offset "
                        + "regions at several scales, chains along the offset's ridge and valley lines), refining depth "
                        + "and shape every 25 and pruning; each count's key is written as a synced stroke layer "
                        + "(key_NNN.svg, .strokes, .dsl) and scored by chamfer against the reference. Also writes "
                        + "offset_map.svg and unreachable_map.svg (dots for strokes_overlay over the base), the "
                        + "largest key over the reference pictures' cameras (--manifest), and grades another stroke "
                        + "SVG against the largest key (--compare).")
                .param(BASE, RouteParamType.STRING, true, "", "The sculpt's base mesh file, as its load_mesh reads it.",
                        "~/.cache/ixdar-sculpt-judge/skull/key-mesh91/base.ply")
                .param(GOAL, RouteParamType.STRING, true, "", "The goal mesh (OBJ) the offsets are measured to.",
                        "~/.cache/ixdar-sculpt-judge/skull/Skull.obj")
                .param(TEMPLATE, RouteParamType.STRING, true, "",
                        "The multiview SVG template (multiview --svg of the base) whose cells the key is drawn in.",
                        "~/.cache/ixdar-sculpt-judge/skull/key-mesh91/template.svg")
                .param(OUT, RouteParamType.STRING, true, "", "Directory the key's files are written to.",
                        "~/.cache/ixdar-sculpt-judge/skull/key-mesh91")
                .param(REFERENCE, RouteParamType.STRING, false, "",
                        "Mesh chamfer is scored against (the judge's decimated goal); default the goal.",
                        "~/.cache/ixdar-sculpt-judge/skull/skull57k.obj")
                .param(COUNTS, RouteParamType.STRING, false, "25,50,100,200,400", "Stroke counts to keep a key at.",
                        "25,50,100")
                .param(SYMMETRY, RouteParamType.STRING, false, DEFAULT_SYMMETRY,
                        "The sculpt's stroke_sculpt symmetry axis, or none.", DEFAULT_SYMMETRY)
                .param(SURFACE_ROUNDS, RouteParamType.INT, false, "2",
                        "Rounds of midpoint subdivision of the base the offsets are measured on.", "1")
                .param(RESUME, RouteParamType.STRING, false, "",
                        "A key SVG this route wrote (its .strokes beside it): keep its strokes at their depths and "
                        + "add strokes up to --counts, so a long curve is built one bounded call at a time.",
                        "~/.cache/ixdar-sculpt-judge/skull/key-mesh91/key_025.svg")
                .param(RIDGE_LEVELS, RouteParamType.INT, false, "0",
                        "Offer chains along the goal's own ridge and valley lines (OBS04, surface-ridges) fitted at "
                        + "this many levels, carried onto the base, instead of the offset field's lines; 0 keeps those.",
                        "6")
                .param(GOAL_LINE_RADIUS, RouteParamType.FLOAT, false, "0.06",
                        "Falloff radius chains along the goal's lines are offered at, base units.", "0.04")
                .param(MAPS, RouteParamType.BOOL, false, "false",
                        "Also write offset_map.svg and unreachable_map.svg.", "true")
                .param(MANIFEST, RouteParamType.STRING, false, "",
                        "The reference pictures' manifest; draws the largest key through their cameras.",
                        "~/.cache/ixdar-sculpt-judge/skull/references_manifest_mesh88.json")
                .param(COMPARE, RouteParamType.STRING, false, "",
                        "Another stroke SVG on the same base to grade against the largest key by location and kind.",
                        "tmp/edges_r4/edges.svg")
                .responseHint("{ok, seconds, unreachable_share, initial_residual, base_chamfer, counts:[{strokes, chamfer, "
                        + "hausdorff, residual, layers, dsl}], match?:{...by_cell_kind tables}, files} | {ok:false, error}")
                .waitSeconds(WAIT_SECONDS)
                .build();
    }
}
