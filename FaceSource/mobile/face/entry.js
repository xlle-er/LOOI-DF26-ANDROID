import { createFaceController } from '../../frontend/js/ui/faceCanvas.js';
import { ScenarioFrameSequencer } from '../../frontend/js/embodiment/scenarioFrameSequencer.js';
import { getScenarioDefinition, MODEL_SCENARIO_NAMES } from '../../frontend/js/embodiment/scenarioCatalog.js';
import { ESP32Client } from '../../frontend/js/robot/esp32Client.js';
import { CommandQueue } from '../../frontend/js/robot/commandQueue.js';

// A definite pixel host avoids percentage/container height resolution bugs in WebView.
function sizeAndroidHost() {
  const height = Math.max(1, window.innerHeight);
  for (const element of [document.documentElement, document.body, document.querySelector('#faceCanvas')]) {
    element.style.height = `${height}px`;
    element.style.minHeight = `${height}px`;
  }
}
window.addEventListener('resize', sizeAndroidHost);
requestAnimationFrame(sizeAndroidHost);
const pending = new Map(); let sequence = 0; let operationId = null; let nativeFailure = null;
function request(kind, payload) {
  const id = `face-${++sequence}`;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(id); reject(new Error('原生指令超时')); }, 10_000);
    pending.set(id, {resolve, reject, timer});
    window.LooiNative.postMessage(JSON.stringify({id, kind, payload, runId: operationId}));
  });
}
class NativeBody extends ESP32Client {
  isConnected() { return this.connected && this.readyState === 1; }
  async writeJsonMessage(payload) { const ack = await request('command', payload); if(ack?.accepted === false) throw new Error('Native command rejected'); this.handleMessageObject({...ack, id: payload.id}); }
}
const body = new NativeBody(); body.requireAck = true;
const face = createFaceController(document.querySelector('#faceCanvas'));
const queue = new CommandQueue({robotClient: body});
let cameraRunning = false;
const camera = {
  getCameraStatus: () => ({running: cameraRunning, facingMode: 'user'}),
  async captureSnapshot() { const dataUrl = await request('photo', {}); return {ok: true, snapshot: {dataUrl}}; }
};
const sequencer = new ScenarioFrameSequencer({face, commandQueue: queue, cameraInput: camera});
window.looiFace = {
  resize: sizeAndroidHost,
  reply(id, value, error) {
    const call = pending.get(id); if (!call) return;
    pending.delete(id); clearTimeout(call.timer); if(error) { nativeFailure = error; call.reject(new Error(error)); } else call.resolve(value);
  },
  state(value) {
    body.connected = Boolean(value.connected); body.readyState = body.connected ? 1 : 3;
    cameraRunning = Boolean(value.cameraRunning);
    face.setSpeaking(Boolean(value.speaking)); face.setThinking(Boolean(value.thinking));
    if (value.expression) face.setExpression(value.expression);
  },
  async run(name, allowMotion = false, runId = null) {
    operationId = runId; nativeFailure = null;
    const scenario = getScenarioDefinition(name); if (!scenario) throw new Error('未知动作');
    const result = await sequencer.playFrameSequence({...scenario, frames: scenario.sequence}, {allowMotion, allowCamera: cameraRunning});
    if(nativeFailure || !result.ok || result.interrupted || (allowMotion && result.partial)) throw new Error(nativeFailure || result.reason || 'Action incomplete');
    return result;
  },
  cancel() { sequencer.interrupt('native_stop', 100); queue.cancelMotion('native_stop').catch(() => {}); },
  pause(paused) { document.body.style.visibility = paused ? 'hidden' : 'visible'; }
};
window.LooiNative.postMessage(JSON.stringify({kind: 'ready', scenarios: MODEL_SCENARIO_NAMES}));
