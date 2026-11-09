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
const {normalizePhone, chatType} = require('./policy');

const root = process.env.DAURENBOT_DATA_DIR;
if (!root || !path.isAbsolute(root)) throw new Error('Private data directory missing');
fs.mkdirSync(root, {recursive:true, mode:0o700});
const socketPath = path.join(root, 'control.sock');
const authDir = path.join(root, 'baileys-auth');
const status = {
  stage:'node-ready', connected:false, registered:false, pairingCode:null,
  node:process.version, webSocket:typeof WebSocket==='function',
  crypto:typeof crypto.randomBytes==='function', tls:typeof tls.connect==='function',
  tmpdir:os.tmpdir(), incomingCount:0, lastIncomingType:null,
  lastError:null, repliesEnabled:false, authenticationEncrypted:false
};
let wa=null;
let logger=null;
let pairingRequested=false;
let reconnectTimer=null;
let ownerPhone='';

function setStage(next, error) {
  status.stage=next;
  status.lastError=error?String(error).slice(0,200):null;
}
function statusSnapshot(){return {...status, pairingCode:status.pairingCode};}
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
  if (wa && status.stage!=='disconnected' && status.stage!=='error') return {ok:true, status:statusSnapshot()};
  ownerPhone=validated;
  status.pairingCode=null;
  pairingRequested=false;
  setStage('loading-baileys');
  let baileys;
  try {
    baileys=await import('@whiskeysockets/baileys');
    logger=require('pino')({level:'silent'});
  } catch(e) {
    throw new Error('Cannot load Baileys dependencies: '+e.message);
  }

  const makeWASocket=baileys.default;
  const {useMultiFileAuthState, DisconnectReason} = baileys;
  if (typeof makeWASocket!=='function' || typeof useMultiFileAuthState!=='function')
     throw new Error('Incompatible Baileys API');
  const {state,saveCreds}=await useMultiFileAuthState(authDir);
  wa=makeWASocket({
    auth:state, logger, browser:['DaurenBOT','Android','11.0'],
    printQRInTerminal:false, syncFullHistory:false, markOnlineOnConnect:false,
    getMessage:async()=>undefined
  });
  status.registered=Boolean(state.creds.registered);
  status.connected=false;
  setStage('connecting');

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
    if(connection==='open') {
      status.connected=true;
      status.registered=true;
      status.pairingCode=null;
      setStage('connected');
    }
    if(connection==='close') {
      status.connected=false;wa=null;
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
    if(qr && !state.creds.registered && !pairingRequested) {
      pairingRequested=true;
      (async()=>{
        try {
          await delay(2000);
          if(!wa) return;
          status.pairingCode=await wa.requestPairingCode(validated);
          setStage('pairing-code-issued');
        } catch(e) {setStage('pairing-error',e.message);}
      })();
    }
  });
  return {ok:true,status:statusSnapshot()};
}

async function execute(command) {
  if(!command || typeof command!=='object') throw new Error('Invalid IPC request');
  switch(command.cmd){
    case 'status': return {ok:true, status:statusSnapshot()};
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
