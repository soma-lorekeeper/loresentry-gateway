package com.loresentry.gateway.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.test.MockServerRestClientCustomizer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class UpstreamForwardTest {

    private static final String BASE = "http://content-api";

    private final UUID user = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d60");

    private MockRestServiceServer server;

    private ContentClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockServerRestClientCustomizer customizer = new MockServerRestClientCustomizer();
        customizer.customize(builder);
        this.server = customizer.getServer();
        this.client = new ContentClient(UpstreamClient.restClient(builder, BASE));
    }

    @Test
    void sendsThePathVerbatimWithTheIdentityHeader() {
        server.expect(requestTo(BASE + "/projects"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(UpstreamClient.USER_ID_HEADER, user.toString()))
                .andRespond(withSuccess("{\"projects\":[]}", MediaType.APPLICATION_JSON));

        ResponseEntity<byte[]> response = client.forward(HttpMethod.GET, "/projects", null, null, null, user);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo("{\"projects\":[]}");
        server.verify();
    }

    @Test
    void carriesTheQueryStringAndRequestBody() {
        server.expect(requestTo(BASE + "/projects?q=%EC%9C%A0%EB%A6%AC"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string("{\"name\":\"x\"}"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.forward(HttpMethod.POST, "/projects", "q=%EC%9C%A0%EB%A6%AC",
                "{\"name\":\"x\"}".getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_JSON, user);

        server.verify();
    }

    @Test
    void passesUpstreamErrorsThroughUntouched() {
        // 업스트림이 이유를 안다. gateway가 다시 포장하면 그 이유가 지워진다.
        server.expect(requestTo(BASE + "/projects"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"PROJECT_NAME_TAKEN\"}"));

        ResponseEntity<byte[]> response = client.forward(HttpMethod.POST, "/projects", null,
                "{}".getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_JSON, user);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8))
                .isEqualTo("{\"code\":\"PROJECT_NAME_TAKEN\"}");
    }

    @Test
    void keepsLocationButDropsHopByHopHeaders() {
        server.expect(requestTo(BASE + "/projects"))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.LOCATION, "/projects/abc")
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .body("{}"));

        ResponseEntity<byte[]> response = client.forward(HttpMethod.POST, "/projects", null,
                "{}".getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_JSON, user);

        assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo("/projects/abc");
        assertThat(response.getHeaders().getFirst(HttpHeaders.TRANSFER_ENCODING)).isNull();
    }
}
