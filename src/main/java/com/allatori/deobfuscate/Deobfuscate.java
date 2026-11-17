package com.allatori.deobfuscate;

import com.allatori.StackTrace;
import com.allatori.VersionInfo;
import com.allatori.cli.ObfuscationCommandSupport;
import com.allatori.lib.bcel.generic.ClassGen;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deobfuscation entry point. Reverses, exactly and deterministically, the reversible
 * parts of what the obfuscator did:
 *
 *   restore-stacktrace  mapping.xml input.txt output.txt
 *                       rewrites a stack trace of the obfuscated build back to
 *                       original class/method names and line numbers.
 *
 *   rename-back         mapping.xml obfuscated.jar deobfuscated.jar
 *                       restores original class, method and field names from the
 *                       mapping file, including every constant pool reference,
 *                       descriptors, generic signatures and source names.
 *
 *   decrypt-strings     obfuscated.jar deobfuscated.jar [--remove-decryptor]
 *                       replaces every encrypted string constant by its plain text
 *                       by actually running the generated decryptor bytecode.
 *
 *   full                mapping.xml obfuscated.jar deobfuscated.jar [--remove-decryptor]
 *                       rename-back + decrypt-strings in one pass.
 *
 * The mapping file is the XML log written by the obfuscator (property "log-file").
 * Without it, only string decryption (and nothing else) is possible - that is by
 * design: proper obfuscation is not meant to be reversible without the map.
 */
public class Deobfuscate {

  public static void printUsage() {
    System.out.println(
        VersionInfo.getProductName() + " " + VersionInfo.getVersion() + " - deobfuscator");
    System.out.println("Usage:");
    System.out.println(
        "  com.allatori.Deobfuscate restore-stacktrace <mapping.xml> <input.txt> <output.txt>");
    System.out.println(
        "  com.allatori.Deobfuscate rename-back <mapping.xml> <input.jar> <output.jar>");
    System.out.println(
        "  com.allatori.Deobfuscate decrypt-strings <input.jar> <output.jar> [--remove-decryptor]");
    System.out.println(
        "  com.allatori.Deobfuscate full <mapping.xml> <input.jar> <output.jar>"
            + " [--remove-decryptor]");
  }

  public static void main(String[] args) {
    System.out.println(ObfuscationCommandSupport.getBanner());
    if (args.length < 1) {
      printUsage();
      System.exit(0);
    }
    String command = args[0];
    try {
      if ("restore-stacktrace".equals(command)) {
        if (args.length != 4) {
          printUsage();
          System.exit(0);
        }
        restoreStackTrace(args[1], args[2], args[3]);
      } else if ("rename-back".equals(command)) {
        if (args.length != 4) {
          printUsage();
          System.exit(0);
        }
        renameBack(args[1], args[2], args[3], false);
      } else if ("decrypt-strings".equals(command)) {
        if (args.length < 3) {
          printUsage();
          System.exit(0);
        }
        decryptStrings(args[1], args[2], hasFlag(args, "--remove-decryptor"));
      } else if ("full".equals(command)) {
        if (args.length < 4) {
          printUsage();
          System.exit(0);
        }
        full(args[1], args[2], args[3], hasFlag(args, "--remove-decryptor"));
      } else {
        System.out.println("Unknown command: " + command);
        printUsage();
        System.exit(-1);
      }
    } catch (Exception exception) {
      System.out.println("############### EXCEPTION ###############");
      exception.printStackTrace();
      System.out.println("#########################################");
      System.exit(-1);
    }
  }

  private static boolean hasFlag(String[] args, String flag) {
    for (String arg : args) {
      if (flag.equals(arg)) {
        return true;
      }
    }
    return false;
  }

  public static void restoreStackTrace(String mappingFile, String inputFile, String outputFile)
      throws Exception {
    System.out.println("[INFO] Restoring stack trace using mapping " + mappingFile);
    BufferedReader bufferedReader =
        new BufferedReader(new InputStreamReader(new FileInputStream(inputFile), "UTF-8"));
    StringBuilder stringBuilder = new StringBuilder();
    String line;
    while ((line = bufferedReader.readLine()) != null) {
      stringBuilder.append(line);
      stringBuilder.append("\n");
    }
    bufferedReader.close();
    String restored = StackTrace.restoreStackTrace(new File(mappingFile), stringBuilder.toString());
    PrintWriter printWriter =
        new PrintWriter(new BufferedWriter(new OutputStreamWriter(
            new FileOutputStream(outputFile), "UTF-8")));
    printWriter.print(restored);
    printWriter.close();
    System.out.println("[INFO] Stack trace written to " + outputFile);
  }

  public static Map<String, ClassGenWrapper> loadClasses(JarIO jarIO) {
    Map<String, ClassGenWrapper> classes = new LinkedHashMap<>();
    for (Map.Entry<String, ClassGen> entry : jarIO.classes.entrySet()) {
      String entryName = entry.getKey();
      String internalName = entryName.substring(0, entryName.length() - ".class".length());
      classes.put(entryName, new ClassGenWrapper(internalName, entry.getValue()));
    }
    return classes;
  }

  public static void renameBack(
      String mappingFile, String inputJar, String outputJar, boolean quiet) throws Exception {
    MappingData mappingData = MappingData.parse(mappingFile);
    renameBack(mappingData, inputJar, outputJar, quiet);
  }

  public static void renameBack(
      MappingData mappingData, String inputJar, String outputJar, boolean quiet) throws Exception {
    if (!quiet) {
      System.out.println(
          "[INFO] Mapping loaded: "
              + mappingData.classNewToOld.size()
              + " classes, "
              + mappingData.methodNewToOld.size()
              + " methods, "
              + mappingData.fieldNewToOld.size()
              + " fields");
    }
    JarIO jarIO = JarIO.read(inputJar);
    Map<String, ClassGenWrapper> classes = loadClasses(jarIO);
    UnrenameTransformer unrenameTransformer = new UnrenameTransformer(mappingData);
    for (ClassGenWrapper wrapper : classes.values()) {
      unrenameTransformer.transform(wrapper);
    }
    if (!quiet) {
      System.out.println(
          "[INFO] Restored "
              + unrenameTransformer.renamedClasses
              + " classes, "
              + unrenameTransformer.restoredMethods
              + " methods, "
              + unrenameTransformer.restoredFields
              + " fields");
    }
    jarIO.write(outputJar);
    if (!quiet) {
      System.out.println("[INFO] Deobfuscated JAR written to " + outputJar);
    }
  }

  public static void decryptStrings(String inputJar, String outputJar, boolean removeDecryptor)
      throws Exception {
    JarIO jarIO = JarIO.read(inputJar);
    Map<String, ClassGenWrapper> classes = loadClasses(jarIO);
    StringDecryptorTransformer stringDecryptorTransformer = new StringDecryptorTransformer();
    stringDecryptorTransformer.collectCandidates(classes);
    System.out.println(
        "[INFO] Decryptor candidates found: " + stringDecryptorTransformer.candidates.size());
    stringDecryptorTransformer.transform(classes);
    System.out.println(
        "[INFO] Decrypted "
            + stringDecryptorTransformer.decryptedCount
            + " strings ("
            + stringDecryptorTransformer.unsupportedCount
            + " left encrypted: runtime-dependent decryptors)");
    if (removeDecryptor) {
      removeDecryptorClasses(jarIO, classes, stringDecryptorTransformer, null);
    }
    jarIO.write(outputJar);
    System.out.println("[INFO] Deobfuscated JAR written to " + outputJar);
  }

  public static void full(String mappingFile, String inputJar, String outputJar, boolean removeDecryptor)
      throws Exception {
    MappingData mappingData = MappingData.parse(mappingFile);
    System.out.println(
        "[INFO] Mapping loaded: "
            + mappingData.classNewToOld.size()
            + " classes, "
            + mappingData.methodNewToOld.size()
            + " methods, "
            + mappingData.fieldNewToOld.size()
            + " fields");
    JarIO jarIO = JarIO.read(inputJar);
    Map<String, ClassGenWrapper> classes = loadClasses(jarIO);
    // string decryption first: it must see the obfuscated call sites and decryptor
    // names exactly as the obfuscator generated them
    StringDecryptorTransformer stringDecryptorTransformer = new StringDecryptorTransformer();
    stringDecryptorTransformer.collectCandidates(classes);
    System.out.println(
        "[INFO] Decryptor candidates found: " + stringDecryptorTransformer.candidates.size());
    stringDecryptorTransformer.transform(classes);
    System.out.println(
        "[INFO] Decrypted "
            + stringDecryptorTransformer.decryptedCount
            + " strings ("
            + stringDecryptorTransformer.unsupportedCount
            + " left encrypted: runtime-dependent decryptors)");
    if (removeDecryptor) {
      removeDecryptorClasses(jarIO, classes, stringDecryptorTransformer, mappingData);
    }
    // then restore names: classes, members, every constant pool reference
    UnrenameTransformer unrenameTransformer = new UnrenameTransformer(mappingData);
    for (ClassGenWrapper wrapper : classes.values()) {
      unrenameTransformer.transform(wrapper);
    }
    System.out.println(
        "[INFO] Restored "
            + unrenameTransformer.renamedClasses
            + " classes, "
            + unrenameTransformer.restoredMethods
            + " methods, "
            + unrenameTransformer.restoredFields
            + " fields");
    jarIO.write(outputJar);
    System.out.println("[INFO] Deobfuscated JAR written to " + outputJar);
  }

  /**
   * Removes decryptor classes: those that hosted verified decryptors and are no
   * longer referenced anywhere, plus (when a mapping is present) classes whose
   * original name marks them as the obfuscator's own string decryptor.
   */
  private static void removeDecryptorClasses(
      JarIO jarIO,
      Map<String, ClassGenWrapper> classes,
      StringDecryptorTransformer stringDecryptorTransformer,
      MappingData mappingData) {
    List<String> removable = new ArrayList<>(stringDecryptorTransformer.removableClasses(classes));
    if (mappingData != null) {
      for (Map.Entry<String, String> entry : mappingData.classOldToNew.entrySet()) {
        String oldName = entry.getKey();
        if (oldName.startsWith("com_allatori") || oldName.contains("string_decryptor")) {
          String newName = entry.getValue();
          if (!removable.contains(newName)) {
            removable.add(newName);
          }
        }
      }
    }
    for (ClassGenWrapper wrapper : new ArrayList<>(classes.values())) {
      if (wrapper.internalName.startsWith("com_allatori")
          || wrapper.internalName.startsWith("com/allatori/runtime")) {
        if (!removable.contains(wrapper.internalName)) {
          removable.add(wrapper.internalName);
        }
      }
    }
    // never remove a class that is still referenced by live instructions
    List<String> safe = new ArrayList<>();
    for (String internalName : removable) {
      boolean referenced = false;
      for (ClassGenWrapper wrapper : classes.values()) {
        if (wrapper.internalName.equals(internalName)) {
          continue;
        }
        if (stringDecryptorTransformer.referencesClass(wrapper.classGen, internalName)) {
          referenced = true;
          break;
        }
      }
      if (!referenced) {
        safe.add(internalName);
      }
    }
    for (String internalName : safe) {
      boolean removed = false;
      for (Map.Entry<String, ClassGenWrapper> entry : new ArrayList<>(classes.entrySet())) {
        if (entry.getValue().internalName.equals(internalName)) {
          jarIO.classes.remove(entry.getKey());
          classes.remove(entry.getKey());
          removed = true;
        }
      }
      if (removed) {
        System.out.println("[INFO] Removed decryptor class " + internalName);
      }
    }
  }
}
