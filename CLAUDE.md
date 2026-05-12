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
