waitSmart(); sleep(1000);
edt(function () { reset(); open("ansible.cfg"); });
sleep(1500);
later(function () { com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project(), "Vault"); });
waitDialog(10000); sleep(3500);
later(function () {
  var bs = find(null, function (c) { return ("" + c.getClass().getSimpleName()) == "ActionButton" && javax.swing.SwingUtilities.getWindowAncestor(c) instanceof java.awt.Dialog; });
  bs[0].click();
});
sleep(2500);
edt(function () { var cs = find(null, function (c) { return c instanceof javax.swing.JComboBox; }); var o = ""; for (var i = 0; i < cs.length; i++) o += cs[i].getItemCount() + " " + cs[i].getSelectedItem() + "\n"; write(OUT + "/err.txt", o);
  for (var i = 0; i < cs.length; i++) if (cs[i].getItemCount() > 4) { cs[i].setPopupVisible(true); break; } });
sleep(1200);
edt(function () { clean(); shot(OUT + "/v19-password-sources.png"); });
edt(function(){ var ps = find(null, function (c) { return c instanceof javax.swing.JComboBox; }); for (var k = 0; k < ps.length; k++) ps[k].setPopupVisible(false); closeDialogs(); }); sleep(800); edt(closeDialogs);
