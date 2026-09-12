package com.loresentry.gateway.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.restclient.test.MockServerRestClientCustomizer;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class UpstreamClientTest {

    record Upstream(String name, String baseUrl, Function<RestClient, UpstreamClient> factory) {

        @Override
        public String toString() {
            return name;
        }
    }

    static List<Upstream> upstreams() {
        return List.of(
                new Upstream("graph-rag", "http://graph-rag-api", GraphRagClient::new),
                new Upstream("ai-chat", "http://ai-chat-api", AiChatClient::new),
                new Upstream("authentication", "http://authentication-api", AuthenticationClient::new),
                new Upstream("content", "http://content-api", ContentClient::new));
    }

    private record Fixture(MockRestServiceServer server, UpstreamClient client) {
    }

    private static Fixture fixtureFor(Upstream upstream) {
        RestClient.Builder builder = RestClient.builder().baseUrl(upstream.baseUrl());
        MockServerRestClientCustomizer customizer = new MockServerRestClientCustomizer();
        customizer.customize(builder);

        return new Fixture(customizer.getServer(), upstream.factory().apply(builder.build()));
    }

    @ParameterizedTest
    @MethodSource("upstreams")
    void reportsItsUpstreamName(Upstream upstream) {
        assertThat(fixtureFor(upstream).client().name()).isEqualTo(upstream.name());
    }

    @ParameterizedTest
    @MethodSource("upstreams")
    void describeCallsRootAndReturnsPayload(Upstream upstream) {
        Fixture fixture = fixtureFor(upstream);

        fixture.server()
                .expect(requestTo(upstream.baseUrl() + "/"))
                .andRespond(withSuccess("{\"service\":\"probe\"}", MediaType.APPLICATION_JSON));

        assertThat(fixture.client().describe()).containsEntry("service", "probe");

        fixture.server().verify();
    }

    @ParameterizedTest
    @MethodSource("upstreams")
    void healthCallsHealthEndpoint(Upstream upstream) {
        Fixture fixture = fixtureFor(upstream);

        fixture.server()
                .expect(requestTo(upstream.baseUrl() + "/health"))
                .andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));

        assertThat(fixture.client().health()).containsEntry("status", "ok");

        fixture.server().verify();
    }

    @ParameterizedTest
    @MethodSource("upstreams")
    void wrapsUpstreamFailureWithItsName(Upstream upstream) {
        Fixture fixture = fixtureFor(upstream);

        fixture.server().expect(requestTo(upstream.baseUrl() + "/")).andRespond(withServerError());

        assertThatExceptionOfType(UpstreamException.class)
                .isThrownBy(() -> fixture.client().describe())
                .satisfies(exception -> assertThat(exception.getUpstream()).isEqualTo(upstream.name()));
    }
}
