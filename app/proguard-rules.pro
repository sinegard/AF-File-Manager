-keepattributes Signature,*Annotation*
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn org.apache.commons.compress.archivers.sevenz.**

# JSch selects authentication, key-exchange, cipher, signature, and key parsing
# implementations from fully qualified class-name strings at runtime. Preserve
# the Android/JCE implementations AF File Manager can actually use; optional
# desktop integrations such as Pageant, JNA, Log4j, and Unix-domain agents stay
# removable.
-keep,allowoptimization class com.jcraft.jsch.JSchException { *; }
-keep,allowoptimization class com.jcraft.jsch.CipherNone { *; }
-keep,allowoptimization class com.jcraft.jsch.UserAuthNone { *; }
-keep,allowoptimization class com.jcraft.jsch.UserAuthPassword { *; }
-keep,allowoptimization class com.jcraft.jsch.UserAuthKeyboardInteractive { *; }
-keep,allowoptimization class com.jcraft.jsch.UserAuthPublicKey { *; }
-keep,allowoptimization class com.jcraft.jsch.DH** { *; }
-keep,allowoptimization class com.jcraft.jsch.jce.** { *; }

# Release instrumentation calls this non-exported verification seam from a
# separately optimized test APK, so its binary name and method signature form a
# stable cross-APK contract.
-keep class com.affilemanager.app.network.SftpRuntimeVerifier { *; }

# Shizuku and libsu start these application classes by their binary names in a
# separate privileged process. Renaming or removing either class would make the
# optimized APK report an available backend that can never connect.
-keep class com.affilemanager.app.advanced.ShizukuFileService { *; }
-keep class com.affilemanager.app.advanced.RootFileService { *; }
-keep class com.affilemanager.app.advanced.IPrivilegedFileService$Stub { *; }

# SMBJ optionally supports Kerberos/GSS and mbassador EL filters. AF File Manager
# uses username/password NTLM and no expression-language filters, so those
# desktop-only optional classes are intentionally absent on Android.
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

# termlib's native renderer resolves these private CellRun fields by their
# exact JVM names. The library's consumer rules keep only its public API, so a
# minified build otherwise renames the fields and aborts during nativeInit.
-keepclassmembers class org.connectbot.terminal.CellRun {
    int fgRed;
    int fgGreen;
    int fgBlue;
    int bgRed;
    int bgGreen;
    int bgBlue;
    boolean bold;
    int underline;
    boolean italic;
    boolean blink;
    boolean reverse;
    boolean strike;
    int font;
    boolean dwl;
    int dhl;
    char[] chars;
    int runLength;
}
