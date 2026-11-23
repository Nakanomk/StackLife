package io.github.nakanomk.stacklife.updates

import io.github.nakanomk.stacklife.about.InstalledVersion
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ReleaseAssetsTest {
    // Public v0.10.17 prerelease from release.yml: real APK size, digest, version and signer.
    private val cert = "6a51915f4e57205dfd4e5f1bee5bac6c14434e7bdaa2050c165975a512b758d9"
    private val apkHash = "a938c0a1c4c0f7d75cb8a1b056e675844d5ed9e1eb47ebe2ebdd13e105cf0c40"
    private val name = "0.10.17"
    private val old = InstalledVersion("0.10.9", 24)
    private fun ByteArray.sha() = MessageDigest.getInstance("SHA-256").digest(this).toHexString()
    private fun JsonObject.set(key: String, value: String) = JsonObject(this + (key to JsonPrimitive(value)))
    private fun info(version: String = name, code: Long = 32L) = buildJsonObject {
        put("applicationId", RELEASE_PACKAGE); put("versionName", version); put("versionCode", code)
        put("variant", "preview"); put("sourceTag", "v$version"); put("signingCertificateSha256", cert)
    }
    private fun asset(name: String, size: Long, hash: String, version: String) = buildJsonObject {
        put("name", name); put("size", size); put("digest", "sha256:$hash"); put("state", "uploaded")
        put("browser_download_url", "$STACKLIFE_REPOSITORY/releases/download/v$version/$name")
    }
    private fun apk() = asset("StackLife-$name-preview.apk", 18_393_648L, apkHash, name)
    private fun build(bytes: ByteArray) = asset("BUILD-INFO.json", bytes.size.toLong(), bytes.sha(), name)
    private fun release(vararg assets: JsonObject, version: String = name, draft: Boolean = false) = buildJsonObject {
        put("draft", draft); put("prerelease", true); put("tag_name", "v$version"); put("body", "Release notes")
        put("assets", JsonArray(assets.toList()))
    }
    private fun one(release: JsonObject, bytes: ByteArray, installed: InstalledVersion = old) = runBlocking {
        findRelease(installed, RELEASE_PACKAGE, { listOf(cert) }, { JsonArray(listOf(release)).toString() }, { bytes })
    }
    private fun problem(expected: ReleaseProblem, action: () -> Unit) {
        val result = assertThrows(ReleaseFailure::class.java) { action() }
        assertEquals(expected, result.problem)
    }

    @Test fun selectsNumericallyLatestPublicPreviewAcrossPagesAndChecksOnlyItsBuildInfo() = runBlocking {
        val bytes = info().toString().encodeToByteArray()
        val first = List(RELEASE_PAGE_SIZE - 2) { release(version = "0.10.2") } +
            listOf(release(version = "0.10.10"), release(version = "9.9.9", draft = true))
        val second = listOf(release(asset("StackLife-bench.apk", 9, apkHash, name), build(bytes), apk()))
        val pages = mutableListOf<Int>()
        var infoRequests = 0
        val found = findRelease(old, RELEASE_PACKAGE, { listOf(cert) }, { page ->
            pages += page
            JsonArray(if (page == 1) first else second).toString()
        }, { asset ->
            infoRequests++
            assertEquals("$STACKLIFE_REPOSITORY/releases/download/v0.10.17/BUILD-INFO.json", asset.url)
            bytes
        })
        assertEquals(listOf(1, 2), pages)
        assertEquals(1, infoRequests)
        assertEquals(ReleaseUpdate(name, 32, "Release notes", 18_393_648, apkHash), found)
    }

    @Test fun sameOrAheadDoesNotRequestSignersBuildInfoOrApk() = runBlocking {
        for (installed in listOf(InstalledVersion(name, 32), InstalledVersion("0.10.20", 35), InstalledVersion("1.0.0", 40))) {
            assertNull(findRelease(installed, RELEASE_PACKAGE, { error("Read signers without an update") }, {
                JsonArray(listOf(release(), release(version = "2.0.0", draft = true))).toString()
            }, { error("Fetched build information with no newer tag") }))
        }
    }

    @Test fun incompleteOrRedirectedPublicationCannotFallBackToAnOlderRelease() {
        val bytes = info().toString().encodeToByteArray()
        val apk = apk()
        val metadata = build(bytes)
        val wrong = listOf(
            release(apk), release(metadata), release(metadata, apk, apk),
            release(metadata, apk.set("browser_download_url", "https://example.com/app.apk")),
            release(metadata.set("browser_download_url", "$STACKLIFE_REPOSITORY/releases/latest/download/BUILD-INFO.json"), apk),
            release(metadata, apk.set("digest", "sha1:$apkHash")), release(metadata, apk.set("digest", "sha256:abc")),
            release(metadata, apk.set("state", "open")), release(metadata, apk.set("name", "StackLife-$name-kitLab.apk")),
            release(metadata, JsonObject(apk + ("size" to JsonPrimitive(MAX_APK_BYTES + 1)))),
        )
        for (broken in wrong) problem(ReleaseProblem.INVALID) {
            runBlocking { findRelease(old, RELEASE_PACKAGE, { listOf(cert) }, {
                JsonArray(listOf(broken, release(version = "0.10.10"))).toString()
            }, { error("Requested an invalid asset") }) }
        }
    }

    @Test fun rejectsChangedMetadataIncompatibleIdentityAndConflictingCode() {
        val metadata = info()
        fun check(candidate: JsonObject, expected: ReleaseProblem) {
            val bytes = candidate.toString().encodeToByteArray()
            problem(expected) { one(release(apk(), build(bytes)), bytes) }
        }
        check(metadata.set("sourceTag", "v0.10.16"), ReleaseProblem.INVALID)
        check(metadata.set("versionName", "0.10.18"), ReleaseProblem.INVALID)
        check(metadata.set("versionCode", "32"), ReleaseProblem.INVALID)
        check(metadata.set("signingCertificateSha256", "missing"), ReleaseProblem.INVALID)
        check(info(code = old.code), ReleaseProblem.INCOMPATIBLE)
        check(metadata.set("applicationId", "$RELEASE_PACKAGE.bench"), ReleaseProblem.INCOMPATIBLE)
        check(metadata.set("variant", "kitLab"), ReleaseProblem.INCOMPATIBLE)
        check(metadata.set("signingCertificateSha256", "0".repeat(64)), ReleaseProblem.INCOMPATIBLE)
        val bytes = metadata.toString().encodeToByteArray()
        problem(ReleaseProblem.INVALID) { one(release(apk(), build(bytes)), bytes.reversedArray()) }
    }

    @Test fun malformedResponsesAndFailuresAreNeverNoUpdate() {
        for (broken in listOf("{}", "[", "[{}]", "[{\"tag_name\":\"v1.0.0\"}]", JsonArray(listOf(release(version = "1.00.0"))).toString())) {
            problem(ReleaseProblem.INVALID) { runBlocking { findRelease(old, RELEASE_PACKAGE, { listOf(cert) }, { broken }, { error("Build info") }) } }
        }
        fun failHttp(status: Int, expected: ReleaseProblem, body: String = "", exhausted: Boolean = false) {
            val response = Response.Builder().request(Request.Builder().url(STACKLIFE_REPOSITORY).build())
                .protocol(Protocol.HTTP_1_1).code(status).message("Fixture").body(body.toResponseBody())
                .apply { if (exhausted) addHeader("X-RateLimit-Remaining", "0") }.build()
            response.use {
                problem(expected) { runBlocking {
                    findRelease(old, RELEASE_PACKAGE, { listOf(cert) }, { throw httpFailure(response) }, { error("Build info") })
                } }
            }
        }
        failHttp(429, ReleaseProblem.RATE_LIMIT)
        failHttp(403, ReleaseProblem.RATE_LIMIT, exhausted = true)
        failHttp(403, ReleaseProblem.RATE_LIMIT, """{"message":"You have exceeded a secondary rate limit."}""")
        failHttp(403, ReleaseProblem.NETWORK)
        failHttp(502, ReleaseProblem.NETWORK)
    }

    @Test fun redirectRequiresHttpsGitHubReleaseAssetHostAndPathWithoutCredentials() {
        val host = "release-assets.githubusercontent.com"
        val path = "/github-production-release-asset/1464773004/asset?sig=fixture"
        assertTrue(isReleaseAssetRedirect("https://$host$path"))
        for (url in listOf("http://$host$path", "https://$host.example.org$path", "https://$host@elsewhere.org$path",
            "https://user@$host$path", "https://$host:8443$path", "https://$host$path#fragment", "https://$host/api/download",
            "https://$host/github-production-release-asset/../unrelated", "/github-production-release-asset/1/2")) {
            assertFalse(url, isReleaseAssetRedirect(url))
        }
    }

    @Test fun verifiesBoundedStreamRejectsIncompleteOrCorruptBytesAndHonorsCancellation() {
        val bytes = ByteArray(173_000) { it.toByte() }
        val update = ReleaseUpdate(name, 32, "", bytes.size.toLong(), bytes.sha())
        val out = ByteArrayOutputStream()
        val progress = mutableListOf<Int>()
        verifyBytes(bytes.inputStream(), out, update, EmptyCoroutineContext, progress::add)
        assertArrayEquals(bytes, out.toByteArray())
        assertTrue(progress.isNotEmpty() && progress == progress.distinct().sorted() && progress.last() == 99)
        for (wrong in listOf(bytes.copyOf(bytes.size - 1), bytes.copyOf(bytes.size + 1), bytes.reversedArray())) {
            problem(ReleaseProblem.INVALID) { verifyBytes(wrong.inputStream(), null, update, EmptyCoroutineContext) {} }
        }
        val cancelled = Job().apply { cancel() }
        assertThrows(java.util.concurrent.CancellationException::class.java) { verifyBytes(bytes.inputStream(), out, update, cancelled) {} }
    }
}
