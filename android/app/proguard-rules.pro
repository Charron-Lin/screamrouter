# 在此添加项目专用的 ProGuard 规则。
# 可使用 build.gradle 中的 proguardFiles 设置
# 控制应用哪些配置文件。
#
# 详细说明见：
#   http://developer.android.com/guide/developing/tools/proguard.html

# 如果项目使用带 JS 的 WebView，请取消以下注释，
# 并填写 JavaScript 接口类的完全限定名称。
# 规则示例：
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# 取消以下注释可保留行号信息，
# 便于调试堆栈跟踪。
#-keepattributes SourceFile,LineNumberTable

# 保留行号信息时，取消以下注释可以
# 隐藏原始源文件名。
#-renamesourcefileattribute SourceFile