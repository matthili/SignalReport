package at.mafue.signalreport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Internationalisierung (i18n) fuer alle benutzersichtbaren Texte.
 * <p>
 * Sprachdateien sind flache JSON-Dateien (UTF-8) mit Punkt-Schluesseln,
 * z. B. {"nav.settings": "Einstellungen"}. Gebuendelte Sprachen liegen im
 * Classpath unter /lang/&lt;code&gt;.json. Zusaetzlich wird ein externer
 * Ordner ./lang neben der JAR-Datei eingelesen - dort abgelegte Dateien
 * ergaenzen oder ueberschreiben gebuendelte Sprachen, ohne dass neu
 * kompiliert werden muss.
 * <p>
 * Fallback-Kette bei fehlenden Schluesseln: gewaehlte Sprache -> Deutsch
 * (Referenzsprache) -> Schluesselname selbst.
 */
public final class I18n
{
    private static final Logger logger = LoggerFactory.getLogger(I18n.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REFERENCE_LANGUAGE = "de";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z0-9_.]+)}}");
    private static final Path EXTERNAL_DIR = Paths.get("./lang");

    private static volatile Map<String, String> active = Map.of();
    private static volatile Map<String, String> reference = Map.of();
    private static volatile String currentLanguage = REFERENCE_LANGUAGE;

    private I18n()
    {
    }

    /**
     * Laedt die angegebene Sprache als aktive Sprache. Die Referenzsprache
     * (Deutsch) wird immer mitgeladen, damit fehlende Schluessel sauber
     * zurueckfallen. Unbekannte Sprachcodes fallen auf Deutsch zurueck.
     */
    public static synchronized void load(String language)
    {
        if (reference.isEmpty())
            {
            Map<String, String> ref = readLanguageFile(REFERENCE_LANGUAGE);
            reference = (ref != null) ? ref : Map.of();
            }

        if (language == null || language.isBlank())
            {
            language = REFERENCE_LANGUAGE;
            }

        Map<String, String> loaded = readLanguageFile(language);
        if (loaded == null)
            {
            logger.warn("Sprachdatei fuer '{}' nicht gefunden - verwende '{}'", language, REFERENCE_LANGUAGE);
            active = reference;
            currentLanguage = REFERENCE_LANGUAGE;
            return;
            }

        active = loaded;
        currentLanguage = language;
        logger.info("Sprache geladen: {} ({})", get("language.name"), language);
    }

    /** Liefert den Text zum Schluessel (mit Fallback-Kette). */
    public static String get(String key)
    {
        String value = active.get(key);
        if (value != null) return value;
        value = reference.get(key);
        if (value != null) return value;
        return key;
    }

    /** Ersetzt alle {{key}}-Platzhalter im uebergebenen Text. */
    public static String resolve(String template)
    {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder(template.length() + 256);
        while (matcher.find())
            {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(get(matcher.group(1))));
            }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** Aktiver Sprachcode (z. B. "de", "en", "uk"). */
    public static String current()
    {
        return currentLanguage;
    }

    /** Java-Locale der aktiven Sprache (fuer Zahlen-/Datumsformatierung). */
    public static Locale locale()
    {
        return Locale.forLanguageTag(currentLanguage);
    }

    /** Aktive Sprachtabelle als JSON-Objekt (fuer die Einbettung ins Frontend-JS). */
    public static String activeAsJson()
    {
        try
            {
            // Referenz als Basis, aktive Sprache darueber - damit sieht das JS nie ein Loch
            Map<String, String> merged = new LinkedHashMap<>(reference);
            merged.putAll(active);
            return MAPPER.writeValueAsString(merged);
            } catch (IOException e)
            {
            logger.error("I18N-JSON-Serialisierung fehlgeschlagen: {}", e.getMessage());
            return "{}";
            }
    }

    /**
     * Alle verfuegbaren Sprachen: Code -> Eigenbezeichnung (language.name).
     * Vereint gebuendelte Sprachen (JAR/Classpath) und den externen ./lang-Ordner.
     * Sortiert nach Sprachcode.
     */
    public static Map<String, String> availableLanguages()
    {
        Map<String, String> result = new TreeMap<>();

        for (String code : scanClasspathLanguages())
            {
            addLanguageName(result, code);
            }
        for (String code : scanExternalLanguages())
            {
            addLanguageName(result, code);
            }

        return result;
    }

    /**
     * Ermittelt die Standardsprache fuer Neuinstallationen: die Systemsprache,
     * falls unterstuetzt - sonst Englisch.
     */
    public static String detectOsLanguage()
    {
        String osLanguage = Locale.getDefault().getLanguage();
        if (availableLanguages().containsKey(osLanguage))
            {
            return osLanguage;
            }
        return "en";
    }

    /** Prueft, ob ein Sprachcode verfuegbar ist. */
    public static boolean isAvailable(String language)
    {
        return language != null && availableLanguages().containsKey(language);
    }

    /**
     * Baut die &lt;option&gt;-Liste fuer Sprach-Dropdowns. Jede Sprache erscheint
     * in ihrer Eigenbezeichnung, die aktive Sprache ist vorausgewaehlt.
     */
    public static String languageOptionsHtml()
    {
        StringBuilder options = new StringBuilder();
        for (Map.Entry<String, String> entry : availableLanguages().entrySet())
            {
            options.append("<option value=\"").append(entry.getKey()).append("\"");
            if (entry.getKey().equals(currentLanguage))
                {
                options.append(" selected");
                }
            options.append(">").append(entry.getValue()).append("</option>");
            }
        return options.toString();
    }

    // ------------------------------------------------------------------
    //  Intern
    // ------------------------------------------------------------------

    private static void addLanguageName(Map<String, String> result, String code)
    {
        Map<String, String> file = readLanguageFile(code);
        if (file != null)
            {
            result.put(code, file.getOrDefault("language.name", code));
            }
    }

    /**
     * Liest eine Sprachdatei. Externe Dateien (./lang) haben Vorrang vor
     * gebuendelten - so koennen Uebersetzungen ohne Neukompilieren korrigiert
     * oder ergaenzt werden.
     */
    private static Map<String, String> readLanguageFile(String code)
    {
        if (code == null || !code.matches("[a-zA-Z-]{2,8}"))
            {
            return null;
            }

        Path external = EXTERNAL_DIR.resolve(code + ".json");
        if (Files.isRegularFile(external))
            {
            try
                {
                return MAPPER.readValue(external.toFile(), new TypeReference<LinkedHashMap<String, String>>() {});
                } catch (IOException e)
                {
                logger.error("Externe Sprachdatei {} fehlerhaft: {}", external, e.getMessage());
                }
            }

        try (InputStream in = I18n.class.getResourceAsStream("/lang/" + code + ".json"))
            {
            if (in == null) return null;
            return MAPPER.readValue(in, new TypeReference<LinkedHashMap<String, String>>() {});
            } catch (IOException e)
            {
            logger.error("Sprachdatei /lang/{}.json fehlerhaft: {}", code, e.getMessage());
            return null;
            }
    }

    /** Findet gebuendelte Sprachen - funktioniert im Fat-JAR und im IDE-Betrieb. */
    private static java.util.Set<String> scanClasspathLanguages()
    {
        java.util.Set<String> codes = new java.util.TreeSet<>();
        try
            {
            URL url = I18n.class.getResource("/lang");
            if (url == null) return codes;

            if ("jar".equals(url.getProtocol()))
                {
                JarURLConnection connection = (JarURLConnection) url.openConnection();
                try (JarFile jar = new JarFile(connection.getJarFileURL().getPath().replace("%20", " ")))
                    {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements())
                        {
                        String name = entries.nextElement().getName();
                        if (name.startsWith("lang/") && name.endsWith(".json"))
                            {
                            codes.add(name.substring(5, name.length() - 5));
                            }
                        }
                    }
                } else
                {
                Path dir = Paths.get(url.toURI());
                try (Stream<Path> stream = Files.list(dir))
                    {
                    stream.map(p -> p.getFileName().toString())
                            .filter(n -> n.endsWith(".json"))
                            .forEach(n -> codes.add(n.substring(0, n.length() - 5)));
                    }
                }
            } catch (Exception e)
            {
            logger.error("Sprachen-Scan (Classpath) fehlgeschlagen: {}", e.getMessage());
            }
        return codes;
    }

    /** Findet Sprachen im externen ./lang-Ordner neben der JAR. */
    private static java.util.Set<String> scanExternalLanguages()
    {
        java.util.Set<String> codes = new java.util.TreeSet<>();
        if (!Files.isDirectory(EXTERNAL_DIR)) return codes;
        try (Stream<Path> stream = Files.list(EXTERNAL_DIR))
            {
            stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .forEach(n -> codes.add(n.substring(0, n.length() - 5)));
            } catch (IOException e)
            {
            logger.error("Sprachen-Scan (./lang) fehlgeschlagen: {}", e.getMessage());
            }
        return codes;
    }
}
