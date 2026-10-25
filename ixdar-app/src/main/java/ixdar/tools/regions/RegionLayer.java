package ixdar.tools.regions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import ixdar.geometry.mesh.data.labels.MeshLabels;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.rings.RegionExplosion;
import ixdar.geometry.mesh.data.rings.RingRegions;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.color.Palettes;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.gui.ui.text.HyperString;
import ixdar.platform.Clock;
import ixdar.platform.Platforms;
import ixdar.scenes.ring.RingScene;
import ixdar.tools.ring.RingTool;

/**
 * The editing scene's ring regions as shown: their colours, in the region tool, while the ring tool
 * bisects, or switched on; the hidden faces no tool draws or picks; and the region labels. Each
 * change rebuilds what it touches; a frame only animates the explosion.
 */
public final class RegionLayer {

    public static final String LOG_PREFIX = "[regions] ";

    public static final Vector4f SLIVER_COLOUR = Color.WHITE.toVector4f();

    public static final Vector4f UNLABELLED_COLOUR = Color.GREY_VERY_DARK.toVector4f();

    public static final String REGION_NUMBER_PREFIX = "R";

    /** Scene whose surface and ring tool the layer reads. */
    public final RingScene scene;

    /** Regions of the shown surface, or {@code null} before the layer first showed. */
    public RingRegions regions;

    /** Whether the colours show outside the region tool too; G flips it. */
    public boolean visible;

    /** Whether slivers are merged into their largest neighbour; A flips it. */
    public boolean absorbSlivers;

    /** Bumped whenever {@link #regions} changes, so the region tool re-resolves its selection. */
    public int revision;

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
     * The part labels of the shown surface, each kept as points inside its regions and resolved
     * against {@link #regions}: read from the graph's output when a surface is first shown, then
     * edited by the region tool.
     */
    public MeshLabels partLabels = new MeshLabels();

    /** Whether {@link #partLabels} differs from what the working .dsl holds. */
    public boolean labelsUnsaved;

    /** Whether labelled regions take their label's colour and the rest one dark grey; P flips it. */
    public boolean colourByPart = true;

    /** Whether each labelled region's part name is drawn on it; Shift+P flips it. */
    public boolean showPartNames = true;

    /** Whether each region's number is drawn on it; Shift+N flips it. */
    public boolean showRegionNumbers;

    /**
     * One label per region with anything to show, centred on the region: its number while
     * {@link #showRegionNumbers}, then its part name while {@link #showPartNames}.
     */
    public List<HyperString> regionLabels = new ArrayList<>();

    /**
     * The ring tool's {@code ringRevision} {@link #regions} were last built from; behind it, the
     * regions and their labels trail the rings until the layer shows again.
     */
    public int builtRingRevision = -1;

    /**
     * Exploded view of {@link #regions}, shown in every tool and re-measured when they are
     * rebuilt; X in the region tool and the terminal's {@code xp} set it.
     */
    public final RegionExplosion explosion = new RegionExplosion(RingRegionTool::regionTag);

    private boolean shown;

    private final Matrix4f labelsTiedView = new Matrix4f().zero();

    private boolean shownColoured;

    private HalfEdgeMeshRuntime.ShaderMode shaderModeBefore;

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
        boolean[] hidden = hiddenByActiveFace.length == selected.length ? hiddenByActiveFace
                : new boolean[selected.length];
        int hiddenFaces = 0;
        for (int activeFace = 0; activeFace < selected.length; activeFace++) {
            hidden[activeFace] = isolate ? !selected[activeFace]
                    : hidden[activeFace] || selected[activeFace];
            hiddenFaces += hidden[activeFace] ? 1 : 0;
        }
        tool.lastError = "";
        Platforms.get().log(LOG_PREFIX + (isolate ? "isolated " : "hid ") + tool.selectedCount()
                + " region(s): " + hiddenFaces + " of " + selected.length + " faces hidden");
        hide(hidden);
        if (!isolate) {
            tool.clearSelection();
        }
    }

    /** U: show every hidden face again. */
    public void showAll() {
        if (hiddenByActiveFace.length == 0) {
            return;
        }
        Platforms.get().log(LOG_PREFIX + "showing every region");
        hide(new boolean[0]);
    }

    /**
     * Hide exactly these faces: re-upload the pick buffer without them, leave the rings only they
     * bound undrawn and redraw the colours.
     *
     * @param hidden hidden faces by dense face index; empty hides none
     */
    public void hide(boolean[] hidden) {
        hiddenByActiveFace = hidden;
        HalfEdgeMeshRuntime runtime = scene.surfaceRuntime();
        MeshTopology surface = scene.halfEdgeSurface();
        if (runtime != null && surface != null) {
            runtime.uploadFacePickBuffer(surface, hiddenByActiveFace);
        }
        if (regions != null) {
            hiddenRingLabels = regions.hiddenRingLabels(hiddenByActiveFace);
            markRings();
        }
        redraw();
    }

    /** A: merge slivers into their largest neighbour, or split them out again. */
    public void toggleAbsorbSlivers() {
        absorbSlivers = !absorbSlivers;
        Platforms.get().log(LOG_PREFIX + (absorbSlivers ? "absorbing" : "showing")
                + " regions under " + RingRegions.SLIVER_FACES + " faces");
        if (regions != null) {
            rebuild();
        }
    }

    /** Shift+P or Shift+N: rebuild the region labels after a toggle of what they show. */
    public void labelsShownChanged() {
        regionLabels = new ArrayList<>();
        int[] labelByRegion = partLabels.labelByRegion;
        for (int region = 0; regions != null && region < regions.regionCount; region++) {
            int label = region < labelByRegion.length ? labelByRegion[region] : MeshLabels.UNLABELLED;
            String name = showPartNames && label != MeshLabels.UNLABELLED ? partLabels.names.get(label) : "";
            String number = showRegionNumbers ? REGION_NUMBER_PREFIX + region : "";
            String text = number.isEmpty() || name.isEmpty() ? number + name : number + " " + name;
            if (!text.isEmpty()) {
                HyperString regionLabel = new HyperString();
                regionLabel.addWord(text, RingScene.LABEL_FILL_COLOUR, RingScene.LABEL_BORDER_COLOUR,
                        RingScene.LABEL_BORDER_PIXELS);
                regionLabel.setData(region);
                regionLabel.anchor.set(regions.regionLabelPoint[RingRegions.TRIANGLE_CORNERS * region],
                        regions.regionLabelPoint[RingRegions.TRIANGLE_CORNERS * region + 1],
                        regions.regionLabelPoint[RingRegions.TRIANGLE_CORNERS * region + 2]);
                regionLabel.anchorCamera = scene.camera;
                regionLabel.anchorDepthLift = (float) (RingTool.NUMBER_DEPTH_TIE_PIXELS * scene.camera
                        .worldPerPixel(regionLabel.anchor.x, regionLabel.anchor.y, regionLabel.anchor.z));
                regionLabels.add(regionLabel);
            }
        }
    }

    /** Resolve, recolour and relabel {@link #partLabels} after an edit, which a save writes. */
    public void labelsChanged() {
        labelsUnsaved = true;
        resolveLabels();
        redraw();
    }

    /** Resolve {@link #partLabels} against the regions, colour the parts and rebuild the labels. */
    private void resolveLabels() {
        if (regions != null) {
            partLabels.resolve(regions, Palettes.BRIGHT_PATCH);
        }
        labelsShownChanged();
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
        for (int label : partLabels.labelByRegion) {
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
     * Whether the region colours are wanted now: switched on, in the region tool, or while the
     * ring tool's bisector previews the regions it would split.
     *
     * @return true when the regions draw in their colours
     */
    public boolean coloured() {
        return visible || scene.activeTool == scene.regionTool
                || scene.activeTool == scene.ringTool
                        && scene.ringTool.activeRingSubTool().showsRegions();
    }

    /**
     * One frame: once the camera holds still at a new view, resize the labels' depth ties to it;
     * show or drop the regions when what wants them changed, rebuild them when the rings or
     * surface changed, and step the explosion.
     */
    public void perFrame() {
        HalfEdgeMeshRuntime runtime = scene.surfaceRuntime();
        MeshTopology surface = scene.halfEdgeSurface();
        if (scene.cameraStill && !scene.camera.view.equals(labelsTiedView)) {
            labelsTiedView.set(scene.camera.view);
            for (HyperString label : regionLabels) {
                label.anchorDepthLift = (float) (RingTool.NUMBER_DEPTH_TIE_PIXELS
                        * scene.camera.worldPerPixel(label.anchor.x, label.anchor.y, label.anchor.z));
            }
        }
        boolean coloured = coloured();
        if (runtime == null || surface == null || surface.faceCount() == 0
                || !coloured && hiddenByActiveFace.length == 0 && explosion.target == 0f
                        && !explosion.exploded()) {
            if (shown && runtime != null) {
                runtime.clearTags();
                runtime.setShaderMode(shaderModeBefore);
            }
            if (shown) {
                scene.ringTool.markRings(Map.of(), Set.of());
            }
            shown = false;
            return;
        }
        if (regions == null || regions.mesh != surface
                || builtRingRevision != scene.ringTool.ringRevision) {
            rebuild();
        }
        if (!shown || coloured != shownColoured) {
            if (!shown) {
                shaderModeBefore = runtime.getShaderMode();
                markRings();
            }
            shown = true;
            redraw();
        }
        explosion.step((float) Clock.deltaTime(), runtime.tagOffsets);
    }

    /**
     * Rebuild the regions from the rings as they stand: a new surface gets new regions, whole
     * again and named by the labels its graph saved; otherwise only what changed rings bound is
     * re-flooded. Then the flatness, colours, notes, labels and explosion follow, and it is logged.
     */
    public void rebuild() {
        MeshTopology surface = scene.halfEdgeSurface();
        if (surface == null || !scene.ringTool.readyCreases(surface)) {
            return;
        }
        long start = System.nanoTime();
        Map<String, boolean[]> rings = scene.ringTool.liveRingMarks();
        boolean fresh = regions == null || regions.mesh != surface;
        int absorbBelow = absorbSlivers ? RingRegions.SLIVER_FACES : 0;
        if (!fresh && regions.absorbBelowFaces == absorbBelow
                && Arrays.equals(rings.keySet().toArray(), regions.ringLabels)
                && Arrays.equals(rings.values().toArray(), regions.ringMarksByEdgeId)) {
            // An edit that left every live mask as it was, such as a step of a re-opened
            // bisector's drag (its draft walls nothing until confirmed), leaves the regions be.
            builtRingRevision = scene.ringTool.ringRevision;
            return;
        }
        if (fresh) {
            regions = new RingRegions(surface, new String[0], new boolean[0][]);
            // A new surface is a model the graph just ran: its labels are the ones it saved.
            partLabels = new MeshLabels(MeshLabels.inBundle(scene.getGeometryBundle()));
            labelsUnsaved = false;
            hiddenByActiveFace = new boolean[0];
            if (scene.surfaceRuntime() != null) {
                scene.surfaceRuntime().uploadFacePickBuffer(surface, hiddenByActiveFace);
            }
        }
        regions.rebuild(rings.keySet().toArray(new String[0]), rings.values().toArray(new boolean[0][]),
                absorbBelow, scene.ringTool.creases, Palettes.REGION);
        builtRingRevision = scene.ringTool.ringRevision;
        revision++;
        explosion.measure(surface, regions.regionByActiveFace, regions.regionCount);
        hiddenRingLabels = regions.hiddenRingLabels(hiddenByActiveFace);
        // Points name regions, not region numbers, so every rebuild resolves them again.
        resolveLabels();
        if (shown) {
            markRings();
            redraw();
        }
        summary = String.format(Locale.ROOT, "%d regions, %d slivers under %d faces (white)%s; "
                + "rings in pink: %d split nothing; %s and drawn in %.0f ms, "
                + "%d faces re-flooded; %s", regions.regionCount, regions.slivers,
                RingRegions.SLIVER_FACES, absorbSlivers ? " absorbed" : "",
                regions.noteByRingLabel.size(), fresh ? "built" : "updated",
                (System.nanoTime() - start) / 1e6, regions.refloodedFaces, partsSummary());
        Platforms.get().log(LOG_PREFIX + summary);
    }

    /** Hand the ring tool the notes and hidden rings of the regions as built. */
    private void markRings() {
        scene.ringTool.markRings(regions.noteByRingLabel, hiddenRingLabels);
    }

    /**
     * While the regions show, hand the runtime one draw range per region, hidden faces in none, in
     * its region or part colour, grey outside a selection, or the surface's colour with colours off.
     */
    public void redraw() {
        HalfEdgeMeshRuntime runtime = scene.surfaceRuntime();
        MeshTopology surface = scene.halfEdgeSurface();
        if (!shown || runtime == null || regions == null || regions.mesh != surface) {
            return;
        }
        boolean coloured = coloured();
        shownColoured = coloured;
        RingRegionTool tool = scene.regionTool;
        boolean dimming = scene.activeTool == tool && tool.selectedCount() > 0
                && tool.selectedRegions.length == regions.regionCount;
        // By part, a labelled region takes its part's colour, touching parts apart, and every
        // unlabelled one the same dark grey, so what is left to name stands out.
        Vector4f grey = Color.GREY.toVector4f();
        int[] labelByRegion = partLabels.labelByRegion;
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
                        : Palettes.BRIGHT_PATCH.shadedColor(
                                partLabels.colouring.colourByNode[labelByRegion[region]]).toVector4f();
            } else {
                colourByRegion[region] = regions.isSliver(region) ? SLIVER_COLOUR
                        : Palettes.REGION.shadedColor(regions.colouring.colourByNode[region])
                                .toVector4f();
            }
            tagByRegion[region] = RingRegionTool.regionTag(region);
        }
        int[] groupByActiveFace = regions.regionByActiveFace;
        if (hiddenByActiveFace.length == groupByActiveFace.length) {
            groupByActiveFace = groupByActiveFace.clone();
            for (int activeFace = 0; activeFace < groupByActiveFace.length; activeFace++) {
                if (hiddenByActiveFace[activeFace]) {
                    groupByActiveFace[activeFace] = MeshTopology.NONE;
                }
            }
        }
        runtime.setShaderMode(coloured ? HalfEdgeMeshRuntime.ShaderMode.STAGES : shaderModeBefore);
        runtime.setFaceGroups(surface, groupByActiveFace, colourByRegion, tagByRegion);
    }
}
