# TRYST Method-Level Execution Flows

## 1. Asynchronous Task Lifecycle Call Flow

```
[HTTP Client]
      |
      | POST /api/tasks {projectId, title, description, priority}
      v
[TrystHttpServer$TasksHandler.handle()]
      |
      | taskOrchestrator.submitTask(projectId, sessionId, title, description, priority, userId, modelName, context)
      v
[TaskOrchestrator.submitTask()]
      |
      +---> permissionManager.validateUserProjectAccess()
      |
      +---> taskRepository.saveTask(new Task(... Status: CREATED))
      |
      +---> taskQueue.enqueue(task) -> task.transitionTo(QUEUED) -> taskRepository.saveTask()
      |
      +---> notificationService.notifyUser(TASK_ACCEPTED)
      |
      +---> return task (HTTP 201 Created returned immediately to client)
      |
      ~ (Asynchronous Background Thread)
      v
[TaskOrchestrator.dispatchLoop()]
      |
      | taskQueue.claimNext(workerId) (Atomic SQL UPDATE ... WHERE status = 'QUEUED')
      v
[TaskOrchestrator.executeTaskAsync(task, workerId)]
      |
      +---> workspaceService.allocateTaskWorkspace(...) -> isolated sandbox
      |
      +---> workerExecutor.submit(new TaskWorker(context, ...))
      v
[TaskWorker.call()]
      |
      +--- [Stage 1: CLONING]
      |     lifecycleManager.transition(task, CLONING)
      |     gitHubService.cloneOrFetchRepository(ws.getRootPath())
      |     notificationService.notifyUser(REPOSITORY_CLONED)
      |
      +--- [Stage 2: ANALYZING]
      |     lifecycleManager.transition(task, ANALYZING)
      |     taskAnalyzer.analyze(ws, task) -> conventions, dependencies, source files
      |     notificationService.notifyUser(AI_ANALYSIS_COMPLETED)
      |
      +--- [Stage 3: PLANNING]
      |     lifecycleManager.transition(task, PLANNING)
      |     implementationPlanner.createPlan(task, analysis, ws)
      |
      +--- [Stage 4: IMPLEMENTING]
      |     lifecycleManager.transition(task, IMPLEMENTING)
      |     notificationService.notifyUser(IMPLEMENTATION_STARTED)
      |     executeAgentImplementationLoop() -> CodeAgent.runStep() -> Tool calls / file writes
      |
      +--- [Stage 5: BUILDING]
      |     lifecycleManager.transition(task, BUILDING)
      |     resultValidator.validate() -> BuildAndQualityService.runQualityAssurance()
      |     notificationService.notifyUser(BUILD_COMPLETED)
      |
      +--- [Stage 6: TESTING]
      |     lifecycleManager.transition(task, TESTING)
      |     resultValidator verifies automated test outcome
      |
      +--- [Stage 7: WAITING_FOR_REVIEW]
      |     extractWorkspaceDiff()
      |     codeReviewRepository.save(new CodeReview(...) or updated revision)
      |     lifecycleManager.transition(task, WAITING_FOR_REVIEW)
      |     notificationService.notifyUser(REVIEW_READY)
      |     notificationService.notifyUser(APPROVAL_REQUIRED)
      v
[ExecutionResult persisted in execution_results]
```

## 2. Code Review & Approval Method Call Flow

```
[Reviewer / User Web Client]
      |
      | GET /api/reviews/{id}/details
      v
[TrystHttpServer$ReviewsHandler.handle()]
      |
      | Returns parsed side-by-side diff, build/test reports, revision history, comments
      |
      | POST /api/reviews/{id}/approve {"revisionNumber": 1}
      v
[TrystHttpServer$ReviewsHandler.handle()]
      |
      +---> CodeReview.isAiAgent(userId) -> Rejected (403) if AI tries to self-approve
      |
      +---> Check exact revision: revisionNumber == review.getRevisionCount()
      |
      +---> Check blocking comments: findCommentsByReviewId() -> reject if unresolved blocking > 0
      |
      +---> Check working tree diff SHA-256 fingerprint matches approved diff
      |
      +---> review.approve(userId, revisionNumber)
      |
      +---> task.transitionTo(TaskStatus.APPROVED)
      |
      v
[POST /api/reviews/{id}/commit {"message": "Verified by human"}]
      |
      +---> CodeReview.isAiAgent(userId) -> 403
      |
      +---> GitService.commitAuthorizedReview(repoDir, review, userId, message)
      |     |
      |     +---> Verify status clean & fingerprint exact match
      |     +---> git add -A && git commit -m "..."
      |     +---> git rev-parse HEAD -> commitHash
      |     v
      +---> gitCommitRepository.save(new GitCommitRecord(...))
      +---> task.transitionTo(TaskStatus.COMPLETED)
      +---> workspace.complete()
```
