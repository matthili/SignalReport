package at.mafue.signalreport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer die Internationalisierung: Schluessel-Paritaet aller Sprachdateien,
 * Fallback-Verhalten und Konsistenz zwischen Quellcode-Platzhaltern und der
 * Referenz-Sprachdatei (de.json).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class I18nTest
{
    private static final String[] LANGUAGES = {"de", "en", "fr", "it", "es", "pt", "tr", "pl", "uk"};
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterAll
    void resetLanguage()
    {
        // Globalen I18n-Zustand fuer andere Tests zuruecksetzen
        I18n.load("de");
    }

    private Map<String, String> readLanguageFile(String code) throws Exception
    {
        try (InputStream in = getClass().getResourceAsStream("/lang/" + code + ".json"))
            {
            assertNotNull(in, "Sprachdatei /lang/" + code + ".json fehlt");
            return MAPPER.readValue(in, new TypeReference<LinkedHashMap<String, String>>() {});
            }
    }

    @Test
    void testAllLanguageFilesExist() throws Exception
    {
        for (String code : LANGUAGES)
            {
            Map<String, String> file = readLanguageFile(code);
            assertFalse(file.isEmpty(), code + ".json darf nicht leer sein");
            }
    }

    @Test
    void testAllLanguagesHaveSameKeysAsReference() throws Exception
    {
        Map<String, String> reference = readLanguageFile("de");
        Set<String> referenceKeys = reference.keySet();

        for (String code : LANGUAGES)
            {
            if (code.equals("de")) continue;

            Map<String, String> language = readLanguageFile(code);
            Set<String> keys = language.keySet();

            // Fehlende Schluessel ermitteln (fuer aussagekraeftige Fehlermeldung)
            Set<String> missing = new java.util.TreeSet<>(referenceKeys);
            missing.removeAll(keys);
            Set<String> extra = new java.util.TreeSet<>(keys);
            extra.removeAll(referenceKeys);

            assertTrue(missing.isEmpty(), code + ".json: fehlende Schluessel: " + missing);
            assertTrue(extra.isEmpty(), code + ".json: ueberzaehlige Schluessel: " + extra);
            }
    }

    @Test
    void testLanguageNameAndNonEmptyValues() throws Exception
    {
        for (String code : LANGUAGES)
            {
            Map<String, String> language = readLanguageFile(code);

            String name = language.get("language.name");
            assertNotNull(name, code + ".json: language.name fehlt");
            assertFalse(name.isBlank(), code + ".json: language.name ist leer");

            for (Map.Entry<String, String> entry : language.entrySet())
                {
                assertNotNull(entry.getValue(), code + ".json: Wert fuer '" + entry.getKey() + "' ist null");
                assertFalse(entry.getValue().isBlank(), code + ".json: Wert fuer '" + entry.getKey() + "' ist leer");
                }
            }
    }

    @Test
    void testRendererPlaceholdersExistInReference() throws Exception
    {
        Map<String, String> reference = readLanguageFile("de");

        // Alle {{key}}- und I18N['key']-Verweise in den Quelldateien muessen
        // in der Referenz-Sprachdatei existieren (Schutz vor Tippfehlern)
        Pattern htmlPlaceholder = Pattern.compile("\\{\\{([a-zA-Z0-9_.]+)}}");
        Pattern jsReference = Pattern.compile("I18N\\['([a-zA-Z0-9_.]+)']");

        String[] sourceFiles = {
                "src/main/java/at/mafue/signalreport/HtmlPageRenderer.java",
                "src/main/java/at/mafue/signalreport/LoginPageRenderer.java",
                "src/main/java/at/mafue/signalreport/SetupPageRenderer.java"
        };

        for (String sourceFile : sourceFiles)
            {
            Path path = Path.of(sourceFile);
            if (!Files.exists(path))
                {
                continue; // andere Arbeitsverzeichnis-Konfiguration - Test ueberspringen
                }
            String source = Files.readString(path);

            Matcher html = htmlPlaceholder.matcher(source);
            while (html.find())
                {
                String key = html.group(1);
                assertTrue(reference.containsKey(key),
                        sourceFile + ": Platzhalter {{" + key + "}} fehlt in de.json");
                }

            Matcher js = jsReference.matcher(source);
            while (js.find())
                {
                String key = js.group(1);
                assertTrue(reference.containsKey(key),
                        sourceFile + ": JS-Verweis I18N['" + key + "'] fehlt in de.json");
                }
            }
    }

    @Test
    void testLoadAndGet()
    {
        I18n.load("en");
        assertEquals("en", I18n.current());
        assertEquals("Settings", I18n.get("nav.settings"));

        I18n.load("de");
        assertEquals("de", I18n.current());
        assertEquals("Einstellungen", I18n.get("nav.settings"));
    }

    @Test
    void testUnknownLanguageFallsBackToGerman()
    {
        I18n.load("xx");
        assertEquals("de", I18n.current());
        assertEquals("Einstellungen", I18n.get("nav.settings"));
    }

    @Test
    void testUnknownKeyReturnsKeyItself()
    {
        I18n.load("de");
        assertEquals("does.not.exist", I18n.get("does.not.exist"));
    }

    @Test
    void testResolveReplacesPlaceholders()
    {
        I18n.load("de");
        String html = "<button>{{nav.settings}}</button><p>{{does.not.exist}}</p>";
        String resolved = I18n.resolve(html);
        assertEquals("<button>Einstellungen</button><p>does.not.exist</p>", resolved);
    }

    @Test
    void testAvailableLanguagesContainsAllBundled()
    {
        Map<String, String> available = I18n.availableLanguages();
        for (String code : LANGUAGES)
            {
            assertTrue(available.containsKey(code), "Sprache '" + code + "' nicht in availableLanguages()");
            }
        assertEquals("Deutsch", available.get("de"));
        assertEquals("Українська", available.get("uk"));
    }

    @Test
    void testDetectOsLanguageReturnsAvailableLanguage()
    {
        String detected = I18n.detectOsLanguage();
        assertTrue(I18n.isAvailable(detected),
                "detectOsLanguage() muss eine verfuegbare Sprache liefern, war: " + detected);
    }
}
