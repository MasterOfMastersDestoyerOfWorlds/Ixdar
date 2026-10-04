package ixdar.geometry.mesh.nodes.selection;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.data.GeometryBundle;
import ixdar.geometry.mesh.data.MeshTopology;
import ixdar.geometry.mesh.data.RingRegionExtraction;
import ixdar.geometry.mesh.data.RingRegions;
import ixdar.geometry.mesh.data.paths.SurfaceSpline;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;
import ixdar.platform.Platforms;

/**
 * Takes the ring regions a selection names out of a surface as their own closed mesh, cut along
 * the rings' smooth splines and capped, ready for mesh_boolean.
 */
@MeshNodeAnnotation(id = "extract_ring_region", desktopOnly = true)
public class ExtractRingRegionNode implements MeshNode {

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE,
            null);
    public static final InputPort LABELS = new InputPort("labels", PortType.STRING, "");
    public static final InputPort SELECT = new InputPort("select", PortType.STRING, "");
    public static final InputPort CAP = new InputPort("cap", PortType.BOOLEAN, true);
    public static final InputPort SNAP = new InputPort("snap", PortType.FLOAT,
            (float) RingRegionExtraction.DEFAULT_SNAP_FRACTION, 0f, 0.5f);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name,
            PortType.GEOMETRY_BUNDLE);
    public static final OutputPort OPEN = new OutputPort("open", PortType.GEOMETRY_BUNDLE);
    public static final OutputPort REPORT = new OutputPort("report", PortType.STRING);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, LABELS, SELECT, CAP, SNAP);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT, OPEN, REPORT);
    }

    @Override
    public String description() {
        return "Takes the selected ring regions out of the surface as their own closed mesh. "
                + "Regions are chosen by ring_regions' edge-set flood, but the cut follows each "
                + "ring's smooth spline: a copy of the surface is split at the spline's edge "
                + "crossings, re-flooded with the spline as wall, the selected pieces kept, and "
                + "every loop the cut opened capped with repair_mesh's Liepa filler. The input "
                + "surface is not modified. A ring with no traced spline is cut along its edges.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name,
                "Input: a surface carrying ring edge marks and, from spline_ring, the traced "
                        + "splines in the " + SurfaceSpline.SLOT + " slot; quads and larger "
                        + "polygons are split where a spline crosses or passes inside them. "
                        + "Output: the extracted closed mesh alone, all triangles, no slots "
                        + "carried, ready for mesh_boolean.",
                LABELS.name,
                "Comma-separated ring labels, as ring_regions takes them; empty takes every "
                        + "boolean edge-mark label.",
                SELECT.name,
                "Regions to extract, as ring_regions' select: `region N` and `point x,y,z` "
                        + "terms separated by ';'.",
                CAP.name,
                "When true the cut loops are capped; false outputs the open cut on both ports.",
                SNAP.name,
                "A spline crossing within this fraction of its edge from a vertex or another "
                        + "crossing merges into it instead of cutting a sliver.",
                OPEN.name,
                "The selected pieces before capping, their boundary on the splines.",
                REPORT.name,
                "The cut, the pieces kept, the boundary distances against the spline and the "
                        + "snapped loops, the cap with closed=true|false and the Manifold "
                        + "kernel's verdict, then any problems.");
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle bundle = ctx.getInput(GEOMETRY.name, GeometryBundle.class);
        MeshTopology mesh = bundle == null ? null : bundle.mesh();
        if (mesh == null || mesh.faceCount() == 0) {
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            ctx.setOutput(OPEN.name, GeometryBundle.empty());
            ctx.setOutput(REPORT.name, "");
            return;
        }
        RingRegions regions = RingRegions.ofBundle(bundle, ctx.getInput(LABELS.name, String.class));
        SurfaceSpline[] splines = new SurfaceSpline[regions.ringLabels.length];
        for (int ring = 0; ring < splines.length; ring++) {
            splines[ring] = SurfaceSpline.inBundle(bundle, regions.ringLabels[ring]);
        }
        RingRegionExtraction extraction = new RingRegionExtraction(regions, splines,
                regions.select(ctx.getInput(SELECT.name, String.class)));
        extraction.cap = FieldBroadcast.boolAt(
                FieldBroadcast.getInputOrDefault(ctx, CAP.name, CAP.defaultValue), 0, true);
        extraction.snapFraction = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, SNAP.name, SNAP.defaultValue),
                (float) RingRegionExtraction.DEFAULT_SNAP_FRACTION);
        extraction.solidCheck = Platforms.get().meshBooleanBackend();
        extraction.build();
        ctx.setOutput(GEOMETRY.name, extraction.closedMesh == null ? GeometryBundle.empty()
                : GeometryBundle.ofMesh(extraction.closedMesh));
        ctx.setOutput(OPEN.name, extraction.openMesh == null ? GeometryBundle.empty()
                : GeometryBundle.ofMesh(extraction.openMesh));
        ctx.setOutput(REPORT.name, String.join("\n", extraction.reportLines()));
    }
}
