waitSmart(); sleep(2000);
edt(function () { reset(); hideTool("Project"); useHost("production", "prd-db-01.acme.internal"); open("environments/production/group_vars/db/vars.yml"); });
sleep(4000);
edt(function () { showTool("Problems View"); });
sleep(2500);
edt(function () { write(OUT + "/problems.txt", ""); var infos = com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl.getHighlights(editor().getDocument(), com.intellij.lang.annotation.HighlightSeverity.WEAK_WARNING, project()); var o=""; for (var j=0;j<infos.size();j++) o += infos.get(j).getSeverity()+" "+infos.get(j).getDescription()+"\n"; write(OUT + "/problems.txt", o);
  caretAt("18"); editor().getContentComponent().requestFocus(); action("ShowErrorDescription"); });
sleep(2500);
edt(function () { clean(); shot(OUT + "/04-type-checks.png"); });
