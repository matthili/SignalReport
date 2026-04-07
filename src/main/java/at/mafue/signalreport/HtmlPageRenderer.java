package at.mafue.signalreport;

public class HtmlPageRenderer
{
    public String render()
    {
        boolean darkMode = Config.getInstance().getTheme().isDarkMode();
        String bodyClass = darkMode ? " class=\"dark-mode\"" : "";
        return """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <link rel="icon" id="favicon" type="image/png" href="/favicon-32x32-light.png">
                    <link rel="apple-touch-icon" href="/apple-icon-180x180-light.png">
                    <title>SignalReport</title>
                    <script src="https://cdn.jsdelivr.net/npm/chart.js@3.9.1/dist/chart.min.js"></script>
                    <style>
                        :root {
                            --bg-body: #f8f9fa;
                            --bg-card: #ffffff;
                            --bg-tab-inactive: #e9ecef;
                            --bg-tab-active: #0d6efd;
                            --bg-table-header: #0d6efd;
                            --bg-region-europa: #cfe8ff;
                            --bg-region-nordamerika: #ffe0b2;
                            --color-primary: #0d6efd;
                            --color-text: #212529;
                            --color-text-secondary: #6c757d;
                            --color-border: #dddddd;
                            --color-shadow: rgba(0,0,0,0.1);
                            --color-shadow-light: rgba(0,0,0,0.05);
                            --color-input-bg: #ffffff;
                            --color-input-border: #ddd;
                            --bg-info-box: #e7f5ff;
                            --bg-card-warn: #fff8e6;
                            --bg-card-warn-inner: #fff3cd;
                        }
                        body.dark-mode {
                            --bg-body: #1a1a2e;
                            --bg-card: #16213e;
                            --bg-tab-inactive: #2c2c44;
                            --bg-tab-active: #0d6efd;
                            --bg-table-header: #0a58ca;
                            --bg-region-europa: #1a3a5c;
                            --bg-region-nordamerika: #3d2e0a;
                            --color-primary: #4d94ff;
                            --color-text: #e0e0e0;
                            --color-text-secondary: #9e9e9e;
                            --color-border: #3a3a5c;
                            --color-shadow: rgba(0,0,0,0.3);
                            --color-shadow-light: rgba(0,0,0,0.2);
                            --color-input-bg: #1e1e3a;
                            --color-input-border: #3a3a5c;
                            --bg-info-box: #0a2540;
                            --bg-card-warn: #2a1f0a;
                            --bg-card-warn-inner: #3d2e0a;
                        }
                        *, *::before, *::after { box-sizing: border-box; }
                        body { font-family: Arial, sans-serif; max-width: 1230px; margin: 40px auto; padding: 20px; background: var(--bg-body); color: var(--color-text); }
                        .header { text-align: center; margin-bottom: 30px; position: relative; }
                        .header-logo { max-width: 400px; height: auto; margin: 0 auto; display: block; }
                        .header-logo.dark { display: none; }
                        body.dark-mode .header-logo.light { display: none; }
                        body.dark-mode .header-logo.dark { display: block; margin: 0 auto; }
                        #theme-toggle { position: absolute; right: 0; top: 10px; background: none; border: 2px solid var(--color-primary); border-radius: 50%; width: 40px; height: 40px; font-size: 20px; cursor: pointer; color: var(--color-primary); }
                        .network-info { background: var(--bg-card); padding: 15px; border-radius: 8px; margin-bottom: 20px; box-shadow: 0 2px 4px var(--color-shadow); }
                        .network-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 15px; }
                        .network-item { text-align: center; word-wrap: break-word; hyphens: auto; }
                        .network-label { font-size: 0.9em; color: var(--color-text-secondary); margin-bottom: 5px; }
                        .network-value { font-weight: bold; font-family: monospace; font-size: 1.1em; color: var(--color-primary); }
                        .tabs { display: flex; gap: 10px; margin-bottom: 20px; flex-wrap: wrap; }
                        .tab { padding: 10px 20px; background: var(--bg-tab-inactive); color: var(--color-text); border: none; border-radius: 5px; cursor: pointer; }
                        .tab.active { background: var(--bg-tab-active); color: white; }
                        .tab-content { display: none; }
                        .tab-content.active { display: block; }
                        .stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 15px; margin-bottom: 25px; }
                        .stat-card { background: var(--bg-card); padding: 15px; border-radius: 8px; text-align: center; box-shadow: 0 2px 4px var(--color-shadow-light); }
                        .stat-label { font-size: 0.9em; color: var(--color-text-secondary); margin-bottom: 5px; }
                        .stat-value { font-size: 1.8em; font-weight: bold; }
                        .stat-avg { color: var(--color-primary); }
                        .stat-p95 { color: #fd7e14; }
                        .stat-loss { color: #dc3545; }
                        .stat-jitter { color: #198754; }
                        .chart-container { width: 100%; height: 300px; margin-bottom: 30px; background: var(--bg-card); padding: 15px; border-radius: 8px; box-shadow: 0 2px 4px var(--color-shadow); }
                        .heatmap-container { width: 100%; height: 220px; margin-top: 30px; background: var(--bg-card); padding: 15px; border-radius: 8px; box-shadow: 0 2px 4px var(--color-shadow); }
                        table { width: 100%; border-collapse: collapse; margin-top: 20px; background: var(--bg-card); border-radius: 8px; overflow: hidden; box-shadow: 0 2px 4px var(--color-shadow); }
                        th, td { padding: 12px 15px; text-align: left; border-bottom: 1px solid var(--color-border); font-size: 0.9em; }
                        th { background: var(--bg-table-header); color: white; }
                        .excellent { color: #198754; font-weight: bold; }
                        .good { color: #ffc107; font-weight: bold; }
                        .poor { color: #dc3545; font-weight: bold; }
                        .failure { color: var(--color-text-secondary); text-decoration: line-through; }
                        .footer { text-align: center; margin-top: 30px; color: var(--color-text-secondary); font-size: 0.9em; }
                        .button-group { display: flex; gap: 10px; margin: 25px 0; flex-wrap: wrap; }
                        .btn { padding: 10px 15px; background: var(--color-primary); color: white; border: none; border-radius: 5px; cursor: pointer; text-decoration: none; display: inline-block; }
                        .btn-secondary { background: var(--color-text-secondary); }
                        .btn-success { background: #198754; }
                        .region-europa { background: var(--bg-region-europa); }
                        .region-nordamerika { background: var(--bg-region-nordamerika); }
                        .dns-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(250px, 1fr)); gap: 15px; margin-top: 20px; }
                        .dns-card { background: var(--bg-card); padding: 15px; border-radius: 8px; box-shadow: 0 2px 4px var(--color-shadow); }
                        .dns-card h4 { margin: 0 0 10px 0; font-size: 1.1em; }
                        .dns-card .latency { font-size: 1.5em; font-weight: bold; color: var(--color-primary); }
                        .dns-card .region { font-size: 0.9em; color: var(--color-text-secondary); }
                        .dns-card .success { color: #198754; }
                        .dns-card .failure { color: #dc3545; }
                        input, select { background: var(--color-input-bg); color: var(--color-text); border: 1px solid var(--color-input-border); }
                    </style>
                </head>
                <body""" + bodyClass + """
                >
                    <div class="header">
                        <button id="theme-toggle" onclick="toggleTheme()" title="Dark Mode umschalten"></button>
                        <img src="/logo_mit_schriftzug_light_web.png" alt="SignalReport" class="header-logo light">
                        <img src="/logo_mit_schriftzug_dark.png" alt="SignalReport" class="header-logo dark">
                    </div>
                
                    <div class="network-info">
                        <div class="network-grid">
                            <div class="network-item">
                                <div class="network-label">Lokale IPv4</div>
                                <div class="network-value" id="local-ipv4">--</div>
                            </div>
                            <div class="network-item">
                                <div class="network-label">Externe IPv4</div>
                                <div class="network-value" id="external-ipv4">--</div>
                            </div>
                            <div class="network-item">
                                <div class="network-label">Hostname</div>
                                <div class="network-value" id="current-host">--</div>
                            </div>
                            <div class="network-item">
                                <div class="network-label">Host-Hash</div>
                                <div class="network-value" style="font-size:0.9em;" id="current-hash">--</div>
                            </div>
                            <div class="network-item">
                                <div class="network-label">Lokale IPv6</div>
                                <div class="network-value" id="local-ipv6">--</div>
                            </div>
                            <div class="network-item">
                                <div class="network-label">Externe IPv6</div>
                                <div class="network-value" id="external-ipv6">--</div>
                            </div>
                        </div>
                    </div>
                
                    <div class="tabs">
                        <button class="tab active" onclick="showTab('monitoring')">\uD83D\uDCCA Monitoring</button>
                        <button class="tab" onclick="showTab('dns')">\uD83C\uDF0D DNS-Benchmark</button>
                        <button class="tab" onclick="showTab('hosts')">\uD83D\uDDA5\uFE0F Hosts</button>
                        <button class="tab" onclick="showTab('ip-tracking')">\uD83C\uDF10 IP-Tracking</button>
                        <button class="tab" onclick="showTab('security')">\uD83D\uDD10 Sicherheit</button>
                        <button class="tab" onclick="showTab('settings')">\u2699\uFE0F Einstellungen</button>
                    </div>
                
                    <div id="monitoring" class="tab-content active">
                        <div class="stat-grid">
                            <div class="stat-card">
                                <div class="stat-label">\u2300 PING (24h)</div>
                                <div class="stat-value stat-avg" id="stat-avg">-- ms</div>
                            </div>
                            <div class="stat-card">
                                <div class="stat-label">95. Perzentil</div>
                                <div class="stat-value stat-p95" id="stat-p95">-- ms</div>
                            </div>
                            <div class="stat-card">
                                <div class="stat-label">Paketverlust</div>
                                <div class="stat-value stat-loss" id="stat-loss">-- %</div>
                            </div>
                            <div class="stat-card">
                                <div class="stat-label">Jitter</div>
                                <div class="stat-value stat-jitter" id="stat-jitter">-- ms</div>
                            </div>
                        </div>
                
                        <div class="chart-container">
                            <canvas id="latencyChart"></canvas>
                        </div>
                
                        <div class="heatmap-container">
                            <canvas id="hourlyChart"></canvas>
                        </div>
                
                        <div class="button-group">
                            <a href="#" class="btn" onclick="downloadReport(24)">\uD83D\uDCC4 PDF-Bericht (24h)</a>
                            <a href="#" class="btn btn-secondary" onclick="downloadReport(168)">\uD83D\uDCC4 PDF-Bericht (7 Tage)</a>
                            <a href="#" class="btn btn-secondary" onclick="downloadReport(8760)">\uD83D\uDCC4 PDF-Bericht (12 Monate)</a>
                            <a href="#" class="btn btn-secondary" onclick="downloadCsv(24)">\uD83D\uDCCA CSV-Export (24h)</a>
                            <a href="#" class="btn btn-secondary" onclick="downloadCsv(168)">\uD83D\uDCCA CSV-Export (7 Tage)</a>
                            <a href="#" class="btn btn-secondary" onclick="downloadCsvAll()">\uD83D\uDCCA CSV-Export (Alle Daten)</a>
                        </div>
                
                        <table>
                            <thead>
                                <tr>
                                    <th>Zeit</th>
                                    <th>Typ</th>
                                    <th>Ziel</th>
                                    <th>Latenz</th>
                                    <th>Status</th>
                                    <th>Host</th>
                                    <th>LAN IP</th>
                                </tr>
                            </thead>
                            <tbody id="measurementsTable">
                                <tr><td colspan="7" style="text-align:center">Lade Daten...</td></tr>
                            </tbody>
                        </table>
                    </div>
                
                    <div id="dns" class="tab-content">
                        <h2>\uD83C\uDF0D DNS-Benchmark</h2>
                        <p>Vergleiche die Performance verschiedener DNS-Server weltweit.</p>
                
                        <div class="button-group">
                            <button class="btn btn-success" onclick="runDnsBenchmark()">\uD83D\uDE80 Benchmark ausführen</button>
                            <select id="dnsHostname" style="padding: 10px; border-radius: 5px;">
                                <option value="google.com">google.com</option>
                                <option value="example.com">example.com</option>
                                <option value="github.com">github.com</option>
                                <option value="wikipedia.org">wikipedia.org</option>
                            </select>
                        </div>
                
                        <div id="dnsResults" class="dns-grid"></div>
                        <p id="dnsLoading" style="text-align:center; margin-top:20px;">Klicke auf "Benchmark ausführen"...</p>
                    </div>
                
                    <div id="hosts" class="tab-content">
                        <h2>\uD83D\uDDA5\uFE0F Host-Informationen</h2>
                
                        <table id="hostsTable">
                            <thead>
                                <tr>
                                    <th>Hostname</th>
                                    <th>OS</th>
                                    <th>Hash</th>
                                    <th>Erstellt</th>
                                    <th>Zuletzt gesehen</th>
                                </tr>
                            </thead>
                            <tbody id="hostsTableBody"></tbody>
                        </table>
                    </div>
                
                    <div id="ip-tracking" class="tab-content">
                        <h2>\uD83C\uDF10 IP-Änderungs-Tracking</h2>
                        <p>Überwachung der externen IP-Adresse – erkennt automatisch, wann sich die IP ändert.</p>
                
                        <div style="background:var(--bg-info-box); padding:15px; border-radius:8px; margin:20px 0; border-left:4px solid var(--color-primary);">
                            <strong>\uD83D\uDCA1 Hinweis:</strong>\s
                            <ul style="margin:10px 0 0 20px;">
                                <li>Die externe IPv4-Adresse wird bei jeder Messung überprüft</li>
                                <li>Bei IP-Änderung wird automatisch ein Eintrag erstellt</li>
                                <li>Perfekt für DSL-Anschlüsse mit dynamischer IP</li>
                            </ul>
                        </div>
                
                        <h3>\uD83D\uDCCA IP-Wechsel-Statistik</h3>
                        <table id="ip-stats-table">
                            <thead>
                                <tr>
                                    <th>Host</th>
                                    <th>IP-Wechsel</th>
                                    <th>Erster Wechsel</th>
                                    <th>Letzter Wechsel</th>
                                </tr>
                            </thead>
                            <tbody id="ip-stats-body"></tbody>
                        </table>
                
                        <h3>\uD83D\uDCCB Letzte IP-Änderungen</h3>
                        <table id="ip-changes-table">
                            <thead>
                                <tr>
                                    <th>Zeit</th>
                                    <th>Host</th>
                                    <th>Alt</th>
                                    <th>Neu</th>
                                    <th>Typ</th>
                                </tr>
                            </thead>
                            <tbody id="ip-changes-body">
                                <tr><td colspan="5" style="text-align:center">Lade IP-Änderungen...</td></tr>
                            </tbody>
                        </table>
                    </div>
                
                    <div id="security" class="tab-content">
                        <h2>\uD83D\uDD10 Sicherheit & Authentifizierung</h2>
                
                        <div style="background:var(--bg-info-box); padding:15px; border-radius:8px; margin:20px 0; border-left:4px solid var(--color-primary);">
                            <strong>\uD83D\uDCA1 Hinweis:</strong>\s
                            <ul style="margin:10px 0 0 20px;">
                                <li>Admin-Passwort wurde bei der ersten Einrichtung festgelegt</li>
                                <li>Authentifizierung schützt das Web-Interface bei öffentlicher IP</li>
                                <li>User-Passwort für normale Benutzer, Admin-Passwort für Einstellungen</li>
                            </ul>
                        </div>
                
                        <div style="background:var(--bg-card); padding:20px; border-radius:8px; margin-bottom:20px;">
                            <h3>Authentifizierung</h3>
                
                            <div id="auth-status" style="margin-bottom:20px; padding:15px; border-radius:8px;"></div>
                
                            <div id="auth-enable-section" style="display:none;">
                                <h4>\uD83D\uDD10 Authentifizierung aktivieren</h4>
                                <div style="display:grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap:20px; margin-top:15px;">
                                    <div>
                                        <label style="display:block; margin-bottom:5px; font-weight:bold;">Admin-Passwort</label>
                                        <input type="password" id="auth-admin-password" placeholder="Admin-Passwort" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                                    </div>
                                    <div>
                                        <label style="display:block; margin-bottom:5px; font-weight:bold;">User-Passwort</label>
                                        <input type="password" id="auth-user-password" placeholder="User-Passwort" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                                    </div>
                                </div>
                                <button onclick="enableAuth()" style="margin-top:20px; padding:10px 20px; background:#28a745; color:white; border:none; border-radius:5px; font-weight:bold;">\uD83D\uDD10 Aktivieren</button>
                            </div>
                
                            <div id="auth-disable-section" style="display:none;">
                                <h4>\uD83D\uDD13 Authentifizierung deaktivieren</h4>
                                <div style="margin-top:15px;">
                                    <label style="display:block; margin-bottom:5px; font-weight:bold;">Admin-Passwort</label>
                                    <input type="password" id="auth-disable-admin-password" placeholder="Admin-Passwort" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                                </div>
                                <button onclick="disableAuth()" style="margin-top:20px; padding:10px 20px; background:#dc3545; color:white; border:none; border-radius:5px; font-weight:bold;">\uD83D\uDD13 Deaktivieren</button>
                            </div>
                
                            <div style="margin-top:30px; padding-top:20px; border-top:2px solid #e9ecef;">
                                <h4>\uD83D\uDD10 Admin-Passwort ändern</h4>
                                <div style="display:grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap:20px; margin-top:15px;">
                                    <div>
                                        <label style="display:block; margin-bottom:5px; font-weight:bold;">Aktuelles Passwort</label>
                                        <input type="password" id="change-old-admin-password" placeholder="Aktuelles Passwort" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                                    </div>
                                    <div>
                                        <label style="display:block; margin-bottom:5px; font-weight:bold;">Neues Passwort</label>
                                        <input type="password" id="change-new-admin-password" placeholder="Neues Passwort" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                                    </div>
                                </div>
                                <button onclick="changeAdminPassword()" style="margin-top:20px; padding:10px 20px; background:#198754; color:white; border:none; border-radius:5px; font-weight:bold;">\uD83D\uDD04 Ändern</button>
                            </div>
                        </div>
                    </div>
                
                    <div id="settings" class="tab-content">
                        <h2>\u2699\uFE0F Messkonfiguration</h2>
                        <p>Ändere die Messziele, Intervall und Maintenance-Fenster. Einstellungen werden sofort gespeichert.</p>
                
                        <div style="background:var(--bg-card); padding:20px; border-radius:8px; margin:20px 0;">
                            <h3>\uD83D\uDCCD Messziele & Intervall</h3>
                            <div style="display:grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap:20px; margin-top:15px;">
                                <div>
                                    <label style="display:block; margin-bottom:5px; font-weight:bold;">Ping-Ziel (IP)</label>
                                    <input type="text" id="config-ping" value="8.8.8.8" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                                </div>
                                <div>
                                    <label style="display:block; margin-bottom:5px; font-weight:bold;">DNS-Ziel (Hostname)</label>
                                    <input type="text" id="config-dns" value="google.com" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                                </div>
                                <div>
                                    <label style="display:block; margin-bottom:5px; font-weight:bold;">HTTP-Ziel (URL)</label>
                                    <input type="text" id="config-http" value="https://example.com" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                                </div>
                                <div>
                                    <label style="display:block; margin-bottom:5px; font-weight:bold;">\u23F1\uFE0F Intervall (Sekunden)</label>
                                    <input type="number" id="config-interval" value="10" min="5" max="3600" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                                </div>
                            </div>
                        </div>
                
                        <div style="background:var(--bg-card-warn); padding:20px; border-radius:8px; margin:20px 0; border-left:4px solid #ffc107;">
                            <h3>\u23F8\uFE0F Maintenance-Fenster (Messungsunterbrechung)</h3>
                            <p>Definiere ein Zeitfenster, in dem keine Messungen durchgeführt werden.</p>
                
                            <div style="display:flex; align-items:center; gap:15px; margin-top:15px;">
                                <input type="checkbox" id="maintenance-enabled" style="width:18px; height:18px;">
                                <label for="maintenance-enabled" style="font-weight:bold;">Maintenance-Fenster aktivieren</label>
                            </div>
                
                            <div id="maintenance-fields" style="display:none; margin-top:15px; padding:15px; background:var(--bg-card-warn-inner); border-radius:8px;">
                                <div style="display:grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap:15px; align-items:end;">
                                    <div>
                                        <label style="display:block; margin-bottom:5px; font-weight:bold;">Von Stunde</label>
                                        <select id="maintenance-start-hour" style="width:100%; padding:6px; border:1px solid #ddd; border-radius:4px;"></select>
                                    </div>
                                    <div>
                                        <label style="display:block; margin-bottom:5px; font-weight:bold;">Von Minute</label>
                                        <select id="maintenance-start-minute" style="width:100%; padding:6px; border:1px solid #ddd; border-radius:4px;">
                                            <option value="0">00</option><option value="5">05</option><option value="10">10</option>
                                            <option value="15">15</option><option value="20">20</option><option value="25">25</option>
                                            <option value="30">30</option><option value="35">35</option><option value="40">40</option>
                                            <option value="45">45</option><option value="50">50</option><option value="55">55</option>
                                        </select>
                                    </div>
                                    <div>
                                        <label style="display:block; margin-bottom:5px; font-weight:bold;">Bis Stunde</label>
                                        <select id="maintenance-end-hour" style="width:100%; padding:6px; border:1px solid #ddd; border-radius:4px;"></select>
                                    </div>
                                    <div>
                                        <label style="display:block; margin-bottom:5px; font-weight:bold;">Bis Minute</label>
                                        <select id="maintenance-end-minute" style="width:100%; padding:6px; border:1px solid #ddd; border-radius:4px;">
                                            <option value="0">00</option><option value="5">05</option><option value="10">10</option>
                                            <option value="15">15</option><option value="20">20</option><option value="25">25</option>
                                            <option value="30">30</option><option value="35">35</option><option value="40">40</option>
                                            <option value="45">45</option><option value="50">50</option><option value="55">55</option>
                                        </select>
                                    </div>
                                </div>
                                <div style="margin-top:10px; font-size:0.9em; color:#856404;">
                                    \uD83D\uDCA1 Hinweis: Fenster kann über Mitternacht gehen (z.B. 23:00–01:00)
                                </div>
                            </div>
                        </div>
                
                        <div style="background:var(--bg-info-box); padding:20px; border-radius:8px; margin:20px 0; border-left:4px solid var(--color-primary);">
                            <h3>\uD83D\uDD14 Browser-Benachrichtigungen</h3>
                            <p>Erhalte Push-Benachrichtigungen bei Internet-Problemen (Browser-Berechtigung erforderlich).</p>
                
                            <div style="display:flex; align-items:center; gap:15px; margin:15px 0;">
                                <input type="checkbox" id="push-enabled" style="width:18px; height:18px;">
                                <label for="push-enabled" style="font-weight:bold;">Push-Benachrichtigungen aktivieren</label>
                            </div>
                
                            <div id="push-settings" style="display:none; margin-top:20px; padding:15px; background:var(--bg-body); border-radius:8px;">
                                <div style="display:grid; grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); gap:20px;">
                                    <div>
                                        <label style="display:block; margin-bottom:5px; font-weight:bold;">Latenz-Schwellwert (ms)</label>
                                        <input type="number" id="push-latency-threshold" value="100" min="50" max="1000"\s
                                               style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                                        <small style="color:#6c757d;">Benachrichtigung bei Latenz > Schwellwert</small>
                                    </div>
                                    <div>
                                        <label style="display:block; margin-bottom:5px; font-weight:bold;">Aufeinanderfolgende schlechte Messungen</label>
                                        <input type="number" id="push-consecutive-bad" value="2" min="1" max="10"\s
                                               style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                                        <small style="color:#6c757d;">Anzahl bevor Benachrichtigung ausgelöst wird</small>
                                    </div>
                                </div>
                
                                <div style="margin-top:15px; padding:10px; background:var(--bg-info-box); border-radius:5px; font-size:0.9em;">
                                    <strong>\uD83D\uDD14 Benachrichtigungsarten:</strong>
                                    <ul style="margin:8px 0 0 20px; padding-left:10px;">
                                        <li>\u274C Internet-Ausfall (keine Verbindung)</li>
                                        <li>\u26A0\uFE0F Schlechte Verbindung (Latenz > Schwellwert)</li>
                                    </ul>
                                </div>
                            </div>
                        </div>
                
                        <div style="background:var(--bg-info-box); padding:20px; border-radius:8px; margin:20px 0; border-left:4px solid var(--color-primary);">
                            <h3>\uD83D\uDC64 Kundendaten</h3>
                            <p>Diese Informationen werden im PDF-Bericht angezeigt.</p>
                
                            <div style="display:grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap:20px; margin-top:15px;">
                                <div>
                                    <label style="display:block; margin-bottom:5px; font-weight:bold;">Provider/Tarif</label>
                                    <input type="text" id="config-provider" value="" placeholder="z.B. Telekom / Internet L" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                                </div>
                                <div>
                                    <label style="display:block; margin-bottom:5px; font-weight:bold;">Kundennummer</label>
                                    <input type="text" id="config-customer-id" value="" placeholder="z.B. 47110815" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                                </div>
                                <div>
                                    <label style="display:block; margin-bottom:5px; font-weight:bold;">Name</label>
                                    <input type="text" id="config-user-name" value="" placeholder="z.B. Max Mustermann" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                                </div>
                            </div>
                        </div>
                
                        <button onclick="saveConfig()" style="margin-top:20px; padding:12px 24px; background:#28a745; color:white; border:none; border-radius:5px; font-weight:bold; cursor:pointer;">
                            \uD83D\uDCBE Konfiguration speichern
                        </button>
                        <div id="config-status" style="margin-top:10px; padding:10px; border-radius:4px; display:none;"></div>
                    </div>
                
                    <div class="footer">
                        <p>SignalReport v1.0 • Daten aktualisieren sich automatisch</p>
                    </div>
                
                    <script>
                        // Stunden-Dropdowns befüllen (0-23)
                        function populateHourDropdowns() {
                            const hours = Array.from({length: 24}, (_, i) => i);
                            ['maintenance-start-hour', 'maintenance-end-hour'].forEach(id => {
                                const select = document.getElementById(id);
                                select.innerHTML = '';
                                hours.forEach(hour => {
                                    const option = document.createElement('option');
                                    option.value = hour;
                                    option.textContent = hour.toString().padStart(2, '0');
                                    select.appendChild(option);
                                });
                            });
                        }
                
                        // Tab-Wechsel
                        function showTab(tabId) {
                            document.querySelectorAll('.tab-content').forEach(tab => tab.classList.remove('active'));
                            document.querySelectorAll('.tab').forEach(tab => tab.classList.remove('active'));
                            document.getElementById(tabId).classList.add('active');
                            event.target.classList.add('active');
                
                            if (tabId === 'hosts') {
                                loadHosts();
                            } else if (tabId === 'settings') {
                                populateHourDropdowns();
                                loadConfig();
                            } else if (tabId === 'ip-tracking') {
                                loadIpStatistics();
                                loadIpChanges();
                            } else if (tabId === 'security') {
                               loadAuthStatus();
                            }
                        }
                
                        // Netzwerk-Info laden
                        function loadNetworkInfo() {
                            fetch('/api/host/current')
                                .then(response => response.json())
                                .then(info => {
                                    document.getElementById('local-ipv4').textContent = info.localIPv4 || 'unknown';
                                    document.getElementById('local-ipv6').textContent = info.localIPv6 || 'unknown';
                                    document.getElementById('external-ipv4').textContent = info.externalIPv4 || 'unknown';
                                    document.getElementById('external-ipv6').textContent = info.externalIPv6 || 'unknown';
                                    document.getElementById('current-host').textContent = info.hostname || 'unknown';
                                    document.getElementById('current-hash').textContent = info.hostHash ? info.hostHash.substring(0, 12) : 'unknown';
                                })
                                .catch(error => console.error('Netzwerk-Info-Fehler:', error));
                        }
                
                        // Statistik laden
                        function loadStatistics() {
                            fetch('/api/statistics?hours=24')
                                .then(response => response.json())
                                .then(stats => {
                                    document.getElementById('stat-avg').textContent = stats.ping.avgLatency.toFixed(2) + ' ms';
                                    document.getElementById('stat-p95').textContent = stats.ping.p95Latency.toFixed(2) + ' ms';
                                    document.getElementById('stat-loss').textContent = stats.ping.packetLossPercent.toFixed(1) + ' %';
                                    document.getElementById('stat-jitter').textContent = stats.ping.jitter.toFixed(2) + ' ms';
                                })
                                .catch(error => console.error('Statistik-Fehler:', error));
                        }
                
                        // Haupt-Chart laden
                        function loadMeasurements() {
                            fetch('/api/measurements?limit=20')
                                .then(response => response.json())
                                .then(data => {
                                    const tableBody = document.getElementById('measurementsTable');
                                    tableBody.innerHTML = '';
                                    data.forEach(m => {
                                        const row = document.createElement('tr');
                                        const latencyClass = m.success\s
                                            ? (m.latencyMs < 50 ? 'excellent' : m.latencyMs < 100 ? 'good' : 'poor')
                                            : 'failure';
                                        const statusText = m.success ? '\u2705' : '\u274C';
                                        row.innerHTML = `
                                            <td>${new Date(m.timestamp * 1000).toLocaleTimeString('de-DE')}</td>
                                            <td><strong>${m.type}</strong></td>
                                            <td>${m.target}</td>
                                            <td class="${latencyClass}"><strong>${m.latencyMs.toFixed(2)}</strong> ms</td>
                                            <td>${statusText}</td>
                                            <td style="font-family:monospace;font-size:0.8em;">${m.hostHash.substring(0,8)}</td>
                                            <td style="font-family:monospace;font-size:0.8em;">${m.localIPv4}</td>
                                        `;
                                        tableBody.appendChild(row);
                                    });
                
                                    const pingData = data.filter(m => m.type === 'PING').slice(0, 10).reverse();
                                    const labels = pingData.map(m => new Date(m.timestamp * 1000).toLocaleTimeString('de-DE', {hour: '2-digit', minute:'2-digit', second:'2-digit'}));
                                    const values = pingData.map(m => m.latencyMs);
                
                                    const ctx = document.getElementById('latencyChart').getContext('2d');
                
                                    if (window.latencyChart && typeof window.latencyChart.destroy === 'function') {
                                        window.latencyChart.destroy();
                                    }
                
                                    window.latencyChart = new Chart(ctx, {
                                        type: 'line',
                                         data: {
                                            labels: labels,
                                            datasets: [{
                                                label: 'PING Latenz (ms)',
                                                data: values,
                                                borderColor: '#0d6efd',
                                                backgroundColor: 'rgba(13, 110, 253, 0.1)',
                                                borderWidth: 2,
                                                tension: 0.3,
                                                fill: true,
                                                cubicInterpolationMode: 'monotone',
                                                pointRadius: 4,
                                                pointHoverRadius: 6
                                            }]
                                        },
                                        options: {
                                            animation: false,
                                            responsive: true,
                                            maintainAspectRatio: false,
                                            plugins: {
                                                title: {
                                                    display: true,
                                                    text: 'PING Latenz über Zeit'
                                                }
                                            },
                                            scales: {
                                                y: {
                                                    beginAtZero: true,
                                                    title: { display: true, text: 'Latenz (ms)' }
                                                },
                                                x: {
                                                    title: { display: true, text: 'Uhrzeit' }
                                                }
                                            }
                                        }
                                    });
                                })
                                .catch(error => {
                                    console.error('Fehler beim Laden:', error);
                                    document.getElementById('measurementsTable').innerHTML =\s
                                        '<tr><td colspan="7" style="text-align:center;color:red">Fehler beim Laden der Daten</td></tr>';
                                });
                        }
                
                        // Heatmap laden
                        function loadHourlyChart() {
                            fetch('/api/hourly-averages?days=7&type=PING')
                                .then(response => response.json())
                                .then(data => {
                                    const hours = Array.from({length: 24}, (_, i) => i);
                                    const latencies = hours.map(h => {
                                        const entry = data.find(e => e.hourOfDay === h);
                                        return entry ? entry.avgLatency : null;
                                    });
                
                                    const ctx = document.getElementById('hourlyChart').getContext('2d');
                
                                    if (window.hourlyChart && typeof window.hourlyChart.destroy === 'function') {
                                        window.hourlyChart.destroy();
                                    }
                
                                    window.hourlyChart = new Chart(ctx, {
                                        type: 'bar',
                                        data: {
                                            labels: hours.map(h => h + ':00'),
                                            datasets: [{
                                                label: '\u2300 Latenz pro Stunde (letzte 7 Tage)',
                                                data: latencies.map(l => l !== null ? parseFloat(l.toFixed(2)) : 0),
                                                backgroundColor: latencies.map(l => {
                                                    if (l === null) return '#e9ecef';
                                                    if (l < 50) return '#198754';
                                                    if (l < 100) return '#ffc107';
                                                    return '#dc3545';
                                                }),
                                                borderWidth: 0
                                            }]
                                        },
                                        options: {
                                            responsive: true,
                                            maintainAspectRatio: false,
                                            plugins: {
                                                legend: { display: false },
                                                tooltip: {
                                                    callbacks: {
                                                        label: context => {
                                                            const value = context.parsed.y;
                                                            if (value === 0) return 'Keine Messungen';
                                                            return `${value.toFixed(2)} ms`;
                                                        }
                                                    }
                                                }
                                            },
                                            scales: {
                                                y: {
                                                    beginAtZero: true,
                                                    title: { display: true, text: 'Latenz (ms)' }
                                                },
                                                x: {
                                                    title: { display: true, text: 'Uhrzeit' }
                                                }
                                            }
                                        }
                                    });
                                })
                                .catch(error => console.error('Stunden-Chart-Fehler:', error));
                        }
                
                        // Hosts laden
                        function loadHosts() {
                            fetch('/api/hosts')
                                .then(response => response.json())
                                .then(hosts => {
                                    const tbody = document.getElementById('hostsTableBody');
                                    tbody.innerHTML = '';
                                    hosts.forEach(host => {
                                        const row = document.createElement('tr');
                                        row.innerHTML = `
                                            <td>${host.hostname}</td>
                                            <td>${host.operatingSystem}</td>
                                            <td style="font-family:monospace;font-size:0.8em;">${host.hostHash.substring(0,8)}</td>
                                            <td>${new Date(host.firstSeen * 1000).toLocaleDateString('de-DE')}</td>
                                            <td>${new Date(host.lastSeen * 1000).toLocaleDateString('de-DE')}</td>
                                        `;
                                        tbody.appendChild(row);
                                    });
                                })
                                .catch(error => console.error('Host-Fehler:', error));
                        }
                
                        // DNS-Benchmark State: false=ready, true=running (Cooldown wird per setTimeout gemanagt)
                        // let isDnsBenchmarkRunning = false;
                        // let dnsBenchmarkCooldownTimer = null;
                
                        // DNS-Benchmark ausführen
                            function runDnsBenchmark() {
                              const loadingElement = document.getElementById('dnsLoading');
                
                              // STATE-DEBUG: Immer ausgeben, um Problem zu diagnostizieren
                              console.log('[DNS] State vor Start: isRunning=' + isDnsBenchmarkRunning + ', timer=' + (dnsBenchmarkCooldownTimer ? 'aktiv' : 'null'));
                
                              // Cooldown-Check
                              if (dnsBenchmarkCooldownTimer) {
                                  const remaining = Math.ceil((dnsBenchmarkCooldownEndTime - Date.now()) / 1000);
                                  alert(`\u26A0\uFE0F Bitte warte noch ${remaining} Sekunden bis zum nächsten Benchmark.`);
                                  return;
                              }
                
                              // Running-Check
                              if (isDnsBenchmarkRunning) {
                                  console.warn('[DNS] FEHLER: Benchmark läuft bereits, aber State nicht zurückgesetzt!');
                                  // NOTFALL-RESET: Wenn State inkonsistent ist, erzwinge Reset
                                  isDnsBenchmarkRunning = false;
                                  if (dnsBenchmarkCooldownTimer) {
                                      clearTimeout(dnsBenchmarkCooldownTimer);
                                      dnsBenchmarkCooldownTimer = null;
                                      dnsBenchmarkCooldownEndTime = 0;
                                  }
                                  loadingElement.textContent = 'State zurückgesetzt. Benchmark kann neu gestartet werden.';
                                  return;
                              }
                
                              // State setzen
                              isDnsBenchmarkRunning = true;
                              const hostname = document.getElementById('dnsHostname').value;
                              loadingElement.textContent = 'Benchmark läuft... (max. 10s)';
                
                              // TIMEOUT FÜR HÄNGENDE REQUESTS (10 Sekunden)
                              const controller = new AbortController();
                              const timeoutId = setTimeout(() => controller.abort(), 10000);
                
                              fetch(`/api/dns/benchmark?hostname=${hostname}`, {\s
                                  method: 'POST',
                                  signal: controller.signal
                              })
                              .then(response => {
                                  clearTimeout(timeoutId);
                                  if (!response.ok) throw new Error(`HTTP ${response.status}`);
                                  return response.json();
                              })
                              .then(results => {
                                  const container = document.getElementById('dnsResults');
                                  container.innerHTML = '';
                
                                  results.forEach(result => {
                                      const card = document.createElement('div');
                                      card.className = `dns-card region-${result.region.toLowerCase()}`;
                
                                      const statusClass = result.success ? 'success' : 'failure';
                                      const statusIcon = result.success ? '\u2705' : '\u274C';
                
                                      card.innerHTML = `
                                          <h4>${result.serverName}</h4>
                                          <div class="latency ${statusClass}">${result.latencyMs.toFixed(2)} ms ${statusIcon}</div>
                                          <div class="region">${result.region} \u2022 ${result.provider}</div>
                                          <div style="font-size:0.8em;color:#6c757d;">${result.serverAddress}</div>
                                      `;
                                      container.appendChild(card);
                                  });
                
                                  console.log('[DNS] Benchmark erfolgreich abgeschlossen. Ergebnisse: ' + results.length);
                              })
                              .catch(error => {
                                  clearTimeout(timeoutId);
                                  console.error('[DNS] FEHLER:', error);
                
                                  if (error.name === 'AbortError') {
                                      loadingElement.textContent = '\u26A0\uFE0F Benchmark abgebrochen (Timeout nach 10s)';
                                  } else {
                                      loadingElement.textContent = '\u274C Fehler: ' + (error.message || 'Unbekannter Fehler');
                                  }
                              })
                              .finally(() => {
                                  // GARANTIERTER STATE-RESET (wird IMMER ausgeführt)
                                  console.log('[DNS] finally-Block: State wird zurückgesetzt');
                                  isDnsBenchmarkRunning = false;
                
                                  // Cooldown starten
                                  loadingElement.textContent = 'Nächster Benchmark möglich in 30 Sekunden...';
                                  dnsBenchmarkCooldownEndTime = Date.now() + 30000;
                                  dnsBenchmarkCooldownTimer = setTimeout(() => {
                                      loadingElement.textContent = 'Klicke auf "Benchmark ausführen"...';
                                      dnsBenchmarkCooldownTimer = null;
                                      dnsBenchmarkCooldownEndTime = 0;
                                      console.log('[DNS] Cooldown abgelaufen. State: ready');
                                  }, 30000);
                              });
                          }
                
                        // IP-Statistik laden
                        function loadIpStatistics() {
                            fetch('/api/ip-statistics')
                                .then(response => response.json())
                                .then(stats => {
                                    const tbody = document.getElementById('ip-stats-body');
                                    tbody.innerHTML = '';
                
                                    if (stats.length === 0) {
                                        tbody.innerHTML = '<tr><td colspan="4" style="text-align:center">Keine IP-Änderungen erfasst</td></tr>';
                                        return;
                                    }
                
                                    stats.forEach(stat => {
                                        const row = document.createElement('tr');
                                        row.innerHTML = `
                                            <td style="font-family:monospace;font-size:0.9em;">${stat.hostHash.substring(0,8)}</td>
                                            <td><strong>${stat.changeCount}</strong></td>
                                            <td>${new Date(stat.firstChange * 1000).toLocaleDateString('de-DE')}</td>
                                            <td>${new Date(stat.lastChange * 1000).toLocaleDateString('de-DE')}</td>
                                        `;
                                        tbody.appendChild(row);
                                    });
                                })
                                .catch(error => console.error('IP-Statistik-Fehler:', error));
                        }
                
                        // IP-Änderungen laden
                        function loadIpChanges() {
                            fetch('/api/ip-changes?limit=50')
                                .then(response => response.json())
                                .then(changes => {
                                    const tbody = document.getElementById('ip-changes-body');
                                    tbody.innerHTML = '';
                
                                    if (changes.length === 0) {
                                        tbody.innerHTML = '<tr><td colspan="5" style="text-align:center">Keine IP-Änderungen erfasst</td></tr>';
                                        return;
                                    }
                
                                    changes.forEach(change => {
                                        const row = document.createElement('tr');
                                        const changeType = change.changeType === 'INITIAL' ? '\uD83D\uDFE2 Initial' : '\uD83D\uDD04 Wechsel';
                                        const changeColor = change.changeType === 'INITIAL' ? '#198754' : '#0d6efd';
                
                                        row.innerHTML = `
                                            <td>${new Date(change.timestamp * 1000).toLocaleString('de-DE')}</td>
                                            <td style="font-family:monospace;font-size:0.8em;">${change.hostHash.substring(0,8)}</td>
                                            <td style="font-family:monospace;">${change.oldIp || '\u2013'}</td>
                                            <td style="font-family:monospace;font-weight:bold;color:${changeColor};">${change.newIp}</td>
                                            <td style="color:${changeColor};">${changeType}</td>
                                        `;
                                        tbody.appendChild(row);
                                    });
                                })
                                .catch(error => {
                                    console.error('IP-Änderungen-Fehler:', error);
                                    document.getElementById('ip-changes-body').innerHTML =\s
                                        '<tr><td colspan="5" style="text-align:center;color:red">Fehler beim Laden der IP-Änderungen</td></tr>';
                                });
                        }
                
                        // Einstellungen laden (inkl. Push)
                        function loadConfig() {
                            fetch('/api/config/current')
                                .then(response => response.json())
                                .then(config => {
                                    document.getElementById('config-ping').value = config.ping;
                                    document.getElementById('config-dns').value = config.dns;
                                    document.getElementById('config-http').value = config.http;
                                    document.getElementById('config-interval').value = config.intervalSeconds;
                
                                    const maint = config.maintenance;
                                    document.getElementById('maintenance-enabled').checked = maint.enabled;
                                    document.getElementById('maintenance-start-hour').value = maint.startHour;
                                    document.getElementById('maintenance-start-minute').value = maint.startMinute;
                                    document.getElementById('maintenance-end-hour').value = maint.endHour;
                                    document.getElementById('maintenance-end-minute').value = maint.endMinute;
                                    document.getElementById('maintenance-fields').style.display = maint.enabled ? 'block' : 'none';
                
                                    // Push-Einstellungen laden
                                    fetch('/api/push/settings')
                                        .then(response => response.json())
                                        .then(push => {
                                            document.getElementById('push-enabled').checked = push.enabled;
                                            document.getElementById('push-latency-threshold').value = push.latencyThreshold;
                                            document.getElementById('push-consecutive-bad').value = push.consecutiveBadMeasurements;
                                            document.getElementById('push-settings').style.display = push.enabled ? 'block' : 'none';
                                        })
                                        .catch(error => console.error('Push-Lade-Fehler:', error));
                
                                    const ui = config.userInfo;
                                    document.getElementById('config-provider').value = ui.provider || '';
                                    document.getElementById('config-customer-id').value = ui.customerId || '';
                                    document.getElementById('config-user-name').value = ui.userName || '';
                                })
                                .catch(error => console.error('Config-Lade-Fehler:', error));
                        }
                
                        // Maintenance-Checkbox Toggle
                        document.getElementById('maintenance-enabled').addEventListener('change', function() {
                            document.getElementById('maintenance-fields').style.display = this.checked ? 'block' : 'none';
                        });
                
                        // Push-Checkbox Toggle
                        document.getElementById('push-enabled').addEventListener('change', function() {
                            document.getElementById('push-settings').style.display = this.checked ? 'block' : 'none';
                
                            // Browser-Berechtigung anfragen, wenn aktiviert
                            if (this.checked && 'Notification' in window && Notification.permission !== 'granted') {
                                Notification.requestPermission().then(permission => {
                                    if (permission !== 'granted') {
                                        alert('\u26A0\uFE0F Benachrichtigungen wurden nicht erlaubt. Bitte erlaube sie in den Browser-Einstellungen.');
                                    }
                                });
                            }
                        });
                
                        // Konfiguration speichern (inkl. Push)
                        function saveConfig() {
                            const statusDiv = document.getElementById('config-status');
                            statusDiv.style.display = 'block';
                            statusDiv.style.background = '#fff3cd';
                            statusDiv.textContent = '\uD83D\uDCBE Speichere Konfiguration...';
                
                            // Haupt-Konfiguration
                            const config = {
                                ping: document.getElementById('config-ping').value.trim(),
                                dns: document.getElementById('config-dns').value.trim(),
                                http: document.getElementById('config-http').value.trim(),
                                intervalSeconds: parseInt(document.getElementById('config-interval').value),
                                maintenance: {
                                    enabled: document.getElementById('maintenance-enabled').checked,
                                    startHour: parseInt(document.getElementById('maintenance-start-hour').value),
                                    startMinute: parseInt(document.getElementById('maintenance-start-minute').value),
                                    endHour: parseInt(document.getElementById('maintenance-end-hour').value),
                                    endMinute: parseInt(document.getElementById('maintenance-end-minute').value)
                                },
                                userInfo: {
                                    provider: document.getElementById('config-provider').value.trim(),
                                    customerId: document.getElementById('config-customer-id').value.trim(),
                                    userName: document.getElementById('config-user-name').value.trim()
                                }
                            };
                
                            //  Push-Konfiguration separat speichern
                            const pushConfig = {
                                enabled: document.getElementById('push-enabled').checked,
                                latencyThreshold: parseFloat(document.getElementById('push-latency-threshold').value),
                                consecutiveBadMeasurements: parseInt(document.getElementById('push-consecutive-bad').value)
                            };
                
                            // Zuerst Haupt-Konfiguration speichern
                            fetch('/api/config/update', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify(config)
                            })
                            .then(response => response.text())
                            .then(message => {
                                // Dann Push-Konfiguration speichern
                                return fetch('/api/push/settings', {
                                    method: 'POST',
                                    headers: { 'Content-Type': 'application/json' },
                                    body: JSON.stringify(pushConfig)
                                });
                            })
                            .then(response => response.text())
                            .then(message => {
                                statusDiv.style.background = '#778899';
                                statusDiv.textContent = '\u2705 Konfiguration erfolgreich gespeichert!';
                                setTimeout(() => { statusDiv.style.display = 'none'; }, 3000);
                            })
                            .catch(error => {
                                statusDiv.style.background = '#f8d7da';
                                statusDiv.textContent = '\u274C Fehler: ' + error.message;
                            });
                        }
                
                        // Security-Tab Funktionen
                        function loadAuthStatus() {
                            fetch('/api/auth/status')
                                .then(r => r.json())
                                .then(s => {
                                    const div = document.getElementById('auth-status');
                                    const enable = document.getElementById('auth-enable-section');
                                    const disable = document.getElementById('auth-disable-section');
                                    if (s.enabled) {
                                        div.innerHTML = '<strong>\u2705 Authentifizierung AKTIV</strong><br>Benutzer: user | Admin: admin';
                                        enable.style.display = 'none'; disable.style.display = 'block';
                                    } else {
                                        div.innerHTML = '<strong>\u26A0\uFE0F Authentifizierung DEAKTIVIERT</strong><br>Web-Interface ist öffentlich zugänglich';
                                        enable.style.display = 'block'; disable.style.display = 'none';
                                    }
                                });
                        }
                        function enableAuth() {
                            const a = document.getElementById('auth-admin-password').value;
                            const u = document.getElementById('auth-user-password').value;
                            if (!a || !u) { alert('Bitte beide Passwörter eingeben!'); return; }
                            fetch('/api/auth/enable', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({adminPassword:a, userPassword:u})})
                            .then(r => r.text()).then(m => { alert(m); loadAuthStatus(); });
                        }
                        function disableAuth() {
                            const p = document.getElementById('auth-disable-admin-password').value;
                            if (!p) { alert('Admin-Passwort eingeben!'); return; }
                            if (!confirm('\u26A0\uFE0F Web-Interface wird öffentlich! Fortfahren?')) return;
                            fetch('/api/auth/disable', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({adminPassword:p})})
                            .then(r => r.text()).then(m => { alert(m); loadAuthStatus(); });
                        }
                        function changeAdminPassword() {
                            const old = document.getElementById('change-old-admin-password').value;
                            const neu = document.getElementById('change-new-admin-password').value;
                            if (!old || !neu || neu.length < 6) { alert('Passwörter prüfen (mind. 6 Zeichen)!'); return; }
                            fetch('/api/auth/change-admin', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({oldPassword:old, newPassword:neu})})
                            .then(r => r.text()).then(m => { alert(m); document.getElementById('change-old-admin-password').value=''; document.getElementById('change-new-admin-password').value=''; });
                        }
                
                        // PDF-Download
                        function downloadReport(hours) {
                            window.location.href = '/api/report?hours=' + hours;
                        }
                
                        // CSV-Download
                        function downloadCsv(hours) {
                            window.location.href = '/api/export/csv?hours=' + hours;
                        }
                
                        // CSV-Download (alle Daten)
                        function downloadCsvAll() {
                            if (confirm('\u26A0\uFE0F Achtung: Dieser Export kann sehr groß werden! Fortfahren?')) {
                                window.location.href = '/api/export/csv?all=true';
                            }
                        }
                
                        // Theme-Toggle
                        const isDarkInitial = document.body.classList.contains('dark-mode');
                        document.getElementById('theme-toggle').textContent = isDarkInitial ? '\u2600\uFE0F' : '\uD83C\uDF19';
                        updateFavicon(isDarkInitial);
                        if (isDarkInitial) {
                            Chart.defaults.color = '#e0e0e0';
                            Chart.defaults.borderColor = '#3a3a5c';
                        }
                
                        function updateFavicon(isDark) {
                            const suffix = isDark ? 'dark' : 'light';
                            document.getElementById('favicon').href = '/favicon-32x32-' + suffix + '.png';
                            const appleIcon = document.querySelector('link[rel="apple-touch-icon"]');
                            if (appleIcon) appleIcon.href = '/apple-icon-180x180-' + suffix + '.png';
                        }
                
                        function toggleTheme() {
                            const isDark = document.body.classList.toggle('dark-mode');
                            document.getElementById('theme-toggle').textContent = isDark ? '\u2600\uFE0F' : '\uD83C\uDF19';
                            updateFavicon(isDark);
                            updateChartTheme(isDark);
                            fetch('/api/theme/settings', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify({ darkMode: isDark })
                            }).catch(err => console.error('Theme-Speicher-Fehler:', err));
                        }
                
                        function updateChartTheme(isDark) {
                            const textColor = isDark ? '#e0e0e0' : '#666';
                            const gridColor = isDark ? '#3a3a5c' : '#e0e0e0';
                            Chart.defaults.color = textColor;
                            Chart.defaults.borderColor = gridColor;
                            if (window.latencyChart) {
                                window.latencyChart.options.scales.x.ticks.color = textColor;
                                window.latencyChart.options.scales.y.ticks.color = textColor;
                                window.latencyChart.options.scales.x.grid.color = gridColor;
                                window.latencyChart.options.scales.y.grid.color = gridColor;
                                window.latencyChart.update();
                            }
                            if (window.hourlyChart) {
                                window.hourlyChart.options.scales.x.ticks.color = textColor;
                                window.hourlyChart.options.scales.y.ticks.color = textColor;
                                window.hourlyChart.options.scales.x.grid.color = gridColor;
                                window.hourlyChart.options.scales.y.grid.color = gridColor;
                                window.hourlyChart.update();
                            }
                        }
                
                        // Initial laden
                        loadNetworkInfo();
                        loadStatistics();
                        loadMeasurements();
                        loadHourlyChart();
                        // DNS-Benchmark State (MUSS HIER STEHEN, NICHT IN DER FUNKTION!)
                        let isDnsBenchmarkRunning = false;
                        let dnsBenchmarkCooldownTimer = null;
                        let dnsBenchmarkCooldownEndTime = 0;
                
                        // Alle 5 Sekunden aktualisieren
                        setInterval(loadMeasurements, 5000);
                        setInterval(loadStatistics, 30000);
                        setInterval(loadHourlyChart, 300000);
                        setInterval(loadNetworkInfo, 60000);
                
                        // IP-Tracking alle 30 Sekunden aktualisieren
                        setInterval(() => {
                            const activeTab = document.querySelector('.tab.active');
                            if (activeTab && activeTab.textContent.includes('IP-Tracking')) {
                                loadIpStatistics();
                                loadIpChanges();
                            }
                        }, 30000);
                    </script>
                </body>
                </html>
                """;
    }
}
