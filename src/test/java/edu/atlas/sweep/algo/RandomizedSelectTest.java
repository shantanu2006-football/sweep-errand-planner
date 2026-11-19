package edu.atlas.sweep.algo;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class RandomizedSelectTest {
    @Test void everyRankMatchesSorting() {
        Random r = new Random(5);
        double[] a = r.doubles(301, -50, 50).toArray();
        double[] sorted = a.clone(); Arrays.sort(sorted);
        for (int k = 0; k < a.length; k++) assertEquals(sorted[k], RandomizedSelect.select(a.clone(), k, r));
    }

    @Test void singleElement() {
        assertEquals(42, RandomizedSelect.select(new double[]{42}, 0, new Random()));
    }

    @Test void allDuplicatesStayFast() {
        double[] a = new double[200_000];
        Arrays.fill(a, 7);
        long t = System.nanoTime();
        assertEquals(7, RandomizedSelect.select(a, 100_000, new Random(1)));
        assertTrue(System.nanoTime() - t < 500_000_000L, "3-way partition should keep this linear");
    }

    @Test void medianAndPercentile() {
        double[] a = {9, 1, 5, 3, 7};
        assertEquals(5, RandomizedSelect.median(a, new Random(1)));
        assertEquals(1, RandomizedSelect.percentile(a, 0, new Random(1)));
        assertEquals(9, RandomizedSelect.percentile(a, 100, new Random(1)));
        assertArrayEquals(new double[]{9, 1, 5, 3, 7}, a, "helpers must not reorder the caller's array");
    }

    @Test void firstPivotBaselineIsCorrectToo() {
        double[] a = new Random(2).doubles(1000).toArray();
        double[] s = a.clone(); Arrays.sort(s);
        assertEquals(s[123], RandomizedSelect.selectFirstPivot(a, 123));
    }

    @Test void quickSortSortsIncludingSortedAndReversed() {
        Random r = new Random(4);
        for (double[] a : new double[][]{r.doubles(5000).toArray(), new double[]{}, new double[]{3, 2, 1}, {1, 1, 1, 0}}) {
            double[] e = a.clone(); Arrays.sort(e);
            RandomizedSelect.quickSort(a, r);
            assertArrayEquals(e, a);
        }
    }

    @Test void rankFractionAndInvalid() {
        assertEquals(0.4, RandomizedSelect.rankFraction(new double[]{1, 2, 3, 4, 5}, 3), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> RandomizedSelect.select(new double[]{}, 0, new Random()));
        assertThrows(IllegalArgumentException.class, () -> RandomizedSelect.select(new double[]{1, 2}, 2, new Random()));
    }
}
