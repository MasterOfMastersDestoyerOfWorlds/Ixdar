package ixdar.platform.automation.endpoints.ui;

import java.io.File;
import java.util.Base64;

import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.graphics.image.PixelImage;
import ixdar.graphics.image.PngWriter;
import ixdar.platform.Platforms;
import ixdar.platform.automation.AutomationEndpoint;
import ixdar.platform.automation.AutomationPortFile;

@AutomationRouteAnnotation(path = "/ui/screenshot", method = APIMethod.POST)
public class Screenshot extends AutomationEndpoint implements AutomationRoute {
    public static final String PATH = "path";
    public static final String INLINE = "inline";
    public static final String CROP = "crop";
    public static final String SCALE = "scale";
    public static final int NUM_3 = 3;
    public static final int NUM_4 = 4;

    @Override
    public JsonObject endpointHandler(JsonObject body)
            throws Exception {
        String outputPath = body.has(PATH)
                ? body.get(PATH).getAsString()
                : "";
        boolean inline = body.has(INLINE) && body.get(INLINE).getAsBoolean();
        String crop = body.has(CROP) ? body.get(CROP).getAsString() : "";
        int scale = body.has(SCALE) ? Math.max(1, body.get(SCALE).getAsInt()) : 1;
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
            PixelImage image = new PixelImage(width, height);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int srcIndex = (height - 1 - y) * width + x;
                    image.set(x, y, pixels[srcIndex] | PixelImage.OPAQUE);
                }
            }
            File checkout = AutomationPortFile.checkoutRoot().toFile();
            PixelImage output = cropAndScale(image, crop, scale);
            File out;
            if (outputPath == null || outputPath.isBlank()) {
                String filename = "screenshot-" + System.currentTimeMillis() + ".png";
                out = new File(new File(checkout, "screenshots/automation"), filename);
            } else {
                out = new File(outputPath);
                if (!out.isAbsolute()) {
                    out = new File(checkout, outputPath);
                }
            }
            File parent = out.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            PngWriter.write(output, out);
            byte[] pngBytes = imageBytes(output);
            JsonObject result = new JsonObject();
            result.addProperty(PATH, out.getAbsolutePath());
            result.addProperty("width", output.width);
            result.addProperty("height", output.height);
            result.addProperty("framebufferWidth", width);
            result.addProperty("framebufferHeight", height);
            result.addProperty(SCALE, scale);
            result.addProperty(CROP, crop == null ? "" : crop);
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

    /**
     * Cut the requested region out of a capture and enlarge it nearest-neighbour, so a two-pixel
     * line stays crisp. The region is clamped to the image; a malformed one keeps the whole capture.
     *
     * @param image the full framebuffer capture
     * @param crop comma-separated {@code x,y,width,height}, or blank for the whole capture
     * @param scale integer enlargement factor, at least 1
     * @return the cropped and enlarged image, or {@code image} itself when neither applies
     */
    private PixelImage cropAndScale(PixelImage image, String crop, int scale) {
        PixelImage region = image;
        String[] fields = crop == null ? new String[0] : crop.trim().split("\\s*,\\s*");
        if (fields.length == NUM_4) {
            try {
                region = image.region(
                        Integer.parseInt(fields[0]),
                        Integer.parseInt(fields[1]),
                        Integer.parseInt(fields[2]),
                        Integer.parseInt(fields[NUM_3]));
            } catch (NumberFormatException notNumbers) {
                region = image;
            }
        }
        return region.scaled(scale);
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
                .param(CROP, RouteParamType.STRING, false, "",
                        "Region to keep as x,y,width,height in pixels from the top-left; empty keeps all.",
                        "600,300,120,80")
                .param(SCALE, RouteParamType.INT, false, "1",
                        "Nearest-neighbour enlargement factor applied after cropping.", "4")
                .responseHint("{path, width, height, framebufferWidth, framebufferHeight, scale, crop, "
                        + "sha256, inlineBase64, base64?}")
                .build();
    }
}
