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
- Cone points and feature edges flow as their own values (`SINGULARITY_LIST`,
  `EDGE_ID_SET`), never as sub-components of a UV field.
- All logging through `Platforms.log`. No `protected`. The author commits.

## Verification baseline

```
mvn -q clean install -pl annotations -DskipTests
mvn clean test -pl annotations,ixdar-app
./tools/teavm-build.sh
uv run ixdar-cli run-scene --scene quad-layout --timeout 90 --skip-build
```

146 tests; exactly 3 known-failing classes (A7), 5 failing testcases, 3
skipped. Scene must reach ready and reproduce: 13853 quads, energy 5.55e+07 ->
4.02e+04, 49 iterations, 0/38446 flipped, singularities 44 (indexSum4 = -24),
euler = -6. Kill stray `IxdarWindow` processes after scene runs.

## A. Quad layout

- [ ] A1 Engine through the node interface (RULED: no DSL migration). The
      engine's `build*` methods call stage constructors directly; they should
      invoke the registered nodes through evaluate/ports instead, and
      `QuadLayoutEngine` itself becomes a registered mesh node (geometry +
      alpha in, staged products out). `EmbeddedTMeshScene` keeps driving the
      engine directly as an interactive debugger.
- [ ] A2 Fixtures to DSL (RULED: convert all 11). The pathological fixtures
      (fan collapse, pinched cover, sliver pinch, stacked zero rows) encode
      real bugs the author debugged, so they must survive the conversion.
      Requires a way to construct an authored arrangement in a graph, since
      those fixtures deliberately bypass stages 1-5; a node or loader that
      builds the arrangement from data is part of this item.
- [ ] A3 `QuadLayoutRuntime` accepts only port-type values (RULED). Every
      setter must take a value some port could carry: mesh, `UvField`,
      `ARC_NETWORK`, `CROSS_FIELD`, singularity lists, polylines, point lists.
      Offenders: `setMotorcycleGraph(MotorcycleGraph)` (takes the stage, not
      even the network), `setLayoutEmbedding`, `setDiagnostic`,
      `setLayoutPatchSurfaces`, `setSeamlessParametrization(SeamlessUv)` (raw
      uCorner upload). Precedent for the general forms:
      `captureSingularities(List<Singularity>, mesh)` and
      `uploadPatchParametrization(mesh, cornerU, cornerV, ...)`.
      OPEN (author unsure yet): decouple rendering order from rendering, so a
      scene decides the order overlays draw in rather than the runtime.
- [ ] A4 Generic QEx input. `QuadExtractNode` declares a `UV_FIELD` input and
      hard-casts it to `GlobalGridMap` (`QuadExtractNode.java:59`). Evidence of
      what the extraction actually reads: per-patch dense UVs (`uvByPatchId`,
      `denseByCopyVertexByPatchId`), the copy topology, patch regions, and
      chart transitions (`atlas.mapPoint`/`mapTurns`/`chartAcross`). None of
      that is information QEx fundamentally needs: textbook QEx takes a mesh
      plus per-corner UVs, with seam transitions implicit in the corner-value
      discontinuities. The current code needs the atlas only because it
      extracts chart-wise, patch by patch, composing transitions explicitly.
      The fix is a face-local extraction over mesh + `UvField`
      (`GridMapIsoSurface` already has `u(faceId, corner)`/`v` and just needs
      to implement `UvField`). Real algorithm work, not plumbing.
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
      STILL OPEN from this item: the 6.12 conform decision - contract()
      internally conforms before its recarve, but the engine's `conforming`
      flag is never set and the node's CONFORM input re-conforms the recarved
      network; decide the intended pipeline behavior and fix flag or javadoc.
- [ ] A6 Small quad-layout items.
      - `System.out.println` remains in seamless/motorcycle/engine; sweep to
        `Platforms.log`.
      - `ChartWalker` derives seam transitions numerically by corner matching
        instead of reading `CutGraph.atlas`. Low urgency.
      - Coons/surfaces conversion node (`PatchGridExtraction` +
        `LayoutPatchSurfaces`), relating to the existing `coons_patch` node.
      - The motorcycle overlay draws only arrangement-positioned nodes;
        operator-minted nodes have no position. Cosmetic, documented.
      - Update `ARCHITECTURE.md` (overlay pattern, quad-layout entries in the
        system and package maps) once the node migration lands.
- [ ] A7 Three test classes fail on the committed tree, pre-dating all of this
      work: `DenseMeshFewSplitsTest` (refining 8x raises edge splits 9 -> 37),
      `QuadMeshExtractionTest` (ring orientation undecidable on the unrelaxed
      torus), `TJunctionExtensionTest` (contracted torus loses the stub
      T-junction). Deep pipeline behaviour; they are the baseline's known
      failures and need real investigation, not test edits.

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
- [x] D6 DONE 2026-08-28, awaiting merge from the agent worktree at
      `.claude/worktrees/agent-aafdec120f6d01b58/Ixdar/` (ai-workspace repo;
      uncommitted; primary checkout untouched, diff-verified). Accelerate
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
      internal reordering is transparent (equivalence-tested). Merge plan:
      after A5 commits, copy the 5 new files + 8 modified files into the
      primary tree as its own batch and re-verify.
- [ ] D7 Wire loaded models in as boolean operands (Mesh Booleans note, R7
      part b); the `ModelScene` conversion was the prerequisite.
- [ ] D8 Decide whether always-on profiling should fail fast when
      `.profiler/libasyncProfiler` is missing, as it does now, or degrade.
