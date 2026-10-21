waitSmart(); sleep(2000);
edt(function () { reset(); open("site.yml"); showTool("Ansibility"); });
sleep(1500);
var steps = ["Environments", "production", "Groups", "web", "prd-web-01"];
for (var i = 0; i < steps.length; i++) { (function (s) { edt(function () { try { expandLabel(treeIn("Ansibility"), s); } catch (e) {} }); })(steps[i]); sleep(1000); }
edt(function () { expandLabel(treeIn("Ansibility"), "Effective vars", true);
  var tw = toolWindow("Ansibility"); com.intellij.openapi.wm.ex.ToolWindowManagerEx.getInstanceEx(project()).setMaximized(tw, true); });
sleep(1500);
edt(function () { var sp = find(toolWindow("Ansibility").getComponent(), function (c) { return c instanceof com.intellij.ui.OnePixelSplitter; }); sp[0].setProportion(0.42); });
sleep(1000);
edt(function () { var t = treeIn("Ansibility"); t.scrollRowToVisible(t.getSelectionRows()[0]); clean(); shot(OUT + "/06-tool-window-effective-vars.png"); });
