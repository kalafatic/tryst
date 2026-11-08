package eu.kalafatic.tryst.domain.service;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.model.Task;
import eu.kalafatic.tryst.domain.model.TaskPriority;
import eu.kalafatic.tryst.domain.schedule.ScheduleType;
import eu.kalafatic.tryst.domain.schedule.TaskSchedule;
import eu.kalafatic.tryst.domain.schedule.TaskScheduleRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Service managing persistent task scheduling and recurring executions.
 * Background poller scans due schedules from SQLite and submits tasks via TaskOrchestrator.
 */
public class TaskSchedulerService {

    private final TaskScheduleRepository scheduleRepository;
    private final TaskOrchestrator taskOrchestrator;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    public TaskSchedulerService(TaskScheduleRepository scheduleRepository, TaskOrchestrator taskOrchestrator) {
        this.scheduleRepository = Objects.requireNonNull(scheduleRepository, "scheduleRepository must not be null");
        this.taskOrchestrator = Objects.requireNonNull(taskOrchestrator, "taskOrchestrator must not be null");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tryst-task-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        scheduler.scheduleWithFixedDelay(this::pollDueSchedules, 5, 10, TimeUnit.SECONDS);
        TrystLog.info(TaskSchedulerService.class, "TaskSchedulerService started");
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        TrystLog.info(TaskSchedulerService.class, "TaskSchedulerService stopped");
    }

    public TaskSchedule scheduleTask(String name, String projectId, String userId,
                                     ScheduleType type, Instant scheduledTime, String cron, String payloadJson) {
        TaskSchedule schedule = new TaskSchedule(name, projectId, userId, type, cron, scheduledTime, payloadJson);
        scheduleRepository.save(schedule);
        TrystLog.info(TaskSchedulerService.class, "Created task schedule " + schedule.getId() + " (" + type + ") for " + scheduledTime);
        return schedule;
    }

    public void cancelSchedule(String scheduleId) {
        scheduleRepository.findById(scheduleId).ifPresent(s -> {
            s.setStatus(TaskSchedule.Status.CANCELLED);
            scheduleRepository.save(s);
            TrystLog.info(TaskSchedulerService.class, "Cancelled task schedule " + scheduleId);
        });
    }

    public void pollDueSchedules() {
        try {
            Instant now = Instant.now();
            List<TaskSchedule> due = scheduleRepository.findDueSchedules(now);
            for (TaskSchedule s : due) {
                try {
                    dispatchScheduledExecution(s, now);
                } catch (Exception e) {
                    TrystLog.error(TaskSchedulerService.class, "Failed to dispatch schedule " + s.getId() + ": " + e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            TrystLog.error(TaskSchedulerService.class, "Error polling due schedules: " + e.getMessage(), e);
        }
    }

    private void dispatchScheduledExecution(TaskSchedule schedule, Instant now) {
        TrystLog.info(TaskSchedulerService.class, "Triggering scheduled task execution for schedule: " + schedule.getName() + " (" + schedule.getId() + ")");
        taskOrchestrator.submitTask(
                schedule.getProjectId(),
                null,
                schedule.getName(),
                "Scheduled execution from schedule " + schedule.getId(),
                TaskPriority.NORMAL,
                schedule.getUserId(),
                null
        );

        schedule.setLastExecutedAt(now);
        schedule.incrementExecutionCount();

        if (schedule.getScheduleType() == ScheduleType.ONCE) {
            schedule.setStatus(TaskSchedule.Status.COMPLETED);
        } else {
            // Compute next execution
            Instant next = computeNextExecution(schedule.getScheduleType(), now);
            schedule.setNextExecutionAt(next);
        }
        scheduleRepository.save(schedule);
    }

    private Instant computeNextExecution(ScheduleType type, Instant from) {
        return switch (type) {
            case DAILY -> from.plus(1, ChronoUnit.DAYS);
            case WEEKLY -> from.plus(7, ChronoUnit.DAYS);
            case MONTHLY -> from.plus(30, ChronoUnit.DAYS);
            default -> from.plus(1, ChronoUnit.DAYS);
        };
    }
}
