## Context
Audio packaging originally listed individual files in `ixdar-app/pom.xml`, which made every new track require a POM edit and increased churn/risk during content updates.

## Decision
Use Maven resource globs with a single property-backed include pattern (for example `**/*.wav`) for asset ingestion from `${ixdar.asset.repo.root}` into classpath target folders (`res/audio/music`, `res/audio/sfx`).

## Evidence
- `ixdar-app/pom.xml` now uses `${ixdar.audio.asset.include.pattern}` instead of individual filenames.
- `mvn -pl ixdar-app -am -DskipTests test-compile` shows wildcard copy behavior (multiple resources copied from Music/Sfx).

## Reuse trigger
Any time a feature needs packaged runtime assets (audio, data packs, scripted content), prefer property-driven Maven glob patterns over per-file declarations.

## Anti-pattern
Hardcoding individual asset filenames in POM resource blocks, which creates repetitive maintenance and frequent build config edits for simple content changes.
