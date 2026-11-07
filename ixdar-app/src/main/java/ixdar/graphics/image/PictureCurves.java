package ixdar.graphics.image;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import ixdar.geometry.mesh.data.bundle.CurveGeometry;
import ixdar.geometry.mesh.data.sculpt.StrokeSketch;

/**
 * What is traced from a picture as curves in its plane, pixel units at z = 0, and back: a subject
 * outline with its box, and shading edges and dark regions with their measurements, every curve
 * tagged with its view.
 */
public final class PictureCurves {

    public static final String VIEW = StrokeSketch.VIEW_ATTRIBUTE;

    public static final String BOX_X = "box_x";

    public static final String BOX_Y = "box_y";

    public static final String BOX_WIDTH = "box_width";

    public static final String BOX_HEIGHT = "box_height";

    public static final String PICTURE_WIDTH = "picture_width";

    public static final String PICTURE_HEIGHT = "picture_height";

    public static final String REGION = "region";

    public static final String LINE_WIDTH = "width";

    public static final String CONTRAST = "contrast";

    public static final String RIDGE = "ridge";

    public static final String DARKNESS = "darkness";

    public static final String AREA = "area";

    public static final String BLOB = "blob";

    public static final String LIGHT_X = "light_x";

    public static final String LIGHT_Y = "light_y";

    private PictureCurves() {
    }

    /**
     * A subject's outline as one closed curve carrying its box, its picture's size and its view.
     *
     * @param subject the traced subject
     * @param picture the picture it was traced in
     * @param view    the multiview cell it was taken from, or -1
     * @return the curve
     */
    public static CurveGeometry outline(SubjectOutline subject, PixelImage picture, int view) {
        CurveGeometry loop = CurveGeometry.planarLoop(subject.outline);
        Map<String, float[]> attributes = new TreeMap<>();
        attributes.put(VIEW, new float[] { view });
        attributes.put(BOX_X, new float[] { subject.box[0] });
        attributes.put(BOX_Y, new float[] { subject.box[1] });
        attributes.put(BOX_WIDTH, new float[] { subject.box[2] });
        attributes.put(BOX_HEIGHT, new float[] { subject.box[3] });
        attributes.put(PICTURE_WIDTH, new float[] { picture.width });
        attributes.put(PICTURE_HEIGHT, new float[] { picture.height });
        return new CurveGeometry(loop.positions, loop.curveOffsets, loop.closed, Map.of(), attributes);
    }

    /**
     * One curve of a set as the subject it was traced from.
     *
     * @param curves outlines from {@link #outline}, possibly merged
     * @param curve  which curve
     * @return the subject, its outline drawn closed again
     */
    public static SubjectOutline subject(CurveGeometry curves, int curve) {
        SubjectOutline subject = new SubjectOutline();
        subject.outline = curves.drawnPlanar(curve);
        String[] box = { BOX_X, BOX_Y, BOX_WIDTH, BOX_HEIGHT };
        for (int field = 0; field < box.length; field++) {
            subject.box[field] = (int) value(curves, box[field], curve);
        }
        return subject;
    }

    /**
     * A picture's shading as curves: every edge line open, every dark region closed, each carrying
     * its measurements, the light and the picture's size.
     *
     * @param shading the picture's shading
     * @param view    the multiview cell the picture was taken from, or -1
     * @return the curves, lines first in their order, then regions
     */
    public static CurveGeometry shading(ShadingEdges shading, int view) {
        int lines = shading.lines.size();
        int curves = lines + shading.regions.size();
        int points = 0;
        for (float[] line : shading.lines) {
            points += line.length / CurveGeometry.XY;
        }
        for (float[] region : shading.regions) {
            points += Math.max(0, region.length / CurveGeometry.XY - 1);
        }
        float[] positions = new float[points * CurveGeometry.XYZ];
        int[] offsets = new int[curves + 1];
        boolean[] closed = new boolean[curves];
        String[] names = { VIEW, REGION, LINE_WIDTH, CONTRAST, RIDGE, DARKNESS, AREA, BLOB, LIGHT_X, LIGHT_Y,
            PICTURE_WIDTH, PICTURE_HEIGHT };
        float[][] values = new float[names.length][curves];
        for (int curve = 0; curve < curves; curve++) {
            boolean region = curve >= lines;
            int index = region ? curve - lines : curve;
            CurveGeometry one = region ? CurveGeometry.planarLoop(shading.regions.get(index))
                    : CurveGeometry.planar(shading.lines.get(index), false);
            System.arraycopy(one.positions, 0, positions, offsets[curve] * CurveGeometry.XYZ, one.positions.length);
            offsets[curve + 1] = offsets[curve] + one.pointCount();
            closed[curve] = region;
            float[] row = { view, region ? 1f : 0f, region ? 0f : shading.widthByLine[index],
                region ? 0f : shading.contrastByLine[index], !region && shading.ridgeByLine[index] ? 1f : 0f,
                region ? shading.darknessByRegion[index] : 0f, region ? shading.areaByRegion[index] : 0f,
                region && shading.blobByRegion[index] ? 1f : 0f, shading.light[0], shading.light[1], shading.width,
                shading.height };
            for (int name = 0; name < names.length; name++) {
                values[name][curve] = row[name];
            }
        }
        Map<String, float[]> attributes = new TreeMap<>();
        for (int name = 0; name < names.length; name++) {
            attributes.put(names[name], values[name]);
        }
        return new CurveGeometry(positions, offsets, closed, Map.of(), attributes);
    }

    /**
     * One view's shading back from curves made by {@link #shading}, possibly merged with others'.
     *
     * @param curves shading curves
     * @param view   the view whose curves to take
     * @return the view's lines and regions with their measurements, in their order
     */
    public static ShadingEdges shadingOf(CurveGeometry curves, int view) {
        ShadingEdges shading = new ShadingEdges();
        List<Integer> lines = new ArrayList<>();
        List<Integer> regions = new ArrayList<>();
        for (int curve = 0; curve < curves.curveCount(); curve++) {
            if ((int) value(curves, VIEW, curve) == view) {
                (value(curves, REGION, curve) > 0f ? regions : lines).add(curve);
                shading.light[0] = value(curves, LIGHT_X, curve);
                shading.light[1] = value(curves, LIGHT_Y, curve);
                shading.width = (int) value(curves, PICTURE_WIDTH, curve);
                shading.height = (int) value(curves, PICTURE_HEIGHT, curve);
            }
        }
        shading.widthByLine = new float[lines.size()];
        shading.contrastByLine = new float[lines.size()];
        shading.ridgeByLine = new boolean[lines.size()];
        for (int line = 0; line < lines.size(); line++) {
            shading.lines.add(curves.drawnPlanar(lines.get(line)));
            shading.widthByLine[line] = value(curves, LINE_WIDTH, lines.get(line));
            shading.contrastByLine[line] = value(curves, CONTRAST, lines.get(line));
            shading.ridgeByLine[line] = value(curves, RIDGE, lines.get(line)) > 0f;
        }
        shading.darknessByRegion = new float[regions.size()];
        shading.areaByRegion = new int[regions.size()];
        shading.blobByRegion = new boolean[regions.size()];
        for (int region = 0; region < regions.size(); region++) {
            shading.regions.add(curves.drawnPlanar(regions.get(region)));
            shading.darknessByRegion[region] = value(curves, DARKNESS, regions.get(region));
            shading.areaByRegion[region] = (int) value(curves, AREA, regions.get(region));
            shading.blobByRegion[region] = value(curves, BLOB, regions.get(region)) > 0f;
        }
        return shading;
    }

    /**
     * The distinct views a set of picture curves holds, in the order they first appear.
     *
     * @param curves picture curves
     * @return the views
     */
    public static int[] views(CurveGeometry curves) {
        int[] views = new int[0];
        for (int curve = 0; curve < curves.curveCount(); curve++) {
            int view = (int) value(curves, VIEW, curve);
            if (Arrays.stream(views).noneMatch(seen -> seen == view)) {
                views = Arrays.copyOf(views, views.length + 1);
                views[views.length - 1] = view;
            }
        }
        return views;
    }

    /**
     * A curve attribute's value, 0 when the curves do not carry it.
     *
     * @param curves the curves
     * @param name   the attribute
     * @param curve  which curve
     * @return the value
     */
    public static float value(CurveGeometry curves, String name, int curve) {
        float[] values = curves.curveAttributes.get(name);
        return values == null ? 0f : values[curve];
    }
}
