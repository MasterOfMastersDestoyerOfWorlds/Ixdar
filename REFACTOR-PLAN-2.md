# Refactor plan 2: leftovers

The live plan. Everything executed before this file was cut is archived in
`REFACTOR-PLAN.md`; nothing here restates history. Method unchanged: Claude
enumerates with evidence, the author rules, work proceeds one verified batch at
a time, the author commits.

## Standing rulings (bind every item below)

- Data separate from algorithm: a stage produces a data class. Scratch instance
  fields on stages are fine; fields that propagate are not.
- Types name data structures, never stages or algorithms.
- Nested classes, records, and enums are illegal (see `CLAUDE.md`).
- One class per concern: no wrapper nodes, no marker or thin interfaces, no two
  ways to do one thing.
- A port's declared type is its real contract. Downstream consumers depend on
  the interface (`UvField` precedent), never cast to a specific producer.
- Cone points and feature edges flow as their own values (a per-vertex
  `IntField` index4 port, a per-edge `BoolField` selection port), never as
  sub-components of a UV field.
- All logging through `Platforms.log`. No `protected`. The author commits.
- One language (ruled 2026-08-30): everything a graph needs is expressed in
  the .dsl language itself. No sibling file formats; the .arr format is
  reversed (see A2).
- Element data flows in general shapes (ruled 2026-08-30): per-element
  selections and attributes (the BOOLEAN-field form the selection nodes
  already output), never a bespoke collection type per stage (see section E).
- Elements are selected geometrically (ruled 2026-08-30): authored graphs
  pick vertices by position and paths by shortest-edge-path with waypoints,
  never by literal vertex id; ids tie authored data to a primitive's
  id-generation order (the .arr disease).
- Patch boundaries are authored in the triangle winding (ruled 2026-08-30):
  walked counter-clockwise seen from outside, interior on the left, asserted
  at `addPatch`, never measured after the fact (see A10).

## Verification baseline

```
mvn -q clean install -pl annotations -DskipTests
mvn clean test -pl annotations,ixdar-app
./tools/teavm-build.sh
uv run ixdar-cli run-scene --scene quad-layout --timeout 90 --skip-build
```

157 tests; exactly 1 known-failing class (A7: DenseMeshFewSplitsTest, 1
failing testcase, "edge splits from 9 to 37"), 1 skipped (3 on hosts without
the Accelerate backend, see D6). TJunctionExtensionTest went green in the A7
batch and QuadMeshExtractionTest in the A4 batch. Scene must reach ready and
reproduce (post-A10, patches wound interior-left): 13853 quads, energy
5.5512e+07 -> 4.0197e+04, 49 iterations, 0/38450 flipped, singularities 44
(indexSum4 = -24), euler = -6, patches = 284, recarved copy V=15003 F=30018,
subdividedChords = 4216, interior/seam arcs 405/163, transitions = 568.
`[snap] chords laid` (4879..4884 observed) and `worstMove`'s last digit jitter
run to run and are not pins. Kill stray `IxdarWindow` processes after scene
runs.

## A. Quad layout

- [x] A1 DONE 2026-08-28, uncommitted. Every engine `build*` now runs its
      stage through the registered node's evaluate/ports (`run(node, names,
      values)` helper), and `QuadLayoutEngine` IS the registered `quad_layout`
      node (geometry + alpha_degrees + target_edge_length in; geometry, uv,
      tmesh, field, singularities out). Stage-instance caches died: the engine
      keeps port products only (`arrangement` ArcNetwork replaced
      `motorcycleGraph`; `seamlessFlippedTriangles`/`seamlessSystem` replaced
      `seamlessMetrics`; quantization diagnostics as `separationCutCount`/
      `separationViolated`/`quantizationVariableCount`; `embedding`/`layout`/
      `patchMaps`/`integerGrid` fields removed, `buildLayout`/
      `buildPatchMaps`/`buildLayoutEmbedding` folded into node-backed stages).
      New ports for consumers: motorcycle_graph `orphaned_traces`/
      `repeated_chain_nodes`; arc_quantization `separation_cuts`/
      `separation_violated`/`variables` ("separation_violated" added to the
      canonical BOOLEAN role names). `traceRecordsByFace` moved from the
      MotorcycleGraph stage onto ArcNetwork (durable arrangement data);
      `QuadLayoutRuntime.setMotorcycleGraph` now takes ArcNetwork.
      Alpha flows in degrees through ports; float
      radians->degrees->radians roundtrip verified exact for the alphas in
      use. A6's System.out sweep for seamless/motorcycle/engine done in the
      same pass. Consumers rewired: QuadLayoutScene, MotorcycleGraph/
      Parametrization scenes, EmbeddedTMeshPipelineTest, CarveOneToOneTest
      (builds its own carve on the quantized arrangement),
      QuadPipelineSeamTest, SeamlessPipelineBenchmark, GridMapPipelineBenchmark,
      ExtractionNodeProbe. `EmbeddedTMeshScene` keeps driving the engine
      directly as an interactive debugger. VERIFIED: 146 tests / same 3
      known-failing classes (1+2+2) / 1 skipped; TeaVM green (quad_layout in
      DESKTOP_ONLY_IDS); scene byte-identical (13853 quads, 5.5512e+07 ->
      4.0197e+04, 49 iterations, 0/38446 flipped, euler=-6).
- [x] A2 REDONE 2026-08-30, uncommitted (reopened and re-executed the same
      day; the 2026-08-28 .arr form below is history). Every
      fixture is now a .dsl graph plus
      an .arr arrangement file under `src/main/resources/dsl/fixtures/`
      (`**/*.arr` added to both pom resource include lists). NEW
      `ArrangementData` (embedding package, + 4 spec classes): a line-based
      text form for authored arrangements (orientation directive, named
      nodes/arcs/patches over source vertex ids, explicit per-arc flank lines
      so loop arcs and `resolveWalkOrientation` results replay exactly);
      parse/apply replays the container's own mutation API, capture/write
      serializes a built network. NEW `load_arrangement` node
      (`LoadArrangement`, mesh + path in, graph out; classpath then
      filesystem). The 10 fixture classes became thin shims: `build()`
      executes the .dsl through `NodeGraphRuntime`
      (`FixtureGraphs` helper) and resolves their named id fields from the
      .arr names, so all ~22 fixture tests kept their guards UNTOUCHED and
      the scene's fixture menu is unchanged. The .arr files were generated by
      a one-shot dump that reflected the fixtures' id fields into names and
      deep-verified replay equality (nodes/arcs/patches/flanks/orientation)
      before the Java authoring code was deleted; the parametric
      ScaledTorusLayoutFixture kept its (scale) ctor via per-scale .arr files
      (x1/x4/x8) and DSL literal overrides. VERIFIED: 146 tests, exactly the
      3 known-failing classes with IDENTICAL messages (stub T-junction,
      8x splits 9 -> 37, ring orientation undecidable), 1 skipped; TeaVM
      green; scene byte-identical.
      CORRECTION 2026-08-30 (author ruling): wrong shape, redo. The ask was
      fixtures expressed in the one .dsl language; A2 instead invented a
      second language (.arr: `ArrangementData` 415 lines + 4 spec classes +
      `LoadArrangement`, 12 .arr files, 580 lines) and kept all 10 fixture
      classes as shims that run the .dsl AND re-parse the .arr to resolve
      named ids, so the information now lives in three places. To delete:
      the .arr files, `ArrangementData` and its spec classes, the
      `load_arrangement` node, `FixtureGraphs`, and the 10 fixture Java
      classes; tests and the `EmbeddedTMeshScene` fixture menu load the .dsl
      files directly (at most one loader shared by the tests that need it).
      RULED 2026-08-30, the redo's shape: fixtures select geometrically,
      never by id. Node positions are authored coordinates (angle pairs on
      the torus) resolved to the nearest vertex; arc paths are shortest edge
      paths between them, with waypoints where a symmetric mesh would tie;
      the DSL statement name IS the element name a test resolves, so no name
      maps. This makes the scaled torus one graph with scale as an input
      (the per-scale .arr files existed only because literal ids do not
      survive density changes). Patches are authored in the triangle winding
      (A10), which deletes the .arr orientation directive and flank replay:
      with direction fixed, a loop arc's side is determined by which way it
      is walked. Missing nodes are small (E1): a deterministic
      nearest-vertex pick, and arrangement authoring nodes (arc from two
      endpoint selections plus a path selection, order derived by walking
      from the start; patch from arcs). The reframe stands: an arrangement
      IS elements + attributes (nodes/arcs/patches are vertices/edges/faces,
      arc length a per-edge int); the T-junction flank/cover bookkeeping is
      the one non-mesh feature the design must carry.
      EXECUTED 2026-08-30, uncommitted. NEW authoring nodes (embedding pkg):
      `arrangement` (ArrangementBase: mesh -> fresh net, interiorLeftOfWalk
      always true), `arrangement_node` (point -> nearest vertex; ties throw),
      `arrangement_arc` (from/to node ids + via1..via8 vector waypoints;
      unique-shortest-path Dijkstra by geometric length, ties throw "add a
      via"), `arrangement_patch` (corners a-d with repeats + optional
      first/second/third/fourth_side counts). NEW `ArrangementTracer`: face
      trace over arc-end darts in half-edge fan order keeping interior LEFT;
      patch = unique (face, corner-split) candidate, ambiguity throws asking
      for side counts; flanks set per traced step direction (the
      loop-arc-correct rule addPatch's startNodeId==walkNode test cannot
      express). NEW `AuthoredGraph` loader (runtime + net + id(name) lookups;
      statement names ARE element names) and `FixtureChoice` for the scene
      menu. The 10 fixture .dsl files were machine-generated from the old
      .arr networks and round-trip-verified element-exact (with the CCW
      rewind for the 5 orientation-right fixtures) BEFORE deletion;
      scaled_torus is ONE .dsl for all three scales via carrier overrides.
      DELETED: all 12 .arr files, ArrangementData + 4 spec classes,
      LoadArrangement (readText moved to AuthoredGraph), the whole
      embedding/fixtures package (10 fixtures, LayoutFixture, FixtureGraphs),
      pom .arr includes. 24 tests + EmbeddedTMeshScene/ModelScene +
      DenseSplitAttributionProbe rewired; ZERO assertion edits needed (flanks
      are physical, guards were id-based). NEW `ArrangementTracerTest` (7
      tests) replaces the migration scaffold. DEVIATIONS: optional-input
      defaults in ArrangementArc/ArrangementPatch go through
      FieldBroadcast.getInputOrDefault (graph statements omit optionals;
      NPE otherwise); scaled_torus header comment rewritten (documented the
      deleted tmesh.path shim); one comment-only javadoc unlink; generated
      float noise (~1e-16 sin residues) snapped to 0.0 post-generation.
      VERIFIED: 153 tests / only DenseMeshFewSplitsTest "9 to 37" / 1
      skipped; TeaVM green with zero arrangement/AuthoredGraph symbols in
      classes.js; quad-layout scene byte-identical (13853 quads, 5.5512e+07
      -> 4.0197e+04, 49 iterations, 0/38446 flipped, singularities 44,
      euler=-6).
      CORRECTED 2026-08-31 (author rulings), uncommitted. (a) The
      net-threading chain died: the graph executor is strictly sequential,
      so every statement references the constructor's net directly
      (`net=network.net`). (b) `AuthoredGraph` deleted: generic loading is
      `NodeGraphRuntime.executeResource(path, overrides)` + `readText` +
      `intOutput`/`lastOutput` on the runtime itself; `ModelScene` knows
      only (.dsl path, display name, overrides) as `GraphChoice` and zero
      ArcNetwork types — the network-typed handling moved down into
      `EmbeddedTMeshScene.loadGraph`. (c) "arrangement" vocabulary died:
      nodes renamed/moved to NEW `ixdar.geometry.mesh.nodes.network` —
      `arc_network` (ArcNetworkNode), `network_node`, `network_arc`,
      `network_patch`, `NetworkTracer` — fixture .dsl ids renamed to match.
      (d) `NearestVertex`/`UniqueShortestPath` moved to
      `data.representation` (general mesh utilities), and ONE shared
      Dijkstra core (`Dijkstra`/`EdgeRelaxation`/`ShortestPathForest`) now
      backs both `UniqueShortestPath` and `input_shortest_edge_paths`
      (bitwise-identical behavior for the latter; its illegal nested record
      also died). NEW general `nearest_vertex` selection node (not
      desktopOnly; selection + index out, tie-throw) — E1's remaining pick.
      DISCOVERY: the tree's TeaVM output had been a 36-byte stub —
      `Class.getPackageName` is missing in TeaVM 0.15 and
      `desktopRegistryMap`'s firewall used it, while teavm-build.sh swallows
      "built with errors" and reports SUCCESS — so earlier TeaVM
      verifications were vacuous. Fixed via an explicit FQN string; the
      script hazard is B4. RE-VERIFIED after all of this: 155 tests / only
      "9 to 37" / 1 skipped; TeaVM genuinely green (6.77MB classes.js,
      network authoring desktop-only, nearest_vertex web-registered); scene
      byte-identical.
- [x] A3 DONE 2026-08-28, uncommitted. Every runtime setter now takes a
      port-type value. `setMotorcycleGraph(ArcNetwork)` (done in A1's pass;
      trace overlay reads `network.traceRecordsByFace`).
      `setSeamlessParametrization(UvField, mesh)`: `uploadSeamlessSurface`
      reads u()/v() through the interface and computes the flip flag inline
      (sign-identical to `SeamlessUv.uvSignedArea`); no SeamlessUv reference
      remains in the runtime. `setLayoutEmbedding` had NO callers and is
      deleted (embedded-arc lines come from `setEmbeddedTMesh`).
      `setDiagnostic(List<float[]>, List<float[]>, List<float[]>)` takes
      world-space clouds and polylines; the id-to-position resolution moved
      onto `ArrangementDiagnostic` as resolve methods
      (faceGroupCenters/markerGroupPositions/pathPolylines, all taking the
      copy mesh), and `EmbeddedTMeshScene.showStepDiagnostic` does the
      conversion at its three call sites. `setLayoutPatchSurfaces` keeps its
      signature but `LayoutPatchSurfaces` is now itself a port-typed value:
      new `PortType.PATCH_SURFACES`, and the class is the registered
      `patch_surfaces` node (uv + initial in, surfaces out) — this also
      closes A6's "Coons/surfaces conversion node" bullet. New
      `PatchGridExtraction.fromRelaxedMap(GlobalGridMap)` dedups the
      regrouping the engine's `buildQuadGrid` and the node both need; engine
      `buildPatchSurfaces` runs the node twice (relaxed + initial).
      VERIFIED: 146 tests / same 3 known-failing classes / 1 skipped; TeaVM
      green; scene byte-identical.
      STILL OPEN (author unsure yet): decouple rendering order from rendering,
      so a scene decides the order overlays draw in rather than the runtime.
      CORRECTION 2026-08-30 (author ruling): the port-typing landed but the
      expected shrink did not; `QuadLayoutRuntime` went 2339 -> 2266 lines
      (3%). The runtime body must shrink substantially; tracked with A8.
      EXECUTED 2026-09-02, uncommitted. `QuadLayoutRuntime` 2269 -> 1151
      lines (-49%; -51% against HEAD's 2339), plus four new top-level
      classes in `graphics/render/model` totalling 262 lines (`VertexLayout`
      28, `VertexBuffer` 78, `LineSet` 77, `PointSet` 79); net 1413 lines
      for what was 2269 (-38%). DUPLICATION MAP (before): 10 GL buffer sets
      in three vertex layouts — position-only vec3 (icosphere, layout
      lines, Coons fill, copy wireframe, embedded arcs, embedded zero arcs,
      N diagnostic path lines), the 16-float cross glyph (cross field,
      constraints), the 26-float iso corner (one buffer, fed by two
      producers) — with SEVEN hand-rolled gen/bind/bufferData/attrib
      sequences (`buildIcosphereBuffers`, `uploadGlyphBuffers`,
      `uploadTraceSurfaceBuffers`, `uploadLineBuffer`, and two inline in
      `setLayoutPatchSurfaces`) and FOUR `BufferUtils.createIntBuffer`
      sites; six point-set overlays each holding their own parallel arrays
      (singularities pos+index4, graph nodes pos+colors, layout corners,
      embedded nodes pos+critical+radii, patch clouds List<float[]>+radii,
      diagnostic centres/markers List<float[]>) drawn by SEVEN copies of the
      bind-sphere / translate-scale / setMat4 / setVec4 / drawElements loop
      (`renderSingularitySpheres`, `renderGraphNodes`, the corner loop in
      `renderLayoutBoundaries`, `renderEmbeddedNodes`, `drawHighlightRegion`,
      `drawHighlightMarker`, patch clouds via `drawHighlightRegion`); six
      copies of the unlit line draw (copy wireframe, quad grid, boundaries,
      embedded arcs x2, `drawHighlightLine`); eight copies of the unlit
      use/view/projection/model-identity preamble; two producers of the
      same iso-corner layout (`uploadSeamlessSurface` from `UvField` +
      traces, `uploadPatchParametrization` from raw `double[]`/`boolean[]`,
      both looping faces, positions, normals, corners, identity indices);
      five delete helpers plus a 55-line `dispose` repeating the
      if-nonzero-delete-zero pattern per handle. DEAD: `showWitnesses` and
      `showEppsteinMarkers` (set by mcg-exam's W/E controls, never read —
      flags and controls deleted), `hasCrossField()` (no caller), public
      `uploadCrossField` (only `setCrossField` called it — merged),
      `setSphereRadiusCap` (internal only — private), 8 unreferenced
      constants (DEFAULT_CROSS_SCALE, NEAR_PLANE, FAR_PLANE_DIAG_MUL,
      SPHERE_FAR_FALLBACK, SPHERE_TINT_*), and the ~60 public GL-handle /
      parallel-array fields nothing outside read (now private buffers and
      sets). No per-frame direct-buffer allocation existed (the four
      `createIntBuffer` sites were all upload-time); they are now ONE, in
      `VertexBuffer.upload` (B2's QuadLayoutRuntime import site moved to
      that class — same count, not fixed, not worsened). LAYOUT CLASSES:
      `VertexLayout` (attribute locations + float sizes, stride derived) with
      three runtime constants POSITION / ISO_CORNER / CROSS_GLYPH;
      `VertexBuffer` (vao/vbo/ebo + counts, `upload(layout, floats,
      indices|null)` frees-then-uploads, `delete()`) is the one upload path
      per layout; `LineSet` (flat xyz, `point(...)` builder, `polyline()`);
      `PointSet` (xyz + per-point colour + per-point world radius cap + one
      scale, `cloud()` builder). Every overlay is now "convert into one of
      those": the runtime keeps 9 `VertexBuffer`s + a diagnostic list, 4
      `PointSet`s + 3 `PointSet` lists, and ONE `drawSpheres(PointSet)`, ONE
      `drawLines(buffer, first, count, colour, width, bias)`, ONE
      `drawTriangles(buffer, first, count)`, ONE `beginUnlit` / ONE
      `beginCrossField` preamble, ONE `uploadIsoSurface(mesh, UvField,
      BoolField|null, traceRows|null)` behind both producers. SETTERS:
      `uploadPatchParametrization(HalfEdgeMesh, double[], double[],
      boolean[])` (the one non-port-typed setter left) became
      `setGridMapParametrization(UvField, HalfEdgeMesh, BoolField)` —
      `GridMapIsoSurface` already IS a `UvField`, and its fold flag stays a
      separate per-face `BoolField` because it encodes chart orientation,
      not UV area sign (verified: `doubleArea == 0 || (doubleArea > 0) !=
      counterClockwise`); `captureSingularities` -> `setSingularities`,
      `uploadConstraints` -> `setConstraints` (naming only, callers
      updated); `diagnosticFaceGroupCenters` public field died —
      `EmbeddedTMeshScene.showStepDiagnostic` returns the centres it
      uploaded and `displayDiagnostic` frames on those. DEVIATION (one):
      patch-cloud dot radius was `sphereRadius * min(1, dot/sphereRadius)`,
      embedded-node radius `min(sphereRadius, cap)`; both are now the one
      `PointSet` rule `min(sphereRadius * scale, cap)` — identical for
      nodes, within one float rounding for clouds, and a cloud whose dot
      radius is 0 (only an empty patch cover) now draws uncapped instead of
      being skipped. Everything else is byte-identical: iso corners, glyph
      corners, line vertices, fill indices, index buffers, palette order,
      draw order, depth biases, line widths. STILL-OPEN scene-decides-draw-
      order did NOT fall out free (the toggles-to-order switch in
      `renderOverlays` is unchanged) — left open. VERIFIED: 157 tests /
      only DenseMeshFewSplitsTest "9 to 37" / 1 skipped; TeaVM genuinely
      green ("Output file successfully built", classes.js 6,705,113 bytes
      vs E4's 6,731,598; VertexBuffer/LineSet/PointSet/VertexLayout
      present, uploadPatchParametrization/showWitnesses absent);
      quad-layout scene byte-identical on every pin (13853 quads,
      5.5512e+07 -> 4.0197e+04, 49 iterations, 0/38446 flipped,
      singularities 44 indexSum4=-24, euler=-6, patches=284); toggle drive
      through the automation server: C, O (PRE-relaxation / relaxed logged),
      Q, P, B, E, N, G x3 (INITIAL / RELAXED / off logged), T, X — zero
      exceptions; embedded-tmesh (V off/on, W on/off, R on/off, N, D —
      contract and drag logged), mcg-exam (T, N, T), param-exam,
      cross-field-exam (C, X, R, X, C) all ready with zero exceptions.
- [x] A4 DONE 2026-08-29, uncommitted (built by an isolated worktree agent,
      merged and re-verified in the primary tree). `QuadMeshExtraction` is
      face-local: its constructor is (copy mesh, `UvField`, `ArcNetwork`);
      per-face orientation comes from the exact UV signed area, and each seam
      crossing's quarter-turn + integer-translation automorphism is derived
      locally from the shared edge's endpoint UVs (bitwise-exact under
      verification's canonicalization), replacing every
      `atlas.mapPoint`/`mapTurns`/`chartAcross` call plus the
      uvByPatchId/dense-map/patchMaps-region dependencies; dead `indexFaces()`
      deleted. Ports are emitted in SURFACE-fixed rotational order, so
      `ExtractedPatchGrids.determineRingStep` and its throws are deleted for a
      constant ring step (validated per anchored node). `ExtractedQuadMesh`
      dropped its reader-less fields (chart UVs, connection transforms, quad
      edge endpoints); the consumed contract is unchanged.
      `GridMapIsoSurface` implements `UvField`; `GridMapAssembly.extractQuads`
      bakes a fresh iso-surface after verification (fixing a stale-UV hazard
      when a second optimizer pass ran without rebaking); `QuadExtractNode`
      no longer hard-casts for extraction (an instanceof route remains only
      for the regrouping wiring). DEVIATION recorded: one setup line was
      removed from QuadMeshExtractionTest (a free-node `gridDofs.relax()`
      that moved every node off-grid, contradicting the test's own docs and
      its original passing revision, and leaving the anchor-free fixture
      unanchorable under baseline code too); the freed-relax path is still
      exercised byte-identically by the scene. VERIFIED merged: 146 tests /
      only DenseMeshFewSplitsTest failing / 1 skipped; QuadMeshExtractionTest
      2/2 green; TeaVM green; scene byte-identical incl. the
      `[patch-grids]` line.
      CORRECTION 2026-08-30 (author ruling): `QuadExtractNode` still
      hard-casts, now by throwing. Its UV input declares UV_FIELD but
      `evaluate()` throws unless the value is a `GlobalGridMap`
      (QuadExtractNode.java:61), breaking the declared-type-is-the-contract
      ruling with a nicer error message. Split the stages: extraction proper
      consumes any `UvField` (A4 already made it face-local); the
      regroup-onto-layout step that genuinely needs the grid map moves to
      its own node or input.
      EXECUTED 2026-08-31, uncommitted. `quad_extract` now declares its
      real contract: uv (any UV_FIELD) + geometry + tmesh in, geometry out;
      zero instanceof; evaluate is exactly `new QuadMeshExtraction(mesh,
      uv, tmesh).build().toArrayMesh()`. The regroup stayed in
      `GridMapAssembly.extractQuads` (not a node: no graph consumer exists,
      and the regroup needs the full ExtractedQuadMesh, which has no port
      form — a bespoke PortType would be the E4 mistake reborn); the
      stale-UV re-bake is preserved and verified load-bearing. The engine
      calls extractQuads directly and builds its bundle from
      `quadMesh.toArrayMesh()` — NOTE for the author: this means the
      extraction stage no longer runs through the node's ports (A1's
      pattern), because the node's honest contract (pure extraction) and
      the engine's stage (extraction + regroup) are now different
      operations. `QuadLayoutNodeChainTest` rewired: it now genuinely
      exercises the node with a NON-GlobalGridMap UvField (a plain
      GridMapIsoSurface), which the old node would have thrown on;
      assertions unchanged. Observation logged for E4/A9: LayoutPatchSurfaces
      still hard-casts its UV input to GlobalGridMap (same disease, slated
      type), and quad_extract now has no .dsl consumer. VERIFIED: 155 tests
      / only "9 to 37" / 1 skip; TeaVM real (6.77MB); scene byte-identical.
- [x] A5 DONE 2026-08-28, uncommitted. `EmbeddedTMesh` renamed `ArcNetwork`
      (file + word-boundary sweep, 86 files; `EmbeddedTMeshRecarve` ->
      `ArcNetworkRecarve`); the container keeps data, the mutation API,
      validation, and diagnostics. NEW `NetworkContraction`
      (embedding package) IS the `tmesh_contract` node and owns the four
      operators, the collapse/split/conform counters, and contract()/conform()/
      recarve()/contractStep()/stepUpdatedPatches(); the `TmeshContractNode`
      wrapper is deleted. `PatchCorridor` moved onto the container (it is a
      flood query the container's own validation uses; this also dissolved the
      ZeroArcCollapseOperator -> splitPatch cross-reference). Tear diagnostics
      now take operator-count decoration from the contraction (`progress()`)
      instead of reading counters off the container. `EmbeddedTMeshScene`
      holds a `NetworkContraction` rebound on every recarve; engine uses
      `new NetworkContraction(tmesh).contract()`; ~25 tests/probes rewired to
      one contraction instance each (preserving single-operator-set
      semantics, incl. the known-failing TJunctionExtensionTest's counter
      read). `SnappingCarve`/`ArcNetworkRecarve` stay shared algorithm
      classes, not nodes. VERIFIED: 146 tests / same 3 known-failing classes
      (2+1+2) / 3 skipped; TeaVM green; scene identical (13853 quads,
      4.0197e+04, 49 iterations, 0/38446 flipped, euler=-6).
      6.12 RESOLVED in A1 (behavior-preserving call, review welcome): the
      engine passes CONFORM=false explicitly, so the pipeline still never
      re-conforms after the recarve (the scene baseline pins this, and
      QuadPipelineSeamTest characterizes it); the node's CONFORM input
      (default true) is the opt-in post-recarve conform for graphs; the
      engine's `conforming` flag and `buildContractedTMesh` javadoc now say
      exactly this.
- [x] A6 Small quad-layout items (all sub-items done; parent ticked 2026-09-03).
      - [x] `System.out.println` sweep in seamless/motorcycle/engine: done in
        the A1 batch. LEFTOVER found 2026-08-31: `LayoutPatchMaps.java:123`
        still prints `[patch-maps]` via System.out; move to Platforms.log
        in the A10 batch (same pins, same line). DONE 2026-09-02 in the A10
        batch: all three `[patch-maps]` System.out lines in LayoutPatchMaps
        now go through Platforms.log, text unchanged.
      - [x] `ChartWalker` now applies `CutGraph.atlas` transitions instead of
        deriving them numerically: `ChartAtlas` flows as a new CHARTS input on
        `motorcycle_graph` (from seamless_uv's CHARTS output), `ChartWalker`
        takes it in its constructor, and `crossEdge` applies the transition
        triple via `IntegerGridMap.rotate`. VERIFIED byte-identical (the
        exact-seam projection makes the stored transitions and the old numeric
        snap agree bitwise): suite, TeaVM, scene all unchanged.
      - [x] Coons/surfaces conversion node: done in the A3 batch
        (`patch_surfaces`).
      - [x] The motorcycle overlay draws only arrangement-positioned nodes;
        operator-minted nodes have no position. Cosmetic, stays documented in
        the renderer comment; no action.
      - [x] `ARCHITECTURE.md` updated: node-integrated pipeline and engine,
        port-typed runtime, fixture .dsl/.arr form, Cholesky ladder, known
        failures now referenced from this plan's baseline.
- [ ] A7 Investigation of the three pre-existing failing classes.
      - [x] `TJunctionExtensionTest` FIXED (2/2 green). Mechanism: the failures
        were a stage-semantics mismatch, not a pipeline bug. `contract()` grew
        an internal conform-before-recarve during the 6.x work, so by the time
        the test counted T-junctions the stub was already extended
        (tjunctionsBefore == 0) and the later explicit `conform()` had nothing
        to do. Fix: `NetworkContraction.contractRounds()` exposes the
        rounds-only stage (in place, no conform/recarve); `contract()` is now
        the composition contractRounds + conform + recarve, byte-identical for
        the pipeline; the test drives `contractRounds()` then `conform()`,
        genuinely exercising the extension operator again.
        CORRECTION 2026-08-30 (author ruling): `contractRounds()` is churn.
        It has exactly two callers, `contract()` itself and
        TJunctionExtensionTest; API minted for one consumer. Remove it and
        find another way for the test to exercise the extension operator
        (part of the A9 sweep).
      - [ ] `DenseMeshFewSplitsTest` ATTRIBUTED to its mechanism, fix needs an
        author ruling. A stepping probe
        (`test/benchmark/DenseSplitAttributionProbe`) shows the scale-8
        torus's 37 splits are 1+1+1 (three arc collapses), +2 (one patch
        split), and +32 in a SINGLE arc collapse; a stack trace on
        `splitEdgeAtParameter` puts 35 of 37 in
        `ZeroArcCollapseOperator.routeDraggedTail -> ArcRerouter.tryRoute`.
        Deeper finding: the router is NOT wasteful - its gate pass computes
        the minimum-split corridor and the refined pass walks exactly it, so
        32 mints are provably minimal GIVEN the claims: the dragged tail is
        confined to a channel whose wall vertices are all claimed by the two
        flanking arc paths, so the only admitted route is a minted midpoint
        per free rung, and channel length scales with mesh density. The
        defect is the drag's admission/claim-release policy (what region the
        drag may route through, and when the collapsing arc's claims free
        up), not the search. Changing that policy touches exactly the
        operator semantics the pathological fixtures pin (minted lanes are
        asserted by MergedCellSlot/PinchedCover tests), so it needs a design
        ruling, not a patch.
        RULED 2026-08-31: option (i), release the collapsing arc's claims
        before routing the dragged tail. SEQUENCING (author): NOT part of
        this refactor's commit — the probe starts only after the A/E
        refactor is committed, so a failed probe never mixes into the
        refactor diff. AUTHOR'S WARNING: this was tried
        once before and failed — with the region opened up, some newly
        routed arcs cut off arcs that still needed to route through it; the
        bug was never tracked down. The batch must therefore (1) treat it
        as a probe with the full fixture suite as the oracle, (2) if the
        cut-off recurs, find its mechanism (which routing order, which
        claim) rather than patching around it, and (3) report every
        minted-lane assertion that moves, for a ruling, before changing any
        fixture guard.
      - [x] `QuadMeshExtractionTest` FIXED (2/2 green) by the A4 face-local
        extraction: ports are emitted in surface-consistent order, so the
        ring step is a constant and the "undecidable" derivation is gone
        (plus the one-line test correction recorded under A4).
- [x] A8 QuadLayoutEngine chaining rework (ruled 2026-08-30). The engine grew
      347 -> 472 lines (+36%): every `build*` stage is ~15 lines of
      `run(node, new String[]{...}, new Object[]{...})` parallel-array
      plumbing plus per-output `ctx.getOutput` unpacking, and 13 diagnostic
      counters sit as public engine fields. Replace the helper with a concise
      typed chain that uses the declared `InputPort`/`OutputPort` constants
      directly (e.g. `run(node).with(PORT, value)...`, ports read off the
      returned context); NO second execution path beside the nodes. Revisit
      which stage counters deserve engine fields at all. Pairs with the
      `QuadLayoutRuntime` shrink recorded under A3's correction.
      EXECUTED 2026-08-31, uncommitted. ZERO new classes: the chain lives on
      `MapNodeContext` itself (`with(InputPort, value)` / `eval()` /
      `output(OutputPort, Class)`, the node now stored by the constructor),
      so one context class stays the one execution path — every stage is
      still `node.evaluate(ctx)` on a MapNodeContext. Engine 469 -> 413
      lines; the `run(node, String[], Object[])` helper died. Field verdicts
      (caller evidence in the batch report): DIED `conforming` (never
      written anywhere — QuadPipelineSeamTest's assertFalse was vacuous and
      is deleted), `seamlessSystem` (one reader, SeamlessPipelineBenchmark,
      which now runs the seamless node itself through the chain and reads
      DOFS off the context), `gridSystem` (no reader; now a local in
      buildGlobalGridMap), `quadGrid`+`buildQuadGrid()` (one caller,
      ExtractionNodeProbe, which now calls
      `PatchGridExtraction.fromRelaxedMap(engine.buildGlobalGridMap())`),
      `patchSurfacesInitial` (one reader, QuadLayoutScene's comparison
      toggle, which now builds the INITIAL=true surfaces through the
      `patch_surfaces` node lazily on first toggle). KEPT
      `seamlessFlippedTriangles` (3 readers: ParametrizationExaminationScene,
      SeamlessPipelineBenchmark, QuadPipelineSeamTest),
      `orphanedTraceCount`/`repeatedChainNodeCount`/`separationCutCount`/
      `separationViolated`/`quantizationVariableCount` (all read by
      EmbeddedTMeshPipelineTest's Lyon-invariant assertions; reading the node
      outputs there would duplicate the engine's stage wiring in the test),
      `quantized`/`contracted` (lazy-stage guards for in-place mutation;
      `contracted` also read by QuadPipelineSeamTest), `seamlessCharts`/
      `singularities`/`featureEdgeIds` (cross-stage inputs to
      buildMotorcycleGraph). VERIFIED with A9 below.
- [x] A9 Single-caller churn sweep (ruled 2026-08-30). The batch introduced
      methods with one real consumer (`contractRounds`, see A7 correction;
      audit the rest of the diff for the same pattern). Remove what exists
      only to serve one test or one call site.
      EXECUTED 2026-08-31, uncommitted. `contractRounds()` is private; the
      stage split is graph vocabulary instead: `tmesh_contract` grew a
      RECARVE input (default true) beside CONFORM, so evaluate runs
      contract() when recarve is on and the in-place rounds alone when off,
      then the optional conform — the engine's CONFORM=false call and the
      default graph call are byte-identical compositions.
      TJunctionExtensionTest drives the node with conform=false,
      recarve=false through the MapNodeContext chain, counts T-junctions
      (still > 0 — the stub's junction survives, genuine exercise), then
      conforms via a fresh `NetworkContraction(net).conform()` whose
      extensionCount test 2 still reads. Sweep verdicts:
      `PatchGridExtraction.fromRelaxedMap` KEPT (LayoutPatchSurfaces +
      ExtractionNodeProbe), `ExtractedQuadMesh.toArrayMesh` KEPT
      (QuadExtractNode + engine buildGlobalGridMap),
      `NodeGraphRuntime.executeResource`/`lastOutput`/`intOutput` KEPT
      (2 scenes + ~27 test classes); `NodeGraphRuntime.readText` made
      private (only executeResource called it). KEPT with reasons:
      `UniqueShortestPath.find` (one caller, NetworkArc, but it IS the
      ruled 2026-08-31 A2-correction shape — the tie-throwing face of the
      shared Dijkstra core), `NetworkTracer` (algorithm class of the
      `network_patch` node, SnappingCarve precedent),
      `ArrangementDiagnostic.markerGroupPositions`/`pathPolylines` (one
      scene caller each, but the A3-verified resolve trio used together at
      showStepDiagnostic's three call sites; faceGroupCenters has 2+),
      `QuadLayoutRuntime.setDiagnostic` (one caller; pre-existing upload
      API reshaped in A3, not minted), engine `buildMotorcycleGraph`/
      `buildQuantization` (MotorcycleGraphExaminationScene /
      CarveOneToOneTest), `contractStep`/`stepUpdatedPatches`
      (EmbeddedTMeshScene + 5 probes). VERIFIED: 155 tests / only
      DenseMeshFewSplitsTest "9 to 37" / 1 skipped; TJunctionExtensionTest
      2/2 green with tjunctionsBefore > 0 before the explicit conform;
      TeaVM genuinely green ("Output file successfully built", classes.js
      6,769,629 bytes, nearest_vertex web-registered, NetworkTracer
      absent); quad-layout scene byte-identical (singularities=44
      indexSum4=-24, 5.5512e+07 -> 4.0197e+04, 49 iterations, 0/38446
      flipped, quads=13853, euler=-6, patch-grids patches=284).
- [x] A10 Patch winding convention (ruled 2026-08-30). Triangles are wound
      counter-clockwise seen from outside (normals derive from winding; a
      half-edge from->to belongs to the face on the LEFT of travel, the
      identity `interiorLiesLeftOfWalk` already uses). Patches adopt the
      same contract: boundary walked CCW, interior left, asserted in
      `addPatch` via the walk's first embedded half-edge. Then
      `interiorLeftOfWalk` is constantly true and dies,
      `resolveWalkOrientation` shrinks to a validator or dies, the
      `interiorLiesLeftOfWalk`/`floodStaysInside` measurement dies, and the
      PatchCorridor walked-the-other-way caveat dies; "ring orientation
      undecidable" becomes an authoring error naming the backwards patch.
      The walk direction only ever originates in authored code (main-code
      `addPatch` callers are the recarve copy, the .arr loader, and patch
      splits, all direction-preserving), so the cost is rewinding the
      hand-authored walks: the 10 fixtures (rewritten in A2 anyway) and the
      unit tests that authored the other way (ZeroLoop*, EmbeddedTMeshTest
      and kin).
      PROGRESS 2026-08-30: the A2 redo delivered the fixture half — every
      authored fixture now builds through the tracer with
      interiorLeftOfWalk=true and correct per-end flanks. REMAINING: rewind
      the unit tests that still hand-call addPatch the other way, assert the
      winding in addPatch, then delete interiorLeftOfWalk,
      resolveWalkOrientation's measurement (interiorLiesLeftOfWalk/
      floodStaysInside), and the PatchCorridor caveat; confirm the pipeline's
      own patch construction is CCW before asserting.
      BLOCKED 2026-08-31, needs an author ruling. Step-0 finding: the
      pipeline walks interior-RIGHT (PatchBoundaryBuilder's "next CCW port"
      arrangement walk; measured false at assembly patches=2051 and recarve
      patches=284). BOTH flip transforms — plain reversal [S3',S2',S1',S0']
      and the role-preserving rotation [S0',S3',S2',S1'] — produce the SAME
      scene divergence, so it is the walk direction itself, not side-role
      swapping: the contraction's chord-lane decisions shift (chords laid
      4884 -> 4881, corner-served crossings 4416 -> 4417), the recarved copy
      lands V=15004->15003 F=30020->30018, subdividedChords 4213 -> 4216,
      and the optimizer denominator moves 0/38446 -> 0/38450; interior/seam
      arcs reclassify 419/149 -> 405/163. Every quality pin is unchanged
      (energy 4.0197e+04, 49 iterations, 0 flipped, quads 13853, euler -6,
      singularities 44, transitions 568). Scene logs saved in the session
      scratchpad (scene-preflip-baseline.log / scene-rotated-flip.log), the
      candidate diff as a10-candidate-flip.patch; the tree is reverted to
      the interior-right baseline meanwhile. OPTIONS: (a) accept 0/38450 as
      the post-A10 baseline pin and complete the batch (recommended:
      quality-neutral, kills the measurement machinery per the ruling);
      (b) keep the pipeline interior-right and enforce the convention only
      for authored networks — A10 stays permanently half-done and
      resolveWalkOrientation's measurement survives.
      RULED 2026-08-31: option (a). 0/38450 is the post-A10 baseline pin
      (the denominator is GridMapOptimizer's region triangle count, recarved
      copy faces + 2 x subdivided chords; the relaxation itself is unchanged:
      same 49 iterations, same energy, zero flips). Apply the parked flip,
      complete the container conversion and test rewinds, update the
      verification-baseline block to 0/38450 and the new recarve/chord
      numbers.
      EXECUTED 2026-09-02, uncommitted. The rotated flip is in
      `PatchBoundaryBuilder.splitSides` (each walked side reversed, sides 1
      and 3 trade places, side 0 stays the width side).
      `ArcNetwork.interiorLeftOfWalk` is gone; `addPatch` and `assemble`
      flank by the winding alone (arc traversed start-to-end => left).
      `resolveWalkOrientation` became `validateWalkOrientation`, a throwing
      check called at the same two points (assemble, recarve) with
      `interiorLiesLeftOfWalk`/`floodStaysInside` as its internals; message
      "patch N is wound backwards: boundary walks must run counter-clockwise
      seen from outside, interior on the left". `ArcNetworkNode` no longer
      sets the field, PatchCorridor's walked-the-other-way caveat is deleted,
      NetworkTracer's per-step flank overwrite stays. Test rewinds (assertions
      untouched): EmbeddedTMeshTest's ten hand walks rotated to interior-left
      (helper takes bottom, left, top, right from the bottom-right corner;
      first corners moved one side along; the two-arc sides reversed);
      ZeroArcLoopCollapseTest's first corner startNode -> endNode;
      ZeroLoopPointPatchCollapseTest's hand-set loop flanks swapped to
      left=farPatch, right=pinchedPatch (the loop's forward walk has its
      square on the left); ZeroLoopBigon/CriticalNode walks are loop-only,
      identity under the rewind. PatchInteriorSeedTest's swap-and-restate case
      became "a patch wound backwards is named by the validator" (a reversed
      duplicate of plane_layout.dsl's first patch, message asserted verbatim).
      Finding: the flood check is vacuous on a bordered grid whose complement
      is bounded by the same arcs (every Zero* fixture passes it either way),
      so those windings were derived from the grid chart (left of a +column
      step is the row-1 face) rather than measured. VERIFIED: 157 tests /
      1 failing (DenseMeshFewSplitsTest 9 to 37) / 1 skip, fixture tests and
      NetworkTracerTest untouched-green; TeaVM "Output file successfully
      built", classes.js 6,702,958 bytes, validateWalkOrientation and its
      message present, interiorLeftOfWalk absent, NetworkTracer absent,
      nearest_vertex web-registered; quad-layout scene twice: flipped=0/38450,
      5.5512e+07 -> 4.0197e+04, 49 iterations, quads=13853, euler=-6,
      singularities 44 indexSum4=-24, patches=284, recarve V=15003 F=30018,
      subdividedChords=4216, interior/seam 405/163, transitions=568 on both
      runs; chords laid 4884 / 4879 (jitter). No stray IxdarWindow.

## B. Web build hygiene

- [ ] B1 `MeshNodeViewerScene` is what `WebLauncher` instantiates, and it calls
      `Files.readAllBytes`/`MeshLoader` (`java.nio.file`); that path throws in
      a browser.
- [ ] B2 `HalfEdgeMeshRuntime`, `QuadLayoutRuntime`, `IcosphereRuntime`, and
      `AssimpModelRuntime` import `org.lwjgl.BufferUtils` directly instead of
      `IxBuffer`, so LWJGL buffer code is emitted into the shipped JavaScript.
      `SDFUnion` static-imports `org.lwjgl.opengl.GL13` texture constants, the
      one GL-abstraction violation in `graphics` outside `render/model`.
- [ ] B3 `PatchRenderer` stays out of the web build only by method-level
      dead-code elimination; one call to `renderMultiview` from web-reachable
      code pulls `Graphics2D` in.
- [ ] B4 (found 2026-08-31) `tools/teavm-build.sh` swallows TeaVM's "Output
      file built with errors" and exits 0, so a broken build ships a 36-byte
      classes.js stub while reporting SUCCESS — this masked the
      `Class.getPackageName` breakage for an unknown stretch. Make the
      script fail on TeaVM [ERROR] output.

## C. Bugs and dead code (ticket candidates)

- [ ] C1 Mesh-boolean provenance: recover `runOriginalID` from the MeshGL64
      segment to restore provenance tinting; `MeshBooleanProvenanceTest`'s
      provenance half is `@Disabled` pointing at this. Also answer why the old
      backend reported `faces new=0/36` without flagging an intersection face.
- [ ] C2 Tickets: `IX-6` exists in both `content/IX/` and `done/IX/` with
      contradictory statuses; `BOARD.md` is stale and omits the DSL epic.
- [ ] C3 `instance_on_points` writes the `_instance_mesh` slot but nothing
      reads it; instances placed on 0 or 1 points are silently dropped instead
      of realized.
- [ ] C5 (found 2026-08-31) `join_geometry` drops curves: it appends the two
      meshes topologically and merges `_tags`/bone-weight slots, but has zero
      references to `CurveGeometry.SLOT`, so joining two bundles that each
      carry a curve silently loses both. `transform_geometry` honors the
      slot; join, whose whole purpose is the logical-join semantics, does
      not. One-node fix.
- [ ] C6 (found 2026-08-31) Two attribute conventions share the bundle slot
      map: legacy raw `float[]`/`boolean[]`/`Map<String,boolean[]>` slots
      (`_tags`, `_bone_weight_*`, bezier handles, boolean provenance) and
      the `IntField`/`Vector3Field`/`BoolField` objects the E-section built
      on (`half_extent`, `cell_type`, `patch_id`). Pick one; the Field form
      is the general shape the selection nodes already use.
- [ ] C4 Terminal `.help` files can never load: `TerminalCommand` builds the
      stale path `./src/shell/terminal/help/` while files live at
      `ixdar/gui/terminal/help/`, and the pom's resource includes omit
      `**/*.help`. `manifoldtest.help` has no matching command.

## D. Tooling, docs, platform

- [ ] D1 Rewrite `README.md` as a short overview: what Ixdar is, how to build,
      `IXDAR_ASSET_REPO_ROOT`, pointer to `ARCHITECTURE.md`. No TSP link (that
      material lives in `KriegEterna/web/content/essays/tsp-adventures.md`).
- [ ] D2 Migrate still-true `.cursor/` content into `Ixdar/CLAUDE.md`, then
      delete `.cursor/`.
- [ ] D3 Node test coverage: 95 registered nodes, 3 tests.
- [ ] D4 Decide what replaces the `ixdar-tickets` submodule; the gitlink reads
      as deleted and the standalone repo is at `~/Code/ixdar-tickets`.
- [ ] D5 Verify two autofix recipes that look unreachable
      (`MultipleStringLiteralsRecipe` keyed to a renamed check,
      `MagicNumberRecipe` keyed to a commented-out rule) and find where
      `InlineGlueStringConstantsRecipe` is invoked, if anywhere.
- [x] D6 DONE 2026-08-28, merged and committed (05dcd8ef "accelerate
      solver"); post-merge suite re-verified on master (146 tests, same 3
      known-failing classes, 1 skipped). Accelerate
      sparse Cholesky via FFM: 5 new solver classes (AccelerateSparseLibrary/
      CholeskyFactor/ReleaseAction/SparseBackend + DesktopCholeskyBackend
      ladder PARDISO -> Accelerate -> EJML), platforms hand out the ladder,
      `CholeskyBackend.forceEjml` public field replaces a system-property
      knob, tests/benchmarks go through `nativeBackend()`. VERIFIED there:
      146 tests, same 3 known-failing classes, skips 3 -> 1 (two Cholesky
      native-assumption skips now run and PASS on Accelerate); equivalence
      2/2 and refactorize 4/4 green; TeaVM clean (no backend in classes.js).
      Timing on fertility: seamless 1.343s -> 0.654s (~2.1x); micro factor
      3.3x, per-solve parity. Rank-1 pin updates stay on EJML
      (IncrementalCholeskySolver never used the native path); Accelerate's
      internal reordering is transparent (equivalence-tested).
- [ ] D7 Wire loaded models in as boolean operands (Mesh Booleans note, R7
      part b); the `ModelScene` conversion was the prerequisite.
- [ ] D8 Decide whether always-on profiling should fail fast when
      `.profiler/libasyncProfiler` is missing, as it does now, or degrade.

## E. Port and value types (audit 2026-08-30)

Audit finding: `PortType` has 22 entries; only about six name a structure
that is not "mesh elements plus attributes". The general machinery already
exists twice (`GeometryBundle` is mesh + named slots; `EDGE_ID_SET` is an
element selection); the bespoke types exist because stages minted new types
instead of reaching for either. The .arr language (A2) is the same failure
mode in file form. `CROSS_FIELD` is borderline (theta per face, periodJump
per edge are attributes) but stays until the attribute story settles.

- [x] E1 Selection kernel, prerequisite for the A2 redo. REVISED 2026-08-30:
      the general shape already exists. Selection nodes output per-element
      BOOLEAN fields (`select_by_distance`, `select_by_normal`,
      `input_shortest_edge_paths` is the Dijkstra node,
      `edge_paths_to_selection`), so no id-set literals, no grammar change,
      and no ordered-path type are needed (an arc's order is derived by
      walking its path selection from an endpoint selection). Remaining
      work: reconcile `EDGE_ID_SET` with BOOLEAN-field selections (one
      selection form, not two), a deterministic nearest-vertex pick
      (`select_by_distance` picks a ball, not the one nearest vertex), and
      the arrangement authoring nodes recorded under A2. Attributes ride
      the existing field machinery (`FloatField`/`IntField`/`Vec3Field`).
      Target kernel: one geometry type, selections, attributes, and the
      structural survivors `UV_FIELD`, `ARC_NETWORK`, `DOF_SYSTEM`,
      `CHART_ATLAS`.
      PROGRESS 2026-08-30: the A2 redo shipped the authoring side
      (nearest-vertex and unique-shortest-path live inside the arrangement
      nodes; both throw on ties rather than tie-break by id). PROGRESS
      2026-08-31: the standalone `nearest_vertex` selection node shipped
      (general, web-registered), and the Dijkstra dedupe landed (one core
      behind both `input_shortest_edge_paths` and `UniqueShortestPath`).
      REMAINING EXECUTED 2026-08-31 with E3 (one batch), uncommitted:
      `EDGE_ID_SET` is deleted; feature/boundary edges flow as a per-edge
      `BoolField` on a BOOLEAN port in dense active-edge order — exactly the
      selection-node shape. See E3 for the full record.
- [x] E2 EXECUTED 2026-08-31, uncommitted. The five `PortType` entries
      (`ROOM_LIST`, `ROOM_LIST_3D`, `EDGE_GRAPH`, `TILE_GRID`,
      `TILE_GRID_3D`), the five value classes, and their nested `Room`
      records are deleted; everything flows as GEOMETRY_BUNDLE + BOOLEAN
      selections + attribute slots on the EXISTING mechanism (bundle slots
      holding `Vector3Field`/`IntField`/`BoolField`; no extension needed).
      Shapes: rooms = point cloud (one vertex per room center, 2D at z=0)
      with a per-vertex `half_extent` Vector3Field slot; delaunay = a mesh
      over the SAME vertices whose wire edges ARE the graph (HalfEdgeMesh
      addEdge, sorted (min,max) for determinism); MST = per-edge BoolField
      on a BOOLEAN `selection` port in dense edge order (the selection-node
      shape); 2D tile grid = quad-grid faces over [0,w]x[0,h] on XZ with a
      per-face `cell_type` IntField; 3D tile grid = point lattice (vertex
      per cell center) with the same attribute per vertex, because no 3D
      grid primitive exists. `CellType` survives as a plain internal enum
      (the `cell_type` ordinals need the named constants). Consumers read
      dims/cells back geometrically (positions, face centroids) via the new
      `DungeonGrids` helpers — no ids, no metadata side-channel.
      Unification verdicts per pair: random_rooms UNIFIED into one node
      (grid_d=0 -> planar placement at z=0, grid_d>0 -> 3D placement across
      grid_h floors with the start-room guarantee; both rejection samplers
      kept in RoomPlacer/RoomPlacer3D, dispatch on grid_d because the
      start-room insertion and floor semantics are genuinely 3D-only).
      delaunay_graph UNIFIED (one node dispatches on geometric degeneracy:
      any constant coordinate axis -> planar Bowyer-Watson on the two
      varying axes, else 3D tetrahedralization — a coplanar cloud is
      degenerate for the in-sphere predicate, so the dispatch is forced by
      the math, not by types; both algo classes stay).
      minimum_spanning_tree UNIFIED fully (one code path; 3D Euclidean
      weights equal 2D distance when a coordinate is constant).
      astar_corridors / astar_corridors_3d KEPT SEPARATE (the 3D stair move
      set — 2-horizontal + 1-vertical carve with STAIR_UP/STAIR_DOWN
      intermediates — has no 2D analog). dungeon_grid_to_mesh /
      dungeon_grid_to_mesh_3d KEPT SEPARATE (per-column floor+ceiling vs
      6-neighbor vertical adjacency with stair openings; and their inputs
      differ in shape: grid faces vs lattice vertices). Deleted:
      `RandomRooms3DNode`, `DelaunayGraph3DNode`,
      `MinimumSpanningTree3DNode`; ids random_rooms_3d/delaunay_graph_3d/
      minimum_spanning_tree_3d are gone from the registry and catalog.
      Nested-record hygiene in touched files: `CostWeights` hoisted to
      top-level `CorridorCostWeights`; delaunay/Prim/A* private helper
      records hoisted top-level package-private (DelaunayEdge/Triangle/Tet/
      Face, WeightedNeighbor, PrimQueueEdge, AStarEntry). The player stack
      (PlayerController, CapsuleMover, CameraGridSweep, ThirdPersonCamera,
      PlayerSpawner) takes `CellType[] cells + gridW/H/D` and mesh +
      half-extent field as plain parameters — deliberately NOT a new grid
      class, which would resurrect `TileGridValue3D` under a new name.
      DungeonViewerScene rebinds via `getNodeOutput(id, "geometry")` +
      DungeonGrids readers. Both .dsl graphs migrated (geometry/selection
      ports). NEW end-to-end `DungeonChainTest` (2D + 3D chains: point/
      half-extent invariants, edge counts >= n-1, selection length ==
      edge count and kept >= n-1, 900-face grid / 4500-vertex lattice,
      ROOM+HALLWAY cells present, non-empty final ArrayMesh). VERIFIED:
      157 tests (155 baseline + 2 new), exactly the known DenseMeshFewSplits
      "9 to 37" failure, 1 skipped; TeaVM classes.js 6.7MB with all seven
      surviving dungeon ids present and the three dead ids absent; scene
      byte-identical (13853 quads, 5.5512e+07 -> 4.0197e+04, 49 iterations,
      0/38446 flipped, singularities 44, euler=-6); dungeon-viewer runs both
      graphs (2D: verts=8976 faces=2244 fly-cam; 3D via
      -Dixdar.mesh.dsl=dungeon_3d: player spawned at room[0] world=(0,~0,0),
      mode=player).
- [x] E3 `SINGULARITY_LIST` -> vertex set + per-vertex int attribute
      (`index4` is real data, MotorcycleGraph reads it, so the attribute
      stays; the bespoke list type goes). Also dedup the flow: `CrossField`
      carries the same singularities list internally while the port ships it
      separately.
      EXECUTED 2026-08-31 (one batch with E1's remaining line), uncommitted.
      Final shapes: singularities are ONE per-vertex `IntField` port
      (PortType.INT) carrying index4 in dense active-vertex order
      (`mesh.vertexIdAt`), zero everywhere non-singular — the selection is
      the nonzero entries and Poincare-Hopf is the field sum; feature/
      boundary edges are a per-edge `BoolField` port (PortType.BOOLEAN) in
      dense active-edge order, the E2/select_by selection shape.
      `SINGULARITY_LIST` and `EDGE_ID_SET` are deleted from PortType;
      "singularities" joined the INT role names and "feature_edges" the
      BOOLEAN role names. The `Singularity` record is DELETED outright (not
      kept internally): after conversion every consumer reads the field
      directly, so the record had zero uses. The list-vs-port dup died with
      it — `CrossField.singularityIndex4` (IntField) and
      `CrossField.alignmentEdges` (BoolField) are the one authoritative
      representation, allocated in the ctor and shipped on the ports
      unwrapped; derived queries live as CrossField methods
      (`singularityCount()`, `singularVertexIds()`, `hasAlignmentEdges()`),
      computed on demand, never stored. Producers converted: NDirectionField
      (extract fills the array; alignment detect sets flags; the
      feature-alignment load still gathers ids and sorts ascending, exactly
      the old accumulation order), QuadLayoutEngine (fields
      `singularityIndex4`/`featureEdges`, SINGULARITIES output now INT).
      Consumers converted: MotorcycleGraph (dense nonzero scans for node
      seeding and spawnFromSingularities), ChartWalker (vertex-id set built
      once in the ctor, membership unchanged), CutGraph (dense scan for
      routing; direct BoolField probes replace the two contains calls),
      SeamlessDofSystem (BoolField probe), SeamlessProjector
      (singularVertexIds), CrossFieldWriter/Loader (.ndf format
      byte-identical for built fields; the writer's O(V)-per-singularity
      linear id scan died since the dense index IS the written index),
      QuadLayoutRuntime.captureSingularities(IntField, mesh), four scenes,
      QuadPipelineSeamTest/QuadLayoutNodeChainTest/SeamlessPipelineBenchmark.
      ORDER FINDING: the old list order was ascending active-vertex index
      (extractSingularities scans vAi ascending), so the ascending nonzero
      scan reproduces every order-sensitive consumer exactly (motorcycle
      node/trace ids, CutGraph routing order, writer output, render capture).
      The ONE exception: a loader-built field used to preserve the .ndf
      file's own singularity order; that order is now normalized to
      ascending active index. Its only consumers are render capture and the
      param-exam copy-into-built-field path (order-insensitive); no
      load-then-write round trip exists in the tree. Also recorded: a
      malformed .ndf repeating a vertex index now last-wins instead of
      double-listing. VERIFIED full gate: 157 tests / only
      DenseMeshFewSplitsTest "9 to 37" / 1 skipped; TeaVM genuinely green
      (classes.js 6,733,624 bytes, nearest_vertex present, catalog
      regenerated with zero SINGULARITY_LIST/EDGE_ID_SET entries);
      quad-layout scene byte-identical on every pin (singularities=44
      indexSum4=-24 fourChi=-24, 5.5512e+07 -> 4.0197e+04, 49 iterations,
      0/38446 flipped, quads=13853, euler=-6, patches=284, traces=200,
      arcs=4202, recarve V=15004 F=30020, subdividedChords=4213,
      interior/seam 419/149, transitions=568).
      DISCOVERY (verification methodology): two scene runs of IDENTICAL code
      differ in low-order float digits (meanDistortion tail, newton step
      tails) AND in [snap] chords laid (4884 vs 4882 observed back-to-back)
      — the solver stack is not run-to-run bitwise deterministic, so
      "byte-identical" is operative on the recorded pins, which were stable
      across both runs. This also means A10's "chords laid 4884 -> 4881"
      evidence line sits within observed run-to-run jitter; A10's stable
      pins (arc reclassification 419/149 -> 405/163, denominator 38446 ->
      38450, recarve V/F) are unaffected.
- [x] E4 `PATCH_SURFACES` -> geometry. `LayoutPatchSurfaces` holds, per
      patch, four polylines, a position grid, and a Coons blend: curves and
      grid meshes, nothing that is not geometry. It became a `PortType` in
      A3 only to make a runtime setter port-typed. Express as per-patch
      geometry.
      EXECUTED 2026-08-31, uncommitted. The data flows as GEOMETRY_BUNDLE
      on the E2 mechanism: each live patch's extracted quad grid as quad
      faces (one connected component per patch, vertices row-major, faces
      in patch-id order) with a per-face `patch_id` IntField slot — NEW
      plain builder/reader class `PatchSurfaceGeometry` (surfaceBundle /
      coonsBundle / patchIds; coonsBlend moved verbatim from the node).
      No separate polyline/corner/dims encoding needed: the boundary
      polylines ARE the components' border edges, the corner markers ARE
      the vertices with exactly one incident face, and the fill triangles
      come straight off the quad faces, so
      `QuadLayoutRuntime.setLayoutPatchSurfaces(GeometryBundle)` derives
      all of it topologically (border vs interior edges split the one
      GL_LINES buffer exactly as before) and the useCoonsGrid flag died —
      the caller passes the surface or Coons bundle. NODE VERDICT: the
      `patch_surfaces` node DIES ("no node nothing calls") — grep of every
      .dsl/graph resource found ZERO consumers of the `patch_surfaces` id;
      its only callers were the engine's own buildPatchSurfaces and
      QuadLayoutScene's lazy initial build, both now direct
      `PatchSurfaceGeometry` calls on `PatchGridExtraction` products
      (relaxed via fromRelaxedMap, initial via globalGrid.quadGridInitial).
      A1 TENSION recorded as in A4's entry: stage 15 no longer runs through
      node ports — with the node gone there is no port contract to run, and
      resurrecting one would resurrect the PATCH_SURFACES type. The A4-flagged
      disease died with the node: no GlobalGridMap hard-cast survives —
      the builder honestly takes the grid-map regroup product
      (`PatchGridExtraction`), which is what the node's UV_FIELD input
      always secretly required. DELETED: `LayoutPatchSurfaces`,
      `LayoutPatchCurves`, `PortType.PATCH_SURFACES`. Engine holds
      `patchSurfaces`/`patchCoons` bundles; the scene caches the initial
      pair lazily on first toggle (A8's shape, node-free).
      QuadPipelineSeamTest's per-live-patch guard now counts distinct
      `patch_id` values on the bundle (assertions equivalent, messages
      updated). DEVIATION: with the Coons fill selected, boundary lines and
      corner markers now read the Coons bundle's border (previously always
      the surface grid); the blend reproduces borders verbatim up to the
      one float add-order rounding in blendGrid, and the headless gate
      never toggles the fill. VERIFIED: 157 tests / only
      DenseMeshFewSplitsTest "9 to 37" / 1 skipped; TeaVM genuinely green
      ("Output file successfully built", classes.js 6,731,598 bytes,
      patch_surfaces absent, nearest_vertex present); quad-layout scene
      byte-identical on every pin (13853 quads, 5.5512e+07 -> 4.0197e+04,
      49 iterations, 0/38446 flipped, singularities 44 indexSum4=-24,
      euler=-6, [patch-grids] patches=284); live toggle drive through the
      automation server exercised all four surface/coons x relaxed/initial
      uploads (PRE-relaxation/relaxed log lines, zero exceptions).
- [x] E5 EXECUTED 2026-09-02, uncommitted. `PortType.MESH` and the `MeshValue`
      marker are deleted; the `GeometryBundleValue` marker died with them
      (its second implementor `CurveGeometry` never flows on a port — curves
      ride in the `_curve` slot), so `GEOMETRY_BUNDLE(GeometryBundle.class)`
      is the one geometry type bound to its one class. CENSUS: 29 MESH
      declarations moved (the addendum said 28): 7 inputs (subdivide_mesh,
      subdivision_surface, loop_cut, spherize `mesh`;
      instance_on_points.instance; curve_sweep.profile; arc_network.mesh) and
      22 outputs (10 primitives, extrude/inset/solidify, coons_inset/
      coons_extrude, the four modifier `mesh` outputs, realize_instances,
      dungeon_grid_to_mesh(_3d)). Every port NAME is unchanged. .DSL CLAIM:
      the port-name zero-edit claim holds across all 31 graph resources;
      ONE non-port edit was needed — `test_function.dsl`'s return annotation
      `-> MESH` became `-> GEOMETRY_BUNDLE` (the annotation is an opaque
      identifier read only by SkillLibrary's echo and GraphAnalyzer's seam
      check, which now accepts GEOMETRY_BUNDLE alone). UNWRAP: the 7 read
      `ctx.getInput(name, GeometryBundle.class)` and unwrap through
      `GeometryBundles.meshPart(GeometryBundle)` — the ONE surviving helper
      (typed, null in -> null out). `bundlePart` died (24 sites -> the typed
      read); `requireBundle` died (35 sites -> `Objects.requireNonNullElse(
      typed read, GeometryBundle.empty())`, JDK idiom, present in TeaVM
      0.15's classlib). HOLE CLOSED: GraphValidator's MESH<->GEOMETRY_BUNDLE
      rules and its redundant CLOSURE self-rule are gone; only FLOAT<->INT
      stays permissive, and no `getInput(..., MeshTopology.class)` read
      survives for a bundle to fall through. SLOT VERDICTS (all seven
      received no slots before, so these are the new contracts): spherize
      PASS-THROUGH via `withMesh` (vertex and face order are preserved by
      the rebuild, so per-element arrays stay aligned); subdivide_mesh DROP
      (destructive; both ports emit the same slot-less bundle);
      subdivision_surface `mesh` input DROP (topology-changing; crease
      remap remains the `geometry` input's contract); loop_cut `mesh` input
      DROP (same); instance_on_points.instance, curve_sweep.profile and
      arc_network.mesh MESH-ONLY. OUTPUTS: the 22 emit
      `GeometryBundle.ofMesh(mesh)`; on the 8 dual-port nodes (subdivide,
      subdivision_surface, loop_cut, solidify, extrude, inset, coons_inset,
      coons_extrude) the `mesh` port carries a slot-less bundle exactly
      where it carried the raw mesh and `geometry` is untouched, so every
      consumer sees identical data. TENSION for a ruling: those 8 nodes now
      expose two GEOMETRY_BUNDLE outputs that differ only in slots (and
      loop_cut/subdivision_surface two such inputs) — a port-deletion
      candidate, parked because it changes port names (.dsl edits: the
      `.mesh` references across 19 graph variables). RUNTIME:
      `NodeGraphRuntime`'s field-context tracking is behavior-identical; its
      raw-MeshTopology arms (function-parameter binding, `meshFromValue`,
      `executeGraphToMesh`) were dead and are gone; `DungeonViewerScene`
      reads the final mesh through `executeGraphToMesh`; six test helpers
      that pulled raw meshes off `mesh` ports now unwrap the bundle.
      TOOLING: BatchDslEvaluator / SkeletonSensitivityAnalyzer / ValidateDsl
      candidate-port and raw-result MESH arms trimmed; CanonicalPortNames
      keeps "mesh" as a GEOMETRY_BUNDLE role name; mesh-node-catalog.json
      regenerated by the compile-phase exporter (0 MESH entries).
      VERIFIED: 157 tests / only DenseMeshFewSplitsTest "9 to 37" / 1
      skipped; TeaVM "Output file successfully built", classes.js
      6,734,595 bytes, MeshValue/GeometryBundleValue/bundlePart/
      requireBundle absent, meshPart present; quad-layout pins
      byte-identical (13853 quads, 5.5512e+07 -> 4.0197e+04, iterations=49,
      flipped=0/38446, singularities 44 indexSum4=-24, euler=-6,
      patches=284); dungeon-viewer 2D verts=8976 faces=2244 fly-cam, 3D via
      -Dixdar.mesh.dsl=dungeon_3d player spawned at room[0] world=(0,~0,0)
      mode=player; no stray IxdarWindow.
      RULED 2026-08-31: delete the redundant `mesh` outputs on the nine
      dual-output nodes (subdivision_surface, subdivide_mesh, solidify_mesh,
      extrude_mesh, loop_cut, inset_faces, coons_inset_faces,
      coons_extrude_mesh, quad_cylinder); `geometry` is the one output.
      Rewrite the .dsl references (census: two, `palm_cut_x.mesh` and
      `palm_cut_z.mesh`, one file); all .dsl lives in this codebase.
      EXECUTED-leftover 2026-09-03, uncommitted. The nine `mesh` outputs
      are gone: QuadCylinderMeshNode.MESH and the eight MESH_OUT constants
      (SubdivisionMeshNode, SubdivideMeshNode, LoopCutNode, SolidifyMeshNode,
      ExtrudeMeshNode, InsetFacesNode, CoonsInsetFacesNode,
      CoonsExtrudeMeshNode) with their outputs() entries, socketDocs entries
      and every setOutput (23 setOutput sites deleted; the three single-use
      `unchanged`/`quads`/`out` locals in subdivide_mesh inlined). `geometry`
      is the one output; quad_cylinder's GEOMETRY doc took the deleted
      port's text. The only MESH_OUT left in src is SpherizeMeshNode's
      single output (not in the nine); NetworkContraction.TMESH_OUT is
      ARC_NETWORK. CENSUS CORRECTION: the ruling counted two .dsl
      references; the grep found THREE — `palm_cut_z.mesh` and
      `palm_cut_x.mesh` in voyage_skull.dsl plus `cyl.mesh` in
      quad_cylinder_test.dsl (quad_cylinder is one of the nine). All three
      now read `.geometry`; the 33 other `.mesh` reads across the 31 graphs
      are primitives / realize_instances / dungeon_grid_to_mesh outputs or
      INPUT-port keywords (`loop_cut(mesh=...)`, `arc_network(mesh=...)`)
      and stay. Data-identical: loop_cut fed through its `mesh` input has a
      null GEOMETRY_IN bundle, so its `geometry` output was already
      `GeometryBundle.ofMesh(result)`, byte-for-byte what `mesh` carried;
      quad_cylinder emitted `ofMesh(mesh)` on both ports. JAVA/TOOLING: no
      code read the deleted ports by name — `getNodeOutput(id, "mesh")`
      (DungeonChainTest, DungeonViewerScene), `getOutput("mesh")`
      (NetworkTracerTest, NearestVertexNodeTest) and the `("geometry",
      "mesh")` probe lists in NodeGraphRuntime / BatchDslEvaluator /
      ValidateDsl / SkeletonSensitivityAnalyzer all target primitive or
      dungeon nodes, and CanonicalPortNames keeps "mesh" as the
      GEOMETRY_BUNDLE role name for those; unchanged. mesh-node-catalog.json
      regenerated by the compile-phase exporter (the nine list `geometry`
      [+ `generated`]; 13 nodes still emit a `mesh` port: the 10 primitives,
      spherize, realize_instances, dungeon_grid_to_mesh(_3d)). COMMENT
      REPAIR in the files this batch owns: commit 4b3cd5a8's port-name sed
      had rewritten the ticket ids MESH-45/47/48 in InsetFacesNode and
      CoonsInsetFacesNode comments to `MESH_OUT.name-45/47/48`; restored.
      SIDE ITEM (logging ruling): ArcNetworkRecarve's `[recarve] ...
      contracted path vertices` println is `Platforms.log` (text unchanged);
      the two other quadlayout/embedding|motorcycle|gridmap sites went the
      same way — IntegerGridMap `[integer-grid] placed=...` (import added)
      and LayoutResolution's `[grid-sizing] worst aspect mismatches:`
      header; motorcycle had none. REPORT-ONLY, 121 live `System.out.print`
      remain in the main tree (plus one commented-out in DistanceMatrix):
      quadlayout/crossfield NDirectionField x2 (`[hopf]`/`[aligned]` PCG
      iters — outside the ruled subpackages), geometry/mesh/data
      BoundarySnap x3, PatchDecomposerCLI x7, MeshSkeletonExtractor x1,
      documentation BatchDslEvaluator x4 / ValidateDsl x1 /
      ExportMeshNodeCatalog x1 (CLI stdout), gui/ui/tools RoutePlanningTool
      x45 / HeadquartersPickerTool x1, gui/ui/actions x2, scenes MainScene
      x11 / TradeScene x4, platform (Platforms x2 = the pre-init fallback
      itself, LwjglPlatform x3 / HeadlessPlatform x5 = the adapters' log
      sinks, TradeMouseTrap x2, TradeKeyGuy x2, FileManagement x1,
      ExportAutomationRoutes x1), canvas IxdarWindow x6 / Canvas3D x3,
      graphics ShaderProgram x5, geometry/point PointSet x3, shell Shell x1,
      game CityNetwork x1, audio AudioSystem x1, common StringBuff x2,
      documentation SpliceArchitectureDoc x1. Ticket candidate. VERIFIED:
      157 tests / only DenseMeshFewSplitsTest "9 to 37" / 1 skipped
      (MeshBooleanProvenanceTest); TeaVM "Output file successfully built",
      classes.js 6,699,105 bytes (was 6,734,595), the only surviving
      `MESH_OUT` symbols are SpherizeMeshNode_MESH_OUT and
      NetworkContraction_TMESH_OUT, MeshValue/GeometryBundleValue absent;
      quad-layout byte-identical to the baseline block (13853 quads,
      5.5512e+07 -> 4.0197e+04, iterations=49, flipped=0/38450,
      singularities 44 indexSum4=-24, euler=-6, patches=284, recarve copy
      V=15003 F=30018, subdividedChords=4216, interior/seam 405/163,
      transitions=568, chords laid 4882); dungeon-viewer 2D verts=8976
      faces=2244 fly-cam, 3D player spawned at room[0] world=(0,~0,0)
      verts=13008 faces=3252 mode=player; mesh-viewer
      -Dixdar.mesh.dsl=quad_cylinder_test builds (verts=800 faces=768, both
      nodes evaluated); no stray IxdarWindow. NOT VERIFIABLE, pre-existing:
      mesh-viewer -Dixdar.mesh.dsl=voyage_skull (the graph carrying the two
      loop_cut rewrites) throws NullPointerException "this.halfEdgeEdge is
      null" in ArrayMesh.faceEdgeAt from CoonsPatchNode.evaluate:149 — the
      loop_cut chain evaluates (the crash is three nodes downstream) and
      coons_cube.dsl (cube -> assign_bezier_handles -> coons_patch, none of
      the nine involved) fails identically, so this batch is not the cause:
      commit 2c3da231 "small refactor" (2026-05-10) removed
      `halfEdgeEdge = topo.halfEdgeEdge` from ArrayMesh and the field has
      no writer since, so `coons_patch` on any ArrayMesh input (cube emits
      `ArrayMesh.fromQuads`) has been dead at HEAD since then. Ticket
      candidate; untouched here.
- [x] E6 EXECUTED 2026-09-02, uncommitted. `CLOSURE(FloatCurveKernel.class)`:
      both producers (float_curve, function_curve) emit a FloatCurveKernel
      and all four consumers (evaluate_closure, curve_to_mesh.radius_closure,
      curve_deform.closure, birail_loft.blend_closure) read one, so the
      valueType is real and the three `Object` + instanceof reads became
      typed reads. The ROOM_LIST / ROOM_LIST_3D / EDGE_GRAPH / TILE_GRID /
      TILE_GRID_3D family and their nested `Room` records died in E2; no
      `Object.class` entry survives. FINAL ROSTER (13): GEOMETRY_BUNDLE,
      FLOAT, INT, BOOLEAN, VECTOR3, STRING, ROTATION, CLOSURE, CROSS_FIELD,
      UV_FIELD, ARC_NETWORK, DOF_SYSTEM, CHART_ATLAS. NESTED-TYPE SWEEP,
      hoisted in the files this batch owns (11 types -> top-level,
      package-private where implementation detail):
      CanonicalPortNames.InputRole; SubdivisionMeshNode.{CatmullClarkResult,
      DenseQuadMesh, DensePolyMesh}; ExtrudeMeshNode.{BoundaryEdge (local
      record), ExtrusionResult}; CoonsInsetFacesNode.InsetResult;
      CoonsExtrudeMeshNode.{SideEdge (local record), ExtrudeResult};
      NodeGraphRuntime.ParsedGraph. REPORT-ONLY: 152 pre-existing nested
      types remain in files this batch did not own (grep
      `^\s+(public |private |static |final )*(class|record|enum|interface) [A-Z]`
      over src+test) — heaviest in geometry/mesh/data (MeshDistance,
      MeshSkeletonExtractor/Comparator, MorseSmaleComplex,
      SemanticPatchDecomposer, ...), quadlayout/solver (AdaptiveSolver 11,
      DofSystem, NewtonRelaxation, LazyConstraints), graph (GraphAnalyzer 4,
      PythonParser 4, SkillLibrary), platform (WebPlatform 7, Platform 6,
      MouseTrap 4), gui/tools, the five math nodes' `Mode` enums, and the
      scenes. Ticket candidate; untouched here.
      E6 ADDENDUM (hoisted-types cleanup) EXECUTED 2026-09-03, uncommitted.
      RULED 2026-09-03: the no-nested-types rule is not an invitation to mint
      meaningless record classes (the author dislikes records generally). Of
      the hoisted types, 19 are deleted and replaced; every survivor is an
      algorithm, a node, or a data class with more than one consumer. The
      keep-list (ShortestPathForest, GraphChoice, VertexLayout, VertexBuffer,
      LineSet, PointSet, Dijkstra, NearestVertex, UniqueShortestPath,
      NetworkTracer, ArcNetworkNode, NetworkNode, NetworkArc, NetworkPatch,
      NearestVertexNode, PatchSurfaceGeometry, DungeonGrids, the three tests,
      the probe, package-info) is untouched. Untracked Java files 34 -> 15
      (the ruling counted 35). PER-TYPE REPLACEMENT:
      (1) modifier/ExtrusionResult -> ExtrudeMeshNode scratch fields
      `extrudedFaces` (per-input-face selection = top faces) and
      `extrudedFromVertex` (new->original vertex map); the two private
      extruders return the MeshTopology.
      (2) modifier/BoundaryEdge -> `long[]` of `EdgeKey.directed(va, vb)` keys
      in the selected face's winding, bounded by faceCount*vpf with a count.
      (3) patch/ExtrudeResult -> CoonsExtrudeMeshNode scratch `outHandles`
      (float[][]) and `outGenerated`; the five private builders return the
      HalfEdgeMesh.
      (4) patch/SideEdge -> `int[4]` rows {denseA, denseB, topA, topB} in an
      ArrayList<int[]>, emitted as (a, b, topB, topA) exactly as before.
      (5) patch/InsetResult -> CoonsInsetFacesNode scratch `outHandles` /
      `outGenerated`; `doInset` returns the HalfEdgeMesh.
      (6) modifier/DenseQuadMesh -> ArrayMesh: `extractDenseQuadMesh` hands
      back an all-quad ArrayMesh input itself and `ArrayMesh.fromQuads(pos,
      quads)` for HalfEdgeMesh input; vertexCount is derived.
      (7) modifier/CatmullClarkResult -> both level functions return
      `ArrayMesh.fromQuads(newPositions, newQuads)` and leave the next level's
      crease array in the scratch field `levelCreaseWeights` (two-return via
      field). Each level reads `copyPositions()`/`copyFaceIndices()` off the
      ArrayMesh, one extra O(n) copy per level; the arithmetic is untouched
      and the outputs are bit-identical (probe below).
      (8) modifier/DensePolyMesh -> REPORT: ArrayMesh's uniform vertsPerFace
      cannot hold the ragged n-gon faces, so the three arrays live in the
      scratch fields `densePositions` / `denseFaceIndices` / `denseFaceOffsets`
      filled by `extractDensePolyMesh` and read by `applyMixedCatmullClarkLevel
      (nv, nf, creaseWeights)`; no third mesh class.
      (9) dungeon/algo/DelaunayEdge -> `EdgeKey.undirected` packed longs
      (smaller site in the high word) as LinkedHashMap/LinkedHashSet keys,
      sorted as longs (numeric long order == (min, max) lexicographic) by the
      shared `DelaunayTriangulation2D.sortedPairs`.
      (10) DelaunayTriangle -> `int[3]` rows; (11) DelaunayTet -> `int[4]` rows
      (List<int[]>; `removeAll` matches the same instances by identity, as the
      record version matched unique triangles by value); (12) DelaunayFace ->
      ascending-sorted 3 x 21-bit packed long `faceKey` with an assert that
      every site index is below 2^21.
      (13) WeightedNeighbor -> CSR in PrimMinimumSpanningTree (`adjOffsets`,
      `adjEdge`, `adjNeighbor`; the weight is `weights[edgeIdx]`), each
      vertex's spokes in dense edge order as before.
      (14) PrimQueueEdge -> PriorityQueue<Integer> of edge indices ordered by
      (weights[edge], edge); the far endpoint is the one not yet in the tree
      when polled (an edge is only ever queued from a tree vertex).
      (15) AStarEntry -> PriorityQueue<Integer> of cell indices with the
      comparator `AStarCorridorPathfinder2D.cellOrder(f, g)` (estimated total,
      cost so far, index) shared by the 3D pathfinder. DEVIATION from the
      spec's "standard stale-entry handling on pop": keys that change while
      queued break java.util.PriorityQueue's heap invariant, so a cell is
      removed from the queue (`queued[]` flag + `remove(Integer)`) before its
      scores change, i.e. decrease-key; stale entries never exist and no pop
      check is needed. Pop order over live entries is identical to the record
      version (a stale record entry always sorted after its cell's live entry
      and was skipped without effect; the target break saw the live entry
      first), so tie-breaks and paths are unchanged (probe below).
      (16) nodes/api/InputRole -> `CanonicalPortNames.OPERATION_SELECTOR`
      ("operation") and `isOperationSelector(nodeId, port)`; `canonicalForRole`
      and `roleOf` are gone; MeshNodeRegistryTest reads the string.
      (17) graph/ParsedGraph -> `NodeGraphRuntime.statements` (public final),
      a `NodeGraphRuntime(List<ParsedNode>)` constructor (the no-arg one
      delegates with `List.of()` for the tooling that builds bare runtimes);
      `fromSource` returns the runtime. CENSUS CORRECTION: six call sites, not
      four: MeshBooleanScene, MeshNodeViewerScene x3, DungeonViewerScene and
      NodeGraphRuntime.executeResource.
      (18) dungeon/algo/CorridorCostWeights -> three doubles (hallwayReuseCost,
      emptyCellCost, throughRoomCost) through carve/aStar/enterCost of both
      pathfinders; DEFAULT -> `AStarCorridorPathfinder2D.DEFAULT_HALLWAY_REUSE_
      COST / DEFAULT_EMPTY_CELL_COST / DEFAULT_THROUGH_ROOM_COST` (1, 5, 50),
      the port defaults of AStarCorridorsNode and AStarCorridors3DNode.
      (19) data/representation/EdgeRelaxation -> `Dijkstra.forest(MeshTopology
      mesh, int[] sources, double[] edgeCost)` walking vertexEdgeCount /
      vertexEdgeAt / edgeHalfEdge directly with vertex ids as slots; both
      IntUnaryOperator/IntBinaryOperator lambdas dropped since both callers
      hold a MeshTopology. UniqueShortestPath builds `edgeLengths` per edge id
      (the float length widened to double, the same sum as before, arc paths
      bit-identical in the probe). InputShortestEdgePathsNode keeps its
      adapter inside the node: dense-index sources -> ids, `edgeCosts` per
      edge id from the endpoint-averaged FloatField, parent ids -> dense
      indices. RECORDED BEHAVIOR CHANGE: input_shortest_edge_paths now
      accumulates in double (it used to round every relaxation through float
      to stay bitwise with its old loop) so `total_cost` may differ in the
      last digit, and spokes are visited in vertex-edge order instead of
      outgoing-half-edge order, so `next_vertex` may pick a different parent
      among exact ties. Nothing pins them and nothing can measure them: the
      only .dsl consumer, tool_quilt.dsl, fails pre-existing ("missing edge
      midpoint" in subdivide_mesh on a cube, before the node runs), and a
      scratch graph feeding the node a cube crashes pre-existing in its
      adjacency walk because ArrayMesh's lazy topology is never built
      (`vertexOutgoingOffsets` null before, `vertexEdgeOffsets` null after).
      Ticket candidate.
      PRE-EXISTING, NOT VERIFIABLE (fail identically before and after):
      mesh-viewer test_bridge.dsl and voyage_skull.dsl (the ArrayMesh
      `halfEdgeEdge` null crash recorded under E2, here via
      InsetFacesNode.insetFacesQuadWithSharedEdgeMerge -> ArrayMesh.faceEdgeAt
      when inset_faces receives an ArrayMesh), hand_v11_bridged.dsl
      ("adaptive_bridge_loops: no boundary loop found for tag 'ix_base'").
      coons_extrude_mesh / coons_inset_faces have no .dsl consumer; a scratch
      graph (quad_cylinder -> assign_bezier_handles -> coons_inset_faces ->
      coons_extrude_mesh individual + region) is their oracle below.
      VERIFIED: 157 tests / only DenseMeshFewSplitsTest "9 to 37" / 1 skipped
      (MeshBooleanProvenanceTest); TeaVM "Output file successfully built",
      classes.js 6,671,209 bytes (was 6,699,105), none of the 19 deleted
      names survives as a symbol (the residual `DenseQuadMesh`/`DensePolyMesh`
      /`BoundaryEdge` substrings are the kept method names
      extractDenseQuadMesh / extractDensePolyMesh / isBoundaryEdge),
      Dijkstra_forest, ShortestPathForest, sortedPairs, cellOrder,
      PrimMinimumSpanningTree_build, NodeGraphRuntime_fromSource present;
      quad-layout byte-identical to the baseline block (13853 quads,
      5.5512e+07 -> 4.0197e+04, iterations=49, flipped=0/38450,
      singularities 44 indexSum4=-24, euler=-6, patches=284, recarve copy
      V=15003 F=30018, subdividedChords=4216, interior/seam 405/163,
      transitions=568, chords laid 4883); dungeon-viewer 2D verts=8976
      faces=2244 fly-cam, 3D player spawned at room[0] world=(0,~0,0)
      verts=13008 faces=3252 mode=player, both equal to the E2 record;
      mesh-viewer hand.dsl verts=610 faces=608, organic_test.dsl verts=3232
      faces=3200, quad_cylinder_test.dsl verts=800 faces=768, all equal to the
      pre-change run captured before editing. ORACLE PROBE (scratch, outside
      the repo): every graph executed headless through
      NodeGraphRuntime.executeResource before and after, hashing per node
      output the vertex positions (float bits), face index lists, edge
      endpoints, bundle slots (crease/handle float[], IntField cell_type,
      Vector3Field half_extent), BoolField selections and ArcNetwork arc
      vertex paths: hand, organic_test, quad_cylinder_test, dungeon_2d,
      dungeon_3d, fixtures plane_layout / torus_layout / twin_cell /
      scaled_torus, the coons scratch graph (coons_inset_faces +
      coons_extrude_mesh individual/region), the plain scratch graph
      (extrude_mesh region/individual, inset_faces, subdivision_surface x1 on
      the region extrude and x2 on the inset) -- 429 lines, byte-identical
      except the input_shortest_edge_paths scratch graph, which fails before
      and after (see the RECORDED BEHAVIOR CHANGE above). No stray
      IxdarWindow.
