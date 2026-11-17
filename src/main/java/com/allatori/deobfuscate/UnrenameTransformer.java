package com.allatori.deobfuscate;

import com.allatori.lib.bcel.classfile.Attribute;
import com.allatori.lib.bcel.classfile.Code;
import com.allatori.lib.bcel.classfile.Constant;
import com.allatori.lib.bcel.classfile.ConstantClass;
import com.allatori.lib.bcel.classfile.ConstantCP;
import com.allatori.lib.bcel.classfile.ConstantFieldref;
import com.allatori.lib.bcel.classfile.ConstantInterfaceMethodref;
import com.allatori.lib.bcel.classfile.ConstantInvokeDynamic;
import com.allatori.lib.bcel.classfile.ConstantMethodType;
import com.allatori.lib.bcel.classfile.ConstantMethodref;
import com.allatori.lib.bcel.classfile.ConstantNameAndType;
import com.allatori.lib.bcel.classfile.ConstantPool;
import com.allatori.lib.bcel.classfile.ConstantUtf8;
import com.allatori.lib.bcel.classfile.Field;
import com.allatori.lib.bcel.classfile.LocalVariable;
import com.allatori.lib.bcel.classfile.LocalVariableTable;
import com.allatori.lib.bcel.classfile.Method;
import com.allatori.lib.bcel.classfile.Signature;
import com.allatori.lib.bcel.classfile.SourceFile;
import com.allatori.lib.bcel.generic.ConstantPoolGen;
import java.util.List;

/**
 * Restores the original names of classes, methods and fields using the mapping file
 * the obfuscator wrote during obfuscation.
 *
 * The rewriting happens at the constant pool level, mirroring exactly the points the
 * forward renamer touched: class constants, field/method references (with rewritten
 * descriptors), method types (lambda signatures), declared member names and types,
 * generic signatures, local variable descriptors and source file names.
 */
public class UnrenameTransformer {

  public final MappingData mappingData;

  public int renamedClasses;
  public int restoredMethods;
  public int restoredFields;

  public UnrenameTransformer(MappingData mappingData) {
    this.mappingData = mappingData;
  }

  public void transform(ClassGenWrapper wrapper) {
    com.allatori.lib.bcel.generic.ClassGen classGen = wrapper.classGen;
    ConstantPoolGen constantPoolGen = classGen.getConstantPool();
    ConstantPool constantPool = constantPoolGen.getConstantPool();
    String currentDotted = classGen.getClassName();

    // Names of declared members are restored whenever the mapping has an entry for
    // them, even if the class itself kept its name; descriptors are rewritten for
    // every class because they may reference renamed classes.
    for (Method method : classGen.getMethods()) {
      String name = method.getText();
      String descriptor = method.getText2();
      String oldName =
          this.mappingData.methodNewToOld.get(currentDotted + "#" + name + "#" + descriptor);
      if (oldName != null) {
        method.count3 = constantPoolGen.addUtf8(oldName);
        this.restoredMethods++;
      }
      String oldDescriptor = this.mappingData.rewriteDescriptorBackward(descriptor);
      if (!oldDescriptor.equals(descriptor)) {
        method.count = constantPoolGen.addUtf8(oldDescriptor);
      }
    }
    for (Field field : classGen.getFields()) {
      String name = field.getText();
      String type = field.getText2();
      String oldName = this.mappingData.fieldNewToOld.get(currentDotted + "#" + name + "#" + type);
      if (oldName != null) {
        field.count3 = constantPoolGen.addUtf8(oldName);
        this.restoredFields++;
      }
      String oldType = this.mappingData.rewriteDescriptorBackward(type);
      if (!oldType.equals(type)) {
        field.count = constantPoolGen.addUtf8(oldType);
      }
    }
    // interfaces implemented by the class may reference renamed classes regardless
    // of whether this class itself was renamed
    List interfaceNames = classGen.interfaceNames;
    for (int index = 0; index < interfaceNames.size(); index++) {
      String interfaceName = (String) interfaceNames.get(index);
      String old = this.mappingData.classNewToOld.get(interfaceName);
      if (old != null) {
        interfaceNames.set(index, old);
      }
    }
    if (this.mappingData.classNewToOld.containsKey(currentDotted)) {
      // keep the cached class name consistent with the constant pool
      String oldDotted = this.mappingData.classNewToOld.get(currentDotted);
      if (!oldDotted.equals(currentDotted)) {
        classGen.processCount2(constantPoolGen.addClass(oldDotted));
        this.renamedClasses++;
      }
    }

    // ---- 2. constant pool rewriting (applies to every class) ------------
    Constant[] constants = constantPool.getConstantPool();
    for (int index = 0; index < constants.length; index++) {
      Constant constant = constants[index];
      if (constant == null) {
        continue;
      }
      if (constant instanceof ConstantClass) {
        ConstantClass constantClass = (ConstantClass) constant;
        String internalName = utf8(constantPool, constantClass.getNameIndex());
        String dotted = internalName.replace('/', '.');
        String old = this.mappingData.classNewToOld.get(dotted);
        if (old != null) {
          constantClass.setNameIndex(constantPoolGen.addUtf8(old.replace('.', '/')));
        }
      } else if (constant instanceof ConstantFieldref
          || constant instanceof ConstantMethodref
          || constant instanceof ConstantInterfaceMethodref) {
        ConstantCP constantCP = (ConstantCP) constant;
        ConstantNameAndType constantNameAndType =
            (ConstantNameAndType) constants[constantCP.getName_and_type_index()];
        ConstantClass ownerClass = (ConstantClass) constants[constantCP.getClass_index()];
        String ownerInternal = utf8(constantPool, ownerClass.getNameIndex());
        String ownerDotted = ownerInternal.replace('/', '.');
        // the owner's class constant may already have been rewritten in this pass;
        // member keys are keyed by the obfuscated name, so resolve it back
        String ownerObf = ownerDotted;
        if (!this.mappingData.classNewToOld.containsKey(ownerObf)) {
          String forward = this.mappingData.classOldToNew.get(ownerObf);
          if (forward != null) {
            ownerObf = forward;
          }
        }
        String memberName = utf8(constantPool, constantNameAndType.getName_index());
        String memberDesc = utf8(constantPool, constantNameAndType.getSignature_index());
        boolean isMethod =
            !(constant instanceof ConstantFieldref)
                && !"<init>".equals(memberName)
                && !"<clinit>".equals(memberName);
        String restoredName = null;
        String key = ownerObf + "#" + memberName + "#" + memberDesc;
        if (isMethod) {
          restoredName = this.mappingData.methodNewToOld.get(key);
        } else {
          restoredName = this.mappingData.fieldNewToOld.get(key);
        }
        String restoredDesc = this.mappingData.rewriteDescriptorBackward(memberDesc);
        if (restoredName != null || !restoredDesc.equals(memberDesc)) {
          if (restoredName == null) {
            restoredName = memberName;
          }
          constantCP.setName_and_type_index(
              constantPoolGen.addNameAndType(restoredName, restoredDesc));
        }
      } else if (constant instanceof ConstantMethodType) {
        ConstantMethodType constantMethodType = (ConstantMethodType) constant;
        String descriptor = utf8(constantPool, constantMethodType.getDescriptorIndex());
        String restored = this.mappingData.rewriteDescriptorBackward(descriptor);
        if (!restored.equals(descriptor)) {
          constantMethodType.setDescriptorIndex(constantPoolGen.addUtf8(restored));
        }
      } else if (constant instanceof ConstantInvokeDynamic) {
        ConstantInvokeDynamic constantInvokeDynamic = (ConstantInvokeDynamic) constant;
        ConstantNameAndType constantNameAndType =
            (ConstantNameAndType) constants[constantInvokeDynamic.count2];
        String memberName = utf8(constantPool, constantNameAndType.getName_index());
        String memberDesc = utf8(constantPool, constantNameAndType.getSignature_index());
        String restoredDesc = this.mappingData.rewriteDescriptorBackward(memberDesc);
        if (!restoredDesc.equals(memberDesc)) {
          constantInvokeDynamic.setCount2(
              constantPoolGen.addNameAndType(memberName, restoredDesc));
        }
      }
    }

    // ---- 3. attributes ---------------------------------------------------
    this.processAttributes(classGen.getAttributes(), constantPoolGen);
  }

  private void processAttributes(Attribute[] attributes, ConstantPoolGen constantPoolGen) {
    for (Attribute attribute : attributes) {
      if (attribute instanceof Code) {
        this.processAttributes(((Code) attribute).getAttributes(), constantPoolGen);
      } else if (attribute instanceof Signature) {
        Signature signature = (Signature) attribute;
        String text = signature.getText();
        String restored = this.mappingData.rewriteSignatureBackward(text);
        if (!restored.equals(text)) {
          signature.signature_index = constantPoolGen.addUtf8(restored);
        }
      } else if (attribute instanceof LocalVariableTable) {
        LocalVariableTable localVariableTable = (LocalVariableTable) attribute;
        for (LocalVariable localVariable : localVariableTable.local_variable_table) {
          String descriptor = utf8(constantPoolGen.getConstantPool(), localVariable.signature_index);
          String restored = this.mappingData.rewriteDescriptorBackward(descriptor);
          if (!restored.equals(descriptor)) {
            localVariable.signature_index = constantPoolGen.addUtf8(restored);
          }
        }
      } else if (attribute instanceof SourceFile) {
        SourceFile sourceFile = (SourceFile) attribute;
        String current = sourceFile.getText();
        String old = this.mappingData.sourceNewToOld.get(current);
        if (old != null) {
          sourceFile.sourcefile_index = constantPoolGen.addUtf8(old);
        }
      }
    }
  }

  private static String utf8(ConstantPool constantPool, int index) {
    return ((ConstantUtf8) constantPool.getConstant(index)).getBytes();
  }
}
