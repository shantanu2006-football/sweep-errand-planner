package edu.atlas.sweep.service;

import edu.atlas.sweep.algo.*;

import java.util.*;
import java.util.function.Supplier;

/**
 * Reproducible micro-benchmarks run on the server (fixed seeds, JIT warm-up, median of reps).
 * Each method returns rows of plain maps so the frontend can chart them directly.
 * Wall-clock numbers depend on the machine; ratios and counts are seed-deterministic.
 */
public final class BenchmarkService {

    private static final long SEED = 2026;
    private final Map<String, List<Map<String, Object>>> cache = new HashMap<>();

    public synchronized List<Map<String, Object>> run(String name, boolean fresh) {
        if (!fresh && cache.containsKey(name)) return cache.get(name);
        List<Map<String, Object>> rows = switch (name) {
            case "trie" -> trie();
            case "bloom" -> bloom();
            case "select" -> select();
            case "setcover" -> setCover();
            case "tsp" -> tsp();
            case "kmp" -> kmp();
            default -> throw new IllegalArgumentException("unknown benchmark '" + name + "' (trie, kmp, bloom, select, setcover, tsp)");
        };
        cache.put(name, rows);
        return rows;
    }

    // ---------------------------------------------------------------- trie vs linear scan
    List<Map<String, Object>> trie() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int n : new int[]{1_000, 10_000, 100_000}) {
            Random r = new Random(SEED);
            List<String> words = new ArrayList<>(n);
            Trie t = new Trie();
            for (int i = 0; i < n; i++) { String w = word(r) + " " + word(r); words.add(w); t.insert(w, i); }
            String[] queries = new String[500];
            for (int i = 0; i < queries.length; i++) { String w = words.get(r.nextInt(n)); queries[i] = w.substring(0, Math.min(3, w.length())); }
            double trieNs = medianNs(() -> { int s = 0; for (String q : queries) s += t.suggest(q, 8).ids().size(); return s; }) / queries.length;
            double scanNs = medianNs(() -> { int s = 0; for (String q : queries) s += linearScan(words, q, 8).size(); return s; }) / queries.length;
            rows.add(row("n", n, "trieNsPerQuery", Math.round(trieNs), "scanNsPerQuery", Math.round(scanNs),
                    "speedup", round(scanNs / trieNs, 1), "trieNodes", t.nodeCount()));
        }
        return rows;
    }

    static List<Integer> linearScan(List<String> words, String prefix, int k) {
        List<Integer> out = new ArrayList<>();
        String p = Trie.normalize(prefix);
        for (int i = 0; i < words.size() && out.size() < k; i++) {
            String w = words.get(i);
            if (w.startsWith(p) || w.contains(" " + p)) out.add(i);
        }
        return out;
    }

    // ---------------------------------------------------------------- KMP vs naive matching
    List<Map<String, Object>> kmp() {
        List<Map<String, Object>> rows = new ArrayList<>();
        String pat = "a".repeat(199) + "b";             // adversarial: almost matches at every shift
        for (int n : new int[]{50_000, 200_000, 800_000}) {
            String text = "a".repeat(n) + "b";
            double k = medianNs(() -> Kmp.indexOf(text, pat));
            double nv = medianNs(() -> Kmp.naiveIndexOf(text, pat));
            Random r = new Random(SEED);
            StringBuilder sb = new StringBuilder(n);
            for (int i = 0; i < n; i++) sb.append((char) ('a' + r.nextInt(26)));
            String rnd = sb.toString(), rp = "zqxjk";
            double kr = medianNs(() -> Kmp.indexOf(rnd, rp));
            double nr = medianNs(() -> Kmp.naiveIndexOf(rnd, rp));
            rows.add(row("n", n, "m", pat.length(), "kmpAdversarialMs", ms(k), "naiveAdversarialMs", ms(nv),
                    "kmpRandomMs", ms(kr), "naiveRandomMs", ms(nr), "speedup", round(nv / k, 1)));
        }
        return rows;
    }

    // ---------------------------------------------------------------- bloom: observed vs theory
    List<Map<String, Object>> bloom() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int n : new int[]{1_000, 10_000, 100_000}) {
            for (double p : new double[]{0.05, 0.01, 0.001}) {
                BloomFilter bf = new BloomFilter(n, p, new Random(SEED));
                Set<String> exact = new HashSet<>();
                for (int i = 0; i < n; i++) { String k = "member-" + i; bf.add(k); exact.add(k); }
                int probes = 100_000, fp = 0;
                for (int i = 0; i < probes; i++) if (bf.mightContain("absent-" + i)) fp++;
                boolean noFalseNeg = true;
                for (int i = 0; i < n; i++) if (!bf.mightContain("member-" + i)) { noFalseNeg = false; break; }
                double bloomNs = medianNs(() -> { int s = 0; for (int i = 0; i < 10_000; i++) if (bf.mightContain("absent-" + i)) s++; return s; }) / 10_000;
                double setNs = medianNs(() -> { int s = 0; for (int i = 0; i < 10_000; i++) if (exact.contains("absent-" + i)) s++; return s; }) / 10_000;
                rows.add(row("n", n, "targetFp", p, "observedFp", round((double) fp / probes, 5),
                        "theoreticalFp", round(bf.theoreticalFpRate(), 5), "m", bf.m(), "k", bf.k(),
                        "bloomBytes", bf.sizeBytes(), "hashSetBytesEst", (long) n * 64,
                        "bloomNs", round(bloomNs, 1), "hashSetNs", round(setNs, 1), "noFalseNegatives", noFalseNeg));
            }
        }
        return rows;
    }

    // ---------------------------------------------------------------- randomized selection
    List<Map<String, Object>> select() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int n : new int[]{10_000, 100_000, 1_000_000}) {
            double[] base = new Random(SEED).doubles(n, 0, 1000).toArray();
            int k = n / 2;
            double rs = medianNs(() -> RandomizedSelect.select(base.clone(), k, new Random(1)));
            double fp = medianNs(() -> RandomizedSelect.selectFirstPivot(base.clone(), k));
            double qs = medianNs(() -> { double[] c = base.clone(); RandomizedSelect.quickSort(c, new Random(1)); return c[k]; });
            rows.add(row("input", "random", "n", n, "randomizedSelectMs", ms(rs), "firstPivotSelectMs", ms(fp), "sortThenPickMs", ms(qs)));
        }
        for (int n : new int[]{2_000, 8_000, 32_000}) {
            double[] sorted = new double[n];
            for (int i = 0; i < n; i++) sorted[i] = i;
            int k = n - 1; // max element: first-pivot quickselect peels one element per round
            double rs = medianNs(() -> RandomizedSelect.select(sorted.clone(), k, new Random(1)));
            double fp = medianNs(() -> RandomizedSelect.selectFirstPivot(sorted.clone(), k));
            double qs = medianNs(() -> { double[] c = sorted.clone(); RandomizedSelect.quickSort(c, new Random(1)); return c[k]; });
            rows.add(row("input", "sorted (adversarial)", "n", n, "randomizedSelectMs", ms(rs), "firstPivotSelectMs", ms(fp), "sortThenPickMs", ms(qs)));
        }
        return rows;
    }

    // ---------------------------------------------------------------- greedy vs exact set cover
    List<Map<String, Object>> setCover() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Random r = new Random(SEED);
        for (int n : new int[]{8, 12, 16, 18}) {
            int trials = 20;
            double sumRatio = 0, worst = 1;
            long gNs = 0, eNs = 0;
            for (int t = 0; t < trials; t++) {
                SetCover.Instance in = randomCover(n, 30, r);
                long a = System.nanoTime();
                double g = SetCover.greedy(in, 0).cost();
                long b = System.nanoTime();
                double e = SetCover.exact(in).cost();
                long c = System.nanoTime();
                gNs += b - a; eNs += c - b;
                double ratio = g / e;
                sumRatio += ratio; worst = Math.max(worst, ratio);
            }
            rows.add(row("n", n, "sets", 30, "avgRatio", round(sumRatio / trials, 4), "worstRatio", round(worst, 4),
                    "boundHn", round(SetCover.harmonic(n), 3), "greedyUs", round(gNs / 1000.0 / trials, 1),
                    "exactUs", round(eNs / 1000.0 / trials, 1)));
        }
        return rows;
    }

    static SetCover.Instance randomCover(int n, int m, Random r) {
        int[][] covers = new int[m][];
        double[] cost = new double[m];
        for (int j = 0; j < m; j++) {
            List<Integer> c = new ArrayList<>();
            for (int e = 0; e < n; e++) if (r.nextDouble() < 0.25) c.add(e);
            if (j < n) c.add(j % n); // guarantee every element is coverable
            covers[j] = c.stream().distinct().mapToInt(Integer::intValue).toArray();
            cost[j] = 20 + r.nextInt(80);
        }
        return new SetCover.Instance(n, covers, cost, null);
    }

    // ---------------------------------------------------------------- TSP approximation quality
    List<Map<String, Object>> tsp() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Random r = new Random(SEED);
        for (int n : new int[]{6, 9, 12}) {
            int trials = 15;
            double sumPre = 0, sumImp = 0, sumNn = 0, worstImp = 1;
            long aNs = 0, hNs = 0;
            for (int t = 0; t < trials; t++) {
                double[][] d = randomPlane(n, r);
                long a = System.nanoTime();
                MetricTsp.ApproxTrace tr = MetricTsp.doubleTreeTwoOpt(d, new Random(t));
                long b = System.nanoTime();
                double opt = MetricTsp.heldKarp(d).length();
                long c = System.nanoTime();
                aNs += b - a; hNs += c - b;
                sumPre += tr.preorder().length() / opt;
                double imp = tr.improved().length() / opt;
                sumImp += imp; worstImp = Math.max(worstImp, imp);
                sumNn += MetricTsp.nearestNeighbour(d).length() / opt;
            }
            rows.add(row("n", n, "doubleTreeRatio", round(sumPre / trials, 4), "withTwoOptRatio", round(sumImp / trials, 4),
                    "worstTwoOptRatio", round(worstImp, 4), "nearestNeighbourRatio", round(sumNn / trials, 4),
                    "approxUs", round(aNs / 1000.0 / trials, 1), "heldKarpUs", round(hNs / 1000.0 / trials, 1), "baseline", "Held-Karp optimum"));
        }
        for (int n : new int[]{50, 200, 800}) {
            double[][] d = randomPlane(n, r);
            long a = System.nanoTime();
            MetricTsp.ApproxTrace tr = MetricTsp.doubleTreeTwoOpt(d, new Random(1));
            long b = System.nanoTime();
            rows.add(row("n", n, "doubleTreeRatio", round(tr.preorder().length() / tr.mstWeight(), 4),
                    "withTwoOptRatio", round(tr.improved().length() / tr.mstWeight(), 4),
                    "nearestNeighbourRatio", round(MetricTsp.nearestNeighbour(d).length() / tr.mstWeight(), 4),
                    "approxUs", round((b - a) / 1000.0, 1), "baseline", "MST lower bound (OPT >= MST)"));
        }
        return rows;
    }

    static double[][] randomPlane(int n, Random r) {
        double[] xs = new double[n], ys = new double[n];
        for (int i = 0; i < n; i++) { xs[i] = r.nextDouble() * 8; ys[i] = r.nextDouble() * 6; }
        return MetricTsp.euclidean(xs, ys, 1.3);
    }

    // ---------------------------------------------------------------- helpers
    private static double medianNs(Supplier<Object> job) {
        for (int i = 0; i < 2; i++) sink(job.get()); // JIT warm-up
        double[] t = new double[5];
        for (int i = 0; i < t.length; i++) { long a = System.nanoTime(); sink(job.get()); t[i] = System.nanoTime() - a; }
        Arrays.sort(t);
        return t[t.length / 2];
    }

    private static volatile Object blackhole;
    private static void sink(Object o) { blackhole = o; }

    private static final String[] SYL = {"ka", "ri", "to", "sa", "mi", "lu", "pe", "do", "na", "vi", "ro", "che", "an", "gu", "be", "sh", "ta", "mo"};
    private static String word(Random r) {
        StringBuilder sb = new StringBuilder();
        int len = 2 + r.nextInt(3);
        for (int i = 0; i < len; i++) sb.append(SYL[r.nextInt(SYL.length)]);
        return sb.toString();
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static double ms(double ns) { return round(ns / 1e6, 3); }
    private static double round(double v, int p) { double f = Math.pow(10, p); return Math.round(v * f) / f; }
}
