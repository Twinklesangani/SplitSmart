package com.splitsmart.config;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.context.annotation.Import;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@JsonTest
@Import(JacksonConfig.class)
class MonetaryPrecisionTest {
    @Autowired ObjectMapper mapper;

    @Test
    void serializesDecimalMoneyAsExactTextIncludingAggregateTotals() throws Exception {
        for (String amount : new String[]{"0.60", "1000000000000.00", "70368744177664.01",
                "99999999999999999.99", "199999999999999999.98"}) {
            var node = mapper.readTree(mapper.writeValueAsString(Map.of("amount", new BigDecimal(amount))));
            assertTrue(node.get("amount").isTextual());
            assertEquals(amount, node.get("amount").asText());
        }
    }
}
