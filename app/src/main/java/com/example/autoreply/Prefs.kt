package com.example.autoreply

import android.content.Context

/** 설정 저장소 (SharedPreferences) */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("auto_reply", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_ENABLED, v).apply()

    /** 화면에 입력한 원문 그대로 (한 줄에 번호 하나) */
    var numbersText: String
        get() = sp.getString(KEY_NUMBERS, "") ?: ""
        set(v) = sp.edit().putString(KEY_NUMBERS, v).apply()

    var message: String
        get() = sp.getString(KEY_MESSAGE, DEFAULT_MESSAGE) ?: DEFAULT_MESSAGE
        set(v) = sp.edit().putString(KEY_MESSAGE, v).apply()

    /** 같은 번호로 문자를 다시 보내기까지 기다리는 시간(분) */
    var cooldownMinutes: Int
        get() = sp.getInt(KEY_COOLDOWN, 30)
        set(v) = sp.edit().putInt(KEY_COOLDOWN, v).apply()

    /** 비교용으로 정규화한 번호 목록 */
    fun targetNumbers(): Set<String> =
        numbersText.lines().map { normalize(it) }.filter { it.isNotEmpty() }.toSet()

    fun isTarget(rawNumber: String?): Boolean {
        val n = normalize(rawNumber ?: return false)
        return n.isNotEmpty() && n in targetNumbers()
    }

    /** 쿨다운 안에 이미 보냈으면 false */
    fun shouldSendTo(number: String, now: Long = System.currentTimeMillis()): Boolean {
        val last = sp.getLong(KEY_LAST_SENT + normalize(number), 0L)
        return now - last >= cooldownMinutes * 60_000L
    }

    fun markSent(number: String, now: Long = System.currentTimeMillis()) {
        sp.edit().putLong(KEY_LAST_SENT + normalize(number), now).apply()
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_NUMBERS = "numbers"
        private const val KEY_MESSAGE = "message"
        private const val KEY_COOLDOWN = "cooldown_min"
        private const val KEY_LAST_SENT = "last_sent_"

        const val DEFAULT_MESSAGE =
            "안녕하세요, 지금 휴가 중이라 전화를 받기 어렵습니다. 복귀 후 연락드리겠습니다."

        /**
         * 번호 비교용 정규화.
         * "+82 10-1234-5678", "010-1234-5678", "01012345678" 을 모두 "01012345678" 로 맞춤.
         */
        fun normalize(raw: String): String {
            var d = raw.filter { it.isDigit() }
            if (d.startsWith("82") && raw.trim().startsWith("+")) d = "0" + d.removePrefix("82")
            else if (d.startsWith("82") && d.length >= 11 && !d.startsWith("0")) d = "0" + d.removePrefix("82")
            return d
        }
    }
}
