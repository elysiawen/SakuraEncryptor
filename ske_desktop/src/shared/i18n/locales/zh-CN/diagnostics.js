/**
 * The playback diagnostics page.
 *
 * The log lines are user-visible text, so they are translated too — but the
 * values inside them (durations, MIME types, bytes) stay as produced.
 */
export default {
  title: '播放诊断',
  subtitle: '直接对分块解密端点做一次真实播放与跳转，用于验证当前机器的解码能力。',
  back: '返回',

  file: '加密文件',
  pickFile: '选择文件',
  targetPlaceholder: '本地绝对路径，或 https:// 开头的远端地址',
  password: '密码',
  passwordPlaceholder: '加密时使用的密码',
  displayName: '解密后的文件名',
  displayNamePlaceholder: '例如 movie.mp4 —— 决定播放器使用的 MIME 类型',
  open: '打开并播放',
  decrypting: '解密中…',

  rangeTitle: 'Range / Seek 诊断',
  runTest: '开始测试',
  testing: '测试中…',
  logEmpty: '打开文件后运行诊断，可验证分块解密与任意跳转是否正常。',

  needInput: '请先填写文件与密码',
  openFailed: '打开失败：{error}',
  sessionOpened: '已建立解密会话：{size}，MIME {mime}',
  plainPassthrough: '该文件不是 .ske 容器，按明文直通：{size}，MIME {mime}',
  timeout: '{event} 超时',
  unknown: '未知',

  noDuration: '无法获知时长，跳过 seek 测试（容器或编码不被当前机器支持）。',
  needExternal: '结论：需要外部播放器后端。',
  seekOk: 'seek → {at}s 成功，用时 {ms}ms（实际 {actual}s）',
  seekFail: 'seek → {at}s 失败或超时',
  randomOk: '随机 seek {count}/8 次成功，平均 {avg}ms，最慢 {max}ms',
  randomFail: '随机 seek 全部失败。',
  verdictOk: '结论：原生 video 元素可播放该容器，分块解密与跳转正常。',
  verdictBad: '结论：原生 video 元素对该容器支持不足，需要 remux 或外接播放器后端。',
  interrupted: '诊断中断：{error}',
}
