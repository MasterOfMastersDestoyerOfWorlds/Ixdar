# Architecture documentation and reuse plan

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

- [ ] 1.1 Write `ARCHITECTURE.md`: system map, task-indexed patterns, constraints section
- [ ] 1.2 Write `package-info.java` files (purpose / entry points / what does not belong here)
- [ ] 1.3 Build the splice generator: `compile` phase, marker-delimited, fails loudly if markers absent
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
- [ ] 2.9 R6 icosphere cluster: delete `standalone/Icosphere` and `IcosphereRuntime`, rebuild the
      scene on `IcosphereMeshNode`, point `QuadLayoutRuntime`'s hardcoded table at the same source.
      ~470 lines. Decide whether `IcosphereSavePointScene` survives
- [ ] 2.10 R1 leftover: centroid-and-radius duplication between `ModelScene.focusOrbitOn` and
      `QuadLayoutRuntime.setDiagnostic`
- [-] 2.11 R5 `DungeonViewerScene` restructuring

## 3. Later ruling groups

- [ ] 3.1 Fixtures: 2,073 lines of `LayoutFixture` in `src/main/java`, plus 418 lines of cloned
      `buildGrid()` across 15 test files that use a different plane, origin and diagonal from
      `GridMeshNode`. Blocked behind generalising `MarkCreaseNode` to edge labels
- [ ] 3.2 Runtime overlays: `QuadLayoutRuntime`'s 8 stage-typed setters and 10 GPU buffer sets,
      ~1,400 lines duplicating `setTags` / `setPerVertexScalar` / `setFeatureEdgeOverlay` on its parent
- [ ] 3.3 Mesh data: `ArrayMesh` vs `HalfEdgeMesh` conventions, `GeometryBundle` slot naming, face
      adjacency built three ways, edge-key packing inlined at ~35 sites with no `EdgeKey`
- [ ] 3.4 Nodes: 95 registered, 3 tests

## 4. Enforcement in code, not prose

- [ ] 4.1 `MeshNodeRegistry` processor error for undocumented ports, making the existing
      `MeshNode.socketDocs()` Javadoc claim true. Wire `CanonicalPortNames.isAllowed` at the same
      point; it is fully implemented with zero callers today
- [ ] 4.2 `desktopOnly` on `@MeshNodeAnnotation`, default false. `RegistryProcessor` emits two maps
      so the web registration path never references the heavy classes. A single map would let TeaVM
      reach them all and the flag would buy nothing
- [ ] 4.3 Error to the mesh scene terminal when a web build meets a desktop-only node
- [ ] 4.4 Surface the per-node timings `NodeGraphRuntime` already records in `lastTimingMs`
- [ ] 4.5 `GridMapOptimizer.timeBudgetMilliseconds` 500s to 60s
- [ ] 4.6 `desktopOnly` in the node catalog export, and move catalog regeneration off the opt-in
      profile onto `compile` so it stops drifting (it was 2 nodes and 3 months stale)

## 5. Cleanups

- [x] 5.1 `manifold3d` 3.0.7 to v3.5.1-4; `ManifoldMeshBooleanBackend` rewritten JavaCPP to FFM.
      Removes the undeclared Homebrew dependency on macOS and retires the preview-feature pin
- [x] 5.2 Always-on profiling on every platform: `.profiler/libasyncProfiler` symlink,
      `tools/link-profiler.sh`, settings and `run_scene.py` pointed at it
- [x] 5.3 `Mesh Boolean` launch config gains `-XstartOnFirstThread` and `-Xmx4g`
- [ ] 5.4 Delete `io.humble:humble-video-all` (AGPL) and `mtj` (LGPL). Both unused in source, both
      compile scope, both in the fat jar. Contradicts the commercial-use licensing intent
- [x] 5.5 `CP` moved to `ixdar-app/target/CP`, already-ignored ground, so no `.gitignore` entry is
      needed. Root file deleted; commit the deletion to untrack it
- [ ] 5.6 Decide what replaces the `ixdar-tickets` submodule. The gitlink still reads as deleted; the
      standalone repo is at `~/Code/ixdar-tickets`
- [ ] 5.7 Verify two autofix recipes that look unreachable: `MultipleStringLiteralsRecipe` is keyed to
      `MultipleStringLiterals` but the config now emits `MeaningfulDuplicateStringLiteralsCheck`, and
      `MagicNumberRecipe` is keyed to a rule that is commented out. Also find where
      `InlineGlueStringConstantsRecipe` is invoked; it is in neither `RECIPE_ORDER` nor `InlineHelpers`

## 6. Bugs found, needing tickets

- [x] 6.1 `run-scene` passes `-XstartOnFirstThread` on darwin
- [x] 6.2 `run-scene` builds with `clean compile`. Plain `mvn compile` is still unreliable after an
      edit, so every verification here needs a clean build; the root cause is untouched
- [x] 6.3 `run-scene` builds both modules and puts `annotations/target/classes` ahead of the
      installed jar on the classpath
- [ ] 6.4 Recover `runOriginalID` from the MeshGL64 segment to restore boolean provenance tinting.
      Answer why the old backend reported `faces new=0/36` and never flagged an intersection face
- [ ] 6.5 `MeshNodeViewerScene` is the scene `WebLauncher` instantiates, and it calls
      `Files.readAllBytes` and `MeshLoader`, both `java.nio.file`. That path throws in a browser
- [ ] 6.6 `HalfEdgeMeshRuntime`, `QuadLayoutRuntime`, `IcosphereRuntime` and `AssimpModelRuntime`
      import `org.lwjgl.BufferUtils` directly instead of `IxBuffer`. LWJGL's buffer functions are
      emitted into the shipped JavaScript as a result
- [ ] 6.7 `PatchRenderer` stays out of the web build only by method-level dead-code elimination. One
      call to `renderMultiview` from web-reachable code pulls `Graphics2D` in
- [ ] 6.8 `IX-6` exists in both `content/IX/` and `done/IX/` with contradictory statuses; `BOARD.md`
      is stale and omits the DSL epic

## 7. Decisions still owed

- [ ] 7.1 R7 part (b): wire loaded models in as boolean operands, which is what the Mesh Booleans
      note asks for. The `ModelScene` conversion was the prerequisite
- [ ] 7.2 Quad layout stages to mesh nodes. Author's words: quad layout "was a long divergance and was
      hard to get right so its bee na while but should be integrated into the common system", and it
      "will need a lot of testing". Related: the pipeline is really a mesh-to-DSL generator, which only
      makes sense as DSL once a load-mesh node exists
- [ ] 7.3 Whether always-on profiling should fail fast when `.profiler/libasyncProfiler` is missing,
      as it does now, or degrade

## Constraints to carry into `ARCHITECTURE.md`

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
