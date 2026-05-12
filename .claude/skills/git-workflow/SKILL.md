---
name: git-workflow
description: Git workflow standards for branch management, commit practices, and code quality gates. Use when creating branches, committing code, merging, or managing git workflow. Enforces clean commit history, meaningful messages, and pre-commit testing requirements.
---

# Git Workflow Standards

## Purpose

Establish consistent git practices for branch management, commit hygiene, and quality gates. These standards ensure a clean, readable history and prevent broken code from entering the repository.

## When to Use This Skill

Automatically activates when:
- Starting a new feature, bug fix, or task
- Creating or switching branches
- Committing changes
- Merging or creating pull requests
- Discussing git workflow or history

---

## Branch Management

### Branch Naming Convention

```
<type>/<short-description>
```

| Type | Use Case | Example |
|------|----------|---------|
| `feature/` | New functionality | `feature/user-authentication` |
| `bugfix/` | Bug fixes | `bugfix/fix-login-timeout` |
| `hotfix/` | Production urgent fixes | `hotfix/security-patch` |
| `chore/` | Maintenance, refactoring | `chore/update-dependencies` |
| `docs/` | Documentation only | `docs/api-documentation` |

### Starting a New Task

```powershell
# 1. Ensure you're on the latest main
git checkout main
git pull origin main

# 2. Create feature branch
git checkout -b feature/user-authentication

# 3. Verify branch
git branch --show-current
```

### Branch Rules

| Rule | Description |
|------|-------------|
| Branch from `main` | Always create branches from the latest main |
| One task per branch | Each branch addresses ONE task/story |
| Keep branches short-lived | Merge within days, not weeks |
| Delete after merge | Clean up merged branches |
| No direct commits to main | All changes via pull requests |

---

## Commit Message Standards

### Format

```
<type>: <short summary>

[optional body - explain WHY, not WHAT]
```

### Rules

| Rule | Requirement |
|------|-------------|
| **NO EMOJIS** | Keep messages professional and searchable |
| Max 50 chars | Subject line must be concise |
| Imperative mood | "Add feature" not "Added feature" |
| Focus on WHY | Explain the reason, not the code change |
| No period | Don't end subject line with a period |
| Capitalize | Start with capital letter |
| **No Claude footer** | Do NOT add the default Claude Code footer |

### CRITICAL: No Default Claude Code Footer

**DO NOT USE** the standard Claude Code commit footer:
```
# WRONG - Never use this format
feat: Add feature

Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude <noreply@anthropic.com>
```

**CORRECT** - Simple, clean commit message:
```
feat: Add feature

Optional body explaining WHY.
```

This project requires clean commit messages without emojis or AI attribution footers.

### Commit Types

| Type | Use Case |
|------|----------|
| `feat` | New feature |
| `fix` | Bug fix |
| `refactor` | Code restructuring (no behavior change) |
| `docs` | Documentation only |
| `test` | Adding or fixing tests |
| `chore` | Maintenance, dependencies |
| `perf` | Performance improvement |
| `style` | Formatting (no code change) |

### Good vs Bad Examples

```
# BAD - describes code change
git commit -m "Changed UserService.java to add null check"

# GOOD - explains why
git commit -m "fix: Prevent crash when user profile is incomplete"
```

```
# BAD - too vague
git commit -m "Fixed bug"

# GOOD - specific and clear
git commit -m "fix: Resolve timeout on login with slow network"
```

```
# BAD - uses emoji
git commit -m ":sparkles: Add new dashboard feature"

# GOOD - professional
git commit -m "feat: Add analytics dashboard for admin users"
```

```
# BAD - too long, describes code
git commit -m "Updated the UserController to add a new endpoint for getting user preferences and also modified the UserService to include the new method"

# GOOD - concise, explains purpose
git commit -m "feat: Allow users to retrieve their preferences"
```

### Multi-line Commit Example

```
feat: Add user preference management

Users need to customize their dashboard layout and notification
settings. This enables self-service configuration without admin
intervention.
```

---

## Pre-Commit Requirements

### Before Every Commit

```powershell
# 1. Run unit tests (minimum)
./gradlew test

# 2. Check for compilation errors
./gradlew compileJava

# 3. Review your changes
git diff --staged

# 4. Commit only if tests pass
git commit -m "feat: Your message here"
```

### Pre-Commit Checklist

- [ ] Code compiles without errors
- [ ] Unit tests pass
- [ ] No unintended files staged (check `git status`)
- [ ] Commit message follows standards
- [ ] Changes relate to ONE logical unit

### Testing Requirements by Change Type

| Change Type | Minimum Tests | Recommended |
|-------------|---------------|-------------|
| New feature | Unit tests | Unit + Integration |
| Bug fix | Unit test for fix | Unit + Regression |
| Refactor | All existing tests | Full test suite |
| Hotfix | Related unit tests | Smoke tests |

### Quick Test Commands

```powershell
# Run only unit tests (fast)
./gradlew test

# Run specific test class
./gradlew test --tests "UserServiceTest"

# Run all tests including integration
./gradlew check

# Run tests with output
./gradlew test --info
```

---

## Commit Frequency

### Commit Often

| Guideline | Description |
|-----------|-------------|
| Small commits | Each commit is ONE logical change |
| Working state | Every commit should compile and pass tests |
| Frequent saves | Commit at least every few hours of work |
| Logical units | Group related changes, split unrelated ones |

### When to Commit

- After completing a small, working piece of functionality
- Before switching context or taking a break
- After fixing a bug (separate from feature work)
- After writing tests for new code
- Before any risky refactoring

### Anti-Patterns

| Anti-Pattern | Problem |
|--------------|---------|
| Giant commits | Hard to review, hard to revert |
| "WIP" commits | Unclear history, may not compile |
| Mixed changes | Bug fix + feature = confusing history |
| End-of-day dumps | No logical grouping |

---

## Workflow: Complete Feature Cycle

### 1. Start Feature

```powershell
git checkout main
git pull origin main
git checkout -b feature/user-preferences
```

### 2. Work and Commit Incrementally

```powershell
# After each logical unit of work
./gradlew test
git add -p                    # Review changes interactively
git commit -m "feat: Add UserPreference entity"

# Continue working...
./gradlew test
git add src/main/java/com/app/service/PreferenceService.java
git commit -m "feat: Implement preference storage service"

# Add tests
./gradlew test
git add src/test/
git commit -m "test: Add unit tests for PreferenceService"
```

### 3. Before Creating PR

```powershell
# Run full test suite
./gradlew check

# Rebase on latest main
git fetch origin
git rebase origin/main

# Resolve any conflicts, then
./gradlew check               # Test again after rebase

# Push
git push origin feature/user-preferences
```

### 4. After PR Merged

```powershell
git checkout main
git pull origin main
git branch -d feature/user-preferences
```

---

## Quick Reference

### Commands

| Task | Command |
|------|---------|
| Create branch | `git checkout -b feature/description` |
| Check branch | `git branch --show-current` |
| Stage selectively | `git add -p` |
| Commit | `git commit -m "type: Message"` |
| View staged | `git diff --staged` |
| Run tests | `./gradlew test` |
| Rebase on main | `git fetch origin && git rebase origin/main` |
| Delete branch | `git branch -d branch-name` |

### Commit Message Template

```
<type>: <50 char max summary in imperative mood>

<Optional body: explain WHY this change is needed, not WHAT changed.
Wrap at 72 characters. Leave blank line after subject.>
```

### Branch Lifecycle

```
main ──┬─────────────────────────────────────► main
       │                                    ▲
       └──► feature/description ──► PR ──► merge
            (commit) (commit) (commit)
```

---

## Summary

| Category | Key Points |
|----------|------------|
| **Branches** | Feature branches from main, one task per branch, delete after merge |
| **Commits** | No emojis, 50 char limit, explain WHY, imperative mood |
| **Testing** | Run tests before every commit, minimum unit tests |
| **Frequency** | Commit often, small logical units, always working state |
| **PRs** | Rebase on main, full test suite, clean up after merge |

---

**Skill Status**: COMPLETE
**Line Count**: < 500