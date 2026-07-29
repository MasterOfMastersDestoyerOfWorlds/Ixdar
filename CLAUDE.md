# Working with uncommitted state

Never revert or overwrite uncommitted changes without an explicit yes/no from the user — including changes you made earlier in the same session. If a fix you tried looks wrong, made tests worse, or "feels safer to undo," the default is to leave it in place and investigate, not to roll back. Ask before any `git stash`, `git checkout -- <file>`, `git reset --hard`, or Edit/Write that restores a prior version of a file. "Reverting is the safe move" is never an unprompted decision.

# Conventions

## Naming

Use full words. No cryptic abbreviations — a reader landing in the file cold should not have to grep to learn what an identifier means.

- `edgeId`, `vertexId`, `faceId` — raw `HalfEdgeMesh` handles. Sparse (mesh may have holes).
- `activeEdge`, `activeVertex`, `activeFace` — dense `[0, count)` solver indices.
- `halfEdge` — a half-edge handle (use `halfEdge` even when it's the canonical one; don't write `hCanon`).
- `periodJump`, `chartVertex`, `cornerStartA`, etc. — spell it out.

The id-vs-active-index distinction is load-bearing; mixing them silently produces wrong arrays. Never name a variable just `e`, `v`, `f`, `ae`, `vId`, `hCanon`, `cv`, `p` — the type of integer is the whole point.

When a method takes both kinds, the parameter name says which: `activeVertexIndex(int vertexId)` (id in, active index out).

Variables for loop accumulators, search frontiers, etc.: `frontier` not `pq`, `posHere`/`posOther` not `pa`/`pb`, `reachedActiveVertex` not `hitVa`, `newDistance` not `nd`.

## Comments

Default to none. The code says *what*; identifiers carry meaning. Only write a comment when the *why* is non-obvious — a hidden constraint, a paper citation, a workaround, behavior that would surprise a reader.

- **Method docs: Javadoc, not `//`.** Every non-trivial method gets a Javadoc that says what it produces and any non-obvious invariant. Inline `//` clutter inside a method body is the wrong shape — if a method needs paragraph-length explanation, that explanation belongs on the method.
- **No `// section ====` banners.** If a class has sections, the method Javadocs are the section headers.
- **No restating the code.** `// Mark edge non-cut` above `isCutEdge[ae] = false` is noise.
- **Citations stay.** `BZK09 §5`, `Lyon 2021`, derivations of integer constraints — keep these, but inside the Javadoc of the method that implements them, not floating mid-body.
- **`@param` for every parameter, always.** Every parameter on a Javadoc'd method gets an `@param` with a non-empty description, even when the name seems obvious. Same for `@return` (non-void) and `@throws`. This matches the checkstyle config in `~/Code/autofix/src/main/resources/checkstyle.xml` (`JavadocMethod` with `allowMissingParamTags=false`, `allowMissingReturnTag=false`, `validateThrows=true`).
- **No stub Javadoc.** Never leave `TODO: document` or `TODO: describe` placeholders — checkstyle rejects them. Write the real prose, even one line.
- **The 50-word Javadoc-description limit is a smell detector, not a target.** When `JavadocDescriptionLength` fires, do NOT nudge the wording down word-by-word to land at 49 — that wastes turns and leaves a bloated doc. Cut it to **~25 words**: state what the member produces and its one non-obvious invariant, nothing else. **Implementation details do not belong in a Javadoc** — how the method works internally (the algorithm, the data structure, the BFS/loop mechanics, why a prior approach was tried) is what pushes it over the limit; delete it. If the *why* is genuinely load-bearing, one short sentence or a `See also:` citation, not a paragraph.

If a method needs a paragraph of Javadoc to explain a single parameter, the parameter is probably wrong — split the method or rename. A long doc is a smell, not a fix.

## Method shape

- One public entry point per pipeline class (e.g. `build()`); internals are private and called in dependency order.
- Don't interleave responsibilities across classes — if `A.foo()` calls `B.bar()` calls `A.baz()` calls `B.qux()`, the class boundary is in the wrong place.
- Don't add a parameter that exists for only one of N callers without a clear name and a one-line Javadoc that says when `-1` (or whatever sentinel) means "default."

### Class layout

- **One top-level class per file.** No exceptions for "small" companion classes.
- **Avoid nested classes.** Inner classes, static nested classes, and anonymous classes hide structure inside other files and resist refactoring. If you need a nested class, that's the signal to promote it to its own top-level file.
- **Scratch state is fields, not classes.** Reusable working state (distance arrays, visit stamps, queues) belongs as primitive-array fields at the top of the class that uses them — a companion "Scratch"/"State" class is an indirection, not a concept. Class extraction is for named concepts, not buffers.
- **Avoid records.** Prefer a regular class with `public final` fields. (Records read fine in isolation but in practice they accumulate carve-outs — custom `equals`, validation in compact constructors, escape-analysis worries on hot paths — at which point they're a normal class wearing a costume.)

### Field visibility

Prefer `public` (or `public final` / `public static final`) for fields. **Avoid package-private and `private` instance fields** — they create refactor friction (visibility juggling every time you split or merge classes) without buying meaningful encapsulation in this codebase. If you find yourself reaching for `private` on a field, ask whether the class boundary is in the right place instead.

Methods are the opposite default: `private` unless the class genuinely exposes them as API.

### Single-caller private methods

Default: **don't extract them.** A private method that is only ever called from one place earns its existence only by being (a) a complete logical unit you can name without referring to its caller, and (b) substantial — roughly 20+ lines, or non-trivial enough that inlining would meaningfully hurt readability. "I like helper methods" is not a reason; the helper is then just an indirection layer hiding the real flow.

**A loop body is not a helper.** Extracting `perFooThing(int i)` so the caller can write `for (...) perFooThing(i)` adds no information — the loop and its body already say "do this per element." Keep the work inline. Helpers exist to name a *concept*, not to shave lines off a loop. The same goes for `if`-branch bodies: don't extract a 6-line method just because it's the body of one branch.

When a single-caller helper genuinely is warranted (see `CutGraph.java` for the rare cases), place it:

- **Adjacent to its caller**, not in some "helpers section" at the bottom.
- **In call order** down the file, so a top-down read of the class follows the runtime path.

This rule has a custom checkstyle module (`SingleCallerHelperCheck`, currently disabled in `~/Code/autofix`) — treat it as the policy regardless.

## No system properties

**Never add a `System.getProperty` knob.** Properties are a hack: they move a decision out of the code into an invocation nobody will remember, they are untyped and unchecked, they never show up in a stack trace or a test, and each one is a branch that silently rots.

When two behaviours genuinely both need to exist, they are a **public field with a named enum** on the owning class, and the caller sets it. When one of them is just the loser of an experiment, delete it and let the commit message hold the result.

The existing `ixdar.model` / `tmeshPipeline.off` / `benchmark.off` properties are entry points choosing an *input file*, which is the one case this does not cover. Anything that changes *behaviour* is a field.

## Other checkstyle rules to know

**Never skip checkstyle.** Don't run with `-Dcheckstyle.skip=true` or comment out rules to make a build pass — fix the violations. A red checkstyle is a red build, full stop. The build is checked against `~/Code/autofix/src/main/resources/checkstyle.xml`. Beyond Javadoc, the rules that bite most often:

**Always fix checkstyle violations, regardless of origin.** If the build is red on checkstyle — whether the violation is in code you just wrote, code the user just wrote, or code that's been broken for ten commits — fix it. Don't ask, don't defer, don't suggest the user fix it. Treat a red checkstyle the same way you'd treat a compiler error: it's blocking, and the next move is always to clear it. The fixes are mechanical (move a declaration, add an `@param`, extract a duplicate string to a constant) and shouldn't add visible behavior changes; if any single fix is non-mechanical, surface that one specifically before applying. Don't leave a partially-fixed build expecting the user to clean up.

- **Declaration order** (`DeclarationOrder`): static fields → instance fields → constructors → methods. Within each field bucket, `public → protected → package → private`. The autofix recipe reorders this for you, but write it right the first time.
- **Magic numbers:** literals other than `-1, 0, 1, 2` must be named constants. Field initializers and annotations are exempt.
- **Duplicated string literals:** a repeated string that *carries meaning* — a map key, a format string, a system-property name, a file path — should be a constant. A repeated string that is only **string-assembly glue** — an operand of `+`, or an `append(...)` argument — stays inline, however often it repeats. `ARC = "arc "`, `AND = " and "`, `CLOSE_PAREN = ")"` name nothing; they are punctuation wearing a constant's clothes, and they push the declarations a reader actually needs off the top of the file. Usage decides this, not length: `KEY_FACES = "Faces"` is real and `" and "` is glue at the same character count. `MeaningfulDuplicateStringLiteralsCheck` enforces exactly this, and `InlineGlueStringConstantsRecipe` inlines glue constants back automatically.
- **No inline fully-qualified class names:** write `Collectors.toSet()` with an import, not `java.util.stream.Collectors.toSet()`. The only exception is genuine simple-name collisions across packages.

# Profiling

We profile with [async-profiler](https://github.com/async-profiler/async-profiler) (CPU, `event=cpu`), attached as an agent and dumping a flame-graph HTML. We **always** want the flame graph, so keep the capture as `.html`:

```
-agentpath:/usr/lib/libasyncProfiler.so=start,event=cpu,file=${workspaceFolder}/profile.html
```

For a scene, don't assemble this by hand — `ixdar-cli run-scene --profile` attaches the agent, waits for the scene, shuts the JVM down cleanly (async-profiler only flushes its HTML at exit) and prints the parsed hot-method table in one command:

```
uv run ixdar-cli run-scene --scene embedded-tmesh --profile --timeout 420 \
  --property embeddedTMesh.off=<mesh.off> --property embeddedTMesh.contractFail=true
```

The agent writes exactly one file per run (`profile.html` at the repo root by default), so don't expect a separate text dump — the textual view is extracted from that same HTML by `ixdar_automation_cli/async_profile.py`, which `run-scene` calls for you. To re-read an existing capture without re-running:

```
python3 -m ixdar_automation_cli.async_profile profile.html --top 30 [substr ...]
```

It reconstructs async-profiler's prefix-compressed `cpool` and replays the `f()/u()/n()` frame stream to report **self-time per method** (where the CPU actually was) and total samples. Trailing substrings filter an extra "inclusive / self" table to matching frames (e.g. `integrateCurvature applySparse vertexPosition`) — use this to compare a method's own cost against time spent in its callees.

When reading results: a high *inclusive* but low *self* number means the cost is in callees (often mesh accessors or `HashMap.getNode` from the boxing `faceIdToActive`/`edgeIdToActive` maps), not the method itself — optimize the callee or the call count, not the method body.

## Picking what to optimize

Do not argue about *which* thing to optimize. When I point at a specific method or target, optimize that one — even if you believe a different hotspot is the bigger win. State the bigger opportunity **once, in a single sentence**, then drop it and do what I asked. Don't re-raise it across turns, don't re-rank the options every reply, and don't treat a small absolute time as "not worth it" — if I say 2.5s is too long, it's too long. I decide priority; you make the thing I named faster.

# Scenes and visual debugging

Interactive 3D views are **scenes**: a class `extends Scene` (or `Canvas3D`) annotated `@SceneAnnotation(id = "...")`, auto-registered by an annotation processor (no registry list to edit — like the `@MeshNodeAnnotation` primitives). The window entry point is `ixdar.canvas.IxdarWindow`, and the scene id is `args[0]`, so `IxdarWindow embedded-tmesh` runs the scene with that id. Every scene id also has (or should have) a `.vscode/launch.json` entry — `mainClass: ixdar.canvas.IxdarWindow`, `args: <id>` — and can be run from the CLI with `mvn -q -f ixdar-app/pom.xml exec:java -Dexec.mainClass=ixdar.canvas.IxdarWindow -Dexec.args=<id>`.

**Don't hand-write a new scene's boilerplate** — scaffold it. This creates the Scene `.java`, the launch.json entry, and (optionally) a Maven profile in one shot:

```
uv run ixdar-cli new-scene --name FooScene --id foo-canvas --subfolder ui \
  --display-name "Foo" --base Scene --camera 3d [--maven-profile foo-scene] [--dry-run]
```

## Seeing a render yourself — always `ixdar-cli run-scene`

**Never hand-roll a JVM launch.** Do NOT `setsid java … ixdar.canvas.IxdarWindow <id>`, `mvn exec:java`, or a raw `curl`/`until` health-poll loop to bring a scene up. `run-scene` is the one supported entry point: it builds, launches, waits for `sceneReady` (not just an open port), screenshots, and shuts the JVM down cleanly — no orphaned processes, no port collisions, no stale-classpath surprises. Hand-rolled `java` also picks up whatever `.class` files the IDE's incremental (ECJ) build last wrote, which can be broken even when `mvn` is green.

```
uv run ixdar-cli run-scene --scene <scene-id> [--property key=value ...] [--mesh <name>]
```

It **always runs headless** — an off-screen GL context, never a window on the desktop, so it can't interrupt whatever you're doing. The headless platform loads textures and fonts, so screenshots render text (terminal, ESC menu) just like the desktop window. Pick the model with `--property ixdar.model=<token>` or `--mesh <name>`. Run `uv run ixdar-cli run-scene --help` for the flags — output path, `--timeout`, `--profile` (see Profiling), and how to keep the scene alive (`--keep-alive`) when you need to inject input (`ixdar-cli key`/`type`/`click`/`screenshot` against the running server) rather than a one-shot capture.

Then `Read` the PNG. **Do not write a per-scene headless renderer** (`RenderEmbeddedTMesh` and the like were a wrong turn); feed the scene's own `QuadLayoutRuntime`/overlays and screenshot it. `ixdar-cli multiview <png>` composites an 8-angle grid under the image limit.

Do not hand-roll a bespoke visualizer (an SVG unwrap, a custom exporter): the runtime already draws meshes, arcs, and node markers on the surface.

> The automation server had four latent bugs (it had never actually served a request): the `Canvas3D`/`MenuBox`/`KeyGuy`/`MouseTrap` reflection pointed at the pre-move package (missing `endpoints`), `@AutomationRouteAnnotation` was `@Retention(CLASS)` instead of `RUNTIME`, `AutomationApiServer.registerAll` didn't prefix paths with `/` or group GET+POST on one path, and route `runtime` was never injected. All fixed. If automation breaks again, suspect one of these.

# Automation

<!-- BEGIN-GENERATED: automation-cli -->
_Generated by `ixdar-cli gen-docs` from Java route `describe()` and CLI command docstrings._
_Do not edit by hand; see [ixdar_automation_cli/README.md](ixdar_automation_cli/README.md)._

Run any command with `ixdar-cli <command> --help`. Install the global alias with `ixdar-cli install-alias` (or `bash tools/install-cli.sh`).

**Server-backed commands** (generated from the automation routes manifest):
- `ixdar-cli click [--x] [--y] [--normalized] [--button]` — Move the cursor to a point then issue a press/release click on the active mouse handler.
- `ixdar-cli health` — Liveness probe reporting server status, recording/replaying flags, and port.
- `ixdar-cli hover [--x] [--y] [--normalized] [--persistent]` — Move the cursor without clicking, optionally installing a persistent hover lock.
- `ixdar-cli hover-clear` — Release the persistent automation hover lock on the active trade mouse handler.
- `ixdar-cli key [--key] [--action] [--mods] [--scancode]` — Synthesize a single GLFW key event on the active key handler.
- `ixdar-cli mesh-compare --reference [--distance-type] [--scale] [--normalize]` — Compare the active viewer mesh against a reference OBJ using Hausdorff and Chamfer metrics.
- `ixdar-cli mesh-dsl --name [--node] [--port]` — Load and execute a named DSL skill graph, making its output geometry the active mesh.
- `ixdar-cli mesh-dsl-timing` — Report per-node execution times from the most recent DSL graph run.
- `ixdar-cli mesh-dsl-validate --dsl [--export]` — Validate DSL source text against the skill schema, optionally probing and exporting its output mesh.
- `ixdar-cli mesh-fingerprint` — Compute the canonical SHA-256 fingerprint of the active viewer mesh.
- `ixdar-cli mesh-patches-decompose --path [--resolution]` — Hybrid skeleton and curvature patch decomposition of a reference mesh.
- `ixdar-cli mesh-patches-render-flat-multiview --path [--resolution] [--out-path]` — Decompose a mesh into semantic patches and render a flat-shaded multiview composite PNG.
- `ixdar-cli mesh-patches-render-multiview --path [--resolution] [--out-path]` — Decompose a mesh into semantic patches and render a shaded multiview composite PNG.
- `ixdar-cli mesh-segmentation --path [--method] [--n-clusters]` — Segment a mesh into labeled vertex groups by connected components, curvature, or spatial clustering.
- `ixdar-cli mesh-skeleton-compare --generated --reference [--resolution]` — Compare TEASAR skeletons of two meshes and recommend parameter fixes.
- `ixdar-cli mesh-skeleton-compare-detailed --generated --reference [--resolution]` — Detailed skeleton comparison returning per-joint 3D position deltas.
- `ixdar-cli mesh-skeleton-sensitivity --dsl --reference [--resolution] [--epsilon]` — Compute the Jacobian of skeleton joints w.r.t. DSL parameters.
- `ixdar-cli multiview [--out] [--inline]` — Capture 8 orbit viewpoints and composite them into a labeled 4x2 grid PNG.
- `ixdar-cli orbit-get` — Report the active mesh viewer's current camera orbit and mesh radius.
- `ixdar-cli orbit-set [--azimuth] [--elevation] [--distance]` — Set the active mesh viewer's camera orbit (azimuth, elevation, distance).
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
- `ixdar-cli screenshot [--out] [--inline]` — Capture a PNG screenshot of the current framebuffer to a file.
- `ixdar-cli scroll [--delta]` — Deliver a synthesized scroll event to the active mouse handler.
- `ixdar-cli shutdown` — Acknowledge, then asynchronously close the canvas and exit the process.
- `ixdar-cli type [--text]` — Synthesize character events on the active key handler, one per character of the text.
- `ixdar-cli ui-state` — Snapshot the full UI state: window, scene, trade, mesh, text, menu, and audio.

**CLI commands** (client-side scenarios, tools, and utilities):
- `ixdar-cli assert-tooltip` — Assert that the visible tooltip text contains the requested strings.
- `ixdar-cli audio-log` — Return recent audio log events.
- `ixdar-cli audio-state` — Extract the audio state from the UI snapshot.
- `ixdar-cli click-scan` — Click through a grid until the scene leaves the menu.
- `ixdar-cli coverage-report` — Merge JaCoCo exec files and report the code they never executed.
- `ixdar-cli dsl-optimize` — Batch-optimize mesh DSL parameters against a reference OBJ.
- `ixdar-cli duplication-report` — Report duplicated code ranked by how much repetition factoring it out would remove.
- `ixdar-cli gen-docs` — Regenerate the CLAUDE.md command list and the CLI README from the manifest and registry.
- `ixdar-cli install-alias` — Install a global ixdar-cli wrapper into ~/.local/bin.
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
- `ixdar-cli start-new-game` — Leave the menu by clicking Start New Game.
- `ixdar-cli trade-hover-scan` — Scan trade cities until the requested toolbar tooltip appears.
- `ixdar-cli validate-route-ops` — Validate trade route operations against the running app.
<!-- END-GENERATED: automation-cli -->
