package com.drivevault.core

import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlin.test.*

class CompatTest {
    private val dir = File(System.getProperty("dv.testdir").ifEmpty { "/tmp/compat1" })
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun pythonVaultWorksInKotlin() {
        ITERATIONS = 1000; KEY_ITERATIONS = 1000; QUESTION_ITERATIONS = 1000; ARGON2_MEMORY_KIB = 2048; ARGON2_ITERATIONS = 1
        val expect = Json.parseToJsonElement(File(dir, "expect.json").readText()).jsonObject
        val root = File(dir, "DriveVault/vault")
        val v = Vault(root)
        assertEquals("pbkdf2-sha256", v.kdfName(), "python made a pre-1.8 style vault")
        assertFailsWith<WrongPassword> { v.unlock("wrong password") }
        v.unlock("py password 1")
        // every file decrypts to the same bytes Python wrote
        for ((id, h) in expect["files"]!!.jsonObject) {
            assertEquals(h.jsonPrimitive.content, sha(v.readAll(id)), "content of ${v.get(id).name}")
        }
        println("kotlin read ${expect["files"]!!.jsonObject.size} python files OK")
        // random access across chunk boundaries
        val big = v.entries.entries.first { it.value.name == "big.bin" }.key
        val all = v.readAll(big)
        v.Reader(big).use { r ->
            for (pos in listOf(0L, CHUNK - 5L, CHUNK.toLong(), 3L * CHUNK + 700)) {
                val buf = ByteArray(10); val k = r.read(pos, buf, 0, 10)
                assertContentEquals(all.copyOfRange(pos.toInt(), pos.toInt() + k), buf.copyOf(k))
            }
        }
        assertTrue(v.inTrash(expect["trashed"]!!.jsonPrimitive.content)); assertEquals(1, v.trashCount())
        val rootFiles = v.children(ROOT).filter { !it.isDir }.sortedBy { it.order ?: Double.MAX_VALUE }.map { it.name }
        println("custom order from python: $rootFiles")
        val h = v.historyRead(); assertTrue(h.problems.isEmpty(), h.problems.toString()); assertEquals(2, h.events.size)
        assertEquals(listOf("Q1?", "Q2?", "Q3?"), v.recoveryInfo().questions)
        v.lockNow()

        // recovery with answers written in Telugu + different spacing/case/punctuation
        assertFailsWith<WrongPassword> { v.recover("questions", null, listOf("blue bell school", "తెలుగు పాఠశాల", "rex 98"), "new pass 123") }
        v.recover("questions", null, listOf("  BLUE-bell school ", "తెలుగు   పాఠశాల!", "rex 99"), "kotlin pass 1")
        v.lockNow()
        v.recover("key", expect["key"]!!.jsonPrimitive.content.lowercase().replace("-", " "), null, "kotlin pass 2")
        assertEquals("argon2id", v.kdfName(), "a password reset upgrades the KDF")

        // an Argon2id vault made by python (python's m/t/p are read from its vault.json)
        Vault(File(dir, "py2/DriveVault/vault")).run {
            assertFailsWith<WrongPassword> { unlock("py argon 2") }
            unlock("py argon 1")
            assertEquals("made by python with argon2id", readAll(expect["argon_file"]!!.jsonPrimitive.content).toString(Charsets.UTF_8))
            lockNow()
        }

        // write from Kotlin: new folder, files, trash ops, order, history
        val ph = v.mkdir(ROOT, "Phone uploads")
        val data = ByteArray(2 * CHUNK + 123) { (it * 31).toByte() }
        val id = v.addFile(ph, "from android.bin", ByteArrayInputStream(data))
        v.addFile(ph, "exact.bin", ByteArrayInputStream(ByteArray(CHUNK) { 7 }))
        assertEquals(sha(data), sha(v.readAll(id)))
        v.restore(listOf(expect["trashed"]!!.jsonPrimitive.content))
        v.reorder(ROOT, v.children(ROOT).filter { !it.isDir }.map { it.id }.sorted())
        v.historyAppend(buildJsonObject { put("t", System.currentTimeMillis()); put("ev", "encrypted"); put("name", "from android.bin"); put("host", "Pixel"); put("os", "Android") })
        v.setSetting("plainLogNames", JsonPrimitive(false))
        v.lockNow()
        println("kotlin wrote changes")

        // a brand new vault made by Kotlin
        val fresh = Vault(File(dir, "kt/DriveVault/vault")).also { File(dir, "kt").deleteRecursively() }
        fresh.create("kotlin made 1")
        assertEquals("argon2id", fresh.kdfName())
        val keyRef = fresh.master!!
        fresh.lockNow(); assertTrue(keyRef.all { it == 0.toByte() }, "master key wiped on lock"); fresh.unlock("kotlin made 1")
        fresh.addFile(ROOT, "hello.txt", ByteArrayInputStream("hello from kotlin".toByteArray()))
        fresh.historyAppend(buildJsonObject { put("t", 1); put("ev", "created") })
        println("kotlin recovery key: " + fresh.setRecoveryKey("kotlin made 1"))
        fresh.setQuestions("kotlin made 1", listOf("A?", "B?", "C?"), listOf("Hyderabad", "మంచిర్యాల", "R2-D2"))
        val dk = Crypto.random(32)
        fresh.setDevice("kotlin made 1", dk, "Pixel 8", "dev-123")
        fresh.lockNow()
        assertFailsWith<WrongPassword> { fresh.recoverWithDevice(Crypto.random(32), "via phone 1") }
        fresh.recoverWithDevice(dk, "via phone 1"); assertTrue(fresh.unlocked)
        assertEquals("Pixel 8", fresh.recoveryInfo().device)
        fresh.lockNow(); fresh.unlock("via phone 1"); fresh.lockNow()
    }
}
