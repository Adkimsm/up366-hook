package io.github.adkimsm.up366.hook;

/** Read-only method catalog derived from the callHandler entries in assets/api.json. */
final class BridgeApiCatalog {
    private BridgeApiCatalog() {}

    static String[] methods() {
        return new String[]{
                "canIUse", "getAppInfo", "upgradeClient", "getLaunchOptions", "getUserInfo",
                "searchDB", "writeTextToClipboard", "readTextFromClipboard", "share", "reboot",
                "quit", "getBookInfo", "submitTask", "getTasksUploadStatus", "manualUploadTask",
                "refreshJobList", "getCourseInfo", "postMessage", "getRootPath", "exists", "stat",
                "writeFile", "copyFile", "mkdir", "readFile", "readDir", "unlink", "rmdir",
                "rename", "unzip", "zip", "isDir", "log", "uploadUserLog", "uploadAppLogs",
                "cancelAppLogs", "playAudio", "pauseAudio", "resumeAudio", "stopAudio",
                "getCurrentAudioTime", "getVolume", "setVolume", "setAudioRate", "getAudioRate",
                "startRecord", "stopRecord", "startRecordV2", "stopRecordV2", "getAsecResult",
                "delAsecResult", "ocr", "mixAudioVideo", "saveToGallery", "getPhoto", "deletePhoto",
                "scan", "request", "downloadFile", "cancelDownload", "uploadFile", "saveData",
                "removeData", "getData", "openWebView", "closeWebView", "gotoNativeModule", "loginOut",
                "showLoading", "setBackgroundColor", "setStatusBar", "setFullScreen", "giveMark",
                "openDictionary", "submitBatch", "buildOSSURL", "getUT", "getAudioInfo",
                "getSubmitTaskInfoList", "addSubmitTaskInfo", "getOpenTypeBookIdDict", "getTaskList",
                "getClippedPhoto", "getClippedPhotoV2", "openDictionaryV2", "getClientInfo",
                "requestDataFromServer", "loadTaskInfo", "loadPageInfo",
                "loadChapterInfo", "submitEleAttr", "submitPageEleInfo", "submitTaskV1",
                "playAudioWithParams", "stopPlayAudio", "writeJSErrorLog"
        };
    }
}
