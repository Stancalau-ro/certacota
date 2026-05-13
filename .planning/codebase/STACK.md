# Technology Stack

**Analysis Date:** 2026-05-13

## Languages

**Primary:**
- TypeScript 5.3.x - Hook scripts in `.claude/hooks/` (the only runnable code)
- Markdown - All skills, agents, commands, and reference documentation

**Secondary:**
- JavaScript (CommonJS) - GSD framework binaries in `.claude/get-shit-done/bin/` (compiled `.cjs` files)
- Java 17+ - Target language for Spring Boot 3.x projects this repo integrates into (not present here — this is the reference library)

**Note:** `certacota` is a reference library, not a runnable application. No main class, no build artifact, no server. The only executable code is the TypeScript hooks.

## Runtime

**Environment:**
- Node.js 20+ required for all hooks
- Hooks run via `npx tsx` (TypeScript execution without pre-compilation)

**Package Manager:**
- npm (`.claude/hooks/package.json`)
- Lockfile: `package-lock.json` present at `.claude/hooks/package-lock.json`

## Frameworks

**Core:**
- Claude Code SDK (agent/subagent system) — hooks integrate with Claude Code lifecycle events
- GSD (Get Shit Done) framework — pre-compiled `.cjs` binaries in `.claude/get-shit-done/bin/`

**Hook Execution:**
- `tsx` 4.7.x — TypeScript execution engine for hooks (no separate compilation step)

**Build/Dev:**
- `tsc` (TypeScript compiler) 5.3.x — Type checking only (`tsc --noEmit`), no build output needed
- `npx` — Used to run hooks cross-platform without global installs

## Key Dependencies

**`.claude/hooks/package.json`:**
- `tsx` ^4.7.0 — Runs TypeScript hooks directly without pre-compilation
- `typescript` ^5.3.3 — Type checking and language support
- `@types/node` ^20.11.0 — Node.js type definitions

**No application-level dependencies** — this is a configuration/tooling library, not an application with a runtime dependency tree.

## Configuration

**TypeScript (`.claude/hooks/tsconfig.json`):**
- `target`: ES2022
- `module`: NodeNext
- `moduleResolution`: NodeNext
- `strict`: true
- `resolveJsonModule`: true (hooks read `.json` files at runtime)
- `types`: ["node"]
- Includes: `*.ts` in hooks directory only
- Excludes: `node_modules`, `dist`

**Claude Code hooks (`.claude/settings.json`):**
- `UserPromptSubmit` → `skill-activation-prompt.ts`, `request-clarity-check.ts`
- `Stop` → `error-handling-reminder.ts`
- `SessionStart` → `gsd-check-update.js`, `gsd-session-state.sh`
- `PostToolUse` (Bash|Edit|Write|MultiEdit|Agent|Task) → `gsd-context-monitor.js`
- `PostToolUse` (Read) → `gsd-read-injection-scanner.js`
- `PostToolUse` (Write|Edit) → `gsd-phase-boundary.sh`
- `PreToolUse` (Write|Edit) → `gsd-prompt-guard.js`, `gsd-read-guard.js`, `gsd-workflow-guard.js`
- `PreToolUse` (Bash) → `gsd-validate-commit.sh`
- MCP servers enabled: `mysql`, `sequential-thinking`, `playwright`

**Skill activation (`.claude/skills/skill-rules.json`):**
- Version: `1.0`
- Skills defined: `skill-developer`, `backend-dev-guidelines`, `route-tester`, `git-workflow`, `error-tracking`, `product-owner`
- Enforcement modes: `suggest`, `block`, `warn`
- Priority levels: `critical`, `high`, `medium`, `low`

**Environment variables used by hooks:**
- `CLAUDE_PROJECT_DIR` — Project root; falls back to `process.cwd()`
- `SKIP_ERROR_REMINDER` — Set to `1` to disable `error-handling-reminder.ts`

## Platform Requirements

**Development:**
- Node.js 20+
- npm (for `npm install` in `.claude/hooks/`)
- Cross-platform: Windows (PowerShell), Linux, macOS all supported
- No Java, Maven, or Gradle required in this repo itself

**Target Project (integration target, not this repo):**
- Spring Boot 3.x
- Java 17+
- Lombok
- Spring Data JPA
- Maven or Gradle
- Docker (for local dev with Docker Compose)

---

*Stack analysis: 2026-05-13*
