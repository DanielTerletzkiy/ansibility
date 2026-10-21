waitSmart(); sleep(1000);
edt(function () { reset(); open("ansible.cfg"); });
sleep(1500);
later(function () { com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project(), "Vault"); });
waitDialog(10000); sleep(3500);
edt(function () { clean(); shot(OUT + "/v18-vault-settings.png"); });
edt(closeDialogs);
