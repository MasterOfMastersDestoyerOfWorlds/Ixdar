package shell.platform.web;

import org.teavm.jso.JSBody;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.html.HTMLCanvasElement;
import org.teavm.jso.dom.html.HTMLDocument;
import org.teavm.jso.dom.html.HTMLElement;

import shell.platform.Platform;

public class WebPlatform implements Platform {

    private final HTMLCanvasElement canvas;

    public WebPlatform() {
        HTMLDocument document = Window.current().getDocument();
        HTMLCanvasElement cnv = (HTMLCanvasElement) document.getElementById("ixdar-canvas");
        if (cnv == null) {
            cnv = (HTMLCanvasElement) document.createElement("canvas");
            cnv.setId("ixdar-canvas");
            HTMLElement body = document.getBody();
            body.appendChild(cnv);
        }
        this.canvas = cnv;
    }

    @Override
    public void setTitle(String title) {
        setDocTitle(title);
    }

    @JSBody(params = { "t" }, script = "document.title=t;")
    private static native void setDocTitle(String t);

    @Override
    public int getWindowWidth() {
        return canvas.getWidth();
    }

    @Override
    public int getWindowHeight() {
        return canvas.getHeight();
    }

    @Override
    public void requestRepaint() {
        // RAF loop externally drives rendering
    }

    @Override
    public float timeSeconds() {
        return (float) (Window.current().getPerformance().now() / 1000.0);
    }

    @Override
    public void setKeyCallback(KeyCallback callback) {
        /* wired by app-level JS if needed */ }

    @Override
    public void setCharCallback(CharCallback callback) {
        /* not used in web MVP */ }

    @Override
    public void setCursorPosCallback(CursorPosCallback callback) {
        /* attach in launcher if needed */ }

    @Override
    public void setMouseButtonCallback(MouseButtonCallback callback) {
        /* attach in launcher if needed */ }

    @Override
    public void setScrollCallback(ScrollCallback callback) {
        /* attach in launcher if needed */ }
}
