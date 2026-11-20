package ixdar.geometry.mesh.data.bundle;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ixdar.geometry.mesh.data.representation.HalfEdgeMesh;
import ixdar.geometry.mesh.data.representation.MeshTopology;
import ixdar.geometry.mesh.data.representation.PackedPoints;
import ixdar.nodegraph.field.FloatField;
import ixdar.nodegraph.field.IntField;
import ixdar.nodegraph.nodes.data.TagGeometryNode;
import ixdar.nodegraph.nodes.modifier.SetBoneWeightNode;
import ixdar.nodegraph.nodes.patch.AssignBezierHandlesNode;

/**
 * Carries a bundle's slots onto the mesh an op built from it, through the provenance layers the op
 * recorded on that {@link HalfEdgeMesh}. Slots that are not per-element are copied whole.
 */
public final class SlotCarry {

    /** The op's inputs; the operand layers index this list. */
    public final List<GeometryBundle> inputs;

    /** The mesh the op built. */
    public final MeshTopology mesh;

    /** Input vertex id per output vertex id, or null when the op recorded none. */
    public int[] sourceVertex;

    /** Input edge a minted output vertex lies on, per output vertex id, or null. */
    public int[] sourceEdgeOfVertex;

    /** Input index per output vertex id. */
    public int[] vertexOperand;

    /** Input face id per output face id, or null when the op recorded none. */
    public int[] sourceFace;

    /** Input index per output face id. */
    public int[] faceOperand;

    /** Input edge id per output edge id, {@link MeshTopology#NONE} when it has none. */
    public int[] edgeSource;

    /** Input index per output edge id. */
    public int[] edgeOperand;

    /** Per output edge id: both ends have source vertices, so the edge's direction is known. */
    public boolean[] edgeDirected;

    /** Per output edge id: its canonical direction runs against its source edge's. */
    public boolean[] edgeReversed;

    /** Offset of each active output face's corners in {@link #cornerSource}. */
    public int[] cornerStart;

    /** Corner index in the source face per output corner, or -1 when unmatched. */
    public int[] cornerSource;

    /**
     * Reads the output's provenance layers. An edge's source joins its ends' sources; a corner's
     * is the source face's corner at its vertex's source, else the same corner (a weld).
     *
     * @param inputs     the op's inputs
     * @param mesh       the output mesh
     * @param traceEdges whether to trace edge provenance, which only edge-domain slots read
     */
    private SlotCarry(List<GeometryBundle> inputs, MeshTopology mesh, boolean traceEdges) {
        this.inputs = inputs;
        this.mesh = mesh;
        if (!(mesh instanceof HalfEdgeMesh layered)) {
            return;
        }
        int sourceVertexLayer = layered.vertexLayer(HalfEdgeMesh.SOURCE_VERTEX);
        int sourceEdgeLayer = layered.vertexLayer(HalfEdgeMesh.SOURCE_EDGE);
        int vertexOperandLayer = layered.vertexLayer(HalfEdgeMesh.SOURCE_OPERAND);
        int sourceFaceLayer = layered.faceLayer(HalfEdgeMesh.SOURCE_FACE);
        int faceOperandLayer = layered.faceLayer(HalfEdgeMesh.SOURCE_OPERAND);
        sourceVertex = sourceVertexLayer == MeshTopology.NONE ? null
                : layered.vertexLayers[sourceVertexLayer];
        sourceEdgeOfVertex = sourceEdgeLayer == MeshTopology.NONE ? null
                : layered.vertexLayers[sourceEdgeLayer];
        vertexOperand = vertexOperandLayer == MeshTopology.NONE
                ? new int[layered.vertexIdBound()] : layered.vertexLayers[vertexOperandLayer];
        sourceFace = sourceFaceLayer == MeshTopology.NONE ? null
                : layered.faceLayers[sourceFaceLayer];
        faceOperand = faceOperandLayer == MeshTopology.NONE
                ? new int[layered.faceIdBound()] : layered.faceLayers[faceOperandLayer];
        if (sourceVertex == null) {
            return;
        }

        edgeSource = new int[traceEdges ? mesh.edgeIdBound() : 0];
        edgeOperand = new int[edgeSource.length];
        edgeDirected = new boolean[edgeSource.length];
        edgeReversed = new boolean[edgeSource.length];
        Arrays.fill(edgeSource, MeshTopology.NONE);
        if (sourceFace != null) {
            cornerStart = new int[mesh.faceCount() + 1];
            for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
                cornerStart[activeFace + 1] =
                        cornerStart[activeFace] + mesh.faceVertexCount(mesh.faceIdAt(activeFace));
            }
            cornerSource = new int[cornerStart[mesh.faceCount()]];
            Arrays.fill(cornerSource, -1);
        }
        for (int activeFace = 0; sourceFace != null && activeFace < mesh.faceCount(); activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            int source = sourceFace[faceId];
            if (source == MeshTopology.NONE) {
                continue;
            }
            MeshTopology input = inputs.get(faceOperand[faceId]).mesh();
            int corners = mesh.faceVertexCount(faceId);
            int sourceCorners = input.faceVertexCount(source);
            for (int corner = 0; corner < corners; corner++) {
                int vertexId = mesh.faceVertexAt(faceId, corner);
                if (sourceVertex[vertexId] == MeshTopology.NONE) {
                    continue;
                }
                int match = -1;
                if (inputs.get(vertexOperand[vertexId]).mesh() == input) {
                    for (int sourceCorner = 0; sourceCorner < sourceCorners; sourceCorner++) {
                        if (input.faceVertexAt(source, sourceCorner) == sourceVertex[vertexId]) {
                            match = sourceCorner;
                        }
                    }
                }
                if (match < 0 && sourceCorners == corners) {
                    match = corner;
                }
                cornerSource[cornerStart[activeFace] + corner] = match;
            }
            for (int corner = 0; corner < corners && traceEdges; corner++) {
                int from = cornerSource[cornerStart[activeFace] + corner];
                int to = cornerSource[cornerStart[activeFace] + (corner + 1) % corners];
                int fromVertex = mesh.faceVertexAt(faceId, corner);
                int edgeId = mesh.edgeBetween(fromVertex,
                        mesh.faceVertexAt(faceId, (corner + 1) % corners));
                if (from < 0 || to < 0 || edgeId == MeshTopology.NONE
                        || edgeSource[edgeId] != MeshTopology.NONE) {
                    continue;
                }
                int sourceFrom = input.faceVertexAt(source, from);
                int sourceTo = input.faceVertexAt(source, to);
                int sourceEdge = sourceFrom == sourceTo ? MeshTopology.NONE
                        : input.edgeBetween(sourceFrom, sourceTo);
                if (sourceEdge == MeshTopology.NONE) {
                    continue;
                }
                boolean alongFace = mesh.halfEdgeVertex(mesh.edgeHalfEdge(edgeId)) == fromVertex;
                int sourceTail = input.halfEdgeVertex(input.edgeHalfEdge(sourceEdge));
                edgeSource[edgeId] = sourceEdge;
                edgeOperand[edgeId] = faceOperand[faceId];
                edgeDirected[edgeId] = true;
                edgeReversed[edgeId] = sourceTail != (alongFace ? sourceFrom : sourceTo);
            }
        }

        for (int activeEdge = 0; traceEdges && activeEdge < mesh.edgeCount(); activeEdge++) {
            int edgeId = mesh.edgeIdAt(activeEdge);
            if (edgeSource[edgeId] != MeshTopology.NONE) {
                continue;
            }
            int halfEdge = mesh.edgeHalfEdge(edgeId);
            int tail = mesh.halfEdgeVertex(halfEdge);
            int head = mesh.halfEdgeEndVertex(halfEdge);
            int tailSource = sourceVertex[tail];
            int headSource = sourceVertex[head];
            int operand = vertexOperand[tail];
            MeshTopology input = inputs.get(operand).mesh();
            if (inputs.get(vertexOperand[head]).mesh() != input) {
                continue;
            }
            edgeOperand[edgeId] = operand;
            if (tailSource != MeshTopology.NONE && headSource != MeshTopology.NONE) {
                int source = tailSource == headSource ? MeshTopology.NONE
                        : input.edgeBetween(tailSource, headSource);
                if (source != MeshTopology.NONE) {
                    edgeSource[edgeId] = source;
                    edgeDirected[edgeId] = true;
                    edgeReversed[edgeId] =
                            input.halfEdgeVertex(input.edgeHalfEdge(source)) != tailSource;
                }
                continue;
            }
            if (sourceEdgeOfVertex == null) {
                continue;
            }
            int tailEdge = sourceEdgeOfVertex[tail];
            int headEdge = sourceEdgeOfVertex[head];
            if (tailSource == MeshTopology.NONE && headSource == MeshTopology.NONE) {
                edgeSource[edgeId] = tailEdge == headEdge ? tailEdge : MeshTopology.NONE;
                continue;
            }
            int mintedEdge = tailSource == MeshTopology.NONE ? tailEdge : headEdge;
            int keptSource = tailSource == MeshTopology.NONE ? headSource : tailSource;
            if (mintedEdge != MeshTopology.NONE
                    && input.edgeOtherVertex(mintedEdge, keptSource) != MeshTopology.NONE) {
                edgeSource[edgeId] = mintedEdge;
            }
        }
    }

    /**
     * Gives an op's output every slot its inputs carried, re-indexed through the output mesh's
     * provenance. Slots the output already holds are the op's own; a per-element slot without
     * provenance for its domain is dropped.
     *
     * @param inputs the op's inputs, in the order its operand layers number them
     * @param output the op's output: its mesh plus any slots it set itself
     * @return the output with the inputs' slots carried onto it
     */
    @SuppressWarnings("unchecked")
    public static GeometryBundle carry(List<GeometryBundle> inputs, GeometryBundle output) {
        MeshTopology mesh = output.mesh();
        Map<String, Object> slots = new HashMap<>(output.slots());

        Set<MaterialData> singleMaterials = new LinkedHashSet<>();
        boolean anySet = false;
        boolean anyUntextured = false;
        for (GeometryBundle input : inputs) {
            anySet |= MaterialSet.of(input) != null;
            anyUntextured |= MaterialData.of(input) == null && MaterialSet.of(input) == null;
            if (MaterialData.of(input) != null) {
                singleMaterials.add(MaterialData.of(input));
            }
        }
        boolean perFaceMaterials = (anySet || singleMaterials.size() > 1
                || anyUntextured && !singleMaterials.isEmpty())
                && !slots.containsKey(MaterialSet.SLOT);

        Set<String> tagNames = new LinkedHashSet<>();
        Set<String> vertexFloatNames = new LinkedHashSet<>();
        Set<String> fieldNames = new LinkedHashSet<>();
        Set<String> markLabels = new LinkedHashSet<>();
        boolean anyUv = false;
        boolean anyHandles = false;
        for (GeometryBundle input : inputs) {
            for (Map.Entry<String, Object> slot : input.slots().entrySet()) {
                String name = slot.getKey();
                Object value = slot.getValue();
                if (name.equals(TagGeometryNode.TAGS_SLOT) && value instanceof Map<?, ?> tags) {
                    tagNames.addAll((Set<String>) tags.keySet());
                } else if (name.equals(EdgeMarks.SLOT) && value instanceof Map<?, ?> marks) {
                    markLabels.addAll((Set<String>) marks.keySet());
                } else if (name.equals(CornerUvField.SLOT)) {
                    anyUv |= value instanceof CornerUvField field
                            && field.faceCount() == input.mesh().faceCount();
                } else if (name.equals(AssignBezierHandlesNode.SLOT_HANDLES_START)
                        || name.equals(AssignBezierHandlesNode.SLOT_HANDLES_END)) {
                    anyHandles = true;
                } else if (name.startsWith(SetBoneWeightNode.BONE_WEIGHT_PREFIX)
                        && value instanceof float[]) {
                    vertexFloatNames.add(name);
                } else if (value instanceof FloatField) {
                    fieldNames.add(name);
                } else if (!name.equals(MaterialSet.FACE_MATERIAL_SLOT)
                        && !(perFaceMaterials && (name.equals(MaterialSet.SLOT)
                                || name.equals(MaterialData.SLOT)))) {
                    slots.putIfAbsent(name, value);
                }
            }
        }

        SlotCarry trace = new SlotCarry(inputs, mesh, !markLabels.isEmpty() || anyHandles);
        if (trace.sourceVertex != null) {
            int vertexBound = mesh.vertexIdBound();
            if (!tagNames.isEmpty() && !slots.containsKey(TagGeometryNode.TAGS_SLOT)) {
                Map<String, boolean[]> carried = new LinkedHashMap<>();
                for (String tag : tagNames) {
                    boolean[] target = new boolean[vertexBound];
                    for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
                        int vertexId = mesh.vertexIdAt(activeVertex);
                        target[vertexId] = trace.tagAt(tag, trace.vertexOperand[vertexId],
                                trace.sourceVertex[vertexId]);
                    }
                    for (int activeFace = 0; trace.cornerSource != null
                            && activeFace < mesh.faceCount(); activeFace++) {
                        int faceId = mesh.faceIdAt(activeFace);
                        MeshTopology input = inputs.get(trace.faceOperand[faceId]).mesh();
                        for (int corner = 0; corner < mesh.faceVertexCount(faceId); corner++) {
                            int sourceCorner = trace.cornerSource[trace.cornerStart[activeFace] + corner];
                            if (sourceCorner >= 0) {
                                target[mesh.faceVertexAt(faceId, corner)] |= trace.tagAt(tag,
                                        trace.faceOperand[faceId],
                                        input.faceVertexAt(trace.sourceFace[faceId], sourceCorner));
                            }
                        }
                    }
                    carried.put(tag, target);
                }
                slots.put(TagGeometryNode.TAGS_SLOT, carried);
            }
            for (String name : vertexFloatNames) {
                float[] target = new float[vertexBound];
                for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
                    int vertexId = mesh.vertexIdAt(activeVertex);
                    int source = trace.sourceVertex[vertexId];
                    if (source != MeshTopology.NONE && inputs.get(trace.vertexOperand[vertexId])
                            .slots().get(name) instanceof float[] values && source < values.length) {
                        target[vertexId] = values[source];
                    }
                }
                slots.putIfAbsent(name, target);
            }
            for (String name : fieldNames) {
                List<int[]> activeVertexOfId = new ArrayList<>();
                for (GeometryBundle input : inputs) {
                    int[] activeOfId = new int[input.mesh().vertexIdBound()];
                    for (int activeVertex = 0; activeVertex < input.mesh().vertexCount();
                            activeVertex++) {
                        activeOfId[input.mesh().vertexIdAt(activeVertex)] = activeVertex;
                    }
                    activeVertexOfId.add(activeOfId);
                }
                float[] target = new float[mesh.vertexCount()];
                for (int activeVertex = 0; activeVertex < mesh.vertexCount(); activeVertex++) {
                    int vertexId = mesh.vertexIdAt(activeVertex);
                    int operand = trace.vertexOperand[vertexId];
                    int source = trace.sourceVertex[vertexId];
                    if (source != MeshTopology.NONE
                            && inputs.get(operand).slots().get(name) instanceof FloatField field) {
                        int sourceIndex = activeVertexOfId.get(operand)[source];
                        target[activeVertex] = sourceIndex < field.length() ? field.get(sourceIndex)
                                : 0f;
                    }
                }
                slots.putIfAbsent(name, new FloatField(target));
            }

            int edgeBound = mesh.edgeIdBound();
            if (!markLabels.isEmpty() && !slots.containsKey(EdgeMarks.SLOT)) {
                Map<String, Object> carried = new LinkedHashMap<>();
                for (String label : markLabels) {
                    Object[] sourceMarks = new Object[inputs.size()];
                    Object kind = null;
                    for (int operand = 0; operand < inputs.size(); operand++) {
                        if (inputs.get(operand).slots().get(EdgeMarks.SLOT) instanceof Map<?, ?> m) {
                            sourceMarks[operand] = m.get(label);
                            kind = kind == null ? sourceMarks[operand] : kind;
                        }
                    }
                    Object target = kind instanceof float[] ? new float[edgeBound]
                            : kind instanceof int[] ? new int[edgeBound] : new boolean[edgeBound];
                    for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
                        int edgeId = mesh.edgeIdAt(activeEdge);
                        int source = trace.edgeSource[edgeId];
                        Object marks = sourceMarks[trace.edgeOperand[edgeId]];
                        if (source == MeshTopology.NONE || marks == null
                                || source >= Array.getLength(marks)
                                || marks.getClass() != target.getClass()) {
                            continue;
                        }
                        System.arraycopy(marks, source, target, edgeId, 1);
                    }
                    carried.put(label, target);
                }
                slots.put(EdgeMarks.SLOT, carried);
            }
            if (anyHandles && !slots.containsKey(AssignBezierHandlesNode.SLOT_HANDLES_START)) {
                PackedPoints start = new PackedPoints(edgeBound);
                PackedPoints end = new PackedPoints(edgeBound);
                for (int activeEdge = 0; activeEdge < mesh.edgeCount(); activeEdge++) {
                    int edgeId = mesh.edgeIdAt(activeEdge);
                    GeometryBundle input = inputs.get(trace.edgeOperand[edgeId]);
                    int source = trace.edgeSource[edgeId];
                    if (!trace.edgeDirected[edgeId]
                            || !(input.slots().get(AssignBezierHandlesNode.SLOT_HANDLES_START)
                                    instanceof PackedPoints sourceStart)
                            || !(input.slots().get(AssignBezierHandlesNode.SLOT_HANDLES_END)
                                    instanceof PackedPoints sourceEnd)
                            || source >= sourceStart.size() || source >= sourceEnd.size()) {
                        continue;
                    }
                    boolean reversed = trace.edgeReversed[edgeId];
                    PackedPoints startSource = reversed ? sourceEnd : sourceStart;
                    PackedPoints endSource = reversed ? sourceStart : sourceEnd;
                    start.set(edgeId, startSource.get(source, 0), startSource.get(source, 1),
                            startSource.get(source, 2));
                    end.set(edgeId, endSource.get(source, 0), endSource.get(source, 1),
                            endSource.get(source, 2));
                }
                slots.put(AssignBezierHandlesNode.SLOT_HANDLES_START, start);
                slots.put(AssignBezierHandlesNode.SLOT_HANDLES_END, end);
            }
        }

        if (trace.sourceFace == null) {
            return new GeometryBundle(mesh, slots);
        }
        boolean uvFits = anyUv && trace.cornerSource != null && !slots.containsKey(CornerUvField.SLOT)
                && mesh.faceIdBound() == mesh.faceCount()
                && trace.cornerSource.length == mesh.faceCount() * CornerUvField.CORNERS_PER_FACE;
        double[] cornerU = uvFits ? new double[trace.cornerSource.length] : null;
        double[] cornerV = uvFits ? new double[trace.cornerSource.length] : null;
        for (int activeFace = 0; uvFits && activeFace < mesh.faceCount(); activeFace++) {
            int faceId = mesh.faceIdAt(activeFace);
            uvFits = trace.sourceFace[faceId] != MeshTopology.NONE;
            GeometryBundle input = uvFits ? inputs.get(trace.faceOperand[faceId]) : null;
            CornerUvField uv = CornerUvField.of(input);
            if (uv == null || uv.faceCount() != input.mesh().faceCount()) {
                continue;
            }
            for (int corner = 0; corner < CornerUvField.CORNERS_PER_FACE; corner++) {
                int sourceCorner = trace.cornerSource[trace.cornerStart[activeFace] + corner];
                uvFits = sourceCorner >= 0;
                if (!uvFits) {
                    break;
                }
                int target = faceId * CornerUvField.CORNERS_PER_FACE + corner;
                cornerU[target] = uv.u(trace.sourceFace[faceId], sourceCorner);
                cornerV[target] = uv.v(trace.sourceFace[faceId], sourceCorner);
            }
        }
        if (uvFits) {
            slots.put(CornerUvField.SLOT, new CornerUvField(cornerU, cornerV));
        }

        if (perFaceMaterials) {
            List<MaterialData> materials = new ArrayList<>();
            int[] faceMaterial = new int[mesh.faceIdBound()];
            Arrays.fill(faceMaterial, MaterialSet.NO_MATERIAL);
            for (int activeFace = 0; activeFace < mesh.faceCount(); activeFace++) {
                int faceId = mesh.faceIdAt(activeFace);
                int source = trace.sourceFace[faceId];
                if (trace.faceOperand[faceId] == MeshTopology.NONE) {
                    continue;
                }
                GeometryBundle input = inputs.get(trace.faceOperand[faceId]);
                MaterialSet set = MaterialSet.of(input);
                IntField sourceIndex = MaterialSet.faceMaterialOf(input);
                MaterialData material = MaterialData.of(input);
                if (set != null && sourceIndex != null && source != MeshTopology.NONE
                        && source < sourceIndex.length()) {
                    material = set.get(sourceIndex.get(source));
                }
                int index = materials.indexOf(material);
                if (material != null && index < 0) {
                    index = materials.size();
                    materials.add(material);
                }
                faceMaterial[faceId] = material == null ? MaterialSet.NO_MATERIAL : index;
            }
            if (!materials.isEmpty()) {
                slots.put(MaterialSet.SLOT, new MaterialSet(materials.toArray(new MaterialData[0])));
                slots.put(MaterialSet.FACE_MATERIAL_SLOT, new IntField(faceMaterial));
            }
        }
        return new GeometryBundle(mesh, slots);
    }

    /**
     * One input vertex's flag under a tag.
     *
     * @param tag      tag name
     * @param operand  input index
     * @param vertexId input vertex id, or {@link MeshTopology#NONE}
     * @return whether that input tags the vertex
     */
    private boolean tagAt(String tag, int operand, int vertexId) {
        Map<String, boolean[]> tags = TagGeometryNode.getTags(inputs.get(operand));
        boolean[] flags = tags == null ? null : tags.get(tag);
        return vertexId != MeshTopology.NONE && flags != null && vertexId < flags.length
                && flags[vertexId];
    }
}
