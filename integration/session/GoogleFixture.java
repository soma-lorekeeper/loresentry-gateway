package com.loresentry.authentication.fixture;

import com.loresentry.authentication.adapter.out.google.GoogleOidcClient;
import com.loresentry.authentication.adapter.out.google.GoogleSettings;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.*;
import tools.jackson.databind.json.JsonMapper;

/** Test-only provider, copied into an isolated Auth source snapshot by run.py. */
@Configuration(proxyBeanMethods = false)
public class GoogleFixture {
    @Bean(destroyMethod = "close")
    Provider fixtureProvider() throws Exception { return new Provider(); }

    @Bean(destroyMethod = "close")
    @Primary
    GoogleOidcClient fixtureGoogleClient(GoogleSettings settings, Clock clock, Provider provider) {
        String base = "http://127.0.0.1:" + provider.server.getAddress().getPort();
        return new GoogleOidcClient(settings, clock, new GoogleOidcClient.Endpoints(
                "https://accounts.google.com/o/oauth2/v2/auth", base + "/token", base + "/jwks"));
    }

    static final class Provider implements AutoCloseable {
        final HttpServer server;
        final KeyPair keys;
        final String kid = UUID.randomUUID().toString();
        final JsonMapper json = new JsonMapper();

        Provider() throws Exception {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            keys = generator.generateKeyPair();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", request -> respond(request, 200, new JWKSet(
                    new RSAKey.Builder((RSAPublicKey) keys.getPublic()).keyID(kid)
                            .algorithm(JWSAlgorithm.RS256).build()).toString()));
            server.createContext("/token", request -> {
                try {
                    var form = form(new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    var code = json.readTree(Base64.getUrlDecoder().decode(form.get("code")));
                    var challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                            MessageDigest.getInstance("SHA-256").digest(
                                    form.get("code_verifier").getBytes(StandardCharsets.US_ASCII)));
                    if (!challenge.equals(code.get("challenge").asString())) throw new IllegalArgumentException();
                    var now = Instant.now();
                    var claims = new JWTClaimsSet.Builder().issuer("https://accounts.google.com")
                            .audience("test-client").subject(code.get("subject").asString())
                            .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300)))
                            .claim("nonce", code.get("nonce").asString()).claim("name", "Integration User")
                            .claim("email", "integration@example.test").build();
                    var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(), claims);
                    token.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
                    respond(request, 200, json.writeValueAsString(Map.of("access_token", "fixture-only",
                            "token_type", "Bearer", "expires_in", 300, "id_token", token.serialize())));
                } catch (Exception invalid) {
                    respond(request, 400, "{\"error\":\"invalid_grant\"}");
                }
            });
            server.start();
        }
        static Map<String, String> form(String raw) {
            var fields = new HashMap<String, String>();
            for (var part : raw.split("&")) {
                var pair = part.split("=", 2);
                fields.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            return fields;
        }
        static void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
            finally { exchange.close(); }
        }
        public void close() { server.stop(0); }
    }
}
