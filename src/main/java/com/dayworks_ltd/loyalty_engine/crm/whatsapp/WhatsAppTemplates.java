package com.dayworks_ltd.loyalty_engine.crm.whatsapp;

import com.dayworks_ltd.loyalty_engine.crm.models.CrmTemplate;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The WhatsApp versions of the CRM templates, as submitted to Meta for approval.
 * One Tribe number sends for every distributor, so each message names the distributor.
 * Meta rules: a template may not start or end with a variable, and variables may not sit side by
 * side. Marketing templates carry an "Order now" button that opens the order form.
 */
public final class WhatsAppTemplates {

    private WhatsAppTemplates() {}

    public static final String STOP_FOOTER = "Reply STOP to opt out";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z]+)}");

    public record WaTemplate(String name, boolean marketing, String text, boolean orderButton) {

        /** Placeholder names in the order they become {{1}}, {{2}}… */
        public List<String> variables() {
            List<String> names = new ArrayList<>();
            Matcher m = PLACEHOLDER.matcher(text);
            while (m.find()) if (!names.contains(m.group(1))) names.add(m.group(1));
            return names;
        }

        /** Text with {{n}} placeholders, as Meta wants it. */
        public String metaText() {
            String out = text;
            List<String> vars = variables();
            for (int i = 0; i < vars.size(); i++) out = out.replace("{" + vars.get(i) + "}", "{{" + (i + 1) + "}}");
            return out;
        }

        /** Values for {{1}}, {{2}}… from the CRM placeholder values. */
        public List<String> params(Map<String, String> values) {
            return variables().stream().map(v -> clean(values.get(v), v)).toList();
        }
    }

    private static final Map<CrmTemplate, WaTemplate> TEMPLATES = new EnumMap<>(CrmTemplate.class);

    static {
        put(CrmTemplate.WEEKEND_STOCKUP, true, true,
                "Hey {shop}, weekend imefika! Stock up early with your usual {items} from {distributor}. Tap Order now below.");
        put(CrmTemplate.PAYDAY_WEEK, true, true,
                "Hey {shop}, mwisho wa mwezi - customers wako na pesa! Top up {items} from {distributor} now. Tap Order now below.");
        put(CrmTemplate.REORDER_DUE, true, true,
                "Sasa {shop}! Shelf iko low? Restock your usual {items} from {distributor} in 2 taps. Tap Order now below.");
        put(CrmTemplate.NEW_STOCK, true, true,
                "Mzigo imefika! Hey {shop}, {distributor} just got fresh stock of {items}. Order yours before it runs out - tap Order now below.");
        put(CrmTemplate.WIN_BACK, true, true,
                "Hey {shop}, tumekumiss! Customers are asking for {items} and {distributor} is ready to deliver. Tap Order now below.");
        put(CrmTemplate.SELF_ORDER_INVITE, true, true,
                "Hey {shop}, sasa unaweza order yourself anytime on WhatsApp - no need to wait for the rep. "
                        + "{distributor} delivers and you pay on delivery or by M-Pesa. Tap Order now below.");
        put(CrmTemplate.PROMO, true, true,
                "Hey {shop}, offer from {distributor}: {text}. Tap Order now below to order.");
        put(CrmTemplate.ORDER_CONFIRMED, false, false,
                "Hi {shop}, your order {count} from {distributor} is confirmed. We will tell you when it is on the way.");
        put(CrmTemplate.ORDER_DISPATCHED, false, false,
                "Hi {shop}, your order {count} from {distributor} is on the way.");
        put(CrmTemplate.ORDER_DELIVERED, false, false,
                "Hi {shop}, your order {count} from {distributor} has been delivered. Asante for ordering with us!");
        // CUSTOM has no template: free text can only go out within 24h of the shop's last message
    }

    private static void put(CrmTemplate t, boolean marketing, boolean orderButton, String text) {
        TEMPLATES.put(t, new WaTemplate("tribe_" + t.name().toLowerCase(), marketing, text, orderButton));
    }

    public static Optional<WaTemplate> forTemplate(CrmTemplate t) {
        return Optional.ofNullable(TEMPLATES.get(t));
    }

    public static Collection<WaTemplate> all() {
        return TEMPLATES.values();
    }

    /** Request body for POST /{waba-id}/message_templates. */
    public static Map<String, Object> metaDefinition(WaTemplate t, String language, String flowId) {
        List<Map<String, Object>> components = new ArrayList<>();
        List<String> example = t.variables().stream().map(WhatsAppTemplates::example).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "BODY");
        body.put("text", t.metaText());
        if (!example.isEmpty()) body.put("example", Map.of("body_text", List.of(example)));
        components.add(body);
        if (t.marketing()) components.add(Map.of("type", "FOOTER", "text", STOP_FOOTER));
        if (t.orderButton()) {
            components.add(Map.of("type", "BUTTONS", "buttons", List.of(Map.of(
                    "type", "FLOW", "text", "Order now", "flow_id", flowId, "flow_action", "data_exchange"))));
        }
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("name", t.name());
        def.put("language", language);
        def.put("category", t.marketing() ? "MARKETING" : "UTILITY");
        def.put("components", components);
        return def;
    }

    private static String example(String variable) {
        return switch (variable) {
            case "shop" -> "Mama Pima Wines";
            case "distributor" -> "ASANO";
            case "items" -> "Tusker 500ml and Gilbeys 250ml";
            case "count" -> "ORD-2601";
            case "text" -> "10% off Tusker crates this weekend";
            default -> "sample";
        };
    }

    /** Meta rejects parameters with newlines, tabs or long runs of spaces; blanks get a sensible word. */
    private static String clean(String value, String variable) {
        String v = value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").replaceAll(" {2,}", " ").trim();
        if (!v.isEmpty()) return v;
        return switch (variable) {
            case "items" -> "favourites";
            case "text" -> "special prices this week";
            default -> "-";
        };
    }
}
