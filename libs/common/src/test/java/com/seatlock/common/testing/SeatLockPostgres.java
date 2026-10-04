package com.seatlock.common.testing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * One Postgres 16 container per test JVM, initialised by the same script that Compose uses
 * (deploy/postgres/init). Tests log in as the real service roles, so a missing or wrong grant
 * fails a test instead of showing up later in Compose.
 */
public final class SeatLockPostgres {

    public static final String IMAGE = "postgres:16-alpine";
    private static final String INIT_SCRIPT = "deploy/postgres/init/01-schemas-and-roles.sh";

    /** Each service role and the schemas it owns, exactly as the init script sets them up. */
    public static final Map<String, List<String>> OWNED_SCHEMAS = Map.of(
            "user_catalog_user", List.of("auth", "catalog"),
            "inventory_user", List.of("inventory"),
            "booking_user", List.of("booking"),
            "payment_user", List.of("payment"));

    public static final String CHECKER_RO = "checker_ro";

    private static final PostgreSQLContainer<?> CONTAINER = start();

    private SeatLockPostgres() {}

    private static PostgreSQLContainer<?> start() {
        PostgreSQLContainer<?> pg = new PostgreSQLContainer<>(IMAGE)
                .withDatabaseName("seatlock")
                .withUsername("seatlock_admin")
                .withPassword("admin_test_pw")
                .withEnv("USER_CATALOG_DB_PASSWORD", password("user_catalog_user"))
                .withEnv("INVENTORY_DB_PASSWORD", password("inventory_user"))
                .withEnv("BOOKING_DB_PASSWORD", password("booking_user"))
                .withEnv("PAYMENT_DB_PASSWORD", password("payment_user"))
                .withEnv("CHECKER_RO_DB_PASSWORD", password(CHECKER_RO))
                .withCopyFileToContainer(
                        MountableFile.forHostPath(repoRoot().resolve(INIT_SCRIPT), 0755),
                        "/docker-entrypoint-initdb.d/01-schemas-and-roles.sh");
        // The default wait strategy waits for the second "ready to accept connections" log line,
        // which the image prints only after the init scripts have finished.
        pg.start();
        return pg;
    }

    public static String jdbcUrl() {
        return CONTAINER.getJdbcUrl();
    }

    public static String password(String role) {
        return role + "_test_pw";
    }

    public static Connection connect(String role) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), role, password(role));
    }

    /** Points a Spring Boot service at the container, logged in as its own restricted role. */
    public static void registerDatasource(DynamicPropertyRegistry registry, String role) {
        registry.add("spring.datasource.url", SeatLockPostgres::jdbcUrl);
        registry.add("spring.datasource.username", () -> role);
        registry.add("spring.datasource.password", () -> password(role));
    }

    /**
     * Maven failsafe sets seatlock.repo.root. When a test runs from an IDE instead, walk up from
     * the working directory until the init script is found.
     */
    private static Path repoRoot() {
        String fromMaven = System.getProperty("seatlock.repo.root");
        if (fromMaven != null && !fromMaven.isBlank()) {
            return Path.of(fromMaven);
        }
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve(INIT_SCRIPT))) {
                return dir;
            }
        }
        throw new IllegalStateException("Cannot find " + INIT_SCRIPT + "; set -Dseatlock.repo.root");
    }
}
