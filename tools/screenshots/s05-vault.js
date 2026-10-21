waitSmart(); sleep(2000);
edt(function () { reset(); useHost("production", "prd-web-01.acme.internal"); open("environments/production/group_vars/all/vault.yml"); selectInProject("environments/production/group_vars/all/vault.yml"); });
sleep(2500);
edt(function () {
  var e = editor(); var psi = com.intellij.psi.PsiDocumentManager.getInstance(project()).getPsiFile(e.getDocument());
  var t = e.getDocument().getText(); var el = psi.findElementAt(t.indexOf("$ANSIBLE_VAULT", t.indexOf("vault_db_password")));
  pcls("de.terletzkiy.ansibility.vault.actions.VaultRevealIntention").getDeclaredConstructor().newInstance().invoke(project(), e, el);
});
sleep(3000);
edt(function () {
  var bs = find(null, function (c) { return c instanceof javax.swing.AbstractButton && javax.swing.SwingUtilities.getWindowAncestor(c) !== frame() && (c.getText() == null || c.getText() == ""); });
  if (bs.length) bs[0].doClick();
});
sleep(800);
edt(function () { clean(); shot(OUT + "/05-vault.png"); });
