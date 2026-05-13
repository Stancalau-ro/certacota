# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Repository Is

`certacota` is a reference library of Claude Code infrastructure components — skills, hooks, agents, and commands — designed to be integrated into Spring Boot 3.x projects. It is **not a runnable application**; there is no main class or build artifact.

## Hooks Setup

The only runnable code is the TypeScript hooks in `.claude/hooks/`:

```powershell
cd .claude/hooks
npm install
```

Validate TypeScript without running:
```powershell
npx tsc --noEmit
```

Test hook manually:
```powershell
npm test
```

Hooks run via `npx tsx` and require Node.js 20+.

Register hooks in `.claude/settings.json`:

```json
{
  "hooks": {
    "UserPromptSubmit": [
      { "hooks": [{ "type": "command", "command": "npx tsx .claude/hooks/skill-activation-prompt.ts" }] },
      { "hooks": [{ "type": "command", "command": "npx tsx .claude/hooks/request-clarity-check.ts" }] }
    ],
    "Stop": [
      { "hooks": [{ "type": "command", "command": "npx tsx .claude/hooks/error-handling-reminder.ts" }] }
    ]
  }
}
```

## Architecture

The system has four component types that work together:

### Skills (`.claude/skills/`)
Domain knowledge bases that auto-activate based on trigger patterns defined in `skill-rules.json`. The most substantial skill is `backend-dev-guidelines/` with 11 resource files covering Spring Boot 3.x patterns (controllers, services, DTOs, JPA, security, testing, Docker).

### Hooks (`.claude/hooks/`)
TypeScript scripts wired to Claude Code lifecycle events in `.claude/settings.json`:
- `UserPromptSubmit` → `skill-activation-prompt.ts` reads `skill-rules.json` and injects relevant skill context; `request-clarity-check.ts` flags vague requests
- `Stop` → `error-handling-reminder.ts` checks edited Java files for Spring Boot best practice gaps

### Agents (`.claude/agents/`)
Autonomous Claude sub-instances (13 total) for complex tasks: architecture review, refactoring, documentation generation, JWT auth testing, Java error resolution, and planning.

### Commands (`.claude/commands/`)
Slash commands: `/dev-docs` creates a 3-file planning structure (plan.md, context.md, tasks.md) for multi-day tasks; `/dev-docs-update` refreshes plans before context resets; `/route-research-for-testing` gathers REST testing patterns.

## Key Configuration Files

- `.claude/settings.json` — hook bindings; this is where hooks are registered
- `.claude/skills/skill-rules.json` — trigger patterns that map prompt keywords/file patterns to skills
- `.claude/hooks/tsconfig.json` — TypeScript strict mode, ES2022 target

## Integration Into Other Projects

`CLAUDE_INTEGRATION_GUIDE.md` at the repo root is the authoritative guide for copying components into a target project, including which `skill-rules.json` patterns to customize for different project structures.

The `dev/README.md` explains the 3-file dev docs methodology used by the `/dev-docs` command.

<!-- GSD:project-start source:PROJECT.md -->
## Project

**Real-Time Token Economy Engine**

A standalone, payment-rail-agnostic token management engine for real-time multi-party token economies. It manages concurrent streaming (rate-based) and discrete (one-off) token flows across any number of participants, provides forward estimation of each participant's token position, and extracts platform rake atomically — all without knowledge of the underlying payment mechanism.

Deployed as both a standalone Spring Boot service (REST API) and an embeddable Spring Boot autoconfigure library (JAR).

**Core Value:** Correct, real-time token accounting across concurrent mixed transaction types in multi-party sessions — something no off-the-shelf ledger or billing system handles.

### Constraints

- **Tech stack**: Java / Spring Boot — engine and library are Spring ecosystem artifacts
- **API style**: REST only — no WebSocket or gRPC in v1; estimation is served via polling or on-demand GET
- **Storage**: In-memory for hot streaming state + Postgres for durable audit and recovery
- **Correctness over throughput**: concurrent correctness is the non-negotiable constraint; latency optimization is secondary
- **No payment coupling**: the engine must never hold a reference to a payment provider SDK or API
<!-- GSD:project-end -->

<!-- GSD:stack-start source:codebase/STACK.md -->
## Technology Stack

## Languages
- TypeScript 5.3.x - Hook scripts in `.claude/hooks/` (the only runnable code)
- Markdown - All skills, agents, commands, and reference documentation
- JavaScript (CommonJS) - GSD framework binaries in `.claude/get-shit-done/bin/` (compiled `.cjs` files)
- Java 17+ - Target language for Spring Boot 3.x projects this repo integrates into (not present here — this is the reference library)
## Runtime
- Node.js 20+ required for all hooks
- Hooks run via `npx tsx` (TypeScript execution without pre-compilation)
- npm (`.claude/hooks/package.json`)
- Lockfile: `package-lock.json` present at `.claude/hooks/package-lock.json`
## Frameworks
- Claude Code SDK (agent/subagent system) — hooks integrate with Claude Code lifecycle events
- GSD (Get Shit Done) framework — pre-compiled `.cjs` binaries in `.claude/get-shit-done/bin/`
- `tsx` 4.7.x — TypeScript execution engine for hooks (no separate compilation step)
- `tsc` (TypeScript compiler) 5.3.x — Type checking only (`tsc --noEmit`), no build output needed
- `npx` — Used to run hooks cross-platform without global installs
## Key Dependencies
- `tsx` ^4.7.0 — Runs TypeScript hooks directly without pre-compilation
- `typescript` ^5.3.3 — Type checking and language support
- `@types/node` ^20.11.0 — Node.js type definitions
## Configuration
- `target`: ES2022
- `module`: NodeNext
- `moduleResolution`: NodeNext
- `strict`: true
- `resolveJsonModule`: true (hooks read `.json` files at runtime)
- `types`: ["node"]
- Includes: `*.ts` in hooks directory only
- Excludes: `node_modules`, `dist`
- `UserPromptSubmit` → `skill-activation-prompt.ts`, `request-clarity-check.ts`
- `Stop` → `error-handling-reminder.ts`
- `SessionStart` → `gsd-check-update.js`, `gsd-session-state.sh`
- `PostToolUse` (Bash|Edit|Write|MultiEdit|Agent|Task) → `gsd-context-monitor.js`
- `PostToolUse` (Read) → `gsd-read-injection-scanner.js`
- `PostToolUse` (Write|Edit) → `gsd-phase-boundary.sh`
- `PreToolUse` (Write|Edit) → `gsd-prompt-guard.js`, `gsd-read-guard.js`, `gsd-workflow-guard.js`
- `PreToolUse` (Bash) → `gsd-validate-commit.sh`
- MCP servers enabled: `mysql`, `sequential-thinking`, `playwright`
- Version: `1.0`
- Skills defined: `skill-developer`, `backend-dev-guidelines`, `route-tester`, `git-workflow`, `error-tracking`, `product-owner`
- Enforcement modes: `suggest`, `block`, `warn`
- Priority levels: `critical`, `high`, `medium`, `low`
- `CLAUDE_PROJECT_DIR` — Project root; falls back to `process.cwd()`
- `SKIP_ERROR_REMINDER` — Set to `1` to disable `error-handling-reminder.ts`
## Platform Requirements
- Node.js 20+
- npm (for `npm install` in `.claude/hooks/`)
- Cross-platform: Windows (PowerShell), Linux, macOS all supported
- No Java, Maven, or Gradle required in this repo itself
- Spring Boot 3.x
- Java 17+
- Lombok
- Spring Data JPA
- Maven or Gradle
- Docker (for local dev with Docker Compose)
<!-- GSD:stack-end -->

<!-- GSD:conventions-start source:CONVENTIONS.md -->
## Conventions

## Naming Patterns
- kebab-case filenames: `skill-activation-prompt.ts`, `error-handling-reminder.ts`, `request-clarity-check.ts`
- GSD hook files prefixed with `gsd-`: `gsd-prompt-guard.js`, `gsd-context-monitor.js`
- kebab-case filenames with `gsd-` prefix: `gsd-check-update.js`, `gsd-workflow-guard.js`
- kebab-case: `code-architecture-reviewer.md`, `git-commit-assistant.md`
- Skill directories: kebab-case: `backend-dev-guidelines/`, `error-tracking/`, `git-workflow/`
- Agent definition files: `{name}.md` inside `.claude/agents/`
- Command definition files: `{command-name}.md` inside `.claude/commands/` or `.claude/commands/gsd/`
- Controllers: `PascalCase + Controller` — `UserController.java`
- Services: `PascalCase + Service` / `PascalCase + ServiceImpl` — `UserService.java`, `UserServiceImpl.java`
- Repositories: `PascalCase + Repository` — `UserRepository.java`
- Entities: `PascalCase` — `User.java`
- DTOs: `PascalCase + Request` / `PascalCase + Response` — `CreateUserRequest.java`, `UserResponse.java`
- Exceptions: `PascalCase + Exception` — `ResourceNotFoundException.java`
- PascalCase: `HookInput`, `SkillRule`, `SkillRules`, `MatchedSkill`, `VaguePattern`
- Inline object types used for complex return shapes (see `analyzeFileContent` in `error-handling-reminder.ts`)
- camelCase: `getFileCategory`, `shouldCheckErrorHandling`, `analyzeFileContent`, `hasContext`, `shouldExclude`
- Entry point always named `main()`, async, called at end of file
- camelCase: `matchedSkills`, `rulesPath`, `projectDir`, `trackingFile`
- Constant arrays: `SCREAMING_SNAKE_CASE` — `VAGUE_PATTERNS`, `EXCLUSION_PATTERNS`, `CONTEXT_INDICATORS`
- `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `perf`, `style`
- Format: `<type>: <short summary — max 50 chars>`
- NO emojis, NO attribution footers, imperative mood ("Add" not "Added")
## Code Style
- 4-space indentation (consistent across all `.ts` hook files)
- Single quotes for strings
- Semicolons required
- Opening braces on same line as declaration
- No trailing commas in function parameter lists
- 4-space indentation
- `camelCase` for methods and variables
- `PascalCase` for classes
- Constructor injection via Lombok `@RequiredArgsConstructor`, never field injection
- TypeScript: strict mode enforced via `tsconfig.json` (`"strict": true`)
- No eslint or prettier config detected in hooks — formatting enforced via TypeScript compiler only
- Java target: checkstyle not configured in this repo (reference library only)
## TypeScript Configuration
- `target`: ES2022
- `module`: NodeNext
- `moduleResolution`: NodeNext
- `strict`: true (enables all strict checks)
- `esModuleInterop`: true
- `forceConsistentCasingInFileNames`: true
- `resolveJsonModule`: true
- `skipLibCheck`: true
## Import Organization
## Error Handling
- All hooks wrap the `main()` body in `try/catch`
- On error in non-critical hooks (`request-clarity-check.ts`, `error-handling-reminder.ts`): silently `process.exit(0)` — never block workflow
- On error in critical hooks (`skill-activation-prompt.ts`): log to `console.error` then `process.exit(1)`
- Pattern: fail-safe over fail-fast for hooks that are "reminders" only
- Use `@RestControllerAdvice` + `@Slf4j` for global exception handler
- Map custom exceptions to HTTP status codes via `@ExceptionHandler` + `@ResponseStatus`
- Custom exception hierarchy: `ResourceNotFoundException` (404), `ConflictException` (409), `BadRequestException` (400)
- Log at `warn` level for expected exceptions, `error` level for unexpected
- Never throw generic `Exception` or `RuntimeException` from services
## Logging
- Use `console.log` for structured output to stdout (Claude reads this as context)
- Use `console.error` only for actual error conditions in critical hooks
- Output formatted with `━━━━` separator lines for visual clarity in Claude's context
- Always use `@Slf4j` Lombok annotation (never manual `LoggerFactory`)
- Log method: `log.info(...)` for business events, `log.warn(...)` for expected failures, `log.error(...)` for unexpected exceptions
- Use SLF4J parameterized logging: `log.info("Creating user: {}", email)` — never string concatenation
## Comments
- Inline comments used sparingly for non-obvious logic
- Block comments at top of GSD JS hooks describe purpose, trigger, and action:
- No JSDoc used in hook files
- YAML frontmatter required in all agent and command `.md` files:
- SKILL.md files use YAML frontmatter with `name` and `description` fields
- Command `.md` files use `description` and optionally `argument-hint` in frontmatter
## Function Design
- Single-purpose functions: each does one classification/check
- `async function main()` pattern — always the primary entry point, called once
- Helper functions defined before `main()` in file
- Boolean predicate functions: `shouldCheckErrorHandling`, `isShortAndVague`, `shouldExclude`
- Return early (`process.exit(0)`) when no action needed — avoid deep nesting
- Controllers: thin — delegate all logic to service immediately
- Services: one public method per use case, business rules inside
- Maximum one level of nesting in service logic recommended
- `@Transactional` on service write methods, never on controllers
## Module Design
- Each hook is a standalone self-contained `.ts` file (no imports between hooks)
- No barrel files or index files
- Each hook reads from stdin, processes, writes to stdout, exits
- Each skill is a directory with `SKILL.md` as the index + optional `resources/` subdirectory
- `SKILL.md` frontmatter defines name and description for auto-activation
- Resources are supplementary detail files (e.g., `testing-guide.md`, `exception-handling.md`)
- One agent per `.md` file in `.claude/agents/`
- Self-contained: full instructions, no cross-agent imports
- One command per `.md` file in `.claude/commands/` or `.claude/commands/gsd/`
- `$ARGUMENTS` used as placeholder for user-provided input
<!-- GSD:conventions-end -->

<!-- GSD:architecture-start source:ARCHITECTURE.md -->
## Architecture

## System Overview
```text
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
- No source code to compile or run — all components are markdown and TypeScript/JavaScript files
- Hooks intercept Claude Code lifecycle events (stdin/stdout JSON protocol)
- Skill activation is data-driven via `skill-rules.json` (keyword + regex matching)
- Commands are thin wrappers that delegate to workflow files via `@` includes
- Agents are self-contained markdown files with YAML frontmatter
## Layers
- Purpose: Entry points invoked by the user via `/slash-command` syntax
- Location: `.claude/commands/` and `.claude/commands/gsd/`
- Contains: Markdown files with YAML frontmatter (`description`, `argument-hint`, `allowed-tools`)
- Depends on: GSD workflow files via `@` include references
- Used by: User directly
- Purpose: Contains the actual step-by-step execution logic for each command
- Location: `.claude/get-shit-done/workflows/`
- Contains: Markdown workflow definitions, conditionals, subagent spawning instructions
- Depends on: References (shared logic), Templates (file scaffolds), Contexts (mode defaults)
- Used by: Commands layer
- Purpose: Reusable logic fragments loaded by workflows as needed
- Location: `.claude/get-shit-done/references/`
- Contains: 50+ reference documents (TDD, planning config, verification patterns, gate prompts, etc.)
- Depends on: Nothing (leaf nodes)
- Used by: GSD workflows
- Purpose: Autonomous sub-instances for complex, multi-step tasks
- Location: `.claude/agents/`
- Contains: Markdown files with YAML frontmatter (`name`, `description`, `model`, `color`, `tools`)
- Two groups: original certacota agents (11) and GSD agents (20+, prefixed `gsd-`)
- Depends on: Nothing (standalone); some load skill context at runtime
- Used by: User directly or spawned by GSD workflow commands
- Purpose: Domain knowledge bases injected into Claude's context when relevant
- Location: `.claude/skills/`
- Contains: Skill directories, each with a `SKILL.md` and optional `resources/` subdirectory
- Skills: `backend-dev-guidelines` (11 resource files), `skill-developer` (7 reference files), `error-tracking`, `git-workflow`, `product-owner`, `route-tester`
- Depends on: `skill-rules.json` for trigger configuration
- Used by: `skill-activation-prompt.ts` hook at `UserPromptSubmit` time
- Purpose: Intercept Claude Code lifecycle events to enforce guardrails and inject context
- Location: `.claude/hooks/`
- Runtime: `npx tsx` (TypeScript hooks), `node` (GSD JS hooks), `bash` (GSD shell hooks)
- Depends on: `skill-rules.json` (skill activation), `.planning/config.json` (GSD guards), stdin JSON
- Used by: Claude Code runtime (registered in `settings.json`)
## Data Flow
### Skill Auto-Activation (UserPromptSubmit)
### Request Clarity Check (UserPromptSubmit)
### Spring Boot Code Quality Check (Stop)
### GSD Context Monitoring (PostToolUse)
### GSD Write Guards (PreToolUse)
### GSD Session Start (SessionStart)
## Key Abstractions
- Purpose: A named domain knowledge base that activates automatically based on prompt or file context
- Structure: Directory under `.claude/skills/{name}/` containing `SKILL.md` plus optional `resources/` files
- Pattern: Progressive disclosure — `SKILL.md` stays under 500 lines; detailed content lives in `resources/*.md`
- Examples: `.claude/skills/backend-dev-guidelines/SKILL.md`, `.claude/skills/skill-developer/SKILL.md`
- Purpose: A process that intercepts a Claude Code lifecycle event, reads JSON from stdin, optionally writes JSON to stdout
- Protocol: Reads `{ session_id, prompt, tool_name, tool_input, cwd, ... }` from stdin; exits 0 (advisory), exits 2 (block), or writes `{ hookSpecificOutput: { additionalContext: "..." } }` to stdout
- Examples: `.claude/hooks/skill-activation-prompt.ts`, `.claude/hooks/gsd-context-monitor.js`
- Purpose: A self-contained markdown file describing how Claude should behave as an autonomous sub-instance
- Structure: YAML frontmatter (`name`, `description`, `model`, `tools`, `color`) + markdown instructions
- Pattern: Standalone — no external dependencies beyond the markdown file itself
- Examples: `.claude/agents/code-architecture-reviewer.md`, `.claude/agents/gsd-planner.md`
- Purpose: A thin slash command entry point that loads workflow logic via `@` file includes
- Structure: YAML frontmatter + `<execution_context>` block with `@path/to/workflow.md` includes
- Pattern: Commands never contain logic directly; they reference workflow files
- Examples: `.claude/commands/gsd/execute-phase.md`, `.claude/commands/gsd/plan-phase.md`
## Entry Points
- Location: `.claude/commands/*.md` (original certacota: `dev-docs.md`, `dev-docs-update.md`, `route-research-for-testing.md`)
- Location: `.claude/commands/gsd/*.md` (GSD framework: 60+ commands including `plan-phase.md`, `execute-phase.md`, `debug.md`, etc.)
- Triggers: User types `/command-name [arguments]`
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
### Activating all skills at once
### Hardcoding file paths in hook commands
## Error Handling
- TypeScript hooks wrap all logic in `try/catch` blocks that call `process.exit(1)` on failure (advisory hooks) or `process.exit(0)` to fail silently
- JS hooks use a stdin timeout guard (`setTimeout(() => process.exit(0), N000)`) to prevent hanging when stdin doesn't close (Windows/Git Bash issue)
- The pattern `} catch { process.exit(0); }` appears in every GSD hook as the catch-all
## Cross-Cutting Concerns
<!-- GSD:architecture-end -->

<!-- GSD:skills-start source:skills/ -->
## Project Skills

No project skills found. Add skills to any of: `.claude/skills/`, `.agents/skills/`, `.cursor/skills/`, `.github/skills/`, or `.codex/skills/` with a `SKILL.md` index file.
<!-- GSD:skills-end -->

<!-- GSD:workflow-start source:GSD defaults -->
## GSD Workflow Enforcement

Before using Edit, Write, or other file-changing tools, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:
- `/gsd-quick` for small fixes, doc updates, and ad-hoc tasks
- `/gsd-debug` for investigation and bug fixing
- `/gsd-execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.
<!-- GSD:workflow-end -->

<!-- GSD:profile-start -->
## Developer Profile

> Profile not yet configured. Run `/gsd-profile-user` to generate your developer profile.
> This section is managed by `generate-claude-profile` -- do not edit manually.
<!-- GSD:profile-end -->
