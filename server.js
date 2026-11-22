const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = process.env.DEFAULT_APP_PORT || 3000;
const APK_PATH = path.resolve(__dirname, 'app/build/outputs/apk/debug/app-debug.apk');
const BACKUP_APK_PATH = path.resolve(__dirname, '.build-outputs/app-debug.apk');

function getApkInfo() {
  const target = fs.existsSync(APK_PATH) ? APK_PATH : (fs.existsSync(BACKUP_APK_PATH) ? BACKUP_APK_PATH : null);
  if (!target) {
    return { available: false, sizeMb: 0, path: null, modified: null };
  }
  try {
    const stat = fs.statSync(target);
    return {
      available: true,
      sizeBytes: stat.size,
      sizeMb: (stat.size / (1024 * 1024)).toFixed(1),
      path: target,
      modified: stat.mtime.toISOString(),
    };
  } catch {
    return { available: false, sizeMb: 0, path: null, modified: null };
  }
}

const HTML_CONTENT = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>J.A.R.V.I.S. Command Center · Octavian</title>
  <style>
    :root {
      --bg: #070B12;
      --panel: #0D1424;
      --panel-border: rgba(0, 229, 255, 0.28);
      --cyan: #00E5FF;
      --cyan-bright: #38E1FF;
      --cyan-glow: rgba(0, 229, 255, 0.45);
      --ice: #D8F8FF;
      --text-dim: #7B93B2;
      --yt-red: #FF0000;
      --yt-red-glow: rgba(255, 0, 0, 0.4);
      --success: #00E676;
    }

    * { box-sizing: border-box; margin: 0; padding: 0; }

    body {
      background-color: var(--bg);
      color: var(--ice);
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", sans-serif;
      min-height: 100vh;
      display: flex;
      flex-direction: column;
      align-items: center;
      background-image: 
        radial-gradient(circle at 50% 18%, rgba(0, 229, 255, 0.08) 0%, transparent 60%),
        linear-gradient(180deg, #070B12 0%, #03060A 100%);
      overflow-x: hidden;
      padding: 24px 16px 48px;
    }

    .container {
      width: 100%;
      max-width: 860px;
      display: flex;
      flex-direction: column;
      gap: 20px;
    }

    /* Header & Reactor */
    .hero {
      text-align: center;
      padding: 32px 16px 20px;
      position: relative;
    }

    .reactor-wrap {
      width: 110px;
      height: 110px;
      margin: 0 auto 18px;
      position: relative;
      display: flex;
      align-items: center;
      justify-content: center;
    }

    .reactor-ring-outer {
      position: absolute;
      width: 100%;
      height: 100%;
      border-radius: 50%;
      border: 2px dashed var(--cyan);
      box-shadow: 0 0 25px var(--cyan-glow), inset 0 0 15px var(--cyan-glow);
      animation: spin 14s linear infinite;
    }

    .reactor-ring-inner {
      position: absolute;
      width: 74%;
      height: 74%;
      border-radius: 50%;
      border: 2px solid var(--cyan-bright);
      box-shadow: 0 0 18px var(--cyan);
      animation: spin-rev 9s linear infinite;
    }

    .reactor-core {
      width: 44%;
      height: 44%;
      border-radius: 50%;
      background: radial-gradient(circle, #FFFFFF 10%, var(--cyan) 65%, transparent 100%);
      box-shadow: 0 0 30px var(--cyan-bright), 0 0 50px var(--cyan);
      animation: pulse 2.4s ease-in-out infinite;
    }

    @keyframes spin { 100% { transform: rotate(360deg); } }
    @keyframes spin-rev { 100% { transform: rotate(-360deg); } }
    @keyframes pulse {
      0%, 100% { transform: scale(0.92); opacity: 0.85; }
      50% { transform: scale(1.08); opacity: 1; filter: drop-shadow(0 0 20px #FFF); }
    }

    h1 {
      font-size: 26px;
      font-weight: 800;
      letter-spacing: 2.5px;
      color: #FFF;
      text-transform: uppercase;
      text-shadow: 0 0 14px var(--cyan-glow);
      margin-bottom: 6px;
    }

    .subtitle {
      font-size: 13px;
      color: var(--text-dim);
      letter-spacing: 1px;
    }

    .highlight-octavian {
      color: var(--cyan-bright);
      font-weight: 700;
    }

    /* Cards */
    .card {
      background: var(--panel);
      border: 1px solid var(--panel-border);
      border-radius: 18px;
      padding: 20px;
      box-shadow: 0 8px 30px rgba(0, 0, 0, 0.45);
      position: relative;
      overflow: hidden;
    }

    .card::before {
      content: "";
      position: absolute;
      top: 0; left: 0; right: 0; height: 1px;
      background: linear-gradient(90deg, transparent, var(--cyan-bright), transparent);
    }

    .card-title {
      font-size: 14px;
      font-weight: 700;
      letter-spacing: 1.4px;
      text-transform: uppercase;
      color: var(--cyan-bright);
      margin-bottom: 14px;
      display: flex;
      align-items: center;
      gap: 8px;
    }

    /* APK Download Banner */
    .apk-card {
      background: linear-gradient(135deg, rgba(0, 229, 255, 0.12) 0%, rgba(13, 20, 36, 0.95) 100%);
      border: 1.5px solid var(--cyan);
      display: flex;
      flex-direction: column;
      gap: 16px;
    }

    .apk-details {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(130px, 1fr));
      gap: 12px;
      background: rgba(0, 0, 0, 0.35);
      padding: 12px 14px;
      border-radius: 12px;
      border: 1px solid rgba(0, 229, 255, 0.15);
    }

    .apk-detail-item {
      display: flex;
      flex-direction: column;
    }

    .apk-detail-label {
      font-size: 10px;
      color: var(--text-dim);
      text-transform: uppercase;
      letter-spacing: 1px;
    }

    .apk-detail-val {
      font-size: 13px;
      font-weight: 700;
      color: #FFF;
      margin-top: 2px;
    }

    .btn-download {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 10px;
      background: linear-gradient(90deg, #00B4D8, var(--cyan-bright));
      color: #03060A;
      font-weight: 800;
      font-size: 15px;
      letter-spacing: 1.2px;
      text-transform: uppercase;
      text-decoration: none;
      padding: 14px 24px;
      border-radius: 14px;
      box-shadow: 0 4px 20px rgba(0, 229, 255, 0.4);
      transition: all 0.2s ease;
      cursor: pointer;
    }

    .btn-download:hover {
      transform: translateY(-2px);
      box-shadow: 0 6px 26px rgba(0, 229, 255, 0.6);
      filter: brightness(1.1);
    }

    /* Support Developer Card */
    .yt-card {
      background: linear-gradient(135deg, rgba(255, 0, 0, 0.12) 0%, rgba(20, 10, 13, 0.94) 100%);
      border: 1.5px solid rgba(255, 0, 0, 0.65);
      box-shadow: 0 8px 30px rgba(255, 0, 0, 0.18);
    }

    .yt-btn {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 10px;
      background: var(--yt-red);
      color: #FFF;
      font-weight: 800;
      font-size: 14px;
      letter-spacing: 1.1px;
      text-transform: uppercase;
      text-decoration: none;
      padding: 13px 20px;
      border-radius: 14px;
      box-shadow: 0 4px 20px var(--yt-red-glow);
      transition: all 0.2s ease;
    }

    .yt-btn:hover {
      transform: translateY(-2px);
      box-shadow: 0 6px 28px rgba(255, 0, 0, 0.6);
      filter: brightness(1.1);
    }

    /* Modes Grid */
    .modes-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
      gap: 10px;
    }

    .mode-pill {
      background: rgba(0, 229, 255, 0.06);
      border: 1px solid rgba(0, 229, 255, 0.2);
      border-radius: 12px;
      padding: 10px 12px;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    .mode-name {
      font-size: 13px;
      font-weight: 700;
      color: var(--cyan-bright);
    }

    .mode-desc {
      font-size: 11px;
      color: var(--text-dim);
      line-height: 1.3;
    }

    /* Battery & Optimization List */
    .opt-list {
      display: flex;
      flex-direction: column;
      gap: 10px;
    }

    .opt-item {
      display: flex;
      align-items: flex-start;
      gap: 10px;
      font-size: 13px;
      line-height: 1.4;
      color: var(--ice);
    }

    .opt-badge {
      background: rgba(0, 230, 118, 0.15);
      color: var(--success);
      font-size: 10px;
      font-weight: 800;
      letter-spacing: 0.8px;
      padding: 3px 7px;
      border-radius: 6px;
      border: 1px solid rgba(0, 230, 118, 0.35);
      white-space: nowrap;
    }
  </style>
</head>
<body>
  <div class="container">
    <div class="hero">
      <div class="reactor-wrap">
        <div class="reactor-ring-outer"></div>
        <div class="reactor-ring-inner"></div>
        <div class="reactor-core"></div>
      </div>
      <h1>J.A.R.V.I.S. Command Center</h1>
      <p class="subtitle">Voice-First AI Assistant for Samsung Galaxy A54 · Created by <span class="highlight-octavian">Octavian</span></p>
    </div>

    <!-- Live APK Download Card -->
    <div class="card apk-card">
      <div class="card-title">
        <span>⚡</span> Native Android APK (Ready for Sideload)
      </div>
      <p style="font-size: 13px; color: var(--ice); line-height: 1.5;">
        Complete native Android build with Material 3 Jetpack Compose, high-performance coroutines, 
        offline AudioTrack streaming, Tier 1–3 phone and screen automation, and 5 operational modes.
      </p>
      <div class="apk-details">
        <div class="apk-detail-item">
          <span class="apk-detail-label">File Size</span>
          <span class="apk-detail-val" id="apkSize">47.2 MB</span>
        </div>
        <div class="apk-detail-item">
          <span class="apk-detail-label">Version</span>
          <span class="apk-detail-val">v3.1.0</span>
        </div>
        <div class="apk-detail-item">
          <span class="apk-detail-label">Target SDK</span>
          <span class="apk-detail-val">Android 14+ (minSdk 31)</span>
        </div>
        <div class="apk-detail-item">
          <span class="apk-detail-label">Device</span>
          <span class="apk-detail-val">Samsung Galaxy A54</span>
        </div>
      </div>
      <a href="/download/app-debug.apk" class="btn-download" download="jarvis-v3.1.0-debug.apk">
        <span>📥</span> Download app-debug.apk
      </a>
    </div>

    <!-- Battery & System Optimization -->
    <div class="card">
      <div class="card-title">
        <span>🔋</span> High-Efficiency Battery & Performance Optimizations
      </div>
      <div class="opt-list">
        <div class="opt-item">
          <span class="opt-badge">CPU THRESHOLD</span>
          <div>
            <strong>Silence Gate & Zero-Allocation Audio Buffering:</strong> Audio buffers and FFT analysis are paused when audio RMS falls below background ambient floor, saving battery drain during active listening.
          </div>
        </div>
        <div class="opt-item">
          <span class="opt-badge">GPU RENDER</span>
          <div>
            <strong>Hardware RenderThread Offloading:</strong> Canvas rotations, Fourier rings, and Arc Reactor pulses use <code>graphicsLayer</code> transforms, completely bypassing CPU composition passes.
          </div>
        </div>
        <div class="opt-item">
          <span class="opt-badge">DOZE SAFE</span>
          <div>
            <strong>Samsung A54 Standby Profile:</strong> Uses <code>AlarmManager.setExactAndAllowWhileIdle</code> exclusively for user-scheduled deadlines while coalescing background memory extraction to battery-friendly windows.
          </div>
        </div>
        <div class="opt-item">
          <span class="opt-badge">CACHE ZERO-GC</span>
          <div>
            <strong>Memory Buffer Pooling:</strong> In-memory STT recording arrays and TTS frame queues reuse pre-allocated byte arrays, eliminating GC memory spikes and stutter on mobile devices.
          </div>
        </div>
      </div>
    </div>

    <!-- Operational Modes -->
    <div class="card">
      <div class="card-title">
        <span>🎛️</span> 5 Operational Modes Available
      </div>
      <div class="modes-grid">
        <div class="mode-pill">
          <span class="mode-name">⚡ Standard</span>
          <span class="mode-desc">Balanced everyday assistant & device control</span>
        </div>
        <div class="mode-pill">
          <span class="mode-name">📚 Study</span>
          <span class="mode-desc">Feynman learning, active recall & concept breakdown</span>
        </div>
        <div class="mode-pill">
          <span class="mode-name">🔬 Research</span>
          <span class="mode-desc">Multi-query web synthesis & source citations</span>
        </div>
        <div class="mode-pill">
          <span class="mode-name">📋 Tasks</span>
          <span class="mode-desc">Eisenhower priority matrix & schedule alarms</span>
        </div>
        <div class="mode-pill">
          <span class="mode-name">🤖 Agentic</span>
          <span class="mode-desc">Autonomous multi-step tool & screen execution</span>
        </div>
      </div>
    </div>

    <!-- Support Developer YouTube Card -->
    <div class="card yt-card">
      <div class="card-title" style="color: #FF4D4D;">
        <span>▶</span> Support Developer
      </div>
      <p style="font-size: 13px; color: var(--ice); margin-bottom: 14px; line-height: 1.5;">
        Support Octavian's ongoing development of J.A.R.V.I.S., voice models, neural automation, 
        and AI mobile architecture. Subscribe on YouTube for system breakdowns and tutorials.
      </p>
      <a href="https://youtube.com/@octavian-codes?si=VpUqmvjT2ok2s7Pc" target="_blank" rel="noopener noreferrer" class="yt-btn">
        <span>▶</span> Subscribe to @octavian-codes on YouTube
      </a>
    </div>

    <div style="text-align: center; font-size: 11px; color: var(--text-dim); margin-top: 10px;">
      J.A.R.V.I.S. Android v3.1.0 · Private Mobile AI Assistant · Engineered by Octavian
    </div>
  </div>
</body>
</html>
`;

const server = http.createServer((req, res) => {
  const url = req.url.split('?')[0];

  // Health check for monitoring and control plane
  if (url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok', app: 'J.A.R.V.I.S.', version: '3.1.0', author: 'Octavian' }));
    return;
  }

  // API status endpoint
  if (url === '/api/status') {
    const info = getApkInfo();
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      app: 'J.A.R.V.I.S.',
      version: '3.1.0',
      author: 'Octavian',
      youtube: 'https://youtube.com/@octavian-codes?si=VpUqmvjT2ok2s7Pc',
      apk: info,
      optimizations: {
        silenceGate: true,
        zeroAllocationBuffers: true,
        renderThreadGpuOffloading: true,
        adaptiveDozeManagement: true,
      },
      modes: ['default', 'study', 'research', 'tasks', 'agentic'],
    }));
    return;
  }

  // Direct APK file download
  if (url === '/download/app-debug.apk' || url === '/app-debug.apk') {
    const info = getApkInfo();
    if (!info.available) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('APK build not found. Please compile with gradle assembleDebug first.');
      return;
    }

    res.writeHead(200, {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Length': info.sizeBytes,
      'Content-Disposition': 'attachment; filename="jarvis-v3.1.0-debug.apk"',
      'Cache-Control': 'no-cache',
    });
    const stream = fs.createReadStream(info.path);
    stream.on('error', () => {
      if (!res.headersSent) {
        res.writeHead(500, { 'Content-Type': 'text/plain' });
      }
      res.end();
    });
    res.on('close', () => stream.destroy());
    stream.pipe(res);
    return;
  }

  // Main UI
  const apkInfo = getApkInfo();
  const sizeLabel = apkInfo.available ? `${apkInfo.sizeMb} MB` : 'Not built yet';
  const html = HTML_CONTENT.replace(
    '<span class="apk-detail-val" id="apkSize">47.2 MB</span>',
    `<span class="apk-detail-val" id="apkSize">${sizeLabel}</span>`
  );
  res.writeHead(200, {
    'Content-Type': 'text/html; charset=utf-8',
    'Cache-Control': 'no-cache',
  });
  res.end(html);
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`J.A.R.V.I.S. Companion Web Server running at http://0.0.0.0:${PORT}`);
});
