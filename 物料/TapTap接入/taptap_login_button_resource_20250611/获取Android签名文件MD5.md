# 获取 Android 签名文件 MD5

### 通过 APP 工具获取

当只有已签名的 APK 文件时，可使用 [**GenSignatureMD5**](https://capacity-files.lcfile.com/vW65JxH2b2KwDS8JcbVUfiwLHSeHTlD5/tds_getsign.apk) 工具进行获取，具体使用步骤如下：

1.  将该游戏 APK 安装到手机上
2.  将 `GenSignatureMD5` 工具 APK 也安装到同一部手机上
3.  打开 `GenSignatureMD5`，输入游戏包名，即可查看签名 MD5 值

### 通过 Android Studio 终端命令获取

如果可以访问签名文件或项目工程，可以在 Android Studio 的 **Terminal 终端** 中选择对应命令执行：

1.  使用签名文件路径

```sh
keytool -exportcert -alias {alias} -keystore {storefile} | openssl dgst -md5
```

2.  使用当前项目的签名配置属性

```sh
./gradlew signingReport
```

命令执行完成后，就可在窗口看到签名文件的SHA1 值和 MD5 值信息。

### 通过 Android Studio 的 Gradle Tasks 获取

打开 Android Studio 右侧的 **Gradle** 工具窗口，依次展开 **项目名称 > Tasks > android**，双击 **signingReport**。运行完成后，调试窗口会输出 MD5 值。

![在 Android Studio 的 Gradle 工具窗口中运行 Tasks > android > signingReport](https://capacity-files.lcfile.com/y7fcVDW6cUFKfG4ATDXj8KKE9L2jWprB/%E8%8E%B7%E5%8F%96MD5%E7%A4%BA%E4%BE%8B1.jpeg)

> **警告**
>
> 注意，运行 signingReport 调试窗口输出的 MD5 值带冒号分隔符，绑定到开发者中心时需要手动删除冒号。
> 
> > TapTap 开发者中心绑定 MD5 格式举例：
> > 
> > 正确格式：6EB4347CF9C098BE1C8D965D539C42E2
> > 
> > 错误格式：6E:B4:34:7C:F9:C0:98:BE:1C:8D:96:5D:53:9C:42:E2

如果右侧 Gradle 面板没有 Gradle Tasks 选项卡，打开 **Settings / Preferences > Experimental**，取消勾选 **Do not build Gradle task list during Gradle sync**，然后重新 Sync Gradle。

![在 Android Studio 的 Experimental 设置中取消禁止构建 Gradle 任务列表](https://capacity-files.lcfile.com/8dEVF81X34JFtUE50tnqj6OIoxxDdXsU/%E8%8E%B7%E5%8F%96MD5%E7%A4%BA%E4%BE%8B2.jpeg)

文档反馈