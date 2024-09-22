package shell.ui.actions;

import java.awt.Canvas;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.JComponent;

import org.lwjgl.opengl.awt.AWTGLCanvas;

import shell.render.Canvas3D;

public class PrintScreenAction extends AbstractAction {
    Canvas3D canvas;
    JComponent component;

    public PrintScreenAction(Canvas3D canvas2) {
        super("printScreen");
        this.canvas = canvas2;
    }

    public PrintScreenAction(JComponent component) {
        super("printScreen");
        this.component = component;
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        System.out.println("Printing Screenshot");
        if (canvas != null) {
            int numInFolder = new File("./img").list().length;
            canvas.printScreen("./img/snap" + numInFolder + ".png");
            return;
        } else {
            BufferedImage img = new BufferedImage(component.getWidth(), component.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            component.paint(img.getGraphics());
            int numInFolder = new File("./img").list().length;
            File outputfile = new File("./img/snap" + numInFolder + ".png");
            try {
                ImageIO.write(img, "png", outputfile);
            } catch (IOException e) {
                System.out.println(e.getMessage());
            }
            return;
        }
    }
}
