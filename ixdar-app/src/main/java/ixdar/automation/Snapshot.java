package ixdar.automation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import ixdar.annotations.command.Subsystem;
import ixdar.annotations.scene.SceneDrawable;
import ixdar.automation.endpoints.mesh.Fingerprint;
import ixdar.automation.endpoints.ui.Screenshot;
import ixdar.automation.endpoints.ui.State;
import ixdar.canvas.CanvasSceneMap;
import ixdar.canvas.IxdarWindow;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.scenes.Scene;
import ixdar.scenes.model.ModelScene;

/**
 * Records each fixture or scene entry headlessly, in one JVM, as {@code DIR/SLUG.json}:
 * {@code {entry, kind, ok, error?, graph?, fingerprint?, state?, screenshot?}}. No value depends on
 * the clock, so two builds run over one list differ only where their behaviour does; {@link #main}
 * documents each field.
 */
public final class Snapshot {

    public static final int SETTLE_FRAMES = 3;

    public static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
            .create();

    public static final String GRAPH_SUFFIX = ".dsl";

    public static final String KIND = "kind";

    public static final String GRAPH = "graph";

    public static final String ENTRY = "entry";

    public static final String DURATION_PLACEHOLDER = "<duration>";

    public static final Pattern DURATION = Pattern.compile("\\b\\d+(\\.\\d+)? ?(ms|s)\\b");

    private static final String OUT_FLAG = "--out";

    private static final String SCREENSHOT_FLAG = "--screenshot";

    private Snapshot() {
    }

    /**
     * Snapshot every entry the arguments list into the output directory, binding no port, then
     * exit 0 when every entry is ok, else 1.
     *
     * @param args {@code --out DIR [--screenshot] ENTRY...}, run in order, where ENTRY is
     *             <ul>
     *             <li>{@code GRAPH.dsl}: a graph resource or file run with no scene, e.g.
     *             {@code dsl/fixtures/twin_cell.dsl};</li>
     *             <li>{@code SCENE}: a scene id opened on its default model, e.g. {@code ixdar};</li>
     *             <li>{@code SCENE=MODEL}: a scene opened on the model {@code -Dixdar.model} would
     *             name, e.g. {@code ring-tool=fixtures/fertility_rings.dsl}.</li>
     *             </ul>
     *             A scene replaces the previous one as the window switches scenes and is painted
     *             {@link #SETTLE_FRAMES} frames before it is read. Each entry writes
     *             {@code DIR/SLUG.json}, SLUG the entry with every character outside
     *             {@code [A-Za-z0-9_-]} made {@code _}, pretty-printed by {@link #PRETTY_GSON}
     *             with these keys in this order:
     *             <ul>
     *             <li>{@code entry}: the argument; {@code kind}: {@code graph} or {@code scene};
     *             {@code ok}; {@code error}: the cause chain, only when not ok;</li>
     *             <li>{@code graph}: {@link Fingerprint#graphFingerprint} of a graph entry, or of
     *             the graph a model scene ran;</li>
     *             <li>{@code fingerprint}: {@link Fingerprint#bundleFingerprint} of the bundle a
     *             scene shows ({@code Scene.dataset(MESH)});</li>
     *             <li>{@code state}: the ui-state route's answer for a scene, less what the clock
     *             sets: {@code timestamp}, {@code framesRendered} and {@code audio.eventLog} are
     *             dropped and every duration in a string ({@code 21 ms}, {@code 1.5s}) reads
     *             {@code <duration>};</li>
     *             <li>{@code screenshot}: {@code {file: SLUG.png, sha256}}, for a scene when
     *             {@code --screenshot} is given; the hash repeats only for a scene that draws
     *             nothing animated (the menus do not), so compare the images themselves.</li>
     *             </ul>
     * @throws IllegalArgumentException when no {@code --out} directory or no entry is given
     * @throws IOException when the output directory or a snapshot file cannot be written
     */
    public static void main(String[] args) throws IOException {
        Path out = null;
        boolean screenshot = false;
        List<String> entries = new ArrayList<>();
        for (int argument = 0; argument < args.length; argument++) {
            if (OUT_FLAG.equals(args[argument]) && argument + 1 < args.length) {
                out = Path.of(args[++argument]).toAbsolutePath();
            } else if (SCREENSHOT_FLAG.equals(args[argument])) {
                screenshot = true;
            } else {
                entries.add(args[argument]);
            }
        }
        if (out == null || entries.isEmpty()) {
            throw new IllegalArgumentException("usage: Snapshot --out DIR [--screenshot] ENTRY...");
        }
        Files.createDirectories(out);
        IxdarWindow.installHeadlessPlatform();
        AutomationRuntime runtime = AutomationRuntime.get();
        runtime.serveHttp = false;
        State state = new State();
        state.runtime = runtime;
        Screenshot capture = new Screenshot();
        capture.runtime = runtime;

        Scene current = null;
        int failures = 0;
        for (String entry : entries) {
            String slug = entry.replaceAll("[^A-Za-z0-9_-]", "_");
            JsonObject snapshot = new JsonObject();
            snapshot.addProperty(ENTRY, entry);
            boolean graphEntry = entry.endsWith(GRAPH_SUFFIX) && !entry.contains("=");
            snapshot.addProperty(KIND, graphEntry ? GRAPH : "scene");
            try {
                if (graphEntry) {
                    snapshot.addProperty(AutomationEndpoint.OK, true);
                    snapshot.add(GRAPH, Fingerprint.graphFingerprint(
                            NodeGraphRuntime.executeResource(entry, Map.of())));
                } else {
                    int equals = entry.indexOf('=');
                    String sceneId = equals < 0 ? entry : entry.substring(0, equals);
                    Supplier<? extends SceneDrawable> supplier = CanvasSceneMap.MAP.get(sceneId);
                    if (supplier == null) {
                        throw new IllegalArgumentException(IxdarWindow.CANVAS3D_NOT_FOUND_FOR + sceneId);
                    }
                    if (equals < 0) {
                        System.clearProperty(ModelScene.COMMON_MODEL_PROPERTY);
                    } else {
                        System.setProperty(ModelScene.COMMON_MODEL_PROPERTY, entry.substring(equals + 1));
                    }
                    IxdarWindow.canvasId = sceneId;
                    Scene next = (Scene) supplier.get();
                    if (current == null) {
                        next.show();
                    } else {
                        current.switchTo(next);
                        current.paintFrame();
                    }
                    current = next;
                    for (int frame = 0; frame < SETTLE_FRAMES; frame++) {
                        current = current.paintFrame();
                    }
                    snapshot.addProperty(AutomationEndpoint.OK, true);
                    if (current instanceof ModelScene model && model.graph != null) {
                        snapshot.add(GRAPH, Fingerprint.graphFingerprint(model.graph));
                    }
                    if (current.dataset(Subsystem.MESH) instanceof GeometryBundle bundle
                            && bundle.mesh() != null) {
                        snapshot.add(Fingerprint.FINGERPRINT, Fingerprint.bundleFingerprint(bundle));
                    }
                    JsonObject shown = state.endpointHandler(new JsonObject());
                    shown.remove("timestamp");
                    shown.remove("framesRendered");
                    shown.getAsJsonObject("audio").remove("eventLog");
                    snapshot.add("state", withoutDurations(shown));
                    if (screenshot) {
                        JsonObject request = new JsonObject();
                        request.addProperty(Screenshot.PATH, out.resolve(slug + ".png").toString());
                        JsonObject written = capture.endpointHandler(request);
                        JsonObject image = new JsonObject();
                        image.addProperty("file", slug + ".png");
                        image.add(Fingerprint.SHA256, written.get(Fingerprint.SHA256));
                        snapshot.add("screenshot", image);
                    }
                }
            } catch (Exception | AssertionError failure) {
                failures++;
                StringBuilder chain = new StringBuilder();
                for (Throwable level = failure; level != null; level = level.getCause()) {
                    chain.append(chain.length() == 0 ? "" : " <- ").append(level);
                }
                snapshot.addProperty(AutomationEndpoint.OK, false);
                snapshot.addProperty(AutomationEndpoint.ERROR, chain.toString());
            }
            Files.write(out.resolve(slug + ".json"),
                    (PRETTY_GSON.toJson(snapshot) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        if (current != null) {
            current.shutdown();
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * A copy of a JSON tree with every duration inside a string, such as the {@code 21 ms} a tool's
     * last row reports, written {@link #DURATION_PLACEHOLDER}: the one clock-set part of a state
     * that is otherwise counts, names and layout.
     *
     * @param element the tree
     * @return the copy; numbers, booleans and nulls are kept as they are
     */
    private static JsonElement withoutDurations(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject copy = new JsonObject();
            for (Map.Entry<String, JsonElement> member : element.getAsJsonObject().entrySet()) {
                copy.add(member.getKey(), withoutDurations(member.getValue()));
            }
            return copy;
        }
        if (element.isJsonArray()) {
            JsonArray copy = new JsonArray();
            for (JsonElement item : element.getAsJsonArray()) {
                copy.add(withoutDurations(item));
            }
            return copy;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return new JsonPrimitive(DURATION.matcher(element.getAsString()).replaceAll(DURATION_PLACEHOLDER));
        }
        return element;
    }
}
