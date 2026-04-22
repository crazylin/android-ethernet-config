package com.example.ethernetconfig.model

/**
 * 验证结果密封类，表示字段验证的三种可能结果。
 *
 * Valid - 验证通过
 * Invalid - 验证失败，包含错误信息
 * Warning - 验证通过但有警告，包含警告信息
 */
sealed class ValidationResult {
    object Valid : ValidationResult()
    data class Invalid(val errorMessage: String) : ValidationResult()
    data class Warning(val warningMessage: String) : ValidationResult()
}
