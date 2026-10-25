package ixdar.tools.regions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import ixdar.geometry.mesh.data.labels.MeshLabels;
import ixdar.geometry.mesh.data.paths.query.SurfaceCreases;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.rings.RegionExplosion;
import ixdar.geometry.mesh.data.rings.RingRegions;
import ixdar.graphics.cameras.Camera3D;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.Palettes;
import ixdar.graphics.render.color.RegionColouring;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.graphics.render.model.MeshOverlayRuntime;
import ixdar.nodegraph.nodes.data.SurfaceCreasesNode;
import ixdar.nodegraph.runtime.GraphNodeContext;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.platform.Clock;
import ixdar.platform.Platforms;
import ixdar.platform.input.Keys;
import ixdar.scenes.main.Toggle;
import ixdar.scenes.ring.RingScene;

/**
 * The editing scene's ring regions, updated incrementally as rings change: their colours, shown in
 * the region tool, while the ring tool bisects, or switched on, and the hidden faces no
 * tool draws or picks.
 */
public final class RegionLayer {

    public static final String LOG_PREFIX = "[regions] ";

    public static final Vector4f SLIVER_COLOUR = Color.WHITE.toVector4f();

    public static final String SPLITS_NOTHING_NOTE = "splits nothing";

    public static final Vector4f UNLABELLED_COLOUR = Color.GREY_VERY_DARK.toVector4f();

    public static final Color PART_NAME_COLOUR = Color.BLACK;

    public static final Color REGION_NUMBER_COLOUR = Color.WHITE;

    public static final String REGION_NUMBER_PREFIX = "R";

    public static final int REGION_NUMBER_CELL_PIXELS = 8;

    private static final float HALF_CELL = 0.5f;

    private static final int[] CHAMFER_COLUMN_STEPS = { -1, -1, 0, 1 };

    private static final int[] CHAMFER_ROW_STEPS = { 0, -1, -1, -1 };

    private static final int[] CHAMFER_WEIGHTS = { 3, 4, 3, 4 };

    /** Scene whose surface and ring tool the layer reads. */
    public final RingScene scene;

    /** Regions of the shown surface, or {@code null} before the layer first showed. */
    public RingRegions regions;

    /** Colours of {@link #regions}, kept across updates. */
    public final RegionColouring colouring = new RegionColouring();

    /** Whether the colours show outside the region tool too; G flips it. */
    public boolean visible;

    /** Whether slivers are merged into their largest neighbour; A flips it. */
    public boolean absorbSlivers;

    /** Bumped whenever {@link #regions} changes, so the region tool re-resolves its selection. */
    public int revision;

    /**
     * The note the ring tool draws beside a marked ring, by label: {@link #SPLITS_NOTHING_NOTE}
     * for one with a single region on both sides with every ring in place.
     */
    public Map<String, String> ringNoteByLabel = Map.of();

    /** One-line summary of the regions and the last update, as logged. */
    public String summary = "";

    /**
     * Faces neither drawn nor picked in any tool, by dense face index, kept through ring edits;
     * empty hides none.
     */
    public boolean[] hiddenByActiveFace = new boolean[0];

    /** Live labels of the rings left undrawn because every region they bound is hidden. */
    public Set<String> hiddenRingLabels = Set.of();

    /**
     * The part labels of the shown surface, each kept as points inside its regions: read from the
     * graph's output when a surface is first shown, then edited by the region tool.
     */
    public MeshLabels partLabels = new MeshLabels();

    /** Whether {@link #partLabels} differs from what the working .dsl holds. */
    public boolean labelsUnsaved;

    /** Index into {@link #partLabels} of each region's label, {@link MeshLabels#UNLABELLED} for none. */
    public int[] labelByRegion = new int[0];

    /** Whether labelled regions take their label's colour and the rest one dark grey; P flips it. */
    public boolean colourByPart = true;

    /** Whether each label's name is drawn on its regions; Shift+P flips it. */
    public boolean showPartNames = true;

    /** Packed xyz of each drawn part name: the label points, which lie on the surface. */
    public float[] partNameXyz = new float[0];

    /** Text of each drawn part name, parallel to {@link #partNameXyz}. */
    public String[] partNameText = new String[0];

    /** Colour of each drawn part name, parallel to {@link #partNameText}. */
    public Color[] partNameColours = new Color[0];

    /**
     * How far each part name's point is lifted toward the eye before its depth test: the overlay's
     * marker radius, so a name on the surface is not hidden by the surface it lies on.
     */
    public float[] partNameLift = new float[0];

    /** Whether each region's number is drawn on the thickest part of it the camera sees; Shift+N flips it. */
    public boolean showRegionNumbers;

    /** Framebuffer x then y, y up, of each region's drawn number, by region. */
    public float[] regionNumberXy = new float[0];

    /** Each region's drawn number, {@code null} for one the camera does not see; empty while none are drawn. */
    public String[] regionNumberText = new String[0];

    /**
     * Exploded view of {@link #regions}, shown in every tool and re-measured when they are
     * rebuilt; X in the region tool and the terminal's {@code xp} set it.
     */
    public final RegionExplosion explosion = new RegionExplosion(RingRegionTool::regionTag);

    private boolean hiddenStale;

    private boolean labelsStale;

    private int builtRingRevision = -1;

    private boolean builtAbsorbing;

    private boolean shown;

    private boolean overlayStale;

    private HalfEdgeMeshRuntime.ShaderMode shaderModeBefore;

    private final Matrix4f numberedView = new Matrix4f();

    private final Matrix4f numberedProjection = new Matrix4f();

    /**
     * Binds the layer to its scene.
     *
     * @param scene the editing scene whose surface and rings the layer reads
     */
    public RegionLayer(RingScene scene) {
        this.scene = scene;
    }

    /** G: show or hide the colours outside the region tool. */
    public void toggleVisible() {
        visible = !visible;
        overlayStale = true;
        Platforms.get().log(LOG_PREFIX + "region colours " + (visible ? "on" : "off"));
    }

    /**
     * Shift+H hides the region tool's selection, Shift+I everything but it. Hidden faces stay
     * hidden in every tool, through ring edits, until U.
     *
     * @param isolate hide everything outside the selection rather than the selection itself
     */
    public void hideSelection(boolean isolate) {
        RingRegionTool tool = scene.regionTool;
        if (regions == null || tool.selectedCount() == 0
                || tool.selectedRegions.length != regions.regionCount) {
            tool.lastError = "select a region to " + (isolate ? "isolate" : "hide") + " first";
            Platforms.get().log(LOG_PREFIX + tool.lastError);
            return;
        }
        boolean[] selected = regions.selectionByActiveFace(tool.selectedRegions);
        if (hiddenByActiveFace.length != selected.length) {
            hiddenByActiveFace = new boolean[selected.length];
        }
        int hiddenFaces = 0;
        for (int activeFace = 0; activeFace < selected.length; activeFace++) {
            hiddenByActiveFace[activeFace] = isolate ? !selected[activeFace]
                    : hiddenByActiveFace[activeFace] || selected[activeFace];
            hiddenFaces += hiddenByActiveFace[activeFace] ? 1 : 0;
        }
        hiddenStale = true;
        tool.lastError = "";
        Platforms.get().log(LOG_PREFIX + (isolate ? "isolated " : "hid ") + tool.selectedCount()
                + " region(s): " + hiddenFaces + " of " + selected.length + " faces hidden");
        if (!isolate) {
            tool.clearSelection();
        }
    }

    /** U: show every hidden face again. */
    public void showAll() {
        if (hiddenByActiveFace.length == 0) {
            return;
        }
        hiddenByActiveFace = new boolean[0];
        hiddenStale = true;
        Platforms.get().log(LOG_PREFIX + "showing every region");
    }

    /** A: merge slivers into their largest neighbour, or split them out again. */
    public void toggleAbsorbSlivers() {
        absorbSlivers = !absorbSlivers;
        Platforms.get().log(LOG_PREFIX + (absorbSlivers ? "absorbing" : "showing")
                + " regions under " + RingRegions.SLIVER_FACES + " faces");
    }

    /** Redraw the colours on the next frame, after the region tool's selection changed. */
    public void redraw() {
        overlayStale = true;
    }

    /** Resolve and redraw {@link #partLabels} on the next frame after an edit, which a save writes. */
    public void labelsChanged() {
        labelsUnsaved = true;
        labelsStale = true;
    }

    /**
     * The labelled parts with their region counts, then the unlabelled count, as the region tool
     * reports them.
     *
     * @return e.g. {@code parts: claw_left (2 regions), body (1 region); 37 regions unlabelled}
     */
    public String partsSummary() {
        int[] regionsByLabel = new int[partLabels.names.size()];
        int unlabelled = 0;
        for (int label : labelByRegion) {
            if (label == MeshLabels.UNLABELLED) {
                unlabelled++;
            } else {
                regionsByLabel[label]++;
            }
        }
        List<String> parts = new ArrayList<>();
        for (int label = 0; label < regionsByLabel.length; label++) {
            parts.add(partLabels.names.get(label) + " (" + regionsByLabel[label]
                    + (regionsByLabel[label] == 1 ? " region)" : " regions)"));
        }
        return "parts: " + (parts.isEmpty() ? "none" : String.join(", ", parts)) + "; "
                + unlabelled + (unlabelled == 1 ? " region" : " regions") + " unlabelled";
    }

    /**
     * One frame: while colours are wanted or faces hidden, re-flood the regions changed rings
     * bound and draw the shown faces; otherwise drop the colours once.
     */
    public void perFrame() {
        HalfEdgeMeshRuntime runtime = scene.surfaceRuntime();
        MeshTopology surface = scene.halfEdgeSurface();
        if (hiddenByActiveFace.length > 0 && (regions == null || regions.mesh != surface)) {
            // The mask names faces of a surface no longer shown; a new one shows whole.
            hiddenByActiveFace = new boolean[0];
            hiddenStale = true;
        }
        if (hiddenStale && runtime != null && surface != null) {
            hiddenStale = false;
            runtime.uploadFacePickBuffer(surface, hiddenByActiveFace);
            overlayStale = true;
        }
        boolean coloured = visible || scene.activeTool == scene.regionTool
                || scene.activeTool == scene.ringTool
                        && scene.ringTool.activeSubTool == scene.ringTool.bisector;
        boolean exploding = explosion.target > 0f || explosion.exploded();
        if (runtime == null || surface == null || surface.faceCount() == 0
                || !coloured && hiddenByActiveFace.length == 0 && !exploding) {
            if (shown && runtime != null) {
                runtime.clearTags();
                runtime.setShaderMode(shaderModeBefore);
            }
            if (shown) {
                scene.ringTool.markRings(Map.of(), Set.of());
            }
            shown = false;
            regionNumberText = new String[0];
            return;
        }
        long start = System.nanoTime();
        String rebuilt = null;
        if (regions == null || regions.mesh != surface
                || builtRingRevision != scene.ringTool.ringRevision
                || builtAbsorbing != absorbSlivers) {
            Map<String, boolean[]> rings = scene.ringTool.liveRingMarks();
            String[] labels = rings.keySet().toArray(new String[0]);
            boolean[][] masks = rings.values().toArray(new boolean[0][]);
            boolean fresh = regions == null || regions.mesh != surface;
            if (fresh) {
                regions = new RingRegions(surface, labels, masks);
                colouring.colourByNode = new int[0];
                // A new surface is a model the graph just ran: its labels are the ones it saved.
                partLabels = new MeshLabels(MeshLabels.inBundle(scene.getGeometryBundle()));
                labelsUnsaved = false;
                labelsStale = true;
            }
            regions.absorbBelowFaces = absorbSlivers ? RingRegions.SLIVER_FACES : 0;
            if (fresh || builtAbsorbing != absorbSlivers) {
                regions.build();
            } else {
                regions.update(labels, masks);
            }
            // Flatness reads the creases crease rings follow: the ring tool's, else the graph's
            // own surface_creases on this surface, else measured once here and handed to the tool.
            SurfaceCreases creases = scene.ringTool.creases;
            boolean creasesOnSurface = creases != null && creases.sourceMesh == surface;
            NodeGraphRuntime graph = scene.graph;
            for (GraphNodeContext record : creasesOnSurface || graph == null
                    ? List.<GraphNodeContext>of() : graph.root.children) {
                if (record.getOutput(SurfaceCreasesNode.CREASES.name)
                        instanceof SurfaceCreases built && built.sourceMesh == surface) {
                    creases = built;
                }
            }
            if (creases == null || creases.sourceMesh != surface) {
                creases = SurfaceCreases.of(surface);
                Platforms.get().log(String.format(Locale.ROOT, LOG_PREFIX + "measured the "
                        + "grooves of %d faces in %.0f ms for region flatness",
                        surface.faceCount(), creases.buildMillis));
            }
            scene.ringTool.creases = creases;
            regions.measureFlatness(creases);
            colouring.colour(regions.neighboursByRegion, regions.regionFaceCount,
                    regions.formerRegionByRegion, regions.regionKeptFaces, Palettes.REGION);
            builtRingRevision = scene.ringTool.ringRevision;
            builtAbsorbing = absorbSlivers;
            revision++;
            explosion.measure(surface, regions.regionByActiveFace, regions.regionCount);
            Map<String, String> notes = new TreeMap<>();
            for (int ring = 0; ring < regions.ringLabels.length; ring++) {
                if (regions.ringSplitsNothing[ring]) {
                    notes.put(regions.ringLabels[ring], SPLITS_NOTHING_NOTE);
                }
            }
            ringNoteByLabel = notes;
            rebuilt = fresh ? "built" : "updated";
            overlayStale = true;
        }
        if (labelsStale || rebuilt != null) {
            // Points name regions, not region numbers, so every rebuild resolves them again.
            labelsStale = false;
            labelByRegion = partLabels.labelByRegion(regions);
            int pointCount = 0;
            for (float[] points : partLabels.pointsByLabel) {
                pointCount += points.length / MeshLabels.XYZ;
            }
            partNameXyz = new float[MeshLabels.XYZ * pointCount];
            partNameText = new String[pointCount];
            partNameColours = new Color[pointCount];
            partNameLift = new float[pointCount];
            Arrays.fill(partNameLift, runtime instanceof MeshOverlayRuntime overlay
                    ? overlay.sphereRadius : 0f);
            int named = 0;
            for (int label = 0; label < partLabels.names.size(); label++) {
                float[] points = partLabels.pointsByLabel.get(label);
                System.arraycopy(points, 0, partNameXyz, MeshLabels.XYZ * named, points.length);
                for (int point = 0; point < points.length / MeshLabels.XYZ; point++) {
                    partNameText[named] = partLabels.names.get(label);
                    partNameColours[named] = PART_NAME_COLOUR;
                    named++;
                }
            }
            overlayStale = true;
        }
        if (!shown) {
            shaderModeBefore = runtime.getShaderMode();
            shown = true;
            overlayStale = true;
        }
        boolean hiding = hiddenByActiveFace.length == regions.regionByActiveFace.length;
        if (overlayStale) {
            // A ring is left undrawn when every region it bounds is hidden.
            boolean[] regionShown = new boolean[regions.regionCount];
            for (int activeFace = 0; activeFace < regions.regionByActiveFace.length; activeFace++) {
                regionShown[regions.regionByActiveFace[activeFace]] |= !hiding
                        || !hiddenByActiveFace[activeFace];
            }
            boolean[] ringShown = new boolean[regions.ringLabels.length];
            for (int region = 0; region < regions.regionCount; region++) {
                for (int ring : regions.boundingRingsByRegion[region]) {
                    ringShown[ring] |= regionShown[region];
                }
            }
            Set<String> hiddenRings = new TreeSet<>();
            for (int ring = 0; ring < ringShown.length; ring++) {
                if (!ringShown[ring]) {
                    hiddenRings.add(regions.ringLabels[ring]);
                }
            }
            hiddenRingLabels = hiddenRings;
        }
        scene.ringTool.markRings(ringNoteByLabel, hiddenRingLabels);
        // Holding , or . nudges the explosion only in the region tool, which lists those keys.
        boolean keysFree = scene.activeTool == scene.regionTool && scene.keys != null
                && !Toggle.IsTerminalFocused.value;
        int heldDirection = (keysFree && scene.keys.pressedKeys.contains(Keys.PERIOD) ? 1 : 0)
                - (keysFree && scene.keys.pressedKeys.contains(Keys.COMMA) ? 1 : 0);
        explosion.step((float) Clock.deltaTime(), heldDirection, runtime.tagOffsets);
        Camera3D camera = scene.camera;
        if (!showRegionNumbers || explosion.exploded() || scene.regionTool.showingExtraction
                || !runtime.facePickReady()) {
            regionNumberText = new String[0];
        } else if (overlayStale || regionNumberText.length != regions.regionCount
                || !camera.view.equals(numberedView) || !camera.projection.equals(numberedProjection)) {
            // Each number goes where the region's seen part is thickest: the cell of a coarse id
            // pass farthest, by a 3-4 chamfer distance, from any cell showing another region, no
            // surface or the view's edge.
            int width = Platforms.get().getFrameBufferWidth();
            int height = Platforms.get().getFrameBufferHeight();
            int columns = width / REGION_NUMBER_CELL_PIXELS;
            int rows = height / REGION_NUMBER_CELL_PIXELS;
            int[] regionByCell = runtime.faceIndexGrid(camera, columns, rows);
            numberedView.set(camera.view);
            numberedProjection.set(camera.projection);
            int[] distance = new int[regionByCell.length];
            for (int cell = 0; cell < regionByCell.length; cell++) {
                int activeFace = regionByCell[cell];
                regionByCell[cell] = activeFace < 0 || activeFace >= regions.regionByActiveFace.length
                        ? MeshTopology.NONE : regions.regionByActiveFace[activeFace];
                distance[cell] = regionByCell[cell] < 0 ? 0 : Integer.MAX_VALUE / 2;
            }
            for (int pass = 0; pass < 2; pass++) {
                int sign = pass == 0 ? 1 : -1;
                for (int step = 0; step < regionByCell.length; step++) {
                    int cell = pass == 0 ? step : regionByCell.length - 1 - step;
                    int region = regionByCell[cell];
                    for (int neighbour = 0; region >= 0 && neighbour < CHAMFER_WEIGHTS.length; neighbour++) {
                        int column = cell % columns + sign * CHAMFER_COLUMN_STEPS[neighbour];
                        int row = cell / columns + sign * CHAMFER_ROW_STEPS[neighbour];
                        boolean inside = column >= 0 && column < columns && row >= 0 && row < rows
                                && regionByCell[row * columns + column] == region;
                        distance[cell] = Math.min(distance[cell],
                                (inside ? distance[row * columns + column] : 0) + CHAMFER_WEIGHTS[neighbour]);
                    }
                }
            }
            int[] centreCell = new int[regions.regionCount];
            int[] centreDistance = new int[regions.regionCount];
            Arrays.fill(centreCell, -1);
            for (int cell = 0; cell < regionByCell.length; cell++) {
                int region = regionByCell[cell];
                if (region >= 0 && region < regions.regionCount && distance[cell] > centreDistance[region]) {
                    centreDistance[region] = distance[cell];
                    centreCell[region] = cell;
                }
            }
            regionNumberXy = new float[2 * regions.regionCount];
            regionNumberText = new String[regions.regionCount];
            for (int region = 0; region < regions.regionCount; region++) {
                int cell = centreCell[region];
                if (cell >= 0) {
                    regionNumberXy[2 * region] = (cell % columns + HALF_CELL) * width / columns;
                    regionNumberXy[2 * region + 1] = (cell / columns + HALF_CELL) * height / rows;
                    regionNumberText[region] = REGION_NUMBER_PREFIX + region;
                }
            }
        }
        if (!overlayStale) {
            return;
        }
        overlayStale = false;
        // One draw range per region under its region tag: its palette colour, white for a
        // sliver, grey while the region tool has a selection the region is not in, or the
        // surface's own colour while the colours are off; hidden faces in no range.
        RingRegionTool tool = scene.regionTool;
        boolean dimming = scene.activeTool == tool && tool.selectedCount() > 0
                && tool.selectedRegions.length == regions.regionCount;
        // By part, a labelled region takes its label's entry, in label order, and every unlabelled
        // one the same dark grey, so what is left to name stands out.
        Vector4f grey = Color.GREY.toVector4f();
        boolean byPart = colourByPart && !partLabels.names.isEmpty()
                && labelByRegion.length == regions.regionCount;
        Vector4f[] colourByRegion = new Vector4f[regions.regionCount];
        String[] tagByRegion = new String[regions.regionCount];
        for (int region = 0; region < regions.regionCount; region++) {
            if (!coloured) {
                colourByRegion[region] = runtime.solidColor;
            } else if (dimming && !tool.selectedRegions[region]) {
                colourByRegion[region] = grey;
            } else if (byPart) {
                colourByRegion[region] = labelByRegion[region] == MeshLabels.UNLABELLED
                        ? UNLABELLED_COLOUR
                        : Palettes.BRIGHT_PATCH.shadedColor(labelByRegion[region]).toVector4f();
            } else {
                colourByRegion[region] = regions.isSliver(region) ? SLIVER_COLOUR
                        : Palettes.REGION.shadedColor(colouring.colourByNode[region])
                                .toVector4f();
            }
            tagByRegion[region] = RingRegionTool.regionTag(region);
        }
        int[] groupByActiveFace = regions.regionByActiveFace;
        if (hiding) {
            groupByActiveFace = groupByActiveFace.clone();
            for (int activeFace = 0; activeFace < groupByActiveFace.length; activeFace++) {
                if (hiddenByActiveFace[activeFace]) {
                    groupByActiveFace[activeFace] = MeshTopology.NONE;
                }
            }
        }
        runtime.setShaderMode(coloured ? HalfEdgeMeshRuntime.ShaderMode.STAGES
                : shaderModeBefore);
        runtime.setFaceGroups(surface, groupByActiveFace, colourByRegion, tagByRegion);
        if (rebuilt == null) {
            return;
        }
        int slivers = 0;
        for (int region = 0; region < regions.regionCount; region++) {
            slivers += regions.isSliver(region) ? 1 : 0;
        }
        summary = String.format(Locale.ROOT, "%d regions, %d slivers under %d faces (white)%s; "
                + "rings in pink: %d split nothing; %s and drawn in %.0f ms, "
                + "%d faces re-flooded; %s", regions.regionCount, slivers,
                RingRegions.SLIVER_FACES, absorbSlivers ? " absorbed" : "", ringNoteByLabel.size(),
                rebuilt, (System.nanoTime() - start) / 1e6, regions.refloodedFaces,
                partsSummary());
        Platforms.get().log(LOG_PREFIX + summary);
    }
}
