package com.dayworks_ltd.loyalty_engine.merchants;

import com.dayworks_ltd.loyalty_engine.auth.model.CustomUserDetails;
import com.dayworks_ltd.loyalty_engine.auth.model.User;
import com.dayworks_ltd.loyalty_engine.smm.setup.SmmSetupController;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Merchants see and call only the modules they bought; admins and anonymous callers are untouched. */
class PlanGuardTest {

    static final List<String> SOKO = List.of("SOKOREPLY");

    @Test
    void sokoReplyOnlyMerchantsAreKeptOutOfTheShopModules() {
        for (String path : new String[]{"/api/v1/inventory", "/api/v1/inventory/stock-levels/1", "/api/v1/orders/42",
                "/api/v1/crm/shops", "/api/stock-transfers", "/api/v1/campaigns", "/api/v1/shop-order/x", "/api/v1/credit-limits"}) {
            assertTrue(PlanGuard.blocked(SOKO, path), path);
        }
    }

    @Test
    void sokoReplyAndSharedEndpointsStayOpen() {
        for (String path : new String[]{"/api/v1/smm/comments", "/api/v1/smm/setup/catalogue", "/api/v1/login",
                "/api/v1/user/me", "/api/v1/inventoryx", "/api/v1/ordersheet"}) {
            assertFalse(PlanGuard.blocked(SOKO, path), path);
        }
    }

    @Test
    void eachModuleOpensItsOwnApis() {
        List<String> posOnly = List.of("POS");
        assertFalse(PlanGuard.blocked(posOnly, "/api/v1/inventory/sale"), "POS sells through the inventory API");
        assertFalse(PlanGuard.blocked(posOnly, "/api/v1/orders/1"));
        assertTrue(PlanGuard.blocked(posOnly, "/api/v1/crm/shops"));
        assertTrue(PlanGuard.blocked(posOnly, "/api/v1/smm/comments"), "SokoReply not bought");
        assertFalse(PlanGuard.blocked(posOnly, "/api/v1/smm/webhook"), "Meta's webhook is never blocked");
        assertFalse(PlanGuard.blocked(List.of("FINANCE"), "/api/v1/credit/score"));
    }

    @Test
    void adminsAndAnonymousAreNotAffected() {
        assertFalse(PlanGuard.blocked(null, "/api/v1/orders"));
        assertNull(new CustomUserDetails(new User()).getModules(), "admins have no merchant and no modules");
    }

    @Test
    void modulesComeFromTheChoiceOrElseFromThePlan() {
        Merchant old = new Merchant();
        assertEquals(EnumSet.allOf(MerchantModule.class), MerchantModule.of(old), "merchants from before modules have everything");

        Merchant sokoPlan = new Merchant();
        sokoPlan.setPlan(MerchantPlan.SOKOREPLY);
        assertEquals(EnumSet.of(MerchantModule.SOKOREPLY), MerchantModule.of(sokoPlan));

        Merchant chosen = new Merchant();
        chosen.setModules("pos, Orders,nonsense");
        assertEquals(EnumSet.of(MerchantModule.POS, MerchantModule.ORDERS), MerchantModule.of(chosen));

        assertEquals(MerchantPlan.SOKOREPLY, MerchantModule.planFor(EnumSet.of(MerchantModule.SOKOREPLY)));
        assertEquals(MerchantPlan.FULL, MerchantModule.planFor(EnumSet.of(MerchantModule.SOKOREPLY, MerchantModule.POS)));
        assertEquals("POS,SOKOREPLY", MerchantModule.join(EnumSet.of(MerchantModule.SOKOREPLY, MerchantModule.POS)));
        assertEquals(MerchantPlan.SOKOREPLY, MerchantPlan.parse(" sokoreply "));
    }

    @Test
    void merchantsWithoutInventoryKeepProductsInSokoReply() {
        Merchant soko = new Merchant();
        soko.setModules("SOKOREPLY,ORDERS");
        User seller = new User();
        seller.setMerchant(soko);
        assertTrue(SmmSetupController.ownsCatalogue(new CustomUserDetails(seller)));

        User shopOwner = new User();
        shopOwner.setMerchant(new Merchant());
        assertFalse(SmmSetupController.ownsCatalogue(new CustomUserDetails(shopOwner)), "merchants with Inventory use it");
    }

    @Test
    void eachColourAndSizeGetsItsOwnCode() {
        assertEquals("7-MAXIDRESS", SmmSetupController.newItemCode(7L, "Maxi dress", null, null));
        assertEquals("7-MAXIDRESS-NAVYBLUE-XL", SmmSetupController.newItemCode(7L, "Maxi dress!", "navy blue", "XL"));
        assertEquals(3, SmmSetupController.count("3"));
        assertNull(SmmSetupController.count("-1"));
        assertNull(SmmSetupController.count("2.5"));
    }
}
