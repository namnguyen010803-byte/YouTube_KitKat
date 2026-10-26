# Quy tắc ProGuard/R8 (chỉ có hiệu lực khi bật minifyEnabled).

# Giữ nguyên các model (được Serializable truyền qua Intent).
-keep class com.ytkitkat.app.model.** { *; }

# Giữ TLSSocketFactory và lớp socket bọc (được tạo/gọi qua reflection-sensitive code).
-keep class com.ytkitkat.app.network.TLSSocketFactory { *; }
-keep class com.ytkitkat.app.network.TLSSocketFactory$* { *; }

# Giữ tên phương thức setHostname / setUseSessionTickets khi gọi qua reflection.
-keepclassmembers class * extends javax.net.ssl.SSLSocket {
    public void setHostname(java.lang.String);
    public void setUseSessionTickets(boolean);
}

-keepattributes Signature,InnerClasses,EnclosingMethod
