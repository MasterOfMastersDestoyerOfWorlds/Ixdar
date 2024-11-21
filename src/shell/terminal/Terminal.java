package shell.terminal;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;
import static org.lwjgl.glfw.GLFW.glfwGetKeyName;

import java.util.ArrayList;

import shell.cameras.Camera2D;
import shell.render.Clock;
import shell.render.color.Color;
import shell.render.text.HyperString;
import shell.terminal.commands.MoveAfterCommand;
import shell.terminal.commands.MoveBeforeCommand;
import shell.terminal.commands.MoveCommand;
import shell.terminal.commands.TerminalCommand;
import shell.ui.Drawing;
import shell.ui.main.Main;

public class Terminal {
    ArrayList<String> history;
    String commandLine;
    String nextLogicalCommand;

    private HyperString cachedInfo;

    public float scrollOffsetY = 0;
    public float SCROLL_SPEED = 300f;

    public static TerminalCommand[] commandList = new TerminalCommand[] {
            new MoveCommand(),
            new MoveAfterCommand(),
            new MoveBeforeCommand(),
    };

    public Terminal() {
        commandLine = "";
        nextLogicalCommand = "";
        history = new ArrayList<>();
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
            history.add(commandLine);
            run(commandLine);
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
        for (TerminalCommand command : commandList) {
            if (args[0].equals(command.shortName()) || args[0].equals(command.fullName())) {
                remainingArgs--;
                if (remainingArgs == 0) {
                    history.add("exception: not enough args: " + command.usage());
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
                    history.add("exception: not enough args: " + command.usage());
                    return;
                }
                String cmd = command.run(args, startIdx, history);
                if (!cmd.isBlank()) {
                    nextLogicalCommand = cmd;
                }

                break;
            }
        }
    }

    public void draw(Camera2D camera) {
        int row = 0;
        float rowHeight = Drawing.FONT_HEIGHT_PIXELS;
        HyperString commandHyperString = new HyperString();
        for (String s : history) {
            commandHyperString.addWord(s, Color.LIGHT_GRAY);
            commandHyperString.newLine();
        }
        commandHyperString.newLine();
        commandHyperString.addWord(commandLine);
        commandHyperString.wrap();
        cachedInfo = commandHyperString;
        Drawing.font.drawHyperStringRows(commandHyperString, row, scrollOffsetY, rowHeight, camera);
    }

    public void scrollTerminal(boolean scrollUp) {
        float menuBottom = cachedInfo.getLastWord().yScreenOffset;
        double d = Clock.deltaTime();
        if (scrollUp) {
            scrollOffsetY -= SCROLL_SPEED * d;
            if (scrollOffsetY < 0) {
                scrollOffsetY = 0;
            }
        } else if (menuBottom < Main.MAIN_VIEW_OFFSET_Y) {
            scrollOffsetY += SCROLL_SPEED * d;
            if (menuBottom > cachedInfo.getLastWord().rowHeight) {
                scrollOffsetY -= SCROLL_SPEED * d;
            }
        }
    }
}
