package shell.ui.actions;

import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.JFrame;

public class PrintScreenAction extends AbstractAction {
    JFrame frame;

    public PrintScreenAction(JFrame frame) {
        super("printScreen");
        this.frame = frame;
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        System.out.println("Printing Screenshot");
        BufferedImage img = new BufferedImage(frame.getWidth(), frame.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        frame.paint(img.getGraphics());
        int numInFolder = new File("./img").list().length;
        File outputfile = new File("./img/snap" + numInFolder + ".png");
        try {
            ImageIO.write(img, "png", outputfile);
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }
}
