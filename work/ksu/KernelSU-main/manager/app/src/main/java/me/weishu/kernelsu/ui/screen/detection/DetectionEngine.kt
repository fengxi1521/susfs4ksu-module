package me.weishu.kernelsu.ui.screen.detection

import android.content.Context
import android.os.Process
import androidx.annotation.StringRes
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.util.getRootShell
import java.io.File

/**
 * Read-only environment probes ported from Duck Detector's detector set.
 *
 * Every probe collects evidence instead of guessing: a check reports [DetectionStatus.UNKNOWN]
 * when the underlying interface is not readable, and [DetectionStatus.NOTICE] when the signal is
 * expected on a device that already runs this manager.
 */
object DetectionEngine {

    private const val MAX_DETAILS = 16

    private val ROOT_MANAGER_PACKAGES = setOf(
        // KernelSU family.
        "me.weishu.kernelsu",
        "io.github.a13e300.mksu",
        "com.rifsxd.ksunext",
        "com.sukisu.ultra",
        "com.resukisu.resukisu",
        // APatch / KernelPatch.
        "me.bmax.apatch",
        // SKRoot.
        "com.linux.permissionmanager",
        // Magisk.
        "com.topjohnwu.magisk",
        "io.github.vvb2060.magisk",
        // Legacy superuser managers.
        "eu.chainfire.supersu",
        "com.noshufou.android.su",
        "com.koushikdutta.superuser",
        "com.kingroot.kinguser",
        "com.kingo.root",
    )

    private val ROOT_MANAGER_DIRECTORIES = listOf(
        "/data/adb/ksu",
        "/data/adb/ksunext",
        "/data/adb/magisk",
        "/data/adb/ap",
        "/data/adb/apatch",
    )

    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/system/sbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/system/bin/failsafe/su",
        "/data/local/su",
        "/data/local/bin/su",
        "/data/local/xbin/su",
    )

    private val ZYGISK_PATHS = listOf(
        "/data/adb/zygisk",
        "/dev/zygisk",
        "/data/adb/magisk/zygisk",
    )

    private val LSPOSED_PACKAGES = setOf(
        "org.lsposed.manager",
        "org.lsposed.lspatch",
        "de.robv.android.xposed.installer",
        "me.weishu.exp",
    )

    private val LSPOSED_PATHS = listOf(
        "/data/adb/lspd",
        "/data/adb/modules/riru_lsposed",
        "/data/adb/modules/zygisk_lsposed",
        "/data/adb/modules/lsposed",
    )

    private val KERNEL_MODULE_PATHS = listOf(
        "/sys/module/kernelsu",
        "/sys/module/ksu",
        "/sys/module/kernelsu_next",
        "/sys/module/kernelpatch",
        "/sys/module/apatch",
        "/sys/module/sukisu",
    )

    private val KERNEL_MARKERS = listOf(
        "kernelsu", "sukisu", "kernelpatch", "apatch", "-dirty", "lineage", "crdroid",
    )

    private val CUSTOM_ROM_PROPERTIES = listOf(
        "ro.lineage.version",
        "ro.modversion",
        "ro.pixelexperience.version",
        "ro.arrow.version",
        "ro.crdroid.version",
        "ro.evolution.version",
        "ro.havoc.version",
        "ro.aospa.version",
        "ro.derpfest.version",
        "ro.resurrectionremix.version",
        "ro.omni.version",
    )

    private val VIRTUALIZATION_FILES = listOf(
        "/dev/goldfish_pipe",
        "/dev/qemu_pipe",
        "/system/lib64/libgoldfish.so",
        "/vendor/lib64/libgoldfish.so",
    )

    /** Hook, hiding, root and modification tools. Labels mirror Duck Detector's catalog. */
    private val SENSITIVE_PACKAGES = mapOf(
        "org.lsposed.manager" to "LSPosed Manager",
        "org.lsposed.lspatch" to "LSPatch",
        "de.robv.android.xposed.installer" to "Xposed Installer",
        "me.weishu.exp" to "TaiChi",
        "me.simpleHook" to "SimpleHook",
        "top.hookvip.pro" to "HookVip Pro",
        "com.bug.hookvip" to "HookVip",
        "lin.xposed" to "Lin Xposed",
        "io.github.qauxv" to "QAuxiliary",
        "com.omarea.vtools" to "Scene",
        "github.tornaco.android.thanos" to "Thanox",
        "com.sevtinge.hyperceiler" to "HyperCeiler",
        "com.tsng.hidemyapplist" to "Hide My Applist",
        "org.frknkrc44.hma_oss" to "HMA-OSS",
        "com.tsng.pzyhrx.hma" to "HMA",
        "com.lerist.fakelocation" to "Fake Location",
        "com.zhufucyd.motion_emulator" to "Motion Emulator",
        "lucky.patcher" to "Lucky Patcher",
        "com.chelpus.lackypatch" to "Lucky Patcher",
        "bin.mt.termex" to "MT Manager",
        "ru.maximoff.apktool" to "APKTool",
        "com.houvven.guise" to "Guise",
        "com.houvven.impad" to "IMPad",
        "cn.geektang.privacyspace" to "Privacy Space",
        "moe.shizuku.privileged.api" to "Shizuku",
        "moe.shizuku.redirectstorage" to "Storage Redirect",
        "com.termux" to "Termux",
        "eu.chainfire.supersu" to "SuperSU",
        "com.kingroot.kinguser" to "KingRoot",
        "com.topjohnwu.magisk" to "Magisk",
        "io.github.vvb2060.magisk" to "Magisk alpha",
        "me.bmax.apatch" to "APatch",
        "com.sukisu.ultra" to "SukiSU Ultra",
        "com.resukisu.resukisu" to "ReSukiSU",
        "com.rifsxd.ksunext" to "KernelSU-Next",
    )

    suspend fun scan(context: Context): DetectionReport = withContext(Dispatchers.IO) {
        val probe = EnvironmentProbe()
        val packages = probe.packages()
        val properties = probe.properties()
        val mounts = probe.mounts()

        val findings = listOf(
            checkRootManager(probe, packages, context.packageName),
            checkSuBinary(probe),
            checkZygisk(probe),
            checkLsposed(probe, packages),
            checkSelinux(probe, properties),
            checkBootloader(properties),
            checkMounts(probe, mounts),
            checkSystemProperties(properties),
            checkKernel(probe),
            checkAdb(probe, properties),
            checkCustomRom(properties),
            checkVirtualization(probe, properties),
            checkSensitiveApps(packages, context.packageName),
        )

        DetectionReport(
            findings = findings,
            scannedAtMillis = System.currentTimeMillis(),
            rootAvailable = probe.isRootAvailable,
        )
    }

    private fun finding(
        id: String,
        @StringRes titleRes: Int,
        @StringRes summaryRes: Int,
        status: DetectionStatus,
        details: List<String>,
    ) = DetectionFinding(
        id = id,
        titleRes = titleRes,
        summaryRes = summaryRes,
        status = status,
        details = details.take(MAX_DETAILS),
    )

    private fun checkRootManager(
        probe: EnvironmentProbe,
        packages: Set<String>,
        selfPackage: String,
    ): DetectionFinding {
        val foundPackages = ROOT_MANAGER_PACKAGES.filter { it in packages && it != selfPackage }
        val foundDirectories = ROOT_MANAGER_DIRECTORIES.filter { probe.exists(it) }
        val details = buildList {
            foundPackages.forEach { add("package: $it") }
            foundDirectories.forEach { add("directory: $it") }
        }
        val status = when {
            foundPackages.isNotEmpty() -> DetectionStatus.WARNING
            foundDirectories.isNotEmpty() -> DetectionStatus.NOTICE
            packages.isEmpty() -> DetectionStatus.UNKNOWN
            else -> DetectionStatus.CLEAN
        }
        return finding(
            "root_manager",
            R.string.detection_check_root_manager_title,
            R.string.detection_check_root_manager_summary,
            status,
            details,
        )
    }

    private fun checkSuBinary(probe: EnvironmentProbe): DetectionFinding {
        val foundPaths = SU_PATHS.filter { probe.exists(it) }
        val which = probe.exec("which su 2>/dev/null")?.trim()?.takeIf { it.isNotEmpty() }
        val details = buildList {
            foundPaths.forEach { add("path: $it") }
            if (which != null) add("which: $which")
        }
        val status = if (foundPaths.isEmpty() && which == null) {
            DetectionStatus.CLEAN
        } else {
            // KernelSU keeps /system/bin/su as an expected compatibility shim.
            DetectionStatus.NOTICE
        }
        return finding(
            "su_binary",
            R.string.detection_check_su_binary_title,
            R.string.detection_check_su_binary_summary,
            status,
            details,
        )
    }

    private fun checkZygisk(probe: EnvironmentProbe): DetectionFinding {
        val foundPaths = ZYGISK_PATHS.filter { probe.exists(it) }
        val moduleDirectories = probe.moduleDirectoriesNamed("zygisk")
        val injected = probe.mapEvidence("zygisk", "libzygisk")
        val details = buildList {
            foundPaths.forEach { add("path: $it") }
            moduleDirectories.forEach { add("module: $it") }
            injected.forEach { add("maps: $it") }
        }
        val status = if (foundPaths.isNotEmpty() || moduleDirectories.isNotEmpty() || injected.isNotEmpty()) {
            DetectionStatus.WARNING
        } else {
            DetectionStatus.CLEAN
        }
        return finding(
            "zygisk",
            R.string.detection_check_zygisk_title,
            R.string.detection_check_zygisk_summary,
            status,
            details,
        )
    }

    private fun checkLsposed(probe: EnvironmentProbe, packages: Set<String>): DetectionFinding {
        val foundPackages = LSPOSED_PACKAGES.filter { it in packages }
        val foundPaths = LSPOSED_PATHS.filter { probe.exists(it) }
        val moduleDirectories = probe.moduleDirectoriesNamed("lsposed")
        val injected = probe.mapEvidence("lspd", "liblspd", "xposed", "edxp")
        val processes = probe.processEvidence("lspd")
        val details = buildList {
            foundPackages.forEach { add("package: $it") }
            foundPaths.forEach { add("path: $it") }
            moduleDirectories.forEach { add("module: $it") }
            injected.forEach { add("maps: $it") }
            processes.forEach { add("process: $it") }
        }
        val status = if (details.isNotEmpty()) DetectionStatus.WARNING else DetectionStatus.CLEAN
        return finding(
            "lsposed",
            R.string.detection_check_lsposed_title,
            R.string.detection_check_lsposed_summary,
            status,
            details,
        )
    }

    private fun checkSelinux(
        probe: EnvironmentProbe,
        properties: Map<String, String>,
    ): DetectionFinding {
        val getenforce = probe.exec("getenforce")?.lineSequence()?.firstOrNull()?.trim()
        val enforceFile = probe.fileContent("/sys/fs/selinux/enforce")
        val bootSelinux = properties["ro.boot.selinux"]
        val mode = when {
            getenforce.equals("Enforcing", true) -> "Enforcing"
            getenforce.equals("Permissive", true) -> "Permissive"
            getenforce.equals("Disabled", true) -> "Disabled"
            bootSelinux.equals("enforcing", true) -> "Enforcing"
            bootSelinux.equals("permissive", true) -> "Permissive"
            enforceFile == "1" -> "Enforcing"
            enforceFile == "0" -> "Permissive"
            else -> null
        }
        val details = buildList {
            if (!getenforce.isNullOrBlank()) add("getenforce: $getenforce")
            if (!bootSelinux.isNullOrBlank()) add("ro.boot.selinux: $bootSelinux")
            if (!enforceFile.isNullOrBlank()) add("/sys/fs/selinux/enforce: $enforceFile")
        }
        val status = when (mode) {
            "Enforcing" -> DetectionStatus.CLEAN
            "Permissive", "Disabled" -> DetectionStatus.WARNING
            else -> DetectionStatus.UNKNOWN
        }
        return finding(
            "selinux",
            R.string.detection_check_selinux_title,
            R.string.detection_check_selinux_summary,
            status,
            details,
        )
    }

    private fun checkBootloader(properties: Map<String, String>): DetectionFinding {
        val verifiedBootState = properties["ro.boot.verifiedbootstate"]
        val flashLocked = properties["ro.boot.flash.locked"]
        val warrantyBit = properties["ro.boot.warranty_bit"]
        val verityMode = properties["ro.boot.veritymode"]
        val vbmetaState = properties["ro.boot.vbmeta.device_state"]
        val details = buildList {
            if (!verifiedBootState.isNullOrBlank()) add("ro.boot.verifiedbootstate: $verifiedBootState")
            if (!flashLocked.isNullOrBlank()) add("ro.boot.flash.locked: $flashLocked")
            if (!warrantyBit.isNullOrBlank()) add("ro.boot.warranty_bit: $warrantyBit")
            if (!verityMode.isNullOrBlank()) add("ro.boot.veritymode: $verityMode")
            if (!vbmetaState.isNullOrBlank()) add("ro.boot.vbmeta.device_state: $vbmetaState")
        }
        val status = when {
            verifiedBootState.isNullOrBlank() && flashLocked.isNullOrBlank() -> DetectionStatus.UNKNOWN
            verifiedBootState.equals("green", true) && flashLocked == "1" -> DetectionStatus.CLEAN
            else -> DetectionStatus.WARNING
        }
        return finding(
            "bootloader",
            R.string.detection_check_bootloader_title,
            R.string.detection_check_bootloader_summary,
            status,
            details,
        )
    }

    private fun checkMounts(probe: EnvironmentProbe, mounts: List<MountEntry>): DetectionFinding {
        val suspicious = mounts.filter { entry ->
            val systemMount = entry.mountPoint == "/" ||
                entry.mountPoint.startsWith("/system") ||
                entry.mountPoint.startsWith("/vendor") ||
                entry.mountPoint.startsWith("/product") ||
                entry.mountPoint.startsWith("/odm")
            systemMount && (
                entry.fileSystem == "overlay" ||
                    entry.fileSystem == "tmpfs" ||
                    entry.device.startsWith("/dev/loop")
                )
        }
        val moduleCount = probe.moduleDirectoryCount()
        val details = buildList {
            suspicious.forEach { add("${it.mountPoint} <- ${it.fileSystem} (${it.device})") }
            if (moduleCount > 0) add("modules: $moduleCount under /data/adb/modules")
        }
        val status = when {
            suspicious.isNotEmpty() -> DetectionStatus.WARNING
            moduleCount > 0 -> DetectionStatus.NOTICE
            mounts.isEmpty() -> DetectionStatus.UNKNOWN
            else -> DetectionStatus.CLEAN
        }
        return finding(
            "mount",
            R.string.detection_check_mount_title,
            R.string.detection_check_mount_summary,
            status,
            details,
        )
    }

    private fun checkSystemProperties(properties: Map<String, String>): DetectionFinding {
        val anomalies = listOf(
            "ro.debuggable",
            "ro.secure",
            "ro.adb.secure",
            "ro.build.type",
            "ro.build.tags",
            "service.adb.root",
        ).mapNotNull { key ->
            val value = properties[key] ?: return@mapNotNull null
            val suspicious = when (key) {
                "ro.debuggable" -> value == "1"
                "ro.secure" -> value == "0"
                "ro.adb.secure" -> value == "0"
                "ro.build.type" -> value == "userdebug" || value == "eng"
                "ro.build.tags" -> value.contains("test-keys")
                "service.adb.root" -> value == "1"
                else -> false
            }
            if (suspicious) "$key: $value" else null
        }
        val status = when {
            anomalies.any { it.startsWith("ro.debuggable") || it.startsWith("ro.secure") } ->
                DetectionStatus.WARNING
            anomalies.isNotEmpty() -> DetectionStatus.NOTICE
            properties.isEmpty() -> DetectionStatus.UNKNOWN
            else -> DetectionStatus.CLEAN
        }
        return finding(
            "system_properties",
            R.string.detection_check_system_properties_title,
            R.string.detection_check_system_properties_summary,
            status,
            anomalies,
        )
    }

    private fun checkKernel(probe: EnvironmentProbe): DetectionFinding {
        val release = probe.exec("uname -r")?.trim()?.takeIf { it.isNotEmpty() }
        val versionFile = probe.fileContent("/proc/version")
        val versionLine = versionFile?.lineSequence()?.firstOrNull()?.trim()
        val modulePaths = KERNEL_MODULE_PATHS.filter { probe.exists(it) }
        val markers = KERNEL_MARKERS.filter { marker ->
            release?.contains(marker, true) == true || versionLine?.contains(marker, true) == true
        }
        val symbols = probe.exec("grep -m 3 -i kernelsu /proc/kallsyms 2>/dev/null")
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.take(3)
            ?.toList()
            .orEmpty()
        val details = buildList {
            if (release != null) add("uname -r: $release")
            modulePaths.forEach { add("module: $it") }
            markers.forEach { add("version marker: $it") }
            symbols.forEach { add("kallsyms: $it") }
        }
        val status = if (modulePaths.isNotEmpty() || markers.isNotEmpty() || symbols.isNotEmpty()) {
            DetectionStatus.NOTICE
        } else if (release.isNullOrBlank() && versionLine.isNullOrBlank()) {
            DetectionStatus.UNKNOWN
        } else {
            DetectionStatus.CLEAN
        }
        return finding(
            "kernel",
            R.string.detection_check_kernel_title,
            R.string.detection_check_kernel_summary,
            status,
            details,
        )
    }

    private fun checkAdb(
        probe: EnvironmentProbe,
        properties: Map<String, String>,
    ): DetectionFinding {
        val adbEnabled = probe.exec("settings get global adb_enabled 2>/dev/null")?.trim()
        val tcpPort = properties["service.adb.tcp.port"] ?: properties["persist.adb.tcp.port"]
        val adbSecure = properties["ro.adb.secure"]
        val usbConfig = properties["persist.sys.usb.config"]
        val networkAdb = probe.networkAdbListening()
        val details = buildList {
            if (!adbEnabled.isNullOrBlank()) add("adb_enabled: $adbEnabled")
            if (!tcpPort.isNullOrBlank()) add("adb tcp port: $tcpPort")
            if (!adbSecure.isNullOrBlank()) add("ro.adb.secure: $adbSecure")
            if (!usbConfig.isNullOrBlank()) add("persist.sys.usb.config: $usbConfig")
            if (networkAdb) add("listening: tcp/5555")
        }
        val status = when {
            networkAdb || tcpPort?.isNotBlank() == true || adbSecure == "0" -> DetectionStatus.WARNING
            adbEnabled == "1" -> DetectionStatus.NOTICE
            adbEnabled.isNullOrBlank() && properties.isEmpty() -> DetectionStatus.UNKNOWN
            else -> DetectionStatus.CLEAN
        }
        return finding(
            "adb",
            R.string.detection_check_adb_title,
            R.string.detection_check_adb_summary,
            status,
            details,
        )
    }

    private fun checkCustomRom(properties: Map<String, String>): DetectionFinding {
        val markers = CUSTOM_ROM_PROPERTIES.mapNotNull { key ->
            properties[key]?.takeIf { it.isNotBlank() }?.let { "$key: $it" }
        }
        val fingerprint = properties["ro.build.fingerprint"]
        val tags = properties["ro.build.tags"]
        val details = buildList {
            addAll(markers)
            if (tags?.contains("test-keys") == true) add("ro.build.tags: $tags")
            if (!fingerprint.isNullOrBlank()) add("ro.build.fingerprint: $fingerprint")
        }
        val status = when {
            markers.isNotEmpty() -> DetectionStatus.NOTICE
            tags?.contains("test-keys") == true -> DetectionStatus.NOTICE
            properties.isEmpty() -> DetectionStatus.UNKNOWN
            else -> DetectionStatus.CLEAN
        }
        return finding(
            "custom_rom",
            R.string.detection_check_custom_rom_title,
            R.string.detection_check_custom_rom_summary,
            status,
            details,
        )
    }

    private fun checkVirtualization(
        probe: EnvironmentProbe,
        properties: Map<String, String>,
    ): DetectionFinding {
        val qemu = properties["ro.kernel.qemu"]
        val hardware = properties["ro.hardware"]
        val bootQemu = properties["ro.boot.qemu"]
        val avdName = properties["ro.boot.qemu.avd_name"]
        val foundFiles = VIRTUALIZATION_FILES.filter { probe.exists(it) }
        val emulatorHardware = hardware in setOf("goldfish", "ranchu")
        val details = buildList {
            if (!qemu.isNullOrBlank()) add("ro.kernel.qemu: $qemu")
            if (!hardware.isNullOrBlank()) add("ro.hardware: $hardware")
            if (!bootQemu.isNullOrBlank()) add("ro.boot.qemu: $bootQemu")
            if (!avdName.isNullOrBlank()) add("ro.boot.qemu.avd_name: $avdName")
            foundFiles.forEach { add("path: $it") }
        }
        val status = if (qemu == "1" || bootQemu == "1" || emulatorHardware || foundFiles.isNotEmpty()) {
            DetectionStatus.WARNING
        } else if (properties.isEmpty()) {
            DetectionStatus.UNKNOWN
        } else {
            DetectionStatus.CLEAN
        }
        return finding(
            "virtualization",
            R.string.detection_check_virtualization_title,
            R.string.detection_check_virtualization_summary,
            status,
            details,
        )
    }

    private fun checkSensitiveApps(
        packages: Set<String>,
        selfPackage: String,
    ): DetectionFinding {
        val found = packages
            .filter { it != selfPackage }
            .mapNotNull { packageName ->
                val label = SENSITIVE_PACKAGES[packageName] ?: return@mapNotNull null
                "$packageName ($label)"
            }
            .sorted()
        val status = when {
            found.isNotEmpty() -> DetectionStatus.WARNING
            packages.isEmpty() -> DetectionStatus.UNKNOWN
            else -> DetectionStatus.CLEAN
        }
        return finding(
            "dangerous_apps",
            R.string.detection_check_dangerous_apps_title,
            R.string.detection_check_dangerous_apps_summary,
            status,
            found,
        )
    }
}

private data class MountEntry(
    val device: String,
    val mountPoint: String,
    val fileSystem: String,
)

private class EnvironmentProbe {
    private val shell: Shell? = runCatching { getRootShell() }.getOrNull()

    val isRootAvailable: Boolean = shell?.isRoot == true

    fun exec(command: String): String? {
        val target = shell ?: return null
        return runCatching {
            val out = ArrayList<String>()
            target.newJob().add(command).to(out, null).exec()
            out.joinToString("\n").trim()
        }.getOrNull()
    }

    fun exists(path: String): Boolean = File(path).exists()

    fun fileContent(path: String): String? =
        exec("cat $path 2>/dev/null")?.takeIf { it.isNotBlank() }

    fun packages(): Set<String> {
        val raw = exec("pm list packages 2>/dev/null") ?: return emptySet()
        return raw.lineSequence()
            .mapNotNull { line ->
                line.trim().takeIf { it.startsWith("package:") }?.removePrefix("package:")
            }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    fun properties(): Map<String, String> {
        val raw = exec("getprop") ?: return emptyMap()
        val pattern = Regex("^\\[(.*?)]: \\[(.*?)]$")
        return raw.lineSequence()
            .mapNotNull { line ->
                val match = pattern.find(line.trim()) ?: return@mapNotNull null
                val key = match.groupValues[1]
                if (key.isEmpty()) null else key to match.groupValues[2]
            }
            .toMap()
    }

    fun mounts(): List<MountEntry> {
        val raw = exec("cat /proc/mounts 2>/dev/null") ?: return emptyList()
        return raw.lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(' ')
                if (parts.size < 3) null else MountEntry(parts[0], parts[1], parts[2])
            }
            .toList()
    }

    /** Matches paths of this process that contain any of [tokens]. */
    fun mapEvidence(vararg tokens: String): List<String> {
        val maps = fileContent("/proc/${Process.myPid()}/maps") ?: return emptyList()
        return maps.lineSequence()
            .filter { line -> tokens.any { token -> line.contains(token, ignoreCase = true) } }
            .map { line -> line.substringAfterLast(' ').trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(4)
            .toList()
    }

    fun processEvidence(token: String): List<String> {
        val raw = exec("ps -A 2>/dev/null") ?: return emptyList()
        return raw.lineSequence()
            .filter { it.contains(token, ignoreCase = true) }
            .map { it.trim() }
            .take(3)
            .toList()
    }

    fun moduleDirectoriesNamed(name: String): List<String> {
        val raw = exec(
            "find /data/adb/modules -maxdepth 2 -type d -iname '*$name*' 2>/dev/null"
        ) ?: return emptyList()
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(4)
            .toList()
    }

    fun moduleDirectoryCount(): Int {
        val raw = exec("ls -1 /data/adb/modules 2>/dev/null") ?: return 0
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "modules_update" }
            .count()
    }

    fun networkAdbListening(): Boolean {
        val tcp = fileContent("/proc/net/tcp") ?: ""
        val tcp6 = fileContent("/proc/net/tcp6") ?: ""
        return (tcp + "\n" + tcp6).lineSequence().any { line ->
            // 5555 == 0x15B3, state 0A == TCP_LISTEN.
            line.contains(":15B3") && line.contains(" 0A ")
        }
    }
}
