package eu.kalafatic.tryst.domain.security;

import eu.kalafatic.tryst.domain.model.User;
import eu.kalafatic.tryst.domain.model.UserRole;

import java.util.Objects;

/**
 * Thread-local or explicit security context representing the authenticated actor.
 */
public record SecurityContext(User user) {

    public static final SecurityContext SYSTEM = new SecurityContext(
            new User("system", "system", "SYSTEM_INTERNAL", "SYSTEM_INTERNAL", UserRole.ADMIN, true, false, 0, null, null)
    );

    public static final SecurityContext DEV = new SecurityContext(
            new User("dev-user", "dev-user", "DEV_MODE_BYPASS", "DEV_MODE_BYPASS", UserRole.ADMIN, true, false, 0, null, null)
    );

    public SecurityContext {
        Objects.requireNonNull(user, "user must not be null");
    }

    public String getUserId() {
        return user.getId();
    }

    public String getUsername() {
        return user.getUsername();
    }

    public UserRole getRole() {
        return user.getRole();
    }

    public boolean isAdmin() {
        return user.getRole() == UserRole.ADMIN;
    }
}
