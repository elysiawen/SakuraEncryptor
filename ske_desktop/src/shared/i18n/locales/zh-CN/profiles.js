/** Cloud profiles: one server account plus its own encryption password. */
export default {
  title: '云端配置',
  subtitle: '每个配置是一套 AList 或 OpenList 账号加一套独立的加密密码，可随时切换。',
  new: '新建配置',

  emptyTitle: '还没有云端配置',
  emptyDesc: '添加 AList 或 OpenList 服务器地址与账号后，就能浏览加密目录并直接播放。密码会通过系统钥匙串加密保存。',
  emptyAction: '添加第一个配置',

  current: '当前使用',
  insecureBadge: '钥匙串不可用',
  insecureTitle: '系统钥匙串不可用，密码以弱保护形式保存',

  server: '服务端',
  user: '用户',
  notFilled: '未填写',
  loginSaved: '登录密码已保存',
  loginUnsaved: '登录密码未保存',
  keySaved: '加密密码已保存',
  keyUnsaved: '加密密码未保存',
  rootDir: '根目录',

  inUse: '使用中',
  switchTo: '切换到此配置',
  edit: '编辑',
  delete: '删除',

  removeTitle: '删除云端配置',
  removeDesc: '删除「{name}」？只会移除本地保存的账号与密码，云端文件不受影响。',

  savedCreated: '配置已添加',
  savedUpdated: '配置已更新',
  switched: '已切换到「{name}」',
  removed: '配置已删除',

  /** The editor. */
  dialogTitleEdit: '编辑配置',
  dialogTitleNew: '新建配置',
  dialogDesc: '每个配置是一套 AList / OpenList 账号加一套独立的加密密码。',
  nameField: '配置名称',
  namePlaceholder: '例如 家里 NAS',
  providerField: '服务端类型',
  otpHint: '账号若开启了两步验证，连接时会提示输入动态验证码。',
  serverField: '服务器地址',
  usernameField: '用户名',
  usernamePlaceholder: '{provider} 用户名',
  passwordField: '{provider} 密码',
  passwordPlaceholder: '{provider} 登录密码',
  savedKeep: '已保存，留空则不修改',
  passwordHint: '使用系统钥匙串加密保存，不会明文落盘。',
  skePasswordField: '媒体加密密码',
  skePlaceholder: '解密 .ske 文件用的密码',
  basePathField: '根目录（可选）',
  remember: '记住密码（关闭后每次连接都需要重新输入）',
  saving: '保存中…',
  save: '保存',
}
