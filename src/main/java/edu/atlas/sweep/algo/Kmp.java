package edu.atlas.sweep.algo;

import java.util.ArrayList;
import java.util.List;

/**
 * Knuth-Morris-Pratt string matching (Unit 4, CO3).
 *
 * failure[i] = length of the longest proper prefix of pattern[0..i] that is also a suffix
 * of it. On a mismatch the search falls back to failure[j-1] instead of re-scanning the
 * text, so every text character is examined a bounded number of times.
 *
 * failure table: O(m) time and space. search: O(n + m) time, compared with O(n * m)
 * worst case for the naive "try every shift" method (e.g. text "aaaa...ab", pattern "aaab").
 */
public final class Kmp {

    private Kmp() {}

    public static int[] failure(String pattern) {
        if (pattern == null || pattern.isEmpty()) throw new IllegalArgumentException("empty pattern");
        int m = pattern.length();
        int[] f = new int[m];
        for (int i = 1, k = 0; i < m; i++) {
            while (k > 0 && pattern.charAt(i) != pattern.charAt(k)) k = f[k - 1];
            if (pattern.charAt(i) == pattern.charAt(k)) k++;
            f[i] = k;
        }
        return f;
    }

    /** Start indices of every (possibly overlapping) occurrence of pattern in text. */
    public static List<Integer> searchAll(String text, String pattern) {
        int[] f = failure(pattern);
        List<Integer> out = new ArrayList<>();
        if (text == null) return out;
        int m = pattern.length();
        for (int i = 0, j = 0; i < text.length(); i++) {
            while (j > 0 && text.charAt(i) != pattern.charAt(j)) j = f[j - 1];
            if (text.charAt(i) == pattern.charAt(j)) j++;
            if (j == m) { out.add(i - m + 1); j = f[j - 1]; }
        }
        return out;
    }

    /** First occurrence index or -1. Stops at the first match. */
    public static int indexOf(String text, String pattern) {
        int[] f = failure(pattern);
        if (text == null) return -1;
        int m = pattern.length();
        for (int i = 0, j = 0; i < text.length(); i++) {
            while (j > 0 && text.charAt(i) != pattern.charAt(j)) j = f[j - 1];
            if (text.charAt(i) == pattern.charAt(j)) j++;
            if (j == m) return i - m + 1;
        }
        return -1;
    }

    /** Naive O(n*m) baseline, used only by the benchmark. */
    public static int naiveIndexOf(String text, String pattern) {
        int n = text.length(), m = pattern.length();
        for (int s = 0; s + m <= n; s++) {
            int j = 0;
            while (j < m && text.charAt(s + j) == pattern.charAt(j)) j++;
            if (j == m) return s;
        }
        return -1;
    }
}
