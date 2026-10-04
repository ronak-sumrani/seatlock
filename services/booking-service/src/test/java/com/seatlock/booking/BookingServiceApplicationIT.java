package com.seatlock.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatlock.common.testing.SeatLockPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.embedded.TomcatVirtualThreadsWebServerFactoryCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class BookingServiceApplicationIT {

    // Log in as the real restricted role, not the container superuser. That way Flyway and the app
    // prove they work with exactly the grants the init script gives booking_user.
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        SeatLockPostgres.registerDatasource(registry, "booking_user");
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void contextLoadsWithHealthUpAndVirtualThreads() {
        // Health now includes the db indicator, so UP also means the datasource can connect.
        ResponseEntity<String> health = rest.getForEntity("/actuator/health", String.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("\"status\":\"UP\"");

        // Boot registers this customizer only when spring.threads.virtual.enabled=true, so its presence
        // proves Tomcat really hands requests to virtual threads, not just that the property was typed.
        assertThat(context.getBeanNamesForType(TomcatVirtualThreadsWebServerFactoryCustomizer.class))
                .hasSize(1);
    }

    @Test
    void flywayAppliedBaselineInOwnSchema() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM booking.flyway_schema_history WHERE version = '1' AND success", Integer.class);
        assertThat(applied).isEqualTo(1);
    }
}
