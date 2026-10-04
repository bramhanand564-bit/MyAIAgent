package com.myaiagent.automation

enum class AutomationState {
    IDLE,
    WAITING_FOR_APP,
    FIND_CREATE,
    VERIFY_CREATE_MENU,
    FIND_UPLOAD,
    VERIFY_UPLOAD_PICKER,
    WAITING_FOR_PICKER,
    FILL_DETAILS,
    SET_VISIBILITY,
    PUBLISH,
    MONITOR_UPLOAD,
    VERIFY,
    WAITING_USER,
    COMPLETE,
    ERROR
}
