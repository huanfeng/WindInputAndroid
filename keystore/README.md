# 签名密钥

**原则：一把密钥签所有包（debug / release 不分），任何机器构出来的包互相都能覆盖安装。**
卸载重装会丢输入法启用状态、用户词、剪贴板历史，所以"装不上"是要花力气避免的事。

## 个人密钥（不进仓库）

jks 放本目录（`*.jks` 已 gitignore），四项写进 `local.properties`（也已 gitignore）：

```
signing.storeFile=keystore/xxx.jks
signing.storePassword=…
signing.keyAlias=…
signing.keyPassword=…
```

四项齐全，`assembleDebug` 与 `assembleRelease` 都用它签。换台机器要能覆盖安装，
把 jks 和这四行一起带过去即可。

⚠ 缺任何一项都视为没给：debug 回退到下面那把公开密钥，release 不签名。别只填一半。

## CI（release.yml）

同一把密钥以 secrets 形式给 CI：

| secret | 内容 |
| --- | --- |
| `ANDROID_SIGNING_KEYSTORE_BASE64` | `base64 -w0 xxx.jks` |
| `ANDROID_SIGNING_STORE_PASSWORD` / `_KEY_ALIAS` / `_KEY_PASSWORD` | 与 local.properties 四项一一对应 |

设置：`base64 -w0 xxx.jks | gh secret set ANDROID_SIGNING_KEYSTORE_BASE64`，其余三个同理。
缺任何一个 release.yml 直接失败，不会退回 debug 密钥。

## debug.keystore（已提交，随意分发）

没有个人密钥时（别人 clone、CI）debug 包的兜底。alias `androiddebugkey`，
store/key 密码都是 `android`。debug 密钥不是秘密——它只能装 debug 包，AOSP 自己就把
debug 密钥公开在源码里。

## 核对当前包用的是哪把

```bash
$ANDROID_HOME/build-tools/<ver>/apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk | grep SHA-256
keytool -list -keystore keystore/xxx.jks | grep -i sha
```

两个指纹一致才是对的；不一致多半是 local.properties 少填了一项。
