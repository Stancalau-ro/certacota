# Hooks Configuration Guide

Cross-platform hook configuration using Node.js/TypeScript.

---

## Prerequisites

Node.js must be installed. The hooks use `npx tsx` which works identically on Windows, Linux, and Mac.

```bash
node --version
npm --version
```

---

## Quick Start

### 1. Install Dependencies

```bash
cd .claude/hooks
npm install
```

### 2. Configure Hooks

Add to `.claude/settings.json`:

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

**Note:** This configuration works on Windows, Linux, and Mac without modification.

---

## Complete Configuration Example

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

## Customization

### Skill Activation Rules

Edit `.claude/skills/skill-rules.json` to customize skill triggers:

```json
{
  "skills": {
    "my-custom-skill": {
      "type": "domain",
      "enforcement": "suggest",
      "priority": "high",
      "promptTriggers": {
        "keywords": ["my-keyword", "another-keyword"],
        "intentPatterns": ["(create|add).*my-feature"]
      }
    }
  }
}
```

### Error Handling Reminder

Edit `error-handling-reminder.ts` to customize file category detection:

```typescript
function getFileCategory(filePath: string): 'controller' | 'service' | 'repository' | 'config' | 'test' | 'other' {
    const normalizedPath = filePath.replace(/\\/g, '/');

    // Add custom patterns
    if (normalizedPath.includes('/my-custom-dir/')) return 'service';

    // Existing patterns
    if (normalizedPath.includes('/controller/')) return 'controller';
    // ...
}
```

---

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `CLAUDE_PROJECT_DIR` | Project root directory | Current working directory |
| `SKIP_ERROR_REMINDER` | Set to `1` to disable error reminders | Not set |

**Windows:**
```powershell
$env:SKIP_ERROR_REMINDER = "1"
```

**Linux/Mac:**
```bash
export SKIP_ERROR_REMINDER=1
```

---

## Hook Event Types

### UserPromptSubmit
Runs when user submits a prompt.

**Use for:** Skill auto-activation, prompt enhancement

### PreToolUse
Runs before a tool executes.

**Use for:** Blocking dangerous operations, validation

### PostToolUse
Runs after a tool completes.

**Use for:** File tracking, audit logging

### Stop
Runs when Claude stops working.

**Use for:** Build validation, reminders

---

## Troubleshooting

### "npx: command not found"

Node.js is not installed. Install it:

**Windows:**
```powershell
winget install OpenJS.NodeJS.LTS
```

**Linux:**
```bash
curl -fsSL https://deb.nodesource.com/setup_lts.x | sudo -E bash -
sudo apt-get install -y nodejs
```

**Mac:**
```bash
brew install node
```

### "Cannot find module 'tsx'"

```bash
cd .claude/hooks
npm install
```

### Hook not running

1. Check settings.json has correct configuration
2. Verify `node --version` works
3. Run `npm list` in .claude/hooks to verify dependencies

### Debugging

Add console.log statements to TypeScript files:

```typescript
console.error('DEBUG: prompt =', prompt);
```

Output goes to stderr and will be visible in Claude Code.

---

## Best Practices

1. **Start minimal** - Enable skill-activation-prompt first
2. **Test thoroughly** - Verify hooks work before adding more
3. **Version control** - Commit `.claude/` directory to git
4. **Team consistency** - Share configuration across team

---

## See Also

- [README.md](./README.md) - Hooks overview
- [CLAUDE_INTEGRATION_GUIDE.md](../../CLAUDE_INTEGRATION_GUIDE.md) - Full integration guide