// Helpers prepended to every script (see shoot.py).
importPackage(java.awt); importPackage(java.awt.image);
var AM = com.intellij.openapi.actionSystem.ActionManager.getInstance();
var PM = com.intellij.openapi.project.ProjectManager.getInstance();
function project() { var ps = PM.getOpenProjects(); for (var i = 0; i < ps.length; i++) if (ps[i].getName() == "acme-infra") return ps[i]; return ps[0]; }
function frame() { return com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project()); }
function vfile(rel) { return com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(project().getBasePath() + "/" + rel); }
function editor() { return com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project()).getSelectedTextEditor(); }
function open(rel, line, col) {   // opens editor-only (split previews are switched on per scenario)
  var d = new com.intellij.openapi.fileEditor.OpenFileDescriptor(project(), vfile(rel), line || 0, col || 0);
  var e = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project()).openTextEditor(d, true);
  try { action("TextEditorWithPreview.Layout.EditorOnly", e.getContentComponent()); } catch (x) {}
  return e;
}
function caretAt(text, occurrence) {           // put the caret on the n-th occurrence of text in the current editor
  var e = editor(); var doc = e.getDocument().getText(); var i = -1;
  for (var k = 0; k <= (occurrence || 0); k++) i = doc.indexOf(text, i + 1);
  if (i < 0) throw "not found: " + text;
  e.getCaretModel().moveToOffset(i); e.getScrollingModel().scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER);
  return i;
}
function action(id, component) {
  var a = AM.getAction(id); if (a == null) throw "no action " + id;
  var ctx = com.intellij.ide.DataManager.getInstance().getDataContext(component || editor().getContentComponent());
  var ev = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(a, ctx, null, "Screenshot", com.intellij.openapi.actionSystem.ActionUiKind.NONE, null);
  com.intellij.openapi.actionSystem.ex.ActionUtil.performAction(a, ev);
}
function shot(path, scale) {
  scale = scale || 2; var f = frame(); var fb = f.getBounds(); var root = f.getRootPane();
  var rp = root.getLocationOnScreen(); var fl = f.getLocationOnScreen();
  var img = new BufferedImage(root.getWidth() * scale, root.getHeight() * scale, BufferedImage.TYPE_INT_RGB);
  var g = img.createGraphics(); g.scale(scale, scale);
  g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
  root.printAll(g);
  var ws = Window.getWindows();
  for (var i = 0; i < ws.length; i++) {
    var w = ws[i]; if (w === f || !w.isShowing() || w.getWidth() == 0) continue;
    var p = w.getLocationOnScreen(); var c = w.getRootPane ? w.getRootPane() : w;
    if (c == null) c = w;
    var g2 = g.create(); g2.translate(p.x - rp.x, p.y - rp.y);
    if (w instanceof java.awt.Dialog) { dialogChrome(g, w, p.x - rp.x, p.y - rp.y); g2.translate(0, 0); }
    var bg = (c.getContentPane ? c.getContentPane().getBackground() : c.getBackground()) || Color.DARK_GRAY;
    g2.setColor(bg); g2.fillRect(0, 0, w.getWidth(), w.getHeight()); c.printAll(g2); g2.dispose();
  }
  g.dispose(); javax.imageio.ImageIO.write(img, "png", new java.io.File(path));
}
function resize(w, h) { var f = frame(); f.setExtendedState(0); f.setBounds(100, 60, w, h); f.validate(); }
var APP = com.intellij.openapi.application.ApplicationManager.getApplication();
var ERR = null;
function edt(fn) {   // run on the EDT with the write-intent lock, rethrow errors here
  var res = null; ERR = null;
  APP.invokeAndWait(new java.lang.Runnable({ run: function () { try { res = fn(); } catch (e) { ERR = e; } } }), com.intellij.openapi.application.ModalityState.any());
  if (ERR != null) throw ERR; return res;
}
function sleep(ms) { java.lang.Thread.sleep(ms); }
function clean() {     // drop balloons and chrome that does not belong in a product screenshot
  var ns = com.intellij.notification.NotificationsManager.getNotificationsManager().getNotificationsOfType(com.intellij.notification.Notification, project());
  for (var i = 0; i < ns.length; i++) ns[i].expire();
  hideMatching(frame().getRootPane(), function (c) {
    return ("" + c.getClass().getName()).indexOf("TrialState") >= 0 ||
           (("" + c.getClass().getName()).indexOf("NavBar") >= 0 && javax.swing.SwingUtilities.getAncestorOfClass(com.intellij.openapi.wm.impl.status.IdeStatusBarImpl, c) != null) ||
           ("" + c.getClass().getName()).indexOf("PyInterpreter") >= 0 ||
           (c.getText && /No interpreter|No JSON schema/.test("" + c.getText()));
  });
}
function hideMatching(c, pred) {
  if (pred(c)) { c.setVisible(false); return; }
  if (c instanceof java.awt.Container) { var cs = c.getComponents(); for (var i = 0; i < cs.length; i++) hideMatching(cs[i], pred); }
}
function dump(c, depth, out) {   // component tree for exploration
  out = out || new java.lang.StringBuilder(); depth = depth || 0;
  if (!c.isShowing()) return out;
  var t = ""; try { if (c.getText) t = " '" + c.getText() + "'"; } catch (e) {}
  out.append(new java.lang.String(" ").repeat(depth) + c.getClass().getName() + t + " " + c.getBounds() + "\n");
  if (c instanceof java.awt.Container) { var cs = c.getComponents(); for (var i = 0; i < cs.length; i++) dump(cs[i], depth + 1, out); }
  return out;
}
function write(path, text) { java.nio.file.Files.writeString(java.nio.file.Path.of(path), "" + text); }
var PLUGIN_CL = com.intellij.ide.plugins.PluginManagerCore.getPlugin(com.intellij.openapi.extensions.PluginId.getId("de.terletzkiy.ansibility")).getPluginClassLoader();
function pcls(name) { return java.lang.Class.forName(name, true, PLUGIN_CL); }
function kobject(name) { return pcls(name).getField("INSTANCE").get(null); }
function service(name) { return project().getService(pcls(name)); }
function root() { return service("de.terletzkiy.ansibility.api.AnsibleWorkspace").roots().get(0); }
function useHost(env, host) { kobject("de.terletzkiy.ansibility.context.switching.ContextSwitcher").selectHost(project(), root(), env, host); }
function useEnv(env) { kobject("de.terletzkiy.ansibility.context.switching.ContextSwitcher").selectEnvironment(project(), root(), env); }
function refresh() { var d = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(project().getBasePath()); com.intellij.openapi.vfs.VfsUtil.markDirty(true, true, d); d.refresh(false, true); com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().reloadFromDisk; }
function closeAll() { var m = com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project()); m.closeAllFiles(); }
function selectInProject(rel) {
  var v = com.intellij.ide.projectView.ProjectView.getInstance(project());
  v.select(null, vfile(rel), false);
}
function find(c, pred, acc) {   // all showing components (in every window) matching pred
  acc = acc || [];
  if (c == null) { var ws = Window.getWindows(); for (var i = 0; i < ws.length; i++) if (ws[i].isShowing()) find(ws[i], pred, acc); return acc; }
  if (!c.isShowing()) return acc;
  if (pred(c)) acc.push(c);
  if (c instanceof java.awt.Container) { var cs = c.getComponents(); for (var i = 0; i < cs.length; i++) find(cs[i], pred, acc); }
  return acc;
}
function editorPanes() { return find(null, function (c) { return c instanceof javax.swing.JEditorPane; }); }
function clickLink(text) {     // fire the hyperlink whose anchor text contains `text` in any visible JEditorPane
  var panes = editorPanes();
  for (var i = 0; i < panes.length; i++) {
    var p = panes[i]; var doc = p.getDocument(); if (!(doc instanceof javax.swing.text.html.HTMLDocument)) continue;
    var it = doc.getIterator(javax.swing.text.html.HTML.Tag.A);
    while (it.isValid()) {
      var s = doc.getText(it.getStartOffset(), it.getEndOffset() - it.getStartOffset());
      if (s.indexOf(text) >= 0) {
        var href = it.getAttributes().getAttribute(javax.swing.text.html.HTML.Attribute.HREF);
        var ev = new javax.swing.event.HyperlinkEvent(p, javax.swing.event.HyperlinkEvent.EventType.ACTIVATED, null, href, doc.getCharacterElement(it.getStartOffset()));
        p.fireHyperlinkUpdate(ev); return href;
      }
      it.next();
    }
  }
  throw "no link " + text;
}
function closePopups() {
  var ws = Window.getWindows();
  for (var i = 0; i < ws.length; i++) { var w = ws[i]; if (w !== frame() && w.isShowing() && !(w instanceof java.awt.Dialog)) w.dispose(); }
}
function toolWindow(id) { return com.intellij.openapi.wm.ToolWindowManager.getInstance(project()).getToolWindow(id); }
function hideTool(id) { toolWindow(id).hide(); }
function showTool(id) { toolWindow(id).show(); toolWindow(id).activate(null); }
function chooseInPopupList(text) {
  var lists = find(null, function (c) { return c instanceof javax.swing.JList; });
  for (var i = 0; i < lists.length; i++) {
    var l = lists[i]; var m = l.getModel();
    for (var k = 0; k < m.getSize(); k++) {
      var e = m.getElementAt(k); var s = "" + e;
      try { if (e.getText) s = "" + e.getText(); } catch (x) {}
      try { if (e.getAction) s = "" + e.getAction().getText(); } catch (x) {}
      if (s.indexOf(text) >= 0) {
        l.setSelectedIndex(k);
        var ke = new java.awt.event.KeyEvent(l, java.awt.event.KeyEvent.KEY_PRESSED, java.lang.System.currentTimeMillis(), 0, java.awt.event.KeyEvent.VK_ENTER, '\n');
        l.dispatchEvent(ke); return s;
      }
    }
  }
  throw "no list item " + text;
}
function listItems() { var o = ""; var lists = find(null, function (c) { return c instanceof javax.swing.JList; });
  for (var i = 0; i < lists.length; i++) { var m = lists[i].getModel(); for (var k = 0; k < m.getSize(); k++) o += i + ": " + m.getElementAt(k) + "\n"; } return o; }
function clickButton(text) {
  var bs = find(null, function (c) { return c instanceof javax.swing.AbstractButton && ("" + c.getText()).indexOf(text) >= 0; });
  if (bs.length == 0) throw "no button " + text; bs[0].doClick(); return bs.length;
}
function treeIn(toolId) { var c = toolWindow(toolId).getComponent(); var ts = find(c, function (x) { return x instanceof javax.swing.JTree; }); return ts[0]; }
function rowText(t, r) { var p = t.getPathForRow(r); var n = p.getLastPathComponent(); var s = "" + n;
  try { var u = n.getUserObject(); if (u != null) { s = "" + u; try { s = "" + u.getPresentation().getPresentableText() + " " + s; } catch (e) {} } } catch (e) {}
  return s; }
function treeRows(t) { var o = ""; for (var r = 0; r < t.getRowCount(); r++) o += r + ": " + rowText(t, r) + "\n"; return o; }
function expandLabel(t, label, select) {   // expand (and optionally select) the first visible row whose text contains label
  for (var r = 0; r < t.getRowCount(); r++) if (rowText(t, r).indexOf(label) >= 0) {
    if (select) { t.setSelectionRow(r); t.scrollRowToVisible(r); } else t.expandRow(r); return r; }
  throw "no row " + label;
}
function waitSmart() {
  for (var i = 0; i < 300; i++) {
    var ok = false; try { ok = PM.getOpenProjects().length > 0 && !com.intellij.openapi.project.DumbService.isDumb(project()) && frame() != null && frame().isShowing(); } catch (e) {}
    if (ok) return; sleep(1000);
  }
}
function reset() {     // common starting state for every scenario
  refresh(); resize(1440, 900); closePopups(); closeAll();
  var tm = com.intellij.openapi.wm.ex.ToolWindowManagerEx.getInstanceEx(project());
  var ids = ["Ansibility", "Problems View", "Run", "Find"];
  for (var i = 0; i < ids.length; i++) { var tw = toolWindow(ids[i]); if (tw != null) { if (tm.isMaximized(tw)) tm.setMaximized(tw, false); tw.hide(); } }
  showTool("Project"); setWidth("Project", 440); var pt = treeIn("Project"); if (pt != null) com.intellij.util.ui.tree.TreeUtil.collapseAll(pt, 2); clean();
}
function setWidth(id, w) { var tw = toolWindow(id); var cur = tw.getComponent().getWidth(); if (cur > 0) tw.stretchWidth(w - cur); }
function later(fn) { APP.invokeLater(new java.lang.Runnable({ run: function () { try { fn(); } catch (e) { java.lang.System.err.println("screenshot later: " + e); } } }), com.intellij.openapi.application.ModalityState.any()); }
function intentionAt(simpleName, anchor, after) {   // invoke a vault intention on the YAML scalar at `after` text following `anchor`
  var e = editor(); var t = e.getDocument().getText(); var at = t.indexOf(anchor); if (after) at = t.indexOf(after, at);
  if (at < 0) throw "anchor not found: " + anchor;
  e.getCaretModel().moveToOffset(at);
  var psi = com.intellij.psi.PsiDocumentManager.getInstance(project()).getPsiFile(e.getDocument());
  var it = pcls("de.terletzkiy.ansibility.vault.actions." + simpleName).getDeclaredConstructor().newInstance();
  it.invoke(project(), e, psi.findElementAt(at));
}
function dialogShowing() { var ws = Window.getWindows(); for (var i = 0; i < ws.length; i++) if (ws[i] instanceof java.awt.Dialog && ws[i].isShowing()) return true; return false; }
function waitDialog(ms) { for (var i = 0; i < (ms || 10000) / 250; i++) { if (edt(dialogShowing)) return true; sleep(250); } return false; }
function closeDialogs() { var ws = Window.getWindows(); for (var i = 0; i < ws.length; i++) if (ws[i] instanceof java.awt.Dialog && ws[i].isShowing()) { var d = com.intellij.openapi.ui.DialogWrapper.findInstance(ws[i].getRootPane()); if (d != null) d.close(1); else ws[i].dispose(); } }

var TITLE_H = 30;
function dialogChrome(g, w, x, y) {   // painted stand-in for the native dialog frame: shadow, border, title bar
  var g3 = g.create(); g3.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
  for (var i = 18; i > 0; i--) { g3.setColor(rgba(0, 0, 0, Math.round(5 + (18 - i) * 0.9))); g3.fillRoundRect(x - i, y - TITLE_H - i + 6, w.getWidth() + 2 * i, w.getHeight() + TITLE_H + 2 * i, 18 + i, 18 + i); }
  var bg = w.getRootPane().getContentPane().getBackground();
  g3.setColor(bg.brighter() == bg ? bg : rgba(Math.min(255, bg.getRed() + 10), Math.min(255, bg.getGreen() + 10), Math.min(255, bg.getBlue() + 12), 255));
  g3.fillRoundRect(x, y - TITLE_H, w.getWidth(), w.getHeight() + TITLE_H, 12, 12);
  g3.setColor(rgba(255, 255, 255, 28)); g3.drawRoundRect(x, y - TITLE_H, w.getWidth() - 1, w.getHeight() + TITLE_H - 1, 12, 12);
  var title = "" + (w.getTitle() || ""); g3.setFont(com.intellij.util.ui.JBFont.label().asBold()); g3.setColor(rgba(0xDF, 0xE1, 0xE5, 255));
  var fm = g3.getFontMetrics(); g3.drawString(title, x + (w.getWidth() - fm.stringWidth(title)) / 2, y - TITLE_H + (TITLE_H + fm.getAscent() - fm.getDescent()) / 2);
  var dots = [rgba(0xFF, 0x5F, 0x57, 255), rgba(0xFE, 0xBC, 0x2E, 255), rgba(0x28, 0xC8, 0x40, 255)];
  for (var k = 0; k < 3; k++) { g3.setColor(dots[k]); g3.fillOval(x + 12 + k * 20, y - TITLE_H + 9, 12, 12); }
  g3.dispose();
}
function openCombo() { var cs = find(null, function (c) { return c instanceof javax.swing.JComboBox && javax.swing.SwingUtilities.getWindowAncestor(c) instanceof java.awt.Dialog; }); if (cs.length && cs[0].getItemCount() > 1) cs[0].setPopupVisible(true); }
function rgba(r, g, b, a) { return new Color(((a & 255) << 24) | ((r & 255) << 16) | ((g & 255) << 8) | (b & 255), true); }
function callPrefix(obj, prefix, args) {   // Kotlin `internal` members are name-mangled (foo$module): call by prefix via reflection
  var ms = obj.getClass().getDeclaredMethods();
  for (var i = 0; i < ms.length; i++) { var n = "" + ms[i].getName(); if ((n == prefix || n.indexOf(prefix + "$") == 0) && ms[i].getParameterCount() == args.length) { ms[i].setAccessible(true); return ms[i].invoke(obj, args); } }
  throw "no method " + prefix;
}
function vaultScalar(anchor) {
  var e = editor(); var t = e.getDocument().getText(); var at = t.indexOf("$ANSIBLE_VAULT", t.indexOf(anchor));
  e.getCaretModel().moveToOffset(t.indexOf(anchor));
  var psi = com.intellij.psi.PsiDocumentManager.getInstance(project()).getPsiFile(e.getDocument());
  return callPrefix(kobject("de.terletzkiy.ansibility.vault.actions.VaultValuePsi"), "vaultScalarAt", [psi.findElementAt(at)]);
}
function vaultValueAction(method, anchor) {   // VaultValueActions.<method>(VaultValueRef.of(scalar), editor)
  var refCls = pcls("de.terletzkiy.ansibility.vault.actions.VaultValueRef");
  var comp = refCls.getField("Companion").get(null);
  var ref = callPrefix(comp, "of", [vaultScalar(anchor)]);
  var svc = service("de.terletzkiy.ansibility.vault.actions.VaultValueActions");
  callPrefix(svc, method, [ref, editor()]);
}
function clickLinkLabel(text) {   // banner links (HyperlinkLabel / ActionLink)
  var ls = find(null, function (c) { try { return c.getText && ("" + c.getText()).replace(/<[^>]+>/g, "").indexOf(text) >= 0 && (c.doClick || c.fireHyperlinkEvent); } catch (e) { return false; } });
  if (!ls.length) throw "no link " + text;
  var l = ls[ls.length - 1]; if (l.doClick) l.doClick(); else l.doClick(); return ls.length;
}
function errorAt(rel, anchor, outName, env, host) {   // open rel, caret on anchor, show the inspection tooltip, shoot
  edt(function () { reset(); hideTool("Project"); if (host) useHost(env, host); else if (env) useEnv(env); open(rel); });
  sleep(4500);
  edt(function () { caretAt(anchor); editor().getContentComponent().requestFocus(); action("ShowErrorDescription"); });
  sleep(2500);
  edt(function () { clean(); shot(OUT + "/" + outName + ".png"); closePopups(); });
}
function problems(rel) {
  var e = open(rel); var infos = com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl.getHighlights(e.getDocument(), com.intellij.lang.annotation.HighlightSeverity.WEAK_WARNING, project());
  var o = ""; for (var j = 0; j < infos.size(); j++) o += infos.get(j).getSeverity() + " " + e.getDocument().getText().substring(infos.get(j).getStartOffset(), infos.get(j).getEndOffset()) + " | " + infos.get(j).getDescription() + "\n"; return o;
}
function typeAt(anchor, text) {   // insert text right after anchor (an undoable command), caret at its end
  var e = editor(); var d = e.getDocument(); var at = d.getText().indexOf(anchor) + anchor.length;
  com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project(), new java.lang.Runnable({ run: function () { d.insertString(at, text); } }));
  com.intellij.psi.PsiDocumentManager.getInstance(project()).commitDocument(d);
  e.getCaretModel().moveToOffset(at + text.length);
}
function settingsPage(name, outName, extra) {
  edt(function () { reset(); open("site.yml"); });
  sleep(1500);
  later(function () { com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project(), name); });
  waitDialog(10000); sleep(3500);
  if (extra) extra();
  edt(function () { clean(); shot(OUT + "/" + outName + ".png"); });
  edt(closeDialogs); sleep(800); edt(closeDialogs); sleep(500);
}
function revert(rel) { var d = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vfile(rel)); com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().reloadFromDisk(d); }
