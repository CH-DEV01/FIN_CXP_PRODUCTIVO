package com.davivienda.factoraje.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

class RestTemplateConfigTest {

    @Test
    void payDaviviendaWaitsAtMostFifteenSecondsToConnectAndToRead() throws Exception {
        RestTemplate restTemplate = new RestTemplateConfig().restTemplate();
        assertThat(restTemplate.getRequestFactory()).isInstanceOf(SimpleClientHttpRequestFactory.class);

        SimpleClientHttpRequestFactory factory = (SimpleClientHttpRequestFactory) restTemplate.getRequestFactory();
        assertThat(timeoutMillis(factory, "connectTimeout")).isEqualTo(15_000);
        assertThat(timeoutMillis(factory, "readTimeout")).isEqualTo(15_000);
    }

    private static int timeoutMillis(SimpleClientHttpRequestFactory factory, String fieldName) throws Exception {
        Field field = SimpleClientHttpRequestFactory.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(factory);
    }
}
