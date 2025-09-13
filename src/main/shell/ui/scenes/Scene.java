package shell.ui.scenes;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;

import shell.cameras.Bounds;
import shell.cameras.Camera2D;
import shell.render.color.Color;
import shell.render.shaders.ShaderProgram;
import shell.ui.Canvas3D;
import shell.ui.Drawing;
import shell.ui.ShaderCodePane;
import shell.ui.main.Main;
import shell.render.text.HyperString;

public abstract class Scene extends Canvas3D {

    protected Camera2D camera2D;
    protected Map<String, Bounds> webViews;
    protected Bounds leftBounds;
    protected Bounds rightBounds;
    protected boolean showCode;
    protected HyperString showCodeButton;
    protected ShaderCodePane codePane;
    protected float SCROLL_SPEED = 5f;

    public static final String DEFAULT_VIEW_LEFT = "LEFT_RENDER";
    public static final String DEFAULT_VIEW_RIGHT = "RIGHT_CODE";
    public static final String BTN_SHOW_CODE = "Show Code";

    public Scene() {
        super();
    }

    @Override
    public void initGL() throws UnsupportedEncodingException, IOException {
        super.initGL();
        showCode = false;
    }

    protected void initViews(Camera2D camera2D, String leftId, String rightId) {
        this.camera2D = camera2D;
        webViews = new HashMap<>();
        int half = Canvas3D.frameBufferWidth / 2;
        leftBounds = new Bounds(0, 0, Canvas3D.frameBufferWidth, Canvas3D.frameBufferHeight,
                b -> b.update(0, 0, showCode ? Canvas3D.frameBufferWidth / 2 : Canvas3D.frameBufferWidth,
                        Canvas3D.frameBufferHeight));
        rightBounds = new Bounds(half, 0, 0, Canvas3D.frameBufferHeight,
                b -> b.update(
                        Canvas3D.frameBufferWidth / 2,
                        0,
                        showCode ? Canvas3D.frameBufferWidth / 2f : 0f,
                        Canvas3D.frameBufferHeight));
        webViews.put(leftId, leftBounds);
        webViews.put(rightId, rightBounds);
        camera2D.initCamera(webViews, leftId);

        showCodeButton = new HyperString();
        showCodeButton.addWordClick(BTN_SHOW_CODE, Color.CYAN, () -> {
            showCode = !showCode;
            if (showCode) {
                rightBounds.viewWidth = Canvas3D.frameBufferWidth / 2f;
                leftBounds.viewWidth = Canvas3D.frameBufferWidth / 2f;
            } else {
                rightBounds.viewWidth = 0f;
                leftBounds.viewWidth = Canvas3D.frameBufferWidth;
            }
            camera2D.updateView(leftId);
        });
        showCodeButton.draw();
    }

    protected void initCodePane(String title, ShaderProgram shader) {
        codePane = new ShaderCodePane(rightBounds, SCROLL_SPEED, shader, title);
    }

    protected void drawUI(String leftId, String rightId) {
        Drawing.font.drawHyperStringRows(showCodeButton, 0, 0, Drawing.FONT_HEIGHT_PIXELS, camera2D);
        if (rightBounds.viewWidth > 0) {
            camera2D.updateView(rightId);
            codePane.draw(camera2D);
        }
    }
}
