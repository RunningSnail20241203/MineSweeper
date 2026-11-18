# Android SDK 集成

本文介绍如何在 Android 项目中快速接入 TapSDK 。

## 环境要求

Android 5.0（API level 21）或更高版本， 当使用 Kotlin 时，需确保使用 1.7.21 或更高版本

## 编译工具链

Android 一般通过 Gradle & AGP 进行构建，所以对于 Android 项目，需要确保 Gradle 和 AGP 的版本与 SDK 兼容，SDK 对应版本要求如下：

-   GRADLE\_VERSION: 6.1.1+
-   AGP\_VERSION: 4.0.1+

## SDK 获取

1.  项目根目录的 build.gradle 添加仓库地址：

```groovy
allprojects {
  repositories {
      google()
      mavenCentral()
  }
}
```

2.  app module 的 build.gradle 添加对应模块依赖（如：登录以及内嵌动态）：

```groovy
dependencies {
  implementation 'com.taptap.sdk:tap-core:4.11.1'
}
```

3.  在 `AndroidManifest.xml` 添加网络权限：

```xml
<uses-permission android:name="android.permission.INTERNET"></uses-permission>
```

4.  旧版 Android 额外配置
    
    如果 `targetSdkVersion < 29`，还需要添加如下配置：
    
    -   `manifest` 节点添加 `xmlns:tools="http://schemas.android.com/tools"`
    -   `application` 节点添加 `tools:remove="android:requestLegacyExternalStorage"`

## 初始化

> **信息**
>
> 游戏 [**适用地区**](/docs/sdk/access/get-ready/#%E9%80%82%E7%94%A8%E5%9C%B0%E5%8C%BA) 在开启应用配置时选定。
> 
> -   [TapTap 开发者中心](https://developer.taptap.cn/)适用地区为中国大陆。
>     
> -   出海游戏请前往 [**TapTap.io 开发者中心**](https://developer.taptap.io/) 开启游戏服务，适用地区为其他国家或地区。

新版 SDK 采用统一初始化机制，初始化时需设置 Client ID、clientToken、区域等应用配置信息，完成后即可直接使用所有业务模块（如登录、成就等），无需对各模块分别初始化。

**Android Java**

```java
import com.taptap.sdk.core.TapTapRegion;
import com.taptap.sdk.core.TapTapSdk;
import com.taptap.sdk.core.TapTapSdkOptions;
import com.taptap.sdk.initializer.api.model.ScreenOrientation;

TapTapSdkOptions tapSdkOptions = new TapTapSdkOptions(
       "clientId", // 开发者中心对应的游戏 Client ID
       "clientToken", // 开发者中心对应 游戏 Client Token
       TapTapRegion.CN // 游戏可玩区域: [TapTapRegion.CN]=国内 [TapTapRegion.GLOBAL]=海外
);
// 设置屏幕方向，ScreenOrientation.LANDSCAPE 或 1 横屏， ScreenOrientation.PORTRAIT 或 0 竖屏
tapSdkOptions.setScreenOrientation(ScreenOrientation.LANDSCAPE);
// 是否开启 log，建议 Debug 开启，Release 关闭，默认关闭 log
tapSdkOptions.setEnableLog(BuildConfig.DEBUG);
// 初始化 TapSDK
TapTapSdk.init(context, tapSdkOptions);
```

**Android Kotlin**

```kotlin
import com.taptap.sdk.core.TapTapRegion;
import com.taptap.sdk.core.TapTapSdk;
import com.taptap.sdk.core.TapTapSdkOptions;
import com.taptap.sdk.initializer.api.model.ScreenOrientation;

TapTapSdk.init(
    context,
    TapTapSdkOptions(
        clientId = "clientId", // 开发者中心对应的游戏 Client ID
        clientToken = "clientToken", // 开发者中心对应 游戏 Client Token
        region = TapTapRegion.CN, // 游戏可玩区域: [TapTapRegion.CN]=国内 [TapTapRegion.GLOBAL]=海外
        screenOrientation = ScreenOrientation.LANDSCAPE, // 设置屏幕方向，ScreenOrientation.LANDSCAPE 或 1 横屏， ScreenOrientation.PORTRAIT 或 0 竖屏
        enableLog = true, // 是否开启 log，建议 Debug 开启，Release 关闭，默认关闭 log
    )
)
```

初始化具体参数如下：

| 参数名 | 是否必填 | 说明 |
| --- | --- | --- |
| clientId | 是 | 客户端 ID，开发者后台获取 |
| clientToken | 是 | 客户端令牌，开发者后台获取 |
| region | 是 | 地区 |
| screenOrientation | 否 | 屏幕方向：0-竖屏 1-横屏，默认为横屏 |
| enableLog | 否 | 是否开启日志，默认关闭，建议开发阶段开启，上线前关闭 |
| preferredLanguage | 否 | 语言，默认自动获取系统语言 |
| gameVersion | 否 | 应用版本号，默认读取应用的版本号 |

## 获取初始化结果

`TapTapSdk.init()` 是一个异步过程，调用后并不代表初始化已经成功。请通过 `addInitCallback` 注册回调，获取真实的初始化结果：

```kotlin
import com.taptap.sdk.core.TapTapSdk
import com.taptap.sdk.initializer.api.TapInitCallback
import com.taptap.sdk.initializer.api.TapInitErrorCode

private val initCallback = object : TapInitCallback {
    override fun onInitSuccess() {
        // 初始化成功，可以正常使用登录、成就等功能
    }

    override fun onInitFail(errorCode: Int, errorMsg: String) {
        // 初始化失败，errorCode 含义见下表
        when (errorCode) {
            TapInitErrorCode.CONFIG_ERROR -> {
                // 应用信息与开发者中心配置不一致，需检查配置后重新发布，无法通过重试恢复
            }
            else -> {
                // TapInitErrorCode.PARAM_ERROR / TapInitErrorCode.INTERNAL_ERROR
            }
        }
    }
}

// 可以在 init() 之前或之后的任意时机注册；若注册时已有结果，会尽快在主线程回调一次
TapTapSdk.addInitCallback(initCallback)

// 不再需要监听时，请调用 removeInitCallback 取消注册
TapTapSdk.removeInitCallback(initCallback)
```

`errorCode` 取值如下：

| errorCode | 说明 |
| --- | --- |
| `TapInitErrorCode.PARAM_ERROR`（1000） | 初始化参数非法（如 `clientId`/`clientToken` 为空） |
| `TapInitErrorCode.CONFIG_ERROR`（1001） | 应用信息与开发者中心配置不一致（包名/签名/`clientId`），需检查配置后重新发布，无法通过重试恢复 |
| `TapInitErrorCode.INTERNAL_ERROR`（1003） | SDK 内部异常 |

> **信息**
>
> `init()` 需在主线程调用。

## 常见问题

### 编译报错 mergeJavaResource task

如果 Build Output 中的 `:app:mergeDebugJavaResource` 任务报告 `More than one file was found with OS independent path 'nativeCommonMain/default/linkdata/module'`：

![mergeDebugJavaResource 任务报告 nativeCommonMain default linkdata module 文件冲突](https://img.tapimg.com/market/images/e3e2f145c22488461e391b6ed397a546.jpg)

这类编译错误通常是由于开发者使用的 AGP（`com.android.tools.build:gradle:${agp_version}`）版本过低，无法正确处理 Kotlin Multiplatform 构建生成的 AAR 中特有的文件。 解决方案： 将 Gradle 升级到 6.1.1 或更高版本，同时将 AGP 升级到 4.0.1 或更高版本。

### 运行时报错签名不匹配或 `signature not match`

检查 TapTap 开发者后台配置的[应用包名和签名信息](/docs/sdk/access/get-ready/#%E9%85%8D%E7%BD%AE%E7%AD%BE%E5%90%8D%E8%AF%81%E4%B9%A6) 是否和当前工程设置一致

文档反馈