# Uncommitted state

Never revert or overwrite uncommitted changes without an explicit yes from the user, including changes you made earlier in the session. A fix that looks wrong is investigated, not rolled back. You never perform a revert yourself: `git checkout`, `git clean`, `git reset` and shell overwrites of tracked files are denied, and `git show HEAD:path > path`, a `cp` from a scratch copy, or a Write of a prior version are the same operation in a different hat. When a revert is right, print two lists, **restore to HEAD** (tracked) and **delete** (untracked), then stop. The one exception is a file you created this session that nothing else touched. Land the rewrite that removes the dependency on the reverted code first, so applying the list leaves the build green; if that is impossible, say the build will be red in between.

# Worktrees and git: `wt` is the one door

Agent work happens in a linked worktree at `.claude/worktrees/<ticket>` on a branch of the same name, and the worktree's uncommitted diff is the proposed change. Leave it uncommitted; the branch sits on `master`, so `git diff` shows exactly what would land.

Every git write verb (`add`, `commit`, `merge`, `checkout`, `restore`, `reset`, `stash`, `rebase`, `push` and the rest) is denied in every worktree. A denial is about the verb, not the `cd` in front or the pipe after it, so do not retry it another way. Use `wt`: `status` (the first command in a worktree and the whole of a resume), `sync` (replay the diff onto current `master`; on a conflict fix the markers and `continue`, or `abort`), `launch-add` (the `.vscode/launch.json` entry the user verifies with F5; never hand-edit that file), `done` (sync, refuse leftover diagnostics, build, run the entry once exactly as F5 runs it but headless — `ixdar-cli launch`, same `vmArgs` including the profiler agent — check `tmp/` holds a screenshot, mark the ticket REVIEW; the launch run cannot be skipped) and `commit` (only when asked). `wt --help` has the rest. Read-only git is fine, but `git branch` and `git tag` are denied even to list: `wt status` prints the branch's published and archived refs, and `git for-each-ref` or `git show-ref` answer anything else. Merging to `master` is `land`, which is the user's alone.

Debugging scaffolding does not land. `wt done` refuses a diff that adds a `Platforms.log` or `System.out` line, a public `…Count`/`…Gap`/`…Ulps`/`worst…` field, a `describe…`/`dump…` method, a `…SAMPLE_LIMIT` constant or a `test/benchmark/*Probe.java` file, unless the ticket's definition of done names it; `wt status` lists them as you go. Strip each one once it has answered its question, or, when it is meant to stay (a summary line the user reads, a metric that is the acceptance number), name it in the DoD with `--append-definition-of-done`.

Never `cat` or `tail` the harness's `tasks/` directory, which the sleep-poll hook refuses. Run a long command in the foreground with its output redirected to a file under the worktree's `tmp/` and read that file; the TaskOutput tool, where a session has it, also reads a backgrounded command's output, but subagents do not have it.

# Conventions

## Naming

Use full words; a reader landing cold should not have to grep to learn what an identifier means. Two kinds of integer are load-bearing: `edgeId`, `vertexId`, `faceId` are sparse `HalfEdgeMesh` handles, and `activeEdge`, `activeVertex`, `activeFace` are dense `[0, count)` solver indices. Mixing them silently produces wrong arrays, so never name one `e`, `v`, `ae`, `vId` or `hCanon`, and when a method takes both, the parameter name says which: `activeVertexIndex(int vertexId)`. Accumulators and frontiers get real names too: `frontier`, not `pq`.

A constant is named for its meaning, not its value, and carries no Javadoc: the name is the documentation. `NUM_3`, `NUM_0_5` and `STR_2` are rejected by checkstyle.

## Comments

Default to none; identifiers carry the *what*. Write a comment only for a non-obvious *why*: a hidden constraint, a citation, a workaround.

- Method docs are Javadoc, not `//`. Every non-trivial method gets one saying what it produces and its non-obvious invariant. No `// section ====` banners, no restating the code, and citations live in the Javadoc of the method that implements them.
- `@param` for every parameter, `@return` on non-void, `@throws` where thrown, always, with non-empty descriptions. Never leave a `TODO: document` stub.
- The 50-word description limit is a smell detector, not a target. When `JavadocDescriptionLength` fires, cut to about 25 words: what the member produces and one invariant. Implementation detail (the algorithm, the loop mechanics, what was tried before) is what pushes a doc over, so delete it. A paragraph needed to explain one parameter means the parameter is wrong.

## Method and class shape

- One public entry point per pipeline class; internals are private and follow in dependency order. If `A.foo()` calls `B.bar()` calls `A.baz()`, the class boundary is in the wrong place.
- One top-level class per file. Avoid nested classes, records and thin data classes (an "Entry", "Info" or result holder): store rows as parallel arrays on their owner, the way meshes store positions and normals, and keep scratch state (distance arrays, visit stamps, queues) as primitive-array fields of the class that uses them. A class earns its file when it is a named concept with behaviour or an identity that outlives its owner.
- Fields are `public` (or `public final`). Private fields buy refactor friction, not encapsulation. Methods are the opposite: `private` unless genuinely API.
- Do not extract single-caller private methods. A loop body or an `if` branch is not a helper; a helper names a concept and is substantial (roughly 20 lines or more). When one is warranted, it sits adjacent to its caller, in call order down the file.

## No system properties

Never add a `System.getProperty` knob: it is untyped, unchecked, invisible to tests and stack traces, and rots. Two behaviours that both need to exist are a `public` field on the owning class with a behaviour-describing name (`coupleSeams`), a boolean for two states, an enum only for three or more. Never a speculative switch; when one behaviour lost an experiment, delete it. The existing properties that choose an *input file* (`ixdar.model`, `benchmark.off`) are the one case this does not cover.

## Checkstyle

Never skip it and never comment a rule out: a red checkstyle is a red build. Fix every violation regardless of who wrote it or how long it has been red; the fixes are mechanical, and a non-mechanical one is surfaced, not skipped. The rules are `~/Code/autofix/src/main/resources/checkstyle.xml`, and a rule change is made there, not in a review checklist. The ones that bite most:

- Declaration order: static fields, instance fields, constructors, methods; within a bucket public before private.
- Magic numbers: literals other than `-1, 0, 1, 2, 3, 0.5` are named constants. Field initializers and annotations are exempt.
- Duplicated strings: a repeated literal that carries meaning (a key, a format, a path) is a constant; string-assembly glue (an operand of `+` or an `append` argument) stays inline however often it repeats. `MeaningfulDuplicateStringLiteralsCheck` enforces this and `InlineGlueStringConstantsRecipe` undoes the glue constants.
- No inline fully-qualified class names; import instead, except for a genuine simple-name collision.
- Banned packages: `java.awt`, `javax.swing`, `javax.imageio`, `java.applet`, `java.beans`. The GL renderer is the only renderer: rendering anything means driving a scene and capturing it, never rasterizing on the CPU. PNGs are written with `ixdar.graphics.image.PngWriter` over a `PixelImage`.

# Building and testing

Plain maven from the repo root; the pom carries every flag.

```sh
mvn -q compile                          # both modules, checkstyle included
mvn test -Dtest=SurfaceSplineTest       # one class, or a comma-separated list
mvn test                                # the unit suite
```

`mvn -q compile` prints nothing on success, so anything it prints is a problem; do not filter it. `mvn test` ends with surefire's `Tests run: N, Failures: N, ...` line with failures named above it, and a stale `-Dtest=` class shows as `Tests run: 0`, not an error. If you pipe maven, read `${PIPESTATUS[0]}`, never `$?`. Each test has a 300 s timeout (`-Dixdar.test.timeout=1200s` to raise it once) and the test JVM gets `-Xmx4g`; a benchmark needing more heap is a bug in the code under test. A JVM abort leaves `hs_err_pid*.log`, `*.hprof` and surefire `*.dumpstream` under `ixdar-app/target/`.

# Unit tests

A `unit.mesh.*Test` never loads a mesh file and never runs the pipeline. The reproducer is a hand-authored fixture under `ixdar-app/src/main/java/ixdar/geometry/mesh/quadlayout/embedding/fixtures/` (the `*LayoutFixture` classes are the pattern) and writing one is part of the work. Reproduce on a real mesh to learn what the fixture must contain, then encode it; what is disallowed is shipping the mesh run as the test. Mesh-backed checks live in `ixdar-app/test/benchmark/`, run deliberately with `-Dtest=`.

Never write a throwaway `.java` file to get a number. The number comes from a tracked automation route (add one if none reports it) or from a test under `ixdar-app/test/` that survives the merge. Every `.java` file left in the worktree at handover is part of the proposed change.

# Profiling

Profile with async-profiler's CPU flame graph, always kept as HTML. For a scene, `ixdar-cli run-scene --profile` attaches the agent, shuts the JVM down cleanly so the capture flushes, and prints the hot-method table; `ixdar-cli profile-report profile.html --top 30 [substr ...]` re-reads a capture. High inclusive but low self time means the cost is in callees, so optimize the callee or the call count.

When the user names a method or target, optimize that one. State a bigger opportunity once, in one sentence, then drop it. The user decides priority.

# Scenes and visual debugging

An interactive view is a scene: a class extending `Scene` (or `Canvas3D`) annotated `@SceneAnnotation(id = "...")` and registered by the annotation processor. The entry point is `ixdar.canvas.IxdarWindow <id>`, and every scene id has a `.vscode/launch.json` entry for F5. Scaffold a new scene with `ixdar-cli new-scene` rather than hand-writing the boilerplate.

To see a render, use `ixdar-cli run-scene --scene <id>` and nothing else: no hand-rolled `java`, `mvn exec:java` or health-poll loops. It builds, launches headless, waits for `sceneReady`, screenshots and shuts the JVM down. Never pass a port and never sleep waiting for a scene: the scene publishes its port to `tmp/automation.port`, every command reads it, `--keep-alive` returns once the scene is ready, and `ixdar-cli shutdown` returns once the process is gone. `ixdar-cli launch "<entry>"` runs a launch entry the way F5 does. Both write the scene's output to the next free `tmp/logs/<scene>-<mesh or entry>-<n>.log` of the checkout they run in, print that path first and return it as `log`; `tmp/logs/latest-<scene>.log` is always the newest run. `ixdar-cli multiview` composites eight views into one PNG with `viewOrder` naming the cells. Then `Read` the PNG.

Do not write a per-scene renderer or a bespoke visualizer: the runtime already draws meshes, arcs, markers and overlays on the surface, so feed the scene and capture it.

# Web build (TeaVM)

`./tools/teavm-build.sh` packages the browser bundle and fails the build itself when TeaVM emits a stub `classes.js`; the full log is `ixdar-app/target/teavm-build.log`. Everything reachable from `ixdar.canvas.WebLauncher` must stay inside TeaVM's class library: no `Files.readString`/`writeString` (use `readAllBytes` and `write` with `StandardCharsets.UTF_8`), no `Arrays.compare` on arrays, and no Gson or other reflection on the web path (parse with `Platforms.get().parseJson`). A `[ERROR] ... was not found` line names the missing method; the class to change is usually one step up the call graph.

# Automation

<!-- BEGIN-GENERATED: automation-cli -->
_Generated by `ixdar-cli gen-docs` from Java route `describe()` and CLI command docstrings._
_Do not edit by hand; see [ixdar_automation_cli/README.md](ixdar_automation_cli/README.md)._

Run any command with `ixdar-cli <command> --help`. Install the global alias with `ixdar-cli install-alias` (or `bash tools/install-cli.sh`).

**Server-backed commands** (generated from the automation routes manifest):
- `ixdar-cli click [--x] [--y] [--normalized] [--button] [--settle]` — Click at a point on the active mouse handler, then wait for the click to be drawn.
- `ixdar-cli frame [--selection] [--bounds] [--padding] [--azimuth] [--elevation]` — Fit the camera to a named selection or an explicit bounding box, filling the view with it.
- `ixdar-cli health` — Liveness probe reporting server status, recording/replaying flags, and port.
- `ixdar-cli hover [--x] [--y] [--normalized] [--persistent] [--settle]` — Move the cursor without clicking, then wait for the hover to be drawn.
- `ixdar-cli hover-clear` — Release the persistent automation hover lock on the active trade mouse handler.
- `ixdar-cli key --key [--action] [--settle]` — Deliver a named key event to the active key handler and report whether it was consumed.
- `ixdar-cli mesh-compare --reference [--distance-type] [--scale] [--normalize]` — Compare the active viewer mesh against a reference OBJ using Hausdorff and Chamfer metrics.
- `ixdar-cli mesh-dsl --name [--node] [--port]` — Load and execute a named DSL skill graph, making its output geometry the active mesh.
- `ixdar-cli mesh-dsl-timing` — Report per-node execution times and peak heap from the most recent DSL graph run.
- `ixdar-cli mesh-dsl-validate --dsl [--export]` — Validate DSL source text, or the contents of a .dsl file path, against the skill schema.
- `ixdar-cli mesh-fingerprint` — Compute the canonical SHA-256 fingerprint of the active viewer mesh.
- `ixdar-cli mesh-holes [--path]` — List every boundary loop of the mesh with its edge count, perimeter and area estimate, plus the loops repair_mesh filled and the triangles it used.
- `ixdar-cli mesh-patches-decompose --path [--resolution]` — Hybrid skeleton and curvature patch decomposition of a reference mesh.
- `ixdar-cli mesh-seamless-diagnosis [--highlight]` — Report the seamless solver's singular-system diagnosis: how the singularity was classified, the null vector's support and the mesh vertices it sits on.
- `ixdar-cli mesh-segmentation --path [--method] [--n-clusters]` — Segment a mesh into labeled vertex groups by connected components, curvature, or spatial clustering.
- `ixdar-cli mesh-skeleton-compare --generated --reference [--resolution]` — Compare TEASAR skeletons of two meshes and recommend parameter fixes.
- `ixdar-cli mesh-skeleton-compare-detailed --generated --reference [--resolution]` — Detailed skeleton comparison returning per-joint 3D position deltas.
- `ixdar-cli mesh-skeleton-sensitivity --dsl --reference [--resolution] [--epsilon]` — Compute the Jacobian of skeleton joints w.r.t. DSL parameters.
- `ixdar-cli mesh-topology [--path] [--duplicate-tolerance]` — Report mesh topology: element counts, shells with their Euler characteristic, boundary loops, non-manifold edges and duplicate-position vertices.
- `ixdar-cli model <name>` — Switch the active model scene to a named model and recompute, as the terminal command ml does, returning once it has loaded or failed, with the failure message and the seconds it took.
- `ixdar-cli multiview [--out] [--inline]` — Capture 8 orbit viewpoints and composite them into a 4x2 grid PNG.
- `ixdar-cli orbit-get` — Report the active mesh viewer's current camera orbit and mesh radius.
- `ixdar-cli orbit-set [--azimuth] [--elevation] [--distance] [--target]` — Set the active mesh viewer's camera orbit (azimuth, elevation, distance).
- `ixdar-cli projection-get` — Report whether the active mesh viewer is using orthographic projection.
- `ixdar-cli projection-set [--orthographic]` — Toggle the active mesh viewer between orthographic and perspective projection.
- `ixdar-cli record-start` — Begin a new recording session, clearing any previously buffered events.
- `ixdar-cli record-status` — Snapshot of the recorder: recording flag, event counts, start time, saved file.
- `ixdar-cli record-stop [--path]` — End the active recording session and write the captured events to disk.
- `ixdar-cli replay-cancel` — Signal the active replay to abort at the next event boundary; no-op if idle.
- `ixdar-cli replay-pause` — Suspend the replay engine before the next event; no-op when nothing is running.
- `ixdar-cli replay-resume` — Clear the paused flag on the replay engine; no-op when nothing is running.
- `ixdar-cli replay-start --file [--mode]` — Launch a replay from a previously saved recording file.
- `ixdar-cli replay-status` — Snapshot of the replay engine: running flag, status, current file, paused flag.
- `ixdar-cli rings add --points [--tighten]` — Ring the shown surface through authored points and report the ring row: centroid, length and marked edge count.
- `ixdar-cli rings-list --path [--resolution] [--min-neckness]` — Rank the neck rings a mesh's skeleton proposes, most neck-like first.
- `ixdar-cli screenshot [--out] [--inline] [--crop] [--scale]` — Capture a PNG screenshot of the current framebuffer to a file.
- `ixdar-cli scroll [--delta]` — Deliver a synthesized scroll event to the active mouse handler.
- `ixdar-cli type [--text]` — Synthesize character events on the active key handler, one per character of the text.
- `ixdar-cli ui-state` — Snapshot the full UI state: window, frames, scene, trade, mesh, text, menu, and audio.

**CLI commands** (client-side scenarios, tools, and utilities):
- `ixdar-cli assert-tooltip` — Assert that the visible tooltip text contains the requested strings.
- `ixdar-cli audio-log` — Return recent audio log events.
- `ixdar-cli audio-state` — Extract the audio state from the UI snapshot.
- `ixdar-cli click-scan` — Click through a grid until the scene leaves the menu.
- `ixdar-cli collection-keep` — Mark one collection member as kept and rewrite its manifest.
- `ixdar-cli collection-list` — List model collections and their members with keep flags.
- `ixdar-cli collection-reject` — Mark one collection member as rejected and rewrite its manifest.
- `ixdar-cli coverage-report` — Merge JaCoCo exec files and report the code they never executed.
- `ixdar-cli dsl-optimize` — Batch-optimize mesh DSL parameters against a reference OBJ.
- `ixdar-cli duplication-report` — Report duplicated code ranked by how much repetition factoring it out would remove.
- `ixdar-cli gen-docs` — Regenerate the CLAUDE.md command list and the CLI README from the manifest and registry.
- `ixdar-cli image-diff` — Compare two PNG screenshots, reporting RMSE and how many pixels differ beyond a fuzz.
- `ixdar-cli image-stats` — Report a PNG's mean, minimum and maximum channel values and whether it is a blank frame.
- `ixdar-cli install-alias` — Install a global ixdar-cli wrapper into ~/.local/bin.
- `ixdar-cli launch` — Run a .vscode/launch.json entry headless, then report its first log lines and a screenshot.
- `ixdar-cli list-meshes` — List mesh files a scene can load, with the short names run-scene resolves.
- `ixdar-cli mesh-overlay` — Load a reference OBJ as a semi-transparent overlay, or clear it.
- `ixdar-cli mesh-probe` — Capture the mesh-focused automation probe bundle.
- `ixdar-cli mesh-state` — Extract the mesh viewer state from the UI snapshot.
- `ixdar-cli mesh-validate` — Validate the current mesh viewer payload.
- `ixdar-cli mesh-viewer` — Launch the mesh viewer, optionally overlay a reference OBJ, and screenshot.
- `ixdar-cli new-scene` — Scaffold a new Scene class, launch.json entry, and optional Maven profile.
- `ixdar-cli probe` — Capture the core automation probe bundle.
- `ixdar-cli profile-report` — Report self-time hot methods from an async-profiler HTML capture.
- `ixdar-cli quilt-mesh-compare` — Compare mesh viewer canonical fingerprint to a reference OBJ (same algorithm as Java).
- `ixdar-cli rebuild-krieg-web` — Build the TeaVM web output then run Hugo for Krieg Eterna (KRIEG_ETERNA_WEB overrides path).
- `ixdar-cli run-scene` — Build, launch, wait for, optionally profile and screenshot, then shut down a scene.
- `ixdar-cli shutdown` — Ask the scene to exit and return only once its process is gone.
- `ixdar-cli start-new-game` — Leave the menu by clicking Start New Game.
- `ixdar-cli terminal` — Type a line into the scene terminal, press enter, and return the terminal's response.
- `ixdar-cli trade-hover-scan` — Scan trade cities until the requested toolbar tooltip appears.
- `ixdar-cli validate-route-ops` — Validate trade route operations against the running app.
<!-- END-GENERATED: automation-cli -->
