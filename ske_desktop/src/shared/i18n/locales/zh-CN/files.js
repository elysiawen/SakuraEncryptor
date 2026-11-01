/**
 * File operations the cloud and local libraries both offer.
 *
 * These live outside `local` / `browse` on purpose: the two libraries show the
 * same verbs, and a verb defined twice is a verb that will drift apart the first
 * time one of them is reworded.
 */
export default {
  selectMode: '多选',
  refresh: '刷新',
  selectAll: '全选',
  clearSelection: '取消选择',
  selectedCount: '已选 {count} 项',

  download: '下载',
  downloadCount: '下载 {count} 个文件',
  deleteCount: '删除 {count} 项',

  emptyDirTitle: '这个目录是空的',

  mkdirTitle: '新建文件夹',
  folderNamePlaceholder: '文件夹名称',
  create: '创建',
  encryptName: '加密名称',
  rename: '重命名',
  renameFolder: '重命名文件夹',
  renameDesc: '加密条目会重新加密为新名字，未加密的条目保持明文。',
  newNamePlaceholder: '新名称',
  deleteTitle: '确认删除',

  copyLink: '复制播放链接',
  externalPlayer: '外部播放器',
  linkCopied: '播放链接已复制，可粘贴到 mpv / VLC 等播放器；复制其他文件或退出应用后失效',
  openedWith: '已用 {player} 打开；播放期间请保持应用运行',

  queuedOne: '已加入下载：{name}',
  queuedMany: '已加入下载：{count} 个文件',
  created: '已创建文件夹：{name}',
  renamed: '已重命名为：{name}',
  removedOne: '已删除：{name}',
  removedMany: '已删除 {count} 项',
  removeFailed: '{count} 项删除失败：{error}',
}
