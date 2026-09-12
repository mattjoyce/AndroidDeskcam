// Drive a real Firefox against a real phone, with nothing installed.
//
// Firefox speaks WebDriver BiDi over a WebSocket, and node 22 has a WebSocket client built
// in, so a genuine browser driving the genuine page costs no dependency and no 19 MB
// install. That matters for this project: browser-smoke.mjs needs Playwright fetched from
// outside the repository, so in practice nobody runs it, and harness.mjs runs the page's
// script against a DOM double, which cannot tell you where a pointer landed.
//
// This is for the claims only a browser and a phone together can settle: that a gesture at
// a place on the picture reaches the camera as that same place, through zoom, pan and a
// rotation. It found a mapping error that four rounds of reading the source had missed.
//
// It is a library, deliberately not named *.test.mjs, because build.sh runs that glob on
// every build and this needs a phone and a browser. Run the checks beside it by hand.
import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

/** A port nobody is on. Card 69 lost an hour photographing an unrelated server's 404. */
function freePort() {
  return new Promise((resolve, reject) => {
    const s = createServer();
    s.on('error', reject);
    s.listen(0, '127.0.0.1', () => {
      const { port } = s.address();
      s.close(() => resolve(port));
    });
  });
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

async function connect(url, deadline) {
  for (;;) {
    try {
      const ws = new WebSocket(url);
      await new Promise((resolve, reject) => {
        ws.addEventListener('open', resolve, { once: true });
        ws.addEventListener('error', reject, { once: true });
      });
      return ws;
    } catch (e) {
      if (Date.now() > deadline) throw new Error(`no BiDi socket at ${url}: ${e.message || e}`);
      await sleep(250);
    }
  }
}

/**
 * Launches Firefox, opens a BiDi session, and returns the handle the checks use.
 *
 * Every scrap of state is scratch: a fresh profile in the temp directory and --no-remote,
 * so this can never touch the browser the person at the bench is using.
 */
export async function browser({ binary = process.env.FIREFOX || 'firefox',
                                width = 1280, height = 900, headless = true } = {}) {
  const port = await freePort();
  const profile = mkdtempSync(join(tmpdir(), 'deskcam-ff-'));
  const args = ['--no-remote', '--profile', profile,
                '--remote-debugging-port', String(port), 'about:blank'];
  if (headless) args.unshift('--headless');
  const proc = spawn(binary, args, { stdio: 'ignore' });

  const ws = await connect(`ws://127.0.0.1:${port}/session`, Date.now() + 30000);
  let nextId = 1;
  const pending = new Map();
  const logs = [];
  const events = [];

  ws.addEventListener('message', m => {
    const msg = JSON.parse(String(m.data));
    if (msg.type === 'event') {
      events.push(msg);
      if (msg.method === 'log.entryAdded') logs.push(msg.params);
      return;
    }
    const waiting = pending.get(msg.id);
    if (!waiting) return;
    pending.delete(msg.id);
    if (msg.type === 'success') waiting.resolve(msg.result);
    else waiting.reject(new Error(`${msg.error}: ${msg.message}`));
  });

  function send(method, params = {}) {
    const id = nextId++;
    return new Promise((resolve, reject) => {
      pending.set(id, { resolve, reject });
      ws.send(JSON.stringify({ id, method, params }));
      setTimeout(() => {
        if (pending.delete(id)) reject(new Error(`${method} did not answer in 30s`));
      }, 30000);
    });
  }

  await send('session.new', { capabilities: {} });
  const tree = await send('browsingContext.getTree', {});
  const context = tree.contexts[0].context;
  await send('session.subscribe', { events: ['log.entryAdded'] });
  await send('browsingContext.setViewport', { context, viewport: { width, height } });

  /**
   * Evaluates an expression in the page and returns it as data.
   *
   * The value crosses as a JSON string rather than as a BiDi remote value, so a nested
   * object arrives whole instead of as a handle that needs another round trip per field.
   */
  async function json(expression) {
    const r = await send('script.evaluate', {
      expression: `JSON.stringify((() => (${expression}))() ?? null)`,
      target: { context },
      awaitPromise: true,
    });
    if (r.type !== 'success') {
      throw new Error(`page threw: ${r.exceptionDetails?.text ?? JSON.stringify(r)}`);
    }
    return JSON.parse(r.result.value);
  }

  async function pointer(actions) {
    await send('input.performActions', {
      context,
      actions: [{ type: 'pointer', id: 'mouse', parameters: { pointerType: 'mouse' }, actions }],
    });
  }

  const move = (x, y) => ({ type: 'pointerMove', x: Math.round(x), y: Math.round(y) });
  const down = { type: 'pointerDown', button: 0 };
  const up = { type: 'pointerUp', button: 0 };
  const pause = duration => ({ type: 'pause', duration });

  return {
    context, logs, events, send, json, pointer,
    errors: () => logs.filter(l => l.level === 'error' || l.type === 'javascriptError'),

    async navigate(url) {
      await send('browsingContext.navigate', { context, url, wait: 'complete' });
    },

    async viewport(w, h) {
      await send('browsingContext.setViewport', { context, viewport: { width: w, height: h } });
    },

    async tap(x, y) {
      await pointer([move(x, y), down, up]);
    },

    /** Two taps inside the page's double-tap window, which is what focuses a spot. */
    async doubleTap(x, y) {
      await pointer([move(x, y), down, up, pause(60), down, up]);
    },

    /** A dragged box, in enough steps that the page sees a drag and not a jump. */
    async drag(x0, y0, x1, y1, steps = 6) {
      const path = [];
      for (let i = 1; i <= steps; i++) {
        path.push(move(x0 + ((x1 - x0) * i) / steps, y0 + ((y1 - y0) * i) / steps));
        path.push(pause(16));
      }
      await pointer([move(x0, y0), down, pause(30), ...path, up]);
    },

    async screenshot(path) {
      const r = await send('browsingContext.captureScreenshot', { context });
      const { writeFileSync } = await import('node:fs');
      writeFileSync(path, Buffer.from(r.data, 'base64'));
      return path;
    },

    /**
     * Prints the page to a vector PDF, the way the browser's own print dialogue would.
     *
     * The page decides the paper. Its own @page rule carries the size and orientation, so
     * nothing here names A4: shrinkToFit stays off and the margins stay at zero, or the
     * millimetres in the artwork would stop being millimetres on paper, which is the whole
     * point of a printed measuring surface.
     */
    async print(path, { background = true } = {}) {
      const r = await send('browsingContext.print', {
        context,
        background,
        shrinkToFit: false,
        margin: { top: 0, bottom: 0, left: 0, right: 0 },
      });
      const { writeFileSync } = await import('node:fs');
      writeFileSync(path, Buffer.from(r.data, 'base64'));
      return path;
    },

    async close() {
      try { ws.close(); } catch { /* the socket dies with the browser anyway */ }
      proc.kill('SIGTERM');
      await sleep(300);
      proc.kill('SIGKILL');
      rmSync(profile, { recursive: true, force: true });
    },
  };
}
