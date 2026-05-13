# Coding Conventions

**Analysis Date:** 2026-05-13

## Naming Patterns

**TypeScript Files (hooks):**
- kebab-case filenames: `skill-activation-prompt.ts`, `error-handling-reminder.ts`, `request-clarity-check.ts`
- GSD hook files prefixed with `gsd-`: `gsd-prompt-guard.js`, `gsd-context-monitor.js`

**JavaScript Files (GSD hooks):**
- kebab-case filenames with `gsd-` prefix: `gsd-check-update.js`, `gsd-workflow-guard.js`

**Markdown Files (agents/commands/skills):**
- kebab-case: `code-architecture-reviewer.md`, `git-commit-assistant.md`
- Skill directories: kebab-case: `backend-dev-guidelines/`, `error-tracking/`, `git-workflow/`
- Agent definition files: `{name}.md` inside `.claude/agents/`
- Command definition files: `{command-name}.md` inside `.claude/commands/` or `.claude/commands/gsd/`

**Java Classes (Spring Boot target projects — defined in skill docs):**
- Controllers: `PascalCase + Controller` — `UserController.java`
- Services: `PascalCase + Service` / `PascalCase + ServiceImpl` — `UserService.java`, `UserServiceImpl.java`
- Repositories: `PascalCase + Repository` — `UserRepository.java`
- Entities: `PascalCase` — `User.java`
- DTOs: `PascalCase + Request` / `PascalCase + Response` — `CreateUserRequest.java`, `UserResponse.java`
- Exceptions: `PascalCase + Exception` — `ResourceNotFoundException.java`

**TypeScript Interfaces:**
- PascalCase: `HookInput`, `SkillRule`, `SkillRules`, `MatchedSkill`, `VaguePattern`
- Inline object types used for complex return shapes (see `analyzeFileContent` in `error-handling-reminder.ts`)

**TypeScript Functions:**
- camelCase: `getFileCategory`, `shouldCheckErrorHandling`, `analyzeFileContent`, `hasContext`, `shouldExclude`
- Entry point always named `main()`, async, called at end of file

**TypeScript Variables:**
- camelCase: `matchedSkills`, `rulesPath`, `projectDir`, `trackingFile`
- Constant arrays: `SCREAMING_SNAKE_CASE` — `VAGUE_PATTERNS`, `EXCLUSION_PATTERNS`, `CONTEXT_INDICATORS`

**Commit Message Types (git-workflow skill):**
- `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `perf`, `style`
- Format: `<type>: <short summary — max 50 chars>`
- NO emojis, NO attribution footers, imperative mood ("Add" not "Added")

## Code Style

**Formatting (TypeScript):**
- 4-space indentation (consistent across all `.ts` hook files)
- Single quotes for strings
- Semicolons required
- Opening braces on same line as declaration
- No trailing commas in function parameter lists

**Formatting (Java — target projects, as prescribed in skill docs):**
- 4-space indentation
- `camelCase` for methods and variables
- `PascalCase` for classes
- Constructor injection via Lombok `@RequiredArgsConstructor`, never field injection

**Linting:**
- TypeScript: strict mode enforced via `tsconfig.json` (`"strict": true`)
- No eslint or prettier config detected in hooks — formatting enforced via TypeScript compiler only
- Java target: checkstyle not configured in this repo (reference library only)

## TypeScript Configuration

**Settings** (`.claude/hooks/tsconfig.json`):
- `target`: ES2022
- `module`: NodeNext
- `moduleResolution`: NodeNext
- `strict`: true (enables all strict checks)
- `esModuleInterop`: true
- `forceConsistentCasingInFileNames`: true
- `resolveJsonModule`: true
- `skipLibCheck`: true

## Import Organization

**TypeScript Hook Files:**

Order observed across all three hooks:
1. Node built-ins: `fs`, `path`, `os`
2. No third-party imports (hooks use only Node built-ins)
3. No local module imports (each hook is a self-contained single file)

**Pattern:**
```typescript
import { readFileSync, existsSync } from 'fs';
import { join } from 'path';
import { homedir } from 'os';
```

Named imports preferred over default imports. Destructured at import site.

## Error Handling

**TypeScript Hooks:**
- All hooks wrap the `main()` body in `try/catch`
- On error in non-critical hooks (`request-clarity-check.ts`, `error-handling-reminder.ts`): silently `process.exit(0)` — never block workflow
- On error in critical hooks (`skill-activation-prompt.ts`): log to `console.error` then `process.exit(1)`
- Pattern: fail-safe over fail-fast for hooks that are "reminders" only

```typescript
async function main() {
    try {
        // ... logic
        process.exit(0);
    } catch (err) {
        // Silently fail - this is just a reminder, not critical
        process.exit(0);
    }
}
main().catch(() => process.exit(0));
```

**Java (Spring Boot target — exception-handling.md):**
- Use `@RestControllerAdvice` + `@Slf4j` for global exception handler
- Map custom exceptions to HTTP status codes via `@ExceptionHandler` + `@ResponseStatus`
- Custom exception hierarchy: `ResourceNotFoundException` (404), `ConflictException` (409), `BadRequestException` (400)
- Log at `warn` level for expected exceptions, `error` level for unexpected
- Never throw generic `Exception` or `RuntimeException` from services

## Logging

**TypeScript Hooks:**
- Use `console.log` for structured output to stdout (Claude reads this as context)
- Use `console.error` only for actual error conditions in critical hooks
- Output formatted with `━━━━` separator lines for visual clarity in Claude's context

**Java (Spring Boot target — backend-dev-guidelines skill):**
- Always use `@Slf4j` Lombok annotation (never manual `LoggerFactory`)
- Log method: `log.info(...)` for business events, `log.warn(...)` for expected failures, `log.error(...)` for unexpected exceptions
- Use SLF4J parameterized logging: `log.info("Creating user: {}", email)` — never string concatenation

## Comments

**TypeScript Hooks:**
- Inline comments used sparingly for non-obvious logic
- Block comments at top of GSD JS hooks describe purpose, trigger, and action:
  ```javascript
  // gsd-hook-version: 1.41.2
  // GSD Prompt Injection Guard — PreToolUse hook
  // Scans file content being written to .planning/ for prompt injection patterns.
  ```
- No JSDoc used in hook files

**Markdown Documents (agents/skills/commands):**
- YAML frontmatter required in all agent and command `.md` files:
  ```yaml
  ---
  name: agent-name
  description: ...
  model: sonnet|opus|haiku
  color: blue|yellow|...
  tools: Read, Write, Edit, Bash
  ---
  ```
- SKILL.md files use YAML frontmatter with `name` and `description` fields
- Command `.md` files use `description` and optionally `argument-hint` in frontmatter

## Function Design

**TypeScript:**
- Single-purpose functions: each does one classification/check
- `async function main()` pattern — always the primary entry point, called once
- Helper functions defined before `main()` in file
- Boolean predicate functions: `shouldCheckErrorHandling`, `isShortAndVague`, `shouldExclude`
- Return early (`process.exit(0)`) when no action needed — avoid deep nesting

**Java (Spring Boot target):**
- Controllers: thin — delegate all logic to service immediately
- Services: one public method per use case, business rules inside
- Maximum one level of nesting in service logic recommended
- `@Transactional` on service write methods, never on controllers

## Module Design

**TypeScript Hooks:**
- Each hook is a standalone self-contained `.ts` file (no imports between hooks)
- No barrel files or index files
- Each hook reads from stdin, processes, writes to stdout, exits

**Skills:**
- Each skill is a directory with `SKILL.md` as the index + optional `resources/` subdirectory
- `SKILL.md` frontmatter defines name and description for auto-activation
- Resources are supplementary detail files (e.g., `testing-guide.md`, `exception-handling.md`)

**Agents:**
- One agent per `.md` file in `.claude/agents/`
- Self-contained: full instructions, no cross-agent imports

**Commands:**
- One command per `.md` file in `.claude/commands/` or `.claude/commands/gsd/`
- `$ARGUMENTS` used as placeholder for user-provided input

---

*Convention analysis: 2026-05-13*
