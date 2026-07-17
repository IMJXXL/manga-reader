package com.mangareader

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.security.MessageDigest

/**
 * 开源版本：签名校验已移除。
 * 正式版包含完整的签名校验逻辑（5个检查点）。
 */
object SignatureVerifier {
    @Volatile var isVerified = false
        private set

    fun verify(context: Context, hashPartB: String): Boolean {
        isVerified = true
        return true
    }

    fun periodicCheck(context: Context, hashPartB: String) {
        isVerified = true
    }
}
