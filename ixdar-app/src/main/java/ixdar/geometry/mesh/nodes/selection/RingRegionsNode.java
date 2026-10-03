package ixdar.geometry.mesh.nodes.selection;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.RingRegions;
import ixdar.geometry.mesh.nodes.api.BoolField;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.IntField;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.nodes.data.TagGeometryNode;

/**
 * Splits a surface into the regions its ring mark loops enclose, labels every face with its
 * region, and selects regions by number or surface point.
 */
@MeshNodeAnnotation(id = "ring_regions")
public class RingRegionsNode implements MeshNode {

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE,
            null);
    public static final InputPort LABELS = new InputPort("labels", PortType.STRING, "");
    public static final InputPort SELECT = new InputPort("select", PortType.STRING, "");
    public static final InputPort TAG = new InputPort("tag", PortType.STRING, "");
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name,
            PortType.GEOMETRY_BUNDLE);
    public static final OutputPort REGION = new OutputPort("region", PortType.INT);
    public static final OutputPort SELECTION = new OutputPort("selection", PortType.BOOLEAN);
    public static final OutputPort REGION_COUNT = new OutputPort("region_count", PortType.INT);
    public static final OutputPort REPORT = new OutputPort("report", PortType.STRING);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, LABELS, SELECT, TAG);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT, REGION, SELECTION, REGION_COUNT, REPORT);
    }

    @Override
    public String description() {
        return "Splits the surface into the regions its ring mark loops enclose: faces flood "
                + "across every edge no closed ring marks, and each region records its bounding "
                + "rings and which side of each it lies on (distal is a ring's smaller side). An "
                + "open ring is reported by name and walls nothing; crossing and non-separating "
                + "rings are reported too.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name,
                "Input: a surface carrying ring edge marks (spline_ring, loop_through_points, "
                        + "ring_candidates). Output: the same bundle with the regions in the "
                        + RingRegions.SLOT + " slot and, when `tag` is set, the selection as a "
                        + "vertex tag.",
                LABELS.name,
                "Comma-separated edge-mark labels to use as rings. Empty takes every boolean "
                        + "edge-mark label on the bundle, which after the ring nodes is every "
                        + "ring.",
                SELECT.name,
                "Regions to select, terms separated by ';': `region N` and `point x,y,z` (the "
                        + "region holding the face nearest the point, stable when rings are "
                        + "added).",
                TAG.name,
                "When set, the vertices of the selected faces are tagged with this name in the "
                        + TagGeometryNode.TAGS_SLOT + " slot, merged with existing tags, so "
                        + "tag consumers see the selection as a part label.",
                REGION.name,
                "Per-face INT field: the region of each face.",
                SELECTION.name,
                "Per-face BOOLEAN mask, true on every face of a selected region; feed it to "
                        + "separate_geometry or delete_geometry with domain FACE.",
                REGION_COUNT.name,
                "Number of regions.",
                REPORT.name,
                "The region count, one line per region with its face count, area share and "
                        + "bounding rings, then one line per problem ring.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle bundle = ctx.getInput(GEOMETRY.name, GeometryBundle.class);
        if (bundle == null) {
            bundle = GeometryBundle.empty();
        }
        MeshTopology mesh = bundle.mesh();
        if (mesh == null || mesh.faceCount() == 0) {
            ctx.setOutput(GEOMETRY.name, bundle);
            ctx.setOutput(REGION.name, new IntField(new int[0]));
            ctx.setOutput(SELECTION.name, new BoolField(new boolean[0]));
            ctx.setOutput(REGION_COUNT.name, 0);
            ctx.setOutput(REPORT.name, "");
            return;
        }
        RingRegions regions = RingRegions.ofBundle(bundle,
                ctx.getInput(LABELS.name, String.class));
        boolean[] selection = regions.selectionByActiveFace(
                regions.select(ctx.getInput(SELECT.name, String.class)));
        GeometryBundle out = bundle.withSlot(RingRegions.SLOT, regions);
        String tag = ctx.getInput(TAG.name, String.class);
        if (tag != null && !tag.isBlank()) {
            Map<Integer, Integer> activeVertexById = new HashMap<>();
            for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
                activeVertexById.put(mesh.vertexIdAt(activeVertex), activeVertex);
            }
            boolean[] tagged = new boolean[mesh.vertexCount()];
            for (int activeFace = 0; activeFace < selection.length; activeFace++) {
                if (!selection[activeFace]) {
                    continue;
                }
                int faceId = mesh.faceIdAt(activeFace);
                for (int corner = 0; corner < mesh.faceVertexCount(faceId); corner++) {
                    tagged[activeVertexById.get(mesh.faceVertexAt(faceId, corner))] = true;
                }
            }
            Map<String, boolean[]> tags = new HashMap<>();
            Map<String, boolean[]> existing = TagGeometryNode.getTags(bundle);
            if (existing != null) {
                tags.putAll(existing);
            }
            tags.put(tag.strip(), tagged);
            out = out.withSlot(TagGeometryNode.TAGS_SLOT, tags);
        }
        ctx.setOutput(GEOMETRY.name, out);
        ctx.setOutput(REGION.name, new IntField(Arrays.copyOf(regions.regionByActiveFace,
                regions.regionByActiveFace.length)));
        ctx.setOutput(SELECTION.name, new BoolField(selection));
        ctx.setOutput(REGION_COUNT.name, regions.regionCount);
        ctx.setOutput(REPORT.name, String.join("\n", regions.reportLines()));
    }
}
