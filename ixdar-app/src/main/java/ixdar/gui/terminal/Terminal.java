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
     * TODO: document {@code Terminal}.
     *
     * @param file TODO: describe
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
     * TODO: document {@code loadClassType}.
     *
     * @param <E> TODO: describe
     * @param packageName TODO: describe
     * @param list TODO: describe
     * @param classMap TODO: describe
     * @param type TODO: describe
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
     * TODO: document {@code calculateClick}.
     *
     * @param normalizedPosX TODO: describe
     * @param normalizedPosY TODO: describe
     */
    public void calculateClick(float normalizedPosX, float normalizedPosY) {

    }

    /**
     * TODO: document {@code keyPress}.
     *
     * @param key TODO: describe
     * @param mods TODO: describe
     * @param controlMask TODO: describe
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
     * TODO: document {@code type}.
     *
     * @param typedCharacter TODO: describe
     */
    public void type(String typedCharacter) {
        if (ixdar.common.utils.Compat.isBlank(typedCharacter)) {
            return;
        }
        commandHistoryIdx = -1;
        commandLine += typedCharacter;
    }

    /**
     * TODO: document {@code run}.
     *
     * @param commandLine TODO: describe
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
     * TODO: document {@code draw}.
     *
     * @param camera TODO: describe
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
     * TODO: document {@code onScroll}.
     *
     * @param scrollUp TODO: describe
     * @param deltaSeconds TODO: describe
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
     * TODO: document {@code instruct}.
     *
     * @param instruction TODO: describe
     */
    public void instruct(String instruction) {
        this.commandLineInstruct = instruction;
    }

    /**
     * TODO: document {@code clearInstruct}.
     */
    public void clearInstruct() {
        this.commandLineInstruct = "";
    }

    /**
     * TODO: document {@code getCachedInfo}.
     *
     * @return TODO: describe
     */
    public HyperString getCachedInfo() {
        return cachedInfo;
    }

    /**
     * TODO: document {@code error}.
     *
     * @param string TODO: describe
     */
    public void error(String string) {
        this.history.addLine("EXCEPTION: " + string, Color.RED);
    }

    /**
     * TODO: document {@code runNoArgs}.
     *
     * @param <E> TODO: describe
     * @param cmd TODO: describe
     */
    public static <E extends TerminalCommand> void runNoArgs(Class<E> cmd) {
        TerminalCommand tc = (TerminalCommand) commandClassMap.get(cmd);
        if (tc.argLength() <= 0) {
            tc.run(new String[] {}, 0, MainScene.terminal);
        }
    }
}
