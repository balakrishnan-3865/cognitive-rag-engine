# Commit Message Generation Guidelines

## Format
```
<type>(<scope>): <subject>

<business-context>

<technical-details>

<impact-assessment>
```

## Types (Priority Order)
1. **feat** - New user-facing feature
2. **fix** - User-impacting bug fix
3. **security** - Security patches
4. **perf** - Performance improvements
5. **refactor** - Code improvements (no behavior change)
6. **test** - Test coverage for critical logic
7. **docs** - Documentation
8. **chore** - Maintenance, dependencies, tooling
9. **style** - Formatting, cosmetic (group only)

## ROI-Based Filtering

### INCLUDE (High ROI)
- Features affecting revenue or user experience
- Bug fixes impacting >10% of users
- Security vulnerabilities (any severity)
- Performance improvements with measurable impact
- Database migrations
- Breaking changes
- Test coverage for critical business logic

### EXCLUDE or GROUP (Low ROI)
- Single formatting/linting changes → group as chore
- Individual dependency bumps → group as chore
- Documentation typo fixes → group as docs
- Minor config updates → group as chore
- Debug logging changes → group as chore
- Dead code removal → group as refactor

### Grouping Rule
When 3+ minor changes exist, consolidate into:
```
chore(maintenance): consolidate updates and fixes
```

## Subject Guidelines
- Imperative present tense ("add" not "added")
- No capitalization at start
- No period at end
- Maximum 50 characters
- Focus on outcome, not implementation

## Message Components

### Business Context (2-3 sentences)
Answer: "What business problem does this solve?"
```
Addresses [pain point] by [solution]. Expected impact: [metric].
```

### Technical Details (3-5 bullet points)
```
- [Component] now [does X]
- [Integration] established with [Y]
- [Metric] improved from [A] to [B]
```

### Impact Assessment
```
Impact: [Low/Medium/High/Critical]
- Users affected: [% or count]
- Rollback complexity: [Low/Medium/High]
- Breaking change: [Yes/No]
```

## Examples

### High ROI Feature
```
feat(billing): implement tiered enterprise pricing

Addresses competitive pressure by enabling flexible enterprise pricing.
Expected impact: 15% increase in enterprise conversion.

- Billing engine supports tiered pricing with usage tracking
- Stripe integration enhanced for subscription management
- Admin dashboard updated with pricing controls

Impact: High
- Users affected: Enterprise (5% users, 40% revenue)
- Rollback complexity: High
- Breaking change: No
```

### Performance Fix
```
perf(api): reduce query response time with indexing

Improves API response times to meet SLAs. Identified n+1 query
pattern causing 2.5s latency in orders endpoint.

- Added composite indexes on orders.user_id and created_at
- Optimized JOINs reducing query count from 8 to 2
- Response time reduced from 2.5s to 400ms

Impact: High
- Users affected: All (100%)
- Rollback complexity: Low
- Breaking change: No
```

### Grouped Minor Changes
```
chore(maintenance): apply linting and dependency updates

Batch update for multiple maintenance tasks. Includes:
- ESLint fixes for component files
- Updated dependencies to latest patches
- Removed unused import statements

Impact: Low
- Users affected: None
- Rollback complexity: Low
- Breaking change: No
```

## Decision Flow
1. User-facing? → feat/fix with business context
2. Security/performance? → security/perf with metrics
3. Maintainability? → refactor/test with rationale
4. Multiple minor? → group as single chore
5. Single minor? → skip or group with next commit

## Breaking Changes
Always include:
```
BREAKING CHANGE: [description]
Migration: [steps required]
```

## Critical Rules
- NEVER commit multiple unrelated features together
- ALWAYS include business context for user-facing changes
- SPLIT large changes into logical commits
- GROUP minor/noise changes into single commits
- EXCLUDE trivial changes when possible

## Quality Checklist
- [ ] Subject follows format and <50 chars
- [ ] Business context explains "why"
- [ ] Technical details are specific
- [ ] Impact assessment provided
- [ ] No noise commits
- [ ] Breaking changes marked
- [ ] Metrics included where relevant
```