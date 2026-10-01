package com.yagay.yauto.platform.shizuku;
import android.os.Bundle;
interface IShizukuShell {
    Bundle execute(String requestId, String command, long timeoutMs) = 1;
    void cancel(String requestId) = 2;
    void destroy() = 16777114;
}
