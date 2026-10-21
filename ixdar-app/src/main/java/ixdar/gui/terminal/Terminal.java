package ixdar.gui.terminal;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import ixdar.annotations.command.CommandRegistry_Commands;
import ixdar.annotations.command.Subsystem;
import ixdar.annotations.command.TerminalOption;
import ixdar.annotations.geometry.Geometry;
import ixdar.annotations.geometry.GeometryRegistry_Geometries;

import ixdar.platform.Compat;
import ixdar.geometry.tsp.input.PointCollection;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorLerp;
import ixdar.gui.terminal.commands.TerminalCommand;
import ixdar.gui.ui.PaneTypes;
import ixdar.gui.ui.menu.MenuScrollBox;
import ixdar.gui.ui.text.HyperString;
import ixdar.platform.file.TextFile;
import ixdar.platform.input.Keys;
import ixdar.scenes.Scene;
import ixdar.scenes.main.Toggle;

/**
 * The editor's interactive REPL: keeps the command and point-collection registries,
 * tokenises and dispatches input lines through the matching {@link TerminalCommand},
 * and renders the scrollable history plus current prompt into a {@link HyperString}.
 */
public class Terminal {

    public static final String VIEW_TERMINAL = "TERMINAL";

    public static ArrayList<TerminalOption> commandList;
    public static HashMap<String, TerminalOption> commandMap = new HashMap<>();
    public static HashMap<Class<? extends TerminalOption>, TerminalOption> commandClassMap = new HashMap<>();

    /** The subsystems each registered command acts on, from its {@code @CommandAnnotation}. */
    public static HashMap<TerminalOption, Subsystem[]> commandActs = new HashMap<>();
    public static ArrayList<PointCollection> pointCollectionList;
    public static HashMap<Class<? extends Geometry>, PointCollection> pointCollectionClassMap = new HashMap<>();

    /**
     * The terminal that currently receives keyboard input and argument-less command
     * dispatch. {@code MainScene} sets this to its terminal; the model
     * scenes set it to their own, so {@code Terminal} is no longer hard-wired to MainScene.
     */
    public static Terminal current;
    static {
        if (commandList == null) {
            commandList = new ArrayList<>();
            for (Map.Entry<String, Supplier<? extends TerminalOption>> registered : CommandRegistry_Commands.MAP
                    .entrySet()) {
                TerminalOption command = registered.getValue().get();
                commandActs.put(command, CommandRegistry_Commands.ACTS.get(registered.getKey()));
                commandList.add(command);
                commandMap.put(command.fullName(), command);
                commandMap.put(command.shortName(), command);
                commandClassMap.put(command.getClass(), command);
            }
        }
        if (pointCollectionList == null) {
            pointCollectionList = new ArrayList<>();
            for (Supplier<? extends Geometry> commandSupplier : GeometryRegistry_Geometries.MAP.values()) {
                Geometry geometry = commandSupplier.get();
                PointCollection command = (PointCollection) commandSupplier.get();
                pointCollectionList.add(command);
                pointCollectionClassMap.put(geometry.getClass(), command);
            }
        }
    }
    public HyperString history;
    public String directory;

    public TextFile loadedFile;

    /** The scene this terminal belongs to, which every command typed into it runs against. */
    public final Scene scene;

    /**
     * The box the history and prompt scroll in, holding them as last drawn; the scene lays its
     * bounds out as the terminal's view and subscribes it, and a click in it focuses the terminal.
     */
    public final MenuScrollBox scrollBox;

    ArrayList<String> commandHistory;
    String storedCommandLine;
    int commandHistoryIdx;
    String commandLine;
    String commandLineInstruct;
    ColorLerp instructColor = ColorLerp.flashColor(Color.BLUE_GREY, 3);
    String[] nextLogicalCommand;
    private int nextLogicalCommandIdx;

    /**
     * Build a terminal bound to {@code file}: its working directory becomes the file's
     * parent path and its history, command line, and command-history index start empty.
     *
     * @param file currently loaded file that anchors this terminal's directory context
     * @param scene the scene the terminal belongs to
     */
    public Terminal(TextFile file, Scene scene) {
        this.scene = scene;
        this.scrollBox = new MenuScrollBox(VIEW_TERMINAL, () -> scene.terminalShown(),
                () -> Toggle.setPanelFocus(PaneTypes.Terminal));
        initState();
        this.directory = new File(file.path).getParent();
        this.loadedFile = file;
    }

    /**
     * Build a terminal anchored at a directory rather than a loaded file — used by the
     * model scenes, which have a working directory but no {@code .ix} file. {@link #loadedFile}
     * stays {@code null}.
     *
     * @param directory working directory this terminal's {@code cd}/{@code ls} start from
     * @param scene the scene the terminal belongs to
     */
    public Terminal(String directory, Scene scene) {
        this.scene = scene;
        this.scrollBox = new MenuScrollBox(VIEW_TERMINAL, () -> scene.terminalShown(),
                () -> Toggle.setPanelFocus(PaneTypes.Terminal));
        initState();
        this.directory = directory;
        this.loadedFile = null;
    }

    private void initState() {
        storedCommandLine = "";
        commandLine = "";
        commandLineInstruct = "";
        nextLogicalCommand = new String[] {};
        nextLogicalCommandIdx = 0;
        history = new HyperString();
        commandHistory = new ArrayList<>();
        commandHistoryIdx = -1;
    }

    /**
     * Apply a keyboard event to the active command line: backspace edits (with Ctrl-word-delete),
     * up/down browses {@link #commandHistory}, Enter submits via {@link #run(String)}, Space
     * inserts a literal space, and Tab cycles through the latest {@code nextLogicalCommand}
     * suggestions.
     *
     * @param key key code from {@link Keys}
     * @param mods modifier-key bitmask (currently unused)
     * @param controlMask {@code true} if Ctrl is held (enables word-wise backspace)
     */
    public void keyPress(int key, int mods, boolean controlMask) {
        if (key == Keys.BACKSPACE) {
            if (controlMask) {
                commandLine = Compat.stripTrailing(commandLine);
                int lastSpace = commandLine.lastIndexOf(" ");
                if (lastSpace == -1) {
                    commandLine = "";
                } else {
                    commandLine = Compat.stripTrailing(commandLine.substring(0, lastSpace));
                }
            } else {
                int back = commandLine.length() - 1;
                if (back < 0) {
                    return;
                }
                commandLine = commandLine.substring(0, back);
            }
            return;
        }
        if (key == Keys.BACKSPACE) {
            int nextCommand = commandHistoryIdx + 1;
            if (nextCommand >= commandHistory.size()) {
                return;
            }
            if (commandHistoryIdx == -1) {
                storedCommandLine = commandLine;
            }
            commandHistoryIdx = nextCommand;
            commandLine = commandHistory.get(nextCommand);
            return;
        } else if (key == Keys.DOWN) {
            int prevCommand = commandHistoryIdx - 1;
            if (prevCommand < -1) {
                return;
            }
            if (prevCommand == -1) {
                commandLine = storedCommandLine;
            } else {
                commandLine = commandHistory.get(prevCommand);
            }
            commandHistoryIdx = prevCommand;
            return;
        }
        if (key == Keys.ENTER) {
            String entered = commandLine;
            commandLine = "";
            scrollBox.scrollToEndPending = true;
            history.addLine(entered);
            if (!Compat.isBlank(entered)) {
                commandHistory.add(0, entered);
                commandHistoryIdx = -1;
                run(entered);
            }
            return;
        }
        if (key == Keys.SPACE) {
            commandLine += " ";
            return;
        }
        if (key == Keys.TAB) {
            if (Compat.isBlank(commandLine) && nextLogicalCommand.length > 0) {
                commandLine = nextLogicalCommand[nextLogicalCommandIdx];
                nextLogicalCommandIdx = (nextLogicalCommandIdx + 1) % nextLogicalCommand.length;
                return;
            } else {
                for (int i = 0; i < nextLogicalCommand.length; i++) {
                    if (nextLogicalCommand[i].equals(commandLine)) {
                        nextLogicalCommandIdx = (i + 1) % nextLogicalCommand.length;
                        commandLine = nextLogicalCommand[nextLogicalCommandIdx];
                        nextLogicalCommandIdx = (i + 1) % nextLogicalCommand.length;
                        break;
                    }
                }
            }
        }
    }

    /**
     * Append a typed character to the command line and reset the history-browse index so the
     * next up-arrow starts again from the most recent entry.
     *
     * @param typedCharacter character text from the OS input event; ignored if blank, since a
     *     space arrives as its own {@link #keyPress} and would otherwise be inserted twice
     */
    public void type(String typedCharacter) {
        if (Compat.isBlank(typedCharacter)) {
            return;
        }
        commandHistoryIdx = -1;
        commandLine += typedCharacter;
    }

    /**
     * Dispatch one line to the {@link TerminalCommand} its first word names and keep the
     * tab-completion suggestions it returns. Every failure, a thrown exception included, is
     * printed into {@link #history}.
     *
     * @param commandLine raw user-entered command, with arguments separated by whitespace
     */
    public void run(String commandLine) {
        String[] args = commandLine.split(" +");

        int remainingArgs = args.length;
        int startIdx = 1;
        if (remainingArgs == 0) {
            return;
        }
        TerminalOption command = commandMap.get(args[0]);
        if (command == null) {
            history.addLine("command not found: " + args[0], Color.RED);
            return;
        }
        remainingArgs--;
        if (remainingArgs >= 1 && (args[1].equals("-h") || args[1].equals("--help"))) {
            TerminalCommand.help(this, command);
            remainingArgs--;
            startIdx++;
            if (remainingArgs == 0) {
                return;
            }
        }
        int argLength = command.argLength();
        if (remainingArgs != argLength && argLength >= 0) {
            history.addLine("exception: not enough args: " + command.usage(), Color.RED);
            return;
        }
        String refusal = refusal(command);
        if (refusal != null) {
            error(refusal);
            return;
        }

        String[] cmd;
        try {
            cmd = ((TerminalCommand) command).run(args, startIdx, this);
        } catch (RuntimeException thrown) {
            error(thrown.toString());
            return;
        }
        if (cmd != null) {
            nextLogicalCommand = cmd;
            nextLogicalCommandIdx = 0;
        }
    }

    /**
     * Render the terminal in {@link #scrollBox}: {@link #history} plus the current command line
     * (or its instruction placeholder if empty), scrolled to the prompt after each Enter.
     *
     * @param camera 2D camera whose view is moved to the box
     */
    public void draw(Camera2D camera) {
        HyperString commandHyperString = new HyperString();
        commandHyperString.addHyperString(history);
        commandHyperString.newLine();
        if (commandLine.isEmpty()) {
            commandHyperString.addWord(commandLineInstruct, instructColor);
        } else {
            commandHyperString.addWord(commandLine);
        }
        commandHyperString.wrap();
        scrollBox.text = commandHyperString;
        scrollBox.draw(camera);
    }

    /**
     * Set the instruction placeholder shown in the prompt area when the command line is empty.
     *
     * @param instruction text rendered in {@link #instructColor} as a hint
     */
    public void instruct(String instruction) {
        this.commandLineInstruct = instruction;
    }

    /**
     * Clear any active prompt-area instruction set by {@link #instruct(String)}.
     */
    public void clearInstruct() {
        this.commandLineInstruct = "";
    }

    /**
     * Append an exception line to {@link #history} prefixed with {@code "EXCEPTION: "} and
     * coloured red.
     *
     * @param string error message body
     */
    public void error(String string) {
        this.history.addLine("EXCEPTION: " + string, Color.RED);
    }

    /**
     * Look up the registered instance of {@code cmd} and invoke its {@link TerminalCommand#run}
     * with an empty argument array, but only when the command's declared {@link TerminalCommand#argLength()}
     * is zero or negative.
     *
     * @param <E> command type
     * @param cmd concrete {@link TerminalCommand} class to invoke argument-less
     */
    public static <E extends TerminalCommand> void runNoArgs(Class<E> cmd) {
        TerminalCommand tc = (TerminalCommand) commandClassMap.get(cmd);
        if (current == null || tc.argLength() > 0) {
            return;
        }
        String refusal = current.refusal(tc);
        if (refusal != null) {
            current.error(refusal);
            return;
        }
        tc.run(new String[] {}, 0, current);
    }

    /**
     * Why {@code command} cannot run against this terminal's scene: the first subsystem it acts on
     * that the scene shows no data for. The one place a command is refused for its scene.
     *
     * @param command command about to run
     * @return the refusal, or {@code null} when the scene shows everything the command acts on
     */
    public String refusal(TerminalOption command) {
        for (Subsystem subsystem : commandActs.getOrDefault(command, new Subsystem[0])) {
            if (!subsystem.sceneFree && scene.dataset(subsystem) == null) {
                return command.shortName() + " acts on " + subsystem.noun + "; this scene shows none";
            }
        }
        return null;
    }

    /**
     * The data this terminal's scene shows for {@code subsystem}, typed as the command expects;
     * the terminal has refused any command whose subsystems the scene lacks before it runs.
     *
     * @param <T> the subsystem's data type
     * @param subsystem subsystem the asking command lists in its {@code acts}
     * @return the scene's data for it
     */
    @SuppressWarnings("unchecked")
    public <T> T dataset(Subsystem subsystem) {
        return (T) scene.dataset(subsystem);
    }
}
