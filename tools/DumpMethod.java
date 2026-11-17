import com.allatori.lib.bcel.classfile.Attribute;
import com.allatori.lib.bcel.classfile.Code;
import com.allatori.lib.bcel.classfile.JavaClass;
import com.allatori.lib.bcel.classfile.LineNumberTable;
import com.allatori.lib.bcel.classfile.LocalVariable;
import com.allatori.lib.bcel.classfile.LocalVariableTable;
import com.allatori.lib.bcel.classfile.Method;
import java.io.FileInputStream;

/** Diagnostic: dump code length and table entries of one method. */
public class DumpMethod {
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
      System.out.println("code_length=" + code.code_length + " code array=" + code.code.length
          + " max_stack=" + code.max_stack + " max_locals=" + code.max_locals);
      for (Attribute attribute : code.getAttributes()) {
        if (attribute instanceof LineNumberTable) {
          LineNumberTable lnt = (LineNumberTable) attribute;
          com.allatori.lib.bcel.classfile.LineNumber[] lns = lnt.getLineNumberTable();
          System.out.println("LineNumberTable entries=" + (lns == null ? -1 : lns.length));
          if (lns != null) {
            for (com.allatori.lib.bcel.classfile.LineNumber ln : lns) {
              System.out.println("  line start_pc=" + ln.start_pc);
            }
          }
        } else if (attribute instanceof LocalVariableTable) {
          LocalVariableTable lvt = (LocalVariableTable) attribute;
          LocalVariable[] lvs = lvt.local_variable_table;
          System.out.println("LocalVariableTable entries=" + (lvs == null ? -1 : lvs.length));
          if (lvs != null) {
            for (LocalVariable lv : lvs) {
              System.out.println("  lv start_pc=" + lv.start_pc + " length=" + lv.length
                  + " index=" + lv.index);
            }
          }
        }
      }
      // print instruction offsets from raw bytes
      System.out.print("last code bytes: ");
      for (int i = Math.max(0, code.code.length - 10); i < code.code.length; i++) {
        System.out.print(Integer.toHexString(code.code[i] & 0xFF) + " ");
      }
      System.out.println();
    }
  }
}
