package com.clockwise.colabService.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.web.server.ServerWebExchange
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler
import org.springframework.http.HttpStatus
import reactor.core.publisher.Mono
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.MediaType
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.reactive.CorsConfigurationSource
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource

private val logger = KotlinLogging.logger {}

@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
class SecurityConfig {

    @Value("\${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private lateinit var jwkSetUri: String

    @Bean
    fun springSecurityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        return http
            .csrf { it.disable() }
            .cors { cors -> cors.configurationSource(corsConfigurationSource()) }
            .authorizeExchange { exchanges ->
                exchanges
                    .pathMatchers("/actuator/**").permitAll()
                    .pathMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                    .pathMatchers(HttpMethod.OPTIONS).permitAll()
                    
                    // Marketplace endpoints - all authenticated users
                    .pathMatchers(HttpMethod.GET, "/v1/marketplace/shifts").hasAnyRole("admin", "manager", "employee")
                    .pathMatchers(HttpMethod.POST, "/v1/marketplace/shifts/**").hasAnyRole("admin", "manager", "employee")
                    .pathMatchers(HttpMethod.DELETE, "/v1/marketplace/my-shifts/**").hasAnyRole("admin", "manager", "employee")
                    .pathMatchers(HttpMethod.GET, "/v1/marketplace/my-shifts").hasAnyRole("admin", "manager", "employee")
                    .pathMatchers(HttpMethod.GET, "/v1/marketplace/my-requests").hasAnyRole("admin", "manager", "employee")
                    .pathMatchers(HttpMethod.POST, "/v1/marketplace/shifts/*/requests").hasAnyRole("admin", "manager", "employee")
                    .pathMatchers(HttpMethod.GET, "/v1/marketplace/my-shifts/*/requests").hasAnyRole("admin", "manager", "employee")
                    .pathMatchers(HttpMethod.PUT, "/v1/marketplace/my-shifts/*/requests/*/accept").hasAnyRole("admin", "manager", "employee")
                    
                    // Manager approval endpoints - admin and manager only
                    .pathMatchers(HttpMethod.GET, "/v1/marketplace/manager/pending-approvals").hasAnyRole("admin", "manager")
                    .pathMatchers(HttpMethod.PUT, "/v1/marketplace/manager/requests/*/approve").hasAnyRole("admin", "manager")
                    .pathMatchers(HttpMethod.PUT, "/v1/marketplace/manager/requests/*/reject").hasAnyRole("admin", "manager")
                    
                    // All other requests require authentication
                    .anyExchange().authenticated()
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt ->
                    jwt.jwtDecoder(jwtDecoder())
                       .jwtAuthenticationConverter(ReactiveJwtAuthenticationConverterAdapter(jwtAuthenticationConverter()))
                }
            }
            .exceptionHandling { exceptions ->
                exceptions.accessDeniedHandler(accessDeniedHandler())
            }
            .build()
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            allowedOriginPatterns = listOf("*")
            allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
            allowedHeaders = listOf("*")
            allowCredentials = true
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", configuration)
        }
    }

    @Bean
    fun jwtDecoder(): ReactiveJwtDecoder {
        logger.info { "Configuring JWT decoder with JWK Set URI: $jwkSetUri" }
        return NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build()
    }

    @Bean
    fun jwtAuthenticationConverter(): JwtAuthenticationConverter {
        val converter = JwtAuthenticationConverter()
        converter.setJwtGrantedAuthoritiesConverter { jwt ->
            logger.debug("JWT Claims: ${jwt.claims}")
            logger.debug("JWT Subject: ${jwt.subject}")
            
            val realmAccess = jwt.getClaimAsMap("realm_access")
            val resourceAccess = jwt.getClaimAsMap("resource_access")
            
            logger.debug("Realm access: $realmAccess")
            logger.debug("Resource access: $resourceAccess")
            
            val authorities = mutableListOf<org.springframework.security.core.GrantedAuthority>()
            
            // Extract realm roles and add ROLE_ prefix
            realmAccess?.get("roles")?.let { roles ->
                if (roles is List<*>) {
                    logger.debug("Found realm roles: $roles")
                    roles.filterIsInstance<String>().forEach { role ->
                        authorities.add(org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_$role"))
                        authorities.add(org.springframework.security.core.authority.SimpleGrantedAuthority(role))
                        logger.debug("Added authorities for role '$role': ROLE_$role, $role")
                    }
                }
            }
            
            // Extract resource-specific roles and add ROLE_ prefix
            resourceAccess?.forEach { (resource, access) ->
                if (access is Map<*, *>) {
                    access["roles"]?.let { roles ->
                        if (roles is List<*>) {
                            roles.filterIsInstance<String>().forEach { role ->
                                when {
                                    resource == "auth-service" && role == "admin" -> {
                                        authorities.add(org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_admin"))
                                        authorities.add(org.springframework.security.core.authority.SimpleGrantedAuthority("admin"))
                                    }
                                    else -> {
                                        val resourceRole = "${resource}_${role}"
                                        authorities.add(org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_$resourceRole"))
                                        authorities.add(org.springframework.security.core.authority.SimpleGrantedAuthority(resourceRole))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            logger.debug("Final extracted authorities from JWT: ${authorities.map { it.authority }}")
            authorities
        }
        return converter
    }

    @Bean
    fun accessDeniedHandler(): ServerAccessDeniedHandler {
        return ServerAccessDeniedHandler { exchange: ServerWebExchange, denied ->
            logger.warn { "Access denied: ${denied.message}" }
            
            val response = exchange.response
            response.statusCode = HttpStatus.FORBIDDEN
            response.headers.add("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            
            val errorMessage = """
                {
                    "status": 403,
                    "error": "Access Denied",
                    "message": "Insufficient permissions to access this resource",
                    "path": "${exchange.request.path.value()}"
                }
            """.trimIndent()
            
            val buffer: DataBuffer = response.bufferFactory().wrap(errorMessage.toByteArray())
            response.writeWith(Mono.just(buffer))
        }
    }
}