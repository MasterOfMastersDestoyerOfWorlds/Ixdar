# AI Workflow for Ixdar

This repo uses a compounding workflow for AI-assisted engineering:

1. Plan
2. Work
3. Review
4. Compound

The goal is to ensure each task leaves reusable knowledge and safer defaults.

## Cursor Integration

- Rules: `.cursor/rules/`
- Commands: `.cursor/commands/`
- Hooks: `.cursor/hooks.json` and `.cursor/hooks/*.py`

## Operating Model

- Use `/workflows-plan` before non-trivial implementation.
- Use `/workflows-work` while executing approved plans.
- Use `/workflows-review` before finalizing.
- Use `/workflows-compound` to write learnings into `docs/ai-learnings/`.

## Compounding Standard

For meaningful work, add or update a note in `docs/ai-learnings/` with:

- Context
- Decision
- Evidence
- Reuse trigger
- Anti-pattern
