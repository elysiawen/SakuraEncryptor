/** Transport controls. Shared by the sidebar bar and the player window. */
export default {
  /** Sidebar bar: reopening the music page. */
  openPage: '打开音乐页',
  trackFallback: '音乐',
  playing: '正在播放',
  paused: '已暂停',
  stop: '停止播放',
  progress: '播放进度',
  previous: '上一首',
  next: '下一首',
  play: '播放',
  pause: '暂停',

  /** Transport details that only the standalone player window shows. */
  playlist: '播放列表',
  playlistCollapse: '收起播放列表',
  closeWindow: '关闭窗口',
  closeWindowKeepPlaying: '关闭窗口（继续播放）',
  decrypting: '正在解密…',
  empty: '暂无播放内容',
  volume: '音量',
  mute: '静音',
  unmute: '取消静音',
  playSpace: '播放（空格）',
  pauseSpace: '暂停（空格）',
  speed: '播放速度',
  fullscreen: '全屏',

  /** Play modes. `modeClick` is the tooltip wrapper around them. */
  modeList: '列表循环',
  modeOne: '单曲循环',
  modeShuffle: '随机播放',
  modeClick: '{mode}（点击切换）',

  /** Failures the window reports itself, because the shell cannot be reached. */
  bridgeOutdated: '主程序版本过旧，请完全退出应用后重新打开',
  hostNoResponse: '主窗口没有响应',
  bridgeLost: '无法连接主窗口：音轨可能已停止，或应用升级后尚未重启',
  audioFailed: '音频加载失败，文件可能已损坏或无法解密',
  videoFailed: '视频加载失败，文件可能已损坏或无法解密',
  videoCodec: '当前格式无法由内置播放器解码（常见于 avi / wmv / 老编码）',
  imageLoading: '正在解密并加载图片…',
  imageFailed: '图片加载失败，文件可能已损坏或无法解密',

  /** Decrypted play links, copied out to an external player. */
  linkCopy: '复制解密播放链接',
  linkCopied: '链接已复制，可粘贴到外部播放器；关闭应用后失效',

  /** Lyrics: a same-named .lrc next to the audio file. */
  lyricsLoading: '歌词加载中…',
  lyricsEmpty: '这首歌没有可用的歌词',
  lyricsHint: '把同名的 .lrc 放在音频旁边即可显示',

  /** Image and comic viewers. */
  previousImage: '上一张',
  nextImage: '下一张',
  resetView: '重置视图',
  zoomIn: '放大',
  zoomOut: '缩小',
  rotate: '旋转 90°',
  comicOn: '漫画模式（连续阅读）',
  comicOff: '退出漫画模式',
  comicStrip: '切换为竖向连续',
  comicSpread: '切换为双页跨页',
  pageOf: '第 {page} / {total} 页',
  pageRange: '第 {from}–{to} / {total} 页',

  /** Subtitle tracks. */
  subtitle: '字幕',
  subtitleOff: '关闭字幕',
  subtitleSize: '字幕大小',
  subtitleReadFailed: '字幕读取失败',
  subtitleEmpty: '字幕内容为空或格式无法识别',
  subtitleDefault: '默认 · {format}',

  /**
   * Language endonyms: a track listing names languages in their own script, so
   * both tables carry the same text. Localised names here would be a bug — the
   * viewer is looking for the language they actually speak.
   */
  subLangZh: '中文',
  subLangZhHans: '简体中文',
  subLangZhHant: '繁体中文',
  subLangEn: 'English',
  subLangJa: '日本語',
  subLangKo: '한국어',
  subLangFr: 'Français',
  subLangDe: 'Deutsch',
  subLangEs: 'Español',
  subLangRu: 'Русский',
}
