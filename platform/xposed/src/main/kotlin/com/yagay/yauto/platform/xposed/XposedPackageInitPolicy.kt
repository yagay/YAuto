package com.yagay.yauto.platform.xposed

/** Central policy for selecting package callbacks that may install process hooks. */
internal object XposedPackageInitPolicy {
    fun shouldInitialize(
        packageName: String,
        firstPackage: Boolean,
        ownPackage: String,
    ): Boolean =
        firstPackage && packageName.isNotBlank() &&
            packageName != "android" && packageName != ownPackage
}
