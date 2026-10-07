package ixdar.scenes.regions;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.joml.Vector4f;

import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.RingRegions;
import ixdar.graphics.render.color.Color;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.platform.Platforms;
import ixdar.scenes.ring.RingScene;

/**
 * The editing scene's region colours: the regions the ring tool's rings cut the surface into,
 * updated incrementally as rings change, drawn with neighbours in different colours and slivers in
 * white, while the region tool is active or the layer is switched on.
 */
public final class RegionLayer {

    public static final String LOG_PREFIX = "[regions] ";

    public static final float UNSELECTED_GREY = 0.42f;

    public static final Vector4f SLIVER_COLOUR = Color.WHITE.toVector4f();

    public static final String HANDLE_NOTE = "handle";

    public static final String SPLITS_NOTHING_NOTE = "splits nothing";

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
     * The note the ring tool draws beside a marked ring, by label: {@link #HANDLE_NOTE} for a ring
     * that loops a handle, {@link #SPLITS_NOTHING_NOTE} for one with a single region on both sides.
     */
    public Map<String, String> ringNoteByLabel = Map.of();

    /** One-line summary of the regions and the last update, as logged. */
    public String lastRow = "";

    private int builtRingRevision = -1;

    private boolean builtAbsorbing;

    private boolean shown;

    private boolean overlayStale;

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

    /**
     * One frame: while the layer is on or the region tool is active, bring the regions up to date with the rings, re-flooding only the
     * regions a changed ring bounds, and draw them; otherwise drop the colours once.
     */
    public void perFrame() {
        HalfEdgeMeshRuntime runtime = scene.surfaceRuntime();
        MeshTopology surface = scene.halfEdgeSurface();
        boolean wanted = visible || scene.activeTool == scene.regionTool;
        if (runtime == null || surface == null || surface.faceCount() == 0 || !wanted) {
            if (shown && runtime != null) {
                runtime.clearTags();
                runtime.setShaderMode(shaderModeBefore);
            }
            if (shown) {
                scene.ringTool.markRings(Map.of());
            }
            shown = false;
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
                colouring.colourByRegion = new int[0];
            }
            regions.absorbBelowFaces = absorbSlivers ? RingRegions.SLIVER_FACES : 0;
            if (fresh || builtAbsorbing != absorbSlivers) {
                regions.build();
            } else {
                regions.update(labels, masks);
            }
            colouring.colour(regions);
            builtRingRevision = scene.ringTool.ringRevision;
            builtAbsorbing = absorbSlivers;
            revision++;
            Map<String, String> notes = new TreeMap<>();
            for (int ring = 0; ring < regions.ringLabels.length; ring++) {
                if (regions.ringSplitsNothing[ring]) {
                    notes.put(regions.ringLabels[ring], SPLITS_NOTHING_NOTE);
                } else if (regions.ringIsWall[ring] && !regions.ringSeparates[ring]) {
                    notes.put(regions.ringLabels[ring], HANDLE_NOTE);
                }
            }
            ringNoteByLabel = notes;
            rebuilt = fresh ? "built" : "updated";
            overlayStale = true;
        }
        if (!shown) {
            shaderModeBefore = runtime.getShaderMode();
            shown = true;
            overlayStale = true;
        }
        scene.ringTool.markRings(ringNoteByLabel);
        if (!overlayStale) {
            return;
        }
        overlayStale = false;
        // One draw range per region under its region tag: its palette colour, white for a
        // sliver, grey while the region tool has a selection the region is not in.
        RingRegionTool tool = scene.regionTool;
        boolean dimming = scene.activeTool == tool && tool.selectedCount() > 0
                && tool.selectedRegions.length == regions.regionCount;
        Vector4f grey = new Vector4f(UNSELECTED_GREY, UNSELECTED_GREY, UNSELECTED_GREY, 1f);
        Vector4f[] colourByRegion = new Vector4f[regions.regionCount];
        String[] tagByRegion = new String[regions.regionCount];
        for (int region = 0; region < regions.regionCount; region++) {
            colourByRegion[region] = dimming && !tool.selectedRegions[region] ? grey
                    : regions.isSliver(region) ? SLIVER_COLOUR
                            : RegionColouring.paletteColor(colouring.colourByRegion[region])
                                    .toVector4f();
            tagByRegion[region] = RingRegionTool.regionTag(region);
        }
        runtime.setShaderMode(HalfEdgeMeshRuntime.ShaderMode.STAGES);
        runtime.setFaceGroups(surface, regions.regionByActiveFace, colourByRegion, tagByRegion);
        if (rebuilt == null) {
            return;
        }
        int slivers = 0;
        for (int region = 0; region < regions.regionCount; region++) {
            slivers += regions.isSliver(region) ? 1 : 0;
        }
        int handles = 0;
        int splittingNothing = 0;
        for (String note : ringNoteByLabel.values()) {
            handles += HANDLE_NOTE.equals(note) ? 1 : 0;
            splittingNothing += SPLITS_NOTHING_NOTE.equals(note) ? 1 : 0;
        }
        lastRow = String.format(Locale.ROOT, "%d regions, %d slivers under %d faces (white)%s; "
                + "rings in red: %d loop a handle, %d split nothing; %s and drawn in %.0f ms, "
                + "%d faces re-flooded", regions.regionCount, slivers, RingRegions.SLIVER_FACES,
                absorbSlivers ? " absorbed" : "", handles, splittingNothing, rebuilt,
                (System.nanoTime() - start) / 1e6, regions.refloodedFaces);
        Platforms.get().log(LOG_PREFIX + lastRow);
    }
}
