package ixdar.tools.debug;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import ixdar.annotations.tool.ToolAnnotation;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.gui.ui.text.HyperString;
import ixdar.models.ModelChoice;
import ixdar.nodegraph.nodes.data.SceneModelNode;
import ixdar.nodegraph.runtime.GraphNodeContext;
import ixdar.nodegraph.runtime.GraphStepper;
import ixdar.nodegraph.runtime.GraphStepper.Action;
import ixdar.nodegraph.runtime.GraphStepper.Move;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.scenes.model.ModelScene;
import ixdar.tools.ControlHint;
import ixdar.tools.Tool;

/**
 * The graph debugger: runs the scene's graph (the open DSL, else the scene graph over the loaded
 * model) to its first {@code breakpoint}, then steps the rest forwards and back, the scene drawing
 * what each step made through {@link ModelScene#showGraphValue}.
 */
@ToolAnnotation(id = GraphDebugTool.TOOL_NAME)
public final class GraphDebugTool extends Tool {

    public static final String TOOL_NAME = "debug";

    public static final String LOG_PREFIX = "[debug] ";

    public static final String NO_GRAPH_OPEN = "no graph open";

    /** What a key asked for, applied on the render thread by {@link #perFrame}. */
    public enum Command {
        /** Start the scene's graph afresh and run it to its first breakpoint. */
        OPEN,
        /** One step: an inner step at {@link #level} inside a node, else the next statement. */
        NEXT,
        /** One inner step at the finest level of the node being stepped inside. */
        FINEST,
        /** Into a group call or a steppable node; inside a node, one level finer. */
        INTO,
        /** Out one inner level, else out of the node or the group call. */
        OUT,
        /** Back one move, or to just before a failed one. */
        BACK,
        /** On to the next breakpoint. */
        CONTINUE,
        /** On in the finest steps until one fails. */
        UNTIL_FAILURE
    }

    /** Scene whose graph the tool steps and which draws each step. */
    public final ModelScene scene;

    /** The stepped run, or {@code null} before the tool first opens. */
    public GraphStepper stepper;

    /** The inner-step level the right arrow steps at while inside a node, 0 the outermost. */
    public int level;

    /** Keys pressed since the last frame, in order. */
    public final ConcurrentLinkedQueue<Command> pending = new ConcurrentLinkedQueue<>();

    /** The model whose graph {@link #stepper} runs. */
    private ModelChoice opened;

    /**
     * Binds the tool to its scene.
     *
     * @param scene the scene whose graph is stepped
     */
    public GraphDebugTool(ModelScene scene) {
        this.scene = scene;
    }

    @Override
    public String toolName() {
        return TOOL_NAME;
    }

    @Override
    public String desc() {
        return "Run the graph to its breakpoint, then step it node by node, forwards and back";
    }

    /** Opens the graph on first use and whenever the scene has changed model since. */
    @Override
    public void activate() {
        if (stepper == null || opened != scene.currentModel()) {
            pending.add(Command.OPEN);
        }
    }

    /** Keeps the run where it is for the next activation. */
    @Override
    public void deactivate() {
    }

    /**
     * Applies the keys pressed since the last frame, where the GL context is current, then hands
     * the scene what the run is working on: the open node's in-flight value and overlay, else the
     * newest evaluated value it can draw, then any failure.
     */
    @Override
    public void perFrame() {
        for (Command command = pending.poll(); command != null; command = pending.poll()) {
            if (command != Command.OPEN && stepper == null) {
                lastError = NO_GRAPH_OPEN;
                continue;
            }
            switch (command) {
                case OPEN -> {
                    // The open DSL with its overrides, else the scene graph over the loaded model.
                    ModelChoice choice = scene.currentModel();
                    NodeGraphRuntime run;
                    Map<String, Object> overrides;
                    if (choice != null && choice.kind == ModelChoice.Kind.DSL) {
                        run = NodeGraphRuntime.fromResource(choice.path);
                        overrides = scene.modelCollection.overridesFor(choice);
                    } else if (scene.sceneGraph() != null && scene.loadedMesh != null) {
                        // The file's surface, not the drawn one: stepping swaps that for stage outputs.
                        run = new NodeGraphRuntime(scene.sceneGraph());
                        run.graphInputs.put(SceneModelNode.BINDING,
                                GeometryBundle.ofMesh(scene.loadedMesh));
                        overrides = scene.modelCollection.overrides;
                    } else {
                        lastError = "this scene runs no graph over its model";
                        continue;
                    }
                    opened = choice;
                    stepper = new GraphStepper(run, overrides);
                    scene.graph = run;
                    stepper.continueToBreakpoint();
                }
                case NEXT -> stepper.apply(stepper.inner != null ? new Action(Move.INNER, level)
                        : new Action(Move.OVER, 0));
                case FINEST -> {
                    if (stepper.inner == null) {
                        lastError = "not inside a steppable node; down steps into one";
                        continue;
                    }
                    stepper.apply(new Action(Move.INNER, stepper.inner.stepLevels().size() - 1));
                }
                case INTO -> {
                    if (stepper.inner != null) {
                        level = Math.min(level + 1, stepper.inner.stepLevels().size() - 1);
                    } else {
                        stepper.apply(new Action(Move.INTO, 0));
                    }
                }
                case OUT -> {
                    if (stepper.inner != null && level > 0) {
                        level--;
                    } else {
                        stepper.apply(new Action(Move.OUT, 0));
                    }
                }
                case BACK -> stepper.back();
                case CONTINUE -> stepper.continueToBreakpoint();
                case UNTIL_FAILURE -> stepper.runUntilFailure();
                default -> throw new IllegalArgumentException("unknown command " + command);
            }
            if (stepper.inner == null) {
                level = 0;
            }
            if (stepper.inner != null) {
                scene.showGraphValue(stepper.inner.stepShown(), stepper.inner.stepOverlay());
            } else {
                List<GraphNodeContext> records = stepper.runtime.treeRecords();
                boolean shown = false;
                for (int record = records.size() - 1; record >= 0 && !shown; record--) {
                    for (Object value : records.get(record).getOutputsSnapshot().values()) {
                        shown = scene.showGraphValue(value, null);
                        if (shown) {
                            break;
                        }
                    }
                }
            }
            if (command == Command.OPEN) {
                scene.frameLoadedModel();
            }
            if (stepper.failure != null) {
                scene.showGraphFailure(stepper.failure);
            }
            lastRow = "move " + stepper.history.size() + ": " + stepper.lastStep + " | " + position();
            lastError = stepper.failure == null ? "" : String.valueOf(stepper.failure.getMessage());
            Platforms.get().log(LOG_PREFIX + lastRow);
            if (!lastError.isEmpty()) {
                Platforms.get().log(LOG_PREFIX + "failure: " + lastError
                        + "; left goes back to just before it, then step it finer");
            }
        }
    }

    /**
     * Where the run stands: inside which node and at what level right steps, else the next
     * statement.
     *
     * @return one line, or {@link #NO_GRAPH_OPEN} before the tool first opens
     */
    public String position() {
        if (stepper == null) {
            return NO_GRAPH_OPEN;
        }
        GraphNodeContext next = stepper.next();
        if (stepper.inner != null) {
            return "inside " + stepper.innerRecord.path + ", right steps one "
                    + stepper.inner.stepLevels().get(level);
        } else if (next != null) {
            return "next " + next.path + " (" + next.type + ")";
        }
        return stepper.finished ? "end of program" : "end of " + stepper.last.path;
    }

    /**
     * The info pane's section: where the run stands and what the last move did.
     *
     * @return the text
     */
    @Override
    public HyperString info() {
        HyperString info = new HyperString();
        info.addLine(position());
        if (stepper != null) {
            info.addLine(stepper.lastStep);
        }
        return info;
    }

    /**
     * The stepping keys, on the arrows and enter so no scene's letter keys are shadowed: right
     * next, shift+right finest inner step, down into, up out, left back, enter continue,
     * shift+enter until something fails, shift+left restart. Shift rows come first, since a
     * plain row also answers its shifted key.
     *
     * @param controls the scene's control list, after the tool switches
     */
    @Override
    public void addControls(List<ControlHint> controls) {
        controls.add(new ControlHint("debug-finest", Keys.RIGHT, false, true, "finest step",
                () -> pending.add(Command.FINEST)));
        controls.add(new ControlHint("debug-restart", Keys.LEFT, false, true, "restart",
                () -> pending.add(Command.OPEN)));
        controls.add(new ControlHint("debug-until-failure", Keys.ENTER, false, true, "run until failure",
                () -> pending.add(Command.UNTIL_FAILURE)));
        controls.add(new ControlHint("debug-next", Keys.RIGHT, "step", () -> pending.add(Command.NEXT)));
        controls.add(new ControlHint("debug-into", Keys.DOWN, "step into", () -> pending.add(Command.INTO)));
        controls.add(new ControlHint("debug-out", Keys.UP, "step out", () -> pending.add(Command.OUT)));
        controls.add(new ControlHint("debug-back", Keys.LEFT, "step back", () -> pending.add(Command.BACK)));
        controls.add(new ControlHint("debug-continue", Keys.ENTER, "continue to breakpoint",
                () -> pending.add(Command.CONTINUE)));
    }
}
