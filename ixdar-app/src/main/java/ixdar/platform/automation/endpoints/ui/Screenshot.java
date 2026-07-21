package ixdar.platform.automation.endpoints.ui;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Base64;

import javax.imageio.ImageIO;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.platform.Platforms;
import ixdar.platform.automation.AutomationEndpoint;

@AutomationRouteAnnotation(path = "/ui/screenshot", method = APIMethod.POST)
public class Screenshot extends AutomationEndpoint implements AutomationRoute {
    public static final String PATH = "path";
    public static final String INLINE = "inline";
    public static final int NUM_4 = 4;

    @Override
    public JsonObject endpointHandler(JsonObject body)
            throws Exception {
        String outputPath = body.has(PATH)
                ? body.get(PATH).getAsString()
                : "";
        boolean inline = body.has(INLINE) && body.get(INLINE).getAsBoolean();
        return runtime.runOnMainThread(() -> {
            int width = Platforms.get().getFrameBufferWidth();
            int height = Platforms.get().getFrameBufferHeight();
            int[] pixels = Platforms.gl().readPixels(
                    0,
                    0,
                    width,
                    height,
                    Platforms.gl().RGBA(),
                    Platforms.gl().UNSIGNED_BYTE(),
                    width * height * NUM_4);
            BufferedImage image = new BufferedImage(
                    width,
                    height,
                    BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int srcIndex = (height - 1 - y) * width + x;
                    image.setRGB(x, y, pixels[srcIndex]);
                }
            }
            File out;
            if (outputPath == null || outputPath.isBlank()) {
                String filename = "screenshot-" + System.currentTimeMillis() + ".png";
                out = new File("screenshots/automation", filename);
            } else {
                out = new File(outputPath);
                if (!out.isAbsolute()) {
                    out = new File(System.getProperty("user.dir"), outputPath);
                }
            }
            File parent = out.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            ImageIO.write(image, "PNG", out);
            byte[] pngBytes = imageBytes(image);
            JsonObject result = new JsonObject();
            result.addProperty(PATH, out.getAbsolutePath());
            result.addProperty("width", width);
            result.addProperty("height", height);
            result.addProperty("sha256", sha256(pngBytes));
            if (inline) {
                result.addProperty(
                        "base64",
                        Base64.getEncoder().encodeToString(pngBytes));
            }
            result.addProperty("inlineBase64", inline);
            return result;
        });
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName("screenshot")
                .description("Capture a PNG screenshot of the current framebuffer to a file.")
                .paramAliased(PATH, "out", RouteParamType.STRING, false, "",
                        "Output file path; empty writes under screenshots/automation/.", "/tmp/shot.png")
                .param(INLINE, RouteParamType.BOOL, false, "false",
                        "Also return the PNG as base64 in the response.", "true")
                .responseHint("{path, width, height, sha256, inlineBase64, base64?}")
                .build();
    }
}
