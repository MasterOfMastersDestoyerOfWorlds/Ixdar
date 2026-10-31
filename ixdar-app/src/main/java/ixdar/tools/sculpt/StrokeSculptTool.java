package ixdar.tools.sculpt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ixdar.annotations.tool.ToolAnnotation;
import ixdar.geometry.mesh.data.sculpt.StrokeSketch;
import ixdar.models.ModelCatalog;
import ixdar.nodegraph.nodes.modifier.StrokeSculptNode;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.nodegraph.syntax.PythonParser;
import ixdar.platform.Platforms;
import ixdar.platform.input.KeyNames;
import ixdar.platform.input.Keys;
import ixdar.scenes.mesh.MeshNodeViewerScene;
import ixdar.tools.ControlHint;
import ixdar.tools.EditHistory;
import ixdar.tools.Tool;
import ixdar.tools.ring.RingDslWriter;

/**
 * Sculpts the viewer's graph with weighted strokes drawn on the live view: each stroke goes on the
 * open {@code stroke_sculpt} pass, chained after the graph's own, and the graph re-runs. The pass
 * list is the state undo and redo step through; Ctrl+S writes the .dsl and each pass's SVG.
 */
@ToolAnnotation(id = StrokeSculptTool.TOOL_NAME)
public final class StrokeSculptTool extends Tool {

    public static final String TOOL_NAME = "stroke sculpt";

    public static final String LOG_PREFIX = "[stroke-sculpt] ";

    public static final String[] KINDS = { StrokeSketch.SILHOUETTE, StrokeSketch.RIDGE, StrokeSketch.VALLEY,
        StrokeSketch.FILL };

    public static final String SPHERE_PRELUDE = "ball = sphere(size=2.0, resolution=64)\n"
            + "welded = merge_by_distance(geometry=ball.mesh, distance=0.0001)\n";

    public static final String SPHERE_ANCHOR = "welded.geometry";

    public static final String NEW_DOCUMENT_NAME = "stroke_sculpt_sketch";

    public static final String PASS_ID_PREFIX = "pass";

    public static final Pattern PASS_ID = Pattern.compile("^\\s*" + PASS_ID_PREFIX + "(\\d+)\\s*=",
            Pattern.MULTILINE);

    public static final String DSL_EXTENSION = "\\.dsl$";

    public static final String RESOURCE_ROOT = "src/main/resources/";

    public static final String SVG_SUFFIX = ".svg";

    public static final String PORT_SEPARATOR = ".";

    public static final String LINE_BREAK = "\n";

    public static final String SYMMETRY_AXIS = "x";

    public static final String REDO_ACTION = "redo";

    public static final String REDO_HINT = "redo stroke or pass";

    public static final String NOT_ACTIVE = "the stroke-sculpt tool is not active - ctrl+K switches to it";

    public static final float DEFAULT_STRENGTH = 0.5f;

    public static final float STRENGTH_STEP = 0.1f;

    public static final float STRENGTH_STEPS_PER_UNIT = 10f;

    public static final float DEFAULT_WIDTH_PIXELS = 40f;

    public static final float WIDTH_FACTOR = 1.25f;

    public static final float MINIMUM_WIDTH_PIXELS = 2f;

    public static final float MAXIMUM_WIDTH_PIXELS = 400f;

    public static final float MINIMUM_POINT_GAP_PIXELS = 2f;

    public static final double MINIMUM_FILL_AREA_PIXELS = 100.0;

    public static final int INITIAL_STROKE_CAPACITY = 64;

    public static final double NANOSECONDS_PER_MILLISECOND = 1e6;

    /** The viewer whose graph the tool sculpts and whose camera every stroke records. */
    public final MeshNodeViewerScene scene;

    /**
     * The passes as they stand, in chain order; the first {@link #loadedPasses} are the graph's
     * own, the rest drawn here, the last of which takes strokes while it is open.
     */
    public List<SculptPass> passes = List.of();

    /** How many of {@link #passes} the graph already held; undo never reaches below them. */
    public int loadedPasses;

    /** Every stroke, commit and symmetry switch since the graph loaded, as pass lists. */
    public final EditHistory<List<SculptPass>> history = new EditHistory<>();

    /** The .dsl a save writes, {@code null} before the graph is read. */
    public String documentFile;

    /** The graph's text through the statement the first drawn pass reads its geometry from. */
    public String prelude = "";

    /** The graph's text after its last pass, which reads the last pass's geometry instead. */
    public String postlude = "";

    /** The {@code id.port} the first drawn pass reads its geometry from. */
    public String anchor = "";

    /** What {@link #documentFile} held when last read or saved; a save is due while it differs. */
    public String savedSource = "";

    /** Whether the next pass mirrors its strokes across x; X flips it, and the open pass with it. */
    public boolean symmetric = true;

    /** Weight of the next stroke in [-1, 1]; ridge and valley take its magnitude, fill its sign. */
    public float strength = DEFAULT_STRENGTH;

    /** Falloff width of the next stroke in view pixels. */
    public float widthPixels = DEFAULT_WIDTH_PIXELS;

    /** Whether the tool takes the mouse. */
    public boolean active;

    /** Whether a press is drawing a stroke now. */
    public boolean drawing;

    /** The stroke being drawn, packed xy in framebuffer pixels from the top left. */
    public float[] strokePixels = new float[INITIAL_STROKE_CAPACITY];

    /** Points of {@link #strokePixels} in use. */
    public int strokePointsDrawn;

    /** The graph the tool read or ran last; another one showing means a model was loaded. */
    private NodeGraphRuntime shownGraph;

    /** Whether the shown graph has been read into passes since the tool was created. */
    private boolean documentRead;

    /**
     * Bind the tool to the viewer it sculpts. Its modes are the stroke kinds, the one the next
     * stroke is drawn as, which 1 to 4 pick.
     *
     * @param scene the viewer whose graph and camera the tool works with
     */
    public StrokeSculptTool(MeshNodeViewerScene scene) {
        super(KINDS);
        this.scene = scene;
    }

    @Override
    public String toolName() {
        return TOOL_NAME;
    }

    @Override
    public String desc() {
        return "Draw silhouette, ridge, valley and fill strokes on the view into chained stroke_sculpt passes, "
                + "with undo, redo and a save to the .dsl and pass SVGs";
    }

    @Override
    public EditHistory<List<SculptPass>> history() {
        return history;
    }

    /**
     * Every pass, the graph's own first, each saved when the working .dsl holds it as it stands.
     *
     * @return the passes in chain order
     */
    @Override
    public List<SculptPass> items() {
        boolean saved = savedSource.equals(source());
        for (int pass = 0; pass < passes.size(); pass++) {
            passes.get(pass).saved = saved || pass < loadedPasses;
        }
        return passes;
    }

    /** Take the mouse: a drag draws from now on, and the shown graph is read if it is new. */
    @Override
    public void activate() {
        active = true;
        lastError = "";
    }

    /** Hand the mouse back, dropping a stroke half drawn and keeping every pass. */
    @Override
    public void deactivate() {
        active = false;
        drawing = false;
        strokePointsDrawn = 0;
    }

    /**
     * Read the shown graph into passes when the scene loaded another one while the tool is active,
     * dropping the passes and history of the last: its stroke_sculpt statements are the passes, and
     * drawn passes go after the last of them. A view running no graph starts from a sphere.
     */
    @Override
    public void perFrame() {
        if (!active || documentRead && scene.graph == shownGraph) {
            return;
        }
        documentRead = true;
        shownGraph = scene.graph;
        history.clear();
        String file = scene.workingDslPath();
        String source = null;
        try {
            source = file == null || shownGraph == null ? null
                    : new String(Files.readAllBytes(Path.of(file)), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            Platforms.get().log(LOG_PREFIX + "cannot read " + file + ", starting from a sphere: "
                    + unreadable.getMessage());
        }
        List<SculptPass> loaded = new ArrayList<>();
        if (source == null) {
            // No graph to chain onto: a fresh sculpt of the sphere, saved under the next free name.
            int serial = 1;
            Path root = ModelCatalog.repoDslRoot();
            while (Files.exists(root.resolve(NEW_DOCUMENT_NAME + (serial == 1 ? "" : "_" + serial)
                    + MeshNodeViewerScene.DSL))) {
                serial++;
            }
            documentFile = root.resolve(NEW_DOCUMENT_NAME + (serial == 1 ? "" : "_" + serial)
                    + MeshNodeViewerScene.DSL).toString();
            prelude = SPHERE_PRELUDE;
            postlude = "";
            anchor = SPHERE_ANCHOR;
            savedSource = "";
        } else {
            documentFile = file;
            savedSource = source;
            List<PythonParser.ParsedNode> statements = PythonParser.parse(source);
            int lastPass = -1;
            for (int statement = 0; statement < statements.size(); statement++) {
                PythonParser.ParsedNode node = statements.get(statement);
                if (!StrokeSculptNode.NODE_ID.equals(node.type)) {
                    continue;
                }
                lastPass = statement;
                Object geometry = node.arguments.get(StrokeSculptNode.GEOMETRY.name);
                String upstream = geometry instanceof PythonParser.NodeReference reference
                        ? reference.nodeId + PORT_SEPARATOR + reference.portName : "";
                String svgPath = node.arguments.get(StrokeSculptNode.SVG.name) instanceof String text ? text : "";
                String svg = "";
                try {
                    svg = svgPath.isEmpty() ? "" : StrokeSketch.read(svgPath).toSvg();
                } catch (IOException | RuntimeException unreadable) {
                    Platforms.get().log(LOG_PREFIX + node.id + "'s drawing is unreadable: " + unreadable.getMessage());
                }
                loaded.add(new SculptPass(node.id, upstream, svgPath, svg,
                        node.arguments.get(StrokeSculptNode.SYMMETRY.name) instanceof String axis ? axis
                                : StrokeSculptNode.NO_SYMMETRY, false));
            }
            PythonParser.ParsedNode last = statements.isEmpty() ? null
                    : statements.get(lastPass >= 0 ? lastPass : statements.size() - 1);
            anchor = last == null ? ""
                    : last.id + PORT_SEPARATOR + (lastPass >= 0 ? StrokeSculptNode.GEOMETRY_OUT.name
                            : RingDslWriter.geometryPort(last.type));
            // The text splits at the statement after the last pass; parsed lines are 1-based.
            String[] lines = source.split(LINE_BREAK, -1);
            int split = lastPass >= 0 && lastPass + 1 < statements.size()
                    ? Math.min(lines.length, statements.get(lastPass + 1).line - 1) : lines.length;
            prelude = String.join(LINE_BREAK, Arrays.copyOfRange(lines, 0, split));
            prelude = prelude.endsWith(LINE_BREAK) || prelude.isEmpty() ? prelude : prelude + LINE_BREAK;
            postlude = String.join(LINE_BREAK, Arrays.copyOfRange(lines, split, lines.length));
        }
        passes = List.copyOf(loaded);
        loadedPasses = passes.size();
        lastRow = String.format(Locale.ROOT, "%d pass(es) in %s; drag to draw a %s stroke", loadedPasses,
                documentFile, KINDS[mode]);
        Platforms.get().log(LOG_PREFIX + lastRow);
        if (source == null) {
            showPasses();
        }
    }

    /**
     * The graph as the passes stand: the graph's text through its last pass, each drawn pass's
     * statement, then the rest, which reads the last drawn pass's geometry in place of the anchor's.
     *
     * @return the DSL source a save writes and the scene runs
     */
    public String source() {
        StringBuilder dsl = new StringBuilder(prelude);
        for (int pass = loadedPasses; pass < passes.size(); pass++) {
            dsl.append(passes.get(pass).statement()).append('\n');
        }
        String end = passes.size() > loadedPasses
                ? passes.get(passes.size() - 1).id + PORT_SEPARATOR + StrokeSculptNode.GEOMETRY_OUT.name : anchor;
        return dsl.append(anchor.isEmpty() ? postlude : Pattern.compile("\\b" + Pattern.quote(anchor) + "\\b")
                .matcher(postlude).replaceAll(Matcher.quoteReplacement(end))).toString();
    }

    /**
     * Run {@link #source} in the scene with every drawn pass's SVG text set on its {@code svg} port,
     * so unsaved passes evaluate exactly as their saved files will, keeping the camera still.
     *
     * @return true when the graph ran; {@link #lastError} says why not
     */
    private boolean showPasses() {
        Map<String, Object> drawings = new LinkedHashMap<>();
        for (int pass = loadedPasses; pass < passes.size(); pass++) {
            drawings.put(passes.get(pass).id + PORT_SEPARATOR + StrokeSculptNode.SVG.name, passes.get(pass).svg);
        }
        long start = System.nanoTime();
        try {
            scene.showGraph(source(), drawings);
        } catch (Exception failure) {
            lastError = "the sculpt did not run: " + failure.getMessage();
            Platforms.get().log(LOG_PREFIX + lastError);
            return false;
        } finally {
            shownGraph = scene.graph;
        }
        Platforms.get().log(String.format(Locale.ROOT, LOG_PREFIX + "%d pass(es) ran in %.0f ms, %d vertices",
                passes.size(), (System.nanoTime() - start) / NANOSECONDS_PER_MILLISECOND,
                scene.getMeshVertexCount()));
        return true;
    }

    /**
     * Make a pass list the state, re-run the graph and record the change as one edit; a graph that
     * fails puts the last state back.
     *
     * @param name  the edit's name, as undo and ui-state report it
     * @param after the pass list the edit leaves
     * @return true when the edit is applied
     */
    private boolean applyEdit(String name, List<SculptPass> after) {
        List<SculptPass> before = passes;
        passes = List.copyOf(after);
        if (!showPasses()) {
            String refusal = lastError;
            passes = before;
            showPasses();
            lastError = refusal;
            return false;
        }
        history.push(name, before, passes);
        lastRow = String.format(Locale.ROOT, "%s: %d pass(es), %d to undo; mesh %d vertices", name, passes.size(),
                history.undoDepth, scene.getMeshVertexCount());
        scene.refreshControls();
        return true;
    }

    /**
     * The open drawn pass strokes go on, or {@code null} when the last pass is the graph's own or
     * committed.
     *
     * @return the open pass
     */
    private SculptPass openPass() {
        SculptPass last = passes.size() > loadedPasses ? passes.get(passes.size() - 1) : null;
        return last != null && last.open ? last : null;
    }

    /**
     * Step back over the last stroke, commit or symmetry switch, or with {@code backward} false
     * re-apply the one the last undo stepped back over, re-running the graph.
     *
     * @param backward true to undo, false to redo
     */
    private void stepHistory(boolean backward) {
        lastError = "";
        if (!active || drawing) {
            lastError = drawing ? "a stroke is being drawn - release it first" : NOT_ACTIVE;
            return;
        }
        String what = backward ? history.nextUndoName() : history.nextRedoName();
        List<SculptPass> restored = backward ? history.undo() : history.redo();
        if (restored == null) {
            lastError = backward ? "nothing to undo" : "nothing to redo";
            return;
        }
        passes = restored;
        SculptPass open = openPass();
        symmetric = open == null ? symmetric : !StrokeSculptNode.NO_SYMMETRY.equals(open.symmetry);
        showPasses();
        lastRow = String.format(Locale.ROOT, "%s %s: %d to undo, %d to redo, %d pass(es); mesh %d vertices",
                backward ? "undid" : "redid", what, history.undoDepth, history.redoDepth(), passes.size(),
                scene.getMeshVertexCount());
        Platforms.get().log(LOG_PREFIX + lastRow);
        scene.refreshControls();
    }

    /**
     * Start a stroke at the press; Ctrl or Shift leave the drag to the camera, to orbit or pan.
     *
     * @param x    framebuffer x of the press
     * @param y    framebuffer y of the press, y up
     * @param mods modifier-key bitmask of the press
     * @return true when the press draws
     */
    @Override
    public boolean onPress(float x, float y, int mods) {
        if (!active || (mods & (KeyNames.MOD_SHIFT | KeyNames.MOD_CONTROL)) != 0) {
            return false;
        }
        drawing = true;
        strokePointsDrawn = 0;
        onDrag(x, y);
        return true;
    }

    /**
     * Add the cursor to the stroke being drawn once it has moved a couple of pixels.
     *
     * @param x framebuffer x of the cursor
     * @param y framebuffer y of the cursor, y up
     */
    @Override
    public void onDrag(float x, float y) {
        float fromTop = Platforms.get().getFrameBufferHeight() - y;
        if (!drawing || strokePointsDrawn > 0 && Math.hypot(x - strokePixels[2 * strokePointsDrawn - 2],
                fromTop - strokePixels[2 * strokePointsDrawn - 1]) < MINIMUM_POINT_GAP_PIXELS) {
            return;
        }
        if (2 * strokePointsDrawn + 2 > strokePixels.length) {
            strokePixels = Arrays.copyOf(strokePixels, 2 * strokePixels.length);
        }
        strokePixels[2 * strokePointsDrawn] = x;
        strokePixels[2 * strokePointsDrawn + 1] = fromTop;
        strokePointsDrawn++;
    }

    /**
     * End the stroke being drawn and put it on the open pass, or on a new pass chained after the
     * last, as one edit; its cell records the camera it was drawn through. A click draws nothing.
     *
     * @param x     framebuffer x of the release
     * @param y     framebuffer y of the release, y up
     * @param click true when the cursor stayed within the click threshold of the press
     * @return true when the press was the tool's
     */
    @Override
    public boolean onRelease(float x, float y, boolean click) {
        if (!drawing) {
            return false;
        }
        onDrag(x, y);
        drawing = false;
        lastError = "";
        if (click || strokePointsDrawn < 2) {
            lastRow = "a click draws nothing - drag to draw a " + KINDS[mode] + " stroke";
            strokePointsDrawn = 0;
            return true;
        }
        // Shoelace area of the closed outline: a fill drawn as a line encloses nothing to lift.
        double enclosed = 0.0;
        for (int point = 0, previous = strokePointsDrawn - 1; point < strokePointsDrawn; previous = point++) {
            enclosed += strokePixels[2 * previous] * strokePixels[2 * point + 1]
                    - strokePixels[2 * point] * strokePixels[2 * previous + 1];
        }
        if (StrokeSketch.FILL.equals(KINDS[mode]) && Math.abs(enclosed) / 2 < MINIMUM_FILL_AREA_PIXELS) {
            lastError = String.format(Locale.ROOT, "the fill outline encloses %.0f px - drag a loop round the "
                    + "area to fill", Math.abs(enclosed) / 2);
            strokePointsDrawn = 0;
            return true;
        }
        SculptPass open = openPass();
        List<SculptPass> after = new ArrayList<>(passes);
        if (open == null) {
            Matcher ids = PASS_ID.matcher(source());
            int serial = 0;
            while (ids.find()) {
                serial = Math.max(serial, Integer.parseInt(ids.group(1)));
            }
            String id = PASS_ID_PREFIX + (serial + 1);
            String upstream = passes.size() > loadedPasses
                    ? passes.get(passes.size() - 1).id + PORT_SEPARATOR + StrokeSculptNode.GEOMETRY_OUT.name : anchor;
            // A .dsl under the resources folder names its SVGs as resources, any other one by path.
            String normalized = documentFile.replace('\\', '/');
            String stem = Path.of(documentFile).getFileName().toString().replaceFirst(DSL_EXTENSION, "");
            int resources = normalized.indexOf(RESOURCE_ROOT);
            String folder = resources >= 0
                    ? Path.of(normalized.substring(resources + RESOURCE_ROOT.length())).resolveSibling(stem).toString()
                    : Path.of(documentFile).toAbsolutePath().resolveSibling(stem).toString();
            open = new SculptPass(id, upstream, folder.replace('\\', '/') + "/" + id + SVG_SUFFIX, "",
                    symmetric ? SYMMETRY_AXIS : StrokeSculptNode.NO_SYMMETRY, true);
        } else {
            after.remove(after.size() - 1);
        }
        try {
            after.add(open.withStroke(KINDS[mode], strength, widthPixels,
                    Arrays.copyOf(strokePixels, 2 * strokePointsDrawn), scene.camera, scene.isOrthographic(),
                    Platforms.get().getFrameBufferWidth(), Platforms.get().getFrameBufferHeight()));
            applyEdit(String.format(Locale.ROOT, "%s stroke on %s", KINDS[mode], open.id), after);
        } catch (IllegalArgumentException refused) {
            lastError = refused.getMessage();
        }
        strokePointsDrawn = 0;
        return true;
    }

    /**
     * The tool's rows: the drag, a key per stroke kind, strength and width, commit, symmetry, undo
     * and redo, and the save, which writes each drawn pass's SVG beside the .dsl in a folder named
     * after it, then the .dsl, so reopening the file restores the passes and the mesh.
     *
     * @param controls the scene's control list
     */
    @Override
    public void addControls(List<ControlHint> controls) {
        controls.add(new ControlHint(ControlHint.NO_KEY, "drag", "draw a " + KINDS[mode]
                + " stroke; ctrl+drag orbits, shift+drag pans"));
        for (int kind = 0; kind < KINDS.length; kind++) {
            int picked = kind;
            controls.add(new ControlHint("stroke-" + KINDS[kind], '1' + kind, String.valueOf(kind + 1),
                    "draw " + KINDS[kind] + " strokes" + (kind == mode ? " (drawing)" : ""), () -> {
                        mode = picked;
                        lastRow = "drag to draw a " + KINDS[picked] + " stroke";
                        scene.refreshControls();
                    }));
        }
        for (int step = -1; step <= 1; step += 2) {
            float delta = step * STRENGTH_STEP;
            controls.add(ControlHint.repeating(step < 0 ? "strength-down" : "strength-up",
                    step < 0 ? Keys.MINUS : Keys.EQUAL, step < 0 ? "-" : "=",
                    String.format(Locale.ROOT, "strength %.1f, %s", strength, step < 0 ? "lower" : "raise"), () -> {
                        strength = Math.round(Math.max(-1f, Math.min(1f, strength + delta)) * STRENGTH_STEPS_PER_UNIT)
                                / STRENGTH_STEPS_PER_UNIT;
                        lastRow = String.format(Locale.ROOT, "stroke strength %.1f", strength);
                        scene.refreshControls();
                    }));
        }
        for (int step = -1; step <= 1; step += 2) {
            float factor = step < 0 ? 1f / WIDTH_FACTOR : WIDTH_FACTOR;
            controls.add(ControlHint.repeating(step < 0 ? "width-down" : "width-up",
                    step < 0 ? Keys.COMMA : Keys.PERIOD, step < 0 ? "," : PORT_SEPARATOR,
                    String.format(Locale.ROOT, "width %.0f px, %s", widthPixels, step < 0 ? "narrower" : "wider"),
                    () -> {
                        widthPixels = Math.max(MINIMUM_WIDTH_PIXELS, Math.min(MAXIMUM_WIDTH_PIXELS, widthPixels * factor));
                        lastRow = String.format(Locale.ROOT, "stroke width %.0f px", widthPixels);
                        scene.refreshControls();
                    }));
        }
        controls.add(new ControlHint("commit-pass", Keys.ENTER, "enter", "commit the open pass", () -> {
            lastError = "";
            SculptPass open = openPass();
            if (open == null) {
                lastError = "no open pass to commit - draw a stroke first";
                return;
            }
            List<SculptPass> after = new ArrayList<>(passes);
            after.set(after.size() - 1, new SculptPass(open.id, open.upstream, open.svgPath, open.svg, open.symmetry,
                    false));
            applyEdit("commit pass " + open.id, after);
        }));
        controls.add(new ControlHint("toggle-symmetry", Keys.X, "X", ControlHint.toggled("symmetry across x",
                symmetric) + ", toggle", () -> {
                    lastError = "";
                    symmetric = !symmetric;
                    String axis = symmetric ? SYMMETRY_AXIS : StrokeSculptNode.NO_SYMMETRY;
                    SculptPass open = openPass();
                    if (open == null) {
                        lastRow = ControlHint.toggled("the next pass mirrors across x", symmetric);
                        scene.refreshControls();
                        return;
                    }
                    List<SculptPass> after = new ArrayList<>(passes);
                    after.set(after.size() - 1, new SculptPass(open.id, open.upstream, open.svgPath, open.svg, axis,
                            true));
                    if (!applyEdit("symmetry " + axis + " on " + open.id, after)) {
                        symmetric = !symmetric;
                    }
                }));
        controls.add(new ControlHint(REDO_ACTION, Keys.Z, true, true, "ctrl+shift+Z", REDO_HINT,
                () -> stepHistory(false)));
        controls.add(new ControlHint("undo", Keys.Z, true, "ctrl+Z", "undo stroke or pass", () -> stepHistory(true)));
        controls.add(new ControlHint(REDO_ACTION, Keys.Y, true, "ctrl+Y", REDO_HINT, () -> stepHistory(false)));
        controls.add(new ControlHint("save-sculpt", Keys.S, true, "ctrl+S", "save the .dsl and pass SVGs", () -> {
            lastError = "";
            if (documentFile == null) {
                lastError = NOT_ACTIVE;
                return;
            }
            String source = source();
            Path folder = Path.of(documentFile).toAbsolutePath().resolveSibling(
                    Path.of(documentFile).getFileName().toString().replaceFirst(DSL_EXTENSION, ""));
            try {
                for (int pass = loadedPasses; pass < passes.size(); pass++) {
                    SculptPass drawn = passes.get(pass);
                    Files.createDirectories(folder);
                    Files.write(folder.resolve(drawn.id + SVG_SUFFIX), drawn.svg.getBytes(StandardCharsets.UTF_8));
                }
                RingDslWriter.writeAtomically(Path.of(documentFile), source);
            } catch (IOException | RuntimeException failure) {
                lastError = "could not save to " + documentFile + ": " + failure.getMessage();
                return;
            }
            savedSource = source;
            lastRow = String.format(Locale.ROOT, "saved %d pass(es) to %s, %d drawn here with their SVGs in %s",
                    passes.size(), documentFile, passes.size() - loadedPasses, folder);
            Platforms.get().log(LOG_PREFIX + lastRow);
        }));
    }
}
