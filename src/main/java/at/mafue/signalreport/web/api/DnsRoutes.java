package at.mafue.signalreport.web.api;

import at.mafue.signalreport.config.BuiltinCatalog;
import at.mafue.signalreport.config.Config;
import at.mafue.signalreport.config.CustomDnsServer;
import at.mafue.signalreport.config.DnsBenchmarkConfig;
import at.mafue.signalreport.config.DnsBenchmarkConfig.Problem;
import at.mafue.signalreport.config.DnsBenchmarkConfig.Scope;
import at.mafue.signalreport.config.DnsServer;
import at.mafue.signalreport.i18n.I18n;
import at.mafue.signalreport.measurement.DnsBenchmark;
import at.mafue.signalreport.web.ErrorResponse;
import io.javalin.Javalin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * DNS-Benchmark: mitgelieferte Server (kommen mit dem Programm, {@link BuiltinCatalog}) und
 * eigene Server des Nutzers (config.json, {@link DnsBenchmarkConfig}).
 * <ul>
 *   <li>{@code GET /api/dns/servers} – Server eines vollen Benchmarks</li>
 *   <li>{@code GET /api/dns/catalog} – mitgelieferte Liste mit Ausblende-Status und eigene Server</li>
 *   <li>{@code POST /api/dns/settings} – eigene Server und ausgeblendete mitgelieferte speichern</li>
 *   <li>{@code POST /api/dns/benchmark?hostname=…&scope=all|custom} – Benchmark ausfuehren</li>
 *   <li>{@code GET /api/dns/statistics} – Anzahl, Regionen, Anbieter</li>
 * </ul>
 */
public class DnsRoutes
{
    /** Speichert die Konfiguration (im Betrieb config.json; in Tests austauschbar). */
    @FunctionalInterface
    public interface ConfigSaver
    {
        void save() throws Exception;
    }

    /** Anfrage-Koerper von {@code POST /api/dns/settings}. */
    public static class DnsSettingsRequest
    {
        public List<CustomDnsServer> customServers;
        public List<String> hiddenBuiltins;
    }

    private static final int BENCHMARK_TIMEOUT_MS = 5000;

    private DnsRoutes()
    {
    }

    public static void register(Javalin app)
    {
        register(app, Config::getInstance, () -> Config.save("config.json"));
    }

    public static void register(Javalin app, Supplier<Config> config, ConfigSaver saver)
    {
        // Server, die ein voller Benchmark prueft (mitgelieferte ohne ausgeblendete + eingeschaltete eigene)
        app.get("/api/dns/servers", ctx ->
        {
        try
            {
            ctx.json(config.get().getDnsBenchmark().serversFor(Scope.ALL));
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("DNS-Server-Lade-Fehler: " + e.getMessage()));
            }
        });

        // Mitgelieferte Liste mit Ausblende-Status und eigene Server (Verwaltung im DNS-Tab)
        app.get("/api/dns/catalog", ctx ->
        {
        try
            {
            ctx.json(catalogView(config.get().getDnsBenchmark()));
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("DNS-Katalog-Fehler: " + e.getMessage()));
            }
        });

        // Eigene Server und ausgeblendete mitgelieferte speichern (immer der komplette eigene Teil)
        app.post("/api/dns/settings", ctx ->
        {
        DnsSettingsRequest body;
        try
            {
            body = ctx.bodyAsClass(DnsSettingsRequest.class);
            } catch (Exception e)
            {
            ctx.status(400).json(new ErrorResponse(I18n.get("api.invalidRequest")));
            return;
            }
        try
            {
            DnsBenchmarkConfig cfg = config.get().getDnsBenchmark();
            List<CustomDnsServer> incoming = body.customServers == null ? List.of()
                    : body.customServers.stream().filter(Objects::nonNull).toList();
            List<Problem> problems = DnsBenchmarkConfig.validate(incoming, cfg.getCustomServers());
            if (!problems.isEmpty())
                {
                String message = problems.stream()
                        .map(p -> I18n.get(p.key()).replace("{value}", p.value()))
                        .distinct()
                        .collect(Collectors.joining(" "));
                ctx.status(400).json(new ErrorResponse(message));
                return;
                }
            cfg.setCustomServers(incoming);
            cfg.setHiddenBuiltins(onlyBuiltinAddresses(body.hiddenBuiltins));
            saver.save();
            ctx.json(catalogView(cfg));
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("DNS-Einstellungen-Speicher-Fehler: " + e.getMessage()));
            }
        });

        // DNS-Benchmark ausfuehren: scope=all (Standard) oder scope=custom (nur eigene Server)
        app.post("/api/dns/benchmark", ctx ->
        {
        try
            {
            String hostname = ctx.queryParam("hostname") != null
                    ? ctx.queryParam("hostname")
                    : "google.com";
            Scope scope = "custom".equalsIgnoreCase(ctx.queryParam("scope")) ? Scope.CUSTOM : Scope.ALL;

            List<DnsServer> servers = config.get().getDnsBenchmark().serversFor(scope);
            List<DnsBenchmark.DnsResult> results = servers.isEmpty()
                    ? new ArrayList<>()
                    : new DnsBenchmark(servers, hostname, BENCHMARK_TIMEOUT_MS).benchmark();

            results.sort((a, b) -> Double.compare(a.getLatencyMs(), b.getLatencyMs()));

            ctx.json(results);
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("DNS-Benchmark-Fehler: " + e.getMessage()));
            }
        });

        // DNS-Statistik: Server eines vollen Benchmarks, Regionen und Anbieter der mitgelieferten
        app.get("/api/dns/statistics", ctx ->
        {
        try
            {
            List<DnsServer> servers = config.get().getDnsBenchmark().serversFor(Scope.ALL);
            List<DnsServer> builtin = servers.stream().filter(s -> !s.isCustom()).toList();
            ctx.json(Map.of(
                    "totalServers", servers.size(),
                    "customServers", servers.size() - builtin.size(),
                    "regions", builtin.stream()
                            .map(DnsServer::getRegion)
                            .distinct()
                            .toList(),
                    "providers", builtin.stream()
                            .map(DnsServer::getProvider)
                            .distinct()
                            .toList()
            ));
            } catch (Exception e)
            {
            ctx.status(500);
            ctx.json(new ErrorResponse("DNS-Statistik-Fehler: " + e.getMessage()));
            }
        });
    }

    /** Antwort fuer die Verwaltung: mitgelieferte Server mit Ausblende-Status, eigene Server, Obergrenze. */
    static Map<String, Object> catalogView(DnsBenchmarkConfig cfg)
    {
        Set<String> hidden = new HashSet<>(cfg.getHiddenBuiltins());
        List<Map<String, Object>> builtins = new ArrayList<>();
        for (DnsServer d : BuiltinCatalog.dnsServers())
            {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", d.getName());
            m.put("address", d.getAddress());
            m.put("region", d.getRegion());
            m.put("provider", d.getProvider());
            m.put("hidden", hidden.contains(DnsBenchmarkConfig.addressKey(d.getAddress())));
            builtins.add(m);
            }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("builtins", builtins);
        view.put("customServers", cfg.getCustomServers());
        view.put("maxCustomServers", DnsBenchmarkConfig.MAX_CUSTOM_SERVERS);
        return view;
    }

    /** Behaelt nur Adressen mitgelieferter Server (Unbekanntes wird verworfen). */
    static List<String> onlyBuiltinAddresses(List<String> addresses)
    {
        if (addresses == null)
            {
            return List.of();
            }
        Set<String> builtinKeys = new HashSet<>();
        for (DnsServer d : BuiltinCatalog.dnsServers())
            {
            builtinKeys.add(DnsBenchmarkConfig.addressKey(d.getAddress()));
            }
        return addresses.stream()
                .filter(Objects::nonNull)
                .map(DnsBenchmarkConfig::addressKey)
                .filter(builtinKeys::contains)
                .distinct()
                .toList();
    }
}
