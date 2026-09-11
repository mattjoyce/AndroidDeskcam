// Run the shipped script, with only the DOM, network and clock supplied by the test.
// This checks request ordering and state; it does not claim browser layout coverage.
import { readFileSync, mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import vm from 'node:vm';

function servedPage() {
  const java = readFileSync(new URL('../../app/src/dev/deskcam/WebUi.java', import.meta.url), 'utf8');
  const block = java.slice(java.indexOf('private static final String PAGE ='));
  const dir = mkdtempSync(join(tmpdir(), 'deskcam-page-'));
  try {
    const file = join(dir, 'PageFixture.java');
    // Let Java interpret its own text block, just as it does in the APK.
    writeFileSync(file, 'class PageFixture { public static void main(String[] args) {'
      + 'System.out.print(PAGE); } ' + block);
    return execFileSync('java', [file], { encoding: 'utf8' });
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

export const html = servedPage();
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];

class Element {
  constructor() {
    this.childNodes = [];
    this.dataset = {};
    this.style = {};
    this.attrs = {};
    this.hidden = false;
    this.value = '';
    this._text = '';
    this.naturalWidth = 640;
    this.classList = { add() {}, remove() {} };
  }
  set textContent(text) { this._text = text; this.childNodes = []; }
  get textContent() { return this._text; }
  get firstChild() { return this.childNodes[0]; }
  get lastChild() { return this.childNodes.at(-1); }
  get src() { return this.attrs.src || ''; }
  set src(value) { this.attrs.src = value; }
  appendChild(el) { this.childNodes.push(el); el.parentNode = this; return el; }
  insertBefore(el) { this.childNodes.unshift(el); el.parentNode = this; }
  removeChild(el) { this.childNodes.splice(this.childNodes.indexOf(el), 1); }
  getAttribute(name) { return this.attrs[name] ?? null; }
  removeAttribute(name) { delete this.attrs[name]; }
  setAttribute(name, value) { this.attrs[name] = value; }
  addEventListener() {}
  getBoundingClientRect() { return { left: 0, top: 0, width: 640, height: 480 }; }
}

export function status(zoom = 1) {
  return { ok: true, settings: { zoom, cx: 0.5, cy: 0.5, ev: 0, torch: 0,
    af: 'continuous', ae: 'auto', awb: 'auto', focus_diopters: null },
    state: 'running', stream_clients: 1 };
}

export async function panel(search = '') {
  const elements = new Map();
  const element = id => {
    if (!elements.has(id)) elements.set(id, new Element());
    return elements.get(id);
  };
  element('view').parentNode = new Element();
  const initialSrc = html.match(/<img id="view"[^>]*\bsrc="([^"]*)"/);
  if (initialSrc) element('view').src = initialSrc[1];
  const document = { hidden: false, activeElement: null, body: new Element(),
    getElementById: element, createElement: () => new Element(),
    createTextNode: text => ({ textContent: text }), addEventListener() {} };
  const calls = [], opened = [], timers = new Map();
  let automatic = true, now = 100000, nextTimer = 0;
  const context = vm.createContext({ document, console, URL, URLSearchParams,
    AbortController, DOMException, navigator: {},
    location: { search, href: 'http://phone:8080/' + search },
    Date: class extends Date { static now() { return now; } },
    setTimeout(fn, delay) { const id = ++nextTimer; timers.set(id, { fn, at: now + delay }); return id; },
    clearTimeout(id) { timers.delete(id); },
    setInterval() {},
    fetch(url, options = {}) {
      return new Promise((resolve, reject) => {
        const call = { url: String(url), options, settled: false,
          reply(body = status(), code = 200) {
            this.settled = true;
            resolve({ ok: code >= 200 && code < 300, status: code,
              json: async () => body });
          }, reject };
        options.signal?.addEventListener('abort', () => {
          call.settled = true;
          reject(new DOMException('aborted', 'AbortError'));
        }, { once: true });
        calls.push(call);
        if (automatic) call.reply(String(url).includes('/api/marks') ? { marks: [] } : status());
      });
    }
  });
  context.window = { location: context.location, addEventListener() {},
    open(url) { opened.push(String(url)); } };
  vm.runInContext(script, context, { filename: 'served-panel.js' });
  const flush = async () => { for (let i = 0; i < 30; i++) await Promise.resolve(); };
  await flush();
  automatic = false;
  return { calls, opened, element, context, flush,
    run(code) { return vm.runInContext(code, context); },
    async advance(ms) {
      now += ms;
      for (const [id, timer] of [...timers]) {
        if (timer.at <= now) { timers.delete(id); timer.fn(); }
      }
      await flush();
    }
  };
}
