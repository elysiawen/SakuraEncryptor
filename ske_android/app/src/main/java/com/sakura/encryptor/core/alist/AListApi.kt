package com.sakura.encryptor.core.alist

import com.sakura.encryptor.core.remote.RemoteClient
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * Thin AList client.
 *
 * AList has no chunked upload endpoint, so the shared form upload from
 * [RemoteClient] is its only upload path. The embedded-server admin surface
 * lives in the `openlist` flavor, so builds without it carry no part of it.
 */
class AListApi(
    client: OkHttpClient,
    json: Json = RemoteClient.DEFAULT_JSON,
) : RemoteClient(client, json) {

    override val label: String = "AList"
}
