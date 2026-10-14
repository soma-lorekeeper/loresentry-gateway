package com.loresentry.gateway.config;

import static org.assertj.core.api.Assertions.*;
import java.util.Map;
import java.util.UUID;
import com.loresentry.gateway.application.ChatAllowlist;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class ChatAllowlistTest {
    static final UUID WRITER = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d60");
    static final UUID OTHER = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d61");

    @ParameterizedTest @ValueSource(strings = {"", "  ", ",", " , "})
    void emptyListAllowsNobody(String value) {
        var allowlist = new ChatConfiguration().chatAllowlist(value);
        assertThat(allowlist.allows(WRITER)).isFalse();
        assertThat(allowlist.allows(null)).isFalse();
    }

    @Test void unsetValueAllowsNobody() {
        assertThat(ChatAllowlist.parse(null).allows(WRITER)).isFalse();
    }

    @Test void listedUsersOnly() {
        var allowlist = ChatAllowlist.parse(" " + WRITER.toString().toUpperCase() + " ,0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d69,");
        assertThat(allowlist.allows(WRITER)).isTrue();
        assertThat(allowlist.allows(OTHER)).isFalse();
    }

    @ParameterizedTest @ValueSource(strings = {"*", " * ", "0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d69,*"})
    void starAllowsEveryone(String value) {
        var allowlist = ChatAllowlist.parse(value);
        assertThat(allowlist.allows(WRITER)).isTrue();
        assertThat(allowlist.allows(OTHER)).isTrue();
        assertThat(allowlist.allows(null)).isFalse();
    }

    @ParameterizedTest @ValueSource(strings = {"writer", "1-2-3-4-5", "0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d60;0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d61", "**"})
    void malformedEntryStopsStartup(String value) {
        assertThatThrownBy(() -> ChatAllowlist.parse(value)).isInstanceOf(IllegalStateException.class).hasMessageNotContaining(value);
    }

    @Test void environmentVariableFeedsTheAllowlist() {
        new ApplicationContextRunner().withUserConfiguration(ChatConfiguration.class)
            .withBean(UpstreamProperties.class, () -> new UpstreamProperties(null, new UpstreamProperties.Service("http://chat.test"), null, null))
            .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("test-env", Map.of("LORESENTRY_CHAT_ALLOWED_USERS", WRITER.toString()))))
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(ChatAllowlist.class).allows(WRITER)).isTrue();
                assertThat(context.getBean(ChatAllowlist.class).allows(OTHER)).isFalse();
            });
        new ApplicationContextRunner().withUserConfiguration(ChatConfiguration.class)
            .withBean(UpstreamProperties.class, () -> new UpstreamProperties(null, new UpstreamProperties.Service("http://chat.test"), null, null))
            .run(context -> assertThat(context.getBean(ChatAllowlist.class).allows(WRITER)).isFalse());
    }
}
