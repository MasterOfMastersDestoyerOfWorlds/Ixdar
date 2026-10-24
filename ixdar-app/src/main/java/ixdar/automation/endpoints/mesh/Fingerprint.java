package ixdar.automation.endpoints.mesh;

import java.io.IOException;
import java.util.Map;
import java.util.TreeSet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ixdar.annotations.automation.APIMethod;
import ixdar.annotations.automation.AutomationRoute;
import ixdar.annotations.automation.AutomationRouteAnnotation;
import ixdar.annotations.automation.RouteDoc;
import ixdar.geometry.mesh.MeshCanonicalFingerprint;
import ixdar.geometry.mesh.data.bundle.EdgeMarks;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.automation.AutomationEndpoint;
import ixdar.nodegraph.runtime.NodeGraphRuntime;
import ixdar.scenes.mesh.MeshNodeViewerScene;

/**
 * The canonical fingerprint of a mesh and what it carries: the {@code /mesh/fingerprint} route for
 * the viewer's mesh, and the same object for any bundle or finished graph, which the golden
 * fingerprint tests and the snapshot entry point record.
 */
@AutomationRouteAnnotation(path = "/mesh/fingerprint", method = APIMethod.GET)
public class Fingerprint extends AutomationEndpoint implements AutomationRoute {
    public static final String ALGORITHM = "algorithm";

    public static final String LABEL = "label";

    public static final String EDGE_COUNT = "edgeCount";

    public static final String FINGERPRINT = "fingerprint";

    public static final String SHA256 = "sha256";

    /**
     * Each boolean edge-marks label a bundle carries, in the slot's order: its label, its marked
     * edge count and the geometry-keyed fingerprint of the marked set.
     *
     * @param bundle the bundle, its mesh the masks index by edge id
     * @return one object per label, empty when the bundle marks no edges
     */
    public static JsonArray edgeMarks(GeometryBundle bundle) {
        JsonArray edgeMarks = new JsonArray();
        if (!(bundle.slots().get(EdgeMarks.SLOT) instanceof Map<?, ?> marks)) {
            return edgeMarks;
        }
        for (Map.Entry<?, ?> entry : marks.entrySet()) {
            if (!(entry.getValue() instanceof boolean[] flags)) {
                continue;
            }
            int marked = 0;
            for (boolean edgeMarked : flags) {
                marked += edgeMarked ? 1 : 0;
            }
            JsonObject mark = new JsonObject();
            mark.addProperty(LABEL, String.valueOf(entry.getKey()));
            mark.addProperty(EDGE_COUNT, marked);
            mark.addProperty(FINGERPRINT, EdgeMarks.fingerprint(bundle.mesh(), flags));
            edgeMarks.add(mark);
        }
        return edgeMarks;
    }

    /**
     * A bundle's fingerprint: the mesh's canonical SHA-256 and element counts, its sorted slot
     * names and its edge marks. Every value is a count or a hash, so equal geometry writes equal text.
     *
     * @param bundle the bundle, carrying a mesh
     * @return {@code {algorithm, sha256, vertexCount, edgeCount, faceCount, triangleCount,
     *         boundaryEdgeCount, slots, edgeMarks}}
     */
    public static JsonObject bundleFingerprint(GeometryBundle bundle) {
        MeshTopology mesh = bundle.mesh();
        JsonObject result = new JsonObject();
        result.addProperty(ALGORITHM, MeshCanonicalFingerprint.ALGORITHM_ID);
        result.addProperty(SHA256, MeshCanonicalFingerprint.sha256Hex(mesh));
        result.addProperty("vertexCount", mesh.vertexCount());
        result.addProperty(EDGE_COUNT, mesh.edgeCount());
        result.addProperty("faceCount", mesh.faceCount());
        result.addProperty("triangleCount", MeshCanonicalFingerprint.triangleCount(mesh));
        result.addProperty("boundaryEdgeCount", mesh.boundaryEdgeCount());
        JsonArray slots = new JsonArray();
        for (String slot : new TreeSet<>(bundle.slots().keySet())) {
            slots.add(slot);
        }
        result.add("slots", slots);
        result.add("edgeMarks", edgeMarks(bundle));
        return result;
    }

    /**
     * A finished graph's fingerprint: every output of its last statement a scene would show, by
     * port, in the order the node declares them. A bundle or mesh is written as
     * {@link #bundleFingerprint}; an arc network as its working surface's plus its node, arc and
     * patch counts.
     *
     * @param run the executed graph
     * @return {@code {statement, outputs: {port: fingerprint}}}; outputs is empty when the last
     *         statement produced nothing shown
     */
    public static JsonObject graphFingerprint(NodeGraphRuntime run) {
        JsonObject outputs = new JsonObject();
        String last = run.lastId();
        Map<String, Object> produced = last == null || run.node(last) == null ? Map.of()
                : run.node(last).getOutputsSnapshot();
        for (Map.Entry<String, Object> output : produced.entrySet()) {
            if (output.getValue() instanceof GeometryBundle bundle && bundle.mesh() != null) {
                outputs.add(output.getKey(), bundleFingerprint(bundle));
            } else if (output.getValue() instanceof MeshTopology mesh) {
                outputs.add(output.getKey(), bundleFingerprint(GeometryBundle.ofMesh(mesh)));
            } else if (output.getValue() instanceof ArcNetwork network) {
                JsonObject shown = bundleFingerprint(GeometryBundle.ofMesh(network.topology.copy));
                shown.addProperty("nodeCount", network.nodes.size());
                shown.addProperty("arcCount", network.arcs.size());
                shown.addProperty("patchCount", network.patches.size());
                outputs.add(output.getKey(), shown);
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("statement", last == null ? "" : last);
        result.add("outputs", outputs);
        return result;
    }

    @Override
    public JsonObject endpointHandler(JsonObject body) throws IOException {
        try {
            return runtime.runOnMainThread(() -> {
                boolean viewerActive = runtime.canvas instanceof MeshNodeViewerScene;
                GeometryBundle shown = viewerActive
                        ? ((MeshNodeViewerScene) runtime.canvas).getGeometryBundle() : null;
                if (shown == null || shown.mesh() == null) {
                    JsonObject failed = failure(viewerActive ? "Mesh not loaded yet"
                            : "MeshNodeViewerScene is not active");
                    failed.addProperty(ALGORITHM, MeshCanonicalFingerprint.ALGORITHM_ID);
                    return failed;
                }
                JsonObject result = bundleFingerprint(shown);
                result.addProperty(OK, true);
                return result;
            });
        } catch (Exception e) {
            return failure(e.getMessage() == null ? "" : e.getMessage());
        }
    }

    @Override
    public RouteDoc describe() {
        return RouteDoc.builder()
                .description("Compute the canonical SHA-256 fingerprint of the active viewer mesh.")
                .responseHint("{algorithm, ok, sha256, vertexCount, edgeCount, faceCount, triangleCount,"
                        + " boundaryEdgeCount, slots, edgeMarks:[{label, edgeCount, fingerprint}]}")
                .build();
    }
}
