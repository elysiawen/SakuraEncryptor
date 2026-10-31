/**
 * AList backend entry point.
 *
 * AList and OpenList expose the same `/api` surface, and that shared part lives
 * in `remote-client.js`. This module exists so AList keeps its own entry point:
 * anything AList-specific belongs here and only here, never in the base. Today
 * that is just the label used in user-facing messages — AList v3 accounts can
 * also use TOTP, and that flow is already shared.
 */
import { RemoteClient } from './remote-client.js'

export class AListClient extends RemoteClient {
  constructor(options) {
    super({ ...options, label: 'AList' })
  }
}
