package com.skyshift.cognitiveragengine.auth.jwt;

import com.skyshift.cognitiveragengine.auth.config.JwtProperties;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokenProviderTest {

    private static final String SECRET = "test-jwt-secret-key-for-unit-tests-minimum-32-bytes-long";

    private JwtTokenProvider provider(long accessTokenTtlSeconds) {
        return new JwtTokenProvider(new JwtProperties(SECRET, accessTokenTtlSeconds, 604800));
    }

    @Test
    void issueAccessToken_hasValidSignatureAndThreeSegments() {
        JwtTokenProvider tokenProvider = provider(900);
        String token = tokenProvider.issueAccessToken("jsmith");

        assertEquals(3, token.split("\\.").length);
        assertTrue(tokenProvider.parseClaims(token).isPresent());
    }

    @Test
    void parseClaims_freshToken_yieldsSubjectAndExpiryMatchingTtl() {
        JwtTokenProvider tokenProvider = provider(900);
        Instant before = Instant.now();
        String token = tokenProvider.issueAccessToken("jsmith");

        Claims claims = tokenProvider.parseClaims(token).orElseThrow();

        assertEquals("jsmith", claims.getSubject());
        long expectedExpiryEpoch = before.plusSeconds(900).getEpochSecond();
        long actualExpiryEpoch = claims.getExpiration().toInstant().getEpochSecond();
        assertTrue(Math.abs(expectedExpiryEpoch - actualExpiryEpoch) <= 2,
            "expected exp near " + expectedExpiryEpoch + " but was " + actualExpiryEpoch);
    }

    @Test
    void parseClaims_tokenSignedWithDifferentSecret_rejected() {
        JwtTokenProvider tokenProvider = provider(900);
        JwtTokenProvider otherProvider = new JwtTokenProvider(
            new JwtProperties("a-completely-different-secret-key-also-32-bytes-plus", 900, 604800));
        String token = otherProvider.issueAccessToken("jsmith");

        Optional<Claims> claims = tokenProvider.parseClaims(token);

        assertFalse(claims.isPresent());
    }

    @Test
    void parseClaims_expiredToken_rejected() {
        JwtTokenProvider tokenProvider = provider(-10);
        String token = tokenProvider.issueAccessToken("jsmith");

        Optional<Claims> claims = tokenProvider.parseClaims(token);

        assertFalse(claims.isPresent());
    }

    @Test
    void parseClaims_malformedToken_rejectedWithoutUnhandledException() {
        JwtTokenProvider tokenProvider = provider(900);

        Optional<Claims> claims = tokenProvider.parseClaims("not-a-real-jwt");

        assertFalse(claims.isPresent());
    }
}
