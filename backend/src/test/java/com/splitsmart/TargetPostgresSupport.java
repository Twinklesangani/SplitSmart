package com.splitsmart;

import java.nio.file.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.context.DynamicPropertyRegistry;
import static org.junit.jupiter.api.Assertions.*;

/** Cloud tests opt in to one manifest-bound synthetic database only. */
final class TargetPostgresSupport {
    static String verifiedUrl(boolean application) throws Exception {
        var manifest=new ObjectMapper().readTree(Files.readString(Path.of(System.getenv("PG_TARGET_MANIFEST"))));
        assertEquals("splitsmart-postgres",manifest.path("service").asText());
        assertEquals("twinklesangani-9b5e",manifest.path("project").asText());
        String url=manifest.path(application?"application_url":"test_url").asText();
        assertTrue(url.startsWith("jdbc:postgresql://"));
        assertFalse(url.contains("@")); // credentials must never be in the URL
        assertTrue(manifest.path("test_database").asText().startsWith("splitsmart_synthetic_"));
        assertNotEquals(manifest.path("application_url").asText(),manifest.path("test_url").asText());
        return url;
    }
    static void configure(DynamicPropertyRegistry properties) {
        try {
            String url=verifiedUrl(false);
            assertEquals(url,System.getenv("PG_TARGET_TEST_URL"));
            properties.add("spring.datasource.url",()->url);
            properties.add("spring.flyway.url",()->url);
        } catch(Exception e) { throw new IllegalStateException("Target database guard failed",e); }
    }
}
