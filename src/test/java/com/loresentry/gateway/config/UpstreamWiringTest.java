package com.loresentry.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.loresentry.gateway.client.AiChatClient;
import com.loresentry.gateway.client.AuthenticationClient;
import com.loresentry.gateway.client.ContentClient;
import com.loresentry.gateway.client.GraphRagClient;
import com.loresentry.gateway.client.UpstreamClient;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpResponse;

@com.loresentry.gateway.LocalTestEnvironment
@SpringBootTest(properties = {
        "loresentry.upstream.graph-rag.base-url=http://wired-graph-rag",
        "loresentry.upstream.ai-chat.base-url=http://wired-ai-chat",
        "loresentry.upstream.authentication.base-url=http://wired-authentication",
        "loresentry.upstream.content.base-url=http://wired-content"
})
class UpstreamWiringTest {

    static class RecordingInterceptor implements ClientHttpRequestInterceptor {

        private final List<URI> uris = new CopyOnWriteArrayList<>();

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws IOException {
            uris.add(request.getURI());

            MockClientHttpResponse response =
                    new MockClientHttpResponse("{}".getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

            return response;
        }

        URI lastUri() {
            return uris.get(uris.size() - 1);
        }
    }

    @TestConfiguration
    static class RecordingConfiguration {

        @Bean
        RecordingInterceptor recordingInterceptor() {
            return new RecordingInterceptor();
        }

        @Bean
        RestClientCustomizer recordingCustomizer(RecordingInterceptor interceptor) {
            return builder -> builder.requestInterceptor(interceptor);
        }
    }

    @Autowired
    private RecordingInterceptor interceptor;

    @Autowired
    private UpstreamProperties properties;

    @Autowired
    private GraphRagClient graphRagClient;

    @Autowired
    private AiChatClient aiChatClient;

    @Autowired
    private AuthenticationClient authenticationClient;

    @Autowired
    private ContentClient contentClient;

    @Test
    void everyUpstreamIsBoundFromConfiguration() {
        assertThat(properties.graphRag().baseUrl()).isEqualTo("http://wired-graph-rag");
        assertThat(properties.aiChat().baseUrl()).isEqualTo("http://wired-ai-chat");
        assertThat(properties.authentication().baseUrl()).isEqualTo("http://wired-authentication");
        assertThat(properties.content().baseUrl()).isEqualTo("http://wired-content");
    }

    @Test
    void everyClientReportsItsUpstreamName() {
        assertThat(graphRagClient.name()).isEqualTo("graph-rag");
        assertThat(aiChatClient.name()).isEqualTo("ai-chat");
        assertThat(authenticationClient.name()).isEqualTo("authentication");
        assertThat(contentClient.name()).isEqualTo("content");
    }

    @Test
    void everyClientCallsItsOwnUpstreamHost() {
        assertThat(hostCalledBy(graphRagClient)).isEqualTo("wired-graph-rag");
        assertThat(hostCalledBy(aiChatClient)).isEqualTo("wired-ai-chat");
        assertThat(hostCalledBy(authenticationClient)).isEqualTo("wired-authentication");
        assertThat(hostCalledBy(contentClient)).isEqualTo("wired-content");
    }

    private String hostCalledBy(UpstreamClient client) {
        client.describe();

        return interceptor.lastUri().getHost();
    }
}
