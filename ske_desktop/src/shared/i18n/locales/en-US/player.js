/** Transport controls. Shared by the sidebar bar and the player window. */
export default {
  /** Sidebar bar: reopening the music page. */
  openPage: 'Open the music page',
  trackFallback: 'Music',
  playing: 'Playing',
  paused: 'Paused',
  stop: 'Stop playback',
  progress: 'Playback progress',
  previous: 'Previous track',
  next: 'Next track',
  play: 'Play',
  pause: 'Pause',

  /** Transport details that only the standalone player window shows. */
  playlist: 'Playlist',
  playlistCollapse: 'Hide the playlist',
  closeWindow: 'Close the window',
  closeWindowKeepPlaying: 'Close the window (keeps playing)',
  decrypting: 'Decrypting…',
  empty: 'Nothing to play',
  volume: 'Volume',
  mute: 'Mute',
  unmute: 'Unmute',
  playSpace: 'Play (space)',
  pauseSpace: 'Pause (space)',
  speed: 'Playback speed',
  fullscreen: 'Fullscreen',

  /** Play modes. `modeClick` is the tooltip wrapper around them. */
  modeList: 'Repeat all',
  modeOne: 'Repeat one',
  modeShuffle: 'Shuffle',
  modeClick: '{mode} (click to switch)',

  /** Failures the window reports itself, because the shell cannot be reached. */
  bridgeOutdated: 'The app is too old for this window; quit it completely and open it again',
  hostNoResponse: 'The main window did not respond',
  bridgeLost: 'Could not reach the main window: the track may have stopped, or the app was updated without a restart',
  audioFailed: 'Could not load the audio; the file may be damaged or undecryptable',
  videoFailed: 'Could not load the video; the file may be damaged or undecryptable',
  videoCodec: 'The built-in player cannot decode this format (often avi / wmv / older codecs)',
  imageLoading: 'Decrypting and loading the image…',
  imageFailed: 'Could not load the image; the file may be damaged or undecryptable',

  /** Decrypted play links, copied out to an external player. */
  linkCopy: 'Copy the decrypted play link',
  linkCopied: 'Link copied; paste it into an external player. It stops working once the app closes',

  /** Lyrics: a same-named .lrc next to the audio file. */
  lyricsLoading: 'Loading lyrics…',
  lyricsEmpty: 'No lyrics are available for this track',
  lyricsHint: 'Put a .lrc file with the same name next to the audio',

  /** Image and comic viewers. */
  previousImage: 'Previous image',
  nextImage: 'Next image',
  resetView: 'Reset the view',
  zoomIn: 'Zoom in',
  zoomOut: 'Zoom out',
  rotate: 'Rotate 90°',
  comicOn: 'Comic mode (continuous reading)',
  comicOff: 'Leave comic mode',
  comicStrip: 'Switch to vertical strip',
  comicSpread: 'Switch to two-page spread',
  pageOf: 'Page {page} / {total}',
  pageRange: 'Pages {from}–{to} / {total}',

  /** Subtitle tracks. */
  subtitle: 'Subtitles',
  subtitleOff: 'Turn subtitles off',
  subtitleSize: 'Subtitle size',
  subtitleReadFailed: 'Could not read the subtitles',
  subtitleEmpty: 'The subtitle file is empty or in an unrecognised format',
  subtitleDefault: 'Default · {format}',

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
