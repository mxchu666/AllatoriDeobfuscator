package com.allatori.deobfuscate;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Parses the XML mapping file (the "log file") written by OpenAllatori/Allatori
 * during obfuscation and builds the inverted tables used to undo the renaming.
 *
 * Supported entries (same format as the official Allatori log):
 *
 *   <class old="a.b.C" new="a.b.X">
 *     <method old="run(I)I" new="a"/>
 *     <field  old="value I" new="b"/>
 *   </class>
 *   <annotations>
 *     <annotation oldClassName="a.b.A" oldMethodName="value" newMethodName="a"/>
 *   </annotations>
 *   <sources>
 *     <source old="C.java" new="X.java"/>
 *   </sources>
 */
public class MappingData {

  /** new dotted class name -> old dotted class name */
  public final Map<String, String> classNewToOld = new HashMap<>();

  /** old dotted class name -> new dotted class name */
  public final Map<String, String> classOldToNew = new HashMap<>();

  /** "newClass#newName#newDescriptor" -> old method name */
  public final Map<String, String> methodNewToOld = new HashMap<>();

  /** "newClass#newName#newFieldDescriptor" -> old field name */
  public final Map<String, String> fieldNewToOld = new HashMap<>();

  /** new source file name -> old source file name */
  public final Map<String, String> sourceNewToOld = new HashMap<>();

  /** "newClassName#newAnnotationMethodName" -> old method name (annotation members) */
  public final Map<String, String> annotationMethodNewToOld = new HashMap<>();

  public static MappingData parse(String mappingFile)
      throws ParserConfigurationException, SAXException, IOException {
    MappingData mappingData = new MappingData();
    InputStream inputStream = new FileInputStream(mappingFile);
    try {
      SAXParser sAXParser = SAXParserFactory.newInstance().newSAXParser();
      sAXParser.parse(
          new InputSource(new InputStreamReader(inputStream, "UTF-8")),
          mappingData.new Handler());
    } finally {
      inputStream.close();
    }
    return mappingData;
  }

  private class Handler extends DefaultHandler {

    public String currentNewClass;
    public String currentOldClass;

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) {
      if ("class".equals(qName)) {
        String old = attributes.getValue("old");
        String nnew = attributes.getValue("new");
        this.currentOldClass = old;
        this.currentNewClass = nnew;
        if (old != null && nnew != null && !nnew.equals(old)) {
          MappingData.this.classNewToOld.put(nnew, old);
          MappingData.this.classOldToNew.put(old, nnew);
        }
      } else if ("method".equals(qName) && this.currentNewClass != null) {
        String old = attributes.getValue("old");
        String nnew = attributes.getValue("new");
        if (old != null && nnew != null && !nnew.equals(old)) {
          String oldName = old.substring(0, old.indexOf('('));
          String oldDesc = old.substring(old.indexOf('('));
          String newDesc = MappingData.this.rewriteDescriptorForward(oldDesc);
          MappingData.this.methodNewToOld.put(
              this.currentNewClass + "#" + nnew + "#" + newDesc, oldName);
        }
      } else if ("field".equals(qName) && this.currentNewClass != null) {
        String old = attributes.getValue("old");
        String nnew = attributes.getValue("new");
        if (old != null && nnew != null && !nnew.equals(old)) {
          int split = old.indexOf(' ');
          if (split > 0) {
            String oldName = old.substring(0, split);
            String oldDesc = old.substring(split + 1);
            String newDesc = MappingData.this.rewriteDescriptorForward(oldDesc);
            MappingData.this.fieldNewToOld.put(
                this.currentNewClass + "#" + nnew + "#" + newDesc, oldName);
          }
        }
      } else if ("annotation".equals(qName)) {
        String oldClassName = attributes.getValue("oldClassName");
        String oldMethodName = attributes.getValue("oldMethodName");
        String newMethodName = attributes.getValue("newMethodName");
        if (oldClassName != null
            && oldMethodName != null
            && newMethodName != null
            && !newMethodName.equals(oldMethodName)) {
          MappingData.this.annotationMethodNewToOld.put(
              MappingData.this.classOldToNew.getOrDefault(oldClassName, oldClassName)
                  + "#"
                  + newMethodName,
              oldMethodName);
        }
      } else if ("source".equals(qName)) {
        String old = attributes.getValue("old");
        String nnew = attributes.getValue("new");
        if (old != null && nnew != null && !nnew.equals(old)) {
          MappingData.this.sourceNewToOld.put(nnew, old);
        }
      }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
      if ("class".equals(qName)) {
        this.currentNewClass = null;
        this.currentOldClass = null;
      }
    }
  }

  /** Rewrites an old descriptor so class references point to the new (obfuscated) names. */
  public String rewriteDescriptorForward(String descriptor) {
    return DescriptorRewriter.rewrite(descriptor, MappingData.this.classOldToNew);
  }

  /** Rewrites a descriptor from the obfuscated jar back to original class names. */
  public String rewriteDescriptorBackward(String descriptor) {
    return DescriptorRewriter.rewrite(descriptor, MappingData.this.classNewToOld);
  }

  /** Rewrites a generic signature from the obfuscated jar back to original class names. */
  public String rewriteSignatureBackward(String signature) {
    return DescriptorRewriter.rewrite(signature, MappingData.this.classNewToOld);
  }
}
