package com.splitsmart.config;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

	@Bean
	Jackson2ObjectMapperBuilderCustomizer strictTextualScalarCoercion() {
		return builder -> builder.postConfigurer(objectMapper -> {
			objectMapper.registerModule(new com.fasterxml.jackson.databind.module.SimpleModule()
                    .addSerializer(java.math.BigDecimal.class, new com.fasterxml.jackson.databind.JsonSerializer<java.math.BigDecimal>() {
                        @Override
                        public void serialize(java.math.BigDecimal value, com.fasterxml.jackson.core.JsonGenerator generator,
                                com.fasterxml.jackson.databind.SerializerProvider provider) throws java.io.IOException {
                            generator.writeString(value.toPlainString());
                        }
                    }));
            objectMapper.getFactory().setStreamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
                    .maxNestingDepth(32).maxStringLength(4096).maxNumberLength(64).build());
            var textualCoercion = objectMapper.coercionConfigFor(LogicalType.Textual);
			textualCoercion.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
			textualCoercion.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
			textualCoercion.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
		});
	}
}
