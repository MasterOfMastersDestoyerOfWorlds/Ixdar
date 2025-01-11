package shell.terminal.commands;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

import shell.terminal.Terminal;
import shell.ui.Canvas3D;
import shell.ui.IxdarWindow;
import shell.ui.main.Main;

public class ScreenShotCommand extends TerminalCommand {

    public static String cmd = "prtsc";

    @Override
    public String fullName() {
        return "printscreen";
    }

    @Override
    public String shortName() {
        return cmd;
    }

    @Override
    public String desc() {
        return "take a screen shot of the ixdar window";
    }

    @Override
    public String usage() {
        return "usage: prtsc|printscreen";
    }

    @Override
    public int argLength() {
        return 0;
    }

    @Override
    public String[] run(String[] args, int startIdx, Terminal terminal) {
        System.out.println("Printing Screenshot");
        if (Canvas3D.active) {
            int numInFolder = new File("./img").list().length;
            Canvas3D.canvas.printScreen("./img/snap" + numInFolder + ".png");
        } else if (Main.active) {
            BufferedImage img = new BufferedImage((int) IxdarWindow.getWidth(), (int) IxdarWindow.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            Canvas3D.canvas.paintGL();
            int numInFolder = new File("./img").list().length;
            File outputfile = new File("./img/snap" + numInFolder + ".png");
            try {
                ImageIO.write(img, "png", outputfile);
            } catch (IOException ex) {
                System.out.println(ex.getMessage());
            }
        }
        return new String[] { cmd };
    }
}
