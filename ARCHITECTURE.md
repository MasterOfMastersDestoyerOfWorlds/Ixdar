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
implementations; an HTTP automation server (a free loopback port, published in a per-process record
`tmp/automation/<pid>.json`) drives headless runs, screenshots, and
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
firewall. Every node declares its `category` (a `NodeCategory` constant, required) on the
annotation; `MeshNodeCatalog` lists it under that constant's display name, whatever its package.

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
(`setFeatureEdgeOverlay`). Check those before writing new GL plumbing. `MeshOverlayRuntime` now
takes only general values (mesh, `UvField`, `ArcNetwork`, `CrossField`, patch surfaces, point
clouds, coloured line groups, labels at points and polylines), converts each into a `LineSet`, a `PointSet`, or a corner array, and
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

- `MeshOverlayRuntime` overlay draw order moves out of the runtime so scenes decide what draws
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
- **ixdar.annotations.tool**: Tool registration: `@ToolAnnotation` names a tool and the scene type that hosts it, and `ToolRegistry` generates `ToolRegistry_Tools`, a factory per tool that builds it for its host.
- **ixdar.audio**: OpenAL playback for desktop: `AudioSystem` singleton with WAV caching, music loop, SFX, and volume control. Reached only through `Platform.audio()`, which the browser platform answers with null, so OpenAL stays out of the web build. Observability accessors exist for automation assertions.
- **ixdar.automation**: The loopback HTTP automation server that lets `ixdar-cli` and agents drive the editor, and `AutomationRuntime`, which owns it and marshals endpoint work onto the render thread. Desktop only: the browser platform's `Platform.automation()` answers null.
- **ixdar.automation.documentation**: `AutomationRouteCatalog` builds the route manifest JSON that `ixdar.devtools.ExportAutomationRoutes` writes every compile; the checked-in `automation_routes.json` is the single source of truth for the Python CLI.
- **ixdar.automation.endpoints**: The route classes, grouped by subject; `/health` and `/shutdown` sit at this root.
- **ixdar.automation.endpoints.input**: Synthesized input routes (`/input/click`, `/input/key`, `/input/type`, scroll, hover) that feed the active `MouseTrap`/`KeyGuy` directly, bypassing the OS event source.
- **ixdar.automation.endpoints.mesh**: Geometry inspection against the active viewer scene: `/mesh/fingerprint`, `/mesh/compare`, segmentation and overlay routes.
- **ixdar.automation.endpoints.mesh.dsl**: DSL routes: load a graph (`/mesh/dsl`), validate it, and report per-node timing for the latest run.
- **ixdar.automation.endpoints.mesh.patches**: Quad-patch decomposition routes and multiview renders of the result (default resolution 128, caller-supplied output path).
- **ixdar.automation.endpoints.mesh.skeleton**: Skeleton comparison against a reference mesh and sensitivity of the score to DSL parameter perturbation. Relative paths resolve against the working directory.
- **ixdar.automation.endpoints.record**: Recording routes: start/stop/status for capturing raw input events to a JSON file.
- **ixdar.automation.endpoints.replay**: Replay routes: play a recording back through the live handlers on a dedicated thread. Cancel takes effect at the next event boundary, not immediately.
- **ixdar.automation.endpoints.ring**: The ring-tool routes, URL paths unchanged: neck-ring listing, ring authoring and bisection, and the region, connection, pick and project views. Authoring needs the `ring-tool` scene.
- **ixdar.automation.endpoints.scene**: Scene-level control: `/scene/model` switches the active model scene's model and waits for the recompute to finish.
- **ixdar.automation.endpoints.ui**: View observation and posing: screenshots (single and multiview), orbit and projection get/set (paired classes sharing a path, differing by HTTP method), and the full UI state dump.
- **ixdar.canvas**: Bootstrap layer. `IxdarWindow` (desktop main: GL on a dedicated render thread, GLFW polling on the main thread, headless mode via `ixdar.headless`), `WebLauncher` (TeaVM main) and `CanvasSceneMap` (the scene registry plus the hand-registered `ixdar-canvas` id).
- **ixdar.devtools**: Build-time exporters and developer command lines (named apart from the edit-tool `tools`). `ExportAutomationRoutes` and `SpliceArchitectureDoc` run every compile; `ValidateDsl` and `PatchDecomposerCLI` run by hand.
- **ixdar.game**: Trade-game domain model: `City`, `Road`, and `CityNetwork` (all possible routes, distinct from the player's `Knot` trade routes). Model classes carry their own drawing code.
- **ixdar.geometry**: Split point between disjoint bodies of geometry code: `mesh` (the current node-graph stack), `tsp` (the knot/cuts/shell lineage and its `.ix` file format) and `grid2d` (the 2D grids). Nothing under `mesh` imports `tsp`.
- **ixdar.geometry.grid2d**: 2D grids: `Grid` (cartesian and hex, which decide the point types a `.ix` file may hold), `IrregularQuadGrid`, and `IrregularQuadLayoutGenerator` (Townscaper-style quad layouts). The two irregular grids are still separate classes.
- **ixdar.geometry.mesh**: The mesh subsystem root: mesh nodes, mesh data structures, and the quad-layout pipeline. Subpackages hold the parts; this level has shared engines like subdivision and boolean backends.
- **ixdar.geometry.mesh.csg**: CSG support code: the Manifold-backed boolean backend (FFM bindings) and quad triangulation of boolean output.
- **ixdar.geometry.mesh.data**: Mesh data and the algorithms over it, one sub-package per concern; no class lives at this root. The conventions in ARCHITECTURE.md's constraints section are enforced from here.
- **ixdar.geometry.mesh.data.analysis**: Mesh analysis: `MeshDistance` compares two meshes, and the sub-packages segment a surface, extract its skeleton and measure its curvature.
- **ixdar.geometry.mesh.data.analysis.curvature**: Curvature features of a surface: principal directions, crest lines, saddle points and the axis a limb runs along.
- **ixdar.geometry.mesh.data.analysis.segmentation**: Surface segmentation into patches: Morse-Smale and discrete-gradient decompositions, semantic patches, clustering segmenters, and the patch boundaries and colours they report.
- **ixdar.geometry.mesh.data.analysis.skeleton**: TEASAR curve skeletons of a mesh and the comparison of a generated skeleton against a reference.
- **ixdar.geometry.mesh.data.bundle**: `GeometryBundle`, a mesh plus named slots, and the slot payloads nodes pass along with it: edge marks, rings, curves, corner UVs and materials. `SlotCarry` copies slots across a topology change.
- **ixdar.geometry.mesh.data.fields.crossfield**: Cross-field computation: per-face 4-direction fields, singularity detection, `NDirectionField` smoothing.
- **ixdar.geometry.mesh.data.fields.crossfield.constraint**: Constraint sources pinning cross-field directions: boundary edges, sharp creases, principal curvature, and the gauge anchor.
- **ixdar.geometry.mesh.data.io**: Mesh file loading (`MeshLoader` and formats). Desktop-only in practice: uses `java.nio.file`, which throws in the browser.
- **ixdar.geometry.mesh.data.ops**: Representation-preserving mesh operations (delete vertices/edges, merge by distance, vertex offset). Each branches on the concrete mesh type and returns the input's kind.
- **ixdar.geometry.mesh.data.patches**: Parametric surface patches: Bezier fitting, Coons and Charrot-Gregory evaluators, their handle construction and the reconstruction error of a fit.
- **ixdar.geometry.mesh.data.paths.geodesic**: Geodesics on a surface: the intrinsic metric and triangulation, FlipOut shortening, shortest edge paths, and tracing an intrinsic path back onto the mesh.
- **ixdar.geometry.mesh.data.paths.query**: Spatial queries on a surface: ray picks, nearest vertices, plane cuts, path crossings, groove costs and the connection between two faces.
- **ixdar.geometry.mesh.data.paths.spline**: Cubic splines on a surface: authored waypoints, anchor fitting and tracing a spline through its anchors with geodesic midpoints.
- **ixdar.geometry.mesh.data.representation**: The two mesh representations, `HalfEdgeMesh` (mutable adjacency) and `ArrayMesh` (dense GPU arrays), their engines, the shared `MeshTopology` interface and the compiled form the renderer uploads, plus edge keys, triangle measurements and the unchecked `InvalidMeshTopologyException`.
- **ixdar.geometry.mesh.data.rings**: Rings around a surface: candidate extraction, authored and cross-section rings, the regions rings cut a surface into, and the exploded view of those regions.
- **ixdar.geometry.mesh.nodes**: The ~95 DSL mesh nodes, organized by category subpackage. Every node follows the `GridMeshNode` shape: public static port constants with inline name literals, `socketDocs` keyed by `PORT.name`, documentation enforced by `MeshNodeRegistryTest`.
- **ixdar.geometry.mesh.nodes.curve**: Curve nodes: primitives (circle, bezier, function), conversion to and from meshes, sweep, loft, resample, deform. Curves travel between nodes as `CurveGeometry` under `CurveGeometry.SLOT`.
- **ixdar.geometry.mesh.nodes.data**: Data-attachment nodes: tagging geometry (`TagGeometryNode` owns the `_tags` slot), named attributes, and inputs that surface external data to a graph.
- **ixdar.geometry.mesh.nodes.geometry**: Geometry-level operations: mesh boolean (desktop-only, Manifold FFM backend; owns the provenance slots), join, transform-level ops on whole bundles.
- **ixdar.geometry.mesh.nodes.modifier**: Mesh modifiers: subdivide, extrude, inset, solidify, mirror, loop cut, bridge loops, instance-on- points, mark_edges. Nodes needing dense arrays coerce via `ArrayMeshEngine.fromUniformMeshTopology`.
- **ixdar.geometry.mesh.nodes.patch**: Coons-patch nodes: patch fill, extrude and inset with bezier handle preservation (handles built by `ixdar.geometry.mesh.data.patches.CoonsHandleBuilder`, slots owned by `AssignBezierHandlesNode`).
- **ixdar.geometry.mesh.nodes.primitives**: Primitive generators: cube, grid, icosphere, UV sphere, cylinder, cone, disk, torus, segments. `GridMeshNode` is the reference node shape and exposes static helpers (`triangulated`, `vertexId`, `rowCoordinate`) that tests build fixture geometry with.
- **ixdar.geometry.mesh.nodes.selection**: Selection nodes producing per-element masks consumed by modifiers.
- **ixdar.geometry.mesh.nodes.transform**: Transform nodes: translate/rotate/scale geometry, align rotation to vector, instancing transforms.
- **ixdar.geometry.mesh.quadlayout**: Desktop-only quad-layout pipeline, cross field to quantized quad layout (BZK09/LCBK19 lineage). Slated for integration into the node system; until then it is reached from scenes, not from DSL graphs.
- **ixdar.geometry.mesh.quadlayout.embedding**: Re-embedding of the quantized layout on a refined working copy of the mesh: arc routing, rerouting, carving, collapse operators, and arrangement diagnostics.
- **ixdar.geometry.mesh.quadlayout.embedding.model**: Value types for the embedding: `ArcNetwork`, arcs, nodes, patches, topology with owner-arc claims per copy edge.
- **ixdar.geometry.mesh.quadlayout.extraction**: Extraction of the final quad mesh and per-patch grids from the embedded layout, plus Coons surfaces for rendering.
- **ixdar.geometry.mesh.quadlayout.gridmap**: Per-patch integer grid maps: rectangle parametrizations (Tutte-style), patch regions, and the grid-map optimizer (60s time budget).
- **ixdar.geometry.mesh.quadlayout.motorcycle**: Motorcycle graph tracing over the parametrization: iso-line traces from singularities partition the surface into a T-mesh.
- **ixdar.geometry.mesh.quadlayout.motorcycle.model**: Value types for the motorcycle graph: nodes, traces, patch boundaries.
- **ixdar.geometry.mesh.quadlayout.nodes**: Desktop-only graph nodes over the quad-layout pipeline: its stages as independent capabilities, and the arc-network authoring nodes (network, nodes, arcs, patches).
- **ixdar.geometry.mesh.quadlayout.quantization**: Quantization of T-mesh arc lengths to integers, deciding which arcs collapse to zero.
- **ixdar.geometry.mesh.quadlayout.seamless**: Seamless global parametrization built over the cross field: per-corner (u,v) with matched transitions across cut edges.
- **ixdar.geometry.mesh.quadlayout.seamless.exact**: Exact-arithmetic predicates for the seamless parametrization's degenerate cases.
- **ixdar.geometry.tsp**: The TSP lineage, unrelated to the mesh code: `knot`, `cuts`, `shell`, `points` and the terminal-addable `input` primitives. This level holds `PointSetPath` with the `.ix` solution file format, `FileStringable`, and the TSP-only exceptions, dominated by `SegmentBalanceException`.
- **ixdar.geometry.tsp.cuts**: TSP knot-resolution bookkeeping: `CutMatchList` accumulates cut/match pairs with delta cost, `BalanceMap` polices segment balance, `DisjointUnionSets` prevents multi-cycle joins. Mutually recursive with `knot` and `shell`; reaches into rendering for debug drawing.
- **ixdar.geometry.tsp.cuts.engines**: `KnotEngine`: the growth/merge loop from singleton knots to one knot. Its design notes file documents known-incomplete behavior.
- **ixdar.geometry.tsp.cuts.enums**: `Group` (left/right/none) and `RouteType`, whose opposite-route wiring is assigned in static initializers, not visible on the constants.
- **ixdar.geometry.tsp.input**: The geometries the terminal `add` command and `.ix` files place as point batches: `Arc`, `Circle`, `Line`, `Triangle`, nested `Ix` loads, their `PointCollection` base, and `GeometryMap`, the `@GeometryAnnotation` registry keyed by option id.
- **ixdar.geometry.tsp.knot**: The recursive knot structure (`Knot` is a circular route of knots bottoming out at points) plus undo/redo records for route planning. Fused to the 2D renderer: `Knot extends SDFCircle`, `Segment extends SDFLine`, both import `MainScene`.
- **ixdar.geometry.tsp.points**: The TSP point primitives: `PointND` (cartesian and hex variants, drawn as SDF circles) and `PointSet`. Not TSP-free: `PointSet` imports knot and shell.
- **ixdar.geometry.tsp.shell**: `Shell`: a point list that starts as a convex hull and merges toward a TSP path, with the cached `DistanceMatrix` (Apache commons-math eigendecomposition). Constructing a `Shell` eagerly builds a `KnotEngine`. `StringBuff` is the depth-tagged trace a `Shell` keeps.
- **ixdar.graphics**: Namespace root for `cameras` and `render`.
- **ixdar.graphics.cameras**: `Camera2D` (pan/scale, zoom-to-fit) and `Camera3D` (first-person, or an orbit whose state it holds) behind a 2D-shaped `Camera` interface; several `Camera3D` methods throw. `CameraController` is how a camera answers drags, the wheel and held keys: `Camera2D` pans, `Camera3D` orbits, `FlyController` flies. `Bounds` is the viewport rectangle with a resize recalculator.
- **ixdar.graphics.render**: Shared render primitives: `Clock` (process-global static time, frame deltas, oscillation; the animation driver for shaders) and `Texture` (supports a deferred placeholder mode filled in by async platform loading).
- **ixdar.graphics.render.color**: `Color` interface with the named palette, `ColorRGB`, and animated lerps driven by the static `Clock`. `PatchColorHash` mirrors the GLSL `patchColor()` hash; but surface fill and layout overlay hash different id spaces, so a shared palette does not mean matching colors.
- **ixdar.graphics.render.model**: Mesh rendering runtimes. `HalfEdgeMeshRuntime` uploads compiled meshes and draws them with tag partitioning, scalar heat maps, feature-edge overlays, and wireframe. `MeshOverlayRuntime` extends it with general overlays: points, lines, labels, fields and arcs become a `LineSet`, a `PointSet`, or a corner array, uploaded through `VertexBuffer` per `VertexLayout`. `AssimpModelRuntime` renders loaded models.
- **ixdar.graphics.render.sdf**: Signed-distance-field drawables, the editor's 2D drawing vocabulary: `ShaderDrawable` base, `SDFLine` variants, circles, textures (MSDF). `SDFUnion` imports LWJGL directly; the one GL- abstraction violation in `graphics` outside `render/model`.
- **ixdar.graphics.render.shaders**: Shader compilation and GL object wrappers. `ShaderProgram.ShaderType` is the central registry mapping every logical shader to its class and `.vs`/`.fs` pair; subclasses differ mainly in vertex stride and attribute layout. Sources pass through `GlslSource` for the dialect rewrite.
- **ixdar.graphics.render.text**: MSDF font atlas loading, glyphs and colored text runs, drawn for the rich-text model in `ixdar.gui.ui.text`. Each glyph is an SDF drawable. Private-Use-Area code points carry non-font glyphs.
- **ixdar.gui**: Front-end container: `terminal` (text REPL) and `ui` (drawn widgets).
- **ixdar.gui.terminal**: The in-app REPL. `Terminal` owns command/tool registries, dispatches typed lines, renders scrollable history. Commands come from the annotation-generated registry via `CommandMap`. `Terminal.current` is a mutable static that scenes reassign to route keyboard input. `TerminalParseException` reports a malformed command or `.ix` line.
- **ixdar.gui.terminal.commands**: One class per terminal verb, each extending `TerminalCommand` with `@CommandAnnotation`. Commands reach into scene singletons and `FileManagement`, so they are not testable in isolation.
- **ixdar.gui.ui**: `Drawing`: static low-level 2D overlay drawing (segments, circles, paths, knot diagrams), cached per platform id. Its statics (thickness, font size) are retuned at runtime with global visual effect. Also the scene widgets: `ErrorToasts`, the fading refusal messages, and the TSP scene's `InfoPane` with its `PaneTypes`.
- **ixdar.gui.ui.actions**: Command-pattern adapters between menu rows and effects (`Action.perform()`): screen switches, `.ix` loading, map editor. Mostly delegates to terminal commands; `StartNewGameAction` carries real game-setup logic.
- **ixdar.gui.ui.code**: The live GLSL editor view: `ShaderCodePane` (scrollable pane), `GLSLColorizer` (highlighting), `ShaderBranchInjector` (rewrites shader source to inject debug preview writes). Evaluation lives in `glsl`.
- **ixdar.gui.ui.code.glsl**: GLSL tokenizer/interpreter feeding the live shader editor; uniforms, declarations, branches, built-in constants. An interpreter for the debug UI, not a compiler; depends on the color and text render packages.
- **ixdar.gui.ui.menu**: The `Menu`/`MenuBox`/`MenuItem` screen-stack menu system and its scroll boxes and bars. The model scenes' immediate-mode ESC panels are in `ixdar.gui.ui.menu.model`.
- **ixdar.gui.ui.menu.model**: The model scenes' ESC panels: `SceneModelMenu`, an immediate-mode panel rebuilt every frame for live highlights and clickable regions, and `SceneCollectionMenu`, its collection page.
- **ixdar.gui.ui.text**: `HyperString`, the colored, wrappable, hoverable, clickable rich-text model used for all editor UI text, and its `HyperWord` runs, drawn with the MSDF fonts of `ixdar.graphics.render.text`.
- **ixdar.input**: Scene-level input dispatch: the one `KeyGuy` firing a scene's control rows, the one `MouseTrap` driving its camera controller and view, the `PointerDispatcher` offering pointer events to UI regions and the active tool first, and `SceneInputFrameUpdater` pumping them each frame.
- **ixdar.models**: The model catalogue the model scenes switch between: `ModelCatalog` with `quadLayout` and `staging` factories, `ModelChoice` and its `Kind`, `GraphChoice`, model collections with their `CollectionManifest`, and the `ModelLoadLedger` of load outcomes.
- **ixdar.nodegraph**: The DSL node-graph engine, independent of any one domain: node API, fields, runtime, syntax, analysis, parameter optimisation, the node catalog and the generic nodes.
- **ixdar.nodegraph.analysis**: Static checks and introspection of parsed graphs: `GraphValidator` (node types, ports, edge types) and `GraphAnalyzer`.
- **ixdar.nodegraph.api**: The node contract surface: `MeshNode`, `InputPort`/`OutputPort`/`PortType`, `MeshNodeSchema`, `NodeContext`, `MapNodeContext` and `CanonicalPortNames`. Moved from the annotations module so port types can reference app classes; only the annotation and its processor stay there.
- **ixdar.nodegraph.catalog**: Node metadata: `MeshNodeCatalog` exports every node to the checked-in JSON each compile (sorted, byte-stable), and `NodeInspector` walks the graph a model scene ran.
- **ixdar.nodegraph.field**: Per-element fields and their values: the typed `*Field` and `*Value` records, `FieldContext` and its mesh implementations, `FieldBroadcast` for constant-or-field inputs, and `FloatCurveKernel`.
- **ixdar.nodegraph.nodes**: The domain-free DSL nodes: math, control flow and closures. Domain node packs sit with their domain (`ixdar.geometry.mesh.nodes`, `ixdar.geometry.mesh.quadlayout.nodes`, `ixdar.procgen.dungeon.nodes`).
- **ixdar.nodegraph.nodes.closure**: Nodes wrapping reusable sub-graph closures defined in the DSL (`FunctionDef` support).
- **ixdar.nodegraph.nodes.control**: Graph control-flow nodes: switches and selection between inputs by index or condition.
- **ixdar.nodegraph.nodes.math**: Scalar, vector, and field math nodes; inputs that may be a constant or a per-element field resolve through `ixdar.nodegraph.field.FieldBroadcast`.
- **ixdar.nodegraph.optimize**: DSL parameter optimisation: the optimizable parameter descriptors, `SkeletonSensitivityAnalyzer` and `BatchDslEvaluator`.
- **ixdar.nodegraph.runtime**: Node-graph runtime: `NodeGraphRuntime` parses and executes .dsl sources against the generated registries, with the `Class.forName` desktop firewall and per-node timing (`logTimings`); node groups, the group library and the heap sampler.
- **ixdar.nodegraph.syntax**: Lexer and recursive-descent parser for the Python-flavored mesh DSL, producing the AST `NodeGraphRuntime` executes. Deliberately lenient: unknown characters are skipped so LLM- generated DSL stays parseable; malformed input degrades silently.
- **ixdar.nodegraph.syntax.expr**: `MathExpressionEvaluator`: the single-variable math expressions procedural curve nodes evaluate.
- **ixdar.numeric**: General numerics with no stage of the pipeline in them: sparse linear algebra (`sparse`), exact predicates and integer elimination (`exact`), and graph primitives (`graph`).
- **ixdar.numeric.exact**: Exact arithmetic: `ExactArithmetic` (fraction-free integer elimination and fixed-point doubles for the seamless constraints) and `ExactBarycentricOrient` (the filtered, sign-exact orientation predicate).
- **ixdar.numeric.graph**: Graph primitives: `UnionFind` over a caller-owned parent array, and the shared multi-source `Dijkstra` core with the `ShortestPathForest` it returns.
- **ixdar.numeric.sparse**: Sparse linear algebra for the pipeline: normal matrices, AMD ordering, Cholesky backends (MKL when present, EJML fallback on macOS).
- **ixdar.numeric.sparse.system**: The DOF-system state and the solve strategies decoupled from the stacks: `DofSystem` holds the canonical solution/frozen state plus assembly, energy, and write-back hooks; each outer loop (single solve, Newton, greedy rounding, lazy constraints, power iteration) is its own strategy class.
- **ixdar.platform**: Portability root: `Platforms` is the process-global registry (`init`, `get()`, `gl()`, `switchTo` for multi-canvas web). `Toggle` is the app-wide feature-flag enum, here only because everything reaches for it.
- **ixdar.platform.concurrent**: The fan-out/join seam: `WorkerPool` with a threaded desktop implementation and an inline web one. `ThreadWorkerPool` is deliberately the only class in the codebase naming `java.util.concurrent` executors; a second reference elsewhere breaks the web build.
- **ixdar.platform.file**: File access helpers: `FileManagement` resolves solution and asset paths (the asset repo via `IXDAR_ASSET_REPO_ROOT`) and appends lines, delegating raw I/O to the `Platform`; `TextFile` is the logical file. The `.ix` format itself lives in `geometry/tsp`.
- **ixdar.platform.gl**: The core portability seam: `GL` (the minimum GL surface, enum values as methods since numerics differ per backend) and `Platform` (windowing, assets, and the injection point for native backends: Cholesky, integer programs, mesh booleans). `IxBuffer` abstracts buffers; `GlslSource` rewrites ES 300 shaders to core 330.
- **ixdar.platform.gl.headless**: CI/test implementation: an invisible GLFW window provides a real offscreen GL 3.3 core context. Works on macOS; Linux needs a display server such as Xvfb.
- **ixdar.platform.gl.lwjgl**: Desktop implementation: GLFW window, real OpenGL, direct NIO buffers, `java.nio.file` I/O.
- **ixdar.platform.gl.web**: TeaVM implementation: `WebGLRenderingContext` on an HTML canvas, async-only resource loading, `InlineWorkerPool`, `WebBuffer.flip()` a deliberate no-op.
- **ixdar.platform.input**: Input devices: GLFW-numbered `Keys`, `KeyNames` (key specs, modifier bits), semantic `KeyActions` with the key-consumed count, `MouseButtons`, and the `PointerRegion` contract UI panes implement. Scene-level dispatch lives in `ixdar.input`.
- **ixdar.platform.json**: The platform-neutral JSON tree `Platform.parseJson` returns. {@link ixdar.platform.json.JsonValue} is the only type callers touch; {@link ixdar.platform.json.GsonJsonTree} builds it for the desktop platforms, and the web platform builds the same shape from the browser's `JSON.parse`.
- **ixdar.platform.teavm**: TeaVM bytecode transformers registered in the `web-teavm` profile: `WebMathTransformer` supplies the missing `Math.fma` (true fused multiply-add via BigDecimal; two roundings would break the exact orientation predicates), `JomlUnsafeTransformer` drops JOML's `sun.misc.Unsafe` path.
- **ixdar.procgen**: Namespace root for procedural content generation; all code lives under `dungeon`.
- **ixdar.procgen.dungeon**: Vazgriz-style dungeon pipeline in 2D and 3D: place rooms, Delaunay-triangulate, MST plus loop edges, A* corridors, tile grid to mesh; exposed as DSL nodes; plus the runtime to walk the result (capsule physics, player controller, cameras, viewer scene).
- **ixdar.procgen.dungeon.algo**: Headless generation algorithms, no rendering or scene dependencies: room placers (may return fewer rooms than asked), Bowyer-Watson Delaunay, Prim MST with probabilistic loop edges, A* corridors (3D adds single-floor stair moves), grid-to-mesh with inward-wound hollow rooms, and `DungeonGrids`, the builders/readers for the pipeline's geometry-plus-attribute shapes.
- **ixdar.procgen.dungeon.camera**: `ThirdPersonCamera` (the camera controller following the player, its orbit on the shared `Camera3D`) and `CameraGridSweep` (sphere-cast that hard-stops at the first obstacle cell).
- **ixdar.procgen.dungeon.nodes**: Thin `MeshNode` adapters wrapping each algorithm stage for the DSL, all in the `dungeon` scope. Every stage is one node for 2D and 3D: `grid_d` on rooms and corridors chooses 3D, and grid-to-mesh reads the dimension off the tiles it is given.
- **ixdar.procgen.dungeon.physics**: Minimal dungeon collision: `CapsuleShape`, `AabbBox`, MTV separation, and `CapsuleMover` (sub- stepped move-and-slide over the tile grid). Must agree with `GridToMesh3D` on cell size and the origin-centered convention; `EMPTY` and out-of-grid are the obstacles.
- **ixdar.procgen.dungeon.player**: `PlayerController` (WASD, gravity, yaw-relative motion; mouse-look belongs to the mouse handler), `PlayerSpawner` (relies on room[0] being the start room), `SpawnPoint`.
- **ixdar.procgen.dungeon.render**: `DebugCapsuleRuntime`: builds the player capsule mesh once, writes only a model matrix per frame into a wrapped `HalfEdgeMeshRuntime`.
- **ixdar.procgen.dungeon.values**: `CellType`, the named constants behind the per-cell `cell_type` int attribute the corridor and grid-to-mesh stages share. Dungeon data itself flows as geometry bundles with attributes.
- **ixdar.scenes**: Scene layer root: `Scene`, the base of every launchable view (frame loop, input, cameras, hosted tools, tooltip), and every class under this package extends it. A scene hands the window to another with `switchTo`, as the menu does to the TSP editor and the trade game.
- **ixdar.scenes.anatomy**: Anatomy visualization scenes built on the point/knot lineage.
- **ixdar.scenes.dungeon**: `DungeonViewerScene`, a model scene over the dungeon graphs: F toggles fly-cam vs player-walk, V toggles person view; only 3D dungeons support player mode since the graph's mesh feeds both renderer and collision. Its keys are control rows; its camera controllers are a `FlyController` and the `ThirdPersonCamera`.
- **ixdar.scenes.main**: `MainScene` (scene id `ixdar-canvas`, or opened from the menu and the `ld` command): the 2D TSP editor scene; knots, shells, terminal, tools, with its own key and mouse handlers. The legacy lineage's user surface.
- **ixdar.scenes.menu**: `MenuScene` (scene id `ixdar`): the main menu over its fluid backdrop. Its items switch the window to `MainScene` or `TradeScene`, which hand it back on Escape.
- **ixdar.scenes.mesh**: Mesh-centric scenes: the node viewer (`MeshNodeViewerScene`, also the web entry scene) and `MeshBooleanScene`. Both execute .dsl graphs and log node timings.
- **ixdar.scenes.model**: `ModelScene`: the base class of the scenes that view a switchable model from the `ixdar.models` catalogue, with the ESC model menu, the shared orbit input and the load plumbing.
- **ixdar.scenes.ring**: Ring authoring: `RingScene` (scene id `ring-tool`) is the mesh editing scene hosting the orbit, ring, region and connection `Tool`s of `ixdar.tools` and the region colour layer, plus the `ring` command's waypoints and the numbered ring overlay.
- **ixdar.scenes.trade**: `TradeScene`: the trade-game scene over `CityNetwork`, with its own input handlers, swapped in by the menu's Start New Game.
- **ixdar.tools**: Tools a scene hosts, 3D and 2D alike: the `Tool` interface (one active tool owns the clicks, drags and tool keys), the `ControlHint` rows that map a key to a function, and `EditHistory`, the undo stack the ring and route-planning tools keep. Each tool family lives in a subpackage.
- **ixdar.tools.connection**: Hidden connections: `ConnectionTool` is the `ring-tool` editing scene's tool that shows how two picked places of the surface are joined and offers the neck between them as a ring to edit.
- **ixdar.tools.graph**: The DSL graph view: a ran DSL's statements laid out left to right by dependency depth as rounded boxes joined by bezier arrows, each box carrying its run time, toggled over any model scene that ran a graph.
- **ixdar.tools.knot**: The TSP editor's knot tools, each a `KnotTool` (the shared knot hover and selection behind the merged `Tool`): free inspection, map editing and diagnostic overlays over the editor's knots.
- **ixdar.tools.orbit**: `OrbitTool`: the look-only tool every model scene opens with; the mouse orbits, pans and zooms the camera and nothing is edited.
- **ixdar.tools.regions**: Ring regions of the `ring-tool` editing scene: `RegionLayer` keeps the regions the ring tool's rings enclose up to date and colours them, `RegionColouring` picks the colours, and `RingRegionTool` (the region tool) selects regions by click and Shift+click and extracts them.
- **ixdar.tools.ring**: The ring tool of the `ring-tool` editing scene: `RingTool` previews, drafts and confirms rings from authored anchors, `RingToolState` is its undoable snapshot, and `RingDslWriter` appends each ring to the working .dsl graph as one statement.
- **ixdar.tools.trade**: Trade-game tools on the merged `Tool`: `HeadquartersPickerTool` and `RoutePlanningTool`, which depend on the game model instead of the knot editor.
<!-- package-map:end -->
