package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import ixdar.geometry.grid2d.PolygonScan;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.TriangulationRule;
import ixdar.geometry.mesh.data.sculpt.StrokeSketch;
import ixdar.geometry.volume.VoxelGrid;
import ixdar.graphics.cameras.Camera3D;
import ixdar.nodegraph.runtime.NodeGraphRuntime;

/**
 * silhouette_hull and the volume nodes after it, on fixtures under {@code dsl/fixtures/silhouette_hull/};
 * bounds are in lattice steps, so they hold at any fixture resolution.
 */
class SilhouetteHullNodeTest {
    private static final String FIXTURES = "dsl/fixtures/silhouette_hull/";
    private static final String NOTCHED = "notched";
    private static final String[] SHAPES = { "pear", NOTCHED, "egg" };
    private static final String HULL = "hull";
    private static final String HULL_MISSES = "hull.misses";
    private static final String VOLUME = "volume";
    private static final String GEOMETRY = "geometry";
    private static final String MESH = "mesh";
    private static final int MINIMUM_OUTLINE_POINTS = 3;
    // The hull's silhouette may part from an outline by the lattice's rounding: half a step on
    // either side of the outline on average, and at a concave corner one step plus smoothing's pull.
    private static final float HULL_MEAN_STEPS = 1f;
    private static final float HULL_MAXIMUM_STEPS = 2f;
    // Strict (misses 0): every kept lattice point lies inside every outline, and the extracted
    // surface may cut a cell's corner past it by under one step.
    private static final float STRICT_OUTSIDE_STEPS = 1f;
    // As traced the front bulges 0.15 past its mirror image, several lattice steps; mirrored, a
    // vertex's mirror image lies on the surface to within interpolation error.
    // vertex's mirror image lies on the surface to within trilinear interpolation and rounding,
    // under a step, since the lattice itself need not be symmetric about the plane.
    private static final float LOPSIDED_TRACED_STEPS = 1.5f;
    private static final float LOPSIDED_MIRRORED_STEPS = 1f;
    private static final String WOUND_OUTWARD = "volume_to_mesh winds its faces outward";
    private static final String WOUND_AS_INPUT = "the faces keep their input's winding";
    private static final int[] SKULL_RESOLUTIONS = { 32, 40, 48, 64, 96 };
    private static final float SPHERE_RADIUS = 1f;
    // Taubin smoothing (lambda 0.5, mu -0.53) is volume preserving to second order: 20 passes over
    // an icosphere of 642 vertices move its mean radius by well under one percent.
    private static final float SMOOTHED_RADIUS_FRACTION = 0.01f;
    private static final int SPHERE_EULER_CHARACTERISTIC = 2;
    private static final String VIEW_ERROR = "%s cell %d: mean %.2f, max %.2f steps; ";

    @Test
    void hullKeepsThePearsWaist() {
        assertHullFollowsItsOutlines(SHAPES[0]);
    }

    @Test
    void hullKeepsThePrismsNotch() {
        assertHullFollowsItsOutlines(NOTCHED);
    }

    @Test
    void hullLeansWithTheEgg() {
        assertHullFollowsItsOutlines(SHAPES[2]);
    }

    @Test
    void strictHullNeverStandsOutsideAnOutline() {
        NodeGraphRuntime hull = NodeGraphRuntime.executeResource(FIXTURES + NOTCHED + ".dsl", Map.of(HULL_MISSES, 0));
        MeshTopology surface = mesh(hull.lastOutput(GEOMETRY));
        assertTrue(assertClosedAndUnfolded(surface) > 0f, WOUND_OUTWARD);
        StrokeSketch sketch = StrokeSketch.load(FIXTURES + NOTCHED + ".svg", NOTCHED);
        float step = stepPixels(sketch, (VoxelGrid) hull.getNodeOutput(HULL, VOLUME));
        float[][] outlines = sketch.cellOutlines();
        for (int cell = 0; cell < outlines.length; cell++) {
            if (outlines[cell].length / 2 >= MINIMUM_OUTLINE_POINTS) {
                float outside = silhouetteError(surface, sketch, cell, outlines[cell])[2] / step;
                assertTrue(outside <= STRICT_OUTSIDE_STEPS, "cell " + cell + " stands " + outside + " steps out");
            }
        }
    }

    @Test
    void symmetryXEvensOutALopsidedTrace() {
        // MESH-80: mirrored, the hull keeps a point by the mean of its own and its mirror image's depth
        // inside the outlines, so each surface vertex's mirror image lies on the surface too.
        float traced = mirrorDeviation(NodeGraphRuntime.executeResource(FIXTURES + "lopsided.dsl",
                Map.of("hull.symmetry", "none")));
        float mirrored = mirrorDeviation(NodeGraphRuntime.executeResource(FIXTURES + "lopsided.dsl", Map.of()));
        assertTrue(traced > LOPSIDED_TRACED_STEPS, "as traced the hull is lopsided: " + traced + " steps");
        assertTrue(mirrored < LOPSIDED_MIRRORED_STEPS, "mirrored the hull is symmetric: " + mirrored
                + " steps (as traced " + traced + ")");
    }

    @Test
    void hullRefusesViewsThatKeepNoCommonVolume() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> NodeGraphRuntime.executeResource(FIXTURES + "disjoint.dsl", Map.of()));
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertTrue(cause.getMessage().contains("no common volume"), cause.getMessage());
    }

    @Test
    @Tag("heavy")
    void skullHullHasNoFoldedFaces() {
        // MESH-77 / PATCH-127: pleats of sliver faces folded back against the surface broke the quad
        // layout at some resolutions; none may come out at any of them.
        StringBuilder report = new StringBuilder();
        int total = 0;
        for (int run = 0; run < SKULL_RESOLUTIONS.length * 2; run++) {
            int resolution = SKULL_RESOLUTIONS[run / 2];
            NodeGraphRuntime skull = NodeGraphRuntime.executeResource(FIXTURES + "skull_fold.dsl",
                    Map.of("hull.resolution", resolution, HULL_MISSES, run % 2));
            int[] folded = { foldedFaces(mesh(skull.getNodeOutput("surface", GEOMETRY))),
                    foldedFaces(mesh(skull.getNodeOutput("part", GEOMETRY))), foldedFaces(mesh(skull.lastOutput(GEOMETRY))) };
            report.append(String.format(Locale.ROOT, "resolution %d misses %d: %d extracted, %d kept, %d smoothed; ",
                    resolution, run % 2, folded[0], folded[1], folded[2]));
            total += folded[0] + folded[2];
        }
        assertEquals(0, total, report.toString());
    }

    @Test
    void volumeToMeshClosesASphere() {
        NodeGraphRuntime hull = NodeGraphRuntime.executeResource(FIXTURES + "sphere.dsl", Map.of());
        MeshTopology sphere = mesh(hull.lastOutput(GEOMETRY));
        float spacing = ((VoxelGrid) hull.getNodeOutput(HULL, VOLUME)).spacing;
        assertTrue(assertClosedAndUnfolded(sphere) > 0f, WOUND_OUTWARD);
        // The hull of three circles is a sphere's to within a lattice step.
        assertTrue(Math.abs(meanRadius(sphere) - SPHERE_RADIUS) <= spacing, "radius " + meanRadius(sphere)
                + ", lattice step " + spacing);
    }

    @Test
    void keepLargestPartKeepsTheBiggerShell() {
        NodeGraphRuntime shells = NodeGraphRuntime.executeResource("dsl/fixtures/keep_largest_part.dsl", Map.of());
        MeshTopology kept = mesh(shells.lastOutput(GEOMETRY));
        MeshTopology big = mesh(shells.getNodeOutput("big", MESH));
        assertEquals(big.faceCount(), kept.faceCount(), "the bigger shell's faces, and only those");
        assertTrue(Math.abs(meanRadius(kept) - SPHERE_RADIUS) < SPHERE_RADIUS / 2, "the kept shell is the unit sphere");
        assertTrue(assertClosedAndUnfolded(kept) * assertClosedAndUnfolded(big) > 0f, WOUND_AS_INPUT);
    }

    @Test
    void smoothMeshDoesNotShrinkASphere() {
        NodeGraphRuntime smoothing = NodeGraphRuntime.executeResource("dsl/fixtures/smooth_sphere.dsl", Map.of());
        MeshTopology sphere = mesh(smoothing.getNodeOutput("ball", MESH));
        MeshTopology smoothed = mesh(smoothing.lastOutput(GEOMETRY));
        assertEquals(sphere.vertexCount(), smoothed.vertexCount(), "topology kept");
        float change = Math.abs(meanRadius(smoothed) - meanRadius(sphere)) / meanRadius(sphere);
        assertTrue(change <= SMOOTHED_RADIUS_FRACTION, "radius " + meanRadius(sphere) + " -> " + meanRadius(smoothed));
        assertTrue(assertClosedAndUnfolded(smoothed) * assertClosedAndUnfolded(sphere) > 0f, WOUND_AS_INPUT);
    }

    /**
     * Assert a shape's hull comes out closed, wound outward, and with its outlines as its silhouettes
     * to about a lattice step.
     *
     * @param shape the fixture's name under {@code dsl/fixtures/silhouette_hull/}
     */
    private static void assertHullFollowsItsOutlines(String shape) {
        NodeGraphRuntime hull = NodeGraphRuntime.executeResource(FIXTURES + shape + ".dsl", Map.of());
        MeshTopology surface = mesh(hull.lastOutput(GEOMETRY));
        assertTrue(assertClosedAndUnfolded(surface) > 0f, WOUND_OUTWARD);
        StrokeSketch sketch = StrokeSketch.load(FIXTURES + shape + ".svg", shape);
        float step = stepPixels(sketch, (VoxelGrid) hull.getNodeOutput(HULL, VOLUME));
        float[][] outlines = sketch.cellOutlines();
        StringBuilder report = new StringBuilder();
        boolean within = true;
        for (int cell = 0; cell < outlines.length; cell++) {
            if (outlines[cell].length / 2 >= MINIMUM_OUTLINE_POINTS) {
                float[] error = silhouetteError(surface, sketch, cell, outlines[cell]);
                report.append(String.format(Locale.ROOT, VIEW_ERROR, shape, cell, error[0] / step, error[1] / step));
                within &= error[0] <= HULL_MEAN_STEPS * step && error[1] <= HULL_MAXIMUM_STEPS * step;
            }
        }
        assertTrue(within, report.toString());
    }

    /**
     * The mesh a graph output carries.
     *
     * @param output a geometry output
     * @return its mesh
     */
    private static MeshTopology mesh(Object output) {
        return ((GeometryBundle) output).mesh();
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
     * How long one lattice step of a hull's volume is in a cell's pixels, for the template's
     * orthographic views.
     *
     * @param sketch the template the hull was carved from
     * @param volume the hull's volume
     * @return pixels per lattice step
     */
    private static float stepPixels(StrokeSketch sketch, VoxelGrid volume) {
        float halfHeight = sketch.distance * (float) Math.tan(Math.toRadians(sketch.fieldOfViewDegrees) / 2.0);
        return volume.spacing * sketch.cellHeight / (2f * halfHeight);
    }

    /**
     * The farthest any surface vertex's mirror image about x = 0 lies off the hull's surface: the
     * hull's volume at that image, by trilinear interpolation clamped to the lattice, in lattice steps.
     *
     * @param hull the executed lopsided graph
     * @return the largest deviation in lattice steps
     */
    private static float mirrorDeviation(NodeGraphRuntime hull) {
        VoxelGrid volume = (VoxelGrid) hull.getNodeOutput(HULL, VOLUME);
        MeshTopology surface = mesh(hull.lastOutput(GEOMETRY));
        Vector3f position = new Vector3f();
        int[] base = new int[VoxelGrid.AXES];
        float[] fraction = new float[VoxelGrid.AXES];
        float worst = 0f;
        for (int index = 0; index < surface.vertexCount(); index++) {
            surface.vertexPosition(surface.vertexIdAt(index), position);
            position.x = -position.x;
            for (int axis = 0; axis < VoxelGrid.AXES; axis++) {
                float coordinate = Math.max(0f, Math.min(volume.size[axis] - 1.001f,
                        (position.get(axis) - volume.origin[axis]) / volume.spacing));
                base[axis] = (int) coordinate;
                fraction[axis] = coordinate - base[axis];
            }
            float value = 0f;
            for (int corner = 0; corner < 8; corner++) {
                float weight = 1f;
                int[] lattice = new int[VoxelGrid.AXES];
                for (int axis = 0; axis < VoxelGrid.AXES; axis++) {
                    int bit = corner >> axis & 1;
                    lattice[axis] = base[axis] + bit;
                    weight *= bit == 1 ? fraction[axis] : 1f - fraction[axis];
                }
                value += weight * volume.values[volume.index(lattice[0], lattice[1], lattice[2])];
            }
            worst = Math.max(worst, Math.abs(value));
        }
        return worst;
    }

    /**
     * Rasterise the surface's silhouette and the outline in one cell and measure how far they part:
     * mismatched area over the outline's length, the farthest mismatched pixel, and the farthest the
     * silhouette stands outside. Distances to the outline are taken only for mismatched pixels.
     *
     * @param surface the surface
     * @param sketch  the template the outline is from
     * @param cell    the cell
     * @param polygon the cell's outline in its pixels, packed xy
     * @return mean, maximum and outside deviation in pixels
     */
    private static float[] silhouetteError(MeshTopology surface, StrokeSketch sketch, int cell, float[] polygon) {
        int width = sketch.cellWidth;
        int height = sketch.cellHeight;
        Camera3D camera = sketch.cellCamera(cell);
        Matrix4f projection = new Matrix4f(camera.projection).mul(camera.view);
        int[] faceOfTriangle = new int[surface.triangleCount()];
        int[] corners = surface.triangulate(TriangulationRule.FAN, faceOfTriangle);
        boolean[] covered = new boolean[width * height];
        Vector3f position = new Vector3f();
        Vector4f clip = new Vector4f();
        float[] triangle = new float[6];
        for (int index = 0; index < faceOfTriangle.length; index++) {
            for (int corner = 0; corner < 3; corner++) {
                // Pixel centres at whole numbers, as the outline's points are.
                surface.vertexPosition(corners[index * 3 + corner], position);
                projection.transform(clip.set(position, 1f));
                triangle[corner * 2] = (clip.x / clip.w + 1f) / 2f * width - 0.5f;
                triangle[corner * 2 + 1] = (1f - clip.y / clip.w) / 2f * height - 0.5f;
            }
            float area = (triangle[2] - triangle[0]) * (triangle[5] - triangle[1])
                    - (triangle[4] - triangle[0]) * (triangle[3] - triangle[1]);
            int left = Math.max(0, (int) Math.floor(Math.min(triangle[0], Math.min(triangle[2], triangle[4]))));
            int right = Math.min(width - 1, (int) Math.ceil(Math.max(triangle[0], Math.max(triangle[2], triangle[4]))));
            int top = Math.max(0, (int) Math.floor(Math.min(triangle[1], Math.min(triangle[3], triangle[5]))));
            int bottom = Math.min(height - 1, (int) Math.ceil(Math.max(triangle[1], Math.max(triangle[3], triangle[5]))));
            for (int row = top; row <= bottom && area != 0f; row++) {
                for (int column = left; column <= right; column++) {
                    boolean inside = true;
                    for (int edge = 0; edge < 3 && inside; edge++) {
                        int from = edge * 2;
                        int to = (edge + 1) % 3 * 2;
                        inside = area * ((triangle[to] - triangle[from]) * (row - triangle[from + 1])
                                - (triangle[to + 1] - triangle[from + 1]) * (column - triangle[from])) >= 0f;
                    }
                    covered[row * width + column] |= inside;
                }
            }
        }
        int count = polygon.length / 2;
        float perimeter = 0f;
        for (int point = 0, previous = count - 1; point < count; previous = point++) {
            perimeter += (float) Math.hypot(polygon[point * 2] - polygon[previous * 2],
                    polygon[point * 2 + 1] - polygon[previous * 2 + 1]);
        }
        int mismatched = 0;
        float farthest = 0f;
        float farthestOutside = 0f;
        boolean[] outline = PolygonScan.covered(polygon, 0f, 0f, 1f, width, height);
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                boolean inside = outline[row * width + column];
                if (inside == covered[row * width + column]) {
                    continue;
                }
                float nearest = Float.POSITIVE_INFINITY;
                for (int point = 0, previous = count - 1; point < count; previous = point++) {
                    float pointX = polygon[point * 2];
                    float pointY = polygon[point * 2 + 1];
                    float segmentX = polygon[previous * 2] - pointX;
                    float segmentY = polygon[previous * 2 + 1] - pointY;
                    float along = Math.max(0f, Math.min(1f, ((column - pointX) * segmentX + (row - pointY) * segmentY)
                            / Math.max(1e-12f, segmentX * segmentX + segmentY * segmentY)));
                    nearest = Math.min(nearest, (float) Math.hypot(column - pointX - along * segmentX,
                            row - pointY - along * segmentY));
                }
                mismatched++;
                farthest = Math.max(farthest, nearest);
                farthestOutside = inside ? farthestOutside : Math.max(farthestOutside, nearest);
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
     * Assert a surface is closed with a sphere's topology, with no degenerate face and no two
     * neighbouring faces turned more than a right angle apart.
     *
     * @param surface triangle mesh
     * @return its signed volume, positive when its faces wind outward
     */
    private static float assertClosedAndUnfolded(MeshTopology surface) {
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
        assertTrue(smallest > 0f, "no degenerate face");
        return volume;
    }
}
