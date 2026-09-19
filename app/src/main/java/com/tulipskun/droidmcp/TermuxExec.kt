package com.tulipskun.droidmcp

import android.os.Build
import java.io.File

object TermuxExec {
    private const val LINKER_64 = "/system/bin/linker64"
    private const val LINKER_32 = "/system/bin/linker"

    fun start(binary: File, vararg args: String[], environment: Map<String, String> = emptyMap()): Process {
        require(binary.isFile) { "Executable does not exist: " + binary.absolutePath }
        require(binary.canRead()) { "Executable is not readable: " + binary.absolutePath }

        val linker = if (is64Bit()) LINKER_64 else LINKER_32
        val command = ArrayList<String>(args.size + 1)
        command += binary.absolutePath
        command.addAll(args)

        return ProcessBuilder(listOf(linker) + command)
            .redirectErrorStream(true)
            .apply {
                environment().putAll(environment)
                environment().remove("LD_PRELOAD")
                environment().remove("LD_LIBRARY_PATH")
                environment().remove("TERMUX_EXEC__PROC_SELF_EXE")
            }
            .start()
    }

    private fun is64Bit(): Boolean {
        return Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
    }
}
