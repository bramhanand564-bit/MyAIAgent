package com.myaiagent.automation

enum class AutomationState {
    IDLE,
    WAITING_FOR_APP,
    FIND_CREATE,
    FIND_UPLOAD,
    WAITING_FOR_PICKER,
    FILL_DETAILS,
    PUBLISH,
    VERIFY,
    RETRY,
    COMPLETE,
    ERROR
}
