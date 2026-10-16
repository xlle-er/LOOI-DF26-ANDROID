import {test,expect} from '@playwright/test';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
const url=pathToFileURL(path.resolve('app/src/main/assets/face/index.html')).href;
test.beforeEach(async ({page})=>{
 await page.addInitScript(()=>{
  window.__commands=[];
  window.LooiNative={postMessage(raw){
   const message=JSON.parse(raw);
   if(message.kind==='ready'){window.__ready=true;return;}
   if(message.kind==='scenarioDone')return;
   window.__commands.push(message);
   setTimeout(()=>window.looiFace.reply(message.id,{type:'ack',accepted:true},window.__fail && message.payload?.type==='motion'?'ACK timeout':null),20);
  }};
 });
 await page.goto(url);await page.waitForFunction(()=>window.__ready && window.looiFace);
});
test('original eyes resize, speak and run confirmed original motion',async({page})=>{
 for(const viewport of [{width:390,height:844},{width:844,height:390}]){
  await page.setViewportSize(viewport); const box=await page.locator('.looi-eye').first().boundingBox();expect(box.width).toBeGreaterThan(80);expect(box.width).toBeLessThan(viewport.width/2);
 }
 await page.evaluate(()=>window.looiFace.state({connected:true,speaking:true}));
 await expect(page.locator('.looi-eyes')).toHaveClass(/is-speaking/);
 const result=await page.evaluate(()=>window.looiFace.run('look_left',true,'test-1'));
 expect(result.ok).toBe(true);expect(result.partial).toBe(false);
 const command=await page.evaluate(()=>window.__commands.find(m=>m.payload?.type==='motion'));
 expect(command.runId).toBe('test-1');expect(command.payload.angular).toBe(-.055);expect(command.payload.duration_ms).toBe(170);
});
test('native ACK failure is rejected rather than reported as completion',async({page})=>{
 await page.evaluate(()=>{window.looiFace.state({connected:true});window.__fail=true;});
 const result=await page.evaluate(()=>window.looiFace.run('look_right',true,'test-2').then(()=>({ok:true})).catch(()=>({ok:false})));
 expect(result.ok).toBe(false);
});
test('camera-off photo cannot complete and never opens a browser camera',async({page})=>{
 const result=await page.evaluate(()=>window.looiFace.run('take_picture',false,'photo').then(()=>true).catch(()=>false));
 expect(result).toBe(false);expect(await page.evaluate(()=>window.__commands.filter(m=>m.kind==='photo').length)).toBe(0);
});
test('cancel invalidates an active scenario',async({page})=>{
 await page.evaluate(()=>{window.looiFace.state({connected:true});window.__run=window.looiFace.run('look_left',true,'cancel').then(()=>true).catch(()=>false);});
 await page.waitForFunction(()=>window.__commands.length>0);
 await page.evaluate(()=>window.looiFace.cancel());expect(await page.evaluate(()=>window.__run)).toBe(false);
});
test('Android host recovers full height after a collapsed initial viewport', async ({page})=>{
 await page.setViewportSize({width:1,height:1});
 await page.setViewportSize({width:844,height:390});
 await page.evaluate(()=>{document.body.style.height='0px'; window.looiFace.resize();});
 await expect.poll(()=>page.evaluate(()=>document.body.getBoundingClientRect().height)).toBeGreaterThan(380);
 const rect=await page.locator('.looi-eye').first().boundingBox();
 expect(rect.y).toBeGreaterThan(0);expect(rect.y+rect.height).toBeLessThan(390);
});
