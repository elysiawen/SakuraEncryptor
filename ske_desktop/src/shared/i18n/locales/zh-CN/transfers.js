/** The transfers page: download and upload jobs, running or finished. */
export default {
  title: '传输',
  subtitle: '下载把云端或本地的容器解密到本机，上传把本机文件加密后推到云端。任务在后台继续，切换页面不影响进度。',

  notChosen: '尚未选择下载目录',
  pickDir: '选择目录',
  clearFinished: '清除已完成',
  dirUpdated: '下载目录已更新',

  emptyTitle: '没有传输任务',
  emptyDesc: '云端库和本地库的右键菜单可以下载或上传；任务会出现在这里，加密与解密都在本机完成。',

  kindDownload: '下载',
  kindUpload: '上传',
  reveal: '打开所在文件夹',
  deleteLocalFile: '删除文件',
  deleteRemoteFile: '删除云端文件',
  deleteRecord: '删除记录',
  deleteLocalFileDesc: '会把「{name}」从本机删除，并同时移除这条记录。',
  deleteRemoteFileDesc: '会把「{name}」从云端目录 {dir} 删除，并同时移除这条记录。',
  deletedFile: '文件已删除',

  listUploads: '上传列表',
  listDownloads: '下载列表',
  listFinished: '完成列表',
  emptyUploads: '暂无进行中的上传',
  emptyDownloads: '暂无进行中的下载',
  emptyFinished: '还没有结束的任务',

  statusQueued: '排队中',
  statusEncrypting: '加密中',
  statusUploading: '上传中 {percent}%',
  statusStoring: '写入云端 {percent}%',
  statusStoringPending: '写入云端…',
  cloudPercent: '云端 {percent}%',
  statusDownloaded: '已下载',
  statusUploaded: '已上传',
  statusCancelled: '已取消',
  statusFailed: '失败',
}
