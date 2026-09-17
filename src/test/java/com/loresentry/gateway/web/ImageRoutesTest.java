package com.loresentry.gateway.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import com.loresentry.gateway.client.ContentClient;
import com.loresentry.gateway.client.UpstreamException;
import com.loresentry.gateway.config.CorsProperties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ImageController.class)
@EnableConfigurationProperties(CorsProperties.class)
class ImageRoutesTest {

    private static final UUID PROJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID IMAGE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ContentClient contentClient;

    @Test
    void createUploadForwardsBodyAndPreservesUpstreamStatus() throws Exception {
        given(contentClient.createImageUpload(eq(PROJECT), eq(Map.of(
                "fileName", "cover.png", "contentType", "image/png", "sizeBytes", 1234))))
                .willReturn(ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                        "imageId", IMAGE.toString(),
                        "uploadUrl", "https://bucket.s3.amazonaws.com/key?sig")));

        mockMvc.perform(post("/projects/{projectId}/images", PROJECT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"cover.png\",\"contentType\":\"image/png\",\"sizeBytes\":1234}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.imageId").value(IMAGE.toString()))
                .andExpect(jsonPath("$.uploadUrl").value("https://bucket.s3.amazonaws.com/key?sig"));
    }

    @Test
    void upstreamErrorBodyAndStatusPassThrough() throws Exception {
        given(contentClient.completeImageUpload(PROJECT, IMAGE))
                .willReturn(ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "error", "object_not_uploaded")));

        mockMvc.perform(post("/projects/{projectId}/images/{imageId}/complete", PROJECT, IMAGE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("object_not_uploaded"));
    }

    @Test
    void getImageForwardsToContent() throws Exception {
        given(contentClient.getImage(PROJECT, IMAGE))
                .willReturn(ResponseEntity.ok(Map.of("imageId", IMAGE.toString(), "status", "COMMITTED")));

        mockMvc.perform(get("/projects/{projectId}/images/{imageId}", PROJECT, IMAGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"));
    }

    @Test
    void transportFailureBecomesBadGateway() throws Exception {
        given(contentClient.getImage(PROJECT, IMAGE))
                .willThrow(new UpstreamException("content", "content call failed", new RuntimeException()));

        mockMvc.perform(get("/projects/{projectId}/images/{imageId}", PROJECT, IMAGE))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("upstream_unavailable"))
                .andExpect(jsonPath("$.upstream").value("content"));
    }

    @Test
    void nonUuidProjectIdIsRejectedBeforeReachingContent() throws Exception {
        mockMvc.perform(get("/projects/{projectId}/images/{imageId}", "not-a-uuid", IMAGE))
                .andExpect(status().isBadRequest());
    }
}
