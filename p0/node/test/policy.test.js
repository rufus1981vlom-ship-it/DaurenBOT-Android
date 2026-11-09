const test = require('node:test');
const assert = require('node:assert/strict');
const {normalizePhone, chatType, mayAutoReply} = require('../policy');
test('phone validation', () => {
 assert.equal(normalizePhone('+7 (701) 123-45-67'), '77011234567');
 assert.throws(()=>normalizePhone('abc'), /Некорректный/);
});
test('group and unknown LID are never automatically answered', () => {
 assert.equal(chatType('120363012345678901@g.us'), 'group');
 assert.equal(chatType('12345678901234@lid'), 'unverified-lid');
 assert.equal(mayAutoReply('12345678901234@lid', null, new Set()), false);
 assert.equal(mayAutoReply('120363012345678901@g.us', null, new Set()), false);
});
test('protected verified aliases still block LID', () => {
 const blocked = new Set(['77011234567']);
 assert.equal(mayAutoReply('12345678901234@lid','77011234567',blocked), false);
 assert.equal(mayAutoReply('12345678901234@lid','77019876543',blocked), true);
});
test('personal chats are classifiable but outgoing AI is disabled in P0',()=>{
 assert.equal(chatType('77011234567@s.whatsapp.net'), 'private');
 assert.equal(chatType('123@newsletter'), 'unknown');
});
