# Xposed 相关
-keep class com.cdtubear.xpwechatcall.XposedEntry { *; }
-keep class com.cdtubear.xpwechatcall.hook.** { *; }
-keep class com.cdtubear.xpwechatcall.voip.** { *; }

# 保留快捷方式入口
-keep class com.cdtubear.xpwechatcall.ShortcutActivity { *; }

# 保留好友数据接收 Provider
-keep class com.cdtubear.xpwechatcall.FriendProvider { *; }
