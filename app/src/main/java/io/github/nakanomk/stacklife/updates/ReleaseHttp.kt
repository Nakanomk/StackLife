package io.github.nakanomk.stacklife.updates

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

private val catalogUrl = "https://api.github.com/repos${URI(STACKLIFE_REPOSITORY).path}/releases"
private const val MAX_PAGE_BYTES = 2L * 1024 * 1024
private val releaseAssetPath = Regex("/github-production-release-asset/[0-9]+/[A-Za-z0-9-]+")

/** No assistant interceptors, cookies, auth, mirrors, disk cache or automatic redirects. */
private val releaseClient = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(5, TimeUnit.MINUTES)
    .build()

internal fun isReleaseAssetRedirect(location: String): Boolean {
    val uri = try { URI(location) } catch (_: URISyntaxException) { return false }
    return uri.scheme == "https" && uri.host == "release-assets.githubusercontent.com" &&
        uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443) && uri.rawFragment == null &&
        releaseAssetPath.matches(uri.rawPath)
}

private sealed interface Fetch<out T> {
    data class Complete<T>(val value: T) : Fetch<T>
    data class Redirect(val location: String) : Fetch<Nothing>
}

/** Called on Dispatchers.IO. Keep cancellation attached to the call until the body is fully consumed. */
private suspend fun <T> fetch(url: String, asset: Boolean, read: (InputStream, Long) -> T): T {
    var target = url
    for (hop in 0..1) {
        val request = Request.Builder().url(target)
            .header("User-Agent", "StackLife-Updates")
            .header("Accept-Encoding", "identity")
            .header("Accept", if (asset) "application/octet-stream" else "application/vnd.github+json")
            .apply { if (!asset) header("X-GitHub-Api-Version", "2022-11-28") }.build()
        val fetched = suspendCancellableCoroutine<Fetch<T>> { waiting ->
            val call = releaseClient.newCall(request)
            waiting.invokeOnCancellation { call.cancel() }
            val result = runCatching {
                call.execute().use { response ->
                    when {
                        response.code == 200 -> Fetch.Complete(read(response.body.byteStream(), response.body.contentLength()))
                        response.isRedirect && asset && hop == 0 -> {
                            val location = response.header("Location") ?: invalid("Missing release asset redirect")
                            if (!isReleaseAssetRedirect(location)) invalid("Unexpected release asset redirect")
                            Fetch.Redirect(location)
                        }
                        else -> throw httpFailure(response)
                    }
                }
            }
            waiting.resumeWith(result)
        }
        when (fetched) {
            is Fetch.Complete -> return fetched.value
            is Fetch.Redirect -> target = fetched.location
        }
    }
    invalid("Too many release asset redirects")
}

internal fun httpFailure(response: Response): ReleaseFailure {
    val limited = response.code == 429 || response.code == 403 && (
        response.header("X-RateLimit-Remaining") == "0" || response.header("Retry-After") != null ||
            response.peekBody(4096).string().contains("rate limit", ignoreCase = true))
    val problem = when {
        limited -> ReleaseProblem.RATE_LIMIT
        response.isRedirect -> ReleaseProblem.INVALID
        else -> ReleaseProblem.NETWORK
    }
    return ReleaseFailure(problem, "GitHub HTTP ${response.code}")
}

private fun readMetadata(input: InputStream, length: Long, cap: Long): ByteArray {
    if (length > cap) invalid("Oversized release metadata")
    val bytes = ByteArrayOutputStream(if (length >= 0) length.toInt() else DEFAULT_BUFFER_SIZE)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        if (bytes.size() + n > cap) invalid("Oversized release metadata")
        bytes.write(buffer, 0, n)
    }
    return bytes.toByteArray()
}

internal suspend fun releasePage(page: Int) = network {
    fetch("$catalogUrl?per_page=$RELEASE_PAGE_SIZE&page=$page", asset = false) { input, length ->
        readMetadata(input, length, MAX_PAGE_BYTES).decodeToString()
    }
}

internal suspend fun releaseBuildInfo(asset: ReleaseAsset) = network {
    fetch(asset.url, asset = true) { input, length -> readMetadata(input, length, asset.size) }
}

internal suspend fun <T> fetchApk(version: String, read: (InputStream, Long) -> T): T = network {
    fetch(assetUrl(version, apkName(version)), asset = true, read)
}

private suspend inline fun <T> network(block: () -> T): T = try { block() } catch (e: ReleaseFailure) {
    throw e
} catch (e: IOException) {
    throw ReleaseFailure(ReleaseProblem.NETWORK, "Release request failed", e)
}
