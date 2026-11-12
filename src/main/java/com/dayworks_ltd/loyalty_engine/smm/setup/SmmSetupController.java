package com.dayworks_ltd.loyalty_engine.smm.setup;

import com.dayworks_ltd.loyalty_engine.auth.model.CustomUserDetails;
import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppCloudClient;
import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppProperties;
import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryRepository;
import com.dayworks_ltd.loyalty_engine.inventory.services.StockAuditRecorder;
import com.dayworks_ltd.loyalty_engine.merchants.MerchantModule;
import com.dayworks_ltd.loyalty_engine.orders.repositories.OrderRepository;
import com.dayworks_ltd.loyalty_engine.smm.intake.SmmAlertService;
import com.dayworks_ltd.loyalty_engine.smm.intake.SmmMessageTracker;
import com.dayworks_ltd.loyalty_engine.smm.meta.MetaGraphClient;
import com.dayworks_ltd.loyalty_engine.smm.model.*;
import com.dayworks_ltd.loyalty_engine.smm.repository.*;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.BiFunction;

/**
 * The seller's SokoReply setup in the portal (onboarding steps 5-9) and the Comments and Leads lists.
 * The merchant is always the logged-in user's, never taken from the request.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/smm")
@RequiredArgsConstructor
public class SmmSetupController {

    private final SmmSellerProfileRepository profiles;
    private final SmmDeliveryZoneRepository zones;
    private final SmmSetRepository sets;
    private final SmmPostItemRepository postItems;
    private final SmmAccountRepository accounts;
    private final SmmCommentRepository comments;
    private final SmmReplyRepository replies;
    private final SmmHandoverRepository handovers;
    private final SmmLeadRepository leads;
    private final InventoryRepository inventory;
    private final StockAuditRecorder stockAudit;
    private final OrderRepository orders;
    private final MetaGraphClient graph;
    private final WhatsAppCloudClient whatsapp;
    private final WhatsAppProperties whatsappProps;
    private final com.dayworks_ltd.loyalty_engine.smm.intake.SmmWatermarkService watermark;

    // ───────────────────────────── step 5: shop details ─────────────────────────────

    @GetMapping("/setup/profile")
    @Operation(summary = "The shop's SokoReply profile (created on first read)")
    public ResponseEntity<?> profile(@AuthenticationPrincipal CustomUserDetails user) {
        return withMerchant(user, (m, u) -> ok("Profile", profileFor(m)));
    }

    @PutMapping("/setup/profile")
    @Operation(summary = "Save shop details: location, hours, WhatsApp, staff, tone, voice, no-go phrases")
    public ResponseEntity<?> saveProfile(@AuthenticationPrincipal CustomUserDetails user, @RequestBody Map<String, Object> body) {
        return withMerchant(user, (m, u) -> {
            SmmSellerProfile p = profileFor(m);
            if (body.containsKey("locationText")) p.setLocationText(text(body, "locationText", 300));
            if (body.containsKey("landmarkDirections")) p.setLandmarkDirections(text(body, "landmarkDirections", 2000));
            if (body.containsKey("openingHours")) p.setOpeningHours(text(body, "openingHours", 200));
            if (body.containsKey("mapLat")) p.setMapLat(number(body.get("mapLat")));
            if (body.containsKey("mapLng")) p.setMapLng(number(body.get("mapLng")));
            if (body.containsKey("whatsappNumber")) {
                String phone = text(body, "whatsappNumber", 20);
                if (phone != null && !phone.replaceAll("\\D", "").matches("(254|0)?[17]\\d{8}")) {
                    return fail(HttpStatus.BAD_REQUEST, "WhatsApp number must be a Kenyan mobile number, e.g. 0712 345 678");
                }
                p.setWhatsappNumber(phone);
            }
            if (body.containsKey("staffContacts")) p.setStaffContacts(text(body, "staffContacts", 2000));
            if (body.containsKey("tone")) p.setTone(text(body, "tone", 40));
            if (body.containsKey("languages")) p.setLanguages(text(body, "languages", 40));
            if (body.containsKey("alertLanguage")) p.setAlertLanguage("sw".equals(body.get("alertLanguage")) ? "sw" : "en");
            if (body.containsKey("voiceSamples")) p.setVoiceSamples(text(body, "voiceSamples", 10000));
            if (body.containsKey("noGoPhrases")) p.setNoGoPhrases(text(body, "noGoPhrases", 4000));
            return ok("Saved", profiles.save(p));
        });
    }

    @PutMapping("/setup/profile/mode")
    @Operation(summary = "Shadow mode on/off and pause: {\"shadowMode\": false, \"agentsPaused\": false}")
    public ResponseEntity<?> mode(@AuthenticationPrincipal CustomUserDetails user, @RequestBody Map<String, Object> body) {
        return withMerchant(user, (m, u) -> {
            SmmSellerProfile p = profileFor(m);
            if (body.get("shadowMode") instanceof Boolean b) p.setShadowMode(b);
            if (body.get("agentsPaused") instanceof Boolean b) p.setAgentsPaused(b);
            log.info("Merchant {} SokoReply mode: shadow={}, paused={} (by user {})", m, p.isShadowMode(), p.isAgentsPaused(), u);
            return ok(p.isShadowMode() ? "Shadow mode: replies are logged, not sent" : "Live", profiles.save(p));
        });
    }

    private SmmSellerProfile profileFor(Long merchantId) {
        return profiles.findByMerchantId(merchantId)
                .orElseGet(() -> profiles.save(SmmSellerProfile.builder().merchantId(merchantId).build()));
    }

    // ───────────────────────────── step 6: delivery zones and sets ─────────────────────────────

    @GetMapping("/setup/zones")
    public ResponseEntity<?> zones(@AuthenticationPrincipal CustomUserDetails user) {
        return withMerchant(user, (m, u) -> ok("Delivery zones", zones.findByMerchantIdAndActiveTrue(m)));
    }

    @PostMapping("/setup/zones")
    @Operation(summary = "Add or update a delivery zone: {id?, name, places, fee, days, method}")
    public ResponseEntity<?> saveZone(@AuthenticationPrincipal CustomUserDetails user, @RequestBody Map<String, Object> body) {
        return withMerchant(user, (m, u) -> {
            String name = text(body, "name", 100);
            if (name == null) return fail(HttpStatus.BAD_REQUEST, "Give the zone a name, e.g. Nairobi CBD");
            SmmDeliveryZone z = body.get("id") == null ? SmmDeliveryZone.builder().merchantId(m).build()
                    : zones.findById(Long.valueOf(String.valueOf(body.get("id")))).filter(x -> x.getMerchantId().equals(m)).orElse(null);
            if (z == null) return fail(HttpStatus.NOT_FOUND, "Zone not found");
            z.setName(name);
            z.setPlaces(text(body, "places", 500));
            z.setFee(money(body.get("fee")));
            z.setDays(text(body, "days", 40));
            try {
                if (body.get("method") != null) z.setMethod(SmmDeliveryZone.Method.valueOf(String.valueOf(body.get("method"))));
            } catch (IllegalArgumentException e) {
                return fail(HttpStatus.BAD_REQUEST, "method must be one of " + Arrays.toString(SmmDeliveryZone.Method.values()));
            }
            z.setActive(true);
            return ok("Saved", zones.save(z));
        });
    }

    @DeleteMapping("/setup/zones/{id}")
    public ResponseEntity<?> removeZone(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long id) {
        return withMerchant(user, (m, u) -> zones.findById(id).filter(z -> z.getMerchantId().equals(m)).<ResponseEntity<?>>map(z -> {
            z.setActive(false); // kept for old leads that matched it
            zones.save(z);
            return ok("Removed", null);
        }).orElseGet(() -> fail(HttpStatus.NOT_FOUND, "Zone not found")));
    }

    @GetMapping("/setup/sets")
    public ResponseEntity<?> sets(@AuthenticationPrincipal CustomUserDetails user) {
        return withMerchant(user, (m, u) -> ok("Sets", sets.findByMerchantIdAndActiveTrue(m)));
    }

    @PostMapping("/setup/sets")
    @Operation(summary = "Add or update a set: {id?, name, itemCodes: [..], setPrice, optionalItemCodes: [..]}")
    public ResponseEntity<?> saveSet(@AuthenticationPrincipal CustomUserDetails user, @RequestBody Map<String, Object> body) {
        return withMerchant(user, (m, u) -> {
            String name = text(body, "name", 150);
            List<String> codes = codes(body.get("itemCodes"));
            BigDecimal price = money(body.get("setPrice"));
            if (name == null || codes.size() < 2 || price == null) {
                return fail(HttpStatus.BAD_REQUEST, "A set needs a name, at least two items and a set price");
            }
            Set<String> known = itemCodes(m);
            List<String> unknown = codes.stream().filter(c -> !known.contains(c)).toList();
            if (!unknown.isEmpty()) return fail(HttpStatus.BAD_REQUEST, "Not in your catalogue: " + String.join(", ", unknown));
            SmmSet s = body.get("id") == null ? SmmSet.builder().merchantId(m).build()
                    : sets.findById(Long.valueOf(String.valueOf(body.get("id")))).filter(x -> x.getMerchantId().equals(m)).orElse(null);
            if (s == null) return fail(HttpStatus.NOT_FOUND, "Set not found");
            s.setName(name);
            s.setItemCodes(String.join(",", codes));
            s.setOptionalItemCodes(String.join(",", codes(body.get("optionalItemCodes"))));
            s.setSetPrice(price);
            s.setActive(true);
            return ok("Saved", sets.save(s));
        });
    }

    @DeleteMapping("/setup/sets/{id}")
    public ResponseEntity<?> removeSet(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long id) {
        return withMerchant(user, (m, u) -> sets.findById(id).filter(s -> s.getMerchantId().equals(m)).<ResponseEntity<?>>map(s -> {
            s.setActive(false);
            sets.save(s);
            return ok("Removed", null);
        }).orElseGet(() -> fail(HttpStatus.NOT_FOUND, "Set not found")));
    }

    // ───────────────────────────── step 7: catalogue check ─────────────────────────────

    @GetMapping("/setup/catalogue")
    @Operation(summary = "The shop's products with type, colour, size and parent product")
    public ResponseEntity<?> catalogue(@AuthenticationPrincipal CustomUserDetails user) {
        return withMerchant(user, (m, u) -> ok("Catalogue", inventory.findByMerchantId(String.valueOf(m)).stream()
                .sorted(Comparator.comparing(i -> String.valueOf(i.getItemName()))).map(SmmSetupController::item).toList()));
    }

    @PostMapping("/setup/catalogue")
    @Operation(summary = "Add a product (SokoReply-only plan; shop-system merchants add products in Inventory)")
    public ResponseEntity<?> addItem(@AuthenticationPrincipal CustomUserDetails user, @RequestBody Map<String, Object> body) {
        return withMerchant(user, (m, u) -> {
            if (!ownsCatalogue(user)) return fail(HttpStatus.FORBIDDEN, "Add products on the Inventory page");
            String name = text(body, "itemName", 120);
            if (name == null) return fail(HttpStatus.BAD_REQUEST, "Give the product a name");
            BigDecimal price = money(body.get("unitPrice"));
            if (price == null) return fail(HttpStatus.BAD_REQUEST, "Give the product a price");
            boolean service = "SERVICE".equals(body.get("itemType"));
            Integer stock = service ? Integer.valueOf(0) : count(body.get("availableStock"));
            if (stock == null) return fail(HttpStatus.BAD_REQUEST, "Stock must be a whole number, 0 or more");
            String colour = lower(text(body, "colour", 40));
            String size = upper(text(body, "size", 20));
            Set<String> known = itemCodes(m);
            String code = newItemCode(m, name, colour, size);
            if (known.contains(code)) return fail(HttpStatus.CONFLICT, "This product is already in your catalogue; change its stock instead");
            String parent = text(body, "parentCode", 100);
            if (parent != null && !known.contains(parent)) {
                return fail(HttpStatus.BAD_REQUEST, "Parent product must be another product in your catalogue");
            }
            Inventory i = new Inventory();
            i.setMerchantId(String.valueOf(m));
            i.setItemName(name);
            i.setItemCode(code);
            i.setUnitPrice(price);
            i.setStartingStock(stock);
            i.setAvailableStock(stock);
            i.setClosingStock(stock);
            i.setItemType(service ? Inventory.ItemType.SERVICE : Inventory.ItemType.GOOD);
            i.setColour(colour);
            i.setSize(size);
            i.setParentCode(parent);
            Inventory saved = inventory.save(i);
            stockAudit.record(saved, StockAuditRecorder.NEW_ITEM, 0, stock, null, String.valueOf(u), "Added in SokoReply");
            return ok("Added", item(saved));
        });
    }

    @PutMapping("/setup/catalogue/{id}")
    @Operation(summary = "Set a product's type (GOOD/SERVICE), colour, size and parent product code; "
            + "on the SokoReply-only plan also its name, price and stock")
    public ResponseEntity<?> saveItem(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long id, @RequestBody Map<String, Object> body) {
        return withMerchant(user, (m, u) -> inventory.findByIdAndMerchantId(id, String.valueOf(m)).<ResponseEntity<?>>map(i -> {
            boolean shopFields = body.containsKey("itemName") || body.containsKey("unitPrice") || body.containsKey("availableStock");
            if (shopFields && !ownsCatalogue(user)) {
                return fail(HttpStatus.FORBIDDEN, "Change names, prices and stock on the Inventory page");
            }
            if (body.containsKey("itemName")) {
                String name = text(body, "itemName", 120);
                if (name == null) return fail(HttpStatus.BAD_REQUEST, "Give the product a name");
                i.setItemName(name);
            }
            if (body.containsKey("unitPrice")) {
                BigDecimal price = money(body.get("unitPrice"));
                if (price == null) return fail(HttpStatus.BAD_REQUEST, "Give the product a price");
                i.setUnitPrice(price);
            }
            if (body.containsKey("availableStock") && !i.isService()) {
                Integer stock = count(body.get("availableStock"));
                if (stock == null) return fail(HttpStatus.BAD_REQUEST, "Stock must be a whole number, 0 or more");
                int before = i.getAvailableStock() == null ? 0 : i.getAvailableStock();
                i.setAvailableStock(stock);
                i.setClosingStock(stock);
                i.syncTotals();
                stockAudit.record(i, StockAuditRecorder.MANUAL_CORRECTION, before, stock, null, String.valueOf(u),
                        "Stock set by the seller in SokoReply");
            }
            if (body.containsKey("itemType")) {
                i.setItemType("SERVICE".equals(body.get("itemType")) ? Inventory.ItemType.SERVICE : Inventory.ItemType.GOOD);
            }
            if (body.containsKey("colour")) i.setColour(lower(text(body, "colour", 40)));
            if (body.containsKey("size")) i.setSize(upper(text(body, "size", 20)));
            if (body.containsKey("parentCode")) {
                String parent = text(body, "parentCode", 100);
                if (parent != null && (parent.equals(i.getItemCode()) || !itemCodes(m).contains(parent))) {
                    return fail(HttpStatus.BAD_REQUEST, "Parent product must be another product in your catalogue");
                }
                i.setParentCode(parent);
            }
            return ok("Saved", item(inventory.save(i)));
        }).orElseGet(() -> fail(HttpStatus.NOT_FOUND, "Product not found")));
    }

    private static Map<String, Object> item(Inventory i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("itemCode", i.getItemCode());
        m.put("itemName", i.getItemName());
        m.put("unitPrice", i.getUnitPrice());
        m.put("availableStock", i.getAvailableStock());
        m.put("itemType", i.getItemType() == null ? "GOOD" : i.getItemType().name());
        m.put("colour", i.getColour());
        m.put("size", i.getSize());
        m.put("parentCode", i.getParentCode());
        return m;
    }

    // ───────────────────────────── step 8: tag products on Reels ─────────────────────────────

    @GetMapping("/setup/posts")
    @Operation(summary = "Recent Reels and Instagram posts with the products tagged on each (and suggestions from the caption)")
    public ResponseEntity<?> posts(@AuthenticationPrincipal CustomUserDetails user) {
        return withMerchant(user, (m, u) -> {
            List<Inventory> catalogue = inventory.findByMerchantId(String.valueOf(m));
            List<Map<String, Object>> out = new ArrayList<>();
            List<String> problems = new ArrayList<>();
            for (SmmAccount a : accounts.findByMerchantId(m)) {
                if (a.getStatus() != SmmAccount.Status.ACTIVE) continue;
                try {
                    List<MetaGraphClient.Post> posts = a.getPlatform() == SocialPlatform.INSTAGRAM
                            ? graph.recentInstagramPosts(a.getPageId(), a.getAccessToken())
                            : graph.recentFacebookReels(a.getPageId(), a.getAccessToken());
                    for (MetaGraphClient.Post p : posts) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("postId", p.id());
                        row.put("platform", p.platform());
                        row.put("account", a.getPageName());
                        row.put("caption", p.caption());
                        row.put("link", p.link());
                        row.put("thumbnail", p.thumbnail());
                        row.put("createdAt", p.createdAt());
                        row.put("tagged", postItems.findByMerchantIdAndPostId(m, p.id()).stream().map(SmmPostItem::getItemCode).toList());
                        row.put("suggested", suggest(p.caption(), catalogue));
                        out.add(row);
                    }
                } catch (Exception e) {
                    problems.add(a.getPageName() + ": " + e.getMessage());
                }
            }
            return ok(problems.isEmpty() ? "Posts" : "Some accounts could not be read: " + String.join("; ", problems), out);
        });
    }

    @PutMapping("/setup/posts/{postId}/items")
    @Transactional
    @Operation(summary = "Set the products on a post: {\"itemCodes\": [..]} (replaces the list)")
    public ResponseEntity<?> tag(@AuthenticationPrincipal CustomUserDetails user, @PathVariable String postId, @RequestBody Map<String, Object> body) {
        return withMerchant(user, (m, u) -> {
            List<String> codes = codes(body.get("itemCodes"));
            Set<String> known = itemCodes(m);
            known.addAll(sets.findByMerchantIdAndActiveTrue(m).stream().map(s -> "SET-" + s.getId()).toList());
            List<String> unknown = codes.stream().filter(c -> !known.contains(c)).toList();
            if (!unknown.isEmpty()) return fail(HttpStatus.BAD_REQUEST, "Not in your catalogue: " + String.join(", ", unknown));
            postItems.deleteAll(postItems.findByMerchantIdAndPostId(m, postId));
            List<SmmPostItem> saved = postItems.saveAll(codes.stream().map(c -> SmmPostItem.builder()
                    .merchantId(m).postId(postId).itemCode(c).taggedBy(SmmPostItem.TaggedBy.SELLER).build()).toList());
            return ok("Tagged " + saved.size() + " product(s)", saved.stream().map(SmmPostItem::getItemCode).toList());
        });
    }

    /** Products whose name words appear in the caption (e.g. "Satin wrap dress 3500" -> the satin wrap dress). */
    public static List<String> suggest(String caption, List<Inventory> catalogue) {
        if (caption == null || caption.isBlank()) return List.of();
        String c = caption.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (Inventory i : catalogue) {
            String name = String.valueOf(i.getItemName()).toLowerCase(Locale.ROOT);
            String[] words = Arrays.stream(name.split("[^a-z0-9]+")).filter(w -> w.length() > 3).toArray(String[]::new);
            long hits = Arrays.stream(words).filter(c::contains).count();
            if (words.length > 0 && hits * 2 >= words.length) {
                out.add(i.getParentCode() != null ? i.getParentCode() : i.getItemCode());
            }
        }
        return out.stream().distinct().limit(5).toList();
    }

    // ───────────────────────────── step 9: voice ─────────────────────────────

    @PostMapping("/setup/voice/import")
    @Operation(summary = "Pull the seller's own past replies from their Facebook Page as voice samples")
    public ResponseEntity<?> importVoice(@AuthenticationPrincipal CustomUserDetails user) {
        return withMerchant(user, (m, u) -> {
            LinkedHashSet<String> samples = new LinkedHashSet<>();
            SmmSellerProfile p = profileFor(m);
            if (p.getVoiceSamples() != null) samples.addAll(Arrays.stream(p.getVoiceSamples().split("\n")).map(String::trim).filter(s -> !s.isEmpty()).toList());
            for (SmmAccount a : accounts.findByMerchantId(m)) {
                if (a.getStatus() != SmmAccount.Status.ACTIVE || a.getPlatform() != SocialPlatform.FACEBOOK) continue;
                try {
                    samples.addAll(graph.ownReplies(a.getPageId(), a.getAccessToken(), 20));
                } catch (Exception e) {
                    log.warn("Voice import failed for {}: {}", a.getPageName(), e.getMessage());
                }
            }
            p.setVoiceSamples(String.join("\n", samples.stream().limit(20).toList()));
            profiles.save(p);
            return ok(samples.isEmpty() ? "No past replies found: type a few in your own words" : "Imported", p);
        });
    }

    // ───────────────────────────── comments and leads ─────────────────────────────

    @GetMapping("/comments")
    @Operation(summary = "Comments received, newest first, with what the agents understood and did")
    public ResponseEntity<?> comments(@AuthenticationPrincipal CustomUserDetails user,
                                      @RequestParam(required = false) String postId,
                                      @RequestParam(required = false) String intent,
                                      @RequestParam(required = false) String show,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "50") int size) {
        return withMerchant(user, (m, u) -> {
            PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
            // show: buyers | not-buyers | harmful (anything else = all)
            Boolean buyer = "buyers".equals(show) ? Boolean.TRUE : "not-buyers".equals(show) ? Boolean.FALSE : null;
            Boolean negative = "harmful".equals(show) ? Boolean.TRUE : null;
            var list = comments.search(m, blankToNull(postId), blankToNull(intent), buyer, negative, pr);
            List<Map<String, Object>> rows = list.getContent().stream().map(c -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("comment", c);
                r.put("actions", replies.findByCommentIdOrderByCreatedAtAsc(c.getId()));
                r.put("handovers", handovers.findByCommentId(c.getId()));
                return r;
            }).toList();
            return ok("Comments", Map.of("total", list.getTotalElements(), "page", list.getNumber(), "rows", rows));
        });
    }

    @GetMapping("/comments/posts")
    @Operation(summary = "Posts that have comments (for the filter), newest activity first")
    public ResponseEntity<?> commentPosts(@AuthenticationPrincipal CustomUserDetails user) {
        return withMerchant(user, (m, u) -> ok("Posts", comments.postsWithComments(m).stream()
                .map(r -> Map.of("postId", r[0], "comments", r[1])).toList()));
    }

    @GetMapping("/leads")
    @Operation(summary = "Buyers from comments, most recent first; filter by temperature (HOT, WARM, COLD) and post")
    public ResponseEntity<?> leads(@AuthenticationPrincipal CustomUserDetails user,
                                   @RequestParam(required = false) String temperature,
                                   @RequestParam(required = false) String postId,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "50") int size) {
        return withMerchant(user, (m, u) -> {
            PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
            Lead.Temperature t;
            try {
                t = blankToNull(temperature) == null ? null : Lead.Temperature.valueOf(temperature.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return fail(HttpStatus.BAD_REQUEST, "temperature must be HOT, WARM or COLD");
            }
            String post = blankToNull(postId);
            var list = post == null && t == null ? leads.findByMerchantIdOrderByLastContactAtDesc(m, pr)
                    : post == null ? leads.findByMerchantIdAndTemperatureOrderByLastContactAtDesc(m, t, pr)
                    : t == null ? leads.findByMerchantIdAndPostIdOrderByLastContactAtDesc(m, post, pr)
                    : leads.findByMerchantIdAndPostIdAndTemperatureOrderByLastContactAtDesc(m, post, t, pr);
            return ok("Leads", Map.of("total", list.getTotalElements(), "page", list.getNumber(), "rows", list.getContent()));
        });
    }

    @GetMapping("/handovers")
    @Operation(summary = "Comments waiting for the seller (urgent first)")
    public ResponseEntity<?> openHandovers(@AuthenticationPrincipal CustomUserDetails user) {
        return withMerchant(user, (m, u) -> {
            List<Map<String, Object>> rows = handovers.findByMerchantIdAndAnsweredAtIsNullOrderByCreatedAtAsc(m).stream()
                    .sorted(Comparator.comparing(SmmHandover::isUrgent).reversed()).map(h -> {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("handover", h);
                        r.put("comment", comments.findById(h.getCommentId()).orElse(null));
                        return r;
                    }).toList();
            return ok("Waiting for you", rows);
        });
    }

    @PostMapping("/handovers/{id}/done")
    @Operation(summary = "Mark a handover dealt with: {\"outcome\": \"called her, refunded\"}")
    public ResponseEntity<?> handoverDone(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        return withMerchant(user, (m, u) -> handovers.findById(id).filter(h -> h.getMerchantId().equals(m)).<ResponseEntity<?>>map(h -> {
            h.setAnsweredAt(java.time.LocalDateTime.now(java.time.ZoneId.of("Africa/Nairobi")));
            h.setOutcome(body == null ? null : text(body, "outcome", 2000));
            return ok("Done", handovers.save(h));
        }).orElseGet(() -> fail(HttpStatus.NOT_FOUND, "Not found")));
    }

    @GetMapping("/reply-rate")
    @Operation(summary = "Of buyers who got a private reply in the last N days, how many answered or ordered")
    public ResponseEntity<?> replyRate(@AuthenticationPrincipal CustomUserDetails user, @RequestParam(defaultValue = "30") int days) {
        return withMerchant(user, (m, u) -> {
            var since = java.time.LocalDateTime.now(java.time.ZoneId.of("Africa/Nairobi")).minusDays(Math.min(Math.max(days, 1), 365));
            List<SmmReply> sent = replies.findByMerchantIdAndKindAndStatusAndSentAtAfter(m, SmmReply.Kind.PRIVATE, SmmReply.Status.SENT, since);
            // Orders carry Meta's comment ID; replies carry ours: map one to the other
            Map<String, Long> byMetaId = new HashMap<>();
            for (SmmReply r : sent) comments.findById(r.getCommentId()).ifPresent(c -> byMetaId.put(c.getCommentId(), c.getId()));
            Set<Long> ordered = new HashSet<>();
            if (!byMetaId.isEmpty()) orders.findSocialCommentIdsWithOrders(m, byMetaId.keySet()).forEach(id -> ordered.add(byMetaId.get(id)));
            return ok("Reply rate", SmmMessageTracker.ReplyRate.of(sent, ordered));
        });
    }

    // ───────────────────────────── admin ─────────────────────────────

    @PostMapping("/admin/alert-template")
    @Operation(summary = "Register SokoReply's WhatsApp templates with Meta: bad comment, hot lead, morning summary (admin, once)")
    public ResponseEntity<?> registerTemplate(@AuthenticationPrincipal CustomUserDetails user) {
        if (user == null || !"ADMIN".equals(user.getUserRole())) return fail(HttpStatus.FORBIDDEN, "Admins only");
        String lang = whatsappProps.getTemplateLanguage();
        Map<String, Object> results = new LinkedHashMap<>();
        for (Map<String, Object> def : List.of(SmmAlertService.templateDefinition(lang),
                com.dayworks_ltd.loyalty_engine.smm.intake.SmmLeadAlertService.templateDefinition(lang),
                com.dayworks_ltd.loyalty_engine.smm.intake.SmmMorningSummary.templateDefinition(lang))) {
            try {
                results.put(String.valueOf(def.get("name")), whatsapp.createTemplate(def));
            } catch (Exception e) {
                results.put(String.valueOf(def.get("name")), Map.of("error", String.valueOf(e.getMessage())));
            }
        }
        return ok("Submitted to Meta for approval", results);
    }

    @PostMapping("/admin/till")
    @Operation(summary = "Record a seller's till after our KES 1 test payment: number + the name exactly as M-Pesa showed it (admin)")
    public ResponseEntity<?> verifyTill(@AuthenticationPrincipal CustomUserDetails user, @RequestBody Map<String, Object> body) {
        if (user == null || !"ADMIN".equals(user.getUserRole())) return fail(HttpStatus.FORBIDDEN, "Admins only");
        Double merchant = number(body.get("merchantId"));
        if (merchant == null) return fail(HttpStatus.BAD_REQUEST, "merchantId is required");
        try {
            SmmSellerProfile p = watermark.verifyTill(merchant.longValue(), text(body, "tillNumber", 20), text(body, "tillName", 100));
            return ok("Till verified; the seller's videos now get the watermark",
                    Map.of("merchantId", p.getMerchantId(), "tillNumber", p.getTillNumber(), "tillName", p.getTillName()));
        } catch (IllegalArgumentException e) {
            return fail(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private ResponseEntity<?> withMerchant(CustomUserDetails user, BiFunction<Long, Long, ResponseEntity<?>> action) {
        if (user == null || user.getMerchantId() == null) return fail(HttpStatus.BAD_REQUEST, "This user is not linked to any merchant");
        return action.apply(user.getMerchantId(), user.getUserId());
    }

    /**
     * Merchants without the Inventory module keep their products here; those with it use Inventory
     * (with its stock controls).
     */
    public static boolean ownsCatalogue(CustomUserDetails user) {
        java.util.List<String> modules = user == null ? null : user.getModules();
        return modules != null && modules.contains(MerchantModule.SOKOREPLY.name()) && !modules.contains(MerchantModule.INVENTORY.name());
    }

    /** Same shape as Inventory's own codes (merchant-NAME), plus colour and size so each variant gets its own row. */
    public static String newItemCode(Long merchantId, String name, String colour, String size) {
        StringBuilder code = new StringBuilder(merchantId + "-" + squash(name));
        if (colour != null) code.append('-').append(squash(colour));
        if (size != null) code.append('-').append(squash(size));
        return code.length() > 100 ? code.substring(0, 100) : code.toString();
    }

    private static String squash(String s) {
        return s.replaceAll("[^\\p{L}\\p{N}]", "").toUpperCase(Locale.ROOT);
    }

    /** A whole number, 0 or more; null when missing or not a number. */
    public static Integer count(Object v) {
        Double d = number(v);
        if (d == null || d < 0 || d != Math.floor(d) || d > Integer.MAX_VALUE) return null;
        return d.intValue();
    }

    private Set<String> itemCodes(Long merchantId) {
        Set<String> codes = new HashSet<>();
        inventory.findByMerchantId(String.valueOf(merchantId)).forEach(i -> codes.add(i.getItemCode()));
        return codes;
    }

    private static List<String> codes(Object value) {
        if (!(value instanceof List<?> l)) return List.of();
        return l.stream().map(String::valueOf).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }

    private static String text(Map<String, Object> body, String key, int max) {
        Object v = body.get(key);
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static Double number(Object v) {
        try {
            return v == null || String.valueOf(v).isBlank() ? null : Double.valueOf(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal money(Object v) {
        try {
            return v == null || String.valueOf(v).isBlank() ? null : new BigDecimal(String.valueOf(v).replaceAll("[^0-9.]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    private static ResponseEntity<?> ok(String message, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "SUCCESS");
        body.put("statusCode", 200);
        body.put("message", message);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<?> fail(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("status", "FAILURE", "statusCode", status.value(), "message", message));
    }
}
