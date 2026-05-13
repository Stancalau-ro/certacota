# External Integrations

**Analysis Date:** 2026-05-13

## APIs & External Services

**Claude Code Platform:**
- Claude Code SDK — hooks integrate with the Claude Code agent lifecycle via stdin/stdout JSON messaging
  - Hook input: JSON object with `session_id`, `transcript_path`, `cwd`, `permission_mode`, `prompt`
  - Hook output: plain text printed to stdout (injected into Claude's context)
  - Exit code 0 = success, exit code 1 = failure (blocks or flags the event)
  - Hook events consumed: `UserPromptSubmit`, `Stop`, `SessionStart`, `PreToolUse`, `PostToolUse`

**GSD (Get Shit Done) Framework:**
- Pre-compiled CommonJS binaries in `.claude/get-shit-done/bin/`
- Invoked via hooks: `gsd-check-update.js`, `gsd-context-monitor.js`, `gsd-read-injection-scanner.js`, `gsd-prompt-guard.js`, `gsd-read-guard.js`, `gsd-workflow-guard.js`
- Shell scripts: `gsd-session-state.sh`, `gsd-phase-boundary.sh`, `gsd-validate-commit.sh`
- No external network calls from hooks — all processing is local file I/O

## Data Storage

**Databases:**
- None in this repo. The hooks reference session-level file-based tracking only.
  - Edited files tracking: `~/.claude/tsc-cache/{session_id}/edited-files.log` (written by editor hooks, read by `error-handling-reminder.ts`)

**File Storage:**
- Local filesystem only
  - Skill rules loaded from `.claude/skills/skill-rules.json` at hook runtime
  - Hook input read from stdin (fd 0) using `readFileSync(0, 'utf-8')`
  - No writes to disk from the TypeScript hooks themselves

**Caching:**
- Session cache at `~/.claude/tsc-cache/{session_id}/` — stores edited file tracking per Claude session

## Authentication & Identity

**Auth Provider:**
- None — this is a tooling/configuration library with no user-facing authentication

**Target Project Auth (documented in skills for reference):**
- Spring Security with JWT (stateless sessions) — patterns documented in `.claude/skills/backend-dev-guidelines/resources/security-guide.md`
- OAuth2 Resource Server — also covered in security guide
- JWT filter (`JwtAuthenticationFilter`) added before `UsernamePasswordAuthenticationFilter`
- Method security via `@PreAuthorize` with `@EnableMethodSecurity`

## Monitoring & Observability

**Error Tracking:**
- None in this repo itself

**Logs:**
- Hook errors: written to stderr via `console.error()`
- Hook suggestions/warnings: written to stdout via `console.log()` — injected into Claude's context window
- `error-handling-reminder.ts` silently swallows its own errors (`catch` → `process.exit(0)`) to avoid disrupting the session

**Target Project Monitoring (documented in skills):**
- Spring Boot Actuator — endpoints: `health`, `info`, `metrics`, `prometheus`
  - Configured in `application.yml` under `management.endpoints`
- SLF4J/Logback with `@Slf4j` (Lombok)
  - Logback pattern: `%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n`
  - Rolling file appender with 30-day retention

## CI/CD & Deployment

**Hosting:**
- Not applicable — this is a reference library distributed by copying files into target projects

**CI Pipeline:**
- None — no automated tests, linting pipeline, or deployment workflow in this repo

**Hooks setup command:**
```powershell
cd .claude/hooks
npm install
npx tsc --noEmit   # Type-check only, no compilation needed
npm test           # Runs skill-activation-prompt.ts with test-input.json
```

## MCP Servers

**Enabled in `.claude/settings.json`:**
- `mysql` — MySQL database MCP server (enabled for projects with MySQL)
- `sequential-thinking` — Reasoning/planning MCP server
- `playwright` — Browser automation MCP server (for E2E testing workflows)

These are enabled project-wide via `enableAllProjectMcpServers: true` and listed under `enabledMcpjsonServers`. They are Claude Code integrations, not application-level dependencies.

## Environment Configuration

**Required environment variables for hooks:**
- `CLAUDE_PROJECT_DIR` — Path to project root; used to locate `.claude/skills/skill-rules.json`
  - Falls back to `process.cwd()` when not set
- `SKIP_ERROR_REMINDER` — Optional; set to `1` to suppress `error-handling-reminder.ts` output

**No secrets files present** — this repo contains no `.env`, credential files, or API keys.

## Webhooks & Callbacks

**Incoming:**
- None — hooks are invoked locally by Claude Code, not via HTTP webhooks

**Outgoing:**
- None — no HTTP requests made by any hook scripts

## Target Project Integrations (Referenced in Skills)

The following integrations are documented as patterns in `.claude/skills/` for use in target Spring Boot projects. They do not exist in this repo itself.

**Databases (via JPA/Hibernate):**
- PostgreSQL — referenced in `testing-guide.md` (`PostgreSQLContainer<>("postgres:15")`)
- Any JPA-compatible RDBMS via Spring Data JPA

**Testing Infrastructure:**
- TestContainers — integration testing with real Docker containers
  - `@Testcontainers` + `@Container static PostgreSQLContainer<?>` pattern
- MockMvc — controller unit testing (`@WebMvcTest`)
- JUnit 5 + Mockito — unit testing (`@ExtendWith(MockitoExtension.class)`)

**Build/Containerization:**
- Docker Compose — local dev database management (Spring Boot 3.1+ auto-compose)
- Jib / Spring Boot Buildpacks — container image building (documented in `docker-and-deployment.md`)

**API Documentation:**
- OpenAPI/Swagger — permitted in security config at `/v3/api-docs/**`, `/swagger-ui/**`

---

*Integration audit: 2026-05-13*
