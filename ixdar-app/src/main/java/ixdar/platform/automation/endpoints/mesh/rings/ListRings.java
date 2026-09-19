package ixdar.platform.automation.endpoints.mesh.rings;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.annotations.automation.RouteParamType;
import ixdar.geometry.mesh.data.MeshSkeletonExtractor;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.RingCandidateExtractor;
import ixdar.geometry.mesh.data.RingCandidates;
import ixdar.geometry.mesh.data.load.MeshLoader;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshEngine;
import ixdar.platform.automation.AutomationEndpoint;

/**
 * Ranked neck-ring rows for a mesh file, sorted by descending neckness.
 */
@AutomationRouteAnnotation(path = "/mesh/rings/list", method = APIMethod.POST)
public class ListRings extends AutomationEndpoint implements AutomationRoute {

    /** Body key naming the mesh file to propose rings on. */
    public static final String PATH = "path";

    /** Body key of the skeleton's voxel resolution. */
    public static final String RESOLUTION = "resolution";

    /** Body key of the neckness threshold. */
    public static final String MIN_NECKNESS = "min_neckness";

    /** Response key carrying the success flag. */
    public static final String OK = "ok";

    /** Decimals every number in a row is rounded to, so two runs return the same bytes. */
    public static final int ROW_DECIMALS = 6;

    /** Coordinates per point in a packed position array. */
    public static final int COORDINATES_PER_POINT = 3;

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        String path = body.has(PATH) ? body.get(PATH).getAsString() : "";
        File file = resolvePath(path);
        JsonObject out = new JsonObject();
        if (file == null) {
            out.addProperty(OK, false);
            out.addProperty("error", "File not found: " + path);
            return out;
        }
        RingCandidateExtractor extractor = new RingCandidateExtractor();
        if (body.has(RESOLUTION)) {
            extractor.resolution = body.get(RESOLUTION).getAsInt();
        }
        if (body.has(MIN_NECKNESS)) {
            extractor.minimumNeckness = body.get(MIN_NECKNESS).getAsFloat();
        }
        MeshTopology mesh = HalfEdgeMeshEngine.fromMeshTopology(
                MeshLoader.load(file.getAbsolutePath()));
        RingCandidates rings = extractor.extract(mesh);

        out.addProperty(OK, true);
        out.addProperty(PATH, file.getAbsolutePath());
        out.addProperty(RESOLUTION, extractor.resolution);
        out.addProperty(MIN_NECKNESS, rounded(extractor.minimumNeckness));
        out.addProperty("ring_count", rings.ringCount);
        out.add("rings", rows(mesh, rings));
        JsonObject rejected = new JsonObject();
        rejected.addProperty("examined", rings.boundariesExamined);
        rejected.addProperty("grown", rings.rejectedGrown);
        rejected.addProperty("collapsed", rings.rejectedCollapsed);
        rejected.addProperty("merged", rings.rejectedMerged);
        rejected.addProperty("below_threshold", rings.rejectedBelowThreshold);
        rejected.addProperty("unclosed", rings.rejectedUnclosed);
        rejected.addProperty("not_girdling", rings.rejectedNotGirdling);
        out.add("rejected", rejected);
        return out;
    }

    /**
     * The ranked rows, in ring order, with every number rounded so the response is byte-stable.
     *
     * @param mesh  surface the rings' edge ids belong to
     * @param rings candidates to render
     * @return one object per ring
     */
    private static JsonArray rows(MeshTopology mesh, RingCandidates rings) {
        JsonArray array = new JsonArray();
        for (int ring = 0; ring < rings.ringCount; ring++) {
            JsonObject row = new JsonObject();
            row.addProperty("ring", ring);
            row.addProperty("neckness", rounded(rings.neckness[ring]));
            row.addProperty("length", rounded(rings.length[ring]));
            row.addProperty("seed_length", rounded(rings.seedLength[ring]));
            row.addProperty("mean_radius", rounded(rings.meanRadius[ring]));
            row.addProperty("skeleton_radius", rounded(rings.skeletonRadius[ring]));
            JsonArray centroid = new JsonArray();
            for (int axis = 0; axis < COORDINATES_PER_POINT; axis++) {
                centroid.add(rounded(rings.centroid[COORDINATES_PER_POINT * ring + axis]));
            }
            row.add("centroid", centroid);
            row.addProperty("branch_one_side", rings.branchOnOneSide[ring]);
            row.addProperty("branch_other_side", rings.branchOnOtherSide[ring]);
            row.addProperty("region_one_side", rings.regionOnOneSide[ring]);
            row.addProperty("region_other_side", rings.regionOnOtherSide[ring]);
            row.addProperty("area_one_side", rounded(rings.areaOnOneSide[ring]));
            row.addProperty("area_other_side", rounded(rings.areaOnOtherSide[ring]));
            int[] edgeIds = rings.edgeIdsOf(ring);
            row.addProperty("edge_count", edgeIds.length);
            row.addProperty("closed_cycle", RingCandidates.marksOneClosedCycle(mesh, edgeIds));
            array.add(row);
        }
        return array;
    }

    private static BigDecimal rounded(float value) {
        return BigDecimal.valueOf(value).setScale(ROW_DECIMALS, RoundingMode.HALF_UP);
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .commandName("rings-list")
                .description("Rank the neck rings a mesh's skeleton proposes, most neck-like first.")
                .param(PATH, RouteParamType.STRING, true, "",
                        "Path to the mesh file to propose rings on.",
                        "ixdar-app/test/resources/quadlayout/figure_8/fertility_in_tri.off")
                .param(RESOLUTION, RouteParamType.INT, false,
                        String.valueOf(MeshSkeletonExtractor.NUM_128),
                        "Voxel resolution for the skeleton.", "128")
                .param(MIN_NECKNESS, RouteParamType.FLOAT, false,
                        String.valueOf(RingCandidateExtractor.DEFAULT_MINIMUM_NECKNESS),
                        "Neckness a ring must reach: the skeleton's local circumference over the ring's length.",
                        "0.6")
                .responseHint("{ok, path, resolution, ring_count, rings:[{ring, neckness, length, "
                        + "seed_length, centroid, edge_count, ...}], rejected:{...}}")
                .build();
    }
}
