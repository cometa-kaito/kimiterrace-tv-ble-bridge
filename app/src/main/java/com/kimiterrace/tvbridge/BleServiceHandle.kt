package com.kimiterrace.tvbridge

/**
 * BleService の現在インスタンスを保持する弱い参照（簡易版）。
 *
 * Production では BoundService + AIDL や Flow ベースの状態共有が望ましいが、
 * PoC スコープでは静的参照で済ませる。Service が生きている時のみ non-null。
 */
object BleServiceHandle {
    @Volatile
    var current: BleService? = null
}
