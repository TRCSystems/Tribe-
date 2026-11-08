package com.dayworks_ltd.loyalty_engine.smm.meta;

import com.dayworks_ltd.loyalty_engine.smm.model.SocialPlatform;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.*;

/**
 * The Graph API calls SokoReply makes for sellers' Pages and Instagram accounts. Access tokens are passed in and
 * never logged; errors carry Meta's message without the token.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetaGraphClient {

    private final RestTemplate restTemplate;
    private final SmmProperties props;

    public static class GraphException extends RuntimeException {
        public GraphException(String message) {
            super(message);
        }
    }

    /** A Page the seller manages, with its Page token and linked Instagram business account (if any). */
    public record ManagedPage(String id, String name, String accessToken, String instagramId, String instagramUsername) {}

    /** A Reel or Instagram post, for tagging products. */
    public record Post(String id, SocialPlatform platform, String caption, String link, String createdAt, String thumbnail) {}

    public record TokenInfo(boolean valid, Long expiresAt) {}

    // ───────────────────────────── login ─────────────────────────────

    /** The Facebook Login for Business dialog the seller is sent to. */
    public String loginDialogUrl(String state) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString("https://www.facebook.com/" + props.getGraphVersion() + "/dialog/oauth")
                .queryParam("client_id", props.getAppId())
                .queryParam("redirect_uri", props.getRedirectUri())
                .queryParam("state", state)
                .queryParam("response_type", "code");
        if (!props.getLoginConfigId().isBlank()) {
            b.queryParam("config_id", props.getLoginConfigId());
        } else {
            b.queryParam("scope", String.join(",", SCOPES));
        }
        return b.encode().build().toUriString();
    }

    /** What SokoReply asks the seller for (used when no Login for Business configuration is set). */
    static final List<String> SCOPES = List.of("pages_show_list", "pages_read_engagement", "pages_read_user_content",
            "pages_manage_engagement", "pages_manage_metadata", "pages_messaging", "business_management",
            "instagram_basic", "instagram_manage_comments", "instagram_manage_messages");

    /** Login code to a long-lived user token (about 60 days); Page tokens taken from it do not expire. */
    public String longLivedUserToken(String code) {
        Map<String, Object> shortLived = get(graph("oauth/access_token")
                .queryParam("client_id", props.getAppId())
                .queryParam("redirect_uri", props.getRedirectUri())
                .queryParam("client_secret", props.getAppSecret())
                .queryParam("code", code));
        Map<String, Object> longLived = get(graph("oauth/access_token")
                .queryParam("grant_type", "fb_exchange_token")
                .queryParam("client_id", props.getAppId())
                .queryParam("client_secret", props.getAppSecret())
                .queryParam("fb_exchange_token", String.valueOf(shortLived.get("access_token"))));
        return String.valueOf(longLived.get("access_token"));
    }

    @SuppressWarnings("unchecked")
    public List<ManagedPage> managedPages(String userToken) {
        Map<String, Object> res = get(graph("me/accounts")
                .queryParam("fields", "id,name,access_token,instagram_business_account{id,username}")
                .queryParam("limit", 100)
                .queryParam("access_token", userToken));
        List<ManagedPage> pages = new ArrayList<>();
        for (Map<String, Object> p : (List<Map<String, Object>>) res.getOrDefault("data", List.of())) {
            Map<String, Object> ig = (Map<String, Object>) p.get("instagram_business_account");
            pages.add(new ManagedPage(str(p.get("id")), str(p.get("name")), str(p.get("access_token")),
                    ig == null ? null : str(ig.get("id")), ig == null ? null : str(ig.get("username"))));
        }
        return pages;
    }

    /** Turns on comment webhooks for the Page (and, through it, its Instagram account). */
    public void subscribePage(String pageId, String pageToken) {
        post(graph(pageId + "/subscribed_apps")
                .queryParam("subscribed_fields", "feed,messages") // comments, and buyers answering our private replies
                .queryParam("access_token", pageToken));
    }

    public void unsubscribePage(String pageId, String pageToken) {
        exchange(HttpMethod.DELETE, graph(pageId + "/subscribed_apps").queryParam("access_token", pageToken));
    }

    @SuppressWarnings("unchecked")
    public TokenInfo checkToken(String token) {
        Map<String, Object> res = get(graph("debug_token")
                .queryParam("input_token", token)
                .queryParam("access_token", props.getAppId() + "|" + props.getAppSecret()));
        Map<String, Object> data = (Map<String, Object>) res.getOrDefault("data", Map.of());
        Object exp = data.get("expires_at");
        long expiresAt = exp instanceof Number n ? n.longValue() : 0L;
        return new TokenInfo(Boolean.TRUE.equals(data.get("is_valid")), expiresAt == 0 ? null : expiresAt);
    }

    // ───────────────────────────── comments ─────────────────────────────

    /** Hides a comment from the public (the writer and their friends still see it). */
    public void hideComment(SocialPlatform platform, String commentId, String pageToken) {
        post(graph(commentId)
                .queryParam(platform == SocialPlatform.INSTAGRAM ? "hide" : "is_hidden", true)
                .queryParam("access_token", pageToken));
    }

    // ───────────────────────────── setup ─────────────────────────────

    @SuppressWarnings("unchecked")
    public List<Post> recentFacebookReels(String pageId, String pageToken) {
        Map<String, Object> res = get(graph(pageId + "/video_reels")
                .queryParam("fields", "id,description,permalink_url,created_time,picture")
                .queryParam("limit", 25)
                .queryParam("access_token", pageToken));
        List<Post> posts = new ArrayList<>();
        for (Map<String, Object> v : (List<Map<String, Object>>) res.getOrDefault("data", List.of())) {
            String link = str(v.get("permalink_url"));
            if (link != null && link.startsWith("/")) link = "https://www.facebook.com" + link;
            posts.add(new Post(str(v.get("id")), SocialPlatform.FACEBOOK, str(v.get("description")), link,
                    str(v.get("created_time")), str(v.get("picture"))));
        }
        return posts;
    }

    @SuppressWarnings("unchecked")
    public List<Post> recentInstagramPosts(String instagramId, String pageToken) {
        Map<String, Object> res = get(graph(instagramId + "/media")
                .queryParam("fields", "id,caption,media_type,permalink,timestamp,thumbnail_url,media_url")
                .queryParam("limit", 25)
                .queryParam("access_token", pageToken));
        List<Post> posts = new ArrayList<>();
        for (Map<String, Object> m : (List<Map<String, Object>>) res.getOrDefault("data", List.of())) {
            String thumb = str(m.get("thumbnail_url"));
            posts.add(new Post(str(m.get("id")), SocialPlatform.INSTAGRAM, str(m.get("caption")), str(m.get("permalink")),
                    str(m.get("timestamp")), thumb != null ? thumb : str(m.get("media_url"))));
        }
        return posts;
    }

    /** The seller's own past replies on their recent posts: samples of their voice for the Writer. */
    @SuppressWarnings("unchecked")
    /** A comment fetched from the Graph API (backfill), shaped like the webhook gives it. */
    public record FetchedComment(String id, String postId, String parentId, String fromId, String fromName, String text, String createdTime) {}

    /** Facebook posts (Reels included) the Page published since a time, newest first. Post IDs look like the webhook's. */
    @SuppressWarnings("unchecked")
    public List<String> facebookPostIdsSince(String pageId, String pageToken, long sinceEpochSeconds, int max) {
        Map<String, Object> res = get(graph(pageId + "/published_posts")
                .queryParam("fields", "id,created_time")
                .queryParam("since", sinceEpochSeconds)
                .queryParam("limit", Math.min(max, 100))
                .queryParam("access_token", pageToken));
        return ((List<Map<String, Object>>) res.getOrDefault("data", List.of())).stream()
                .map(p -> str(p.get("id"))).filter(Objects::nonNull).limit(max).toList();
    }

    /** Every comment and reply on one Facebook post (filter=stream), oldest first, up to max. */
    @SuppressWarnings("unchecked")
    public List<FetchedComment> facebookComments(String postId, String pageToken, int max) {
        List<FetchedComment> out = new ArrayList<>();
        String after = null;
        do {
            UriComponentsBuilder b = graph(postId + "/comments")
                    .queryParam("filter", "stream").queryParam("order", "chronological")
                    .queryParam("fields", "id,message,created_time,from{id,name},parent{id}")
                    .queryParam("limit", 100).queryParam("access_token", pageToken);
            if (after != null) b.queryParam("after", after);
            Map<String, Object> res = get(b);
            for (Map<String, Object> c : (List<Map<String, Object>>) res.getOrDefault("data", List.of())) {
                Map<String, Object> from = c.get("from") instanceof Map<?, ?> f ? (Map<String, Object>) f : Map.of();
                Map<String, Object> parent = c.get("parent") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
                out.add(new FetchedComment(str(c.get("id")), postId, str(parent.get("id")), str(from.get("id")), str(from.get("name")),
                        str(c.get("message")), str(c.get("created_time"))));
            }
            after = nextCursor(res);
        } while (after != null && out.size() < max);
        return out.size() > max ? out.subList(0, max) : out;
    }

    /** Instagram posts and Reels since a time, newest first. */
    @SuppressWarnings("unchecked")
    public List<String> instagramMediaIdsSince(String instagramId, String pageToken, java.time.Instant since, int max) {
        Map<String, Object> res = get(graph(instagramId + "/media")
                .queryParam("fields", "id,timestamp")
                .queryParam("limit", Math.min(max, 100))
                .queryParam("access_token", pageToken));
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> m : (List<Map<String, Object>>) res.getOrDefault("data", List.of())) {
            java.time.Instant at = instant(str(m.get("timestamp")));
            if (at != null && at.isBefore(since)) break; // newest first: the rest are older
            if (m.get("id") != null) ids.add(str(m.get("id")));
            if (ids.size() >= max) break;
        }
        return ids;
    }

    /** Comments on one Instagram post, with their replies, up to max. */
    @SuppressWarnings("unchecked")
    public List<FetchedComment> instagramComments(String mediaId, String pageToken, int max) {
        List<FetchedComment> out = new ArrayList<>();
        String after = null;
        do {
            UriComponentsBuilder b = graph(mediaId + "/comments")
                    .queryParam("fields", "id,text,timestamp,from{id,username},username,replies{id,text,timestamp,from{id,username},username}")
                    .queryParam("limit", 50).queryParam("access_token", pageToken);
            if (after != null) b.queryParam("after", after);
            Map<String, Object> res = get(b);
            for (Map<String, Object> c : (List<Map<String, Object>>) res.getOrDefault("data", List.of())) {
                out.add(instagramComment(c, mediaId, null));
                for (Map<String, Object> r : dataOf(c.get("replies"))) out.add(instagramComment(r, mediaId, str(c.get("id"))));
            }
            after = nextCursor(res);
        } while (after != null && out.size() < max);
        return out.size() > max ? out.subList(0, max) : out;
    }

    @SuppressWarnings("unchecked")
    private static FetchedComment instagramComment(Map<String, Object> c, String mediaId, String parentId) {
        Map<String, Object> from = c.get("from") instanceof Map<?, ?> f ? (Map<String, Object>) f : Map.of();
        String name = from.get("username") != null ? str(from.get("username")) : str(c.get("username"));
        return new FetchedComment(str(c.get("id")), mediaId, parentId, str(from.get("id")), name, str(c.get("text")), str(c.get("timestamp")));
    }

    @SuppressWarnings("unchecked")
    private static String nextCursor(Map<String, Object> res) {
        if (!(res.get("paging") instanceof Map<?, ?> paging) || paging.get("next") == null) return null;
        return paging.get("cursors") instanceof Map<?, ?> cursors ? str(((Map<String, Object>) cursors).get("after")) : null;
    }

    /** Graph times: "2026-11-08T10:15:30+0000". */
    public static java.time.Instant instant(String t) {
        if (t == null || t.isBlank()) return null;
        try {
            return java.time.OffsetDateTime.parse(t.replaceAll("([+-]\\d{2})(\\d{2})$", "$1:$2")).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    public List<String> ownReplies(String pageId, String pageToken, int max) {
        Map<String, Object> res = get(graph(pageId + "/published_posts")
                .queryParam("fields", "comments.limit(50){from,message,comments.limit(20){from,message}}")
                .queryParam("limit", 15)
                .queryParam("access_token", pageToken));
        LinkedHashSet<String> mine = new LinkedHashSet<>();
        for (Map<String, Object> post : (List<Map<String, Object>>) res.getOrDefault("data", List.of())) {
            for (Map<String, Object> c : dataOf(post.get("comments"))) {
                collect(c, pageId, mine);
                for (Map<String, Object> reply : dataOf(c.get("comments"))) collect(reply, pageId, mine);
                if (mine.size() >= max) return new ArrayList<>(mine).subList(0, max);
            }
        }
        return new ArrayList<>(mine);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> dataOf(Object edge) {
        return edge instanceof Map<?, ?> m && m.get("data") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    @SuppressWarnings("unchecked")
    private static void collect(Map<String, Object> comment, String pageId, Set<String> into) {
        Map<String, Object> from = (Map<String, Object>) comment.get("from");
        String msg = str(comment.get("message"));
        if (from != null && pageId.equals(str(from.get("id"))) && msg != null && msg.trim().length() > 3) into.add(msg.trim());
    }

    // ───────────────────────────── plumbing ─────────────────────────────

    private UriComponentsBuilder graph(String path) {
        return UriComponentsBuilder.fromUriString(props.graph() + path);
    }

    private Map<String, Object> get(UriComponentsBuilder b) {
        return exchange(HttpMethod.GET, b);
    }

    private Map<String, Object> post(UriComponentsBuilder b) {
        return exchange(HttpMethod.POST, b);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exchange(HttpMethod method, UriComponentsBuilder b) {
        URI uri = b.encode().build().toUri();
        try {
            ResponseEntity<Map> res = restTemplate.exchange(uri, method, null, Map.class);
            return res.getBody() == null ? Map.of() : res.getBody();
        } catch (HttpStatusCodeException e) {
            // Meta's error text, never the URL (it carries the token)
            String body = e.getResponseBodyAsString();
            throw new GraphException("Meta said " + e.getStatusCode().value() + ": " + (body.length() > 300 ? body.substring(0, 300) : body));
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
