package ixdar.gui.terminal;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import ixdar.annotations.command.TerminalOption;
import ixdar.annotations.geometry.Geometry;
import ixdar.geometry.point.GeometryMap;
import ixdar.geometry.point.PointCollection;
import ixdar.graphics.cameras.Camera2D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.ColorLerp;
import ixdar.graphics.render.text.HyperString;
import ixdar.gui.terminal.commands.TerminalCommand;
import ixdar.gui.ui.Drawing;
import ixdar.gui.ui.tools.Tool;
import ixdar.platform.file.TextFile;
import ixdar.platform.input.Keys;
import ixdar.platform.input.MouseTrap;
import ixdar.scenes.main.MainScene;

/**
 * The editor's interactive REPL: keeps the command/tool/point-collection registries,
 * tokenises and dispatches input lines through the matching {@link TerminalCommand},
 * and renders the scrollable history plus current prompt into a {@link HyperString}.
 */
public class Terminal implements MouseTrap.ScrollHandler {

    public static ArrayList<TerminalOption> commandList;
    public static HashMap<String, TerminalOption> commandMap = new HashMap<>();
    public static HashMap<Class<? extends TerminalOption>, TerminalOption> commandClassMap = new HashMap<>();
    public static ArrayList<Tool> tools;
    public static HashMap<String, Tool> toolMap = new HashMap<>();
    public static HashMap<Class<Tool>, Tool> toolClassMap = new HashMap<>();
    public static ArrayList<PointCollection> pointCollectionList;
    public static HashMap<Class<? extends Geometry>, PointCollection> pointCollectionClassMap = new HashMap<>();
    static {
        if (commandList == null) {
            commandList = new ArrayList<>();
            for (Supplier<? extends TerminalOption> commandSupplier : CommandMap.MAP.values()) {
                TerminalOption command = commandSupplier.get();
                commandList.add(command);
                commandMap.put(command.fullName(), command);
                commandMap.put(command.shortName(), command);
                commandClassMap.put(command.getClass(), command);
            }
        }
        if (tools == null) {
            tools = new ArrayList<>();
            loadClassType("ixdar.gui.ui.tools", tools, toolClassMap, Tool.class);
            for (Tool t : tools) {
                toolMap.put(t.shortName(), t);
                toolMap.put(t.fullName(), t);
            }
        }
        if (pointCollectionList == null) {
            pointCollectionList = new ArrayList<>();
            for (Supplier<? extends Geometry> commandSupplier : GeometryMap.MAP.values()) {
                Geometry geometry = commandSupplier.get();
                PointCollection command = (PointCollection) commandSupplier.get();
                pointCollectionList.add(command);
                pointCollectionClassMap.put(geometry.getClass(), command);
            }
        }
    }
    public HyperString history;
    public String directory;

    public float scrollOffsetY = 0;
    public float SCROLL_SPEED = 300f;
    public TextFile loadedFile;
    ArrayList<String> commandHistory;
    String storedCommandLine;
    int commandHistoryIdx;
    String commandLine;
    String commandLineInstruct;
    ColorLerp instructColor = ColorLerp.flashColor(Color.BLUE_WHITE, 3);
    String[] nextLogicalCommand;
    boolean scrollToCommandLine;
    private int nextLogicalCommandIdx;
    private HyperString cachedInfo;

    /**
     * Build a terminal bound to {@code file}: its working directory becomes the file's
     * parent path and its history, command line, and command-history index start empty.
     *
     * @param file currently loaded file that anchors this terminal's directory context
     */
    public Terminal(TextFile file) {
        storedCommandLine = "";
        commandLine = "";
        commandLineInstruct = "";
        nextLogicalCommand = new String[] {};
        nextLogicalCommandIdx = 0;
        scrollToCommandLine = false;
        this.directory = new File(file.path).getParent();
        this.loadedFile = file;
        history = new HyperString();
        commandHistory = new ArrayList<>();
        commandHistoryIdx = -1;

    }

    /**
     * Reflectively scan {@code packageName} for non-abstract, non-enum classes that extend
     * {@code type}, instantiate each via its no-arg constructor, and register the instances
     * into both {@code list} and {@code classMap}.
     *
     * @param <E> base type implemented by every discovered class
     * @param packageName fully-qualified package to scan (dotted form)
     * @param list output collection to which each new instance is added
     * @param classMap output map keyed by concrete class for class-based lookup
     * @param type marker base class that discovered classes must extend
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static <E> void loadClassType(String packageName, ArrayList<E> list, Map<Class<E>, E> classMap,
            Class<E> type) {
        InputStream stream = ClassLoader.getSystemClassLoader().getResourceAsStream(packageName.replaceAll("[.]", "/"));
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
        List<Class> commandClasses = new ArrayList<>();
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.endsWith(".class")) {
                    Class c = getClass(line, packageName);
                    if (c != null) {
                        commandClasses.add(c);
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        for (Class c : commandClasses) {
            if (!Modifier.isAbstract(c.getModifiers()) && !c.isEnum() && hasSuperClass(c, type)) {
                try {
                    E e = (E) c.getConstructor().newInstance();
                    list.add(e);
                    classMap.put((Class<E>) e.getClass(), e);
                } catch (InstantiationException | IllegalAccessException | IllegalArgumentException
                        | InvocationTargetException | NoSuchMethodException | SecurityException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private static <E> boolean hasSuperClass(Class c, Class<E> type) {
        Class superClass = c.getSuperclass();
        while (superClass != null) {
            if (superClass == type) {
                return true;
            }
            superClass = superClass.getSuperclass();
        }
        return false;
    }

    @SuppressWarnings("rawtypes")
    private static Class getClass(String className, String packageName) {
        try {
            return Class.forName(packageName + "." + className.substring(0, className.lastIndexOf('.')));
        } catch (ClassNotFoundException e) {
            // handle the exception
        }
        return null;
    }

    /**
     * Click hook for the terminal's pane. Currently a no-op — the terminal does not yet
     * react to direct clicks within its drawn region.
     *
     * @param normalizedPosX normalised x coordinate of the click
     * @param normalizedPosY normalised y coordinate of the click
     */
    public void calculateClick(float normalizedPosX, float normalizedPosY) {

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
                commandLine = ixdar.common.utils.Compat.stripTrailing(commandLine);
                int lastSpace = commandLine.lastIndexOf(" ");
                if (lastSpace == -1) {
                    commandLine = "";
                } else {
                    commandLine = ixdar.common.utils.Compat.stripTrailing(commandLine.substring(0, lastSpace));
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
            history.addLine(commandLine);
            if (!ixdar.common.utils.Compat.isBlank(commandLine)) {
                commandHistory.add(0, commandLine);
                commandHistoryIdx = -1;
                run(commandLine);
            }
            scrollToCommandLine = true;
            commandLine = "";
            return;
        }
        if (key == Keys.SPACE) {
            commandLine += " ";
            return;
        }
        if (key == Keys.TAB) {
            if (ixdar.common.utils.Compat.isBlank(commandLine) && nextLogicalCommand.length > 0) {
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
     * @param typedCharacter character text from the OS input event; ignored if blank
     */
    public void type(String typedCharacter) {
        if (ixdar.common.utils.Compat.isBlank(typedCharacter)) {
            return;
        }
        commandHistoryIdx = -1;
        commandLine += typedCharacter;
    }

    /**
     * Tokenise {@code commandLine}, look the leading word up in {@link #commandMap}, optionally
     * print help when the next token is {@code -h}/{@code --help}, validate the argument count,
     * dispatch to the resolved {@link TerminalCommand}, and capture any returned tab-completion
     * suggestions. Errors are reported into {@link #history}.
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

        String[] cmd = ((TerminalCommand) command).run(args, startIdx, this);
        if (cmd != null) {
            nextLogicalCommand = cmd;
            nextLogicalCommandIdx = 0;
        }

    }

    /**
     * Render the terminal: build a {@link HyperString} from {@link #history} plus the current
     * command line (or its instruction placeholder if empty), draw it through the font system,
     * and snap the scroll offset so the prompt is visible whenever {@code scrollToCommandLine}
     * was set.
     *
     * @param camera 2D camera supplying the view transform
     */
    public void draw(Camera2D camera) {
        int row = 0;
        float rowHeight = Drawing.FONT_HEIGHT_PIXELS;
        HyperString commandHyperString = new HyperString();
        commandHyperString.addHyperString(history);
        commandHyperString.newLine();
        if (commandLine.isEmpty()) {
            commandHyperString.addWord(commandLineInstruct, instructColor);
        } else {
            commandHyperString.addWord(commandLine);
        }
        commandHyperString.wrap();
        cachedInfo = commandHyperString;
        Drawing.getDrawing().font.drawHyperStringRows(commandHyperString, row, scrollOffsetY, rowHeight, camera);
        if (scrollToCommandLine) {
            scrollToCommandLine = false;
            scrollOffsetY -= cachedInfo.getLastWord().yScreenOffset;
        }

    }

    /**
     * Adjust {@link #scrollOffsetY} in response to a scroll-wheel tick, clamping at the top
     * (offset never below zero) and at the bottom (when the cached last-word position is on
     * or above the visible row).
     *
     * @param scrollUp {@code true} when scrolling upward (towards earlier history)
     * @param deltaSeconds frame delta used to scale {@link #SCROLL_SPEED}
     */
    @Override
    public void onScroll(boolean scrollUp, double deltaSeconds) {
        float menuBottom = cachedInfo != null ? cachedInfo.getLastWord().yScreenOffset : 0;
        if (scrollUp) {
            scrollOffsetY -= SCROLL_SPEED * deltaSeconds;
            if (scrollOffsetY < 0) {
                scrollOffsetY = 0;
            }
        } else if (menuBottom < 0) {
            scrollOffsetY += SCROLL_SPEED * deltaSeconds;
            if (cachedInfo != null && menuBottom > cachedInfo.getLastWord().rowHeight) {
                scrollOffsetY -= SCROLL_SPEED * deltaSeconds;
            }
        }
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
     * The {@link HyperString} most recently composed by {@link #draw(Camera2D)} (history plus
     * current command line), used by scroll bookkeeping.
     *
     * @return cached snapshot, or {@code null} before the first draw
     */
    public HyperString getCachedInfo() {
        return cachedInfo;
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
        if (tc.argLength() <= 0) {
            tc.run(new String[] {}, 0, MainScene.terminal);
        }
    }
}
