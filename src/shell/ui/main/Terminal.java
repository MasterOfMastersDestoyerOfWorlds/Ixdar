package shell.ui.main;

import java.util.ArrayList;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.glfwGetKeyName;

import shell.cameras.Camera2D;
import shell.exceptions.IdDoesNotExistException;
import shell.file.FileManagement;
import shell.render.color.Color;
import shell.render.text.HyperString;
import shell.ui.Drawing;

public class Terminal {
    ArrayList<String> history;
    String commandLine;

    public Terminal() {
        commandLine = "";
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
        String str = glfwGetKeyName(key, mods);
        commandLine += str;
    }

    private void run(String commandLine) {
        String[] args = commandLine.split(" ");
        if (args[0].equals("m") || args[0].equals("move")) {
            if (args[1].equals("-h") || args[1].equals("--help")) {
                history.add("move - used to move a point in the file in between two other points");
                history.add(
                        "usage: m|move [target point to move(int)] [point 1 in destination(int)] [point 2 in destination(int)]");
                history.add("example: m 1 4 5 would move point with id 1 in between points 4 and 5");
                history.add("exception: no point with id exists");
                history.add("exception: arguments are not integers");
            }
        }
        if (args[0].equals("ma") || args[0].equals("move-after")) {
            String usage = "usage: ma|move-after [target point to move(int)] [point 1 in destination(int)]";
            if (args[1].equals("-h") || args[1].equals("--help")) {
                history.add("move-after - used to move a point in the file after another point");
                history.add(usage);
                history.add("example: ma 1 5 would move point with id 1 after 5 and wrap to zero if necessary");
                history.add("exception: no point with id exists");
                history.add("exception: arguments are not integers");
                history.add("exception: not enough arguments");
            }
            try {
                if (args.length != 3) {
                    history.add("exception: not enough args: " + usage);
                }
                int idTarget = Integer.parseInt(args[1]);
                int idDest = Integer.parseInt(args[2]);
                Main.orgShell.moveAfter(idTarget, idDest);
                FileManagement.moveAfter(idTarget, idDest);

            } catch (NumberFormatException e) {
                history.add("exception: arguments are not integers: " + usage);
            } catch (IdDoesNotExistException e) {
                history.add("exception: no point with id " + e.ID + " exists");
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
        Drawing.font.drawHyperStringRows(commandHyperString, row, 0, rowHeight, camera);
    }
}
