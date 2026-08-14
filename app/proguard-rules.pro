-keepattributes Signature,*Annotation*
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn org.apache.commons.compress.archivers.sevenz.**

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
