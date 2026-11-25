package io.github.jumpstartpz.core.market;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The mod libraries of the Jumpstart cloud Worker (jumpstart-pz/cloud): search the Steam Workshop
 * for Java or Lua mods. What comes back is only a list to choose from; nothing from it is trusted
 * for safety (every jar is checked by BiteCheck before it loads).
 */
public final class LibraryClient {
    public enum Kind { JAVA, LUA }

    public enum Sort { POPULAR, RECENT, TREND }

    /** One Workshop item; {@code requires}: the Workshop items it needs (without ZombieBuddy). */
    public record Item(long id, String title, long subscriptions, long updatedEpochSeconds, boolean java,
                       List<String> tags, String description, List<Long> requires) {
        public Item {
            requires = List.copyOf(requires);
        }

        public Item(long id, String title, long subscriptions, long updatedEpochSeconds, boolean java,
                    List<String> tags, String description) {
            this(id, title, subscriptions, updatedEpochSeconds, java, tags, description, List.of());
        }
    }

    /** One page of results; {@code next} is the cursor for the next page, or empty at the end. */
    public record Page(long total, String next, List<Item> items) {
    }

    /** Fetches a URL and returns the body; tests replace it. */
    @FunctionalInterface
    public interface Http {
        String get(URI uri) throws IOException;
    }

    static final String FORMAT = "jumpstart-library 1";

    private final URI base;
    private final Http http;

    public LibraryClient(URI base, Http http) {
        this.base = Objects.requireNonNull(base);
        this.http = Objects.requireNonNull(http);
    }

    public LibraryClient(URI base) {
        this(base, httpGet());
    }

    /** cursor: null or "*" for the first page. */
    public Page search(Kind kind, String query, Sort sort, String cursor) throws IOException {
        StringBuilder q = new StringBuilder("/v1/library?kind=").append(kind.name().toLowerCase(java.util.Locale.ROOT))
                .append("&sort=").append(sort.name().toLowerCase(java.util.Locale.ROOT));
        if (query != null && !query.isBlank()) {
            q.append("&q=").append(URLEncoder.encode(query.strip(), StandardCharsets.UTF_8));
        }
        if (cursor != null && !cursor.isEmpty() && !cursor.equals("*")) {
            q.append("&cursor=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        }
        return parse(http.get(base.resolve(q.toString())));
    }

    static Page parse(String body) throws IOException {
        String[] lines = body.split("\n");
        if (lines.length == 0 || !lines[0].strip().equals(FORMAT)) {
            throw new IOException("not a Jumpstart library answer: " + firstLine(body));
        }
        long total = 0;
        String next = "";
        List<Item> items = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].replace("\r", "").split("\t", -1);
            switch (f[0]) {
                case "total" -> total = number(f, 1);
                case "next" -> next = f.length > 1 ? f[1] : "";
                case "item" -> {
                    if (f.length < 8) {
                        continue;
                    }
                    List<String> tags = f[6].isEmpty() ? List.of() : List.of(f[6].split(","));
                    List<Long> requires = new ArrayList<>();
                    if (f.length > 8) {
                        for (String id : f[8].split(",")) {
                            if (id.strip().matches("[0-9]{1,18}")) {
                                requires.add(Long.parseLong(id.strip()));
                            }
                        }
                    }
                    items.add(new Item(number(f, 1), f[2], number(f, 3), number(f, 4), f[5].equals("1"), tags, f[7], requires));
                }
                default -> {
                    // newer fields are ignored
                }
            }
        }
        return new Page(total, next, List.copyOf(items));
    }

    private static long number(String[] f, int i) {
        try {
            return f.length > i ? Long.parseLong(f[i].strip()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String firstLine(String body) {
        int nl = body.indexOf('\n');
        String line = nl < 0 ? body : body.substring(0, nl);
        return line.length() > 80 ? line.substring(0, 80) : line;
    }

    /** HTTPS GET with timeouts; a status other than 200 becomes an IOException with the server's text. */
    public static Http httpGet() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        return uri -> {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                    .header("user-agent", "Jumpstart").GET().build();
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) {
                    throw new IOException("server answered " + response.statusCode() + ": " + firstLine(response.body()));
                }
                return response.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        };
    }
}
