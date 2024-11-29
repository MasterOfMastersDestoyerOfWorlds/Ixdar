package shell.terminal;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import shell.cameras.Camera2D;
import shell.objects.PointCollection;
import shell.render.Clock;
import shell.render.color.Color;
import shell.render.text.HyperString;
import shell.terminal.commands.TerminalCommand;
import shell.ui.Drawing;

public class Terminal {
    public HyperString history;
    String commandLine;
    String[] nextLogicalCommand;
    private int nextLogicalCommandIdx;
    public String directory;
    private HyperString cachedInfo;

    public float scrollOffsetY = 0;
    public float SCROLL_SPEED = 300f;
    boolean scrollToCommandLine;
    public File loadedFile;

    public static ArrayList<TerminalCommand> commandList;
    public static HashMap<String, TerminalCommand> commandMap = new HashMap<>();
    public static ArrayList<PointCollection> pointCollectionList;

    public Terminal(File loadedFile) {
        commandLine = "";
        nextLogicalCommand = new String[] {};
        nextLogicalCommandIdx = 0;
        scrollToCommandLine = false;
        this.directory = loadedFile.getParent();
        this.loadedFile = loadedFile;
        history = new HyperString();
        if (commandList == null) {
            commandList = new ArrayList<>();
            loadClassType("shell.terminal.commands", commandList, TerminalCommand.class);
            for (TerminalCommand command : commandList) {
                commandMap.put(command.fullName(), command);
                commandMap.put(command.shortName(), command);
            }
        }
        if (pointCollectionList == null) {
            pointCollectionList = new ArrayList<>();
            loadClassType("shell.objects", pointCollectionList, PointCollection.class);
        }

    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private <E> void loadClassType(String packageName, ArrayList<E> list, Class<E> type) {
        InputStream stream = ClassLoader.getSystemClassLoader()
                .getResourceAsStream(packageName.replaceAll("[.]", "/"));
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
        List<Class> commandClasses = reader.lines()
                .filter(line -> line.endsWith(".class"))
                .map(line -> getClass(line, packageName))
                .collect(Collectors.toList());
        for (Class c : commandClasses) {
            Class superClass = c.getSuperclass();
            if (!Modifier.isAbstract(c.getModifiers()) && !c.isEnum() && superClass == type) {
                try {
                    list.add((E) c.getConstructor().newInstance());
                } catch (InstantiationException | IllegalAccessException | IllegalArgumentException
                        | InvocationTargetException | NoSuchMethodException | SecurityException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private Class getClass(String className, String packageName) {
        try {
            return Class.forName(packageName + "."
                    + className.substring(0, className.lastIndexOf('.')));
        } catch (ClassNotFoundException e) {
            // handle the exception
        }
        return null;
    }

    public void calculateClick(float normalizedPosX, float normalizedPosY) {

    }

    public void keyPress(int key, int mods) {
        if (key == GLFW_KEY_BACKSPACE) {
            int back = commandLine.length() - 1;
            if (back < 0) {
                return;
            }
            commandLine = commandLine.substring(0, back);
            return;
        }
        if (key == GLFW_KEY_ENTER) {
            history.addLine(commandLine);
            run(commandLine);
            scrollToCommandLine = true;
            commandLine = "";
            return;
        }
        if (key == GLFW_KEY_SPACE) {
            commandLine += " ";
            return;
        }
        if (key == GLFW_KEY_TAB) {
            if (commandLine.isBlank() && nextLogicalCommand.length > 0) {
                commandLine = nextLogicalCommand[nextLogicalCommandIdx];
                nextLogicalCommandIdx = (nextLogicalCommandIdx + 1) % nextLogicalCommand.length;
                return;
            }
        }
    }

    public void type(String typedCharacter) {
        if (typedCharacter.isBlank()) {
            return;
        }
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
        commandHyperString.addWord(commandLine);
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

    public void error(String string) {
        this.history.addLine("EXCEPTION: " + string, Color.RED);
    }
}
