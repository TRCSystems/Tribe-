package com.dayworks_ltd.loyalty_engine.smm.order;

import com.dayworks_ltd.loyalty_engine.common.OrderStatus;
import com.dayworks_ltd.loyalty_engine.common.PaymentMode;
import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppCloudClient;
import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryRepository;
import com.dayworks_ltd.loyalty_engine.merchants.Merchant;
import com.dayworks_ltd.loyalty_engine.orders.dto.OrderRequest;
import com.dayworks_ltd.loyalty_engine.orders.models.Order;
import com.dayworks_ltd.loyalty_engine.orders.models.OrderSource;
import com.dayworks_ltd.loyalty_engine.orders.repositories.OrderRepository;
import com.dayworks_ltd.loyalty_engine.orders.services.OrderService;
import com.dayworks_ltd.loyalty_engine.smm.model.*;
import com.dayworks_ltd.loyalty_engine.smm.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The SokoReply order page: catalogue view, placing an order against the seller's own till, and the paid report. */
class SmmOrderServiceTest {

    static final Long MERCHANT = 23L;
    static final String POST = "POST1";
    static final String TOKEN = "ABCD1234";

    SmmOrderLinkService linkService = mock(SmmOrderLinkService.class);
    SmmPostItemRepository postItems = mock(SmmPostItemRepository.class);
    SmmSetRepository sets = mock(SmmSetRepository.class);
    SmmDeliveryZoneRepository zones = mock(SmmDeliveryZoneRepository.class);
    SmmSellerProfileRepository profiles = mock(SmmSellerProfileRepository.class);
    SmmAccountRepository accounts = mock(SmmAccountRepository.class);
    InventoryRepository inventory = mock(InventoryRepository.class);
    OrderService orderService = mock(OrderService.class);
    OrderRepository orders = mock(OrderRepository.class);
    WhatsAppCloudClient whatsapp = mock(WhatsAppCloudClient.class);

    SmmOrderService service = new SmmOrderService(linkService, postItems, sets, zones, profiles, accounts, inventory, orderService, orders, whatsapp);

    SmmSellerProfile seller = SmmSellerProfile.builder().id(1L).merchantId(MERCHANT).whatsappNumber("0712 000 111")
            .locationText("Moi Avenue").build();
    SmmOrderLink link = SmmOrderLink.builder().id(1L).token(TOKEN).merchantId(MERCHANT).postId(POST)
            .expiresAt(LocalDateTime.now().plusDays(10)).build();

    @BeforeEach
    void setUp() {
        when(linkService.find(TOKEN)).thenReturn(Optional.of(link));
        when(profiles.findByMerchantId(MERCHANT)).thenReturn(Optional.of(seller));
        when(accounts.findByMerchantId(MERCHANT)).thenReturn(List.of(SmmAccount.builder().id(1L).merchantId(MERCHANT).pageName("@ClaudHub").build()));
        when(postItems.findByMerchantIdAndPostId(MERCHANT, POST)).thenReturn(List.of(
                SmmPostItem.builder().id(1L).merchantId(MERCHANT).postId(POST).itemCode("DRESS-GRN").build()));
        when(inventory.findByMerchantId("23")).thenReturn(List.of(
                inv("DRESS-GRN", "Green Dress", "Green", "M", "1500", 5)));
        when(sets.findByMerchantIdAndActiveTrue(MERCHANT)).thenReturn(List.of());
        when(zones.findByMerchantIdAndActiveTrue(MERCHANT)).thenReturn(List.of(
                SmmDeliveryZone.builder().id(9L).merchantId(MERCHANT).name("CBD").fee(new BigDecimal("200")).days("same day").active(true).build()));
    }

    static Inventory inv(String code, String name, String colour, String size, String price, int stock) {
        Inventory i = new Inventory();
        i.setMerchantId("23");
        i.setItemCode(code);
        i.setItemName(name);
        i.setColour(colour);
        i.setSize(size);
        i.setUnitPrice(new BigDecimal(price));
        i.setAvailableStock(stock);
        return i;
    }

    @Test
    void anUnverifiedSellerShowsNoTillInfoButStillSellsItems() {
        SmmOrderService.View view = service.view(TOKEN);
        assertEquals("ClaudHub", view.sellerName());
        assertFalse(view.tillVerified());
        assertNull(view.tillNumber());
        assertEquals(1, view.items().size());
        assertEquals("Green Dress", view.items().get(0).name());
        assertEquals(1, view.zones().size());
        verify(linkService).recordOpen(link);
    }

    @Test
    void aVerifiedSellersTillShows() {
        seller.setTillNumber("123456");
        seller.setTillName("CLAD HUB");
        seller.setTillVerifiedAt(LocalDateTime.now());
        SmmOrderService.View view = service.view(TOKEN);
        assertTrue(view.tillVerified());
        assertEquals("123456", view.tillNumber());
        assertEquals("CLAD HUB", view.tillName());
    }

    @Test
    void anExpiredOrUnknownLinkIsNotFound() {
        when(linkService.find("NOPE")).thenReturn(Optional.empty());
        assertThrows(java.util.NoSuchElementException.class, () -> service.view("NOPE"));
    }

    @Test
    void placingAnOrderComputesTheTotalAndAlertsTheSeller() {
        Merchant merchant = new Merchant();
        merchant.setId(MERCHANT);
        Order created = Order.builder().id(1L).orderCode("SR-0001").merchant(merchant).totalAmount(new BigDecimal("1700.00"))
                .status(OrderStatus.PENDING_PAYMENT).build();
        when(orderService.createOrder(eq("23"), any(OrderRequest.class), eq(OrderSource.SOCIAL), isNull(), isNull())).thenReturn(created);
        when(orders.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(whatsapp.sendTemplate(any(), any(), any(), any())).thenReturn(new WhatsAppCloudClient.Sent(true, "wamid.1", null));

        SmmOrderService.PlacedOrder placed = service.placeOrder(TOKEN, List.of(new SmmOrderService.ItemLine("DRESS-GRN", 1)),
                9L, "0712345678", "Mary", "COMMENT1");

        assertEquals("SR-0001", placed.orderCode());
        assertFalse(placed.tillVerified(), "no till verified yet");

        ArgumentCaptor<OrderRequest> cap = ArgumentCaptor.forClass(OrderRequest.class);
        verify(orderService).createOrder(eq("23"), cap.capture(), eq(OrderSource.SOCIAL), isNull(), isNull());
        assertEquals(PaymentMode.SELLER_TILL, cap.getValue().getPaymentMode());
        assertEquals(2, cap.getValue().getItems().size(), "the dress plus the delivery fee line");
        assertEquals("DELIVERY", cap.getValue().getItems().get(1).getItemCode());
        assertEquals(new BigDecimal("200"), cap.getValue().getItems().get(1).getWholesalePrice());

        assertEquals(POST, created.getSocialPostId());
        assertEquals("COMMENT1", created.getSocialCommentId());
        assertEquals("Mary", created.getCustomerName());
        verify(linkService).recordOrder(link);
        verify(whatsapp).sendTemplate(eq("0712 000 111"), eq(SmmOrderService.NEW_ORDER_TEMPLATE), any(), isNull());
    }

    @Test
    void anOutOfStockOrMissingItemIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.placeOrder(TOKEN, List.of(new SmmOrderService.ItemLine("DRESS-GRN", 99)), null, "0712345678", null, null),
                "only 5 in stock");
        assertThrows(IllegalArgumentException.class,
                () -> service.placeOrder(TOKEN, List.of(new SmmOrderService.ItemLine("NOT-A-CODE", 1)), null, "0712345678", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.placeOrder(TOKEN, List.of(), null, "0712345678", null, null), "no items");
        assertThrows(IllegalArgumentException.class,
                () -> service.placeOrder(TOKEN, List.of(new SmmOrderService.ItemLine("DRESS-GRN", 1)), null, "123", null, null), "bad phone");
    }

    @Test
    void reportingPaymentMarksTheOrderPaidAndTellsTheSeller() {
        Merchant merchant = new Merchant();
        merchant.setId(MERCHANT);
        Order order = Order.builder().id(1L).orderCode("SR-0001").merchant(merchant).totalAmount(new BigDecimal("1700.00"))
                .orderSource(OrderSource.SOCIAL).status(OrderStatus.PENDING_PAYMENT).build();
        when(orders.findByOrderCode("SR-0001")).thenReturn(Optional.of(order));
        when(orders.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.reportPaid("SR-0001", "qk7ab12xyz");

        assertEquals(OrderStatus.PAID, order.getStatus());
        assertEquals("QK7AB12XYZ", order.getPaymentReference());
        assertNotNull(order.getPaymentDate());
        verify(whatsapp).sendText(eq("0712 000 111"), contains("SR-0001"));
    }

    @Test
    void reportingPaymentOnANonSocialOrderIsRejected() {
        Order order = Order.builder().id(1L).orderCode("X-1").orderSource(OrderSource.CUSTOMER).build();
        when(orders.findByOrderCode("X-1")).thenReturn(Optional.of(order));
        assertThrows(IllegalArgumentException.class, () -> service.reportPaid("X-1", null));
    }
}
