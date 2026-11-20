package edu.atlas.sweep;

import edu.atlas.sweep.domain.City;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class GeoAlignmentTest {
    static double km(double[] a, double la, double ln) { return Math.hypot((a[0] - la) * 110.57, (a[1] - ln) * 105.2); }

    @Test void landmarksMapToRealPowai() {
        assertTrue(km(City.toLatLng(4.95, 3.28), 19.1176, 72.9106) < 0.01, "Hiranandani");
        assertTrue(km(City.toLatLng(2.95, 0.95), 19.1334, 72.9133) < 0.01, "IIT Bombay");
        assertTrue(km(City.toLatLng(6.75, 2.05), 19.1102, 72.8970) < 0.01, "Chandivali");
    }

    @Test void everyShopIsInPowaiAndNotInTheLake() {
        City c = City.load(Path.of("data"), 42);
        for (City.Store s : c.stores()) {
            double[] p = City.toLatLng(s.x(), s.y());
            assertTrue(p[0] > 19.09 && p[0] < 19.15 && p[1] > 72.87 && p[1] < 72.95, s.name() + " outside Powai: " + p[0] + "," + p[1]);
            double ey = (p[0] - 19.1265) * 110.57 / 0.83, ex = (p[1] - 72.9050) * 105.2 / 0.84;
            assertTrue(Math.hypot(ex, ey) >= 1.0, s.name() + " is in Powai Lake");
        }
    }
}
