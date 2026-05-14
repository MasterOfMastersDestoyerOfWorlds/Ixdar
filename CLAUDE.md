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

If a method needs a paragraph of Javadoc to explain a single parameter, the parameter is probably wrong — split the method or rename. A long doc is a smell, not a fix.

## Method shape

- One public entry point per pipeline class (e.g. `build()`); internals are private and called in dependency order.
- Don't interleave responsibilities across classes — if `A.foo()` calls `B.bar()` calls `A.baz()` calls `B.qux()`, the class boundary is in the wrong place.
- Don't add a parameter that exists for only one of N callers without a clear name and a one-line Javadoc that says when `-1` (or whatever sentinel) means "default."

### Class layout

- **One top-level class per file.** No exceptions for "small" companion classes.
- **Avoid nested classes.** Inner classes, static nested classes, and anonymous classes hide structure inside other files and resist refactoring. If you need a nested class, that's the signal to promote it to its own top-level file.
- **Avoid records.** Prefer a regular class with `public final` fields. (Records read fine in isolation but in practice they accumulate carve-outs — custom `equals`, validation in compact constructors, escape-analysis worries on hot paths — at which point they're a normal class wearing a costume.)

### Field visibility

Prefer `public` (or `public final` / `public static final`) for fields. **Avoid package-private and `private` instance fields** — they create refactor friction (visibility juggling every time you split or merge classes) without buying meaningful encapsulation in this codebase. If you find yourself reaching for `private` on a field, ask whether the class boundary is in the right place instead.

Methods are the opposite default: `private` unless the class genuinely exposes them as API.

### Single-caller private methods

Default: **don't extract them.** A private method that is only ever called from one place earns its existence only by being (a) a complete logical unit you can name without referring to its caller, and (b) substantial — roughly 10+ lines, or non-trivial enough that inlining would meaningfully hurt readability. "I like helper methods" is not a reason; the helper is then just an indirection layer hiding the real flow.

**A loop body is not a helper.** Extracting `perFooThing(int i)` so the caller can write `for (...) perFooThing(i)` adds no information — the loop and its body already say "do this per element." Keep the work inline. Helpers exist to name a *concept*, not to shave lines off a loop. The same goes for `if`-branch bodies: don't extract a 6-line method just because it's the body of one branch.

When a single-caller helper genuinely is warranted (see `CutGraph.java` for the rare cases), place it:

- **Adjacent to its caller**, not in some "helpers section" at the bottom.
- **In call order** down the file, so a top-down read of the class follows the runtime path.

This rule has a custom checkstyle module (`SingleCallerHelperCheck`, currently disabled in `~/Code/autofix`) — treat it as the policy regardless.

## Other checkstyle rules to know

The build is checked against `~/Code/autofix/src/main/resources/checkstyle.xml`. Beyond Javadoc, the rules that bite most often:

- **Declaration order** (`DeclarationOrder`): static fields → instance fields → constructors → methods. Within each field bucket, `public → protected → package → private`. The autofix recipe reorders this for you, but write it right the first time.
- **Magic numbers:** literals other than `-1, 0, 1, 2` must be named constants. Field initializers and annotations are exempt.
- **Duplicated string literals:** the same string repeated more than once should be a constant.
- **No inline fully-qualified class names:** write `Collectors.toSet()` with an import, not `java.util.stream.Collectors.toSet()`. The only exception is genuine simple-name collisions across packages.
