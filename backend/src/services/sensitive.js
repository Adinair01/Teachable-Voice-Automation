// Single source of truth for "needs spoken/tapped confirmation before this runs".
const SENSITIVE = /\b(pay|payment|place order|order now|checkout|buy|purchase|confirm order|send|submit|transfer|delete|remove|uninstall|book|proceed to pay)\b/i;
const CREDENTIAL = /\b(password|passcode|pin|otp|cvv|card number)\b/i;

export const isSensitiveText = (s = '') => SENSITIVE.test(s);
export const looksLikeCredentialField = (s = '') => CREDENTIAL.test(s);

export function isSensitiveStep(step) {
  if (step.type !== 'tap') return false;
  const { text = '', contentDesc = '', resourceId = '' } = step.selector ?? {};
  return [text, contentDesc, resourceId].some(isSensitiveText);
}
