package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop.ShoppingListReader;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmCaptionItem;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmCaptionItemRepository;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Builds up a seller's range from their posts' own captions, one post at a time: most sellers never tag a catalogue
 * product, they just write the price in the caption. Read once per post and kept, so a later buyer's budget, or a
 * different item than the one on the post they commented on, can still be answered from the seller's other posts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmmCaptionIndexService {

    private final SmmCaptionItemRepository items;
    private final ShoppingListReader claude;

    record Found(
            @JsonPropertyDescription("One row per item or variant the caption is selling, with its own price. Empty if the caption names no item with a price (a thank-you post, an announcement, a repost with no price).")
            List<Item> items) {}

    record Item(
            @JsonPropertyDescription("The item's name, as a buyer would ask for it")
            String name,
            @JsonPropertyDescription("The price in KES as a plain number, no currency sign or comma. Null if this item has no price in the caption.")
            String price,
            @JsonPropertyDescription("Size, colour, spec or other detail from the caption, in a few words; null if none")
            String detail) {}

    static final String SYSTEM = """
            You read the caption a Kenyan seller wrote on a Facebook or Instagram post selling something. Pull out every item \
            with a price. A caption often sells one item with its price and specs; sometimes it lists several sizes, colours \
            or variants each with its own price, or several different products in one post. Ignore delivery fees, hashtags \
            and calls to action ("order now", "DM us"). If no price is written anywhere, return an empty list rather than \
            guessing one.""";

    /** Reads the caption once per post; a later call for a post already indexed does nothing. */
    public void indexOnce(Long merchantId, String postId, String caption) {
        if (merchantId == null || postId == null || caption == null || caption.isBlank()) return;
        if (items.existsByMerchantIdAndPostId(merchantId, postId)) return;
        try {
            Found found = claude.readText(SYSTEM, caption, Found.class);
            if (found == null || found.items() == null || found.items().isEmpty()) return;
            for (Item i : found.items()) {
                if (i.name() == null || i.name().isBlank()) continue;
                items.save(SmmCaptionItem.builder().merchantId(merchantId).postId(postId)
                        .name(i.name().strip()).price(price(i.price())).detail(i.detail()).build());
            }
            log.info("SokoReply: indexed {} item(s) from the caption of post {} (merchant {})", found.items().size(), postId, merchantId);
        } catch (Exception e) {
            log.warn("SokoReply: could not read the caption of post {} for its items: {}", postId, e.getMessage());
        }
    }

    private static BigDecimal price(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return new BigDecimal(s.replaceAll("[^0-9.]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** This seller's range from other posts, as one line a model can quote from directly, or null if there is none. */
    public String rangeFactsFor(Long merchantId, String excludingPostId) {
        List<SmmCaptionItem> mine = items.findTop60ByMerchantIdOrderByIdDesc(merchantId).stream()
                .filter(i -> !i.getPostId().equals(excludingPostId)).limit(25).toList();
        if (mine.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("Other items this seller has posted (from their own captions; each is its own post, not this one):\n");
        for (SmmCaptionItem i : mine) {
            sb.append("- ").append(i.getName());
            if (i.getPrice() != null) sb.append(", KES ").append(i.getPrice().stripTrailingZeros().toPlainString());
            if (i.getDetail() != null) sb.append(", ").append(i.getDetail());
            sb.append('\n');
        }
        return sb.toString();
    }
}
