package io.github.nakanomk.stacklife.updates

import io.github.nakanomk.stacklife.about.InstalledVersion
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

internal const val STACKLIFE_REPOSITORY = "https://github.com/Nakanomk/StackLife"
internal const val RELEASE_PACKAGE = "io.github.nakanomk.stacklife"
internal const val RELEASE_PAGE_SIZE = 25
internal const val MAX_APK_BYTES = 256L * 1024 * 1024
private const val BUILD_INFO = "BUILD-INFO.json"
private val versionPattern = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")
internal val sha256Pattern = Regex("[0-9a-f]{64}")

@Serializable
internal data class ReleaseUpdate(val version: String, val versionCode: Long, val notes: String, val size: Long,
    val sha256: String, val publishedAt: String? = null)

internal enum class UpdateChannel { STABLE, BETA }

internal enum class ReleaseProblem { NETWORK, RATE_LIMIT, INVALID, INCOMPATIBLE }
internal class ReleaseFailure(val problem: ReleaseProblem, message: String, cause: Throwable? = null) : IOException(message, cause)
internal fun invalid(message: String): Nothing = throw ReleaseFailure(ReleaseProblem.INVALID, message)
internal fun incompatible(message: String): Nothing = throw ReleaseFailure(ReleaseProblem.INCOMPATIBLE, message)

internal fun apkName(version: String) = "StackLife-$version-preview.apk"
internal fun assetUrl(version: String, name: String) = "$STACKLIFE_REPOSITORY/releases/download/v$version/$name"
internal data class ReleaseAsset(val url: String, val size: Long, val sha256: String)

private fun versionParts(name: String): List<String> {
    if (name.length > 64 || !versionPattern.matches(name)) invalid("Invalid release version")
    return name.split('.')
}

/** Canonical decimal components have no leading zeroes. Length comparison avoids numeric overflow. */
private fun compareParts(a: List<String>, b: List<String>): Int {
    for (i in 0..2) {
        val length = a[i].length.compareTo(b[i].length)
        if (length != 0) return length
        val digits = a[i].compareTo(b[i])
        if (digits != 0) return digits
    }
    return 0
}

internal fun requireUpgrade(update: ReleaseUpdate, installed: InstalledVersion) {
    if (update.size !in 1L..MAX_APK_BYTES || !sha256Pattern.matches(update.sha256) || update.versionCode <= 0) invalid("Invalid APK metadata")
    if (compareParts(versionParts(update.version), installedParts(installed)) <= 0 || update.versionCode <= installed.code) {
        incompatible("Not a newer installed-package version and code")
    }
}

private fun installedParts(installed: InstalledVersion): List<String> {
    val name = installed.name ?: incompatible("Missing installed version")
    if (installed.code <= 0 || !versionPattern.matches(name) || name.length > 64) incompatible("Unsupported installed version")
    return versionParts(name)
}

private fun json(raw: String) = try { Json.parseToJsonElement(raw) } catch (error: SerializationException) {
    throw ReleaseFailure(ReleaseProblem.INVALID, "Malformed release metadata", error)
}

private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid("Invalid $key")
private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: invalid("Invalid $key")

private fun JsonArray.asset(version: String, name: String, limit: Long): ReleaseAsset {
    val item = singleOrNull { (it as? JsonObject)?.string("name") == name } as? JsonObject ?: invalid("Missing or duplicate $name")
    val url = assetUrl(version, name)
    val size = item.number("size")
    val digest = item.string("digest")
    if (item.string("state") != "uploaded" || item.string("browser_download_url") != url || size !in 1..limit ||
        !digest.startsWith("sha256:") || !sha256Pattern.matches(digest.substringAfter(':'))) invalid("Invalid $name asset")
    return ReleaseAsset(url, size, digest.substringAfter(':'))
}

/**
 * BiliPai c7f835b34d8323358be57be298f86f29fecb53c8 (GPL-3.0),
 * app/src/main/java/com/android/purebilibili/feature/settings/update/AppUpdateChecker.kt:
 * channel eligibility and publication-time selection. StackLife scans all bounded pages first,
 * then applies ReleaseHttp's fixed-origin limits and release.yml's exact asset/identity contract.
 */
internal suspend fun findRelease(
    installed: InstalledVersion,
    applicationId: String,
    channel: UpdateChannel,
    signers: () -> List<String>,
    loadPage: suspend (Int) -> String,
    loadBuildInfo: suspend (ReleaseAsset) -> ByteArray,
): ReleaseUpdate? {
    if (applicationId != RELEASE_PACKAGE) incompatible("Not the ordinary StackLife package")
    val local = installedParts(installed)
    var published: Instant? = null
    var releaseId = 0L
    var release: JsonObject? = null
    var page = 1
    while (true) {
        if (page > 40) invalid("Release catalog exceeds the supported limit")
        val list = json(loadPage(page++)) as? JsonArray ?: invalid("Not a release catalog")
        if (list.size > RELEASE_PAGE_SIZE) invalid("Oversized release page")
        for (entry in list) {
            val item = entry as? JsonObject ?: invalid("Invalid release record")
            val draft = (item["draft"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: invalid("Missing draft status")
            if (draft) continue
            val prerelease = (item["prerelease"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                ?: invalid("Missing prerelease status")
            if (channel == UpdateChannel.STABLE && prerelease) continue
            val time = try { Instant.parse(item.string("published_at")) }
                catch (_: DateTimeParseException) { invalid("Invalid release publication time") }
            val id = item.number("id")
            if (id <= 0) invalid("Invalid release ID")
            if (published == null || time > published || time == published && id > releaseId) {
                published = time
                releaseId = id
                release = item
            }
        }
        if (list.size < RELEASE_PAGE_SIZE) break
    }
    val newest = release ?: return null
    val tag = newest.string("tag_name")
    if (!tag.startsWith('v')) invalid("Missing version tag")
    val name = tag.substring(1)
    val parts = versionParts(name)
    val assets = newest["assets"] as? JsonArray ?: invalid("Missing release assets")
    val apk = assets.asset(name, apkName(name), MAX_APK_BYTES)
    val metadata = assets.asset(name, BUILD_INFO, 64L * 1024)
    val bytes = loadBuildInfo(metadata)
    if (bytes.size.toLong() != metadata.size || MessageDigest.getInstance("SHA-256").digest(bytes).toHexString() != metadata.sha256) {
        invalid("BUILD-INFO size or SHA-256 mismatch")
    }
    val info = json(bytes.decodeToString()) as? JsonObject ?: invalid("Invalid BUILD-INFO")
    if (info.string("sourceTag") != "v$name" || info.string("versionName") != name) invalid("Build and tag differ")
    if (info.string("applicationId") != applicationId || info.string("variant") != "preview") incompatible("Different package or variant")
    val certificate = info.string("signingCertificateSha256")
    if (!sha256Pattern.matches(certificate)) invalid("Invalid signing certificate digest")
    if (signers().singleOrNull() != certificate) incompatible("Different signing certificate")
    val notes = when (newest["body"]) {
        null, JsonNull -> ""
        else -> newest.string("body")
    }
    val code = info.number("versionCode")
    if (code <= 0) invalid("Invalid version code")
    if (compareParts(parts, local) <= 0 && code <= installed.code) return null
    return ReleaseUpdate(name, code, notes, apk.size, apk.sha256, published.toString()).also { requireUpgrade(it, installed) }
}
