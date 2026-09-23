// What the assistant says when the phone hands control back at a credential/payment boundary (ScreenGuard.kt).
// One line per kind so the user knows exactly what is theirs to do; nothing here is ever automated.
const LINES = {
  payment: "You're at the payment step. Your turn: I don't touch payments, so please review and pay yourself.",
  otp: "This screen is asking for a code. Your turn: please enter it yourself, I never handle OTPs.",
  login: 'The app wants you to log in first. Your turn: please sign in yourself, then ask me again.',
  password: 'This screen is asking for a password. Your turn: please enter it yourself, I never handle passwords.',
};

export const HANDOVER_KINDS = Object.keys(LINES);
export const handoverLine = (kind) => LINES[kind] ?? LINES.password;
