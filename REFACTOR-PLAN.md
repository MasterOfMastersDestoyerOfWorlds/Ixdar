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

## 7. Decisions still owed

- [ ] 7.1 R7 part (b): wire loaded models in as boolean operands, which is what the Mesh Booleans
      note asks for. The `ModelScene` conversion was the prerequisite
- [ ] 7.2 Quad layout stages to mesh nodes. Author's words: quad layout "was a long divergance and was
      hard to get right so its bee na while but should be integrated into the common system", and it
      "will need a lot of testing". Related: the pipeline is really a mesh-to-DSL generator, which only
      makes sense as DSL once a load-mesh node exists. TODO when executed: update `ARCHITECTURE.md`
      (the overlay pattern and the quad-layout entries in the system map and package map)
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
