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
