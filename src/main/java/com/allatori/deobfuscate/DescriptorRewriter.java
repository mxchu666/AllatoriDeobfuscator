package com.allatori.deobfuscate;

import java.util.Map;

/**
 * Rewrites type descriptors and generic signatures, replacing the internal names of
 * mapped classes ("Lcom/example/h/K;") by their counterpart in the given table.
 *
 * The replacement always includes the leading 'L' and the trailing ';' so that
 * names that merely share a prefix are never mixed up.
 */
public class DescriptorRewriter {

  public static String rewrite(String text, Map<String, String> map) {
    if (text == null || text.indexOf('L') < 0 || map.isEmpty()) {
      return text;
    }
    StringBuilder stringBuilder = new StringBuilder(text.length() + 64);
    int index = 0;
    int length = text.length();
    while (index < length) {
      char character = text.charAt(index);
      if (character == 'L') {
        int end = findDescriptorEnd(text, index);
        if (end < 0) {
          stringBuilder.append(text, index, length);
          break;
        }
        int nameEnd = text.indexOf('<', index + 1);
        if (nameEnd < 0 || nameEnd > end) {
          nameEnd = end;
        }
        String internalName = text.substring(index + 1, nameEnd);
        String dotted = internalName.replace('/', '.');
        String replacement = map.get(dotted);
        if (replacement != null) {
          stringBuilder.append('L');
          stringBuilder.append(replacement.replace('.', '/'));
          stringBuilder.append(text, nameEnd, end + 1);
        } else {
          stringBuilder.append(text, index, end + 1);
        }
        index = end + 1;
      } else {
        int next = text.indexOf('L', index);
        if (next < 0) {
          next = length;
        }
        stringBuilder.append(text, index, next);
        index = next;
      }
    }
    return stringBuilder.toString();
  }

  /** Returns the index of the ';' that closes the 'L' descriptor starting at 'start'. */
  private static int findDescriptorEnd(String text, int start) {
    int depth = 0;
    for (int index = start + 1; index < text.length(); index++) {
      char character = text.charAt(index);
      if (character == '<') {
        depth++;
      } else if (character == '>') {
        if (depth > 0) {
          depth--;
        }
      } else if (character == ';' && depth == 0) {
        return index;
      }
    }
    return -1;
  }
}
