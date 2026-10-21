waitSmart(); sleep(2000);
edt(function () { reset(); useEnv("staging"); open("environments/staging/group_vars/all/secrets.yml"); selectInProject("environments/staging/group_vars/all/secrets.yml"); });
sleep(6000);
later(function () { action("Ansibility.Vault.EncryptFile"); });
waitDialog(); sleep(800);
edt(openCombo); sleep(800);
edt(function () { clean(); shot(OUT + "/v01-encrypt-file.png"); closeDialogs(); });
