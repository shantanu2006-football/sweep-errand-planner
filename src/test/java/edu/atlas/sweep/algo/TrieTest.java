package edu.atlas.sweep.algo;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TrieTest {
    private Trie sample() {
        Trie t = new Trie();
        t.insert("Toor Dal 1kg", 1);
        t.insert("Moong Dal 500g", 2);
        t.insert("Milk 1L", 3);
        t.insert("Milk Powder 500g", 4);
        t.insert("Mineral Water 1L", 5);
        return t;
    }

    @Test void prefixOfFirstWord() {
        assertEquals(List.of(3, 4), sample().suggest("mil", 10).ids());
    }

    @Test void matchesWordStartsInsideName() {
        // "dal" is the second word of two names
        assertEquals(List.of(1, 2), sample().suggest("dal", 10).ids().stream().sorted().toList());
    }

    @Test void caseAndWhitespaceInsensitive() {
        assertEquals(sample().suggest("mi", 10).ids(), sample().suggest("  MI ", 10).ids());
    }

    @Test void respectsK() {
        assertEquals(2, sample().suggest("m", 2).ids().size());
    }

    @Test void edgeEmptyAndMissingPrefix() {
        Trie t = sample();
        assertTrue(t.suggest("", 5).ids().isEmpty());
        assertTrue(t.suggest("zzz", 5).ids().isEmpty());
        assertFalse(t.containsPrefix("zzz"));
    }

    @Test void midWordSubstringIsNotAMatch() {
        // "lk" occurs inside "milk" but is not a word start
        assertTrue(sample().suggest("lk", 5).ids().isEmpty());
    }

    @Test void invalidArguments() {
        Trie t = new Trie();
        assertThrows(IllegalArgumentException.class, () -> t.insert("   ", 1));
        assertThrows(IllegalArgumentException.class, () -> t.suggest("a", 0));
    }

    @Test void stressAgreesWithLinearScan() {
        Trie t = new Trie();
        java.util.Random r = new java.util.Random(7);
        java.util.List<String> words = new java.util.ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            String w = Long.toString(r.nextLong() & 0xffffffL, 36) + " " + Long.toString(r.nextLong() & 0xfffL, 36);
            words.add(w); t.insert(w, i);
        }
        for (String p : new String[]{"a", "1z", "b3", "k"}) {
            java.util.Set<Integer> expect = new java.util.HashSet<>();
            for (int i = 0; i < words.size(); i++) if (words.get(i).startsWith(p) || words.get(i).contains(" " + p)) expect.add(i);
            assertEquals(expect, new java.util.HashSet<>(t.suggest(p, 100000).ids()), "prefix " + p);
        }
    }
}
