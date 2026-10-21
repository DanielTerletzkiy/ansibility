waitSmart(); sleep(1000);
edt(function () { reset(); hideTool("Project"); useEnv("staging"); open("site.yml"); });
sleep(1000);
edt(function () {
  var P = "de.terletzkiy.ansibility.run.";
  var target = pcls(P + "PlaybookTarget").getDeclaredConstructor().newInstance();
  var native = pcls(P + "PlaybookExecutor").getField("NATIVE").get(null);
  var ctors = pcls(P + "PlaybookRunSpec").getConstructors(); var ctor = null;
  for (var i = 0; i < ctors.length; i++) if (ctors[i].getParameterCount() == 17) ctor = ctors[i];
  var spec = ctor.newInstance(project().getBasePath() + "/site.yml", target, "staging", "web", "", "",
    "ansible_connection=community.docker.docker ansible_python_interpreter=/usr/bin/python3",
    false, true, java.lang.Integer.valueOf(0), null, true, native, "", "", "", "");
  kobject(P + "PlaybookLauncher").run(project(), spec, true);
});
for (var i = 0; i < 60; i++) {
  var pf = edt(function () { return find(null, function (c) { return c instanceof javax.swing.JPasswordField; }); });
  if (pf.length) break; sleep(1000);
}
edt(function () { var pf = find(null, function (c) { return c instanceof javax.swing.JPasswordField; }); pf[0].setText("demo"); clickButton("Don't remember"); clickButton("OK"); });
sleep(8000);
for (var i = 0; i < 300; i++) { var running = edt(function () { var ds = com.intellij.execution.ExecutionManager.getInstance(project()).getRunningProcesses(); for (var k = 0; k < ds.length; k++) if (!ds[k].isProcessTerminated()) return true; return false; }); if (!running) break; sleep(2000); }
edt(function () { var tw = toolWindow("Run"); com.intellij.openapi.wm.ex.ToolWindowManagerEx.getInstanceEx(project()).setMaximized(tw, true); });
sleep(1000);
function runTree() { return find(toolWindow("Run").getComponent(), function (x) { return x instanceof javax.swing.JTree; })[0]; }
function nodeLabel(t, r) { var n = t.getPathForRow(r).getLastPathComponent(); var u = n; try { if (n.getUserObject) u = n.getUserObject(); } catch (e) {}
  var cn = "" + u.getClass().getSimpleName(); try { return cn.substring(0,1) + ":" + u.getName(); } catch (e) {} try { return "H:" + u.getHost(); } catch (e) {} return "" + u; }
edt(function () {
  var t = runTree();
  for (var r = 0; r < t.getRowCount(); r++) t.expandRow(r);
  for (var r = t.getRowCount() - 1; r >= 0; r--) if (nodeLabel(t, r).indexOf("T:") == 0) t.collapseRow(r);
  var sel = -1;
  for (var r = 0; r < t.getRowCount(); r++) if (nodeLabel(t, r).indexOf("Render nginx.conf") >= 0) { t.expandRow(r); sel = r + 1; break; }
  t.setSelectionRow(sel); t.scrollRowToVisible(0);
  var o = ""; for (var r = 0; r < t.getRowCount(); r++) o += r + " " + nodeLabel(t, r) + "\n"; write(OUT + "/rows.txt", o);
});
sleep(1500);
edt(function () { clean(); shot(OUT + "/07-run-plays.png"); });
