# Resume-Driven Multi-Round Interview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make parsed resumes mandatory for multi-round interviews and make the controlled Agent's validated plan drive production question generation.

**Architecture:** Preserve `InterviewSessionEntity` as the internal lifecycle anchor and `MultiRoundInterviewEntity` as its one-to-one extension. Add an immutable snapshot and a resume-based bootstrap API; build planning context from that snapshot plus interview history, validate Agent output in the service layer, and pass the accepted plan to question generation. The frontend starts from a resume and treats server-issued session IDs as opaque routing state.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring AI 2, Spring Data JPA, PostgreSQL/Flyway, React 18, TypeScript, Vite, JUnit 5/Mockito, Playwright.

**Spec:** `docs/superpowers/specs/2026-08-18-resume-driven-multi-round-design.md`

## Global Constraints

- Add only new Flyway migrations; do not edit executed `V20260822` through `V20260830` migrations.
- New multi-round interviews require an existing parsed resume; no fallback to an unbound general interview.
- Agent output is advisory JSON only; state mutation remains in transactional domain services.
- Preserve algorithm-first/once, 2-4 rounds, HR-last, contiguous rounds, per-round question cap, max two follow-ups, idempotency, and deterministic fallback.
- Never log or persist API keys, Provider ciphertext/nonce, or a full prompt beyond the permitted snapshot and existing redacted audit summaries.
- Legacy multi-round records with no linked resume remain report-readable but all mutation endpoints reject them.

---

### Task 1: Persist Multi-Round Creation Snapshots

**Files:**
- Create: `app/src/main/resources/db/migration/V20260831__add_multi_round_resume_snapshot.sql`
- Modify: `app/src/main/java/interview/guide/modules/multiround/model/MultiRoundInterviewEntity.java`
- Modify: `app/src/main/java/interview/guide/modules/multiround/repository/MultiRoundInterviewRepository.java`
- Test: `app/src/test/java/interview/guide/modules/multiround/service/MultiRoundInterviewServiceTest.java`

**Interfaces:**
- Produces entity fields `resumeSnapshot`, `resumeSnapshotVersion`, `jdSnapshot`, `skillId`, `difficulty`, and `scopeFingerprint`.
- Produces `findActiveByScopeFingerprint(String fingerprint)` returning the current nonterminal interview if one exists.

- [ ] **Step 1: Write failing repository/service tests for snapshot identity**

```java
@Test
@DisplayName("同一简历范围只恢复进行中的多轮面试")
void shouldFindActiveInterviewByScopeFingerprint() {
    when(interviewRepository.findActiveByScopeFingerprint("sha256"))
        .thenReturn(Optional.of(activeInterview));

    assertThat(service.findActive("sha256")).containsSame(activeInterview);
}
```

- [ ] **Step 2: Run the focused test and verify it fails because the query and fields do not exist**

Run: `JAVA_HOME=/usr/local/opt/openjdk@25 PATH=/usr/local/opt/openjdk@25/bin:$PATH ./gradlew --no-daemon :app:test --tests '*MultiRoundInterviewServiceTest'`

Expected: compilation failure for missing snapshot fields/repository method.

- [ ] **Step 3: Add the additive Flyway migration**

```sql
ALTER TABLE multi_round_interviews
  ADD COLUMN resume_snapshot TEXT,
  ADD COLUMN resume_snapshot_version VARCHAR(64),
  ADD COLUMN jd_snapshot TEXT,
  ADD COLUMN skill_id VARCHAR(64),
  ADD COLUMN difficulty VARCHAR(16),
  ADD COLUMN scope_fingerprint VARCHAR(64);

CREATE INDEX idx_multi_round_interviews_scope_status
  ON multi_round_interviews(scope_fingerprint, status);

CREATE UNIQUE INDEX uq_multi_round_active_scope
  ON multi_round_interviews(scope_fingerprint)
  WHERE status IN ('CREATED', 'ROUND_READY', 'IN_PROGRESS', 'ROUND_EVALUATING', 'ROUND_COMPLETED');
```

Keep columns nullable in this migration so existing history remains valid. New bootstrap validation supplies every required value before persisting.

- [ ] **Step 4: Map fields and query using existing JPA conventions**

```java
@Column(name = "resume_snapshot", columnDefinition = "TEXT")
private String resumeSnapshot;

@Query("""
    select value from MultiRoundInterviewEntity value
    where value.scopeFingerprint = :fingerprint
      and value.status in :activeStatuses
    """)
Optional<MultiRoundInterviewEntity> findActiveByScopeFingerprint(
    @Param("fingerprint") String fingerprint,
    @Param("activeStatuses") Collection<MultiRoundInterviewEntity.Status> activeStatuses);
```

- [ ] **Step 5: Run focused tests and migration-backed application startup**

Run: `JAVA_HOME=/usr/local/opt/openjdk@25 PATH=/usr/local/opt/openjdk@25/bin:$PATH ./gradlew --no-daemon :app:test --tests '*MultiRoundInterviewServiceTest'`

Run: `SERVER_PORT=8081 ./scripts/run-backend-java25.sh`

Expected: focused tests pass and Flyway applies `V20260831` without modifying legacy rows.

### Task 2: Add Resume-Based Bootstrap and Enforce Resume Binding

**Files:**
- Create: `app/src/main/java/interview/guide/modules/multiround/dto/StartMultiRoundInterviewRequest.java`
- Create: `app/src/main/java/interview/guide/modules/multiround/dto/MultiRoundInterviewBootstrapResponse.java`
- Create: `app/src/main/java/interview/guide/modules/multiround/service/MultiRoundInterviewBootstrapService.java`
- Modify: `app/src/main/java/interview/guide/modules/multiround/MultiRoundController.java`
- Modify: `app/src/main/java/interview/guide/modules/multiround/service/MultiRoundInterviewService.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/model/InterviewSessionEntity.java`
- Modify: `app/src/main/java/interview/guide/modules/interview/repository/InterviewSessionRepository.java`
- Test: `app/src/test/java/interview/guide/modules/multiround/service/MultiRoundInterviewBootstrapServiceTest.java`
- Test: `app/src/test/java/interview/guide/modules/multiround/service/MultiRoundInterviewServiceTest.java`

**Interfaces:**
- Consumes `POST /api/resumes/{resumeId}/multi-round-interviews` with `Idempotency-Key` and `{ skillId, difficulty, jdText, restart }`.
- Produces `{ sessionId, interviewId, status, currentRoundNo, resumed }`.
- Produces `GET /api/resumes/{resumeId}/multi-round-interviews/active`.

- [ ] **Step 1: Write bootstrap tests before implementation**

```java
@Test
void shouldRejectResumeWithoutCompletedAnalysis() {
    when(resumeRepository.findById(9L)).thenReturn(Optional.of(resumeWithPendingAnalysis()));

    assertThatThrownBy(() -> service.start(9L, request(), "create-9"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("简历尚未解析完成");
}

@Test
void shouldCreateResumeBoundSessionAndSnapshotWithoutSingleRoundQuestions() {
    var response = service.start(9L, request(), "create-9");

    verify(interviewSessionRepository).save(argThat(session ->
        session.getResumeId().equals(9L) && "MULTI_ROUND".equals(session.getSourceType())));
    verify(singleRoundQuestionService, never()).generateQuestionsBySkill(any(), any(), any(), any(), anyInt(), any(), any(), any());
    assertThat(response.resumed()).isFalse();
}
```

- [ ] **Step 2: Run the bootstrap test and verify it fails because the service/API contract is absent**

Run: `JAVA_HOME=/usr/local/opt/openjdk@25 PATH=/usr/local/opt/openjdk@25/bin:$PATH ./gradlew --no-daemon :app:test --tests '*MultiRoundInterviewBootstrapServiceTest'`

Expected: compilation failure for missing request, response, and bootstrap service.

- [ ] **Step 3: Implement the request and bootstrap service**

```java
public record StartMultiRoundInterviewRequest(
    @NotBlank String skillId,
    @Pattern(regexp = "junior|mid|senior") String difficulty,
    String jdText,
    boolean restart
) {}

public record MultiRoundInterviewBootstrapResponse(
    String sessionId, Long interviewId, String status, int currentRoundNo, boolean resumed
) {}
```

`MultiRoundInterviewBootstrapService.start` must load the resume with its completed analysis, normalize JD whitespace, calculate the SHA-256 scope fingerprint, return an existing active scope unless `restart=true`, and create a new linked `InterviewSessionEntity` directly with `sourceType="MULTI_ROUND"`, zero single-round questions, and a generated public session ID. It then delegates creation of the extension and four constrained round rows to `MultiRoundInterviewService`.

- [ ] **Step 4: Expose resume-based endpoints and retire session-ID creation**

```java
@PostMapping("/api/resumes/{resumeId}/multi-round-interviews")
Result<MultiRoundInterviewBootstrapResponse> start(
    @PathVariable Long resumeId,
    @RequestHeader("Idempotency-Key") String idempotencyKey,
    @Valid @RequestBody StartMultiRoundInterviewRequest request);

@GetMapping("/api/resumes/{resumeId}/multi-round-interviews/active")
Result<MultiRoundInterviewBootstrapResponse> active(@PathVariable Long resumeId);
```

Change the former `POST /api/multi-round/interviews/{sessionId}` implementation to return `MULTI_ROUND_RESUME_REQUIRED`. Every multi-round mutation loads the base session with its resume and rejects null `resumeId` before continuing.

- [ ] **Step 5: Run bootstrap, existing multi-round, and controller tests**

Run: `JAVA_HOME=/usr/local/opt/openjdk@25 PATH=/usr/local/opt/openjdk@25/bin:$PATH ./gradlew --no-daemon :app:test --tests '*MultiRoundInterviewBootstrapServiceTest' --tests '*MultiRoundInterviewServiceTest' --tests '*MultiRoundOperationServiceTest'`

Expected: resume validation, active-scope recovery, restart behavior, and legacy mutation rejection pass.

### Task 3: Make the Agent Plan a Production Input

**Files:**
- Create: `app/src/main/java/interview/guide/common/ai/agent/InterviewPlanningContext.java`
- Create: `app/src/main/java/interview/guide/modules/multiround/service/MultiRoundPlanningContextFactory.java`
- Modify: `app/src/main/java/interview/guide/common/ai/agent/InterviewAgentOrchestrator.java`
- Modify: `app/src/main/java/interview/guide/modules/multiround/service/MultiRoundOperationService.java`
- Modify: `app/src/main/java/interview/guide/modules/multiround/service/MultiRoundQuestionGenerationService.java`
- Modify: `app/src/main/java/interview/guide/modules/multiround/model/MultiRoundQuestionEntity.java`
- Test: `app/src/test/java/interview/guide/common/ai/agent/InterviewAgentOrchestratorTest.java`
- Test: `app/src/test/java/interview/guide/modules/multiround/service/MultiRoundPlanningContextFactoryTest.java`
- Test: `app/src/test/java/interview/guide/modules/multiround/service/MultiRoundQuestionGenerationServiceTest.java`

**Interfaces:**
- Produces a bounded `InterviewPlanningContext` containing only immutable snapshot, active round, prior report summaries, prior questions/answers, and configured limits.
- `InterviewAgentOrchestrator.plan(InterviewPlanningContext context, String trigger)` returns validated `InterviewPlan` or a deterministic fallback plan.
- `ensureQuestionsGenerated(MultiRoundEntity round, InterviewPlan plan, InterviewPlanningContext context)` only creates questions conforming to the accepted plan.

- [ ] **Step 1: Write failing context isolation and generation tests**

```java
@Test
void shouldBuildPlanningContextFromSnapshotAndPriorAnswersOnly() {
    InterviewPlanningContext context = factory.create(interview, currentRound);

    assertThat(context.resumeSnapshot()).contains("Candidate project");
    assertThat(context.jdSnapshot()).contains("Java");
    assertThat(context.previousAnswers()).containsExactly("answer from round one");
    assertThat(context).doesNotHaveToStringContaining("other interview");
}

@Test
void shouldGenerateQuestionsUsingAcceptedPlanCategories() {
    InterviewPlan plan = new InterviewPlan(2, "TECHNICAL_1", 1, List.of("distributed-cache"), 2);

    service.ensureQuestionsGenerated(round, plan, context);

    verify(questionRepository).save(argThat(question ->
        "distributed-cache".equals(question.getCategoryKey())));
}
```

- [ ] **Step 2: Run tests and verify they fail on the former round-only signature**

Run: `JAVA_HOME=/usr/local/opt/openjdk@25 PATH=/usr/local/opt/openjdk@25/bin:$PATH ./gradlew --no-daemon :app:test --tests '*InterviewAgentOrchestratorTest' --tests '*MultiRoundQuestionGenerationServiceTest' --tests '*MultiRoundPlanningContextFactoryTest'`

Expected: compilation failure for missing context factory and overloaded generation method.

- [ ] **Step 3: Build the bounded planning context and accept only valid plans**

```java
public record InterviewPlanningContext(
    String resumeSnapshot, String jdSnapshot, String skillId, String difficulty,
    int roundNo, String roundType, List<String> priorQuestionSummaries,
    List<String> previousAnswers, int questionLimit, int followUpLimit
) {}
```

The factory reads the immutable `MultiRoundInterviewEntity` snapshot, filters questions to this interview only, truncates fields through `AgentBudget`, and never reads another session. Update the orchestrator prompt to serialize this context under `PromptSanitizer` delimiters. Keep `AgentPlanValidator` as the hard gate and preserve audit records for successful, rejected, and fallback plans.

- [ ] **Step 4: Wire planning into the state transition before generation**

In `MultiRoundOperationService.startRound`, load the context, ask the Agent, validate/fallback, persist a compact plan summary in the existing Agent run audit, then invoke question generation with the accepted plan. Do not let the Agent write entities or decide a state transition directly.

Update the question-generation system prompt to require categories and difficulty from the accepted plan. Persist only generated questions whose category is in the validated plan. The deterministic fallback must derive category and wording from the resume skill plus round type, not generic `#n` prompts.

- [ ] **Step 5: Run the focused Agent and multi-round test set**

Run: `JAVA_HOME=/usr/local/opt/openjdk@25 PATH=/usr/local/opt/openjdk@25/bin:$PATH ./gradlew --no-daemon :app:test --tests '*InterviewAgentOrchestratorTest' --tests '*AgentPlanValidatorTest' --tests '*MultiRoundOperationServiceTest' --tests '*MultiRoundQuestionGenerationServiceTest' --tests '*MultiRoundLifecycleServiceTest'`

Expected: valid Agent plans shape generated questions; malformed/failed plans retain state-machine validity through deterministic fallback.

### Task 4: Replace the Session-ID Frontend Entry with Resume Actions

**Files:**
- Modify: `frontend/src/api/multiRound.ts`
- Modify: `frontend/src/pages/ResumeDetailPage.tsx`
- Modify: `frontend/src/pages/HistoryPage.tsx`
- Modify: `frontend/src/pages/MultiRoundInterviewPage.tsx`
- Modify: `frontend/src/constants/routes.ts`
- Modify: `frontend/src/App.tsx`
- Create: `frontend/src/pages/multiRoundResumeEntry.test.ts`
- Test: `frontend/e2e/multi-round.spec.ts`

**Interfaces:**
- `multiRoundApi.startForResume(resumeId, request, idempotencyKey)` and `multiRoundApi.getActiveForResume(resumeId)`.
- Resume UI navigates to `/multi-round?sessionId=<response.sessionId>` only after server bootstrap.

- [ ] **Step 1: Write failing frontend unit tests for resume-only entry**

```ts
test('starts multi-round interview from a resume without asking for session ID', async () => {
  render(<ResumeDetailPage resumeId={7} {...props} />)
  await user.click(await screen.findByRole('button', { name: '开始多轮面试' }))
  await user.click(screen.getByRole('button', { name: '确认开始' }))

  expect(multiRoundApi.startForResume).toHaveBeenCalledWith(7, expect.objectContaining({ skillId: 'java-backend' }), expect.any(String))
  expect(navigate).toHaveBeenCalledWith('/multi-round?sessionId=server-session')
})
```

- [ ] **Step 2: Run the unit test and verify the current UI still exposes the session-ID field**

Run: `cd frontend && pnpm exec tsx --test src/pages/multiRoundResumeEntry.test.ts`

Expected: failing assertion because the API/action does not exist and `MultiRoundInterviewPage` renders an input for `sessionId`.

- [ ] **Step 3: Update API types and resume detail/list actions**

```ts
export interface StartMultiRoundInterviewRequest {
  skillId: string
  difficulty: 'junior' | 'mid' | 'senior'
  jdText?: string
  restart?: boolean
}

export const multiRoundApi = {
  startForResume: (resumeId, body, key) =>
    request.post<MultiRoundBootstrapResponse>(`/api/resumes/${resumeId}/multi-round-interviews`, body, withIdempotencyKey(key)),
  getActiveForResume: (resumeId) => request.get<MultiRoundBootstrapResponse>(`/api/resumes/${resumeId}/multi-round-interviews/active`),
}
```

Add a compact start/resume control in `ResumeDetailPage` and the resume list. Disable it while `analyzeStatus` is `PENDING`, `PROCESSING`, or `FAILED`; successful bootstrap navigates using the response's opaque session ID.

- [ ] **Step 4: Simplify the multi-round page route behavior**

Remove `sessionIdInput`, `startSession`, and the creation-on-mount request. With `sessionId` present, load existing detail/report/trace only. Without it, redirect to the resume list rather than rendering a manual ID input. Keep refresh support because the issued ID remains in the URL.

- [ ] **Step 5: Run frontend unit, production build, and end-to-end flow**

Run: `cd frontend && pnpm exec tsx --test src/pages/multiRoundResumeEntry.test.ts`

Run: `cd frontend && pnpm run build`

Run: `cd frontend && pnpm run test:e2e -- --grep 'resume-driven multi-round'`

Expected: a parsed resume starts/resumes the interview; a direct no-context route redirects; production TypeScript/Vite build passes.

### Task 5: Validate Legacy Handling and Full Regression

**Files:**
- Modify: `app/src/main/java/interview/guide/modules/multiround/MultiRoundController.java`
- Modify: `app/src/main/java/interview/guide/modules/multiround/service/MultiRoundOperationService.java`
- Modify: `frontend/src/pages/MultiRoundInterviewPage.tsx`
- Test: `app/src/test/java/interview/guide/modules/multiround/service/MultiRoundOperationServiceTest.java`
- Test: `frontend/e2e/multi-round.spec.ts`

**Interfaces:**
- Legacy interview read endpoints continue to return reports.
- Any attempt to start, answer, advance, or generate Agent plans for a legacy no-resume interview returns `MULTI_ROUND_RESUME_REQUIRED`.

- [ ] **Step 1: Write failing legacy mutation tests**

```java
@Test
void shouldRejectMutationForLegacyInterviewWithoutResume() {
    when(sessionRepository.findBySessionIdWithResume("legacy")).thenReturn(Optional.of(sessionWithoutResume()));

    assertThatThrownBy(() -> operationService.nextRound("legacy", "key"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("必须绑定简历");
}
```

- [ ] **Step 2: Implement one shared guard and apply it to every mutation**

```java
private InterviewSessionEntity requireResumeBoundSession(String sessionId) {
    InterviewSessionEntity session = sessionRepository.findBySessionIdWithResume(sessionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "面试会话不存在: " + sessionId));
    if (session.getResumeId() == null) {
        throw new BusinessException(ErrorCode.MULTI_ROUND_RESUME_REQUIRED, "多轮面试必须绑定简历");
    }
    return session;
}
```

Use it before start, answer, finish, next-round, algorithm submission, and Agent plan operations. Keep GET detail/report readable; the UI renders read-only legacy history when the backend response identifies it.

- [ ] **Step 3: Run the full verification suite**

Run: `JAVA_HOME=/usr/local/opt/openjdk@25 PATH=/usr/local/opt/openjdk@25/bin:$PATH ./gradlew --no-daemon :app:test`

Run: `cd frontend && pnpm run build`

Run: `cd frontend && pnpm run test:e2e`

Expected: all backend tests, frontend build, and end-to-end tests pass. Manually verify: upload/parse a resume, start multi-round from its detail, inspect Agent audit, complete a round, reload, and confirm the same interview resumes.

## Plan Review

- Spec coverage: mandatory resume, immutable snapshot, controlled Agent production integration, deterministic fallback, internal-only session ID, legacy read-only behavior, APIs, and tests each have an explicit task.
- Placeholder scan: no deferred implementation markers or unspecified test commands remain.
- Type consistency: `StartMultiRoundInterviewRequest`, `MultiRoundInterviewBootstrapResponse`, and `InterviewPlanningContext` are defined before their consuming tasks.
