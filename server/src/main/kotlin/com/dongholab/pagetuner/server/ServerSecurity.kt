package com.dongholab.pagetuner.server

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.Customizer.withDefaults
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.savedrequest.NullRequestCache
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
class ServerSecurity {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain = http
        .cors(withDefaults())
        .authorizeHttpRequests {
            it.requestMatchers("/actuator/health", "/api/v1/csrf").permitAll()
                .anyRequest().authenticated()
        }
        .requestCache { it.requestCache(NullRequestCache()) }
        .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
        .httpBasic(withDefaults())
        .formLogin {
            it.loginProcessingUrl("/api/v1/session")
                .successHandler { _, response, _ -> response.status = 204 }
                .failureHandler { _, response, _ -> response.status = 401 }
        }
        .logout {
            it.logoutUrl("/api/v1/session/logout").deleteCookies("JSESSIONID")
                .logoutSuccessHandler { _, response, _ -> response.status = 204 }
        }
        // Login, logout, and all other mutations retain CSRF protection.
        .build()

    @Bean
    fun corsConfigurationSource(
        @Value("\${pagetuner.web.allowed-origins:http://localhost:3000,http://127.0.0.1:3000,http://localhost:5173,http://127.0.0.1:5173}") origins: String,
    ): CorsConfigurationSource {
        val allowed = origins.split(',').map(String::trim).filter(String::isNotEmpty)
        require(allowed.none { '*' in it }) { "CORS requires explicit frontend origins." }
        val config = CorsConfiguration().apply {
            allowedOrigins = allowed
            allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Content-Type", "X-CSRF-TOKEN", "Authorization")
            exposedHeaders = listOf("Content-Disposition")
            allowCredentials = true
            maxAge = 3600
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/api/**", config) }
    }
}
