'use strict';
/**
 * DaurenBOT v11 P0 embedded engine.
 * Deliberately NO automatic AI replies or report scheduling until device pairing,
 * @lid resolution, owner protection and keystore integration pass physical testing.
 */
const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const os = require('node:os');
const crypto = require('node:crypto');
const tls = require('node:tls');
const dns = require('node:dns').promises;
const https = require('node:https');
const {normalizePhone, chatType} = require('./policy');

const root = process.env.DAURENBOT_DATA_DIR;
if (!root || !path.isAbsolute(root)) throw new Error('Private data directory missing');
fs.mkdirSync(root, {recursive:true, mode:0o700});
const socketPath = path.join(root, 'control.sock');
const {createSecureAuthState}=require('./secure-auth');
const status = {
  stage:'node-ready', connected:false, registered:false, pairingCode:null,
  node:process.version, webSocket:typeof WebSocket==='function',
  crypto:typeof crypto.randomBytes==='function', tls:typeof tls.connect==='function',
  tmpdir:os.tmpdir(), incomingCount:0, lastIncomingType:null,
  lastError:null, repliesEnabled:false, authenticationEncrypted:false,
  socketOpen:false, qrEvents:0, updateCount:0, lastUpdateAt:null,
  connectStartedAt:null, pairingAttempts:0, diagnosis:'not-run',
  diagnosticEvents:[]
};
let wa=null;
let logger=null;
let pairingRequested=false;
let reconnectTimer=null;
let ownerPhone='';
let requestPairingFromControl=async()=>{throw new Error('Нет активного запроса');};
let pairingTimer=null;
let timeoutTimer=null;
let pairingPromise=null;
let socketGeneration=0;
const history=[];
function trace(event, detail) {
  const item={at:new Date().toISOString(),event:String(event).slice(0,50)};
  if(detail) item.detail=String(detail).slice(0,180);
  history.push(item);
  if(history.length>14)history.shift();
  status.diagnosticEvents=history.slice(-8);
}
function errLabel(e) {
  const msg=String(e?.message||e||'').replace(/\b\d{8,15}\b/g,'[phone]');
  return msg.slice(0,180);
}
function withTimeout(promise,ms,label){
  let timer;
  return Promise.race([promise,new Promise((_,reject)=>{
    timer=setTimeout(()=>reject(new Error(label+' exceeded '+ms+'ms')),ms);
  })]).finally(()=>clearTimeout(timer));
}
async function networkProbe(){
  const output={dns:'checking',https:'checking',socketOpen:Boolean(wa?.ws?.isOpen)};
  try{await withTimeout(dns.lookup('web.whatsapp.com'),6000,'DNS');output.dns='ok';}
  catch(e){output.dns=errLabel(e);}
  try{
    output.https=await withTimeout(new Promise((resolve,reject)=>{
      const req=https.request('https://web.whatsapp.com/',{method:'HEAD',timeout:8000},res=>{
        res.resume();resolve('HTTP '+res.statusCode);
      });
      req.on('timeout',()=>req.destroy(new Error('HTTPS timeout')));
      req.on('error',reject);req.end();
    }),10000,'HTTPS');
  }catch(e){output.https=errLabel(e);}
  return output;
}


function setStage(next, error) {
  status.stage=next;
  status.lastError=error?errLabel(error):null;
  trace('stage',next+(error?': '+errLabel(error):''));
}
function statusSnapshot(){
  const age=status.connectStartedAt?Date.now()-status.connectStartedAt:null;
  return {...status, pairingCode:status.pairingCode,
    elapsedSeconds:age==null?null:Math.floor(age/1000),socketOpen:Boolean(wa?.ws?.isOpen)};
}
function delay(ms){return new Promise(resolve=>setTimeout(resolve,ms));}
function safeSend(sock, object) {
  if (!sock.destroyed) sock.end(JSON.stringify(object)+'\n');
}
function fail(error) {
  const text=error instanceof Error?error.message:String(error);
  setStage('error',text);
  return {ok:false,error:text.slice(0,250)};
}

async function connectWhatsApp(phone) {
  const validated=normalizePhone(phone);
  if (wa)
    return {ok:true, status:statusSnapshot()};
  ownerPhone=validated;
  socketGeneration++;
  const generation=socketGeneration;
  if(pairingTimer)clearTimeout(pairingTimer);
  if(timeoutTimer)clearTimeout(timeoutTimer);
  status.connectStartedAt=Date.now();
  status.lastUpdateAt=null;
  status.qrEvents=0;
  status.updateCount=0;
  status.pairingAttempts=0;
  status.pairingCode=null;
  pairingRequested=false;
  pairingPromise=null;
  setStage('loading-baileys');
  let baileys;
  try {
    baileys=await import('@whiskeysockets/baileys');
    logger=require('pino')({level:'silent'});
  } catch(e) {
    throw new Error('Cannot load Baileys dependencies: '+e.message);
  }

  const makeWASocket=baileys.default;
  const {DisconnectReason, Browsers} = baileys;
  if (typeof makeWASocket!=='function') throw new Error('Incompatible Baileys API');
  const encrypted=await createSecureAuthState(root,baileys);
  const {state,saveCreds}=encrypted;
  status.authenticationEncrypted=true;
  trace('secure-auth','AES-GCM; migrated='+encrypted.migrated);
  wa=makeWASocket({
    auth:state, logger,
    // WhatsApp Web companion platform, not unsupported 'Android browser' emulation
    browser: typeof Browsers?.ubuntu === 'function' ? Browsers.ubuntu('Chrome') : ['Ubuntu','Chrome','22.04.4'],
    printQRInTerminal:false, syncFullHistory:false, markOnlineOnConnect:false,
    getMessage:async()=>undefined
  });
  status.registered=Boolean(state.creds.registered);
  status.connected=false;
  setStage('connecting');
  trace('socket-created', 'registered='+status.registered);

  async function requestCode(reason) {
    if(generation!==socketGeneration || !wa || status.registered || pairingRequested || status.connected) return;
    if(!wa.ws?.isOpen){trace('pairing-wait','WebSocket not open');return;}
    pairingRequested=true;
    status.pairingAttempts++;
    setStage('requesting-pairing-code');
    trace('pairing-request',reason);
    try{
      const active=wa;
      const code=await withTimeout(active.requestPairingCode(validated),16000,'Pairing request');
      if(generation!==socketGeneration || wa!==active) return;
      if(typeof code!=='string'||!code.trim())throw new Error('WhatsApp returned empty pairing code');
      status.pairingCode=code.trim();
      setStage('pairing-code-issued');
      trace('pairing-ready','code received (not logged)');
    }catch(e){
      if(generation!==socketGeneration)return;
      setStage('pairing-error',e);
      trace('pairing-failed',errLabel(e));
      pairingRequested=false;
    }
  }

  requestPairingFromControl=()=>requestCode('manual-retry');
  if(wa.ws && typeof wa.ws.on==='function'){
    wa.ws.on('open',()=>{if(generation===socketGeneration) trace('websocket-open');});
    wa.ws.on('error',e=>{
      if(generation!==socketGeneration)return;
      status.lastError='WebSocket: '+errLabel(e);
      trace('websocket-error',errLabel(e));
    });
    wa.ws.on('close',()=>{if(generation===socketGeneration)trace('websocket-close');});
  }
  // The QR event can be absent on some Baileys/WhatsApp versions.
  // Fallback only when WebSocket is already open; NEVER generate a fake code.
  pairingTimer=setTimeout(()=>{
    if(generation!==socketGeneration || status.connected || status.registered || status.pairingCode)return;
    if(wa?.ws?.isOpen) void requestCode('qr-event-timeout');
    else trace('pairing-wait','WebSocket did not open after 10s');
  },10000);
  timeoutTimer=setTimeout(()=>{
    if(generation!==socketGeneration||status.connected||status.pairingCode)return;
    if(status.stage==='connecting'||status.stage==='requesting-pairing-code'){
      setStage('network-timeout','Нет ответа WhatsApp. Проверь сеть, DNS, VPN и настройки энергосбережения.');
    }
  },30000);

  wa.ev.on('creds.update',saveCreds);
  wa.ev.on('messages.upsert',({messages,type})=>{
    if(type!=='notify' || !Array.isArray(messages)) return;
    for(const msg of messages) {
      if (!msg || msg.key?.fromMe) continue;
      status.incomingCount++;
      status.lastIncomingType=chatType(msg.key?.remoteJid||'');
      // Never save incoming message text in P0.
    }
  });
  wa.ev.on('connection.update',({connection,qr,lastDisconnect})=>{
    if(generation!==socketGeneration)return;
    status.updateCount++;
    status.lastUpdateAt=new Date().toISOString();
    trace('wa-update',connection|| (qr?'qr':'handshake'));
    if(connection==='open') {
      status.connected=true;
      status.registered=true;
      status.pairingCode=null;
      if(pairingTimer)clearTimeout(pairingTimer);
      if(timeoutTimer)clearTimeout(timeoutTimer);
      setStage('connected');
    }
    if(connection==='close') {
      status.connected=false;wa=null;
      if(pairingTimer)clearTimeout(pairingTimer);
      if(timeoutTimer)clearTimeout(timeoutTimer);
      const code=lastDisconnect?.error?.output?.statusCode;
      if(code===DisconnectReason?.loggedOut) {
        setStage('logged-out','WhatsApp ended the linked session');
        ownerPhone='';
      } else {
        setStage('disconnected',lastDisconnect?.error?.message||'Connection closed');
        if(ownerPhone && !reconnectTimer) {
          reconnectTimer=setTimeout(()=>{
            reconnectTimer=null;
            connectWhatsApp(ownerPhone).catch(e=>setStage('error',e.message));
          },8000);
        }
      }
    }
    if(qr && !state.creds.registered) {
      status.qrEvents++;
      // Delay slightly after QR signal to let the Noise handshake settle.
      setTimeout(()=>void requestCode('qr-ready'),1100);
    }
  });
  return {ok:true,status:statusSnapshot()};
}

async function execute(command) {
  if(!command || typeof command!=='object') throw new Error('Invalid IPC request');
  switch(command.cmd){
    case 'status': return {ok:true, status:statusSnapshot()};
    case 'diagnose': return {ok:true, network:await networkProbe(),status:statusSnapshot()};
    case 'retry-pairing':{
      if(!wa || status.connected || status.registered)
        throw new Error('Для повтора сначала начни подключение');
      if(pairingRequested)throw new Error('Запрос кода уже выполняется');
      if(!wa.ws?.isOpen)throw new Error('WebSocket пока закрыт — используй диагностику сети');
      await requestPairingFromControl();
      return {ok:true,status:statusSnapshot()};
    }
    case 'connect': return await connectWhatsApp(command.phone);
    case 'send': {
      if(!status.connected || !wa) throw new Error('WhatsApp not connected');
      if(command.confirmed!==true) throw new Error('Manual confirmation required');
      const recipient=normalizePhone(command.to);
      const text=String(command.text||'').trim();
      if(!text || text.length>1000) throw new Error('Text should contain 1–1000 chars');
      const jid=recipient+'@s.whatsapp.net';
      const sent=await wa.sendMessage(jid,{text});
      // An API acknowledgement is not equivalent to delivery on recipient's device.
      return {ok:true,submittedId:sent?.key?.id||null,deliveryConfirmed:false};
    }
    default: throw new Error('Unknown IPC command');
  }
}

if(fs.existsSync(socketPath)) fs.unlinkSync(socketPath);
const server=net.createServer(sock=>{
  sock.setTimeout(15000);
  let input='';
  sock.on('data',async buffer=>{
    input+=buffer.toString('utf8');
    if(input.length>4096){safeSend(sock,{ok:false,error:'Input too long'});return;}
    const nl=input.indexOf('\n');
    if(nl<0)return;
    const line=input.slice(0,nl);
    input='';
    try {safeSend(sock,await execute(JSON.parse(line)));}
    catch(e){safeSend(sock,fail(e));}
  });
  sock.on('timeout',()=>sock.destroy());
  sock.on('error',()=>{});
});
server.on('error',err=>{setStage('ipc-error',err.message);});
server.listen(socketPath,()=>{
  fs.chmodSync(socketPath,0o600);
  status.stage='ready';
});
