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
import ixdar.geometry.mesh.data.paths.query.SurfaceConnection;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.rings.RegionExplosion;
import ixdar.geometry.mesh.data.rings.RingRegions;
import ixdar.graphics.render.color.Palette;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.gui.ui.text.HyperString;
import ixdar.platform.Clock;
import ixdar.platform.Platforms;
import ixdar.scenes.ring.RingScene;
import ixdar.tools.ring.RingTool;

/**
 * The editing scene's ring regions as shown: their region or part colours, the hidden faces no tool
 * draws or picks, and the region labels. Each change rebuilds what it touches.
 */
public final class RegionLayer {

    public static final String LOG_PREFIX = "[regions] ";

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

    /**
     * One label per region with anything to show, centred on the region: its number while the
     * scene shows numbers, then its part name while it shows labels.
     */
    public List<HyperString> regionLabels = new ArrayList<>();

    /**
     * The ring tool's {@code ringRevision} {@link #regions} were last built from; the next frame
     * rebuilds them when it falls behind, in every tool.
     */
    public int builtRingRevision = -1;

    /**
     * Exploded view of {@link #regions}, shown in every tool and re-measured when they are
     * rebuilt; X in the region tool and the terminal's {@code xp} set it.
     */
    public final RegionExplosion explosion = new RegionExplosion(RingRegionTool::regionTag);

    /**
     * Face graph of the shown surface the regions find where a ring that splits nothing stays
     * joined on, built the first time one does.
     */
    public SurfaceConnection joinPaths;

    /**
     * The scene palette the part colours and labels were last resolved in; a frame that finds the
     * scene wearing another resolves and redraws them again.
     */
    public Palette resolvedPalette;

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

    /** N or P: rebuild the region labels after the scene's numbers or labels were toggled. */
    public void labelsShownChanged() {
        regionLabels = new ArrayList<>();
        Palette palette = scene.palette;
        int[] labelByRegion = partLabels.labelByRegion;
        for (int region = 0; regions != null && region < regions.regionCount; region++) {
            int label = region < labelByRegion.length ? labelByRegion[region] : MeshLabels.UNLABELLED;
            String name = scene.showLabels && label != MeshLabels.UNLABELLED ? partLabels.names.get(label) : "";
            String number = scene.showNumbers ? REGION_NUMBER_PREFIX + region : "";
            String text = number.isEmpty() || name.isEmpty() ? number + name : number + " " + name;
            if (!text.isEmpty()) {
                HyperString regionLabel = new HyperString();
                regionLabel.addWord(text, palette.text, palette.background,
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
        resolvedPalette = scene.palette;
        if (regions != null) {
            partLabels.resolve(regions, resolvedPalette.categories);
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
     * Whether the region colours are wanted now: switched on, in the region tool, while the ring
     * tool's bisector previews the regions it would split, or while the part labels show.
     *
     * @return true when the regions draw in their colours
     */
    public boolean coloured() {
        return visible || colouredByPart() || scene.activeTool == scene.regionTool
                || scene.activeTool == scene.ringTool
                        && scene.ringTool.activeRingSubTool().showsRegions();
    }

    /**
     * Whether the regions take their part's colour rather than their own: exactly while the scene
     * shows the part labels and the regions have parts resolved.
     *
     * @return true when coloured regions are coloured by part
     */
    public boolean colouredByPart() {
        return scene.showLabels && !partLabels.names.isEmpty() && regions != null
                && partLabels.labelByRegion.length == regions.regionCount;
    }

    /**
     * One frame: retie the labels to a new still view, rebuild the regions on any ring or surface
     * change whichever tool is active, so they are current when read, then show, drop or explode them.
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
        boolean ready = runtime != null && surface != null && surface.faceCount() > 0;
        if (ready && (regions == null || regions.mesh != surface
                || builtRingRevision != scene.ringTool.ringRevision)) {
            rebuild();
        } else if (resolvedPalette != scene.palette) {
            // A palette swap: its part cycle may differ in length, and every colour is re-read.
            resolveLabels();
            redraw();
        }
        boolean coloured = coloured();
        if (!ready || !coloured && hiddenByActiveFace.length == 0 && explosion.target == 0f
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
                absorbBelow, scene.ringTool.creases, scene.palette.region);
        if (!regions.noteByRingLabel.isEmpty() || joinPaths != null && joinPaths.mesh == surface) {
            if (joinPaths == null || joinPaths.mesh != surface) {
                joinPaths = new SurfaceConnection(surface);
            }
            regions.measureJoins(joinPaths);
        }
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
        Palette palette = scene.palette;
        Vector4f dimmed = palette.mutedText.toVector4f();
        Vector4f unlabelled = palette.surface.toVector4f();
        Vector4f sliver = palette.warning.toVector4f();
        int[] labelByRegion = partLabels.labelByRegion;
        boolean byPart = colouredByPart();
        Vector4f[] colourByRegion = new Vector4f[regions.regionCount];
        String[] tagByRegion = new String[regions.regionCount];
        for (int region = 0; region < regions.regionCount; region++) {
            if (!coloured) {
                colourByRegion[region] = runtime.solidColor;
            } else if (dimming && !tool.selectedRegions[region]) {
                colourByRegion[region] = dimmed;
            } else if (byPart) {
                colourByRegion[region] = labelByRegion[region] == MeshLabels.UNLABELLED
                        ? unlabelled
                        : Palette.shadedEntry(palette.categories,
                                partLabels.colouring.colourByNode[labelByRegion[region]]).toVector4f();
            } else {
                colourByRegion[region] = regions.isSliver(region) ? sliver
                        : Palette.shadedEntry(palette.region, regions.colouring.colourByNode[region])
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
