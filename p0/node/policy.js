'use strict';
const PHONE = /^[1-9][0-9]{7,14}$/;
function normalizePhone(value) {
  const digits = String(value || '').replace(/[+ ()-]/g, '');
  if (!PHONE.test(digits)) throw new Error('Некорректный номер: 8–15 цифр');
  return digits;
}
function chatType(jid) {
  if (typeof jid !== 'string') return 'unknown';
  if (/^[0-9]{5,30}(-[0-9]+)?@g\.us$/.test(jid)) return 'group';
  if (/^[0-9]{5,30}@lid$/.test(jid)) return 'unverified-lid';
  if (/^[0-9]{8,15}@s\.whatsapp\.net$/.test(jid)) return 'private';
  if (/^[0-9]{8,15}@c\.us$/.test(jid)) return 'private';
  return 'unknown';
}
function mayAutoReply(jid, confirmedAlias, blocked) {
  if (chatType(jid) === 'group' || chatType(jid) === 'unknown') return false;
  if (chatType(jid) === 'unverified-lid' && !confirmedAlias) return false;
  const number = confirmedAlias || String(jid).split('@')[0];
  return Boolean(number && !blocked.has(number));
}
module.exports = { normalizePhone, chatType, mayAutoReply };
