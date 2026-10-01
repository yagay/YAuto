package com.yagay.yauto.platform.shizuku;
import android.os.Bundle;
interface IShizukuShell {
    Bundle execute(String command, long timeoutMs) = 1;
    void destroy() = 16777114;
}
