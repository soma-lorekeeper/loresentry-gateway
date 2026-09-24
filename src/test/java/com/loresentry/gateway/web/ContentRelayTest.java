package com.loresentry.gateway.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.loresentry.gateway.client.AiChatClient;
import com.loresentry.gateway.client.AuthenticationClient;
import com.loresentry.gateway.client.ContentClient;
import com.loresentry.gateway.client.GraphRagClient;
import com.loresentry.gateway.client.UpstreamClient;
import com.loresentry.gateway.client.UpstreamException;
import com.loresentry.gateway.config.CorsProperties;
import com.loresentry.gateway.identity.ClientHeaderIdentityResolver;
import com.loresentry.gateway.identity.CurrentUserArgumentResolver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest
@Import({ UpstreamRelay.class, ClientHeaderIdentityResolver.class, CurrentUserArgumentResolver.class })
@EnableConfigurationProperties(CorsProperties.class)
class ContentRelayTest {

    private static final UUID USER = UUID.fromString("0199a3f2-8c41-7c2a-9f3d-2b7e1c4a5d60");

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

    private static ResponseEntity<byte[]> ok(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(body.getBytes(StandardCharsets.UTF_8), headers,
                org.springframework.http.HttpStatus.OK);
    }

    @Test
    void relaysTheProjectListVerbatim() throws Exception {
        given(contentClient.forward(any(), any(), any(), any(), any(), any()))
                .willReturn(ok("{\"projects\":[]}"));

        mockMvc.perform(get("/projects").header(ClientHeaderIdentityResolver.HEADER, USER))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"projects\":[]}"));

        // 경로를 다시 쓰지 않는다. 공개 /projects 가 content 의 /projects 다.
        verify(contentClient).forward(eq(HttpMethod.GET), eq("/projects"), eq(null), any(), any(), eq(USER));
    }

    @Test
    void relaysNestedPathsAndQueryStrings() throws Exception {
        given(contentClient.forward(any(), any(), any(), any(), any(), any())).willReturn(ok("{}"));

        // 서블릿이 주는 쿼리는 이미 인코딩된 원문이다. 그대로 넘겨야 이중 인코딩이 나지 않는다.
        // URL 문자열 대신 URI 를 넘긴다 — MockMvc 의 URL 템플릿 처리가 % 를 다시 인코딩한다.
        mockMvc.perform(get(java.net.URI.create("/projects/abc/search?q=%EC%9C%A0%EB%A6%AC"))
                        .header(ClientHeaderIdentityResolver.HEADER, USER))
                .andExpect(status().isOk());

        verify(contentClient).forward(eq(HttpMethod.GET), eq("/projects/abc/search"),
                eq("q=%EC%9C%A0%EB%A6%AC"), any(), any(), eq(USER));
    }

    @Test
    void relaysEveryMethodTheDomainUses() throws Exception {
        given(contentClient.forward(any(), any(), any(), any(), any(), any())).willReturn(ok("{}"));

        mockMvc.perform(post("/projects").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}")
                        .header(ClientHeaderIdentityResolver.HEADER, USER))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/projects/abc").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header(ClientHeaderIdentityResolver.HEADER, USER))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/projects/abc").header(ClientHeaderIdentityResolver.HEADER, USER))
                .andExpect(status().isOk());

        verify(contentClient).forward(eq(HttpMethod.POST), eq("/projects"), any(), any(), any(), eq(USER));
        verify(contentClient).forward(eq(HttpMethod.PATCH), eq("/projects/abc"), any(), any(), any(), eq(USER));
        verify(contentClient).forward(eq(HttpMethod.DELETE), eq("/projects/abc"), any(), any(), any(), eq(USER));
    }

    @Test
    void ownsTheFileAndEpisodeNamespacesToo() throws Exception {
        given(contentClient.forward(any(), any(), any(), any(), any(), any())).willReturn(ok("{}"));

        mockMvc.perform(get("/files/abc").header(ClientHeaderIdentityResolver.HEADER, USER))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/episodes/abc").header(ClientHeaderIdentityResolver.HEADER, USER))
                .andExpect(status().isOk());

        verify(contentClient).forward(eq(HttpMethod.GET), eq("/files/abc"), any(), any(), any(), eq(USER));
        verify(contentClient).forward(eq(HttpMethod.DELETE), eq("/episodes/abc"), any(), any(), any(), eq(USER));
    }

    @Test
    void keepsContentHealthEndpointsOffThePublicSurface() throws Exception {
        // 네임스페이스 단위 선언이라 content 의 /health 와 / 는 공개되지 않는다.
        // gateway 의 /health 는 gateway 자신의 것이고 content 로 가지 않는다.
        mockMvc.perform(get("/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        verify(contentClient, never()).forward(any(), any(), any(), any(), any(), any());
    }

    @Test
    void demandsAnIdentityItCanUse() throws Exception {
        mockMvc.perform(get("/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USER_CONTEXT_REQUIRED"))
                .andExpect(jsonPath("$.next_action").value("RELOGIN"));

        mockMvc.perform(get("/projects").header(ClientHeaderIdentityResolver.HEADER, "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(get("/projects")
                        .header(ClientHeaderIdentityResolver.HEADER, USER.toString())
                        .header(ClientHeaderIdentityResolver.HEADER, UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verify(contentClient, never()).forward(any(), any(), any(), any(), any(), any());
    }

    @Test
    void passesUpstreamStatusAndBodyStraightThrough() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.LOCATION, "/projects/new");
        given(contentClient.forward(any(), any(), any(), any(), any(), any()))
                .willReturn(new ResponseEntity<>("{\"code\":\"PROJECT_NAME_TAKEN\"}".getBytes(StandardCharsets.UTF_8),
                        headers, org.springframework.http.HttpStatus.CONFLICT));

        mockMvc.perform(post("/projects").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header(ClientHeaderIdentityResolver.HEADER, USER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_NAME_TAKEN"))
                .andExpect(header().string(HttpHeaders.LOCATION, "/projects/new"));
    }

    @Test
    void reportsAnUnreachableUpstreamAsItsOwnFailure() throws Exception {
        given(contentClient.forward(any(), any(), any(), any(), any(), any()))
                .willThrow(new UpstreamException("content", "content call failed", new RuntimeException()));

        mockMvc.perform(get("/projects").header(ClientHeaderIdentityResolver.HEADER, USER))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"))
                .andExpect(jsonPath("$.upstream").value("content"));
    }

    @Test
    void pinsTheIdentityHeaderNameToWhatTheServicesRead() {
        org.assertj.core.api.Assertions.assertThat(ClientHeaderIdentityResolver.HEADER)
                .isEqualTo("X-User-Id")
                .isEqualTo(UpstreamClient.USER_ID_HEADER);
    }
}
