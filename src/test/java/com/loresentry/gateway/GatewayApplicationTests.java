package com.loresentry.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@com.loresentry.gateway.LocalTestEnvironment
@SpringBootTest
class GatewayApplicationTests {

    @Value("${spring.threads.virtual.enabled}")
    private boolean virtualThreadsEnabled;

    @Test
    void contextLoads() {
    }

    @Test
    void virtualThreadsAreEnabled() {
        assertThat(virtualThreadsEnabled).isTrue();
    }
}
