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
