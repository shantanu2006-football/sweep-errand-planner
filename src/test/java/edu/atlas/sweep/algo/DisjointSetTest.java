package edu.atlas.sweep.algo;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DisjointSetTest {
    @Test void startsAsSingletons() { DisjointSet d = new DisjointSet(5); assertEquals(5, d.sets()); assertNotEquals(d.find(0), d.find(1)); }
    @Test void unionMerges() { DisjointSet d = new DisjointSet(4); assertTrue(d.union(0, 1)); assertEquals(d.find(0), d.find(1)); assertEquals(3, d.sets()); }
    @Test void repeatedUnionIsNoop() { DisjointSet d = new DisjointSet(3); d.union(0, 1); assertFalse(d.union(1, 0)); assertEquals(2, d.sets()); }
    @Test void transitivity() { DisjointSet d = new DisjointSet(6); d.union(0, 1); d.union(2, 3); d.union(1, 3); assertEquals(d.find(0), d.find(2)); assertNotEquals(d.find(0), d.find(5)); }
    @Test void edgeZeroAndLongChain() {
        assertEquals(0, new DisjointSet(0).sets());
        DisjointSet d = new DisjointSet(200_000);
        for (int i = 1; i < 200_000; i++) d.union(i - 1, i);
        assertEquals(1, d.sets());
        assertEquals(d.find(0), d.find(199_999));
        assertThrows(IllegalArgumentException.class, () -> new DisjointSet(-1));
    }
}
