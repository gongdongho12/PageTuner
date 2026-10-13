package com.dongholab.pagetuner.server

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.savedrequest.NullRequestCache
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.security.config.Customizer.withDefaults
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.beans.factory.ObjectProvider
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter
import com.dongholab.pagetuner.server.account.AccountAttempts
import com.dongholab.pagetuner.server.account.AccountLoginGuard
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository
import org.springframework.security.config.http.SessionCreationPolicy

@Configuration
class ServerSecurity {
    @Bean
    fun securityFilterChain(http: HttpSecurity, attempts: ObjectProvider<AccountAttempts>): SecurityFilterChain = http
        .cors(withDefaults())
        .authorizeHttpRequests {
            it.requestMatchers(HttpMethod.GET, "/", "/index.html", "/sharing.html", "/assets/**", "/icon.svg", "/manifest.webmanifest", "/sw.js", "/fonts/OFL-NotoSerifKR.txt")
                .permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/accounts/csrf", "/api/v1/accounts/languages").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/accounts/register").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated()
        }
        // Every request must revalidate Basic credentials. Sessions carry CSRF tokens only, never
        // authentication that could keep an old password valid after a password change.
        .securityContext { it.securityContextRepository(RequestAttributeSecurityContextRepository()) }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .requestCache { it.requestCache(NullRequestCache()) }
        .exceptionHandling {
            it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                .accessDeniedHandler { _, response, _ ->
                    // CSRF runs before Basic authentication. sendError would dispatch to the protected
                    // /error endpoint without authentication and replace this denial with a 401.
                    response.status = HttpStatus.FORBIDDEN.value()
                    response.setHeader("Cache-Control", "no-store")
                }
        }
        .httpBasic(withDefaults())
        .addFilterAfter(ApiRequestBodyLimit(), BasicAuthenticationFilter::class.java)
        .also { security -> attempts.ifAvailable { security.addFilterBefore(AccountLoginGuard(it), BasicAuthenticationFilter::class.java) } }
        // Keep CSRF protection enabled, including for browser-cached Basic credentials.
        .build()
    @Bean
    fun corsConfigurationSource(
        @Value("\${pagetuner.web.allowed-origins:http://localhost:3000,http://127.0.0.1:3000,http://localhost:5173,http://127.0.0.1:5173}") origins: String,
    ): CorsConfigurationSource {
        val allowed = origins.split(',').map(String::trim).filter(String::isNotEmpty)
        require(allowed.none { '*' in it }) { "CORS requires explicit frontend origins." }
        val config = CorsConfiguration().apply {
            allowedOrigins = allowed
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Content-Type", "X-CSRF-TOKEN", "Authorization")
            exposedHeaders = listOf("Content-Disposition")
            allowCredentials = true
            maxAge = 3600
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/api/**", config) }
    }
}
