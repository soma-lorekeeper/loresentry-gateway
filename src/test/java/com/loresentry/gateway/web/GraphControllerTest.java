package com.loresentry.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.Map;

import com.loresentry.gateway.client.GraphRagClient;
import com.loresentry.gateway.client.UpstreamException;
import com.loresentry.gateway.config.RestClientConfig;
import com.loresentry.gateway.config.UpstreamProperties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@RestClientTest(GraphRagClient.class)
@Import(RestClientConfig.class)
@EnableConfigurationProperties(UpstreamProperties.class)
@TestPropertySource(properties = {
        "loresentry.upstream.graph-rag.base-url=http://graph-rag-api"
})
class GraphControllerTest {

    @Autowired
    private GraphRagClient graphRagClient;

    @Autowired
    private MockRestServiceServer server;

    @Test
    void returnsUpstreamPayload() {
        server.expect(requestTo("http://graph-rag-api/"))
                .andRespond(withSuccess("{\"service\":\"graph-rag-api\"}", MediaType.APPLICATION_JSON));

        Map<String, Object> body = graphRagClient.describe();

        assertThat(body).containsEntry("service", "graph-rag-api");
    }

    @Test
    void wrapsUpstreamFailure() {
        server.expect(requestTo("http://graph-rag-api/")).andRespond(withServerError());

        assertThatExceptionOfType(UpstreamException.class)
                .isThrownBy(() -> graphRagClient.describe())
                .satisfies(exception -> assertThat(exception.getUpstream()).isEqualTo("graph-rag"));
    }
}
