package io.github.jumpstartpz.core.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The client against answers in the Worker's format (see cloud/test/worker.test.js). */
class LibraryClientTest {
    private static final String ANSWER = String.join("\n",
            "jumpstart-library 1",
            "total\t2",
            "next\tAoJ4+/=",
            "item\t111\tJava Mod One\t500\t1763700000\t1\tBuild 42,Framework\tdesc one\t555,556,bad",
            "item\t201\tLua Mod\t7\t1763600000\t0\t\t",
            "future\tfield",
            "");

    @Test
    void parsesAPageAndBuildsTheRequest() throws IOException {
        List<URI> asked = new ArrayList<>();
        LibraryClient client = new LibraryClient(URI.create("https://cloud.example"), uri -> {
            asked.add(uri);
            return ANSWER;
        });
        LibraryClient.Page page = client.search(LibraryClient.Kind.JAVA, " better fps ", LibraryClient.Sort.POPULAR, "AoJ4+/=");
        assertEquals("https://cloud.example/v1/library?kind=java&sort=popular&q=better+fps&cursor=AoJ4%2B%2F%3D", asked.get(0).toString());
        assertEquals(2, page.total());
        assertEquals("AoJ4+/=", page.next());
        assertEquals(2, page.items().size());
        LibraryClient.Item java = page.items().get(0);
        assertEquals(111, java.id());
        assertEquals("Java Mod One", java.title());
        assertTrue(java.java());
        assertEquals(List.of("Build 42", "Framework"), java.tags());
        assertEquals(List.of(555L, 556L), java.requires(), "the Workshop items it needs");
        LibraryClient.Item lua = page.items().get(1);
        assertFalse(lua.java());
        assertEquals(List.of(), lua.tags());
        assertEquals("", lua.description());
        assertEquals(List.of(), lua.requires(), "an answer from a Worker without the field");
    }

    @Test
    void refusesSomethingElse() {
        LibraryClient client = new LibraryClient(URI.create("https://cloud.example"), uri -> "<html>captive portal</html>");
        assertThrows(IOException.class, () -> client.search(LibraryClient.Kind.LUA, "", LibraryClient.Sort.RECENT, null));
    }
}
