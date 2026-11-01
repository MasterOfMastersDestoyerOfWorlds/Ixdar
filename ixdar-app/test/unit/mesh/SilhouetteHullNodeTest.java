package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import ixdar.automation.endpoints.ui.MultiviewScreenshot;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.ops.MeshAppend;
import ixdar.geometry.mesh.data.ops.MeshVertexOffset;
import ixdar.nodegraph.field.Vector3Value;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.TriangulationRule;
import ixdar.geometry.volume.VoxelGrid;
import ixdar.graphics.cameras.Camera3D;
import ixdar.nodegraph.api.MapNodeContext;
import ixdar.nodegraph.nodes.geometry.VolumeToMeshNode;
import ixdar.nodegraph.nodes.modifier.KeepLargestPartNode;
import ixdar.nodegraph.nodes.modifier.SmoothMeshNode;
import ixdar.nodegraph.nodes.patch.SilhouetteHullNode;

/**
 * silhouette_hull and the volume nodes it chains into: the hull of synthetic outlines whose shape is
 * known keeps those outlines as its silhouettes, volume_to_mesh closes a sphere, keep_largest_part
 * keeps the bigger of two shells and smooth_mesh does not shrink a sphere.
 */
class SilhouetteHullNodeTest {
    private static final int CELL = 256;
    private static final float DISTANCE = 4f;
    private static final float FIELD_OF_VIEW = 45f;
    private static final int OUTLINE_POINTS = 240;
    private static final int[] CELL_OF_VIEW_AXIS = { 1, 4, 0 };
    private static final int COLUMNS = MultiviewScreenshot.GRID_COLUMNS;
    private static final String[] SHAPES = { "pear", "notched", "egg" };
    private static final float[] EGG_RADII = { 0.9f, 1.3f, 0.7f };
    private static final float EGG_LEAN = 0.5f;
    private static final float PEAR_WAIST_DEPTH = 0.3f;
    private static final float PEAR_WAIST_HEIGHT = 0.35f;
    private static final float PEAR_WAIST_HALF_WIDTH = 0.25f;
    private static final float PEAR_TAPER = 0.25f;
    private static final float PEAR_RADIUS = 0.75f;
    private static final float NOTCH_HALF_WIDTH = 0.7f;
    private static final float[] NOTCH_CORNERS = { -1f, -1f, 0.2f, -1f, 0.2f, 0f, 1f, 0f, 1f, 1f, -1f, 1f };
    private static final float HULL_MEAN_PIXELS = 2.5f;
    private static final float HULL_MAXIMUM_PIXELS = 5f;
    private static final float STRICT_OUTSIDE_PIXELS = 3.5f;
    private static final int[] SKULL_RESOLUTIONS = { 32, 40, 48, 64, 96 };
    private static final String SKULL_OUTLINES = "dsl/silhouette_hull_skull/outlines.svg";
    private static final float DISJOINT_SCALE = 0.3f;
    private static final float DISJOINT_SHIFT = 1.2f;
    private static final int SPHERE_LATTICE = 40;
    private static final float SPHERE_RADIUS = 1f;
    private static final float SPHERE_RADIUS_TOLERANCE = 0.02f;
    private static final int SPHERE_EULER_CHARACTERISTIC = 2;
    private static final float LOPSIDED_WIDE = 1.0f;
    private static final float LOPSIDED_NARROW = 0.7f;
    private static final float LOPSIDED_HEIGHT = 1.2f;
    private static final float LOPSIDED_DEPTH = 0.8f;
    private static final float LOPSIDED_TOP_WIDTH = 0.85f;
    private static final float LOPSIDED_TRACED_ASYMMETRY = 0.1f;
    private static final float LOPSIDED_MIRRORED_ASYMMETRY = 0.06f;
    private static final String VIEW_ERROR ="%s along axis %d: mean %.2f px, max %.2f px; ";

    @Test
    void hullFollowsNotchedAndConcaveOutlines() {
        // The pear's waist, the prism's L notch and the leaning egg must come out as their own outlines
        // to about a lattice step (some 3 px here at the default resolution), closed and unfolded.
        StringBuilder report = new StringBuilder();
        boolean within = true;
        for (String shape : SHAPES) {
            float[][] outlines = outlines(shape);
            MeshTopology surface = hullSurface(outlines, 1);
            assertClosedAndUnfolded(surface);
            for (int axis = 0; axis < 3; axis++) {
                float[] error = silhouetteError(surface, outlines[axis], axis);
                report.append(String.format(Locale.ROOT, VIEW_ERROR, shape, axis, error[0], error[1]));
                within &= error[0] <= HULL_MEAN_PIXELS && error[1] <= HULL_MAXIMUM_PIXELS;
            }
        }
        assertTrue(within, report.toString());
    }

    @Test
    void strictHullNeverStandsOutsideAnOutline() {
        // With misses 0 every carved point lies inside every outline; the surface may cut a lattice
        // cell's corner past it, by less than half a step (about 1.5 px).
        float[][] outlines = outlines(SHAPES[1]);
        MeshTopology surface = hullSurface(outlines, 0);
        for (int axis = 0; axis < 3; axis++) {
            float outside = silhouetteError(surface, outlines[axis], axis)[2];
            assertTrue(outside <= STRICT_OUTSIDE_PIXELS, "view along axis " + axis + " stands " + outside + " px out");
        }
    }

    @Test
    void symmetryXEvensOutALopsidedTrace() {
        // MESH-80: the front trace bulges right (+x reaches 1.0, -x only 0.7) while top and side agree
        // on a symmetric body; mirrored, the hull keeps a point by the mean of its own and its mirror
        // image's depth inside the outlines, so it comes out symmetric about x = 0.
        float[][] outlines = new float[3][OUTLINE_POINTS * 3];
        for (int point = 0; point < OUTLINE_POINTS; point++) {
            double angle = 2.0 * Math.PI * point / OUTLINE_POINTS;
            float cosine = (float) Math.cos(angle);
            float sine = (float) Math.sin(angle);
            int at = point * 3;
            outlines[2][at] = cosine * (cosine > 0f ? LOPSIDED_WIDE : LOPSIDED_NARROW);
            outlines[2][at + 1] = LOPSIDED_HEIGHT * sine;
            outlines[0][at + 1] = LOPSIDED_HEIGHT * sine;
            outlines[0][at + 2] = LOPSIDED_DEPTH * cosine;
            outlines[1][at] = LOPSIDED_TOP_WIDTH * cosine;
            outlines[1][at + 2] = LOPSIDED_DEPTH * sine;
        }
        float[] asymmetry = new float[2];
        String[] symmetries = { SilhouetteHullNode.NO_SYMMETRY, SilhouetteHullNode.MIRROR_X };
        for (int variant = 0; variant < symmetries.length; variant++) {
            VoxelGrid volume = new MapNodeContext(new SilhouetteHullNode()).with(SilhouetteHullNode.SVG, template(outlines))
                    .with(SilhouetteHullNode.MISSES, 0).with(SilhouetteHullNode.SYMMETRY, symmetries[variant]).eval()
                    .output(SilhouetteHullNode.VOLUME_OUT, VoxelGrid.class);
            MeshTopology surface = new MapNodeContext(new VolumeToMeshNode()).with(VolumeToMeshNode.VOLUME, volume)
                    .eval().output(VolumeToMeshNode.GEOMETRY_OUT, GeometryBundle.class).mesh();
            Vector3f position = new Vector3f();
            Vector3f other = new Vector3f();
            float worst = 0f;
            for (int index = 0; index < surface.vertexCount(); index++) {
                surface.vertexPosition(surface.vertexIdAt(index), position);
                float nearest = Float.POSITIVE_INFINITY;
                for (int candidate = 0; candidate < surface.vertexCount(); candidate++) {
                    surface.vertexPosition(surface.vertexIdAt(candidate), other);
                    nearest = Math.min(nearest, other.distance(-position.x, position.y, position.z));
                }
                worst = Math.max(worst, nearest);
            }
            asymmetry[variant] = worst;
        }
        assertTrue(asymmetry[0] > LOPSIDED_TRACED_ASYMMETRY, "as traced the hull is lopsided: " + asymmetry[0]);
        assertTrue(asymmetry[1] < LOPSIDED_MIRRORED_ASYMMETRY, "mirrored the hull is symmetric to a lattice step: "
                + asymmetry[1] + " (as traced " + asymmetry[0] + ")");
    }

    @Test
    void hullRefusesViewsThatKeepNoCommonVolume() {
        float[][] outlines = outlines(SHAPES[2]);
        // The front outline shrunk and moved right of anything the top outline allows.
        for (int point = 0; point < OUTLINE_POINTS; point++) {
            outlines[2][point * 3] = outlines[2][point * 3] * DISJOINT_SCALE + DISJOINT_SHIFT;
            outlines[2][point * 3 + 1] *= DISJOINT_SCALE;
        }
        String template = template(outlines);
        assertThrows(IllegalArgumentException.class, () -> new MapNodeContext(new SilhouetteHullNode())
                .with(SilhouetteHullNode.SVG, template).eval());
    }

    @Test
    void skullHullHasNoFoldedFaces() {
        // MESH-77 / PATCH-127: pleats of sliver faces folded back against the surface broke the quad
        // layout at some resolutions; none may come out at any of them.
        StringBuilder report = new StringBuilder();
        int total = 0;
        for (int run = 0; run < SKULL_RESOLUTIONS.length * 2; run++) {
            int resolution = SKULL_RESOLUTIONS[run / 2];
            VoxelGrid volume = new MapNodeContext(new SilhouetteHullNode()).with(SilhouetteHullNode.SVG, SKULL_OUTLINES)
                    .with(SilhouetteHullNode.RESOLUTION, resolution).with(SilhouetteHullNode.MISSES, run % 2).eval()
                    .output(SilhouetteHullNode.VOLUME_OUT, VoxelGrid.class);
            GeometryBundle surface = new MapNodeContext(new VolumeToMeshNode()).with(VolumeToMeshNode.VOLUME, volume)
                    .eval().output(VolumeToMeshNode.GEOMETRY_OUT, GeometryBundle.class);
            GeometryBundle part = new MapNodeContext(new KeepLargestPartNode())
                    .with(KeepLargestPartNode.GEOMETRY, surface).eval()
                    .output(KeepLargestPartNode.GEOMETRY_OUT, GeometryBundle.class);
            MeshTopology smoothed = new MapNodeContext(new SmoothMeshNode()).with(SmoothMeshNode.GEOMETRY, part).eval()
                    .output(SmoothMeshNode.GEOMETRY_OUT, GeometryBundle.class).mesh();
            int[] folded = { foldedFaces(surface.mesh()), foldedFaces(part.mesh()), foldedFaces(smoothed) };
            report.append(String.format(Locale.ROOT, "resolution %d misses %d: %d extracted, %d kept, %d smoothed; ",
                    resolution, run % 2, folded[0], folded[1], folded[2]));
            total += folded[0] + folded[2];
        }
        assertEquals(0, total, report.toString());
    }

    @Test
    void volumeToMeshClosesASphere() {
        MeshTopology sphere = new MapNodeContext(new VolumeToMeshNode()).with(VolumeToMeshNode.VOLUME, sphereVolume())
                .eval().output(VolumeToMeshNode.GEOMETRY_OUT, GeometryBundle.class).mesh();
        assertClosedAndUnfolded(sphere);
        assertTrue(Math.abs(meanRadius(sphere) - SPHERE_RADIUS) <= SPHERE_RADIUS_TOLERANCE, "radius " + meanRadius(sphere));
    }

    @Test
    void keepLargestPartKeepsTheBiggerShell() {
        MeshTopology sphere = new MapNodeContext(new VolumeToMeshNode()).with(VolumeToMeshNode.VOLUME, sphereVolume())
                .eval().output(VolumeToMeshNode.GEOMETRY_OUT, GeometryBundle.class).mesh();
        // A pebble of half the radius, shifted clear of the sphere.
        VoxelGrid small = sphereVolume();
        for (int index = 0; index < small.values.length; index++) {
            small.values[index] -= SPHERE_RADIUS / 2 / small.spacing;
        }
        MeshTopology pebble = new MapNodeContext(new VolumeToMeshNode()).with(VolumeToMeshNode.VOLUME, small)
                .eval().output(VolumeToMeshNode.GEOMETRY_OUT, GeometryBundle.class).mesh();
        MeshTopology both = MeshAppend.join(sphere, MeshVertexOffset.apply(pebble,
                new Vector3Value(3f * SPHERE_RADIUS, 0f, 0f)));
        MeshTopology kept = new MapNodeContext(new KeepLargestPartNode())
                .with(KeepLargestPartNode.GEOMETRY, GeometryBundle.ofMesh(both)).eval()
                .output(KeepLargestPartNode.GEOMETRY_OUT, GeometryBundle.class).mesh();
        assertEquals(sphere.faceCount(), kept.faceCount(), "the bigger shell's faces, and only those");
        assertClosedAndUnfolded(kept);
    }

    @Test
    void smoothMeshDoesNotShrinkASphere() {
        MeshTopology sphere = new MapNodeContext(new VolumeToMeshNode()).with(VolumeToMeshNode.VOLUME, sphereVolume())
                .eval().output(VolumeToMeshNode.GEOMETRY_OUT, GeometryBundle.class).mesh();
        MeshTopology smoothed = new MapNodeContext(new SmoothMeshNode())
                .with(SmoothMeshNode.GEOMETRY, GeometryBundle.ofMesh(sphere)).with(SmoothMeshNode.PASSES, 20).eval()
                .output(SmoothMeshNode.GEOMETRY_OUT, GeometryBundle.class).mesh();
        assertEquals(sphere.vertexCount(), smoothed.vertexCount(), "topology kept");
        assertTrue(Math.abs(meanRadius(smoothed) - meanRadius(sphere)) <= SPHERE_RADIUS_TOLERANCE,
                "radius " + meanRadius(sphere) + " -> " + meanRadius(smoothed));
        assertClosedAndUnfolded(smoothed);
    }

    /**
     * A sphere's signed distance in lattice steps, positive inside, on a lattice about the origin whose
     * outer layer lies outside it.
     *
     * @return the volume
     */
    private static VoxelGrid sphereVolume() {
        float spacing = 3f * SPHERE_RADIUS / SPHERE_LATTICE;
        float half = (SPHERE_LATTICE - 1) * spacing / 2;
        VoxelGrid grid = new VoxelGrid(new int[] { SPHERE_LATTICE, SPHERE_LATTICE, SPHERE_LATTICE },
                new float[] { -half, -half, -half }, spacing);
        Vector3f point = new Vector3f();
        for (int index = 0; index < grid.values.length; index++) {
            grid.values[index] = (SPHERE_RADIUS - grid.position(index, point).length()) / spacing;
        }
        return grid;
    }

    /**
     * Mean distance of a mesh's vertices from the origin.
     *
     * @param mesh the mesh
     * @return the mean radius
     */
    private static float meanRadius(MeshTopology mesh) {
        Vector3f position = new Vector3f();
        float sum = 0f;
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            sum += mesh.vertexPosition(mesh.vertexIdAt(activeVertex), position).length();
        }
        return sum / mesh.vertexCount();
    }

    /**
     * The three orthographic outlines of a pear (revolved about y, with a waist), a prism with an L
     * notch in its side outline, or a leaning egg (an ellipsoid whose z gains lean times height).
     *
     * @param shape "pear", "notched" or "egg"
     * @return outlines indexed by view axis, packed xyz
     */
    private static float[][] outlines(String shape) {
        float[][] outlines = new float[3][OUTLINE_POINTS * 3];
        float widest = 0f;
        float topDepth = (float) Math.hypot(EGG_RADII[2], EGG_LEAN * EGG_RADII[1]);
        for (int point = 0; point < OUTLINE_POINTS; point++) {
            double angle = 2.0 * Math.PI * point / OUTLINE_POINTS;
            float cosine = (float) Math.cos(angle);
            float sine = (float) Math.sin(angle);
            int at = point * 3;
            if (SHAPES[0].equals(shape)) {
                float waist = (float) (1.0 - PEAR_WAIST_DEPTH
                        * Math.exp(-Math.pow((cosine - PEAR_WAIST_HEIGHT) / PEAR_WAIST_HALF_WIDTH, 2)));
                float radius = sine * PEAR_RADIUS * (1f - PEAR_TAPER * cosine) * waist;
                widest = Math.max(widest, Math.abs(radius));
                outlines[0][at + 1] = cosine;
                outlines[0][at + 2] = radius;
                outlines[2][at] = radius;
                outlines[2][at + 1] = cosine;
            } else if (SHAPES[1].equals(shape)) {
                float square = Math.max(Math.abs(cosine), Math.abs(sine));
                outlines[1][at] = NOTCH_HALF_WIDTH * cosine / square;
                outlines[1][at + 2] = sine / square;
                outlines[2][at] = NOTCH_HALF_WIDTH * cosine / square;
                outlines[2][at + 1] = sine / square;
                float along = NOTCH_CORNERS.length / 2f * point / OUTLINE_POINTS;
                int side = (int) along;
                int next = (side + 1) % (NOTCH_CORNERS.length / 2);
                outlines[0][at + 2] = NOTCH_CORNERS[side * 2] + (along - side) * (NOTCH_CORNERS[next * 2]
                        - NOTCH_CORNERS[side * 2]);
                outlines[0][at + 1] = NOTCH_CORNERS[side * 2 + 1] + (along - side) * (NOTCH_CORNERS[next * 2 + 1]
                        - NOTCH_CORNERS[side * 2 + 1]);
            } else {
                outlines[0][at + 1] = EGG_RADII[1] * cosine;
                outlines[0][at + 2] = EGG_RADII[2] * sine + EGG_LEAN * EGG_RADII[1] * cosine;
                outlines[1][at] = EGG_RADII[0] * cosine;
                outlines[1][at + 2] = topDepth * sine;
                outlines[2][at] = EGG_RADII[0] * cosine;
                outlines[2][at + 1] = EGG_RADII[1] * sine;
            }
        }
        for (int point = 0; point < OUTLINE_POINTS && SHAPES[0].equals(shape); point++) {
            double angle = 2.0 * Math.PI * point / OUTLINE_POINTS;
            outlines[1][point * 3] = widest * (float) Math.cos(angle);
            outlines[1][point * 3 + 2] = widest * (float) Math.sin(angle);
        }
        return outlines;
    }

    /**
     * Carve the outlines' hull and run it through volume_to_mesh, keep_largest_part and smooth_mesh,
     * as the skull sample does.
     *
     * @param outlines outlines indexed by view axis, packed xyz
     * @param misses   views a kept point may lie outside
     * @return the hull's surface
     */
    private static MeshTopology hullSurface(float[][] outlines, int misses) {
        VoxelGrid volume = new MapNodeContext(new SilhouetteHullNode()).with(SilhouetteHullNode.SVG, template(outlines))
                .with(SilhouetteHullNode.MISSES, misses).eval().output(SilhouetteHullNode.VOLUME_OUT, VoxelGrid.class);
        GeometryBundle surface = new MapNodeContext(new VolumeToMeshNode()).with(VolumeToMeshNode.VOLUME, volume).eval()
                .output(VolumeToMeshNode.GEOMETRY_OUT, GeometryBundle.class);
        GeometryBundle part = new MapNodeContext(new KeepLargestPartNode()).with(KeepLargestPartNode.GEOMETRY, surface)
                .eval().output(KeepLargestPartNode.GEOMETRY_OUT, GeometryBundle.class);
        return new MapNodeContext(new SmoothMeshNode()).with(SmoothMeshNode.GEOMETRY, part).eval()
                .output(SmoothMeshNode.GEOMETRY_OUT, GeometryBundle.class).mesh();
    }

    /**
     * An orthographic multiview template, as {@code ixdar-cli trace-outlines} writes one, with each
     * outline projected into its view's cell.
     *
     * @param outlines outlines indexed by view axis, packed xyz
     * @return the SVG text
     */
    private static String template(float[][] outlines) {
        StringBuilder views = new StringBuilder();
        for (float[] view : MultiviewScreenshot.VIEW_AZIMUTH_ELEVATION_RADIANS) {
            views.append(views.length() == 0 ? "" : ";").append(String.format(Locale.ROOT, "%.6f %.6f", view[0],
                    view[1]));
        }
        StringBuilder svg = new StringBuilder(String.format(Locale.ROOT,
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\" "
                        + "data-center=\"0 0 0\" data-distance=\"%.4f\" data-fov=\"%.4f\" "
                        + "data-projection=\"orthographic\" data-cell-width=\"%d\" data-cell-height=\"%d\" "
                        + "data-columns=\"%d\" data-views=\"%s\">%n",
                COLUMNS * CELL, 2 * CELL, COLUMNS * CELL, 2 * CELL, DISTANCE, FIELD_OF_VIEW, CELL, CELL, COLUMNS, views));
        float[] pixel = new float[2];
        for (int axis = 0; axis < 3; axis++) {
            int cell = CELL_OF_VIEW_AXIS[axis];
            svg.append("<polyline data-kind=\"silhouette\" fill=\"none\" points=\"");
            for (int point = 0; point < outlines[axis].length / 3; point++) {
                toPixel(axis, outlines[axis][point * 3], outlines[axis][point * 3 + 1], outlines[axis][point * 3 + 2],
                        pixel);
                svg.append(String.format(Locale.ROOT, "%.2f,%.2f ", pixel[0] + cell % COLUMNS * CELL,
                        pixel[1] + cell / COLUMNS * CELL));
            }
            svg.append("\"/>\n");
        }
        return svg.append("</svg>\n").toString();
    }

    /**
     * Where a world point lands in the cell of the view along an axis.
     *
     * @param axis  the view's axis
     * @param x     world x
     * @param y     world y
     * @param z     world z
     * @param pixel receives the cell pixel, whole numbers at pixel centres
     */
    private static void toPixel(int axis, float x, float y, float z, float[] pixel) {
        float[] view = MultiviewScreenshot.VIEW_AZIMUTH_ELEVATION_RADIANS[CELL_OF_VIEW_AXIS[axis]];
        Camera3D camera = new Camera3D(new Vector3f(), 0f, 0f);
        camera.setDistanceBounds(0f, Float.MAX_VALUE);
        camera.fov = FIELD_OF_VIEW;
        camera.moveTarget(new Vector3f());
        camera.setOrbit(view[0], view[1], DISTANCE);
        camera.updateProjection(true, 1f, DISTANCE * 0.01f, DISTANCE * 4f);
        Vector4f clip = new Matrix4f(camera.projection).mul(camera.view).transform(new Vector4f(x, y, z, 1f));
        pixel[0] = (clip.x / clip.w + 1f) / 2f * CELL - 0.5f;
        pixel[1] = (1f - clip.y / clip.w) / 2f * CELL - 0.5f;
    }

    /**
     * Rasterise the surface's silhouette and the outline in one view's cell and measure how far they
     * part: mismatched area over the outline's length, the farthest mismatched pixel, and the farthest
     * the silhouette stands outside.
     *
     * @param surface the surface
     * @param outline the view's outline, packed xyz
     * @param axis    the view's axis
     * @return mean, maximum and outside deviation in pixels
     */
    private static float[] silhouetteError(MeshTopology surface, float[] outline, int axis) {
        int[] faceOfTriangle = new int[surface.triangleCount()];
        int[] corners = surface.triangulate(TriangulationRule.FAN, faceOfTriangle);
        boolean[] covered = new boolean[CELL * CELL];
        Vector3f position = new Vector3f();
        float[] pixel = new float[2];
        float[] triangle = new float[6];
        for (int index = 0; index < faceOfTriangle.length; index++) {
            for (int corner = 0; corner < 3; corner++) {
                surface.vertexPosition(corners[index * 3 + corner], position);
                toPixel(axis, position.x, position.y, position.z, pixel);
                triangle[corner * 2] = pixel[0];
                triangle[corner * 2 + 1] = pixel[1];
            }
            float area = (triangle[2] - triangle[0]) * (triangle[5] - triangle[1])
                    - (triangle[4] - triangle[0]) * (triangle[3] - triangle[1]);
            int left = Math.max(0, (int) Math.floor(Math.min(triangle[0], Math.min(triangle[2], triangle[4]))));
            int right = Math.min(CELL - 1, (int) Math.ceil(Math.max(triangle[0], Math.max(triangle[2], triangle[4]))));
            int top = Math.max(0, (int) Math.floor(Math.min(triangle[1], Math.min(triangle[3], triangle[5]))));
            int bottom = Math.min(CELL - 1, (int) Math.ceil(Math.max(triangle[1], Math.max(triangle[3], triangle[5]))));
            for (int row = top; row <= bottom && area != 0f; row++) {
                for (int column = left; column <= right; column++) {
                    boolean inside = true;
                    for (int edge = 0; edge < 3 && inside; edge++) {
                        int from = edge * 2;
                        int to = (edge + 1) % 3 * 2;
                        inside = area * ((triangle[to] - triangle[from]) * (row - triangle[from + 1])
                                - (triangle[to + 1] - triangle[from + 1]) * (column - triangle[from])) >= 0f;
                    }
                    covered[row * CELL + column] |= inside;
                }
            }
        }
        int count = outline.length / 3;
        float[] polygon = new float[count * 2];
        float perimeter = 0f;
        for (int point = 0; point < count; point++) {
            toPixel(axis, outline[point * 3], outline[point * 3 + 1], outline[point * 3 + 2], pixel);
            polygon[point * 2] = pixel[0];
            polygon[point * 2 + 1] = pixel[1];
        }
        for (int point = 0, previous = count - 1; point < count; previous = point++) {
            perimeter += (float) Math.hypot(polygon[point * 2] - polygon[previous * 2],
                    polygon[point * 2 + 1] - polygon[previous * 2 + 1]);
        }
        int mismatched = 0;
        float farthest = 0f;
        float farthestOutside = 0f;
        for (int row = 0; row < CELL; row++) {
            for (int column = 0; column < CELL; column++) {
                boolean inside = false;
                float nearest = Float.POSITIVE_INFINITY;
                for (int point = 0, previous = count - 1; point < count; previous = point++) {
                    float pointX = polygon[point * 2];
                    float pointY = polygon[point * 2 + 1];
                    float segmentX = polygon[previous * 2] - pointX;
                    float segmentY = polygon[previous * 2 + 1] - pointY;
                    if ((pointY > row) != (pointY + segmentY > row)
                            && column < pointX + (row - pointY) * segmentX / segmentY) {
                        inside = !inside;
                    }
                    float along = Math.max(0f, Math.min(1f, ((column - pointX) * segmentX + (row - pointY) * segmentY)
                            / Math.max(1e-12f, segmentX * segmentX + segmentY * segmentY)));
                    nearest = Math.min(nearest, (float) Math.hypot(column - pointX - along * segmentX,
                            row - pointY - along * segmentY));
                }
                if (inside != covered[row * CELL + column]) {
                    mismatched++;
                    farthest = Math.max(farthest, nearest);
                    farthestOutside = inside ? farthestOutside : Math.max(farthestOutside, nearest);
                }
            }
        }
        return new float[] { mismatched / perimeter, farthest, farthestOutside };
    }

    /**
     * Faces folded back against the surface: those whose normal points against the sum of their
     * corners' vertex normals, each vertex normal the sum of its faces' unit normals.
     *
     * @param surface triangle mesh
     * @return how many faces fold
     */
    private static int foldedFaces(MeshTopology surface) {
        Vector3f[] faceNormals = new Vector3f[surface.faceCount()];
        Vector3f[] vertexNormals = new Vector3f[surface.vertexIdBound()];
        Vector3f first = new Vector3f();
        Vector3f second = new Vector3f();
        Vector3f third = new Vector3f();
        for (int activeFace = 0; activeFace < faceNormals.length; activeFace++) {
            int faceId = surface.faceIdAt(activeFace);
            surface.vertexPosition(surface.faceVertexAt(faceId, 0), first);
            surface.vertexPosition(surface.faceVertexAt(faceId, 1), second).sub(first);
            surface.vertexPosition(surface.faceVertexAt(faceId, 2), third).sub(first);
            faceNormals[activeFace] = new Vector3f(second).cross(third).normalize();
            for (int corner = 0; corner < 3; corner++) {
                int vertexId = surface.faceVertexAt(faceId, corner);
                vertexNormals[vertexId] = (vertexNormals[vertexId] == null ? new Vector3f() : vertexNormals[vertexId])
                        .add(faceNormals[activeFace]);
            }
        }
        int folded = 0;
        for (int activeFace = 0; activeFace < faceNormals.length; activeFace++) {
            int faceId = surface.faceIdAt(activeFace);
            Vector3f corners = new Vector3f();
            for (int corner = 0; corner < 3; corner++) {
                corners.add(vertexNormals[surface.faceVertexAt(faceId, corner)]);
            }
            folded += faceNormals[activeFace].dot(corners) < 0f ? 1 : 0;
        }
        return folded;
    }

    /**
     * Assert a surface is closed with a sphere's topology, wound outward, with no degenerate face and
     * no two neighbouring faces turned more than a right angle apart.
     *
     * @param surface triangle mesh
     */
    private static void assertClosedAndUnfolded(MeshTopology surface) {
        int boundaryEdges = 0;
        int folds = 0;
        Vector3f[] normals = { new Vector3f(), new Vector3f() };
        Vector3f first = new Vector3f();
        Vector3f second = new Vector3f();
        Vector3f third = new Vector3f();
        for (int activeEdge = 0; activeEdge < surface.edgeCount(); activeEdge++) {
            int halfEdge = surface.edgeHalfEdge(surface.edgeIdAt(activeEdge));
            int[] faces = { surface.halfEdgeFace(halfEdge), surface.halfEdgeFace(surface.halfEdgeTwin(halfEdge)) };
            if (faces[0] < 0 || faces[1] < 0) {
                boundaryEdges++;
                continue;
            }
            for (int side = 0; side < 2; side++) {
                surface.vertexPosition(surface.faceVertexAt(faces[side], 0), first);
                surface.vertexPosition(surface.faceVertexAt(faces[side], 1), second).sub(first);
                surface.vertexPosition(surface.faceVertexAt(faces[side], 2), third).sub(first);
                normals[side].set(second).cross(third).normalize();
            }
            folds += normals[0].dot(normals[1]) < 0f ? 1 : 0;
        }
        assertEquals(0, boundaryEdges, "the surface is closed");
        assertEquals(SPHERE_EULER_CHARACTERISTIC,
                surface.vertexCount() - surface.edgeCount() + surface.faceCount(), "a sphere's topology");
        assertEquals(0, folds, "no two neighbouring faces turn more than a right angle apart");
        float volume = 0f;
        float smallest = Float.POSITIVE_INFINITY;
        for (int activeFace = 0; activeFace < surface.faceCount(); activeFace++) {
            int faceId = surface.faceIdAt(activeFace);
            surface.vertexPosition(surface.faceVertexAt(faceId, 0), first);
            surface.vertexPosition(surface.faceVertexAt(faceId, 1), second);
            surface.vertexPosition(surface.faceVertexAt(faceId, 2), third);
            volume += first.dot(new Vector3f(second).cross(third)) / 6f;
            smallest = Math.min(smallest, second.sub(first).cross(third.sub(first)).length() / 2f);
        }
        assertTrue(volume > 0f, "faces wound outward");
        assertTrue(smallest > 0f, "no degenerate face");
    }
}
