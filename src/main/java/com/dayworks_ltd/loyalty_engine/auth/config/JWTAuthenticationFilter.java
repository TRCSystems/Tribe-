package com.dayworks_ltd.loyalty_engine.auth.config;

import java.io.IOException;
import com.dayworks_ltd.loyalty_engine.auth.model.CustomUserDetails;
import com.dayworks_ltd.loyalty_engine.auth.services.CustomUserDetailsService;
import com.dayworks_ltd.loyalty_engine.auth.services.JWTService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class JWTAuthenticationFilter extends OncePerRequestFilter {

    @Autowired
    private JWTService jwtService;

    @Autowired
    private CustomUserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException
    {
        try{
            final String authHeader = request.getHeader("Authorization");

            System.out.println("=== JWT FILTER DEBUG ===");
            System.out.println("Request URI: " + request.getRequestURI());
            System.out.println("Auth Header: " + authHeader);

            if( authHeader == null || !authHeader.startsWith("Bearer ") )
            {
                System.out.println("No Bearer token found");
            } else {
                authenticateFromToken(request, authHeader);
            }
        } catch (Exception e) {
            // An expired or broken token means "not logged in". The request must still continue:
            // public pages (shop order links, webhooks) answer normally and protected ones reply 401.
            // (Stopping here used to send an empty 200 for every request carrying a stale token.)
            System.out.println(" Filter Exception: " + e.getMessage());
            Logger.getAnonymousLogger().log(Level.WARNING, "Ignoring unusable login token: " + e.getMessage());
            SecurityContextHolder.clearContext();
        }
        filterChain.doFilter(request, response);
    }

    private void authenticateFromToken(HttpServletRequest request, String authHeader) {
        {

            String jwt = authHeader.substring(7);
            String username = jwtService.extractUsername(jwt);

            System.out.println("Extracted username: " + username);

            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            System.out.println("Current authentication: " + authentication);

            if( username != null && authentication == null )
            {
                CustomUserDetails user = userDetailsService.loadUserByUsername(username);
                System.out.println("Loaded user: " + user.getUsername());

                if( jwtService.isTokenValid( jwt, user ) )  // ← This already checks expiration!
                {
                    System.out.println("✓ Token is VALID - Setting authentication");

                    UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                            user,
                            null,
                            user.getAuthorities()
                    );

                    token.setDetails(
                            new WebAuthenticationDetailsSource()
                                    .buildDetails(request)
                    );

                    SecurityContextHolder.getContext().setAuthentication(token);
                } else {
                    System.out.println("✗ Token is INVALID");
                }
            }
        }
    }
}
