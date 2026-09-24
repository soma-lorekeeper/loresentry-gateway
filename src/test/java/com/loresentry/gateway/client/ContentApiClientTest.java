package com.loresentry.gateway.client;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.util.UUID;
import com.loresentry.gateway.application.ContentService;
import com.loresentry.gateway.client.content.ContentApiClient;
import com.loresentry.gateway.client.content.ContentApiClient.Conditions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.test.MockServerRestClientCustomizer;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

class ContentApiClientTest {
    @Test void encodesSearchExactlyOnceAndOnlyForwardsExplicitIdentity() {
        var builder = RestClient.builder().baseUrl("http://content.test");
        var mocks = new MockServerRestClientCustomizer();mocks.customize(builder);
        var server = mocks.getServer();
        var service = new ContentService(new ContentApiClient(builder.build()));
        var user = UUID.randomUUID();var project = UUID.randomUUID();
        server.expect(requestTo("http://content.test/projects/"+project+"/search?q=%EC%9C%A0%EB%A6%AC%20%26%20%2B%3F"))
            .andExpect(header("X-User-Id",user.toString())).andExpect(headerDoesNotExist("Cookie"))
            .andExpect(headerDoesNotExist("Authorization")).andExpect(header("If-None-Match","\"tag\""))
            .andRespond(withSuccess("{\"hits\":[]}",MediaType.APPLICATION_JSON));
        assertThat(service.search(user,project,"유리 & +?",new Conditions(null,null,"\"tag\"")).hits()).isEmpty();
        server.verify();
    }
}
