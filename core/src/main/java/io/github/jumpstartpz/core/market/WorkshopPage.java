package io.github.jumpstartpz.core.market;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

import io.github.bitecheckpz.core.Json;

/**
 * A Steam Workshop item's page for the secure screen: the full description (BBCode turned into plain
 * text) and the preview image. From Steam's public GetPublishedFileDetails, which needs no key; only
 * the item id is sent. The image is fetched only from Steam's own hosts, scaled down and kept as a PNG
 * (the game draws PNG) in a cache folder.
 */
public final class WorkshopPage {
    static final URI DETAILS = URI.create("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/");
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private static final int IMAGE_SIZE = 512;
    private static final Duration CACHE_DAYS = Duration.ofDays(7);

    /** What the page shows; {@code previewUrl} may be empty. */
    public record Page(long id, String title, String description, String previewUrl) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    /** The item's page from Steam; empty if Steam does not know it (or it is hidden). */
    public Optional<Page> fetch(long id) throws IOException {
        String form = "itemcount=1&publishedfileids%5B0%5D=" + id;
        HttpRequest request = HttpRequest.newBuilder(DETAILS).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("Steam: HTTP " + response.statusCode());
            }
            return parse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    @SuppressWarnings("unchecked")
    static Optional<Page> parse(String json) throws IOException {
        Object root;
        try {
            root = Json.parse(json);
        } catch (Json.JsonException e) {
            throw new IOException("Steam's answer is not JSON: " + e.getMessage(), e);
        }
        if (!(root instanceof Map<?, ?> r) || !(r.get("response") instanceof Map<?, ?> response)
                || !(response.get("publishedfiledetails") instanceof List<?> list) || list.isEmpty()
                || !(list.get(0) instanceof Map<?, ?> item)) {
            return Optional.empty();
        }
        Map<String, Object> d = (Map<String, Object>) item;
        if (!"1".equals(text(d.get("result")))) {
            return Optional.empty(); // 9: not found, or not visible
        }
        long id;
        try {
            id = Long.parseLong(text(d.get("publishedfileid")));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        return Optional.of(new Page(id, text(d.get("title")), plainText(text(d.get("description"))), text(d.get("preview_url"))));
    }

    private static String text(Object o) {
        if (o instanceof Json.Num n) {
            return n.text();
        }
        return o == null ? "" : o.toString();
    }

    private static final Pattern URL_TAG = Pattern.compile("(?is)\\[url=[^\\]]*\\](.*?)\\[/url\\]");
    private static final Pattern IMG = Pattern.compile("(?is)\\[(img|previewyoutube)[^\\]]*\\].*?\\[/\\1\\]");
    private static final Pattern HEADING = Pattern.compile("(?i)\\[(h[1-6])\\](.*?)\\[/h[1-6]\\]");
    private static final Pattern ANY_TAG = Pattern.compile("\\[/?[a-zA-Z][a-zA-Z0-9]*(=[^\\]]*)?\\]");

    /** Steam's BBCode as plain text: links keep their text, images go, list items get a bullet. */
    static String plainText(String bbcode) {
        String s = bbcode.replace("\r\n", "\n");
        s = IMG.matcher(s).replaceAll("");
        s = URL_TAG.matcher(s).replaceAll("$1");
        s = HEADING.matcher(s).replaceAll("\n$2\n");
        s = s.replaceAll("(?i)\\[\\*\\]", "\n\u2022 ");
        s = s.replaceAll("(?i)\\[/?(list|olist|table|tr)\\]", "\n");
        s = ANY_TAG.matcher(s).replaceAll("");
        s = s.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n+\u2022 ", "\n\u2022 ").replaceAll("\\n{3,}", "\n\n");
        return s.strip();
    }

    /** True for Steam's own image hosts: nothing else is downloaded. */
    static boolean steamHost(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return "https".equalsIgnoreCase(uri.getScheme())
                && (host.endsWith(".steamusercontent.com") || host.endsWith(".steamstatic.com")
                || host.equals("steamuserimages-a.akamaihd.net") || host.endsWith(".steampowered.com"));
    }

    /**
     * The preview as a PNG of at most 512 px in {@code cacheDir} ({@code <id>.png}), downloaded if it is
     * not there or older than a week. Empty without a preview or for a host that is not Steam's.
     */
    public Optional<Path> preview(Page page, Path cacheDir) throws IOException {
        Path file = cacheDir.resolve(page.id() + ".png");
        if (Files.isRegularFile(file)
                && Files.getLastModifiedTime(file).toInstant().isAfter(Instant.now().minus(CACHE_DAYS))) {
            return Optional.of(file);
        }
        if (page.previewUrl().isBlank()) {
            return Optional.empty();
        }
        URI uri = URI.create(page.previewUrl());
        if (!steamHost(uri)) {
            return Optional.empty();
        }
        byte[] bytes;
        try {
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body().length > MAX_IMAGE_BYTES) {
                return Optional.empty();
            }
            bytes = response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            return Optional.empty(); // a format Java cannot read
        }
        Files.createDirectories(cacheDir);
        Path tmp = cacheDir.resolve(page.id() + ".png.tmp");
        ImageIO.write(scaled(image), "png", tmp.toFile());
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        return Optional.of(file);
    }

    private static BufferedImage scaled(BufferedImage image) {
        double f = Math.min(1.0, (double) IMAGE_SIZE / Math.max(image.getWidth(), image.getHeight()));
        int w = Math.max(1, (int) Math.round(image.getWidth() * f));
        int h = Math.max(1, (int) Math.round(image.getHeight() * f));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}
