package edu.atlas.sweep.algo;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MetricTspTest {
    private static double[][] plane(int n, long seed) {
        Random r = new Random(seed);
        double[] x = new double[n], y = new double[n];
        for (int i = 0; i < n; i++) { x[i] = r.nextDouble() * 10; y[i] = r.nextDouble() * 10; }
        return MetricTsp.euclidean(x, y, 1.0);
    }

    private static void assertPermutationFromZero(List<Integer> tour, int n) {
        assertEquals(0, tour.get(0));
        assertEquals(n, new HashSet<>(tour).size());
        assertEquals(n, tour.size());
    }

    @Test void squareIsSolvedOptimally() {
        double[][] d = MetricTsp.euclidean(new double[]{0, 0, 1, 1}, new double[]{0, 1, 1, 0}, 1);
        MetricTsp.ApproxTrace t = MetricTsp.doubleTreeTwoOpt(d, new Random(1));
        assertEquals(4.0, t.improved().length(), 1e-9);
        assertEquals(4.0, MetricTsp.heldKarp(d).length(), 1e-9);
    }

    @Test void twoApproximationBoundHolds() {
        for (long s = 0; s < 40; s++) {
            double[][] d = plane(9, s);
            MetricTsp.ApproxTrace t = MetricTsp.doubleTreeTwoOpt(d, new Random(s));
            double opt = MetricTsp.heldKarp(d).length();
            assertTrue(t.preorder().length() <= 2 * opt + 1e-9, "double tree within 2x");
            assertTrue(t.improved().length() <= t.preorder().length() + 1e-9, "2-opt never worsens");
            assertTrue(t.mstWeight() <= opt + 1e-9, "MST is a lower bound");
            assertPermutationFromZero(t.improved().order(), 9);
        }
    }

    @Test void mstHasNMinusOneEdgesAndIsMinimal() {
        double[][] d = plane(30, 3);
        List<MetricTsp.Edge> mst = MetricTsp.kruskal(d, new Random(1));
        assertEquals(29, mst.size());
        // compare with a straightforward O(n^2) Prim
        int n = d.length; boolean[] in = new boolean[n]; double[] best = new double[n];
        Arrays.fill(best, Double.POSITIVE_INFINITY); best[0] = 0; double prim = 0;
        for (int it = 0; it < n; it++) {
            int u = -1; for (int v = 0; v < n; v++) if (!in[v] && (u < 0 || best[v] < best[u])) u = v;
            in[u] = true; prim += best[u];
            for (int v = 0; v < n; v++) if (!in[v]) best[v] = Math.min(best[v], d[u][v]);
        }
        assertEquals(prim, mst.stream().mapToDouble(MetricTsp.Edge::w).sum(), 1e-9);
    }

    @Test void heldKarpMatchesPermutationBruteForce() {
        double[][] d = plane(7, 8);
        int[] p = {1, 2, 3, 4, 5, 6};
        double best = Double.POSITIVE_INFINITY;
        do {
            double s = d[0][p[0]] + d[p[p.length - 1]][0];
            for (int i = 0; i + 1 < p.length; i++) s += d[p[i]][p[i + 1]];
            best = Math.min(best, s);
        } while (nextPerm(p));
        assertEquals(best, MetricTsp.heldKarp(d).length(), 1e-9);
    }

    @Test void edgeTinyInstances() {
        assertEquals(0, MetricTsp.doubleTreeTwoOpt(new double[][]{{0}}, new Random()).improved().length());
        double[][] two = {{0, 3}, {3, 0}};
        assertEquals(6, MetricTsp.doubleTreeTwoOpt(two, new Random()).improved().length(), 1e-9);
        assertEquals(6, MetricTsp.heldKarp(two).length(), 1e-9);
        // duplicate points (two stores in one mall)
        double[][] dup = MetricTsp.euclidean(new double[]{0, 2, 2}, new double[]{0, 0, 0}, 1);
        assertEquals(4, MetricTsp.doubleTreeTwoOpt(dup, new Random()).improved().length(), 1e-9);
    }

    @Test void rejectsNonMetricInput() {
        double[][] bad = {{0, 1, 10}, {1, 0, 1}, {10, 1, 0}};      // 10 > 1 + 1
        assertThrows(IllegalArgumentException.class, () -> MetricTsp.doubleTreeTwoOpt(bad, new Random()));
        assertThrows(IllegalArgumentException.class, () -> MetricTsp.heldKarp(new double[][]{{0, 1}, {2, 0}}));
        assertThrows(IllegalArgumentException.class, () -> MetricTsp.nearestNeighbour(new double[][]{}));
    }

    private static boolean nextPerm(int[] a) {
        int i = a.length - 2; while (i >= 0 && a[i] >= a[i + 1]) i--;
        if (i < 0) return false;
        int j = a.length - 1; while (a[j] <= a[i]) j--;
        int t = a[i]; a[i] = a[j]; a[j] = t;
        for (int l = i + 1, r = a.length - 1; l < r; l++, r--) { t = a[l]; a[l] = a[r]; a[r] = t; }
        return true;
    }
}
