package com.seatlock.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.embedded.TomcatVirtualThreadsWebServerFactoryCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class InventoryServiceApplicationTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ApplicationContext context;

    @Test
    void contextLoadsWithHealthUpAndVirtualThreads() {
        ResponseEntity<String> health = rest.getForEntity("/actuator/health", String.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("\"status\":\"UP\"");

        // Boot registers this customizer only when spring.threads.virtual.enabled=true, so its presence
        // proves Tomcat really hands requests to virtual threads, not just that the property was typed.
        assertThat(context.getBeanNamesForType(TomcatVirtualThreadsWebServerFactoryCustomizer.class))
                .hasSize(1);
    }
}
