package com.loresentry.gateway.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;

@Configuration(proxyBeanMethods = false)
public class JsonConfiguration {
    @Bean
    JsonMapperBuilderCustomizer strictRequests() {
        return builder -> builder.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .withCoercionConfig(tools.jackson.databind.type.LogicalType.Textual, config -> {
                    for (var shape : java.util.List.of(tools.jackson.databind.cfg.CoercionInputShape.Integer,
                            tools.jackson.databind.cfg.CoercionInputShape.Float,
                            tools.jackson.databind.cfg.CoercionInputShape.Boolean)) {
                        config.setCoercion(shape, tools.jackson.databind.cfg.CoercionAction.Fail);
                    }
                });
    }
}
