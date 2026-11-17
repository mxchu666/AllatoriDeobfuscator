package com.allatori.deobfuscate;

import com.allatori.lib.bcel.generic.ClassGen;

/** Simple holder pairing a parsed class with its internal (slash form) name. */
public class ClassGenWrapper {

  public final String internalName;
  public final ClassGen classGen;

  public ClassGenWrapper(String internalName, ClassGen classGen) {
    this.internalName = internalName;
    this.classGen = classGen;
  }
}
