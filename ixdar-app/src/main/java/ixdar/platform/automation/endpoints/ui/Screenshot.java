package ixdar.platform.automation.endpoints.ui;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import ixdar.annotations.automation.APIMethod;
import ixdar.platform.Platforms;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Base64;
import javax.imageio.ImageIO;

@AutomationRouteAnnotation(path = "/ui/screenshot", method = APIMethod.POST)
public class Screenshot extends AutomationEndpoint implements AutomationRoute {

    @Override
    public JsonObject endpointHandler(HttpExchange exchange)
            throws Exception {
        JsonObject body = readBodyJson(exchange);
        String outputPath = body.has("path")
                ? body.get("path").getAsString()
                : "";
        boolean inline = body.has("inline") && body.get("inline").getAsBoolean();
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
                    width * height * 4);
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
            result.addProperty("path", out.getAbsolutePath());
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
}
