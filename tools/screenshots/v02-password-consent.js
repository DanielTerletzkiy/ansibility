waitSmart(); sleep(1000);
edt(function () { reset(); useHost("production", "prd-web-01.acme.internal"); open("environments/production/group_vars/all/vault.yml"); selectInProject("environments/production/group_vars/all/vault.yml"); });
sleep(3000);
later(function () { vaultValueAction("unlock", "vault_smtp_password"); });
waitDialog(8000); sleep(1200);
edt(function () { clean(); shot(OUT + "/v02-password-consent.png"); });
