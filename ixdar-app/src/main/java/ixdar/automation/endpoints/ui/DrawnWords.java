package ixdar.automation.endpoints.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.automation.AutomationEndpoint;
import ixdar.graphics.cameras.Bounds;
import ixdar.gui.ui.actions.Action;
import ixdar.gui.ui.text.HyperString;
import ixdar.gui.ui.text.HyperWord;
import ixdar.input.MouseTrap;
import ixdar.platform.Platforms;

/**
 * The words the last frame's {@link HyperString}s drew, with their rectangles, and the labels they
 * make, which {@code click --label} clicks with no scene knowledge.
 */
@AutomationRouteAnnotation(path = "ui/words", method = APIMethod.POST)
public class DrawnWords extends AutomationEndpoint implements AutomationRoute {
    public static final String WITHIN = "within";
    public static final String WORDS = "words";
    public static final String LABELS = "labels";
    public static final String TEXT = "text";
    public static final String VIEW = "view";
    public static final String CLICKABLE = "clickable";
    public static final String VISIBLE = "visible";
    public static final String CLICK = "click";
    public static final String CANDIDATES = "candidates";
    public static final String FRAMEBUFFER = "framebuffer";
    public static final String WINDOW = "window";
    public static final String X = "x";
    public static final String Y = "y";
    public static final String WIDTH = "width";
    public static final String HEIGHT = "height";
    public static final String ID = "id";
    public static final String LABEL = "label";
    public static final String MATCH = "match";
    public static final String INDEX = "index";
    public static final int NO_INDEX = -1;
    public static final String WHITESPACE_RUN = "\\s+";
    public static final String WHITESPACE = "\\s";

    /**
     * {@code POST /ui/words}: the last frame's drawn words and labels, optionally only those of one
     * view, or, given a label, the pixel {@code click --label} would click for it.
     *
     * @param body JSON body with optional {@code within} (a view id), {@code label} and
     *             {@code index}
     * @throws IOException never thrown directly; declared to satisfy the route contract
     * @return the {@link #drawnText} listing, the {@link #labelTarget} answer for a label, or an
     *         error object when the render thread is stuck
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        String within = body.has(WITHIN) ? body.get(WITHIN).getAsString() : "";
        String label = body.has(LABEL) ? body.get(LABEL).getAsString() : "";
        int index = body.has(INDEX) ? body.get(INDEX).getAsInt() : NO_INDEX;
        try {
            return runtime.runOnMainThread(() -> label.isEmpty() ? drawnText(within)
                    : labelTarget(label, within, index));
        } catch (Exception failure) {
            return failure(String.valueOf(failure.getMessage()));
        }
    }

    /**
     * List the last frame's words and labels, a label being the words of one line sharing a click
     * action. A word is visible when a whole window pixel's click lands on it inside its view.
     *
     * @param within view id to keep words and labels of, or empty for every view
     * @return {@code {ok, window, framebuffer, words:[...], labels:[...]}}
     */
    public static JsonObject drawnText(String within) {
        float windowWidth = Math.max(MouseTrap.MIN_WINDOW_DIMENSION, Platforms.get().getWindowWidth());
        float windowHeight = Math.max(MouseTrap.MIN_WINDOW_DIMENSION, Platforms.get().getWindowHeight());
        float framebufferWidth = Math.max(MouseTrap.MIN_WINDOW_DIMENSION,
                Platforms.get().getFrameBufferWidth());
        float framebufferHeight = Math.max(MouseTrap.MIN_WINDOW_DIMENSION,
                Platforms.get().getFrameBufferHeight());
        List<HyperWord> drawn = new ArrayList<>();
        List<Integer> labelOfWord = new ArrayList<>();
        int labelCount = 0;
        for (HyperString string : MouseTrap.hyperStrings) {
            Action labelAction = null;
            boolean labelOpen = false;
            for (HyperWord word : string.words) {
                if (word.newLine) {
                    labelOpen = false;
                    continue;
                }
                for (HyperWord piece : word.subWords != null ? word.subWords : List.of(word)) {
                    if (!labelOpen || piece.clickAction != labelAction) {
                        labelCount++;
                        labelOpen = true;
                        labelAction = piece.clickAction;
                    }
                    drawn.add(piece);
                    labelOfWord.add(labelCount - 1);
                }
            }
        }
        StringBuilder[] labelText = new StringBuilder[labelCount];
        float[] labelBestArea = new float[labelCount];
        int[] labelClickX = new int[labelCount];
        int[] labelClickY = new int[labelCount];
        float[] labelMinX = new float[labelCount];
        float[] labelMinY = new float[labelCount];
        float[] labelMaxX = new float[labelCount];
        float[] labelMaxY = new float[labelCount];
        HyperWord[] labelFirstWord = new HyperWord[labelCount];
        JsonArray words = new JsonArray();
        for (int index = 0; index < drawn.size(); index++) {
            HyperWord word = drawn.get(index);
            int label = labelOfWord.get(index);
            Bounds view = word.viewBounds;
            float left = Math.max(word.xScreenOffset, view.offsetX);
            float right = Math.min(word.xScreenOffset + word.drawnWidth, view.offsetX + view.viewWidth);
            float bottom = Math.max(word.yScreenOffset, view.offsetY);
            float top = Math.min(word.yScreenOffset + word.rowHeight, view.offsetY + view.viewHeight);
            int clickX = Math.round((left + right) / 2 * windowWidth / framebufferWidth);
            int clickY = Math.round((1 - (bottom + top) / 2 / framebufferHeight) * windowHeight);
            float hitX = clickX * framebufferWidth / windowWidth;
            float hitY = (1 - clickY / windowHeight) * framebufferHeight;
            boolean visible = !word.culled && hitX > left && hitX < right && hitY > bottom && hitY < top;
            float visibleArea = visible ? (right - left) * (top - bottom) : 0;
            if (labelText[label] == null) {
                labelText[label] = new StringBuilder();
                labelFirstWord[label] = word;
                labelMinX[label] = word.xScreenOffset;
                labelMinY[label] = word.yScreenOffset;
                labelMaxX[label] = word.xScreenOffset + word.drawnWidth;
                labelMaxY[label] = word.yScreenOffset + word.rowHeight;
            }
            labelText[label].append(word.charSequence);
            labelMinX[label] = Math.min(labelMinX[label], word.xScreenOffset);
            labelMinY[label] = Math.min(labelMinY[label], word.yScreenOffset);
            labelMaxX[label] = Math.max(labelMaxX[label], word.xScreenOffset + word.drawnWidth);
            labelMaxY[label] = Math.max(labelMaxY[label], word.yScreenOffset + word.rowHeight);
            if (visibleArea > labelBestArea[label]) {
                labelBestArea[label] = visibleArea;
                labelClickX[label] = clickX;
                labelClickY[label] = clickY;
            }
            String text = word.charSequence.toString().strip();
            if (text.isEmpty() || !(within.isEmpty() || within.equals(view.id))) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty(TEXT, text);
            entry.addProperty(VIEW, view.id);
            entry.addProperty(CLICKABLE, word.clickable);
            entry.addProperty("culled", word.culled);
            entry.addProperty(VISIBLE, visible);
            entry.addProperty(LABEL, label);
            entry.add(FRAMEBUFFER, rectangle(word.xScreenOffset, word.yScreenOffset, word.drawnWidth,
                    word.rowHeight));
            entry.add(WINDOW, rectangle(word.xScreenOffset * windowWidth / framebufferWidth,
                    (1 - (word.yScreenOffset + word.rowHeight) / framebufferHeight) * windowHeight,
                    word.drawnWidth * windowWidth / framebufferWidth,
                    word.rowHeight * windowHeight / framebufferHeight));
            words.add(entry);
        }
        JsonArray labels = new JsonArray();
        for (int label = 0; label < labelCount; label++) {
            String text = labelText[label].toString().strip().replaceAll(WHITESPACE_RUN, " ");
            String view = labelFirstWord[label].viewBounds.id;
            if (text.isEmpty() || !(within.isEmpty() || within.equals(view))) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty(ID, label);
            entry.addProperty(TEXT, text);
            entry.addProperty(VIEW, view);
            entry.addProperty(CLICKABLE, labelFirstWord[label].clickable);
            entry.addProperty(VISIBLE, labelBestArea[label] > 0);
            entry.add(FRAMEBUFFER, rectangle(labelMinX[label], labelMinY[label],
                    labelMaxX[label] - labelMinX[label], labelMaxY[label] - labelMinY[label]));
            if (labelBestArea[label] > 0) {
                JsonObject click = new JsonObject();
                click.addProperty(X, labelClickX[label]);
                click.addProperty(Y, labelClickY[label]);
                entry.add(CLICK, click);
            }
            labels.add(entry);
        }
        JsonObject result = success();
        result.add(WINDOW, rectangle(0, 0, windowWidth, windowHeight));
        result.add(FRAMEBUFFER, rectangle(0, 0, framebufferWidth, framebufferHeight));
        result.add(WORDS, words);
        result.add(LABELS, labels);
        return result;
    }

    /**
     * The window pixel {@code click --label} clicks: that of the one visible clickable label whose
     * text is {@code label}, else contains it ignoring whitespace. Refusals name the candidates.
     *
     * @param label text to find
     * @param within view id to search, or empty for every view
     * @param index which of several visible clickable matches, in drawing order; below zero for none
     * @return {@code {ok, x, y, label, view, match}} in window pixels, or {@code {ok: false, error,
     *         candidates}}
     */
    public static JsonObject labelTarget(String label, String within, int index) {
        JsonArray labels = drawnText(within).getAsJsonArray(LABELS);
        String wanted = label.strip().replaceAll(WHITESPACE_RUN, " ");
        String squeezed = wanted.replaceAll(WHITESPACE, "");
        JsonArray matches = new JsonArray();
        String match = "exact";
        for (JsonElement element : labels) {
            if (element.getAsJsonObject().get(TEXT).getAsString().equals(wanted)) {
                matches.add(element);
            }
        }
        if (matches.isEmpty()) {
            match = "contains";
            for (JsonElement element : labels) {
                if (element.getAsJsonObject().get(TEXT).getAsString().replaceAll(WHITESPACE, "")
                        .contains(squeezed)) {
                    matches.add(element);
                }
            }
        }
        JsonArray clickable = new JsonArray();
        JsonArray targets = new JsonArray();
        for (JsonElement element : matches) {
            JsonObject candidate = element.getAsJsonObject();
            if (candidate.get(CLICKABLE).getAsBoolean()) {
                clickable.add(candidate);
                if (candidate.get(VISIBLE).getAsBoolean()) {
                    targets.add(candidate);
                }
            }
        }
        JsonObject refusal;
        if (matches.isEmpty()) {
            refusal = failure("no drawn text matches \"" + wanted + "\"");
            JsonArray shown = new JsonArray();
            for (JsonElement element : labels) {
                JsonObject candidate = element.getAsJsonObject();
                if (candidate.get(CLICKABLE).getAsBoolean() && candidate.get(VISIBLE).getAsBoolean()) {
                    shown.add(candidate);
                }
            }
            refusal.add(CANDIDATES, shown);
        } else if (clickable.isEmpty()) {
            refusal = failure("\"" + wanted + "\" is drawn but a click on it does nothing");
            refusal.add(CANDIDATES, matches);
        } else if (targets.isEmpty()) {
            refusal = failure("\"" + wanted + "\" is scrolled or clipped out of its view");
            refusal.add(CANDIDATES, clickable);
        } else if (index >= targets.size() || (index < 0 && targets.size() > 1)) {
            refusal = failure("\"" + wanted + "\" matches " + targets.size()
                    + " visible clickable labels; pass index (0 to " + (targets.size() - 1)
                    + ") or within a view");
            refusal.add(CANDIDATES, targets);
        } else {
            JsonObject target = targets.get(Math.max(index, 0)).getAsJsonObject();
            JsonObject result = success();
            result.addProperty(X, target.getAsJsonObject(CLICK).get(X).getAsInt());
            result.addProperty(Y, target.getAsJsonObject(CLICK).get(Y).getAsInt());
            result.addProperty(LABEL, target.get(TEXT).getAsString());
            result.addProperty(VIEW, target.get(VIEW).getAsString());
            result.addProperty(MATCH, match);
            return result;
        }
        return refusal;
    }

    private static JsonObject rectangle(float x, float y, float width, float height) {
        JsonObject rectangle = new JsonObject();
        rectangle.addProperty(X, x);
        rectangle.addProperty(Y, y);
        rectangle.addProperty(WIDTH, width);
        rectangle.addProperty(HEIGHT, height);
        return rectangle;
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName(WORDS)
                .description("List the words the last frame drew and the labels they make: text, "
                        + "rectangle in framebuffer and window pixels, clickable, visible, culled "
                        + "and owning view; a visible label's click pixel is what click --label clicks.")
                .param(WITHIN, RouteParamType.STRING, false, "",
                        "Only the words and labels drawn in this view id.", "SCENE_MENU_MODELS")
                .param(LABEL, RouteParamType.STRING, false, "",
                        "Instead of the listing, the label click --label would click for this text.",
                        "Recompute")
                .param(INDEX, RouteParamType.INT, false, String.valueOf(NO_INDEX),
                        "Which of several visible clickable matches the label picks, in drawing order.",
                        "1")
                .responseHint("{ok, window, framebuffer, words:[{text, view, clickable, culled, "
                        + "visible, label, framebuffer:{x,y,width,height}, window:{x,y,width,height}}], "
                        + "labels:[{id, text, view, clickable, visible, framebuffer, click:{x,y}}]}; "
                        + "framebuffer rectangles are y up, window ones y down; with label "
                        + "{ok, x, y, label, view, match} or {ok:false, error, candidates}")
                .build();
    }
}
