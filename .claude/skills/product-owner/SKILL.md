---
name: product-owner
description: Product ownership patterns for defining features, writing user stories, creating acceptance criteria, and breaking work into testable tasks. Use when planning new features, writing requirements, organizing feature documentation, splitting epics into stories, or defining done criteria. Covers user story format, acceptance criteria patterns, task breakdown strategies, and requirements documentation.
---

# Product Owner Skill

## Purpose

Guide for defining features clearly, writing effective user stories, creating testable acceptance criteria, and organizing requirements documentation. Ensures work is properly scoped, actionable, and verifiable.

## When to Use

Activate when:
- Planning a new feature or epic
- Writing user stories or requirements
- Breaking down large tasks into smaller pieces
- Defining acceptance criteria
- Creating feature documentation
- Organizing system documentation
- Preparing work for development

---

## User Story Format

### Standard Template

```markdown
**As a** [type of user]
**I want** [goal/desire]
**So that** [benefit/value]
```

### Example

```markdown
**As a** registered user
**I want** to export my dashboard data as PDF
**So that** I can share reports with stakeholders offline
```

### Guidelines

- Focus on user value, not implementation
- One story = one deliverable piece of value
- Keep stories independent when possible
- Stories should be estimable and testable

---

## Acceptance Criteria Patterns

### Given-When-Then Format

```markdown
**Given** [precondition/context]
**When** [action/trigger]
**Then** [expected outcome]
```

### Example

```markdown
**Given** I am logged in and on the dashboard page
**When** I click the "Export PDF" button
**Then** a PDF file downloads containing my current dashboard data

**Given** I have no data in my dashboard
**When** I click the "Export PDF" button
**Then** I see a message "No data available to export"
```

### Checklist Format (Alternative)

```markdown
- [ ] PDF contains all visible dashboard widgets
- [ ] PDF includes current date in header
- [ ] PDF filename follows pattern: dashboard-{date}.pdf
- [ ] Export completes within 5 seconds
- [ ] Error message shown if export fails
```

---

## Task Breakdown Strategy

### INVEST Criteria

Good stories are:
- **I**ndependent - Can be developed separately
- **N**egotiable - Details can be discussed
- **V**aluable - Delivers user/business value
- **E**stimable - Team can size it
- **S**mall - Completable in one sprint
- **T**estable - Clear pass/fail criteria

### Splitting Techniques

**By Workflow Steps:**
```
Epic: User Registration
├── Story: User can enter registration details
├── Story: System validates email uniqueness
├── Story: User receives confirmation email
└── Story: User can activate account via link
```

**By Data Variations:**
```
Epic: Export Reports
├── Story: Export as PDF
├── Story: Export as CSV
└── Story: Export as Excel
```

**By User Roles:**
```
Epic: View Analytics
├── Story: Admin views all user analytics
├── Story: Manager views team analytics
└── Story: User views personal analytics
```

**By Operations (CRUD):**
```
Epic: Manage Products
├── Story: Create new product
├── Story: View product details
├── Story: Update product information
└── Story: Delete/archive product
```

---

## Feature Documentation Structure

### Recommended Layout

```
/docs/features/
├── feature-name/
│   ├── README.md           # Overview and status
│   ├── requirements.md     # User stories and acceptance criteria
│   ├── technical-notes.md  # Implementation considerations
│   └── decisions.md        # Key decisions and rationale
```

### Feature README Template

```markdown
# Feature: [Name]

## Status
[Draft | In Review | Approved | In Development | Complete]

## Overview
Brief description of the feature and its purpose.

## User Value
Why this feature matters to users.

## Scope
### In Scope
- Item 1
- Item 2

### Out of Scope
- Item 1
- Item 2

## Dependencies
- Dependency 1
- Dependency 2

## Related Documents
- [Requirements](./requirements.md)
- [Technical Notes](./technical-notes.md)
```

---

## Requirements Document Template

```markdown
# [Feature Name] Requirements

## Epic
[High-level description]

## User Stories

### Story 1: [Title]
**As a** [user type]
**I want** [goal]
**So that** [benefit]

#### Acceptance Criteria
- [ ] Criterion 1
- [ ] Criterion 2
- [ ] Criterion 3

#### Technical Notes
[Any implementation considerations]

---

### Story 2: [Title]
[...]
```

---

## Definition of Done

### Story Level
- [ ] All acceptance criteria met
- [ ] Code reviewed and approved
- [ ] Unit tests written and passing
- [ ] Integration tests passing
- [ ] Documentation updated
- [ ] No critical bugs

### Feature Level
- [ ] All stories complete
- [ ] End-to-end testing complete
- [ ] Performance requirements met
- [ ] Security review passed
- [ ] Deployed to staging
- [ ] Product owner sign-off

---

## Quick Reference

### Story Sizing Guide

| Size | Description | Typical Duration |
|------|-------------|------------------|
| XS | Trivial change, obvious solution | Hours |
| S | Simple, well-understood | 1-2 days |
| M | Some complexity, may need research | 3-5 days |
| L | Complex, multiple components | 1-2 weeks |
| XL | Too large - needs splitting | Split required |

### Red Flags (Story Needs Splitting)

- Contains "and" in the title
- Multiple acceptance criteria groups
- Touches more than 3 system areas
- Cannot be demoed independently
- Team cannot estimate confidently

---

## Integration with Other Tools

**Use with product-owner agent for:**
- Creating complete feature documentation
- Breaking down epics into stories
- Generating acceptance criteria
- Organizing documentation structure

**Workflow:**
1. Use this skill for patterns and templates
2. Use product-owner agent for autonomous documentation creation
3. Use plan-reviewer agent to validate technical approach
4. Use documentation-architect for post-implementation docs
