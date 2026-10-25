package unit.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.rings.RingRegions;
import ixdar.graphics.render.model.HalfEdgeMeshRuntime;
import ixdar.platform.Platforms;
import ixdar.platform.gl.GL;
import ixdar.platform.Platform;
import ixdar.platform.gl.lwjgl.DefaultBuffer;
import ixdar.scenes.ring.RingScene;

/**
 * Hiding and isolating regions on the capped cylinder of {@link RingRegionsTest}, cut by rings at
 * rows 2 and 5: the face-pick pass and the draw hold only the shown faces, a ring lying only on
 * hidden regions goes undrawn, and showing all brings every face back.
 */
class RegionHidingTest {

    private static final int STAND_IN_PLATFORM_ID = 7718;

    private static final int WINDOW_SIZE = 64;

    private static final int LOW_RING_ROW = 2;

    private static final int HIGH_RING_ROW = 5;

    private static final String LOW_RING = "ring_02";

    private static final String HIGH_RING = "ring_05";

    private static final String ADDED_RING = "ring_06";

    private static final float[] MIDDLE_POINT = { 1f, 0f, 1.75f };

    private static final float[] BOTTOM_POINT = { 1f, 0f, 0.5f };

    private static final int XYZ = 3;

    private static final int CHANNEL_MAX = 255;

    private static final int BITS_PER_CHANNEL = 8;

    private static final String GET_PLATFORM_ID = "getPlatformID";

    private Platform suitePlatform;

    private GL suiteGl;

    private MeshTopology cylinder;

    private RingScene scene;

    private float[] lastFloatUpload;

    private int lastIndexUploadLength;

    /**
     * A ring scene over the cylinder with its two rings and the region tool active, its surface
     * runtime on a GL that keeps the last float array and index buffer uploaded.
     */
    @BeforeEach
    void installScene() {
        suitePlatform = Platforms.get();
        suiteGl = Platforms.gl();
        Platform window = (Platform) Proxy.newProxyInstance(Platform.class.getClassLoader(),
                new Class<?>[] {Platform.class}, (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "getWindowWidth":
                        case "getFrameBufferWidth":
                        case "getWindowHeight":
                        case "getFrameBufferHeight":
                            return WINDOW_SIZE;
                        case GET_PLATFORM_ID:
                            return STAND_IN_PLATFORM_ID;
                        case "allocateFloats":
                            return new DefaultBuffer((Integer) arguments[0]);
                        default:
                            return method.getReturnType() == int.class ? 0
                                    : method.getReturnType() == String.class ? "" : null;
                    }
                });
        GL recordingGl = (GL) Proxy.newProxyInstance(GL.class.getClassLoader(),
                new Class<?>[] {GL.class}, (proxy, method, arguments) -> {
                    if (GET_PLATFORM_ID.equals(method.getName())) {
                        return STAND_IN_PLATFORM_ID;
                    }
                    if ("bufferData".equals(method.getName())) {
                        if (arguments[1] instanceof float[] floats) {
                            lastFloatUpload = floats.clone();
                        } else if (arguments[1] instanceof int[] indices) {
                            lastIndexUploadLength = indices.length;
                        }
                    }
                    Class<?> type = method.getReturnType();
                    return type == int.class ? 1 : type == boolean.class ? false
                            : type == float.class ? 0f : type == String.class ? "" : null;
                });
        Platforms.init(window, recordingGl);
        cylinder = RingRegionsTest.cappedCylinder();
        scene = new RingScene() {
            @Override
            public MeshTopology getMesh() {
                return cylinder;
            }

            @Override
            public MeshTopology halfEdgeSurface() {
                return cylinder;
            }
        };
        scene.initPoints();
        scene.setTools();
        scene.switchTool(scene.defaultTool);
        scene.meshRuntime = new HalfEdgeMeshRuntime();
        scene.meshRuntime.upload(cylinder);
        scene.ringMarksByLabel.put(LOW_RING,
                RingRegionsUpdateTest.rowMask(cylinder, LOW_RING_ROW, false));
        scene.ringMarksByLabel.put(HIGH_RING,
                RingRegionsUpdateTest.rowMask(cylinder, HIGH_RING_ROW, false));
        scene.switchTool(scene.regionTool);
    }

    /** Put the suite's platform back. */
    @AfterEach
    void restoreSuitePlatform() {
        Platforms.init(suitePlatform, suiteGl);
    }

    /**
     * A ring edit that leaves every live mask as it was, such as a step of a re-opened bisector's
     * drag on the crawfish, keeps the regions without a rebuild; a changed mask rebuilds them.
     */
    @Test
    void aRingRevisionThatLeavesEveryMaskAsItWasKeepsTheRegions() {
        scene.updateScene();
        int[] built = scene.regionLayer.regions.regionByActiveFace;
        int revision = scene.regionLayer.revision;
        scene.ringTool.invalidateRings();
        scene.updateScene();
        assertSame(built, scene.regionLayer.regions.regionByActiveFace,
                "an edit that changed no mask rebuilt the regions");
        assertEquals(revision, scene.regionLayer.revision);

        scene.ringMarksByLabel.put(ADDED_RING,
                RingRegionsUpdateTest.rowMask(cylinder, HIGH_RING_ROW + 1, false));
        scene.ringTool.invalidateRings();
        scene.updateScene();
        assertNotSame(built, scene.regionLayer.regions.regionByActiveFace,
                "a new ring left the regions as they were");
        assertEquals(revision + 1, scene.regionLayer.revision);
    }

    @Test
    void picksAndDrawsSkipHiddenFacesUntilShowAll() {
        // The first frame builds the regions, the second the region tool's pick buffer.
        scene.updateScene();
        scene.updateScene();
        RingRegions regions = scene.regionLayer.regions;
        assertEquals(2 + 1, regions.regionCount);
        assertEquals(allFaces(), pickedFaces(), "nothing hidden: every face picks");

        select(MIDDLE_POINT);
        int middle = regions.regionByActiveFace[regions.nearestActiveFace(MIDDLE_POINT[0],
                MIDDLE_POINT[1], MIDDLE_POINT[2])];
        scene.regionLayer.hideSelection(true);
        scene.updateScene();
        assertEquals(facesOf(regions, middle), pickedFaces(), "isolated: only the middle picks");
        assertEquals(XYZ * regions.regionFaceCount[middle], lastIndexUploadLength,
                "isolated: only the middle is drawn");
        assertEquals(Set.of(), scene.ringTool.hiddenRingLabels, "both rings bound the middle");
        scene.switchTool(scene.orbitTool);
        lastIndexUploadLength = -1;
        scene.updateScene();
        assertEquals(XYZ * regions.regionFaceCount[middle], lastIndexUploadLength,
                "another tool, colours off, still draws only the middle");
        assertEquals(HalfEdgeMeshRuntime.ShaderMode.LAMBERT, scene.meshRuntime.getShaderMode(),
                "with the colours off the surface keeps its own shading");
        scene.switchTool(scene.regionTool);

        select(MIDDLE_POINT);
        scene.regionLayer.hideSelection(false);
        scene.updateScene();
        assertEquals(Set.of(), pickedFaces(), "the isolated region hidden too: nothing picks");
        assertEquals(0, lastIndexUploadLength, "and nothing is drawn");
        assertEquals(0, scene.regionTool.selectedCount(), "hiding drops the selection");
        assertEquals(Set.of(LOW_RING, HIGH_RING), scene.ringTool.hiddenRingLabels);

        scene.regionLayer.showAll();
        select(BOTTOM_POINT);
        int bottom = regions.regionByActiveFace[regions.nearestActiveFace(BOTTOM_POINT[0],
                BOTTOM_POINT[1], BOTTOM_POINT[2])];
        scene.regionLayer.hideSelection(true);
        scene.updateScene();
        assertEquals(facesOf(regions, bottom), pickedFaces(), "isolated: only the bottom picks");
        assertEquals(Set.of(HIGH_RING), scene.ringTool.hiddenRingLabels,
                "the high ring bounds only hidden regions");

        scene.regionLayer.showAll();
        scene.updateScene();
        assertEquals(allFaces(), pickedFaces(), "shown again: every face picks");
        assertEquals(XYZ * 2 * (RingRegionsTest.SIDE_ROWS + 1) * RingRegionsTest.SEGMENTS_AROUND,
                lastIndexUploadLength, "shown again: every face is drawn");
        assertTrue(scene.ringTool.hiddenRingLabels.isEmpty());
    }

    private void select(float[] point) {
        scene.regionTool.pickedPoints.clear();
        scene.regionTool.pickedPoints.add(point.clone());
        scene.regionTool.reselect();
    }

    /**
     * The faces the last face-pick upload can name, decoded from its per-vertex id colours.
     */
    private Set<Integer> pickedFaces() {
        Set<Integer> faces = new TreeSet<>();
        if (scene.meshRuntime.facePick.vertexCount == 0) {
            return faces;
        }
        // Each pick vertex is its position, then its face's id as three colour channels.
        int stride = HalfEdgeMeshRuntime.FACE_PICK_LAYOUT.floatsPerVertex;
        for (int vertex = 0; vertex + stride <= lastFloatUpload.length; vertex += stride) {
            int code = 0;
            for (int channel = 0; channel < XYZ; channel++) {
                code = (code << BITS_PER_CHANNEL)
                        | Math.round(lastFloatUpload[vertex + XYZ + channel] * CHANNEL_MAX);
            }
            faces.add(code - 1);
        }
        return faces;
    }

    private Set<Integer> allFaces() {
        Set<Integer> faces = new TreeSet<>();
        for (int activeFace = 0; activeFace < cylinder.faceCount(); activeFace++) {
            faces.add(activeFace);
        }
        return faces;
    }

    private static Set<Integer> facesOf(RingRegions regions, int region) {
        Set<Integer> faces = new TreeSet<>();
        for (int activeFace = 0; activeFace < regions.regionByActiveFace.length; activeFace++) {
            if (regions.regionByActiveFace[activeFace] == region) {
                faces.add(activeFace);
            }
        }
        return faces;
    }
}
