'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const crypto=require('node:crypto');
const {EncryptedVault}=require('../vault');
const {createSecureAuthState}=require('../secure-auth');

function temporary(t) {
 const directory=fs.mkdtempSync(path.join(os.tmpdir(),'dauren-v11-p1-'));
 t.after(()=>fs.rmSync(directory,{recursive:true,force:true}));
 return directory;
}
function makeFakeBaileys(){
 return {
  BufferJSON:{replacer:(_,x)=>x,reviver:(_,x)=>x},
  proto:{Message:{AppStateSyncKeyData:{fromObject:x=>x}}},
  initAuthCreds:()=>({registered:false})
 };
}
test('encrypted vault writes no plaintext and restores state',async t=>{
 const directory=temporary(t);
 const vault=new EncryptedVault(path.join(directory,'vault'),crypto.randomBytes(32).toString('hex'));
 await vault.write('creds.json','{\"secret\":\"critical-key\",\"registered\":true}');
 const stored=fs.readFileSync(vault.filename('creds.json'));
 assert.equal(stored.includes(Buffer.from('critical-key')),false);
 assert.equal(await vault.read('creds.json'),'{"secret":"critical-key","registered":true}');
 assert.equal(stored.toString('ascii',0,4),'DBV1');
});
test('authentication associated data prevents swapping credential records',async t=>{
 const directory=temporary(t);
 const vault=new EncryptedVault(directory,crypto.randomBytes(32).toString('hex'));
 await vault.write('a.json','alpha');
 await vault.write('b.json','bravo');
 const first=fs.readFileSync(vault.filename('a.json'));
 fs.copyFileSync(vault.filename('b.json'),vault.filename('a.json'));
 await assert.rejects(vault.read('a.json'),/authentication failed/i);
 fs.writeFileSync(vault.filename('a.json'),first);
 assert.equal(await vault.read('a.json'),'alpha');
});
test('wrong key is rejected rather than silently starting a new session',async t=>{
 const directory=temporary(t);
 const authDirectory=path.join(directory,'auth');
 const original=new EncryptedVault(authDirectory,crypto.randomBytes(32).toString('hex'));
 await original.write('creds.json','registered');
 const replacement=new EncryptedVault(authDirectory,crypto.randomBytes(32).toString('hex'));
 await assert.rejects(replacement.read('creds.json'),/authentication failed/i);
});
test('concurrent writes to a single entry remain valid',async t=>{
 const directory=temporary(t),vault=new EncryptedVault(directory,crypto.randomBytes(32).toString('hex'));
 await Promise.all(Array.from({length:18},(_,i)=>vault.write('creds.json','value-'+i)));
 assert.equal(await vault.read('creds.json'),'value-17');
});
test('migrates P0 plaintext auth once and retains existing registration',async t=>{
 const directory=temporary(t);
 const legacy=path.join(directory,'baileys-auth');
 fs.mkdirSync(legacy);
 const old=JSON.stringify({registered:true,me:{id:'77019990000@s.whatsapp.net'}});
 fs.writeFileSync(path.join(legacy,'creds.json'),old);
 fs.writeFileSync(path.join(legacy,'session-test.json'),JSON.stringify({key:'test'}));
 const key=crypto.randomBytes(32).toString('hex');
 const prior=process.env.DAURENBOT_AUTH_SECRET_HEX;
 process.env.DAURENBOT_AUTH_SECRET_HEX=key;
 t.after(()=>{ if(prior===undefined)delete process.env.DAURENBOT_AUTH_SECRET_HEX; else process.env.DAURENBOT_AUTH_SECRET_HEX=prior;});
 const initial=await createSecureAuthState(directory,makeFakeBaileys());
 assert.equal(initial.state.creds.registered,true);
 assert.equal(initial.migrated,2);
 assert.equal(fs.existsSync(path.join(legacy,'creds.json')),false);
 assert.equal((await initial.state.keys.get('session',['test'])).test.key,'test');
 initial.state.creds.note='retained';
 await initial.saveCreds();
 const later=await createSecureAuthState(directory,makeFakeBaileys());
 assert.equal(later.state.creds.note,'retained');
 assert.equal(later.migrated,0);
});
test('conflicting encrypted and P0 credential state fails closed',async t=>{
 const directory=temporary(t);
 const key=crypto.randomBytes(32).toString('hex');
 const prior=process.env.DAURENBOT_AUTH_SECRET_HEX;
 process.env.DAURENBOT_AUTH_SECRET_HEX=key;
 t.after(()=>{ if(prior===undefined)delete process.env.DAURENBOT_AUTH_SECRET_HEX; else process.env.DAURENBOT_AUTH_SECRET_HEX=prior;});
 const vault=new EncryptedVault(path.join(directory,'baileys-auth-enc'),key);
 await vault.write('creds.json',JSON.stringify({registered:false}));
 const old=path.join(directory,'baileys-auth');fs.mkdirSync(old);
 fs.writeFileSync(path.join(old,'creds.json'),JSON.stringify({registered:true}));
 await assert.rejects(createSecureAuthState(directory,makeFakeBaileys()),/state conflict/i);
 assert.equal(fs.existsSync(path.join(old,'creds.json')),true);
});
