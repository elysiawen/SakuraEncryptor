/** The transfers page: download and upload jobs, running or finished. */
export default {
  title: 'Transfers',
  subtitle: 'Downloads decrypt a container from the cloud or this machine; uploads encrypt a local file and push it to the cloud. Tasks keep running in the background, so switching pages does not stop them.',

  notChosen: 'No download folder yet',
  pickDir: 'Choose folder',
  clearFinished: 'Clear finished',
  dirUpdated: 'Download folder updated',

  emptyTitle: 'No transfers',
  emptyDesc: 'The cloud and local libraries download or upload from a file\u2019s context menu; tasks show up here, and encryption stays on this machine.',

  kindDownload: 'Download',
  kindUpload: 'Upload',
  reveal: 'Show in folder',
  deleteLocalFile: 'Delete file',
  deleteRemoteFile: 'Delete from cloud',
  deleteRecord: 'Remove record',
  deleteLocalFileDesc: 'Deletes “{name}” from this computer and removes the record with it.',
  deleteRemoteFileDesc: 'Deletes “{name}” from the cloud folder {dir} and removes the record with it.',
  deletedFile: 'File deleted',

  listUploads: 'Uploads',
  listDownloads: 'Downloads',
  listFinished: 'Finished',
  emptyUploads: 'Nothing uploading',
  emptyDownloads: 'Nothing downloading',
  emptyFinished: 'No finished tasks yet',

  statusQueued: 'Queued',
  statusEncrypting: 'Encrypting',
  statusUploading: 'Uploading {percent}%',
  statusStoring: 'Saving to cloud {percent}%',
  statusStoringPending: 'Saving to cloud…',
  cloudPercent: 'Cloud {percent}%',
  statusDownloaded: 'Downloaded',
  statusUploaded: 'Uploaded',
  statusCancelled: 'Cancelled',
  statusFailed: 'Failed',
}
