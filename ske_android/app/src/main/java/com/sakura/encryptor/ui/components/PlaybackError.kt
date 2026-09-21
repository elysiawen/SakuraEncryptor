package com.sakura.encryptor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackException
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.net.ssl.SSLException

/**
 * Full-screen failure notice for a player.
 *
 * Playback can fail for reasons the user can actually fix — a mistyped
 * password, an expired AList session, a flaky network. Without this the player
 * just goes black and looks hung, which is the worst possible outcome.
 */
@Composable
fun PlaybackErrorOverlay(
    message: String,
    onRetry: (() -> Unit)?,
    onBack: () -> Unit,
    title: String = "无法播放",
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF2121214))
            // Swallow gestures so the player's own tap/swipe controls stay inert.
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = Color(0xFFE57373),
                modifier = Modifier.size(50.dp),
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xB3FFFFFF),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(26.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onRetry != null) {
                    Button(onClick = onRetry) { Text("重试") }
                }
                OutlinedButton(
                    onClick = onBack,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                ) {
                    Text("返回")
                }
            }
        }
    }
}

/**
 * Explain an ExoPlayer failure in terms of what the user can do about it.
 *
 * A mistyped password surfaces as an `AEADBadTagException` buried inside a
 * generic IO error, so the error code alone is not enough — the cause chain is
 * inspected as well.
 */
fun describePlaybackError(error: PlaybackException): String = when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        "服务器拒绝了访问，可能需要重新登录"

    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
        "文件不存在或已被移动"

    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
        "网络连接失败，请检查网络与 AList 地址"

    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
        "网络连接超时，请稍后重试"

    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
        "暂不支持这种媒体格式"

    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    -> "文件已损坏，无法解析"

    else -> describeFailure(error)
}

/**
 * Explain a decrypt/read failure in terms of what the user can do about it.
 *
 * Walks the whole cause chain: our data source wraps the crypto exception in a
 * plain [java.io.IOException], so the interesting part is never the outermost
 * one.
 */
fun describeFailure(throwable: Throwable): String {
    val chain = generateSequence<Throwable>(throwable) { it.cause }.toList()

    return when {
        chain.any { it is AEADBadTagException || it is BadPaddingException } ->
            "密码错误，或文件已损坏"

        chain.any { it is UnknownHostException } ->
            "找不到服务器，请检查网络与 AList 地址"

        chain.any { it is SocketTimeoutException || it is ConnectException } ->
            "连接超时，请稍后重试"

        chain.any { it is SSLException } ->
            "安全连接失败，请检查服务器证书"

        chain.any { it is FileNotFoundException } ->
            "文件不存在或已被移动"

        else -> chain.firstNotNullOfOrNull { it.message?.takeIf(String::isNotBlank) }
            ?: "操作失败"
    }
}
