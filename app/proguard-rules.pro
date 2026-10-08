-keep class com.aurafiles.app.data.NativeFileTime {
    native <methods>;
}

# PlayerView inflates these Media3 views from exo_player_view.xml. R8 9.x can
# rewrite their otherwise unreferenced (Context, AttributeSet) constructors,
# causing a NoSuchMethodException before any video starts on release builds.
-keep class androidx.media3.ui.AspectRatioFrameLayout {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keep class androidx.media3.ui.SubtitleView {
    public <init>(android.content.Context, android.util.AttributeSet);
}

# Optional desktop-only SMBJ integrations. Aura uses guest/NTLM authentication,
# not Java GSS/Kerberos or MBassador expression-language filters.
-dontwarn javax.el.BeanELResolver
-dontwarn javax.el.ELContext
-dontwarn javax.el.ELResolver
-dontwarn javax.el.ExpressionFactory
-dontwarn javax.el.FunctionMapper
-dontwarn javax.el.ValueExpression
-dontwarn javax.el.VariableMapper
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.Oid

# Optional Commons Compress Zstandard integration. Aura does not expose
# .zst/.tar.zst formats, so zstd-jni is intentionally not packaged.
-dontwarn com.github.luben.zstd.**

# Apache MINA SSHD 2.19 keeps desktop JMX exception peeling behind
# `!OsUtils.isAndroid()`. Android has no javax.management classes, so these
# references are intentionally absent at runtime.
-dontwarn javax.management.MBeanException
-dontwarn javax.management.ReflectionException
