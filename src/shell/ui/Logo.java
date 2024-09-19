package shell.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;
import javax.swing.JComponent;

public class Logo extends JComponent {
    BufferedImage img;

    public Logo() {
        this.setBackground(new Color(20, 20, 20));
        this.setSize(150, 150);
        this.setVisible(true);
        this.setMinimumSize(new Dimension(150, 150));
        this.setPreferredSize(new Dimension(150, 150));
        this.setEnabled(true);
        try {
            img = ImageIO.read(new File("./res/decal.png"));
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    @Override
    public void paint(Graphics g) {
        g.drawImage(img, 0, 0, 150, 150,
                null);

    }
}
