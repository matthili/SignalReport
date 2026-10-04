# Datenbank-Analyse tars – Stand 2026-10-04

Arbeitsnotiz zu Dateiwachstum, Korruption und Dienst-Stopp der SignalReport-Installation auf dem
MiniPC „tars" (Windows, Dienst über prunsrv, SignalReport 2.0.1, H2 2.4.240). Alle Aussagen sind mit
Quelle (Datei:Zeile) oder Messung belegt; Vermutungen sind als solche gekennzeichnet.
Messprogramme liegen in diesem Ordner: `H2GrowthExperiment.java` (Dateiwachstum) und
`H2Inventory.java` (reines Lese-Inventar einer bestehenden Datenbank).

## 1. Beobachtungen auf tars

| Punkt | Wert / Befund | Quelle |
|---|---|---|
| Laufzeit | > 3 Monate Dauerbetrieb seit 2026-06-13 | Inventar |
| Messintervall | 10 s (bewusst kurz gewählt, um schnell Datenvolumen aufzubauen) | config.json auf tars |
| Zeilen pro Zyklus | 5 (PING, DNS, HTTP, GATEWAY_NEAR, GATEWAY_FAR) | Inventar: je Typ ~949.135 Zeilen |
| Zeilen pro Tag | ~42.500 | Inventar (Tagesscan) |
| `signalreport.mv.db` | 24.896.835.584 Byte (24,9 GB) | `dir` 2026-10-04 14:02 |
| `signalreport-shadow.mv.db` | 45.968.138.240 Byte (46,0 GB) | `dir` 2026-10-04 14:02 |
| `signalreport.trace.db` | 46.577 Byte, 30 Exceptions seit 2026-07-03 | `Select-String` 2026-10-04 |
| Platte C: auf tars | 404,9 GB belegt, 1.440,4 GB frei | `Get-PSDrive` 2026-10-04 |
| PDF 12 Monate / CSV „Alle Daten" | `MVStoreException ... File corrupted while reading record: "type: 185" [90030-240]` | trace.db |
| Startseite | 5–7 s nur beim ersten Aufruf nach Tagen, danach sofort | Nutzer |
| Endpunkte einzeln (warm) | alle < 700 ms; `hourly-averages` (7 Tage) ~650 ms am langsamsten | gemessen 2026-10-04 |
| Log alle paar Tage | `JavalinBindException: Port already in use`, Stack `SignalReportApp.main:107` → `WebServer.start:57`; zuletzt 2026-10-03 18:41:49 | stderr-Log |

Der vollständige Fehlertext aus der Trace-Datei nennt den defekten Chunk:
`chunk:577da2,block:297de4,len:a,pages:24,livePages:1,max:a6c0,liveMax:400,map:69`. Also der
5.733.794. Chunk der Datei, 40 KB groß bei Byte-Position ~11,1 GB, mit 36 Seiten, von denen noch
**eine einzige** lebt (≤ 1 KB Nutzdaten). Genau dieses Muster (ganze Chunks, die wegen einer
einzigen lebenden Seite nicht freigegeben werden) ist der Aufblas-Mechanismus aus Abschnitt 2.
„type: 185" bedeutet: an der Stelle, an der eine B-Baum-Seite stehen muss, steht ein ungültiges
Typ-Byte; der Dateiinhalt dort ist nicht die Seite, auf die die Chunk-Metadaten zeigen.

### Inventar beider Datenbanken (read-only über Z:\data, 2026-10-04, `H2Inventory.java`)

Geöffnet mit `ACCESS_MODE_DATA=r;IFEXISTS=TRUE;TRACE_LEVEL_FILE=0` (kein Schreibzugriff, keine
Trace-Datei). Tageweiser Lesetest über `SUM(latency_ms)`, damit jede Zeile wirklich gelesen wird.

| | Primary (24,9 GB) | Shadow (46,0 GB) |
|---|---|---|
| Öffnen über Gigabit-LAN | 397 s | 675 s |
| `measurements` | 4.752.282 Zeilen | 4.752.280 Zeilen |
| Zeitraum | 2026-06-13 22:02:27 bis 2026-10-04 14:02:31 | identisch |
| `service_checks` / `ip_changes` / `hosts` | 5.785 / 3 / 3 | 5.785 / 3 / 3 |
| Tage mit Lesefehler | **1: 2026-08-18** | **0** |
| lesbare Zeilen | 4.709.777 | **4.752.280 (alle)** |

Folgerung: Die Shadow ist vollständig intakt und liegt um 2 Zeilen hinter der Primary (je eine
PING- und GATEWAY_FAR-Zeile). Die Rettung ist ein Neuaufbau aus der Shadow, ergänzt um die 2 Zeilen
aus der Primary (Union über timestamp/target/type). Erwartete Größe bei gemessenen 92,6 Byte/Zeile:
~440 MB. Das H2-Recover-Werkzeug ist nicht nötig. Der Vorschlag „Shadow über die Primary kopieren"
ist zurückgezogen. Die Dateien werden seit 14:02:31 nicht mehr beschrieben: Der Dienst steht seit dem
Stopp um 14:02 (Stop-JVM-Log 14:02:30).

## 2. Ursache 1: Dateiwachstum durch `WRITE_DELAY=0` + Autocommit pro Statement (belegt)

### SignalReport-Code (Stand 2.0.1)
- `H2MeasurementRepository.java:56` – JDBC-Suffix `;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0` (beide Twins).
- `H2MeasurementRepository.save()` – INSERT + MERGE `hosts`, jeweils im Autocommit (nirgends
  `setAutoCommit(false)`): **2 Commits pro Messung pro DB**, 10 Commits pro Zyklus pro DB,
  ~86.000 Chunk-Schreibvorgänge pro Tag pro DB. Die Chunk-Nummer 5.733.794 aus der Trace-Datei
  bestätigt die Größenordnung (~57.000 Chunks pro Tag seit Juni).

### H2 2.4.240 (Sources-Jar aus dem lokalen Maven-Repository)
- `Database.java:1792–1794` – `setWriteDelay(v)` → `mvStore.setAutoCommitDelay(v)`.
  Standard: `WRITE_DELAY = 500` (`Constants.java:228`, `Database.java:383`).
- `FileStore.java:420–437` – `setAutoCommitDelay(millis)`: der `BackgroundWriterThread` wird **nur bei
  `millis > 0`** gestartet. Bei `WRITE_DELAY=0` gibt es keinen Hintergrund-Thread.
- `FileStore.java:1833–1852` – nur der Hintergrund-Thread ruft `writeInBackground()` →
  `doHousekeeping()` auf; `RandomAccessStore.java:704–747` – dort passiert die laufende Kompaktierung
  (Chunks verschieben/neu schreiben, sobald der Füllgrad unter `autoCompactFillRate` = 90 fällt,
  `FileStore.java:224`). **Ohne Thread findet im Betrieb keine Kompaktierung statt.**
- Normales Schließen kompaktiert höchstens `MAX_COMPACT_TIME` = 200 ms (`DbSettings.java:145`,
  `Database.java:1310–1314`, `FileStore.java:273–279`). `SHUTDOWN COMPACT` / `SHUTDOWN DEFRAG`
  erzwingen die vollständige Kompaktierung (`allowedCompactionTime = -1` → `MVStore.java:792–824`).
- Standard-Sperrmethode ist `FileLockMethod.FS` (`Database.java:248`): exklusive Sperre auf der
  ganzen Datei (`SingleFileStore.lockFileChannel`, `tryLock(0, Long.MAX_VALUE, readOnly)`), keine
  `.lock.db`-Datei. Ein zweiter Prozess kann die Datei nicht schreibend öffnen.

### Messung (`H2GrowthExperiment.java`)
Schema 1:1 aus `createTablesOn()` (inkl. aller Indizes), pro Zyklus 5 Messzeilen mit je einem
`hosts`-MERGE wie in `save()`; 1.000 Zyklen = 5.000 Zeilen (≈ 2,8 h tars-Betrieb). JDK 25, H2 2.4.240,
lokale SSD, 2026-10-04.

| Modus | Commits/Zyklus | Dauer | Datei offen | nach `close()` | nach `SHUTDOWN COMPACT` |
|---|---|---|---|---|---|
| `wd0-auto` = **Zustand bis 2.0.1** | 10 | 1,3 s | 188.338.176 | 188.358.656 | 462.848 |
| `wddef-auto` (WRITE_DELAY Standard) | 10 | 0,4 s | 12.288 ¹ | 1.306.624 | 462.848 |
| `wd0-tx` (WRITE_DELAY=0, 1 Transaktion/Zyklus) | 1 | 0,6 s | 33.423.360 | 2.617.344 | 462.848 |
| `wddef-tx` (Standard, 1 Transaktion/Zyklus) = **ab 2.0.2** | 1 | 0,3 s | 12.288 ¹ | 1.306.624 | 462.848 |

¹ Zum Messzeitpunkt noch nicht geschrieben (Hintergrund-Thread flusht nach ≤ 500 ms; beim `close()`
waren alle Daten in der Datei).

Ergebnis:
- Nutzdaten inkl. Indizes: **92,6 Byte/Zeile**. Hochrechnung tars: 4,75 Mio. Zeilen ≈ **440 MB** kompakt.
- Zustand bis 2.0.1: **37,7 KB Dateiwachstum pro Zeile** (Faktor ~400 im Kurzlauf). Die 71 GB auf
  tars bestehen zu > 99 % aus toten Chunks, nicht aus Messdaten.
- Mit Standard-`WRITE_DELAY` bleibt die Datei im Kurzlauf bei 2,8× der Nutzdaten; zusätzlich eine
  Transaktion pro Zyklus senkt die Chunk-Anzahl nochmals um Faktor 10.
- Die Schluss-Kompaktierung (200 ms) hat die 188-MB-Datei nicht verkleinert.

Nicht gemessen, aber zu den Beobachtungen passend (Vermutung): Die 5–7 s beim ersten Seitenaufruf
nach Tagen entstehen durch kalte Lesezugriffe auf eine 25-GB-Datei mit weit verstreuten Seiten; nach
dem Neuaufbau (~440 MB) passt die DB vollständig in den Dateisystem-Cache.

## 3. Ursache 2: Der Dienst-Stopp beendete die JVM hart (belegt, bestätigt)

- `deployment/windows/in.ps1` (bis 2.0.1) – `--StartMode=exe`, `--StopMode=exe`,
  `--StopImage=java.exe`, `--StopParams=...#-jar#signalreport.jar#stop`, `--StopTimeout=10`,
  **kein `--StopPath`**.
- `SignalReportApp.main(String[] args)` wertete `args` nicht aus. `java -jar signalreport.jar stop`
  startete daher die **komplette Anwendung**: Konfiguration laden, Datenbank öffnen, Webserver starten
  → Port 4567 belegt → genau der im Log stehende Stack.
- **Bestätigt auf tars (Prüfung 3 und 4, 2026-10-04):** Die Stop-JVM läuft in `C:\WINDOWS\system32`
  (Log 14:02:30 „Konfiguration geladen: C:\WINDOWS\system32\config.json"); dort existieren
  `config.json` und `data\signalreport.mv.db`. Die anschließende Logzeile „Datenbanken sauber
  geschlossen" stammt vom Shutdown-Hook der Stop-JVM für ihre eigenen System32-Dateien.
- Die laufende JVM erhielt kein Stoppsignal. prunsrv-Doku: *„Once the time out is elapsed, procrun will
  try to kill the whole process tree the service has created."* Microsoft-Doku zu TerminateProcess:
  *„used to unconditionally cause a process to exit"*, *„stops execution of all threads within the
  process"*. Beim Kill läuft kein Code der JVM mehr → Shutdown-Hook läuft nie, `repo.close()` wird nie
  aufgerufen, H2 schreibt nie den sauberen Abschluss, keine Schluss-Kompaktierung.
- Offen: Warum am 2026-10-03 18:41:50 beim Dienst-Start (Log: „Konfiguration geladen:
  C:\ProgramData\SignalReport\config.json") der Port belegt war. Die exklusive FS-Sperre beweist, dass
  zu diesem Zeitpunkt kein zweiter Prozess die DB-Dateien offen hatte, obwohl einer den Port hielt.
  Die Ereignisanzeige-Abfrage (Service Control Manager, 18:30–18:50) lieferte keine Ausgabe.
  Nicht geklärt, durch Phase 1 aber gegenstandslos.

## 4. Korruption und Auto-Recovery

- Die Auto-Recovery in `openTwins()` prüft ausschließlich das **Öffnen** der DB; `isCorruptionError`
  wird nur dort ausgewertet. Ein 90030 bei einer Abfrage im Betrieb wird weder erkannt noch auf die
  Shadow umgeleitet (alle Lesezugriffe gehen auf die Primary). Deshalb kein automatischer Fix.
- Primary: genau eine unlesbare Seite (Tag 2026-08-18). Shadow: vollständig intakt (Abschnitt 1).
- Mögliche Auslöser der Korruption (Vermutungen, nicht belegt): ~86.000 Chunk-Schreibvorgänge pro
  Tag pro DB über Monate bei nie sauber geschlossener Datei; harte Prozessabbrüche. Ein voller
  Datenträger scheidet aus (1.440 GB frei).
- Die 28 älteren Exceptions in `signalreport.trace.db` (2026-07-03 bis 2026-09-30, viele nachts um
  02:1x–02:3x) enthalten nicht „File corrupted"; ihre Meldungen wurden noch nicht angesehen:
  `Select-String -Path ...trace.db -Pattern ': exception' -Context 0,1`.

## 5. Umsetzung

**Phase 1 (umgesetzt 2026-10-04, Version 2.0.2, committet als 0674e2c):**
1. `WRITE_DELAY=0` aus der JDBC-URL entfernt (H2-Standard 500 ms).
2. `H2MeasurementRepository.saveAll(List)`: eine Transaktion pro Messrunde (INSERT-Batch + ein
   `hosts`-MERGE, Commit/Rollback); `save()` delegiert daran; `SignalReportApp` sammelt die Runde.
3. `idx_measurements_type` entfällt (`DROP INDEX IF EXISTS`, Präfix von `idx_measurements_type_timestamp`;
   separat abgesichert, damit ein Lesefehler in einer vorgeschädigten Datei den Start nicht verhindert).
4. Echter Stopp: `java -jar signalreport.jar stop` → `StopCommand` → `POST /api/system/shutdown`
   (`SystemRoutes`, nur Loopback, ohne Login-Pflicht) → `SignalReportApp.requestStop()` → Messschleife
   endet nach der laufenden Runde (CountDownLatch statt Interrupt), Webserver stoppt, beide DBs werden
   geschlossen, `System.exit(0)`. `StopCommand` wartet, bis der Port frei ist (max. 90 s).
5. `in.ps1`: `--StopPath=$DATA_DIR`, `--StopTimeout=120`; Update-Modus setzt beides per `//US//` und
   entfernt die verwaisten System32-Dateien (nur SignalReport-eigene).
6. Tests: `saveAll` (2), `SystemRoutesTest` (2), `StopCommandTest` (3). Doku: README (EN/DE),
   Architecture (EN/DE), LaTeX Kapitel 03/04/05, PlantUML-Notizen, ProjectStructure (EN/DE).
   Verifikation: 166 Tests grün; Start/Stopp lokal zweimal sauber durchlaufen.

**Phase 2 – Rettung und Neuaufbau (umgesetzt 2026-10-04, Version 2.1.0):** siehe Abschnitt 7.

**Phase 3 – Aufbereitung (offen):** Stunden-Rollups (`measurement_hourly`), Berichte/Heatmap auf
Rollups, CSV-Streaming + `.zip`, Einstellungs-Karte „Daten-Aufbereitung" unter dem Wartungsfenster.

## 6. Entscheidungen des Nutzers (2026-10-04)

- Messintervall bleibt 10 s.
- Retention: alle „Funde" (Ausfälle) dauerhaft behalten; „alles OK"-Strecken nach 30/60/90 Tagen zu
  Zusammenfassungen eindampfen (Anzahl, Min, Max, Durchschnitt, Median je Zeitraum). Umsetzung als
  Stunden-Rollups plus Behalten aller Fehlschläge, der ersten erfolgreichen Messung nach einem
  Fehlschlag (Ausfall-Ende) und der MAINTENANCE-Marker. DB-, CSV- und PDF-Anpassungen akzeptiert.
- Aufbereitungsfenster: Default 03–05 Uhr und Option „im Wartungsfenster ausführen".
- CSV „Alle Daten": `.zip` (bekannter als `.gz`; `java.util.zip.ZipOutputStream`, streamend).
- Phase 1 freigegeben und committet; Phase 2 freigegeben.

## 7. Phase 2: Neuaufbau und Selbstheilung (umgesetzt 2026-10-04, Version 2.1.0)

- `storage/DatabaseRebuilder`: öffnet Primary und Shadow **nur lesend** (`ACCESS_MODE_DATA=r`;
  läuft der Dienst noch, scheitert das an der exklusiven H2-Sperre, also eingebaute Sicherung),
  baut `<pfad>-rebuild.mv.db` mit dem aktuellen Schema auf (`H2MeasurementRepository.createSchema`),
  kopiert `hosts`, `ip_changes`, `service_checks` und `measurements` als **Vereinigung beider
  Quellen** (Schlüssel: timestamp, target, type, host_hash; `excluded` = ODER), Tag für Tag in je
  einer Transaktion. Scheitert ein Tag in einer Quelle, wird stundenweise gelesen; unlesbare Stunden
  werden im Bericht aufgeführt (`RebuildReport`). Danach `SHUTDOWN COMPACT`, Alt-Dateien nach
  `data/quarantine/rebuild_<Zeit>/` verschieben (kein Löschen), neue Datei als Primary einsetzen,
  Kopie als Shadow, Bericht `data/signalreport_rebuild-report_<Zeit>.txt`.
- Aufruf: `java -jar signalreport.jar rebuild-db [--no-swap]` im Datenverzeichnis bei gestopptem
  Dienst (`RebuildCommand`); Begleitskripte `deployment/windows/dbrebuild.bat` (+ `.ps1`) und
  `deployment/macos-linux/dbrebuild.sh` stoppen den Dienst, führen den Neuaufbau aus und starten wieder.
- Selbstheilung: alle Lesezugriffe laufen über `readWithFallback`; scheitert einer mit H2-Fehler
  90030 auf der Primary, wird er auf der Shadow wiederholt (Berichte funktionieren trotz kaputter
  Primary) und die Marker-Datei `<pfad>.REBUILD_REQUIRED` gesetzt. Beim nächsten Start führt der
  Konstruktor von `H2MeasurementRepository` den Neuaufbau aus, bevor er die Twins öffnet; scheitert
  er, wird der Marker nach `<pfad>.REBUILD_FAILED` überführt, damit nicht jeder Start erneut
  minutenlang scheitert.
- Tests: `DatabaseRebuilderTest` (Vereinigung inkl. `excluded`-ODER, Stunden-Fallback bei simuliert
  unlesbarem Tag, Dateitausch mit Quarantäne und Shadow-Kopie, Marker-gesteuerter Neuaufbau beim
  Start, Abbruch ohne lesbare Quelle), `RebuildCommandTest`. 171 Tests gesamt, 168 ausgeführt.
- **Erster Lauf auf tars (2026-10-04 18:24, Oracle JDK 26.0.1, `java` aus dem PATH):** Beide
  Quellen geöffnet (Primary 25,0 GB / 4.752.982 Zeilen, Shadow 46,1 GB / 4.752.980 Zeilen), nach
  ~20 Tagen (818.402 Zeilen, 39 s) **Absturz der JVM**: `EXCEPTION_ACCESS_VIOLATION` im
  C2-kompilierten `java.util.TimSort.countRunAndMakeAscending` (hs_err_pid16212.log in
  `C:\ProgramData\SignalReport`). Alte Dateien unverändert (Abbruch vor dem Dateitausch).
  Ob es ein bekannter JDK-26.0.1-Fehler ist, ließ sich per Websuche nicht belegen.
  Gegenmaßnahmen: Vereinigung pro Tag jetzt in einer `TreeMap` mit festem Comparator statt
  `List.sort` (kein TimSort mehr auf diesem Pfad); die Skripte starten den Neuaufbau mit
  `-XX:TieredStopAtLevel=1` (nur C1-JIT) und zeigen die verwendete Java-Version an. Empfehlung:
  tars auf ein LTS-JDK (21 oder 25) umstellen; prüfen, welches Java der Dienst selbst nutzt.
- **Anwendung auf tars:** Update auf 2.1.0 per `install.bat` (Update-Modus), danach `dbrebuild.bat`
  als Administrator. Erwartung laut Inventar: 4.752.282 Zeilen aus Shadow + 2 Zeilen nur aus der
  Primary, eine unlesbare Stunde am 2026-08-18 in der Primary (durch die Shadow abgedeckt), Ergebnis
  ~440 MB. Die 71 GB alten Dateien liegen danach unter `data\quarantine\rebuild_<Zeit>\` und können
  nach Sichtung des Berichts gelöscht werden. Der 12-Monats-PDF lädt weiterhin alle Zeilen in den
  Speicher und bleibt bis Phase 3 (Rollups) langsam oder speicherhungrig; der Korruptionsfehler ist
  damit aber behoben.
