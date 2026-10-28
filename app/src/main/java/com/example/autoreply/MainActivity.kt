package com.example.autoreply

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var switchEnabled: Switch
    private lateinit var editNumbers: EditText
    private lateinit var editMessage: EditText
    private lateinit var editCooldown: EditText
    private lateinit var textStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        switchEnabled = findViewById(R.id.switchEnabled)
        editNumbers = findViewById(R.id.editNumbers)
        editMessage = findViewById(R.id.editMessage)
        editCooldown = findViewById(R.id.editCooldown)
        textStatus = findViewById(R.id.textStatus)

        switchEnabled.isChecked = prefs.enabled
        editNumbers.setText(prefs.numbersText)
        editMessage.setText(prefs.message)
        editCooldown.setText(prefs.cooldownMinutes.toString())

        findViewById<Button>(R.id.buttonSave).setOnClickListener { save() }
        findViewById<Button>(R.id.buttonSetup).setOnClickListener { requestSetup() }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun save() {
        prefs.numbersText = editNumbers.text.toString()
        prefs.message = editMessage.text.toString().ifBlank { Prefs.DEFAULT_MESSAGE }
        prefs.cooldownMinutes = editCooldown.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 30
        prefs.enabled = switchEnabled.isChecked
        Toast.makeText(this, "저장했어요 (대상 ${prefs.targetNumbers().size}개)", Toast.LENGTH_SHORT).show()
        updateStatus()
    }

    /** 권한 → 통화 스크리닝 앱 지정 순서로 요청 */
    private fun requestSetup() {
        val missing = arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
        } else {
            requestScreeningRole()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_PERMS) requestScreeningRole()
    }

    private fun requestScreeningRole() {
        val rm = getSystemService(RoleManager::class.java)
        if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
            !rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        ) {
            @Suppress("DEPRECATION")
            startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), REQ_ROLE)
        } else {
            updateStatus()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        updateStatus()
    }

    private fun updateStatus() {
        val rm = getSystemService(RoleManager::class.java)
        val role = rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        val sms = checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val contacts = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        textStatus.text = buildString {
            appendLine("${mark(role)} 통화 스크리닝 앱으로 지정")
            appendLine("${mark(sms)} 문자 보내기 권한")
            appendLine("${mark(contacts)} 연락처 권한 (없으면 연락처에 저장된 번호는 못 거름)")
            append("${mark(prefs.enabled)} 자동응답 켜짐")
        }
    }

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_ROLE = 2
    }
}
