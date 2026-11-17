<p align="center">
  <img src="assets/ss.png" alt="Skid Solutions Banner" width="350" />
  <br>
  <font size="6"><b>OpenAllatori</b></font>
  <br>
  <font size="3">by Skid Solutions</font>
</p>

---

<p align="center">
  <a href="https://discord.gg/AEwatvnvSf">https://discord.gg/AEwatvnvSf</a>
</p>

---

[English](README.md) | [Español](README.es.md) | [日本語](README.ja.md) | **中文** | [Русский](README.ru.md) | [Български](README.bg.md) | [Magyar](README.hu.md)

---

# 如何构建

## 前提条件
- **JDK 17 或更新版本** — 构建直接使用运行 Gradle 的那个 JDK（包括 JDK 21 和 JDK 25）；生成的字节码以 Java 17 为目标（`--release 17`）。不固定工具链。

## 构建 OpenAllatori

在本文件夹中运行：

```powershell
.\gradlew.bat clean build
```

出现 `BUILD SUCCESSFUL` 即为成功；生成于 `build/libs/`：
- `OpenAllatori-9.9.jar` — 混淆器**与**反混淆器
- `OpenAllatori-9.9-sources.jar`

---

# 混淆器的使用

与原版 Allatori 相同：由 XML 配置驱动。

```powershell
java -jar build\libs\OpenAllatori-9.9.jar my-config.xml
```

最重要的属性是**日志文件** — 混淆器会把每一次重命名写进去，正是这个文件让构建日后可以被还原：

```xml
<property name="log-file" value="out/mapping.xml"/>
```

# 反混淆器的使用

```powershell
java -cp build\libs\OpenAllatori-9.9.jar com.allatori.deobfuscate.Deobfuscate <命令> ...
```

| 命令 | 参数 | 功能 |
|---|---|---|
| `full` | `<mapping.xml> <in.jar> <out.jar> [--remove-decryptor]` | 解密字符串、删除解密器、还原全部名称 — 按此顺序 |
| `rename-back` | `<mapping.xml> <in.jar> <out.jar>` | 根据映射还原原始的类/方法/字段名称 |
| `decrypt-strings` | `<in.jar> <out.jar> [--remove-decryptor]` | 将加密字符串替换为明文 |
| `restore-stacktrace` | `<mapping.xml> <in.txt> <out.txt>` | 将混淆后的堆栈跟踪改写回原始名称/行号 |

## 演示完整往返

```powershell
.\gradlew.bat runDemoRoundTrip
```

它会编译一个小型演示应用，先混淆再反混淆，并运行原始、混淆后、反混淆后的三个 JAR。经验证的结果：反混淆后的 JAR 打印出与原始版本**逐字节相同的输出**，产生**完全相同的堆栈跟踪**（`at com.example.demo.App.crash(App.java:31)`），并且 `javap -c` 能干净地反汇编每个类。

---

# 反混淆器的工作原理

反混淆器是混淆器中可逆部分的精确逆操作。它在常量池和指令级别上工作，使用与混淆器本身相同的 BCEL 模型，因此写回的内容正是 JVM 所期望的。

四个机制，由 `full` 流水线按以下顺序执行：

**1. 字符串解密（`DecryptorSimulator`）。** 混淆器把每个字符串常量替换为 `ldc "密文"` 加 `invokestatic DecryptString`。反混淆器通过指令特征（XOR 循环、掩码 63 的 AND、char 数组构建、`charAt`）找到候选解密方法，然后做一件朴素的工具绝不会做的事：用一个小型栈式机解释器**真正执行生成的解密器字节码**。算法无关紧要——无论 Allatori 生成了什么运算（种子推导、链式 XOR 密钥、掩码），解释器都会运行它并返回明文。调用点随后被改写为 `ldc "明文"` + `nop`；当不再有任何活指令引用解密器类时，该类被删除。依赖运行时值的解密器会被报告为"仍处于加密状态"，而不是靠猜测。

**2. 名称还原（`UnrenameTransformer`）。** 映射 XML（`<class old=... new=...>`，以及 `<method old="名称(描述符)" new="n"/>` 和 `<field old="名称 类型" new="n"/>`）驱动常量池级别的逆重命名：还原 `this_class`、`super_class` 和接口，让每个方法和字段的声明恢复原名，并改写所有引用（Methodref、Fieldref、InterfaceMethodref 及其类所有者和 NameAndType 条目）——包括在同一遍处理中已被改写过的所有者。JAR 条目按还原后的类名重新键入，因此原始的包结构会重新出现。

**3. StackMapTable 重映射（`StackMapRemapper`）。** 解密字符串会使代码变短（`invokestatic` 3 字节 → `nop` 1 字节）。StackMapTable 属性在重新编码时被原样带过，因此其帧会继续指向旧布局，JVM 验证器（以及 `javap`）会拒绝该类。重映射器改写每个帧的偏移，使其指向新布局中的同一条指令——包括 `ITEM_Uninitialized` 验证条目内的指令偏移。

**4. 堆栈跟踪还原（`restore-stacktrace`）。** 读取同一个映射 XML（方法条目以及 `<lines>` 中的加密行号列表），逐行改写捕获到的堆栈跟踪：`at com.example.demo.h.K.K(K.java:7)` 变为 `at com.example.demo.App.crash(App.java:31)`。

**诚实的限制。** 完整的名称还原需要映射文件——没有它，混淆在设计上就是不可逆的，只有字符串解密（它不需要映射）可用。这才是正确的 1:1 行为：有混淆器写下的日志，它改过的全部内容都被还原；没有它，只有那些不损失信息也能恢复的部分会被恢复。

---

# 项目结构

```
src/main/java/com/allatori/          混淆器（原始重新实现）
src/main/java/com/allatori/deobfuscate/   反混淆器
  Deobfuscate.java                   CLI + 流水线编排
  MappingData.java                   解析混淆器的日志 XML
  UnrenameTransformer.java           常量池级逆重命名
  StringDecryptorTransformer.java    解密器检测 + 调用点改写
  DecryptorSimulator.java            运行解密器字节码的小型栈式机
  StackMapRemapper.java              编辑后的 StackMapTable 偏移重映射
demo/                                Gradle 任务使用的演示应用 + 配置
tools/                               诊断辅助工具（DumpMain）
```

# 故障排查

- PowerShell 会对 `java`/`gradlew` 的控制台输出打印嘈杂的 `NativeCommandError` 文本；请检查 `BUILD SUCCESSFUL` 而不是退出码。
- 带值的系统属性必须加引号：`java "-Dallatori.deobfuscate.trace=true" ...`
- 字符串解密的调试诊断：`-Dallatori.deobfuscate.debug=true`（摘要）和 `-Dallatori.deobfuscate.trace=true`（`DecryptorSimulator` 中逐操作码的跟踪）。
