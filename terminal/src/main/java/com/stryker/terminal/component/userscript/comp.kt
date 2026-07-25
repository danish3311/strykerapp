package com.stryker.terminal.component.userscript

import android.content.Context
import android.system.Os
import com.stryker.terminal.App
import com.stryker.terminal.component.NeoComponent
import com.stryker.terminal.component.config.NeoTermPath
import com.stryker.terminal.ui.other.SuUtils
import com.stryker.terminal.utils.NLog
import com.stryker.terminal.utils.extractAssetsDir
import java.io.File

class UserScript(val scriptFile: File)

class UserScriptComponent : NeoComponent {
  var binFiles = listOf<UserScript>()
  var userScripts = listOf<UserScript>()
  private val binDir = File(NeoTermPath.BIN_PATH)
  private val scriptDir = File(NeoTermPath.USER_SCRIPT_PATH)

  override fun onServiceInit() = checkForFiles()

  override fun onServiceDestroy() {
  }

  override fun onServiceObtained() = checkForFiles()

  fun extractDefaultScript(context: Context) = kotlin.runCatching {
    val binReady = File(NeoTermPath.BIN_PATH, "bash").exists()
      && File(NeoTermPath.BIN_PATH, "stryker-ch").exists()
    if (!binReady) {
      SuUtils.customCommand("mkdir -p ${NeoTermPath.USR_PATH}/")
      context.extractAssetsDir("bin", NeoTermPath.BIN_PATH, overwrite = true)
    }
    // 0755 — do NOT pass decimal 448 to shell chmod (that is Os.chmod mode).
    for (f in (binDir.listFiles() ?: emptyArray())) {
      try {
        Os.chmod(f.absolutePath, 493) // 0755
      } catch (_: Exception) {
        f.setExecutable(true, false)
      }
    }

    context.extractAssetsDir("scripts", NeoTermPath.USER_SCRIPT_PATH)
    scriptDir.listFiles()?.forEach {
      try {
        Os.chmod(it.absolutePath, 493)
      } catch (_: Exception) {
        it.setExecutable(true, false)
      }
    }
  }.onFailure {
    NLog.e("UserScript", "Failed to extract default user scripts: ${it.localizedMessage}")
  }


  private fun checkForFiles() {
    extractDefaultScript(App.get())
    reloadScripts()
  }

  private fun reloadScripts() {
    userScripts = (scriptDir.listFiles() ?: emptyArray())
      .filter { it.canExecute() }
      .map { UserScript(it) }
      .toList()

    binFiles = (binDir.listFiles() ?: emptyArray())
      .filter { it.canExecute() }
      .map { UserScript(it) }
      .toList()
  }

}
