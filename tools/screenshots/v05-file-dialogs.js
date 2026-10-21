waitSmart(); sleep(1000);
var shots = [["Rekey", "v05-rekey-file"], ["Change id", "v06-change-file-id"], ["Decrypt file", "v07-decrypt-file"]];
for (var i = 0; i < shots.length; i++) {
  edt(function () { reset(); useHost("production", "prd-db-01.acme.internal"); open("environments/production/group_vars/db/vault.yml"); selectInProject("environments/production/group_vars/db/vault.yml"); });
  sleep(3000);
  (function (s) {
    later(function () { clickLinkLabel(s[0]); });
    if (!waitDialog(8000)) write(OUT + "/err-" + s[1] + ".txt", "no dialog");
    sleep(1000); edt(openCombo); sleep(800);
    edt(function () { clean(); shot(OUT + "/" + s[1] + ".png"); });
    edt(function () { var ps = find(null, function (c) { return c instanceof javax.swing.JComboBox; }); for (var k = 0; k < ps.length; k++) ps[k].setPopupVisible(false); closeDialogs(); });
    sleep(1000);
  })(shots[i]);
}
