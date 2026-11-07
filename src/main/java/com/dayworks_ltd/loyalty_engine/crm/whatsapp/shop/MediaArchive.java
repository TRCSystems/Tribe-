package com.dayworks_ltd.loyalty_engine.crm.whatsapp.shop;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Keeps the photos and voice notes customers send for a short time, so a misread order can be checked against
 * what was really sent (and models compared on real photos). Off unless {@code whatsapp.shop.media-dir} is set;
 * files older than {@code whatsapp.shop.media-retention-days} are deleted every night.
 */
@Slf4j
@Component
public class MediaArchive {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @Value("${whatsapp.shop.media-dir:}")
    private String dir = "";

    @Value("${whatsapp.shop.media-retention-days:14}")
    private int retentionDays = 14;

    /** Saves the file and returns its path, or null when archiving is off or the save failed. */
    public Path save(byte[] bytes, String mimeType, String kind, String from) {
        if (dir == null || dir.isBlank() || bytes == null || bytes.length == 0) return null;
        try {
            Path folder = Files.createDirectories(Paths.get(dir.trim()));
            String phone = from == null ? "" : from.replaceAll("\\D", "");
            String tail = phone.length() > 4 ? phone.substring(phone.length() - 4) : phone;
            Path file = folder.resolve(LocalDateTime.now().format(STAMP) + "_" + tail + "_" + kind + extension(mimeType));
            Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
            log.info("Saved WhatsApp {} from …{} to {}", kind, tail, file);
            return file;
        } catch (IOException | RuntimeException e) {
            log.warn("WhatsApp {} could not be saved: {}", kind, e.getMessage());
            return null;
        }
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void deleteOld() {
        if (dir == null || dir.isBlank()) return;
        Path folder = Paths.get(dir.trim());
        if (!Files.isDirectory(folder)) return;
        FileTime cutoff = FileTime.from(Instant.now().minus(retentionDays, ChronoUnit.DAYS));
        int deleted = 0;
        try (Stream<Path> files = Files.list(folder)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (Files.isRegularFile(p) && Files.getLastModifiedTime(p).compareTo(cutoff) < 0) {
                    Files.deleteIfExists(p);
                    deleted++;
                }
            }
        } catch (IOException e) {
            log.warn("Old WhatsApp media could not be cleaned up: {}", e.getMessage());
        }
        if (deleted > 0) log.info("Deleted {} WhatsApp media files older than {} days", deleted, retentionDays);
    }

    private static String extension(String mimeType) {
        String m = String.valueOf(mimeType).toLowerCase(Locale.ROOT);
        if (m.contains("png")) return ".png";
        if (m.contains("webp")) return ".webp";
        if (m.contains("ogg")) return ".ogg";
        if (m.contains("mpeg") || m.contains("mp3")) return ".mp3";
        if (m.contains("mp4") || m.contains("aac") || m.contains("m4a")) return ".m4a";
        if (m.contains("amr")) return ".amr";
        if (m.contains("audio")) return ".audio";
        return ".jpg";
    }
}
