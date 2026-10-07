package com.example.streamdav.metadata;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void parsesNestedDocuments() {
        Json.Obj root = Json.parseObject("""
                {"page": 1, "results": [{"id": 603, "title": "The Matrix", "adult": false, "vote_average": 8.2,
                  "genre_ids": [28, 878], "poster_path": null}], "total": -1.5e1}
                """);
        assertEquals(1, root.integer("page"));
        assertEquals(-15.0, root.number("total"));
        Json.Obj movie = root.objects("results").getFirst();
        assertEquals(603, movie.integer("id"));
        assertEquals("The Matrix", movie.text("title"));
        assertFalse(movie.bool("adult"));
        assertEquals(8.2, movie.number("vote_average"));
        assertEquals(List.of(28, 878), movie.integers("genre_ids"));
        assertNull(movie.text("poster_path"));
    }

    @Test
    void missingAndMistypedFieldsAreEmpty() {
        Json.Obj root = Json.parseObject("{\"title\": 5, \"list\": \"x\"}");
        assertNull(root.text("title"));
        assertNull(root.integer("missing"));
        assertTrue(root.objects("list").isEmpty());
        assertTrue(root.object("missing").fields().isEmpty());
        assertTrue(root.object("missing").objects("anything").isEmpty());
    }

    @Test
    void decodesEscapes() {
        Json.Obj root = Json.parseObject("{\"t\": \"Caf\\u00e9 \\\"Noir\\\"\\n\\/\"}");
        assertEquals("Café \"Noir\"\n/", root.text("t"));
    }

    @Test
    void quotesRoundTrip() {
        String tricky = "Re:Zero \"kara\" \\ Hajimeru\n\u0001";
        assertEquals(tricky, Json.parseObject("{\"q\": " + Json.quote(tricky) + "}").text("q"));
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\": }"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1, 2"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("\"open"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{} extra"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("[]"));
    }
}
