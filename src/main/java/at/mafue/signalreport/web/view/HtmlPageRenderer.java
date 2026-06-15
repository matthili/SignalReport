package at.mafue.signalreport.web.view;

import at.mafue.signalreport.config.GatewayConfig;

import at.mafue.signalreport.config.Config;
import at.mafue.signalreport.i18n.I18n;
import at.mafue.signalreport.network.GatewayDiscovery;

public class HtmlPageRenderer
{
    public String render()
    {
        boolean darkMode = Config.getInstance().getTheme().isDarkMode();
        String bodyClass = darkMode ? " class=\"dark-mode\"" : "";
        String html = """
                <!DOCTYPE html>
                <html lang="__LOCALE__">
                <head>
                    <meta charset="UTF-8">
                    <link rel="icon" id="favicon" type="image/png" href="/favicon-32x32-light.png">
                    <link rel="apple-touch-icon" href="/apple-icon-180x180-light.png">
                    <title>SignalReport</title>
                    <script src="https://cdn.jsdelivr.net/npm/chart.js@3.9.1/dist/chart.min.js"></script>
                    <link rel="stylesheet" href="/app.css">
                </head>
                <body""" + bodyClass + """
                >
                    <div class="header">
                        <button id="theme-toggle" onclick="toggleTheme()" title="{{common.darkModeToggle}}"></button>
                        <button id="logout-btn" onclick="logout()" title="{{common.logout}}" style="position:absolute; right:50px; top:10px; background:none; border:2px solid #dc3545; border-radius:5px; padding:5px 12px; font-size:14px; cursor:pointer; color:#dc3545; display:none;">🚪 {{common.logout}}</button>
                        <img src="/logo_mit_schriftzug_light_web.png" alt="SignalReport" class="header-logo light">
                        <img src="/logo_mit_schriftzug_dark.png" alt="SignalReport" class="header-logo dark">
                    </div>

                    <div class="network-info">
                        <div class="network-grid">
                            <div class="network-item">
                                <div class="network-label">{{network.localIPv4}}</div>
                                <div class="network-value" id="local-ipv4">--</div>
                            </div>
                            <div class="network-item">
                                <div class="network-label">{{network.externalIPv4}}</div>
                                <div class="network-value" id="external-ipv4">--</div>
                            </div>
                            <div class="network-item">
                                <div class="network-label">{{network.hostname}}</div>
                                <div class="network-value" id="current-host">--</div>
                            </div>
                            <div class="network-item">
                                <div class="network-label">{{network.hostHash}}</div>
                                <div class="network-value" style="font-size:0.9em;" id="current-hash">--</div>
                            </div>
                            <div class="network-item">
                                <div class="network-label">{{network.localIPv6}}</div>
                                <div class="network-value" id="local-ipv6">--</div>
                            </div>
                            <div class="network-item">
                                <div class="network-label">{{network.externalIPv6}}</div>
                                <div class="network-value" id="external-ipv6">--</div>
                            </div>
                        </div>
                    </div>

                    <div class="tabs">
                        <button class="tab active" data-tab="monitoring" onclick="showTab('monitoring')">📊 {{nav.monitoring}}</button>
                        <button class="tab" data-tab="dns" onclick="showTab('dns')">🌍 {{nav.dnsBenchmark}}</button>
                        <button class="tab" data-tab="hosts" onclick="showTab('hosts')">🖥️ {{nav.hosts}}</button>
                        <button class="tab" data-tab="ip-tracking" onclick="showTab('ip-tracking')">🌐 {{nav.ipTracking}}</button>
                        <button class="tab" data-tab="security" onclick="showTab('security')">🔐 {{nav.security}}</button>
                        <button class="tab" data-tab="settings" onclick="showTab('settings')">⚙️ {{nav.settings}}</button>
                    </div>

                    <div id="monitoring" class="tab-content active">
                        <div class="stat-grid">
                            <div class="stat-card">
                                <div class="stat-label">{{stats.avgPing}}</div>
                                <div class="stat-value stat-avg" id="stat-avg">-- ms</div>
                            </div>
                            <div class="stat-card">
                                <div class="stat-label">{{stats.p95}}</div>
                                <div class="stat-value stat-p95" id="stat-p95">-- ms</div>
                            </div>
                            <div class="stat-card">
                                <div class="stat-label">{{stats.packetLoss}}</div>
                                <div class="stat-value stat-loss" id="stat-loss">-- %</div>
                            </div>
                            <div class="stat-card">
                                <div class="stat-label">{{stats.jitter}}</div>
                                <div class="stat-value stat-jitter" id="stat-jitter">-- ms</div>
                            </div>
                        </div>

                        <div id="connectivity-card" style="display:none; background:var(--bg-card); padding:15px 20px; border-radius:8px; margin-bottom:25px; box-shadow:0 2px 4px var(--color-shadow); border-left:5px solid var(--color-primary);">
                            <h3 style="margin:0 0 10px 0;">🧭 {{connectivity.title}}</h3>
                            <div id="connectivity-verdict" style="font-weight:bold; margin-bottom:14px;"></div>
                            <div id="connectivity-segments" style="display:flex; flex-wrap:wrap; gap:10px; align-items:stretch;"></div>
                        </div>

                        <div id="reliability-card" style="display:none; background:var(--bg-card); padding:15px 20px; border-radius:8px; margin-bottom:25px; box-shadow:0 2px 4px var(--color-shadow);">
                            <h3 style="margin:0 0 12px 0;">📈 {{reliability.title}}</h3>
                            <div id="reliability-grid" style="display:grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap:12px;"></div>
                            <div id="outage-details" style="display:none; margin-top:14px;"></div>
                        </div>

                        <div class="chart-container">
                            <canvas id="latencyChart"></canvas>
                        </div>

                        <div class="heatmap-container">
                            <canvas id="hourlyChart"></canvas>
                        </div>

                        <div class="button-group">
                            <a href="#" class="btn" onclick="downloadReport(24)">📄 {{buttons.pdfReport24h}}</a>
                            <a href="#" class="btn btn-secondary" onclick="downloadReport(168)">📄 {{buttons.pdfReport7d}}</a>
                            <a href="#" class="btn btn-secondary" onclick="downloadReport(8760)">📄 {{buttons.pdfReport12m}}</a>
                            <a href="#" class="btn btn-secondary" onclick="downloadCsv(24)">📊 {{buttons.csvExport24h}}</a>
                            <a href="#" class="btn btn-secondary" onclick="downloadCsv(168)">📊 {{buttons.csvExport7d}}</a>
                            <a href="#" class="btn btn-secondary" onclick="downloadCsvAll()">📊 {{buttons.csvExportAll}}</a>
                        </div>

                        <table>
                            <thead>
                                <tr>
                                    <th>{{table.time}}</th>
                                    <th>{{table.status}}</th>
                                    <th style="width:1%"></th>
                                </tr>
                            </thead>
                            <tbody id="measurementsTable">
                                <tr><td colspan="3" style="text-align:center">{{common.loadingData}}</td></tr>
                            </tbody>
                        </table>
                    </div>

                    <div id="dns" class="tab-content">
                        <h2>🌍 {{nav.dnsBenchmark}}</h2>
                        <p>{{dns.description}}</p>

                        <div class="button-group">
                            <button class="btn btn-success" onclick="runDnsBenchmark()">🚀 {{dns.runBenchmark}}</button>
                            <select id="dnsHostname" style="padding: 10px; border-radius: 5px;">
                                <option value="google.com">google.com</option>
                                <option value="example.com">example.com</option>
                                <option value="github.com">github.com</option>
                                <option value="wikipedia.org">wikipedia.org</option>
                            </select>
                        </div>

                        <div id="dnsResults" class="dns-grid"></div>
                        <p id="dnsLoading" style="text-align:center; margin-top:20px;">{{dns.clickToRun}}</p>
                    </div>

                    <div id="hosts" class="tab-content">
                        <h2>🖥️ {{hosts.title}}</h2>

                        <table id="hostsTable">
                            <thead>
                                <tr>
                                    <th>{{network.hostname}}</th>
                                    <th>{{hosts.os}}</th>
                                    <th>{{hosts.hash}}</th>
                                    <th>{{hosts.firstSeen}}</th>
                                    <th>{{hosts.lastSeen}}</th>
                                </tr>
                            </thead>
                            <tbody id="hostsTableBody"></tbody>
                        </table>
                    </div>

                    <div id="ip-tracking" class="tab-content">
                        <h2>🌐 {{ip.title}}</h2>
                        <p>{{ip.description}}</p>

                        <div style="background:var(--bg-info-box); padding:15px; border-radius:8px; margin:20px 0; border-left:4px solid var(--color-primary);">
                            <strong>💡 {{common.note}}</strong>\s
                            <ul style="margin:10px 0 0 20px;">
                                <li>{{ip.note1}}</li>
                                <li>{{ip.note2}}</li>
                                <li>{{ip.note3}}</li>
                            </ul>
                        </div>

                        <h3>📊 {{ip.statsTitle}}</h3>
                        <table id="ip-stats-table">
                            <thead>
                                <tr>
                                    <th>{{table.host}}</th>
                                    <th>{{ip.changes}}</th>
                                    <th>{{ip.firstChange}}</th>
                                    <th>{{ip.lastChange}}</th>
                                </tr>
                            </thead>
                            <tbody id="ip-stats-body"></tbody>
                        </table>

                        <h3>📋 {{ip.recentChanges}}</h3>
                        <table id="ip-changes-table">
                            <thead>
                                <tr>
                                    <th>{{table.time}}</th>
                                    <th>{{table.host}}</th>
                                    <th>{{ip.old}}</th>
                                    <th>{{ip.new}}</th>
                                    <th>{{table.type}}</th>
                                </tr>
                            </thead>
                            <tbody id="ip-changes-body">
                                <tr><td colspan="5" style="text-align:center">{{ip.loadingChanges}}</td></tr>
                            </tbody>
                        </table>
                    </div>

                    <div id="security" class="tab-content">
                        <h2>🔐 {{security.title}}</h2>

                        <div style="background:var(--bg-info-box); padding:15px; border-radius:8px; margin:20px 0; border-left:4px solid var(--color-primary);">
                            <strong>💡 {{common.note}}</strong>\s
                            <ul style="margin:10px 0 0 20px;">
                                <li>{{security.note1}}</li>
                                <li>{{security.note2}}</li>
                                <li>{{security.note3}}</li>
                            </ul>
                        </div>

                        <div style="background:var(--bg-card); padding:20px; border-radius:8px; margin-bottom:20px;">
                            <h3>{{security.auth}}</h3>

                            <div id="auth-status" style="margin-bottom:20px; padding:15px; border-radius:8px;"></div>

                            <div id="auth-enable-section" style="display:none;">
                                <h4>🔐 {{security.enableTitle}}</h4>
                                <div style="display:grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap:20px; margin-top:15px;">
                                    <div>
                                        <label style="display:block; margin-bottom:5px; font-weight:bold;">{{security.adminPasswordConfirm}}</label>
                                        <input type="password" id="auth-admin-password" placeholder="{{security.adminPassword}}" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                                    </div>
                                    <div>
                                        <label style="display:block; margin-bottom:5px; font-weight:bold;">{{security.userPasswordMin}}</label>
                                        <input type="password" id="auth-user-password" placeholder="{{security.userPassword}}" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                                    </div>
                                </div>
                                <button onclick="enableAuth()" style="margin-top:20px; padding:10px 20px; background:#28a745; color:white; border:none; border-radius:5px; font-weight:bold;">🔐 {{security.enable}}</button>
                            </div>

                            <div id="auth-disable-section" style="display:none;">
                                <h4>🔓 {{security.disableTitle}}</h4>
                                <div style="margin-top:15px;">
                                    <label style="display:block; margin-bottom:5px; font-weight:bold;">{{security.adminPasswordConfirm}}</label>
                                    <input type="password" id="auth-disable-admin-password" placeholder="{{security.adminPassword}}" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                                </div>
                                <button onclick="disableAuth()" style="margin-top:15px; padding:10px 20px; background:#dc3545; color:white; border:none; border-radius:5px; font-weight:bold;">🔓 {{security.disable}}</button>
                            </div>

                            <div style="margin-top:30px; padding-top:20px; border-top:2px solid #e9ecef;">
                                <h4>🔐 {{security.changeAdminTitle}}</h4>
                                <div style="display:grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap:20px; margin-top:15px;">
                                    <div>
                                        <label style="display:block; margin-bottom:5px; font-weight:bold;">{{security.currentPassword}}</label>
                                        <input type="password" id="change-old-admin-password" placeholder="{{security.currentPassword}}" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                                    </div>
                                    <div>
                                        <label style="display:block; margin-bottom:5px; font-weight:bold;">{{security.newPassword}}</label>
                                        <input type="password" id="change-new-admin-password" placeholder="{{security.newPassword}}" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                                    </div>
                                </div>
                                <button onclick="changeAdminPassword()" style="margin-top:20px; padding:10px 20px; background:#198754; color:white; border:none; border-radius:5px; font-weight:bold;">🔄 {{security.change}}</button>
                            </div>
                        </div>
                    </div>

                    <div id="settings" class="tab-content">
                        <h2>⚙️ {{settings.title}}</h2>
                        <p>{{settings.description}}</p>

                        <div style="background:var(--bg-card); padding:20px; border-radius:8px; margin:20px 0;">
                            <h3>📍 {{settings.targetsTitle}}</h3>
                            <div style="display:grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap:20px; margin-top:15px;">
                                <div>
                                    <label style="display:block; margin-bottom:5px; font-weight:bold;">{{settings.pingTarget}}</label>
                                    <input type="text" id="config-ping" value="8.8.8.8" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                                </div>
                                <div>
                                    <label style="display:block; margin-bottom:5px; font-weight:bold;">{{settings.dnsTarget}}</label>
                                    <input type="text" id="config-dns" value="google.com" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                                </div>
                                <div>
                                    <label style="display:block; margin-bottom:5px; font-weight:bold;">{{settings.httpTarget}}</label>
                                    <input type="text" id="config-http" value="https://example.com" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                                </div>
                                <div>
                                    <label style="display:block; margin-bottom:5px; font-weight:bold;">⏱️ {{settings.interval}}</label>
                                    <input type="number" id="config-interval" value="30" min="5" max="3600" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                                </div>
                            </div>
                        </div>

                        <div style="background:var(--bg-card); padding:20px; border-radius:8px; margin:20px 0;">
                            <h3>🛰️ {{settings.gatewayTitle}}</h3>
                            <p style="color:var(--color-text-secondary); font-size:0.9em; margin-top:5px;">{{settings.gatewayDescription}}</p>

                            <div id="gw-virtual-warning" style="display:none; background:var(--bg-card-warn); border-left:4px solid #ffc107; padding:10px 14px; border-radius:6px; margin-top:12px; font-size:0.9em;">⚠️ {{settings.gatewayVirtualWarning}}</div>

                            <div style="margin-top:15px; padding:15px; background:var(--bg-body); border-radius:8px;">
                                <div style="margin-bottom:8px;"><strong>{{gateway.near}}:</strong> <span id="gw-near-current" style="font-family:monospace;">--</span></div>
                                <div style="display:flex; align-items:center; gap:10px; flex-wrap:wrap;">
                                    <input type="checkbox" id="gw-near-manual" style="width:18px; height:18px;">
                                    <label for="gw-near-manual" style="font-weight:bold;">{{settings.gatewayManual}}</label>
                                    <input type="text" id="gw-near-ip" placeholder="192.168.0.1" style="flex:1; min-width:140px; padding:6px; border:1px solid #ddd; border-radius:4px;">
                                </div>
                                <div style="display:flex; align-items:center; gap:10px; margin-top:8px;">
                                    <input type="checkbox" id="gw-near-persistent" style="width:18px; height:18px;">
                                    <label for="gw-near-persistent">{{settings.gatewayPersistent}}</label>
                                </div>
                            </div>

                            <div style="margin-top:12px; padding:15px; background:var(--bg-body); border-radius:8px;">
                                <div style="margin-bottom:8px;"><strong>{{gateway.far}}:</strong> <span id="gw-far-current" style="font-family:monospace;">--</span></div>
                                <div style="display:flex; align-items:center; gap:10px; flex-wrap:wrap;">
                                    <input type="checkbox" id="gw-far-manual" style="width:18px; height:18px;">
                                    <label for="gw-far-manual" style="font-weight:bold;">{{settings.gatewayManual}}</label>
                                    <input type="text" id="gw-far-ip" placeholder="10.0.0.1" style="flex:1; min-width:140px; padding:6px; border:1px solid #ddd; border-radius:4px;">
                                </div>
                                <div style="display:flex; align-items:center; gap:10px; margin-top:8px;">
                                    <input type="checkbox" id="gw-far-persistent" style="width:18px; height:18px;">
                                    <label for="gw-far-persistent">{{settings.gatewayPersistent}}</label>
                                </div>
                                <div style="display:flex; align-items:center; gap:10px; margin-top:8px;">
                                    <input type="checkbox" id="gw-far-noping" style="width:18px; height:18px;">
                                    <label for="gw-far-noping">{{settings.gatewayFarNoPing}}</label>
                                </div>
                            </div>
                        </div>

                        <div style="background:var(--bg-card-warn); padding:20px; border-radius:8px; margin:20px 0; border-left:4px solid #ffc107;">
                            <h3>⏸️ {{settings.maintenanceTitle}}</h3>
                            <p>{{settings.maintenanceDescription}}</p>

                            <div style="display:flex; align-items:center; gap:15px; margin-top:15px;">
                                <input type="checkbox" id="maintenance-enabled" style="width:18px; height:18px;">
                                <label for="maintenance-enabled" style="font-weight:bold;">{{settings.maintenanceEnable}}</label>
                            </div>

                            <div id="maintenance-fields" style="display:none; margin-top:15px; padding:15px; background:var(--bg-card-warn-inner); border-radius:8px;">
                                <div style="display:grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap:15px; align-items:end;">
                                    <div>
                                        <label style="display:block; margin-bottom:5px; font-weight:bold;">{{settings.fromHour}}</label>
                                        <select id="maintenance-start-hour" style="width:100%; padding:6px; border:1px solid #ddd; border-radius:4px;"></select>
                                    </div>
                                    <div>
                                        <label style="display:block; margin-bottom:5px; font-weight:bold;">{{settings.fromMinute}}</label>
                                        <select id="maintenance-start-minute" style="width:100%; padding:6px; border:1px solid #ddd; border-radius:4px;">
                                            <option value="0">00</option><option value="5">05</option><option value="10">10</option>
                                            <option value="15">15</option><option value="20">20</option><option value="25">25</option>
                                            <option value="30">30</option><option value="35">35</option><option value="40">40</option>
                                            <option value="45">45</option><option value="50">50</option><option value="55">55</option>
                                        </select>
                                    </div>
                                    <div>
                                        <label style="display:block; margin-bottom:5px; font-weight:bold;">{{settings.toHour}}</label>
                                        <select id="maintenance-end-hour" style="width:100%; padding:6px; border:1px solid #ddd; border-radius:4px;"></select>
                                    </div>
                                    <div>
                                        <label style="display:block; margin-bottom:5px; font-weight:bold;">{{settings.toMinute}}</label>
                                        <select id="maintenance-end-minute" style="width:100%; padding:6px; border:1px solid #ddd; border-radius:4px;">
                                            <option value="0">00</option><option value="5">05</option><option value="10">10</option>
                                            <option value="15">15</option><option value="20">20</option><option value="25">25</option>
                                            <option value="30">30</option><option value="35">35</option><option value="40">40</option>
                                            <option value="45">45</option><option value="50">50</option><option value="55">55</option>
                                        </select>
                                    </div>
                                </div>
                                <div style="margin-top:10px; font-size:0.9em; color:#856404;">
                                    💡 {{settings.maintenanceMidnight}}
                                </div>
                            </div>
                        </div>

                        <div style="background:var(--bg-info-box); padding:20px; border-radius:8px; margin:20px 0; border-left:4px solid var(--color-primary);">
                            <h3>🔔 {{push.title}}</h3>
                            <p>{{push.description}}</p>

                            <div style="display:flex; align-items:center; gap:15px; margin:15px 0;">
                                <input type="checkbox" id="push-enabled" style="width:18px; height:18px;">
                                <label for="push-enabled" style="font-weight:bold;">{{push.enable}}</label>
                            </div>

                            <div id="push-settings" style="display:none; margin-top:20px; padding:15px; background:var(--bg-body); border-radius:8px;">
                                <div style="display:grid; grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); gap:20px;">
                                    <div>
                                        <label style="display:block; margin-bottom:5px; font-weight:bold;">{{push.latencyThreshold}}</label>
                                        <input type="number" id="push-latency-threshold" value="100" min="50" max="1000"\s
                                               style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                                        <small style="color:#6c757d;">{{push.latencyThresholdHint}}</small>
                                    </div>
                                    <div>
                                        <label style="display:block; margin-bottom:5px; font-weight:bold;">{{push.consecutiveBad}}</label>
                                        <input type="number" id="push-consecutive-bad" value="2" min="1" max="10"\s
                                               style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                                        <small style="color:#6c757d;">{{push.consecutiveBadHint}}</small>
                                    </div>
                                </div>

                                <div style="margin-top:15px; padding:10px; background:var(--bg-info-box); border-radius:5px; font-size:0.9em;">
                                    <strong>🔔 {{push.types}}</strong>
                                    <ul style="margin:8px 0 0 20px; padding-left:10px;">
                                        <li>❌ {{push.typeOutage}}</li>
                                        <li>⚠️ {{push.typeBad}}</li>
                                    </ul>
                                </div>
                            </div>
                        </div>

                        <div style="background:var(--bg-info-box); padding:20px; border-radius:8px; margin:20px 0; border-left:4px solid var(--color-primary);">
                            <h3>👤 {{customer.title}}</h3>
                            <p>{{customer.description}}</p>

                            <div style="display:grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap:20px; margin-top:15px;">
                                <div>
                                    <label style="display:block; margin-bottom:5px; font-weight:bold;">{{customer.provider}}</label>
                                    <input type="text" id="config-provider" value="" placeholder="{{customer.providerPlaceholder}}" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                                </div>
                                <div>
                                    <label style="display:block; margin-bottom:5px; font-weight:bold;">{{customer.customerId}}</label>
                                    <input type="text" id="config-customer-id" value="" placeholder="{{customer.customerIdPlaceholder}}" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                                </div>
                                <div>
                                    <label style="display:block; margin-bottom:5px; font-weight:bold;">{{customer.name}}</label>
                                    <input type="text" id="config-user-name" value="" placeholder="{{customer.namePlaceholder}}" style="width:100%; padding:8px; border:1px solid #ddd; border-radius:4px;">
                                </div>
                            </div>
                        </div>

                        <div style="background:var(--bg-card); padding:20px; border-radius:8px; margin:20px 0;">
                            <h3>🌐 {{settings.language}}</h3>
                            <p>{{settings.languageHint}}</p>
                            <select id="config-language" onchange="changeLanguage(this.value)" style="padding:8px; border-radius:4px; min-width:220px;">
                                __LANG_OPTIONS__
                            </select>
                        </div>

                        <button onclick="saveConfig()" style="margin-top:20px; padding:12px 24px; background:#28a745; color:white; border:none; border-radius:5px; font-weight:bold; cursor:pointer;">
                            💾 {{settings.save}}
                        </button>
                        <div id="config-status" style="margin-top:10px; padding:10px; border-radius:4px; display:none;"></div>
                    </div>

                    <div class="footer">
                        <p>SignalReport • {{footer.autoRefresh}}</p>
                    </div>

                    """;
        html += """
                    <script>
                        // Uebersetzungen, Locale und Gateway-Labels (vom Server eingebettet)
                        const I18N = __I18N_JSON__;
                        const LOCALE = '__LOCALE__';
                        const GW_LABELS = __GW_LABELS_JSON__;
                    </script>
                    <script src="/app.js"></script>
                </body>
                </html>
                """;

        html = html.replace("__I18N_JSON__", I18n.activeAsJson())
                .replace("__LANG_OPTIONS__", I18n.languageOptionsHtml())
                .replace("__LOCALE__", I18n.current())
                .replace("__GW_LABELS_JSON__", gatewayLabelsJson());
        return I18n.resolve(html);
    }

    /**
     * Liefert die Anzeige-Labels der Gateway-Messtypen als JSON-Objekt fuer das
     * Frontend. Effektives Label = Config-Override, sonst i18n-Standard.
     */
    private String gatewayLabelsJson()
    {
        GatewayConfig gw = Config.getInstance().getGateway();
        String near = !gw.getNearLabel().isBlank() ? gw.getNearLabel() : I18n.get("gateway.near");
        String far = !gw.getFarLabel().isBlank() ? gw.getFarLabel() : I18n.get("gateway.far");
        return "{\"" + GatewayDiscovery.TYPE_NEAR + "\":\"" + jsonEscape(near) + "\",\""
                + GatewayDiscovery.TYPE_FAR + "\":\"" + jsonEscape(far) + "\"}";
    }

    private static String jsonEscape(String s)
    {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
