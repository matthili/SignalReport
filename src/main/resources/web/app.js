function typeLabel(type) { return GW_LABELS[type] || type; }

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

// Stoerungs-Lokalisierung laden (wessen Schuld?)
function loadConnectivity() {
    fetch('/api/connectivity?hours=24')
        .then(response => response.json())
        .then(data => {
            const card = document.getElementById('connectivity-card');
            const verdictDiv = document.getElementById('connectivity-verdict');
            const segDiv = document.getElementById('connectivity-segments');

            const colors = {
                ALL_GOOD: '#198754', LOCAL_NETWORK: '#dc3545',
                LOCAL_EDGE: '#fd7e14', ISP_OR_BEYOND: '#fd7e14', NO_DATA: '#6c757d'
            };
            const icons = {
                ALL_GOOD: '✅', LOCAL_NETWORK: '🏠',
                LOCAL_EDGE: '📡', ISP_OR_BEYOND: '🌐', NO_DATA: 'ℹ️'
            };
            const color = colors[data.verdict] || '#6c757d';
            card.style.borderLeftColor = color;
            verdictDiv.textContent = (icons[data.verdict] || '') + ' ' + data.verdictText;
            verdictDiv.style.color = color;

            segDiv.innerHTML = '';
            data.segments.forEach((seg, i) => {
                if (i > 0) {
                    const arrow = document.createElement('div');
                    arrow.textContent = '→';
                    arrow.style.cssText = 'align-self:center; font-size:1.4em; color:var(--color-text-secondary);';
                    segDiv.appendChild(arrow);
                }
                const status = !seg.hasData
                    ? I18N['connectivity.noData']
                    : (seg.healthy ? I18N['connectivity.healthy'] : I18N['connectivity.degraded']);
                const segColor = !seg.hasData ? '#6c757d' : (seg.healthy ? '#198754' : '#dc3545');
                const metric = seg.hasData
                    ? seg.avgLatency.toFixed(1) + ' ms · ' + seg.packetLoss.toFixed(0) + ' %'
                    : '';
                const box = document.createElement('div');
                box.style.cssText = 'flex:1; min-width:150px; background:var(--bg-body); padding:10px; border-radius:6px; text-align:center;';
                box.innerHTML =
                    '<div style="font-weight:bold;">' + seg.label + '</div>' +
                    '<div style="font-family:monospace; font-size:0.8em; color:var(--color-text-secondary);">' + seg.ip + '</div>' +
                    '<div style="font-size:1.1em; color:' + segColor + '; font-weight:bold; margin-top:4px;">' + status + '</div>' +
                    '<div style="font-size:0.85em; color:var(--color-text-secondary);">' + metric + '</div>';
                segDiv.appendChild(box);
            });
            card.style.display = 'block';
        })
        .catch(error => console.error('Connectivity-Fehler:', error));
}

// Dauer menschenlesbar formatieren
function fmtDuration(sec) {
    sec = Math.round(sec);
    if (sec <= 0) return '0 s';
    if (sec < 60) return sec + ' s';
    if (sec < 3600) return Math.floor(sec / 60) + ' min ' + (sec % 60) + ' s';
    return Math.floor(sec / 3600) + ' h ' + Math.floor((sec % 3600) / 60) + ' min';
}

// Zuverlaessigkeit laden (Verfuegbarkeit, MTBF, MTTR)
function loadReliability() {
    fetch('/api/reliability?hours=24')
        .then(response => response.json())
        .then(d => {
            const card = document.getElementById('reliability-card');
            const grid = document.getElementById('reliability-grid');
            if (!d.hasData) { card.style.display = 'none'; return; }

            const upColor = d.uptimePercent >= 99 ? '#198754'
                : (d.uptimePercent >= 95 ? '#fd7e14' : '#dc3545');
            // Ausfall-Liste fuer die klickbare Detailansicht merken
            window._outages = d.outages || [];
            const hasOutageDetails = window._outages.length > 0;
            const cells = [
                { key: 'uptime', label: I18N['reliability.uptime'], value: d.uptimePercent.toFixed(2) + ' %', color: upColor },
                { key: 'coverage', label: I18N['reliability.coverage'], value: d.coveragePercent.toFixed(0) + ' %', color: 'var(--color-text-secondary)' },
                { key: 'outages', label: I18N['reliability.outages'], value: d.outageCount, color: d.outageCount > 0 ? '#dc3545' : '#198754' },
                { key: 'longest', label: I18N['reliability.longestOutage'], value: fmtDuration(d.longestOutageSeconds), color: 'var(--color-text)' },
                { key: 'mtbf', label: I18N['reliability.mtbf'], value: fmtDuration(d.mtbfSeconds), color: 'var(--color-text)' },
                { key: 'mttr', label: I18N['reliability.mttr'], value: fmtDuration(d.mttrSeconds), color: 'var(--color-text)' }
            ];
            grid.innerHTML = '';
            cells.forEach(c => {
                const box = document.createElement('div');
                const clickable = c.key === 'outages' && hasOutageDetails;
                box.style.cssText = 'background:var(--bg-body); padding:10px; border-radius:6px; text-align:center;'
                    + (clickable ? ' cursor:pointer; outline:1px solid var(--color-primary);' : '');
                const hint = clickable ? ' 🔎' : '';
                box.innerHTML =
                    '<div style="font-size:0.85em; color:var(--color-text-secondary);">' + c.label + hint + '</div>' +
                    '<div style="font-size:1.4em; font-weight:bold; color:' + c.color + '; margin-top:4px;">' + c.value + '</div>';
                if (clickable) box.onclick = toggleOutageDetails;
                grid.appendChild(box);
            });
            // Falls die Detailansicht offen war, neu zeichnen
            if (document.getElementById('outage-details').style.display === 'block') {
                renderOutageDetails();
            }
            card.style.display = 'block';
        })
        .catch(error => console.error('Reliability-Fehler:', error));
}

function toggleOutageDetails() {
    const panel = document.getElementById('outage-details');
    if (panel.style.display === 'block') {
        panel.style.display = 'none';
    } else {
        renderOutageDetails();
        panel.style.display = 'block';
    }
}

function renderOutageDetails() {
    const panel = document.getElementById('outage-details');
    const list = window._outages || [];
    if (list.length === 0) { panel.innerHTML = '<em>' + I18N['outages.none'] + '</em>'; return; }
    let html = '<table style="margin-top:0;"><thead><tr>'
        + '<th>' + I18N['outages.start'] + '</th>'
        + '<th>' + I18N['outages.end'] + '</th>'
        + '<th>' + I18N['outages.duration'] + '</th>'
        + '<th></th></tr></thead><tbody>';
    list.forEach(o => {
        const start = new Date(o.fromEpoch * 1000).toLocaleString(LOCALE);
        const end = new Date(o.toEpoch * 1000).toLocaleString(LOCALE);
        const rowStyle = o.excluded ? ' style="opacity:0.5; text-decoration:line-through;"' : '';
        const btnLabel = o.excluded ? I18N['outages.include'] : I18N['outages.exclude'];
        const badge = o.excluded ? ' <span style="color:var(--color-text-secondary);">(' + I18N['outages.excludedBadge'] + ')</span>' : '';
        html += '<tr' + rowStyle + '><td>' + start + '</td><td>' + end + '</td>'
            + '<td>' + fmtDuration(o.durationSeconds) + badge + '</td>'
            + '<td><button onclick="excludeOutage(' + o.fromEpoch + ',' + o.toEpoch + ',' + (!o.excluded) + ')" '
            + 'style="padding:4px 10px; border:1px solid var(--color-border); border-radius:4px; cursor:pointer; background:var(--bg-card); color:var(--color-text);">'
            + btnLabel + '</button></td></tr>';
    });
    html += '</tbody></table>';
    panel.innerHTML = html;
}

function excludeOutage(fromEpoch, toEpoch, excluded) {
    fetch('/api/outages/exclude', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ fromEpoch: fromEpoch, toEpoch: toEpoch, excluded: excluded })
    })
    .then(() => loadReliability())
    .catch(error => console.error('Ausschluss-Fehler:', error));
}

// Messzyklen: feste Anzeige-Reihenfolge der Messtypen in der Statuszeile
const CYCLE_TYPE_ORDER = ['GATEWAY_FAR', 'GATEWAY_NEAR', 'HTTP', 'DNS', 'PING'];
const MAINTENANCE_TYPE = 'MAINTENANCE';
const CYCLE_GAP_SECONDS = 300; // Sicherheitsnetz: groesserer Abstand = neuer Zyklus
const MAX_CYCLES = 20;
// Gemerkter Aufklapp-Zustand, damit aufgeklappte Zeilen den 5s-Refresh ueberleben.
// Schluessel = gerundeter Zyklus-Zeitstempel (stabil, im Gegensatz zur Position).
const _expandedCycles = new Set();

// Flache, nach Zeit absteigend sortierte Messliste in Messzyklen gruppieren.
// Ein Zyklus endet, sobald sich ein Messtyp wiederholt (jeder Zyklus misst jeden
// Typ genau einmal), bei einem Wartungsmarker oder bei sehr grossem Zeitabstand.
function groupIntoCycles(measurements) {
    const cycles = [];
    let current = null;
    let seen = null;
    for (const m of measurements) {
        const startNew = !current
            || seen.has(m.type)
            || m.type === MAINTENANCE_TYPE
            || current.isMaint
            || (current.tsMax - m.timestamp) > CYCLE_GAP_SECONDS;
        if (startNew) {
            current = { byType: {}, tsMax: m.timestamp, isMaint: m.type === MAINTENANCE_TYPE };
            seen = new Set();
            cycles.push(current);
        }
        current.byType[m.type] = m;
        seen.add(m.type);
        if (m.timestamp > current.tsMax) current.tsMax = m.timestamp;
    }
    return cycles;
}

// Messtypen eines Zyklus in fester Anzeige-Reihenfolge (Unbekannte ans Ende).
function orderedTypes(byType) {
    const known = CYCLE_TYPE_ORDER.filter(t => byType[t]);
    const extra = Object.keys(byType).filter(t => CYCLE_TYPE_ORDER.indexOf(t) === -1);
    return known.concat(extra);
}

// Eine Zeile pro Messzyklus mit kompaktem Status; Klick klappt die Einzelwerte auf.
function renderCycles(cycles) {
    const tableBody = document.getElementById('measurementsTable');
    tableBody.innerHTML = '';
    if (!cycles.length) {
        tableBody.innerHTML = '<tr><td colspan="3" style="text-align:center">' + I18N['common.loadingData'] + '</td></tr>';
        return;
    }
    cycles.slice(0, MAX_CYCLES).forEach((cycle) => {
        const types = orderedTypes(cycle.byType);
        const anyFail = types.some(t => t !== MAINTENANCE_TYPE && !cycle.byType[t].success);
        const time = new Date(cycle.tsMax * 1000).toLocaleTimeString(LOCALE);
        const key = Math.round(cycle.tsMax); // stabile Kennung pro Zyklus (ueberlebt den Refresh)
        const expanded = _expandedCycles.has(key);

        const summary = document.createElement('tr');
        summary.className = 'cycle-row' + (anyFail ? ' cycle-fail' : '');
        summary.onclick = () => toggleCycle(key);

        let statusHtml;
        if (cycle.isMaint) {
            statusHtml = '<span class="chip chip-maint">🔧 ' + (I18N['table.maintenance'] || 'Wartung') + '</span>';
        } else {
            statusHtml = types.map(t => {
                const m = cycle.byType[t];
                const cls = m.success ? 'chip chip-ok' : 'chip chip-fail';
                return '<span class="' + cls + '">' + typeLabel(t) + ' ' + (m.success ? '✅' : '❌') + '</span>';
            }).join('');
        }

        const caret = cycle.isMaint ? '' : '<span class="cycle-caret" id="caret-' + key + '">' + (expanded ? '▾' : '▸') + '</span>';
        summary.innerHTML =
            '<td style="white-space:nowrap">' + time + '</td>' +
            '<td>' + statusHtml + '</td>' +
            '<td style="text-align:center">' + caret + '</td>';
        tableBody.appendChild(summary);

        if (cycle.isMaint) return;

        const detail = document.createElement('tr');
        detail.className = 'cycle-detail';
        detail.id = 'detail-' + key;
        detail.style.display = expanded ? 'table-row' : 'none';
        let inner = '<td colspan="3"><table><thead><tr>'
            + '<th>' + I18N['table.type'] + '</th>'
            + '<th>' + I18N['table.target'] + '</th>'
            + '<th>' + I18N['table.latency'] + '</th>'
            + '<th>' + I18N['table.status'] + '</th>'
            + '<th>' + I18N['table.host'] + '</th>'
            + '<th>' + I18N['table.lanIp'] + '</th>'
            + '</tr></thead><tbody>';
        types.forEach(t => {
            const m = cycle.byType[t];
            const latencyClass = m.success
                ? (m.latencyMs < 50 ? 'excellent' : m.latencyMs < 100 ? 'good' : 'poor')
                : 'failure';
            inner += '<tr>'
                + '<td><strong>' + typeLabel(m.type) + '</strong></td>'
                + '<td>' + m.target + '</td>'
                + '<td class="' + latencyClass + '"><strong>' + m.latencyMs.toFixed(2) + '</strong> ms</td>'
                + '<td>' + (m.success ? '✅' : '❌') + '</td>'
                + '<td style="font-family:monospace;font-size:0.85em;">' + m.hostHash.substring(0, 8) + '</td>'
                + '<td style="font-family:monospace;font-size:0.85em;">' + m.localIPv4 + '</td>'
                + '</tr>';
        });
        inner += '</tbody></table></td>';
        detail.innerHTML = inner;
        tableBody.appendChild(detail);
    });

    // Aufklapp-Zustand nur fuer aktuell sichtbare Zyklen behalten, sonst waechst die Menge.
    const visibleKeys = new Set(cycles.slice(0, MAX_CYCLES).map(c => Math.round(c.tsMax)));
    [..._expandedCycles].forEach(k => { if (!visibleKeys.has(k)) _expandedCycles.delete(k); });
}

// Detailzeile eines Zyklus ein-/ausklappen und den Zustand merken.
function toggleCycle(key) {
    const detail = document.getElementById('detail-' + key);
    const caret = document.getElementById('caret-' + key);
    if (!detail) return;
    const willOpen = !_expandedCycles.has(key);
    if (willOpen) { _expandedCycles.add(key); } else { _expandedCycles.delete(key); }
    detail.style.display = willOpen ? 'table-row' : 'none';
    if (caret) caret.textContent = willOpen ? '▾' : '▸';
}

// Haupt-Chart laden
function loadMeasurements() {
    fetch('/api/measurements?limit=150')
        .then(response => response.json())
        .then(data => {
            renderCycles(groupIntoCycles(data));

            const pingData = data.filter(m => m.type === 'PING').slice(0, 10).reverse();
            const labels = pingData.map(m => new Date(m.timestamp * 1000).toLocaleTimeString(LOCALE, {hour: '2-digit', minute:'2-digit', second:'2-digit'}));
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
                        label: I18N['chart.pingLatency'],
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
                            text: I18N['chart.pingOverTime']
                        }
                    },
                    scales: {
                        y: {
                            beginAtZero: true,
                            title: { display: true, text: I18N['chart.latencyMs'] }
                        },
                        x: {
                            title: { display: true, text: I18N['chart.timeOfDay'] }
                        }
                    }
                }
            });
        })
        .catch(error => {
            console.error('Fehler beim Laden:', error);
            document.getElementById('measurementsTable').innerHTML = 
                '<tr><td colspan="3" style="text-align:center;color:red">' + I18N['common.loadError'] + '</td></tr>';
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
                        label: I18N['chart.hourlyAvg'],
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
                                    if (value === 0) return I18N['chart.noMeasurements'];
                                    return `${value.toFixed(2)} ms`;
                                }
                            }
                        }
                    },
                    scales: {
                        y: {
                            beginAtZero: true,
                            title: { display: true, text: I18N['chart.latencyMs'] }
                        },
                        x: {
                            title: { display: true, text: I18N['chart.timeOfDay'] }
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
                    <td>${new Date(host.firstSeen * 1000).toLocaleDateString(LOCALE)}</td>
                    <td>${new Date(host.lastSeen * 1000).toLocaleDateString(LOCALE)}</td>
                `;
                tbody.appendChild(row);
            });
        })
        .catch(error => console.error('Host-Fehler:', error));
}

// DNS-Benchmark ausführen
    function runDnsBenchmark() {
      const loadingElement = document.getElementById('dnsLoading');

      // STATE-DEBUG: Immer ausgeben, um Problem zu diagnostizieren
      console.log('[DNS] State vor Start: isRunning=' + isDnsBenchmarkRunning + ', timer=' + (dnsBenchmarkCooldownTimer ? 'aktiv' : 'null'));

      // Cooldown-Check
      if (dnsBenchmarkCooldownTimer) {
          const remaining = Math.ceil((dnsBenchmarkCooldownEndTime - Date.now()) / 1000);
          alert('⚠️ ' + I18N['dns.cooldownWait'].replace('{seconds}', remaining));
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
          loadingElement.textContent = I18N['dns.stateReset'];
          return;
      }

      // State setzen
      isDnsBenchmarkRunning = true;
      const hostname = document.getElementById('dnsHostname').value;
      loadingElement.textContent = I18N['dns.running'];

      // TIMEOUT FÜR HÄNGENDE REQUESTS (10 Sekunden)
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 10000);

      fetch(`/api/dns/benchmark?hostname=${hostname}`, { 
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
              const statusIcon = result.success ? '✅' : '❌';

              card.innerHTML = `
                  <h4>${result.serverName}</h4>
                  <div class="latency ${statusClass}">${result.latencyMs.toFixed(2)} ms ${statusIcon}</div>
                  <div class="region">${result.region} • ${result.provider}</div>
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
              loadingElement.textContent = '⚠️ ' + I18N['dns.timeout'];
          } else {
              loadingElement.textContent = '❌ ' + I18N['common.error'] + ': ' + (error.message || I18N['dns.unknownError']);
          }
      })
      .finally(() => {
          // GARANTIERTER STATE-RESET (wird IMMER ausgeführt)
          console.log('[DNS] finally-Block: State wird zurückgesetzt');
          isDnsBenchmarkRunning = false;

          // Cooldown starten
          loadingElement.textContent = I18N['dns.cooldownNext'];
          dnsBenchmarkCooldownEndTime = Date.now() + 30000;
          dnsBenchmarkCooldownTimer = setTimeout(() => {
              loadingElement.textContent = I18N['dns.clickToRun'];
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
                tbody.innerHTML = '<tr><td colspan="4" style="text-align:center">' + I18N['ip.noChanges'] + '</td></tr>';
                return;
            }

            stats.forEach(stat => {
                const row = document.createElement('tr');
                row.innerHTML = `
                    <td style="font-family:monospace;font-size:0.9em;">${stat.hostHash.substring(0,8)}</td>
                    <td><strong>${stat.changeCount}</strong></td>
                    <td>${new Date(stat.firstChange * 1000).toLocaleDateString(LOCALE)}</td>
                    <td>${new Date(stat.lastChange * 1000).toLocaleDateString(LOCALE)}</td>
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
                tbody.innerHTML = '<tr><td colspan="5" style="text-align:center">' + I18N['ip.noChanges'] + '</td></tr>';
                return;
            }

            changes.forEach(change => {
                const row = document.createElement('tr');
                const changeType = change.changeType === 'INITIAL' ? '🟢 ' + I18N['ip.initial'] : '🔄 ' + I18N['ip.change'];
                const changeColor = change.changeType === 'INITIAL' ? '#198754' : '#0d6efd';

                row.innerHTML = `
                    <td>${new Date(change.timestamp * 1000).toLocaleString(LOCALE)}</td>
                    <td style="font-family:monospace;font-size:0.8em;">${change.hostHash.substring(0,8)}</td>
                    <td style="font-family:monospace;">${change.oldIp || '–'}</td>
                    <td style="font-family:monospace;font-weight:bold;color:${changeColor};">${change.newIp}</td>
                    <td style="color:${changeColor};">${changeType}</td>
                `;
                tbody.appendChild(row);
            });
        })
        .catch(error => {
            console.error('IP-Änderungen-Fehler:', error);
            document.getElementById('ip-changes-body').innerHTML = 
                '<tr><td colspan="5" style="text-align:center;color:red">' + I18N['ip.loadError'] + '</td></tr>';
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

            const gw = config.gateway || {};
            document.getElementById('gw-near-current').textContent = gw.near || '--';
            document.getElementById('gw-far-current').textContent = gw.far || '--';
            document.getElementById('gw-near-manual').checked = !!gw.nearManual;
            document.getElementById('gw-far-manual').checked = !!gw.farManual;
            document.getElementById('gw-near-persistent').checked = !!gw.nearPersistent;
            document.getElementById('gw-far-persistent').checked = !!gw.farPersistent;
            document.getElementById('gw-far-noping').checked = gw.farPingEnabled === false;
            document.getElementById('gw-near-ip').value = gw.nearManual ? (gw.near || '') : '';
            document.getElementById('gw-far-ip').value = gw.farManual ? (gw.far || '') : '';
            document.getElementById('gw-virtual-warning').style.display = gw.virtualSuspected ? 'block' : 'none';
            syncGatewayControls();

            if (config.language) {
                document.getElementById('config-language').value = config.language;
            }
        })
        .catch(error => console.error('Config-Lade-Fehler:', error));
}

// Sprache wechseln: sofort speichern und Seite neu laden
function changeLanguage(language) {
    fetch('/api/config/language', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ language: language })
    })
    .then(() => location.reload())
    .catch(error => console.error('Sprach-Wechsel-Fehler:', error));
}

// Maintenance-Checkbox Toggle
document.getElementById('maintenance-enabled').addEventListener('change', function() {
    document.getElementById('maintenance-fields').style.display = this.checked ? 'block' : 'none';
});

// Gateway-Steuerelemente je nach Manuell-Schalter aktivieren/deaktivieren
function syncGatewayControls() {
    const nm = document.getElementById('gw-near-manual').checked;
    document.getElementById('gw-near-ip').disabled = !nm;
    document.getElementById('gw-near-persistent').disabled = !nm;
    const fm = document.getElementById('gw-far-manual').checked;
    document.getElementById('gw-far-ip').disabled = !fm;
    document.getElementById('gw-far-persistent').disabled = !fm;
}
document.getElementById('gw-near-manual').addEventListener('change', syncGatewayControls);
document.getElementById('gw-far-manual').addEventListener('change', syncGatewayControls);

// Push-Checkbox Toggle
document.getElementById('push-enabled').addEventListener('change', function() {
    document.getElementById('push-settings').style.display = this.checked ? 'block' : 'none';

    // Browser-Berechtigung anfragen, wenn aktiviert
    if (this.checked && 'Notification' in window && Notification.permission !== 'granted') {
        Notification.requestPermission().then(permission => {
            if (permission !== 'granted') {
                alert('⚠️ ' + I18N['push.notAllowed']);
            }
        });
    }
});

// Konfiguration speichern (inkl. Push)
function saveConfig() {
    const statusDiv = document.getElementById('config-status');
    statusDiv.style.display = 'block';
    statusDiv.style.background = '#fff3cd';
    statusDiv.textContent = '💾 ' + I18N['settings.saving'];

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
        },
        gateway: {
            nearManual: document.getElementById('gw-near-manual').checked,
            near: document.getElementById('gw-near-ip').value.trim(),
            nearPersistent: document.getElementById('gw-near-persistent').checked,
            farManual: document.getElementById('gw-far-manual').checked,
            far: document.getElementById('gw-far-ip').value.trim(),
            farPersistent: document.getElementById('gw-far-persistent').checked,
            farPingEnabled: !document.getElementById('gw-far-noping').checked
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
        statusDiv.textContent = '✅ ' + I18N['settings.saved'];
        setTimeout(() => { statusDiv.style.display = 'none'; }, 3000);
    })
    .catch(error => {
        statusDiv.style.background = '#f8d7da';
        statusDiv.textContent = '❌ ' + I18N['common.error'] + ': ' + error.message;
    });
}

// SHA-256 Hash-Funktion (Web Crypto API)
async function sha256(message) {
    const encoder = new TextEncoder();
    const data = encoder.encode(message);
    const hashBuffer = await crypto.subtle.digest('SHA-256', data);
    const hashArray = Array.from(new Uint8Array(hashBuffer));
    return hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
}

// Auth-Fetch Wrapper: bei 401 zur Login-Seite weiterleiten
async function authFetch(url, options) {
    const response = await fetch(url, options);
    if (response.status === 401) {
        window.location.href = '/login';
        throw new Error('Session abgelaufen');
    }
    return response;
}

// Logout
async function logout() {
    try {
        await fetch('/api/auth/logout', { method: 'POST' });
    } catch (e) { /* ignorieren */ }
    document.cookie = 'SR_SESSION=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT';
    window.location.href = '/login';
}

// Logout-Button anzeigen, wenn Auth aktiv
function checkLogoutButton() {
    fetch('/api/auth/status')
        .then(r => r.json())
        .then(s => {
            document.getElementById('logout-btn').style.display = s.enabled ? 'block' : 'none';
        })
        .catch(() => {});
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
                div.innerHTML = '<strong>✅ ' + I18N['security.statusActive'] + '</strong><br>' + I18N['security.statusUsers'] + '<br><small>' + I18N['security.statusSecured'] + '</small>';
                enable.style.display = 'none'; disable.style.display = 'block';
            } else {
                div.innerHTML = '<strong>⚠️ ' + I18N['security.statusInactive'] + '</strong><br>' + I18N['security.statusPublic'];
                enable.style.display = 'block'; disable.style.display = 'none';
            }
            checkLogoutButton();
        });
}
async function enableAuth() {
    const a = document.getElementById('auth-admin-password').value;
    const u = document.getElementById('auth-user-password').value;
    if (!a) { alert(I18N['security.enterAdminPassword']); return; }
    if (!u || u.length < 6) { alert(I18N['security.enterUserPassword']); return; }
    try {
        const nonceResp = await fetch('/api/auth/nonce');
        const nonceData = await nonceResp.json();
        const nonce = nonceData.nonce;
        const adminHash = await sha256(a);
        const challengeResponse = await sha256(adminHash + nonce);
        const userPasswordHash = await sha256(u);
        const resp = await fetch('/api/auth/enable', {method:'POST', headers:{'Content-Type':'application/json'},
            body:JSON.stringify({nonce:nonce, challengeResponse:challengeResponse, userPasswordHash:userPasswordHash})});
        const msg = await resp.text();
        alert(msg);
        loadAuthStatus();
    } catch (e) { alert(I18N['common.error'] + ': ' + e.message); }
}
async function disableAuth() {
    const p = document.getElementById('auth-disable-admin-password').value;
    if (!p) { alert(I18N['security.enterAdminPassword']); return; }
    if (!confirm('⚠️ ' + I18N['security.confirmDisable'])) return;
    try {
        const nonceResp = await fetch('/api/auth/nonce');
        const nonceData = await nonceResp.json();
        const nonce = nonceData.nonce;
        const adminHash = await sha256(p);
        const challengeResponse = await sha256(adminHash + nonce);
        const resp = await fetch('/api/auth/disable', {method:'POST', headers:{'Content-Type':'application/json'},
            body:JSON.stringify({nonce:nonce, challengeResponse:challengeResponse})});
        const msg = await resp.text();
        alert(msg);
        loadAuthStatus();
    } catch (e) { alert(I18N['common.error'] + ': ' + e.message); }
}
async function changeAdminPassword() {
    const old = document.getElementById('change-old-admin-password').value;
    const neu = document.getElementById('change-new-admin-password').value;
    if (!old || !neu || neu.length < 6) { alert(I18N['security.checkPasswords']); return; }

    try {
        // Nonce holen
        const nonceResp = await fetch('/api/auth/nonce');
        const nonceData = await nonceResp.json();
        const nonce = nonceData.nonce;

        // Challenge-Response fuer altes Passwort: SHA-256(SHA-256(oldPw) + nonce)
        const oldHash = await sha256(old);
        const challengeResponse = await sha256(oldHash + nonce);

        // Neues Passwort hashen
        const newPasswordHash = await sha256(neu);

        const resp = await authFetch('/api/auth/change-admin', {
            method: 'POST',
            headers: {'Content-Type':'application/json'},
            body: JSON.stringify({nonce: nonce, challengeResponse: challengeResponse, newPasswordHash: newPasswordHash})
        });
        const msg = await resp.text();
        alert(msg);
        document.getElementById('change-old-admin-password').value = '';
        document.getElementById('change-new-admin-password').value = '';
    } catch (e) {
        alert(I18N['common.error'] + ': ' + e.message);
    }
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
    if (confirm('⚠️ ' + I18N['buttons.csvConfirmAll'])) {
        window.location.href = '/api/export/csv?all=true';
    }
}

// Theme-Toggle
const isDarkInitial = document.body.classList.contains('dark-mode');
document.getElementById('theme-toggle').textContent = isDarkInitial ? '☀️' : '🌙';
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
    document.getElementById('theme-toggle').textContent = isDark ? '☀️' : '🌙';
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
loadConnectivity();
loadReliability();
checkLogoutButton();
// DNS-Benchmark State (MUSS HIER STEHEN, NICHT IN DER FUNKTION!)
let isDnsBenchmarkRunning = false;
let dnsBenchmarkCooldownTimer = null;
let dnsBenchmarkCooldownEndTime = 0;

// Alle 5 Sekunden aktualisieren
setInterval(loadMeasurements, 5000);
setInterval(loadStatistics, 30000);
setInterval(loadConnectivity, 30000);
setInterval(loadReliability, 300000);
setInterval(loadHourlyChart, 300000);
setInterval(loadNetworkInfo, 60000);

// IP-Tracking alle 30 Sekunden aktualisieren
setInterval(() => {
    const activeTab = document.querySelector('.tab.active');
    if (activeTab && activeTab.dataset.tab === 'ip-tracking') {
        loadIpStatistics();
        loadIpChanges();
    }
}, 30000);
