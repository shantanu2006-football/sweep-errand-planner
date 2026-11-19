package edu.atlas.sweep.service;

import edu.atlas.sweep.domain.City;
import edu.atlas.sweep.web.Api.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ListParserTest {
    static final City CITY = City.load(Path.of("data"), 42);
    final ListParser p = new ListParser(CITY);

    private ParsedLine one(String s) { return p.parse(s).lines().get(0); }

    @Test void hinglishWhatsappMessage() {
        ParseResponse r = p.parse("Beta ghar aate waqt le aana:\n- 2 doodh\n- ande\n- dolo\n- 3 copy\n- pyaaz 1kg");
        Map<String, Integer> got = new LinkedHashMap<>();
        r.lines().stream().filter(l -> l.itemId() != null).forEach(l -> got.put(l.item(), l.qty()));
        assertEquals(2, got.get("Milk 1L"));
        assertEquals(1, got.get("Eggs 12pk"));
        assertEquals(1, got.get("Paracetamol 500mg Strip"));
        assertEquals(3, got.get("A4 Notebook 200pg"));
        assertEquals(1, got.get("Onions 1kg"));
    }

    @Test void quantityForms() {
        assertEquals(4, one("milk x4").qty());
        assertEquals(3, one("x3 eggs").qty());
        assertEquals(2, one("do dahi").qty());
        assertEquals(5, one("5 pcs gel pen").qty());
        assertEquals(1, one("atta 5kg").qty());          // 5kg is a size, not a quantity
        assertEquals("Atta 5kg", one("atta 5kg").item());
    }

    @Test void brandNamesMapToGenericItems() {
        assertEquals("Detergent Powder 1kg", one("surf excel").item());
        assertEquals("Toilet Cleaner 500ml", one("harpic").item());
        assertEquals("Dishwash Liquid 500ml", one("vim").item());
    }

    @Test void exactNameIsFullConfidence() {
        ParsedLine l = one("Toor Dal 1kg");
        assertEquals("exact", l.matchedBy());
        assertEquals(1.0, l.confidence());
    }

    @Test void unknownThingsAreReportedNotGuessed() {
        ParsedLine l = one("unicorn saddle");
        assertNull(l.itemId());
        assertNotNull(l.note());
    }

    @Test void ambiguousOffersAlternatives() {
        ParsedLine l = one("cable");
        assertNotNull(l.itemId());
        assertFalse(l.alternatives().isEmpty());
    }

    @Test void edgeEmptyAndTooLong() {
        assertThrows(IllegalArgumentException.class, () -> p.parse("   "));
        assertThrows(IllegalArgumentException.class, () -> p.parse("a".repeat(ListParser.MAX_CHARS + 1)));
        assertTrue(p.parse(",,,\n\n;").lines().isEmpty());
    }
}
