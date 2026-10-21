waitSmart(); sleep(1000);
var ops = [
  ["VaultEditIntention", "environments/production/group_vars/all/vault.yml", "vault_api_token", "$ANSIBLE_VAULT", "v08-edit-vault-value", "production"],
  ["VaultEncryptIntention", "environments/staging/group_vars/all/secrets.yml", "vault_api_token", "stg_tok", "v09-encrypt-value", "staging"],
  ["VaultRekeyIntention", "environments/production/group_vars/all/vault.yml", "vault_smtp_password", "$ANSIBLE_VAULT", "v10-rekey-value", "production"],
  ["VaultChangeIdIntention", "environments/production/group_vars/all/vault.yml", "vault_grafana_admin_password", "$ANSIBLE_VAULT", "v11-change-value-id", "production"],
  ["VaultDecryptToPlainIntention", "environments/production/group_vars/all/vault.yml", "vault_backup_passphrase", "$ANSIBLE_VAULT", "v12-decrypt-value", "production"],
];
for (var i = 0; i < ops.length; i++) (function (o) {
  edt(function () { reset(); closeDialogs(); if (o[5] == "staging") useEnv("staging"); else useHost("production", "prd-web-01.acme.internal"); open(o[1]); selectInProject(o[1]); });
  sleep(3000);
  later(function () { intentionAt(o[0], o[2], o[3]); });
  var before = java.lang.System.currentTimeMillis();
  waitDialog(5000); sleep(2000);
  edt(openCombo); sleep(800);
  edt(function () { clean(); shot(OUT + "/" + o[4] + ".png"); });
  edt(function () { var ps = find(null, function (c) { return c instanceof javax.swing.JComboBox; }); for (var k = 0; k < ps.length; k++) ps[k].setPopupVisible(false); closeDialogs(); closePopups(); });
  sleep(1000);
})(ops[i]);
