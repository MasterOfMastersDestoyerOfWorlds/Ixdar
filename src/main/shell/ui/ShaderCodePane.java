package shell.ui;

import shell.cameras.Bounds;
import shell.cameras.Camera2D;
import shell.platform.input.MouseTrap;
import shell.render.color.Color;
import shell.render.text.HyperString;
import shell.render.shaders.ShaderProgram;
import shell.render.shaders.ShaderProgram.ShaderType;

/**
 * Renders shader source code into a scrollable pane area using HyperString.
 * Owns its HyperString buffer and a scroll subscription bound.
 */
public class ShaderCodePane implements MouseTrap.ScrollHandler {

    private final Bounds paneBounds;
    private final HyperString codeText;
    private float scrollOffsetY;
    private final float scrollSpeed;

    public ShaderCodePane(Bounds paneBounds, float scrollSpeed) {
        this.paneBounds = paneBounds;
        this.scrollSpeed = scrollSpeed;
        this.codeText = new HyperString();
        loadCode();
        codeText.draw();
        MouseTrap.subscribeScrollRegion(this.paneBounds, this);
    }

    private void loadCode() {
        try {
            ShaderProgram font = ShaderType.Font.shader;
            String vs = font != null ? font.getVertexSource() : "";
            String fs = font != null ? font.getFragmentSource() : "";
            codeText.addLine("// Vertex Shader: font.vs", Color.WHITE);
            for (String ln : vs.split("\n")) {
                codeText.addLine(ln, Color.WHITE);
            }
            codeText.addLine(" ", Color.WHITE);
            codeText.addLine("// Fragment Shader: font.fs", Color.WHITE);
            for (String ln : fs.split("\n")) {
                codeText.addLine(ln, Color.WHITE);
            }
        } catch (Exception e) {
            codeText.addLine("Failed to load shader from program", Color.RED);
        }
    }

    public void draw(Camera2D camera) {
        Drawing.font.drawHyperStringRows(codeText, 0, scrollOffsetY, Drawing.FONT_HEIGHT_PIXELS, camera);
    }

    @Override
    public void onScroll(boolean scrollUp, double deltaSeconds) {
        if (scrollUp) {
            scrollOffsetY -= scrollSpeed * (float) deltaSeconds;
            if (scrollOffsetY < 0) {
                scrollOffsetY = 0;
            }
        } else {
            float bottom = codeText.getLastWord().yScreenOffset;
            if (bottom < 0) {
                scrollOffsetY += scrollSpeed * (float) deltaSeconds;
            }
        }
    }

    public Bounds getBounds() {
        return paneBounds;
    }
}
