/**
 * The cloud library. Verbs it shares with the local library live in `files`;
 * what is here is specific to talking to an AList / OpenList server.
 */
export default {
  otpTitle: '两步验证',
  otpDesc: '该账号已开启两步验证，请输入身份验证器中的动态验证码。',
  otpPlaceholder: '动态验证码',
  otpConfirm: '验证并连接',
  otpInvalid: '验证码无效，请重试',

  noProfileTitle: '尚未选择云端配置',
  noProfileDesc: '云端库通过 AList / OpenList 读取加密目录。添加一套账号后即可在应用内浏览并直接播放，密码不会离开本机。',
  noProfileAction: '前往配置',

  starting: '正在启动内置服务器…',
  connecting: '正在连接 {name}…',
  connectFailed: '无法连接云端',
  enterOtp: '输入验证码',

  uploadChipTitle: '上传进度',
  uploading: '上传中 {count} 个 · {percent}%',
  itemCount: '{count} 项',
  upload: '上传',
  gridView: '网格视图',
  listView: '列表视图',
  grid: '网格',
  list: '列表',

  emptyDirDesc: '加密文件与普通文件都会出现在这里。加密条目会标注出来，只有输入正确的媒体密码才能显示文件名与内容。',
  encryptedBadge: '加密文件',
  mkdirDesc: '名称会被加密成 token 存进云端目录树；取消勾选则使用明文名称。',

  dropTitle: '松开以上传到「{path}」',
  dropHint: '确认后按你的设置加密上传',
  dragNoFiles: '拖入的内容里没有可上传的文件（文件夹不会被展开）',
  ignoredFolders: '已忽略 {count} 个文件夹',
  pickFailed: '无法打开文件选择器',
  uploadQueued: '已加入上传队列：{count} 个文件',
  uploadFailed: '{count} 个文件上传失败：{error}',
  uploadDone: '上传完成',

  /** The confirmation dialog shown before files leave the machine. */
  uploadTitle: '上传文件',
  uploadTo: '将上传到「{folder}」',
  uploadEncrypt: '加密上传（生成 .ske 容器）',
  uploadEncryptHint: '文件先在本机加密成容器再上传，云端只见到密文。',
  uploadPlainHint: '关闭后上传原始文件，云端目录里会存明文。',
  uploadAdding: '加入中…',
  uploadCount: '上传 {count} 个文件',

  nothingDownloadable: '选中的项目里没有可下载的文件',
  removeOne: '「{name}」将从云端删除，此操作不可撤销。',
  removeMany: '选中的 {count} 项将从云端删除，此操作不可撤销。',

  tagLocked: '无法解密',
  tagEncrypted: '加密',
  tagPlain: '未加密',

  /** Clicking a file the viewer cannot open. */
  playLocked: '该文件无法解密，请检查媒体加密密码',
  playUnsupported: '暂不支持播放该类型的文件',
}
