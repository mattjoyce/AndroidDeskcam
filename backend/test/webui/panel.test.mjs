import test from 'node:test';
import assert from 'node:assert/strict';
import { panel, status } from './harness.mjs';

test('a token link authenticates startup, controls, pan, restart and still images', async () => {
  const token = 'bench +&?#/key';
  const p = await panel('?token=' + encodeURIComponent(token));
  const urlToken = url => new URL(url, 'http://phone:8080').searchParams.get('token');
  for (const call of p.calls) assert.equal(urlToken(call.url), token, call.url);
  assert.equal(urlToken(p.element('view').src), token);
  const control = p.run("api('/api/set?zoom=2')");
  await p.flush();
  assert.equal(urlToken(p.calls.at(-1).url), token);
  p.calls.at(-1).reply(status(2));
  await control;
  const pan = p.run('sendPan(0.6, 0.6)');
  await p.flush();
  assert.equal(urlToken(p.calls.at(-1).url), token);
  p.calls.at(-1).reply();
  await pan;
  p.run('restream(); still();');
  assert.equal(urlToken(p.element('view').src), token);
  assert.equal(urlToken(p.opened[0]), token);
  // Credentials must never enter the copyable request log.
  for (const row of p.element('log').childNodes) {
    assert.ok(!row.dataset.line.includes('token='));
  }
});

test('an open panel sends no token', async () => {
  const p = await panel();
  for (const call of p.calls) assert.ok(!call.url.includes('token='));
  assert.ok(!p.element('view').src.includes('token='));
});

test('a stalled command times out visibly and a later command can run', async () => {
  const p = await panel();
  p.run("api('/api/af')");
  await p.flush();
  const stuck = p.calls.at(-1);
  await p.advance(15001);
  assert.equal(stuck.options.signal?.aborted, true);
  assert.match(p.element('msg').textContent, /timed out/i);
  const next = p.run("api('/api/set?zoom=3')");
  await p.flush();
  assert.match(p.calls.at(-1).url, /zoom=3/);
  p.calls.at(-1).reply(status(3));
  await next;
  assert.equal(p.run('last.zoom'), 3);
});

test('status and marks polls allow only one pending request each and recover after timeout', async () => {
  const p = await panel();
  p.calls.length = 0;
  p.run('refresh(); refresh(); loadMarks(); loadMarks();');
  await p.flush();
  assert.equal(p.calls.filter(c => c.url.includes('/api/status')).length, 1);
  assert.equal(p.calls.filter(c => c.url.includes('/api/marks')).length, 1);
  await p.advance(15001);
  p.run('refresh(); loadMarks();');
  await p.flush();
  assert.equal(p.calls.length, 4);
  for (const call of p.calls.slice(2)) call.reply();
  await p.flush();
});

test('the deadline also covers a response whose JSON body never finishes', async () => {
  const p = await panel();
  const command = p.run("api('/api/af')");
  await p.flush();
  p.calls.at(-1).reply(new Promise(() => {}));
  await p.flush();
  await p.advance(15001);
  assert.equal(await command, null);
  assert.match(p.element('msg').textContent, /timed out/i);
});

test('slider input keeps the final value and coalesces intermediate pending values', async () => {
  const p = await panel();
  p.calls.length = 0;
  p.run("setv('zoom', 2); setv('zoom', 3); setv('zoom', 4); setv('zoom', 5);");
  await p.flush();
  assert.equal(p.calls.length, 1);
  p.calls[0].reply(status(2));
  await p.flush();
  assert.equal(p.calls.length, 2);
  assert.match(p.calls[1].url, /zoom=5/);
  p.calls[1].reply(status(5));
  await p.flush();
  assert.equal(p.run('last.zoom'), 5);
});

test('coalescing does not cross an autofocus action and pan shares the command queue', async () => {
  const p = await panel();
  p.calls.length = 0;
  p.run("setv('zoom', 2); setv('zoom', 3); api('/api/af'); setv('zoom', 4); sendPan(0.6, 0.7);");
  await p.flush();
  const expected = [/zoom=2/, /zoom=3/, /\/api\/af/, /zoom=4/, /cx=0.6000&cy=0.7000/];
  for (let i = 0; i < expected.length; i++) {
    assert.equal(p.calls.length, i + 1, 'one command in flight');
    assert.match(p.calls[i].url, expected[i]);
    p.calls[i].reply();
    await p.flush();
  }
});

test('an ambiguous timeout cancels queued controls and never replays them', async () => {
  const p = await panel();
  p.calls.length = 0;
  const first = p.run("api('/api/af')");
  const queued = p.run("api('/api/set?zoom=6')");
  await p.flush();
  await p.advance(15001);
  assert.equal(await first, null);
  assert.equal(await queued, null);
  assert.equal(p.calls.length, 1);
  assert.match(p.element('msg').textContent, /cancelled/i);
});

test('a status response started before a control cannot restore old framing', async () => {
  const p = await panel();
  p.run('refresh()');
  const oldPoll = p.calls.at(-1);
  const change = p.run("api('/api/set?zoom=5')");
  await p.flush();
  p.calls.at(-1).reply(status(5));
  await change;
  oldPoll.reply(status(1));
  await p.flush();
  assert.equal(p.run('last.zoom'), 5);
  assert.equal(Number(p.element('zoom').value), 5);
  p.run('refresh()');
  p.calls.at(-1).reply(status(6));
  await p.flush();
  assert.equal(p.run('last.zoom'), 6, 'later external changes still arrive');
});

test('polls wait while controls are pending and intermediate replies leave slider input alone', async () => {
  const p = await panel();
  p.calls.length = 0;
  p.run("setv('zoom', 2); setv('zoom', 5);");
  p.element('zoom').value = '5';
  p.run('refresh(); loadMarks();');
  await p.flush();
  assert.equal(p.calls.length, 1);
  p.calls[0].reply(status(2));
  await p.flush();
  assert.equal(Number(p.element('zoom').value), 5);
  p.calls[1].reply(status(5));
  await p.flush();
  assert.equal(Number(p.element('zoom').value), 5);
});

test('HTTP failures stay visible, cancel pending actions, and release the command queue', async () => {
  for (const code of [400, 401, 409, 500]) {
    const p = await panel();
    p.calls.length = 0;
    const first = p.run("api('/api/af')");
    const queued = p.run("api('/api/set?dx=0.2')");
    p.calls[0].reply({ error: 'request refused' }, code);
    assert.equal(await first, null);
    assert.equal(await queued, null);
    assert.equal(p.calls.length, 1);
    assert.equal(p.element('msg').hidden, false);
    assert.match(p.element('msg').textContent, /cancelled/);
    const retry = p.run("api('/api/set?zoom=2')");
    p.calls.at(-1).reply(status(2));
    await retry;
    assert.equal(p.run('last.zoom'), 2);
  }
});

test('marks from an older crop are discarded and later marks still arrive', async () => {
  const p = await panel();
  p.run('loadMarks()');
  const oldPoll = p.calls.at(-1);
  const change = p.run("api('/api/set?zoom=5')");
  p.calls.at(-1).reply(status(5));
  await change;
  oldPoll.reply({ marks: [{ id: 1, label: 'old crop' }] });
  await p.flush();
  assert.equal(p.run('MARKS.length'), 0);
  p.run('loadMarks()');
  p.calls.at(-1).reply({ marks: [{ id: 2, label: 'current crop', cx: 0.5, cy: 0.5, in_crop: true }] });
  await p.flush();
  assert.equal(p.run('MARKS[0].id'), 2);
});

test('coalesced callers all complete when the final value is acknowledged', async () => {
  const p = await panel();
  const first = p.run("setv('zoom', 2)");
  const middle = p.run("setv('zoom', 3)");
  const last = p.run("setv('zoom', 4)");
  p.calls.at(-1).reply(status(2));
  await first;
  await p.flush();
  p.calls.at(-1).reply(status(4));
  assert.equal((await middle).settings.zoom, 4);
  assert.equal((await last).settings.zoom, 4);
});

test('a mark is redrawn when the framing changes, not on the next marks poll', async () => {
  const p = await panel();
  // Placed in CSS pixels, so compared as numbers: the arithmetic lands on
  // -192.00000000000003 and a string comparison would fail on the last bit rather than on
  // anything a person could see.
  const at = el => [parseFloat(el.style.left), parseFloat(el.style.top)];
  const near = (actual, expected) => assert.ok(Math.abs(actual - expected) < 0.001,
    `expected ${expected}, got ${actual}`);

  // One mark against the crop the page has now. The double's picture is 640 by 480 at the
  // origin, so a mark at 0.3,0.7 of a full frame belongs at 192,336.
  p.run('MARKS = [{id: 1, kind: "point", cx: 0.3, cy: 0.7, in_crop: true, by: "agent"}];'
        + ' drawMarks();');
  const marks = p.element('marks');
  assert.equal(marks.childNodes.length, 1);
  near(at(marks.firstChild)[0], 192);
  near(at(marks.firstChild)[1], 336);

  // A status answer that moves the crop, with no marks poll anywhere near it. At zoom 4 on
  // the middle the crop starts at 0.375, so 0.3 is off the left edge at -192 and 0.7 is
  // below the bottom at 624.
  const polling = p.run('refresh()');
  await p.flush();
  assert.match(p.calls.at(-1).url, /\/api\/status/);
  p.calls.at(-1).reply(status(4));
  await polling;
  assert.equal(p.run('last.zoom'), 4);

  // Before this, drawMarks ran only on the marks poll and on a resize, so after a reframe
  // every mark sat on the wrong part of the picture until the next tick. live-bench.mjs
  // caught one 333 px from where it belonged, straight after a zoom.
  near(at(marks.firstChild)[0], -192);
  near(at(marks.firstChild)[1], 624);
});


test('labels go to the gutter on their own side and never overlap each other', async () => {
  const p = await panel();
  // Four marks, two a side, each pair close enough that labels drawn on the marks would
  // have sat on top of one another. The double's picture is 640 by 480 at the origin.
  p.run(`MARKS = [
    {id: 1, kind: "point", cx: 0.2, cy: 0.50, in_crop: true, by: "agent", label: "left upper"},
    {id: 2, kind: "point", cx: 0.2, cy: 0.52, in_crop: true, by: "agent", label: "left lower"},
    {id: 3, kind: "point", cx: 0.8, cy: 0.30, in_crop: true, by: "you",   label: "right upper"},
    {id: 4, kind: "point", cx: 0.8, cy: 0.31, in_crop: true, by: "you",   label: "right lower"}
  ]; drawMarks();`);

  const kids = p.element('calls').childNodes;
  const labels = kids.filter(el => el.className.startsWith('mkcall'));
  const leads = kids.filter(el => el.className.startsWith('mklead'));
  assert.equal(labels.length, 4);
  assert.equal(leads.length, 4, 'every label is joined to its mark');

  // The anchor layer keeps the marks themselves, so a label is never drawn inside one.
  assert.equal(p.element('marks').childNodes.length, 4);
  for (const mk of p.element('marks').childNodes) assert.equal(mk.childNodes.length, 0);

  const side = want => labels.filter(el => el.style[want] !== undefined);
  assert.equal(side('left').length, 2, 'marks left of centre take the left gutter');
  assert.equal(side('right').length, 2);

  // Within a column the order follows the marks down the picture, and the boxes are clear
  // of each other. 0.50 and 0.52 of 480 are 9.6 px apart; a 22 px label needs more.
  for (const want of ['left', 'right']) {
    const column = side(want).map(el => parseFloat(el.style.top)).sort((a, b) => a - b);
    assert.ok(column[1] - column[0] >= 22, `${want} labels overlap: ${column}`);
  }
  const lefts = side('left').map(el => el.textContent);
  assert.deepEqual(lefts, ['left upper', 'left lower'], 'higher mark, higher label');

  // Somebody else's text is set as text, never parsed.
  p.run(`MARKS = [{id: 5, kind: "point", cx: 0.5, cy: 0.5, in_crop: true,
                   label: "<img src=x onerror=alert(1)>"}]; drawMarks();`);
  assert.equal(p.element('calls').firstChild.textContent, '<img src=x onerror=alert(1)>');
  assert.equal(p.element('calls').firstChild.childNodes.length, 0);
});

test('a column with no room for its labels becomes numbered badges, not cut text', async () => {
  const p = await panel();
  // The double's picture is 640 by 480 and a label falls back to 22 px, so a column has
  // room for about 18. Twenty on one side cannot fit however they are stacked.
  const many = n => Array.from({length: n}, (_, i) =>
    `{id: ${i + 1}, kind: "point", cx: 0.8, cy: ${(i + 1) / (n + 1)},
      in_crop: true, by: "agent", label: "part number ${i + 1}"}`).join(',');

  p.run(`MARKS = [${many(20)}]; drawMarks();`);
  const kids = () => p.element('calls').childNodes;
  assert.equal(kids().filter(el => el.className.startsWith('mkcall')).length, 0,
    'no label is drawn when the column cannot hold them all');
  assert.equal(kids().filter(el => el.className.startsWith('mklead')).length, 0,
    'and no leader points at a label that is not there');
  const badges = kids().filter(el => el.className.startsWith('mkbadge'));
  assert.equal(badges.length, 20);
  // The badge carries the mark's place in the list, which is how the two are read together.
  assert.deepEqual(badges.map(el => el.textContent).slice(0, 3), ['1', '2', '3']);
  assert.equal(badges[0].title, 'part number 1', 'the full text survives on the badge');

  // The list carries the same numbers, whichever form the picture is in.
  p.run('listMarks();');
  const row = p.element('marklist').firstChild;
  assert.equal(row.firstChild.textContent, '1');

  // Few enough to fit, and the text comes back rather than staying as badges.
  p.run(`MARKS = [${many(3)}]; drawMarks();`);
  assert.equal(kids().filter(el => el.className.startsWith('mkbadge')).length, 0);
  assert.equal(kids().filter(el => el.className.startsWith('mkcall')).length, 3);
});

test('a label takes more lines rather than being cut, until the column runs out', async () => {
  const p = await panel();
  const many = n => Array.from({length: n}, (_, i) =>
    `{id: ${i + 1}, kind: "point", cx: 0.8, cy: ${(i + 1) / (n + 1)},
      in_crop: true, by: "agent", label: "part number ${i + 1}"}`).join(',');
  const clamps = () => [...p.element('calls').childNodes]
    .filter(el => el.className.startsWith('mkcall'))
    .map(el => el.style['-webkit-line-clamp']);

  // The double reports no layout, so nothing is ever measured as truncated and two lines
  // always suffices. That is the point worth pinning: the budget starts at two and only
  // grows when something was actually cut, never speculatively.
  p.run(`MARKS = [${many(3)}]; drawMarks();`);
  assert.deepEqual(clamps(), ['2', '2', '2']);

  // Twenty in one column cannot fit at any allowance, so they become badges. Growing the
  // budget must not have removed the floor.
  p.run(`MARKS = [${many(20)}]; drawMarks();`);
  assert.equal(clamps().length, 0);
  assert.equal([...p.element('calls').childNodes]
    .filter(el => el.className.startsWith('mkbadge')).length, 20);
});

test('registration rings the marks, and says so loudest when the bench has moved', async () => {
  const p = await panel();
  const marks = p.element('marks');
  const chip = p.element('reg');
  const text = () => [...chip.childNodes].map(n => n.textContent).join(' ');

  // Nothing known. A page that was never told says nothing, rather than showing an empty
  // tick that reads as "not registered" when it means "nobody asked".
  p.run('showRegistration(null);');
  assert.equal(chip.hidden, true);
  assert.equal(marks.className, 'marks');

  p.run(`showRegistration({state: "fresh", markers: 8, micrometres_per_pixel: 82.6,
                           rotation_degrees: -1.67, residual_mm: 0.538});`);
  assert.equal(chip.hidden, false);
  assert.match(marks.className, /\breg\b/, 'the marks carry the ring, not just the chip');
  assert.match(text(), /registered/);
  assert.match(text(), /8 markers/);
  assert.match(text(), /82\.6/);

  // The state that matters. A mark is stored against the sensor, so after the bench moves
  // it names the wrong part and looks no different; this is the only warning there is.
  p.run('showRegistration({state: "moved", moved_mm: 14.93});');
  assert.match(marks.className, /\bmoved\b/);
  assert.doesNotMatch(marks.className, /\breg\b/);
  assert.match(text(), /the bench moved/);
  assert.match(text(), /14\.9 mm/);
  assert.match(text(), /wrong parts/, 'it says what it means for the marks, not just the mm');

  // And it can go back to knowing nothing, for instance when the mat leaves the frame.
  p.run('showRegistration(null);');
  assert.equal(chip.hidden, true);
  assert.equal(marks.className, 'marks');
});

test('shared panel sends the console header on polls and controls', async () => {
  const p = await panel();
  for (const call of p.calls) assert.equal(call.options.headers['X-DeskCam-Console'], '1');
  p.run("api('/api/set?zoom=2')");
  await p.flush();
  assert.equal(p.calls.at(-1).options.headers['X-DeskCam-Console'], '1');
  p.calls.at(-1).reply();
  await p.flush();
});

test('shift gestures mark the cropped sensor without focusing or reframing', async () => {
  const p = await panel();
  p.run('last = {zoom: 2, cx: 0.5, cy: 0.5};');
  const view = p.element('view');
  view.naturalHeight = 480;
  const event = (x, y) => ({button: 0, pointerId: 1, shiftKey: true,
    clientX: x, clientY: y, preventDefault() {}});
  p.calls.length = 0;
  view.listeners.pointerdown(event(160, 120));
  view.listeners.pointermove(event(480, 360));
  view.listeners.pointerup(event(480, 360));
  await p.flush();
  const call = p.calls.find(c => c.url.includes('mark='));
  assert.ok(call);
  assert.equal(new URL(call.url, 'http://phone').searchParams.get('mark'), '0.5000,0.5000,0.2500,0.2500');
  call.reply({marks: []});
  await p.flush();
  for (const c of p.calls) assert.ok(!/\/api\/(af|set)/.test(c.url), c.url);
  assert.equal(p.run('lastTap.t'), 0);
});


test('look-here replaces annotations, while Ctrl-Shift preserves them', async () => {
  const p = await panel();
  const view = p.element('view');
  view.naturalHeight = 480;
  const event = (x, y, ctrlKey) => ({button: 0, pointerId: 1, shiftKey: true,
    ctrlKey, clientX: x, clientY: y, preventDefault() {}});
  for (const keep of [false, true, false]) {
    view.listeners.pointerdown(event(160, 120, keep));
    view.listeners.pointermove(event(480, 360, keep));
    view.listeners.pointerup(event(480, 360, keep));
    await p.flush();
    const call = p.calls.filter(c => c.url.includes('mark=')).at(-1);
    const q = new URL(call.url, 'http://phone').searchParams;
    assert.equal(q.get('unmark'), keep ? null : 'all');
    assert.equal(q.get('label'), 'look here');
    assert.equal(q.get('mark'), '0.5000,0.5000,0.5000,0.5000');
    call.reply({marks: []});
    await p.flush();
  }
});

test('reset clears annotations before a subsequent additive mark', async () => {
  const p = await panel();
  const reset = p.run('resetAll()');
  p.run('markGesture({sx: .2, sy: .2, x: .4, y: .4, keepMarks: true})');
  await p.flush();
  assert.equal(p.calls.at(-1).url, '/api/reset');
  p.calls.at(-1).reply();
  await p.flush();
  assert.equal(p.calls.at(-1).url, '/api/marks?unmark=all');
  p.calls.at(-1).reply({marks: []});
  await p.flush();
  assert.match(p.calls.at(-1).url, /mark=0.3000,0.3000,0.2000,0.2000/);
  assert.ok(!p.calls.at(-1).url.includes('unmark='));
  p.calls.at(-1).reply({marks: []});
  await reset;
});

test('a refused reset leaves annotations intact', async () => {
  const p = await panel();
  const reset = p.run('resetAll()');
  await p.flush();
  p.calls.at(-1).reply({error: 'camera busy'}, 409);
  await reset;
  assert.ok(!p.calls.some(c => c.url.includes('unmark=')));
});


test('plus and minus zoom relatively, while editing and browser shortcuts are untouched', async () => {
  const p = await panel();
  for (const [key, factor] of [['+', 1.25], ['=', 1.25], ['-', .8]]) {
    const event = {key, preventDefault() { this.prevented = true; }};
    p.context.keyEvent = event;
    p.run('zoomKey(keyEvent)');
    await p.flush();
    assert.equal(event.prevented, true);
    assert.equal(p.calls.at(-1).url, '/api/set?zoomby=' + factor);
    p.calls.at(-1).reply();
    await p.flush();
  }
  const before = p.calls.length;
  for (const extra of [{ctrlKey: true}, {metaKey: true}, {altKey: true},
      {isComposing: true}, {defaultPrevented: true}, {target: {closest: () => ({})}}]) {
    p.context.keyEvent = {key: '+', ...extra, preventDefault() { throw new Error('shortcut intercepted'); }};
    p.run('zoomKey(keyEvent)');
  }
  await p.flush();
  assert.equal(p.calls.length, before);
});
