'use strict';
const fs=require('node:fs');
const path=require('node:path');
const {EncryptedVault}=require('./vault');

/** Uses Baileys public auth-state data structures with per-entry encrypted storage. */
async function createSecureAuthState(dataDir,baileys) {
  const key=process.env.DAURENBOT_AUTH_SECRET_HEX;
  const vault=new EncryptedVault(path.join(dataDir,'baileys-auth-enc'),key);
  const {BufferJSON,proto,initAuthCreds}=baileys;
  if(!BufferJSON?.replacer || !BufferJSON?.reviver || !proto?.Message?.AppStateSyncKeyData || !initAuthCreds)
    throw new Error('Incompatible Baileys Auth API');

  const fromJson=text=>JSON.parse(text,BufferJSON.reviver);
  const toJson=value=>JSON.stringify(value,BufferJSON.replacer);

  async function migrateLegacy() {
    const legacy=path.join(dataDir,'baileys-auth');
    let entries;
    try{entries=await fs.promises.readdir(legacy,{withFileTypes:true});}
    catch(e){if(e.code==='ENOENT')return 0;throw e;}
    const files=entries.filter(e=>e.name.endsWith('.json'));
    for(const f of entries)if(!f.isFile())throw new Error('Non-regular entry in legacy credentials folder');
    // This stage only copies. Do not delete any plaintext until ALL files are
    // encrypted and a roundtrip read has been verified.
    for(const entry of files){
      const original=path.join(legacy,entry.name);
      const text=await fs.promises.readFile(original,'utf8');
      fromJson(text); // reject corrupt original session, never silently reset.
      const existing=await vault.read(entry.name);
      if(existing!==null && existing!==text)
        throw new Error('Auth state conflict during encrypted migration; refusing to overwrite');
      if(existing===null) await vault.write(entry.name,text);
      if((await vault.read(entry.name))!==text)
        throw new Error('Encrypted migration verification failed');
    }
    for(const entry of files){
      await fs.promises.unlink(path.join(legacy,entry.name));
    }
    await fs.promises.rmdir(legacy).catch(()=>{});
    return files.length;
  }
  const migrated=await migrateLegacy();
  async function readData(name){
    const value=await vault.read(name);
    return value===null?null:fromJson(value);
  }
  async function writeData(name,value){await vault.write(name,toJson(value));}

  const creds=await readData('creds.json')||initAuthCreds();
  return {
    migrated,
    vault,
    state:{
      creds,
      keys:{
        get:async(type,ids)=>{
          const result={};
          await Promise.all(ids.map(async id=>{
            let data=await readData(`${type}-${id}.json`);
            if(type==='app-state-sync-key'&&data){
              data=proto.Message.AppStateSyncKeyData.fromObject(data);
            }
            result[id]=data;
          }));
          return result;
        },
        set:async batch=>{
          const pending=[];
          for(const category in batch){
            for(const id in batch[category]){
              const entry=`${category}-${id}.json`;
              const value=batch[category][id];
              pending.push(value?writeData(entry,value):vault.remove(entry));
            }
          }
          await Promise.all(pending);
        }
      }
    },
    saveCreds:()=>writeData('creds.json',creds)
  };
}
module.exports={createSecureAuthState};
