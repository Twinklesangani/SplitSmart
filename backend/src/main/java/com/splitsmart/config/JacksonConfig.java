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
			var textualCoercion = objectMapper.coercionConfigFor(LogicalType.Textual);
			textualCoercion.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
			textualCoercion.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
			textualCoercion.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
		});
	}
}
