package edu.atlas.sweep.web;

import java.util.List;
import java.util.Map;

/** Request / response shapes for the REST API (serialised by Jackson). */
public final class Api {
    private Api() {}

    public record Point(Double x, Double y) {}

    public record Line(String name, Integer qty) {}

    public record PlanRequest(Point home, List<Line> items, Integer hour, Double lambda,
                              Double kmCost, Double stopCost, Double speedKmh, String mode) {
        public PlanRequest(Point home, List<Line> items, Integer hour, Double lambda, Double kmCost, Double stopCost, Double speedKmh) {
            this(home, items, hour, lambda, kmCost, stopCost, speedKmh, null);
        }
    }

    public record ParseRequest(String text) {}

    /** One line of a pasted list. itemId == null means no confident match (alternatives offered). */
    public record ParsedLine(String raw, int qty, Integer itemId, String item, double confidence, String matchedBy,
                             List<String> alternatives, String note) {}

    public record ParseResponse(List<ParsedLine> lines, int matched, int unmatched, long nanos) {}

    public record Share(String mapsUrl, String text, String travelMode) {}

    public record Suggestion(int id, String name, String category, int minPrice, int stores) {}

    public record SuggestResponse(String query, List<Suggestion> results, int nodesVisited, long trieNanos,
                                  long scanNanos, int trieNodes) {}

    public record StoreDto(int id, String name, String type, String area, double x, double y,
                           int open, int close, int items, int bloomBits, int bloomK, int bloomBytes,
                           double lat, double lng) {}

    public record ItemDto(int id, String name, String category, int basePrice) {}

    /** Bloom pre-screen outcome for one requested item across all open stores. */
    public record Screen(int itemId, String item, List<Integer> definitelyNot, List<Integer> confirmed,
                         List<Integer> falsePositives, List<Integer> lowStock) {}

    public record ScreenStats(int checks, int bloomRejected, int bloomPassed, int falsePositives,
                              int confirmed, double observedFpRate, double expectedFpRate, long nanos) {}

    public record CoverStep(int storeId, List<String> newlyCovered, double ratio) {}

    public record CoverReport(List<CoverStep> steps, double greedyCost, Double exactCost, Double ratio,
                              double harmonicBound, boolean guaranteeApplies, int candidates, long nanos) {}

    public record Pick(String item, int itemId, int qty, int price, int storeId, int median, double dealScore,
                       int cityMin, int cityMax) {}

    public record Stop(int storeId, String name, String type, double x, double y, List<Pick> picks, int subtotal,
                       double legKm, int etaMin, double lat, double lng, String arrival, int closesAt, boolean closingRisk) {}

    public record Edge(int a, int b, double km) {}

    public record RouteReport(List<Integer> order, List<Integer> preorder, double km, double preorderKm, double mstKm, int twoOptMoves,
                              List<Edge> mstEdges, double nearestNeighbourKm, Double optimalKm, Double ratioToOptimal,
                              long nanos) {}

    public record Totals(int basket, double km, int stops, int minutes, double travelCost, double total,
                         int savedVsMedian) {}

    public record Alternative(String name, String description, int stops, double km, int basket, double total,
                              int minutes, List<Integer> storeIds) {}

    public record Unmet(String item, String reason) {}

    public record PlanResponse(Point home, int hour, List<Stop> stops, Totals totals, ScreenStats screenStats,
                               List<Screen> screens, CoverReport cover, RouteReport route,
                               List<Alternative> alternatives, List<Unmet> unmet, List<String> notices,
                               Map<String, Object> params, Share share) {}

    public record BloomProbe(int storeId, String store, int itemId, String item, int m, int k, int[] positions,
                             boolean[] bitsSet, boolean mightContain, boolean actuallyStocked, String verdict,
                             String bits, int bitsOn, double fillRatio) {}

    public record ApiError(String error, List<String> details) {}
}
