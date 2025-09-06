package shell.ui;

import shell.platform.gl.GL;

/**
 * A simple bouncing line scene - standalone implementation
 */
public class BouncingLineScene {

    private float lineX = 0.0f;
    private float lineY = 0.0f;
    private float velocityX = 0.01f;
    private float velocityY = 0.01f;

    public BouncingLineScene() {
        // Simple constructor
    }

    public void render(GL gl, int width, int height) {
        // Update line position
        lineX += velocityX;
        lineY += velocityY;

        // Bounce off edges
        if (lineX > 0.8f || lineX < -0.8f) {
            velocityX = -velocityX;
        }
        if (lineY > 0.8f || lineY < -0.8f) {
            velocityY = -velocityY;
        }

        // Clear the screen with green background to show it's working
        gl.clearColor(0.1f, 0.5f, 0.1f, 1.0f);
        gl.clear(gl.COLOR_BUFFER_BIT());

        // TODO: Add actual line rendering here using OpenGL primitives
        // For now, just the green background with changing intensity based on position
        float intensity = (lineX + 1.0f) * 0.5f; // Convert -1..1 to 0..1
        gl.clearColor(0.1f, intensity * 0.5f + 0.2f, 0.1f, 1.0f);
        gl.clear(gl.COLOR_BUFFER_BIT());
    }
}