package com.dayworks_ltd.loyalty_engine.merchants;

import com.dayworks_ltd.loyalty_engine.auth.model.CustomUserDetails;
import com.dayworks_ltd.loyalty_engine.auth.model.User;
import com.dayworks_ltd.loyalty_engine.smm.setup.SmmSetupController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** SokoReply-only merchants get SokoReply and nothing of the shop system; everyone else is untouched. */
class PlanGuardTest {

    @Test
    void sokoReplyOnlyMerchantsAreKeptOutOfTheShopModules() {
        for (String path : new String[]{"/api/v1/inventory", "/api/v1/inventory/stock-levels/1", "/api/v1/orders/42",
                "/api/v1/crm/shops", "/api/stock-transfers", "/api/v1/campaigns", "/api/v1/shop-order/x"}) {
            assertTrue(PlanGuard.blocked("SOKOREPLY", path), path);
        }
    }

    @Test
    void sokoReplyAndSharedEndpointsStayOpen() {
        for (String path : new String[]{"/api/v1/smm/comments", "/api/v1/smm/setup/catalogue", "/api/v1/login",
                "/api/v1/user/me", "/api/v1/inventoryx", "/api/v1/ordersheet"}) {
            assertFalse(PlanGuard.blocked("SOKOREPLY", path), path);
        }
    }

    @Test
    void fullMerchantsAdminsAndAnonymousAreNotAffected() {
        assertFalse(PlanGuard.blocked("FULL", "/api/v1/inventory"));
        assertFalse(PlanGuard.blocked(null, "/api/v1/orders"));
    }

    @Test
    void merchantsFromBeforePlansAreFull() {
        assertEquals(MerchantPlan.FULL, MerchantPlan.of(new Merchant()));
        assertEquals(MerchantPlan.FULL, MerchantPlan.of(null));
        assertEquals(MerchantPlan.SOKOREPLY, MerchantPlan.parse(" sokoreply "));
        assertNull(MerchantPlan.parse("gold"));
        assertNull(new CustomUserDetails(new User()).getPlan(), "admins have no merchant and no plan");
    }

    @Test
    void onlySokoReplyOnlySellersEditProductsInSokoReply() {
        Merchant soko = new Merchant();
        soko.setPlan(MerchantPlan.SOKOREPLY);
        User seller = new User();
        seller.setMerchant(soko);
        assertTrue(SmmSetupController.ownsCatalogue(new CustomUserDetails(seller)));

        User shopOwner = new User();
        shopOwner.setMerchant(new Merchant());
        assertFalse(SmmSetupController.ownsCatalogue(new CustomUserDetails(shopOwner)), "shop merchants use Inventory");
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
