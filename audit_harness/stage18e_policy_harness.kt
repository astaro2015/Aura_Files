import com.aurafiles.app.tools.ApkShareDeliveryPolicy

fun main() {
    check(!ApkShareDeliveryPolicy.useMediaStore(26))
    check(!ApkShareDeliveryPolicy.useMediaStore(28))
    check(ApkShareDeliveryPolicy.useMediaStore(29))
    check(ApkShareDeliveryPolicy.useMediaStore(36))
    check(ApkShareDeliveryPolicy.SHARE_MIME == "application/octet-stream")
    println("STAGE18E_APK_SHARE_DELIVERY_POLICY_PASS")
}
