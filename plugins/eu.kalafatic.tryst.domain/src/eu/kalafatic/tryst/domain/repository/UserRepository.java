package eu.kalafatic.tryst.domain.repository;

import eu.kalafatic.tryst.domain.model.User;

import java.util.List;
import java.util.Optional;

public interface UserRepository {
    void save(User user);
    Optional<User> findById(String id);
    Optional<User> findByUsername(String username);
    List<User> findAll();
    void delete(String id);
    long count();
}
