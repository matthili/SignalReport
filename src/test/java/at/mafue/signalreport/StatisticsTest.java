package at.mafue.signalreport;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StatisticsTest {

    @Test
    void testStatisticsCreation() {
        // Arrange & Act
        H2MeasurementRepository.Statistics stats =
            new H2MeasurementRepository.Statistics(23.5, 45.2, 89.7, 0.5, 3.2);

        // Assert
        assertEquals(23.5, stats.getAvgLatency(), 0.01, "Durchschnitt muss korrekt sein");
        assertEquals(45.2, stats.getP95Latency(), 0.01, "95th Percentile muss korrekt sein");
        assertEquals(89.7, stats.getMaxLatency(), 0.01, "Maximum muss korrekt sein");
        assertEquals(0.5, stats.getPacketLossPercent(), 0.01, "Paketverlust muss korrekt sein");
        assertEquals(3.2, stats.getJitter(), 0.01, "Jitter muss korrekt sein");
    }

    @Test
    void testP95CalculationWithSortedLatencies() {
        // Arrange: 20 Messungen (95th Percentile = 19. Wert bei 0-basierter Indizierung)
        List<Double> latencies = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            latencies.add((double) i * 10); // 10, 20, 30, ..., 200
        }

        // Act: 95th Percentile = Index 18 (0-basiert) = 190.0
        int p95Index = (int) Math.ceil(latencies.size() * 0.95) - 1;
        double p95Value = latencies.get(p95Index);

        // Assert
        assertEquals(18, p95Index, "Index für 95th Percentile muss 18 sein (0-basiert)");
        assertEquals(190.0, p95Value, 0.01, "95th Percentile-Wert muss 190.0 sein");
    }

    @Test
    void testJitterCalculation() {
        // Arrange: Konstante Latenz → Jitter = 0
        List<Double> constantLatencies = List.of(20.0, 20.0, 20.0, 20.0);

        // Act
        double sumDiff = 0;
        for (int i = 1; i < constantLatencies.size(); i++) {
            sumDiff += Math.abs(constantLatencies.get(i) - constantLatencies.get(i - 1));
        }
        double jitter = sumDiff / (constantLatencies.size() - 1);

        // Assert
        assertEquals(0.0, jitter, 0.01, "Jitter bei konstanter Latenz muss 0 sein");

        // Arrange: Schwankende Latenz
        List<Double> varyingLatencies = List.of(20.0, 30.0, 25.0, 40.0);

        // Act
        sumDiff = 0;
        for (int i = 1; i < varyingLatencies.size(); i++) {
            sumDiff += Math.abs(varyingLatencies.get(i) - varyingLatencies.get(i - 1));
        }
        jitter = sumDiff / (varyingLatencies.size() - 1);

        // Assert
        assertEquals(10.0, jitter, 0.01, "Jitter muss Durchschnitt der Differenzen sein");
    }

    @Test
    void testPacketLossCalculation() {
        // Arrange: 95 erfolgreiche, 5 fehlgeschlagene Messungen
        int total = 100;
        int failed = 5;

        // Act
        double packetLoss = (failed * 100.0) / total;

        // Assert
        assertEquals(5.0, packetLoss, 0.01, "Paketverlust muss 5.0% sein");
    }
}