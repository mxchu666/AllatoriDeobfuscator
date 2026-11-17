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

<p align="center">
  <img src="assets/jak.jpg" alt="jak" height="150" />
  <img src="assets/hack.jpg" alt="hack" height="150" />
  <img src="assets/deobf2.png" alt="deobf2" height="150" />
  <img src="assets/deobf.jpg" alt="deobf" height="150" />
</p>

---

**English** | [Español](README.es.md) | [日本語](README.ja.md) | [中文](README.zh.md) | [Русский](README.ru.md) | [Български](README.bg.md) | [Magyar](README.hu.md)

---

## How to build

### Prerequisites
- **JDK 17 or newer** — the build compiles with whatever JDK runs Gradle (including JDK 21 and JDK 25); the produced bytecode targets Java 17 (`--release 17`). No toolchain pinning.

### Building OpenAllatori

Run from this folder:

```powershell
.\gradlew.bat clean build
```

`BUILD SUCCESSFUL` is the signal; generated `build/libs/`:
- `OpenAllatori-9.9.jar` — the obfuscator **and** the deobfuscator
- `OpenAllatori-9.9-sources.jar`

---

## Using the obfuscator

Same as the original Allatori: an XML config drives it.

```powershell
java -jar build\libs\OpenAllatori-9.9.jar my-config.xml
```

The most important property is the **log file** — the obfuscator writes every rename into it, and this file is what makes the build reversible later:

```xml
<property name="log-file" value="out/mapping.xml"/>
```

## Using the deobfuscator

```powershell
java -cp build\libs\OpenAllatori-9.9.jar com.allatori.deobfuscate.Deobfuscate <command> ...
```

| Command | Arguments | What it does |
|---|---|---|
| `full` | `<mapping.xml> <in.jar> <out.jar> [--remove-decryptor]` | decrypt strings, drop the decryptor, restore all names — in that order |
| `rename-back` | `<mapping.xml> <in.jar> <out.jar>` | restore original class/method/field names from the mapping |
| `decrypt-strings` | `<in.jar> <out.jar> [--remove-decryptor]` | replace encrypted strings with their plain text |
| `restore-stacktrace` | `<mapping.xml> <in.txt> <out.txt>` | rewrite an obfuscated stack trace back to original names/lines |

### Demo round trip

```powershell
.\gradlew.bat runDemoRoundTrip
```

This compiles a tiny demo app, obfuscates it, deobfuscates it, and runs the original, obfuscated and deobfuscated JARs. Verified result: the deobfuscated JAR prints **byte-for-byte the same output** as the original and produces **identical stack traces** (`at com.example.demo.App.crash(App.java:31)`), and `javap -c` disassembles every class cleanly.

---

# How the deobfuscator works

The deobfuscator is the exact inverse of the reversible parts of the obfuscator. It works at the constant-pool and instruction level, on the same BCEL model the obfuscator itself uses, so what it writes back is exactly what the JVM expects.

Four mechanisms, executed by the `full` pipeline in this order:

**1. String decryption (`DecryptorSimulator`).** The obfuscator replaces every string constant with `ldc "ciphertext"` followed by `invokestatic DecryptString`. The deobfuscator finds candidate decryptor methods by their instruction profile (an XOR loop, a mask-63 AND, a char-array build, `charAt`), and then does something no naive tool does: it **actually executes the generated decryptor bytecode** on a small stack-machine interpreter. The decryption algorithm doesn't matter — whatever arithmetic Allatori generated (seed derivation, chained XOR keys, masks), the interpreter runs it and returns the plain text. The call site is then rewritten to `ldc "plaintext"` + `nop`, and when no live instruction references the decryptor class anymore, the class is deleted. Decryptors that depend on other runtime values are reported as "left encrypted" instead of being guessed.

**2. Name restoration (`UnrenameTransformer`).** The mapping XML (`<class old=... new=...>` with `<method old="name(desc)" new="n"/>` and `<field old="name TYPE" new="n"/>`) drives a constant-pool-level inverse rename: `this_class`, `super_class` and interfaces are restored, every method and field declaration gets its original name back, and every reference (Methodref, Fieldref, InterfaceMethodref, their class owners and NameAndType entries) is rewritten — including owners that were already rewritten earlier in the same pass. Jar entries are re-keyed from the restored class names, so the original package layout reappears.

**3. StackMapTable remapping (`StackMapRemapper`).** Decrypting strings shrinks the code (`invokestatic` 3 bytes → `nop` 1 byte). The StackMapTable attribute is carried through re-encoding verbatim, so its frames would keep pointing into the old layout and the JVM verifier (and `javap`) would reject the class. The remapper rewrites every frame's offset so it points at the same instruction in the new layout — including the instruction offsets inside `ITEM_Uninitialized` verification entries.

**4. Stack trace restoration (`restore-stacktrace`).** Reads the same mapping XML (method entries plus the `<lines>` list of encrypted line numbers) and rewrites a captured stack trace line by line: `at com.example.demo.h.K.K(K.java:7)` becomes `at com.example.demo.App.crash(App.java:31)`.

**Honest limits.** Full name restoration requires the mapping file — without it, obfuscation is by design not reversible, and only string decryption (which needs no mapping) works. That is the correct 1:1 behavior: with the log the obfuscator wrote, everything the obfuscator changed is restored; without it, the parts that can be recovered without information loss are.

---

## Project layout

```
src/main/java/com/allatori/          obfuscator (original re-implementation)
src/main/java/com/allatori/deobfuscate/   deobfuscator
  Deobfuscate.java                   CLI + pipeline orchestration
  MappingData.java                   parses the obfuscator's log XML
  UnrenameTransformer.java           constant-pool inverse renaming
  StringDecryptorTransformer.java    decryptor detection + call-site rewriting
  DecryptorSimulator.java            mini stack-machine that runs decryptor bytecode
  StackMapRemapper.java              StackMapTable offset remapping after edits
demo/                                demo app + config used by the Gradle tasks
tools/                               diagnostic helpers (DumpMain)
```

## Troubleshooting

- PowerShell prints noisy `NativeCommandError` text for console output of `java`/`gradlew`; check for `BUILD SUCCESSFUL` rather than the exit code.
- System properties with values must be quoted: `java "-Dallatori.deobfuscate.trace=true" ...`
- Debug diagnostics for string decryption: `-Dallatori.deobfuscate.debug=true` (summary) and `-Dallatori.deobfuscate.trace=true` (per-opcode trace in `DecryptorSimulator`).
