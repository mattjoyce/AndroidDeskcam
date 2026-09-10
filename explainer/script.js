// Antigravity Agentic Vision Engine
document.addEventListener('DOMContentLoaded', () => {
  initTheme();
  initNavScroll();
  initSensorSimulator();
  initFocusVisualizer();
  initPwmSimulator();
  initTapeSimulator();
  initCmdBuilder();
});

// 1. Theme Management (Antigravity Dark Cosmos vs Aurora Light)
function initTheme() {
  const toggleBtn = document.getElementById('themeToggle');
  const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
  const savedTheme = localStorage.getItem('deskcam-theme') || (prefersDark ? 'dark' : 'light');
  
  setTheme(savedTheme);
  
  if (toggleBtn) {
    toggleBtn.addEventListener('click', () => {
      const current = document.documentElement.getAttribute('data-theme') || 'dark';
      const next = current === 'dark' ? 'light' : 'dark';
      setTheme(next);
      localStorage.setItem('deskcam-theme', next);
      window.dispatchEvent(new Event('themechange'));
    });
  }
}

function setTheme(theme) {
  document.documentElement.setAttribute('data-theme', theme);
  const themeLabel = document.getElementById('themeLabel');
  if (themeLabel) {
    themeLabel.textContent = theme === 'dark' ? 'THEME: DARK' : 'THEME: LIGHT';
  }
}

function getThemeColors() {
  const isLight = document.documentElement.getAttribute('data-theme') === 'light';
  if (isLight) {
    return {
      main: '#0284c7',
      bright: '#6366f1',
      purple: '#9333ea',
      dim: '#64748b',
      dark: '#cbd5e1',
      bg: '#f8fafc',
      card: '#ffffff',
      subtle: '#e2e8f0',
      accentRed: '#e11d48',
      accentEmerald: '#059669'
    };
  }
  return {
    main: '#38bdf8',
    bright: '#818cf8',
    purple: '#c084fc',
    dim: '#64748b',
    dark: '#1e293b',
    bg: '#07090e',
    card: '#101623',
    subtle: 'rgba(255, 255, 255, 0.08)',
    accentRed: '#f43f5e',
    accentEmerald: '#10b981'
  };
}

// 2. Navigation & ScrollSpy
function initNavScroll() {
  const links = document.querySelectorAll('.nav-link');
  const sections = document.querySelectorAll('section[id]');
  const mobileToggle = document.getElementById('mobileNavToggle');
  const sidebar = document.querySelector('.sidebar');

  if (mobileToggle && sidebar) {
    mobileToggle.addEventListener('click', () => {
      sidebar.classList.toggle('open');
    });

    document.addEventListener('click', (e) => {
      if (!sidebar.contains(e.target) && !mobileToggle.contains(e.target)) {
        sidebar.classList.remove('open');
      }
    });
  }

  window.addEventListener('scroll', () => {
    let currentId = '';
    const scrollPos = window.scrollY + 120;

    sections.forEach(sec => {
      const top = sec.offsetTop;
      const height = sec.offsetHeight;
      if (scrollPos >= top && scrollPos < top + height) {
        currentId = sec.getAttribute('id');
      }
    });

    links.forEach(link => {
      link.classList.remove('active');
      if (link.getAttribute('href') === `#${currentId}`) {
        link.classList.add('active');
      }
    });
  });
}

// 3. Sensor & Software ROI Crop Simulator
function initSensorSimulator() {
  const canvas = document.getElementById('sensorCanvas');
  if (!canvas) return;
  const ctx = canvas.getContext('2d');

  const zoomSlider = document.getElementById('simZoom');
  const cxSlider = document.getElementById('simCx');
  const cySlider = document.getElementById('simCy');
  const rotateSelect = document.getElementById('simRotate');

  const zoomVal = document.getElementById('simZoomVal');
  const cxVal = document.getElementById('simCxVal');
  const cyVal = document.getElementById('simCyVal');
  
  const readoutRes = document.getElementById('readoutRes');
  const readoutPath = document.getElementById('readoutPath');
  const readoutBounds = document.getElementById('readoutBounds');

  function resizeCanvas() {
    const rect = canvas.parentElement.getBoundingClientRect();
    canvas.width = rect.width * window.devicePixelRatio;
    canvas.height = (rect.width * 0.75) * window.devicePixelRatio; // 4:3 aspect ratio
    draw();
  }

  window.addEventListener('resize', resizeCanvas);
  window.addEventListener('themechange', draw);

  function draw() {
    const cols = getThemeColors();
    const zoom = parseFloat(zoomSlider.value);
    const cx = parseFloat(cxSlider.value);
    const cy = parseFloat(cySlider.value);
    const rotate = parseInt(rotateSelect.value, 10);

    zoomVal.textContent = zoom.toFixed(1) + 'x';
    cxVal.textContent = cx.toFixed(2);
    cyVal.textContent = cy.toFixed(2);

    const fullW = 4032;
    const fullH = 3024;
    
    // Calculate ROI dimensions in sensor pixels
    const roiW = Math.round(fullW / zoom);
    const roiH = Math.round(fullH / zoom);

    // Clamped ROI center so box stays strictly within sensor boundary
    const halfW = roiW / 2;
    const halfH = roiH / 2;
    const sensorCenterX = fullW * cx;
    const sensorCenterY = fullH * cy;
    const left = Math.max(0, Math.min(fullW - roiW, sensorCenterX - halfW));
    const top = Math.max(0, Math.min(fullH - roiH, sensorCenterY - halfH));

    // Readout text
    readoutRes.textContent = `${roiW} × ${roiH} px`;
    if (zoom <= 1.0001 && rotate === 0) {
      readoutPath.innerHTML = '<span class="highlight" style="color:var(--accent-emerald)">camera_jpeg</span> (Untouched direct sensor JPEG)';
    } else {
      readoutPath.innerHTML = '<span style="color:var(--accent-amber)">decoded_and_reencoded</span> (BitmapRegionDecoder tiled crop)';
    }
    readoutBounds.textContent = `L:${Math.round(left)}, T:${Math.round(top)}, W:${roiW}, H:${roiH}`;

    // Render Canvas
    const w = canvas.width;
    const h = canvas.height;
    ctx.clearRect(0, 0, w, h);

    // Background
    ctx.fillStyle = '#05070c';
    ctx.fillRect(0, 0, w, h);

    // Subtle sensor matrix grid
    ctx.strokeStyle = '#111827';
    ctx.lineWidth = 1 * window.devicePixelRatio;
    const gridCols = 16;
    const gridRows = 12;
    for (let c = 0; c <= gridCols; c++) {
      const x = (w / gridCols) * c;
      ctx.beginPath();
      ctx.moveTo(x, 0);
      ctx.lineTo(x, h);
      ctx.stroke();
    }
    for (let r = 0; r <= gridRows; r++) {
      const y = (h / gridRows) * r;
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(w, y);
      ctx.stroke();
    }

    // Benchmark subject: simulated PCB on bench
    ctx.fillStyle = '#064e3b';
    ctx.fillRect(w * 0.15, h * 0.15, w * 0.7, h * 0.7);

    // Traces & chips
    ctx.fillStyle = '#0f172a';
    ctx.fillRect(w * 0.28, h * 0.28, w * 0.2, h * 0.25); // IC1
    ctx.fillStyle = '#1e293b';
    ctx.fillRect(w * 0.55, h * 0.45, w * 0.15, h * 0.2); // IC2

    // Pins
    ctx.fillStyle = '#e2e8f0';
    for (let p = 0; p < 8; p++) {
      ctx.fillRect(w * 0.26, h * (0.3 + p * 0.025), w * 0.02, h * 0.015);
      ctx.fillRect(w * 0.48, h * (0.3 + p * 0.025), w * 0.02, h * 0.015);
    }

    // Silkscreen text
    ctx.fillStyle = '#f8fafc';
    ctx.font = `${10 * window.devicePixelRatio}px var(--font-mono)`;
    ctx.fillText('U1 [SOC-ARM64]', w * 0.29, h * 0.4);
    ctx.fillText('DEBUG_PORT_UART', w * 0.38, h * 0.8);

    // 1. Hardware HAL CENTER_ONLY Crop (what hardware would do)
    const halW = w / zoom;
    const halH = h / zoom;
    const halX = (w - halW) / 2;
    const halY = (h - halH) / 2;

    ctx.strokeStyle = cols.accentRed;
    ctx.lineWidth = 2 * window.devicePixelRatio;
    ctx.setLineDash([4 * window.devicePixelRatio, 4 * window.devicePixelRatio]);
    ctx.strokeRect(halX, halY, halW, halH);
    ctx.fillStyle = cols.accentRed;
    ctx.fillText('HAL CENTER_ONLY (Hardware Pan Stripped)', halX + 8, halY + 18);

    // 2. DeskCam Software ROI Crop (true pan & crop)
    const scaleX = w / fullW;
    const scaleY = h / fullH;
    const canvasRoiX = left * scaleX;
    const canvasRoiY = top * scaleY;
    const canvasRoiW = roiW * scaleX;
    const canvasRoiH = roiH * scaleY;

    ctx.setLineDash([]);
    ctx.strokeStyle = cols.main;
    ctx.lineWidth = 2.5 * window.devicePixelRatio;
    ctx.strokeRect(canvasRoiX, canvasRoiY, canvasRoiW, canvasRoiH);

    // ROI overlay tint
    ctx.fillStyle = 'rgba(56, 189, 248, 0.12)';
    ctx.fillRect(canvasRoiX, canvasRoiY, canvasRoiW, canvasRoiH);

    // Focus / Metering reticle at ROI center
    const reticleX = canvasRoiX + canvasRoiW / 2;
    const reticleY = canvasRoiY + canvasRoiH / 2;
    ctx.strokeStyle = cols.accentEmerald;
    ctx.lineWidth = 2 * window.devicePixelRatio;
    ctx.beginPath();
    ctx.arc(reticleX, reticleY, 14 * window.devicePixelRatio, 0, Math.PI * 2);
    ctx.stroke();

    ctx.fillStyle = cols.main;
    ctx.fillText(`DeskCam Software ROI (${zoom.toFixed(1)}x True Pixels)`, canvasRoiX + 8, canvasRoiY + 20);
    ctx.fillStyle = cols.accentEmerald;
    ctx.fillText('AF / AE Metering Follows ROI', canvasRoiX + 8, canvasRoiY + 36);
  }

  [zoomSlider, cxSlider, cySlider, rotateSelect].forEach(el => {
    el.addEventListener('input', draw);
  });

  resizeCanvas();
}

// 4. Focus Dioptres vs Millimetres Visualizer
function initFocusVisualizer() {
  const canvas = document.getElementById('focusCurveCanvas');
  const distSlider = document.getElementById('focusDistSlider');
  const distVal = document.getElementById('focusDistVal');
  const dioptreReadout = document.getElementById('focusDioptreReadout');
  const dofReadout = document.getElementById('focusDofReadout');

  if (!canvas || !distSlider) return;
  const ctx = canvas.getContext('2d');

  function resize() {
    const rect = canvas.parentElement.getBoundingClientRect();
    canvas.width = rect.width * window.devicePixelRatio;
    canvas.height = rect.height * window.devicePixelRatio;
    draw();
  }

  window.addEventListener('resize', resize);
  window.addEventListener('themechange', draw);

  function draw() {
    const cols = getThemeColors();
    const distMm = parseFloat(distSlider.value);
    distVal.textContent = distMm + ' mm';
    
    // Dioptres = 1 / (d in metres)
    const dioptres = 1000 / distMm;
    dioptreReadout.textContent = dioptres.toFixed(2) + ' d';
    
    // DOF in mm scales non-linearly
    const dofMm = 1.5 * Math.pow(distMm / 98, 1.85);
    dofReadout.textContent = '±' + (dofMm / 2).toFixed(1) + ' mm';

    const w = canvas.width;
    const h = canvas.height;
    ctx.clearRect(0, 0, w, h);

    const pLeft = 50 * window.devicePixelRatio;
    const pRight = 20 * window.devicePixelRatio;
    const pTop = 20 * window.devicePixelRatio;
    const pBottom = 35 * window.devicePixelRatio;
    const plotW = w - pLeft - pRight;
    const plotH = h - pTop - pBottom;

    // Grid lines
    ctx.strokeStyle = cols.subtle;
    ctx.lineWidth = 1 * window.devicePixelRatio;
    ctx.beginPath();
    ctx.moveTo(pLeft, pTop);
    ctx.lineTo(pLeft, h - pBottom);
    ctx.lineTo(w - pRight, h - pBottom);
    ctx.stroke();

    // Labels
    ctx.fillStyle = cols.dim;
    ctx.font = `${10 * window.devicePixelRatio}px var(--font-mono)`;
    ctx.fillText('98mm', pLeft, h - pBottom + 16 * window.devicePixelRatio);
    ctx.fillText('500mm', pLeft + plotW * 0.5, h - pBottom + 16 * window.devicePixelRatio);
    ctx.fillText('1000mm', w - pRight - 35 * window.devicePixelRatio, h - pBottom + 16 * window.devicePixelRatio);
    ctx.fillText('10.2 d', 10 * window.devicePixelRatio, pTop + 12 * window.devicePixelRatio);
    ctx.fillText('1.0 d', 14 * window.devicePixelRatio, h - pBottom);

    // Plot Dioptres curve: D = 1000 / mm
    ctx.strokeStyle = cols.main;
    ctx.lineWidth = 2.5 * window.devicePixelRatio;
    ctx.beginPath();
    for (let x = 0; x <= plotW; x++) {
      const mm = 98 + (x / plotW) * (1000 - 98);
      const d = 1000 / mm;
      const normD = (d - 1.0) / (10.204 - 1.0);
      const y = (h - pBottom) - normD * plotH;
      if (x === 0) ctx.moveTo(pLeft + x, y);
      else ctx.lineTo(pLeft + x, y);
    }
    ctx.stroke();

    // Current position
    const curNormX = (distMm - 98) / (1000 - 98);
    const curX = pLeft + curNormX * plotW;
    const curNormD = (dioptres - 1.0) / (10.204 - 1.0);
    const curY = (h - pBottom) - curNormD * plotH;

    ctx.fillStyle = cols.bright;
    ctx.beginPath();
    ctx.arc(curX, curY, 6 * window.devicePixelRatio, 0, Math.PI * 2);
    ctx.fill();

    // Drop line
    ctx.strokeStyle = cols.dim;
    ctx.setLineDash([3 * window.devicePixelRatio, 3 * window.devicePixelRatio]);
    ctx.beginPath();
    ctx.moveTo(curX, curY);
    ctx.lineTo(curX, h - pBottom);
    ctx.stroke();
    ctx.setLineDash([]);
  }

  distSlider.addEventListener('input', draw);
  resize();
}

// 5. PWM Wave & Anti-Banding Visualizer
function initPwmSimulator() {
  const canvas = document.getElementById('pwmCanvas');
  const expSelect = document.getElementById('pwmExposureSelect');
  const pwmReadout = document.getElementById('pwmReadout');
  const pwmRatioReadout = document.getElementById('pwmRatioReadout');

  if (!canvas || !expSelect) return;
  const ctx = canvas.getContext('2d');

  function resize() {
    const rect = canvas.parentElement.getBoundingClientRect();
    canvas.width = rect.width * window.devicePixelRatio;
    canvas.height = rect.height * window.devicePixelRatio;
    draw();
  }

  window.addEventListener('resize', resize);
  window.addEventListener('themechange', draw);

  function draw() {
    const cols = getThemeColors();
    const expVal = expSelect.value;
    const pwmFreq = 240;
    const pwmPeriod = 1000 / pwmFreq; // 4.167 ms

    let exposureMs = 4.167;
    if (expVal === '1/240') exposureMs = 4.167;
    else if (expVal === '1/120') exposureMs = 8.333;
    else if (expVal === '1/60') exposureMs = 16.667;
    else if (expVal === '1/30') exposureMs = 33.333;
    else if (expVal === '1/100') exposureMs = 10.000;
    else if (expVal === '1/160') exposureMs = 6.250;

    const periods = exposureMs / pwmPeriod;
    const isInteger = Math.abs(periods - Math.round(periods)) < 0.02;

    pwmRatioReadout.textContent = periods.toFixed(2) + ' periods';
    if (isInteger) {
      pwmReadout.innerHTML = '<span class="highlight" style="color:var(--accent-emerald)">0.0% Banding (Integer Multiple)</span> — Phase Neutral';
    } else {
      pwmReadout.innerHTML = `<span style="color:${cols.accentRed}">SEVERE ROLLING SHUTTER BANDING</span> — Duty cycle mismatch!`;
    }

    const w = canvas.width;
    const h = canvas.height;
    ctx.clearRect(0, 0, w, h);

    // Background
    ctx.fillStyle = '#05070c';
    ctx.fillRect(0, 0, w, h);

    // Draw PWM square wave at top
    ctx.strokeStyle = cols.main;
    ctx.lineWidth = 2 * window.devicePixelRatio;
    ctx.beginPath();
    const cycles = 6;
    const cycleW = w / cycles;
    const topY = 25 * window.devicePixelRatio;
    const botY = 65 * window.devicePixelRatio;

    for (let i = 0; i < cycles; i++) {
      const startX = i * cycleW;
      ctx.moveTo(startX, botY);
      ctx.lineTo(startX, topY);
      ctx.lineTo(startX + cycleW * 0.5, topY);
      ctx.lineTo(startX + cycleW * 0.5, botY);
      ctx.lineTo(startX + cycleW, botY);
    }
    ctx.stroke();

    ctx.fillStyle = cols.dim;
    ctx.font = `${10 * window.devicePixelRatio}px var(--font-mono)`;
    ctx.fillText('240Hz Display PWM Waveform (4.17ms duty cycle)', 10, 18 * window.devicePixelRatio);

    // Rolling Shutter scanlines below
    const scanlineTop = 85 * window.devicePixelRatio;
    const scanlineH = h - scanlineTop - 15 * window.devicePixelRatio;
    const scanlineCount = 80;

    for (let s = 0; s < scanlineCount; s++) {
      const y = scanlineTop + (s / scanlineCount) * scanlineH;
      const phaseOffset = (s / scanlineCount) * 2 * Math.PI;
      
      let intensity = 1.0;
      if (!isInteger) {
        intensity = 0.45 + 0.5 * Math.sin(phaseOffset * (cycles / periods));
      }

      ctx.fillStyle = `rgb(${Math.round(56 * intensity)}, ${Math.round(189 * intensity)}, ${Math.round(248 * intensity)})`;
      ctx.fillRect(10, y, w - 20, scanlineH / scanlineCount);
    }

    ctx.fillStyle = isInteger ? cols.accentEmerald : cols.accentRed;
    ctx.fillText(
      isInteger ? '✓ Uniform Frame (Each sensor row integrates identical photon flux)' : '✗ Banding Wave (Rolling shutter beats across duty cycle phases)',
      15,
      scanlineTop + 20 * window.devicePixelRatio
    );
  }

  expSelect.addEventListener('change', draw);
  resize();
}

// 6. Action Tape Script Runner Simulator
function initTapeSimulator() {
  const runBtn = document.getElementById('runTapeBtn');
  const concurrentBtn = document.getElementById('concurrentReqBtn');
  const tapeText = document.getElementById('tapeText');
  const tapeLog = document.getElementById('tapeLog');
  const lockIndicator = document.getElementById('cameraLockIndicator');

  if (!runBtn || !tapeText || !tapeLog) return;

  let isLocked = false;

  runBtn.addEventListener('click', async () => {
    if (isLocked) return;
    isLocked = true;
    runBtn.disabled = true;
    lockIndicator.textContent = 'CAMERA LOCKED (409 CONFLICT)';
    lockIndicator.style.color = '#f43f5e';
    lockIndicator.style.borderColor = 'rgba(244, 63, 94, 0.4)';
    lockIndicator.style.background = 'rgba(244, 63, 94, 0.12)';

    tapeLog.innerHTML = '<div class="tape-log-step"><span>[POST /api/script] Validating tape verbs...</span><span class="highlight" style="color:var(--accent-emerald)">200 OK (Accepted)</span></div>';

    const lines = tapeText.value.split('\n').map(l => l.trim()).filter(l => l && !l.startsWith('#'));

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      const verb = line.split(' ')[0].toUpperCase();
      
      const stepEl = document.createElement('div');
      stepEl.className = 'tape-log-step';
      stepEl.innerHTML = `<span>STEP ${i}: <code>${line}</code></span><span>EXECUTING...</span>`;
      tapeLog.appendChild(stepEl);
      tapeLog.scrollTop = tapeLog.scrollHeight;

      await sleep(500);

      if (verb === 'SNAP' || verb === 'FRAME' || verb === 'RAW') {
        stepEl.className = 'tape-log-step done';
        stepEl.innerHTML = `<span>STEP ${i}: <code>${line}</code></span><span class="highlight" style="color:var(--accent-cyan)">CAPTURED 00${i}.JPG [PROVENANCE STREAMED]</span>`;
      } else if (verb === 'FOCUSHUNT') {
        stepEl.className = 'tape-log-step done';
        stepEl.innerHTML = `<span>STEP ${i}: <code>${line}</code></span><span class="highlight" style="color:var(--accent-indigo)">FOCUSED 4.25d (14 READINGS IN 4.0s)</span>`;
      } else {
        stepEl.className = 'tape-log-step done';
        stepEl.innerHTML = `<span>STEP ${i}: <code>${line}</code></span><span class="highlight" style="color:var(--accent-emerald)">OK</span>`;
      }
    }

    const doneEl = document.createElement('div');
    doneEl.className = 'tape-log-step done';
    doneEl.style.fontWeight = 'bold';
    doneEl.innerHTML = `<span>>> TAPE EXECUTION COMPLETE. CAMERA RELEASED.</span><span class="highlight" style="color:var(--accent-emerald)">DONE</span>`;
    tapeLog.appendChild(doneEl);
    tapeLog.scrollTop = tapeLog.scrollHeight;

    isLocked = false;
    runBtn.disabled = false;
    lockIndicator.textContent = 'CAMERA IDLE';
    lockIndicator.style.color = 'var(--accent-cyan)';
    lockIndicator.style.borderColor = 'rgba(56, 189, 248, 0.3)';
    lockIndicator.style.background = 'var(--accent-cyan-dim)';
  });

  concurrentBtn.addEventListener('click', () => {
    const time = new Date().toLocaleTimeString();
    const attemptEl = document.createElement('div');
    if (isLocked) {
      attemptEl.className = 'tape-log-step locked';
      attemptEl.innerHTML = `<span>[${time}] GET /api/set?zoom=3 (CONCURRENT AGENT)</span><span style="color:#f43f5e">HTTP 409 CONFLICT: CAMERA LOCKED BY SCRIPT</span>`;
    } else {
      attemptEl.className = 'tape-log-step done';
      attemptEl.innerHTML = `<span>[${time}] GET /api/set?zoom=3 (CONCURRENT AGENT)</span><span class="highlight" style="color:var(--accent-emerald)">HTTP 200 OK (CAMERA ACQUIRED)</span>`;
    }
    tapeLog.appendChild(attemptEl);
    tapeLog.scrollTop = tapeLog.scrollHeight;
  });
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

// 7. Interactive Command Builder
function initCmdBuilder() {
  const verbSelect = document.getElementById('cmdVerb');
  const zoomInput = document.getElementById('cmdZoom');
  const cxInput = document.getElementById('cmdCx');
  const cyInput = document.getElementById('cmdCy');
  const torchInput = document.getElementById('cmdTorch');
  const expInput = document.getElementById('cmdExp');
  const isoInput = document.getElementById('cmdIso');
  const measureSelect = document.getElementById('cmdMeasure');

  const cliOutput = document.getElementById('cliOutput');
  const httpOutput = document.getElementById('httpOutput');
  const copyCli = document.getElementById('copyCliBtn');
  const copyHttp = document.getElementById('copyHttpBtn');

  if (!verbSelect || !cliOutput || !httpOutput) return;

  function update() {
    const verb = verbSelect.value;
    const zoom = zoomInput.value;
    const cx = cxInput.value;
    const cy = cyInput.value;
    const torch = torchInput.value;
    const exp = expInput.value;
    const iso = isoInput.value;
    const measure = measureSelect.value;

    let params = [];
    if (zoom && zoom !== '1') params.push(`zoom=${zoom}`);
    if (cx && cx !== '0.5') params.push(`cx=${cx}`);
    if (cy && cy !== '0.5') params.push(`cy=${cy}`);
    if (torch && torch !== '0') params.push(`torch=${torch}`);
    if (exp && exp !== 'auto') params.push(`exposure=${exp}`);
    if (iso && iso !== 'auto') params.push(`iso=${iso}`);
    if (measure && measure !== '0') params.push(`measure=${measure}`);

    const paramStr = params.join(' ');
    cliOutput.textContent = `deskcam ${verb}${paramStr ? ' ' + paramStr : ''}`;

    let endpoint = '/api/still';
    if (verb === 'snap') endpoint = '/api/still';
    else if (verb === 'frame') endpoint = '/api/frame';
    else if (verb === 'raw') endpoint = '/api/raw';
    else if (verb === 'burst') endpoint = '/api/burst';
    else if (verb === 'focushunt') endpoint = '/api/focushunt';
    else if (verb === 'status') endpoint = '/api/status';
    else if (verb === 'set') endpoint = '/api/set';
    else if (verb === 'reset') endpoint = '/api/reset';

    const queryStr = params.join('&');
    httpOutput.textContent = `http://192.168.1.100:8080${endpoint}${queryStr ? '?' + queryStr : ''}`;
  }

  [verbSelect, zoomInput, cxInput, cyInput, torchInput, expInput, isoInput, measureSelect].forEach(el => {
    el.addEventListener('input', update);
  });

  copyCli.addEventListener('click', () => {
    navigator.clipboard.writeText(cliOutput.textContent);
    copyCli.textContent = 'COPIED';
    setTimeout(() => copyCli.textContent = 'COPY CLI', 1400);
  });

  copyHttp.addEventListener('click', () => {
    navigator.clipboard.writeText(httpOutput.textContent);
    copyHttp.textContent = 'COPIED';
    setTimeout(() => copyHttp.textContent = 'COPY URL', 1400);
  });

  update();
}
