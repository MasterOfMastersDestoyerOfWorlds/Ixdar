---
title: Filesystem as Single Source of Truth for Ticket-to-Epic Membership
category: architecture
severity: medium
modules: [tickets, build-tools]
tags: [data-modeling, tickets, filesystem, single-source-of-truth]
---

# Filesystem as Single Source of Truth for Ticket-to-Epic Membership

## Context
`epics.json` had a `children` array manually listing ticket IDs per epic. This was a second source of truth alongside the actual `tickets/content/<PREFIX>/` directory structure. The list was already stale (missing UI-5, UI-6, TRADE-11 through TRADE-20). It was originally used by Hugo templates, but the Hugo site was unused.

## Decision
Removed `children` from `epics.json` and deleted the entire Hugo site (`layouts/`, `static/`, `hugo.toml`). The board generator discovers tickets by globbing `tickets/content/*/*.json` and groups by prefix extracted from the ticket ID. Epic metadata (name, priority, description) stays in `epics.json`.

## Evidence
The `children` list was missing 12+ tickets that had been created since the original batch. The generator never used it -- filesystem glob was always the real discovery mechanism.

## Reuse Trigger
When adding a new data relationship, ask: "Is there already a filesystem convention that encodes this?" If tickets live in `ENG/`, that's the membership. Don't duplicate it in a JSON array.

## Anti-pattern
Manual index arrays that mirror directory structure. They drift immediately and create a maintenance tax on every ticket creation.
