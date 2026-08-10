package com.skyshift.cognitiveragengine.auth.filter;

import com.skyshift.cognitiveragengine.auth.jwt.JwtTokenProvider;
import com.skyshift.cognitiveragengine.user.model.AuthenticatedUser;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    private JwtTokenProvider jwtTokenProvider;
    private UserDetailsService userDetailsService;
    private JwtAuthenticationFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = mock(JwtTokenProvider.class);
        userDetailsService = mock(UserDetailsService.class);
        filter = new JwtAuthenticationFilter(jwtTokenProvider, userDetailsService);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        filterChain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private AuthenticatedUser user(String role, boolean enabled) {
        return new AuthenticatedUser(1L, 2L, "jsmith", "hashed-pw", role, enabled);
    }

    @Test
    void doFilter_validBearerToken_populatesContextWithFreshlyLoadedUser() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("jsmith");
        when(jwtTokenProvider.parseClaims("valid-token")).thenReturn(Optional.of(claims));
        AuthenticatedUser user = user("USER", true);
        when(userDetailsService.loadUserByUsername("jsmith")).thenReturn(user);

        filter.doFilter(request, response, filterChain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertEquals(user, authentication.getPrincipal());
        assertTrue(authentication.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilter_missingAuthorizationHeader_leavesContextEmptyAndContinuesChain() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilter(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilter_malformedHeader_treatedAsUnauthenticatedNot500() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("NotBearer garbage");

        filter.doFilter(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
        verify(jwtTokenProvider, never()).parseClaims(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void doFilter_expiredOrInvalidToken_contextNotPopulated() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer expired-token");
        when(jwtTokenProvider.parseClaims("expired-token")).thenReturn(Optional.empty());

        filter.doFilter(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilter_userDeletedSinceTokenIssuance_treatedAsUnauthenticatedNotException() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("ghost");
        when(jwtTokenProvider.parseClaims("valid-token")).thenReturn(Optional.of(claims));
        when(userDetailsService.loadUserByUsername("ghost"))
            .thenThrow(new UsernameNotFoundException("User not found: ghost"));

        filter.doFilter(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilter_disabledUser_contextNotPopulated() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("jsmith");
        when(jwtTokenProvider.parseClaims("valid-token")).thenReturn(Optional.of(claims));
        when(userDetailsService.loadUserByUsername("jsmith")).thenReturn(user("USER", false));

        filter.doFilter(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doFilter_roleChangedSinceIssuance_currentRoleReflected() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("jsmith");
        when(jwtTokenProvider.parseClaims("valid-token")).thenReturn(Optional.of(claims));
        when(userDetailsService.loadUserByUsername(eq("jsmith"))).thenReturn(user("ADMIN", true));

        filter.doFilter(request, response, filterChain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertTrue(authentication.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
        verify(filterChain).doFilter(request, response);
    }
}
