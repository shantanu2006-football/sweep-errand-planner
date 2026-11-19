package edu.atlas.sweep.algo;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SetCoverTest {
    private static SetCover.Instance inst(int n, int[][] covers, double... cost) {
        return new SetCover.Instance(n, covers, cost, null);
    }

    private static boolean covers(SetCover.Instance in, SetCover.Result r) {
        Set<Integer> got = new HashSet<>(r.uncoverable());
        for (int j : r.chosen()) for (int e : in.covers()[j]) got.add(e);
        return got.size() == in.n();
    }

    @Test void greedyPicksBestRatio() {
        // set 0 covers all 4 for 10 (ratio 2.5); singletons cost 1 each (ratio 1) -> greedy takes singletons
        SetCover.Instance in = inst(4, new int[][]{{0, 1, 2, 3}, {0}, {1}, {2}, {3}}, 10, 1, 1, 1, 1);
        SetCover.Result r = SetCover.greedy(in, 0);
        assertEquals(4.0, r.cost());
        assertTrue(covers(in, r));
    }

    @Test void classicGreedyTrapIsWithinHn() {
        // OPT = {A,B} cost 2; greedy is lured by the big cheap-per-element sets
        int[][] c = {{0, 1, 2, 3, 4, 5, 6}, {7, 8, 9, 10, 11, 12, 13}, {0, 7, 1, 8, 2, 9, 3, 10}, {4, 11, 5, 12}, {6, 13}};
        SetCover.Instance in = inst(14, c, 1.0, 1.0, 1.0, 0.6, 0.3);
        double g = SetCover.greedy(in, 0).cost(), opt = SetCover.exact(in).cost();
        assertEquals(1.9, opt, 1e-9);
        assertTrue(g >= opt - 1e-9);
        assertTrue(g <= SetCover.harmonic(14) * opt + 1e-9);
    }

    @Test void exactMatchesBruteForceOnRandom() {
        Random r = new Random(11);
        for (int t = 0; t < 30; t++) {
            int n = 6, m = 7;
            int[][] c = new int[m][];
            double[] cost = new double[m];
            for (int j = 0; j < m; j++) {
                List<Integer> l = new ArrayList<>();
                for (int e = 0; e < n; e++) if (r.nextBoolean()) l.add(e);
                c[j] = l.stream().mapToInt(Integer::intValue).toArray();
                cost[j] = 1 + r.nextInt(9);
            }
            SetCover.Instance in = inst(n, c, cost);
            double brute = Double.POSITIVE_INFINITY;
            for (int s = 0; s < 1 << m; s++) {
                int mask = 0; double sc = 0;
                for (int j = 0; j < m; j++) if ((s >> j & 1) == 1) { sc += cost[j]; for (int e : c[j]) mask |= 1 << e; }
                int need = 0; for (int[] cj : c) for (int e : cj) need |= 1 << e;
                if (mask == need) brute = Math.min(brute, sc);
            }
            assertEquals(brute, SetCover.exact(in).cost(), 1e-9);
            assertTrue(SetCover.greedy(in, 0).cost() >= brute - 1e-9);
        }
    }

    @Test void edgeUncoverableReportedNotDropped() {
        SetCover.Instance in = inst(3, new int[][]{{0}, {1}}, 1, 1);
        SetCover.Result r = SetCover.greedy(in, 0);
        assertEquals(List.of(2), r.uncoverable());
        assertEquals(2, r.chosen().size());
    }

    @Test void edgeEmptyUniverse() {
        SetCover.Result r = SetCover.greedy(inst(0, new int[][]{}, new double[]{}), 0);
        assertTrue(r.chosen().isEmpty());
        assertEquals(0, r.cost());
    }

    @Test void lambdaPrefersCheaperPrices() {
        // two stores cover item 0 at equal visit cost; store 1 has a big price premium
        double[][] prem = {{0}, {50}};
        SetCover.Instance in = new SetCover.Instance(1, new int[][]{{0}, {0}}, new double[]{10, 9}, prem);
        assertEquals(List.of(1), SetCover.greedy(in, 0).chosen());   // visit cost only
        assertEquals(List.of(0), SetCover.greedy(in, 1).chosen());   // price matters
    }

    @Test void invalidInput() {
        assertThrows(IllegalArgumentException.class, () -> inst(2, new int[][]{{5}}, 1));
        assertThrows(IllegalArgumentException.class, () -> inst(2, new int[][]{{0}}, -1));
        assertThrows(IllegalArgumentException.class, () -> SetCover.greedy(inst(1, new int[][]{{0}}, 1), -0.5));
    }
}
