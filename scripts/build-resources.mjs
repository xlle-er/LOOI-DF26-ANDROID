import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {createRequire} from 'node:module';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);
const {build} = require('esbuild');
const {buildGeminiLiveSetup} = await import('../FaceSource/frontend/js/gemini/geminiLiveTools.js');
const {DEFAULT_PERSONALITY_PROFILE} = await import('../ReferenceSource/personality/personalityProfile.js');
const {IDLE_SCENARIOS} = await import('../ReferenceSource/idle/idleScenarioCatalog.js');
const {MODEL_SCENARIO_NAMES} = await import('../FaceSource/frontend/js/embodiment/scenarioCatalog.js');
const assets = path.join(root, 'app/src/main/assets');
fs.mkdirSync(path.join(assets,'face'), {recursive:true});
await build({entryPoints:[path.join(root,'FaceSource/mobile/face/entry.js')],outfile:path.join(assets,'face/face.js'),bundle:true,minify:true,format:'iife',target:'chrome80'});
for (const name of ['index.html','face.css','android-host.css']) fs.copyFileSync(path.join(root,'FaceSource',name),path.join(assets,'face',name));
fs.writeFileSync(path.join(assets,'live-setup.json'),JSON.stringify(buildGeminiLiveSetup({model:'gemini-3.8-live'})));
fs.writeFileSync(path.join(assets,'personality.json'),JSON.stringify(DEFAULT_PERSONALITY_PROFILE));
fs.writeFileSync(path.join(assets,'idle-scenarios.json'),JSON.stringify(IDLE_SCENARIOS));
fs.writeFileSync(path.join(assets,'scenarios.json'),JSON.stringify(MODEL_SCENARIO_NAMES));
const hashes = {};
function walk(dir) { for (const entry of fs.readdirSync(dir,{withFileTypes:true})) { const f = path.join(dir,entry.name); if(entry.isDirectory()) walk(f); else hashes[path.relative(root,f)] = crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex'); } }
hashes['icon.png'] = crypto.createHash('sha256').update(fs.readFileSync(path.join(root,'icon.png'))).digest('hex');
walk(path.join(root,'app/src/main/res'));
walk(path.join(root,'app/src/main/assets/licenses'));
walk(path.join(root,'FaceSource')); walk(path.join(root,'ReferenceSource'));
for(const name of ['face/face.js','face/face.css','face/android-host.css','face/index.html','live-setup.json','scenarios.json','personality.json','idle-scenarios.json','efficientdet_lite0.tflite']) {
 const f=path.join(assets,name); if(fs.existsSync(f)) hashes[path.relative(root,f)] = crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');
}
fs.writeFileSync(path.join(root,'docs/resource-hashes.json'), JSON.stringify({iosCommit:'c61b26df3bbca6037723fca070dae5abde3b3235',webSource:'workspace snapshot 2026-10-15 (contains local changes)',hashes},null,2)+'\n');
console.log('Built original face, scenario catalog, Live prompt and resource manifest.');
