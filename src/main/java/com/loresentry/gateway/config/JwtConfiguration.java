package com.loresentry.gateway.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import com.loresentry.gateway.security.AccessTokenVerifier;
import com.loresentry.gateway.security.AccessTokenFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods=false)
public class JwtConfiguration {
    public static RSAPublicKey loadPublicKey(String path) {
        try {
            var file=Path.of(path);
            if(Files.size(file)>16384) throw new IllegalArgumentException();
            String pem=Files.readString(file).strip();
            String begin="-----BEGIN PUBLIC KEY-----",end="-----END PUBLIC KEY-----";
            if(!pem.startsWith(begin)||!pem.endsWith(end)) throw new IllegalArgumentException();
            String value=pem.substring(begin.length(),pem.length()-end.length()).replaceAll("\\s","");
            var key=(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(value)));
            if(key.getModulus().bitLength()<2048) throw new IllegalArgumentException();
            return key;
        } catch(Exception invalid) { throw new IllegalStateException("Invalid JWT public key configuration"); }
    }
    @Bean
    public AccessTokenVerifier accessTokens(JwtProperties properties,Clock clock) {
        if(properties.keyId()==null || properties.keyId().isBlank()) throw new IllegalStateException("JWT key id is required");
        return new AccessTokenVerifier(loadPublicKey(properties.publicKey()),properties.keyId(),clock);
    }
}
