package edu.atlas.sweep.service;

import edu.atlas.sweep.algo.Kmp;
import edu.atlas.sweep.algo.Trie;
import edu.atlas.sweep.domain.City;
import edu.atlas.sweep.web.Api.ParseResponse;
import edu.atlas.sweep.web.Api.ParsedLine;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a free-text list ("2 doodh, ande, dolo, 3 copy, surf excel") into catalogue items.
 *
 * Per line:
 *   1. split on newlines, commas, "and"/"aur", bullets and numbering
 *   2. pull out a quantity (2, x2, 2 pcs, "do", "teen") but keep sizes like "5kg" for matching
 *   3. map Hinglish words and brand names to catalogue vocabulary (doodh -> milk, dolo -> paracetamol)
 *   4. candidate generation with the TRIE (every word-start prefix of every token)
 *   5. scoring with KMP: each query token found inside a candidate name earns its length,
 *      with a bonus when it starts a word; confidence = earned / total query length
 *
 * Cost per line: O(T * (|t| + V)) for the trie lookups plus O(C * (L + |t|)) per token for
 * KMP over C candidates of length L - tiny for a 176-item catalogue, and independent of
 * the catalogue size apart from C.
 */
public final class ListParser {

    public static final int MAX_CHARS = 3000;
    static final double MIN_CONFIDENCE = 0.5;

    private final City city;

    public ListParser(City city) { this.city = city; }

    private static final Pattern SPLIT = Pattern.compile("\\r?\\n|,|;|\\+|\\s&\\s|\\band\\b|\\baur\\b|\\bthen\\b");
    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-*\u2022\u25CF\u2023>]+|\\d{1,2}[.)]\\s)\\s*");
    private static final Pattern LEAD_QTY = Pattern.compile("^(\\d{1,2})\\s*(?:x|\u00d7|pcs?|pieces?|nos?|packets?|packs?|pkts?|bottles?|boxes?)?\\s+(?=\\D)");
    private static final Pattern TAIL_QTY = Pattern.compile("\\s+(?:x|\u00d7)\\s*(\\d{1,2})$|\\s+(\\d{1,2})\\s*(?:x|\u00d7|pcs?|pieces?|nos?|packets?|packs?|pkts?|bottles?)?$");
    private static final Pattern X_QTY = Pattern.compile("^(?:x|\u00d7)\\s*(\\d{1,2})\\s+");

    private static final Map<String, Integer> NUM_WORDS = Map.ofEntries(
            Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4), Map.entry("five", 5),
            Map.entry("six", 6), Map.entry("ten", 10), Map.entry("ek", 1), Map.entry("do", 2), Map.entry("teen", 3),
            Map.entry("char", 4), Map.entry("chaar", 4), Map.entry("paanch", 5), Map.entry("panch", 5), Map.entry("couple", 2));

    private static final Set<String> STOP = Set.of("please", "pls", "plz", "bhi", "le", "lena", "lele", "lana", "laana", "lao",
            "get", "buy", "some", "a", "an", "the", "of", "for", "me", "ka", "ki", "ke", "wala", "wali", "wale", "bring", "also",
            "ghar", "se", "and", "aur", "need", "want", "pick", "up", "from", "any", "small", "big", "packet", "pack", "bottle",
            "box", "piece", "pieces", "pcs", "nos", "fresh", "good", "acha", "achha", "ok", "beta", "mummy", "mom", "dad", "bhai",
            "aate", "jaate", "waqt", "time", "aana", "list", "hi", "hello", "thanks", "thank", "you", "abhi", "jaldi", "urgent");

    /** Hinglish, regional and brand words -> words that appear in catalogue names. */
    private static final Map<String, String> ALIAS = new LinkedHashMap<>();
    static {
        String[][] a = {
            {"chai patti", "tea leaves"}, {"chaipatti", "tea leaves"}, {"double roti", "white bread"}, {"shimla mirch", "capsicum"},
            {"hari mirch", "green chillies"}, {"kachra bag", "garbage bags"}, {"good knight", "mosquito repellent"},
            {"all out", "mosquito repellent"}, {"band aid", "band-aid"}, {"surf excel", "detergent"}, {"phone charger", "phone charger"},
            {"doodh", "milk"}, {"dudh", "milk"}, {"dahi", "curd"}, {"anda", "eggs"}, {"ande", "eggs"}, {"anday", "eggs"}, {"egg", "eggs"},
            {"makhan", "butter"}, {"makkhan", "butter"}, {"amul", "butter"}, {"pyaaz", "onions"}, {"pyaz", "onions"}, {"kanda", "onions"},
            {"onion", "onions"}, {"aloo", "potatoes"}, {"alu", "potatoes"}, {"batata", "potatoes"}, {"potato", "potatoes"},
            {"tamatar", "tomatoes"}, {"tomato", "tomatoes"}, {"kela", "bananas"}, {"kele", "bananas"}, {"banana", "bananas"},
            {"seb", "apples"}, {"apple", "apples"}, {"santra", "oranges"}, {"orange", "oranges"}, {"aam", "mangoes"}, {"mango", "mangoes"},
            {"nimbu", "lemons"}, {"limbu", "lemons"}, {"lemon", "lemons"}, {"palak", "spinach"}, {"dhaniya", "coriander"},
            {"kothimbir", "coriander"}, {"mirchi", "chillies"}, {"adrak", "ginger"}, {"lehsun", "garlic"}, {"lasun", "garlic"},
            {"kheera", "cucumber"}, {"kakdi", "cucumber"}, {"gajar", "carrots"}, {"carrot", "carrots"}, {"papita", "papaya"},
            {"chawal", "rice"}, {"aata", "atta"}, {"chini", "sugar"}, {"cheeni", "sugar"}, {"shakkar", "sugar"}, {"namak", "salt"},
            {"tel", "oil"}, {"haldi", "turmeric"}, {"sooji", "rava"}, {"suji", "rava"}, {"daal", "dal"}, {"chai", "tea"},
            {"nescafe", "coffee"}, {"sauce", "ketchup"}, {"pao", "pav"}, {"biscuit", "biscuits"}, {"biskut", "biscuits"},
            {"parle", "biscuits"}, {"toast", "rusk"}, {"sabun", "soap"}, {"colgate", "toothpaste"}, {"paste", "toothpaste"},
            {"brush", "toothbrush"}, {"crocin", "paracetamol"}, {"dolo", "paracetamol"}, {"calpol", "paracetamol"},
            {"fever", "paracetamol"}, {"electral", "ors"}, {"dettol", "antiseptic"}, {"savlon", "antiseptic"}, {"bandaid", "band-aid"},
            {"vicks", "cough"}, {"benadryl", "cough"}, {"eno", "antacid"}, {"digene", "antacid"}, {"gelusil", "antacid"},
            {"vim", "dishwash"}, {"pril", "dishwash"}, {"surf", "detergent"}, {"tide", "detergent"}, {"ariel", "detergent"},
            {"harpic", "toilet cleaner"}, {"lizol", "floor cleaner"}, {"phenyl", "floor cleaner"}, {"mortein", "mosquito repellent"},
            {"maachis", "matchbox"}, {"machis", "matchbox"}, {"copy", "notebook"}, {"register", "notebook"}, {"cell", "batteries"},
            {"battery", "batteries"}, {"whiskas", "cat food"}, {"pedigree", "dog food"}, {"bulb", "led bulb"}, {"pendrive", "pendrive"},
            {"tissue", "tissue roll"}, {"napkin", "tissue roll"}, {"pads", "sanitary pads"}, {"whisper", "sanitary pads"},
            {"stayfree", "sanitary pads"}, {"glucon", "glucose"}, {"chocolate", "dark chocolate"}, {"coke", "cola"}, {"pepsi", "cola"},
            {"thums", "cola"}, {"pani", "mineral water"}, {"water", "mineral water"}, {"bisleri", "mineral water"},
            {"lays", "potato chips"}, {"chips", "potato chips"}, {"namkeen", "namkeen"}, {"haldiram", "namkeen"}
        };
        for (String[] p : a) ALIAS.put(p[0], p[1]);
    }

    public ParseResponse parse(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("paste some text first");
        if (text.length() > MAX_CHARS) throw new IllegalArgumentException("text too long (max " + MAX_CHARS + " characters)");
        long t0 = System.nanoTime();
        List<ParsedLine> out = new ArrayList<>();
        for (String raw : SPLIT.split(text)) {
            String r = raw.strip();
            if (r.isEmpty() || r.endsWith(":")) continue;   // blank or a header like "Beta, le aana:"
            ParsedLine pl = parseLine(r);
            if (pl != null) out.add(pl);
        }
        int matched = (int) out.stream().filter(l -> l.itemId() != null).count();
        return new ParseResponse(out, matched, out.size() - matched, System.nanoTime() - t0);
    }

    ParsedLine parseLine(String raw) {
        String s = BULLET.matcher(raw.toLowerCase(Locale.ROOT)).replaceFirst("");
        s = s.replaceAll("[^a-z0-9.\\-\u00d7 ]", " ").replaceAll("\\s+", " ").strip();
        if (s.isEmpty()) return null;

        int qty = 1;
        Matcher m;
        if ((m = LEAD_QTY.matcher(s)).find()) { qty = Integer.parseInt(m.group(1)); s = s.substring(m.end()); }
        else if ((m = X_QTY.matcher(s)).find()) { qty = Integer.parseInt(m.group(1)); s = s.substring(m.end()); }
        else if ((m = TAIL_QTY.matcher(s)).find()) { qty = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2)); s = s.substring(0, m.start()); }
        else {
            String first = s.split(" ")[0];
            if (NUM_WORDS.containsKey(first) && s.contains(" ")) { qty = NUM_WORDS.get(first); s = s.substring(first.length() + 1); }
        }
        if (s.contains("dozen") || s.contains("darjan")) { s = s.replace("dozen", " ").replace("darjan", " "); }
        qty = Math.max(1, Math.min(PlannerService.MAX_QTY, qty));

        String expanded = " " + s + " ";
        boolean alias = false;
        for (Map.Entry<String, String> e : ALIAS.entrySet()) {
            String k = " " + e.getKey() + " ";
            if (expanded.contains(k)) { expanded = expanded.replace(k, " " + e.getValue() + " "); alias = true; }
        }
        List<String> tokens = new ArrayList<>();
        for (String t : expanded.strip().split(" ")) if (t.length() >= 2 && !STOP.contains(t) && !NUM_WORDS.containsKey(t)) tokens.add(t);
        if (tokens.isEmpty()) return new ParsedLine(raw, qty, null, null, 0, "none", List.of(), "no item words found");

        // exact catalogue name wins outright
        Optional<Integer> exact = city.itemIdByName(String.join(" ", tokens));
        if (exact.isPresent()) return new ParsedLine(raw, qty, exact.get(), city.item(exact.get()).name(), 1.0, "exact", List.of(), null);

        // candidates from the trie: every token, and its first 3 letters as a typo fallback
        LinkedHashSet<Integer> cands = new LinkedHashSet<>();
        for (String t : tokens) {
            cands.addAll(city.trie().suggest(t, 40).ids());
            if (t.length() > 3) cands.addAll(city.trie().suggest(t.substring(0, 3), 15).ids());
        }
        int totalLen = tokens.stream().mapToInt(String::length).sum();
        record Scored(int id, double score, int len) {}
        List<Scored> scored = new ArrayList<>();
        for (int id : cands) {
            String name = Trie.normalize(city.item(id).name());
            double sc = 0;
            for (String t : tokens) {
                int at = Kmp.indexOf(name, t);
                if (at >= 0) sc += t.length() + ((at == 0 || name.charAt(at - 1) == ' ') ? 1.5 : 0);
            }
            if (sc > 0) scored.add(new Scored(id, sc, name.length()));
        }
        scored.sort(Comparator.comparingDouble((Scored x) -> -x.score()).thenComparingInt(Scored::len));
        List<String> alts = scored.stream().skip(1).limit(3).map(x -> city.item(x.id()).name()).toList();
        if (scored.isEmpty()) {
            List<String> dym = cands.stream().limit(3).map(i -> city.item(i).name()).toList();
            return new ParsedLine(raw, qty, null, null, 0, "none", dym, dym.isEmpty() ? "not sold in the area" : "no confident match");
        }
        Scored best = scored.get(0);
        double conf = Math.min(1.0, best.score() / (totalLen + 1.5 * tokens.size()) + 0.25);
        if (conf < MIN_CONFIDENCE)
            return new ParsedLine(raw, qty, null, null, round(conf), "low", scored.stream().limit(3).map(x -> city.item(x.id()).name()).toList(), "no confident match");
        return new ParsedLine(raw, qty, best.id(), city.item(best.id()).name(), round(conf), alias ? "alias" : "kmp", alts, null);
    }

    private static double round(double v) { return Math.round(v * 100) / 100.0; }
}
