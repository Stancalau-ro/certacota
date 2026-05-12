# Claude Integration Guide

**FOR CLAUDE CODE:** When a user asks you to integrate components from this showcase repository into their project, follow these instructions carefully.

---

## Overview

This repository is a **reference library** of Claude Code infrastructure components for Spring Boot 3.x development. Users will ask you to help integrate specific pieces into their projects. Your role is to:

1. **Ask clarifying questions** about their project structure
2. **Copy the appropriate files**
3. **Customize configurations** for their setup
4. **Verify the integration** works correctly

**Key Principle:** ALWAYS ask before assuming project structure. What works for one project won't work for another.

---

## Tech Stack Compatibility Check

**CRITICAL:** Before integrating a skill, verify the user's tech stack matches the skill requirements.

### Backend Skills

**backend-dev-guidelines requires:**
- Spring Boot 3.x
- Java 17+
- Lombok
- Spring Data JPA
- Maven or Gradle

**Before integrating, ask:**
"Do you use Spring Boot 3.x with Lombok?"

**If NO:**
```
The backend-dev-guidelines skill is designed for Spring Boot 3.x with Lombok. I can:
1. Help you create similar guidelines adapted for [their stack] using this as a template
2. Extract the architecture patterns (layered architecture works for any framework)
3. Skip this skill

Which would you prefer?
```

### Skills That Are Tech-Agnostic

These work for ANY tech stack:
- **skill-developer** - Meta-skill, no tech requirements
- **route-tester** - Adapted for MockMvc/TestContainers (Spring Boot focused)
- **error-tracking** - Logging patterns (Spring Boot focused but concepts transfer)

---

## General Integration Pattern

When user says: **"Add [component] to my project"**

1. Identify component type (skill/hook/agent/command)
2. **CHECK TECH STACK COMPATIBILITY**
3. Ask about their project structure
4. Copy files OR adapt for their stack
5. Customize for their setup
6. Verify integration
7. Provide next steps

---

## Integrating Skills

### Step-by-Step Process

**When user requests a skill** (e.g., "add backend-dev-guidelines"):

#### 1. Understand Their Project

**ASK THESE QUESTIONS:**
- "What's your project structure? Single module, multi-module Maven/Gradle?"
- "Where is your Java source code located?"
- "Do you use Spring Boot 3.x with Lombok?"

#### 2. Copy the Skill

```powershell
Copy-Item -Recurse showcase/.claude/skills/[skill-name] .claude/skills/
```

#### 3. Handle skill-rules.json

**Check if it exists:**
```powershell
Test-Path .claude/skills/skill-rules.json
```

**If NO (doesn't exist):**
- Copy the template from showcase
- Remove skills user doesn't want
- Customize for their project

**If YES (exists):**
- Read their current skill-rules.json
- Add the new skill entry
- Merge carefully to avoid breaking existing skills

#### 4. Customize Path Patterns

**CRITICAL:** Update `pathPatterns` in skill-rules.json to match THEIR structure:

**Example - User has multi-module Maven:**
```json
{
  "backend-dev-guidelines": {
    "fileTriggers": {
      "pathPatterns": [
        "api/src/main/java/**/*.java",
        "core/src/main/java/**/*.java",
        "service/src/main/java/**/*.java"
      ]
    }
  }
}
```

**Example - User has single module:**
```json
{
  "backend-dev-guidelines": {
    "fileTriggers": {
      "pathPatterns": [
        "src/main/java/**/*.java"
      ]
    }
  }
}
```

**Safe Generic Patterns** (when unsure):
```json
{
  "pathPatterns": [
    "**/*.java",
    "src/main/java/**/*.java"
  ]
}
```

#### 5. Verify Integration

```powershell
# Check skill was copied
Get-Item .claude/skills/[skill-name]

# Validate skill-rules.json syntax
Get-Content .claude/skills/skill-rules.json | ConvertFrom-Json
```

**Tell user:** "Try editing a Java file in [their-source-path] and the skill should activate."

---

### Skill-Specific Notes

#### backend-dev-guidelines
- **Tech Requirements:** Spring Boot 3.x, Java 17+, Lombok
- **Ask:** "Do you use Spring Boot with Lombok?" "Where's your Java source code?"
- **If different stack:** Offer to adapt using this as template
- **Customize:** pathPatterns
- **Example paths:** `src/main/java/`, `api/src/main/java/`, `**/src/main/java/`

#### route-tester
- **Tech Requirements:** Spring Boot with Spring Test
- **Ask:** "Do you use MockMvc or TestContainers for testing?"
- **If NO:** "This skill is designed for Spring Boot testing. Want me to adapt it or skip it?"
- **Customize:** Test paths and patterns
- **Works with:** MockMvc, TestRestTemplate, TestContainers

#### error-tracking
- **Tech Requirements:** Spring Boot (works with SLF4J/Logback)
- **Ask:** "Do you use Spring Boot?" "Where's your Java source code?"
- **Customize:** pathPatterns
- **Adaptation tip:** Error handling philosophy transfers to other frameworks

#### skill-developer
- **Tech Requirements:** None!
- **Copy as-is** - meta-skill, fully generic, teaches skill creation for ANY tech stack

---

## Adapting Skills for Different Tech Stacks

When user's tech stack differs from skill requirements, you have options:

### Option 1: Adapt Existing Skill (Recommended)

**When to use:** User wants similar guidelines but for different tech

**Process:**
1. **Copy the skill as a starting point:**
   ```powershell
   Copy-Item -Recurse showcase/.claude/skills/backend-dev-guidelines `
         .claude/skills/quarkus-dev-guidelines
   ```

2. **Identify what needs changing:**
   - Framework-specific code examples (Spring → Quarkus)
   - Library APIs (Spring Data → Panache)
   - Annotation patterns
   - Configuration formats

3. **Keep what transfers:**
   - Layered architecture principles
   - Service layer patterns
   - Repository patterns
   - DTO best practices
   - Testing strategies

### Option 2: Extract Framework-Agnostic Patterns

**When to use:** Stacks are very different, but core principles apply

**What Usually Transfers Across Tech Stacks:**

**Architecture & Organization:**
- Layered architecture (Controller/Service/Repository pattern)
- Separation of concerns
- Package organization strategies
- Repository pattern for data access

**Development Practices:**
- Error handling philosophy
- Input validation importance
- Testing strategies
- Logging best practices

**Framework-Specific Code:**
- Spring annotations → Don't transfer to Quarkus/Micronaut directly
- JPA queries → Different ORM syntax
- Security configuration → Framework-specific

---

## Integrating Hooks

### Prerequisites

**Node.js is required** for hooks to work. Hooks are written in TypeScript and run via `npx tsx`, which works identically on Windows, Linux, and Mac.

**Check Node.js Installation:**
```bash
node --version
npm --version
```

**Install Node.js:**

| Platform | Command |
|----------|---------|
| **Windows** | `winget install OpenJS.NodeJS.LTS` |
| **Linux (Ubuntu/Debian)** | `curl -fsSL https://deb.nodesource.com/setup_lts.x \| sudo -E bash - && sudo apt-get install -y nodejs` |
| **Mac** | `brew install node` |

---

### Essential Hooks (Always Safe to Copy)

#### skill-activation-prompt (UserPromptSubmit)

**Purpose:** Auto-suggests skills based on user prompts

**Integration (NO customization needed):**

```powershell
# Copy TypeScript files and dependencies
Copy-Item showcase/.claude/hooks/skill-activation-prompt.ts .claude/hooks/
Copy-Item showcase/.claude/hooks/package.json .claude/hooks/
Copy-Item showcase/.claude/hooks/tsconfig.json .claude/hooks/

# Install dependencies
Set-Location .claude/hooks
npm install
```

**Add to settings.json (works on ALL platforms):**
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
    ]
  }
}
```

**This hook is FULLY GENERIC** - works on Windows, Linux, and Mac with no changes!

#### error-handling-reminder (Stop)

**Purpose:** Reminds about Spring Boot best practices when Java files are edited

**Checks for:**
- Controllers without @Slf4j logging
- Services without @Transactional
- Missing @ControllerAdvice for exception handling

**Integration:**

```powershell
Copy-Item showcase/.claude/hooks/error-handling-reminder.ts .claude/hooks/
```

**Add to settings.json:**
```json
{
  "hooks": {
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

**This hook is FULLY GENERIC** - cross-platform, no customization needed!

---

### Optional Hooks (Require Heavy Customization)

#### Build-related hooks

**WARNING:** Stop hooks are configured for specific project structures.

**For SIMPLE projects (single module):**
- **RECOMMEND SKIPPING** these hooks
- They're overkill for single-module projects
- User can run `mvn compile` manually instead

**For COMPLEX projects (multi-module):**
1. Copy the files
2. **MUST EDIT** to match your project structure
3. Test manually before adding to settings.json

---

## Integrating Agents

**Agents are STANDALONE** - easiest to integrate!

### Standard Agent Integration

```powershell
# Copy the agent file
Copy-Item showcase/.claude/agents/[agent-name].md .claude/agents/
```

**That's it!** Agents work immediately, no configuration needed.

### Agent-Specific Notes

**auth-route-tester / auth-route-debugger:**
- Require JWT-based authentication in user's project
- Ask: "Do you use JWT for auth?"
- If NO: "These agents are for JWT auth. Skip them or want me to adapt?"

**auto-error-resolver:**
- Works with Maven or Gradle
- May need build command updates

**All other agents:**
- Copy as-is, they're fully generic

---

## Integrating Slash Commands

```powershell
# Copy command file
Copy-Item showcase/.claude/commands/[command].md .claude/commands/
```

### Customize Paths

Commands may reference dev docs paths. **Check and update:**

**dev-docs and dev-docs-update:**
- Look for `dev/active/` path references
- Ask: "Where do you want dev documentation stored?"
- Update paths in the command files

---

## Common Patterns & Best Practices

### Pattern: Asking About Project Structure

**DON'T assume:**
- "I'll add this for your api module"
- "Configuring for your src/main/java directory"

**DO ask:**
- "What's your project structure? Single module or multi-module?"
- "Where is your Java source code located?"
- "Do you use Maven or Gradle?"

### Pattern: Customizing skill-rules.json

**User has multi-module Maven:**
```json
{
  "pathPatterns": [
    "*/src/main/java/**/*.java",
    "*/src/test/java/**/*.java"
  ]
}
```

**User has simple structure:**
```json
{
  "pathPatterns": [
    "src/main/java/**/*.java"
  ]
}
```

### Pattern: settings.json Integration

**NEVER copy the showcase settings.json directly!**

Instead, **extract and merge** the sections they need:

1. Read their existing settings.json
2. Add the hook configurations they want
3. Preserve their existing config

---

## Verification Checklist

After integration, **verify these items:**

```powershell
# 1. skill-rules.json is valid JSON
Get-Content .claude/skills/skill-rules.json | ConvertFrom-Json

# 2. Hook dependencies installed
Get-ChildItem .claude/hooks/node_modules/

# 3. Settings.json is valid JSON
Get-Content .claude/settings.json | ConvertFrom-Json

# 4. Node.js is available (required for hooks)
node --version
```

**Then ask user to test:**
- "Try editing a Java file in [relevant-path] - the skill should activate"
- "Try asking me a question about [topic] - I should suggest the skill"

---

## Common Mistakes to Avoid

### DON'T: Copy settings.json as-is
**Why:** The Stop hooks reference non-existent services
**DO:** Extract only UserPromptSubmit and PostToolUse sections

### DON'T: Keep example module names
**Why:** User doesn't have blog-api, auth-service, etc.
**DO:** Ask about their actual structure and update

### DON'T: Assume Maven structure
**Why:** User might use Gradle or different directory layout
**DO:** Ask first, then customize

### DON'T: Add all skills at once
**Why:** Overwhelming and may not all be relevant
**DO:** Ask which skills are relevant to their work

---

## Example Integration Conversations

### Example 1: Adding Backend Skill

**User:** "Add the backend-dev-guidelines skill to my project"

**Claude should respond:**
```
I'll add the backend-dev-guidelines skill. First, I need to understand
your project structure:

1. Is this a single-module or multi-module Maven/Gradle project?
2. Where is your Java source code located?
3. Do you use Spring Boot 3.x with Lombok?
```

**User:** "Single module Maven, code is in src/main/java/, yes Spring Boot 3.2"

**Claude should:**
1. Copy the skill directory
2. Update skill-rules.json with pathPattern: `"src/main/java/**/*.java"`
3. Verify it's copied correctly
4. Respond:
```
Added backend-dev-guidelines skill!

Configuration:
- Skill triggers when editing files in src/main/java/
- Activated by keywords like "controller", "service", "repository", "entity"

Try it: Edit a Java file and you should see the skill suggestion.
```

### Example 2: Tech Stack Mismatch

**User:** "Add the backend-dev-guidelines skill to my Quarkus project"

**Claude should:**
```
I see you're using Quarkus. The backend-dev-guidelines skill is specifically
designed for Spring Boot 3.x. I have a few options:

Option 1 (Recommended): I can create a quarkus-dev-guidelines skill using the
Spring skill as a template. I'll adapt:
- Annotations for Quarkus (CDI instead of Spring DI)
- Replace Spring Data JPA with Panache
- Update configuration patterns for Quarkus
- Keep: Layered architecture, DTO patterns, testing strategies

Option 2: Extract only the framework-agnostic patterns (layered architecture,
DTOs, testing) without Spring-specific code.

Option 3: Skip it and help you create Quarkus guidelines from scratch.

Which would you prefer?
```

---

## Quick Reference Tables

### What Needs Customization?

| Component | Tech Requirements | Customization | What to Ask |
|-----------|------------------|--------------|-------------|
| **skill-developer** | None | None | Copy as-is |
| **backend-dev-guidelines** | Spring Boot 3.x/Lombok | Paths + tech check | "Use Spring Boot?" "Where's Java source?" |
| **route-tester** | Spring Test | Test paths | "Use MockMvc/TestContainers?" |
| **error-tracking** | Spring Boot | Paths | "Where's Java source?" |
| **skill-activation-prompt** | Node.js | None | Copy as-is, run `npm install` |
| **error-handling-reminder** | Node.js | None | Copy as-is, run `npm install` |
| **All agents** | Minimal | Check paths | Auth for auth-related |
| **All commands** | Paths | Ask about dev docs location |

### When to Recommend Skipping

| Component | Skip If... |
|-----------|-----------|
| **Build hooks** | Single-module project or different build setup |
| **route-tester** | Not using Spring Test patterns |
| **auth agents** | Not using JWT authentication |

---

## Final Tips for Claude

**When user says "add everything":**
- Start with essentials: skill-activation hooks + 1-2 relevant skills
- Don't overwhelm them with all skills + agents
- Ask what they actually need

**When something doesn't work:**
- Check verification checklist
- Verify paths match their structure
- Test hooks manually
- Check for JSON syntax errors

**When user is unsure:**
- Recommend starting with just skill-activation hooks
- Add backend skill if they use Spring Boot
- Add more later as needed

**Always explain what you're doing:**
- Show the commands you're running
- Explain why you're asking questions
- Provide clear next steps after integration

---

**Remember:** This is a reference library, not a working application. Your job is to help users cherry-pick and adapt components for THEIR specific project structure.
