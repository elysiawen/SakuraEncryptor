/** Cloud profiles: one server account plus its own encryption password. */
export default {
  title: 'Cloud profiles',
  subtitle: 'Each profile is one AList or OpenList account plus its own encryption password; switch between them at any time.',
  new: 'New profile',

  emptyTitle: 'No cloud profiles yet',
  emptyDesc: 'Add an AList or OpenList address and account to browse encrypted folders and play straight from them. Passwords are stored through the system keychain.',
  emptyAction: 'Add the first profile',

  current: 'In use',
  insecureBadge: 'No keychain',
  insecureTitle: 'The system keychain is unavailable, so the password is stored with weak protection',

  server: 'Server',
  user: 'User',
  notFilled: 'Not set',
  loginSaved: 'Login password saved',
  loginUnsaved: 'Login password not saved',
  keySaved: 'Encryption password saved',
  keyUnsaved: 'Encryption password not saved',
  rootDir: 'Root folder',

  inUse: 'In use',
  switchTo: 'Switch to this profile',
  edit: 'Edit',
  delete: 'Delete',

  removeTitle: 'Delete cloud profile',
  removeDesc: 'Delete “{name}”? Only the locally stored account and password are removed; files in the cloud are untouched.',

  savedCreated: 'Profile added',
  savedUpdated: 'Profile updated',
  switched: 'Switched to “{name}”',
  removed: 'Profile deleted',

  /** The editor. */
  dialogTitleEdit: 'Edit profile',
  dialogTitleNew: 'New profile',
  dialogDesc: 'Each profile is one AList / OpenList account plus its own encryption password.',
  nameField: 'Profile name',
  namePlaceholder: 'e.g. Home NAS',
  providerField: 'Server type',
  otpHint: 'If the account uses two-factor authentication, connecting will ask for a code.',
  serverField: 'Server address',
  usernameField: 'User name',
  usernamePlaceholder: '{provider} user name',
  passwordField: '{provider} password',
  passwordPlaceholder: '{provider} login password',
  savedKeep: 'Saved; leave empty to keep it',
  passwordHint: 'Stored through the system keychain; never written in plain text.',
  skePasswordField: 'Media encryption password',
  skePlaceholder: 'The password that decrypts .ske files',
  basePathField: 'Root folder (optional)',
  remember: 'Remember the password (with this off, every connection asks again)',
  saving: 'Saving…',
  save: 'Save',
}
