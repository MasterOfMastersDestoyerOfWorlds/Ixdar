package shell.terminal;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;
import static org.lwjgl.glfw.GLFW.glfwGetKeyName;

import java.util.HashMap;

import shell.cameras.Camera2D;
import shell.render.Clock;
import shell.render.color.Color;
import shell.render.text.HyperString;
import shell.terminal.commands.MoveAfterCommand;
import shell.terminal.commands.MoveBeforeCommand;
import shell.terminal.commands.MoveCommand;
import shell.terminal.commands.TerminalCommand;
import shell.ui.Drawing;

public class Terminal {
    HyperString history;
    String commandLine;
    String nextLogicalCommand;

    private HyperString cachedInfo;

    public float scrollOffsetY = 0;
    public float SCROLL_SPEED = 300f;
    boolean scrollToCommandLine;

    public static TerminalCommand[] commandList = new TerminalCommand[] {
            new MoveCommand(),
            new MoveAfterCommand(),
            new MoveBeforeCommand(),
    };
    public static HashMap<String, TerminalCommand> commandMap = new HashMap<>();
    static {
        for (TerminalCommand command : commandList) {
            commandMap.put(command.fullName(), command);
            commandMap.put(command.shortName(), command);
        }
    }

    public Terminal() {
        commandLine = "";
        nextLogicalCommand = "";
        scrollToCommandLine = false;
        history = new HyperString();
    }

    public void calculateClick(float normalizedPosX, float normalizedPosY) {

    }

    public void type(int key, int mods) {
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
            if (commandLine.isBlank() && !nextLogicalCommand.isBlank()) {
                commandLine = nextLogicalCommand;
                return;
            }
        }
        String str = glfwGetKeyName(key, mods);
        commandLine += str;
    }

    private void run(String commandLine) {
        String[] args = commandLine.split(" +");

        int remainingArgs = args.length;
        int startIdx = 1;
        if (remainingArgs == 0) {
            return;
        }
        TerminalCommand command = commandMap.get(args[0]);
        remainingArgs--;
        if (remainingArgs == 0) {
            history.addLine("exception: not enough args: " + command.usage(), Color.RED);
            return;
        }
        if (args[1].equals("-h") || args[1].equals("--help")) {
            command.help(history);
            remainingArgs--;
            startIdx++;
            if (remainingArgs == 0) {
                return;
            }
        }
        if (remainingArgs != command.argLength()) {
            history.addLine("exception: not enough args: " + command.usage(), Color.RED);
            return;
        }
        String cmd = command.run(args, startIdx, history);
        if (!cmd.isBlank()) {
            nextLogicalCommand = cmd;
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
}
