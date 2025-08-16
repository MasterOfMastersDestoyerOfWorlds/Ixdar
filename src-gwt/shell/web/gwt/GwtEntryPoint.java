package shell.web.gwt;

import com.google.gwt.canvas.client.Canvas;
import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.dom.client.Document;
import com.google.gwt.user.client.Window;
import com.google.gwt.animation.client.AnimationScheduler;

public class GwtEntryPoint implements EntryPoint {

    @Override
    public void onModuleLoad() {
        Canvas canvas = Canvas.createIfSupported();
        if (canvas == null) {
            Window.alert("Canvas not supported");
            return;
        }
        canvas.setCoordinateSpaceWidth(Window.getClientWidth());
        canvas.setCoordinateSpaceHeight(Window.getClientHeight());
        canvas.setWidth("100vw");
        canvas.setHeight("100vh");
        canvas.getElement().setId("ixdar-canvas");
        Document.get().getBody().appendChild(canvas.getElement());

        AnimationScheduler.get().requestAnimationFrame(new AnimationScheduler.AnimationCallback() {
            @Override
            public void execute(double timestamp) {
                canvas.getContext2d().setFillStyle("#121212");
                canvas.getContext2d().fillRect(0, 0, canvas.getCoordinateSpaceWidth(),
                        canvas.getCoordinateSpaceHeight());
                AnimationScheduler.get().requestAnimationFrame(this);
            }
        });
    }
}
