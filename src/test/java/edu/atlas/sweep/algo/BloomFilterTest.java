package edu.atlas.sweep.algo;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class BloomFilterTest {
    @Test void noFalseNegatives() {
        BloomFilter bf = new BloomFilter(5000, 0.01, new Random(1));
        for (int i = 0; i < 5000; i++) bf.add("k" + i);
        for (int i = 0; i < 5000; i++) assertTrue(bf.mightContain("k" + i));
    }

    @Test void emptyFilterContainsNothing() {
        BloomFilter bf = new BloomFilter(100, 0.01, new Random(1));
        assertFalse(bf.mightContain("anything"));
        assertEquals(0, bf.bitCount());
    }

    @Test void sizingFormula() {
        BloomFilter bf = new BloomFilter(1000, 0.01, new Random(1));
        // m = ceil(-1000 ln 0.01 / ln2^2) = 9586, k = round(9.586 * ln 2) = 7
        assertEquals(9586, bf.m());
        assertEquals(7, bf.k());
    }

    @Test void observedFpRateNearTheory() {
        BloomFilter bf = new BloomFilter(10_000, 0.01, new Random(3));
        for (int i = 0; i < 10_000; i++) bf.add("in" + i);
        int fp = 0, probes = 200_000;
        for (int i = 0; i < probes; i++) if (bf.mightContain("out" + i)) fp++;
        double observed = (double) fp / probes;
        assertEquals(bf.theoreticalFpRate(), observed, 0.004, "observed " + observed);
    }

    @Test void saturatedFilterSaysMaybeForEverything() {
        BloomFilter bf = new BloomFilter(16, 2, 99L);   // tiny: boundary case
        for (int i = 0; i < 500; i++) bf.add("x" + i);
        assertEquals(16, bf.bitCount());
        assertTrue(bf.mightContain("never-added"));
        assertTrue(bf.theoreticalFpRate() > 0.99);
    }

    @Test void positionsAreDeterministicAndInRange() {
        BloomFilter bf = new BloomFilter(500, 0.05, new Random(9));
        int[] a = bf.positions("milk"), b = bf.positions("milk");
        assertArrayEquals(a, b);
        for (int p : a) assertTrue(p >= 0 && p < bf.m());
    }

    @Test void invalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter(0, 0.01, new Random()));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter(10, 1.0, new Random()));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter(10, 0.01, new Random()).add(null));
    }
}
