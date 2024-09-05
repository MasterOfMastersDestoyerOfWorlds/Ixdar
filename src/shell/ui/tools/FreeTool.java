package shell.ui.tools;

import java.awt.Graphics2D;

import shell.Tool;
import shell.knot.Segment;
import shell.knot.VirtualPoint;
import shell.ui.Camera;

public class FreeTool extends Tool {

    public Segment hover;
    public VirtualPoint hoverKP;
    public VirtualPoint hoverCP;

    @Override
    public void draw(Graphics2D g2, Camera camera, int minLineThickness) {

    }

    @Override
    public void click(Segment s, VirtualPoint kp, VirtualPoint cp) {
    }

}
