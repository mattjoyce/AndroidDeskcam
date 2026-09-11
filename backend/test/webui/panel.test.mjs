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
