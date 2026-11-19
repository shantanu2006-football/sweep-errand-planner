package edu.atlas.sweep.algo;

import java.util.Random;

/**
 * Randomized Quickselect and Randomized Quicksort (Unit 6, CO5), plus their
 * deterministic first-element-pivot counterparts used as baselines.
 *
 * Randomized quickselect: expected O(n) comparisons for any input, because a uniformly
 * random pivot lands in the middle half with probability 1/2. Worst case O(n^2) only with
 * vanishing probability. The deterministic first-pivot version hits O(n^2) on sorted input,
 * which the Lab benchmark demonstrates.
 *
 * Both work in place: O(1) extra space for select; O(log n) stack for sort
 * (we recurse on the smaller side and loop on the larger).
 */
public final class RandomizedSelect {

    private RandomizedSelect() {}

    /**
     * k-th smallest (0-based) of a; a is reordered. Uses a 3-way (Dutch national flag)
     * partition so arrays with many equal values - common for prices - stay linear.
     */
    public static double select(double[] a, int k, Random rng) {
        check(a, k);
        int lo = 0, hi = a.length - 1;
        while (true) {
            if (lo == hi) return a[lo];
            int[] eq = partition3(a, lo, hi, a[lo + rng.nextInt(hi - lo + 1)]);
            if (k < eq[0]) hi = eq[0] - 1;
            else if (k > eq[1]) lo = eq[1] + 1;
            else return a[k];
        }
    }

    /** Baseline: always pivots on the first element. Quadratic on sorted input. */
    public static double selectFirstPivot(double[] a, int k) {
        check(a, k);
        int lo = 0, hi = a.length - 1;
        while (true) {
            if (lo == hi) return a[lo];
            int[] eq = partition3(a, lo, hi, a[lo]);
            if (k < eq[0]) hi = eq[0] - 1;
            else if (k > eq[1]) lo = eq[1] + 1;
            else return a[k];
        }
    }

    /** Median (lower median for even n) via randomized select on a copy. */
    public static double median(double[] values, Random rng) {
        double[] c = values.clone();
        return select(c, (c.length - 1) / 2, rng);
    }

    /** Value at percentile q in [0,100] (nearest-rank), via randomized select on a copy. */
    public static double percentile(double[] values, double q, Random rng) {
        if (q < 0 || q > 100) throw new IllegalArgumentException("q must be in [0,100]");
        double[] c = values.clone();
        int k = (int) Math.ceil(q / 100.0 * c.length) - 1;
        return select(c, Math.max(0, k), rng);
    }

    /** Fraction of values strictly below x (a "deal score": 0 = cheapest in town). */
    public static double rankFraction(double[] values, double x) {
        if (values.length == 0) throw new IllegalArgumentException("empty array");
        int below = 0;
        for (double v : values) if (v < x) below++;
        return (double) below / values.length;
    }

    public static void quickSort(double[] a, Random rng) {
        sort(a, 0, a.length - 1, rng);
    }

    /** Sorts idx[] by key[idx] using randomized quicksort (used by Kruskal for edge order). */
    public static void quickSortIndices(int[] idx, double[] key, Random rng) {
        sortIdx(idx, key, 0, idx.length - 1, rng);
    }

    private static void sort(double[] a, int lo, int hi, Random rng) {
        while (lo < hi) {
            int[] eq = partition3(a, lo, hi, a[lo + rng.nextInt(hi - lo + 1)]);
            // recurse on the smaller side, loop on the larger: O(log n) stack
            if (eq[0] - lo < hi - eq[1]) { sort(a, lo, eq[0] - 1, rng); lo = eq[1] + 1; }
            else { sort(a, eq[1] + 1, hi, rng); hi = eq[0] - 1; }
        }
    }

    private static void sortIdx(int[] idx, double[] key, int lo, int hi, Random rng) {
        while (lo < hi) {
            int pi = lo + rng.nextInt(hi - lo + 1);
            swap(idx, pi, hi);
            double pivot = key[idx[hi]];
            int store = lo;
            for (int i = lo; i < hi; i++) if (key[idx[i]] < pivot) swap(idx, i, store++);
            swap(idx, store, hi);
            if (store - lo < hi - store) { sortIdx(idx, key, lo, store - 1, rng); lo = store + 1; }
            else { sortIdx(idx, key, store + 1, hi, rng); hi = store - 1; }
        }
    }

    /**
     * 3-way partition of a[lo..hi] around value pivot: afterwards a[lo..lt-1] < pivot,
     * a[lt..gt] == pivot, a[gt+1..hi] > pivot. Returns {lt, gt}. One pass, O(hi-lo).
     */
    static int[] partition3(double[] a, int lo, int hi, double pivot) {
        int lt = lo, i = lo, gt = hi;
        while (i <= gt) {
            if (a[i] < pivot) swap(a, lt++, i++);
            else if (a[i] > pivot) swap(a, i, gt--);
            else i++;
        }
        return new int[]{lt, gt};
    }

    private static void check(double[] a, int k) {
        if (a == null || a.length == 0) throw new IllegalArgumentException("empty array");
        if (k < 0 || k >= a.length) throw new IllegalArgumentException("k out of range: " + k);
    }

    private static void swap(double[] a, int i, int j) { double t = a[i]; a[i] = a[j]; a[j] = t; }
    private static void swap(int[] a, int i, int j) { int t = a[i]; a[i] = a[j]; a[j] = t; }
}
