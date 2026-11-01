/** The launch gate: the lock screen and the dialog that edits it. */
export default {
  /** Lock screen hints, depending on which factors are demanded. */
  hintAny: '启动密码或验证码，任一通过即可',
  hintBoth: '需要启动密码和验证码都通过',
  hintTotp: '输入令牌应用中的 6 位验证码',
  hintPassword: '输入启动密码以解锁',

  password: '启动密码',
  currentPassword: '当前密码',
  codePlaceholder: '6 位验证码',
  newPasswordHint: '新密码，至少 {min} 位',

  unlock: '解锁',
  verifying: '验证中…',
  tooManyAttempts: '尝试过多，{seconds} 秒后可重试',

  titleTotp: '绑定 TOTP 身份验证器',
  titleDisable: '关闭启动锁',
  titleChangePassword: '修改启动密码',
  titleSetPassword: '设置启动密码',

  totpScanHint: '用验证器应用扫描或导入下面的 otpauth 链接，再输入它显示的 6 位验证码。',
  generatingSecret: '正在生成密钥…',
  pasteSecret: '粘贴 Base32 密钥或 otpauth 链接',
  existingHint: '使用验证器里已有的那个密钥，绑定后两边的验证码会一致。',
  regenerate: '换一个密钥',
  useNew: '改用新生成的密钥',
  alreadyHave: '已经在验证器里？',

  factorPassword: '密码',
  disableWarning: '{factors} 会一并清除，需要先验证当前已启用的方式。',
  /** Joins two factor names in a sentence: "密码与 TOTP". */
  factorJoin: '与',
  passwordChangedHint: '修改后下次启动生效，当前会话保持解锁。',

  confirmBind: '确认绑定',
  confirmChange: '修改',
  confirmEnable: '启用',
}
