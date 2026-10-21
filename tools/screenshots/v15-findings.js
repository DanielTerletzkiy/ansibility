waitSmart(); sleep(1000);
edt(function () { reset(); open("roles/nginx/files/staging.key"); showTool("Ansibility");
  var cm = toolWindow("Ansibility").getContentManager(); var c = cm.findContent("Vault"); if (c != null) cm.setSelectedContent(c); setWidth("Ansibility", 640); });
sleep(1500);
edt(function () { try { clickLinkLabel("Ignore extension"); } catch (e) {} });
sleep(5000);
edt(function () { var t = treeIn("Ansibility"); if (t) { for (var r = 0; r < t.getRowCount(); r++) t.expandRow(r); write(OUT + "/rows.txt", treeRows(t)); } });
sleep(1500);
edt(function () { clean(); shot(OUT + "/v15-vault-findings.png"); });
