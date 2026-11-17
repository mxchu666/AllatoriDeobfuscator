import com.allatori.lib.bcel.classfile.Attribute;
import com.allatori.lib.bcel.classfile.Code;
import com.allatori.lib.bcel.classfile.CodeException;
import com.allatori.lib.bcel.classfile.JavaClass;
import com.allatori.lib.bcel.classfile.LineNumber;
import com.allatori.lib.bcel.classfile.LineNumberTable;
import com.allatori.lib.bcel.classfile.LocalVariable;
import com.allatori.lib.bcel.classfile.LocalVariableTable;
import com.allatori.lib.bcel.classfile.Method;
import com.allatori.lib.bcel.classfile.StackMapTable;
import com.allatori.lib.bcel.classfile.StackMapTableEntry;
import com.allatori.lib.bcel.generic.BranchInstruction;
import com.allatori.lib.bcel.generic.Instruction;
import com.allatori.lib.bcel.util.ByteSequence;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;

/** Diagnostic: full dump of one method's Code attribute. */
public class DumpMain {
  public static void main(String[] args) throws Exception {
    JavaClass javaClass =
        new com.allatori.lib.bcel.classfile.ClassParser(
                new FileInputStream(args[0]), args[0])
            .parse();
    for (Method method : javaClass.getMethods()) {
      if (!method.getText().equals(args[1])) {
        continue;
      }
      System.out.println("method: " + method.getText() + method.getText2());
      Code code = (Code) method.getCode();
      System.out.println(
          "code_length=" + code.code_length
              + " code array=" + code.code.length
              + " max_stack=" + code.max_stack
              + " max_locals=" + code.max_locals);
      // walk instructions
      try {
        ByteSequence bytes = new ByteSequence(code.code);
        int pos = 0;
        while (bytes.available() > 0) {
          pos = bytes.getIndex();
          Instruction instruction = Instruction.readInstruction(bytes);
          String extra = "";
          if (instruction instanceof BranchInstruction) {
            BranchInstruction branchInstruction = (BranchInstruction) instruction;
            int raw = branchInstruction.getIndex();
            extra =
                " raw_offset=" + raw
                    + " abs_target=" + (pos + raw)
                    + " count_field=" + branchInstruction.count;
          }
          System.out.println(
              "  bci " + pos + ": " + instruction.getClass().getSimpleName()
                  + " opcode=" + instruction.opcode + extra);
          if (bytes.getIndex() <= pos) {
            System.out.println("  (infinite loop guard: no progress)");
            break;
          }
        }
        System.out.println("  end of decode at bci " + bytes.getIndex());
      } catch (Exception e) {
        System.out.println("  DECODE FAILED: " + e);
      }
      for (Attribute attribute : code.getAttributes()) {
        if (attribute instanceof LineNumberTable) {
          LineNumberTable lnt = (LineNumberTable) attribute;
          LineNumber[] lns = lnt.getLineNumberTable();
          System.out.println("LineNumberTable entries=" + (lns == null ? -1 : lns.length));
          if (lns != null) {
            for (LineNumber ln : lns) {
              System.out.println("  line " + ln.line_number + " start_pc=" + ln.start_pc);
            }
          }
        } else if (attribute instanceof LocalVariableTable) {
          LocalVariableTable lvt = (LocalVariableTable) attribute;
          LocalVariable[] lvs = lvt.local_variable_table;
          System.out.println("LocalVariableTable entries=" + (lvs == null ? -1 : lvs.length));
          if (lvs != null) {
            for (LocalVariable lv : lvs) {
              System.out.println(
                  "  lv start_pc=" + lv.start_pc + " length=" + lv.length
                      + " index=" + lv.index + " name=" + lv.getText());
            }
          }
        } else if (attribute instanceof StackMapTable) {
          StackMapTable smt = (StackMapTable) attribute;
          StackMapTableEntry[] entries = smt.getStackMap();
          System.out.println("StackMapTable entries=" + (entries == null ? -1 : entries.length));
          if (entries != null) {
            int cumulative = 0;
            for (StackMapTableEntry entry : entries) {
              cumulative += entry.byteCodeOffset;
              System.out.println(
                  "  frame type=" + entry.frameType + " delta=" + entry.byteCodeOffset
                      + " cumulative_offset=" + cumulative
                      + " locals=" + entry.numberOfLocals
                      + " stack=" + entry.numberOfStackItems);
            }
          }
        } else {
          System.out.println(
              "other attribute tag=" + attribute.tag
                  + " length=" + attribute.length
                  + " name_index=" + attribute.name_index);
        }
      }
      CodeException[] exceptions = code.getExceptionTable();
      System.out.println("exception table entries=" + (exceptions == null ? 0 : exceptions.length));
      if (exceptions != null) {
        for (CodeException codeException : exceptions) {
          System.out.println(
              "  start_pc=" + codeException.start_pc + " end_pc=" + codeException.end_pc
                  + " handler_pc=" + codeException.handler_pc
                  + " catch_type=" + codeException.catch_type);
        }
      }
    }
  }
}
