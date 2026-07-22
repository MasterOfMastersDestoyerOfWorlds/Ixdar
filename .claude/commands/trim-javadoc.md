---
description: Trim over-long Javadoc descriptions in a set of files until JavadocDescriptionLengthCheck passes
argument-hint: <file-or-directory> [more files…]
---

The user invoked `/trim-javadoc` with:

$ARGUMENTS

Treat each whitespace-delimited token as a file or directory to work on. Work **only** on those
files. If none were given, ask which files before proceeding.

## What you are fixing

`JavadocDescriptionLengthCheck` caps a Javadoc **description** at 50 words. It counts only the prose
before the first block tag, so `@param`, `@return` and `@throws` are never counted and must be left
complete — `JavadocMethod` requires every one of them to be present and non-empty, and
`NonEmptyAtclauseDescription` requires each to have real text. Removing a tag to save words is
always wrong.

Run this to see your violations:

```
mvn -f ixdar-app/pom.xml checkstyle:check -Dcheckstyle.failOnViolation=false 2>&1 \
  | grep JavadocDescriptionLength | grep <your-file>
```

## What a good Javadoc says here

Say what the member is **for**, and what a caller **must know** to use it correctly. One or two
well-considered sentences. Prefer the constraint a caller would otherwise violate over a restatement
of the signature.

```java
/**
 * The edge id between two copy vertices, or {@link #UNCLAIMED} when they are not adjacent.
 *
 * @param vertexA first endpoint
 * @param vertexB second endpoint
 * @return the edge id, or {@link #UNCLAIMED}
 */
```

With a precondition and a paper pointer, both of which earn their place:

```java
/**
 * Collapses a zero arc, moving its non-critical node onto the other and dragging the incident
 * arcs with it. Both endpoints critical is a quantization fault and throws.
 *
 * <p>See also: LCBK19 Section 6.1
 *
 * @param arcId zero arc to collapse
 * @throws IllegalStateException when neither endpoint may move
 */
```

**Keep** — these earn their space:

- A **non-obvious invariant or precondition** a caller can break. "The release has to happen before
  the claim: an arc that keeps most of its old lane would otherwise collide with itself."
- A **paper reference**, as a bare pointer on its own line at the **end** of the description, in
  exactly this form — identifier and section, nothing else:

  ```
  * See also: LCBK19 Section 6.1
  ```

**Cut** — this is what has accumulated and what you are here to remove:

- **Quotations from papers.** However apt. Replace the whole quote with the `See also:` pointer
  above; a reader who needs the wording has the paper. This alone will account for much of what you
  delete.
- **Justification of choices.** Why one approach was picked over another, why something is correct,
  why a design is the right one. The code is correct if it works, and the tests say whether it
  works. Do not translate a justification into a shorter justification — remove it.
- **History.** "used to", "previously", "no longer", "originally", "turned out", "was invented
  here", "an earlier version". How the code got here belongs in the commit and in git blame.
- **Measurements.** "31% of all CPU", "3322 operators", "6800 samples", "quadratic on fertility".
  Profile numbers go stale the moment anything changes, and they are not a caller's concern.
- **Benchmark names.** "the sphere reaches a fixed point of 294 collapses", "fertility dies at
  collapse 140", "rockerarm". Naming a specific test mesh in an API doc is a sign the text is a
  lab notebook entry.
- **Editorial voice.** "we", "I", "our". No narrator.
- **Restating the code.** If the sentence can be derived from the signature, delete it.

**Report, do not document** — if a doc exists to warn about a **hidden coupling** between this
member and another that the types do not express, that coupling is usually a design problem rather
than a documentation problem. Cut the text to the bare precondition a caller needs, and name the
member in your report so the coupling can be looked at. Do not "fix" it by writing a better
paragraph, and do not change the code to address it.

## How to do it

1. Read the whole Javadoc before cutting. Decide what single fact a caller genuinely needs.
2. Rewrite the description to one or two sentences carrying that fact. Do not merely truncate — a
   half-sentence is worse than a long one.
3. Leave every block tag intact and complete.
4. **Do not change code.** Comments and Javadoc only. No signatures, no logic, no renames, no
   reordering of members. If you believe code is wrong, say so in your report and leave it.
5. If a genuinely load-bearing explanation cannot fit in 50 words — a real algorithmic invariant, a
   derivation the reader cannot reconstruct — keep it and report the member. Do not delete
   substance to satisfy a word count. The rule is a smell detector, not a truth.

## Verify before you finish

```
mvn -f ixdar-app/pom.xml checkstyle:check   # your files must be clean
mvn -f ixdar-app/pom.xml test               # must stay green
```

Checkstyle must pass for the files you touched, and the suite must stay green — comment edits cannot
break tests, so a failure means you changed code by accident.

## Report back

- Which files you trimmed, and how many violations each had before and after.
- Any member where you kept a long description, and the reason.
- Anything you noticed that looked wrong in the code but did not touch.

Do not touch files outside your assignment; other agents are working on theirs in parallel.
