package com.loresentry.gateway.config;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

class EnvironmentConfigurationTest {
    @Configuration @EnableConfigurationProperties({BrowserProperties.class,UpstreamProperties.class})
    static class Binding {}
    ApplicationContextRunner runner(String... profiles) {
        return new ApplicationContextRunner().withUserConfiguration(Binding.class,EnvironmentConfiguration.class)
            .withInitializer(c->c.getEnvironment().setActiveProfiles(profiles))
            .withPropertyValues("loresentry.browser.frontend-origin=http://localhost:3000",
                "loresentry.browser.public-origin=http://localhost:8000",
                "loresentry.browser.login-redirect=http://localhost:3000/login",
                "loresentry.browser.secure-cookies=false",
                "loresentry.upstream.authentication.base-url=http://auth.test",
                "loresentry.upstream.content.base-url=http://content.test",
                "loresentry.upstream.graph-rag.base-url=http://graph.test",
                "loresentry.upstream.ai-chat.base-url=http://chat.test");
    }
    @Test void rejectsNoProfile() { runner().run(c->assertThat(c).hasFailed()); }
    @Test void rejectsBothProfiles() { runner("local","prod").run(c->assertThat(c).hasFailed()); }
    @Test void acceptsExplicitLocal() { runner("local").run(c->assertThat(c).hasNotFailed()); }
    @Test void acceptsExplicitProductionOnlyWithProductionBrowserSettings() {
        runner("prod").withPropertyValues("loresentry.browser.frontend-origin=https://loresentry.com",
            "loresentry.browser.public-origin=https://api.loresentry.com",
            "loresentry.browser.login-redirect=https://loresentry.com/login",
            "loresentry.browser.secure-cookies=true").run(c->assertThat(c).hasNotFailed());
    }
    @Test void rejectsMixedEnvironment() { runner("prod").run(c->assertThat(c).hasFailed()); }
    @Test void rejectsUnconfiguredServiceAddress() {
        runner("local").withPropertyValues("loresentry.upstream.content.base-url=").run(c->assertThat(c).hasFailed());
    }
    @Test void rejectsRedirectToAnotherHost() {
        runner("local").withPropertyValues("loresentry.browser.login-redirect=https://evil.test/login").run(c->assertThat(c).hasFailed());
    }
}
