# Stability Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Harden the existing Android MVP against题库损坏、旧题库 key 残留、AI 过期回调和停止后残留调度任务。

**Architecture:** Keep existing state machines and APIs. Add small pure helpers where Android-dependent classes are difficult to unit test, then wire them into `AnswerStore`, `AiHook`, `WaitForScheduler`, and service cleanup.

**Tech Stack:** Kotlin, Android SDK 34, JUnit 4, Gradle 8.7.

## Global Constraints

- 不改变现有题库 JSON schema。
- 不改变 AI 服务商、HTTP 协议或用户界面。
- 不迁移个人发布签名配置。
- 不实现需要真机节点样本的新题型。

---

### Task 1: 题库 key 归一化回归

**Files:**
- Modify: `app/src/main/java/com/quizhelper/dumptool/AnswerStore.kt`
- Test: `app/src/test/java/com/quizhelper/dumptool/AnswerStoreKeyTest.kt`

- [ ] **Step 1: Add a focused pure key helper and failing tests**

Expose an `internal` helper that returns normalized old/new keys for editing. Test that punctuation and whitespace in `oldQuestion` are normalized before removal, and that a blank new question keeps the normalized old key.

Run: `gradlew.bat :app:testDebugUnitTest --tests '*AnswerStoreKeyTest' --console=plain`
Expected: FAIL before the helper/behavior exists.

- [ ] **Step 2: Implement the minimal normalization fix**

Use `normalizedKey(oldQuestion)` for removal and compare normalized keys, while preserving the existing public `editEntry` signature and JSON schema.

- [ ] **Step 3: Run the focused test**

Expected: all `AnswerStoreKeyTest` tests pass.

### Task 2: 题库原子写入

**Files:**
- Modify: `app/src/main/java/com/quizhelper/dumptool/AnswerStore.kt`
- Test: `app/src/test/java/com/quizhelper/dumptool/AtomicFileWriterTest.kt`

- [ ] **Step 1: Add failing tests for successful replacement and failed write preservation**

Create a JVM-safe writer helper accepting a target `File` and a write callback. Test that successful writes replace the target and failed callbacks leave the previous contents intact.

Run: `gradlew.bat :app:testDebugUnitTest --tests '*AtomicFileWriterTest' --console=plain`
Expected: FAIL before the helper exists.

- [ ] **Step 2: Implement atomic replacement**

Write to a sibling temporary file, close it, then replace the target using a backup/rename strategy available on the project JVM. Clean up only the temporary file created by this operation.

- [ ] **Step 3: Wire `persist` and `mutateAnswers` to the helper**

Keep current logging and error semantics, but ensure a failed serialization/write cannot truncate the existing JSON file.

- [ ] **Step 4: Run focused and existing tests**

Expected: new tests and all existing tests pass.

### Task 3: AI request generation guard

**Files:**
- Modify: `app/src/main/java/com/quizhelper/dumptool/AiHook.kt`
- Create/Modify: `app/src/main/java/com/quizhelper/dumptool/AsyncAnswerGuard.kt`
- Test: `app/src/test/java/com/quizhelper/dumptool/AsyncAnswerGuardTest.kt`

- [ ] **Step 1: Add failing pure guard tests**

Test that a callback is accepted for the current key and generation, rejected for an old generation, and rejected after invalidation.

- [ ] **Step 2: Implement the guard**

Use a monotonically increasing generation per normalized question key. The guard must provide `begin(key)`, `isCurrent(key, generation)`, and `invalidateAll()` semantics.

- [ ] **Step 3: Integrate with `AiHook`**

Capture the generation when submitting the request. Before posting `onLate`, verify the generation is still current. Preserve cache and in-flight deduplication.

- [ ] **Step 4: Run focused tests**

Expected: guard tests pass and existing codec/parser/matching tests remain green.

### Task 4: Scheduler cancellation and lifecycle wiring

**Files:**
- Modify: `app/src/main/java/com/quizhelper/dumptool/WaitForScheduler.kt`
- Modify: `app/src/main/java/com/quizhelper/dumptool/DumpAccessibilityService.kt`
- Test: `app/src/test/java/com/quizhelper/dumptool/WaitForSchedulerTest.kt` if a JVM-safe scheduling seam is extracted

- [ ] **Step 1: Add a cancellation seam/test**

Expose a small testable callback holder or scheduler abstraction. Verify cancellation prevents queued work from invoking `onStep`.

- [ ] **Step 2: Add `cancel()`**

Remove queued scheduler callbacks and guard future callbacks with the existing `active()` predicate.

- [ ] **Step 3: Call cancellation during service cleanup and reset**

Ensure stopping, pausing, service unbind, and destroy cannot schedule further work.

- [ ] **Step 4: Run tests and compile**

Expected: all unit tests pass and `:app:assembleDebug` succeeds.

### Task 5: Final verification and review

**Files:**
- Verify: all modified files and generated test reports.

- [ ] **Step 1: Inspect diff and status**

Confirm only the planned source, test, spec, and plan files changed; do not include `.claude/settings.local.json` or `app/.qtcreator/`.

- [ ] **Step 2: Run full verification**

Run: `gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain`

Expected: exit code 0, all test suites report zero failures/errors, and a Debug APK is generated.

- [ ] **Step 3: Report remaining non-code validation**

Explicitly retain the known need for real-device validation of exam mode, multi-select, and unsupported question types.
