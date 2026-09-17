# Resume-Driven Multi-Round Interview Design

**Goal:** Make a parsed resume the required product entry point for multi-round interviews, while using a controlled Agent to adapt each round within server-enforced rules.

## Problem

The existing UI asks for an internal `sessionId`, and the multi-round creation API only accepts that ID. A base `InterviewSessionEntity` may have no resume, and the active question generation path only receives a round type and count. Although `InterviewAgentOrchestrator` exists, it is exposed as a manual plan endpoint and does not control question creation or progression.

This does not meet the multi-round TRD: the Agent must receive resume summary, JD, Skill, prior rounds and answers; it may propose structured plans, but the service owns validation, persistence, and state transitions.

## Product Rules

1. A multi-round interview requires one existing, successfully parsed resume. Creating or continuing without `resumeId` is rejected.
2. The user starts from a resume list/detail action. The UI never requests an internal `sessionId`.
3. A resume may have at most one active multi-round interview for the same normalized interview scope: `resumeId`, `skillId`, `difficulty`, and normalized JD content.
4. Starting the same scope restores its active interview. A completed interview is historical; a new attempt requires an explicit `restart` request and gets a new internal session.
5. The stored snapshot is the source of truth during an interview. Later resume edits or re-analysis do not alter already generated questions, decisions, reports, or Agent context.
6. Existing multi-round records whose base session has no resume stay readable as legacy history but cannot be resumed, advanced, or used for Agent planning.

## Domain Model

Keep `interview_sessions` as the common execution and lifecycle anchor. Multi-round is still a one-to-one extension of it, so existing reports, idempotency, provider binding, and audit tables remain connected through the internal session.

Add a new Flyway migration that extends `multi_round_interviews` with the immutable creation input:

| Column | Purpose |
| --- | --- |
| `resume_snapshot` (`TEXT NOT NULL` for new rows) | Sanitized resume text and parsed summary used by the Agent. |
| `resume_snapshot_version` (`VARCHAR(64)`) | Parsed analysis version or deterministic content hash. |
| `jd_snapshot` (`TEXT`) | JD supplied at start, if any. |
| `skill_id` (`VARCHAR(64) NOT NULL`) | Target interview skill. |
| `difficulty` (`VARCHAR(16) NOT NULL`) | Candidate level used for planning. |
| `scope_fingerprint` (`VARCHAR(64)`) | SHA-256 of `resumeId`, normalized JD, skill and difficulty; used to find an active interview. |

Create an index on `(scope_fingerprint, status)` and a partial unique index for active statuses (`CREATED`, `ROUND_READY`, `IN_PROGRESS`, `ROUND_EVALUATING`, `ROUND_COMPLETED`). The migration does not backfill sensitive resume text into legacy records.

Introduce `MultiRoundInterviewBootstrapService`. It verifies the resume and its parsed content, resolves the Provider once, generates the scope fingerprint, then either returns the matching active interview or creates:

```text
Resume + start options
  -> immutable MultiRound snapshot
  -> internal InterviewSession (sourceType=MULTI_ROUND, resume linked)
  -> MultiRoundInterview + default constrained round skeleton
```

This service creates a base session directly and does not call the single-round question generator; `questions_json` remains empty and multi-round questions are stored only in `multi_round_questions`.

## API Design

Replace public multi-round creation by session ID with resume-based endpoints:

```text
POST /api/resumes/{resumeId}/multi-round-interviews
Body: { skillId, difficulty, jdText, restart }
Header: Idempotency-Key
Response: { sessionId, interviewId, status, currentRoundNo, resumed }

GET /api/resumes/{resumeId}/multi-round-interviews/active
Response: same shape, or 404 when no active interview exists
```

Existing detail, report, answer, round, and algorithm endpoints may continue using `sessionId` in internal route parameters after bootstrap. They must assert that the referenced multi-round interview has a bound resume and is not a legacy no-resume record before mutating state.

Remove the public creation route `POST /api/multi-round/interviews/{sessionId}`. During the transition, retain it only as a deprecated endpoint that returns a clear `MULTI_ROUND_RESUME_REQUIRED` error; do not silently create or repair arbitrary sessions.

## Controlled Agent Orchestration

The Agent remains advisory and structured. The server executes all mutations.

At round start, `MultiRoundOperationService` invokes a new orchestration method with an `InterviewPlanningContext` built from the immutable snapshot, current round, prior round reports, previous questions/answers, and configured limits. The Agent returns an `InterviewPlan` containing category allocation, target difficulty, question directives, allowed follow-up count, completion guidance, and learning-signal tags.

The sequence is:

```text
Load snapshot and state
  -> build sanitized planning context
  -> Agent returns InterviewPlan JSON
  -> schema + domain-policy validation
  -> persist audit and accepted plan summary
  -> generate questions from the accepted plan
  -> persist questions in a short transaction
```

The Agent cannot choose an invalid round, write database records, bypass question/follow-up caps, call arbitrary tools, or access another interview. If invocation, parsing, or validation fails, the server records the audit failure and generates deterministic questions based on the resume's skill and current round. The state machine remains authoritative: algorithm first and once, 2-4 total rounds, HR last, contiguous round numbers, and configured per-round limits.

`MultiRoundQuestionGenerationService` receives the validated plan and planning context rather than just `(providerId, round, count)`. The manual `agent-plan` endpoint becomes a read-only diagnostic endpoint behind the same context builder, not the production orchestration path.

## Frontend Flow

1. Resume list/detail displays `Start multi-round interview` or `Continue multi-round interview` based on the active endpoint.
2. The start modal collects target Skill, difficulty, and optional JD. It does not ask for a session ID.
3. Successful bootstrap navigates to `/multi-round?sessionId=<server-issued-id>`. The query parameter is an opaque routing key only.
4. `MultiRoundInterviewPage` removes the session ID input and creation-on-mount behavior. It receives the issued session ID from navigation, loads detail, and renders accepted Agent plan metadata alongside the controlled round status.
5. A direct route without a session ID redirects to the resume list with a concise empty state. A legacy no-resume record shows its historical report but no mutation controls.

## Tests and Acceptance Criteria

Backend tests must prove:

- Bootstrap rejects missing, nonexistent, or unparsed resumes.
- Bootstrap creates a resume-linked internal session and immutable snapshot without invoking single-round question generation.
- Repeating the same active scope resumes the same interview; `restart=true` creates a distinct session after the previous one is completed.
- The public legacy creation route cannot create a multi-round interview from a session without a resume.
- Agent planning context contains the snapshot, JD, skill, prior answers, and only current-interview data.
- Invalid or failed Agent output uses deterministic fallback without breaking state-machine progression.
- Generated questions follow the validated plan and all existing state-machine invariants remain intact.

Frontend tests must prove:

- A user starts or resumes from a resume card/detail action without ever entering a session ID.
- The start request carries resume ID and scope options, then navigates using the server response.
- Refresh restores a server-issued interview route.
- Missing direct-route context returns to resume selection.

End-to-end coverage must start with a parsed resume, create a multi-round interview, verify resume-specific Agent-driven questions, complete a round, and recover the same interview after reload.

## Compatibility and Rollout

The migration is additive and leaves existing rows untouched. Existing APIs for reports remain readable. Legacy no-resume multi-round records are labelled as historical and mutation is blocked. No existing resume, single-round interview, Provider, or Agent audit rows are deleted or rewritten.

The feature is complete when every newly created multi-round interview has a resume-bound immutable snapshot, the Agent plan is part of production question generation, deterministic fallback works, and there is no UI path that asks a user for `sessionId`.
