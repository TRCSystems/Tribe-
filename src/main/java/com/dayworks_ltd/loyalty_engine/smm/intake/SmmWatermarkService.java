package com.dayworks_ltd.loyalty_engine.smm.intake;

import com.dayworks_ltd.loyalty_engine.crm.whatsapp.WhatsAppCloudClient;
import com.dayworks_ltd.loyalty_engine.smm.model.SmmSellerProfile;
import com.dayworks_ltd.loyalty_engine.smm.repository.SmmSellerProfileRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Till watermark: a seller sends a reel to the Tribe WhatsApp number and gets it back with
 * "Pay ONLY to Till 123456 · JANE W. ENTERPRISES · We never send another number" burnt into the picture.
 * <p>
 * A clone Page can repost the real seller's video, but the video then still tells buyers which till to pay and what
 * name M-Pesa will show. The text sits in the upper third and in the middle of the frame (Reels cover the bottom with
 * the caption and the right side with the buttons) and jumps between the two every few seconds, so cropping one spot
 * doesn't remove it.
 * <p>
 * Only sellers with their own verified Lipa na M-Pesa till get it: they are always paid into that till, and the name
 * on the watermark is the one M-Pesa shows (from a small payment we make into the till, see {@link SmmTillCheckService}), never the Page name. Sellers with
 * only a personal M-Pesa number get no watermark.
 * <p>
 * Still photos get the same two lines of text, but burnt in at both the upper and the middle spot at once (a photo
 * has no time to jump between them), since most SokoReply posts are photos, not reels.
 * <p>
 * Sellers should send the video or photo as a Document so WhatsApp doesn't compress it before Facebook compresses it
 * again; it comes back as a Document too.
 */
@Slf4j
@Service
public class SmmWatermarkService {

    /** Biggest document WhatsApp accepts (Meta's document limit). */
    static final long MAX_BYTES = 100L * 1024 * 1024;
    /** Seconds the text stays in one place before it jumps to the other. */
    static final int HOP_SECONDS = 4;
    /** Lipa na M-Pesa till (Buy Goods) numbers: 5 to 7 digits. A phone number is not a till. */
    static final Pattern TILL_COMMAND = Pattern.compile("^(?:TILL|TILL NUMBER|TILL NO\\.?|NAMBARI YA TILL)\\s*[:#-]?\\s*(\\d[\\d ]{3,9})$",
            Pattern.CASE_INSENSITIVE);

    private final SmmSellerProfileRepository profiles;
    private final WhatsAppCloudClient whatsapp;
    private final SmmTillCheckService tillCheck;
    private final String ffmpeg;
    private final String font;
    private final int maxSeconds;
    /**
     * Kill switch for the photo/video path only: a seller's WhatsApp number can double as an ordering line for
     * something else (e.g. ASANO's liquor B2B demo), and a buyer's photo/video there was being swallowed by this
     * service instead of reaching order-taking. TILL text commands and the till-check answer are unaffected.
     */
    private final boolean mediaEnabled;
    /** One video at a time: the server has three cores and the comment agents need them too. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "smm-watermark");
        t.setDaemon(true);
        return t;
    });

    public SmmWatermarkService(SmmSellerProfileRepository profiles, WhatsAppCloudClient whatsapp, SmmTillCheckService tillCheck,
                               @Value("${smm.watermark.ffmpeg:ffmpeg}") String ffmpeg,
                               @Value("${smm.watermark.font:/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf}") String font,
                               @Value("${smm.watermark.max-seconds:600}") int maxSeconds,
                               @Value("${smm.watermark.media-enabled:true}") boolean mediaEnabled) {
        this.profiles = profiles;
        this.whatsapp = whatsapp;
        this.tillCheck = tillCheck;
        this.ffmpeg = ffmpeg;
        this.font = font;
        this.maxSeconds = maxSeconds;
        this.mediaEnabled = mediaEnabled;
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    /** Image extensions a Document might carry instead of a proper mime type. */
    static final Pattern IMAGE_EXT = Pattern.compile(".*\\.(jpe?g|png|webp)$");
    /** Video extensions a Document might carry instead of a proper mime type. */
    static final Pattern VIDEO_EXT = Pattern.compile(".*\\.(mp4|mov|m4v|3gp|webm|mkv)$");

    /** True when the message was a seller's video or photo, or their TILL command, and has been dealt with here. */
    @SuppressWarnings("unchecked")
    public boolean handle(String from, String type, String content, Map<String, Object> message) {
        String wa = WhatsAppCloudClient.toWaNumber(from);
        if (wa == null) return false;
        List<SmmSellerProfile> sellers = profiles.findAll().stream()
                .filter(p -> wa.equals(WhatsAppCloudClient.toWaNumber(p.getWhatsappNumber()))).toList();
        if (sellers.isEmpty()) return false;

        if ("text".equals(type) && content != null) {
            Matcher m = TILL_COMMAND.matcher(content.trim());
            if (m.matches()) return tillGiven(from, sellers, m.group(1).replace(" ", ""));
            return tillCheck.answer(from, sellers, content);
        }
        if (!mediaEnabled) return false;

        Map<String, Object> media = null;
        boolean asDocument = false;
        boolean isImage = false;
        if ("video".equals(type) && message != null && message.get("video") instanceof Map<?, ?> v) {
            media = (Map<String, Object>) v;
        } else if ("image".equals(type) && message != null && message.get("image") instanceof Map<?, ?> im) {
            media = (Map<String, Object>) im;
            isImage = true;
        } else if ("document".equals(type) && message != null && message.get("document") instanceof Map<?, ?> d) {
            media = (Map<String, Object>) d;
            asDocument = true;
            String mime = String.valueOf(media.get("mime_type"));
            String name = String.valueOf(media.get("filename")).toLowerCase(Locale.ROOT);
            boolean video = mime.startsWith("video/") || VIDEO_EXT.matcher(name).matches();
            boolean image = mime.startsWith("image/") || IMAGE_EXT.matcher(name).matches();
            if (!video && !image) return false;
            isImage = image;
        }
        if (media == null || media.get("id") == null) return false;

        SmmSellerProfile seller = sellers.stream().filter(SmmWatermarkService::canWatermark).findFirst().orElse(null);
        if (seller == null) {
            whatsapp.sendText(from, notReady(sellers));
            return true;
        }
        String mediaId = media.get("id").toString();
        boolean document = asDocument;
        boolean image = isImage;
        whatsapp.sendText(from, image
                ? "Got it ⏳ Adding your till to the photo. We'll send it back here in a moment."
                : "Got it ⏳ Adding your till to the video. This takes a minute or two; we'll send it back here.");
        worker.submit(() -> {
            if (image) processImage(from, seller, mediaId, document);
            else process(from, seller, mediaId, document);
        });
        return true;
    }

    /** A seller's till, verified by our test payment: only then is there a watermark. */
    static boolean canWatermark(SmmSellerProfile p) {
        return p.getTillNumber() != null && !p.getTillNumber().isBlank()
                && p.getTillName() != null && !p.getTillName().isBlank()
                && p.getTillVerifiedAt() != null;
    }

    static String notReady(List<SmmSellerProfile> sellers) {
        boolean waiting = sellers.stream().anyMatch(p -> p.getTillNumber() != null && !p.getTillNumber().isBlank());
        if (waiting) {
            return "We're still checking your till. When our small payment arrives on the till, send us its M-Pesa code "
                    + "and the amount, like this: QK7AB12XYZ 3. Then send the photo or video again.";
        }
        return "The watermark shows buyers your Lipa na M-Pesa till on your photos and videos, so a copycat Page can't "
                + "redirect your buyers. It's for sellers with a till (not a personal M-Pesa number). If you have one, "
                + "send TILL and the number, for example: TILL 123456";
    }

    private boolean tillGiven(String from, List<SmmSellerProfile> sellers, String till) {
        if (!till.matches("\\d{5,7}")) {
            whatsapp.sendText(from, "That doesn't look like a till number (5 to 7 digits). A phone number can't be used "
                    + "for the watermark. Send it like this: TILL 123456");
            return true;
        }
        tillCheck.tillGiven(from, sellers, till);
        return true;
    }

    // ───────────────────────────── the video ─────────────────────────────

    private void process(String to, SmmSellerProfile seller, String mediaId, boolean sentAsDocument) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("smm-wm-");
            Path in = dir.resolve("in.video");
            Path out = dir.resolve("out.mp4");
            if (whatsapp.downloadMediaToFile(mediaId, in, MAX_BYTES) == null) {
                whatsapp.sendText(to, "Sorry, we couldn't download that video (WhatsApp allows up to 100 MB as a "
                        + "document). Please try again.");
                return;
            }
            List<String> lines = lines(seller);
            List<Path> lineFiles = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                Path f = dir.resolve("line" + i + ".txt");
                Files.writeString(f, lines.get(i), StandardCharsets.UTF_8);
                lineFiles.add(f);
            }
            List<String> cmd = command(ffmpeg, in, out, lineFiles, font, longest(lines), maxSeconds);
            Path errors = dir.resolve("ffmpeg.log");
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(errors.toFile()).start();
            if (!p.waitFor(15, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IOException("ffmpeg took too long");
            }
            if (p.exitValue() != 0 || !Files.exists(out) || Files.size(out) == 0) {
                throw new IOException("ffmpeg failed (" + p.exitValue() + "): " + tail(errors));
            }
            if (Files.size(out) > MAX_BYTES) {
                whatsapp.sendText(to, "That video is too long to send back on WhatsApp (over 100 MB with the watermark). "
                        + "Please send a shorter one.");
                return;
            }
            String id = whatsapp.uploadMedia(out, "video/mp4");
            if (id == null) throw new IOException("upload to WhatsApp failed");
            String caption = "✅ Post this one. Every buyer who sees it, even on a copied video, is told: pay ONLY to till "
                    + seller.getTillNumber() + " · " + seller.getTillName() + "."
                    + (sentAsDocument ? "" : "\n\nTip: next time send the video as a Document (📎 → Document) so WhatsApp "
                    + "doesn't shrink it first.");
            WhatsAppCloudClient.Sent sent = whatsapp.sendDocument(to, id, "reel-till-" + seller.getTillNumber() + ".mp4", caption);
            log.info("SokoReply: watermarked video for merchant {} ({} bytes) sent: {}", seller.getMerchantId(),
                    Files.size(out), sent.ok() ? sent.messageId() : sent.error());
        } catch (Exception e) {
            log.warn("SokoReply: watermark for merchant {} failed: {}", seller.getMerchantId(), e.getMessage());
            whatsapp.sendText(to, "Sorry, we couldn't add the watermark to that video. Please try again in a few minutes.");
        } finally {
            deleteQuietly(dir);
        }
    }

    // ───────────────────────────── the photo ─────────────────────────────

    private void processImage(String to, SmmSellerProfile seller, String mediaId, boolean sentAsDocument) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("smm-wm-");
            Path in = dir.resolve("in.img");
            Path out = dir.resolve("out.jpg");
            if (whatsapp.downloadMediaToFile(mediaId, in, MAX_BYTES) == null) {
                whatsapp.sendText(to, "Sorry, we couldn't download that photo. Please try again.");
                return;
            }
            List<String> lines = lines(seller);
            List<Path> lineFiles = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                Path f = dir.resolve("line" + i + ".txt");
                Files.writeString(f, lines.get(i), StandardCharsets.UTF_8);
                lineFiles.add(f);
            }
            List<String> cmd = imageCommand(ffmpeg, in, out, lineFiles, font, longest(lines));
            Path errors = dir.resolve("ffmpeg.log");
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(errors.toFile()).start();
            if (!p.waitFor(2, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IOException("ffmpeg took too long");
            }
            if (p.exitValue() != 0 || !Files.exists(out) || Files.size(out) == 0) {
                throw new IOException("ffmpeg failed (" + p.exitValue() + "): " + tail(errors));
            }
            if (Files.size(out) > MAX_BYTES) {
                whatsapp.sendText(to, "Sorry, that photo came out too big to send back on WhatsApp. Please try again.");
                return;
            }
            String id = whatsapp.uploadMedia(out, "image/jpeg");
            if (id == null) throw new IOException("upload to WhatsApp failed");
            String caption = "✅ Post this one. Every buyer who sees it, even on a copied photo, is told: pay ONLY to till "
                    + seller.getTillNumber() + " · " + seller.getTillName() + "."
                    + (sentAsDocument ? "" : "\n\nTip: next time send the photo as a Document (📎 → Document) so WhatsApp "
                    + "doesn't shrink it first.");
            WhatsAppCloudClient.Sent sent = whatsapp.sendDocument(to, id, "post-till-" + seller.getTillNumber() + ".jpg", caption);
            log.info("SokoReply: watermarked photo for merchant {} ({} bytes) sent: {}", seller.getMerchantId(),
                    Files.size(out), sent.ok() ? sent.messageId() : sent.error());
        } catch (Exception e) {
            log.warn("SokoReply: photo watermark for merchant {} failed: {}", seller.getMerchantId(), e.getMessage());
            whatsapp.sendText(to, "Sorry, we couldn't add the watermark to that photo. Please try again in a few minutes.");
        } finally {
            deleteQuietly(dir);
        }
    }

    /**
     * The ffmpeg command for a still photo: the same two lines of text, burnt in at both the upper and the middle
     * spot at once, since a single frame has no time to jump between them the way a video does.
     */
    static List<String> imageCommand(String ffmpeg, Path in, Path out, List<Path> lineFiles, String font, int longestLine) {
        String size = "min(h/30\\,w*0.9/(0.62*" + Math.max(longestLine, 10) + "))";
        StringBuilder vf = new StringBuilder();
        for (String yFraction : new String[]{"0.14", "0.40"}) {
            for (int i = 0; i < lineFiles.size(); i++) {
                if (vf.length() > 0) vf.append(",");
                String y = "h*" + yFraction + "+" + i + "*1.55*" + size;
                vf.append("drawtext=fontfile='").append(escape(font)).append("':textfile='").append(escape(lineFiles.get(i).toString()))
                        .append("':fontsize='").append(size).append("':fontcolor=white")
                        .append(":box=1:boxcolor=black@0.6:boxborderw=12")
                        .append(":x=(w-text_w)/2:y='").append(y).append("'");
            }
        }
        return List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                "-i", in.toString(),
                "-vf", vf.toString(),
                "-frames:v", "1", "-q:v", "3",
                out.toString());
    }

    /** What the buyer reads: an instruction, not just a label, matching the line in the agents' replies. */
    static List<String> lines(SmmSellerProfile p) {
        return List.of("Pay ONLY to Till " + p.getTillNumber(),
                p.getTillName().trim().toUpperCase(Locale.ROOT),
                "We never send another number");
    }

    static int longest(List<String> lines) {
        return lines.stream().mapToInt(String::length).max().orElse(20);
    }

    /**
     * The ffmpeg command. The text is centred, in a dark box, as big as the frame allows; it sits at 14% of the
     * height, then at 40%, swapping every {@link #HOP_SECONDS} seconds. Phone videos are turned upright first
     * (ffmpeg does that by default), so the sizes refer to what the buyer sees.
     */
    static List<String> command(String ffmpeg, Path in, Path out, List<Path> lineFiles, String font, int longestLine, int maxSeconds) {
        // Size: 1/30 of the height, but small enough for the longest line to fit 90% of the width (DejaVu Bold ≈ 0.62em per char).
        String size = "min(h/30\\,w*0.9/(0.62*" + Math.max(longestLine, 10) + "))";
        String top = "if(lt(mod(t\\," + (2 * HOP_SECONDS) + ")\\," + HOP_SECONDS + ")\\,h*0.14\\,h*0.40)";
        StringBuilder vf = new StringBuilder("scale=trunc(iw/2)*2:trunc(ih/2)*2");
        for (int i = 0; i < lineFiles.size(); i++) {
            // Each line centred on its own, one under the other (works on ffmpeg versions without text_align)
            String y = top + "+" + i + "*1.55*" + size;
            vf.append(",drawtext=fontfile='").append(escape(font)).append("':textfile='").append(escape(lineFiles.get(i).toString()))
                    .append("':fontsize='").append(size).append("':fontcolor=white")
                    .append(":box=1:boxcolor=black@0.6:boxborderw=12")
                    .append(":x=(w-text_w)/2:y='").append(y).append("'");
        }
        return List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                "-i", in.toString(), "-t", String.valueOf(maxSeconds),
                "-vf", vf.toString(),
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart",
                out.toString());
    }

    /** Paths inside an ffmpeg filter: backslashes become slashes, and ':' and quotes are escaped. */
    static String escape(String path) {
        return path.replace('\\', '/').replace("'", "").replace(":", "\\:");
    }

    private static String tail(Path log) {
        try {
            String s = Files.readString(log);
            return s.length() <= 400 ? s.trim() : s.substring(s.length() - 400).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (var files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        } catch (IOException ignored) {
            // temp files; the OS cleans them up eventually
        }
    }

}
