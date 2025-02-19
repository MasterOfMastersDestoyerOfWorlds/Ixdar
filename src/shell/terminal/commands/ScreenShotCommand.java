package shell.terminal.commands;

import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glReadPixels;

import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;

import javax.imageio.ImageIO;

import org.lwjgl.system.MemoryUtil;

import shell.terminal.Terminal;
import shell.ui.Canvas3D;

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
        int numInFolder = new File("./img").list().length;
        BufferedImage imageOut = printScreen();
        File outputfile = new File("./img/snap" + numInFolder + ".png");
        try {
            ImageIO.write(imageOut, "png", outputfile);
        } catch (Exception e) {
            System.out.println("ScreenShot() exception: " + e);
        }
        return new String[] { cmd };
    }

    public static BufferedImage printScreen() {
        int width = Canvas3D.frameBufferWidth;
        int height = Canvas3D.frameBufferHeight;
        int[] pixels = new int[width * height];
        int bindex;
        // allocate space for RBG pixels

        ByteBuffer fb = MemoryUtil.memAlloc(width * height * 4);

        // grab a copy of the current frame contents as RGBA
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, fb);
        MemoryUtil.memFree(fb);

        BufferedImage imageIn = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        // convert RGB data in ByteBuffer to integer array
        for (int i = 0; i < pixels.length; i++) {
            bindex = i * 4;
            pixels[i] = ((fb.get(bindex) << 16)) +
                    ((fb.get(bindex + 1) << 8)) +
                    ((fb.get(bindex + 2) << 0));
        }
        // Allocate colored pixel to buffered Image
        imageIn.setRGB(0, 0, width, height, pixels, 0, width);

        // Creating the transformation direction (horizontal)
        AffineTransform at = AffineTransform.getScaleInstance(1, -1);
        at.translate(0, -imageIn.getHeight(null));

        // Applying transformation
        AffineTransformOp opRotated = new AffineTransformOp(at, AffineTransformOp.TYPE_BILINEAR);
        BufferedImage imageOut = opRotated.filter(imageIn, null);
        return imageOut;

    }
}
