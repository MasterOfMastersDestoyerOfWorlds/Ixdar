---
name: test-review
description: Review Ixdar's test suite test by test and decide what is worth keeping (keep, shrink, rewrite, merge, move or delete), against criteria in criteria.md that favour mesh invariants on DSL-built primitives and a whole suite under 5 seconds. Two roles. The coordinator (Opus) times the suite, splits it into batches and merges the reports. The reviewers (Sonnet subagents) each judge a batch of test classes, read-only. Use when asked to audit, prune or speed up the tests, to judge whether a test is worth keeping, or when acting as either role.
---

# Test review

`criteria.md` next to this file holds the criteria, the verdicts and the report format. Both roles
read it in full before starting.

## Roles and models

| Role | Model | Does |
|---|---|---|
| Coordinator | Opus 5.5: the session that loaded this skill | times the suite, splits it into batches, briefs the reviewers, checks their reports, merges them |
| Reviewer | Sonnet: an Agent-tool subagent with `model: "sonnet"` | judges every test method in its batch against `criteria.md`; edits nothing |

The coordinator never reviews classes itself, and it never gives a reviewer a model other than
Sonnet. Haiku is too weak for criteria 7, 8 and 17, which mean tracing callers and reasoning
about whether a test duplicates its code. Opus is kept for coordinating.

## Coordinator

1. **Time the suite once.** In the worktree, run `mvn test > tmp/test-review-baseline.log 2>&1`,
   then read the per-class and per-method times from
   `ixdar-app/target/surefire-reports/TEST-*.xml`. Reviewers don't run Maven: several Maven runs
   in one worktree overwrite each other's `target/`. Record the failing tests too.
2. **Make the batches.** Every class over 1 s gets a batch of its own. Group the rest into batches
   of about 2000 lines of test source, keeping a package together. Put all of `test/benchmark/` in
   one batch, reviewed against section D, and all golden tests in one batch, reviewed against
   section C.
3. **Brief each reviewer.** Give it the worktree path, the list of classes, those classes' times
   from step 1, and the path to `criteria.md`. Tell it to edit nothing and run no Maven, and to
   return the report in the format of section E. Run up to 5 reviewers at a time.
4. **Check every report before accepting it.** Every method in the class has a verdict. Every
   verdict names its criteria. Every MERGE names a test that exists. Every claim of
   "only the test calls this" (criterion 8) or "dead code" (criterion 15) has the grep that shows
   it. Send a report that fails any of these back to the same reviewer (SendMessage).
5. **Merge the reports** into `tmp/test-review-report.md`:
   - A totals table: verdict count, and the time each verdict accounts for.
   - The projected suite time if every non-KEEP verdict is carried out.
   - The verdicts, slowest class first.
   - The **DSL GAP** list (criterion 9).
   - The golden classification (section C).
   - The benchmark list (section D).
   - The static-check candidates the reviewers noted, deduplicated.

## Reviewer

- **Read the whole test class**, plus each production method it calls, one level deep.
- **For criteria 7, 8 and 15, grep for callers** under `ixdar-app/src/main/java` and include the
  command and its result count in the reason.
- **Decide one verdict per method.** When two verdicts apply, take the stronger one: DELETE over
  MERGE over REWRITE over SHRINK over MOVE over KEEP.
- **For REWRITE or SHRINK, sketch the replacement concretely:** the invariant or bound it
  asserts, and the DSL fixture it loads (an existing file under `dsl/fixtures/`, or a new one
  you name and describe).
- **A method citing criterion 9 is never KEEP.** That covers geometry built in Java, including
  through a shared helper or another test class's builder, and it makes the verdict at least
  REWRITE. When one fixture swap fixes a whole class, say so once in the summary.
- **Don't SHRINK on warm-up.** The first method listed for a class ran first, so up to about
  100 ms of its time may be class loading. Don't SHRINK a method on that time alone.
- **Flag DSL GAPs** whenever the operation under test has no DSL node or parameter that reaches
  it.
- **Don't edit, build or run tests.** The verdicts are input for a later ticket.
