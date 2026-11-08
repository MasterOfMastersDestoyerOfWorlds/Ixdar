package ixdar.nodegraph.nodes.data;

import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonExtractor;
import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonExtractor.SkeletonBranch;
import ixdar.geometry.mesh.data.analysis.skeleton.MeshSkeletonExtractor.SkeletonResult;
import ixdar.geometry.mesh.data.bundle.CurveGeometry;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;

/**
 * The TEASAR curve skeleton of a closed surface, published as one polyline per branch with each
 * joint's inscribed radius, so a graph can draw it or read it.
 */
@MeshNodeAnnotation(id = "skeleton", category = NodeCategory.FIELD_DATA)
public class SkeletonNode implements MeshNode {

    public static final float MAXIMUM_RESOLUTION = 512f;

    public static final float MAXIMUM_BRANCH_BUDGET = 1000f;

    public static final String BRANCH_LENGTH = "length";

    public static final InputPort GEOMETRY = InputPort.manifoldGeometry("geometry");
    public static final InputPort RESOLUTION = new InputPort("resolution", PortType.INT,
            MeshSkeletonExtractor.DEFAULT_RESOLUTION, 16f, MAXIMUM_RESOLUTION);
    public static final InputPort BRANCH_BUDGET = new InputPort("branch_budget", PortType.INT,
            MeshSkeletonExtractor.DEFAULT_BRANCH_BUDGET, 1f, MAXIMUM_BRANCH_BUDGET);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name,
            PortType.GEOMETRY_BUNDLE);

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, RESOLUTION, BRANCH_BUDGET);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT);
    }

    @Override
    public String description() {
        return "Extracts the surface's curve skeleton with TEASAR: the interior is voxelized, "
                + "each voxel's distance from the boundary computed, branches traced by a "
                + "penalized Dijkstra from the deepest voxel to the farthest ones, and each "
                + "branch simplified with Ramer-Douglas-Peucker. Branches shorter than "
                + MeshSkeletonExtractor.MIN_BRANCH_VOXELS + " voxels are dropped.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name,
                "Input: a closed surface; open boundaries leak the interior fill. Output: the "
                        + "same bundle with one open polyline per skeleton branch as curve "
                        + "geometry, each joint carrying its inscribed radius as the `"
                        + MeshSkeletonExtractor.RADIUS + "` point attribute and each branch its "
                        + "length as the `" + BRANCH_LENGTH + "` curve attribute.",
                RESOLUTION.name,
                "Voxel cells along the bounding box's longest side. A limb needs several voxels "
                        + "across to keep its branch, so thin parts need a finer grid, at cubic "
                        + "memory and time cost.",
                BRANCH_BUDGET.name,
                "Farthest-point extraction rounds to spend; rounds that find only a short spur "
                        + "still count, so this exceeds the branch count.");
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
            return;
        }
        Number resolution = ctx.getInput(RESOLUTION.name, Number.class);
        Number branchBudget = ctx.getInput(BRANCH_BUDGET.name, Number.class);
        SkeletonResult skeleton = MeshSkeletonExtractor.extract(mesh,
                resolution == null ? MeshSkeletonExtractor.DEFAULT_RESOLUTION
                        : resolution.intValue(),
                branchBudget == null ? MeshSkeletonExtractor.DEFAULT_BRANCH_BUDGET
                        : branchBudget.intValue());
        List<SkeletonBranch> branches = skeleton.branches();
        if (branches.isEmpty()) {
            ctx.setOutput(GEOMETRY.name, bundle.withSlot(CurveGeometry.SLOT,
                    CurveGeometry.singlePolyline(new float[0], false)));
            return;
        }
        int[] offsets = new int[branches.size() + 1];
        for (int branch = 0; branch < branches.size(); branch++) {
            offsets[branch + 1] = offsets[branch] + branches.get(branch).joints().size();
        }
        float[] positions = new float[CurveGeometry.XYZ * offsets[branches.size()]];
        float[] radii = new float[offsets[branches.size()]];
        float[] lengths = new float[branches.size()];
        for (int branch = 0; branch < branches.size(); branch++) {
            List<MeshSkeletonExtractor.SkeletonJoint> joints = branches.get(branch).joints();
            for (int joint = 0; joint < joints.size(); joint++) {
                int point = offsets[branch] + joint;
                System.arraycopy(joints.get(joint).position(), 0, positions,
                        CurveGeometry.XYZ * point, CurveGeometry.XYZ);
                radii[point] = joints.get(joint).radius();
            }
            lengths[branch] = branches.get(branch).length();
        }
        CurveGeometry curves = new CurveGeometry(positions, offsets,
                new boolean[branches.size()], Map.of(MeshSkeletonExtractor.RADIUS, radii),
                Map.of(BRANCH_LENGTH, lengths));
        ctx.setOutput(GEOMETRY.name, bundle.withSlot(CurveGeometry.SLOT, curves));
    }
}
