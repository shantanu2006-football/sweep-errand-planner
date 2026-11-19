package edu.atlas.sweep.service;

import edu.atlas.sweep.domain.City;
import edu.atlas.sweep.web.Api.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlannerServiceTest {
    static final City CITY = City.load(Path.of("data"), 42);
    final PlannerService svc = new PlannerService(CITY, 42);

    private PlanRequest req(List<Line> lines, int hour) {
        return new PlanRequest(new Point(4.5, 3.2), lines, hour, 1.0, 12.0, 40.0, 18.0);
    }

    @Test void endToEndPlanCoversEveryItem() {
        PlanResponse r = svc.plan(req(List.of(new Line("Milk 1L", 2), new Line("Eggs 12pk", 1),
                new Line("Paracetamol 500mg Strip", 1), new Line("A4 Notebook 200pg", 2)), 18));
        Set<String> picked = new HashSet<>();
        r.stops().forEach(s -> s.picks().forEach(p -> picked.add(p.item())));
        assertEquals(Set.of("Milk 1L", "Eggs 12pk", "Paracetamol 500mg Strip", "A4 Notebook 200pg"), picked);
        assertTrue(r.unmet().isEmpty());
        // every pick must be really stocked, with enough units, at the store it is assigned to
        r.stops().forEach(s -> s.picks().forEach(p -> {
            City.Listing l = CITY.listing(s.storeId(), p.itemId());
            assertNotNull(l); assertTrue(l.stock() >= p.qty()); assertEquals(l.price(), p.price());
        }));
        assertEquals(0, r.screenStats().checks() - r.screenStats().bloomRejected() - r.screenStats().bloomPassed());
    }

    @Test void bloomNeverHidesAStockedStore() {
        PlanResponse r = svc.plan(req(List.of(new Line("Milk 1L", 1)), 12));
        Screen s = r.screens().get(0);
        for (int sid : s.definitelyNot()) assertNull(CITY.listing(sid, s.itemId()), "false negative at store " + sid);
        for (int sid : s.falsePositives()) assertNull(CITY.listing(sid, s.itemId()));
    }

    @Test void emptyListRejected() {
        var e = assertThrows(PlannerService.ValidationException.class, () -> svc.plan(req(List.of(), 12)));
        assertTrue(e.details.get(0).contains("empty"));
    }

    @Test void unknownItemGetsSuggestions() {
        var e = assertThrows(PlannerService.ValidationException.class, () -> svc.plan(req(List.of(new Line("milk", 1)), 12)));
        assertTrue(e.details.get(0).contains("Milk 1L"), e.details.toString());
    }

    @Test void duplicatesAreMergedNotDoubleCounted() {
        PlanResponse r = svc.plan(req(List.of(new Line("Milk 1L", 1), new Line("milk 1l", 2)), 12));
        int qty = r.stops().stream().flatMap(s -> s.picks().stream()).mapToInt(Pick::qty).sum();
        assertEquals(3, qty);
        assertFalse(r.notices().isEmpty());
    }

    @Test void boundaryNightTimeOnlyPharmaciesOpen() {
        PlanResponse r = svc.plan(req(List.of(new Line("Paracetamol 500mg Strip", 1), new Line("A4 Notebook 200pg", 1)), 2));
        assertEquals(1, r.unmet().size());
        assertTrue(r.unmet().get(0).reason().contains("closed"), r.unmet().get(0).reason());
    }

    @Test void quantityAboveAnyStockIsReportedNotFaked() {
        PlanResponse r = svc.plan(req(List.of(new Line("Calculator", 50)), 12));
        assertEquals(1, r.unmet().size());
        assertTrue(r.stops().isEmpty());
    }

    @Test void invalidParametersAllListed() {
        PlanRequest bad = new PlanRequest(new Point(99.0, -1.0), List.of(new Line("Milk 1L", 0)), 25, -1.0, 12.0, 40.0, 18.0);
        var e = assertThrows(PlannerService.ValidationException.class, () -> svc.plan(bad));
        assertTrue(e.details.size() >= 4, e.details.toString());
    }

    @Test void routeIsNeverWorseThanTwiceOptimal() {
        PlanResponse r = svc.plan(req(List.of(new Line("Milk 1L", 1), new Line("LED Bulb 9W", 1), new Line("Cat Food 1kg", 1),
                new Line("Paracetamol 500mg Strip", 1), new Line("Ball Pen 5pk", 1), new Line("Croissant", 1)), 12));
        assertNotNull(r.route().ratioToOptimal());
        assertTrue(r.route().ratioToOptimal() <= 2.0 + 1e-9);
        assertTrue(r.route().km() >= r.route().mstKm() - 1e-9);
    }
}
