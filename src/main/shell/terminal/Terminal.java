package shell.terminal;

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

import shell.cameras.Camera2D;
import shell.file.TextFile;
import shell.platform.input.Keys;
import shell.point.PointCollection;
import shell.render.Clock;
import shell.render.color.Color;
import shell.render.color.ColorLerp;
import shell.render.text.HyperString;
import shell.terminal.commands.TerminalCommand;
import shell.ui.Drawing;
import shell.ui.main.Main;
import shell.ui.tools.Tool;

public class Terminal {
    public HyperString history;
    ArrayList<String> commandHistory;
    String storedCommandLine;
    int commandHistoryIdx;
    String commandLine;
    String commandLineInstruct;
    ColorLerp instructColor = ColorLerp.flashColor(Color.BLUE_WHITE, 3);
    String[] nextLogicalCommand;
    private int nextLogicalCommandIdx;
    public String directory;
    private HyperString cachedInfo;

    public float scrollOffsetY = 0;
    public float SCROLL_SPEED = 300f;
    boolean scrollToCommandLine;
    public TextFile loadedFile;

    public static ArrayList<TerminalCommand> commandList;
    public static HashMap<String, TerminalCommand> commandMap = new HashMap<>();
    public static HashMap<Class<TerminalCommand>, TerminalCommand> commandClassMap = new HashMap<>();
    public static ArrayList<Tool> tools;
    public static HashMap<String, Tool> toolMap = new HashMap<>();
    public static HashMap<Class<Tool>, Tool> toolClassMap = new HashMap<>();
    public static ArrayList<PointCollection> pointCollectionList;
    public static HashMap<Class<PointCollection>, PointCollection> pointCollectionClassMap = new HashMap<>();
    static {
        if (commandList == null) {
            commandList = new ArrayList<>();
            loadClassType("shell.terminal.commands", commandList, commandClassMap, TerminalCommand.class);
            for (TerminalCommand command : commandList) {
                commandMap.put(command.fullName(), command);
                commandMap.put(command.shortName(), command);
            }
        }
        if (tools == null) {
            tools = new ArrayList<>();
            loadClassType("shell.ui.tools", tools, toolClassMap, Tool.class);
            for (Tool t : tools) {
                toolMap.put(t.shortName(), t);
                toolMap.put(t.fullName(), t);
            }
        }
        if (pointCollectionList == null) {
            pointCollectionList = new ArrayList<>();
            loadClassType("shell.point", pointCollectionList, pointCollectionClassMap, PointCollection.class);
        }
    }

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

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static <E> void loadClassType(String packageName, ArrayList<E> list, Map<Class<E>, E> classMap,
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

    public void calculateClick(float normalizedPosX, float normalizedPosY) {

    }

    public void keyPress(int key, int mods, boolean controlMask) {
        if (key == Keys.BACKSPACE) {
            if (controlMask) {
                commandLine = shell.utils.Compat.stripTrailing(commandLine);
                int lastSpace = commandLine.lastIndexOf(" ");
                if (lastSpace == -1) {
                    commandLine = "";
                } else {
                    commandLine = shell.utils.Compat.stripTrailing(commandLine.substring(0, lastSpace));
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
            if (!shell.utils.Compat.isBlank(commandLine)) {
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
            if (shell.utils.Compat.isBlank(commandLine) && nextLogicalCommand.length > 0) {
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

    public void type(String typedCharacter) {
        if (shell.utils.Compat.isBlank(typedCharacter)) {
            return;
        }
        commandHistoryIdx = -1;
        commandLine += typedCharacter;
    }

    public void run(String commandLine) {
        String[] args = commandLine.split(" +");

        int remainingArgs = args.length;
        int startIdx = 1;
        if (remainingArgs == 0) {
            return;
        }
        TerminalCommand command = commandMap.get(args[0]);
        if (command == null) {
            history.addLine("command not found: " + args[0], Color.RED);
            return;
        }
        remainingArgs--;
        if (remainingArgs >= 1 && (args[1].equals("-h") || args[1].equals("--help"))) {
            command.help(this);
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

        String[] cmd = command.run(args, startIdx, this);
        if (cmd != null) {
            nextLogicalCommand = cmd;
            nextLogicalCommandIdx = 0;
        }

    }

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
        Drawing.font.drawHyperStringRows(commandHyperString, row, scrollOffsetY, rowHeight, camera);
        if (scrollToCommandLine) {
            scrollToCommandLine = false;
            scrollOffsetY -= cachedInfo.getLastWord().yScreenOffset;
        }

    }

    public void scrollTerminal(boolean scrollUp) {
        float menuBottom = cachedInfo.getLastWord().yScreenOffset;
        double d = Clock.deltaTime();
        if (scrollUp) {
            scrollOffsetY -= SCROLL_SPEED * d;
            if (scrollOffsetY < 0) {
                scrollOffsetY = 0;
            }
        } else if (menuBottom < 0) {
            scrollOffsetY += SCROLL_SPEED * d;
            if (menuBottom > cachedInfo.getLastWord().rowHeight) {
                scrollOffsetY -= SCROLL_SPEED * d;
            }
        }
    }

    public void instruct(String instruction) {
        this.commandLineInstruct = instruction;
    }

    public void clearInstruct() {
        this.commandLineInstruct = "";
    }

    public void error(String string) {
        this.history.addLine("EXCEPTION: " + string, Color.RED);
    }

    public static <E extends TerminalCommand> void runNoArgs(Class<E> cmd) {
        TerminalCommand tc = commandClassMap.get(cmd);
        if (tc.argLength() <= 0) {
            tc.run(new String[] {}, 0, Main.terminal);
        }
    }
}
