waitSmart(); sleep(1000);
edt(function () { reset(); useHost("production", "prd-db-01.acme.internal"); open("environments/production/group_vars/db/vault.yml"); selectInProject("environments/production/group_vars/db/vault.yml"); });
sleep(4000);
edt(function () { clean(); shot(OUT + "/v03-whole-file-vault-banner.png"); });
later(function () { clickLinkLabel("Open decrypted"); });
sleep(4000);
edt(function () { clean(); shot(OUT + "/v04-decrypted-tab.png"); });
