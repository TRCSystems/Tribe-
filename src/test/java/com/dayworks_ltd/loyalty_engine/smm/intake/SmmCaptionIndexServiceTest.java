package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop.ShoppingListReader;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmCaptionItem;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmCaptionItemRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SmmCaptionIndexServiceTest {

    SmmCaptionItemRepository items = mock(SmmCaptionItemRepository.class);
    ShoppingListReader claude = mock(ShoppingListReader.class);
    SmmCaptionIndexService index = new SmmCaptionIndexService(items, claude);

    @Test
    void readsTheCaptionOnceAndSavesEachItemWithAPrice() {
        when(items.existsByMerchantIdAndPostId(23L, "POST1")).thenReturn(false);
        when(claude.readText(anyString(), eq("HP OMEN 15, i7, 16GB RAM, KES 86,000, bag included"), eq(SmmCaptionIndexService.Found.class)))
                .thenReturn(new SmmCaptionIndexService.Found(List.of(
                        new SmmCaptionIndexService.Item("HP OMEN 15", "86000", "i7, 16GB RAM, bag included"))));

        index.indexOnce(23L, "POST1", "HP OMEN 15, i7, 16GB RAM, KES 86,000, bag included");

        verify(items).save(argThat(i -> i.getMerchantId().equals(23L) && i.getPostId().equals("POST1")
                && i.getName().equals("HP OMEN 15") && i.getPrice().equals(new BigDecimal("86000"))));
    }

    @Test
    void aPostAlreadyIndexedIsNotReadAgain() {
        when(items.existsByMerchantIdAndPostId(23L, "POST1")).thenReturn(true);
        index.indexOnce(23L, "POST1", "Anything");
        verifyNoInteractions(claude);
    }

    @Test
    void aCaptionWithNoPriceAddsNothing() {
        when(items.existsByMerchantIdAndPostId(23L, "POST1")).thenReturn(false);
        when(claude.readText(anyString(), anyString(), eq(SmmCaptionIndexService.Found.class)))
                .thenReturn(new SmmCaptionIndexService.Found(List.of()));
        index.indexOnce(23L, "POST1", "Thank you to everyone who came to the launch!");
        verify(items, never()).save(any());
    }

    @Test
    void theRangeListsOtherPostsNotTheCurrentOneAndQuotesThePrice() {
        when(items.findTop60ByMerchantIdOrderByIdDesc(23L)).thenReturn(List.of(
                SmmCaptionItem.builder().merchantId(23L).postId("POST2").name("Dell Latitude").price(new BigDecimal("29000")).build(),
                SmmCaptionItem.builder().merchantId(23L).postId("POST1").name("HP OMEN 15").price(new BigDecimal("86000")).build()));

        String facts = index.rangeFactsFor(23L, "POST1");

        assertNotNull(facts);
        assertTrue(facts.contains("Dell Latitude"));
        assertTrue(facts.contains("29000") || facts.contains("29,000"));
        assertFalse(facts.contains("HP OMEN 15"), "the current post's own item is not repeated as an \"other\" item");
    }

    @Test
    void noOtherItemsGivesNoFacts() {
        when(items.findTop60ByMerchantIdOrderByIdDesc(23L)).thenReturn(List.of());
        assertNull(index.rangeFactsFor(23L, "POST1"));
    }
}
