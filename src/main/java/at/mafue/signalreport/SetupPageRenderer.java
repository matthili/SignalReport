package at.mafue.signalreport;

public class SetupPageRenderer
{
    public String render()
    {
        boolean darkMode = Config.getInstance().getTheme().isDarkMode();
        String bodyClass = darkMode ? " class=\"dark-mode\"" : "";
        String faviconSuffix = darkMode ? "dark" : "light";
        return """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <link rel="icon" id="favicon" type="image/png" href="/favicon-32x32-THEME_SUFFIX.png">
                    <link rel="apple-touch-icon" href="/apple-icon-180x180-THEME_SUFFIX.png">
                    <title>SignalReport Setup</title>
                    <style>
                        :root {
                            --bg-body: #f8f9fa;
                            --bg-card: #ffffff;
                            --bg-tab-inactive: #e9ecef;
                            --color-primary: #0d6efd;
                            --color-primary-hover: #0b5ed7;
                            --color-text: #495057;
                            --color-text-secondary: #6c757d;
                            --color-border: #dddddd;
                            --color-shadow: rgba(0,0,0,0.1);
                            --color-input-bg: #ffffff;
                            --bg-info-box: #e7f5ff;
                            --bg-error: #f8d7da;
                        }
                        body.dark-mode {
                            --bg-body: #1a1a2e;
                            --bg-card: #16213e;
                            --bg-tab-inactive: #2c2c44;
                            --color-primary: #4d94ff;
                            --color-primary-hover: #3a7bd5;
                            --color-text: #e0e0e0;
                            --color-text-secondary: #9e9e9e;
                            --color-border: #3a3a5c;
                            --color-shadow: rgba(0,0,0,0.3);
                            --color-input-bg: #1e1e3a;
                            --bg-info-box: #0a2540;
                            --bg-error: #3d1520;
                        }
                        body { font-family: Arial, sans-serif; background: var(--bg-body); color: var(--color-text); margin: 0; padding: 0; }
                        .setup-container { max-width: 600px; margin: 100px auto; background: var(--bg-card); padding: 40px; border-radius: 10px; box-shadow: 0 2px 10px var(--color-shadow); }
                        h1 { color: var(--color-primary); text-align: center; margin-bottom: 30px; }
                        .setup-steps { display: flex; justify-content: space-between; margin-bottom: 30px; }
                        .step { text-align: center; width: 30%; }
                        .step-number { display: inline-block; width: 30px; height: 30px; background: var(--bg-tab-inactive); border-radius: 50%; line-height: 30px; font-weight: bold; }
                        .step.active .step-number { background: var(--color-primary); color: white; }
                        .form-group { margin-bottom: 20px; }
                        label { display: block; margin-bottom: 8px; font-weight: bold; color: var(--color-text); }
                        input[type="password"] { width: 100%; padding: 10px; border: 1px solid var(--color-border); border-radius: 5px; font-size: 16px; background: var(--color-input-bg); color: var(--color-text); }
                        .checkbox-group { display: flex; align-items: center; gap: 10px; }
                        input[type="checkbox"] { width: 18px; height: 18px; }
                        .btn { width: 100%; padding: 12px; background: var(--color-primary); color: white; border: none; border-radius: 5px; font-size: 16px; font-weight: bold; cursor: pointer; margin-top: 20px; }
                        .btn:hover { background: var(--color-primary-hover); }
                        .btn:disabled { background: var(--color-text-secondary); cursor: not-allowed; }
                        .error { color: #dc3545; margin-top: 10px; padding: 10px; background: var(--bg-error); border-radius: 5px; display: none; }
                        .info-box { background: var(--bg-info-box); padding: 15px; border-radius: 5px; margin-bottom: 20px; border-left: 4px solid var(--color-primary); }
                        .setup-logo { max-width: 280px; height: auto; display: block; margin: 0 auto 10px auto; }
                        .setup-logo.dark { display: none; }
                        body.dark-mode .setup-logo.light { display: none; }
                        body.dark-mode .setup-logo.dark { display: block; margin: 0 auto 10px auto; }
                    </style>
                </head>
                <body BODY_CLASS>
                    <div class="setup-container">
                        <img src="/logo_mit_schriftzug_light.png" alt="SignalReport" class="setup-logo light">
                        <img src="/logo_mit_schriftzug_dark_login.png" alt="SignalReport" class="setup-logo dark">
                        <h2 style="text-align:center; color:var(--color-primary); margin-bottom:30px;">Setup</h2>
                
                        <div class="info-box">
                            <strong>Willkommen!</strong>
                            <p>Dies ist die erstmalige Einrichtung von SignalReport. Bitte lege ein Admin-Passwort fest.</p>
                        </div>
                
                        <div class="setup-steps">
                            <div class="step active">
                                <div class="step-number">1</div>
                                <div>Admin-Passwort</div>
                            </div>
                            <div class="step">
                                <div class="step-number">2</div>
                                <div>Authentifizierung</div>
                            </div>
                            <div class="step">
                                <div class="step-number">3</div>
                                <div>Fertig</div>
                            </div>
                        </div>
                
                        <div class="form-group">
                            <label for="adminPassword">Admin-Passwort *</label>
                            <input type="password" id="adminPassword" placeholder="Mindestens 6 Zeichen" minlength="6">
                        </div>
                
                        <div class="form-group">
                            <label for="adminPasswordConfirm">Admin-Passwort bestätigen *</label>
                            <input type="password" id="adminPasswordConfirm" placeholder="Passwort erneut eingeben" minlength="6">
                        </div>
                
                        <div class="checkbox-group">
                            <input type="checkbox" id="enableAuth">
                            <label for="enableAuth" style="display:inline; font-weight:normal;">Authentifizierung aktivieren (empfohlen für öffentliche IP)</label>
                        </div>
                
                        <div id="userPasswordGroup" style="display:none; margin-top:15px;">
                            <div class="form-group">
                                <label for="userPassword">User-Passwort für Web-Zugriff (Benutzername: User) *</label>
                                <input type="password" id="userPassword" placeholder="Mindestens 6 Zeichen" minlength="6">
                            </div>
                            <div class="form-group">
                                <label for="userPasswordConfirm">User-Passwort bestätigen *</label>
                                <input type="password" id="userPasswordConfirm" placeholder="Passwort erneut eingeben" minlength="6">
                            </div>
                        </div>
                
                        <div class="error" id="errorMessage"></div>
                
                        <button class="btn" id="completeSetup">Setup abschließen</button>
                    </div>
                
                    <script>
                        // SHA-256 Hash-Funktion (Web Crypto API)
                        async function sha256(message) {
                            const encoder = new TextEncoder();
                            const data = encoder.encode(message);
                            const hashBuffer = await crypto.subtle.digest('SHA-256', data);
                            const hashArray = Array.from(new Uint8Array(hashBuffer));
                            return hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
                        }

                        // Authentifizierung-Checkbox Toggle
                        document.getElementById('enableAuth').addEventListener('change', function() {
                            document.getElementById('userPasswordGroup').style.display = this.checked ? 'block' : 'none';
                        });

                        // Setup abschließen
                        document.getElementById('completeSetup').addEventListener('click', async function() {
                            const adminPassword = document.getElementById('adminPassword').value;
                            const adminPasswordConfirm = document.getElementById('adminPasswordConfirm').value;
                            const enableAuth = document.getElementById('enableAuth').checked;
                            const userPassword = document.getElementById('userPassword').value;
                            const userPasswordConfirm = document.getElementById('userPasswordConfirm').value;

                            const errorDiv = document.getElementById('errorMessage');
                            errorDiv.style.display = 'none';

                            // Validierung
                            if (adminPassword.length < 6) {
                                showError('Admin-Passwort muss mindestens 6 Zeichen lang sein!');
                                return;
                            }

                            if (adminPassword !== adminPasswordConfirm) {
                                showError('Admin-Passwoerter stimmen nicht ueberein!');
                                return;
                            }

                            if (enableAuth) {
                                if (userPassword.length < 6) {
                                    showError('User-Passwort muss mindestens 6 Zeichen lang sein!');
                                    return;
                                }

                                if (userPassword !== userPasswordConfirm) {
                                    showError('User-Passwoerter stimmen nicht ueberein!');
                                    return;
                                }
                            }

                            // Passwoerter client-seitig hashen (SHA-256)
                            this.disabled = true;
                            this.textContent = 'Setup wird abgeschlossen...';

                            try {
                                const adminPasswordHash = await sha256(adminPassword);
                                const userPasswordHash = enableAuth ? await sha256(userPassword) : '';

                                const response = await fetch('/api/setup/complete', {
                                    method: 'POST',
                                    headers: { 'Content-Type': 'application/json' },
                                    body: JSON.stringify({
                                        adminPasswordHash: adminPasswordHash,
                                        enableAuth: enableAuth,
                                        userPasswordHash: userPasswordHash
                                    })
                                });

                                const message = await response.text();
                                alert('\u2705 ' + message);

                                // Bei aktivierter Auth zur Login-Seite, sonst zur Hauptseite
                                window.location.href = enableAuth ? '/login' : '/';
                            } catch (error) {
                                showError('Fehler: ' + error.message);
                                this.disabled = false;
                                this.textContent = 'Setup abschliessen';
                            }
                        });

                        function showError(message) {
                            const errorDiv = document.getElementById('errorMessage');
                            errorDiv.textContent = message;
                            errorDiv.style.display = 'block';
                        }
                    </script>
                </body>
                </html>
                """.replace("BODY_CLASS", bodyClass)
                .replace("THEME_SUFFIX", faviconSuffix);
    }
}
