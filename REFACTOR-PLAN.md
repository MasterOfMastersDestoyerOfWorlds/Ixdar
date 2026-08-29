# Architecture documentation and reuse plan (archive)

ARCHIVED 2026-08-27: the live plan is `REFACTOR-PLAN-2.md`, which carries only the open items.
This file remains as the record of rulings and executed batches; read it for history, not for
what to do next.

Working document for the effort that produces `ARCHITECTURE.md`. Delete it when the objectives below
are done. Not the deliverable itself.

## Why

The problem is not missing docs, it is that reinventing is cheaper than reusing. Useful systems get
built and then re-implemented slightly differently elsewhere. The goal is that when work is
requested, the good route is the cheapest route, and the search for the right pattern is short.

Audience is the author six months out and AI agents working in this repo. Daud automation is parked.

## Shape of the deliverable

- One hand-written `ARCHITECTURE.md` at the repo root: a one-page system map, then task-indexed
  patterns, then a constraints section.
- `package-info.java` per package holds the package description. A Java generator bound to the
  `compile` phase splices them into `ARCHITECTURE.md` between markers. Nothing links out to them.
- Descriptions obey the existing 50-word `JavadocDescriptionLengthCheck` cap.
- Good patterns only, not an exhaustive survey. Current state, not aspiration. Forward-looking
  material is quarantined in marked sections, never beside working behaviour.

## Method

Claude enumerates each duplicated behaviour with evidence; the author rules on each. Work proceeds
one subsystem at a time, committing after each is confirmed working. Documentation of current state
precedes restructuring.

## Status legend

`[x]` done and verified · `[~]` in progress · `[ ]` not started · `[-]` deferred

Landed so far: `7ad51c71` covers 2.1-2.6, 5.1-5.3. The R4 and run-scene work (2.8, 5.5, 6.1-6.3)
is in the working tree behind it.

---

## 1. The deliverable

- [x] 1.1 `ARCHITECTURE.md` written: system map (pipeline diagram, two modules, platform story),
      task-indexed patterns (add a node, evaluate without a graph, slots, edge keys, representations,
      scenes, overlays, verification), generated package map, constraints ported from this plan's
      constraints section, forward-looking material fenced in its own final section
- [x] 1.2 111 `package-info.java` files across both modules — purpose, entry points, and the
      load-bearing constraint where one exists. Unfamiliar corners surveyed by four read-only
      agents (gui/parsing, procgen/game/audio/canvas, platform/graphics, legacy geometry +
      annotations); all final text hand-written from that evidence
- [x] 1.3 `ixdar.documentation.SpliceArchitectureDoc`, bound as a third exec-maven execution in
      the `compile` phase: parses every `package-info.java` javadoc, splices a sorted bullet map
      between `package-map` markers, exits 1 (failing the build) when markers are missing.
      Verified: 111 packages spliced, byte-identical across two consecutive clean builds, and the
      missing-marker path exits non-zero
- [ ] 1.4 Rewrite `README.md` as a short overview: what Ixdar is, how to build,
      `IXDAR_ASSET_REPO_ROOT`, pointer to `ARCHITECTURE.md`. No TSP link; that material is preserved
      verbatim in `KriegEterna/web/content/essays/tsp-adventures.md`
- [ ] 1.5 Migrate still-true `.cursor/` content into `Ixdar/CLAUDE.md`, then delete `.cursor/`

## 2. Scenes group

- [x] 2.1 R3 `NodeGraphRuntime.fromSource(String)` returning `ParsedGraph`, plus a static
      `REGISTRY_CLASSES` cache so the 95-node probe pass runs once per process. 5 call sites collapsed
- [x] 2.2 R1 `bindInputDirect` 3 copies to 1, lifted to `Scene`
- [x] 2.3 R1 `meshCenter` and `pendingModelPath` shadows removed, originals public on `ModelScene`
- [x] 2.4 R1 `frameMesh(MeshTopology)` extracted; `frameLoadedModel()` delegates; 3 viewer copies collapsed
- [x] 2.5 R8 `orbitAzimuth` / `orbitElevation` as public fields so a scene can keep its own view
- [x] 2.6 R7 `MeshBooleanScene extends ModelScene`, 241 lines to 210, verified running
- [x] 2.7 R2 catalogs merged into `ixdar.scenes.model.ModelCatalog` with `quadLayout` and `staging`
      factories, `ModelChoice` gaining a `Kind`, and the cursor kept on the catalog itself.
      `LayoutModelCatalog` and `ixdar.scenes.mesh.ModelCatalog` deleted, along with the viewer's
      `availableModels` bridge, a dead `getModelCatalog`, and the third copy of the resolve algorithm
      in `ModelCommand`
- [x] 2.8 R4 `ModelScene.preserveOrbit(BooleanSupplier)` restoring orientation and zoom. Collapses
      `EmbeddedTMeshScene.applyRewind` (6 values) and `MeshNodeViewerScene.loadModelEntry` (3), and
      deletes the camera parameters threaded through the load methods
- [x] 2.9 R6, narrowed after the audit's premise fell apart: `standalone/Icosphere` is the
      save-point animation model (per-face local vertices, ideal quaternions, band selection) and
      `IcosphereRuntime` renders per-face expansion animation `HalfEdgeMeshRuntime` cannot; deleting
      them would delete a feature, not duplication. The real duplicate was the hand-transcribed phi
      table: it now lives once as `Icosphere.VERTICES`/`TRIANGLES`, consumed by both the animation
      model and `QuadLayoutRuntime`'s singularity spheres. `IcosphereMeshNode`'s differing ring-based
      vertex set stays, documented, since changing it would silently alter every `icosphere()` DSL.
      Then, on the author's ruling that the save-point prototype is no longer wanted: the whole
      island deleted — `IcosphereSavePointScene`, `Icosphere`, `IcosphereRuntime`, `Face`,
      `FaceState`, the launch config, and the pom profile (~990 lines). The phi table lives on as
      `QuadLayoutRuntime`'s private `ICO_VERTICES`/`ICO_TRIANGLES`, its sole remaining consumer.
      `IcosphereMeshNode` untouched
- [x] 2.10 R1 leftover: both centroid-and-radius loops collapsed into
      `HalfEdgeMeshRuntime.cloudRadius(clouds, centroidDest)`, the parent both callers already know
- [-] 2.11 R5 `DungeonViewerScene` restructuring

## 3. Later ruling groups

- [x] 3.1 Fixtures. `mark_edges` node (typed marks: FLOAT/INT/BOOL by mode, per the
      random_value precedent) writing `EdgeMarks.SLOT` (`Map<String, boolean[]|int[]|float[]>` by
      edge id), `EdgeMarks` accessor as the consumer contract, crease converted to the "crease"
      float label, `MarkCreaseNode` and `_crease_weights` deleted, all 6 mark_crease .dsl call
      sites rewritten and validated, registry test green. F3(b): the 15 cloned `buildGrid()`
      helpers in `ixdar-app/test/unit/mesh/` replaced by a shared `Grids` fixture that evaluates
      `GridMeshNode` through its port interface (440 cloned lines deleted); expectations follow
      the node's U-major vertex ids via `GridMeshNode.vertexId`, and `MiddleArcChannelRouteTest`'s two
      position checks moved from Y-row values to centered Z via `Grids.rowCoordinate`. All 15
      classes plus `GridMeshNodeTest` green (22 tests). Note: `PlaneLayoutFixture.copyVertex`
      still uses the row-major formula — self-consistent on its square grid (a transpose), but
      its prose diagram is mirrored relative to the mesh. Fixture-to-DSL conversion itself
      deferred until after the quad-layout migration (7.2) per ruling F2
- [-] 3.2 Runtime overlays, deferred behind 7.2 by the author's ruling: generalizing
      `QuadLayoutRuntime` only makes sense after the quad-layout pipeline is restructured into mesh
      nodes with common data structures at the seams; the goal then is a runtime exposing general
      capabilities, not stage-typed setters. The enumeration survives for that day: the child never
      calls the parent's `setTags`/`setPerVertexScalar`/`setFeatureEdgeOverlay` (its overlays draw
      other geometry), and the real duplication is (D1) ~30 raw int handle fields with ~25 hand-rolled
      delete blocks where the parent uses `VertexArrayObject`/`VertexBufferObject`, (D2) the
      position-only GL_LINES upload block 5×, (D3) the colored line draw pass 5×, (D4) the
      sphere-instance loop 6×, (D5) ranged color draws as parallel arrays where the parent has
      `FeatureEdgeRange`, (D6) the 6-line shader preamble 12×, (D7) `setupOverlayProjection`
      re-deriving the parent's private projection. Sketched fix: `LineSet` + `SphereCloud` helpers,
      `FeatureEdgeRange` reuse, `bindOverlayShader`, projection exposed (~500-600 lines).
      TODO when executed: update `ARCHITECTURE.md`'s "Draw overlays on a mesh" pattern, which
      currently names `QuadLayoutRuntime` as the cautionary tale
- [x] 3.3 Mesh data conventions. `EdgeKey` (`ixdar.geometry.mesh.data`) is now the one place edge
      keys are packed: `undirected(a, b)` (min in high 32 bits), `directed(from, to)`,
      `minVertex`/`maxVertex` accessors. Migrated 6 private `edgeKey` clones, ~10 inline ternaries,
      ~14 unpack sites, `CoonsHandleBuilder.dirPack` (kept as the domain name, now delegating),
      `ExtractedPatchGrids.directedEdgeKey` and `ExtrudeMeshNode`'s `vertCount`-multiply scheme
      (both were incompatible third layouts, map-internal so safe to converge), and
      `PatchRectangleMap`'s `KEY_ROW_SHIFT`. Left alone on purpose: label pairs (`BoundarySnap:89`),
      MSC cell pairs, trace/node and matrix keys — same math, different semantics.
      `HalfEdgeMesh.faceAcrossEdge(faceId, edgeId)` added; the two identical `neighborFace` clones
      (`QuadMeshExtraction`, `PatchRegions`) deleted; `CurvatureConstraints`' CSR cache and
      `PatchBoundaryBuilder`'s active-index route are legitimately different and stay. Slot naming
      unified on leading-underscore names owned by one constant: `CurveGeometry.SLOT` replaces 8
      per-file `"_curve"` constants + 4 raw literals, `"__tags"` → `"_tags"`, the two boolean
      provenance slots and `"instance_mesh"` gained the prefix. Empty-mesh spelling unified on
      `ArrayMeshEngine.emptyQuads()` (2 raw constructions in `MeshMergeByDistance`). Verified: full
      suite 141 tests, only the 5 known 6.8 failures; registry test green; mesh-boolean scene runs
      (`graph 7ms`, `UNION V=20 F=36`); catalog diff is exactly the one edited description string
- [ ] 3.4 Nodes: 95 registered, 3 tests
- [x] 3.5 Port name plumbing (author's ruling): `InputPort`/`OutputPort` converted from records to
      plain final classes with public final fields, so `port.name` is field access — a record cannot
      expose a component as a field, only through its accessor method. All 84 node classes now follow
      the `GridMeshNode` shape: ports as public static constants with the name literal inline, the
      string-name constants (`U_TILES_2` style) deleted, and every socketDocs key / getInput /
      setOutput going through `PORT.name`. Where a node's input and output share a name the second
      port references the first (`new OutputPort(GEOMETRY.name, ...)`), which also satisfies the
      duplicate-literal check. The 9 torus/disk layout fixtures and 4 node tests that referenced
      deleted constants were rewritten to `PORT.name`; `PlaneLayoutFixture` now builds its grid via
      `GridMeshNode.triangulated`. Fallout fixed in passing: the catalog exporter serialized two
      `Map.of` maps unsorted (`aliases`, `outputActivationByMode`) — `Map.of` iteration order is
      salted per JVM run, so those keys could flap between builds; both now export through `TreeMap`
      and the catalog is verified byte-identical across rebuilds. Verified: full suite green except
      the 3 known 6.8 classes, mesh-boolean scene evaluates and unions

## 4. Enforcement in code, not prose

- [x] 4.1 Done as `MeshNodeRegistryTest`, not a processor error: the processor sees the type
      model and cannot evaluate `inputs()`/`socketDocs()`, so the exact check lives in the registry
      test the Javadoc always named. All 95 nodes pass documentation; `CanonicalPortNames` is now
      live, with four surveyed exceptions added to its own allowlist (`float_out`/`int_out` on
      `random_value`, `total_cost`/`next_vertex` on `input_shortest_edge_paths`) - renaming those
      would break .dsl files that reference them
- [x] 4.2 `desktopOnly` on `@MeshNodeAnnotation`; `RegistryProcessor` partitions into
      `MeshNodeRegistry_MeshNodes` (web-safe, plus a string-only `DESKTOP_ONLY_IDS`) and
      `..._MeshNodesDesktop`, reached only via `NodeGraphRuntime`'s `Class.forName` firewall.
      `mesh_boolean` is the first flagged node. Generated registries are now sorted, so builds are
      byte-stable. Verified both ways: the desktop scene runs the boolean; a registry without the
      desktop map produces the 4.3 message
- [x] 4.3 `missingNodeMessage` names the cause ("desktop-only and unavailable in this build")
      when the id is in `DESKTOP_ONLY_IDS`, and the scenes' existing failure logging carries it to
      the terminal - observed live when a bug briefly gave desktop the web registry
- [x] 4.4 `NodeGraphRuntime.logTimings(prefix)` logs graph total plus any node at 100ms or more,
      called after every scene DSL execution (verified: `[mesh-boolean] graph 8ms`)
- [x] 4.5 `GridMapOptimizer.timeBudgetMilliseconds` 500s to 60s
- [x] 4.6 Catalog regenerates every compile into checked-in
      `ixdar_automation_cli/mesh-node-catalog.json` (sorted, so no-op builds are byte-identical);
      entries carry `desktopOnly`; the opt-in profile is gone and Daud reads the checked-in file

## 5. Cleanups

- [x] 5.1 `manifold3d` 3.0.7 to v3.5.1-4; `ManifoldMeshBooleanBackend` rewritten JavaCPP to FFM.
      Removes the undeclared Homebrew dependency on macOS and retires the preview-feature pin
- [x] 5.2 Always-on profiling on every platform: `.profiler/libasyncProfiler` symlink,
      `tools/link-profiler.sh`, settings and `run_scene.py` pointed at it
- [x] 5.3 `Mesh Boolean` launch config gains `-XstartOnFirstThread` and `-Xmx4g`
- [x] 5.4 Deleted `io.humble:humble-video-all` (AGPL) and `mtj` (LGPL). Both were unused, both
      compile scope, both in the fat jar. Contradicts the commercial-use licensing intent
- [x] 5.5 `CP` moved to `ixdar-app/target/CP`, already-ignored ground, so no `.gitignore` entry is
      needed. Root file deleted; commit the deletion to untrack it
- [ ] 5.6 Decide what replaces the `ixdar-tickets` submodule. The gitlink still reads as deleted; the
      standalone repo is at `~/Code/ixdar-tickets`
- [ ] 5.7 Verify two autofix recipes that look unreachable: `MultipleStringLiteralsRecipe` is keyed to
      `MultipleStringLiterals` but the config now emits `MeaningfulDuplicateStringLiteralsCheck`, and
      `MagicNumberRecipe` is keyed to a rule that is commented out. Also find where
      `InlineGlueStringConstantsRecipe` is invoked; it is in neither `RECIPE_ORDER` nor `InlineHelpers`
- [ ] 5.8 Replace the EJML fallback on macOS with Apple's native solver (the Accelerate
      framework's LAPACK/BLAS) so the Cholesky path is fast on both platforms instead of
      silently degraded off-MKL
- [x] 5.9 Everything on 25. The author bumped `annotations` to `--release 25`; the Run button
      then failed with "Unable to load annotation processor factory" plus a null
      `CanvasSceneMap.MAP`, proving the language server was still on the extension's bundled JRE
      21 (no `java.jdt.ls.java.home` was actually set anywhere). Resolution: the 25-built jar
      reinstalled to `~/.m2` (major version 69 verified), and `java.jdt.ls.java.home` pinned to
      Homebrew JDK 25 (`/opt/homebrew/opt/openjdk`, the stable symlink) in USER settings, never the git-tracked workspace file (absolute mac
      path would break Linux; each machine pins its own). Constraint rewritten in
      `ARCHITECTURE.md`. Awaiting the author's confirmation that the Run button works after a
      window reload

## 6. Bugs found, needing tickets

- [x] 6.1 `run-scene` passes `-XstartOnFirstThread` on darwin
- [x] 6.2 `run-scene` builds with `clean compile`. Plain `mvn compile` is still unreliable after an
      edit, so every verification here needs a clean build; the root cause is untouched
- [x] 6.3 `run-scene` builds both modules and puts `annotations/target/classes` ahead of the
      installed jar on the classpath
- [ ] 6.4 (half-armed: `MeshBooleanProvenanceTest`'s provenance test is `@Disabled` pointing here;
      its geometry test runs green through the FFM backend) Recover `runOriginalID` from the MeshGL64 segment to restore boolean provenance tinting.
      Answer why the old backend reported `faces new=0/36` and never flagged an intersection face
- [ ] 6.5 `MeshNodeViewerScene` is the scene `WebLauncher` instantiates, and it calls
      `Files.readAllBytes` and `MeshLoader`, both `java.nio.file`. That path throws in a browser
- [ ] 6.6 `HalfEdgeMeshRuntime`, `QuadLayoutRuntime`, `IcosphereRuntime` and `AssimpModelRuntime`
      import `org.lwjgl.BufferUtils` directly instead of `IxBuffer`. LWJGL's buffer functions are
      emitted into the shipped JavaScript as a result. Also `SDFUnion` static-imports
      `org.lwjgl.opengl.GL13` texture constants — the one GL-abstraction violation in `graphics`
      outside `render/model` (surfaced by the 1.2 survey)
- [ ] 6.7 `PatchRenderer` stays out of the web build only by method-level dead-code elimination. One
      call to `renderMultiview` from web-reachable code pulls `Graphics2D` in
- [ ] 6.8 Three quad-layout test classes fail on the committed tree, surfaced by this session's
      first full-suite run and untouched by any change here: `DenseMeshFewSplitsTest` (refining 8x
      raises edge splits 9 to 37), `QuadMeshExtractionTest` (ring orientation undecidable on the
      unrelaxed torus), `TJunctionExtensionTest` (contracted torus loses the stub T-junction).
      All deep pipeline behaviour, consistent with the notes' known instability
- [ ] 6.9 `IX-6` exists in both `content/IX/` and `done/IX/` with contradictory statuses; `BOARD.md`
      is stale and omits the DSL epic
- [ ] 6.10 `instance_on_points` writes the `_instance_mesh` slot but nothing reads it —
      `RealizeInstancesNode` never looks at the slot, so instances placed on 0 or 1 points are
      silently dropped instead of realized. Surfaced by the 3.3 slot audit
- [ ] 6.11 Terminal `.help` files can never load: `TerminalCommand` builds the stale path
      `./src/shell/terminal/help/<name>.help` while the files live at `ixdar/gui/terminal/help/`,
      and the pom's resource includes omit `**/*.help`, so Maven never copies them to the
      classpath either. `manifoldtest.help` has no matching command. Surfaced by the 1.2 survey
- [ ] 6.12 `QuadLayoutEngine` never conforms: the `conforming` flag is declared and
      `buildContractedTMesh`'s javadoc claims stages 9-10 "contract ... then extend every
      surviving T-junction", but no engine method calls `tmesh.conform()` or sets the flag -
      only tests (`PatchGridSeamTest`, `QuadMeshExtractionTest`, `TJunctionExtensionTest`) and
      `EmbeddedTMeshScene` conform manually. The production pipeline builds patch maps on a
      contracted but non-conforming T-mesh. Surfaced by `QuadPipelineSeamTest` (7.2 batch 1),
      which pins the current behavior. Decide during the `tmesh_contract` node work whether the
      pipeline should conform (the ruled node includes conform) and fix flag or javadoc either way

## 7. Decisions still owed

- [ ] 7.1 R7 part (b): wire loaded models in as boolean operands, which is what the Mesh Booleans
      note asks for. The `ModelScene` conversion was the prerequisite
- [ ] 7.2 Quad layout stages to mesh nodes. Author's words: quad layout "was a long divergance and was
      hard to get right so its bee na while but should be integrated into the common system", and it
      "will need a lot of testing". Related: the pipeline is really a mesh-to-DSL generator, which only
      makes sense as DSL once a load-mesh node exists. TODO when executed: update `ARCHITECTURE.md`
      (the overlay pattern and the quad-layout entries in the system map and package map).
      Enumeration (surveyed 2026-08-22), awaiting rulings Q1-Q6:
      - Consumers: exactly 5 scenes drive `QuadLayoutEngine`; each pulls stage products off the
        engine and pushes them into `QuadLayoutRuntime` via 10 stage-typed setters. Stopping
        points: `CrossFieldExaminationScene` stage 2, `ParametrizationExaminationScene` stage 3,
        `MotorcycleGraphExaminationScene` stage 4, `EmbeddedTMeshScene` stage 8 (then drives
        contraction interactively via `contractStep`/`conform`/`collapseArc.*` by hand),
        `QuadLayoutScene` stage 14. The 8 build* methods between `buildQuantization` and
        `buildQuadGrid` are never called from outside the package; `targetEdgeLength` is never
        set by any consumer. Input mesh is always OFF files via `MeshLoader` (`ModelScene`) or
        hand-authored `LayoutFixture`s; no loader node exists.
      - Tests: embedding/carve and contract ops are the heavily covered stages (~40 classes).
        Zero direct coverage: cross field, seamless parametrization, quantization ILP,
        `LayoutExtraction`, `LayoutPatchSurfaces`. Layout fixtures hand-author quantized lengths,
        deliberately bypassing stages 1-5.
      - Node system fit: `NodeGraphRuntime` has no cross-evaluation caching (full re-run per
        evaluate; scenes already full-rebuild on alpha change, so parity). `PortType` is a closed
        enum; dungeon nodes set the precedent for `Object.class`-backed constants; slots are the
        other channel. Multi-output nodes with mixed port types are fully supported. Evaluation
        is synchronous on the render thread; quad stages take seconds, `mesh_boolean`'s
        `desktopOnly` registry is the precedent for keeping ojAlgo/Cholesky nodes out of the
        web build (WebPlatform refuses both backends).
      - Q1 RULED (author, 2026-08-22): the driver is QuadMixer (Nuvoli et al. 2019, layout
        preserving blending of quad meshes; see also the Mesh Booleans note in obsidian:
        Manifold library for the boolean, Apache 2, Java bindings, arbitrary per-triangle data
        surviving ops). QuadMixer reuses motorcycle graphs standalone (patch decomposition of an
        existing quad mesh, no quantization), a cross field + tracing on just the blend region,
        an ILP over patch-side subdivisions, and per-patch quadrangulation - so the stages must
        be independently invokable nodes, not one fused pipeline. Ruled node list, desktop-only:
        `load_mesh`, `cross_field` (stages 1-2), `seamless_uv` (stage 3), `motorcycle_graph`
        (stage 4, explicitly separate from embedding - "motorcycle graphs are a common usecase
        for geometry processing"), `layout_embedding` (embedding/carve through contract+conform),
        `integer_grid_map` (patch maps + IntegerGridMap + GlobalGridMap up to the initial
        uv/DOF state), `newton_solver` (GridMapOptimizer over the DOF system; the seam already
        exists inside GlobalGridMap.build() at the gridOptimizer call, GlobalGridMap.java:139-142),
        and coons patch conversion (PatchGridExtraction + LayoutPatchSurfaces; relate to the
        existing `coons_patch` node). Further ruled (author): QEx as its own node - "a general
        capability that takes uvs and produces a quad mesh"; note the current QuadMeshExtraction
        takes GlobalGridMap + LayoutPatchMaps + EmbeddedTMesh + copy mesh (patch-chart
        iso-tracing), so the generic mesh+UV input form is a redesign of its input side, its own
        line item. Zero-arc collapse node RULED (author): the EMBEDDED collapse. Final cut:
        `layout_embedding` (carve + T-mesh assembly) then `tmesh_contract` (embedded ops 1/2/3
        collapse + conform) as separate nodes; the abstract stage-6 LayoutExtraction (cluster
        nodes across zero-quantized arcs, positive arcs = separatrix skeleton) folds into the
        `arc_quantization` node's output. Settled node list: `load_mesh`, `cross_field`,
        `seamless_uv`, `motorcycle_graph`, `arc_quantization`, `layout_embedding`,
        `tmesh_contract`, `integer_grid_map`, `newton_solver`, `quad_extract` (QEx),
        coons/surfaces conversion. QuadMixer scope (author): not replicating QuadMixer
        entirely - mostly its first stage (motorcycle patch decomposition of two quad meshes) and
        last stage (per-patch quadrangulation), with the quad layout pipeline replacing the
        middle patch/subdivision machinery; exact scheme still open (see Mesh Booleans note:
        Manifold for the boolean, patch dirtying, boundary re-layout schemes).
      - Q2 seam types: new `PortType` constants (validated, DSL-visible) vs decomposing stage
        products into mesh + generic per-element slots (theta/periodJump per face, u/v per
        corner). Recommend PortType constants first; decomposition only where a consumer needs it.
      - Q3 caching: accept full re-run per evaluation initially (matches current scene UX);
        graph-level memoization is separate runtime work if ever needed.
      - Q4 scenes: migrate `QuadLayoutScene` to a DSL graph first; keep `EmbeddedTMeshScene`
        driving the engine directly (it is an interactive debugger, not a pipeline consumer);
        decide exam scenes' fate after.
      - Q5 characterization tests BEFORE restructuring, at the proposed node seams, on the OFF
        fixtures: singularity count, flipped-triangle count == 0 + injectivity, tmesh validate +
        Euler, quad count == quantized count. Covers the zero-coverage stages 1-5.
      - Q6 `load_mesh` input: plain STRING path via `MeshLoader` (recommend) vs `ModelCatalog`
        key. Also unlocks 7.1 (loaded models as boolean operands).
      - [x] Batch 1 (2026-08-22): `LoadMeshNode` (`load_mesh`, desktopOnly, STRING path via
        `MeshLoader`, outputs a GEOMETRY_BUNDLE; empty path yields an empty bundle, missing file
        throws UncheckedIOException) + `LoadMeshNodeTest` (3 tests, port-interface evaluation) +
        `QuadPipelineSeamTest` characterizing the previously-uncovered seams on
        `sphere_base_in_tri.off`: Poincare-Hopf singularity index sum == 4x Euler, seamless
        injective with 0 flipped triangles, contraction leaves 0 live zero arcs (and pins that
        the engine does NOT conform - 6.12), Newton relaxation monotone in energy with 0 flipped
        faces, relaxation preserves the extracted quad count, extracted quad mesh Euler matches
        the surface, one patch surface per live patch. Verified: full suite 145 tests, only the
        3 known 6.8 classes failing (5 tests), 3 skipped; registry test green; catalog diff is
        exactly the new load_mesh entry (+25 lines)
      - [x] Batch 2 (2026-08-22): PortType gained CROSS_FIELD, SEAMLESS_UV, MOTORCYCLE_GRAPH
        (Object.class-backed, dungeon precedent; same-type edges already validator-compatible).
        New package `ixdar.geometry.mesh.nodes.quadlayout`: `CrossFieldNode` (`cross_field`:
        geometry -> field + singularity_count), `SeamlessUvNode` (`seamless_uv`: field -> uv +
        flipped_triangles + injective), `MotorcycleGraphNode` (`motorcycle_graph`: uv +
        alpha_degrees(15) -> graph + node/arc/patch counts; javadoc names the planned quad-mesh
        tracing input mode for QuadMixer). Coercion lives on the engine per the 3.3 convention:
        `HalfEdgeMeshEngine.fromMeshTopology` (counterpart of
        `ArrayMeshEngine.fromUniformMeshTopology`; pass-through, ArrayMesh.toHalfEdgeMesh, else
        throw) - the one-method `QuadLayoutNodes` holder was deleted per author. All desktopOnly.
        Diagnostic port names (singularity_count, flipped_triangles, injective) registered as
        role alternates in `CanonicalPortNames` with rationale. `QuadLayoutNodeChainTest` chains
        all four nodes through MapNodeContext on the sphere OFF and re-checks the
        injectivity/cell-complex invariants. Verified: full suite 146 tests, only the 3 known
        6.8 classes failing, 3 skipped; catalog diff +111 lines = exactly the three new node
        entries; ARCHITECTURE.md package map regenerated with the new package
      - Naming RULED (author): node ids name algorithms, port TYPES name data structures - the
        QuadLayoutRuntime stage-typed-setter disease must not recur in the type system. Renamed
        SEAMLESS_UV -> UV_FIELD (per-corner UVs; seamless stage, Newton relaxation, QEx input,
        and QuadMixer blend-region parametrization all produce/consume the same structure) and
        MOTORCYCLE_GRAPH -> ARC_NETWORK (node-arc-patch network on a surface; motorcycle tracing
        and the planned QuadMixer quad-mesh decomposition both produce it). CROSS_FIELD stays -
        it already names the data structure. The network does NOT split into ARC/NODE port
        types: connectivity is the value (arcs end at nodes by id, patches bound by arcs), and
        splitting follows the MESH precedent - projection nodes, not component types. Planned
        batch: `network_arcs(network) -> CurveGeometry` bundle and `network_nodes(network) ->`
        vertex-only bundle, so instance_on_points + curve_sweep render arrangements through
        general capabilities (converges with 3.2; also the ArrangementDiagnostic render path).
        The network is NOT carried as a bundle slot: slots annotate per-element data on their
        geometry, the arrangement is a sibling structure and the node's primary product. Common
        Java interfaces at the seams (UvField etc.) are deferred until the second producer of
        each type lands, so two real users shape them
      - [x] Batch 3 (2026-08-22): `ArcQuantizationNode` (`arc_quantization`: graph +
        alpha_degrees -> skeleton; QuantizedMeshGrid ILP + the stage-6 LayoutExtraction folded
        into the output per ruling), `LayoutEmbeddingNode` (`layout_embedding`: skeleton ->
        tmesh; carve + EmbeddedTMesh assembly + validate), `TmeshContractNode` (`tmesh_contract`:
        tmesh + conform(default TRUE) -> tmesh; contract to fixed point, then conform unless
        disabled - the node defaults to conforming even though the engine never does (6.12);
        input mutated in place, output port shares the input's name per the 3.5 convention).
        All ARC_NETWORK seams (MotorcycleGraph, LayoutExtraction, EmbeddedTMesh are all
        node-arc-patch structures; common interface still deferred). `QuadLayoutNodeChainTest`
        extended through all seven nodes: skeleton keeps positive arcs, every arrangement patch
        becomes an embedded patch, contraction+conform leaves no live zero arc - conform=true
        WORKS on sphere pipeline data, evidence for the 6.12 decision. Fallout: catalog exporter
        rejects duplicate socketDocs keys, so shared input/output names document once.
      - [x] Batch 4 (2026-08-22): `GlobalGridMap.build()` split into three public phases -
        `buildInitialMap()` (framing + DOF system + pre-relaxation extraction/iso surface),
        `relax()` (GridMapOptimizer + relaxed iso surface), `extractQuads()` (verification +
        QuadMeshExtraction + ExtractedPatchGrids) - with `build()` delegating to all three, so
        the engine's behavior is unchanged (QuadPipelineSeamTest guards parity). Three nodes on
        those phases: `IntegerGridMapNode` (`integer_grid_map`: tmesh + uv + target_edge_length
        -> uv; LayoutPatchMaps + IntegerGridMap + buildInitialMap; first DSL exposure of
        target_edge_length, which no scene ever set), `NewtonSolverNode` (`newton_solver`: uv ->
        uv + energy_before/energy_after; relaxes in place - the second producer of UV_FIELD, as
        the author predicted when renaming the type), `QuadExtractNode` (`quad_extract`: uv ->
        geometry; QEx extraction converted to a real quad ArrayMesh via ArrayMesh.fromQuads, so
        downstream mesh nodes can consume it; generic mesh+UV input form still a planned line
        item). Chain test now runs all ten nodes load_mesh -> quad_extract and adds: 0 off-grid
        nodes, energy monotone, 0 flipped faces relaxed, extracted quad count == quantized,
        quad-mesh Euler == surface Euler - all downstream of conform=true, which the engine path
        never exercised. Verified: full suite green except the 3 known 6.8 classes; catalog
        gains exactly the three new entries
      - Decomposition RULED (author, after probing the seamless.uv -> newton_solver mis-wiring):
        no INTEGER_GRID_MAP type and no diagnostic-error path - solver state decomposes into
        common types instead. New PortTypes DOF_SYSTEM ("a solve's degrees of freedom and their
        couplings") and CHART_ATLAS ("atlas of charts covering a surface"), each with multiple
        producers immediately: cross_field outputs dofs (NDirectionField.massSystemMatrix, now
        surfaced), seamless_uv outputs dofs (SeamlessDofSystem) + charts (CutGraph),
        integer_grid_map outputs uv + dofs (GridMapDofSystem) + charts (LayoutPatchMaps).
        newton_solver takes uv + dofs (GlobalGridMap.relax now takes the DOF system as a
        parameter). Common Java interfaces per type still deferred to the convergence pass.
        ARC_NETWORK ruling (author): do NOT error on mismatched concrete classes - unify the
        underlying structures. Record-family merge survey (TMeshNode/TraceArc/TMeshPatch vs
        EmbeddedNode/EmbeddedArc/EmbeddedPatch vs LayoutExtraction clusters) commissioned;
        enumeration pending before any merge work
      - Interfaces RULED (author): "we need a common interface object that UV always maps to and
        similar for DOF and Chart Atlas" - the deferral is over. Annotations module gained
        `UvFieldValue` (u/v per face corner - SeamlessParameterization already had exactly those
        methods; GlobalGridMap implements by delegating to its latest baked GridMapIsoSurface,
        which grew id-keyed u/v accessors), `DofSystemValue` (dofCount + relax(), default relax
        throws UnsupportedOperation until a stage implements it; GridMapDofSystem.relax is the
        real Newton path - the relaxation logic moved OUT of GlobalGridMap into the DOF system;
        SeamlessDofSystem and the new CrossFieldDofSystem wrapper implement dofCount only),
        `ChartAtlasValue` (chartCount - CutGraph and LayoutPatchMaps implement), and
        `RelaxOutcome`. PortType UV_FIELD/DOF_SYSTEM/CHART_ATLAS now back onto these interfaces,
        so MapNodeContext.setInput validates values at wire time. newton_solver now reads
        `UvFieldValue`/`DofSystemValue` and calls `dofs.relax()` - zero concrete casts.
        REMAINING CAST: quad_extract still casts uv to GlobalGridMap - its chart-walking
        extraction cannot run on a bare per-corner UV field; the honest decomposition IS the
        planned generic QEx (mesh + per-corner UVs) input mode, undecided whether to pull forward
      - [x] SUPERSEDED by the approved solver.system plan (author rejected the marker-thin
        interfaces twice; final design: one concrete DofSystem class, strategies as their own
        classes decoupled from the stacks, node SPI moved out of annotations). Executed
        2026-08-23, batches 0-3 all verified (146-test baseline, TeaVM build, quad-layout scene
        on fertility: 44 singularities with indexSum4 == 4*chi == -24, 46 Newton iterations):
        (0) node SPI moved to `ixdar.geometry.mesh.nodes.api` (22 files, 151 import sites);
        RegistryProcessor gained a String-FQN supertype constructor so the processor no longer
        anchors the SPI; `UvFieldValue` renamed `UvField`.
        (1) `solver.system` package: concrete `DofSystem` (canonical interleaved solution +
        frozen, assembler/energy/writeBack/solve hooks, relax() = stage pipeline or SingleSolve),
        `SingleSolve`, `NewtonRelaxation` (the damped-Newton driver extracted from
        GridMapOptimizer - retained-factor refresh, non-inversion step cap, Armijo - generic over
        any DofSystem; quadratic = one step). `NormalMatrix.quadraticEnergy`. GridMapDofSystem
        storage unified: slotU/slotV/fixedBySlot REPLACED by system.solution/frozen interleaved;
        GridMapOptimizer became the hook provider (assembleInto, totalEnergy, maximumStep).
        (2) seamless: `GreedyRounding` (nearest-integer pin loop, rank-1 penalty updates,
        native fast path with retained factor) and `LazyConstraints` (activation loop over
        InteriorPointQp) extracted as strategies; SeamlessParameterization.build() split into
        setup + public resolve() (clears pins on entry, never rebuilds the DOF system);
        `assemble` renamed `assembleWeighted`; seamless solution is now the DofSystem's array.
        (3) crossfield: `PowerIteration` strategy extracted from solveSmoothest (kept its
        original unpack layout verbatim); `solveField()` split out of build() so re-solves skip
        frames/assembly; NDirectionField carries the canonical system, aligned PCG writes into it.
        `DofSystemValue` DELETED; PortType DOF_SYSTEM backs onto the concrete DofSystem; all
        three stage nodes output the same type, so newton_solver accepts any of them.
        DEVIATION, needs ruling: `SmoothEnergySystem.solveGreedyMIP` (crossfield BZK09 MIP) was
        NOT dissolved into GreedyRounding - it is batch-based (candidate ordering, patch-marked
        batch selection, batched hard freezes, warm AdaptiveSolver ladder re-solves), has zero
        test coverage, and is only reachable via the non-default BommesCrossField builder;
        forcing it into the one-at-a-time strategy would change behavior or reduce the shared
        loop to a hook-only skeleton. Left intact; options: leave (its loop is honestly a
        different strategy, batch rounding), or extract a `BatchRounding` strategy later.
        RESOLVED 2026-08-26 by deletion: the author ruled "remove bommes cross field and smooth
        energy system, we have them in git history if needed". DELETED: BommesCrossField,
        SmoothEnergySystem, VornoiForest (only Bommes/SmoothEnergy used it), and the now-dead
        constraint classes BoundaryConstraints + FeatureEdgeConstraints (only BommesCrossField
        invoked them; ConstraintSource enum constants BOUNDARY/FEATURE kept, QuadLayoutRuntime's
        draw order references them and feature alignment work may re-populate them). KEPT:
        DijkstraNode (BoundarySnap, CurvatureConstraints), SectionIntegrals (NDirectionField),
        CurvatureConstraints (CrossField), ConstraintSource. If batch rounding (BZK09 batched MIP
        with two-hop-patch-disjoint batches + warm AdaptiveSolver ladder) is ever wanted again,
        recover from git history and build it as a solver.system BatchRounding strategy.
        Verified: 146 tests, exactly the 3 known-failing classes, 3 skipped.
        Remaining from the approved plan: ChartAtlas/ChartTransition + CutGraph/GlobalGridMap
        implementers + consumer migrations (QuadMeshExtraction transforms, GridMapDofSystem fans)
    - [x] ChartAtlas batch executed 2026-08-26, RULING amendments: no interface and NO separate
        ChartTransition class - `quadlayout.ChartAtlas` is one CONCRETE data class (chartCount,
        chartOfFace, per-boundary chartA/chartB, quarterTurns/translationU/translationV arrays,
        integral flag; NONE=-1 sentinel shared with EmbeddedTMesh.NONE and NOT_PLACED), with the
        algebra as methods: chartAcross/hasTransition/mapPoint/mapTurns/transition(boundary,
        fromChart) hiding the side test, static invert/compose on `{turns,u,v}` double triples.
        Stored transition maps chartA into chartB: gridmap chartA=rightPatch (right-to-left, per
        GridMapVerification), cutgraph chartA=edgeFaceA (SeamlessProjector:458 direction).
        ChartAtlasValue DELETED (buggy chartCount overrides on CutGraph/LayoutPatchMaps gone);
        PortType CHART_ATLAS(ChartAtlas.class). STORAGE UNIFIED both sides: GlobalGridMap builds
        its atlas in the CONSTRUCTOR from frames (GridMapOptimizerParallelTest skips
        buildInitialMap, so ctor it is); GridMapVerification's three transition arrays DELETED -
        the atlas IS its storage, resolveTransitions refines it in place (frames values re-copied,
        loop arcs recovered, uncrossable cleared) so pre-verification readers (GridMapDofSystem
        seam coupling + node fans, chartNeighbourhood) see framed values and post-verification
        readers (QuadMeshExtraction) see resolved ones, same as before. CutGraph builds its atlas
        at the end of buildCutGraph; seamless cutTranslationS/T now ALIAS atlas.translationU/V
        (writeChartVerticesFromSolution fills in place; SeamlessProjector writes the same arrays).
        Consumer dedup: QuadMeshExtraction otherPatchAcross/mapPointAcrossArc/mapTurnsAcrossArc
        DELETED (atlas calls); GridMapDofSystem invertTransform/composeTransform DELETED (fans are
        double[] triples via atlas.transition + ChartAtlas.compose/invert); GlobalGridMap
        chartNeighbourhood inline algebra replaced (third dup). GridMapVerification
        propagateNodeFan/requireArcConsistency/canonicalizeArcInteriors now go through mapPoint.
        Also: InjectivityConstraints implements LazyConstraints.ConstraintSet directly (30-line
        anonymous adapter at SeamlessParameterization deleted); stray System.out in
        InjectivityConstraints.build, GridMapDofSystem.build moved to Platforms.log (seamless
        setup prints remain, separate sweep owed). Verified: 146 tests, exactly the 3 known
        failing classes (QuadMeshExtractionTest still exactly 2), 3 skipped; TeaVM green
        (PortType now reaches ChartAtlas); quad-layout scene identical: 46 newton iterations,
        3.73e+06 -> 3.97e+04, 0/38238 flipped, verify truncated=4703 integerNodes=44,
        extract quads=13490. Follow-ups flagged, not done: ChartWalker still reconstructs the
        seamless transition numerically instead of reading CutGraph.atlas; GridMapVerification
        recoverTransition writes the atlas directly (fine, it owns it); remaining System.out
        sweep in seamless/embedding.
- [ ] 7.4 Quad-layout endgame surveys DELIVERED 2026-08-26 (three parallel surveys: record-family
      merge, cross-stage coupling, node/stage-merge feasibility). Condensed findings, full detail
      in the session transcripts:
    - ARC_NETWORK families: "family 3" (LayoutExtraction) is NOT a family - it is MotorcycleGraph
      + quantizedLengthByArc[] + clusterByNode[] + a render buffer; LayoutEmbedding reads ONLY
      the graph and the int[]. TMeshPatch.sides and EmbeddedPatch.sideArcIds are structurally
      identical; type<->{critical,border} and vertexId<->copyVertex are renamed concepts. Dead
      fields: TraceArc.oppositeArcId (never read), TraceArc.axis (unread outside record),
      TMeshPatch.boundingArcIds (write-only). Recommended merge = survey option C: ONE concrete
      Node/Arc/Patch family + ArcNetwork container (new quadlayout/network package);
      arc_quantization writes quantizedLength onto arcs IN PLACE (same convention as
      tmesh_contract); LayoutExtraction demoted to diagnostics/render product off the topology
      port; PortType.ARC_NETWORK(ArcNetwork.class); kills all 4 unchecked casts and
      EmbeddedTMesh.build()'s id-remap tables (sourceNodeId/sourceArcId/sourcePatchId become
      identity). Open design calls: EmbeddedTMeshRecarve depends on renumbering (becomes an
      explicit compact op or dies); merged records carry pre-embedding chart fields
      (u/v/activeFace/position, exactly one consumer each) that go stale after embedding.
    - Coupling: seamless->gridmap needs ONLY faceCornerUv (UvField data modulo active-index vs
      face-id convention) - faceArea/targetQuadEdgeLength/weights are NEVER read cross-stage.
      DofSystem and ChartAtlas boundaries already clean; newton_solver fully decoupled.
      Real gaps: CROSS_FIELD is Object.class (honest contract: mesh, faceIdToActive,
      edgeIdToActive, theta, periodJump, faceX/faceY, alignmentEdgeIds, singularities,
      faceCount - all already on the concrete CrossField class, so binding the port to
      CrossField.class is cheap); motorcycle_graph has an UNDECLARED second input (reaches
      seamless.crossField through the UV value); layout_embedding reaches through 4 levels
      (layout->quantization->motorcycleGraph->seamless->crossField); quad_extract's honest
      contract is the gridmap stage's whole internal state (atlas covered; GridMapIsoSurface is
      the natural bakeable UV_FIELD payload but does not implement UvField yet). Invisible
      identity edges: the source HalfEdgeMesh and topology.copy flow outside all ports;
      LayoutResolution WRITES BACK EmbeddedArc.quadCount across the port boundary. Backward
      package dep: embedding imports gridmap.PatchRegions (EmbeddedTMesh:1644, Recarve).
      Engine/nodes disagree at GlobalGridMap.build() (engine fuses build+relax+extract; nodes
      split); engine stages 13-14 (PatchGridExtraction rerun, LayoutPatchSurfaces) have no node.
    - Node/stage merge (author leaning 1a "no two ways"): registry contract is no-arg ctor
      (::new in generated registry + 3 reflective sites) AND a probe instance of EVERY node at
      NodeGraphRuntime class-load static init; ALL 105 registered nodes have ZERO instance
      fields (verified via javap) - both fat-node (static methods) and thin-wrapper (static
      entry) precedents exist, but no per-run-object node exists. Four quad nodes have NO host
      stage class (newton_solver, quad_extract, tmesh_contract, load_mesh - their "stage" is a
      port VALUE type or static helper); three are multi-stage orchestrators (arc_quantization,
      layout_embedding, integer_grid_map). desktopOnly does NOT firewall the numerics from
      TeaVM (scenes reach QuadLayoutEngine, which constructs every stage; the real firewall is
      WebPlatform returning null/throwing for native backends); annotating stages as nodes would
      pull evaluate-body deps (GeometryBundles, FieldBroadcast, MeshLoader) into web reachability.
      The duplication is THREE-way: engine / node wrapper / stage. PENDING RULING with evidence:
      recommend killing the ENGINE copy (7.2-final phase: engine executes the node graph) rather
      than merging wrappers into stages.
    - RULED (author, overriding the recommendation): "remove the separate mesh node classes and
      make the stages stateless for the quad layout pipeline". EXECUTED 2026-08-27, verified
      (146-test baseline, TeaVM green, scene identical: 44 singularities, 46 newton iterations,
      energy 3.73e+06 -> 3.97e+04, 0/38238 flipped, 13490 quads). Five wrappers DELETED, stage
      classes are now the registered nodes, two patterns:
      (1) builder-scratch stage: NDirectionField is the stateless cross_field node; ALL its
      per-run solver state moved into a private nested `Solve extends CrossField` that evaluate
      constructs fresh; the escaping products are the CrossField data and solve.system (whose
      hooks keep the Solve alive for newton re-solves). Static entry `buildField(mesh)` for the
      engine. CrossFieldNode deleted.
      (2) builder-is-product stages: the stage class implements MeshNode alongside its data
      role; the registry/probe instance is INERT (new no-arg ctor nulls the finals, evaluate
      never touches `this` - it builds a fresh fully-parameterized instance and outputs it).
      seamless_uv -> SeamlessParameterization, motorcycle_graph -> MotorcycleGraph,
      arc_quantization -> QuantizedMeshGrid (evaluate also runs LayoutExtraction),
      layout_embedding -> LayoutEmbedding (evaluate also runs EmbeddedTMesh build+validate),
      integer_grid_map -> GlobalGridMap (evaluate also runs LayoutPatchMaps + IntegerGridMap).
      SeamlessUvNode/MotorcycleGraphNode/ArcQuantizationNode/LayoutEmbeddingNode/
      IntegerGridMapNode deleted; QuadLayoutNodeChainTest re-pointed; catalog regenerated with
      the same ten ids. REMAINING in nodes/quadlayout: TmeshContractNode, NewtonSolverNode,
      QuadExtractNode - each is the SOLE class for its stage (they operate on input data:
      EmbeddedTMesh.contract, DofSystem.relax, GlobalGridMap.extractQuads), so no duplication;
      pending author preference whether to rehome them (e.g. tmesh_contract onto EmbeddedTMesh).
      DeclarationOrder convention: statics, instance fields, ctors (inert no-arg first), node
      methods after ctors.
    - RULED (author) data/algorithm separation + no nested classes (now in CLAUDE.md): stages may
      keep SCRATCH instance fields but no fields that propagate; every durable product lands on a
      data class flowing on ports. EXECUTED 2026-08-27 as five batches in one uncommitted pass:
      (A) NDirectionField no longer extends CrossField: stateless-per-run stage producing a
      CrossField (which gained `system` and lost build()/detectAlignmentEdges()/
      extractSingularities() - all moved into the stage); ComplexUpper extracted top-level.
      (B) NEW `SeamlessUv` data class (implements UvField): mesh, crossField, counts, edge
      tables, uCorner/vCorner, cutTranslationS/T (alias atlas), injective, cutGraph, system,
      metrics, targetQuadEdgeLength, faceCornerUv/u/v/uvSignedArea/lookupCorners.
      SeamlessParameterization is the stage (scratch: faceArea/faceShape*/faceWeight/dofSystem/
      solution/baseFactor*) building into `uv`; CutGraph retyped to SeamlessUv; every downstream
      consumer (motorcycle, gridmap, runtime, engine, scenes, tests) retyped to SeamlessUv.
      (C) RECORD-FAMILY MERGE (survey option C, amended): TMeshNode/TraceArc/TMeshPatch DELETED;
      EmbeddedNode gained kind(NodeKind enum, new top-level)/vertexId/activeFace/u/v/position/
      singularityIndex4 + arrangement ctor, EmbeddedArc gained traceId/parametricLength + ctor,
      EmbeddedPatch gained validRectangle. ONE container end-to-end: EmbeddedTMesh gained an
      arrangement ctor (sourceMesh field, topology now mutable/null pre-embedding, traces +
      featureSpanByEdgeId carried), MotorcycleGraph mints into it, QuantizedMeshGrid writes
      arc.quantizedLength IN PLACE (int[] kept as diagnostics; LayoutExtraction demoted to
      stage diagnostics `quantization.layout`, off the port), layout_embedding gained an
      explicit UV input (the hidden crossField reach-through is gone), and
      EmbeddedTMesh.build(embedding) was REPLACED by in-place `assemble(embedding)`: stable ids,
      dead arrangement leftovers alive=false, operators re-created once topology exists.
      KNOWN COMBINATORIAL DRIFT, verified equivalent: contraction now iterates
      arrangement-numbered ids (old ids were remapped dense at assembly), so tie-breaking
      changes the final layout on fertility: quads 13490 -> 13853, energy start 3.73e+06 ->
      5.55e+07, relaxed 3.97e+04 -> 4.02e+04, iterations 46 -> 49; all invariants hold
      (singularities 44 indexSum4=-24=4chi, 0/38446 flipped, seam+chain+extraction tests green).
      (E) gridmap: NEW `GridMapAssembly` stage (id integer_grid_map) holding
      assemble()/assembleInitial()/measureNodes()/static extractQuads(); GlobalGridMap is pure
      data again (UvField + atlas + queries; node role, build(), buildInitialMap(),
      extractQuads() removed); QuadExtractNode and the engine call GridMapAssembly.extractQuads.
      PORT TYPES NOW ALL CONCRETE: CROSS_FIELD(CrossField), ARC_NETWORK(EmbeddedTMesh),
      UV_FIELD(UvField), DOF_SYSTEM(DofSystem), CHART_ATLAS(ChartAtlas) - zero Object.class
      quad ports, zero unchecked casts to mismatched families.
      VERIFIED: 146 tests, exactly the 3 known-failing classes, 3 skipped; TeaVM green;
      quad-layout scene ready and pipeline-complete. Flagged follow-ups: rename EmbeddedTMesh ->
      ArcNetwork + record renames (pure sweep, pending author taste); EmbeddedTMesh still mixes
      data with contraction operators (contract()/conform() + mutation API - the ruled-open
      operator extraction); QuadLayoutRuntime motorcycle overlay now shows only
      arrangement-positioned nodes (operator-minted nodes have no position); engine still
      duplicates the stage sequence (bullet-3 engine->graph migration still owed).
    - RULED (author, 2026-08-27) decoupling pass, EXECUTED same day, uncommitted:
      (1) SeamlessUv no longer encapsulates mesh, crossField, metrics, or system. Evidence
      first: downstream of the seamless build NOTHING reads the cross field proper (faceX/
      faceY/theta/periodJump have zero consumers outside crossfield/seamless; the exam scene's
      theta/periodJump writes are producer-side reference-field swaps). What downstream pulled
      through `uv.crossField` was parametrization data, so SeamlessUv now carries it itself as
      aliases: faceIdToActive/edgeIdToActive (its own arrays are unreadable without them),
      singularities (the cone points), alignmentEdgeIds (feature edges). CrossField flows on
      exactly one edge of the graph: cross_field -> seamless_uv, matching the literature (a
      rotation per face). Ctor is now SeamlessUv(faceCount, edgeCount); lookupCorners takes the
      mesh as a parameter. metrics + dofSystem.system live on the SeamlessParameterization
      stage (new scratch fields mesh/field/metrics); the node ports and the engine
      (seamlessMetrics + new seamlessSystem field, benchmark retargeted) read them there.
      MotorcycleGraph(mesh, uv, alpha) + node gained a GEOMETRY input (mesh rebuilt via
      fromMeshTopology, deterministic ids); ChartWalker(mesh, uv); PatchBoundaryBuilder reads
      graph.mesh; SnappingCarve topology from network.sourceMesh, indexes from uv;
      QuadLayoutRuntime.setSeamlessParametrization(uv, mesh) + captureSingularities(list, mesh)
      (protected dropped); scenes pass their mesh.
      (2) NodeKind DELETED. The enum was redundant with data the node already carries:
      critical/border are now set AT MINT (SINGULARITY->critical, BOUNDARY->border,
      non-boundary termination->critical), one new boolean `truncated` for dead-end traces,
      INTERSECTION is the default. Quantization checks kind==SINGULARITY&&vertexId>=0 became
      critical&&vertexId>=0 (equivalent: critical<->SINGULARITY at arrangement time, border
      excludes boundary-vertex nodes); assemble no longer derives border; markCriticality
      copies node.critical; runtime colors read critical/border/truncated (dead FEATURE branch
      and COLOR_FEATURE_NODE removed); diagnostics log the booleans instead of kind.name().
      (3) ComplexUpper DELETED - it was a 2-use argument bundle; realify(diag, upRe, upIm)
      takes the three directly.
      VERIFIED: 146 tests / 5 failing testcases in exactly the 3 known classes / 3 skipped;
      TeaVM green; quad-layout scene ready with numbers identical to the prior verified run
      (13853 quads, 5.55e+07 -> 4.02e+04, 49 iterations, 0/38446 flipped, euler=-6) - this
      pass is behavior-preserving.
    - RULED (author, 2026-08-27) downstream stages know UvField, not SeamlessUv; singularities
      flow as their own data so the renderer can draw them as points independent of any UV
      field. EXECUTED same day, uncommitted:
      (1) UvField gained `faceCornerUv(faceId, out)` (default via u/v; SeamlessUv overrides
      with the direct array read). This FIXED a latent bug: LayoutResolution and
      GridMapOptimizer were passing source FACE IDS to the old activeFace-indexed accessor,
      correct only while ids coincide with active indices.
      (2) The id->active-index inverse moved to the mesh where it belongs: ActiveIdSet.indexOf
      (the `indexById` array already existed) exposed as
      HalfEdgeMesh.activeFaceIndexOf/activeEdgeIndexOf, unboxed. Downstream stages use these
      instead of uv.faceIdToActive/edgeIdToActive; SeamlessUv keeps its two maps only as the
      internal indexing of its own arrays.
      (3) SeamlessUv dropped `singularities`/`alignmentEdgeIds` (added one batch earlier -
      superseded). New PortTypes SINGULARITY_LIST(List) and EDGE_ID_SET(Set); cross_field node
      outputs `singularities` + `feature_edges`; motorcycle_graph node inputs them.
      MotorcycleGraph(mesh, UvField, List<Singularity>, Set<Integer>, alpha);
      ChartWalker(mesh, UvField, singularities); PatchBoundaryBuilder reads graph.uv + mesh
      lookups; SnappingCarve/LayoutEmbedding/LayoutPatchMaps/LayoutResolution/
      GridMapOptimizer/GridMapAssembly/GlobalGridMap all retyped to UvField.
      (4) Renderer: captureSingularities(List<Singularity>, mesh) is self-sufficient
      (icosphere + radius guards moved inside); setSeamlessParametrization no longer captures
      singularities - the three scenes call captureSingularities(engine.crossField
      .singularities, mesh) explicitly, the first step of the ruled renderer simplification.
      SeamlessUv now appears ONLY in: the seamless package, the engine product field, the two
      exam scenes, the renderer (raw uCorner upload - renderer simplification still owed), and
      tests. Field renames: MotorcycleGraph.seamless -> uv; shadowing locals in
      spawnFromSingularities renamed (au/av/bu/bv/cu/cv); CORNERS constants now
      HalfEdgeMesh.TRIANGLE_CORNERS.
- [ ] 7.3 Whether always-on profiling should fail fast when `.profiler/libasyncProfiler` is missing,
      as it does now, or degrade

## Constraints to carry into `ARCHITECTURE.md`

### Mesh data conventions (established by 3.3)

- Edge keys pack through `EdgeKey` only: `undirected(a, b)` puts the smaller vertex id in the high
  32 bits; `directed(from, to)` keeps `from` high. Never inline the shift/mask again. Packed pairs
  that are not vertex edges (region labels, MSC cells, matrix row/col) stay local to their file.
- "Which face is across this edge" is `HalfEdgeMesh.faceAcrossEdge(faceId, edgeId)`, returning
  `MeshTopology.NONE` at a boundary.
- `GeometryBundle` slot names start with a single underscore and are owned by exactly one constant,
  on the value type when one exists (`CurveGeometry.SLOT`, `EdgeMarks.SLOT`) or on the producing
  node otherwise (`TagGeometryNode.TAGS_SLOT`, `MeshBooleanNode.FACE_ORIGIN_SLOT`). No raw slot
  string literals at use sites.
- `ArrayMesh` vs `HalfEdgeMesh`: modifier nodes that need dense arrays coerce with
  `ArrayMeshEngine.fromUniformMeshTopology(mesh)`; type-preserving ops branch on `instanceof` and
  return the input's kind. The empty mesh is `ArrayMeshEngine.emptyQuads()` or
  `new HalfEdgeMesh()` — never a raw `new ArrayMesh(...)` with empty arrays.

### IDE toolchain facts (hard-won 2026-08-22, all load-bearing)

- VS Code's Java autobuild compiles into `target-ide/` (the `m2e-ide-build` profile in the parent
  pom), never Maven's `target/` - sharing one tree corrupted class files mid-build
  (`ClassFormatError: extra bytes`) and broke `mvn clean` while a build raced.
- The language server runs on the extension's bundled JRE (21) unless `java.jdt.ls.java.home`
  in USER settings (machine-specific, never tracked) points at a newer JDK. Anything the LS must
  LOAD - annotation processors above all - must be built at or below its release level, so the
  pin must stay >= the `annotations` release (25). On this machine it points at
  `/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home`. Symptom of a mismatch:
  "Unable to load annotation processor factory", then null generated-map statics at run time.
- Even with a loadable jar, JDT APT never produced the registries here; the fix that works is
  `ixdar-app/.classpath:44` pointing the IDE at Maven's `target/generated-sources/annotations`,
  so F5 compiles whatever the last `mvn compile` generated. After `mvn clean` the IDE shows
  registry errors until the next compile. `generatedSourcesDirectory` is pinned to
  `${project.basedir}/target/generated-sources/annotations` in ixdar-app's compiler config so
  m2e re-imports keep writing that entry correctly instead of rewriting it to the empty
  `target-ide/` path.
- A class whose static initializer fails to COMPILE still runs under JDT: the field silently stays
  null (`CanvasSceneMap.MAP`) instead of throwing - IDE-side nulls of static finals mean "look at
  the Problems panel", not "runtime bug".
- Standalone `mvn` in `ixdar-app/` resolves `IXDAR:annotations` from `~/.m2`; reinstall it after
  any annotations change or stale-jar errors surface as `NoSuchMethodError`/missing packages.
  Reactor builds from the repo root never hit this.
- `mvn install` on `ixdar-app` runs the test phase, which the three 6.8 failures currently fail;
  use `-DskipTests` until they are fixed.

Platform abstraction, and the fact that there is no exclusion list: the whole source tree compiles for
web, and desktop code is kept out by `WebPlatform` factory methods that refuse, by `Class.forName` used
deliberately as a reachability firewall, and by `provided` scope in the `web-teavm` profile. TeaVM runs
with `strict=false` and `stopOnErrors=false`, so a violation is a silent runtime stub in the browser,
not a build failure. Web displays results rather than computing them; PDEs, matrix solves and booleans
stay off it. GL 3.3 core with GLSL ES 3.00 as the floor. Java 25. Commercial intent, so permissive
licences for anything shipped and GPL-3 only for server-side. Multithreading allowed but deliberate and
localised, with the Newton solver as the reference. No OOM at 100k to 500k meshes. Sub-second normal,
10 seconds suspect, and slowness usually meaning a planning or correctness problem. Invariant-level
tests of pipeline steps, no real models in the unit suite. Assets: separating data from code matters,
mechanism deliberately unresolved. Plus the traps: the resource extension whitelist duplicated in two
pom locations, MKL absent on macOS so the solver silently falls back to EJML, `Platforms.init` ordering,
the `SuitePlatform` SPI file, and GL calls being render-thread only.
