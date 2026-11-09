'use strict';
/** AES-256-GCM encrypted file vault. No plaintext data is persisted by this module. */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

class EncryptedVault {
  constructor(directory, keyHex) {
    if (!path.isAbsolute(directory)) throw new Error('Vault directory must be absolute');
    if (!/^[a-fA-F0-9]{64}$/.test(String(keyHex || ''))) {
      throw new Error('Missing Android Keystore-unwrapped 256-bit key');
    }
    this.directory = directory;
    this.key = Buffer.from(keyHex, 'hex');
    this.serial = new Map();
    fs.mkdirSync(directory, {recursive:true, mode:0o700});
    fs.chmodSync(directory, 0o700);
  }
  filename(name) {
    if (typeof name !== 'string' || name.length < 1 || name.length > 280) {
      throw new Error('Invalid vault entry name');
    }
    return path.join(this.directory, crypto.createHash('sha256').update(name).digest('hex') + '.enc');
  }
  lock(name, work) {
    const earlier = this.serial.get(name) || Promise.resolve();
    const current = earlier.catch(()=>{}).then(work);
    this.serial.set(name, current);
    current.finally(() => {
      if (this.serial.get(name) === current) this.serial.delete(name);
    }).catch(()=>{});
    return current;
  }
  async exists(name) {
    try { await fs.promises.access(this.filename(name)); return true; }
    catch(e) {
      if (e.code === 'ENOENT') return false;
      throw e;
    }
  }
  async read(name) {
    return this.lock(name, async()=>{
      let data;
      try { data = await fs.promises.readFile(this.filename(name)); }
      catch(e) {if(e.code === 'ENOENT') return null; throw e;}
      if (data.length < 4 + 12 + 16 || data.toString('ascii',0,4) !== 'DBV1')
        throw new Error('Encrypted vault entry corrupted');
      const iv = data.subarray(4,16);
      const tag = data.subarray(16,32);
      const ciphertext = data.subarray(32);
      const decoder = crypto.createDecipheriv('aes-256-gcm',this.key,iv);
      decoder.setAAD(Buffer.from(name,'utf8'));
      decoder.setAuthTag(tag);
      try{return Buffer.concat([decoder.update(ciphertext),decoder.final()]).toString('utf8');}
      catch{throw new Error('Encrypted vault authentication failed');}
    });
  }
  async write(name, text) {
    return this.lock(name, async()=>{
      const iv = crypto.randomBytes(12);
      const encoder = crypto.createCipheriv('aes-256-gcm',this.key,iv);
      encoder.setAAD(Buffer.from(name,'utf8'));
      const encrypted = Buffer.concat([encoder.update(Buffer.from(text,'utf8')),encoder.final()]);
      const payload=Buffer.concat([Buffer.from('DBV1'),iv,encoder.getAuthTag(),encrypted]);
      const destination=this.filename(name);
      const temp=destination+'.'+crypto.randomBytes(8).toString('hex')+'.tmp';
      try{
        const fd=await fs.promises.open(temp,'wx',0o600);
        try{await fd.writeFile(payload);await fd.sync();}
        finally{await fd.close();}
        await fs.promises.rename(temp,destination);
      } finally {
        await fs.promises.unlink(temp).catch(()=>{});
      }
    });
  }
  async remove(name) {
    return this.lock(name,async()=>{
      await fs.promises.unlink(this.filename(name)).catch(e=>{
        if(e.code!=='ENOENT')throw e;
      });
    });
  }
}
module.exports={EncryptedVault};
