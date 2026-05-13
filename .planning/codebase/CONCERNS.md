# Codebase Concerns

**Analysis Date:** 2026-05-13

## Tech Debt

**`error-handling-reminder.ts` depends on a never-populated tracking file:**
- Issue: The hook reads `~/.claude/tsc-cache/{session_id}/edited-files.log` to identify which Java files were edited this session. Nothing in this repo writes that tracking file — there is no `PostToolUse` hook or editor integration that populates it. The hook will always find the file missing and silently exit without generating any reminders.
- Files: `.claude/hooks/error-handling-reminder.ts` (lines 74–79)
- Impact: The Spring Boot error-handling reminder is completely non-functional as shipped. It fires on every `Stop` event but immediately exits at line 79 because `edited-files.log` never exists.
- Fix approach: Either add a `PostToolUse` hook that writes to the tracking file when Java files are edited, or rewrite the hook to detect Java files from the session transcript instead of a side-channel log file.

**`SKIP_ERROR_REMINDER` env var is documented in output but never checked:**
- Issue: Line 193 of `error-handling-reminder.ts` prints `"TIP: Disable with SKIP_ERROR_REMINDER=1"`, but the hook never reads `process.env.SKIP_ERROR_REMINDER` before executing. The advertised disable mechanism does nothing.
- Files: `.claude/hooks/error-handling-reminder.ts` (line 193)
- Impact: Users who set the env var will still receive reminders; the documented opt-out is misleading.
- Fix approach: Add `if (process.env.SKIP_ERROR_REMINDER) process.exit(0);` near the top of `main()`.

**`skill-activation-prompt.ts` ignores `fileTriggers` in `skill-rules.json`:**
- Issue: `skill-rules.json` defines `fileTriggers.pathPatterns` and `fileTriggers.contentPatterns` for four skills (`backend-dev-guidelines`, `route-tester`, `error-tracking`, `product-owner`), but `skill-activation-prompt.ts` only processes `promptTriggers` (keywords and intent patterns). The file-based trigger machinery is declared but never evaluated.
- Files: `.claude/skills/skill-rules.json`, `.claude/hooks/skill-activation-prompt.ts`
- Impact: Skills like `backend-dev-guidelines` will not activate when Claude edits a Java file containing `@RestController`, even though the rules declare that as a trigger. The hook only fires on user prompt text, not file context.
- Fix approach: Add a `fileTriggers` evaluation pass in `skill-activation-prompt.ts` that checks the CWD and any file paths present in the hook input against configured `pathPatterns` and `contentPatterns`.

**`dev/README.md` references a directory that does not exist:**
- Issue: `dev/README.md` (line 291) says "See **dev/active/public-infrastructure-repo/** in this repository for a real example". That directory does not exist in the repo.
- Files: `dev/README.md` (line 291)
- Impact: The linked example is a broken reference that will confuse anyone following the guide.
- Fix approach: Either create the example directory or remove the reference.

**`dev/architecture.md` appears to be a design document for a different system (token economy engine), not for the certacota reference library itself:**
- Issue: `dev/architecture.md` describes a Spring Boot token economy engine with Redis, RabbitMQ, and PostgreSQL — a separate product unrelated to the Claude Code infrastructure components in this repo. The file appears to have been committed here accidentally or as a reference artefact.
- Files: `dev/architecture.md`
- Impact: Confusing to anyone exploring the repo; adds 900+ lines of irrelevant material.
- Fix approach: Verify intent; if this is reference material for the architecture skill, move it into `.claude/skills/backend-dev-guidelines/resources/`. If accidental, remove it.

---

## Security Considerations

**`settings.json` uses `$CLAUDE_PROJECT_DIR` env var with `bash` hook invocations on Windows:**
- Risk: Hook commands such as `bash "$CLAUDE_PROJECT_DIR"/.claude/hooks/gsd-session-state.sh` and `bash "$CLAUDE_PROJECT_DIR"/.claude/hooks/gsd-phase-boundary.sh` rely on `bash` being available as a command on the host system. On Windows (this repo's documented platform), `bash` is typically Git Bash or WSL — not guaranteed present or on `PATH`. If absent, these hooks fail silently (exit non-zero) but the shell variable `$CLAUDE_PROJECT_DIR` would not expand in PowerShell anyway.
- Files: `.claude/settings.json` (lines 60–63, 91–94, 132–136)
- Current mitigation: The bash hooks are OPT-IN (they check `hooks.community: true` in `.planning/config.json` and exit silently if not enabled). Because this repo has no `.planning/config.json`, the hooks exit immediately and cause no harm.
- Recommendations: Add a note in `CLAUDE_INTEGRATION_GUIDE.md` that bash hooks require a POSIX shell and are not supported on stock Windows PowerShell environments. Offer PowerShell equivalents or document that these hooks only work on WSL/Git Bash.

**`gsd-read-injection-scanner.js` exclusion list is path-separator-sensitive:**
- Risk: The exclusion for `.claude/hooks/` (line 59) uses a forward-slash path: `p.includes('/.claude/hooks/')`. On Windows, file paths use backslashes. The check preceding this (`p.replace(/\\/g, '/')`) does normalize the path, so this specific check is safe — but the pattern is fragile; future additions to the exclusion list that lack this normalization would be Windows-broken.
- Files: `.claude/hooks/gsd-read-injection-scanner.js` (line 51–61)
- Recommendations: Centralise the path normalisation call at the top of `isExcludedPath()` once, then apply all checks uniformly. Currently the normalisation and the checks are co-located but it requires manual discipline to maintain.

**`settings.json` is committed with permissive `defaultMode: acceptEdits` and `allow: ["Edit:*", "Write:*", "Bash:*"]`:**
- Risk: These permissions are maximally permissive. Any integrator who copies `settings.json` verbatim into a production project inherits these permissions for their AI assistant.
- Files: `.claude/settings.json`
- Current mitigation: `CLAUDE_INTEGRATION_GUIDE.md` explicitly warns "NEVER copy the showcase settings.json directly!" (line 477).
- Recommendations: Consider removing or restricting the blanket `allow` rules from the committed `settings.json`. A more conservative default would reduce blast radius for integrators who miss the guide's warning.

---

## Performance Bottlenecks

**`gsd-statusline.js` reads up to 256 KiB of transcript on every tool use:**
- Problem: `readLastSlashCommand()` reads the last 256 KB of the Claude Code JSONL transcript on every statusline render. The statusline is triggered frequently (every tool use in the `PostToolUse` event). For long sessions with large transcripts, this is repeated I/O.
- Files: `.claude/hooks/gsd-statusline.js` (lines 66–79)
- Cause: Transcript grows unboundedly during a session; 256 KB cap is applied on each render without caching.
- Improvement path: Cache the last-seen slash command with a short TTL or invalidate only when the transcript file mtime changes; avoids repeated reads for the common case where the last command hasn't changed.

**`gsd-check-update-worker.js` spawns `npm view get-shit-done-cc version` on every session start:**
- Problem: This makes a live npm registry HTTP call on every new session. On slow networks or in airgapped environments it will time out (10-second hard timeout) on every session start before caching kicks in.
- Files: `.claude/hooks/gsd-check-update-worker.js` (lines 91–104), `.claude/hooks/gsd-check-update.js`
- Cause: The worker is spawned unconditionally on `SessionStart`; there is no check to see whether the cached result is recent before running the npm query.
- Improvement path: Add a freshness check in the parent hook (`gsd-check-update.js`) — skip spawning the worker if the cache file is less than N hours old (e.g., 6 hours). Currently the cache is read by the statusline but the worker that writes it runs regardless of cache age.

---

## Fragile Areas

**`error-handling-reminder.ts` — hardcoded cache path ties the hook to `~/.claude/`:**
- Files: `.claude/hooks/error-handling-reminder.ts` (line 74)
- Why fragile: The tracking file path is `join(homedir(), '.claude', 'tsc-cache', session_id)`. This hardcodes the `.claude` subdirectory rather than using `CLAUDE_CONFIG_DIR` the way `gsd-statusline.js` does. On environments using a non-default config directory (or when Claude is installed as a different runtime), this path will be wrong.
- Safe modification: Replace `join(homedir(), '.claude', ...)` with `join(process.env.CLAUDE_CONFIG_DIR || join(homedir(), '.claude'), ...)`.
- Test coverage: None. The hook has no tests.

**`gsd-validate-commit.sh` depends on `hooks/lib/git-cmd.js` which is not present in this repo:**
- Files: `.claude/hooks/gsd-validate-commit.sh` (line 29–33)
- Why fragile: The script calls `require(process.env.GIT_CMD_LIB)` to load `hooks/lib/git-cmd.js` for git commit classification. That file does not exist under `.claude/hooks/lib/` in this repository. If the hook is enabled (via `hooks.community: true`) and a `git commit` is run, Node will fail to load the module and the hook will exit non-zero, potentially blocking commits.
- Safe modification: The hook silently handles the failure via `2>/dev/null` but the fall-through path (`if GIT_CMD_LIB=... node -e "..." 2>/dev/null; then`) means it falls through to the message extraction without the isGitSubcommand guard — every Bash command containing a `-m` flag matching the message regex would be checked, not just git commits.
- Test coverage: None in this repo.

**`gsd-context-monitor.js` spawns `gsd-tools.cjs` which is not present in this repo:**
- Files: `.claude/hooks/gsd-context-monitor.js` (lines 143–151)
- Why fragile: When context hits CRITICAL level in a GSD project, the monitor attempts to spawn `path.join(__dirname, '..', 'get-shit-done', 'bin', 'gsd-tools.cjs')`. This path resolves to `.claude/get-shit-done/bin/gsd-tools.cjs`, which does not exist in this repo. The spawn is wrapped in a try/catch and `.unref()`d, so it fails silently — but the intent of recording session state on context exhaustion is not fulfilled.
- Safe modification: Add an `existsSync` check before spawning. Document in `CLAUDE_INTEGRATION_GUIDE.md` that this feature requires the full GSD installation (`get-shit-done` package).

---

## Missing Critical Features

**No test file for TypeScript hooks:**
- Problem: `package.json` defines a `test` script (`tsx skill-activation-prompt.ts < test-input.json`) but `test-input.json` does not exist in the repo. Running `npm test` will immediately fail. The JS hooks (`gsd-*.js`) have no tests at all.
- Blocks: Integrators cannot verify hook correctness before deploying.
- Files: `.claude/hooks/package.json` (line 9)

**`fileTriggers` in `skill-rules.json` is declared but not consumed:**
- Problem: Four skills define `fileTriggers` with `pathPatterns` and `contentPatterns`, but no hook reads these fields. The integration guide describes file-based skill activation but the implementation only supports prompt-text matching.
- Blocks: Skills cannot auto-activate when Claude edits matching files, only when the user types matching keywords.
- Files: `.claude/skills/skill-rules.json`, `.claude/hooks/skill-activation-prompt.ts`

---

## Test Coverage Gaps

**TypeScript hooks (`.ts` files) have zero automated tests:**
- What's not tested: `skill-activation-prompt.ts` — keyword/intent matching logic, priority grouping, output formatting; `request-clarity-check.ts` — all vague pattern detection, exclusion patterns, context indicator logic; `error-handling-reminder.ts` — file categorisation, analysis logic, reminder formatting.
- Files: `.claude/hooks/skill-activation-prompt.ts`, `.claude/hooks/request-clarity-check.ts`, `.claude/hooks/error-handling-reminder.ts`
- Risk: Regex patterns in `request-clarity-check.ts` are complex (~7 vague patterns, ~8 exclusion patterns, ~11 context indicators); breakage during customisation would be silent.
- Priority: High

**JavaScript hooks (`gsd-*.js`) have partial or no tests:**
- What's not tested: `gsd-context-monitor.js` — threshold logic, debounce, severity escalation; `gsd-validate-commit.sh` — commit message parsing, conventional commit validation; `gsd-phase-boundary.sh` — planning file detection and JSON envelope construction.
- Files: All `gsd-*.js` and `gsd-*.sh` under `.claude/hooks/`
- Risk: The hooks that do have exported functions (`gsd-statusline.js`, `gsd-update-banner.js`) export helpers — but tests for those files are not present in this repo.
- Priority: Medium

---

## Dependencies at Risk

**`@types/node` pinned to `^20.11.0` while `tsx` and `typescript` are on newer-compatible ranges:**
- Risk: The Node.js types are pinned to v20 era. As Node.js 22 or 24 becomes the integration target, type definitions for newer built-ins will be missing.
- Impact: TypeScript may fail to compile hooks that use newer Node.js APIs if they are added.
- Migration plan: Update to `@types/node@^22` and test against Node.js 22 LTS.

**`tsx` at `^4.7.0` — now several minor versions behind (`tsx` 4.19+ available as of 2026):**
- Risk: Older `tsx` versions have known issues with ESM/CommonJS interop on Windows. The `package.json` uses `"type": "module"` with `NodeNext` module resolution, which is sensitive to `tsx` version.
- Impact: Potential silent execution failures on Windows if an installed `tsx` version has interop bugs.
- Migration plan: Lock `tsx` to a specific tested version or update to `^4.19`.

---

*Concerns audit: 2026-05-13*
