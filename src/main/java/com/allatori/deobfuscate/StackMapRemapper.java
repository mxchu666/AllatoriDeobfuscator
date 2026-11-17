package com.allatori.deobfuscate;

import com.allatori.lib.bcel.classfile.Attribute;
import com.allatori.lib.bcel.classfile.Unknown;
import com.allatori.lib.bcel.generic.MethodGen;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rewrites StackMapTable frame offsets after a transformer changed the layout of a
 * method body.
 *
 * Allatori's bundled BCEL has no reader for the StackMapTable attribute, so it travels
 * through {@link MethodGen} as an opaque {@link Unknown} attribute and is re-emitted
 * verbatim when the method is re-encoded. When the code shrinks or grows, the frames
 * keep pointing into the old layout and the class is rejected by the JVM verifier
 * ("Bytecode offset out of range") and by javap.
 *
 * This class rewrites every frame's offset so it points to the same instruction in the
 * new layout, using an old-position to new-position map of instruction starts. Frame
 * contents (verification types) are preserved, which is valid for stack-shape
 * preserving edits such as the string decryption (an ldc + invokestatic pair becomes
 * an ldc + nop pair with the same stack effect). Verification entries of type
 * ITEM_Uninitialized also store an instruction offset, so those are remapped too.
 *
 * Frames whose offset cannot be mapped leave the attribute untouched and the caller is
 * told so it can warn; such a method may then fail verification, but the class is not
 * silently corrupted with half-updated offsets.
 */
public final class StackMapRemapper {

  private static final String ATTRIBUTE_NAME = "StackMapTable";

  private StackMapRemapper() {}

  /**
   * Remaps the StackMapTable of the method, if it has one.
   *
   * @param oldToNewPositions absolute instruction-start offsets of the old code mapped
   *     to their offsets in the new code
   * @return true when every frame was remapped or there was nothing to do
   */
  public static boolean remap(MethodGen methodGen, Map<Integer, Integer> oldToNewPositions) {
    for (Attribute attribute : methodGen.getCodeAttributes()) {
      if (attribute instanceof Unknown && ATTRIBUTE_NAME.equals(((Unknown) attribute).name)) {
        return remap((Unknown) attribute, oldToNewPositions);
      }
    }
    return true;
  }

  private static boolean remap(Unknown unknown, Map<Integer, Integer> oldToNewPositions) {
    byte[] payload = unknown.getBytes();
    if (payload == null || payload.length < 2) {
      return true;
    }
    List<Frame> frames = new ArrayList<>();
    try {
      int frameCount = shortAt(payload, 0);
      int p = 2;
      for (int i = 0; i < frameCount; i++) {
        p = parseFrame(payload, p, frames);
        if (p < 0) {
          return false;
        }
      }
      if (p != payload.length) {
        return false;
      }
    } catch (IndexOutOfBoundsException exception) {
      return false;
    }
    // map every frame to the new layout and rebuild the payload
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    try {
      out.writeShort(frames.size());
      int cumulativeOld = 0;
      int cumulativeNew = 0;
      for (Frame frame : frames) {
        cumulativeOld += frame.offset;
        Integer mapped = oldToNewPositions.get(cumulativeOld);
        if (mapped == null) {
          return false;
        }
        int delta = mapped - cumulativeNew;
        if (delta < 0) {
          return false;
        }
        cumulativeNew = mapped;
        if (!writeFrame(out, frame, delta, oldToNewPositions)) {
          return false;
        }
      }
    } catch (IOException exception) {
      return false;
    }
    byte[] rebuilt = buffer.toByteArray();
    unknown.setBytes(rebuilt);
    unknown.setLength(rebuilt.length);
    return true;
  }

  /** Parses one frame at p, appending it to frames; returns the next offset or -1. */
  private static int parseFrame(byte[] payload, int p, List<Frame> frames) {
    Frame frame = new Frame();
    frame.tag = payload[p++] & 0xFF;
    if (frame.tag <= 63) { // same_frame, offset encoded in the tag
      frame.offset = frame.tag;
    } else if (frame.tag <= 127) { // same_locals_1_stack_item, offset encoded in the tag
      frame.offset = frame.tag - 64;
      p = parseTypes(payload, p, 1, frame.stackTypes);
      if (p < 0) {
        return -1;
      }
    } else if (frame.tag == 247) { // same_locals_1_stack_item_frame_extended
      frame.offset = shortAt(payload, p);
      p += 2;
      p = parseTypes(payload, p, 1, frame.stackTypes);
      if (p < 0) {
        return -1;
      }
    } else if (frame.tag <= 250) { // same_frame_extended (248..250)
      frame.offset = shortAt(payload, p);
      p += 2;
    } else if (frame.tag == 251) { // chop_frame
      frame.offset = shortAt(payload, p);
      p += 2;
    } else if (frame.tag <= 254) { // append_frame (252..254)
      frame.offset = shortAt(payload, p);
      p += 2;
      p = parseTypes(payload, p, frame.tag - 251, frame.localTypes);
      if (p < 0) {
        return -1;
      }
    } else if (frame.tag == 255) { // full_frame
      frame.offset = shortAt(payload, p);
      p += 2;
      frame.declaredLocals = shortAt(payload, p);
      p += 2;
      p = parseTypes(payload, p, frame.declaredLocals, frame.localTypes);
      if (p < 0) {
        return -1;
      }
      frame.declaredStack = shortAt(payload, p);
      p += 2;
      p = parseTypes(payload, p, frame.declaredStack, frame.stackTypes);
      if (p < 0) {
        return -1;
      }
    } else {
      return -1;
    }
    frames.add(frame);
    return p;
  }

  /** Parses count verification type entries; returns the next offset or -1. */
  private static int parseTypes(
      byte[] payload, int p, int count, List<VerificationType> types) {
    for (int i = 0; i < count; i++) {
      if (p >= payload.length) {
        return -1;
      }
      VerificationType type = new VerificationType();
      type.tag = payload[p++] & 0xFF;
      if (type.tag <= 6) {
        type.extra = -1;
      } else if (type.tag == 7 || type.tag == 8) {
        if (p + 2 > payload.length) {
          return -1;
        }
        type.extra = shortAt(payload, p);
        p += 2;
      } else {
        return -1;
      }
      types.add(type);
    }
    return p;
  }

  /** Writes one frame with the given new offset delta; types of tag 8 are remapped. */
  private static boolean writeFrame(
      DataOutputStream out,
      Frame frame,
      int delta,
      Map<Integer, Integer> oldToNewPositions)
      throws IOException {
    if (frame.tag <= 63) {
      if (delta <= 63) {
        out.writeByte(delta); // same_frame, offset encoded in the tag
      } else {
        out.writeByte(251); // same_frame_extended
        out.writeShort(delta);
      }
    } else if (frame.tag <= 127) {
      if (delta <= 63) {
        out.writeByte(64 + delta); // same_locals_1_stack_item, offset in the tag
      } else {
        out.writeByte(247); // same_locals_1_stack_item_frame_extended
        out.writeShort(delta);
      }
      if (!writeTypes(out, frame.stackTypes, oldToNewPositions)) {
        return false;
      }
    } else if (frame.tag == 247) {
      out.writeByte(247);
      out.writeShort(delta);
      if (!writeTypes(out, frame.stackTypes, oldToNewPositions)) {
        return false;
      }
    } else if (frame.tag <= 250) { // 248..250 same_frame_extended
      out.writeByte(frame.tag);
      out.writeShort(delta);
    } else if (frame.tag == 251) { // chop_frame
      out.writeByte(frame.tag);
      out.writeShort(delta);
    } else if (frame.tag <= 254) { // append_frame (252..254)
      out.writeByte(frame.tag);
      out.writeShort(delta);
      if (!writeTypes(out, frame.localTypes, oldToNewPositions)) {
        return false;
      }
    } else { // full_frame
      out.writeByte(255);
      out.writeShort(delta);
      out.writeShort(frame.declaredLocals);
      if (!writeTypes(out, frame.localTypes, oldToNewPositions)) {
        return false;
      }
      out.writeShort(frame.declaredStack);
      if (!writeTypes(out, frame.stackTypes, oldToNewPositions)) {
        return false;
      }
    }
    return true;
  }

  private static boolean writeTypes(
      DataOutputStream out,
      List<VerificationType> types,
      Map<Integer, Integer> oldToNewPositions)
      throws IOException {
    for (VerificationType type : types) {
      out.writeByte(type.tag);
      if (type.tag == 7) { // ITEM_Object: constant pool index, unchanged
        out.writeShort(type.extra);
      } else if (type.tag == 8) { // ITEM_Uninitialized: instruction offset, remapped
        Integer mapped = oldToNewPositions.get(type.extra);
        if (mapped == null) {
          return false;
        }
        out.writeShort(mapped);
      }
    }
    return true;
  }

  private static int shortAt(byte[] payload, int p) {
    return ((payload[p] & 0xFF) << 8) | (payload[p + 1] & 0xFF);
  }

  private static final class Frame {
    int tag;
    int offset;
    int declaredLocals = -1;
    int declaredStack = -1;
    final List<VerificationType> localTypes = new ArrayList<>();
    final List<VerificationType> stackTypes = new ArrayList<>();
  }

  private static final class VerificationType {
    int tag;
    int extra;
  }
}
