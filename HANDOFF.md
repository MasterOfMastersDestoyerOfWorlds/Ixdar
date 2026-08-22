# Session handoff (2026-08-22)

For the next context window. `REFACTOR-PLAN.md` is the durable state; this file is the delta and
the operating knowledge that is not in it. Delete once absorbed.

## Where things stand

- Working tree (uncommitted, all verified green): the `mark_edges` batch — new
  `MarkEdgesNode` + `EdgeMarks`, `MarkCreaseNode` deleted, `SubdivisionMeshNode` reads the
  "crease" float label, 6 `.dsl` files rewritten (hand family + organic_test), catalog json
  regenerated. Verified: reactor compile green, all 5 rewritten DSLs pass `ValidateDsl`,
  `organic_test` evaluates (6 nodes), `MeshNodeRegistryTest` green.
- Everything before this batch is committed by the user (they commit; the deny list blocks git
  writes for the agent).
- Next task: plan item 3.1 remainder — migrate the 15 cloned `buildGrid()` helpers in
  `ixdar-app/test/unit/mesh/` onto `GridMeshNode` (user ruled F3(b): change the tests'
  expectations to match the node's XZ-centered triangulation). Expect breakage in hand-computed
  vertex ids; 3 quad-layout test classes already fail pre-existing (plan 6.8) — don't chase those.
- After that: plan section 1 (ARCHITECTURE.md itself) is untouched; sections 5.6, 5.7, 6.x, 7.x open.

## Operating knowledge (hard-won, do not re-derive)

- Build: ALWAYS `mvn -q clean compile -pl annotations,ixdar-app` from the repo root. Plain
  `compile` without `clean` prunes generated registries and dies at `export-automation-routes`.
  Check exit codes via `echo $?` — `${PIPESTATUS[0]}` is empty in this zsh.
- Tests: `mvn clean test -pl annotations,ixdar-app -Dtest=X,Y -Dsurefire.failIfNoSpecifiedTests=false`
  (comma separator; `+` silently runs nothing). Full suite has 3 pre-existing failures (plan 6.8)
  plus one `@Disabled` provenance test (plan 6.4).
- Scenes: `uv run ixdar-cli run-scene --scene <id> --timeout 90 --skip-build` after a manual clean
  build. Kill strays afterwards: `ps -Ao pid,command | grep "[I]xdarWindow"` → kill -9; a live
  scene holds `target/classes` and breaks the next clean.
- A scene run needs `-XstartOnFirstThread` on macOS (run-scene adds it now).
- The user's VS Code launches (F5) compile via JDT into `target-ide/`; the IDE resolves generated
  registries from Maven's `target/generated-sources/annotations` via `ixdar-app/.classpath:44`.
  IDE metadata (.project/.classpath/.settings/.factorypath) is local-only and gitignored.
- `IXDAR:annotations` must be `mvn install`ed after changes or standalone ixdar-app builds and the
  IDE use a stale jar (symptom: NoSuchMethodError / missing packages / unloadable processors).
  The module compiles at release 21 ON PURPOSE (the LS JRE is 21); everything else is 25.
- Checkstyle gates the build; frequent trips: DeclarationOrder (public statics before private,
  fields before methods), MeaningfulDuplicateStringLiterals (extract repeated meaningful strings),
  50-word Javadoc description cap.
- DSL string literals use plain double quotes; a `\"` written into a .dsl is a literal backslash
  and fails the lexer with "Unterminated string literal".
- `ValidateDsl` takes the dsl path as a positional arg. Classpath for standalone java:
  `ixdar-app/target/classes:annotations/target/classes:$(cat ixdar-app/target/CP)`;
  regenerate CP with `mvn -f ixdar-app/pom.xml dependency:build-classpath -Dmdep.outputFile=ixdar-app/target/CP`.
- User interaction: rulings framework (enumerate with evidence, they rule). Output style: unslop +
  short. They commit at boundaries; verify before declaring done (compile is not verification —
  run the scene or test).

## Key new APIs this session

- `NodeGraphRuntime.fromSource(String)` → `ParsedGraph(runtime, statements)`; `supplierFor(id)`;
  `DESKTOP_SUPPLIERS` (reflective firewall); `logTimings(prefix)`.
- `EdgeMarks.floats|ints|bools(bundle, label)` / `EdgeMarks.with(bundle, label, array)`;
  `MarkEdgesNode.CREASE_LABEL`.
- `ModelCatalog.quadLayout(path)` / `.staging(path)` / cursor methods; `ModelChoice.kind`.
- `ModelScene.frameMesh(mesh)`, `preserveOrbit(supplier)`, public `orbitAzimuth/orbitElevation`;
  `Scene.bindInputDirect`.
- `@MeshNodeAnnotation(desktopOnly = true)` → `MeshNodeRegistry_MeshNodesDesktop` +
  `DESKTOP_ONLY_IDS`.
