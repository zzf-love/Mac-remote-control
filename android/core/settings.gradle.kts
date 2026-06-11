// core 是独立的纯 JVM 构建（被 android/ 以 includeBuild 复合引入），
// 因此可以脱离 Android SDK 单独编译和跑测试：gradle -p android/core test
rootProject.name = "core"
