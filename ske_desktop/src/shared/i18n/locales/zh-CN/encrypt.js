/** The encrypt workbench: file queue and the text converter. */
export default {
  title: '加密工作台',
  subtitleFiles: '文件在本地完成加密，文件名同样会被加密；拖进来即可开始。',
  subtitleText: '把一段文字加密成可复制的密文，或把密文还原成明文；与文件名使用同一套密钥。',

  tabFiles: '文件',
  tabText: '文本',
  clearFinished: '清理已完成',

  outputDir: '输出目录',
  outputDirPlaceholder: '选择一个输出目录',
  choose: '选择',
  password: '加密密码',
  passwordPlaceholder: '留空则使用当前配置的媒体密码',
  passwordHintText: '加密与解密共用这一个密码；密文可在安卓端、CLI 或网页端解密。',

  dropTitle: '拖放文件或文件夹到这里',
  dropReady: '加密后写入上面选择的目录',
  dropIdle: '先选择输出目录，然后拖入文件',
  pickFiles: '选择文件',
  pickFolder: '选择文件夹',
  dropUnreadable: '无法读取拖入的文件路径',

  queue: '任务队列',
  queueCount: '{running} 进行中 · {total} 总计',
  queueEmpty: '队列为空。单个大文件按 1 MiB 分块流式处理，内存占用不会随文件大小增长。',
  fileProgress: '{done}/{total} 个文件',

  statusScanning: '扫描中',
  statusQueued: '等待中',
  statusRunning: '加密中',
  statusDone: '完成',
  statusCancelled: '已取消',
  statusFailed: '失败',

  needOutputDir: '请先选择输出目录',
  queued: '已加入 {count} 个任务',

  content: '内容',
  contentPlaceholder: '要加密的文字，或粘贴一段密文',
  encrypt: '加密',
  decrypt: '解密',
  result: '结果',
  chars: '{count} 字符',
  copy: '复制',
  copied: '已复制到剪贴板',
  missingInput: '请先输入内容',
  convertFailed: '转换失败',
}
