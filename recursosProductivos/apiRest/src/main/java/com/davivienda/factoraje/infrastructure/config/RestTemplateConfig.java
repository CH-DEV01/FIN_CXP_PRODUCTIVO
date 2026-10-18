package com.davivienda.factoraje.infrastructure.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    /** Plazo de conexión y de lectura de la validación del OTC en Pay Davivienda. */
    private static final Duration PAY_DAVIVIENDA_TIMEOUT = Duration.ofSeconds(15);

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(PAY_DAVIVIENDA_TIMEOUT);
        factory.setReadTimeout(PAY_DAVIVIENDA_TIMEOUT);
        return new RestTemplate(factory);
    }
}