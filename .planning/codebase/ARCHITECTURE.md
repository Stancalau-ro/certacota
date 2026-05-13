<!-- refreshed: 2026-05-13 -->
# Architecture

**Analysis Date:** 2026-05-13

## System Overview

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                     Claude Code Session (runtime)                        │
├─────────────────┬─────────────────┬────────────────┬────────────────────┤
│  Slash Commands │     Agents      │     Skills     │  GSD Framework     │
│ `.claude/       │  `.claude/      │  `.claude/     │  `.claude/         │
│  commands/`     │   agents/`      │   skills/`     │   get-shit-done/`  │
└────────┬────────┴────────┬────────┴───────┬────────┴────────┬───────────┘
         │                 │                │                 │
         ▼                 ▼                ▼                 ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         Hooks Layer                                      │
│                   `.claude/hooks/`                                       │
│  UserPromptSubmit  │  PreToolUse  │  PostToolUse  │  Stop  │ SessionStart│
└─────────────────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     Configuration                                        │
│  `.claude/settings.json`     `.claude/skills/skill-rules.json`          │
└─────────────────────────────────────────────────────────────────────────┘
```

## Component Responsibilities

| Component | Responsibility | Location |
|-----------|----------------|----------|
| Slash Commands | Invoke specific GSD workflows; delegate to workflow files via `@` references | `.claude/commands/gsd/*.md`, `.claude/commands/*.md` |
| GSD Workflows | Contain the actual step-by-step logic for each command | `.claude/get-shit-done/workflows/*.md` |
| GSD References | Reusable building blocks loaded by workflows via `@` includes | `.claude/get-shit-done/references/*.md` |
| GSD Templates | Scaffold files written to `.planning/` by workflow commands | `.claude/get-shit-done/templates/*.md` |
| Agents | Autonomous Claude sub-instances for complex multi-step tasks | `.claude/agents/*.md` |
| Skills | Domain knowledge bases injected as context before responses | `.claude/skills/*/SKILL.md` |
| Skill Rules | Trigger configuration mapping prompts/files to skills | `.claude/skills/skill-rules.json` |
| Hooks (TypeScript) | Lifecycle scripts for Spring Boot-specific guardrails | `.claude/hooks/*.ts` |
| Hooks (GSD JS/sh) | Lifecycle scripts for GSD workflow guards and session monitoring | `.claude/hooks/gsd-*.js`, `.claude/hooks/gsd-*.sh` |
| Settings | Hook registrations and Claude Code permissions | `.claude/settings.json` |

## Pattern Overview

**Overall:** Reference Library with Layered Lifecycle Interception

This repository is not a runnable application. It is a collection of Claude Code infrastructure components — commands, agents, skills, and hooks — designed to be copied into Spring Boot 3.x projects. The architecture is a two-layer system:

1. **Skills + Hooks layer** (original certacota components): auto-activate domain knowledge and enforce Spring Boot best practices via lifecycle events
2. **GSD Framework layer** (bundled `get-shit-done` framework): provides a full project management workflow system (phases, milestones, planning, execution) via 60+ slash commands backed by markdown workflow definitions

**Key Characteristics:**
- No source code to compile or run — all components are markdown and TypeScript/JavaScript files
- Hooks intercept Claude Code lifecycle events (stdin/stdout JSON protocol)
- Skill activation is data-driven via `skill-rules.json` (keyword + regex matching)
- Commands are thin wrappers that delegate to workflow files via `@` includes
- Agents are self-contained markdown files with YAML frontmatter

## Layers

**Commands Layer:**
- Purpose: Entry points invoked by the user via `/slash-command` syntax
- Location: `.claude/commands/` and `.claude/commands/gsd/`
- Contains: Markdown files with YAML frontmatter (`description`, `argument-hint`, `allowed-tools`)
- Depends on: GSD workflow files via `@` include references
- Used by: User directly

**GSD Workflow Layer:**
- Purpose: Contains the actual step-by-step execution logic for each command
- Location: `.claude/get-shit-done/workflows/`
- Contains: Markdown workflow definitions, conditionals, subagent spawning instructions
- Depends on: References (shared logic), Templates (file scaffolds), Contexts (mode defaults)
- Used by: Commands layer

**GSD References Layer:**
- Purpose: Reusable logic fragments loaded by workflows as needed
- Location: `.claude/get-shit-done/references/`
- Contains: 50+ reference documents (TDD, planning config, verification patterns, gate prompts, etc.)
- Depends on: Nothing (leaf nodes)
- Used by: GSD workflows

**Agents Layer:**
- Purpose: Autonomous sub-instances for complex, multi-step tasks
- Location: `.claude/agents/`
- Contains: Markdown files with YAML frontmatter (`name`, `description`, `model`, `color`, `tools`)
- Two groups: original certacota agents (11) and GSD agents (20+, prefixed `gsd-`)
- Depends on: Nothing (standalone); some load skill context at runtime
- Used by: User directly or spawned by GSD workflow commands

**Skills Layer:**
- Purpose: Domain knowledge bases injected into Claude's context when relevant
- Location: `.claude/skills/`
- Contains: Skill directories, each with a `SKILL.md` and optional `resources/` subdirectory
- Skills: `backend-dev-guidelines` (11 resource files), `skill-developer` (7 reference files), `error-tracking`, `git-workflow`, `product-owner`, `route-tester`
- Depends on: `skill-rules.json` for trigger configuration
- Used by: `skill-activation-prompt.ts` hook at `UserPromptSubmit` time

**Hooks Layer:**
- Purpose: Intercept Claude Code lifecycle events to enforce guardrails and inject context
- Location: `.claude/hooks/`
- Runtime: `npx tsx` (TypeScript hooks), `node` (GSD JS hooks), `bash` (GSD shell hooks)
- Depends on: `skill-rules.json` (skill activation), `.planning/config.json` (GSD guards), stdin JSON
- Used by: Claude Code runtime (registered in `settings.json`)

## Data Flow

### Skill Auto-Activation (UserPromptSubmit)

1. User submits a prompt
2. Claude Code fires `UserPromptSubmit` → runs `.claude/hooks/skill-activation-prompt.ts`
3. Hook reads stdin JSON with `{ prompt, session_id, cwd }` from Claude Code
4. Hook loads `.claude/skills/skill-rules.json`
5. Hook matches prompt against `keywords` (substring) and `intentPatterns` (regex) for each skill
6. Matched skills are grouped by priority and printed to stdout as context injected before Claude's response
7. Simultaneously, `request-clarity-check.ts` runs in parallel — analyzes prompt for vague patterns and injects clarifying question suggestions

### Request Clarity Check (UserPromptSubmit)

1. Same `UserPromptSubmit` event triggers `request-clarity-check.ts`
2. Hook tests the prompt against `VAGUE_PATTERNS` (regex list for "fix the bug", "add feature", etc.)
3. If the prompt matches a vague pattern AND has no context indicators AND is short → injects suggested clarifying questions
4. Always exits 0 (advisory only, never blocks)

### Spring Boot Code Quality Check (Stop)

1. Claude finishes responding (`Stop` event)
2. `error-handling-reminder.ts` reads `~/.claude/tsc-cache/{session_id}/edited-files.log`
3. If any edited `.java` files in the session match controller/service/repository patterns
4. Hook reads the actual file content and checks for missing `@Slf4j`, `@Transactional`, `@ControllerAdvice`
5. Prints targeted reminders to stdout

### GSD Context Monitoring (PostToolUse)

1. After each tool use that matches `Bash|Edit|Write|MultiEdit|Agent|Task`
2. `gsd-context-monitor.js` reads metrics from `/tmp/claude-ctx-{session_id}.json` (written by statusline hook)
3. If remaining context drops below 35%, injects an advisory warning via `additionalContext`
4. If critical (below 25%) and a GSD project is active, spawns `gsd-tools.cjs state record-session` to save state

### GSD Write Guards (PreToolUse)

1. Before any `Write` or `Edit` tool call:
   - `gsd-prompt-guard.js`: scans content being written to `.planning/` for prompt injection patterns
   - `gsd-read-guard.js`: warns if the file already exists and hasn't been read (for non-Claude-Code runtimes only)
   - `gsd-workflow-guard.js`: advises to use a `/gsd-` command if workflow guard is enabled in `config.json`
2. Before any `Bash` tool call:
   - `gsd-validate-commit.sh`: blocks git commits that don't follow Conventional Commits format (opt-in via `hooks.community: true`)

### GSD Session Start (SessionStart)

1. `gsd-check-update.js`: checks for GSD framework version updates, spawns background worker
2. `gsd-session-state.sh`: reads `.planning/STATE.md` and injects a project state reminder (opt-in)

## Key Abstractions

**Skill:**
- Purpose: A named domain knowledge base that activates automatically based on prompt or file context
- Structure: Directory under `.claude/skills/{name}/` containing `SKILL.md` plus optional `resources/` files
- Pattern: Progressive disclosure — `SKILL.md` stays under 500 lines; detailed content lives in `resources/*.md`
- Examples: `.claude/skills/backend-dev-guidelines/SKILL.md`, `.claude/skills/skill-developer/SKILL.md`

**Hook:**
- Purpose: A process that intercepts a Claude Code lifecycle event, reads JSON from stdin, optionally writes JSON to stdout
- Protocol: Reads `{ session_id, prompt, tool_name, tool_input, cwd, ... }` from stdin; exits 0 (advisory), exits 2 (block), or writes `{ hookSpecificOutput: { additionalContext: "..." } }` to stdout
- Examples: `.claude/hooks/skill-activation-prompt.ts`, `.claude/hooks/gsd-context-monitor.js`

**Agent:**
- Purpose: A self-contained markdown file describing how Claude should behave as an autonomous sub-instance
- Structure: YAML frontmatter (`name`, `description`, `model`, `tools`, `color`) + markdown instructions
- Pattern: Standalone — no external dependencies beyond the markdown file itself
- Examples: `.claude/agents/code-architecture-reviewer.md`, `.claude/agents/gsd-planner.md`

**GSD Command:**
- Purpose: A thin slash command entry point that loads workflow logic via `@` file includes
- Structure: YAML frontmatter + `<execution_context>` block with `@path/to/workflow.md` includes
- Pattern: Commands never contain logic directly; they reference workflow files
- Examples: `.claude/commands/gsd/execute-phase.md`, `.claude/commands/gsd/plan-phase.md`

## Entry Points

**User-facing slash commands:**
- Location: `.claude/commands/*.md` (original certacota: `dev-docs.md`, `dev-docs-update.md`, `route-research-for-testing.md`)
- Location: `.claude/commands/gsd/*.md` (GSD framework: 60+ commands including `plan-phase.md`, `execute-phase.md`, `debug.md`, etc.)
- Triggers: User types `/command-name [arguments]`

**Hook entry points (auto-triggered by Claude Code):**
- `UserPromptSubmit`: `.claude/hooks/skill-activation-prompt.ts`, `.claude/hooks/request-clarity-check.ts`
- `Stop`: `.claude/hooks/error-handling-reminder.ts`
- `SessionStart`: `.claude/hooks/gsd-check-update.js`, `.claude/hooks/gsd-session-state.sh`
- `PostToolUse` (Bash|Edit|Write|MultiEdit|Agent|Task): `.claude/hooks/gsd-context-monitor.js`
- `PostToolUse` (Read): `.claude/hooks/gsd-read-injection-scanner.js`
- `PostToolUse` (Write|Edit): `.claude/hooks/gsd-phase-boundary.sh`
- `PreToolUse` (Write|Edit): `.claude/hooks/gsd-prompt-guard.js`, `.claude/hooks/gsd-read-guard.js`, `.claude/hooks/gsd-workflow-guard.js`
- `PreToolUse` (Bash): `.claude/hooks/gsd-validate-commit.sh`

## Architectural Constraints

- **No runnable application:** There is no main class, build artifact, or deployment target. Components exist only to be copied into target Spring Boot 3.x projects.
- **Hook protocol:** Hooks communicate exclusively via stdin/stdout JSON. Exit code 0 = success/advisory; exit code 2 = block (PreToolUse only); stdout JSON with `hookSpecificOutput.additionalContext` injects text into Claude's context.
- **Hook independence:** Each hook file is fully self-contained. GSD JS hooks are plain CommonJS (`require()`), not ESM, to avoid module resolution complexity. TypeScript hooks use strict ESM (`"type": "module"` in `package.json`).
- **Skill 500-line rule:** Every `SKILL.md` must stay under 500 lines. Detail lives in `resources/` reference files (progressive disclosure pattern from Anthropic best practices).
- **settings.json is not copied as-is:** `CLAUDE_INTEGRATION_GUIDE.md` explicitly warns against copying `settings.json` wholesale; users must extract and merge only the hook sections they want.
- **GSD community hooks opt-in:** `gsd-session-state.sh` and `gsd-validate-commit.sh` are no-ops unless `.planning/config.json` has `"hooks": { "community": true }`. This prevents them from running in non-GSD projects.
- **Windows compatibility:** All hook commands in `settings.json` use `node`, `npx tsx`, or `bash` with absolute path via `$CLAUDE_PROJECT_DIR`. PowerShell is the target shell for integration documentation but hooks themselves use bash-compatible scripts.

## Anti-Patterns

### Copying settings.json directly
**What happens:** A user copies `.claude/settings.json` from this repo into their project as-is.
**Why it's wrong:** The Stop hooks in the original config reference example service paths and modules that don't exist in the target project, causing hook errors on every session.
**Do this instead:** Extract only the `UserPromptSubmit` and relevant `PostToolUse`/`PreToolUse` hook entries. Merge with the target project's existing `settings.json`. See `CLAUDE_INTEGRATION_GUIDE.md` for the merge process.

### Activating all skills at once
**What happens:** All 6 skills from `skill-rules.json` are added to a target project's configuration.
**Why it's wrong:** Some skills (e.g., `backend-dev-guidelines`, `route-tester`) are Spring Boot-specific. If the target project uses a different stack, these skills inject irrelevant context and cause false activations.
**Do this instead:** Activate only skills relevant to the target project's tech stack. Adapt Spring Boot-specific skills for other frameworks when needed.

### Hardcoding file paths in hook commands
**What happens:** Hook commands in `settings.json` use relative paths like `./hooks/skill-activation-prompt.ts`.
**Why it's wrong:** Claude Code's working directory during hook execution may differ from the project root. Relative paths break silently.
**Do this instead:** Use `npx tsx .claude/hooks/hook-name.ts` (relative to project root, which Claude Code passes as `cwd`) or `"$CLAUDE_PROJECT_DIR"/.claude/hooks/hook-name.js` for Node hooks.

## Error Handling

**Strategy:** All hooks use silent-fail patterns. A hook that throws an error must never block Claude Code operation.

**Patterns:**
- TypeScript hooks wrap all logic in `try/catch` blocks that call `process.exit(1)` on failure (advisory hooks) or `process.exit(0)` to fail silently
- JS hooks use a stdin timeout guard (`setTimeout(() => process.exit(0), N000)`) to prevent hanging when stdin doesn't close (Windows/Git Bash issue)
- The pattern `} catch { process.exit(0); }` appears in every GSD hook as the catch-all

## Cross-Cutting Concerns

**Logging:** Hooks output to stdout/stderr only. Stdout is piped back to Claude Code as context. Stderr appears in Claude Code's hook error logs.
**Validation:** Skill rules are validated via JSON schema at integration time (`jq .` or `ConvertFrom-Json`). TypeScript hooks are validated via `tsc --noEmit`.
**Authentication:** Not applicable — this is a developer tooling library, not a web application.

---

*Architecture analysis: 2026-05-13*
