package com.loresentry.gateway.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withCreatedEntity;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.test.MockServerRestClientCustomizer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ContentClientTest {

    private static final UUID PROJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID IMAGE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private MockRestServiceServer server;

    private ContentClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://content-api");
        MockServerRestClientCustomizer customizer = new MockServerRestClientCustomizer();
        customizer.customize(builder);
        server = customizer.getServer();
        client = new ContentClient(builder.build());
    }

    @Test
    void createImageUploadPostsJsonToTheProjectPath() {
        server.expect(requestTo("http://content-api/projects/" + PROJECT + "/images"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"fileName\":\"cover.png\",\"contentType\":\"image/png\",\"sizeBytes\":1234}"))
                .andRespond(withCreatedEntity(URI.create("/projects/" + PROJECT + "/images/" + IMAGE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"imageId\":\"" + IMAGE + "\"}"));

        ResponseEntity<Map<String, Object>> response = client.createImageUpload(PROJECT, Map.of(
                "fileName", "cover.png", "contentType", "image/png", "sizeBytes", 1234));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("imageId", IMAGE.toString());
        server.verify();
    }

    @Test
    void errorStatusesAreReturnedRatherThanThrown() {
        server.expect(requestTo("http://content-api/projects/" + PROJECT + "/images/" + IMAGE + "/complete"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"object_not_uploaded\"}"));

        ResponseEntity<Map<String, Object>> response = client.completeImageUpload(PROJECT, IMAGE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("error", "object_not_uploaded");
        server.verify();
    }

    @Test
    void getImageUsesTheImagePath() {
        server.expect(requestTo("http://content-api/projects/" + PROJECT + "/images/" + IMAGE))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"status\":\"COMMITTED\"}", MediaType.APPLICATION_JSON));

        assertThat(client.getImage(PROJECT, IMAGE).getBody()).containsEntry("status", "COMMITTED");
        server.verify();
    }

    @Test
    void transportFailureIsWrappedWithTheUpstreamName() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://127.0.0.1:1");
        ContentClient unreachable = new ContentClient(builder.build());

        assertThatExceptionOfType(UpstreamException.class)
                .isThrownBy(() -> unreachable.getImage(PROJECT, IMAGE))
                .satisfies(exception -> assertThat(exception.getUpstream()).isEqualTo("content"));
    }
}
