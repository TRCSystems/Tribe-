package com.dayworks_ltd.loyalty_engine.smm.setup;

import com.dayworks_ltd.loyalty_engine.auth.model.CustomUserDetails;
import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppCloudClient;
import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppProperties;
import com.dayworks_ltd.loyalty_engine.inventory.models.Inventory;
import com.dayworks_ltd.loyalty_engine.inventory.repositories.InventoryRepository;
import com.dayworks_ltd.loyalty_engine.smm.intake.SmmAlertService;
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
    private final MetaGraphClient graph;
    private final WhatsAppCloudClient whatsapp;
    private final WhatsAppProperties whatsappProps;

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

    @PutMapping("/setup/catalogue/{id}")
    @Operation(summary = "Set a product's type (GOOD/SERVICE), colour, size and parent product code")
    public ResponseEntity<?> saveItem(@AuthenticationPrincipal CustomUserDetails user, @PathVariable Long id, @RequestBody Map<String, Object> body) {
        return withMerchant(user, (m, u) -> inventory.findByIdAndMerchantId(id, String.valueOf(m)).<ResponseEntity<?>>map(i -> {
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
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "50") int size) {
        return withMerchant(user, (m, u) -> {
            PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
            var list = postId == null || postId.isBlank()
                    ? comments.findByMerchantIdOrderByReceivedAtDesc(m, pr)
                    : comments.findByMerchantIdAndPostIdOrderByReceivedAtDesc(m, postId, pr);
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

    @GetMapping("/leads")
    @Operation(summary = "Buyers from comments, most recent first")
    public ResponseEntity<?> leads(@AuthenticationPrincipal CustomUserDetails user,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "50") int size) {
        return withMerchant(user, (m, u) -> {
            var list = leads.findByMerchantIdOrderByLastContactAtDesc(m, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200)));
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

    // ───────────────────────────── admin ─────────────────────────────

    @PostMapping("/admin/alert-template")
    @Operation(summary = "Register the urgent bad-comment WhatsApp template with Meta (admin, once)")
    public ResponseEntity<?> registerTemplate(@AuthenticationPrincipal CustomUserDetails user) {
        if (user == null || !"ADMIN".equals(user.getUserRole())) return fail(HttpStatus.FORBIDDEN, "Admins only");
        try {
            return ok("Submitted to Meta for approval", whatsapp.createTemplate(SmmAlertService.templateDefinition(whatsappProps.getTemplateLanguage())));
        } catch (Exception e) {
            return fail(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private ResponseEntity<?> withMerchant(CustomUserDetails user, BiFunction<Long, Long, ResponseEntity<?>> action) {
        if (user == null || user.getMerchantId() == null) return fail(HttpStatus.BAD_REQUEST, "This user is not linked to any merchant");
        return action.apply(user.getMerchantId(), user.getUserId());
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
