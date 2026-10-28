package com.example.autoreply

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telephony.SmsManager
import android.util.Log

/**
 * 전화가 올 때 시스템이 호출하는 서비스.
 * 이 앱이 "통화 스크리닝 앱"으로 지정되어 있어야 동작함 (MainActivity에서 요청).
 */
class AutoReplyScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val prefs = Prefs(this)
        val number = callDetails.handle?.schemeSpecificPart

        val isIncoming = callDetails.callDirection == Call.Details.DIRECTION_INCOMING
        val match = isIncoming && prefs.enabled && prefs.isTarget(number)

        if (!match) {
            // 대상이 아니면 평소처럼 울리게 둔다
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }

        // 거절. 통화기록과 부재중 알림은 남겨서 누가 걸었는지 나중에 볼 수 있게 함
        val response = CallResponse.Builder()
            .setDisallowCall(true)
            .setRejectCall(true)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()
        respondToCall(callDetails, response)

        if (number != null && prefs.shouldSendTo(number)) {
            if (sendSms(number, prefs.message)) prefs.markSent(number)
        }
    }

    private fun sendSms(number: String, text: String): Boolean {
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "SEND_SMS 권한 없음")
            return false
        }
        return try {
            val sms: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION") SmsManager.getDefault()
            }
            // 한글은 SMS 한 통에 70자까지라 길면 나눠서 보냄
            val parts = sms.divideMessage(text)
            if (parts.size > 1) sms.sendMultipartTextMessage(number, null, parts, null, null)
            else sms.sendTextMessage(number, null, text, null, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "문자 발송 실패", e)
            false
        }
    }

    companion object { private const val TAG = "AutoReply" }
}
