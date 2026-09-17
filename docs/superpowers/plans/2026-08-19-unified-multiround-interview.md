# Unified Multi-Round Interview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make multi-round interview a first-class interview type with unified launch/history entry points and a three-question, tabbed Medium algorithm round.

**Architecture:** Retain `interview_sessions` as the shared record base and `modules.multiround` as the owner of rounds, questions, submissions, and reports. Add narrowly scoped APIs for multi-round list and exact-scope resume recovery. Enforce algorithm invariants in the service layer, then render one question/editor at a time by selected question id in React.

**Tech Stack:** Spring Boot 4.1, Java 25, JPA/Flyway, React, TypeScript, Vite, Vitest, CodeMirror.

**Spec:** `docs/superpowers/specs/2026-08-19-unified-multiround-interview-design.md`

## Global Constraints

- Multi-round interview remains resume-bound; no generic multi-round creation route is enabled.
- Algorithm execution remains AI static review only; do not add compilation or sandbox execution.
- Algorithm rounds contain exactly three `ALGORITHM` Medium questions.
- Google ADK remains a bounded planning runtime; domain state and persistence remain Spring-owned.
- Do not regress text, voice, or knowledge-base interview creation and history routes.

---

### Task 1: Multi-Round Listing and Exact-Scope Recovery

**Files:**
- Modify: `app/src/main/java/interview/guide/modules/multiround/service/MultiRoundInterviewBootstrapService.java`
- Modify: `app/src/main/java/interview/guide/modules/multiround/ResumeMultiRoundController.java`
- Create: `app/src/main/java/interview/guide/modules/multiround/dto/MultiRoundInterviewListItem.java`
- Modify: `app/src/main/java/interview/guide/modules/multiround/repository/MultiRoundInterviewRepository.java`
- Test: `app/src/test/java/interview/guide/modules/multiround/service/MultiRoundInterviewBootstrapServiceTest.java`
- Test: `app/src/test/java/interview/guide/modules/multiround/ResumeMultiRoundControllerTest.java`

**Interfaces:**
- Produces `findActive(resumeId, skillId, difficulty, jdText)` returning only the matching active scope.
- Produces `listForHistory()` returning session id, skill, difficulty, status, current round, total rounds, resume id, and created time.

- [ ] **Step 1: Add failing service tests for exact scope matching and history DTO projection.**

```java
@Test
void findsOnlyActiveInterviewWithMatchingResumeSkillDifficultyAndJd() {
  assertThat(service.findActive(7L, "java-backend", "mid", "backend jd")).contains(expected);
  assertThat(service.findActive(7L, "algorithm", "mid", "backend jd")).isEmpty();
}

@Test
void listsMultiRoundRecordsWithRoundProgress() {
  assertThat(service.listForHistory()).extracting(MultiRoundInterviewListItem::sessionId)
      .containsExactly("mr-session");
}
```

- [ ] **Step 2: Run the new tests and confirm they fail because the exact-scope API and list DTO do not exist.**

Run: `JAVA_HOME=/usr/local/opt/openjdk@25 PATH=/usr/local/opt/openjdk@25/bin:$PATH ./gradlew --no-daemon :app:test --tests '*MultiRoundInterviewBootstrapServiceTest' --tests '*ResumeMultiRoundControllerTest'`

- [ ] **Step 3: Implement repository queries, DTO mapping, and controller endpoints.**

```java
public Optional<MultiRoundInterviewBootstrapResponse> findActive(
    Long resumeId, String skillId, String difficulty, String jdText) {
  return interviewRepository.findFirstByScopeFingerprintAndStatusIn(
      fingerprint(resumeId, skillId, difficulty, jdText), ACTIVE_STATUSES).map(this::response);
}
```

- [ ] **Step 4: Re-run the targeted backend tests.**

- [ ] **Step 5: Mark this task complete after `git diff --check` is clean.**

### Task 2: Enforce Algorithm Question and Completion Invariants

**Files:**
- Modify: `app/src/main/java/interview/guide/modules/multiround/service/MultiRoundInterviewService.java`
- Modify: `app/src/main/java/interview/guide/modules/multiround/service/MultiRoundQuestionGenerationService.java`
- Modify: `app/src/main/java/interview/guide/modules/multiround/service/MultiRoundLifecycleService.java`
- Modify: `app/src/main/java/interview/guide/modules/multiround/service/MultiRoundOperationService.java`
- Modify: `app/src/main/java/interview/guide/modules/multiround/service/AlgorithmSubmissionReviewService.java`
- Modify/Create: Flyway migration under `app/src/main/resources/db/migration/`
- Test: `app/src/test/java/interview/guide/modules/multiround/service/MultiRoundQuestionGenerationServiceTest.java`
- Test: `app/src/test/java/interview/guide/modules/multiround/service/MultiRoundLifecycleServiceTest.java`
- Test: `app/src/test/java/interview/guide/modules/multiround/service/MultiRoundOperationServiceTest.java`

**Interfaces:**
- Produces three persisted algorithm questions with `questionType=ALGORITHM` and `difficulty=medium`.
- `submitAlgorithm(questionId, ...)` transitions only its own in-progress algorithm question to `ANSWERED`.
- `reviewAlgorithm(submissionId)` transitions its question to `EVALUATED` after a successful review.

- [ ] **Step 1: Add failing invariant tests.**

```java
@Test
void algorithmRoundGeneratesExactlyThreeMediumAlgorithmQuestions() {
  assertThat(questions).hasSize(3).allSatisfy(question -> {
    assertThat(question.getQuestionType()).isEqualTo("ALGORITHM");
    assertThat(question.getDifficulty()).isEqualTo("medium");
  });
}

@Test
void algorithmRoundCannotFinishUntilAllQuestionsAreEvaluated() {
  assertThatThrownBy(() -> lifecycle.finish(roundId)).isInstanceOf(BusinessException.class);
}
```

- [ ] **Step 2: Run these tests and confirm the current generic generator/status rules fail them.**

- [ ] **Step 3: Add question difficulty persistence and implement the algorithm-only generation policy.**

```java
if (round.getRoundType() == MultiRoundEntity.Type.ALGORITHM) {
  count = 3;
  entity.setQuestionType("ALGORITHM");
  entity.setDifficulty("medium");
}
```

Use a constrained generated-question DTO for title/statement/input-output/constraints/examples/reference answer. Add three complete deterministic Medium fallback questions with distinct category keys.

- [ ] **Step 4: Advance question state on submission and successful review; reject non-algorithm and non-active-round submissions.**

- [ ] **Step 5: Re-run algorithm service tests and verify text/technical round tests remain green.**

### Task 3: First-Class Multi-Round Selection and History Presentation

**Files:**
- Modify: `frontend/src/hooks/useInterviewConfig.ts`
- Modify: `frontend/src/pages/InterviewHubPage.tsx`
- Modify: `frontend/src/pages/InterviewHistoryPage.tsx`
- Modify: `frontend/src/api/multiRound.ts`
- Create: `frontend/src/pages/multiRoundHistory.ts`
- Test: `frontend/src/pages/interviewEntry.test.ts`
- Create: `frontend/src/pages/multiRoundHistory.test.ts`

**Interfaces:**
- Extends `InterviewMode` with `'multi_round'`.
- Produces `multiRoundApi.findActiveForScope(...)` and `multiRoundApi.listHistory()`.
- History view uses `UnifiedInterviewItem.type === 'multi_round'` and navigates to `ROUTES.multiRound`.

- [ ] **Step 1: Add failing frontend tests for required resume selection and history navigation.**

```ts
it('requires a completed resume before starting multi-round interview', async () => {
  await user.click(screen.getByRole('button', {name: '多轮面试'}));
  expect(screen.getByText('请选择已完成解析的简历')).toBeInTheDocument();
});

it('opens multi-round history items at their session route', () => {
  expect(resolveInterviewTarget(multiRoundItem)).toBe('/multi-round?sessionId=mr-1');
});
```

- [ ] **Step 2: Run Vitest for the new tests and confirm missing mode/mapping failures.**

- [ ] **Step 3: Add the mode card, enforce the resume prerequisite, and start/recover through the exact-scope API.**

- [ ] **Step 4: Merge multi-round items into recent/history views with a distinct label, progress, and continue/view operation.**

- [ ] **Step 5: Run affected Vitest files and TypeScript compilation.**

### Task 4: Tabbed Per-Question Algorithm Workspace

**Files:**
- Modify: `frontend/src/pages/MultiRoundInterviewPage.tsx`
- Modify: `frontend/src/components/multiround/AlgorithmEditor.tsx`
- Create: `frontend/src/components/multiround/algorithmWorkspace.ts`
- Create: `frontend/src/components/multiround/algorithmWorkspace.test.ts`
- Modify: `frontend/src/api/multiRound.ts`

**Interfaces:**
- Produces `selectAlgorithmQuestion(questions, selectedId)` that always selects a valid algorithm question.
- `AlgorithmEditor` receives `questionId`, persisted submission state, and an `onSubmitted` callback.

- [ ] **Step 1: Add failing selection/state tests.**

```ts
it('defaults the selected algorithm question to the first pending question', () => {
  expect(selectAlgorithmQuestion(questions, null)).toBe(11);
});

it('retains isolated editor drafts when switching question tabs', () => {
  const state = saveDraft(initialState, 11, 'class A {}');
  expect(selectDraft(state, 12)).toBe('');
  expect(selectDraft(state, 11)).toBe('class A {}');
});
```

- [ ] **Step 2: Run the tests and confirm the one-editor/`questions[0]` behavior cannot satisfy them.**

- [ ] **Step 3: Build stable question tabs and render only the selected question statement and editor.**

```tsx
{algorithmQuestions.map(question => (
  <button aria-selected={question.id === selectedQuestionId}
      onClick={() => setSelectedQuestionId(question.id)}>
    第 {question.sequenceNo} 题
  </button>
))}
<AlgorithmEditor key={selectedQuestion.id} questionId={selectedQuestion.id} />
```

- [ ] **Step 4: Load each selected question's latest submission and report; update its tab status after submit/review. Replace nested round action buttons with sibling controls.**

- [ ] **Step 5: Run component tests, `tsc`, and Vite production build.**

### Task 5: Cross-Flow Regression and Runtime Verification

**Files:**
- Modify: `docs/superpowers/specs/2026-08-19-unified-multiround-interview-design.md` only if implementation exposes a required design correction.
- Test: existing backend multi-round suites and existing frontend interview entry/history tests.

- [ ] **Step 1: Run all multi-round backend tests.**

Run: `JAVA_HOME=/usr/local/opt/openjdk@25 PATH=/usr/local/opt/openjdk@25/bin:$PATH ./gradlew --no-daemon :app:test --tests 'interview.guide.modules.multiround.*' --tests 'interview.guide.common.ai.agent.*' --tests 'interview.guide.common.ai.adk.*'`

- [ ] **Step 2: Run frontend type-check, production build, and relevant Vitest tests.**

Run: `cd frontend && ./node_modules/.bin/tsc && ./node_modules/.bin/vite build`

- [ ] **Step 3: Start backend on 8081 and frontend on an unused port, then manually verify one text, one voice, and one resume-bound multi-round launch path.**

- [ ] **Step 4: Inspect `git diff --check` and compare current implementation against every acceptance item in the design.**

- [ ] **Step 5: Report verified results and remaining external limitations without claiming unverified behavior.**
