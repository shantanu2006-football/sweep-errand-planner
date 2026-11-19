package edu.atlas.sweep.algo;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KmpTest {
    @Test void failureTableClassic() {
        assertArrayEquals(new int[]{0, 0, 1, 2, 0}, Kmp.failure("ababc"));
        assertArrayEquals(new int[]{0, 1, 2, 3}, Kmp.failure("aaaa"));
        assertArrayEquals(new int[]{0, 0, 0, 1, 2, 3, 4}, Kmp.failure("abcabca"));
    }

    @Test void findsOverlappingOccurrences() {
        assertEquals(List.of(0, 1, 2), Kmp.searchAll("aaaa", "aa"));
        assertEquals(List.of(0, 4, 7), Kmp.searchAll("abcxabcabc", "abc"));
    }

    @Test void indexOfMatchesStringIndexOfOnRandomText() {
        Random r = new Random(3);
        for (int t = 0; t < 500; t++) {
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 60; i++) s.append((char) ('a' + r.nextInt(3)));
            String p = s.substring(r.nextInt(50), 50 + r.nextInt(5));
            if (r.nextBoolean()) p = p + "c";
            assertEquals(s.indexOf(p), Kmp.indexOf(s.toString(), p), s + " / " + p);
            assertEquals(s.indexOf(p), Kmp.naiveIndexOf(s.toString(), p));
        }
    }

    @Test void edgeCases() {
        assertEquals(-1, Kmp.indexOf("", "a"));
        assertEquals(-1, Kmp.indexOf("ab", "abc"));            // pattern longer than text
        assertEquals(0, Kmp.indexOf("abc", "abc"));             // pattern == text
        assertEquals(-1, Kmp.indexOf(null, "a"));
        assertTrue(Kmp.searchAll("xyz", "q").isEmpty());
    }

    @Test void adversarialInputIsLinear() {
        String text = "a".repeat(2_000_000) + "b";
        String pat = "a".repeat(5_000) + "b";
        long t = System.nanoTime();
        assertEquals(text.length() - pat.length(), Kmp.indexOf(text, pat));
        assertTrue(System.nanoTime() - t < 1_000_000_000L, "KMP must not degrade to n*m");
    }

    @Test void rejectsEmptyPattern() {
        assertThrows(IllegalArgumentException.class, () -> Kmp.failure(""));
        assertThrows(IllegalArgumentException.class, () -> Kmp.indexOf("abc", null));
    }
}
