// Node's built-in test runner; synthetic APIs test client ordering, not hardware.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const code = fs.readFileSync(path.join(__dirname, '../web/index.html'), 'utf8').match(/<script>([\s\S]*?)<\/script>/)[1];

async function client() {
  const elements = new Map(), callbacks = new Map(), intervals = new Map(), sockets = [];
  let callbackId = 0, timerId = 0, draws = 0;
  const element = id => {
    if (!elements.has(id)) elements.set(id, {value:'', checked:true, children:[], videoWidth:640,
      append(p){this.children.push(p);},
      requestVideoFrameCallback(fn){callbacks.set(++callbackId, fn);return callbackId;},
      cancelVideoFrameCallback(id){callbacks.delete(id);},
    });
    return elements.get(id);
  };
  const node = () => ({port:{}, gain:{value:1}, connect(){return this;}, disconnect(){}});
  const track = {enabled:true, stop(){}};
  class Socket {
    constructor(){this.readyState=0;this.bufferedAmount=0;this.sent=[];sockets.push(this);}
    send(data){this.sent.push(JSON.parse(data));}
    close(){this.readyState=3;}
  }
  class Context {
    constructor(){this.state='running';this.currentTime=0;this.audioWorklet={addModule:async()=>{}};}
    async resume(){} async close(){this.state='closed';}
    createMediaStreamSource(){return node();} createGain(){return node();}
  }
  vm.runInNewContext(code, {
    document:{getElementById:element, createElement:tag=>tag==='canvas'?{
      getContext:()=>({drawImage(){draws++;}}),toDataURL:()=> 'data:image/jpeg;base64,fake',
    }:{}},
    window:{addEventListener(){}}, location:{protocol:'http:',host:'localhost'},
    navigator:{mediaDevices:{getUserMedia:async()=>({getTracks:()=>[track],getVideoTracks:()=>[track]})}},
    AudioContext:Context, AudioWorkletNode:function(){return node();}, WebSocket:Socket,
    setInterval(fn){intervals.set(++timerId,fn);return timerId;},clearInterval(id){intervals.delete(id);},
  });
  await element('start').onclick();
  const ws=sockets[0];ws.readyState=1;ws.onopen();
  return {element, ws, callbacks, draws:()=>draws,
    tick(){for(const fn of intervals.values())fn();},
    ticket(request){ws.onmessage({data:JSON.stringify({type:'frame_ticket',ticket:'one-use',request_id:request.request_id})});},
    frame(){for(const [id,fn] of [...callbacks]){callbacks.delete(id);fn();}},
  };
}

test('capture waits for ticket and then a new video frame; only one request pending', async()=>{
  const c=await client();c.tick();c.tick();
  const requests=c.ws.sent.filter(m=>m.type==='frame_request');
  assert.equal(requests.length,1);assert.equal(c.draws(),0);
  c.ticket(requests[0]);c.tick();assert.equal(c.draws(),0);
  c.frame();assert.equal(c.draws(),1);
  assert.equal(c.ws.sent.at(-1).ticket,'one-use');assert.equal(c.ws.sent.at(-1).type,'frame');
  c.tick();assert.equal(c.ws.sent.filter(m=>m.type==='frame_request').length,2);
  await c.element('stop').onclick();
});

test('camera off and reset cancel capture and reject delayed tickets', async()=>{
  const c=await client();c.tick();const old=c.ws.sent.at(-1);c.ticket(old);
  assert.equal(c.callbacks.size,1);
  c.element('camera').checked=false;c.element('camera').onchange();
  assert.equal(c.callbacks.size,0);c.ticket(old);c.frame();assert.equal(c.draws(),0);
  c.element('camera').checked=true;c.element('camera').onchange();c.tick();
  const pending=c.ws.sent.at(-1);c.element('reset').onclick();c.ticket(pending);
  assert.equal(c.callbacks.size,0);c.tick();const current=c.ws.sent.at(-1);
  c.ticket(pending);assert.equal(c.callbacks.size,0);
  c.ticket(current);c.frame();assert.equal(c.draws(),1);
  await c.element('stop').onclick();
});

test('camera send is bounded by transport backlog', async()=>{
  const c=await client();c.tick();c.ticket(c.ws.sent.at(-1));
  c.ws.bufferedAmount=64001;c.frame();
  assert.equal(c.ws.readyState,3);assert.equal(c.ws.sent.filter(m=>m.type==='frame').length,0);
});
