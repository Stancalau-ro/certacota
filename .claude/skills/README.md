# Skills

Production-tested skills for Claude Code that auto-activate based on context.

---

## What Are Skills?

Skills are modular knowledge bases that Claude loads when needed. They provide:
- Domain-specific guidelines
- Best practices
- Code examples
- Anti-patterns to avoid

**Problem:** Skills don't activate automatically by default.

**Solution:** This showcase includes the hooks + configuration to make them activate.

---

## Available Skills

### skill-developer (Meta-Skill)
**Purpose:** Creating and managing Claude Code skills

**Files:** 7 resource files

**Use when:**
- Creating new skills
- Understanding skill structure
- Working with skill-rules.json
- Debugging skill activation

**Customization:** None - copy as-is

**[View Skill](skill-developer/)**

---

### backend-dev-guidelines
**Purpose:** Spring Boot 3.x development patterns with Lombok and JPA

**Files:** 11 resource files

**Covers:**
- Layered architecture (Controller → Service → Repository)
- Spring Data JPA with optimized queries
- Bean Validation (Jakarta Validation)
- Spring Security with JWT
- Exception handling with @ControllerAdvice
- Lombok best practices
- TestContainers integration testing
- Configuration with @ConfigurationProperties

**Use when:**
- Creating/modifying REST endpoints
- Building controllers or services
- Database operations with JPA
- Setting up security

**Customization:** Update `pathPatterns` in skill-rules.json to match your Java directories

**Example pathPatterns:**
```json
{
  "pathPatterns": [
    "src/main/java/**/*.java",
    "*/src/main/java/**/*.java",
    "**/controller/**/*.java",
    "**/service/**/*.java"
  ]
}
```

**[View Skill](backend-dev-guidelines/)**

---

### route-tester
**Purpose:** Testing REST endpoints with MockMvc and TestContainers

**Files:** 1 main file

**Covers:**
- MockMvc unit testing
- TestContainers integration testing
- @WebMvcTest patterns
- Authentication testing with @WithMockUser
- Response validation with jsonPath

**Use when:**
- Testing API endpoints
- Writing controller tests
- Validating request/response handling

**Customization:** Customize for your test patterns

**[View Skill](route-tester/)**

---

### error-tracking
**Purpose:** Logging and exception handling patterns

**Files:** 1 main file

**Covers:**
- SLF4J/Logback logging
- @RestControllerAdvice for global exception handling
- Custom exceptions (ResourceNotFoundException, etc.)
- Error response format
- Spring Actuator health checks

**Use when:**
- Setting up logging
- Creating exception handlers
- Adding health checks
- Debugging production issues

**Customization:** Update `pathPatterns` for your project

**[View Skill](error-tracking/)**

---

## How to Add a Skill to Your Project

### Quick Integration

**For Claude Code:**
```
User: "Add the backend-dev-guidelines skill to my project"

Claude should:
1. Ask about project structure
2. Copy skill directory
3. Update skill-rules.json with their paths
4. Verify integration
```

See [CLAUDE_INTEGRATION_GUIDE.md](../../CLAUDE_INTEGRATION_GUIDE.md) for complete instructions.

### Manual Integration

**Step 1: Copy the skill directory**
```powershell
Copy-Item -Recurse claude-code-infrastructure-showcase/.claude/skills/backend-dev-guidelines `
      your-project/.claude/skills/
```

**Step 2: Update skill-rules.json**

If you don't have one, create it:
```powershell
Copy-Item claude-code-infrastructure-showcase/.claude/skills/skill-rules.json `
   your-project/.claude/skills/
```

Then customize the `pathPatterns` for your project:
```json
{
  "skills": {
    "backend-dev-guidelines": {
      "fileTriggers": {
        "pathPatterns": [
          "src/main/java/**/*.java"
        ]
      }
    }
  }
}
```

**Step 3: Test**
- Edit a Java file in your source directory
- The skill should activate automatically

---

## skill-rules.json Configuration

### What It Does

Defines when skills should activate based on:
- **Keywords** in user prompts ("controller", "service", "repository")
- **Intent patterns** (regex matching user intent)
- **File path patterns** (editing Java files)
- **Content patterns** (code contains @RestController)

### Configuration Format

```json
{
  "skill-name": {
    "type": "domain" | "guardrail",
    "enforcement": "suggest" | "block",
    "priority": "high" | "medium" | "low",
    "promptTriggers": {
      "keywords": ["list", "of", "keywords"],
      "intentPatterns": ["regex patterns"]
    },
    "fileTriggers": {
      "pathPatterns": ["src/main/java/**/*.java"],
      "contentPatterns": ["@RestController"]
    }
  }
}
```

### Enforcement Levels

- **suggest**: Skill appears as suggestion, doesn't block
- **block**: Must use skill before proceeding (guardrail)

**Use "block" for:**
- Preventing breaking changes
- Critical database operations
- Security-sensitive code

**Use "suggest" for:**
- General best practices
- Domain guidance
- Code organization

---

## Creating Your Own Skills

See the **skill-developer** skill for complete guide on:
- Skill YAML frontmatter structure
- Resource file organization
- Trigger pattern design
- Testing skill activation

**Quick template:**
```markdown
---
name: my-skill
description: What this skill does
---

# My Skill Title

## Purpose
[Why this skill exists]

## When to Use This Skill
[Auto-activation scenarios]

## Quick Reference
[Key patterns and examples]

## Resource Files
- [topic-1.md](resources/topic-1.md)
- [topic-2.md](resources/topic-2.md)
```

---

## Troubleshooting

### Skill isn't activating

**Check:**
1. Is skill directory in `.claude/skills/`?
2. Is skill listed in `skill-rules.json`?
3. Do `pathPatterns` match your files?
4. Are hooks installed and working?
5. Is settings.json configured correctly?

**Debug:**
```powershell
# Check skill exists
Get-ChildItem .claude/skills/

# Validate skill-rules.json
Get-Content .claude/skills/skill-rules.json | ConvertFrom-Json

# Check hooks exist
Get-ChildItem .claude/hooks/*.sh
```

### Skill activates too often

Update skill-rules.json:
- Make keywords more specific
- Narrow `pathPatterns`
- Increase specificity of `intentPatterns`

### Skill never activates

Update skill-rules.json:
- Add more keywords
- Broaden `pathPatterns`
- Add more `intentPatterns`

---

## For Claude Code

**When integrating a skill for a user:**

1. **Read [CLAUDE_INTEGRATION_GUIDE.md](../../CLAUDE_INTEGRATION_GUIDE.md)** first
2. Ask about their project structure
3. Customize `pathPatterns` in skill-rules.json
4. Verify the skill file has no hardcoded paths
5. Test activation after integration

**Common mistakes:**
- Keeping example paths
- Not asking about multi-module vs single-module
- Copying skill-rules.json without customization

---

## Next Steps

1. **Start simple:** Add one skill that matches your work
2. **Verify activation:** Edit a relevant file, skill should suggest
3. **Add more:** Once first skill works, add others
4. **Customize:** Adjust triggers based on your workflow

**Questions?** See [CLAUDE_INTEGRATION_GUIDE.md](../../CLAUDE_INTEGRATION_GUIDE.md) for comprehensive integration instructions.
