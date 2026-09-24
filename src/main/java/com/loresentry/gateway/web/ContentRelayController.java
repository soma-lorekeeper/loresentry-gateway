package com.loresentry.gateway.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.loresentry.gateway.client.ContentClient;
import com.loresentry.gateway.identity.CurrentUser;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * content 서비스로 가는 중계다. 경로를 <b>다시 쓰지 않는다</b> — 공개 {@code /projects}가 content의
 * {@code /projects}다. 매핑 표를 유지할 일이 없고, 프론트엔드가 보는 경로가 API 문서의 경로와 같다.
 *
 * <p><b>왜 라우트 단위가 아니라 네임스페이스 단위인가.</b> 지금 프론트엔드가 필요한 content 엔드포인트가
 * 스무 개를 넘고, 그중 여러 서비스의 응답을 합쳐야 하는 것은 <b>하나도 없다.</b> 라우트마다 메서드를
 * 하나씩 두면 content에 엔드포인트를 더할 때마다 gateway에도 같은 내용을 옮겨 적어야 하고, 빠뜨리면
 * 조용히 404가 된다.
 *
 * <p>그래도 {@code /**} 하나로 아무 경로나 흘리지는 않는다. 각 매핑이 <b>어느 서비스가 어느 네임스페이스를
 * 소유하는지 명시</b>하므로, content의 {@code /health}·{@code /health/db}·{@code /}는 공개되지 않고
 * 신원을 싣는 자리도 그대로 남는다. 나중에 조합이 필요한 경로가 생기면 그 경로만 더 구체적인 매핑으로
 * 선언하면 된다 — Spring이 더 구체적인 패턴을 먼저 고른다.
 */
@RestController
public class ContentRelayController {

    /**
     * 업스트림에 넘길 요청 헤더. 허용 목록이다 — 통째로 넘기면 클라이언트가 {@code X-User-Id}를
     * 끼워 넣을 수 있고, 인증이 붙은 뒤에는 gateway가 소비해야 할 {@code Authorization}·
     * {@code Cookie}까지 도메인 서비스로 새어 나간다.
     *
     * <p>여기 있는 것은 전부 API 계약의 일부다. {@code If-Match}는 문서 저장의 조건부 갱신 토큰이고
     * {@code X-Save-Id}는 저장 재시도를 알아보는 멱등 키다. 빠뜨리면 content가 조건을 받지 못해
     * 모든 저장이 거절된다.
     */
    private static final List<String> FORWARDED_HEADERS = List.of("If-Match", "If-None-Match", "X-Save-Id");

    private final ContentClient content;

    public ContentRelayController(ContentClient content) {
        this.content = content;
    }

    @RequestMapping(path = { "/projects", "/projects/**" }, method = {
        RequestMethod.GET, RequestMethod.POST, RequestMethod.PATCH,
        RequestMethod.PUT, RequestMethod.DELETE })
    public ResponseEntity<byte[]> projects(
            @CurrentUser UUID userId,
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body) {
        return relay(userId, request, body);
    }

    @RequestMapping(path = { "/files", "/files/**" }, method = {
        RequestMethod.GET, RequestMethod.POST, RequestMethod.PATCH,
        RequestMethod.PUT, RequestMethod.DELETE })
    public ResponseEntity<byte[]> files(
            @CurrentUser UUID userId,
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body) {
        return relay(userId, request, body);
    }

    @RequestMapping(path = { "/episodes", "/episodes/**" }, method = {
        RequestMethod.GET, RequestMethod.POST, RequestMethod.PATCH,
        RequestMethod.PUT, RequestMethod.DELETE })
    public ResponseEntity<byte[]> episodes(
            @CurrentUser UUID userId,
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body) {
        return relay(userId, request, body);
    }

    private ResponseEntity<byte[]> relay(UUID userId, HttpServletRequest request, byte[] body) {
        String path = request.getRequestURI();
        // 중괄호는 RestClient가 URI 템플릿 변수로 읽는다. 그런 경로를 가진 리소스가 없으므로 거절한다.
        if (path.indexOf('{') >= 0 || path.indexOf('}') >= 0) {
            throw new GatewayFailure(GatewayFailure.Reason.INVALID_REQUEST);
        }

        MediaType contentType = null;
        if (request.getContentType() != null) {
            contentType = MediaType.parseMediaType(request.getContentType());
        }

        return content.forward(
                HttpMethod.valueOf(request.getMethod()),
                path,
                request.getQueryString(),
                body,
                contentType,
                userId,
                forwardedHeadersOf(request));
    }

    private static Map<String, String> forwardedHeadersOf(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : FORWARDED_HEADERS) {
            String value = request.getHeader(name);
            if (value != null) {
                headers.put(name, value);
            }
        }
        return headers;
    }
}
