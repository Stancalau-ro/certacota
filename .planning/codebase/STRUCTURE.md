# Codebase Structure

**Analysis Date:** 2026-05-13

## Directory Layout

```
certacota/
├── .claude/                         # All Claude Code infrastructure
│   ├── settings.json                # Hook registrations and permissions
│   ├── agents/                      # Agent definitions (standalone .md files)
│   │   ├── auth-route-debugger.md
│   │   ├── auth-route-tester.md
│   │   ├── auto-error-resolver.md
│   │   ├── code-architecture-reviewer.md
│   │   ├── code-refactor-master.md
│   │   ├── documentation-architect.md
│   │   ├── git-commit-assistant.md
│   │   ├── gsd-*.md                 # ~24 GSD framework agents
│   │   ├── plan-reviewer.md
│   │   ├── product-owner.md
│   │   ├── refactor-planner.md
│   │   ├── request-analyzer.md
│   │   ├── web-research-specialist.md
│   │   └── README.md
│   ├── commands/                    # Slash command entry points
│   │   ├── dev-docs.md              # /dev-docs command
│   │   ├── dev-docs-update.md       # /dev-docs-update command
│   │   ├── route-research-for-testing.md
│   │   └── gsd/                     # ~60 /gsd- commands
│   │       ├── execute-phase.md
│   │       ├── plan-phase.md
│   │       ├── debug.md
│   │       └── ...
│   ├── hooks/                       # Lifecycle hook scripts
│   │   ├── skill-activation-prompt.ts   # UserPromptSubmit: skill suggestions
│   │   ├── request-clarity-check.ts     # UserPromptSubmit: vague request detection
│   │   ├── error-handling-reminder.ts   # Stop: Spring Boot best practice reminders
│   │   ├── gsd-context-monitor.js       # PostToolUse: context usage warnings
│   │   ├── gsd-prompt-guard.js          # PreToolUse: injection scan on .planning/ writes
│   │   ├── gsd-read-guard.js            # PreToolUse: read-before-edit advisory
│   │   ├── gsd-workflow-guard.js        # PreToolUse: nudge toward /gsd- commands
│   │   ├── gsd-read-injection-scanner.js # PostToolUse(Read): injection scan
│   │   ├── gsd-phase-boundary.sh        # PostToolUse(Write|Edit): phase tracking
│   │   ├── gsd-session-state.sh         # SessionStart: project state injection
│   │   ├── gsd-validate-commit.sh       # PreToolUse(Bash): Conventional Commits check
│   │   ├── gsd-check-update.js          # SessionStart: GSD version check
│   │   ├── gsd-check-update-worker.js   # Background worker for version check
│   │   ├── gsd-statusline.js            # Context metrics bridge (writes /tmp files)
│   │   ├── gsd-update-banner.js         # Update notification display
│   │   ├── package.json                 # Node deps: tsx, typescript, @types/node
│   │   ├── tsconfig.json               # Strict TypeScript: ES2022, NodeNext modules
│   │   ├── CONFIG.md
│   │   └── README.md
│   ├── skills/                      # Domain knowledge bases
│   │   ├── skill-rules.json         # Trigger configuration for all skills
│   │   ├── backend-dev-guidelines/  # Spring Boot 3.x patterns
│   │   │   ├── SKILL.md             # Primary skill file (<500 lines)
│   │   │   └── resources/           # 11 detailed reference files
│   │   │       ├── architecture-overview.md
│   │   │       ├── controllers-and-endpoints.md
│   │   │       ├── services-and-repositories.md
│   │   │       ├── dto-patterns.md
│   │   │       ├── security-guide.md
│   │   │       ├── exception-handling.md
│   │   │       ├── jpa-patterns.md
│   │   │       ├── configuration.md
│   │   │       ├── testing-guide.md
│   │   │       ├── docker-and-deployment.md
│   │   │       └── complete-examples.md
│   │   ├── skill-developer/         # Meta-skill for creating skills
│   │   │   ├── SKILL.md
│   │   │   ├── ADVANCED.md
│   │   │   ├── HOOK_MECHANISMS.md
│   │   │   ├── PATTERNS_LIBRARY.md
│   │   │   ├── SKILL_RULES_REFERENCE.md
│   │   │   ├── TRIGGER_TYPES.md
│   │   │   └── TROUBLESHOOTING.md
│   │   ├── error-tracking/
│   │   │   └── SKILL.md
│   │   ├── git-workflow/
│   │   │   └── SKILL.md
│   │   ├── product-owner/
│   │   │   └── SKILL.md
│   │   ├── route-tester/
│   │   │   └── SKILL.md
│   │   └── README.md
│   ├── get-shit-done/               # GSD framework (bundled)
│   │   ├── VERSION                  # Framework version number
│   │   ├── bin/                     # CLI utilities
│   │   │   ├── gsd-tools.cjs        # State management tool (state record-session)
│   │   │   ├── check-latest-version.cjs
│   │   │   └── verify-reapply-patches.cjs
│   │   ├── contexts/                # Mode context defaults
│   │   │   ├── dev.md
│   │   │   ├── research.md
│   │   │   └── review.md
│   │   ├── references/              # ~55 reusable workflow logic fragments
│   │   │   ├── tdd.md
│   │   │   ├── verification-patterns.md
│   │   │   ├── gates.md
│   │   │   ├── planning-config.md
│   │   │   └── ...
│   │   ├── templates/               # .planning/ scaffold files
│   │   │   ├── state.md             # STATE.md template
│   │   │   ├── milestone.md
│   │   │   ├── phase-prompt.md
│   │   │   ├── config.json          # Default .planning/config.json
│   │   │   └── ...
│   │   ├── workflows/               # ~120 workflow definitions
│   │   │   ├── execute-phase.md
│   │   │   ├── plan-phase.md
│   │   │   ├── execute-plan.md
│   │   │   ├── discuss-phase/       # Sub-workflow with modes and templates
│   │   │   │   ├── modes/
│   │   │   │   └── templates/
│   │   │   └── ...
│   │   └── package.json             # For hooks directory node deps
├── .planning/                       # GSD state files (generated by GSD commands)
│   └── codebase/                    # Codebase map documents
├── dev/                             # Documentation
│   ├── README.md                    # Dev docs methodology guide
│   └── architecture.md              # Token economy engine architecture doc
├── CLAUDE.md                        # Project instructions for Claude Code
├── CLAUDE_INTEGRATION_GUIDE.md      # Authoritative integration guide
├── token-economy-engine-summary.md  # Reference: example system summary
└── .gitignore
```

## Directory Purposes

**`.claude/`:**
- Purpose: All Claude Code infrastructure components
- Contains: The four component types (agents, commands, hooks, skills) plus the bundled GSD framework
- Key files: `settings.json` (hook registrations)

**`.claude/agents/`:**
- Purpose: Autonomous Claude sub-instance definitions
- Contains: Markdown files; each is a complete agent specification with YAML frontmatter
- Two groups: 13 original certacota agents (Spring Boot focused) and 24 GSD framework agents (prefixed `gsd-`)
- Key files: `code-architecture-reviewer.md`, `auto-error-resolver.md`, `gsd-planner.md`, `gsd-executor.md`

**`.claude/commands/`:**
- Purpose: Slash command entry points (the `/command-name` invocations)
- Contains: Markdown files with YAML frontmatter and `@` include references to workflow files
- Key files: `dev-docs.md`, `dev-docs-update.md`; `gsd/execute-phase.md`, `gsd/plan-phase.md`

**`.claude/hooks/`:**
- Purpose: TypeScript and JavaScript/shell lifecycle scripts
- Contains: 3 TypeScript hooks (original certacota), 12 GSD hooks (JS/shell), `package.json`, `tsconfig.json`
- Key files: `skill-activation-prompt.ts`, `error-handling-reminder.ts`, `gsd-context-monitor.js`

**`.claude/skills/`:**
- Purpose: Domain knowledge bases for auto-activation
- Contains: 6 skill directories each containing `SKILL.md`; `skill-rules.json` with trigger configuration
- Key files: `skill-rules.json`, `backend-dev-guidelines/SKILL.md`, `skill-developer/SKILL.md`

**`.claude/get-shit-done/`:**
- Purpose: Bundled GSD project management framework (not authored by certacota; integrated as a dependency)
- Contains: Workflows, references, templates, contexts, CLI bin utilities, and a VERSION file
- Key files: `workflows/execute-phase.md`, `workflows/plan-phase.md`, `templates/state.md`, `bin/gsd-tools.cjs`

**`.planning/`:**
- Purpose: GSD runtime state directory (generated by GSD commands in a live project)
- Contains: `STATE.md`, `config.json`, milestone files, phase plans, codebase map documents
- Generated: Yes (by `/gsd-` commands)
- Committed: Yes (state is tracked in git as part of project progress)

**`dev/`:**
- Purpose: Project documentation
- Contains: `README.md` (dev docs 3-file methodology), `architecture.md` (token economy engine reference architecture)

## Key File Locations

**Hook Registration:**
- `.claude/settings.json`: Authoritative list of all registered hooks and their lifecycle event bindings

**Skill Trigger Configuration:**
- `.claude/skills/skill-rules.json`: Master configuration for all skill triggers (keywords, intent patterns, file paths, content patterns)

**Integration Documentation:**
- `CLAUDE_INTEGRATION_GUIDE.md`: Step-by-step guide for copying components into a target project; includes tech stack checks, path customization, and verification steps

**GSD Framework Version:**
- `.claude/get-shit-done/VERSION`: Current version number of the bundled GSD framework

**TypeScript Build Config:**
- `.claude/hooks/tsconfig.json`: Strict mode, ES2022 target, NodeNext module resolution

## Naming Conventions

**Files:**
- Skills: `SKILL.md` (uppercase) — required filename for the primary skill document
- Skill resources: `kebab-case.md` (e.g., `controllers-and-endpoints.md`)
- Hooks (TypeScript, certacota): `kebab-case.ts` (e.g., `skill-activation-prompt.ts`)
- Hooks (GSD): `gsd-kebab-case.js` or `gsd-kebab-case.sh` (always prefixed `gsd-`)
- Agents (certacota): `kebab-case.md` (e.g., `code-architecture-reviewer.md`)
- Agents (GSD): `gsd-kebab-case.md` (e.g., `gsd-planner.md`)
- Commands (GSD): `kebab-case.md` matching the slash command name after `/gsd-`
- Workflow files: `kebab-case.md` matching the command they implement

**Directories:**
- Skills: `kebab-case/` matching the skill name in `skill-rules.json`
- Agent groups: flat directory, distinguished only by `gsd-` prefix on filename

## Where to Add New Code

**New skill:**
- Create directory: `.claude/skills/{skill-name}/`
- Primary file: `.claude/skills/{skill-name}/SKILL.md` (under 500 lines, with YAML frontmatter)
- Resources: `.claude/skills/{skill-name}/resources/{topic}.md` (for progressive disclosure)
- Register triggers: Add entry to `.claude/skills/skill-rules.json` under `skills`

**New hook:**
- TypeScript (certacota style): `.claude/hooks/{purpose}.ts` — read stdin JSON, write stdout JSON, always exit 0
- JavaScript (GSD style): `.claude/hooks/gsd-{purpose}.js` — same protocol, CommonJS, include stdin timeout guard
- Register: Add to `.claude/settings.json` under the appropriate lifecycle event key

**New agent:**
- Certacota agent: `.claude/agents/{role-description}.md` with YAML frontmatter (`name`, `description`, `model`, `tools`)
- GSD agent: `.claude/agents/gsd-{role}.md` (same structure, prefixed name)
- No registration required — agents are invoked by asking Claude to use them

**New slash command:**
- Entry point: `.claude/commands/{name}.md` or `.claude/commands/gsd/{name}.md`
- Workflow: `.claude/get-shit-done/workflows/{name}.md` (actual logic lives here)
- Command file uses `@path/to/workflow.md` to load the workflow

**New documentation:**
- Dev docs: `dev/{topic}.md`
- Integration notes: append to `CLAUDE_INTEGRATION_GUIDE.md`

## Special Directories

**`.claude/get-shit-done/`:**
- Purpose: Bundled GSD project management framework (external dependency, not certacota-authored)
- Generated: No (checked in, version-pinned via `VERSION` file)
- Committed: Yes
- Do NOT modify: This directory is managed by the GSD framework update mechanism (`gsd-check-update.js`)

**`.planning/`:**
- Purpose: GSD runtime state for the current project (codebase maps, config, STATE.md, plans)
- Generated: Yes (by `/gsd-` commands at runtime)
- Committed: Yes (project progress state is tracked)
- `.planning/codebase/`: Codebase analysis documents written by `/gsd-map-codebase`

**`.claude/hooks/node_modules/`:**
- Purpose: TypeScript hook dependencies (tsx, typescript)
- Generated: Yes (via `npm install` in `.claude/hooks/`)
- Committed: No (excluded via `.gitignore`)

---

*Structure analysis: 2026-05-13*
