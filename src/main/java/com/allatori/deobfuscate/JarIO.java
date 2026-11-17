package com.allatori.deobfuscate;

import com.allatori.lib.bcel.classfile.ClassParser;
import com.allatori.lib.bcel.generic.ClassGen;
import com.allatori.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

/**
 * Reads every class of a JAR into memory as an editable {@link ClassGen} and writes
 * the result back out, preserving all non-class entries (manifest, resources...)
 * byte for byte. Class entries are re-keyed to the (possibly restored) class name.
 */
public class JarIO {

  /** entry path (e.g. "com/example/h/K.class") -> parsed class */
  public final Map<String, ClassGen> classes = new LinkedHashMap<>();

  public final Map<String, byte[]> otherEntries = new LinkedHashMap<>();

  public static JarIO read(String jarPath) throws IOException {
    JarIO jarIO = new JarIO();
    JarFile jarFile = new JarFile(jarPath);
    Enumeration enumeration = jarFile.entries();
    while (enumeration.hasMoreElements()) {
      JarEntry jarEntry = (JarEntry) enumeration.nextElement();
      if (jarEntry.isDirectory()) {
        continue;
      }
      try {
        InputStream inputStream = jarFile.getInputStream(jarEntry);
        byte[] values = readAll(inputStream);
        inputStream.close();
        if (jarEntry.getName().endsWith(".class")
            && !jarEntry.getName().endsWith("module-info.class")) {
          try {
            ClassGen classGen =
                new ClassGen(
                    new ClassParser(new java.io.ByteArrayInputStream(values), jarEntry.getName())
                        .parse());
            jarIO.classes.put(jarEntry.getName(), classGen);
          } catch (Exception exception) {
            Log.error("Cannot parse class " + jarEntry.getName());
            jarIO.otherEntries.put(jarEntry.getName(), values);
          }
        } else {
          jarIO.otherEntries.put(jarEntry.getName(), values);
        }
      } catch (IOException ioException) {
        Log.error("Cannot read entry " + jarEntry.getName());
      }
    }
    jarFile.close();
    return jarIO;
  }

  private static byte[] readAll(InputStream inputStream) throws IOException {
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(65536);
    byte[] values = new byte[65536];
    int value;
    while ((value = inputStream.read(values)) > 0) {
      byteArrayOutputStream.write(values, 0, value);
    }
    return byteArrayOutputStream.toByteArray();
  }

  /**
   * Writes the JAR. Every class is stored under a path derived from its current
   * internal name, so restored class names restore their package layout too.
   */
  public void write(String jarPath) throws IOException {
    File file = new File(jarPath);
    File parent = file.getAbsoluteFile().getParentFile();
    if (parent != null && !parent.exists()) {
      parent.mkdirs();
    }
    JarOutputStream jarOutputStream = new JarOutputStream(new FileOutputStream(file));
    for (Map.Entry<String, byte[]> entry : this.otherEntries.entrySet()) {
      jarOutputStream.putNextEntry(new JarEntry(entry.getKey()));
      jarOutputStream.write(entry.getValue());
      jarOutputStream.closeEntry();
    }
    for (ClassGen classGen : this.classes.values()) {
      String internalName = classGen.getClassName().replace('.', '/');
      jarOutputStream.putNextEntry(new JarEntry(internalName + ".class"));
      jarOutputStream.write(classGen.getJavaClass().getBytes());
      jarOutputStream.closeEntry();
    }
    jarOutputStream.close();
  }
}
