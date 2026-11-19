package edu.atlas.sweep.algo;

import java.util.Random;

/**
 * Bloom filter (Unit 6, CO5) with Kirsch-Mitzenmacher double hashing:
 * position_i = (h1 + i * h2) mod m, for i = 0..k-1.
 *
 * Sized from the expected element count n and target false-positive rate p:
 *   m = ceil(-n ln p / (ln 2)^2),  k = round((m / n) ln 2)
 * Theoretical FP rate after n insertions: (1 - e^{-kn/m})^k.
 *
 * Randomization: the hash salt is drawn at construction, so an adversary can't
 * pre-compute keys that collide (and two filters for different stores are independent).
 *
 * No false negatives: every add sets all k bits, and bits are never cleared.
 * add / mightContain: O(k) time. Space: m bits.
 */
public final class BloomFilter {

    private final long[] bits;
    private final int m;
    private final int k;
    private final long salt;
    private int inserted;

    public BloomFilter(int expectedElements, double fpRate, Random rng) {
        if (expectedElements <= 0) throw new IllegalArgumentException("expectedElements must be > 0");
        if (!(fpRate > 0 && fpRate < 1)) throw new IllegalArgumentException("fpRate must be in (0,1)");
        double ln2 = Math.log(2);
        this.m = Math.max(8, (int) Math.ceil(-expectedElements * Math.log(fpRate) / (ln2 * ln2)));
        this.k = Math.max(1, (int) Math.round((double) m / expectedElements * ln2));
        this.bits = new long[(m + 63) >>> 6];
        this.salt = rng.nextLong();
    }

    /** Explicit-geometry constructor (used by tests and the bits-per-element benchmark). */
    public BloomFilter(int m, int k, long salt) {
        if (m <= 0 || k <= 0) throw new IllegalArgumentException("m and k must be > 0");
        this.m = m;
        this.k = k;
        this.bits = new long[(m + 63) >>> 6];
        this.salt = salt;
    }

    public void add(String key) {
        for (int p : positions(key)) bits[p >>> 6] |= 1L << (p & 63);
        inserted++;
    }

    /** false => definitely absent. true => POSSIBLY present; caller must confirm exactly. */
    public boolean mightContain(String key) {
        for (int p : positions(key)) if ((bits[p >>> 6] & (1L << (p & 63))) == 0) return false;
        return true;
    }

    /** The k bit positions for a key (exposed for the UI's bit-array visualisation). */
    public int[] positions(String key) {
        if (key == null) throw new IllegalArgumentException("null key");
        long h1 = mix64(fnv1a64(key) ^ salt);
        long h2 = mix64(h1 + 0x9E3779B97F4A7C15L);
        // reduce first, then step in long arithmetic: avoids 64-bit wrap-around skewing the
        // positions, and a non-zero stride guarantees the k probes are not all the same bit
        long a = Long.remainderUnsigned(h1, m);
        long b = 1 + Long.remainderUnsigned(h2, m - 1 > 0 ? m - 1 : 1);
        int[] out = new int[k];
        for (int i = 0; i < k; i++) out[i] = (int) ((a + i * b) % m);
        return out;
    }

    public boolean isBitSet(int pos) {
        if (pos < 0 || pos >= m) throw new IllegalArgumentException("bit position out of range");
        return (bits[pos >>> 6] & (1L << (pos & 63))) != 0;
    }

    public double theoreticalFpRate() {
        return Math.pow(1 - Math.exp(-(double) k * inserted / m), k);
    }

    public int bitCount() {
        int c = 0;
        for (long w : bits) c += Long.bitCount(w);
        return c;
    }

    public int m() { return m; }
    public int k() { return k; }
    public int inserted() { return inserted; }
    public int sizeBytes() { return bits.length * 8; }

    static long fnv1a64(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    /** SplitMix64 finaliser: a strong avalanche so h2 is independent-looking from h1. */
    static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
