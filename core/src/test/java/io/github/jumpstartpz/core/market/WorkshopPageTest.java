package io.github.jumpstartpz.core.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class WorkshopPageTest {

    @Test
    void readsSteamsAnswer() throws IOException {
        String json = """
                {"response":{"result":1,"resultcount":1,"publishedfiledetails":[{"publishedfileid":"3816775774","result":1,
                "title":"Knox Inventory","description":"[h1]Knox[/h1]A grid inventory.[list][*]Rigs[*]Bags[/list]\\r\\nSee [url=https://example.org]the guide[/url].[img]https://x/y.png[/img]",
                "preview_url":"https://images.steamusercontent.com/ugc/1/2/"}]}}""";
        WorkshopPage.Page p = WorkshopPage.parse(json).orElseThrow();
        assertEquals(3816775774L, p.id());
        assertEquals("Knox Inventory", p.title());
        assertEquals("Knox\nA grid inventory.\n• Rigs\n• Bags\n\nSee the guide.", p.description());
        assertEquals("https://images.steamusercontent.com/ugc/1/2/", p.previewUrl());
    }

    @Test
    void unknownOrHiddenItems() throws IOException {
        assertEquals(Optional.empty(), WorkshopPage.parse(
                "{\"response\":{\"result\":1,\"resultcount\":1,\"publishedfiledetails\":[{\"publishedfileid\":\"900001\",\"result\":9}]}}"));
        assertEquals(Optional.empty(), WorkshopPage.parse("{\"response\":{}}"));
    }

    @Test
    void imagesOnlyFromSteam() {
        assertTrue(WorkshopPage.steamHost(URI.create("https://images.steamusercontent.com/ugc/1/2/")));
        assertTrue(WorkshopPage.steamHost(URI.create("https://steamuserimages-a.akamaihd.net/ugc/1/2/")));
        assertTrue(WorkshopPage.steamHost(URI.create("https://shared.akamai.steamstatic.com/x.png")));
        assertFalse(WorkshopPage.steamHost(URI.create("http://images.steamusercontent.com/ugc/1/")), "no plain http");
        assertFalse(WorkshopPage.steamHost(URI.create("https://evil.example/steamusercontent.com/x.png")));
        assertFalse(WorkshopPage.steamHost(URI.create("https://steamusercontent.com.evil.example/x.png")));
    }
}
