// Optional real-browser check. Install Playwright outside the repository and set
// PLAYWRIGHT_MODULE to its index.mjs. See docs/codex-review.md for the full command.
// The server below is only a localhost camera double; it never contacts a phone.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { html, status } from './harness.mjs';

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const token = 'bench +&?#/key';
const calls = [], errors = [];
let zoom = 1, releaseFirst;
const firstCommand = new Promise(resolve => { releaseFirst = resolve; });
let holdFirst = true;
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=', 'base64');
const server = createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  res.setHeader('Cache-Control', 'no-store');
  if (url.pathname === '/favicon.ico') { res.writeHead(204).end(); return; }
  calls.push(url);
  if (url.searchParams.get('token') !== token) {
    res.writeHead(401, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: 'unauthorised' }));
    return;
  }
  if (url.pathname === '/') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(html);
    return;
  }
  if (url.pathname === '/api/stream' || url.pathname === '/api/still') {
    // A finite PNG checks the image URL/auth path, not MJPEG frame decoding.
    res.writeHead(200, { 'Content-Type': 'image/png' });
    res.end(png);
    return;
  }
  if (url.pathname === '/api/set') {
    if (holdFirst) { holdFirst = false; await firstCommand; }
    if (url.searchParams.has('zoom')) zoom = Number(url.searchParams.get('zoom'));
  }
  const body = url.pathname === '/api/marks' ? { marks: [] } : status(zoom);
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
});
server.listen(0, '127.0.0.1');
await once(server, 'listening');
let browser;
try {
  browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  page.on('pageerror', error => errors.push(error.message));
  const address = server.address();
  await page.goto(`http://127.0.0.1:${address.port}/?token=${encodeURIComponent(token)}`);
  await page.waitForFunction(() => document.getElementById('deckstate').textContent.startsWith('live'));
  await page.waitForFunction(() => document.getElementById('view').naturalWidth > 0);

  // Dispatch actual HTML input handlers while the first network response is held.
  await page.locator('#zoom').evaluate(el => {
    for (const value of ['2', '3', '4', '5']) {
      el.value = value;
      el.dispatchEvent(new Event('input', { bubbles: true }));
    }
  });
  releaseFirst();
  await page.waitForFunction(() => document.getElementById('zoomv').textContent === '5.0x');
  assert.deepEqual(calls.filter(u => u.pathname === '/api/set').map(u => u.searchParams.get('zoom')), ['2', '5']);
  await page.getByRole('button', { name: 'Autofocus', exact: true }).click();
  await page.waitForFunction(() => document.querySelector('#log .le.ok .w')?.textContent === 'af');
  const streamsBefore = calls.filter(u => u.pathname === '/api/stream').length;
  const restarted = page.waitForResponse(r => new URL(r.url()).pathname === '/api/stream');
  await page.getByRole('button', { name: 'Restart stream', exact: true }).click();
  await restarted;
  assert.equal(calls.filter(u => u.pathname === '/api/stream').length, streamsBefore + 1);
  const popupEvent = page.waitForEvent('popup');
  await page.getByRole('button', { name: 'Save full-res still', exact: true }).click();
  const popup = await popupEvent;
  await popup.waitForLoadState();
  assert.equal(new URL(popup.url()).searchParams.get('token'), token);
  assert.ok(calls.some(u => u.pathname === '/api/still'));
  for (const url of calls) assert.equal(url.searchParams.get('token'), token, url.pathname);
  assert.ok(!(await page.locator('#log').textContent()).includes('token='));
  assert.deepEqual(errors, []);
  console.log('Chromium smoke passed: authenticated startup/images, final slider value, autofocus, restart, still popup; no page errors.');
} finally {
  releaseFirst();
  await browser?.close();
  server.closeAllConnections();
  await new Promise(resolve => server.close(resolve));
}
