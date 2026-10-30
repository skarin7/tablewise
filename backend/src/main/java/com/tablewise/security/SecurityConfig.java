package com.tablewise.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain chain(HttpSecurity http, StaffAuthService auth) throws Exception {
        Converter<Jwt, AbstractAuthenticationToken> toStaff = jwt -> auth.authenticate(jwt, currentRequest().getHeader("X-Outlet-Id"));
        return http.csrf(c -> c.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/ws/**", "/webhooks/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/devices/pairing-codes").permitAll()
                        .requestMatchers(HttpMethod.GET, "/devices/pairing-codes/*").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(toStaff)))
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> res.sendError(HttpStatus.UNAUTHORIZED.value())))
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${tablewise.allowed-origins}") String origins) {
        var c = new CorsConfiguration();
        c.setAllowedOriginPatterns(origins(origins));
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("*"));
        var src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", c);
        return src;
    }

    /** Patterns allowed to call the API and open sockets, e.g. https://app.vercel.app, https://*.vercel.app. */
    public static List<String> origins(String csv) { return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList(); }

    @Bean
    @Profile("!local")
    JwtDecoder jwtDecoder(@Value("${tablewise.supabase.jwks-url}") String jwks, @Value("${tablewise.supabase.issuer}") String issuer) {
        var d = NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithms(a -> { a.add(SignatureAlgorithm.ES256); a.add(SignatureAlgorithm.RS256); }).build();
        d.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer)));
        return d;
    }

    /** Local development only: the bearer token "dev-<auth user uuid>" signs you in as that user. Never enable on Render. */
    @Bean
    @Profile("local")
    JwtDecoder devDecoder() {
        return token -> {
            if (!token.startsWith("dev-")) throw new BadJwtException("Use dev-<user uuid> with the local profile");
            return Jwt.withTokenValue(token).header("alg", "none").subject(token.substring(4)).build();
        };
    }

    private static HttpServletRequest currentRequest() {
        return ((org.springframework.web.context.request.ServletRequestAttributes)
                org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes()).getRequest();
    }
}
