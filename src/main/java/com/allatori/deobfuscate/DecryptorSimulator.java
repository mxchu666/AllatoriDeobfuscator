package com.allatori.deobfuscate;

import com.allatori.lib.bcel.classfile.Constant;
import com.allatori.lib.bcel.classfile.ConstantCP;
import com.allatori.lib.bcel.classfile.ConstantClass;
import com.allatori.lib.bcel.classfile.ConstantNameAndType;
import com.allatori.lib.bcel.classfile.ConstantPool;
import com.allatori.lib.bcel.classfile.ConstantString;
import com.allatori.lib.bcel.classfile.ConstantUtf8;
import com.allatori.lib.bcel.classfile.Method;
import com.allatori.lib.bcel.generic.BIPUSH;
import com.allatori.lib.bcel.generic.BranchInstruction;
import com.allatori.lib.bcel.generic.CPInstruction;
import com.allatori.lib.bcel.generic.IINC;
import com.allatori.lib.bcel.generic.Instruction;
import com.allatori.lib.bcel.generic.InstructionHandle;
import com.allatori.lib.bcel.generic.InvokeInstruction;
import com.allatori.lib.bcel.generic.LocalVariableInstruction;
import com.allatori.lib.bcel.generic.MethodGen;
import com.allatori.lib.bcel.generic.NEWARRAY;
import com.allatori.lib.bcel.generic.SIPUSH;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Executes a generated Allatori string decryptor on a given ciphertext WITHOUT a JVM,
 * using a tiny interpreter over the exact opcodes these machine-built methods contain.
 *
 * This makes the deobfuscator algorithm-agnostic: whatever XOR schedule, key order or
 * marker handling the obfuscator generated, the original bytecode decides the result.
 * Methods that depend on runtime state the interpreter cannot observe (for example the
 * RuntimeException-based "DecryptString2" of the v4-global scheme) abort and return
 * {@code null}; their strings are then left untouched and reported.
 */
public class DecryptorSimulator {

  private static final int MAX_STEPS = 1 << 20;

  /** When true, every interpreter abort prints the offending instruction. */
  public static final boolean DEBUG = Boolean.getBoolean("allatori.deobfuscate.debug");

  /** When true, every executed instruction is printed (implies DEBUG). */
  public static final boolean TRACE = Boolean.getBoolean("allatori.deobfuscate.trace");

  public static String simulate(MethodGen methodGen, String input) {
    try {
      Machine machine = new Machine(methodGen, input);
      return machine.run();
    } catch (AbortException | StackOverflowError | OutOfMemoryError runtimeException) {
      return Machine.abort("exception");
    }
  }

  /** True if the method is static and has signature (String)String. */
  public static boolean looksLikeDecryptor(Method method) {
    if ((method.access_flags & 8) == 0) { // not static
      return false;
    }
    return "(Ljava/lang/String;)Ljava/lang/String;".equals(method.getText2());
  }

  private static class AbortException extends RuntimeException {}

  private static class Machine {

    /** Position/instruction of the last executed step, for abort diagnostics. */
    public static InstructionHandle lastPc;

    /** The machine currently executing, for stack dumps in diagnostics. */
    public static Machine current;

    public final Deque<Object> stack = new ArrayDeque<>();
    public Object[] locals;
    public final String input;
    public final ConstantPool constantPool;
    public InstructionHandle pc;
    public int steps;

    public Machine(MethodGen methodGen, String input) {
      Machine.current = this;
      this.constantPool = methodGen.getConstantPool().getConstantPool();
      this.input = input;
      int value;
      try {
        value = methodGen.getMethod().getCode().max_locals;
      } catch (Exception exception) {
        value = 32;
      }
      this.locals = new Object[Math.max(32, value + 4)];
      this.locals[0] = input;
      this.pc = methodGen.getInstructionList().getStart();
    }

    public String run() {
      while (this.pc != null) {
        if (++this.steps > MAX_STEPS) {
          return abort("step limit exceeded");
        }
        Machine.lastPc = this.pc;
        Instruction instruction = this.pc.instruction;
        InstructionHandle next = this.pc.instructionHandle2;
        if (instruction == null) {
          return abort("end of code without return");
        }
        if (TRACE) {
          StringBuilder dump = new StringBuilder();
          for (Object value : this.stack) {
            dump.append(" ").append(name(value));
          }
          System.out.println(
              "[TRACE] "
                  + this.pc.getPosition()
                  + ": "
                  + instruction
                  + " | depth="
                  + this.stack.size()
                  + dump);
        }
        short opcode = instruction.opcode;
        switch (opcode) {
          case 0: // NOP
            break;
          case 1: // ACONST_NULL
            push(null);
            break;
          case 2: // ICONST_M1
          case 3: // ICONST_0
          case 4: // ICONST_1
          case 5: // ICONST_2
          case 6: // ICONST_3
          case 7: // ICONST_4
          case 8: // ICONST_5
            push(opcode - 3);
            break;
          case 16: // BIPUSH
            push(((BIPUSH) instruction).b);
            break;
          case 17: // SIPUSH
            push(((SIPUSH) instruction).b);
            break;
          case 18: // LDC
            {
              int constantIndex = ((CPInstruction) instruction).getIndex();
              Constant constant = this.constantPool.getConstant(constantIndex);
              if (constant instanceof ConstantString) {
                push(constantString(constantIndex));
              } else if (constant
                  instanceof com.allatori.lib.bcel.classfile.ConstantInteger) {
                push(
                    ((com.allatori.lib.bcel.classfile.ConstantInteger) constant).getBytes());
              } else {
                return abort("unsupported");
              }
            }
            break;
          case 21: // ILOAD
          case 25: // ALOAD
          case 26: // ILOAD_0
          case 27: // ILOAD_1
          case 28: // ILOAD_2
          case 29: // ILOAD_3
          case 42: // ALOAD_0
          case 43: // ALOAD_1
          case 44: // ALOAD_2
          case 45: // ALOAD_3
            {
              int localIndex = localIndex(instruction);
              if (localIndex >= this.locals.length) {
                return abort("unsupported");
              }
              push(this.locals[localIndex]);
            }
            break;
          case 54: // ISTORE
          case 58: // ASTORE
          case 59: // ISTORE_0
          case 60: // ISTORE_1
          case 61: // ISTORE_2
          case 62: // ISTORE_3
          case 75: // ASTORE_0
          case 76: // ASTORE_1
          case 77: // ASTORE_2
          case 78: // ASTORE_3
            {
              int localIndex = localIndex(instruction);
              if (localIndex >= this.locals.length) {
                return abort("unsupported");
              }
              this.locals[localIndex] = pop();
            }
            break;
          case 89: // DUP
            {
              Object value = pop();
              push(value);
              push(value);
            }
            break;
          case 90: // DUP_X1
            {
              Object value1 = pop();
              Object value2 = pop();
              push(value1);
              push(value2);
              push(value1);
            }
            break;
          case 91: // DUP_X2
            {
              Object value1 = pop();
              Object value2 = pop();
              Object value3 = pop();
              push(value1);
              push(value3);
              push(value2);
              push(value1);
            }
            break;
          case 92: // DUP2
            {
              Object value1 = pop();
              Object value2 = pop();
              push(value2);
              push(value1);
              push(value2);
              push(value1);
            }
            break;
          case 93: // DUP2_X1
            {
              Object value1 = pop();
              Object value2 = pop();
              Object value3 = pop();
              push(value2);
              push(value1);
              push(value3);
              push(value2);
              push(value1);
            }
            break;
          case 94: // DUP2_X2
            {
              Object value1 = pop();
              Object value2 = pop();
              Object value3 = pop();
              Object value4 = pop();
              push(value2);
              push(value1);
              push(value4);
              push(value3);
              push(value2);
              push(value1);
            }
            break;
          case 87: // POP
            pop();
            break;
          case 88: // POP2
            pop();
            pop();
            break;
          case 95: // SWAP
            {
              Object value1 = pop();
              Object value2 = pop();
              push(value1);
              push(value2);
            }
            break;
          case 96: // IADD
            {
              int value2 = popInt();
              int value1 = popInt();
              push(value1 + value2);
            }
            break;
          case 100: // ISUB
            {
              int value2 = popInt();
              int value1 = popInt();
              push(value1 - value2);
            }
            break;
          case 104: // IMUL
            {
              int value2 = popInt();
              int value1 = popInt();
              push(value1 * value2);
            }
            break;
          case 108: // IDIV
            {
              int value2 = popInt();
              int value1 = popInt();
              push(value1 / value2);
            }
            break;
          case 112: // IREM
            {
              int value2 = popInt();
              int value1 = popInt();
              push(value1 % value2);
            }
            break;
          case 116: // INEG
            push(-popInt());
            break;
          case 120: // ISHL
            {
              int value2 = popInt();
              int value1 = popInt();
              push(value1 << value2);
            }
            break;
          case 122: // ISHR
            {
              int value2 = popInt();
              int value1 = popInt();
              push(value1 >> value2);
            }
            break;
          case 124: // IUSHR
            {
              int value2 = popInt();
              int value1 = popInt();
              push(value1 >>> value2);
            }
            break;
          case 126: // IAND
            {
              int value2 = popInt();
              int value1 = popInt();
              push(value1 & value2);
            }
            break;
          case 128: // IOR
            {
              int value2 = popInt();
              int value1 = popInt();
              push(value1 | value2);
            }
            break;
          case 130: // IXOR
            {
              int value2 = popInt();
              int value1 = popInt();
              push(value1 ^ value2);
            }
            break;
          case 132: // IINC
            {
              IINC iINC = (IINC) instruction;
              int localIndex = iINC.n;
              if (localIndex >= this.locals.length) {
                return abort("unsupported");
              }
              Object value = this.locals[localIndex];
              this.locals[localIndex] = ((Integer) value) + iINC.c;
            }
            break;
          case 133: // I2L
            push((long) popInt());
            break;
          case 134: // I2F
            push((float) popInt());
            break;
          case 135: // I2D
            push((double) popInt());
            break;
          case 146: // I2C
            push((char) popInt());
            break;
          case 145: // I2B
            push((byte) popInt());
            break;
          case 147: // I2S
            push((short) popInt());
            break;
          case 190: // ARRAYLENGTH
            {
              Object value = pop();
              if (value instanceof char[]) {
                push(((char[]) value).length);
              } else if (value instanceof byte[]) {
                push(((byte[]) value).length);
              } else if (value instanceof int[]) {
                push(((int[]) value).length);
              } else {
                return abort("unsupported");
              }
            }
            break;
          case 167: // GOTO
            next = ((BranchInstruction) instruction).getTarget();
            break;
          case 153: // IFEQ
            next = branch(((BranchInstruction) instruction).getTarget(), popInt() == 0, next);
            break;
          case 154: // IFNE
            next = branch(((BranchInstruction) instruction).getTarget(), popInt() != 0, next);
            break;
          case 155: // IFLT
            next = branch(((BranchInstruction) instruction).getTarget(), popInt() < 0, next);
            break;
          case 156: // IFGE
            next = branch(((BranchInstruction) instruction).getTarget(), popInt() >= 0, next);
            break;
          case 157: // IFGT
            next = branch(((BranchInstruction) instruction).getTarget(), popInt() > 0, next);
            break;
          case 158: // IFLE
            next = branch(((BranchInstruction) instruction).getTarget(), popInt() <= 0, next);
            break;
          case 159: // IF_ICMPEQ
            next = branch2(instruction, next, 0);
            break;
          case 160: // IF_ICMPNE
            next = branch2(instruction, next, 1);
            break;
          case 161: // IF_ICMPLT
            next = branch2(instruction, next, 2);
            break;
          case 162: // IF_ICMPGE
            next = branch2(instruction, next, 3);
            break;
          case 163: // IF_ICMPGT
            next = branch2(instruction, next, 4);
            break;
          case 164: // IF_ICMPLE
            next = branch2(instruction, next, 5);
            break;
          case 187: // NEW
            {
              String className = classConstant(((CPInstruction) instruction).getIndex());
              if ("java/lang/String".equals(className)) {
                push(new PendingNew());
              } else {
                return abort("unsupported"); // e.g. RuntimeException (stack-trace based decryptors)
              }
            }
            break;
          case 188: // NEWARRAY
            {
              Object countValue = pop();
              if (!(countValue instanceof Integer)) {
                return abort("unsupported");
              }
              int count = (Integer) countValue;
              byte basicType = ((NEWARRAY) instruction).getTypecode();
              if (basicType == 5) { // T_CHAR
                push(new char[Math.max(0, count)]);
              } else if (basicType == 8) { // T_BYTE
                push(new byte[Math.max(0, count)]);
              } else if (basicType == 10) { // T_INT
                push(new int[Math.max(0, count)]);
              } else {
                return abort("unsupported");
              }
            }
            break;
          case 189: // ANEWARRAY
            {
              Object countValue = pop();
              if (!(countValue instanceof Integer)) {
                return abort("unsupported");
              }
              push(new Object[Math.max(0, (Integer) countValue)]);
            }
            break;
          case 83: // AASTORE
            {
              Object value = pop();
              Object indexValue = pop();
              Object arrayValue = pop();
              if (!(arrayValue instanceof Object[]) || !(indexValue instanceof Integer)) {
                return abort("unsupported");
              }
              ((Object[]) arrayValue)[(Integer) indexValue] = value;
            }
            break;
          case 84: // BASTORE
            {
              Object value = pop();
              Object indexValue = pop();
              Object arrayValue = pop();
              if (!(arrayValue instanceof byte[])
                  || !(indexValue instanceof Integer)
                  || !(value instanceof Integer)) {
                return abort("unsupported");
              }
              ((byte[]) arrayValue)[(Integer) indexValue] = (byte) (int) (Integer) value;
            }
            break;
          case 85: // CASTORE
            {
              Object value = pop();
              Object indexValue = pop();
              Object arrayValue = pop();
              if (!(arrayValue instanceof char[])
                  || !(indexValue instanceof Integer)
                  || !(value instanceof Integer)) {
                return abort("unsupported");
              }
              ((char[]) arrayValue)[(Integer) indexValue] = (char) (int) (Integer) value;
            }
            break;
          case 86: // SASTORE
            {
              Object value = pop();
              Object indexValue = pop();
              Object arrayValue = pop();
              if (!(arrayValue instanceof short[])
                  || !(indexValue instanceof Integer)
                  || !(value instanceof Integer)) {
                return abort("unsupported");
              }
              ((short[]) arrayValue)[(Integer) indexValue] = (short) (int) (Integer) value;
            }
            break;
          case 79: // IASTORE
            {
              Object value = pop();
              Object indexValue = pop();
              Object arrayValue = pop();
              if (!(arrayValue instanceof int[])
                  || !(indexValue instanceof Integer)
                  || !(value instanceof Integer)) {
                return abort("unsupported");
              }
              ((int[]) arrayValue)[(Integer) indexValue] = (Integer) value;
            }
            break;
          case 46: // IALOAD
            {
              Object indexValue = pop();
              Object arrayValue = pop();
              if (!(arrayValue instanceof int[]) || !(indexValue instanceof Integer)) {
                return abort("unsupported");
              }
              push(((int[]) arrayValue)[(Integer) indexValue]);
            }
            break;
          case 50: // AALOAD
            {
              Object indexValue = pop();
              Object arrayValue = pop();
              if (!(arrayValue instanceof Object[]) || !(indexValue instanceof Integer)) {
                return abort("unsupported");
              }
              push(((Object[]) arrayValue)[(Integer) indexValue]);
            }
            break;
          case 51: // BALOAD
            {
              Object indexValue = pop();
              Object arrayValue = pop();
              if (!(arrayValue instanceof byte[]) || !(indexValue instanceof Integer)) {
                return abort("unsupported");
              }
              push(((byte[]) arrayValue)[(Integer) indexValue]);
            }
            break;
          case 52: // CALOAD
            {
              Object indexValue = pop();
              Object arrayValue = pop();
              if (!(arrayValue instanceof char[]) || !(indexValue instanceof Integer)) {
                return abort("unsupported");
              }
              push(((char[]) arrayValue)[(Integer) indexValue]);
            }
            break;
          case 53: // SALOAD
            {
              Object indexValue = pop();
              Object arrayValue = pop();
              if (!(arrayValue instanceof short[]) || !(indexValue instanceof Integer)) {
                return abort("unsupported");
              }
              push(((short[]) arrayValue)[(Integer) indexValue]);
            }
            break;
          case 182: // INVOKEVIRTUAL
          case 183: // INVOKESPECIAL
          case 184: // INVOKESTATIC
            {
              InvokeInstruction invokeInstruction = (InvokeInstruction) instruction;
              int constantIndex = invokeInstruction.getIndex();
              ConstantCP constantCP =
                  (ConstantCP) this.constantPool.getConstant(constantIndex);
              ConstantNameAndType constantNameAndType =
                  (ConstantNameAndType)
                      this.constantPool.getConstant(constantCP.getName_and_type_index());
              String methodName =
                  ((ConstantUtf8)
                          this.constantPool.getConstant(constantNameAndType.getName_index()))
                      .getBytes();
              String className = classConstant(constantCP.getClass_index());
              if (className.startsWith("[")) {
                className = "java/lang/Object";
              }
              if ("java/lang/String".equals(className)) {
                if ("length".equals(methodName) && "()I".equals(signatureOf(constantNameAndType))) {
                  Object value = pop();
                  if (!(value instanceof String)) {
                    return abort("unsupported");
                  }
                  push(((String) value).length());
                  break;
                }
                if ("charAt".equals(methodName)
                    && "(I)C".equals(signatureOf(constantNameAndType))) {
                  Object indexValue = pop();
                  Object value = pop();
                  if (!(value instanceof String) || !(indexValue instanceof Integer)) {
                    return abort("unsupported");
                  }
                  push(((String) value).charAt((Integer) indexValue));
                  break;
                }
                if ("<init>".equals(methodName) && instruction.opcode == 183) {
                  int argCount = argumentCount(signatureOf(constantNameAndType));
                  Object arg = argCount > 0 ? pop() : null;
                  Object receiver = pop();
                  if (arg instanceof char[] && receiver instanceof PendingNew) {
                    push(new String((char[]) arg));
                    break;
                  }
                  if (arg instanceof String && receiver instanceof PendingNew) {
                    push((String) arg);
                    break;
                  }
                  return abort("unsupported");
                }
                return abort("unsupported");
              }
              if ("java/lang/StringBuilder".equals(className)
                  || "java/lang/StringBuffer".equals(className)) {
                if ("<init>".equals(methodName)) {
                  // consume receiver (+ optional arg)
                  int argCount = argumentCount(signatureOf(constantNameAndType));
                  if (argCount > 0) {
                    pop();
                  }
                  Object receiver = pop();
                  if (receiver instanceof PendingNew) {
                    push(new StringBuilder());
                  } else if (receiver instanceof StringBuilder) {
                    push(receiver);
                  } else {
                    return abort("unsupported");
                  }
                  break;
                }
                if ("append".equals(methodName) || "toString".equals(methodName)) {
                  String signature = signatureOf(constantNameAndType);
                  Object arg = null;
                  int argCount = argumentCount(signature);
                  if (argCount > 0) {
                    arg = pop();
                  }
                  Object receiver = pop();
                  if (!(receiver instanceof StringBuilder)) {
                    return abort("unsupported");
                  }
                  if ("toString".equals(methodName)) {
                    push(((StringBuilder) receiver).toString());
                  } else {
                    if (arg instanceof Integer) {
                      ((StringBuilder) receiver).append((Integer) arg);
                    } else if (arg instanceof String) {
                      ((StringBuilder) receiver).append((String) arg);
                    } else if (arg instanceof Character) {
                      ((StringBuilder) receiver).append((Character) arg);
                    } else if (arg instanceof char[]) {
                      ((StringBuilder) receiver).append((char[]) arg);
                    } else {
                      return abort("unsupported");
                    }
                    push(receiver);
                  }
                  break;
                }
                return abort("unsupported");
              }
              return abort("unsupported"); // unsupported call (e.g. getStackTrace)
            }
          case 176: // ARETURN
            {
              Object value = pop();
              if (!(value instanceof String)) {
                return abort("unsupported");
              }
              return (String) value;
            }
          case 191: // ATHROW
            return abort("unsupported");
          case 193: // CHECKCAST
            break; // value stays as-is
          case 192: // INSTANCEOF
            {
              pop();
              push(1);
            }
            break;
          default:
            return abort("unknown opcode");
        }
        this.pc = next;
      }
      return abort("fell off end of code without areturn");
    }

    private static final class PendingNew {}

    /** Local slot of any load/store instruction, including the *_0..*_3 shortcuts. */
    private static int localIndex(Instruction instruction) {
      return ((LocalVariableInstruction) instruction).getIndex();
    }

    private InstructionHandle branch(InstructionHandle target, boolean condition, InstructionHandle next) {
      return condition ? target : next;
    }

    private InstructionHandle branch2(
        Instruction instruction, InstructionHandle next, int mode) {
      int value2 = popInt();
      int value1 = popInt();
      boolean condition;
      switch (mode) {
        case 0:
          condition = value1 == value2;
          break;
        case 1:
          condition = value1 != value2;
          break;
        case 2:
          condition = value1 < value2;
          break;
        case 3:
          condition = value1 >= value2;
          break;
        case 4:
          condition = value1 > value2;
          break;
        default:
          condition = value1 <= value2;
          break;
      }
      return condition ? ((BranchInstruction) instruction).getTarget() : next;
    }

    private String signatureOf(ConstantNameAndType constantNameAndType) {
      return ((ConstantUtf8)
              this.constantPool.getConstant(constantNameAndType.getSignature_index()))
          .getBytes();
    }

    private static int argumentCount(String descriptor) {
      int count = 0;
      int index = 1;
      while (descriptor.charAt(index) != ')') {
        char character = descriptor.charAt(index);
        if (character == 'L') {
          while (descriptor.charAt(index) != ';') {
            index++;
          }
        } else if (character == '[') {
          index++;
          continue;
        } else if (character == 'J' || character == 'D') {
          count++; // two slots
        }
        count++;
        index++;
      }
      return count;
    }

    private String classConstant(int constantIndex) {
      Constant constant = this.constantPool.getConstant(constantIndex);
      if (!(constant instanceof ConstantClass)) {
        return abort("unsupported");
      }
      int nameIndex = ((ConstantClass) constant).getNameIndex();
      return ((ConstantUtf8) this.constantPool.getConstant(nameIndex)).getBytes();
    }

    /** Logs the aborting instruction when debug is enabled; always yields "null". */
    private static String abort(String reason) {
      if (DEBUG && Machine.lastPc != null && Machine.lastPc.instruction != null) {
        StringBuilder dump = new StringBuilder();
        Machine machine = Machine.current;
        if (machine != null) {
          for (Object value : machine.stack) {
            dump.append(" ").append(name(value));
          }
        }
        System.out.println(
            "[DEBUG] Decryptor abort ("
                + reason
                + ") at "
                + Machine.lastPc.getPosition()
                + ": "
                + Machine.lastPc.instruction
                + " | stack [bottom..top]:"
                + dump);
      }
      return null;
    }

    private static String name(Object value) {
      if (value == null) {
        return "null";
      }
      if (value instanceof PendingNew) {
        return "PendingNew";
      }
      if (value instanceof String) {
        return "\"" + value + "\"";
      }
      if (value instanceof char[]) {
        return "char[" + ((char[]) value).length + "]";
      }
      if (value instanceof byte[]) {
        return "byte[" + ((byte[]) value).length + "]";
      }
      if (value instanceof int[]) {
        return "int[" + ((int[]) value).length + "]";
      }
      return value.getClass().getSimpleName() + "@" + Integer.toHexString(value.hashCode());
    }

    private String constantString(int constantIndex) {
      Constant constant = this.constantPool.getConstant(constantIndex);
      int stringIndex = ((ConstantString) constant).getStringIndex();
      return ((ConstantUtf8) this.constantPool.getConstant(stringIndex)).getBytes();
    }

    private void push(Object value) {
      if (value instanceof Character) {
        this.stack.push((int) (char) (Character) value);
      } else if (value instanceof Byte) {
        this.stack.push((int) (byte) (Byte) value);
      } else if (value instanceof Short) {
        this.stack.push((int) (short) (Short) value);
      } else if (value instanceof Boolean) {
        this.stack.push((Boolean) value ? 1 : 0);
      } else if (value instanceof Long) {
        // stored as two slots
        this.stack.push(0);
        this.stack.push(((Long) value).intValue());
      } else if (value instanceof Double) {
        this.stack.push(0);
        this.stack.push((int) (double) (Double) value);
      } else if (value instanceof Float) {
        this.stack.push((int) (float) (Float) value);
      } else {
        this.stack.push(value);
      }
    }

    private Object pop() {
      if (this.stack.isEmpty()) {
        throw new AbortException();
      }
      return this.stack.pop();
    }

    private int popInt() {
      Object value = pop();
      if (value instanceof Integer) {
        return (Integer) value;
      }
      if (DEBUG) {
        System.out.println(
            "[DEBUG] popInt expected Integer but found "
                + name(value)
                + " at pc "
                + (Machine.lastPc != null ? Machine.lastPc.getPosition() : -1));
      }
      throw new AbortException();
    }
  }
}
