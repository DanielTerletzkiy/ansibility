waitSmart(); sleep(1000);
// New › Ansibility Vault File (invoked on the selected directory in the project view)
edt(function () { reset(); open("site.yml"); selectInProject("environments/staging/group_vars/all/secrets.yml"); });
sleep(2000);
later(function () { action("Ansibility.Vault.NewFile", find(toolWindow("Project").getComponent(), function (x) { return x instanceof javax.swing.JTree; })[0]); });
waitDialog(6000); sleep(1200); edt(function () { var tf = find(null, function (c) { return c instanceof javax.swing.JTextField && javax.swing.SwingUtilities.getWindowAncestor(c) instanceof java.awt.Dialog; }); if (tf.length) tf[0].setText("app_secrets.yml"); }); sleep(500); edt(openCombo); sleep(800);
edt(function () { clean(); shot(OUT + "/v13-new-vault-file.png"); var ps = find(null, function (c) { return c instanceof javax.swing.JComboBox; }); for (var k = 0; k < ps.length; k++) ps[k].setPopupVisible(false); closeDialogs(); });
sleep(1000);
