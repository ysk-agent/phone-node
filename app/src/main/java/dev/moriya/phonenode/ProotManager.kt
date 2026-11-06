package dev.moriya.phonenode

import android.content.Context
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * Owns the Debian rootfs and the proot process that runs sshd and
 * opencode inside it.
 *
 * Layout under the app's private files directory:
 *   rootfs/            extracted Debian bookworm-slim (arm64)
 *   rootfs/opt/phonenode/  overlay: setup/run scripts, opencode tarball,
 *                          authorized_keys, .setup-done marker
 *   proot-tmp/         PROOT_TMP_DIR
 *
 * proot itself is bundled in jniLibs as libproot.so (the Termux build,
 * with its NEEDED entry rewritten to libtalloc.so) so it lives in the
 * native library directory — the one place an app may execute binaries
 * from on Android 10+. The light NodeService process only supervises;
 * if memory pressure kills something, the heavy proot side should die
 * first and be restarted from here.
 */
class ProotManager(context: Context) {

    private val appContext = context.applicationContext
    private val filesDir: File = appContext.filesDir
    private val rootfsDir = File(filesDir, "rootfs")
    private val overlayDir = File(rootfsDir, "opt/phonenode")
    private val tmpDir = File(filesDir, "proot-tmp")

    @Volatile
    private var supervising = false
    @Volatile
    private var process: Process? = null

    fun startSupervision() {
        if (supervising) return
        supervising = true
        thread(name = "proot-supervisor", isDaemon = true) {
            try {
                ensureSetup()
            } catch (t: Throwable) {
                // Setup failed (extraction or the in-rootfs script). The
                // status endpoint keeps reporting prootRunning=false;
                // a later service restart retries from the marker check.
                CrashLog.record(appContext, "proot-setup", t)
                return@thread
            }
            while (supervising) {
                try {
                    val p = startProot(
                        listOf("/bin/sh", "/opt/phonenode/run-node.sh"),
                        File(filesDir, "proot-run.log"),
                        append = true
                    )
                    process = p
                    NodeState.prootRunning = true
                    p.waitFor()
                } catch (t: Throwable) {
                    CrashLog.record(appContext, "proot-run", t)
                } finally {
                    NodeState.prootRunning = false
                    process = null
                }
                if (supervising) {
                    try {
                        Thread.sleep(5_000)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
        }
    }

    fun stop() {
        supervising = false
        process?.destroy()
        process = null
        NodeState.prootRunning = false
    }

    // -- setup ------------------------------------------------------------

    private fun ensureSetup() {
        // The overlay scripts must track the installed APK even after
        // setup has completed: run-node.sh is re-read on every service
        // start, so an app update that changes a script has to land
        // here, not only in the first-time setup path. (Before this
        // fix, a node whose setup was done kept running the old
        // run-node.sh forever, and script changes in new builds
        // silently never applied.)
        copyOverlay()
        if (File(overlayDir, ".setup-done").exists()) return
        // Extraction completeness is judged by its own marker, not by
        // the presence of bin/sh: a half-extracted tree can already
        // contain bin/sh and would poison every later step.
        val extractMarker = File(filesDir, ".extract-done")
        if (!extractMarker.exists()) {
            extractRootfs()
            extractMarker.writeText("done")
        }
        writeResolvConf()
        val setup = startProot(
            listOf("/bin/sh", "/opt/phonenode/node-setup.sh"),
            File(filesDir, "proot-setup.log"),
            append = false
        )
        val code = setup.waitFor()
        if (code != 0 || !File(overlayDir, ".setup-done").exists()) {
            throw IllegalStateException("in-rootfs setup failed with exit code $code")
        }
    }

    private fun extractRootfs() {
        rootfsDir.mkdirs()
        val input = TarArchiveInputStream(
            GzipCompressorInputStream(
                BufferedInputStream(appContext.assets.open("debian-rootfs.tgz"))
            )
        )
        input.use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val name = entry.name.removePrefix("./").removePrefix("/")
                if (name.isEmpty() || name.contains("..")) continue
                val dest = File(rootfsDir, name)
                when {
                    entry.isDirectory -> dest.mkdirs()
                    entry.isSymbolicLink -> {
                        dest.parentFile?.mkdirs()
                        if (dest.exists() || isSymlink(dest)) dest.delete()
                        Os.symlink(entry.linkName, dest.absolutePath)
                    }
                    entry.isLink -> {
                        // Hard link to another extracted file. The
                        // kernel's protected-hardlinks rule refuses
                        // links to files the caller cannot both read
                        // and write (Debian ships read-only targets),
                        // so a refused link falls back to a plain copy.
                        val targetName = entry.linkName.removePrefix("./").removePrefix("/")
                        val target = File(rootfsDir, targetName)
                        dest.parentFile?.mkdirs()
                        if (dest.exists() || isSymlink(dest)) dest.delete()
                        if (target.exists()) {
                            try {
                                Os.link(target.absolutePath, dest.absolutePath)
                            } catch (e: Exception) {
                                target.inputStream().use { input ->
                                    FileOutputStream(dest).use { out -> input.copyTo(out) }
                                }
                                try {
                                    Os.chmod(dest.absolutePath, entry.mode and 0b111_111_111)
                                } catch (ignored: Exception) {
                                    // Mode is cosmetic for a copied link.
                                }
                            }
                        }
                    }
                    entry.isFile -> {
                        dest.parentFile?.mkdirs()
                        // Never write through a symlink left by an
                        // earlier partial extraction.
                        if (isSymlink(dest)) dest.delete()
                        FileOutputStream(dest).use { out ->
                            tar.copyTo(out)
                        }
                        Os.chmod(dest.absolutePath, entry.mode and 0b111_111_111)
                    }
                    // Device nodes, fifos etc. are skipped: an app cannot
                    // create them, and proot binds the host /dev instead.
                }
            }
        }
    }

    private fun isSymlink(file: File): Boolean =
        try {
            Os.lstat(file.absolutePath).st_mode and 0b1111_0000_0000_0000 == 0b1010_0000_0000_0000
        } catch (e: Exception) {
            false
        }

    private fun copyOverlay() {
        overlayDir.mkdirs()
        for (name in listOf("opencode.tgz", "authorized_keys", "node-setup.sh", "run-node.sh")) {
            val dest = File(overlayDir, name)
            appContext.assets.open(name).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            if (name.endsWith(".sh")) {
                Os.chmod(dest.absolutePath, 0b111_101_101)
            }
        }
    }

    private fun writeResolvConf() {
        // Android does not expose the network's DNS servers to a rootfs,
        // so the node uses public resolvers.
        val resolv = File(rootfsDir, "etc/resolv.conf")
        resolv.parentFile?.mkdirs()
        if (resolv.exists() && !isSymlink(resolv)) resolv.delete()
        if (!resolv.exists()) {
            resolv.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        }
    }

    // -- proot invocation ---------------------------------------------------

    private fun startProot(args: List<String>, logFile: File, append: Boolean): Process {
        val nativeLibDir = appContext.applicationInfo.nativeLibraryDir
        tmpDir.mkdirs()
        val command = mutableListOf(
            "$nativeLibDir/libproot.so",
            // This app's SELinux domain refuses hard links inside its
            // data area, which dpkg needs (its status backup is a
            // link). proot's link2symlink extension transparently
            // turns guest hard links into symlinks instead.
            "--link2symlink",
            "-0",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "PATH=/usr/local/bin:/usr/local/sbin:/usr/bin:/usr/sbin:/bin:/sbin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8"
        )
        command.addAll(args)
        val builder = ProcessBuilder(command)
        builder.directory(filesDir)
        val env = builder.environment()
        env["PROOT_TMP_DIR"] = tmpDir.absolutePath
        env["PROOT_LOADER"] = "$nativeLibDir/libproot-loader.so"
        env["LD_LIBRARY_PATH"] = nativeLibDir
        // Guest output goes to a log file, never to an undrained pipe:
        // a pipe nobody reads fills up and wedges the guest process,
        // and the file doubles as on-device diagnostics.
        builder.redirectErrorStream(true)
        builder.redirectOutput(
            if (append) {
                ProcessBuilder.Redirect.appendTo(logFile)
            } else {
                ProcessBuilder.Redirect.to(logFile)
            }
        )
        return builder.start()
    }
}
