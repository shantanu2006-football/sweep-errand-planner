package edu.atlas.sweep.algo;

import java.util.*;

/**
 * Weighted set cover (Unit 5, CO4). Universe = the n requested items, sets = stores.
 *
 * greedy(): repeatedly pick the set minimising  (cost_j + lambda * premium of newly covered)
 *           / |newly covered|.  With lambda = 0 this is the classic greedy, which is an
 *           H(n) <= ln n + 1 approximation of the optimum (Chvatal 1979). With lambda > 0
 *           the price-premium term makes it a heuristic: no approximation guarantee is
 *           claimed for that mode.
 *           Time O(m * n * picks) <= O(m n min(m,n)), space O(m n).
 *
 * exact():  dynamic program over subsets of the universe, dp[mask] = min cost to cover mask.
 *           O(2^n * m) time, O(2^n) space - only for n <= 20. Used as the optimality
 *           baseline. Set cover is NP-hard, so no polynomial exact algorithm is expected.
 */
public final class SetCover {

    private SetCover() {}

    /**
     * @param covers  covers[j] = indices of universe elements set j contains
     * @param cost    cost[j] >= 0
     * @param premium premium[j][e] >= 0 extra price of element e at set j (may be null)
     */
    public record Instance(int n, int[][] covers, double[] cost, double[][] premium) {
        public Instance {
            if (n < 0) throw new IllegalArgumentException("n must be >= 0");
            if (covers.length != cost.length) throw new IllegalArgumentException("covers/cost length mismatch");
            for (int j = 0; j < covers.length; j++) {
                if (cost[j] < 0 || Double.isNaN(cost[j])) throw new IllegalArgumentException("negative cost at set " + j);
                for (int e : covers[j]) if (e < 0 || e >= n) throw new IllegalArgumentException("element out of range: " + e);
            }
        }
    }

    /** One greedy pick, kept for the UI's step-by-step trace. */
    public record Step(int set, List<Integer> newlyCovered, double ratio) {}

    public record Result(List<Integer> chosen, double cost, List<Integer> uncoverable, List<Step> steps) {}

    public static Result greedy(Instance in, double lambda) {
        if (lambda < 0) throw new IllegalArgumentException("lambda must be >= 0");
        boolean[] covered = new boolean[in.n];
        List<Integer> uncoverable = uncoverable(in);
        for (int e : uncoverable) covered[e] = true;       // drop them, report separately
        int remaining = in.n - uncoverable.size();
        boolean[] used = new boolean[in.covers.length];
        List<Integer> chosen = new ArrayList<>();
        List<Step> steps = new ArrayList<>();
        double total = 0;

        while (remaining > 0) {
            int best = -1;
            double bestRatio = Double.POSITIVE_INFINITY;
            int bestNew = 0;
            for (int j = 0; j < in.covers.length; j++) {
                if (used[j]) continue;
                int fresh = 0;
                double prem = 0;
                for (int e : in.covers[j]) if (!covered[e]) {
                    fresh++;
                    if (in.premium != null) prem += in.premium[j][e];
                }
                if (fresh == 0) continue;
                double ratio = (in.cost[j] + lambda * prem) / fresh;
                // tie-break: more new elements, then lower index (deterministic output)
                if (ratio < bestRatio - 1e-12 || (Math.abs(ratio - bestRatio) <= 1e-12 && fresh > bestNew)) {
                    best = j; bestRatio = ratio; bestNew = fresh;
                }
            }
            used[best] = true;
            chosen.add(best);
            total += in.cost[best];
            List<Integer> fresh = new ArrayList<>();
            for (int e : in.covers[best]) if (!covered[e]) { covered[e] = true; fresh.add(e); }
            remaining -= fresh.size();
            steps.add(new Step(best, fresh, bestRatio));
        }
        return new Result(chosen, total, uncoverable, steps);
    }

    public static Result exact(Instance in) {
        if (in.n > 20) throw new IllegalArgumentException("exact set cover limited to n <= 20 (2^n states)");
        List<Integer> uncoverable = uncoverable(in);
        int target = 0;
        for (int e = 0; e < in.n; e++) if (!uncoverable.contains(e)) target |= 1 << e;
        int m = in.covers.length;
        int[] mask = new int[m];
        for (int j = 0; j < m; j++) for (int e : in.covers[j]) mask[j] |= 1 << e;

        double[] dp = new double[1 << in.n];
        int[] via = new int[1 << in.n];
        int[] prev = new int[1 << in.n];
        Arrays.fill(dp, Double.POSITIVE_INFINITY);
        dp[0] = 0;
        // dp over masks in increasing order: adding a set only grows the mask
        for (int s = 0; s < dp.length; s++) {
            if (dp[s] == Double.POSITIVE_INFINITY || (s & ~target) != 0) continue;
            for (int j = 0; j < m; j++) {
                int t = (s | mask[j]) & target;
                if (t == s) continue;
                double c = dp[s] + in.cost[j];
                if (c < dp[t]) { dp[t] = c; via[t] = j; prev[t] = s; }
            }
        }
        List<Integer> chosen = new ArrayList<>();
        for (int s = target; s != 0; s = prev[s]) chosen.add(via[s]);
        Collections.reverse(chosen);
        return new Result(chosen, dp[target], uncoverable, List.of());
    }

    private static List<Integer> uncoverable(Instance in) {
        boolean[] any = new boolean[in.n];
        for (int[] c : in.covers) for (int e : c) any[e] = true;
        List<Integer> out = new ArrayList<>();
        for (int e = 0; e < in.n; e++) if (!any[e]) out.add(e);
        return out;
    }

    /** H(n) = 1 + 1/2 + ... + 1/n, the greedy's worst-case approximation factor. */
    public static double harmonic(int n) {
        double h = 0;
        for (int i = 1; i <= n; i++) h += 1.0 / i;
        return h;
    }
}
