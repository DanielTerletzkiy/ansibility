import java.nio.file.*; import java.util.*; import java.util.regex.*;
public class JRegex {
  // minimal JSON array-of-arrays-of-strings reader (cases.json only)
  static List<List<String>> parse(String s) { List<List<String>> out = new ArrayList<>(); List<String> cur = null; int i = 0; int depth = 0;
    while (i < s.length()) { char c = s.charAt(i);
      if (c == '[') { depth++; if (depth == 2) cur = new ArrayList<>(); i++; }
      else if (c == ']') { if (depth == 2) out.add(cur); depth--; i++; }
      else if (c == '"') { StringBuilder b = new StringBuilder(); i++;
        while (s.charAt(i) != '"') { char d = s.charAt(i);
          if (d == '\\') { char e = s.charAt(++i);
            switch (e) { case 'n': b.append('\n'); break; case 'r': b.append('\r'); break; case 't': b.append('\t'); break;
              case 'u': b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; break; default: b.append(e); }
            i++; } else { b.append(d); i++; } }
        i++; cur.add(b.toString()); }
      else i++; }
    return out; }
  static String esc(String s) { StringBuilder b = new StringBuilder("\"");
    for (char c : s.toCharArray()) { if (c == '"' || c == '\\') b.append('\\').append(c); else if (c == '\n') b.append("\\n"); else if (c == '\r') b.append("\\r");
      else if (c < 0x20 || c > 0x7e) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
    return b.append('"').toString(); }
  // naive replacement translation: \N -> $N, \g<name> -> ${name}, \g<0> -> $0, literal $ -> \$
  static String translateRepl(String r) { StringBuilder b = new StringBuilder();
    for (int i = 0; i < r.length(); i++) { char c = r.charAt(i);
      if (c == '$') b.append("\\$");
      else if (c == '\\' && i + 1 < r.length()) { char n = r.charAt(i + 1);
        if (Character.isDigit(n)) { b.append('$').append(n); i++; }
        else if (n == 'g' && i + 2 < r.length() && r.charAt(i + 2) == '<') { int end = r.indexOf('>', i); String name = r.substring(i + 3, end);
          b.append(name.chars().allMatch(Character::isDigit) ? "$" + name : "${" + name + "}"); i = end; }
        else if (n == 'n') { b.append('\n'); i++; } else if (n == '\\') { b.append("\\\\"); i++; } else { b.append("\\\\"); } }
      else b.append(c); }
    return b.toString(); }
  public static void main(String[] a) throws Exception {
    List<List<String>> cases = parse(Files.readString(Path.of(a[0])));
    StringBuilder out = new StringBuilder("{\"java\":" + esc(System.getProperty("java.version")));
    for (List<String> c : cases) {
      String id = c.get(0), pat = c.get(1), fl = c.get(2), inp = c.get(3), rep = c.get(4);
      for (int mode = 0; mode < 2; mode++) {
        int flags = (fl.contains("I") ? Pattern.CASE_INSENSITIVE : 0) | (fl.contains("M") ? Pattern.MULTILINE : 0);
        if (mode == 1) flags |= Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNICODE_CASE | Pattern.UNIX_LINES;
        String res;
        try { Matcher m = Pattern.compile(pat, flags).matcher(inp); String jr = translateRepl(rep);
          if (id.equals("count_2")) { StringBuilder sb = new StringBuilder(); int n = 0; while (n < 2 && m.find()) { m.appendReplacement(sb, jr); n++; } m.appendTail(sb); res = "{\"ok\":" + esc(sb.toString()) + "}"; }
          else res = "{\"ok\":" + esc(m.replaceAll(jr)) + "}"; }
        catch (Exception e) { res = "{\"err\":" + esc(e.getClass().getSimpleName() + ": " + e.getMessage().split("\n")[0]) + "}"; }
        out.append(",").append(esc(id + (mode == 0 ? ".naive" : ".compat"))).append(":").append(res);
      }
    }
    System.out.println(out.append("}"));
  }
}
