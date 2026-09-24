package com.loresentry.gateway.web;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import com.loresentry.gateway.application.ProbeService;
import com.loresentry.gateway.application.ContentService;

import com.loresentry.gateway.client.AiChatClient;
import com.loresentry.gateway.client.AuthenticationClient;
import com.loresentry.gateway.client.ContentClient;
import com.loresentry.gateway.client.GraphRagClient;
import com.loresentry.gateway.client.UpstreamException;
import com.loresentry.gateway.config.BrowserProperties;
import com.loresentry.gateway.security.CurrentUserArgumentResolver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@com.loresentry.gateway.LocalTestEnvironment
@WebMvcTest
@Import({ ProbeService.class, CurrentUserArgumentResolver.class })
@EnableConfigurationProperties(BrowserProperties.class)
class UpstreamRoutesTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GraphRagClient graphRagClient;

    @MockitoBean
    private AiChatClient aiChatClient;

    @MockitoBean
    private AuthenticationClient authenticationClient;

    @MockitoBean
    private ContentClient contentClient;

    @MockitoBean
    private ContentService contentService;

    @Test
    void graphRoutesToGraphRag() throws Exception {
        given(graphRagClient.name()).willReturn("graph-rag");
        given(graphRagClient.describe()).willReturn(Map.of("service", "graph-rag-api"));

        mockMvc.perform(get("/graph"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("gateway-api"))
                .andExpect(jsonPath("$.upstream.graph-rag.service").value("graph-rag-api"));
    }

    @Test
    void aiChatRoutesToAiChat() throws Exception {
        given(aiChatClient.name()).willReturn("ai-chat");
        given(aiChatClient.describe()).willReturn(Map.of("service", "ai-chat-api"));

        mockMvc.perform(get("/ai-chat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("gateway-api"))
                .andExpect(jsonPath("$.upstream.ai-chat.service").value("ai-chat-api"));
    }

    @Test
    void authRoutesToAuthentication() throws Exception {
        given(authenticationClient.name()).willReturn("authentication");
        given(authenticationClient.describe()).willReturn(Map.of("service", "authentication-api"));

        mockMvc.perform(get("/auth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("gateway-api"))
                .andExpect(jsonPath("$.upstream.authentication.service").value("authentication-api"));
    }

    @Test
    void contentRoutesToContent() throws Exception {
        given(contentClient.name()).willReturn("content");
        given(contentClient.describe()).willReturn(Map.of("service", "content-api"));

        mockMvc.perform(get("/content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("gateway-api"))
                .andExpect(jsonPath("$.upstream.content.service").value("content-api"));
    }

    @Test
    void upstreamFailureBecomesBadGateway() throws Exception {
        given(contentClient.name()).willReturn("content");
        given(contentClient.describe())
                .willThrow(new UpstreamException("content", "content call failed", new RuntimeException()));

        mockMvc.perform(get("/content"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"))
                .andExpect(jsonPath("$.next_action").value("RETRY_LATER"))
                .andExpect(jsonPath("$.upstream").value("content"));
    }
}
