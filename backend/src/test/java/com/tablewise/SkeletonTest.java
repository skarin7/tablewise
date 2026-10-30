package com.tablewise;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** M1: context starts with ddl-auto=validate over every entity, Flyway ran both migrations, health is public. */
class SkeletonTest extends IntegrationTest {
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    @Test
    void healthIsUpAndMigrationsApplied() {
        assertThat(rest.getForObject("/actuator/health", String.class)).contains("UP");
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success and version is not null", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from pg_tables where schemaname='pos'", Integer.class)).isEqualTo(25);
    }
}
