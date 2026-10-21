waitSmart(); sleep(1000);
edt(function () { reset(); hideTool("Project"); open("roles/nginx/molecule/default/converge.yml"); });
sleep(1500);
edt(function () {
  var P = "de.terletzkiy.ansibility.run.";
  var test = pcls(P + "molecule.MoleculeCommand").getField("TEST").get(null);
  var native = pcls(P + "PlaybookExecutor").getField("NATIVE").get(null);
  var ctors = pcls(P + "molecule.MoleculeSpec").getConstructors(); var ctor = null;
  for (var i = 0; i < ctors.length; i++) if (ctors[i].getParameterCount() == 7) ctor = ctors[i];
  var spec = ctor.newInstance(project().getBasePath() + "/roles/nginx", "default", test, native, "", "", "");
  kobject(P + "molecule.MoleculeLauncher").run(project(), spec, com.intellij.execution.executors.DefaultRunExecutor.getRunExecutorInstance(), false, null);
});
sleep(8000);
for (var i = 0; i < 300; i++) { var running = edt(function () { var ds = com.intellij.execution.ExecutionManager.getInstance(project()).getRunningProcesses(); for (var k = 0; k < ds.length; k++) if (!ds[k].isProcessTerminated()) return true; return false; }); if (!running) break; sleep(2000); }
sleep(2000);
edt(function () { var tw = toolWindow("Run"); com.intellij.openapi.wm.ex.ToolWindowManagerEx.getInstanceEx(project()).setMaximized(tw, true); });
sleep(1000);
edt(function () {
  var t = find(toolWindow("Run").getComponent(), function (x) { return x instanceof javax.swing.JTree; })[0];
  for (var r = 0; r < t.getRowCount(); r++) t.expandRow(r);
  for (var r = t.getRowCount() - 1; r >= 0; r--) { var n = t.getPathForRow(r).getLastPathComponent(); var u = n; try { if (n.getUserObject) u = n.getUserObject(); } catch (e) {}
    if (("" + u.getClass().getSimpleName()) == "TaskRun") t.collapseRow(r); }
  t.setSelectionRow(0); t.scrollRowToVisible(0);
  var strips = find(toolWindow("Run").getComponent(), function (x) { return x instanceof javax.swing.JScrollPane; });
  for (var s = 0; s < strips.length; s++) strips[s].getHorizontalScrollBar().setValue(0);
});
sleep(1500);
edt(function () { clean(); shot(OUT + "/08-molecule-run.png"); });
