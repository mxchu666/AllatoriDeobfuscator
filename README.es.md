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

[English](README.md) | **Español** | [日本語](README.ja.md) | [中文](README.zh.md) | [Русский](README.ru.md) | [Български](README.bg.md) | [Magyar](README.hu.md)

---

# Cómo compilar

## Requisitos
- **JDK 17 o más reciente** — la compilación usa el JDK con el que corre Gradle (incluye JDK 21 y JDK 25); el bytecode generado apunta a Java 17 (`--release 17`). Sin fijación de toolchain.

## Compilando OpenAllatori

Ejecuta desde esta carpeta:

```powershell
.\gradlew.bat clean build
```

`BUILD SUCCESSFUL` es la señal; en `build/libs/` se generan:
- `OpenAllatori-9.9.jar` — el ofuscador **y** el desofuscador
- `OpenAllatori-9.9-sources.jar`

---

# Uso del ofuscador

Igual que el Allatori original: un XML de configuración lo controla.

```powershell
java -jar build\libs\OpenAllatori-9.9.jar mi-config.xml
```

La propiedad más importante es el **archivo de log**: el ofuscador escribe ahí cada renombrado, y ese archivo es lo que permite revertir la build más adelante:

```xml
<property name="log-file" value="out/mapping.xml"/>
```

# Uso del desofuscador

```powershell
java -cp build\libs\OpenAllatori-9.9.jar com.allatori.deobfuscate.Deobfuscate <comando> ...
```

| Comando | Argumentos | Qué hace |
|---|---|---|
| `full` | `<mapping.xml> <in.jar> <out.jar> [--remove-decryptor]` | descifra cadenas, elimina el descifrador y restaura todos los nombres — en ese orden |
| `rename-back` | `<mapping.xml> <in.jar> <out.jar>` | restaura los nombres originales de clases/métodos/campos desde el mapeo |
| `decrypt-strings` | `<in.jar> <out.jar> [--remove-decryptor]` | sustituye las cadenas cifradas por su texto plano |
| `restore-stacktrace` | `<mapping.xml> <in.txt> <out.txt>` | reescribe un stack trace ofuscado con los nombres/líneas originales |

## Demo de ida y vuelta

```powershell
.\gradlew.bat runDemoRoundTrip
```

Compila una mini app de demo, la ofusca, la desofusca y ejecuta los JAR original, ofuscado y desofuscado. Resultado verificado: el JAR desofuscado imprime **exactamente la misma salida** que el original y produce **stack traces idénticos** (`at com.example.demo.App.crash(App.java:31)`), y `javap -c` desensambla todas las clases limpiamente.

---

# Cómo funciona el desofuscador

El desofuscador es la inversa exacta de las partes reversibles del ofuscador. Trabaja a nivel de *constant pool* e instrucciones, sobre el mismo modelo BCEL que usa el propio ofuscador, de modo que lo que escribe de vuelta es exactamente lo que la JVM espera.

Cuatro mecanismos, ejecutados por el flujo `full` en este orden:

**1. Descifrado de cadenas (`DecryptorSimulator`).** El ofuscador sustituye cada constante de cadena por `ldc "texto cifrado"` seguido de `invokestatic DecryptString`. El desofuscador encuentra los métodos descifradores candidatos por su perfil de instrucciones (bucle XOR, un AND con máscara 63, construcción de un array de chars, `charAt`) y luego hace algo que ninguna herramienta ingenua hace: **ejecuta de verdad el bytecode del descifrador generado** sobre un pequeño intérprete de máquina de pila. No importa el algoritmo: cualquier aritmética que Allatori genere (semilla, claves XOR encadenadas, máscaras), el intérprete la ejecuta y devuelve el texto plano. El sitio de llamada se reescribe a `ldc "texto plano"` + `nop`, y cuando ninguna instrucción viva referencia ya a la clase descifradora, se elimina. Los descifradores que dependen de valores en tiempo de ejecución se informan como "siguen cifradas" en lugar de adivinarse.

**2. Restauración de nombres (`UnrenameTransformer`).** El XML de mapeo (`<class old=... new=...>` con `<method old="nombre(desc)" new="n"/>` y `<field old="nombre TIPO" new="n"/>`) guía un renombrado inverso a nivel de *constant pool*: se restauran `this_class`, `super_class` y las interfaces, cada método y campo recupera su nombre original y se reescribe toda referencia (Methodref, Fieldref, InterfaceMethodref, sus propietarios y entradas NameAndType), incluidos los propietarios ya reescritos antes en la misma pasada. Las entradas del JAR se re-clasifican con los nombres restaurados, de modo que reaparece la estructura de paquetes original.

**3. Remapeo del StackMapTable (`StackMapRemapper`).** Descifrar cadenas reduce el código (`invokestatic` 3 bytes → `nop` 1 byte). El atributo StackMapTable se reemite tal cual al recodificar, así que sus marcos seguirían apuntando al layout antiguo y el verificador de la JVM (y `javap`) rechazarían la clase. El remapeador reescribe el desplazamiento de cada marco para que apunte a la misma instrucción del layout nuevo — incluidos los desplazamientos de instrucción dentro de entradas de verificación `ITEM_Uninitialized`.

**4. Restauración de stack traces (`restore-stacktrace`).** Lee el mismo XML de mapeo (entradas de métodos más la lista `<lines>` de números de línea cifrados) y reescribe la traza capturada línea a línea: `at com.example.demo.h.K.K(K.java:7)` se convierte en `at com.example.demo.App.crash(App.java:31)`.

**Límites honestos.** La restauración completa de nombres exige el archivo de mapeo — sin él, la ofuscación no es reversible por diseño, y solo funciona el descifrado de cadenas (que no necesita mapeo). Ese es el comportamiento 1:1 correcto: con el log que escribió el ofuscador, todo lo que este cambió se restaura; sin él, solo se recupera lo recuperable sin pérdida de información.

---

# Estructura del proyecto

```
src/main/java/com/allatori/          ofuscador (reimplementación original)
src/main/java/com/allatori/deobfuscate/   desofuscador
  Deobfuscate.java                   CLI + orquestación del flujo
  MappingData.java                   parsea el XML de log del ofuscador
  UnrenameTransformer.java           renombrado inverso a nivel de constant pool
  StringDecryptorTransformer.java    detección de descifradores + reescritura de llamadas
  DecryptorSimulator.java            mini máquina de pila que ejecuta el bytecode descifrador
  StackMapRemapper.java              remapeo de offsets del StackMapTable tras las ediciones
demo/                                app de demo + config usada por las tareas de Gradle
tools/                               utilidades de diagnóstico (DumpMain)
```

# Solución de problemas

- PowerShell imprime texto `NativeCommandError` ruidoso para la salida de consola de `java`/`gradlew`; fíjate en `BUILD SUCCESSFUL` más que en el código de salida.
- Las propiedades de sistema con valor deben ir entre comillas: `java "-Dallatori.deobfuscate.trace=true" ...`
- Diagnóstico de depuración del descifrado de cadenas: `-Dallatori.deobfuscate.debug=true` (resumen) y `-Dallatori.deobfuscate.trace=true` (traza por opcode en `DecryptorSimulator`).
