package com.dongholab.pagetuner.server

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
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
        .authorizeHttpRequests {
            it.requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**", "/icon.svg", "/manifest.webmanifest", "/sw.js")
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
        .httpBasic(withDefaults())
        .addFilterAfter(ApiRequestBodyLimit(), BasicAuthenticationFilter::class.java)
        .also { security -> attempts.ifAvailable { security.addFilterBefore(AccountLoginGuard(it), BasicAuthenticationFilter::class.java) } }
        // Keep CSRF protection enabled, including for browser-cached Basic credentials.
        .build()
}
