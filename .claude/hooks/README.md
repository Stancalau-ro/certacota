# Hooks

Cross-platform Claude Code hooks for Spring Boot development. Written in TypeScript, works on Windows, Linux, and Mac.

---

## Prerequisites

**Node.js is required** for hooks to work. The hooks are written in TypeScript and run via `npx tsx`.

### Check Node.js Installation

```bash
node --version
npm --version
```

### Install Node.js

**Windows:**
```powershell
winget install OpenJS.NodeJS.LTS
```

**Linux (Ubuntu/Debian):**
```bash
curl -fsSL https://deb.nodesource.com/setup_lts.x | sudo -E bash -
sudo apt-get install -y nodejs
```

**Mac:**
```bash
brew install node
```

---

## Setup (All Platforms)

```bash
cd .claude/hooks
npm install
```

---

## Available Hooks

### skill-activation-prompt (UserPromptSubmit)

**Purpose:** Automatically suggests relevant skills based on user prompts

**How it works:**
1. Reads `skill-rules.json`
2. Matches user prompt against trigger patterns
3. Injects skill suggestions into Claude's context

**Why it's essential:** This is THE hook that makes skills auto-activate.

### request-clarity-check (UserPromptSubmit)

**Purpose:** Detects vague/incomplete requests and suggests clarifying questions

**How it works:**
1. Analyzes user prompt for vague patterns (e.g., "fix the bug", "add feature")
2. Identifies request category (Bug Report, Feature Request, Refactoring, etc.)
3. Suggests targeted clarifying questions
4. Recommends using AskUserQuestion or request-analyzer agent

**Detects:**
- Vague bug reports without symptoms/reproduction steps
- Feature requests without scope/details
- Improvement requests without metrics/goals
- Refactoring requests without boundaries

**Excludes (no false positives):**
- Questions (starts with what/how/why)
- Specific file references (mentions .java, .ts, etc.)
- Git commands (commit, push, merge)
- Detailed requests with context indicators

### error-handling-reminder (Stop)

**Purpose:** Reminds about Spring Boot best practices when Java files are edited

**Checks for:**
- Controllers without @Slf4j logging
- Services without @Transactional
- Missing @ControllerAdvice for exception handling

---

## Configuration

Add to `.claude/settings.json` - **same configuration works on all platforms:**

```json
{
  "hooks": {
    "UserPromptSubmit": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "npx tsx .claude/hooks/skill-activation-prompt.ts"
          }
        ]
      },
      {
        "hooks": [
          {
            "type": "command",
            "command": "npx tsx .claude/hooks/request-clarity-check.ts"
          }
        ]
      }
    ],
    "Stop": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "npx tsx .claude/hooks/error-handling-reminder.ts"
          }
        ]
      }
    ]
  }
}
```

---

## Full Configuration Example

```json
{
  "enableAllProjectMcpServers": true,
  "permissions": {
    "allow": ["Edit:*", "Write:*", "Bash:*"],
    "defaultMode": "acceptEdits"
  },
  "hooks": {
    "UserPromptSubmit": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "npx tsx .claude/hooks/skill-activation-prompt.ts"
          }
        ]
      },
      {
        "hooks": [
          {
            "type": "command",
            "command": "npx tsx .claude/hooks/request-clarity-check.ts"
          }
        ]
      }
    ],
    "Stop": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "npx tsx .claude/hooks/error-handling-reminder.ts"
          }
        ]
      }
    ]
  }
}
```

---

## What Are Hooks?

Hooks are scripts that run at specific points in Claude's workflow:
- **UserPromptSubmit**: When user submits a prompt
- **PreToolUse**: Before a tool executes
- **PostToolUse**: After a tool completes
- **Stop**: When Claude stops working

**Key insight:** Hooks can modify prompts, block actions, and track state - enabling features Claude can't do alone.

---

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `CLAUDE_PROJECT_DIR` | Project root directory | Current working directory |
| `SKIP_ERROR_REMINDER` | Set to `1` to disable error reminders | Not set |

---

## Hook Files

| File | Purpose |
|------|---------|
| `skill-activation-prompt.ts` | Auto-suggest skills based on prompt |
| `error-handling-reminder.ts` | Spring Boot best practice reminders |
| `package.json` | npm dependencies (tsx) |
| `tsconfig.json` | TypeScript configuration |

---

## Troubleshooting

### "npx: command not found"

Node.js is not installed or not in PATH. See installation instructions above.

### "Cannot find module 'tsx'"

Run npm install in the hooks directory:
```bash
cd .claude/hooks
npm install
```

### Hooks not running

1. Verify settings.json has hooks configured
2. Check Node.js is installed: `node --version`
3. Verify dependencies: `npm list` in .claude/hooks

---

## For Claude Code

**When setting up hooks for a user:**

1. **Check Node.js:** `node --version`
2. **Install dependencies:** `cd .claude/hooks && npm install`
3. **Add hook configuration** to `.claude/settings.json`
4. **Test:** The hook should run on next prompt

**Note:** The same `npx tsx` command works on Windows, Linux, and Mac - no platform-specific configuration needed.

**Questions?** See [CLAUDE_INTEGRATION_GUIDE.md](../../CLAUDE_INTEGRATION_GUIDE.md)