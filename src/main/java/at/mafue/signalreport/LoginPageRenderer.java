package at.mafue.signalreport;

public class LoginPageRenderer
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
                    <title>SignalReport Login</title>
                    <style>
                        :root {
                            --bg-body: #f8f9fa;
                            --bg-card: #ffffff;
                            --color-primary: #0d6efd;
                            --color-primary-hover: #0b5ed7;
                            --color-text: #495057;
                            --color-text-secondary: #6c757d;
                            --color-border: #dddddd;
                            --color-shadow: rgba(0,0,0,0.1);
                            --color-input-bg: #ffffff;
                            --bg-error: #f8d7da;
                        }
                        body.dark-mode {
                            --bg-body: #1a1a2e;
                            --bg-card: #16213e;
                            --color-primary: #4d94ff;
                            --color-primary-hover: #3a7bd5;
                            --color-text: #e0e0e0;
                            --color-text-secondary: #9e9e9e;
                            --color-border: #3a3a5c;
                            --color-shadow: rgba(0,0,0,0.3);
                            --color-input-bg: #1e1e3a;
                            --bg-error: #3d1520;
                        }
                        body { font-family: Arial, sans-serif; background: var(--bg-body); color: var(--color-text); margin: 0; padding: 0; }
                        .login-container { max-width: 420px; margin: 120px auto; background: var(--bg-card); padding: 40px; border-radius: 10px; box-shadow: 0 2px 10px var(--color-shadow); }
                        h1 { color: var(--color-primary); text-align: center; margin-bottom: 10px; }
                        .subtitle { text-align: center; color: var(--color-text-secondary); margin-bottom: 30px; }
                        .form-group { margin-bottom: 20px; }
                        label { display: block; margin-bottom: 8px; font-weight: bold; color: var(--color-text); }
                        input[type="text"], input[type="password"] { width: 100%; padding: 12px; border: 1px solid var(--color-border); border-radius: 5px; font-size: 16px; background: var(--color-input-bg); color: var(--color-text); box-sizing: border-box; }
                        input:focus { outline: none; border-color: var(--color-primary); box-shadow: 0 0 0 3px rgba(13,110,253,0.15); }
                        .btn { width: 100%; padding: 12px; background: var(--color-primary); color: white; border: none; border-radius: 5px; font-size: 16px; font-weight: bold; cursor: pointer; margin-top: 10px; }
                        .btn:hover { background: var(--color-primary-hover); }
                        .btn:disabled { background: var(--color-text-secondary); cursor: not-allowed; }
                        .error { color: #dc3545; margin-top: 15px; padding: 10px; background: var(--bg-error); border-radius: 5px; display: none; text-align: center; }
                        .login-logo { max-width: 280px; height: auto; display: block; margin: 0 auto 10px auto; }
                        .login-logo.dark { display: none; }
                        body.dark-mode .login-logo.light { display: none; }
                        body.dark-mode .login-logo.dark { display: block; margin: 0 auto 10px auto; }
                    </style>
                </head>
                <body BODY_CLASS>
                    <div class="login-container">
                        <img src="/logo_mit_schriftzug_light.png" alt="SignalReport" class="login-logo light">
                        <img src="/logo_mit_schriftzug_dark_login.png" alt="SignalReport" class="login-logo dark">
                        <p class="subtitle">Anmeldung erforderlich</p>
                
                        <div class="form-group">
                            <label for="username">Benutzername</label>
                            <input type="text" id="username" placeholder="admin oder user" autocomplete="username">
                        </div>
                
                        <div class="form-group">
                            <label for="password">Passwort</label>
                            <input type="password" id="password" placeholder="Passwort eingeben" autocomplete="current-password">
                        </div>
                
                        <div class="error" id="errorMessage"></div>
                
                        <button class="btn" id="loginBtn">Anmelden</button>
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
                
                        // Challenge-Response Login
                        async function login() {
                            const username = document.getElementById('username').value.trim();
                            const password = document.getElementById('password').value;
                            const errorDiv = document.getElementById('errorMessage');
                            const loginBtn = document.getElementById('loginBtn');
                
                            errorDiv.style.display = 'none';
                
                            if (!username || !password) {
                                showError('Benutzername und Passwort eingeben!');
                                return;
                            }
                
                            loginBtn.disabled = true;
                            loginBtn.textContent = 'Anmeldung...';
                
                            try {
                                // 1. Nonce vom Server holen
                                const nonceResponse = await fetch('/api/auth/nonce');
                                if (!nonceResponse.ok) throw new Error('Nonce-Anfrage fehlgeschlagen');
                                const nonceData = await nonceResponse.json();
                                const nonce = nonceData.nonce;
                
                                // 2. Challenge-Response berechnen: SHA-256(SHA-256(password) + nonce)
                                const passwordHash = await sha256(password);
                                const challengeResponse = await sha256(passwordHash + nonce);
                
                                // 3. Login-Anfrage senden
                                const loginResponse = await fetch('/api/auth/login', {
                                    method: 'POST',
                                    headers: { 'Content-Type': 'application/json' },
                                    body: JSON.stringify({
                                        username: username,
                                        nonce: nonce,
                                        challengeResponse: challengeResponse
                                    })
                                });
                
                                if (loginResponse.ok) {
                                    const result = await loginResponse.json();
                                    // Session-Token als Cookie speichern
                                    document.cookie = 'SR_SESSION=' + result.token + '; path=/; SameSite=Strict';
                                    window.location.href = '/';
                                } else {
                                    const errorText = await loginResponse.text();
                                    showError(errorText || 'Anmeldung fehlgeschlagen!');
                                }
                            } catch (error) {
                                showError('Verbindungsfehler: ' + error.message);
                            } finally {
                                loginBtn.disabled = false;
                                loginBtn.textContent = 'Anmelden';
                            }
                        }
                
                        function showError(message) {
                            const errorDiv = document.getElementById('errorMessage');
                            errorDiv.textContent = message;
                            errorDiv.style.display = 'block';
                        }
                
                        // Login-Button und Enter-Taste
                        document.getElementById('loginBtn').addEventListener('click', login);
                        document.getElementById('password').addEventListener('keypress', function(e) {
                            if (e.key === 'Enter') login();
                        });
                        document.getElementById('username').addEventListener('keypress', function(e) {
                            if (e.key === 'Enter') document.getElementById('password').focus();
                        });
                
                        // Autofocus auf Benutzername
                        document.getElementById('username').focus();
                    </script>
                </body>
                </html>
                """.replace("BODY_CLASS", bodyClass)
                .replace("THEME_SUFFIX", faviconSuffix);
    }
}
