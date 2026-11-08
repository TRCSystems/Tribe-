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

import java.util.List;

/**
 * Keeps SokoReply-only merchants out of the shop modules they did not buy. The portal hides those
 * modules too; this is the server-side check, so a hand-made request gets a 403 as well.
 * <p>
 * It is a deny-list of the shop modules' APIs, so shared endpoints (login, profile, SokoReply, the public
 * buyer shop page) keep working. Signed-in FULL merchants, admins and anonymous requests are not affected.
 */
@Configuration
public class PlanGuard implements WebMvcConfigurer, HandlerInterceptor {

    /** API areas that belong to the shop system (POS, orders, inventory, customers, reports, finance). */
    static final List<String> SHOP_MODULES = List.of(
            "/api/v1/inventory",      // stock, prices, stock count, POS sales, daily/weekly reports, expenses
            "/api/stock-transfers",
            "/api/v1/orders",
            "/api/v1/shop-order",
            "/api/v1/crm",            // customers, shops, field reps
            "/api/v1/leads",
            "/api/v1/credit",
            "/api/v1/credit-limits",
            "/api/v1/invoices",
            "/api/v1/payments",
            "/api/v1/campaigns",
            "/api/v1/campaign-templates");

    static final String MESSAGE = "Your Tribe plan includes SokoReply only";

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!blocked(planOfCurrentUser(), request.getRequestURI())) return true;
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"status\":\"403\",\"message\":\"" + MESSAGE + "\"}");
        return false;
    }

    /** True when a merchant on this plan may not call this path. */
    static boolean blocked(String plan, String path) {
        if (!MerchantPlan.SOKOREPLY.name().equals(plan) || path == null) return false;
        return SHOP_MODULES.stream().anyMatch(m -> path.equals(m) || path.startsWith(m + "/"));
    }

    private static String planOfCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof CustomUserDetails user ? user.getPlan() : null;
    }
}
