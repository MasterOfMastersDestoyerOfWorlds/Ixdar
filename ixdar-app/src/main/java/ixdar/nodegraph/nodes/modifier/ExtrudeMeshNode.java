package ixdar.nodegraph.nodes.modifier;
import java.util.Objects;
import java.util.Arrays;

import java.util.List;

import java.util.Map;

import org.joml.Vector3f;

import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.nodegraph.field.BoolField;
import ixdar.nodegraph.api.InputPort;
import ixdar.nodegraph.api.MeshNode;
import ixdar.annotations.meshnode.MeshNodeAnnotation;
import ixdar.annotations.meshnode.NodeCategory;
import ixdar.nodegraph.api.NodeContext;
import ixdar.nodegraph.api.OutputPort;
import ixdar.nodegraph.api.PortType;
import ixdar.geometry.mesh.data.bundle.GeometryBundle;
import ixdar.geometry.mesh.data.bundle.SlotCarry;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.ops.MeshVertexOffset;
import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.HalfEdgeMeshOps;
import ixdar.geometry.mesh.data.representation.TriangleGeometry;
import ixdar.nodegraph.field.FieldBroadcast;
import ixdar.nodegraph.nodes.patch.AssignBezierHandlesNode;
import ixdar.geometry.mesh.data.patches.CoonsHandleBuilder;

/**
 * Extrudes selected faces of a mesh along their normals.
 * <p>
 * Supports two modes:
 * <ul>
 *   <li><b>INDIVIDUAL</b> (default, region=false): each face extrudes independently</li>
 *   <li><b>REGION</b> (region=true): adjacent selected faces share extruded vertices;
 *       side walls are only created on boundary edges between selected and unselected faces</li>
 * </ul>
 */
@MeshNodeAnnotation(id = "extrude_mesh", category = NodeCategory.MODIFIERS)
public class ExtrudeMeshNode implements MeshNode {
    public static final float NORMAL_LENGTH_EPSILON = 1e-8f;

    public static final InputPort GEOMETRY = new InputPort("geometry", PortType.GEOMETRY_BUNDLE, null);
    public static final InputPort OFFSET = new InputPort("offset", PortType.FLOAT, 0.1f, -10f, 10f);
    public static final InputPort SELECTION = new InputPort("selection", PortType.BOOLEAN, true);
    public static final InputPort REGION = new InputPort("region", PortType.BOOLEAN, false);
    public static final OutputPort GEOMETRY_OUT = new OutputPort(GEOMETRY.name, PortType.GEOMETRY_BUNDLE);
    public static final OutputPort GENERATED_OUT = new OutputPort("generated", PortType.BOOLEAN);

    private boolean[] extrudedFaces;

    @Override
    public List<InputPort> inputs() {
        return List.of(GEOMETRY, OFFSET, SELECTION, REGION);
    }

    @Override
    public List<OutputPort> outputs() {
        return List.of(GEOMETRY_OUT, GENERATED_OUT);
    }

    @Override
    public String description() {
        return "Extrudes selected faces along their normals by an offset distance, with individual or region mode for controlling whether adjacent faces share extruded vertices.";
    }

    @Override
    public Map<String, String> socketDocs() {
        return Map.of(
                GEOMETRY.name, "Input/output cage. Preserves bezier handle slots via rebuild when _bezier_handle_weight is set.",
                OFFSET.name, "Distance to push extruded faces along the (averaged) face normal. Positive = outward, negative = inward.",
                SELECTION.name, "Per-face BOOLEAN mask (or scalar). True = face gets extruded.",
                REGION.name, "If true, adjacent selected faces share extruded vertices — single extruded region. If false (default), each face extrudes independently (cheese-grater).",
                GENERATED_OUT.name, "Per-output-face BOOLEAN: true for the newly-created top face of each extrusion; false for pass-through and side walls. Thread into the selection of the next op to chain features."
        );
    }

    @Override
    public void evaluate(NodeContext ctx) {
        GeometryBundle base = Objects.requireNonNullElse(ctx.getInput(GEOMETRY.name, GeometryBundle.class), GeometryBundle.empty());
        MeshTopology in = base.mesh();
        if (in == null || in.vertexCount() == 0) {
            ctx.setOutput(GEOMETRY.name, GeometryBundle.empty());
            ctx.setOutput(GENERATED_OUT.name, new BoolField(new boolean[0]));
            return;
        }

        Object offObj = FieldBroadcast.getInputOrDefault(ctx, OFFSET.name, OFFSET.defaultValue);
        float offset = FieldBroadcast.floatScalarOrDefault(offObj, 0.1f);

        Object selObj = FieldBroadcast.getInputOrDefault(ctx, SELECTION.name, SELECTION.defaultValue);
        Object regObj = FieldBroadcast.getInputOrDefault(ctx, REGION.name, REGION.defaultValue);
        boolean region = FieldBroadcast.boolAt(regObj, 0, false);

        int[] activeVertexOfId = new int[in.vertexIdBound()];
        PackedPoints srcPos = in.packedPositions(activeVertexOfId);
        int[] firstTriangles = new int[3 * in.faceCount()];
        for (int corner = 0; corner < firstTriangles.length; corner++) {
            firstTriangles[corner] = activeVertexOfId[in.faceVertexAt(in.faceIdAt(corner / 3), corner % 3)];
        }
        PackedPoints faceNormals = TriangleGeometry.normals(srcPos, firstTriangles, new Vector3f(0f, 1f, 0f));
        MeshTopology out = region
                ? extrudeRegion(in, activeVertexOfId, srcPos, faceNormals, offset, selObj)
                : extrudeFacesIndividual(in, activeVertexOfId, srcPos, faceNormals, offset, selObj);
        int outFaceCount = out == null ? 0 : out.faceCount();
        boolean[] genMask = new boolean[outFaceCount];
        int origFaceCount = in.faceCount();
        for (int fi = 0; fi < Math.min(origFaceCount, outFaceCount); fi++) {
            genMask[fi] = extrudedFaces[fi];
        }

        GeometryBundle outBundle = SlotCarry.carry(List.of(base), GeometryBundle.ofMesh(out));
        if (out != null && CoonsHandleBuilder.hasHandles(base)
                && base.slots().get(AssignBezierHandlesNode.SLOT_WEIGHT) instanceof Number weight) {
            outBundle = AssignBezierHandlesNode.computeHandles(outBundle, weight.floatValue());
        }

        ctx.setOutput(GEOMETRY.name, outBundle);
        ctx.setOutput(GENERATED_OUT.name, new BoolField(genMask));
    }

    /**
     * Each selected face moves off along its own normal onto fresh vertices, joined to its old
     * boundary by one side quad per edge.
     *
     * @param mesh             source mesh, read in place; faces of any corner count
     * @param activeVertexOfId active vertex index per source vertex id
     * @param source           positions by active vertex index
     * @param normals          unit normal per active face
     * @param offset           distance to move along the negated normal
     * @param selection        per-face selection field or scalar
     * @return the extruded mesh: source vertices in active order, then each selected face's new
     *         corners
     */
    private MeshTopology extrudeFacesIndividual(MeshTopology mesh, int[] activeVertexOfId, PackedPoints source,
            PackedPoints normals, float offset, Object selection) {
        int vertCount = mesh.vertexCount();
        int faceCount = mesh.faceCount();

        boolean[] selected = new boolean[faceCount];
        int selectedCount = 0;
        for (int fi = 0; fi < faceCount; fi++) {
            boolean sel = FieldBroadcast.boolAt(selection, fi, true);
            selected[fi] = sel;
            if (sel) selectedCount++;
        }
        extrudedFaces = selected;

        if (selectedCount == 0 || offset == 0f) {
            return MeshVertexOffset.apply(mesh, null);
        }

        int cornerCount = 0;
        int selectedCorners = 0;
        int[] firstNewVertex = new int[faceCount];
        for (int fi = 0; fi < faceCount; fi++) {
            int corners = mesh.faceVertexCount(mesh.faceIdAt(fi));
            cornerCount += corners;
            if (selected[fi]) {
                firstNewVertex[fi] = vertCount + selectedCorners;
                selectedCorners += corners;
            }
        }

        int[] newToOrig = new int[selectedCorners];
        PackedPoints moved = source.copy();
        moved.resize(vertCount + selectedCorners);
        int[] faceSizes = new int[faceCount + selectedCorners];
        int[] outFaces = new int[cornerCount + selectedCorners * 4];
        int cornerWrite = 0;
        for (int fi = 0; fi < faceCount; fi++) {
            int faceId = mesh.faceIdAt(fi);
            int corners = mesh.faceVertexCount(faceId);
            faceSizes[fi] = corners;
            for (int k = 0; k < corners; k++) {
                int vertexId = mesh.faceVertexAt(faceId, k);
                int origVertex = activeVertexOfId[vertexId];
                if (!selected[fi]) {
                    outFaces[cornerWrite++] = origVertex;
                    continue;
                }
                int newVertex = firstNewVertex[fi] + k;
                moved.set(newVertex, source.at(origVertex).fma(-offset, normals.at(fi)));
                newToOrig[newVertex - vertCount] = vertexId;
                outFaces[cornerWrite++] = newVertex;
            }
        }

        int[] sourceFace = new int[faceSizes.length];
        for (int fi = 0; fi < faceCount; fi++) {
            sourceFace[fi] = mesh.faceIdAt(fi);
        }
        int faceWrite = faceCount;
        for (int fi = 0; fi < faceCount; fi++) {
            if (!selected[fi]) {
                continue;
            }
            int faceId = mesh.faceIdAt(fi);
            int corners = mesh.faceVertexCount(faceId);
            for (int k = 0; k < corners; k++) {
                int next = (k + 1) % corners;
                sourceFace[faceWrite] = faceId;
                faceSizes[faceWrite++] = 4;
                outFaces[cornerWrite++] = activeVertexOfId[mesh.faceVertexAt(faceId, k)];
                outFaces[cornerWrite++] = activeVertexOfId[mesh.faceVertexAt(faceId, next)];
                outFaces[cornerWrite++] = firstNewVertex[fi] + next;
                outFaces[cornerWrite++] = firstNewVertex[fi] + k;
            }
        }

        HalfEdgeMesh out = HalfEdgeMeshOps.bulkAllocateMixed(moved, faceSizes, outFaces);
        out.computeNormals();
        out.recordSources(extrudedSources(mesh, newToOrig), null, sourceFace);
        return out;
    }

    /**
     * Source vertex per output vertex of an extrusion: the input's vertices in active order, then
     * each moved copy's original.
     *
     * @param mesh      the input mesh
     * @param newToOrig input vertex id per moved copy, in output order
     * @return input vertex id per output vertex id
     */
    private static int[] extrudedSources(MeshTopology mesh, int[] newToOrig) {
        int[] sourceVertex = new int[mesh.vertexCount() + newToOrig.length];
        for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
            sourceVertex[activeVertex] = mesh.vertexIdAt(activeVertex);
        }
        System.arraycopy(newToOrig, 0, sourceVertex, mesh.vertexCount(), newToOrig.length);
        return sourceVertex;
    }

    /**
     * The selected faces move off together: each vertex they use moves along the mean of its
     * selected faces' normals, and side quads close only the region's boundary edges.
     *
     * @param mesh             source mesh, read in place; faces of any corner count
     * @param activeVertexOfId active vertex index per source vertex id
     * @param source           positions by active vertex index
     * @param normals          unit normal per active face
     * @param offset           distance to move along the negated normal
     * @param selection        per-face selection field or scalar
     * @return the extruded mesh: source vertices in active order, then one moved copy of each
     *         vertex a selected face uses, in active order
     */
    private MeshTopology extrudeRegion(MeshTopology mesh, int[] activeVertexOfId, PackedPoints source,
            PackedPoints normals, float offset, Object selection) {
        int vertCount = mesh.vertexCount();
        int faceCount = mesh.faceCount();

        boolean[] selected = new boolean[faceCount];
        int selectedCount = 0;
        for (int fi = 0; fi < faceCount; fi++) {
            boolean sel = FieldBroadcast.boolAt(selection, fi, true);
            selected[fi] = sel;
            if (sel) selectedCount++;
        }
        extrudedFaces = selected;

        if (selectedCount == 0 || offset == 0f) {
            return MeshVertexOffset.apply(mesh, null);
        }

        boolean[] vertUsedBySelected = new boolean[vertCount];
        PackedPoints vertNormals = new PackedPoints(vertCount); // accumulated
        int[] vertNormalCount = new int[vertCount];
        int cornerCount = 0;
        for (int fi = 0; fi < faceCount; fi++) {
            int faceId = mesh.faceIdAt(fi);
            int corners = mesh.faceVertexCount(faceId);
            cornerCount += corners;
            if (!selected[fi]) continue;
            for (int k = 0; k < corners; k++) {
                int vi = activeVertexOfId[mesh.faceVertexAt(faceId, k)];
                vertUsedBySelected[vi] = true;
                vertNormals.add(vi, normals.at(fi));
                vertNormalCount[vi]++;
            }
        }

        for (int vi = 0; vi < vertCount; vi++) {
            if (vertNormalCount[vi] == 0) continue;
            Vector3f normal = vertNormals.at(vi);
            float len = normal.length();
            if (len > NORMAL_LENGTH_EPSILON) {
                vertNormals.set(vi, normal.div(len));
            }
        }

        int[] vertNewId = new int[vertCount];
        Arrays.fill(vertNewId, -1);
        int newVertTotal = 0;
        for (int vi = 0; vi < vertCount; vi++) {
            if (vertUsedBySelected[vi]) {
                vertNewId[vi] = vertCount + newVertTotal;
                newVertTotal++;
            }
        }
        int[] newToOrig = new int[newVertTotal];
        for (int vi = 0; vi < vertCount; vi++) {
            if (vertNewId[vi] >= 0) {
                newToOrig[vertNewId[vi] - vertCount] = mesh.vertexIdAt(vi);
            }
        }

        int[] activeFaceOfId = new int[mesh.faceIdBound()];
        for (int fi = 0; fi < faceCount; fi++) {
            activeFaceOfId[mesh.faceIdAt(fi)] = fi;
        }
        int[] boundaryStarts = new int[cornerCount];
        int[] boundaryEnds = new int[cornerCount];
        int[] boundaryFaces = new int[cornerCount];
        int boundaryEdgeCount = 0;
        for (int fi = 0; fi < faceCount; fi++) {
            if (!selected[fi]) continue;
            int faceId = mesh.faceIdAt(fi);
            int corners = mesh.faceVertexCount(faceId);
            for (int k = 0; k < corners; k++) {
                int vertexId = mesh.faceVertexAt(faceId, k);
                int nextVertexId = mesh.faceVertexAt(faceId, (k + 1) % corners);
                int edgeId = mesh.edgeBetween(vertexId, nextVertexId);
                int otherFaceId = mesh.edgeFace(edgeId, 0) == faceId ? mesh.edgeFace(edgeId, 1)
                        : mesh.edgeFace(edgeId, 0);
                if (otherFaceId == MeshTopology.NONE || !selected[activeFaceOfId[otherFaceId]]) {
                    boundaryStarts[boundaryEdgeCount] = activeVertexOfId[vertexId];
                    boundaryFaces[boundaryEdgeCount] = faceId;
                    boundaryEnds[boundaryEdgeCount++] = activeVertexOfId[nextVertexId];
                }
            }
        }

        PackedPoints moved = source.copy();
        moved.resize(vertCount + newVertTotal);
        for (int vi = 0; vi < vertCount; vi++) {
            if (!vertUsedBySelected[vi]) continue;
            moved.set(vertNewId[vi], source.at(vi).fma(-offset, vertNormals.at(vi)));
        }

        int[] faceSizes = new int[faceCount + boundaryEdgeCount];
        int[] outFaces = new int[cornerCount + boundaryEdgeCount * 4];
        int[] sourceFace = new int[faceSizes.length];
        System.arraycopy(boundaryFaces, 0, sourceFace, faceCount, boundaryEdgeCount);
        int cornerWrite = 0;
        for (int fi = 0; fi < faceCount; fi++) {
            int faceId = mesh.faceIdAt(fi);
            int corners = mesh.faceVertexCount(faceId);
            sourceFace[fi] = faceId;
            faceSizes[fi] = corners;
            for (int k = 0; k < corners; k++) {
                int origV = activeVertexOfId[mesh.faceVertexAt(faceId, k)];
                outFaces[cornerWrite++] = selected[fi] ? vertNewId[origV] : origV;
            }
        }
        for (int i = 0; i < boundaryEdgeCount; i++) {
            int va = boundaryStarts[i];
            int vb = boundaryEnds[i];
            faceSizes[faceCount + i] = 4;
            outFaces[cornerWrite++] = va;
            outFaces[cornerWrite++] = vb;
            outFaces[cornerWrite++] = vertNewId[vb];
            outFaces[cornerWrite++] = vertNewId[va];
        }

        HalfEdgeMesh out = HalfEdgeMeshOps.bulkAllocateMixed(moved, faceSizes, outFaces);
        out.computeNormals();
        out.recordSources(extrudedSources(mesh, newToOrig), null, sourceFace);
        return out;
    }
}
