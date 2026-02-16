# AI Workflow for Ixdar

This repo uses a compounding workflow for AI-assisted engineering:

1. Plan (search learnings first, then plan)
2. Work
3. Review
4. Compound (search, capture, triage/promote)

The goal is to ensure each task leaves reusable knowledge and safer defaults.

## Cursor Integration

- Rules: `.cursor/rules/`
- Commands: `.cursor/commands/`
- Hooks: `.cursor/hooks.json` and `.cursor/hooks/*.py`

## Operating Model

- Use `/workflows-plan` before non-trivial implementation. **Plan searches `docs/ai-learnings/` first.**
- Use `/workflows-work` while executing approved plans.
- Use `/workflows-review` before finalizing.
- Use `/workflows-compound` to capture learnings. **Compound searches before writing, then triages: promote to rule or stage.**

## Learnings System

Learnings in `docs/ai-learnings/` use YAML frontmatter with `title`, `category`, `severity`, `modules`, and `tags` for machine-searchable retrieval. This folder is a **staging area**, not a permanent archive:

- **Promote** general patterns into always-applied rules (`ixdar-coding-standards.mdc`, `ixdar.mdc`).
- **Stage** one-off gotchas and feature-specific patterns as learning files.
- **Prune** learnings that have been fully captured in rules.

See `docs/ai-learnings/README.md` for format details and search examples.
