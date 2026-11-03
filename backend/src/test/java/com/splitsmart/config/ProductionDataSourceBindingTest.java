package com.splitsmart.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class ProductionDataSourceBindingTest {

    @Test
    void correctlyNamedEnvironmentVariableBindsBeforeAnyConnectionIsRequested() {
        try (var source = new ConnectionTrackingDataSource()) {
            binder("SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT").bind(
                    "spring.datasource.hikari", Bindable.ofInstance(source));

            assertThat(source.getConnectionTimeout()).isEqualTo(15000);
            assertThat(source.getDataSourceProperties()).containsEntry("sslMode", "VERIFY_IDENTITY");
            assertThat(source.connectionRequested).isFalse();
            assertThat(source.isRunning()).isFalse();
        }
    }

    @Test
    void extraUnderscoreAttemptsToBindTheNestedConnectionProperty() {
        try (var source = new ConnectionTrackingDataSource()) {
            assertThatThrownBy(() -> binder("SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT")
                    .bind("spring.datasource.hikari", Bindable.ofInstance(source)))
                    .isInstanceOf(BindException.class);
            assertThat(source.connectionRequested).isTrue();
        }
    }

    private Binder binder(String name) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "systemEnvironment", Map.of(name, "15000")));
        environment.getPropertySources().addLast(new MapPropertySource("tls", Map.of(
                "spring.datasource.hikari.data-source-properties[sslMode]", "VERIFY_IDENTITY")));
        return Binder.get(environment);
    }

    static class ConnectionTrackingDataSource extends HikariDataSource {
        private boolean connectionRequested;

        @Override
        public Connection getConnection() throws SQLException {
            connectionRequested = true;
            throw new SQLException("A connection must not be requested while binding configuration");
        }
    }
}
