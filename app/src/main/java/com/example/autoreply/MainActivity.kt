package com.example.autoreply

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.telephony.PhoneNumberUtils
import android.telephony.SmsMessage
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var switchEnabled: Switch
    private lateinit var textToggleSub: TextView
    private lateinit var editMessage: EditText
    private lateinit var textSmsInfo: TextView
    private lateinit var editCooldown: EditText
    private lateinit var chipGroup: LinearLayout
    private lateinit var buttonSetup: Button
    private lateinit var numberScroll: ScrollView
    private lateinit var numberList: LinearLayout
    private lateinit var textEmpty: TextView
    private lateinit var textCount: TextView

    /** 간격 빠른 선택 칩: 값(분) to 표시 이름 */
    private val cooldownPresets = listOf(0 to "매번", 30 to "30분", 1440 to "하루", 10080 to "일주일")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        switchEnabled = findViewById(R.id.switchEnabled)
        textToggleSub = findViewById(R.id.textToggleSub)
        editMessage = findViewById(R.id.editMessage)
        textSmsInfo = findViewById(R.id.textSmsInfo)
        editCooldown = findViewById(R.id.editCooldown)
        chipGroup = findViewById(R.id.chipGroup)
        buttonSetup = findViewById(R.id.buttonSetup)
        numberScroll = findViewById(R.id.numberScroll)
        numberList = findViewById(R.id.numberList)
        textEmpty = findViewById(R.id.textEmpty)
        textCount = findViewById(R.id.textCount)

        applySystemBarPadding()

        // 켜기/끄기는 누르는 즉시 저장
        switchEnabled.isChecked = prefs.enabled
        switchEnabled.setOnCheckedChangeListener { _, checked ->
            prefs.enabled = checked
            updateToggleText()
            if (checked && !allReady()) {
                Toast.makeText(this, "준비 상태를 모두 완료해야 동작해요", Toast.LENGTH_SHORT).show()
            }
        }

        editMessage.setText(prefs.message)
        editMessage.addTextChangedListener(afterChanged { updateSmsInfo() })
        updateSmsInfo()

        buildCooldownChips()
        editCooldown.setText(prefs.cooldownMinutes.toString())
        editCooldown.addTextChangedListener(afterChanged { updateChipSelection() })
        updateChipSelection()

        findViewById<Button>(R.id.buttonSave).setOnClickListener { save() }
        buttonSetup.setOnClickListener { requestSetup() }
        findViewById<ImageButton>(R.id.buttonAddNumber).setOnClickListener { showAddDialog() }

        renderNumbers()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    /**
     * Android 15+ 는 화면을 상태바/내비게이션바 밑까지 그림.
     * 보라 헤더는 상태바 뒤까지 채우고(위 여백만 추가), 아래는 내비게이션바/키보드만큼 띄움.
     */
    private fun applySystemBarPadding() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val header = findViewById<View>(R.id.header)
        val baseTop = header.paddingTop
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            header.setPadding(header.paddingLeft, baseTop + bars.top, header.paddingRight, header.paddingBottom)
            v.setPadding(bars.left, 0, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
    }

    // ---------------- 번호 목록 ----------------

    /** 목록을 다시 그림. 5개를 넘으면 목록 높이를 5줄로 고정하고 안에서 스크롤 */
    private fun renderNumbers() {
        val numbers = prefs.numbers()
        numberList.removeAllViews()
        numbers.forEach { n ->
            val row = layoutInflater.inflate(R.layout.item_number, numberList, false)
            row.findViewById<TextView>(R.id.textNumber).text = formatForDisplay(n)
            row.findViewById<ImageButton>(R.id.buttonDelete).setOnClickListener { confirmDelete(n) }
            numberList.addView(row)
        }

        val empty = numbers.isEmpty()
        textEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        numberScroll.visibility = if (empty) View.GONE else View.VISIBLE
        textCount.visibility = if (empty) View.GONE else View.VISIBLE
        textCount.text = "${numbers.size}개"

        numberScroll.layoutParams = numberScroll.layoutParams.apply {
            height = if (numbers.size > MAX_VISIBLE_ROWS) dp(ROW_HEIGHT_DP) * MAX_VISIBLE_ROWS
            else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        updateToggleText()
    }

    private fun showAddDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_number, null)
        val input = view.findViewById<EditText>(R.id.editNumber)
        val error = view.findViewById<TextView>(R.id.textError)

        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.apply {
            setBackgroundDrawableResource(R.drawable.bg_dialog)   // 둥근 흰 카드 모양
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }

        fun showError(msg: String) {
            error.text = msg
            error.visibility = View.VISIBLE
        }
        input.addTextChangedListener(afterChanged { error.visibility = View.GONE })

        view.findViewById<Button>(R.id.buttonCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.buttonConfirm).setOnClickListener {
            val normalized = Prefs.normalize(input.text.toString())
            when {
                !isValidNumber(normalized) -> showError("전화번호를 확인해 주세요")
                prefs.numbers().any { Prefs.normalize(it) == normalized } -> showError("이미 등록된 번호예요")
                else -> {
                    prefs.setNumbers(prefs.numbers() + normalized)
                    renderNumbers()
                    numberScroll.post { numberScroll.fullScroll(View.FOCUS_DOWN) }
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
        input.requestFocus()
    }

    private fun confirmDelete(number: String) {
        AlertDialog.Builder(this)
            .setMessage("${formatForDisplay(number)} 번호를 삭제할까요?")
            .setPositiveButton("삭제") { _, _ ->
                prefs.setNumbers(prefs.numbers().filter { it != number })
                renderNumbers()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 국내 번호 기준: 0으로 시작하는 9~11자리 */
    private fun isValidNumber(n: String) = n.startsWith("0") && n.length in 9..11

    private fun formatForDisplay(n: String): String =
        PhoneNumberUtils.formatNumber(n, "KR") ?: n

    // ---------------- 안내 문자 / 간격 ----------------

    private fun updateSmsInfo() {
        val text = editMessage.text.toString()
        val parts = if (text.isEmpty()) 0 else SmsMessage.calculateLength(text, false)[0]
        textSmsInfo.text = "${text.length}자 · 문자 ${parts}통으로 보내져요"
    }

    private fun buildCooldownChips() {
        cooldownPresets.forEachIndexed { i, (minutes, label) ->
            val chip = TextView(this).apply {
                text = label
                tag = minutes
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    if (i > 0) marginStart = dp(8)
                }
                setOnClickListener { editCooldown.setText(minutes.toString()) }
            }
            chipGroup.addView(chip)
        }
    }

    private fun updateChipSelection() {
        val current = editCooldown.text.toString().toIntOrNull()
        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as TextView
            val selected = chip.tag == current
            chip.setBackgroundResource(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip)
            chip.setTextColor(if (selected) Color.WHITE else getColor(R.color.primary))
        }
    }

    // ---------------- 저장 / 상태 ----------------

    private fun save() {
        prefs.message = editMessage.text.toString().ifBlank { Prefs.DEFAULT_MESSAGE }
        prefs.cooldownMinutes = editCooldown.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 30
        if (editMessage.text.isBlank()) editMessage.setText(prefs.message)
        editCooldown.setText(prefs.cooldownMinutes.toString())
        currentFocus?.clearFocus()
        Toast.makeText(this, "저장했어요", Toast.LENGTH_SHORT).show()
    }

    private fun updateToggleText() {
        val count = prefs.numbers().size
        textToggleSub.text = if (prefs.enabled) "켜짐 · 번호 ${count}개 대기 중" else "꺼짐"
    }

    private fun roleHeld() =
        getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_SCREENING)

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun allReady() =
        roleHeld() && granted(Manifest.permission.SEND_SMS) && granted(Manifest.permission.READ_CONTACTS)

    private fun updateStatus() {
        setStatusRow(R.id.statusRole, roleHeld(), "통화 스크리닝 앱으로 지정")
        setStatusRow(R.id.statusSms, granted(Manifest.permission.SEND_SMS), "문자 보내기 권한")
        setStatusRow(
            R.id.statusContacts, granted(Manifest.permission.READ_CONTACTS),
            "연락처 권한 (연락처에 저장된 번호도 거르기)"
        )
        buttonSetup.visibility = if (allReady()) View.GONE else View.VISIBLE
        updateToggleText()
    }

    private fun setStatusRow(rowId: Int, ok: Boolean, label: String) {
        val row = findViewById<View>(rowId)
        val icon = row.findViewById<ImageView>(R.id.statusIcon)
        icon.setImageResource(if (ok) R.drawable.ic_check else R.drawable.ic_close)
        icon.imageTintList = ColorStateList.valueOf(getColor(if (ok) R.color.ok else R.color.bad))
        icon.backgroundTintList = ColorStateList.valueOf(getColor(if (ok) R.color.ok_soft else R.color.bad_soft))
        val text = row.findViewById<TextView>(R.id.statusText)
        text.text = label
        text.setTextColor(getColor(if (ok) R.color.text else R.color.bad))
    }

    // ---------------- 권한 요청 ----------------

    /** 권한 → 통화 스크리닝 앱 지정 순서로 요청 */
    private fun requestSetup() {
        val missing = arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS)
            .filter { !granted(it) }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS)
        else requestScreeningRole()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_PERMS) requestScreeningRole()
    }

    private fun requestScreeningRole() {
        val rm = getSystemService(RoleManager::class.java)
        if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && !roleHeld()) {
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

    // ---------------- 유틸 ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun afterChanged(block: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = block()
    }

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_ROLE = 2
        private const val ROW_HEIGHT_DP = 60
        private const val MAX_VISIBLE_ROWS = 5
    }
}
