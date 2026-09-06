# Ixdar architecture

## System map

Ixdar is a Java 25 rendering engine with two targets built from one source tree: an LWJGL/OpenGL
desktop app and a TeaVM browser build that the KriegEterna site embeds. There is no web exclusion
list: the whole tree compiles for web, and desktop-only code is kept out by `WebPlatform` factory
methods that refuse, `Class.forName` used as a reachability firewall, and `provided` scope in the
`web-teavm` profile.

Two Maven modules. `annotations` holds the annotation interfaces and the processors that
generate registries at compile time. `ixdar-app` holds everything else.

The core pipeline:

```
.dsl graph file                     ixdar-app/src/main/resources/dsl/
  → NodeGraphRuntime.fromSource     parse once, REGISTRY_CLASSES cached per process
  → mesh nodes                      ~95 @MeshNodeAnnotation classes, registry generated
  → GeometryBundle                  MeshTopology (ArrayMesh | HalfEdgeMesh) + named slots
  → runtimes                        HalfEdgeMeshRuntime and subclasses upload/draw GL buffers
  → scenes                          Scene subclasses own cameras, input, and overlays
  → IxdarWindow                     the desktop canvas; WebLauncher is the TeaVM entry
```

Around it: `platform` abstracts GL/file/input behind interfaces with LWJGL, WebGL, and headless
implementations; an HTTP automation server (port 47832) drives headless runs, screenshots, and
mesh fingerprints via `ixdar-cli`; the quad-layout subsystem (`geometry/mesh/quadlayout`) is a
research-grade pipeline from cross fields to quantized quad layouts, desktop-only. Every stage is
a registered mesh node, `QuadLayoutEngine` is itself the `quad_layout` node, and its `build*`
methods drive the stages through their ports.

## Task-indexed patterns

**Add a mesh node.** Implement `MeshNode`, annotate `@MeshNodeAnnotation(id = "...")`. Ports are
`public static final` `InputPort`/`OutputPort` constants with the name literal inline
(`GridMeshNode` is the reference shape); everything else goes through `PORT.name`. When an input
and output share a name, the second port references the first (`new OutputPort(GEOMETRY.name,
...)`). Document every port in `socketDocs()`; `MeshNodeRegistryTest` fails the build otherwise,
and `CanonicalPortNames` rejects nonstandard names unless allowlisted. Desktop-only nodes set
`desktopOnly = true` and land in a separate generated registry behind the `Class.forName`
firewall.

**Evaluate a node without a graph.** `MapNodeContext` feeds inputs and captures outputs directly;
see `GridMeshNode.triangulated` for the pattern of a static helper that evaluates its own node.
Tests build fixture geometry this way instead of cloning mesh construction code.

**Store data alongside a mesh.** `GeometryBundle` slots. Names start with a single underscore and
are owned by exactly one constant: on the value type when one exists (`CurveGeometry.SLOT`,
`EdgeMarks.SLOT`), on the producing node otherwise (`TagGeometryNode.TAGS_SLOT`). Never a raw
string at a use site. Per-edge marks go through the `mark_edges` node and the `EdgeMarks`
accessor.

**Key an edge in a map.** `EdgeKey.undirected(a, b)` / `EdgeKey.directed(from, to)` and the
`minVertex`/`maxVertex` accessors. Never inline the shift-and-mask. The face across an edge is
`HalfEdgeMesh.faceAcrossEdge(faceId, edgeId)`, `NONE` at boundaries.

**Choose a mesh representation.** `HalfEdgeMesh` for topology work (adjacency, deletion via
active flags), `ArrayMesh` for dense position/index data headed to the GPU. Nodes that need dense
arrays coerce with `ArrayMeshEngine.fromUniformMeshTopology`; type-preserving ops branch on
`instanceof` and return the input's kind. The empty mesh is `ArrayMeshEngine.emptyQuads()` or
`new HalfEdgeMesh()`.

**Add a scene.** Extend `Scene`, or `ModelScene` for anything that views a mesh. It provides
`bindInputDirect`, `frameMesh`, `preserveOrbit(BooleanSupplier)`, orbit state as public fields,
and the shared `ModelCatalog`. After executing a DSL graph, call
`NodeGraphRuntime.logTimings(prefix)` so slow nodes surface in the terminal. Every scene needs a
way to run it: a `.vscode/launch.json` entry is required whenever a scene is being tested, plus
a scene id `run-scene` can drive headless. The `ixdar-cli` new-scene command exists but scene
setup has too many variables for it to be reliable; treat it as a starting point only.

**Draw overlays on a mesh.** `HalfEdgeMeshRuntime` already has tag-partitioned coloring
(`setTags`), scalar heat maps (`setPerVertexScalar`), and colored edge-line overlays
(`setFeatureEdgeOverlay`). Check those before writing new GL plumbing. `QuadLayoutRuntime` now
takes only port-typed values (mesh, `UvField`, `ArcNetwork`, `CrossField`, patch surfaces, point
clouds and polylines), converts each into a `LineSet`, a `PointSet`, or a corner array, and
uploads through `VertexBuffer` per `VertexLayout`; decoupling overlay draw order from the runtime
is still open.

**Verify a change.** Build with `mvn -q clean compile -pl annotations,ixdar-app`. Plain
`compile` without `clean` is unreliable after edits and prunes generated registries. Run a scene
headless with `uv run ixdar-cli run-scene --scene <id> --timeout 90 --skip-build` (it adds
`-XstartOnFirstThread` on macOS) and kill strays afterwards. Tests:
`mvn clean test -pl annotations,ixdar-app -Dtest=A,B -Dsurefire.failIfNoSpecifiedTests=false`, with
commas between test names, never `+`.

## Constraints

### Platform and licensing

- Web displays results rather than computing them: PDEs, matrix solves, and booleans stay off it.
  TeaVM runs with `strict=false` and `stopOnErrors=false`, so a platform violation is a silent
  runtime stub in the browser, not a build failure.
- GL 3.3 core with GLSL ES 3.00 as the floor. GL calls are render-thread only. macOS caps OpenGL
  at 4.1 and needs `-XstartOnFirstThread`.
- Commercial intent: permissive licences only for anything shipped; GPL-3 acceptable server-side
  only.
- Multithreading is allowed but deliberate and localised; the Newton solver is the reference.

### Performance and testing

- Never allocate `ByteBuffer.allocateDirect()` per frame; cache and reuse.
- No OOM at 100k–500k vertex meshes. Sub-second is normal, 10 seconds is suspect; slowness
  usually means a planning or correctness problem, not a tuning problem.
- Unit tests assert invariants of pipeline steps. No real models and no fingerprint tests in the
  unit suite.

### Known traps

- The resource extension whitelist is duplicated in two pom locations.
- MKL is absent on macOS; the desktop Cholesky ladder is PARDISO, then Accelerate (macOS), then
  EJML.
- `Map.of`/`Set.of` iteration order is salted per JVM run, so anything serialized to a checked-in
  file must sort first (the catalog exporter wraps in `TreeMap`).
- `mvn install` on `ixdar-app` runs tests; quad-layout test classes with known failures on the
  committed tree are listed in `REFACTOR-PLAN-2.md`'s verification baseline; use `-DskipTests`
  until they are fixed.

## Forward-looking (not current state)

- `QuadLayoutRuntime` overlay draw order moves out of the runtime so scenes decide what draws
  over what.
- Boolean provenance (`runOriginalID` from the MeshGL64 segment) is to be recovered so result
  faces can be tinted by origin.
- Asset mechanism (separating data from code) is deliberately unresolved; `IXDAR_ASSET_REPO_ROOT`
  is the current pointer.

## Package map

<!-- package-map:begin (generated by SpliceArchitectureDoc; do not edit) -->
- **ixdar.annotations**: The annotation-processor module. `RegistryProcessor` is the shared base: each registry generates an id-to-supplier map class, with a `Desktop`-suffixed variant the web build never references.
- **ixdar.annotations.automation**: HTTP route declaration: `@AutomationRouteAnnotation` plus the `RouteDoc` machine-readable docs. Registry is keyed by id; two routes with the same simple class name silently overwrite unless ids differ.
- **ixdar.annotations.command**: Terminal command registration: `@CommandAnnotation` (CLASS retention, not reflectable at runtime) over the `TerminalOption` contract.
- **ixdar.annotations.geometry**: The smallest registry: `@GeometryAnnotation` over the bare `Geometry` marker. Serves the legacy 2D CLI geometries; the real parse/serialize contract lives app-side in `PointCollection`.
- **ixdar.annotations.meshnode**: The mesh-node annotation and its registry processor.
- **ixdar.annotations.scene**: Scene registration: `@SceneAnnotation` over `SceneDrawable`, whose `initGL`/`paintGL` default to throwing rather than being abstract; a scene missing an override compiles and fails at first frame.
- **ixdar.audio**: OpenAL playback for desktop: `AudioSystem` singleton with WAV caching, music loop, SFX, and volume control. Reachable only by reflection from `Canvas3D`; no compile-time dependency from the render path, which keeps OpenAL out of the web build. Observability accessors exist for automation assertions.
- **ixdar.canvas**: Bootstrap layer and root drawable. `IxdarWindow` (desktop main: GL on a dedicated render thread, GLFW polling on the main thread, headless mode via `ixdar.headless`), `WebLauncher` (TeaVM main), `Canvas3D` (base of every scene), `CanvasSceneMap` (registry plus two hand-registered ids).
- **ixdar.common**: Container for `exceptions` and `utils`.
- **ixdar.common.exceptions**: Exception types, dominated by the balancer family: `SegmentBalanceException` carries cut/match diagnostic state renderable as a `HyperString`; subclasses inherit it via copy constructors. Not domain-neutral; imports the TSP geometry and render packages. `InvalidMeshTopologyException` is the lone unchecked type.
- **ixdar.common.utils**: Small static helpers. Only `Compat` is general (null-safe string shims and an `fmaf` fallback that rounds twice; not IEEE fused); `Utils` and `RunListUtils` are knot-algorithm helpers. `StringBuff` is a depth-tagged debug trace.
- **ixdar.documentation**: Repo documentation tooling. `SpliceArchitectureDoc` runs at every compile and splices all `package-info.java` descriptions into the root `ARCHITECTURE.md` between markers, failing the build when the markers are missing.
- **ixdar.game**: Trade-game domain model: `City`, `Road`, and `CityNetwork` (all possible routes, distinct from the player's `Knot` trade routes). Model classes carry their own drawing code. `IrregularQuadLayoutGenerator` (Townscaper-style quad layouts) lives here but belongs with geometry.
- **ixdar.geometry**: Split point between two disjoint bodies of geometry code: `mesh` (the current node-graph stack) and the TSP lineage (`cuts`, `knot`, `point`, `shell`). Verified: nothing under `mesh` imports the TSP packages.
- **ixdar.geometry.cuts**: TSP knot-resolution bookkeeping: `CutMatchList` accumulates cut/match pairs with delta cost, `BalanceMap` polices segment balance, `DisjointUnionSets` prevents multi-cycle joins. Mutually recursive with `knot` and `shell`; reaches into rendering for debug drawing.
- **ixdar.geometry.cuts.engines**: `KnotEngine`: the growth/merge loop from singleton knots to one knot. Its design notes file documents known-incomplete behavior.
- **ixdar.geometry.cuts.enums**: `Group` (left/right/none) and `RouteType`, whose opposite-route wiring is assigned in static initializers, not visible on the constants.
- **ixdar.geometry.knot**: The recursive knot structure (`Knot` is a circular route of knots bottoming out at points) plus undo/redo records for route planning. Fused to the 2D renderer: `Knot extends SDFCircle`, `Segment extends SDFLine`, both import `MainScene`.
- **ixdar.geometry.mesh**: The mesh subsystem root: node-graph DSL, mesh data structures, and the quad-layout pipeline. Subpackages hold the parts; this level has shared engines like subdivision and boolean backends.
- **ixdar.geometry.mesh.csg**: CSG support code: the Manifold-backed boolean backend (FFM bindings) and quad triangulation of boolean output.
- **ixdar.geometry.mesh.curve**: Curve geometry helpers shared by curve nodes.
- **ixdar.geometry.mesh.data**: Mesh-adjacent data contracts: `GeometryBundle` (mesh plus named slots), `EdgeKey` packing, `EdgeMarks`, `CurveGeometry`, decomposition and feature-detection code (Morse-Smale, crest lines, semantic patches). The conventions in ARCHITECTURE.md's constraints section are enforced from here.
- **ixdar.geometry.mesh.data.load**: Mesh file loading (`MeshLoader` and formats). Desktop-only in practice: uses `java.nio.file`, which throws in the browser.
- **ixdar.geometry.mesh.data.ops**: Representation-preserving mesh operations (delete vertices/edges, merge by distance, vertex offset). Each branches on the concrete mesh type and returns the input's kind.
- **ixdar.geometry.mesh.data.representation**: The two mesh representations. `HalfEdgeMesh`: mutable topology with vertex/edge/face/half-edge adjacency, active flags for deletion without array shifts. `ArrayMesh`: dense position and index arrays for GPU submission. Engines convert between them; `MeshTopology` is the shared interface.
- **ixdar.geometry.mesh.documentation**: Mesh-node catalog generation: `MeshNodeCatalog` exports all node metadata to the checked-in JSON every compile (sorted, byte-stable); DSL validation entry points.
- **ixdar.geometry.mesh.graph**: Node-graph runtime: `NodeGraphRuntime` parses and executes .dsl sources against the generated registries, with the `Class.forName` desktop firewall, per-node timing (`logTimings`), and `GraphAnalyzer` introspection.
- **ixdar.geometry.mesh.nodes**: The ~95 DSL mesh nodes, organized by category subpackage. Every node follows the `GridMeshNode` shape: public static port constants with inline name literals, `socketDocs` keyed by `PORT.name`, documentation enforced by `MeshNodeRegistryTest`.
- **ixdar.geometry.mesh.nodes.api**: The mesh-node contract surface: `MeshNode`, `InputPort`/`OutputPort`/`PortType`, typed fields, `NodeContext`, `MapNodeContext`, `CanonicalPortNames`, and the port value interfaces. Moved from the annotations module so port types can reference app classes; only the annotation and its processor stay there.
- **ixdar.geometry.mesh.nodes.closure**: Nodes wrapping reusable sub-graph closures defined in the DSL (`FunctionDef` support).
- **ixdar.geometry.mesh.nodes.control**: Graph control-flow nodes: switches and selection between inputs by index or condition.
- **ixdar.geometry.mesh.nodes.curve**: Curve nodes: primitives (circle, bezier, function), conversion to and from meshes, sweep, loft, resample, deform. Curves travel between nodes as `CurveGeometry` under `CurveGeometry.SLOT`.
- **ixdar.geometry.mesh.nodes.data**: Data-attachment nodes: tagging geometry (`TagGeometryNode` owns the `_tags` slot), named attributes, and inputs that surface external data to a graph.
- **ixdar.geometry.mesh.nodes.geometry**: Geometry-level operations: mesh boolean (desktop-only, Manifold FFM backend; owns the provenance slots), join, transform-level ops on whole bundles.
- **ixdar.geometry.mesh.nodes.math**: Scalar, vector, and field math nodes plus `FieldBroadcast`, which resolves an input that may be a constant or a per-element field.
- **ixdar.geometry.mesh.nodes.modifier**: Mesh modifiers: subdivide, extrude, inset, solidify, mirror, loop cut, bridge loops, instance-on- points, mark_edges. Nodes needing dense arrays coerce via `ArrayMeshEngine.fromUniformMeshTopology`.
- **ixdar.geometry.mesh.nodes.network**: Network authoring nodes: an arc network over a carrier mesh, its nodes, arcs and patches selected geometrically, traced interior left of walk.
- **ixdar.geometry.mesh.nodes.patch**: Coons-patch machinery: patch fill, extrude and inset with bezier handle preservation (`CoonsHandleBuilder`, handle slots owned by `AssignBezierHandlesNode`).
- **ixdar.geometry.mesh.nodes.primitives**: Primitive generators: cube, grid, icosphere, UV sphere, cylinder, cone, disk, torus, segments. `GridMeshNode` is the reference node shape and exposes static helpers (`triangulated`, `vertexId`, `rowCoordinate`) that tests build fixture geometry with.
- **ixdar.geometry.mesh.nodes.quadlayout**: Graph nodes exposing the quad-layout pipeline stages as independent capabilities: cross field, seamless parametrization, and motorcycle-graph tracing, with more stages to follow per the 7.2 migration. All desktop-only; the stages lean on native solver backends the web platform refuses.
- **ixdar.geometry.mesh.nodes.selection**: Selection nodes producing per-element masks consumed by modifiers.
- **ixdar.geometry.mesh.nodes.transform**: Transform nodes: translate/rotate/scale geometry, align rotation to vector, instancing transforms.
- **ixdar.geometry.mesh.quadlayout**: Desktop-only quad-layout pipeline, cross field to quantized quad layout (BZK09/LCBK19 lineage). Slated for integration into the node system; until then it is reached from scenes, not from DSL graphs.
- **ixdar.geometry.mesh.quadlayout.crossfield**: Cross-field computation: per-face 4-direction fields, singularity detection, `NDirectionField` smoothing.
- **ixdar.geometry.mesh.quadlayout.crossfield.constraint**: Constraint sources pinning cross-field directions: boundary edges, sharp creases, principal curvature, and the gauge anchor.
- **ixdar.geometry.mesh.quadlayout.embedding**: Re-embedding of the quantized layout on a refined working copy of the mesh: arc routing, rerouting, carving, collapse operators, and arrangement diagnostics.
- **ixdar.geometry.mesh.quadlayout.embedding.records**: Value types for the embedding: `ArcNetwork`, arcs, nodes, patches, topology with owner-arc claims per copy edge.
- **ixdar.geometry.mesh.quadlayout.extraction**: Extraction of the final quad mesh and per-patch grids from the embedded layout, plus Coons surfaces for rendering.
- **ixdar.geometry.mesh.quadlayout.gridmap**: Per-patch integer grid maps: rectangle parametrizations (Tutte-style), patch regions, and the grid-map optimizer (60s time budget).
- **ixdar.geometry.mesh.quadlayout.motorcycle**: Motorcycle graph tracing over the parametrization: iso-line traces from singularities partition the surface into a T-mesh.
- **ixdar.geometry.mesh.quadlayout.motorcycle.records**: Value types for the motorcycle graph: nodes, traces, patch boundaries.
- **ixdar.geometry.mesh.quadlayout.quantization**: Quantization of T-mesh arc lengths to integers, deciding which arcs collapse to zero.
- **ixdar.geometry.mesh.quadlayout.seamless**: Seamless global parametrization built over the cross field: per-corner (u,v) with matched transitions across cut edges.
- **ixdar.geometry.mesh.quadlayout.seamless.exact**: Exact-arithmetic predicates for the seamless parametrization's degenerate cases.
- **ixdar.geometry.mesh.quadlayout.solver**: Sparse linear algebra for the pipeline: normal matrices, AMD ordering, Cholesky backends (MKL when present, EJML fallback on macOS).
- **ixdar.geometry.mesh.quadlayout.solver.system**: The DOF-system state and the solve strategies decoupled from the stacks: `DofSystem` holds the canonical solution/frozen state plus assembly, energy, and write-back hooks; each outer loop (single solve, Newton, greedy rounding, lazy constraints, power iteration) is its own strategy class.
- **ixdar.geometry.point**: Shared point primitives (`PointND`, `Point2D`, `PointSet`) with wide live usage across cameras, scenes, and automation, plus the terminal-addable CLI geometries registered via `@GeometryAnnotation`. Not TSP-free: `Grid` and `PointSet` import knot and shell.
- **ixdar.geometry.shell**: `Shell`: a point list that starts as a convex hull and merges toward a TSP path, with the cached `DistanceMatrix` (Apache commons-math eigendecomposition). Constructing a `Shell` eagerly builds a `KnotEngine`.
- **ixdar.graphics**: Namespace root for `cameras` and `render`.
- **ixdar.graphics.cameras**: `Camera2D` (pan/scale, zoom-to-fit) and `Camera3D` (first-person plus orbit) behind a 2D-shaped `Camera` interface; several `Camera3D` methods throw. `Bounds` is the viewport rectangle with a resize recalculator.
- **ixdar.graphics.render**: Shared render primitives: `Clock` (process-global static time, frame deltas, oscillation; the animation driver for shaders) and `Texture` (supports a deferred placeholder mode filled in by async platform loading).
- **ixdar.graphics.render.color**: `Color` interface with the named palette, `ColorRGB`, and animated lerps driven by the static `Clock`. `PatchColorHash` mirrors the GLSL `patchColor()` hash; but surface fill and layout overlay hash different id spaces, so a shared palette does not mean matching colors.
- **ixdar.graphics.render.lights**: Directional, point, and spot lights that push uniforms into a bound shader. Point and spot share a public attenuation lookup table.
- **ixdar.graphics.render.model**: Mesh rendering runtimes. `HalfEdgeMeshRuntime` uploads compiled meshes and draws them with tag partitioning, scalar heat maps, feature-edge overlays, and wireframe. `QuadLayoutRuntime` extends it with quad-layout overlays: port-typed values become a `LineSet`, a `PointSet`, or a corner array, uploaded through `VertexBuffer` per `VertexLayout`. `AssimpModelRuntime` renders loaded models.
- **ixdar.graphics.render.sdf**: Signed-distance-field drawables, the editor's 2D drawing vocabulary: `ShaderDrawable` base, `SDFLine` variants, circles, textures (MSDF). `SDFUnion` imports LWJGL directly; the one GL- abstraction violation in `graphics` outside `render/model`.
- **ixdar.graphics.render.shaders**: Shader compilation and GL object wrappers. `ShaderProgram.ShaderType` is the central registry mapping every logical shader to its class and `.vs`/`.fs` pair; subclasses differ mainly in vertex stride and attribute layout. Sources pass through `GlslSource` for the dialect rewrite.
- **ixdar.graphics.render.text**: MSDF font atlas loading and `HyperString`, the colored, wrappable, hoverable, clickable rich-text model used for all editor UI text. Each glyph is an SDF drawable. Private-Use-Area code points carry non-font glyphs.
- **ixdar.gui**: Front-end container: `terminal` (text REPL) and `ui` (drawn widgets).
- **ixdar.gui.terminal**: The in-app REPL. `Terminal` owns command/tool registries, dispatches typed lines, renders scrollable history. Commands come from the annotation-generated registry via `CommandMap`. `Terminal.current` is a mutable static that scenes reassign to route keyboard input.
- **ixdar.gui.terminal.commands**: One class per terminal verb, each extending `TerminalCommand` with `@CommandAnnotation`. Commands reach into scene singletons and `FileManagement`, so they are not testable in isolation.
- **ixdar.gui.ui**: `Drawing`: static low-level 2D overlay drawing (segments, circles, paths, knot diagrams), cached per platform id. Its statics (thickness, font size) are retuned at runtime with global visual effect.
- **ixdar.gui.ui.actions**: Command-pattern adapters between menu rows and effects (`Action.perform()`): screen switches, `.ix` loading, map editor. Mostly delegates to terminal commands; `StartNewGameAction` carries real game-setup logic.
- **ixdar.gui.ui.code**: The live GLSL editor view: `ShaderCodePane` (scrollable pane), `GLSLColorizer` (highlighting), `ShaderBranchInjector` (rewrites shader source to inject debug preview writes). Evaluation lives in `ixdar.parsing.glsl`.
- **ixdar.gui.ui.menu**: Two coexisting menu kinds: the `Menu`/`MenuBox`/`MenuItem` screen-stack system, and `SceneModelMenu`, an immediate-mode ESC panel rebuilt every frame for live highlights and clickable regions.
- **ixdar.gui.ui.tools**: Mode-like editor tools subclassing `Tool`: free inspection, map editing, diagnostic overlays, plus two trade-game tools (`HeadquartersPickerTool`, `RoutePlanningTool`) that depend on the game model instead of the knot editor. `Tool.draw` throws unless overridden.
- **ixdar.parsing**: Container for the two parsers: `glsl` and `python`.
- **ixdar.parsing.glsl**: GLSL tokenizer/interpreter feeding the live shader editor; uniforms, declarations, branches, built-in constants. An interpreter for the debug UI, not a compiler; depends on the color and text render packages.
- **ixdar.parsing.python**: Lexer and recursive-descent parser for the Python-flavored mesh DSL, producing the AST `NodeGraphRuntime` executes. Deliberately lenient: unknown characters are skipped so LLM- generated DSL stays parseable; malformed input degrades silently.
- **ixdar.platform**: Portability root: `Platforms` is the process-global registry (`init`, `get()`, `gl()`, `switchTo` for multi-canvas web). `Toggle` is the app-wide feature-flag enum, here only because everything reaches for it.
- **ixdar.platform.automation**: The in-process HTTP automation server (127.0.0.1:47832, `ixdar.automation.port` to override) that lets `ixdar-cli` and agents drive the editor. Routes come from the annotation-generated registry. Desktop-only (`com.sun.net.httpserver`, `javax.imageio`). `AutomationInputBinder` tees platform input callbacks into the recorder.
- **ixdar.platform.automation.documentation**: `ExportAutomationRoutes` writes the route manifest JSON every compile; the checked-in `automation_routes.json` is generated output and the single source of truth for the Python CLI.
- **ixdar.platform.automation.endpoints**: `AutomationRuntime`: the singleton owning the canvas, recorder, replay engine, and the render- thread marshalling every endpoint uses (anything touching GL goes through its callable queue). Plus `/health` and `/shutdown`.
- **ixdar.platform.automation.endpoints.input**: Synthesized input routes (`/input/click`, `/input/key`, `/input/type`, scroll, hover) that feed the active `MouseTrap`/`KeyGuy` directly, bypassing the OS event source.
- **ixdar.platform.automation.endpoints.mesh**: Geometry inspection against the active viewer scene: `/mesh/fingerprint`, `/mesh/compare`, segmentation and overlay routes.
- **ixdar.platform.automation.endpoints.mesh.dsl**: DSL routes: load a graph (`/mesh/dsl`), validate it, and report per-node timing for the latest run.
- **ixdar.platform.automation.endpoints.mesh.patches**: Quad-patch decomposition routes and multiview renders of the result (default resolution 128, caller-supplied output path).
- **ixdar.platform.automation.endpoints.mesh.skeleton**: Skeleton comparison against a reference mesh and sensitivity of the score to DSL parameter perturbation. Relative paths resolve against the working directory.
- **ixdar.platform.automation.endpoints.record**: Recording routes: start/stop/status for capturing raw input events to a JSON file.
- **ixdar.platform.automation.endpoints.replay**: Replay routes: play a recording back through the live handlers on a dedicated thread. Cancel takes effect at the next event boundary, not immediately.
- **ixdar.platform.automation.endpoints.ui**: View observation and posing: screenshots (single and multiview), orbit and projection get/set (paired classes sharing a path, differing by HTTP method), and the full UI state dump.
- **ixdar.platform.concurrent**: The fan-out/join seam: `WorkerPool` with a threaded desktop implementation and an inline web one. `ThreadWorkerPool` is deliberately the only class in the codebase naming `java.util.concurrent` executors; a second reference elsewhere breaks the web build.
- **ixdar.platform.file**: The `.ix` solution file format: `FileManagement` imports/exports point sets, TSP paths, distance matrices, and grids (comments preserved), delegating raw I/O to the `Platform`. Domain file- format code despite the package name. Asset repo resolves via `IXDAR_ASSET_REPO_ROOT`.
- **ixdar.platform.gl**: The core portability seam: `GL` (the minimum GL surface, enum values as methods since numerics differ per backend) and `Platform` (windowing, assets, and the injection point for native backends: Cholesky, integer programs, mesh booleans). `IxBuffer` abstracts buffers; `GlslSource` rewrites ES 300 shaders to core 330.
- **ixdar.platform.gl.headless**: CI/test implementation: an invisible GLFW window provides a real offscreen GL 3.3 core context. Works on macOS; Linux needs a display server such as Xvfb.
- **ixdar.platform.gl.lwjgl**: Desktop implementation: GLFW window, real OpenGL, direct NIO buffers, `java.nio.file` I/O.
- **ixdar.platform.gl.web**: TeaVM implementation: `WebGLRenderingContext` on an HTML canvas, async-only resource loading, `InlineWorkerPool`, `WebBuffer.flip()` a deliberate no-op.
- **ixdar.platform.input**: Input handling: GLFW-numbered `Keys` (the codebase-wide canonical key encoding; the web backend translates into it), semantic `KeyActions`, and the `KeyGuy`/`MouseTrap` handler hierarchy. The base classes are fused to `MainScene`; the orbit/trade subclasses exist to strip that back out.
- **ixdar.platform.json**: The platform-neutral JSON tree `Platform.parseJson` returns. {@link ixdar.platform.json.JsonValue} is the only type callers touch; {@link ixdar.platform.json.GsonJsonTree} builds it for the desktop platforms, and the web platform builds the same shape from the browser's `JSON.parse`.
- **ixdar.platform.teavm**: TeaVM bytecode transformers registered in the `web-teavm` profile: `WebMathTransformer` supplies the missing `Math.fma` (true fused multiply-add via BigDecimal; two roundings would break the exact orientation predicates), `JomlUnsafeTransformer` drops JOML's `sun.misc.Unsafe` path.
- **ixdar.procgen**: Namespace root for procedural content generation; all code lives under `dungeon`.
- **ixdar.procgen.dungeon**: Vazgriz-style dungeon pipeline in 2D and 3D: place rooms, Delaunay-triangulate, MST plus loop edges, A* corridors, tile grid to mesh; exposed as DSL nodes; plus the runtime to walk the result (capsule physics, player controller, cameras, viewer scene).
- **ixdar.procgen.dungeon.algo**: Headless generation algorithms, no rendering or scene dependencies: room placers (may return fewer rooms than asked), Bowyer-Watson Delaunay, Prim MST with probabilistic loop edges, A* corridors (3D adds single-floor stair moves), grid-to-mesh with inward-wound hollow rooms, and `DungeonGrids`, the builders/readers for the pipeline's geometry-plus-attribute shapes.
- **ixdar.procgen.dungeon.camera**: `ThirdPersonCamera` (orbit above the player, writes back into the shared `Camera3D`) and `CameraGridSweep` (sphere-cast that hard-stops at the first obstacle cell).
- **ixdar.procgen.dungeon.nodes**: Thin `MeshNode` adapters wrapping each algorithm stage for the DSL, all in the `dungeon` scope. Rooms, Delaunay, and MST are dimension-neutral single nodes over point geometry; corridors and grid-to-mesh keep 2D/3D ids because stairs and vertical adjacency have no 2D analog.
- **ixdar.procgen.dungeon.physics**: Minimal dungeon collision: `CapsuleShape`, `AabbBox`, MTV separation, and `CapsuleMover` (sub- stepped move-and-slide over the tile grid). Must agree with `GridToMesh3D` on cell size and the origin-centered convention; `EMPTY` and out-of-grid are the obstacles.
- **ixdar.procgen.dungeon.player**: `PlayerController` (WASD, gravity, yaw-relative motion; mouse-look belongs to the mouse handler), `PlayerSpawner` (relies on room[0] being the start room), `SpawnPoint`.
- **ixdar.procgen.dungeon.render**: `DebugCapsuleRuntime`: builds the player capsule mesh once, writes only a model matrix per frame into a wrapped `HalfEdgeMeshRuntime`.
- **ixdar.procgen.dungeon.scene**: Viewer wiring: `DungeonViewerScene` (F toggles fly-cam vs player-walk, V toggles person view; only 3D dungeons support player mode since the DSL mesh feeds both renderer and collision), `DungeonKeyGuy`, `FlyCamMouseTrap`.
- **ixdar.procgen.dungeon.values**: `CellType`, the named constants behind the per-cell `cell_type` int attribute the corridor and grid-to-mesh stages share. Dungeon data itself flows as geometry bundles with attributes.
- **ixdar.scenes**: Scene layer root. `Scene` extends `Canvas3D`; `ModelScene` adds mesh viewing (framing, orbit preservation, model catalog, direct input binding). Scenes register via `@SceneAnnotation` into the generated scene registry.
- **ixdar.scenes.anatomy**: Anatomy visualization scenes built on the point/knot lineage.
- **ixdar.scenes.main**: `MainScene`: the 2D TSP editor scene; knots, shells, terminal, tools. The legacy lineage's user surface.
- **ixdar.scenes.mesh**: Mesh-centric scenes: the node viewer (`MeshNodeViewerScene`, also the web entry scene) and `MeshBooleanScene`. Both execute .dsl graphs and log node timings.
- **ixdar.scenes.model**: Model viewing support: `ModelScene` base class, `ModelCatalog` with `quadLayout` and `staging` factories, `ModelChoice` and its `Kind`.
- **ixdar.scenes.trade**: `TradeScene`: the trade-game scene over `CityNetwork`, with its own input handlers.
<!-- package-map:end -->
