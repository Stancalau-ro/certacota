---
description: Map edited controllers & launch tests
argument-hint: "[/extra/path …]"
allowed-tools: Bash(cat:*), Bash(awk:*), Bash(grep:*), Bash(sort:*), Bash(xargs:*), Bash(sed:*)
model: sonnet
---

## Context

Changed controller files this session (auto-generated):

!cat "$CLAUDE_PROJECT_DIR/.claude/build-cache"/\*/edited-files.log \
 | awk -F: '{print $2}' \
 | grep -E '(Controller|Resource)\.java' \
 | sort -u

User-specified additional controllers: `$ARGUMENTS`

## Your task

Follow the numbered steps **exactly**:

1. Combine the auto list with `$ARGUMENTS`, dedupe, and identify the REST endpoints
   by examining @RequestMapping, @GetMapping, @PostMapping, etc.
2. For each endpoint, output a JSON record with the path, method, expected
   request/response shapes (from DTOs), and valid + invalid payload examples.
3. **Now call the `Task` tool** using:

```json
{
    "tool": "Task",
    "parameters": {
        "description": "endpoint smoke tests",
        "prompt": "Run the auth-route-tester sub-agent on the JSON above."
    }
}
```
