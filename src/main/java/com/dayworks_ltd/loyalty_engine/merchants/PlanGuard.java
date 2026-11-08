package com.dayworks_ltd.loyalty_engine.merchants;

import com.dayworks_ltd.loyalty_engine.auth.model.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static com.dayworks_ltd.loyalty_engine.merchants.MerchantModule.*;

/**
 * Keeps merchants out of the modules they did not buy. The portal hides those modules too; this is the
 * server-side check, so a hand-made request gets a 403 as well.
 * <p>
 * Each API area needs at least one of its modules. Areas not listed (login, profile, the public buyer shop
 * page, webhooks) are open. Admins and anonymous requests are not affected.
 */
@Configuration
public class PlanGuard implements WebMvcConfigurer, HandlerInterceptor {

    /** API area to the modules that use it (any one is enough). Longest prefixes first. */
    static final Map<String, Set<MerchantModule>> AREAS = new LinkedHashMap<>();

    static {
        // stock, prices, stock count, POS sales, daily/weekly reports, expenses all live under /inventory
        AREAS.put("/api/v1/inventory", Set.of(POS, INVENTORY, REPORTS, FINANCE));
        AREAS.put("/api/stock-transfers", Set.of(INVENTORY));
        AREAS.put("/api/v1/orders", Set.of(ORDERS, POS));
        AREAS.put("/api/v1/shop-order", Set.of(ORDERS));
        AREAS.put("/api/v1/payments", Set.of(POS, ORDERS));
        AREAS.put("/api/v1/invoices", Set.of(ORDERS, FINANCE));
        AREAS.put("/api/v1/crm", Set.of(CUSTOMERS));
        AREAS.put("/api/v1/leads", Set.of(CUSTOMERS));
        AREAS.put("/api/v1/campaigns", Set.of(CUSTOMERS));
        AREAS.put("/api/v1/campaign-templates", Set.of(CUSTOMERS));
        AREAS.put("/api/v1/credit-limits", Set.of(FINANCE));
        AREAS.put("/api/v1/credit", Set.of(FINANCE));
        AREAS.put("/api/v1/smm/webhook", Set.of()); // Meta calls this; never blocked
        AREAS.put("/api/v1/smm", Set.of(SOKOREPLY));
    }

    static final String MESSAGE = "This part of Tribe is not in your plan";

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!blocked(modulesOfCurrentUser(), request.getRequestURI())) return true;
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"status\":\"403\",\"message\":\"" + MESSAGE + "\"}");
        return false;
    }

    /** True when a merchant with these modules may not call this path. Null modules (admins, anonymous) are never blocked. */
    static boolean blocked(Collection<String> modules, String path) {
        if (modules == null || path == null) return false;
        for (Map.Entry<String, Set<MerchantModule>> area : AREAS.entrySet()) {
            String p = area.getKey();
            if (path.equals(p) || path.startsWith(p + "/")) {
                if (area.getValue().isEmpty()) return false;
                return area.getValue().stream().noneMatch(m -> modules.contains(m.name()));
            }
        }
        return false;
    }

    private static Collection<String> modulesOfCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof CustomUserDetails user ? user.getModules() : null;
    }
}
