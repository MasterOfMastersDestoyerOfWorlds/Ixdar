---
description: Create an isolated git worktree from HEAD and start working on a task in it
argument-hint: <worktree-name> <task description…>
---

The user invoked `/worktree` with the following arguments:

$ARGUMENTS

Interpret the **first whitespace-delimited token** as the worktree name, and
**everything after it** as the task to work on. If only one token was given,
treat it as the name and ask what the task is before proceeding.

1. Call the `EnterWorktree` tool with `name` set to the worktree name. This
   creates an isolated git worktree under `.claude/worktrees/`, branches from
   the current HEAD (per the project's `worktree.baseRef: head` setting), and
   switches this session into it.
2. State which worktree directory and branch you are now in.
3. Begin working on the described task inside the worktree. This repo's
   permission rules deny `git commit`/`git add`/`git checkout`, so do not try
   to commit — leave changes in the worktree for review. When the work is done,
   remind the user they can keep or discard it with `ExitWorktree`.
