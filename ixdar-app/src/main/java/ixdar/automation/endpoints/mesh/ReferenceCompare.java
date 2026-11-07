package ixdar.automation.endpoints.mesh;

import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.automation.AutomationEndpoint;
import ixdar.automation.AutomationPortFile;
import ixdar.automation.endpoints.ui.MultiviewScreenshot;
import ixdar.graphics.image.PixelImage;
import ixdar.graphics.image.PngWriter;
import ixdar.graphics.image.ReferenceOutlines;
import ixdar.graphics.image.SubjectOutline;
import ixdar.nodegraph.nodes.picture.TraceOutlineNode;

/**
 * Puts each multiview cell beside the reference picture of its view, the picture scaled and
 * centred so its subject matches the cell's, then the two blended half and half: one row per
 * picture, for comparing a sculpt with its references.
 */
@AutomationRouteAnnotation(path = "/mesh/reference-compare", method = APIMethod.POST)
public class ReferenceCompare extends AutomationEndpoint implements AutomationRoute {

    public static final String CAPTURE = "capture";

    public static final String REFERENCE = "reference";

    public static final String OUT = "out";

    public static final String PANEL = "panel";

    public static final String TOLERANCE = "tolerance";

    public static final int DEFAULT_PANEL = 640;

    public static final int PANELS = 3;

    public static final int COLUMNS = 4;

    public static final int CHANNEL_SHIFTS = 3;

    public static final int CAPTURE_TOLERANCE = 24;

    /**
     * {@code POST /mesh/reference-compare}: one row per picture, its cell, the picture placed on the
     * cell's subject, and their blend.
     *
     * @param body the reference-compare flags, {@code reference} a list of pictures
     * @return {@code {ok, out, rows:[{picture, view, cell, scale}]}}, or a failure naming the bad input
     * @throws Exception when a file cannot be read or written
     */
    @Override
    public JsonObject endpointHandler(JsonObject body) throws Exception {
        Path root = AutomationPortFile.checkoutRoot();
        PixelImage capture = SubjectOutline.read(existingFile("--capture", body.get(CAPTURE).getAsString()));
        int cellSize = capture.width / COLUMNS;
        int panel = body.has(PANEL) ? body.get(PANEL).getAsInt() : DEFAULT_PANEL;
        int tolerance = body.has(TOLERANCE) ? body.get(TOLERANCE).getAsInt() : TraceOutlineNode.DEFAULT_TOLERANCE;
        JsonArray references = body.getAsJsonArray(REFERENCE);
        PixelImage sheet = new PixelImage(panel * PANELS, panel * references.size());
        JsonArray rows = new JsonArray();
        for (int row = 0; row < references.size(); row++) {
            String reference = references.get(row).getAsString();
            int cell = ReferenceOutlines.cellNamed(reference, MultiviewScreenshot.VIEW_LABELS);
            if (cell < 0 || cell / COLUMNS * cellSize >= capture.height) {
                return failure("--reference " + reference + " names no cell of the capture: give picture=cell, or "
                        + "name the picture by its view (ref_01_front.png)");
            }
            PixelImage cellPicture = capture.region(cell % COLUMNS * cellSize, cell / COLUMNS * cellSize, cellSize,
                    cellSize);
            SubjectOutline sculpt = SubjectOutline.of(cellPicture, cellPicture.get(0, 0),
                    CAPTURE_TOLERANCE, 0f);
            String picturePath = ReferenceOutlines.pictureNamed(reference);
            PixelImage picture = SubjectOutline.read(existingFile("--reference", picturePath));
            int background = SubjectOutline.borderMedian(picture);
            SubjectOutline subject = SubjectOutline.of(picture, background, tolerance, 0f);
            if (sculpt == null || subject == null) {
                return failure("no subject stands out in " + (sculpt == null ? "cell " + cell : picturePath));
            }
            // Picture pixels per cell pixel, matching the subjects' heights, their box centres together.
            float scale = subject.box[3] / (float) sculpt.box[3];
            float cellCentreX = sculpt.box[0] + sculpt.box[2] / 2f;
            float cellCentreY = sculpt.box[1] + sculpt.box[3] / 2f;
            float pictureCentreX = subject.box[0] + subject.box[2] / 2f;
            float pictureCentreY = subject.box[1] + subject.box[3] / 2f;
            for (int y = 0; y < panel; y++) {
                for (int x = 0; x < panel; x++) {
                    float cellX = (x + 0.5f) * cellSize / panel;
                    float cellY = (y + 0.5f) * cellSize / panel;
                    int own = cellPicture.get(Math.min(cellSize - 1, (int) cellX), Math.min(cellSize - 1, (int) cellY));
                    int pictureX = Math.round((cellX - cellCentreX) * scale + pictureCentreX);
                    int pictureY = Math.round((cellY - cellCentreY) * scale + pictureCentreY);
                    int placed = pictureX >= 0 && pictureY >= 0 && pictureX < picture.width && pictureY < picture.height
                            ? picture.get(pictureX, pictureY) : background;
                    int blend = PixelImage.OPAQUE;
                    for (int channel = 0; channel < CHANNEL_SHIFTS; channel++) {
                        int shift = channel * PixelImage.CHANNEL_BITS;
                        blend |= ((own >> shift & PixelImage.CHANNEL_MASK) + (placed >> shift & PixelImage.CHANNEL_MASK))
                                / 2 << shift;
                    }
                    sheet.set(x, row * panel + y, own | PixelImage.OPAQUE);
                    sheet.set(panel + x, row * panel + y, placed | PixelImage.OPAQUE);
                    sheet.set(2 * panel + x, row * panel + y, blend);
                }
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("picture", picturePath);
            entry.addProperty("view", MultiviewScreenshot.VIEW_LABELS[cell]);
            entry.addProperty("cell", cell);
            entry.addProperty("scale", Math.round(scale * 1e4) / 1e4);
            rows.add(entry);
        }
        Path outPath = root.resolve(body.get(OUT).getAsString());
        if (outPath.getParent() != null) {
            Files.createDirectories(outPath.getParent());
        }
        PngWriter.write(sheet, outPath.toFile());
        JsonObject result = success();
        result.addProperty(OUT, outPath.toString());
        result.add("rows", rows);
        return result;
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName("reference-compare")
                .description("Put each multiview cell beside the reference picture of its view: one row per picture, "
                        + "the cell, the picture scaled and centred so its subject's height and box centre match the "
                        + "cell's, and the two blended half and half.")
                .param(CAPTURE, RouteParamType.STRING, true, "",
                        "Multiview PNG, 4 cells across (multiview --out).", "tmp/rounds/round05/multiview.png")
                .param(REFERENCE, RouteParamType.STRING_LIST, true, "",
                        "A reference picture, as picture=cell or named by its view (ref_02_front_right.png); repeat "
                                + "per row.", "tmp/references/ref_01_front.png")
                .param(OUT, RouteParamType.STRING, true, "", "PNG written.", "tmp/compare.png")
                .param(PANEL, RouteParamType.INT, false, String.valueOf(DEFAULT_PANEL),
                        "Side of each square panel in pixels.", "640")
                .param(TOLERANCE, RouteParamType.INT, false, String.valueOf(TraceOutlineNode.DEFAULT_TOLERANCE),
                        "Largest summed RGB difference from a picture's border colour still counted as background.",
                        "40")
                .responseHint("{ok, out, rows:[{picture, view, cell, scale}]}")
                .build();
    }
}
