package com.dongholab.pagetuner.server

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.Customizer.withDefaults
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain

@Configuration
class ServerSecurity {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain = http
        .authorizeHttpRequests { it.requestMatchers("/actuator/health").permitAll().anyRequest().authenticated() }
        .httpBasic(withDefaults())
        // Keep CSRF protection enabled, including for browser-cached Basic credentials.
        .build()
}
