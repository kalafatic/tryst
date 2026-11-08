package eu.kalafatic.tryst.domain.schedule;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for persistent task schedules and recurring tasks.
 */
public interface TaskScheduleRepository {

    void save(TaskSchedule schedule);

    Optional<TaskSchedule> findById(String id);

    List<TaskSchedule> findDueSchedules(Instant asOf);

    List<TaskSchedule> findByProjectId(String projectId);

    List<TaskSchedule> findByUserId(String userId);

    List<TaskSchedule> findAll();

    boolean delete(String id);
}
