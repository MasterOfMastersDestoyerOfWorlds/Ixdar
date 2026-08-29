package ixdar.geometry.mesh.quadlayout.gridmap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.geometry.mesh.nodes.api.InputPort;
import ixdar.geometry.mesh.nodes.api.MeshNode;
import ixdar.geometry.mesh.nodes.api.NodeContext;
import ixdar.geometry.mesh.nodes.api.OutputPort;
import ixdar.geometry.mesh.nodes.api.PortType;
import ixdar.geometry.mesh.nodes.math.FieldBroadcast;
import ixdar.geometry.mesh.quadlayout.embedding.ArcNetwork;
import ixdar.geometry.mesh.quadlayout.embedding.records.EmbeddedPatch;
import ixdar.geometry.mesh.quadlayout.extraction.ExtractedPatchGrids;
import ixdar.geometry.mesh.quadlayout.extraction.PatchGridExtraction;
import ixdar.geometry.mesh.quadlayout.extraction.QuadMeshExtraction;
import ixdar.geometry.mesh.nodes.api.UvField;
import ixdar.platform.Platforms;

/**
 * Assembles the integer grid map: maps every layout patch onto its quantized
 * rectangle, frames the patches in one common grid, and builds the map's DOF
 * system, initial extraction and iso surface. All durable products land on the
 * returned {@link GlobalGridMap}.
 *
 * <p>See also: LCBK19 Section 6.2
 */
@MeshNodeAnnotation(id = "integer_grid_map", desktopOnly = true)
public final class GridMapAssembly implements MeshNode {

    public static final float DEFAULT_TARGET_EDGE_LENGTH = 1.0f;

    public static final InputPort TMESH = new InputPort("tmesh", PortType.ARC_NETWORK, null);
    public static final InputPort UV = new InputPort("uv", PortType.UV_FIELD, null);
    public static final InputPort TARGET_EDGE_LENGTH = new InputPort("target_edge_length",
            PortType.FLOAT, DEFAULT_TARGET_EDGE_LENGTH);
    public static final OutputPort UV_OUT = new OutputPort(UV.name, PortType.UV_FIELD);
    public static final OutputPort DOFS = new OutputPort("dofs", PortType.DOF_SYSTEM);
    public static final OutputPort CHARTS = new OutputPort("charts", PortType.CHART_ATLAS);


    @Override
    public List<InputPort> inputs() {
        return List.of(TMESH, UV, TARGET_EDGE_LENGTH);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(UV_OUT, DOFS, CHARTS);
    }

    @Override
    public String description() {
        return "Maps every layout patch onto its quantized rectangle and frames the patches in"
                + " one common integer grid, the UV field the relaxation optimizes.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                TMESH.name, "Contracted, conforming T-mesh, from a tmesh_contract node.",
                UV.name, "Seamless UV field in (constrains the map); the framed integer grid map"
                        + " UV field out.",
                TARGET_EDGE_LENGTH.name, "Parametric length one quad edge spans.",
                DOFS.name, "The map's DOF system, what a newton_solver node relaxes.",
                CHARTS.name, "The map's patch charts and the arc transitions between them."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        ArcNetwork contracted = (ArcNetwork) ctx.getInput(TMESH.name, Object.class);
        UvField constraint = (UvField) ctx.getInput(UV.name, Object.class);
        float targetEdgeLength = FieldBroadcast.floatScalarOrDefault(
                FieldBroadcast.getInputOrDefault(ctx, TARGET_EDGE_LENGTH.name,
                        TARGET_EDGE_LENGTH.defaultValue),
                DEFAULT_TARGET_EDGE_LENGTH);
        GlobalGridMap gridMap = assemble(contracted, constraint, targetEdgeLength);
        ctx.setOutput(UV_OUT.name, gridMap);
        ctx.setOutput(DOFS.name, gridMap.gridDofs.system);
        ctx.setOutput(CHARTS.name, gridMap.atlas);
    }

    /**
     * Solves the per-patch maps and frames, then assembles the initial map.
     *
     * @param tmesh            contracted, conforming T-mesh
     * @param uv               seamless UV field constraining the map
     * @param targetEdgeLength parametric length one quad edge spans
     * @return the assembled, unrelaxed map
     */
    public GlobalGridMap assemble(ArcNetwork tmesh, UvField uv, double targetEdgeLength) {
        LayoutPatchMaps maps = new LayoutPatchMaps(tmesh, uv, targetEdgeLength);
        maps.build();
        IntegerGridMap framing = new IntegerGridMap(tmesh).build();
        return assemble(maps, framing, uv);
    }

    /**
     * Assembles the initial map from already-built patch maps and frames.
     *
     * @param patchMaps solved per-patch rectangle maps
     * @param frames    the patches' quarter turns and integer origins
     * @param uv        seamless UV field constraining the map
     * @return the assembled, unrelaxed map
     */
    public GlobalGridMap assemble(LayoutPatchMaps patchMaps, IntegerGridMap frames,
            UvField uv) {
        GlobalGridMap gridMap = new GlobalGridMap(patchMaps, frames, uv);
        assembleInitial(gridMap);
        return gridMap;
    }

    /**
     * Carries every patch's rectangle coordinates through its frame, checks the
     * layout's nodes landed on integers, and assembles the DOF system plus the
     * pre-relaxation extraction and iso surface.
     *
     * @param gridMap the map to assemble
     */
    @SuppressWarnings("unchecked")
    public void assembleInitial(GlobalGridMap gridMap) {
        gridMap.uvByPatchId = new double[gridMap.tmesh.patches.size()][];
        gridMap.denseByCopyVertexByPatchId = new HashMap[gridMap.tmesh.patches.size()];
        double[] grid = new double[GlobalGridMap.GRID_COORDINATES];
        for (EmbeddedPatch patch : gridMap.tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            PatchRectangleMap map = gridMap.patchMaps.mapByPatchId[patch.patchId];
            double[] uv = new double[map.positions.length * GlobalGridMap.GRID_COORDINATES];
            Map<Integer, Integer> denseByCopyVertex = new HashMap<>();
            for (int dense = 0; dense < map.positions.length; dense++) {
                gridMap.frames.toGrid(patch.patchId, map.rectangleU[dense], map.rectangleV[dense], grid);
                uv[dense * GlobalGridMap.GRID_COORDINATES] = grid[0];
                uv[dense * GlobalGridMap.GRID_COORDINATES + 1] = grid[1];
                denseByCopyVertex.put(map.vertexLabel[dense], dense);
            }
            gridMap.uvByPatchId[patch.patchId] = uv;
            gridMap.denseByCopyVertexByPatchId[patch.patchId] = denseByCopyVertex;
        }
        measureNodes(gridMap);
        Platforms.log("[global-grid] patches=%d offGridNodes=%d worstNodeDeviation=%.3e%n",
                gridMap.frames.placedPatchCount, gridMap.offGridNodeCount, gridMap.worstNodeIntegerDeviation);

        gridMap.gridDofs = new GridMapDofSystem(gridMap);
        gridMap.gridDofs.seamCouplingPinned = false;
        gridMap.gridDofs.nodeFreedomPinned = false;
        gridMap.gridDofs.build();
        gridMap.quadGridInitial = new PatchGridExtraction(gridMap.patchMaps);
        gridMap.quadGridInitial.optimizedGrid = gridMap;
        gridMap.quadGridInitial = gridMap.quadGridInitial.build();
        gridMap.isoSurfaceInitial = new GridMapIsoSurface(gridMap.patchMaps, gridMap.uvByPatchId).build();
    }

    /**
     * Verifies the map and extracts its quad mesh and per-patch grids.
     *
     * @param gridMap the assembled, relaxed map
     */
    public static void extractQuads(GlobalGridMap gridMap) {
        gridMap.gridVerification = new GridMapVerification(gridMap).build();
        QuadMeshExtraction extraction = new QuadMeshExtraction(gridMap, gridMap.gridVerification);
        extraction.expectedQuadCount = gridMap.quadGridInitial.quadCount;
        gridMap.quadMesh = extraction.build();
        gridMap.extractedGrids = new ExtractedPatchGrids(gridMap.quadMesh, gridMap).build();
    }

    /**
     * Checks every layout node sits on an integer of the common grid, which is what
     * makes the quantization's assigned lengths meaningful in the map.
     *
     * @param gridMap the map being assembled
     */
    private void measureNodes(GlobalGridMap gridMap) {
        int samplesPrinted = 0;
        for (EmbeddedPatch patch : gridMap.tmesh.patches) {
            if (!patch.alive) {
                continue;
            }
            for (int side = 0; side < EmbeddedPatch.SIDES; side++) {
                List<Integer> sideNodes = patch.sideNodeIds.get(side);
                for (int nodeId : sideNodes) {
                    double[] position = gridMap.nodePosition(patch.patchId, nodeId);
                    if (position == null) {
                        continue;
                    }
                    boolean offGrid = false;
                    for (int axis = 0; axis < GlobalGridMap.GRID_COORDINATES; axis++) {
                        double deviation = Math.abs(position[axis] - Math.round(position[axis]));
                        gridMap.worstNodeIntegerDeviation = Math.max(gridMap.worstNodeIntegerDeviation, deviation);
                        offGrid |= deviation > GlobalGridMap.INTEGER_TOLERANCE;
                        gridMap.offGridNodeCount += deviation > GlobalGridMap.INTEGER_TOLERANCE ? 1 : 0;
                    }
                    if (offGrid && samplesPrinted < GlobalGridMap.OFF_GRID_SAMPLES_LISTED) {
                        samplesPrinted++;
                        Platforms.log(
                                "[global-grid]   off-grid node=%d patch=%d side=%d sideArcs=%d"
                                        + " sideNodes=%d at (%.4f, %.4f)%n",
                                nodeId, patch.patchId, side, patch.sideArcIds.get(side).size(),
                                sideNodes.size(), position[0], position[1]);
                    }
                }
            }
        }
    }
}
