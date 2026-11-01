/** The launch gate: the lock screen and the dialog that edits it. */
export default {
  /** Lock screen hints, depending on which factors are demanded. */
  hintAny: 'Either the launch password or a code gets you in',
  hintBoth: 'Both the launch password and a code are required',
  hintTotp: 'Enter the 6-digit code from your authenticator app',
  hintPassword: 'Enter the launch password to unlock',

  password: 'Launch password',
  currentPassword: 'Current password',
  codePlaceholder: '6-digit code',
  newPasswordHint: 'New password, at least {min} characters',

  unlock: 'Unlock',
  verifying: 'Verifying…',
  tooManyAttempts: 'Too many attempts; try again in {seconds}s',

  titleTotp: 'Bind a TOTP authenticator',
  titleDisable: 'Turn off the launch lock',
  titleChangePassword: 'Change the launch password',
  titleSetPassword: 'Set a launch password',

  totpScanHint: 'Scan or import the otpauth link below in your authenticator app, then enter the 6-digit code it shows.',
  generatingSecret: 'Generating a key…',
  pasteSecret: 'Paste a Base32 key or an otpauth link',
  existingHint: 'Use the key you already have in your authenticator; after binding, both sides show the same codes.',
  regenerate: 'Use another key',
  useNew: 'Use the newly generated key',
  alreadyHave: 'Already in your authenticator?',

  factorPassword: 'password',
  disableWarning: 'The {factors} will be removed too; verify the factors that are enabled first.',
  /** Joins two factor names in a sentence: "password and TOTP". */
  factorJoin: ' and ',
  passwordChangedHint: 'Applies on the next launch; the current session stays unlocked.',

  confirmBind: 'Bind',
  confirmChange: 'Change',
  confirmEnable: 'Enable',
}
