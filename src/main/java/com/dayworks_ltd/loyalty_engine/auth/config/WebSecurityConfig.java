package com.dayworks_ltd.loyalty_engine.auth.config;


import com.dayworks_ltd.loyalty_engine.auth.enums.UserRole;
import com.dayworks_ltd.loyalty_engine.auth.services.CustomUserDetailsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class WebSecurityConfig {

    @Autowired
    private CustomUserDetailsService userDetailsService;

    @Autowired
    private JWTAuthenticationFilter filter;

    //filter requests
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity) throws Exception {
        httpSecurity
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(request -> request
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/v1/login").permitAll()
                        .requestMatchers("/api/v1/swagger-ui/**").permitAll()


                        // Payments
                        .requestMatchers("/api/v1/payments/intiate-payment")
                        .hasAnyRole(UserRole.ADMIN.name(), UserRole.LEAD_COLLECTOR.name())
                        .requestMatchers("/api/v1/payments/confirm-payment")
                        .hasAnyRole(UserRole.ADMIN.name(), UserRole.LEAD_COLLECTOR.name())
                        .requestMatchers("/api/v1/payments/**").permitAll() // M-Pesa callback

                        // Users & Leads
                        .requestMatchers("/api/v1/user/**").hasRole(UserRole.ADMIN.name())
                        .requestMatchers("/api/v1/leads/**")
                        .hasAnyRole(UserRole.ADMIN.name(), UserRole.LEAD_COLLECTOR.name())

                        // Merchants
                        .requestMatchers(HttpMethod.GET, "/api/v1/merchants/{id}", "/api/v1/merchants/till/{tillNumber}")
                        .hasAnyRole(UserRole.ADMIN.name(), UserRole.MERCHANT.name(), UserRole.SALES_PERSON.name())
                        .requestMatchers(HttpMethod.PUT, "/api/v1/merchants/{id}")
                        .hasAnyRole(UserRole.ADMIN.name(), UserRole.MERCHANT.name())
                        .requestMatchers("/api/v1/merchants/**").hasRole(UserRole.ADMIN.name())

                        // Campaigns & Inventory
                        .requestMatchers("/api/v1/campaigns/**")
                        .hasAnyRole(UserRole.ADMIN.name(), UserRole.MERCHANT.name(), UserRole.SALES_PERSON.name())
                        .requestMatchers("/api/v1/inventory/**")
                        .hasAnyRole(UserRole.ADMIN.name(), UserRole.MERCHANT.name(), UserRole.SALES_PERSON.name())
                        .requestMatchers("/api/v1/invoices/kra/**").permitAll()
                        .requestMatchers("/api/v1/invoices/**")
                          // ← Add this line
                        .hasAnyRole(UserRole.ADMIN.name(), UserRole.MERCHANT.name())

                        // === NEW: ORDER MANAGEMENT ===
                        .requestMatchers("/api/orders/**")
                        .hasAnyRole(UserRole.ADMIN.name(), UserRole.MERCHANT.name(), UserRole.SALES_PERSON.name())


                        // CREDIT ENGINE – THIS IS THE LINE YOU WERE MISSING
                        .requestMatchers("/api/v1/credit/**")
                        .hasAnyRole(UserRole.ADMIN.name(), UserRole.MERCHANT.name())

                        // Everything else → must be authenticated
                        .anyRequest().authenticated()
                )
                .httpBasic(Customizer.withDefaults())
                .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class);

        return httpSecurity.build();
    }
    @Bean
    public BCryptPasswordEncoder bCryptPasswordEncoder()
    {
        return new BCryptPasswordEncoder(14);
    }

    @Bean
    public AuthenticationProvider authenticationProvider()
    {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);

        provider.setPasswordEncoder(bCryptPasswordEncoder());

        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception
    {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource()
    {
        CorsConfiguration config = new CorsConfiguration();

        config.setAllowedOrigins(List.of(
                "http://38.242.155.236",          // production frontend (port 80 – this is the one that was missing)
                "http://38.242.155.236:8080",
                // if you serve frontend on 8080 sometimes
                "http://localhost:3000",
                "http://localhost:3001",
                "http://localhost:8080",
                "https://tribessystems.co.ke"
        ));

//        config.setAllowedOrigins(List.of("http://localhost:3000"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource corsConfig = new UrlBasedCorsConfigurationSource();
        corsConfig.registerCorsConfiguration("/**", config);

        return corsConfig;
    }
}
