package com.allatori.deobfuscate;

import com.allatori.lib.bcel.classfile.Constant;
import com.allatori.lib.bcel.classfile.ConstantClass;
import com.allatori.lib.bcel.classfile.ConstantCP;
import com.allatori.lib.bcel.classfile.ConstantString;
import com.allatori.lib.bcel.classfile.ConstantUtf8;
import com.allatori.lib.bcel.classfile.Method;
import com.allatori.bytecode.MethodFactory;
import com.allatori.lib.bcel.generic.ConstantPoolGen;
import com.allatori.lib.bcel.generic.CPInstruction;
import com.allatori.lib.bcel.generic.Instruction;
import com.allatori.lib.bcel.generic.InstructionHandle;
import com.allatori.lib.bcel.generic.InstructionList;
import com.allatori.lib.bcel.generic.InvokeInstruction;
import com.allatori.lib.bcel.generic.LDC;
import com.allatori.lib.bcel.generic.MethodGen;
import com.allatori.lib.bcel.generic.NOP;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decrypts strings that were encrypted by the obfuscator.
 *
 * Detection is structural: any static method with signature (String)String whose body
 * matches the shape of the generated decryptors (XOR loop, mask 63, char array build)
 * is considered a candidate; it is then verified by actually running it on the
 * ciphertext with {@link DecryptorSimulator}. Call sites of the form
 *
 *   ldc "ciphertext" ; invokestatic Decryptor.decrypt
 *
 * are rewritten to a plain string constant, and the call becomes a NOP so the stack
 * shape does not change.
 */
public class StringDecryptorTransformer {

  /** candidate decryptor identity: "internalClassName.methodName" */
  public final Map<String, MethodGen> candidates = new LinkedHashMap<>();

  public final Set<String> verified = new HashSet<>();

  public int decryptedCount;
  public int unsupportedCount;

  public void collectCandidates(Map<String, ClassGenWrapper> classes) {
    for (ClassGenWrapper wrapper : classes.values()) {
      ConstantPoolGen constantPoolGen = wrapper.classGen.getConstantPool();
      for (Method method : wrapper.classGen.getMethods()) {
        if (!DecryptorSimulator.looksLikeDecryptor(method)) {
          continue;
        }
        if (method.getCode() == null) {
          continue;
        }
        MethodGen methodGen =
            MethodFactory.createMethodGen(
                method,
                wrapper.classGen.getClassName(),
                constantPoolGen,
                constantPoolGen.getConstantPool());
        if (isDecryptorShape(methodGen)) {
          this.candidates.put(wrapper.internalName + "." + method.getText(), methodGen);
        }
      }
    }
  }

  /** Static shape check: the generated decryptors all contain this instruction profile. */
  private boolean isDecryptorShape(MethodGen methodGen) {
    boolean hasXor = false;
    boolean hasMask63 = false;
    boolean hasCharArray = false;
    boolean hasCharAt = false;
    InstructionHandle instructionHandle = methodGen.getInstructionList().getStart();
    int guard = 0;
    while (instructionHandle != null && guard++ < 100000) {
      Instruction instruction = instructionHandle.instruction;
      short opcode = instruction.opcode;
      if (opcode == 130) { // IXOR
        hasXor = true;
      } else if (opcode == 126) { // IAND
        hasMask63 = true; // exact constant checked loosely; 63 is the Allatori mask
      } else if (opcode == 188 || opcode == 189) { // NEWARRAY / ANEWARRAY
        hasCharArray = true;
      } else if (opcode == 182) { // INVOKEVIRTUAL
        String name = methodName(((InvokeInstruction) instruction).getIndex(), methodGen);
        if ("charAt".equals(name)) {
          hasCharAt = true;
        }
      }
      instructionHandle = instructionHandle.instructionHandle2;
    }
    return hasXor && hasMask63 && hasCharArray && hasCharAt;
  }

  private String methodName(int constantIndex, MethodGen methodGen) {
    com.allatori.lib.bcel.classfile.ConstantCP constantCP =
        (com.allatori.lib.bcel.classfile.ConstantCP)
            methodGen.getConstantPool().getConstantPool().getConstant(constantIndex);
    com.allatori.lib.bcel.classfile.ConstantNameAndType constantNameAndType =
        (com.allatori.lib.bcel.classfile.ConstantNameAndType)
            methodGen
                .getConstantPool()
                .getConstantPool()
                .getConstant(constantCP.getName_and_type_index());
    return ((com.allatori.lib.bcel.classfile.ConstantUtf8)
            methodGen
                .getConstantPool()
                .getConstantPool()
                .getConstant(constantNameAndType.getName_index()))
        .getBytes();
  }

  /** Decrypts every ldc+invokestatic pair that targets a verified decryptor. */
  public void transform(Map<String, ClassGenWrapper> classes) {
    for (ClassGenWrapper wrapper : classes.values()) {
      ConstantPoolGen constantPoolGen = wrapper.classGen.getConstantPool();
      for (Method method : wrapper.classGen.getMethods()) {
        if (method.getCode() == null) {
          continue;
        }
        MethodGen methodGen =
            MethodFactory.createMethodGen(
                method,
                wrapper.classGen.getClassName(),
                constantPoolGen,
                constantPoolGen.getConstantPool());
        InstructionList instructionList = methodGen.getInstructionList();
        // positions at this point come from parsing the old code; remember them so
        // frame offsets of the StackMapTable can be remapped after the edit
        Map<InstructionHandle, Integer> oldPositions = new HashMap<>();
        for (InstructionHandle handle = instructionList.getStart();
            handle != null;
            handle = handle.instructionHandle2) {
          oldPositions.put(handle, handle.getPosition());
        }
        InstructionHandle instructionHandle = instructionList.getStart();
        boolean changed = false;
        int guard = 0;
        while (instructionHandle != null && guard++ < 1000000) {
          InstructionHandle current = instructionHandle;
          instructionHandle = instructionHandle.instructionHandle2;
          Instruction instruction = current.instruction;
          if (!(instruction instanceof LDC)) {
            continue;
          }
          LDC lDC = (LDC) instruction;
          Constant constant = constantPoolGen.getConstantPool().getConstant(lDC.getIndex());
          if (!(constant instanceof ConstantString)) {
            continue;
          }
          InstructionHandle next = current.instructionHandle2;
          if (next == null) {
            continue;
          }
          Instruction nextInstruction = next.instruction;
          if (!(nextInstruction instanceof InvokeInstruction)) {
            continue;
          }
          InvokeInstruction invokeInstruction = (InvokeInstruction) nextInstruction;
          if (invokeInstruction.opcode != 184) { // INVOKESTATIC only
            continue;
          }
          String key =
              resolveConstantClass(invokeInstruction, constantPoolGen)
                  + "."
                  + resolveConstantName(invokeInstruction, constantPoolGen);
          MethodGen decryptor = this.candidates.get(key);
          if (decryptor == null) {
            continue;
          }
          String encrypted =
              ((ConstantUtf8)
                      constantPoolGen
                          .getConstantPool()
                          .getConstant(((ConstantString) constant).getStringIndex()))
                  .getBytes();
          String plain = DecryptorSimulator.simulate(decryptor, encrypted);
          if (plain != null) {
            this.verified.add(key);
          }
          if (plain == null) {
            this.unsupportedCount++;
            continue;
          }
          lDC.setIndex(constantPoolGen.addString(plain));
          next.setInstruction(new NOP());
          changed = true;
          this.decryptedCount++;
        }
        if (changed) {
          // recompute positions for the new layout, then remap StackMapTable frames
          // (carried as an opaque attribute) so they point at the same instructions
          instructionList.setPositions(false);
          Map<Integer, Integer> oldToNewPositions = new HashMap<>();
          for (InstructionHandle handle = instructionList.getStart();
              handle != null;
              handle = handle.instructionHandle2) {
            Integer oldPos = oldPositions.get(handle);
            if (oldPos != null) {
              oldToNewPositions.put(oldPos, handle.getPosition());
            }
          }
          if (!StackMapRemapper.remap(methodGen, oldToNewPositions)) {
            System.err.println(
                "[deobfuscate] warning: StackMapTable of "
                    + wrapper.classGen.getClassName()
                    + "."
                    + method.getText()
                    + " could not be remapped; the method may fail verification");
          }
          wrapper.classGen.replaceMethod(method, methodGen.getMethod());
        }
      }
    }
  }

  private String resolveConstantClass(
      InvokeInstruction invokeInstruction, ConstantPoolGen constantPoolGen) {
    com.allatori.lib.bcel.classfile.ConstantCP constantCP =
        (com.allatori.lib.bcel.classfile.ConstantCP)
            constantPoolGen.getConstantPool().getConstant(invokeInstruction.getIndex());
    com.allatori.lib.bcel.classfile.ConstantClass constantClass =
        (com.allatori.lib.bcel.classfile.ConstantClass)
            constantPoolGen.getConstantPool().getConstant(constantCP.getClass_index());
    return ((com.allatori.lib.bcel.classfile.ConstantUtf8)
            constantPoolGen
                .getConstantPool()
                .getConstant(constantClass.getNameIndex()))
        .getBytes();
  }

  private String resolveConstantName(
      InvokeInstruction invokeInstruction, ConstantPoolGen constantPoolGen) {
    com.allatori.lib.bcel.classfile.ConstantCP constantCP =
        (com.allatori.lib.bcel.classfile.ConstantCP)
            constantPoolGen.getConstantPool().getConstant(invokeInstruction.getIndex());
    com.allatori.lib.bcel.classfile.ConstantNameAndType constantNameAndType =
        (com.allatori.lib.bcel.classfile.ConstantNameAndType)
            constantPoolGen
                .getConstantPool()
                .getConstant(constantCP.getName_and_type_index());
    return ((com.allatori.lib.bcel.classfile.ConstantUtf8)
            constantPoolGen
                .getConstantPool()
                .getConstant(constantNameAndType.getName_index()))
        .getBytes();
  }

  /** Internal names of classes that hold verified decryptors and are no longer referenced. */
  public List<String> removableClasses(Map<String, ClassGenWrapper> classes) {
    List<String> removable = new ArrayList<>();
    Set<String> decryptorClasses = new HashSet<>();
    for (String key : this.verified) {
      decryptorClasses.add(key.substring(0, key.indexOf('.')));
    }
    if (decryptorClasses.isEmpty()) {
      return removable;
    }
    for (String decryptorClass : decryptorClasses) {
      boolean referenced = false;
      for (ClassGenWrapper wrapper : classes.values()) {
        if (wrapper.internalName.equals(decryptorClass)) {
          continue;
        }
        if (references(wrapper.classGen, decryptorClass)) {
          referenced = true;
          break;
        }
      }
      if (!referenced) {
        removable.add(decryptorClass);
      }
    }
    return removable;
  }

  /** Public access for removal logic: live-reference check for one class name. */
  public boolean referencesClass(
      com.allatori.lib.bcel.generic.ClassGen classGen, String internalName) {
    return this.references(classGen, internalName);
  }

  /**
   * True if any instruction of any method of the class still references the named
   * class. Scans live instructions rather than raw constant pool entries, because
   * replaced call sites keep their (now dead) method references in the pool.
   */
  private boolean references(com.allatori.lib.bcel.generic.ClassGen classGen, String internalName) {
    com.allatori.lib.bcel.classfile.ConstantPool constantPool =
        classGen.getConstantPool().getConstantPool();
    for (Method method : classGen.getMethods()) {
      if (method.getCode() == null) {
        continue;
      }
      com.allatori.lib.bcel.generic.MethodGen methodGen =
          MethodFactory.createMethodGen(
              method,
              classGen.getClassName(),
              classGen.getConstantPool(),
              classGen.getConstantPool().getConstantPool());
      InstructionHandle instructionHandle = methodGen.getInstructionList().getStart();
      int guard = 0;
      while (instructionHandle != null && guard++ < 1000000) {
        Instruction instruction = instructionHandle.instruction;
        instructionHandle = instructionHandle.instructionHandle2;
        if (!(instruction instanceof CPInstruction)) {
          continue;
        }
        Constant constant = constantPool.getConstant(((CPInstruction) instruction).index);
        String referenced = null;
        if (constant instanceof ConstantCP) {
          com.allatori.lib.bcel.classfile.Constant[] entries = constantPool.getConstantPool();
          ConstantClass ownerClass =
              (ConstantClass) entries[((ConstantCP) constant).getClass_index()];
          referenced =
              ((ConstantUtf8) constantPool.getConstant(ownerClass.getNameIndex())).getBytes();
        } else if (constant instanceof ConstantClass) {
          referenced =
              ((ConstantUtf8) constantPool.getConstant(((ConstantClass) constant).getNameIndex()))
                  .getBytes();
        }
        if (internalName.equals(referenced)) {
          return true;
        }
      }
    }
    return false;
  }
}
