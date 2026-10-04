package com.seatlock.common.testing;

import static com.seatlock.common.testing.SeatLockPostgres.CHECKER_RO;
import static com.seatlock.common.testing.SeatLockPostgres.OWNED_SCHEMAS;
import static com.seatlock.common.testing.SeatLockPostgres.connect;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves spec §3: each service role can use only its own schema(s), and checker_ro can read every
 * schema but write nothing. Runs against the real init script (see {@link SeatLockPostgres}).
 */
class SchemaIsolationIT {

    /** SQLSTATE insufficient_privilege: "permission denied for schema/table ...". */
    private static final String PERMISSION_DENIED = "42501";
    /** SQLSTATE read_only_sql_transaction. */
    private static final String READ_ONLY = "25006";

    private static final List<String> ALL_SCHEMAS =
            OWNED_SCHEMAS.values().stream().flatMap(List::stream).sorted().toList();

    /**
     * Each owner creates a probe table with one row in each of its schemas. This happens after
     * init, the way Flyway will create tables, so the checker_ro tests also prove the default
     * privileges and not only the one-off GRANT.
     */
    @BeforeAll
    static void createProbeTables() throws SQLException {
        for (var entry : OWNED_SCHEMAS.entrySet()) {
            try (Connection c = connect(entry.getKey());
                    Statement st = c.createStatement()) {
                for (String schema : entry.getValue()) {
                    st.execute("CREATE TABLE " + schema + ".probe (id int)");
                    st.execute("INSERT INTO " + schema + ".probe VALUES (1)");
                }
            }
        }
    }

    static Stream<Arguments> ownSchemas() {
        return OWNED_SCHEMAS.entrySet().stream()
                .flatMap(e -> e.getValue().stream().map(s -> Arguments.of(e.getKey(), s)));
    }

    static Stream<Arguments> foreignSchemas() {
        return OWNED_SCHEMAS.entrySet().stream()
                .flatMap(e -> ALL_SCHEMAS.stream()
                        .filter(s -> !e.getValue().contains(s))
                        .map(s -> Arguments.of(e.getKey(), s)));
    }

    static Stream<String> allRoles() {
        return Stream.concat(OWNED_SCHEMAS.keySet().stream(), Stream.of(CHECKER_RO));
    }

    @ParameterizedTest(name = "{0} can read and write {1}")
    @MethodSource("ownSchemas")
    void ownerCanUseItsOwnSchema(String role, String schema) throws SQLException {
        try (Connection c = connect(role);
                Statement st = c.createStatement()) {
            st.execute("INSERT INTO " + schema + ".probe VALUES (2)");
            st.execute("CREATE TABLE " + schema + ".own_" + role + " (id int)");
            st.execute("DROP TABLE " + schema + ".own_" + role);
            st.execute("DELETE FROM " + schema + ".probe WHERE id = 2");
        }
    }

    @ParameterizedTest(name = "{0} cannot touch {1}")
    @MethodSource("foreignSchemas")
    void ownerCannotUseAnotherServicesSchema(String role, String schema) throws SQLException {
        try (Connection c = connect(role)) {
            assertDenied(c, "SELECT * FROM " + schema + ".probe", PERMISSION_DENIED);
            assertDenied(c, "INSERT INTO " + schema + ".probe VALUES (9)", PERMISSION_DENIED);
            assertDenied(c, "CREATE TABLE " + schema + ".intruder (id int)", PERMISSION_DENIED);
        }
    }

    @ParameterizedTest(name = "{0} cannot create in public")
    @MethodSource("allRoles")
    void nobodyCanCreateInPublic(String role) throws SQLException {
        try (Connection c = connect(role)) {
            // checker_ro is also read-only, so for it this fails whichever check Postgres hits first.
            String expected = role.equals(CHECKER_RO) ? READ_ONLY : PERMISSION_DENIED;
            assertDenied(c, "CREATE TABLE public.intruder (id int)", expected);
        }
    }

    @Test
    void checkerCanReadTablesCreatedAfterInitInEverySchema() throws SQLException {
        try (Connection c = connect(CHECKER_RO);
                Statement st = c.createStatement()) {
            for (String schema : ALL_SCHEMAS) {
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + schema + ".probe")) {
                    rs.next();
                    assertThat(rs.getInt(1)).as(schema).isEqualTo(1);
                }
            }
        }
    }

    @Test
    void checkerSessionsAreReadOnlyByDefault() throws SQLException {
        try (Connection c = connect(CHECKER_RO)) {
            for (String schema : ALL_SCHEMAS) {
                assertDenied(c, "INSERT INTO " + schema + ".probe VALUES (9)", READ_ONLY);
            }
        }
    }

    @Test
    void checkerCannotWriteEvenAfterTurningReadOnlyOff() throws SQLException {
        // Any session may switch read-only off for itself, so the read-only default is only a
        // second layer. This proves the grants block writes on their own.
        try (Connection c = connect(CHECKER_RO);
                Statement st = c.createStatement()) {
            st.execute("SET default_transaction_read_only = off");
            for (String schema : ALL_SCHEMAS) {
                assertDenied(c, "INSERT INTO " + schema + ".probe VALUES (9)", PERMISSION_DENIED);
                assertDenied(c, "UPDATE " + schema + ".probe SET id = 9", PERMISSION_DENIED);
                assertDenied(c, "DELETE FROM " + schema + ".probe", PERMISSION_DENIED);
                assertDenied(c, "CREATE TABLE " + schema + ".intruder (id int)", PERMISSION_DENIED);
            }
        }
    }

    private static void assertDenied(Connection c, String sql, String sqlState) {
        assertThatThrownBy(() -> {
                    try (Statement st = c.createStatement()) {
                        st.execute(sql);
                    }
                })
                .as(sql)
                .isInstanceOfSatisfying(
                        SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(sqlState));
    }
}
