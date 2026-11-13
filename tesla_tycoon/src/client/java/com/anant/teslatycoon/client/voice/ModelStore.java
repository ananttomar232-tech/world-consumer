package com.anant.teslatycoon.client.voice;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/**
 * The speech model is too big for the mod jar: it is downloaded once (the first time push-to-talk is used) from the official
 * whisper.cpp model repository into config/tesla_tycoon/, and checked against its SHA-256 before it is used.
 */
public final class ModelStore {
    private ModelStore() {}

    private static String sha(String model) {
        return switch (model) {
            case "tiny" -> "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21";
            case "small" -> "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b";
            default -> "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe";
        };
    }

    public static long sizeMb(String model) {
        return switch (model) {
            case "tiny" -> 75;
            case "small" -> 488;
            default -> 148;
        };
    }

    public static Path path(String model) {
        return VoiceConfig.dir().resolve("ggml-" + model + ".bin");
    }

    public static boolean present(String model) {
        Path p = path(model);
        try {
            return Files.exists(p) && Files.size(p) > sizeMb(model) * 1000_000L * 9 / 10;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    public interface Progress {
        void update(long done, long total);
    }

    /** Downloads and verifies the model (blocking: call it on a worker thread). */
    public static Path download(String model, Progress progress) throws Exception {
        Path target = path(model);
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(20)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-" + model + ".bin"))
                .header("User-Agent", "TeslaTycoon-Mod").GET().build();
        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            throw new java.io.IOException("download failed: HTTP " + res.statusCode());
        }
        long total = res.headers().firstValueAsLong("Content-Length").orElse(sizeMb(model) * 1000_000L);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = res.body(); OutputStream out = Files.newOutputStream(part)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                md.update(buf, 0, n);
                done += n;
                progress.update(done, total);
            }
        }
        String got = HexFormat.of().formatHex(md.digest());
        if (!got.equals(sha(model))) {
            Files.deleteIfExists(part);
            throw new java.io.IOException("the downloaded model is damaged (checksum mismatch), try again");
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
}
