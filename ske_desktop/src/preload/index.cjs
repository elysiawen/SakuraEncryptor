// Sandboxed preload: only the bridge surface below crosses into the renderer.
// Keep this CJS — sandboxed preloads cannot be ES modules.
const { contextBridge, ipcRenderer, webUtils } = require('electron')

const invoke = (channel, ...args) => ipcRenderer.invoke(channel, ...args)
const send = (channel, ...args) => ipcRenderer.send(channel, ...args)

contextBridge.exposeInMainWorld('ske', {
  app: {
    info: () => invoke('app:info'),
    newWindow: (route) => invoke('window:new', route),
    /**
     * A `.ske` opened from the OS (double-click, "open with"). Main sends the
     * absolute path once the window exists, so this also covers cold start.
     */
    onOpenFile: (callback) => {
      const handler = (_event, payload) => callback(payload)
      ipcRenderer.on('app:openFile', handler)
      return () => ipcRenderer.removeListener('app:openFile', handler)
    },
  },

  /** Theme is per-document, so a toggle in one window is pushed to the others. */
  theme: {
    broadcast: (theme) => invoke('theme:broadcast', theme),
    onChange: (callback) => {
      const handler = (_event, theme) => callback(theme)
      ipcRenderer.on('theme:changed', handler)
      return () => ipcRenderer.removeListener('theme:changed', handler)
    },
  },

  /**
   * Launch gate. `unlock` takes every credential the user filled in
   * (`{ password, totp }`): the main process decides whether one is enough or
   * both are required, so the renderer never rules on that itself.
   */
  security: {
    state: () => invoke('security:state'),
    unlock: (input) => invoke('security:unlock', input),
    lock: () => invoke('security:lock'),
    setPassword: (password, currentPassword) => invoke('security:setPassword', password, currentPassword),
    disable: (input) => invoke('security:disable', input),
    setMatch: (match) => invoke('security:setMatch', match),
    totp: {
      begin: () => invoke('security:totpBegin'),
      confirm: (payload) => invoke('security:totpConfirm', payload),
      unbind: () => invoke('security:totpUnbind'),
    },
  },

  vault: {
    list: () => invoke('vault:list'),
    save: (profile) => invoke('vault:save', profile),
    remove: (id) => invoke('vault:delete', id),
    activate: (id) => invoke('vault:activate', id),
  },

  settings: {
    get: () => invoke('settings:get'),
    patch: (patch) => invoke('settings:patch', patch),
  },

  dialog: {
    /** Existing container (decrypt / play). */
    pickFile: () => invoke('dialog:pickFile'),
    /** Plaintext sources to encrypt; multiple files per pick. */
    pickSourceFiles: () => invoke('dialog:pickSourceFiles'),
    /** Files to upload to the current cloud folder, sizes included. */
    pickUploadFiles: () => invoke('dialog:pickUploadFiles'),
    pickDirectory: () => invoke('dialog:pickDirectory'),
    /** Executable of the external player used by "外部播放器". */
    pickPlayer: () => invoke('dialog:pickPlayer'),
  },

  cloud: {
    connect: (profileId) => invoke('cloud:connect', profileId),
    status: (sessionId) => invoke('cloud:status', sessionId),
    disconnect: (sessionId) => invoke('cloud:disconnect', sessionId),
    /** `refresh` forces AList to bypass its directory cache (manual refresh). */
    list: (sessionId, path, refresh = false) => invoke('cloud:list', { sessionId, path, refresh }),
    /** File-manager writes take plaintext names; encryption happens in main. */
    mkdir: (payload) => invoke('cloud:mkdir', payload),
    rename: (payload) => invoke('cloud:rename', payload),
    remove: (payload) => invoke('cloud:remove', payload),
  },

  local: {
    unlock: (password) => invoke('local:unlock', password),
    lock: () => invoke('local:lock'),
    status: () => invoke('local:status'),
    setRoot: (dir) => invoke('local:setRoot', dir),
    list: (path) => invoke('local:list', path),
    play: (payload) => invoke('local:play', payload),
    mkdir: (payload) => invoke('local:mkdir', payload),
    rename: (payload) => invoke('local:rename', payload),
    remove: (payload) => invoke('local:remove', payload),
  },

  jobs: {
    list: () => invoke('jobs:list'),
    add: (payload) => invoke('jobs:add', payload),
    cancel: (id) => invoke('jobs:cancel', id),
    clear: () => invoke('jobs:clear'),
    /** Returns an unsubscribe function so views can clean up on unmount. */
    onUpdate: (callback) => {
      const handler = (_event, snapshot) => callback(snapshot)
      ipcRenderer.on('jobs:update', handler)
      return () => ipcRenderer.removeListener('jobs:update', handler)
    },
  },

  downloads: {
    list: () => invoke('downloads:list'),
    add: (payload) => invoke('downloads:add', payload),
    cancel: (id) => invoke('downloads:cancel', id),
    clear: () => invoke('downloads:clear'),
    reveal: (id) => invoke('downloads:reveal', id),
    /** Returns an unsubscribe function so views can clean up on unmount. */
    onUpdate: (callback) => {
      const handler = (_event, snapshot) => callback(snapshot)
      ipcRenderer.on('downloads:update', handler)
      return () => ipcRenderer.removeListener('downloads:update', handler)
    },
  },

  /**
   * Uploading is a write: the renderer picks local files, the main process
   * encrypts them into a temp container and streams that to AList. No password
   * and no local path ever reaches this bridge on the way back.
   */
  uploads: {
    list: () => invoke('uploads:list'),
    /** Dropped paths → `{ path, name, size }`, folders filtered out in main. */
    prepare: (paths) => invoke('uploads:prepare', paths),
    add: (payload) => invoke('uploads:add', payload),
    cancel: (id) => invoke('uploads:cancel', id),
    clear: () => invoke('uploads:clear'),
    /** Returns an unsubscribe function so views can clean up on unmount. */
    onUpdate: (callback) => {
      const handler = (_event, snapshot) => callback(snapshot)
      ipcRenderer.on('uploads:update', handler)
      return () => ipcRenderer.removeListener('uploads:update', handler)
    },
  },

  /**
   * Every kind of media plays in its own window. The payload is handed over by
   * the main process, so this bridge never carries a password: only where to read.
   *
   * Audio is the exception in one respect: the stream itself lives in the shell
   * (so closing the music window cannot stop it), and the window is a remote
   * control. `publish` mirrors the shell transport there, `command` runs one of
   * its actions.
   */
  player: {
    openWindow: (payload) => invoke('player:openWindow', payload),
    /** Initial file for a freshly opened stage window, consumed once. */
    payload: () => invoke('player:payload'),
    /** Close the stage window: audio in the shell must not play over a video. */
    closeWindow: () => invoke('player:closeWindow'),
    /** A later pick reuses the open window instead of stacking another one. */
    onLoad: (callback) => {
      const handler = (_event, payload) => callback(payload)
      ipcRenderer.on('stage:load', handler)
      return () => ipcRenderer.removeListener('stage:load', handler)
    },
    /** Shell → main: mirror the audio transport to the music window. */
    publish: (state) => send('player:state', state),
    /** Shell: run a command forwarded from the music window. */
    onCommand: (callback) => {
      const handler = (_event, command) => callback(command)
      ipcRenderer.on('player:command', handler)
      return () => ipcRenderer.removeListener('player:command', handler)
    },
    /** Music window: ask the shell for its current transport. */
    command: (command) => invoke('player:command', command),
    /** Music window: transport snapshots published by the shell. */
    onState: (callback) => {
      const handler = (_event, state) => callback(state)
      ipcRenderer.on('player:state', handler)
      return () => ipcRenderer.removeListener('player:state', handler)
    },
    /** Launch a player on a decrypted stream URL; `exe` picks which one. */
    openExternal: (url, exe) => invoke('player:external', { url, exe }),
    /** Every player the machine can launch, for the "外部播放器" submenu. */
    listExternal: () => invoke('player:externalList'),
  },

  window: {
    close: () => invoke('window:close'),
    /** Maximise state for the custom title bar's restore icon. */
    state: () => invoke('window:state'),
    minimize: () => invoke('window:minimize'),
    toggleMaximize: () => invoke('window:toggleMaximize'),
    /** Shape the window around the pages' combined ratio (comic spreads). */
    fitAspect: (aspect, chrome) => invoke('window:fitAspect', aspect, chrome),
    setAlwaysOnTop: (flag) => invoke('window:alwaysOnTop', !!flag),
    toggleFullScreen: () => invoke('window:toggleFullScreen'),
  },

  /**
   * The decrypted stream URL, so it can be pasted into an external player
   * (mpv, VLC, PotPlayer). Nothing else is ever put on the clipboard by the app.
   */
  clipboard: {
    write: (text) => invoke('clipboard:write', text),
  },

  media: {
    open: (payload) => invoke('media:open', payload),
    openCloud: (payload) => invoke('media:openCloud', payload),
    close: (id) => invoke('media:close', id),
  },

  /** Subtitle containers are read as text on the main side and returned decoded. */
  subtitles: {
    read: (payload) => invoke('subtitles:read', payload),
  },

  /** The `.lrc` next to the playing track, found and decoded in the main process. */
  lyrics: {
    read: (payload) => invoke('lyrics:read', payload),
  },

  /**
   * Caches: decrypted previews, signed URLs, decrypt blocks, plus the encrypted
   * preview cache on disk. Clearing is always safe — the worst case is that the
   * next tile is pulled and decrypted again.
   */
  cache: {
    stats: () => invoke('cache:stats'),
    /** target: previews | links | blocks | sessions | all */
    clear: (target) => invoke('cache:clear', { target }),
  },

  files: {
    // Electron 32 removed `File.path`; this is the supported replacement and it
    // only exists inside the preload, so it has to be bridged explicitly.
    pathForFile: (file) => {
      try {
        return webUtils.getPathForFile(file)
      } catch {
        return ''
      }
    },
  },
})
