/**
 * The local library: a folder on disk, unlocked with its own password.
 *
 * The verbs it shares with the cloud library live in `files`.
 */
export default {
  unlockTitle: '解锁本地库',
  unlockDesc: '本地文件用一套独立的密码加密，与云端配置无关。密码只保留在内存中，重启应用后需要重新输入。',
  passwordPlaceholder: '本地加密密码',
  verifying: '验证中…',
  unlock: '解锁',

  noRoot: '未选择目录',
  pickDir: '选择目录',
  lock: '锁定',

  emptyRootTitle: '选择一个本地目录',
  emptyRootDesc: '直接读取磁盘上的加密文件与普通文件，不需要上传，也不需要浏览器授权。',
  emptyRootAction: '选择文件夹',
  readFailed: '无法读取目录',
  emptyDirDesc: '用加密工作台把文件加密到此处，它们就会出现在这里。',

  mkdirDesc: '名称默认加密成 token 写进目录；取消勾选则使用明文名称。',

  removeOne: '「{name}」将从磁盘删除，此操作不可撤销。',
  removeMany: '选中的 {count} 项将从磁盘删除，此操作不可撤销。',

  notFound: '该目录中没有找到 {file}',
  unlocked: '本地库已解锁',
  needUnlock: '请先解锁本地库',
  lockedToast: '本地库已锁定',
  decryptFailed: '该文件无法解密，请检查密码',
  unsupported: '暂不支持播放该类型的文件',

  nothingDownloadable: '选中的项目里没有可下载的加密文件',
}
