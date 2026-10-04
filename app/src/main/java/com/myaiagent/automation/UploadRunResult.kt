package com.myaiagent.automation

sealed class UploadRunResult {
    data class Success(val note: String) : UploadRunResult()
    data class NeedsUser(val note: String) : UploadRunResult()
    data class Failure(val note: String) : UploadRunResult()
}