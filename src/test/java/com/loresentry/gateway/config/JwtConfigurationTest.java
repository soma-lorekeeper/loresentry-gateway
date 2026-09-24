package com.loresentry.gateway.config;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.loresentry.gateway.security.JwtTestTokens;

class JwtConfigurationTest {
    @TempDir Path directory;
    Path publicKey(int bits) throws Exception {
        var path=directory.resolve("rsa-"+bits+".pem");
        Files.writeString(path,"-----BEGIN PUBLIC KEY-----\n"+Base64.getEncoder().encodeToString(JwtTestTokens.keys(bits).getPublic().getEncoded())+"\n-----END PUBLIC KEY-----");
        return path;
    }
    @Test void acceptsStrongPublicKeyAndRejectsMissingMalformedWeakOrPrivateKey() throws Exception {
        assertThat(JwtConfiguration.loadPublicKey(publicKey(2048).toString()).getModulus().bitLength()).isEqualTo(2048);
        var weak=publicKey(1024);
        assertThatThrownBy(()->JwtConfiguration.loadPublicKey(weak.toString())).hasMessage("Invalid JWT public key configuration");
        assertThatThrownBy(()->JwtConfiguration.loadPublicKey(directory.resolve("missing").toString())).isInstanceOf(IllegalStateException.class);
        var invalid=directory.resolve("invalid.pem");Files.writeString(invalid,"not a key");
        assertThatThrownBy(()->JwtConfiguration.loadPublicKey(invalid.toString())).isInstanceOf(IllegalStateException.class);
        Files.writeString(invalid,"-----BEGIN PRIVATE KEY-----\nredacted\n-----END PRIVATE KEY-----");
        assertThatThrownBy(()->JwtConfiguration.loadPublicKey(invalid.toString())).isInstanceOf(IllegalStateException.class);
    }
}
