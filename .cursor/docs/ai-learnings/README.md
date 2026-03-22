# AI Learnings

Staging area for engineering learnings. High-signal patterns get promoted into always-applied rules.

## Format

Every file has YAML frontmatter with structured metadata:

```yaml
---
title: Short descriptive title
category: performance | architecture | automation | tooling | build | playbook
severity: critical | high | medium | low
modules: [graphics, platform]    # which codebase areas
tags: [rendering, buffers, opengl] # searchable keywords
promoted_to: ixdar-coding-standards.mdc  # if promoted (optional)
---
```

## Lifecycle

1. **Write** a learning after significant work (use `templates/learning-template.md`).
2. **Search before writing** -- grep this folder for related tags/keywords first.
3. **Promote or prune** -- if a pattern generalizes, distill it into the appropriate always-applied rule (`ixdar-coding-standards.mdc`, `ixdar.mdc`) and add `promoted_to:` to the frontmatter.
4. **Delete** learnings that are fully captured in rules and no longer add context beyond the rule.

## Structure

- Root files: individual learnings (YAML frontmatter required)
- `playbooks/` for repeatable procedural guidance
- `templates/` for the standard write-up format

## Searching

Tags and categories make grep-based retrieval effective:

```bash
rg "tags:.*rendering" .cursor/docs/ai-learnings/
rg "category: performance" .cursor/docs/ai-learnings/
rg "severity: critical" .cursor/docs/ai-learnings/
```
