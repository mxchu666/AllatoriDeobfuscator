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

[English](README.md) | [Español](README.es.md) | [日本語](README.ja.md) | [中文](README.zh.md) | [Русский](README.ru.md) | [Български](README.bg.md) | **Magyar**

---

# Hogyan lehet fordítani

## Előfeltételek
- **JDK 17 vagy újabb** — a fordítás azzal a JDK-val történik, amelyik alatt a Gradle fut (JDK 21 és JDK 25 is); a kapott bájtkód Java 17-et célz meg (`--release 17`). Toolchain-rögzítés nincs.

## Az OpenAllatori fordítása

Futtassa ebből a mappából:

```powershell
.\gradlew.bat clean build
```

A siker jele a `BUILD SUCCESSFUL`; a `build/libs/`-ban keletkezik:
- `OpenAllatori-9.9.jar` — az obfuscator **és** a deobfuscator
- `OpenAllatori-9.9-sources.jar`

---

# Az obfuscator használata

Ugyanúgy, mint az eredeti Allatori: XML konfiguráció vezérli.

```powershell
java -jar build\libs\OpenAllatori-9.9.jar my-config.xml
```

A legfontosabb tulajdonság a **naplófájl** — az obfuscator ide írja minden átnevezést, és ez a fájl teszi lehetővé, hogy a build később visszafordítható legyen:

```xml
<property name="log-file" value="out/mapping.xml"/>
```

# A deobfuscator használata

```powershell
java -cp build\libs\OpenAllatori-9.9.jar com.allatori.deobfuscate.Deobfuscate <parancs> ...
```

| Parancs | Argumentumok | Mit csinál |
|---|---|---|
| `full` | `<mapping.xml> <in.jar> <out.jar> [--remove-decryptor]` | dekódolja a karakterláncokat, eltávolítja a dekodert, visszaállítja az összes nevet — ebben a sorrendben |
| `rename-back` | `<mapping.xml> <in.jar> <out.jar>` | visszaállítja az eredeti osztály/metódus/mező-neveket a mappingből |
| `decrypt-strings` | `<in.jar> <out.jar> [--remove-decryptor]` | a titkosított karakterláncokat tiszta szövegre cseréli |
| `restore-stacktrace` | `<mapping.xml> <in.txt> <out.txt>` | az obfuszkált stack trace-t eredeti nevekre/sorszámokra írja vissza |

## Demó oda-vissza

```powershell
.\gradlew.bat runDemoRoundTrip
```

Lefordít egy kis demóalkalmazást, obfuszkálja, deobfuszkálja, majd futtatja az eredeti, az obfuszkált és a deobfuszkált JAR-t. Ellenőrzött eredmény: a deobfuszkált JAR **bájról bájtra ugyanazt a kimenetet** írja, mint az eredeti, **azonos stack trace-t** ad (`at com.example.demo.App.crash(App.java:31)`), és a `javap -c` minden osztályt tisztán szétszed.

---

# Hogyan működik a deobfuscator

A deobfuscator az obfuscator visszafordítható részeinek pontos inverze. Konstanspool- és utasításszinten dolgozik, ugyanazzal a BCEL modellel, amelyet maga az obfuscator is használ, így az, amit visszaír, pontosan az, amit a JVM vár.

Négy mechanizmus, amelyet a `full` folyamat ebben a sorrendben hajt végre:

**1. Karakterlánc-visszafejtés (`DecryptorSimulator`).** Az obfuscator minden karakterlánc-konstanst `ldc "titkosított szöveg"` + `invokestatic DecryptString` párosra cserél. A deobfuscator az utasításprofil alapján (XOR ciklus, 63-as maszkos AND, char tömb építése, `charAt`) megtalálja a jelölt dekóder metódusokat, majd megteszi azt, amit egy naiv eszköz sosem tenne: **ténylegesen lefuttatja a generált dekóder bájtkódját** egy kis veremgép-értelmezőn. Az algoritmus nem számít — bármilyen aritmetikát generált is az Allatori (seed-kiszámítás, láncolt XOR kulcsok, maszkok), az értelmező lefuttatja, és visszaadja a tiszta szöveget. A hívási hely ezután `ldc "tiszta szöveg"` + `nop`-ra íródik át, és amikor már egyetlen élő utasítás sem hivatkozik a dekóder osztályra, az törlésre kerül. A futásidejű értékektől függő dekóderek „titkosítottan maradt” jelzéssel kerülnek jelentésre, találgatás helyett.

**2. Név-helyreállítás (`UnrenameTransformer`).** A mapping XML (`<class old=... new=...>` és `<method old="név(leíró)" new="n"/>`, `<field old="név TÍPUS" new="n"/>`) vezérli a konstanspool-szintű visszanevezést: helyreállásra kerül a `this_class`, a `super_class` és az interfészek, minden metódus- és meződeklaráció visszakapja eredeti nevét, és minden hivatkozás átíródik (Methodref, Fieldref, InterfaceMethodref, azok osztálytulajdonosai és NameAndType bejegyzései) — ideértve azokat a tulajdonosokat is, amelyeket ugyanazon menet során korábban már átírtak. A JAR bejegyzései a helyreállított osztálynevek szerint újrakulcsolódnak, így visszatér az eredeti csomagszerkezet.

**3. StackMapTable átmapelés (`StackMapRemapper`).** A karakterláncok visszafejtése rövidíti a kódot (`invokestatic` 3 bájt → `nop` 1 bájt). A StackMapTable attribútum az újrakódoláskor változatlanul átjut, így a keretei a régi elrendezésre mutatnának tovább, és a JVM verifikátora (a `javap` is) elutasítaná az osztályt. A remapper minden keret eltolását úgy írja át, hogy az az új elrendezés ugyanazon utasítására mutasson — ideértve a `ITEM_Uninitialized` verifikációs bejegyzéseken belüli utasításeltolásokat is.

**4. Stack trace helyreállítás (`restore-stacktrace`).** Ugyanazt a mapping XML-t olvassa (metódusbejegyzések plusz a `<lines>` lista a titkosított sorszámokról), és soronként átírja a rögzített stack trace-t: `at com.example.demo.h.K.K(K.java:7)` lesz belőle `at com.example.demo.App.crash(App.java:31)`.

**Őszinte korlátok.** A teljes név-helyreállításhoz a mapping fájl kell — nélküle az obfuszkálás tervezett módon nem fordítható vissza, és csak a karakterlánc-visszafejtés működik (annak nincs szüksége mappingre). Ez a helyes 1:1 viselkedés: az obfuscator által írt naplóval minden visszaáll, amit az obfuscator megváltoztatott; napló nélkül csak az, ami információvesztés nélkül visszanyerhető.

---

# Projekt szerkezete

```
src/main/java/com/allatori/          obfuscator (eredeti újraimplementáció)
src/main/java/com/allatori/deobfuscate/   deobfuscator
  Deobfuscate.java                   CLI + folyamatvezérlés
  MappingData.java                   az obfuscator log XML-jének értelmezése
  UnrenameTransformer.java           konstanspool-szintű visszanevezés
  StringDecryptorTransformer.java    dekóder-keresés + hívási helyek átírása
  DecryptorSimulator.java            kis veremgép, amely lefuttatja a dekóder bájtkódját
  StackMapRemapper.java              StackMapTable eltolások átmapelése a szerkesztések után
demo/                                demóalkalmazás + konfig a Gradle taskokhoz
tools/                               diagnosztikai segédeszközök (DumpMain)
```

# Hibakeresés

- A PowerShell `NativeCommandError` zajt ír ki a `java`/`gradlew` konzolkimenetére; a kilépési kód helyett a `BUILD SUCCESSFUL`-t nézze.
- Az értékkel rendelkező rendszerproperty-eket idézőjelbe kell tenni: `java "-Dallatori.deobfuscate.trace=true" ...`
- A karakterlánc-visszafejtés hibakeresési diagnosztikája: `-Dallatori.deobfuscate.debug=true` (összefoglaló) és `-Dallatori.deobfuscate.trace=true` (opkódonkénti nyomkövetés a `DecryptorSimulator`-ban).
